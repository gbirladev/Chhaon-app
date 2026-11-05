package org.smartgurukul.chhaon.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WhatsApp alerts to the CAREGIVER (port of {@code deliver_pending_alerts} in {@code api/app/services/alerts.py}).
 *
 * <p>The in-app notifications (the bell) are created elsewhere: the scheduler, the reply handlers and database triggers
 * (refill / out of stock). This turns the ones that matter ({@link AlertTexts#TYPES}) into WhatsApp texts. The
 * {@code notifications} table IS the outbox (columns {@code whatsapp_sent_at}, {@code whatsapp_skipped_at},
 * {@code whatsapp_attempts}, migration 20261011000015): every scheduler tick drains it as its last step, and a webhook reply
 * that created a notification (for example "call me") drains it at once.
 *
 * <p>Rules (same as Python):
 * <ul>
 *   <li>double opt-in: only to a caregiver whose own number confirmed by typing 1 ({@code profiles.alert_consent_at}), never to a
 *       number in {@code phone_opt_outs}; anyone else's copy is marked skipped, never retried;</li>
 *   <li>no duplicates: claimed in the database ({@code whatsapp_sent_at}) before sending; overlapping drains cannot both win;</li>
 *   <li>a failed send hands the claim back for a retry, at most {@code max-attempts} times; the in-app notification is never
 *       touched beyond these three columns;</li>
 *   <li>never late: older than {@code lookback-hours} is never sent; at most {@code per-hour} per caregiver (the rest wait);</li>
 *   <li>the "Me" profile ({@code is_self}): the notification itself is not created for the types in {@code CircleNotifier.SELF_QUIET};
 *       refill / out of stock still come through, like Python. Checked here once more as a safety net;</li>
 *   <li>no quiet hours (Python had none): these alerts are rare and time-critical;</li>
 *   <li>logs carry neither phone numbers (masked) nor names or medicines.</li>
 * </ul>
 * Never throws for one bad row: each row is handled on its own.
 */
@Component
public class AlertDelivery {

    private static final Logger log = LoggerFactory.getLogger(AlertDelivery.class);

    /** The types the "Me" profile never hears about (same list as CircleNotifier.SELF_QUIET, limited to alert types). */
    private static final java.util.Set<String> SELF_QUIET = java.util.Set.of(
            "dose_missed", "checkin_missed", "checkin_call_requested", "parent_stopped", "parent_declined");

    private final JdbcTemplate jdbc;
    private final WhatsAppSender wa;
    private final int perHour;
    private final int maxAttempts;
    private final int lookbackHours;

    public AlertDelivery(JdbcTemplate jdbc, WhatsAppSender wa,
                         @Value("${chhaon.alerts.per-hour:6}") int perHour,
                         @Value("${chhaon.alerts.max-attempts:3}") int maxAttempts,
                         @Value("${chhaon.alerts.lookback-hours:6}") int lookbackHours) {
        this.jdbc = jdbc;
        this.wa = wa;
        this.perHour = perHour;
        this.maxAttempts = maxAttempts;
        this.lookbackHours = lookbackHours;
    }

    /**
     * Drains the outbox once. {@code scope} limits it to the alerts about one parent (the scheduler's only-recipient switch,
     * and tests); null = every family. Returns counters (alerts_sent / _skipped / _failed / _deferred).
     */
    public Map<String, Integer> deliver(UUID scope) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Instant now = Instant.now();
        List<Object> p = new ArrayList<>(AlertTexts.TYPES);
        p.add(Timestamp.from(now.minus(Duration.ofHours(lookbackHours))));
        String scopeSql = "";
        if (scope != null) {
            scopeSql = " and n.payload->>'recipient_id' = ?";
            p.add(scope.toString());
        }
        String in = String.join(",", AlertTexts.TYPES.stream().map(t -> "?").toList());
        List<Map<String, Object>> outbox = jdbc.queryForList(
                "select n.id, n.user_id, n.type, n.whatsapp_attempts, n.payload->>'recipient_id' rid, " +
                        "n.payload->>'medicine_id' mid, n.payload->>'dose_event_id' did, n.payload->>'count' cnt " +
                        "from notifications n where n.whatsapp_sent_at is null and n.whatsapp_skipped_at is null " +
                        "and n.type in (" + in + ") and n.created_at >= ?" + scopeSql +
                        " order by n.created_at limit 200", p.toArray());
        Map<Object, Map<String, Object>> profiles = new HashMap<>();
        for (Map<String, Object> n : outbox) {
            try {
                deliverOne(n, profiles, counts);
            } catch (Exception e) { // one bad row never stops the others (or the tick)
                bump(counts, "alerts_failed");
                log.warn("alert {} could not be handled ({})", n.get("id"), e.getClass().getSimpleName());
            }
        }
        return counts;
    }

    private void deliverOne(Map<String, Object> n, Map<Object, Map<String, Object>> profiles, Map<String, Integer> counts) {
        UUID id = (UUID) n.get("id");
        Object userId = n.get("user_id");
        Map<String, Object> profile = profiles.computeIfAbsent(userId, u -> firstOrNull(jdbc.queryForList(
                "select language, timezone, alert_phone, alert_consent_at from profiles where id = ?", u)));
        String phone = profile == null ? null : (String) profile.get("alert_phone");
        Map<String, Object> parent = n.get("rid") == null ? null : firstOrNull(jdbc.queryForList(
                "select name, call_name, timezone, is_self from care_recipients where id = ?::uuid", n.get("rid")));
        boolean selfQuiet = parent != null && Boolean.TRUE.equals(parent.get("is_self")) && SELF_QUIET.contains((String) n.get("type"));
        if (profile == null || profile.get("alert_consent_at") == null || phone == null || phoneOptedOut(phone) || selfQuiet) {
            jdbc.update("update notifications set whatsapp_skipped_at = now() where id = ? " +
                    "and whatsapp_sent_at is null and whatsapp_skipped_at is null", id);
            bump(counts, "alerts_skipped");
            return;
        }
        Integer recent = jdbc.queryForObject(
                "select count(*) from notifications where user_id = ? and whatsapp_sent_at > now() - interval '1 hour'",
                Integer.class, userId);
        if (recent != null && recent >= perHour) {
            bump(counts, "alerts_deferred"); // waits for the hour to free up; the lookback window bounds how late
            return;
        }
        String language = (String) profile.get("language");
        String text = text(n, parent, language, (String) profile.get("timezone"));
        if (text == null) { // cannot happen for the selected types; never retry it
            jdbc.update("update notifications set whatsapp_skipped_at = now() where id = ? and whatsapp_sent_at is null", id);
            bump(counts, "alerts_skipped");
            return;
        }
        if (jdbc.update("update notifications set whatsapp_sent_at = now() where id = ? " +
                "and whatsapp_sent_at is null and whatsapp_skipped_at is null", id) != 1) {
            return; // another drain took it
        }
        try {
            wa.send(phone, text);
            bump(counts, "alerts_sent");
        } catch (Exception e) {
            int attempts = ((Number) n.get("whatsapp_attempts")).intValue() + 1;
            jdbc.update("update notifications set whatsapp_sent_at = null, whatsapp_attempts = ?, " +
                            "whatsapp_skipped_at = case when ? >= ? then now() else null end where id = ?",
                    attempts, attempts, maxAttempts, id);
            bump(counts, "alerts_failed");
            log.warn("could not deliver an alert to {} (attempt {}, {})", WhatsAppSender.mask(phone), attempts,
                    WhatsAppSender.failureReason(e));
        }
    }

    private String text(Map<String, Object> n, Map<String, Object> parent, String language, String caregiverZone) {
        String parentName = null;
        String parentZone = null;
        if (parent != null) {
            String call = (String) parent.get("call_name");
            parentName = call != null && !call.isBlank() ? call : (String) parent.get("name");
            parentZone = (String) parent.get("timezone");
        }
        String medicine = null;
        if (n.get("mid") != null) {
            List<String> names = jdbc.queryForList("select name from medicines where id = ?::uuid", String.class, n.get("mid"));
            if (!names.isEmpty()) medicine = names.get(0);
        }
        String when = null;
        if (n.get("did") != null) {
            List<Timestamp> at = jdbc.queryForList("select scheduled_at from dose_events where id = ?::uuid",
                    Timestamp.class, n.get("did"));
            if (!at.isEmpty()) when = AlertTexts.clock(at.get(0).toInstant(), parentZone, caregiverZone, language);
        }
        Integer count = null;
        try {
            if (n.get("cnt") != null) count = Integer.valueOf(((String) n.get("cnt")).trim());
        } catch (NumberFormatException ignored) {
            // an odd payload: say "a medicine"
        }
        return AlertTexts.alert((String) n.get("type"), language, parentName, medicine, count, when);
    }

    private boolean phoneOptedOut(String phone) {
        Integer c = jdbc.queryForObject("select count(*) from phone_opt_outs where phone = ?", Integer.class, phone);
        return c != null && c > 0;
    }

    private static void bump(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static Map<String, Object> firstOrNull(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }
}
