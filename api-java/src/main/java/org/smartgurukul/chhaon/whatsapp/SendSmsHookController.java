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
 * Supabase <b>Send SMS</b> auth hook ({@code POST /hooks/supabase-sms}). Instead of paying an SMS provider, we deliver
 * the login one-time code over WhatsApp via the self-hosted WA-AKG gateway (like Smart Gurukul). Supabase still owns
 * generating, rate-limiting and verifying the code; we only carry it to the phone.
 *
 * <p>Public in SecurityConfig (a hook is not user-authenticated) and therefore FAILS CLOSED on the signature:
 * <ul>
 *   <li>no secret configured ({@code app.supabase.send-sms-hook-secret} / env SUPABASE_SEND_SMS_HOOK_SECRET): 503;</li>
 *   <li>missing/wrong Standard-Webhooks signature: 401;</li>
 *   <li>body not the expected JSON (no phone / no otp): 400;</li>
 *   <li>delivered: 200;</li>
 *   <li>gateway send failed: 500 with a safe reason, so Supabase surfaces "couldn't send, try again".</li>
 * </ul>
 * Nothing in the request is ever logged (it holds a phone number and a live code).
 *
 * <p>Payload shape (Supabase): {@code { "user": { "phone": "919876543210", ... }, "sms": { "otp": "123456" } }}.
 */
@RestController
public class SendSmsHookController {

    private static final Logger log = LoggerFactory.getLogger(SendSmsHookController.class);
    static final int MAX_BODY_BYTES = 64 * 1024;

    private final String secret;
    private final ObjectMapper json;
    private final WhatsAppSender sender;

    public SendSmsHookController(@Value("${app.supabase.send-sms-hook-secret:}") String secret,
                                ObjectMapper json, WhatsAppSender sender) {
        this.secret = secret;
        this.json = json;
        this.sender = sender;
    }

    @PostMapping("/hooks/supabase-sms")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(name = "webhook-id", required = false) String id,
            @RequestHeader(name = "webhook-timestamp", required = false) String timestamp,
            @RequestHeader(name = "webhook-signature", required = false) String signature) {
        if (secret == null || secret.isBlank()) {
            log.error("send-sms hook refused: app.supabase.send-sms-hook-secret (SUPABASE_SEND_SMS_HOOK_SECRET) is not configured");
            return error(HttpStatus.SERVICE_UNAVAILABLE, "hook not configured");
        }
        byte[] raw = body == null ? new byte[0] : body;
        if (raw.length > MAX_BODY_BYTES) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "too large");
        }
        if (!StandardWebhookSignature.verify(secret, id, timestamp, raw, signature)) {
            log.warn("send-sms hook refused: missing or wrong signature");
            return error(HttpStatus.UNAUTHORIZED, "bad signature");
        }
        String phone;
        String otp;
        try {
            JsonNode root = json.readTree(raw);
            phone = root.path("user").path("phone").asText(null);
            otp = root.path("sms").path("otp").asText(null);
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "not json");
        }
        if (phone == null || phone.isBlank() || otp == null || otp.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "missing phone or otp");
        }
        try {
            sender.send(phone, MessageTexts.loginOtp(otp));
            Map<String, Object> ok = Map.of();
            return ResponseEntity.ok(ok);
        } catch (Exception e) {
            String reason = WhatsAppSender.failureReason(e);
            log.error("send-sms hook: WhatsApp delivery failed ({})", reason);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "delivery failed: " + reason);
        }
    }

    /** Standard Webhooks / Supabase error shape: a top-level {@code error} object the client can surface. */
    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = Map.of("error", Map.of("http_code", status.value(), "message", message));
        return ResponseEntity.status(status).body(body);
    }
}
