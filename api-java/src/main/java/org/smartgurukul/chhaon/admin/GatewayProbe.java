package org.smartgurukul.chhaon.admin;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartgurukul.chhaon.whatsapp.WhatsAppSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * Asks the self-hosted WhatsApp gateway (whatsapp-gateway/, X-API-Key like {@link WhatsAppSender}) three cheap questions, at most
 * once a minute (cached 60 s), with short timeouts:
 * <ol>
 *   <li>{@code GET /api/sessions}: is there a session with our name (WHATSAPP_SENDER_SESSION_NAME)?</li>
 *   <li>{@code GET /api/sessions/{id}}: its LIVE status (CONNECTED / DISCONNECTED / ...) and which number is logged in ({@code me.id});</li>
 *   <li>{@code GET /api/webhooks/{id}}: how many ACTIVE webhooks would deliver {@code message.received} for it. Zero means parent
 *       replies cannot reach us: the 2026-11-05 incident (a number change left the new session without a webhook).</li>
 * </ol>
 * Webhook secrets returned by the gateway are never kept or passed on: only counts and the target address (no query string).
 */
@Component
public class GatewayProbe {

    private static final Logger log = LoggerFactory.getLogger(GatewayProbe.class);
    private static final long CACHE_MS = 60_000;

    /**
     * @param webhooks active webhooks for message.received; null = could not be read
     * @param webhookTargets where they point (scheme://host/path), never the secret
     */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Status(boolean configured, boolean reachable, String sessionName, String sessionStatus, String number,
                         Integer webhooks, java.util.List<String> webhookTargets, String error, Instant checkedAt) {}

    private final String sessionName;
    private final RestClient client;
    private volatile Status cached;

    public GatewayProbe(@Value("${app.whatsapp.gateway-url:}") String baseUrl,
                        @Value("${app.whatsapp.gateway-token:}") String token,
                        @Value("${app.whatsapp.sender-session-name:Chhaon}") String sessionName) {
        this.sessionName = sessionName;
        if (baseUrl == null || baseUrl.isBlank()) {
            this.client = null;
        } else {
            SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
            f.setConnectTimeout(3000);
            f.setReadTimeout(4000);
            this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(f).defaultHeader("X-API-Key", token).build();
        }
    }

    public String sessionName() {
        return sessionName;
    }

    public Status status() {
        Status c = cached;
        if (c != null && Instant.now().toEpochMilli() - c.checkedAt().toEpochMilli() < CACHE_MS) return c;
        Status fresh = probe();
        cached = fresh;
        return fresh;
    }

    /** Forget the cached answer (tests). */
    public void forget() {
        cached = null;
    }

    private Status probe() {
        if (client == null) {
            return new Status(false, false, sessionName, null, null, null, java.util.List.of(), null, Instant.now());
        }
        JsonNode sessions;
        try {
            sessions = client.get().uri("/api/sessions").retrieve().body(JsonNode.class);
        } catch (Exception e) {
            String why = WhatsAppSender.failureReason(e);
            log.warn("gateway probe failed: {}", why);
            return new Status(true, false, sessionName, null, null, null, java.util.List.of(), why, Instant.now());
        }
        String id = findSessionId(sessions, sessionName);
        JsonNode detail = null;
        JsonNode hooks = null;
        if (id != null) {
            try {
                detail = client.get().uri("/api/sessions/{id}", id).retrieve().body(JsonNode.class);
            } catch (Exception e) {
                log.warn("gateway session detail failed: {}", WhatsAppSender.failureReason(e));
            }
            try {
                hooks = client.get().uri("/api/webhooks/{id}", id).retrieve().body(JsonNode.class);
            } catch (Exception e) {
                log.warn("gateway webhook list failed: {}", WhatsAppSender.failureReason(e));
            }
        }
        return interpret(sessionName, sessions, detail, hooks, Instant.now());
    }

    /** Pure: the three gateway answers (any may be null) to a status. Unit-tested. */
    static Status interpret(String sessionName, JsonNode sessions, JsonNode detail, JsonNode hooks, Instant now) {
        String id = findSessionId(sessions, sessionName);
        if (id == null) {
            return new Status(true, true, sessionName, null, null, null, java.util.List.of(), "session_name_not_found", now);
        }
        String status = null;
        JsonNode s = sessionNode(sessions, id);
        if (s != null) status = text(s, "status");
        String number = null;
        JsonNode d = detail == null ? null : detail.path("data");
        if (d != null && d.isObject()) {
            String live = text(d, "status");
            if (live != null) status = live;
            number = numberFromJid(text(d.path("me"), "id"));
        }
        Integer count = null;
        java.util.List<String> targets = new java.util.ArrayList<>();
        JsonNode list = hooks == null ? null : hooks.path("data");
        if (list != null && list.isArray()) {
            int n = 0;
            for (JsonNode w : list) {
                if (!w.path("isActive").asBoolean(true)) continue;
                boolean wants = false;
                for (JsonNode e : w.path("events")) {
                    String ev = e.asText();
                    if ("message.received".equals(ev) || "*".equals(ev)) wants = true;
                }
                if (!wants) continue;
                n++;
                String url = text(w, "url");
                if (url != null) {
                    int q = url.indexOf('?');
                    targets.add(q >= 0 ? url.substring(0, q) : url);
                }
            }
            count = n;
        }
        return new Status(true, true, sessionName, status, number, count, targets, null, now);
    }

    private static String findSessionId(JsonNode sessions, String name) {
        JsonNode data = sessions == null ? null : sessions.path("data");
        if (data == null || !data.isArray()) return null;
        String fallback = null;
        for (JsonNode s : data) {
            if (!name.equals(s.path("name").asText())) continue;
            String id = text(s, "sessionId");
            if ("CONNECTED".equals(s.path("status").asText())) return id;
            if (fallback == null) fallback = id;
        }
        return fallback;
    }

    private static JsonNode sessionNode(JsonNode sessions, String id) {
        for (JsonNode s : sessions.path("data")) if (id.equals(s.path("sessionId").asText())) return s;
        return null;
    }

    /** "919999900001:12@s.whatsapp.net" -> "+919999900001"; null when not a phone jid. */
    static String numberFromJid(String jid) {
        if (jid == null) return null;
        int at = jid.indexOf('@');
        String user = at > 0 ? jid.substring(0, at) : jid;
        int colon = user.indexOf(':');
        if (colon > 0) user = user.substring(0, colon);
        return user.matches("[1-9][0-9]{7,14}") ? "+" + user : null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
