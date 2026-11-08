package org.smartgurukul.chhaon.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartgurukul.chhaon.scheduler.SchedulerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Send a sample reminder now" (docs/sample-reminder.md): right after sign-up the family can send ONE reminder for the person's next
 * time slot, built by the same code as the real one ({@link SchedulerService#slotText} / {@link SchedulerService#slotPoll}), with
 * {@link ReminderTexts#sampleLabel} as its first line. No dose_events row is created or changed, so nothing counts in Today, stats
 * or adherence. Remembered only in {@code whatsapp_events} (no migration):
 * <ul>
 *   <li>{@code gw:sample:<recipient>:<person's local date>} = the once-a-day limit; its received_at = when the sample went out;</li>
 *   <li>{@code gw:out:sample:<recipient>:<message id>} = which WhatsApp message was the sample (a tap / reaction on it is answered
 *       by {@link org.smartgurukul.chhaon.whatsapp.reply.ReplyRouter} with "that was only a sample", never as a dose).</li>
 * </ul>
 */
@Service
public class SampleReminderService {

    private static final Logger log = LoggerFactory.getLogger(SampleReminderService.class);

    /**
     * status: sent | not_found | no_number | not_consented | opted_out | no_medicines | limited | busy | send_failed.
     * slotTime / at: the slot the sample shows (sent only).
     */
    public record Result(String status, String slotTime, Instant at) {
        static Result of(String status) {
            return new Result(status, null, null);
        }
    }

    /** reason: ok or one of the refusals above (never send_failed); realReminderSent: a real dose reminder has already gone out. */
    public record Status(String reason, boolean sentToday, boolean realReminderSent) {}

    private final JdbcTemplate jdbc;
    private final WhatsAppSender wa;
    private final int globalPerHour;

    public SampleReminderService(JdbcTemplate jdbc, WhatsAppSender wa,
                                 @Value("${chhaon.sample-reminder.global-per-hour:60}") int globalPerHour) {
        this.jdbc = jdbc;
        this.wa = wa;
        this.globalPerHour = globalPerHour;
    }

    public static String dayKey(UUID recipientId, LocalDate day) {
        return "gw:sample:" + recipientId + ":" + day;
    }

    public static String messageKey(UUID recipientId, String messageId) {
        return "gw:out:sample:" + recipientId + ":" + messageId;
    }

    /** What the Today card needs; null when the caller is not in the person's circle. */
    public Status status(UUID userId, UUID recipientId) {
        Map<String, Object> person = person(userId, recipientId);
        if (person == null) return null;
        String refusal = refusal(person);
        boolean sentToday = count(dayKey(recipientId, today(person))) > 0;
        if (refusal == null && slot(person).isEmpty()) refusal = "no_medicines";
        if (refusal == null && sentToday) refusal = "limited";
        Boolean real = jdbc.queryForObject("select exists (select 1 from dose_events de join medicine_schedules s on s.id = de.schedule_id " +
                "join medicines m on m.id = s.medicine_id where m.recipient_id = ? and de.sent_at is not null)", Boolean.class, recipientId);
        return new Status(refusal == null ? "ok" : refusal, sentToday, Boolean.TRUE.equals(real));
    }

    public Result send(UUID userId, UUID recipientId) {
        Map<String, Object> person = person(userId, recipientId);
        if (person == null) return Result.of("not_found");
        String refusal = refusal(person);
        if (refusal != null) return Result.of(refusal);
        List<Map<String, Object>> slot = slot(person);
        if (slot.isEmpty()) return Result.of("no_medicines");
        Integer lastHour = jdbc.queryForObject("select count(*) from whatsapp_events where id like 'gw:sample:%' " +
                "and received_at > now() - interval '1 hour'", Integer.class);
        if (lastHour != null && lastHour >= globalPerHour) {
            log.warn("sample reminders: global limit of {} per hour reached", globalPerHour);
            return Result.of("busy");
        }
        String day = dayKey(recipientId, today(person));
        if (jdbc.update("insert into whatsapp_events (id) values (?) on conflict (id) do nothing", day) == 0) {
            return Result.of("limited");
        }
        String phone = (String) person.get("whatsapp_phone");
        Instant now = Instant.now();
        String label = ReminderTexts.sampleLabel((String) person.get("language"));
        String messageId;
        try {
            String pollId = Boolean.TRUE.equals(person.get("is_canary")) ? null
                    : wa.trySendPoll(phone, SchedulerService.slotPoll(slot, null, now, label));
            messageId = pollId != null ? pollId : wa.send(phone, label + "\n" + SchedulerService.slotText(slot, null, now));
        } catch (Exception e) {
            jdbc.update("delete from whatsapp_events where id = ?", day);   // not sent: the family may try again
            log.warn("sample reminder to {} not sent ({})", WhatsAppSender.mask(phone), WhatsAppSender.failureReason(e));
            return Result.of("send_failed");
        }
        if (messageId != null && !messageId.isBlank() && !"sent".equals(messageId)) {
            jdbc.update("insert into whatsapp_events (id) values (?) on conflict (id) do nothing", messageKey(recipientId, messageId));
        }
        Instant at = scheduledAt(slot.get(0));
        String time = at == null ? null : ReminderTexts.slotTime((String) person.get("language"), at.atZone(zone(person)).toLocalTime());
        log.info("sample reminder sent for recipient {} ({} medicine(s))", recipientId, slot.size());
        return new Result("sent", time, at);
    }

    // ---------------------------------------------------------------- the next real reminder (also used for the answer to a tap)

    /** "8:00 pm" / "रात 8:00" of the person's next real reminder in the receiver's language, plus whether it is tomorrow; null = none. */
    public NextTime nextTime(UUID recipientId) {
        List<Map<String, Object>> rows = jdbc.queryForList(columns() + " where r.id = ?", recipientId);
        if (rows.isEmpty()) return null;
        Map<String, Object> person = rows.get(0);
        List<Map<String, Object>> slot = slot(person);
        Instant at = slot.isEmpty() ? null : scheduledAt(slot.get(0));
        if (at == null) return new NextTime((String) person.get("language"), null, false);
        ZonedDateTime local = at.atZone(zone(person));
        return new NextTime((String) person.get("language"), ReminderTexts.slotTime((String) person.get("language"), local.toLocalTime()),
                !local.toLocalDate().equals(today(person)));
    }

    public record NextTime(String language, String time, boolean tomorrow) {}

    // ---------------------------------------------------------------- helpers

    private static String columns() {
        return "select r.id recipient_id, r.circle_id, r.is_self, r.whatsapp_phone, " + ReceiverTexts.languageSql("r") + " language, " +
                "r.call_name, r.name recipient_name, r.name person_name, r.timezone, r.care_type, r.due_date, r.reminder_to, r.reminder_name, " +
                "coalesce((to_jsonb(r) ->> 'is_canary')::boolean, false) is_canary, (r.consent_at is not null) consented, " +
                "(r.opted_out_at is not null or exists (select 1 from phone_opt_outs o where o.phone = r.whatsapp_phone)) stopped " +
                "from care_recipients r";
    }

    private Map<String, Object> person(UUID userId, UUID recipientId) {
        List<Map<String, Object>> rows = jdbc.queryForList(columns() + " where r.id = ? and exists (select 1 from care_circle_members m " +
                "where m.circle_id = r.circle_id and m.user_id = ?)", recipientId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String refusal(Map<String, Object> person) {
        if (person.get("whatsapp_phone") == null) return "no_number";
        if (Boolean.TRUE.equals(person.get("stopped"))) return "opted_out";
        if (!Boolean.TRUE.equals(person.get("consented"))) return "not_consented";
        return null;
    }

    /**
     * The rows of the person's next time slot (person columns + medicine_name, dosage, pill_colour, pill_shape, scheduled_at), as
     * the scheduler would send it: the earliest waiting dose of an active medicine and every other dose at that same moment. With no
     * waiting dose yet (the plan is made a day ahead), the next clock time of the active medicines' schedules. Empty = nothing to show.
     */
    private List<Map<String, Object>> slot(Map<String, Object> person) {
        UUID recipientId = (UUID) person.get("recipient_id");
        List<Map<String, Object>> doses = jdbc.queryForList(
                "select de.scheduled_at, m.name medicine_name, m.dosage, to_jsonb(m) ->> 'pill_colour' pill_colour, " +
                "to_jsonb(m) ->> 'pill_shape' pill_shape from dose_events de join medicine_schedules s on s.id = de.schedule_id " +
                "join medicines m on m.id = s.medicine_id where m.recipient_id = ? and m.active and de.status = 'pending' " +
                "and de.scheduled_at = (select min(d2.scheduled_at) from dose_events d2 join medicine_schedules s2 on s2.id = d2.schedule_id " +
                " join medicines m2 on m2.id = s2.medicine_id where m2.recipient_id = ? and m2.active and d2.status = 'pending' " +
                " and d2.scheduled_at > now()) order by lower(m.name), de.id", recipientId, recipientId);
        if (doses.isEmpty()) doses = fromSchedules(person);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> d : doses) {
            Map<String, Object> row = new HashMap<>(person);
            row.putAll(d);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> fromSchedules(Map<String, Object> person) {
        ZoneId zone = zone(person);
        List<Map<String, Object>> times = jdbc.queryForList(
                "select s.time_of_day, m.name medicine_name, m.dosage, to_jsonb(m) ->> 'pill_colour' pill_colour, " +
                "to_jsonb(m) ->> 'pill_shape' pill_shape from medicine_schedules s join medicines m on m.id = s.medicine_id " +
                "where m.recipient_id = ? and m.active and (s.end_date is null or s.end_date >= ?) order by lower(m.name), m.id",
                person.get("recipient_id"), java.sql.Date.valueOf(today(person)));
        ZonedDateTime now = ZonedDateTime.now(zone);
        Instant best = null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : times) {
            if (!(t.get("time_of_day") instanceof Time time)) continue;
            LocalTime lt = time.toLocalTime();
            ZonedDateTime at = now.toLocalDate().atTime(lt).atZone(zone);
            Instant next = (at.isAfter(now) ? at : at.plusDays(1)).toInstant();
            if (best == null || next.isBefore(best)) {
                best = next;
                out.clear();
            }
            if (next.equals(best)) {
                Map<String, Object> row = new HashMap<>(t);
                row.remove("time_of_day");
                row.put("scheduled_at", Timestamp.from(next));
                out.add(row);
            }
        }
        return out;
    }

    private int count(String key) {
        Integer n = jdbc.queryForObject("select count(*) from whatsapp_events where id = ?", Integer.class, key);
        return n == null ? 0 : n;
    }

    private static Instant scheduledAt(Map<String, Object> row) {
        Object v = row.get("scheduled_at");
        return v instanceof Timestamp ts ? ts.toInstant() : v instanceof java.time.OffsetDateTime o ? o.toInstant()
                : v instanceof Instant i ? i : null;
    }

    static ZoneId zone(Map<String, Object> person) {
        try {
            Object tz = person.get("timezone");
            return ZoneId.of(tz == null || tz.toString().isBlank() ? "Asia/Kolkata" : tz.toString());
        } catch (Exception e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }

    static LocalDate today(Map<String, Object> person) {
        return LocalDate.now(zone(person));
    }
}
