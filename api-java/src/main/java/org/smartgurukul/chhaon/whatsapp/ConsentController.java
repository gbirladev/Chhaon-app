package org.smartgurukul.chhaon.whatsapp;

import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The routes the web app already calls (same paths and answers as the Python API): they are NOT under /api/v1
 * because the web client addresses the API root.
 */
@RestController
public class ConsentController {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    private final ConsentService consent;
    /** The number parents see our WhatsApp messages come from; null when not set (or not a valid E.164 number). */
    private final String senderPhone;

    public ConsentController(ConsentService consent,
                             @Value("${app.whatsapp.sender-phone:}") String senderPhone) {
        this.consent = consent;
        String s = senderPhone == null ? "" : senderPhone.replaceAll("[\\s-]", "");
        this.senderPhone = E164.matcher(s).matches() ? s : null;
    }

    /**
     * Which optional features this server has switched on. No login, no secrets. Both are off in the Java backend. Also
     * "sender": the WhatsApp number our messages come from (it writes to strangers anyway, so it is not a secret), when set.
     */
    @GetMapping("/features")
    public Map<String, Object> features() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("escalation", false);
        m.put("love", false);
        if (senderPhone != null) m.put("sender", senderPhone);
        return m;
    }

    /** Asks the parent, on WhatsApp, to agree to receive Chhaon messages: {status[, retry_after][, position]}. */
    @PostMapping("/recipients/{recipientId}/consent-request")
    public Map<String, Object> requestConsent(@AuthenticationPrincipal AuthenticatedUser user,
                                              @PathVariable UUID recipientId) {
        ConsentService.Result r = consent.requestConsent(user.id(), recipientId);
        return answer(r);
    }

    /**
     * "Get alerts on WhatsApp" (web: requestAlerts in web/src/lib/api.ts): sends the caregiver's own number a question;
     * alerts start only after that number types 1. Body {"phone": "+91..."} (E.164). Answers {status: sent | opted_out |
     * too_soon | too_many [, retry_after]}; 422 for a malformed number; 502 when WhatsApp is unavailable.
     */
    @PostMapping("/alerts/whatsapp/request")
    public Map<String, Object> requestAlerts(@AuthenticationPrincipal AuthenticatedUser user,
                                             @RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("phone");
        String phone = raw instanceof String s ? s.trim() : "";
        if (!E164.matcher(phone).matches()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid phone number");
        }
        return answer(consent.requestAlertOptIn(user.id(), phone));
    }

    private static Map<String, Object> answer(ConsentService.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status());
        if (r.retryAfter() != null) m.put("retry_after", r.retryAfter());
        if (r.position() != null) m.put("position", r.position());
        return m;
    }
}
