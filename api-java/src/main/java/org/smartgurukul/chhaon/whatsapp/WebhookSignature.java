package org.smartgurukul.chhaon.whatsapp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies the gateway's webhook signature: header {@code X-Webhook-Signature: sha256=<hex>} where hex is the
 * HMAC-SHA256 of the RAW request body with the webhook secret (whatsapp-gateway/src/lib/webhook.ts).
 * Fails closed: no secret, no header, wrong format or wrong value all mean "not verified".
 */
public final class WebhookSignature {

    private static final String PREFIX = "sha256=";

    private WebhookSignature() {}

    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static boolean verify(String secret, byte[] body, String header) {
        if (secret == null || secret.isBlank() || body == null || header == null) return false;
        String expected = sign(secret, body);
        // Constant-time compare of the full strings (lower-casing the received hex only).
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = header.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
