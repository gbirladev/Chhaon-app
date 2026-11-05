package org.smartgurukul.chhaon.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asking a parent (or a caregiver, for alerts) to agree to WhatsApp messages. Ports
 * {@code send_consent_request} / {@code request_opt_in} from the Python API, with the same limits decided
 * atomically in the database by {@code claim_consent_request}: one request per phone number per window across ALL
 * families, and a daily cap per caregiver. The parent answers by typing 1 (yes) or 2 (no); see ReplyRouter.
 */
@Service
public class ConsentService {

    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);

    public record Result(String status, Integer retryAfter) {}

    private final JdbcTemplate jdbc;
    private final WhatsAppSender wa;
    private final ObjectMapper json;
    private final int resendMinutes;
    private final int requestsPerDay;

    public ConsentService(JdbcTemplate jdbc, WhatsAppSender wa, ObjectMapper json,
                          @Value("${chhaon.consent.resend-minutes:10}") int resendMinutes,
                          @Value("${chhaon.consent.requests-per-day:10}") int requestsPerDay) {
        this.jdbc = jdbc;
        this.wa = wa;
        this.json = json;
        this.resendMinutes = resendMinutes;
        this.requestsPerDay = requestsPerDay;
    }

    /** sent | already_consented | opted_out | too_soon | too_many | no_phone. 404 when the caller is not in that parent's circle. */
    public Result requestConsent(UUID userId, UUID recipientId) {
        Map<String, Object> r = firstOrNull(jdbc.queryForList(
                "select id, circle_id, name, call_name, language, whatsapp_phone, consent_at, opted_out_at, is_self, " +
                        "reminder_to, reminder_name " +
                        "from care_recipients where id = ?", recipientId));
        if (r == null || !isMember((UUID) r.get("circle_id"), userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"); // same answer: do not reveal other circles
        }
        String phone = (String) r.get("whatsapp_phone");
        if (phone == null) return new Result("no_phone", null);
        // The parent said STOP: only they can restart (START, or 1 under a consent message).
        if (r.get("opted_out_at") != null || phoneOptedOut(phone)) return new Result("opted_out", null);
        if (r.get("consent_at") != null) return new Result("already_consented", null);

        JsonNode claim = claim(phone, userId);
        String status = claim.path("status").asText();
        if (!"ok".equals(status)) {
            return new Result(status, "too_soon".equals(status) ? retryAfter(phone) : null);
        }
        UUID logged = UUID.fromString(claim.path("id").asText());

        String language = (String) r.get("language");
        String text;
        if (Boolean.TRUE.equals(r.get("is_self"))) {
            String name = (String) r.get("name");
            text = MessageTexts.selfConsentRequest(language, name == null ? "" : name.trim().split(" ")[0]);
        } else {
            String callName = r.get("call_name") != null && !((String) r.get("call_name")).isBlank()
                    ? (String) r.get("call_name") : (String) r.get("name");
            text = MessageTexts.consentRequest(language, callName, caregiverName(userId));
        }
        String reminderTo = (String) r.get("reminder_to");
        if (!Boolean.TRUE.equals(r.get("is_self")) && ReceiverTexts.notThePerson(reminderTo)) {
            // A helper or the caregiver gets the reminders: say for whom and on whose behalf (docs/reminder-receiver.md).
            text = ReceiverTexts.consentRequest(language, reminderTo, (String) r.get("reminder_name"),
                    ReceiverTexts.personName((String) r.get("call_name"), (String) r.get("name")), caregiverName(userId));
        }
        String messageId;
        try {
            messageId = wa.send(phone, text);
        } catch (Exception e) {
            jdbc.update("delete from consent_requests where id = ?", logged); // the attempt does not count
            String reason = WhatsAppSender.failureReason(e);
            log.warn("consent request could not be sent to {} ({})", WhatsAppSender.mask(phone), reason);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "WhatsApp is unavailable. Try again shortly. [" + reason + "]");
        }
        jdbc.update("update care_recipients set consent_requested_at = now() where id = ?", recipientId);
        rememberConsentMessage(messageId, recipientId);
        return new Result("sent", null);
    }

    /**
     * Asks a caregiver's own number to confirm WhatsApp alerts (port of {@code request_opt_in}): sent | opted_out | too_soon |
     * too_many. The same limits as a parent's consent request (one request per number per window across all families, a daily
     * cap per caregiver). A new number is a new person: alerts stay OFF until THIS number types 1 (ReplyRouter.AlertOptIn).
     */
    public Result requestAlertOptIn(UUID userId, String phone) {
        if (phoneOptedOut(phone)) return new Result("opted_out", null); // that number said STOP / no: only it can lift that (START)
        JsonNode claim = claim(phone, userId);
        String status = claim.path("status").asText();
        if (!"ok".equals(status)) {
            return new Result(status, "too_soon".equals(status) ? retryAfter(phone) : null);
        }
        UUID logged = UUID.fromString(claim.path("id").asText());
        Map<String, Object> profile = firstOrNull(jdbc.queryForList("select name, language from profiles where id = ?", userId));
        String name = profile == null || profile.get("name") == null ? "" : ((String) profile.get("name")).trim();
        String language = profile == null ? "en" : (String) profile.get("language");
        String text = MessageTexts.alertOptIn(language, name.isEmpty() ? "" : name.split("\\s+")[0]);
        try {
            wa.send(phone, text);
        } catch (Exception e) {
            jdbc.update("delete from consent_requests where id = ?", logged); // the attempt does not count
            String reason = WhatsAppSender.failureReason(e);
            log.warn("alert opt-in could not be sent to {} ({})", WhatsAppSender.mask(phone), reason);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "WhatsApp is unavailable. Try again shortly. [" + reason + "]");
        }
        // Recorded only once the question really went out. Alerts stay off until this number answers.
        jdbc.update("update profiles set alert_phone = ?, alert_requested_at = now(), alert_confirmed_at = null, " +
                "alert_consent_at = null where id = ?", phone, userId);
        return new Result("sent", null);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Remembers which WhatsApp message asked this parent, so a 👍 reaction on it counts as "1" (ReplyRouter.routeReaction).
     * Best effort: without it the parent can still type 1. Skipped when the gateway gave no real id.
     */
    private void rememberConsentMessage(String messageId, UUID recipientId) {
        if (messageId == null || messageId.isBlank() || "sent".equals(messageId)) return;
        try {
            jdbc.update("insert into whatsapp_events (id) values (?) on conflict (id) do nothing",
                    org.smartgurukul.chhaon.whatsapp.reply.ReplyRouter.consentMarker(messageId, recipientId));
        } catch (Exception e) {
            log.warn("consent message id not stored ({})", e.getClass().getSimpleName());
        }
    }

    private JsonNode claim(String phone, UUID userId) {
        String raw = jdbc.queryForObject("select public.claim_consent_request(?, ?, make_interval(mins => ?), ?)::text",
                String.class, phone, userId, resendMinutes, requestsPerDay);
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable claim_consent_request answer", e);
        }
    }

    private boolean isMember(UUID circleId, UUID userId) {
        Integer n = jdbc.queryForObject("select count(*) from care_circle_members where circle_id = ? and user_id = ?",
                Integer.class, circleId, userId);
        return n != null && n > 0;
    }

    private boolean phoneOptedOut(String phone) {
        Integer n = jdbc.queryForObject("select count(*) from phone_opt_outs where phone = ?", Integer.class, phone);
        return n != null && n > 0;
    }

    private String caregiverName(UUID userId) {
        List<String> names = jdbc.queryForList("select name from profiles where id = ?", String.class, userId);
        return names.isEmpty() || names.get(0) == null ? "" : names.get(0);
    }

    /** Seconds until a refused request may be sent again: at least 1, at most the whole window. */
    private int retryAfter(String phone) {
        int window = resendMinutes * 60;
        try {
            List<Timestamp> last = jdbc.queryForList(
                    "select requested_at from consent_requests where phone = ? order by requested_at desc limit 1",
                    Timestamp.class, phone);
            if (last.isEmpty()) return window;
            long elapsed = Duration.between(last.get(0).toInstant(), Instant.now()).getSeconds();
            return (int) Math.max(1, Math.min(window, window - elapsed + 1));
        } catch (Exception e) { // a countdown is a convenience; never fail the request for it
            return window;
        }
    }

    private static Map<String, Object> firstOrNull(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }
}
