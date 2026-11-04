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
                "select id, circle_id, name, call_name, language, whatsapp_phone, consent_at, opted_out_at, is_self " +
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
        try {
            wa.send(phone, text);
        } catch (Exception e) {
            jdbc.update("delete from consent_requests where id = ?", logged); // the attempt does not count
            log.warn("consent request could not be sent to {} ({})", WhatsAppSender.mask(phone), e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "WhatsApp is unavailable. Try again shortly.");
        }
        jdbc.update("update care_recipients set consent_requested_at = now() where id = ?", recipientId);
        return new Result("sent", null);
    }

    // ---------------------------------------------------------------- helpers

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
