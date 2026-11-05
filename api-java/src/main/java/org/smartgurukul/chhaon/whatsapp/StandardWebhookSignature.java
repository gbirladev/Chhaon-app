package org.smartgurukul.chhaon.whatsapp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Verifies a Supabase Auth hook call, which uses the <a href="https://www.standardwebhooks.com">Standard Webhooks</a>
 * scheme. The request carries three headers:
 * <ul>
 *   <li>{@code webhook-id}</li>
 *   <li>{@code webhook-timestamp} (unix seconds)</li>
 *   <li>{@code webhook-signature}: one or more space-separated {@code v1,<base64>} values</li>
 * </ul>
 * The signed content is {@code "<id>.<timestamp>.<rawBody>"}; the signature is the base64 HMAC-SHA256 of that content
 * keyed by the symmetric secret. Supabase shows the secret as {@code v1,whsec_<base64>}; the real key is the base64
 * decode of the part after {@code whsec_}.
 *
 * <p>Fails closed: a blank secret, a missing header, a malformed secret, or a wrong value all return {@code false}.
 */
public final class StandardWebhookSignature {

    private StandardWebhookSignature() {}

    /** The raw HMAC key from a configured secret ("v1,whsec_<b64>" / "whsec_<b64>" / "<b64>"), or null if unusable. */
    static byte[] keyFrom(String secret) {
        if (secret == null || secret.isBlank()) return null;
        String b64 = secret.trim();
        if (b64.startsWith("v1,")) b64 = b64.substring(3);
        if (b64.startsWith("whsec_")) b64 = b64.substring("whsec_".length());
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean verify(String secret, String id, String timestamp, byte[] body, String signatureHeader) {
        byte[] key = keyFrom(secret);
        if (key == null || id == null || timestamp == null || body == null || signatureHeader == null) return false;

        String signed = id + "." + timestamp + "." + new String(body, StandardCharsets.UTF_8);
        String expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            expected = Base64.getEncoder().encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }

        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        // The header may list several signatures (key rotation); accept if any matches. Constant-time per candidate.
        for (String part : signatureHeader.trim().split("\\s+")) {
            int comma = part.indexOf(',');
            String sig = comma >= 0 ? part.substring(comma + 1) : part;
            if (MessageDigest.isEqual(expectedBytes, sig.getBytes(StandardCharsets.UTF_8))) return true;
        }
        return false;
    }
}
