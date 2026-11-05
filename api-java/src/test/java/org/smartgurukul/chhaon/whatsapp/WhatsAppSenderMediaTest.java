package org.smartgurukul.chhaon.whatsapp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The photo reminder against a stand-in gateway on localhost (never the real one): the multipart form the gateway's media
 * route reads (file, type=image, caption), the message id it returns, and the plain text fallback.
 */
class WhatsAppSenderMediaTest {

    record Call(String path, String contentType, byte[] body) {}

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile int mediaStatus = 200;
    private volatile String mediaAnswer = "{\"status\":true,\"message\":\"Operation successful\",\"data\":{\"key\":{\"id\":\"IMG-1\"}}}";
    private WhatsAppSender sender;

    private static final String PHONE = "+919000000123";   // fake number
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};

    @BeforeEach
    void startGateway() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/sessions", ex -> answer(ex, 200,
                "{\"status\":true,\"data\":[{\"name\":\"Chhaon\",\"sessionId\":\"sess1\",\"status\":\"CONNECTED\"}]}"));
        server.createContext("/api/messages/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            String path = ex.getRequestURI().getPath();
            calls.add(new Call(path, ex.getRequestHeaders().getFirst("Content-Type"), body));
            if (path.endsWith("/media")) answer(ex, mediaStatus, mediaAnswer);
            else answer(ex, 200, "{\"status\":true,\"data\":{\"key\":{\"id\":\"TXT-1\"}}}");
        });
        server.start();
        sender = new WhatsAppSender("http://127.0.0.1:" + server.getAddress().getPort(), "wag_test_key", "Chhaon");
    }

    @AfterEach
    void stopGateway() {
        server.stop(0);
    }

    private static void answer(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    void thePhotoGoesAsAMultipartImageWithTheCaption_andTheIdComesBack() {
        String caption = "नमस्ते पापा 🙏\nदवा का समय 💊 *Metformin*";

        String id = sender.sendReminder(PHONE, caption, JPEG);

        assertThat(id).isEqualTo("IMG-1");
        assertThat(calls).hasSize(1);
        Call c = calls.get(0);
        assertThat(c.path()).isEqualTo("/api/messages/sess1/919000000123@s.whatsapp.net/media");
        assertThat(c.contentType()).startsWith("multipart/form-data");
        String form = new String(c.body(), StandardCharsets.UTF_8);
        assertThat(form).contains("name=\"file\"; filename=\"medicine.jpg\"").contains("Content-Type: image/jpeg")
                .contains("name=\"type\"").contains("\r\n\r\nimage\r\n")
                .contains("name=\"caption\"").contains(caption);   // Hindi and emoji arrive intact (UTF-8)
    }

    @Test
    void theGatewayRefusingMedia_sendsThePlainTextInstead() {
        mediaStatus = 500;
        mediaAnswer = "{\"status\":false,\"message\":\"Failed to send media\"}";

        String id = sender.sendReminder(PHONE, "Namaste Papa", JPEG);

        assertThat(id).isEqualTo("TXT-1");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1).path()).endsWith("/send");
        assertThat(new String(calls.get(1).body(), StandardCharsets.UTF_8)).contains("Namaste Papa");
    }

    @Test
    void aMediaAnswerWithoutAMessageId_sendsThePlainTextInstead() {
        mediaAnswer = "{\"status\":true,\"data\":{}}";   // no id: a reply could never be matched

        assertThat(sender.sendReminder(PHONE, "Namaste Papa", JPEG)).isEqualTo("TXT-1");
    }

    @Test
    void noPhoto_sendsTextOnly() {
        assertThat(sender.sendReminder(PHONE, "Namaste Papa", null)).isEqualTo("TXT-1");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).path()).endsWith("/send");
    }

    @Test
    void sendImageOnItsOwnThrowsWhenRefused() {
        mediaStatus = 200;
        mediaAnswer = "{\"status\":false,\"message\":\"Failed to send media\"}";
        assertThatThrownBy(() -> sender.sendImage(PHONE, JPEG, "x"))
                .satisfies(e -> assertThat(WhatsAppSender.failureReason(e)).startsWith("gateway_refused"));
    }
}
