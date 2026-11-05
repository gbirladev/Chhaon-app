package org.smartgurukul.chhaon.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
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

    /** app.whatsapp.polls-enabled (env WHATSAPP_POLLS_ENABLED): dose reminders and the check-in as polls. Off by default. */
    private volatile boolean pollsEnabled;
    /** Set when the gateway accepted a poll but returned no message id (an older gateway): polls stay off until a restart. */
    private volatile boolean pollsUnsupported;
    private final java.util.concurrent.atomic.AtomicBoolean pollFailureLogged = new java.util.concurrent.atomic.AtomicBoolean();

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

    @org.springframework.beans.factory.annotation.Autowired
    public void setPollsEnabled(@Value("${app.whatsapp.polls-enabled:false}") boolean pollsEnabled) {
        this.pollsEnabled = pollsEnabled;
        this.pollsUnsupported = false;
        if (pollsEnabled) log.info("WhatsApp polls are ON: dose reminders and check-ins are sent as polls (text when a poll cannot be sent)");
    }

    /** True while reminders should be tried as polls. */
    public boolean pollsOn() {
        return pollsEnabled && !pollsUnsupported;
    }

    /**
     * Sends {@code poll} when polls are on and returns its message id; returns {@code null} when it was NOT sent as a poll the
     * caller can track (polls off, the call failed, or the gateway gave no message id), so the caller sends the plain text
     * reminder instead. Never throws. A failure is logged once per process (with a safe reason, never the number).
     * No message id although the gateway said OK = an older gateway: the parent DID get that poll (and now also the text), and
     * polls are switched off until a restart so this happens only once.
     */
    public String trySendPoll(String toPhone, ReminderTexts.Poll poll) {
        if (!pollsOn() || poll == null) return null;
        try {
            String id = sendPoll(toPhone, poll.question(), poll.options());
            if (id != null) return id;
            pollsUnsupported = true;
            log.warn("the WhatsApp gateway sent a poll but returned no messageId (old gateway?): votes could not be matched, so polls "
                    + "are OFF until the backend restarts and reminders go out as text; update the gateway");
            return null;
        } catch (Exception | LinkageError e) {
            if (pollFailureLogged.compareAndSet(false, true)) {
                log.warn("poll to {} not sent ({}), sending the text instead (further poll failures are not logged)",
                        mask(toPhone), failureReason(e));
            }
            return null;
        }
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

    /**
     * Send a JPEG with a caption (the gateway's media route: multipart file + type=image + caption). Returns the provider message
     * id, like {@link #send}. Throws on any failure; callers that must not lose the message use {@link #sendReminder}.
     */
    public String sendImage(String toPhone, byte[] jpeg, String caption) {
        if (jpeg == null || jpeg.length == 0) throw new IllegalArgumentException("no image");
        if (client == null) {
            log.info("[WA dry-run] image ({} bytes) to={} caption={} (gateway not configured)", jpeg.length, mask(toPhone), caption);
            return "dryrun-" + UUID.randomUUID();
        }
        String jid = toJid(toPhone);
        String sessionId = resolveSessionId();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.IMAGE_JPEG);
        HttpHeaders textHeaders = new HttpHeaders();
        textHeaders.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));   // Hindi and emoji intact
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new HttpEntity<>(new ByteArrayResource(jpeg) {
            @Override
            public String getFilename() {
                return "medicine.jpg";
            }
        }, fileHeaders));
        form.add("type", new HttpEntity<>("image", textHeaders));
        form.add("caption", new HttpEntity<>(caption == null ? "" : caption, textHeaders));
        JsonNode resp = client.post()
                .uri("/api/messages/{session}/{jid}/media", sessionId, jid)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        if (resp == null || !resp.path("status").asBoolean(false)) {
            String why = resp == null ? "no answer" : resp.path("message").asText(resp.path("error").asText("no reason given"));
            throw new IllegalStateException("WhatsApp gateway send failed: " + why);
        }
        String id = resp.path("data").path("key").path("id").asText("");
        // Without the id a reply (a swipe-reply to this message) could not be matched to the reminder: treat it as failed.
        if (id.isBlank()) throw new IllegalStateException("WhatsApp gateway send failed: no message id");
        return id;
    }

    /**
     * A dose reminder: with the medicine photo as an image (the text as its caption) when there is one, else plain text.
     * ANY failure of the photo send falls back to the plain text at once, so a reminder is never lost because of a photo.
     * Throws only when the plain text also fails (the caller then hands the dose back for the next tick).
     */
    public String sendReminder(String toPhone, String text, byte[] photoJpeg) {
        if (photoJpeg != null && photoJpeg.length > 0) {
            try {
                return sendImage(toPhone, photoJpeg, text);
            } catch (Exception | LinkageError e) {   // LinkageError: a missing library must not cost the reminder either
                log.warn("photo reminder to {} not sent ({}), sending the plain text reminder instead", mask(toPhone), failureReason(e));
            }
        }
        return send(toPhone, text);
    }

    /**
     * Send a one-choice WhatsApp poll (the gateway's poll route, body {@code {question, options, selectableCount: 1}}). Returns the
     * poll's message key id (the gateway's top-level {@code messageId}, or {@code data.key.id}), which is what a later
     * {@code poll.vote} carries as {@code pollKeyId}. Returns {@code null} when the gateway accepted the poll but gave no id (an
     * older gateway): the poll WAS delivered, but its votes could never be matched. Throws on any failure.
     */
    public String sendPoll(String toPhone, String question, java.util.List<String> options) {
        if (client == null) {
            log.info("[WA dry-run] poll to={} question={} options={} (gateway not configured)", mask(toPhone), question, options);
            return "dryrun-poll-" + UUID.randomUUID();
        }
        String jid = toJid(toPhone);
        String sessionId = resolveSessionId();
        JsonNode resp = client.post()
                .uri("/api/messages/{session}/{jid}/poll", sessionId, jid)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", question, "options", options, "selectableCount", 1))
                .retrieve()
                .body(JsonNode.class);
        if (resp == null || !resp.path("status").asBoolean(false)) {
            cachedSessionId = null;
            String why = resp == null ? "no answer" : resp.path("message").asText(resp.path("error").asText("no reason given"));
            throw new IllegalStateException("WhatsApp gateway send failed: " + why);
        }
        String id = resp.path("messageId").asText("");
        if (id.isBlank()) id = resp.path("data").path("messageId").asText("");
        if (id.isBlank()) id = resp.path("data").path("key").path("id").asText("");
        return id.isBlank() ? null : id;
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
