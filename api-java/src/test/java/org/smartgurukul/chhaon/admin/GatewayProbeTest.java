package org.smartgurukul.chhaon.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** How the gateway's answers are read (no network): connected + webhook, logged out, and the "no webhook" incident. */
class GatewayProbeTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode j(String s) throws Exception {
        return json.readTree(s);
    }

    private static final String SESSIONS = "{\"data\":[{\"name\":\"Other\",\"sessionId\":\"x1\",\"status\":\"CONNECTED\"}," +
            "{\"name\":\"Chhaon\",\"sessionId\":\"s1\",\"status\":\"CONNECTED\"}]}";

    @Test
    void connectedWithAWebhookForIncomingMessages() throws Exception {
        GatewayProbe.Status s = GatewayProbe.interpret("Chhaon", j(SESSIONS),
                j("{\"data\":{\"status\":\"CONNECTED\",\"me\":{\"id\":\"919999900009:12@s.whatsapp.net\"}}}"),
                j("{\"data\":[{\"url\":\"https://api.example.test/webhooks/whatsapp-gateway?x=1\",\"secret\":\"never-kept\"," +
                  "\"isActive\":true,\"events\":[\"message.received\",\"message.status\"]}," +
                  "{\"url\":\"https://other.example.test/\",\"isActive\":false,\"events\":[\"*\"]}]}"),
                Instant.now());
        assertThat(s.sessionStatus()).isEqualTo("CONNECTED");
        assertThat(s.number()).isEqualTo("+919999900009");
        assertThat(s.webhooks()).isEqualTo(1);
        assertThat(s.webhookTargets()).containsExactly("https://api.example.test/webhooks/whatsapp-gateway");
        assertThat(s.toString()).doesNotContain("never-kept");
    }

    @Test
    void theLiveStatusWinsAndALoggedOutSessionIsSeen() throws Exception {
        GatewayProbe.Status s = GatewayProbe.interpret("Chhaon", j(SESSIONS),
                j("{\"data\":{\"status\":\"DISCONNECTED\",\"me\":null}}"), j("{\"data\":[]}"), Instant.now());
        assertThat(s.sessionStatus()).isEqualTo("DISCONNECTED");
        assertThat(s.webhooks()).isZero();
    }

    @Test
    void aNumberChangeWithoutAWebhookShowsZeroWebhooks() throws Exception {
        GatewayProbe.Status s = GatewayProbe.interpret("Chhaon", j(SESSIONS),
                j("{\"data\":{\"status\":\"CONNECTED\"}}"),
                j("{\"data\":[{\"url\":\"https://x.test/\",\"isActive\":true,\"events\":[\"message.status\"]}]}"), Instant.now());
        assertThat(s.webhooks()).isZero();
    }

    @Test
    void anUnknownSessionNameIsReported() throws Exception {
        GatewayProbe.Status s = GatewayProbe.interpret("Missing", j(SESSIONS), null, null, Instant.now());
        assertThat(s.error()).isEqualTo("session_name_not_found");
        assertThat(GatewayProbe.numberFromJid("12345@lid")).isNull();
    }

    @Test
    void refusedWebhookStatusesMapToReasons() {
        assertThat(WebhookRejectionRecorder.reason(401)).isEqualTo("bad_signature");
        assertThat(WebhookRejectionRecorder.reason(503)).isEqualTo("no_secret");
        assertThat(WebhookRejectionRecorder.reason(500)).isEqualTo("server_error");
        assertThat(WebhookRejectionRecorder.reason(413)).isEqualTo("bad_request");
        assertThat(WebhookRejectionRecorder.reason(200)).isNull();
    }
}
