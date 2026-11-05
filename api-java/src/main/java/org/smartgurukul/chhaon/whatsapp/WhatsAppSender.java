package org.smartgurukul.chhaon.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Sends WhatsApp messages via the self-hosted WA-AKG gateway (Baileys), mirroring Smart Gurukul.
 * The gateway runs on the same EC2 box; we call it over localhost. Custom free-form text is
 * allowed (no Meta templates). When the gateway isn't configured, sends are logged (dry-run), so
 * the scheduler and auth never fail just because the gateway is down — matching SG's fail-soft rule.
 *
 * Config: app.whatsapp.gateway-url, app.whatsapp.gateway-token (wag_ API key),
 * app.whatsapp.sender-session-name (the linked session's display name, e.g. "Chhaon").
 */
@Service
public class WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppSender.class);

    private final String baseUrl;
    private final String token;
    private final String sessionName;
    private final RestClient client;

    // cheap session-id cache (resolved by name)
    private volatile String cachedSessionId;
    private volatile long cachedAt;

    public WhatsAppSender(
            @Value("${app.whatsapp.gateway-url:}") String baseUrl,
            @Value("${app.whatsapp.gateway-token:}") String token,
            @Value("${app.whatsapp.sender-session-name:Chhaon}") String sessionName) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.sessionName = sessionName;
        this.client = (baseUrl == null || baseUrl.isBlank())
                ? null
                : RestClient.builder().baseUrl(baseUrl).defaultHeader("X-API-Key", token).build();
    }

    public boolean isLive() {
        return client != null;
    }

    /**
     * Send free-form text to an E.164 phone. Returns the provider message id, or a dry-run id when
     * the gateway isn't configured. Throws on a real send failure (caller reverts + retries).
     */
    public String send(String toPhone, String text) {
        if (client == null) {
            log.info("[WA dry-run] to={} text={} (gateway not configured)", mask(toPhone), text);
            return "dryrun-" + UUID.randomUUID();
        }
        String jid = toJid(toPhone);
        String sessionId = resolveSessionId();
        JsonNode resp = client.post()
                .uri("/api/messages/{session}/{jid}/send", sessionId, jid)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", Map.of("text", text)))
                .retrieve()
                .body(JsonNode.class);
        if (resp == null || !resp.path("status").asBoolean(false)) {
            cachedSessionId = null; // force re-resolve next time
            String why = resp == null ? "no answer" : resp.path("message").asText(resp.path("error").asText("no reason given"));
            throw new IllegalStateException("WhatsApp gateway send failed: " + why);
        }
        return resp.path("data").path("key").path("id").asText("sent");
    }

    /** Resolve the session id by its display name (prefer CONNECTED), cached for 5 minutes. */
    private String resolveSessionId() {
        long now = System.currentTimeMillis();
        String cached = cachedSessionId;
        if (cached != null && now - cachedAt < 300_000) return cached;

        JsonNode resp = client.get().uri("/api/sessions").retrieve().body(JsonNode.class);
        JsonNode data = resp == null ? null : resp.path("data");
        String fallback = null;
        if (data != null && data.isArray()) {
            for (JsonNode s : data) {
                if (sessionName.equals(s.path("name").asText())) {
                    fallback = s.path("sessionId").asText();
                    if ("CONNECTED".equals(s.path("status").asText())) {
                        cachedSessionId = fallback;
                        cachedAt = now;
                        return fallback;
                    }
                }
            }
        }
        if (fallback == null) {
            throw new IllegalStateException("No WhatsApp session named '" + sessionName + "' on the gateway");
        }
        cachedSessionId = fallback;
        cachedAt = now;
        return fallback;
    }

    /** E.164 (+919876543210) -> Baileys JID (919876543210@s.whatsapp.net). Always international. */
    private static String toJid(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        if (digits.length() <= 10) digits = "91" + digits; // assume India if no country code
        return digits + "@s.whatsapp.net";
    }

    /**
     * A short, safe reason for a failed send, for the log and for the caregiver's error response: it never holds a phone number,
     * an address or a key. Lets the owner tell "wrong address", "wrong key", "no such session" and "gateway refused" apart.
     */
    public static String failureReason(Throwable e) {
        if (e instanceof org.springframework.web.client.ResourceAccessException) return "gateway_unreachable";
        if (e instanceof org.springframework.web.client.RestClientResponseException h) {
            int s = h.getStatusCode().value();
            return (s == 401 || s == 403) ? "gateway_key_rejected_" + s : "gateway_http_" + s;
        }
        if (e instanceof IllegalStateException) {
            String m = String.valueOf(e.getMessage());
            if (m.startsWith("No WhatsApp session")) return "session_name_not_found";
            if (m.startsWith("WhatsApp gateway send failed: ")) {
                String why = m.substring("WhatsApp gateway send failed: ".length()).replaceAll("[0-9]", "#");
                return "gateway_refused: " + (why.length() > 80 ? why.substring(0, 80) : why);
            }
        }
        return "other_" + e.getClass().getSimpleName();
    }

    /** Phone numbers are never logged in full: only the last three digits. */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return "***" + phone.substring(phone.length() - 3);
    }
}
