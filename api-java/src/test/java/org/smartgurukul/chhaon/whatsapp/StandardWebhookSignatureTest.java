package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests (no database) for the Supabase / Standard-Webhooks signature check. */
class StandardWebhookSignatureTest {

    private static final byte[] KEY = "a-raw-32-byte-symmetric-key-abcde".getBytes(StandardCharsets.UTF_8);
    // How Supabase presents the secret in the dashboard.
    private static final String SECRET = "v1,whsec_" + Base64.getEncoder().encodeToString(KEY);
    private static final String ID = "msg_2abc";
    private static final String TS = "1700000000";
    private static final byte[] BODY = "{\"user\":{\"phone\":\"919999999999\"},\"sms\":{\"otp\":\"123456\"}}".getBytes(StandardCharsets.UTF_8);

    private static String sign(byte[] key, String id, String ts, byte[] body) throws Exception {
        String signed = id + "." + ts + "." + new String(body, StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aValidSignatureIsAccepted() throws Exception {
        String header = sign(KEY, ID, TS, BODY);
        assertThat(StandardWebhookSignature.verify(SECRET, ID, TS, BODY, header)).isTrue();
    }

    @Test
    void secretWithoutTheV1OrWhsecPrefixAlsoWorks() throws Exception {
        String header = sign(KEY, ID, TS, BODY);
        assertThat(StandardWebhookSignature.verify("whsec_" + Base64.getEncoder().encodeToString(KEY), ID, TS, BODY, header)).isTrue();
        assertThat(StandardWebhookSignature.verify(Base64.getEncoder().encodeToString(KEY), ID, TS, BODY, header)).isTrue();
    }

    @Test
    void oneMatchingSignatureAmongSeveralIsEnough() throws Exception {
        String header = "v1,wrongsignaturevalue " + sign(KEY, ID, TS, BODY);
        assertThat(StandardWebhookSignature.verify(SECRET, ID, TS, BODY, header)).isTrue();
    }

    @Test
    void everythingElseIsRejected() throws Exception {
        String good = sign(KEY, ID, TS, BODY);
        assertThat(StandardWebhookSignature.verify(SECRET, ID, TS, BODY, null)).isFalse();
        assertThat(StandardWebhookSignature.verify(SECRET, ID, TS, BODY, "")).isFalse();
        assertThat(StandardWebhookSignature.verify(SECRET, ID, TS, BODY, good + "x")).isFalse();     // tampered sig
        assertThat(StandardWebhookSignature.verify(SECRET, "other-id", TS, BODY, good)).isFalse();   // tampered id
        assertThat(StandardWebhookSignature.verify(SECRET, ID, "1", BODY, good)).isFalse();          // tampered timestamp
        assertThat(StandardWebhookSignature.verify(SECRET, ID, TS, "{}".getBytes(StandardCharsets.UTF_8), good)).isFalse(); // tampered body
        assertThat(StandardWebhookSignature.verify(SECRET, null, TS, BODY, good)).isFalse();
        assertThat(StandardWebhookSignature.verify(SECRET, ID, null, BODY, good)).isFalse();
        assertThat(StandardWebhookSignature.verify("v1,whsec_" + Base64.getEncoder().encodeToString("different-key-value-padding-xx!!".getBytes(StandardCharsets.UTF_8)), ID, TS, BODY, good)).isFalse();
    }

    @Test
    void failsClosedWithoutOrWithAnUnusableSecret() throws Exception {
        String good = sign(KEY, ID, TS, BODY);
        assertThat(StandardWebhookSignature.verify(null, ID, TS, BODY, good)).isFalse();
        assertThat(StandardWebhookSignature.verify("", ID, TS, BODY, good)).isFalse();
        assertThat(StandardWebhookSignature.verify("   ", ID, TS, BODY, good)).isFalse();
        assertThat(StandardWebhookSignature.verify("v1,whsec_not valid base64!!", ID, TS, BODY, good)).isFalse();
    }
}
