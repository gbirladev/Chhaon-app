package org.smartgurukul.chhaon.whatsapp;

import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "Send a sample reminder now" on Today (docs/sample-reminder.md). Family circle members only (404 otherwise). No phone numbers in
 * the answers.
 * <ul>
 *   <li>{@code GET /api/v1/recipients/{id}/sample-reminder}: {reason: ok | no_number | not_consented | opted_out | no_medicines |
 *       limited, sentToday, realReminderSent}.</li>
 *   <li>{@code POST /api/v1/recipients/{id}/sample-reminder}: 200 {status: sent, slotTime, at}; 409 {status: no_number |
 *       not_consented | opted_out | no_medicines}; 429 {status: limited (one per person per day) | busy (global guard)};
 *       502 {status: send_failed}.</li>
 * </ul>
 */
@RestController
public class SampleReminderController {

    private final SampleReminderService samples;

    public SampleReminderController(SampleReminderService samples) {
        this.samples = samples;
    }

    @GetMapping("/api/v1/recipients/{recipientId}/sample-reminder")
    public Map<String, Object> status(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID recipientId) {
        SampleReminderService.Status s = samples.status(user.id(), recipientId);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent profile not found");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reason", s.reason());
        m.put("sentToday", s.sentToday());
        m.put("realReminderSent", s.realReminderSent());
        return m;
    }

    @PostMapping("/api/v1/recipients/{recipientId}/sample-reminder")
    public ResponseEntity<Map<String, Object>> send(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID recipientId) {
        SampleReminderService.Result r = samples.send(user.id(), recipientId);
        HttpStatus code = switch (r.status()) {
            case "sent" -> HttpStatus.OK;
            case "not_found" -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent profile not found");
            case "limited", "busy" -> HttpStatus.TOO_MANY_REQUESTS;
            case "send_failed" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.CONFLICT;
        };
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status());
        if (r.slotTime() != null) m.put("slotTime", r.slotTime());
        if (r.at() != null) m.put("at", r.at().toString());
        return ResponseEntity.status(code).body(m);
    }
}
