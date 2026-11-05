package org.smartgurukul.chhaon.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.smartgurukul.chhaon.whatsapp.WebhookSignature;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Plays the WhatsApp gateway: builds the exact JSON shape documented in docs/java-whatsapp-replies.md (taken from
 * whatsapp-gateway/src/lib/webhook.ts), signs it with the test secret and POSTs it.
 */
public class GatewayClient {

    public static final String URL = "/webhooks/whatsapp-gateway";
    public static final String SECRET = "test-webhook-secret-not-a-real-secret";

    public record Response(int status, String body) {}

    private final MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    public GatewayClient(MockMvc mvc) {
        this.mvc = mvc;
    }

    public static String jid(String e164) {
        return e164.substring(1) + "@s.whatsapp.net";
    }

    /** A 1:1 text message from a parent. */
    public ObjectNode received(String messageId, String fromPhone, String text) {
        return received(messageId, jid(fromPhone), text, null);
    }

    public ObjectNode received(String messageId, String fromJid, String text, String quotedMessageId) {
        ObjectNode root = json.createObjectNode();
        root.put("event", "message.received");
        root.put("sessionId", "session-test");
        root.put("timestamp", "2026-11-04T08:00:00.000Z");
        ObjectNode data = root.putObject("data");
        ObjectNode key = data.putObject("key");
        key.put("id", messageId);
        key.put("remoteJid", fromJid);
        key.put("fromMe", false);
        data.put("pushName", "Papa");
        data.put("messageTimestamp", "1793779200");
        data.put("from", fromJid);
        data.put("receiver", "919000000001@s.whatsapp.net");
        data.put("sender", fromJid);
        data.put("isGroup", false);
        data.put("chatType", "PERSONAL");
        data.put("type", "TEXT");
        data.put("content", text);
        data.putNull("fileUrl");
        if (quotedMessageId == null) {
            data.putNull("quoted");
        } else {
            ObjectNode q = data.putObject("quoted");
            q.putObject("key").put("id", quotedMessageId).put("remoteJid", "919000000001@s.whatsapp.net");
            q.put("type", "TEXT");
            q.put("content", "(our earlier message)");
        }
        return root;
    }

    /**
     * A long-press reaction (gateway change F6, whatsapp-gateway/src/lib/reaction.ts): the same envelope as a text,
     * type REACTION, the emoji in content and reaction.text ("" = removed) and the reacted message's key.
     */
    public ObjectNode reaction(String messageId, String fromPhone, String emoji, String reactedMessageId) {
        ObjectNode root = received(messageId, jid(fromPhone), emoji, null);
        ObjectNode data = (ObjectNode) root.get("data");
        data.put("type", "REACTION");
        ObjectNode r = data.putObject("reaction");
        r.put("text", emoji);
        r.put("removed", emoji.isEmpty());
        r.putObject("key").put("id", reactedMessageId).put("remoteJid", jid(fromPhone)).put("fromMe", true).putNull("participant");
        return root;
    }

    public ObjectNode status(String keyId, String status) {
        ObjectNode root = json.createObjectNode();
        root.put("event", "message.status");
        root.put("sessionId", "session-test");
        root.put("timestamp", "2026-11-04T08:00:00.000Z");
        root.putObject("data").put("keyId", keyId).put("remoteJid", "919810000000@s.whatsapp.net").put("status", status);
        return root;
    }

    public Response post(ObjectNode payload) throws Exception {
        byte[] body = json.writeValueAsBytes(payload);
        return post(body, WebhookSignature.sign(SECRET, body));
    }

    public Response post(byte[] body, String signatureHeader) throws Exception {
        var req = MockMvcRequestBuilders.post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
        if (signatureHeader != null) req = req.header("X-Webhook-Signature", signatureHeader);
        MvcResult r = mvc.perform(req).andReturn();
        return new Response(r.getResponse().getStatus(), r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    public ObjectMapper mapper() {
        return json;
    }
}
