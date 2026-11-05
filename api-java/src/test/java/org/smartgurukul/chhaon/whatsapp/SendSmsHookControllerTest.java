package org.smartgurukul.chhaon.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.support.RecordingWhatsAppSender;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests (no Spring, no database): the controller is driven directly. Proves it fails closed on the signature
 * and delivers the code over WhatsApp otherwise.
 */
class SendSmsHookControllerTest {

    private static final byte[] KEY = "a-raw-32-byte-symmetric-key-abcde".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "v1,whsec_" + Base64.getEncoder().encodeToString(KEY);
    private static final String ID = "msg_42";
    private static final String TS = "1700000000";
    private static final byte[] BODY = "{\"user\":{\"phone\":\"919876500000\"},\"sms\":{\"otp\":\"654321\"}}".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper json = new ObjectMapper();
    private RecordingWhatsAppSender sender;

    @BeforeEach
    void setUp() {
        sender = new RecordingWhatsAppSender();
    }

    private static String sign(byte[] key, String id, String ts, byte[] body) throws Exception {
        String signed = id + "." + ts + "." + new String(body, StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
    }

    private SendSmsHookController controller(String secret) {
        return new SendSmsHookController(secret, json, sender);
    }

    @Test
    void deliversTheCodeOverWhatsAppWhenTheSignatureIsValid() throws Exception {
        ResponseEntity<Map<String, Object>> resp =
                controller(SECRET).receive(BODY, ID, TS, sign(KEY, ID, TS, BODY));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sender.sentTo("919876500000")).hasSize(1);
        assertThat(sender.sentTo("919876500000").get(0).text()).contains("654321");
    }

    @Test
    void refusesWith503WhenNoSecretIsConfigured() throws Exception {
        ResponseEntity<Map<String, Object>> resp =
                controller("").receive(BODY, ID, TS, sign(KEY, ID, TS, BODY));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void refusesWith401OnMissingOrWrongSignature() {
        assertThat(controller(SECRET).receive(BODY, ID, TS, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller(SECRET).receive(BODY, ID, TS, "v1,deadbeef").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void refusesWith400WhenPhoneOrOtpIsMissing() throws Exception {
        byte[] noOtp = "{\"user\":{\"phone\":\"919876500000\"},\"sms\":{}}".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<Map<String, Object>> resp =
                controller(SECRET).receive(noOtp, ID, TS, sign(KEY, ID, TS, noOtp));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void reportsDeliveryFailureAs500() throws Exception {
        sender.failSends(true);
        ResponseEntity<Map<String, Object>> resp =
                controller(SECRET).receive(BODY, ID, TS, sign(KEY, ID, TS, BODY));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
