package org.smartgurukul.chhaon.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartgurukul.chhaon.recipient.Pregnancy;
import org.smartgurukul.chhaon.whatsapp.AlertDelivery;
import org.smartgurukul.chhaon.whatsapp.MedicinePhotoStore;
import org.smartgurukul.chhaon.whatsapp.MessageTexts;
import org.smartgurukul.chhaon.whatsapp.ReceiverTexts;
import org.smartgurukul.chhaon.whatsapp.WhatsAppSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The reminder tick, ported from the Python scheduler. Runs the dose-event + check-in lifecycle:
 * generate -> send due -> expire unsent -> mark missed, plus daily check-ins. Every state change is
 * a guarded conditional UPDATE (claim), so overlapping ticks never double-act. Uses the privileged
 * app DB connection (RLS-bypassing), matching the original service_role scheduler.
 *
 * WhatsApp sends go through {@link WhatsAppSender} (dry-run while no gateway URL is set). The last step drains the
 * caregiver alert outbox ({@link AlertDelivery}). Not yet ported: escalation ladder, appointment reminders, love loop,
 * slot grouping of the reminder itself.
 */
@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final JdbcTemplate jdbc;
    private final WhatsAppSender wa;
    /** null = no WhatsApp alerts to caregivers (tests that build the service by hand). */
    private final AlertDelivery alerts;
    /** Medicine photos for the reminder; null = text only (tests that build the service by hand). */
    private MedicinePhotoStore photos;

    /** The medicine photo in the reminder (feature 5). Optional: without it every reminder is plain text. */
    @Autowired(required = false)
    public void setMedicinePhotoStore(MedicinePhotoStore photos) {
        this.photos = photos;
    }

    private final boolean enabled;
    private final int doseMissedMinutes;
    private final int checkinMissedMinutes;
    private final int checkinStartWindowMinutes;
    private final int horizonHours;
    /** null = every family. Set to one recipient id to run the scheduler for that parent only (first switch-on). */
    private final UUID onlyRecipient;
    /** true when chhaon.scheduler.only-recipient holds something that is neither empty, "all" nor a recipient id: then NOTHING runs. */
    private final boolean onlyRecipientInvalid;

    /** Without caregiver alerts (tests). */
    public SchedulerService(JdbcTemplate jdbc, WhatsAppSender wa, boolean enabled, int doseMissedMinutes, int checkinMissedMinutes,
                            int checkinStartWindowMinutes, int horizonHours, String onlyRecipient) {
        this(jdbc, wa, null, enabled, doseMissedMinutes, checkinMissedMinutes, checkinStartWindowMinutes, horizonHours, onlyRecipient);
    }

    @Autowired
    public SchedulerService(JdbcTemplate jdbc,
                            WhatsAppSender wa,
                            AlertDelivery alerts,
                            @Value("${chhaon.scheduler.enabled:false}") boolean enabled,
                            @Value("${chhaon.scheduler.dose-missed-minutes:60}") int doseMissedMinutes,
                            @Value("${chhaon.scheduler.checkin-missed-minutes:180}") int checkinMissedMinutes,
                            @Value("${chhaon.scheduler.checkin-start-window-minutes:120}") int checkinStartWindowMinutes,
                            @Value("${chhaon.scheduler.horizon-hours:24}") int horizonHours,
                            @Value("${chhaon.scheduler.only-recipient:}") String onlyRecipient) {
        this.jdbc = jdbc;
        this.wa = wa;
        this.alerts = alerts;
        this.enabled = enabled;
        this.doseMissedMinutes = doseMissedMinutes;
        this.checkinMissedMinutes = checkinMissedMinutes;
        this.checkinStartWindowMinutes = checkinStartWindowMinutes;
        this.horizonHours = horizonHours;
        String o = onlyRecipient == null ? "" : onlyRecipient.trim();
        UUID parsed = null;
        boolean bad = false;
        if (!o.isEmpty() && !"all".equalsIgnoreCase(o)) {
            try {
                parsed = UUID.fromString(o);
            } catch (IllegalArgumentException e) {
                bad = true;
            }
        }
        this.onlyRecipient = parsed;
        this.onlyRecipientInvalid = bad;
        if (enabled && bad) {
            log.error("scheduler is ENABLED but chhaon.scheduler.only-recipient is neither empty, 'all' nor a recipient id: it will NOT run");
        } else if (enabled && parsed != null) {
            log.warn("scheduler is enabled for ONE recipient only ({}); set only-recipient to 'all' to run for every family", parsed);
        }
    }

    @Scheduled(fixedDelayString = "${chhaon.scheduler.tick-interval-ms:300000}",
               initialDelayString = "${chhaon.scheduler.initial-delay-ms:60000}")
    public void scheduledTick() {
        if (!enabled || onlyRecipientInvalid) return;   // an unreadable scope never means "everyone"
        tick(onlyRecipient);
    }

    /**
     * Make one parent's upcoming doses now, instead of at the next tick (up to 5 minutes later), so a medicine that was just
     * added or changed shows on the Today screen at once. Only the dose plan is made: nothing is sent here, and a time that has
     * already passed today still gets no dose (generate_dose_events skips times before the schedule was created).
     * Does nothing when the scheduler would not handle this parent (switched off, an unreadable scope, or scoped to someone
     * else), so a dose never sits on Today that no tick will ever send or close. Never throws: saving must not fail because of it.
     */
    public int generateNow(UUID recipientId) {
        if (!enabled || onlyRecipientInvalid || recipientId == null) return 0;
        if (onlyRecipient != null && !onlyRecipient.equals(recipientId)) return 0;
        try {
            return generate(recipientId);
        } catch (Exception e) {
            log.warn("generating doses right after a medicine change failed: {}", e.toString());
            return 0;
        }
    }

    /** Run one tick. scope = a single recipient id to limit to (for tests), or null for all. */
    public Map<String, Integer> tick(UUID scope) {
        Map<String, Integer> counters = new LinkedHashMap<>();
        step(counters, "generated", () -> generate(scope));
        step(counters, "doses_sent", () -> sendDueDoses(scope, counters));
        step(counters, "reminders_not_sent", () -> expireUnsentDoses(scope));
        step(counters, "doses_missed", () -> markMissedDoses(scope));
        step(counters, "checkins_sent", () -> sendCheckins(scope));
        step(counters, "checkins_missed", () -> markMissedCheckins(scope));
        // LAST, so the alerts created above go out in this same tick. Its failures never touch the steps above.
        if (alerts != null) step(counters, "alerts_sent", () -> deliverAlerts(scope, counters));
        recordHeartbeat();
        log.info("tick(scope={}) {}", scope, counters);
        return counters;
    }

    private int deliverAlerts(UUID scope, Map<String, Integer> counters) {
        Map<String, Integer> r = alerts.deliver(scope);
        r.forEach((k, v) -> {
            if (!"alerts_sent".equals(k)) counters.merge(k, v, Integer::sum);
        });
        return r.getOrDefault("alerts_sent", 0);
    }

    /**
     * Sends waiting caregiver alerts now instead of at the next tick (called right after a parent's reply created one, such
     * as "call me"). Same switch and scope as the tick: nothing at all while the scheduler is off. Never throws.
     */
    public void deliverAlertsNow() {
        if (alerts == null || !enabled || onlyRecipientInvalid) return;
        try {
            alerts.deliver(onlyRecipient);
        } catch (Exception e) {
            log.warn("delivering alerts right after a reply failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Tells the database "a tick just ran" so the app can warn the family when reminders stop.
     * Best effort: a failure here is logged and ignored, and never touches the tick's counters.
     */
    private void recordHeartbeat() {
        try {
            jdbc.execute("select public.touch_scheduler_heartbeat()");
        } catch (Exception e) {
            log.warn("scheduler heartbeat could not be recorded: {}", e.getClass().getSimpleName());
        }
    }

    private void step(Map<String, Integer> counters, String key, StepRunnable r) {
        try {
            counters.merge(key, r.run(), Integer::sum);
        } catch (Exception e) {
            counters.merge("step_errors", 1, Integer::sum);
            log.warn("scheduler step {} failed: {}", key, e.toString());
        }
    }

    @FunctionalInterface
    private interface StepRunnable { int run(); }

    // --- steps ---

    private int generate(UUID scope) {
        Integer n;
        if (scope == null) {
            // p_recipients defaults to NULL (everyone).
            n = jdbc.queryForObject(
                    "select public.generate_dose_events(now(), make_interval(hours => ?), make_interval(mins => ?))",
                    Integer.class, horizonHours, doseMissedMinutes);
        } else {
            // Pass a uuid[] as a Postgres array literal, cast explicitly.
            n = jdbc.queryForObject(
                    "select public.generate_dose_events(now(), make_interval(hours => ?), " +
                    "make_interval(mins => ?), cast(? as uuid[]))",
                    Integer.class, horizonHours, doseMissedMinutes, "{" + scope + "}");
        }
        return n == null ? 0 : n;
    }

    private int sendDueDoses(UUID scope, Map<String, Integer> counters) {
        List<Object> p = new ArrayList<>();
        p.add(doseMissedMinutes);
        String sql = "select de.id dose_id, r.id recipient_id, r.circle_id, r.is_self, r.whatsapp_phone, " +
                ReceiverTexts.languageSql("r") + " language, " +   // a helper may read another language (R1's reminder_language)
                "r.call_name, r.name recipient_name, r.timezone, r.care_type, r.due_date, " +
                "r.name person_name, r.reminder_to, r.reminder_name, " +   // who gets the reminder (migration 0044)
                "(r.consent_at is not null and r.opted_out_at is null and r.whatsapp_phone is not null " +
                " and not exists(select 1 from phone_opt_outs o where o.phone = r.whatsapp_phone)) allowed, " +
                "m.id medicine_id, m.name medicine_name, m.dosage, m.photo_url, " +
                // read through to_jsonb so a database without the colour columns (migration 0042 not applied yet)
                // still sends reminders, just without the look line
                "to_jsonb(m) ->> 'pill_colour' pill_colour, to_jsonb(m) ->> 'pill_shape' pill_shape " +
                "from dose_events de join medicine_schedules s on s.id = de.schedule_id " +
                "join medicines m on m.id = s.medicine_id join care_recipients r on r.id = m.recipient_id " +
                "where de.status = 'pending' and de.scheduled_at <= now() " +
                "and de.scheduled_at > now() - make_interval(mins => ?)" + scopeClause(scope, p, "r");
        int sent = 0;
        for (Map<String, Object> row : jdbc.queryForList(sql, p.toArray())) {
            UUID doseId = (UUID) row.get("dose_id");
            boolean allowed = Boolean.TRUE.equals(row.get("allowed"));
            if (!allowed) {
                jdbc.update("update dose_events set status='skipped' where id=? and status='pending'", doseId);
                counters.merge("doses_skipped", 1, Integer::sum);
                continue;
            }
            int claimed = jdbc.update(
                    "update dose_events set status='sent', sent_at=now() where id=? and status='pending'", doseId);
            if (claimed != 1) continue; // another tick took it
            try {
                String text = ReceiverTexts.notThePerson((String) row.get("reminder_to")) ? receiverDoseText(row)
                        : doseText(row, pregnant(row) ? weekToSay(row, doseId) : null, Instant.now());
                // With the medicine photo when there is one; any photo problem sends this same text instead (never throws here).
                byte[] photo = photos == null ? null
                        : photos.reminderJpeg((UUID) row.get("recipient_id"), (String) row.get("photo_url")).orElse(null);
                String msgId = wa.sendReminder((String) row.get("whatsapp_phone"), text, photo);
                jdbc.update("update dose_events set whatsapp_message_id=? where id=?", msgId, doseId);
                sent++;
            } catch (Exception sendEx) {
                jdbc.update("update dose_events set status='pending', sent_at=null where id=? and status='sent'", doseId);
                counters.merge("send_errors", 1, Integer::sum);
                log.warn("dose reminder send failed for {}: {}", doseId, sendEx.toString());
            }
        }
        return sent;
    }

    private int expireUnsentDoses(UUID scope) {
        List<Object> p = new ArrayList<>();
        p.add(doseMissedMinutes);
        String sql = "select de.id dose_id, r.circle_id, r.is_self, m.recipient_id, m.id medicine_id " +
                "from dose_events de join medicine_schedules s on s.id=de.schedule_id " +
                "join medicines m on m.id=s.medicine_id join care_recipients r on r.id=m.recipient_id " +
                "where de.status='pending' and de.scheduled_at <= now() - make_interval(mins => ?)" +
                scopeClause(scope, p, "r");
        int expired = 0;
        for (Map<String, Object> row : jdbc.queryForList(sql, p.toArray())) {
            UUID doseId = (UUID) row.get("dose_id");
            if (jdbc.update("update dose_events set status='skipped' where id=? and status='pending'", doseId) == 1) {
                expired++;
                if (!Boolean.TRUE.equals(row.get("is_self"))) {
                    notifyCircle((UUID) row.get("circle_id"), "reminder_not_sent",
                            json("recipient_id", row.get("recipient_id"), "medicine_id", row.get("medicine_id")));
                }
            }
        }
        return expired;
    }

    private int markMissedDoses(UUID scope) {
        List<Object> p = new ArrayList<>();
        p.add(doseMissedMinutes);
        // NOTE: escalation ladder not ported yet; escalating parents are not excluded here.
        String sql = "select de.id dose_id, de.scheduled_at, r.circle_id, r.is_self, r.opted_out_at, m.recipient_id, m.id medicine_id " +
                "from dose_events de join medicine_schedules s on s.id=de.schedule_id " +
                "join medicines m on m.id=s.medicine_id join care_recipients r on r.id=m.recipient_id " +
                "where de.status='sent' and de.sent_at <= now() - make_interval(mins => ?)" +
                scopeClause(scope, p, "r") + " order by de.scheduled_at, m.name";
        int missed = 0;
        // Doses of one parent due at the same moment that go missed together are ONE alert ("3 medicines"), like Python:
        // three tablets at 8:00 must not become three WhatsApp messages to every caregiver.
        Map<String, List<Map<String, Object>>> slots = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, p.toArray())) {
            UUID doseId = (UUID) row.get("dose_id");
            if (jdbc.update("update dose_events set status='missed' where id=? and status='sent'", doseId) == 1) {
                missed++;
                slots.computeIfAbsent(row.get("recipient_id") + "|" + row.get("scheduled_at"), k -> new ArrayList<>()).add(row);
            }
        }
        for (List<Map<String, Object>> rows : slots.values()) {
            Map<String, Object> first = rows.get(0);
            if (first.get("opted_out_at") != null || Boolean.TRUE.equals(first.get("is_self"))) continue;
            String payload = rows.size() > 1
                    ? json("recipient_id", first.get("recipient_id"), "medicine_id", first.get("medicine_id"),
                            "dose_event_id", first.get("dose_id"), "count", rows.size())
                    : json("recipient_id", first.get("recipient_id"), "medicine_id", first.get("medicine_id"),
                            "dose_event_id", first.get("dose_id"));
            notifyCircle((UUID) first.get("circle_id"), "dose_missed", payload);
        }
        return missed;
    }

    private int sendCheckins(UUID scope) {
        List<Object> p = new ArrayList<>();
        String sql = "select r.id recipient_id, r.timezone, r.checkin_time, r.circle_id, r.whatsapp_phone, r.language, r.call_name, " +
                "r.name recipient_name, r.care_type, r.due_date " +
                "from care_recipients r " +
                "where r.consent_at is not null and r.opted_out_at is null and r.whatsapp_phone is not null " +
                "and r.is_self = false " +
                // "Are you okay?" is asked of the person only: no check-in when a helper or a family member gets the reminders
                "and r.reminder_to = 'person' " +
                // STOP belongs to the NUMBER: never a check-in to a number that opted out, even when another parent record carries it.
                "and not exists(select 1 from phone_opt_outs o where o.phone = r.whatsapp_phone)" + scopeClause(scope, p, "r");
        int sent = 0;
        for (Map<String, Object> row : jdbc.queryForList(sql, p.toArray())) {
            String tz = (String) row.get("timezone");
            ZoneId zone;
            try { zone = ZoneId.of(tz); } catch (Exception e) { zone = ZoneId.of("Asia/Kolkata"); }
            ZonedDateTime localNow = Instant.now().atZone(zone);
            LocalDate today = localNow.toLocalDate();
            LocalTime ct = ((java.sql.Time) row.get("checkin_time")).toLocalTime();
            ZonedDateTime start = ZonedDateTime.of(today, ct, zone);
            if (localNow.isBefore(start) || !localNow.isBefore(start.plusMinutes(checkinStartWindowMinutes))) {
                continue; // outside the bounded start window
            }
            UUID recipientId = (UUID) row.get("recipient_id");
            jdbc.update("insert into checkins (id, recipient_id, date, status) " +
                    "values (gen_random_uuid(), ?, ?, 'pending') on conflict (recipient_id, date) do nothing",
                    recipientId, today);
            int claimed = jdbc.update(
                    "update checkins set status='sent', sent_at=now() where recipient_id=? and date=? and status='pending'",
                    recipientId, today);
            if (claimed != 1) continue;
            try {
                String msgId = wa.send((String) row.get("whatsapp_phone"),
                        checkinText(row, today));
                rememberCheckinMessageId(recipientId, today, msgId);
                sent++;
            } catch (Exception sendEx) {
                jdbc.update("update checkins set status='pending', sent_at=null where recipient_id=? and date=? and status='sent'",
                        recipientId, today);
                log.warn("checkin send failed for {}: {}", recipientId, sendEx.toString());
            }
        }
        return sent;
    }

    /**
     * Remembers which WhatsApp message carried today's check-in, so a swipe-reply to it can be matched
     * (checkins.whatsapp_message_id, migration 20261104000041). Best effort: if the column does not exist yet or the
     * update fails, the check-in itself is unaffected.
     */
    private void rememberCheckinMessageId(UUID recipientId, LocalDate date, String messageId) {
        try {
            jdbc.update("update checkins set whatsapp_message_id = ? where recipient_id = ? and date = ?",
                    messageId, recipientId, date);
        } catch (Exception e) {
            log.warn("check-in message id not stored: {}", e.getClass().getSimpleName());
        }
    }

    private int markMissedCheckins(UUID scope) {
        List<Object> p = new ArrayList<>();
        p.add(checkinMissedMinutes);
        String sql = "select c.id checkin_id, r.circle_id, r.is_self, r.opted_out_at, c.recipient_id " +
                "from checkins c join care_recipients r on r.id = c.recipient_id " +
                "where c.status='sent' and c.sent_at <= now() - make_interval(mins => ?)" +
                scopeClause(scope, p, "r");
        int missed = 0;
        for (Map<String, Object> row : jdbc.queryForList(sql, p.toArray())) {
            UUID checkinId = (UUID) row.get("checkin_id");
            if (jdbc.update("update checkins set status='missed' where id=? and status='sent'", checkinId) == 1) {
                missed++;
                if (row.get("opted_out_at") == null && !Boolean.TRUE.equals(row.get("is_self"))) {
                    notifyCircle((UUID) row.get("circle_id"), "checkin_missed",
                            json("recipient_id", row.get("recipient_id"), "checkin_id", checkinId));
                }
            }
        }
        return missed;
    }

    // --- helpers ---

    private static boolean pregnant(Map<String, Object> row) {
        return "pregnant".equals(row.get("care_type"));
    }

    /** For a pregnancy the message greets her by what the family calls her, else her first name (never "Papa"). */
    private static String pregnancyName(Map<String, Object> row) {
        String call = (String) row.get("call_name");
        if (call != null && !call.isBlank()) return call;
        String name = (String) row.get("recipient_name");
        return name == null || name.isBlank() ? "" : name.trim().split("\\s+")[0];
    }

    private static ZoneId zoneOf(Object tz) {
        try { return ZoneId.of((String) tz); } catch (Exception e) { return ZoneId.of("Asia/Kolkata"); }
    }

    private static LocalDate dueDate(Map<String, Object> row) {
        Object d = row.get("due_date");
        return d instanceof java.sql.Date sql ? sql.toLocalDate() : d instanceof LocalDate ld ? ld : null;
    }

    /**
     * The new pregnancy week to say in this reminder, or null (R2: the week is said once a week, in the FIRST reminder sent on the
     * day a new week starts; MessageTexts#pregnancyDoseReminder). Best effort: any problem leaves the week out, never the reminder.
     */
    private Integer weekToSay(Map<String, Object> row, UUID doseId) {
        try {
            ZoneId zone = zoneOf(row.get("timezone"));
            LocalDate today = Instant.now().atZone(zone).toLocalDate();
            Integer week = Pregnancy.week(dueDate(row), today);
            if (week == null || week.equals(Pregnancy.week(dueDate(row), today.minusDays(1)))) return null;
            // Another reminder already went out today (or in this same tick, claimed just before): it carried the week.
            Integer earlier = jdbc.queryForObject("select count(*) from dose_events d join medicine_schedules s on s.id = d.schedule_id " +
                            "join medicines m on m.id = s.medicine_id where m.recipient_id = ? and d.id <> ? and d.sent_at >= ?",
                    Integer.class, row.get("recipient_id"), doseId, java.sql.Timestamp.from(today.atStartOfDay(zone).toInstant()));
            return earlier != null && earlier > 0 ? null : week;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The dose reminder to the person: older parents get "Namaste Papa", a pregnancy a greeting for the time of day on her clock
     * (and {@code newWeek} when a new week starts today). Public so the in-app WhatsApp preview shows exactly this text.
     *
     * @param at when the reminder goes out (now, or the next dose time in the preview)
     */
    public static String doseText(Map<String, Object> row, Integer newWeek, Instant at) {
        String language = (String) row.get("language");
        if (!pregnant(row)) {
            return MessageTexts.doseReminder(language, (String) row.get("call_name"), (String) row.get("medicine_name"),
                    (String) row.get("pill_colour"), (String) row.get("pill_shape"), (String) row.get("dosage"));
        }
        ZonedDateTime local = at.atZone(zoneOf(row.get("timezone")));
        return MessageTexts.pregnancyDoseReminder(language, pregnancyName(row), (String) row.get("medicine_name"),
                (String) row.get("pill_colour"), (String) row.get("pill_shape"), (String) row.get("dosage"), newWeek, local.getHour());
    }

    /** The daily check-in; for a pregnancy the gentle "How are you feeling today?" (no week: R2). */
    public static String checkinText(Map<String, Object> row, LocalDate localToday) {
        String language = (String) row.get("language");
        if (!pregnant(row)) return MessageTexts.checkin(language, (String) row.get("call_name"));
        return MessageTexts.pregnancyCheckin(language, pregnancyName(row));
    }

    /** The third-person reminder for a helper / the caregiver ("Time for Papa's medicine"), see docs/reminder-receiver.md. */
    public static String receiverDoseText(Map<String, Object> row) {
        return ReceiverTexts.doseReminder((String) row.get("language"), (String) row.get("reminder_to"),
                (String) row.get("reminder_name"),
                ReceiverTexts.personName((String) row.get("call_name"), (String) row.get("person_name")),
                (String) row.get("medicine_name"), (String) row.get("pill_colour"), (String) row.get("pill_shape"),
                (String) row.get("dosage"));
    }

    /** One notification row per circle member (payload carries ids only, never health data/phones). */
    private void notifyCircle(UUID circleId, String type, String payloadJson) {
        jdbc.update("insert into notifications (id, user_id, type, payload, created_at) " +
                "select gen_random_uuid(), m.user_id, ?, cast(? as jsonb), now() " +
                "from care_circle_members m where m.circle_id = ?",
                type, payloadJson, circleId);
    }

    private static String scopeClause(UUID scope, List<Object> params, String alias) {
        if (scope == null) return "";
        params.add(scope);
        return " and " + alias + ".id = ?";
    }

    /** Minimal JSON object builder for id-only payloads (values are UUIDs/strings). */
    private static String json(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(kv[i]).append("\":");
            Object v = kv[i + 1];
            if (v == null) sb.append("null");
            else if (v instanceof Integer) sb.append(v); // a JSON number (the web reads payload.count as a number)
            else sb.append('"').append(v).append('"');
        }
        return sb.append('}').toString();
    }
}
