package org.smartgurukul.chhaon.whatsapp;

import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The routes the web app already calls (/alerts/whatsapp/request is not ported yet) (same paths and answers as the Python API): they are NOT under /api/v1
 * because the web client addresses the API root.
 */
@RestController
public class ConsentController {

    private final ConsentService consent;

    public ConsentController(ConsentService consent) {
        this.consent = consent;
    }

    /** Which optional features this server has switched on. No login, no secrets. Both are off in the Java backend. */
    @GetMapping("/features")
    public Map<String, Boolean> features() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("escalation", false);
        m.put("love", false);
        return m;
    }

    /** Asks the parent, on WhatsApp, to agree to receive Chhaon messages: {status[, retry_after]}. */
    @PostMapping("/recipients/{recipientId}/consent-request")
    public Map<String, Object> requestConsent(@AuthenticationPrincipal AuthenticatedUser user,
                                              @PathVariable UUID recipientId) {
        ConsentService.Result r = consent.requestConsent(user.id(), recipientId);
        return answer(r);
    }

    private static Map<String, Object> answer(ConsentService.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status());
        if (r.retryAfter() != null) m.put("retry_after", r.retryAfter());
        return m;
    }
}
