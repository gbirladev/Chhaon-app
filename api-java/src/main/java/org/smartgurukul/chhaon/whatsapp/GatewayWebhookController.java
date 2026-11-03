package org.smartgurukul.chhaon.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Receives the self-hosted WhatsApp gateway's webhook ({@code POST /webhooks/whatsapp-gateway}). Public in
 * SecurityConfig (a webhook is not user-authenticated) and therefore FAILS CLOSED on the signature:
 * <ul>
 *   <li>no secret configured ({@code app.whatsapp.webhook-secret} / env WHATSAPP_WEBHOOK_SECRET): 503, nothing done;</li>
 *   <li>signature header missing or wrong: 401, nothing done;</li>
 *   <li>body too large or not JSON: 413 / 400;</li>
 *   <li>accepted: 200 (also for events we deliberately ignore, so the gateway does not treat them as failures);</li>
 *   <li>processing failed (database down): 500, with everything rolled back.</li>
 * </ul>
 * Nothing in the request is ever logged (it holds phone numbers and message text).
 */
@RestController
public class GatewayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebhookController.class);
    static final int MAX_BODY_BYTES = 256 * 1024;

    private final String secret;
    private final ObjectMapper json;
    private final GatewayEventService events;

    public GatewayWebhookController(@Value("${app.whatsapp.webhook-secret:}") String secret,
                                    ObjectMapper json, GatewayEventService events) {
        this.secret = secret;
        this.json = json;
        this.events = events;
    }

    @PostMapping("/webhooks/whatsapp-gateway")
    public ResponseEntity<Map<String, String>> receive(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(name = "X-Webhook-Signature", required = false) String signature) {
        if (secret == null || secret.isBlank()) {
            log.error("gateway webhook refused: app.whatsapp.webhook-secret (WHATSAPP_WEBHOOK_SECRET) is not configured");
            return reply(HttpStatus.SERVICE_UNAVAILABLE, "webhook not configured");
        }
        byte[] raw = body == null ? new byte[0] : body;
        if (!WebhookSignature.verify(secret, raw, signature)) {
            log.warn("gateway webhook refused: missing or wrong signature");
            return reply(HttpStatus.UNAUTHORIZED, "bad signature");
        }
        if (raw.length > MAX_BODY_BYTES) {
            return reply(HttpStatus.PAYLOAD_TOO_LARGE, "too large");
        }
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (Exception e) {
            return reply(HttpStatus.BAD_REQUEST, "not json");
        }
        if (root == null || !root.isObject()) {
            return reply(HttpStatus.BAD_REQUEST, "not an event");
        }
        try {
            String outcome = events.process(root);
            return reply(HttpStatus.OK, outcome);
        } catch (Exception e) {
            // Class name only: a stack trace or message could carry phone numbers.
            log.error("gateway webhook processing failed ({})", e.getClass().getSimpleName());
            return reply(HttpStatus.INTERNAL_SERVER_ERROR, "processing failed");
        }
    }

    private static ResponseEntity<Map<String, String>> reply(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", message));
    }
}
