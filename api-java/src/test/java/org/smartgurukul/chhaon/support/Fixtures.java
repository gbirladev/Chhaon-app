package org.smartgurukul.chhaon.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Test data helper. Every row is created with fresh random ids/phone numbers and removed again by
 * {@link #cleanup()} (cascading deletes from the circle), so tests are isolated from each other.
 * Refuses to work unless the connected database name contains "test": it can never wipe the shared
 * development database by accident.
 */
public class Fixtures {

    public record Family(UUID userId, UUID circleId, UUID recipientId, String phone, ZoneId zone) {}

    public record Med(UUID medicineId, UUID scheduleId) {}

    private final JdbcTemplate jdbc;
    private final List<UUID> circles = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();
    private final List<String> phones = new ArrayList<>();

    public Fixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        String db = jdbc.queryForObject("select current_database()", String.class);
        if (db == null || !db.contains("test")) {
            throw new IllegalStateException("Fixtures refuse to run against database '" + db + "'");
        }
    }

    // ---------------------------------------------------------------- creation

    public static String randomPhone() {
        return "+919999" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));   // fake numbers only (+91 9999x)
    }

    public static String messageId() {
        return "3EB0" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }

    /** A timezone in which "now" is comfortably inside a day (not within ~1.5 h of local midnight). */
    public static ZoneId safeZone() {
        for (String id : List.of("Asia/Kolkata", "UTC", "America/New_York", "Australia/Sydney", "Pacific/Auckland")) {
            LocalTime t = Instant.now().atZone(ZoneId.of(id)).toLocalTime();
            if (t.isAfter(LocalTime.of(2, 0)) && t.isBefore(LocalTime.of(22, 0))) return ZoneId.of(id);
        }
        throw new IllegalStateException("no safe zone (impossible)");
    }

    public UUID user(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into profiles (id, name, email, plan) values (?, ?, ?, 'family')",
                id, name, "t-" + id + "@test.invalid");
        users.add(id);
        return id;
    }

    public Family family(String language, boolean consented) {
        return family(randomPhone(), language, consented, ZoneId.of("Asia/Kolkata"));
    }

    public Family family(String phone, String language, boolean consented, ZoneId zone) {
        UUID userId = user("Ananya");
        return recipientFor(userId, phone, language, consented, zone, "Rajesh");
    }

    /** A second parent (own circle, same caregiver) - used for numbers shared by two parents. */
    public Family addRecipient(Family base, String name, String phone, String language, boolean consented) {
        return recipientFor(base.userId(), phone, language, consented, base.zone(), name);
    }

    private Family recipientFor(UUID userId, String phone, String language, boolean consented, ZoneId zone, String name) {
        UUID circleId = UUID.randomUUID();
        jdbc.update("insert into care_circles (id, created_by) values (?, ?)", circleId, userId); // trigger adds owner
        circles.add(circleId);
        UUID recipientId = UUID.randomUUID();
        jdbc.update("insert into care_recipients (id, circle_id, name, call_name, relation, language, whatsapp_phone, timezone) " +
                        "values (?, ?, ?, ?, 'father', ?, ?, ?)",
                recipientId, circleId, name, "hi".equals(language) ? "पापा" : "Papa", language, phone, zone.getId());
        // The default check-in time is 08:00: whenever the suite happens to run inside the morning window the tick would send an
        // unexpected check-in. Park it twelve hours away from now; tests that want a check-in set it themselves.
        jdbc.update("update care_recipients set checkin_time = ? where id = ?",
                ZonedDateTime.now(zone).plusHours(12).toLocalTime().withSecond(0).withNano(0), recipientId);
        if (consented) consent(recipientId);
        phones.add(phone);
        return new Family(userId, circleId, recipientId, phone, zone);
    }

    /** The caregiver's own profile ("Me", is_self) in its own private circle, with its own number. */
    public Family selfFamily(String phone, String language, boolean consented) {
        UUID userId = user("Ananya");
        UUID circleId = UUID.randomUUID();
        jdbc.update("insert into care_circles (id, created_by) values (?, ?)", circleId, userId);
        circles.add(circleId);
        UUID recipientId = UUID.randomUUID();
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        jdbc.update("insert into care_recipients (id, circle_id, name, relation, language, whatsapp_phone, timezone, is_self) " +
                "values (?, ?, 'Ananya', 'self', ?, ?, ?, true)", recipientId, circleId, language, phone, zone.getId());
        jdbc.update("update care_recipients set checkin_time = ? where id = ?",
                ZonedDateTime.now(zone).plusHours(12).toLocalTime().withSecond(0).withNano(0), recipientId);
        if (consented) consent(recipientId);
        phones.add(phone);
        return new Family(userId, circleId, recipientId, phone, zone);
    }

    /** Another family member in the parent's circle (role member). */
    public UUID member(Family f, String name) {
        UUID id = user(name);
        jdbc.update("insert into care_circle_members (circle_id, user_id, role) values (?, ?, 'member')", f.circleId(), id);
        return id;
    }

    /** WhatsApp alerts confirmed on this caregiver's number (as if they had typed 1 to our question a day ago). */
    public void alertsOn(UUID userId, String phone) {
        jdbc.update("update profiles set alert_phone = ?, alert_requested_at = now() - interval '1 day', " +
                "alert_confirmed_at = now() - interval '1 day', alert_consent_at = now() - interval '1 day' where id = ?", phone, userId);
        phones.add(phone);
    }

    /** A number used only by a test (so cleanup removes its opt-out / consent request rows). */
    public String track(String phone) {
        phones.add(phone);
        return phone;
    }

    public Map<String, Object> alerts(UUID userId) {
        return jdbc.queryForMap("select alert_phone, alert_requested_at, alert_confirmed_at, alert_consent_at from profiles where id = ?",
                userId);
    }

    /** The WhatsApp bookkeeping of one user's notifications of one type. */
    public List<Map<String, Object>> outbox(UUID userId, String type) {
        return jdbc.queryForList("select id, payload::text payload, whatsapp_sent_at, whatsapp_skipped_at, whatsapp_attempts " +
                "from notifications where user_id = ? and type = ? order by created_at", userId, type);
    }

    public void consent(UUID recipientId) {
        jdbc.update("update care_recipients set consent_at = now() - interval '1 day', opted_out_at = null where id = ?",
                recipientId);
    }

    /** A medicine with one daily schedule at the given LOCAL time (parent's zone). */
    public Med medicine(Family f, String name, String dosage, Integer stock, LocalTime localTime) {
        UUID medicineId = UUID.randomUUID();
        jdbc.update("insert into medicines (id, recipient_id, name, dosage, instructions, stock_count) " +
                "values (?, ?, ?, ?, 'after_food', ?)", medicineId, f.recipientId(), name, dosage, stock);
        UUID scheduleId = UUID.randomUUID();
        jdbc.update("insert into medicine_schedules (id, medicine_id, time_of_day, start_date) values (?, ?, ?, ?)",
                scheduleId, medicineId, localTime, LocalDate.now().minusDays(3));
        // generate_dose_events ignores times earlier than the schedule's creation: backdate it.
        jdbc.update("update medicine_schedules set created_at = now() - interval '3 days' where id = ?", scheduleId);
        return new Med(medicineId, scheduleId);
    }

    /** Local wall-clock time (in the family's zone) of an instant. */
    public static LocalTime localTime(Family f, Instant at) {
        return ZonedDateTime.ofInstant(at, f.zone()).toLocalTime().withSecond(0).withNano(0);
    }

    public UUID dose(UUID scheduleId, Instant scheduledAt, String status, Instant sentAt, String messageId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into dose_events (id, schedule_id, scheduled_at, status, sent_at, whatsapp_message_id) " +
                        "values (?, ?, ?, cast(? as dose_status), ?, ?)",
                id, scheduleId, Timestamp.from(scheduledAt), status,
                sentAt == null ? null : Timestamp.from(sentAt), messageId);
        return id;
    }

    public UUID checkin(UUID recipientId, LocalDate date, String status, Instant sentAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into checkins (id, recipient_id, date, status, sent_at) values (?, ?, ?, cast(? as checkin_status), ?)",
                id, recipientId, date, status, sentAt == null ? null : Timestamp.from(sentAt));
        return id;
    }

    public void optOutPhone(String phone) {
        jdbc.update("insert into phone_opt_outs (phone) values (?) on conflict do nothing", phone);
        phones.add(phone);
    }

    // ---------------------------------------------------------------- reading

    public String doseStatus(UUID doseId) {
        return jdbc.queryForObject("select status::text from dose_events where id = ?", String.class, doseId);
    }

    public Map<String, Object> dose(UUID doseId) {
        return jdbc.queryForMap("select status::text status, sent_at, responded_at, whatsapp_message_id from dose_events where id = ?", doseId);
    }

    public String checkinStatus(UUID checkinId) {
        return jdbc.queryForObject("select status::text from checkins where id = ?", String.class, checkinId);
    }

    public Map<String, Object> recipient(UUID recipientId) {
        return jdbc.queryForMap("select consent_at, opted_out_at, consent_requested_at from care_recipients where id = ?", recipientId);
    }

    public Integer stock(UUID medicineId) {
        return jdbc.queryForObject("select stock_count from medicines where id = ?", Integer.class, medicineId);
    }

    public int notifications(UUID userId, String type) {
        Integer n = jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = ?",
                Integer.class, userId, type);
        return n == null ? 0 : n;
    }

    public boolean phoneOptedOut(String phone) {
        Integer n = jdbc.queryForObject("select count(*) from phone_opt_outs where phone = ?", Integer.class, phone);
        return n != null && n > 0;
    }

    public int eventCount(String id) {
        Integer n = jdbc.queryForObject("select count(*) from whatsapp_events where id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    // ---------------------------------------------------------------- cleanup

    public void cleanup() {
        for (UUID c : circles) jdbc.update("delete from care_circles where id = ?", c); // cascades downwards
        for (UUID u : users) jdbc.update("delete from profiles where id = ?", u);
        for (String p : phones) {
            jdbc.update("delete from phone_opt_outs where phone = ?", p);
            jdbc.update("delete from consent_requests where phone = ?", p);
        }
        jdbc.update("delete from whatsapp_events"); // throwaway database only (checked in the constructor)
        circles.clear();
        users.clear();
        phones.clear();
    }
}
