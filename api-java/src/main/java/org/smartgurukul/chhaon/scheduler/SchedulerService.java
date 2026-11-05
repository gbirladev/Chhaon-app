package org.smartgurukul.chhaon.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartgurukul.chhaon.whatsapp.MessageTexts;
import org.smartgurukul.chhaon.whatsapp.WhatsAppSender;
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
 * WhatsApp sends go through {@link WhatsAppSender} (dry-run until that port lands). Not yet ported:
 * escalation ladder, alert delivery to caregivers' WhatsApp, appointment reminders, love loop,
 * slot grouping, and the inbound "Taken"/checkin reply transitions (WhatsApp webhook port).
 */
@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final JdbcTemplate jdbc;
    private final WhatsAppSender wa;

    private final boolean enabled;
    private final int doseMissedMinutes;
    private final int checkinMissedMinutes;
    private final int checkinStartWindowMinutes;
    private final int horizonHours;
    /** null = every family. Set to one recipient id to run the scheduler for that parent only (first switch-on). */
    private final UUID onlyRecipient;
    /** true when chhaon.scheduler.only-recipient holds something that is neither empty, "all" nor a recipient id: then NOTHING runs. */
    private final boolean onlyRecipientInvalid;

    public SchedulerService(JdbcTemplate jdbc,
                            WhatsAppSender wa,
                            @Value("${chhaon.scheduler.enabled:false}") boolean enabled,
                            @Value("${chhaon.scheduler.dose-missed-minutes:60}") int doseMissedMinutes,
                            @Value("${chhaon.scheduler.checkin-missed-minutes:180}") int checkinMissedMinutes,
                            @Value("${chhaon.scheduler.checkin-start-window-minutes:120}") int checkinStartWindowMinutes,
                            @Value("${chhaon.scheduler.horizon-hours:24}") int horizonHours,
                            @Value("${chhaon.scheduler.only-recipient:}") String onlyRecipient) {
        this.jdbc = jdbc;
        this.wa = wa;
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

    /** Run one tick. scope = a single recipient id to limit to (for tests), or null for all. */
    public Map<String, Integer> tick(UUID scope) {
        Map<String, Integer> counters = new LinkedHashMap<>();
        step(counters, "generated", () -> generate(scope));
        step(counters, "doses_sent", () -> sendDueDoses(scope, counters));
        step(counters, "reminders_not_sent", () -> expireUnsentDoses(scope));
        step(counters, "doses_missed", () -> markMissedDoses(scope));
        step(counters, "checkins_sent", () -> sendCheckins(scope));
        step(counters, "checkins_missed", () -> markMissedCheckins(scope));
        recordHeartbeat();
        log.info("tick(scope={}) {}", scope, counters);
        return counters;
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
        String sql = "select de.id dose_id, r.id recipient_id, r.circle_id, r.is_self, r.whatsapp_phone, r.language, " +
                "r.call_name, " +
                "(r.consent_at is not null and r.opted_out_at is null and r.whatsapp_phone is not null " +
                " and not exists(select 1 from phone_opt_outs o where o.phone = r.whatsapp_phone)) allowed, " +
                "m.id medicine_id, m.name medicine_name " +
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
                String text = MessageTexts.doseReminder((String) row.get("language"),
                        (String) row.get("call_name"), (String) row.get("medicine_name"));
                String msgId = wa.send((String) row.get("whatsapp_phone"), text);
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
        String sql = "select de.id dose_id, r.circle_id, r.is_self, r.opted_out_at, m.recipient_id, m.id medicine_id " +
                "from dose_events de join medicine_schedules s on s.id=de.schedule_id " +
                "join medicines m on m.id=s.medicine_id join care_recipients r on r.id=m.recipient_id " +
                "where de.status='sent' and de.sent_at <= now() - make_interval(mins => ?)" +
                scopeClause(scope, p, "r");
        int missed = 0;
        for (Map<String, Object> row : jdbc.queryForList(sql, p.toArray())) {
            UUID doseId = (UUID) row.get("dose_id");
            if (jdbc.update("update dose_events set status='missed' where id=? and status='sent'", doseId) == 1) {
                missed++;
                if (row.get("opted_out_at") == null && !Boolean.TRUE.equals(row.get("is_self"))) {
                    notifyCircle((UUID) row.get("circle_id"), "dose_missed",
                            json("recipient_id", row.get("recipient_id"), "medicine_id", row.get("medicine_id"),
                                    "dose_event_id", doseId));
                }
            }
        }
        return missed;
    }

    private int sendCheckins(UUID scope) {
        List<Object> p = new ArrayList<>();
        String sql = "select r.id recipient_id, r.timezone, r.checkin_time, r.circle_id, r.whatsapp_phone, r.language, r.call_name " +
                "from care_recipients r " +
                "where r.consent_at is not null and r.opted_out_at is null and r.whatsapp_phone is not null " +
                "and r.is_self = false " +
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
                        MessageTexts.checkin((String) row.get("language"), (String) row.get("call_name")));
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
            else sb.append('"').append(v).append('"');
        }
        return sb.append('}').toString();
    }
}
