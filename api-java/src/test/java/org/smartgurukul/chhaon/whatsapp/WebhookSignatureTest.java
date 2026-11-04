package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests (no database). */
class WebhookSignatureTest {

    private static final byte[] BODY = "{\"event\":\"message.received\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void anHmacSha256OfTheRawBodyIsAccepted() {
        String header = WebhookSignature.sign("s3cret", BODY);
        assertThat(header).startsWith("sha256=").hasSize(7 + 64);
        assertThat(WebhookSignature.verify("s3cret", BODY, header)).isTrue();
        assertThat(WebhookSignature.verify("s3cret", BODY, header.toUpperCase().replace("SHA256=", "sha256="))).isTrue(); // hex case does not matter
    }

    @Test
    void knownVectorMatchesNodesCryptoOutput() {
        // Node: crypto.createHmac("sha256","key").update("The quick brown fox jumps over the lazy dog").digest("hex")
        assertThat(WebhookSignature.sign("key", "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void everythingElseIsRejected() {
        String good = WebhookSignature.sign("s3cret", BODY);
        assertThat(WebhookSignature.verify("s3cret", BODY, null)).isFalse();
        assertThat(WebhookSignature.verify("s3cret", BODY, "")).isFalse();
        assertThat(WebhookSignature.verify("s3cret", BODY, good.substring(7))).isFalse();     // no prefix
        assertThat(WebhookSignature.verify("s3cret", BODY, good + "0")).isFalse();
        assertThat(WebhookSignature.verify("s3cret", BODY, good.substring(0, good.length() - 1))).isFalse();
        assertThat(WebhookSignature.verify("other", BODY, good)).isFalse();
        assertThat(WebhookSignature.verify("s3cret", "{}".getBytes(StandardCharsets.UTF_8), good)).isFalse();
        assertThat(WebhookSignature.verify("s3cret", null, good)).isFalse();
        assertThat(WebhookSignature.verify("s3cret", BODY, "sha256=ü")).isFalse();
    }

    @Test
    void failsClosedWithoutASecret() {
        assertThat(WebhookSignature.verify(null, BODY, "sha256=00")).isFalse();
        assertThat(WebhookSignature.verify("", BODY, "sha256=00")).isFalse();
        assertThat(WebhookSignature.verify("  ", BODY, "sha256=00")).isFalse();
    }
}
