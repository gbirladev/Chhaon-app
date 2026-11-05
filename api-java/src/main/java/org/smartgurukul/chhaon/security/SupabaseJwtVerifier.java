package org.smartgurukul.chhaon.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Verifies Supabase-issued access tokens. The frontend logs in via Supabase Auth and sends those tokens; this lets the Java backend
 * accept them. The token's {@code sub} claim is the auth.users id, which equals profiles.id in our schema.
 *
 * <p>Two signing styles exist and both are supported:
 * <ul>
 *   <li><b>ES256</b> (current Supabase projects, including the Sydney one): checked against the project's PUBLIC keys published at
 *       {@code app.supabase.jwks-url} (no secret needed). The keys are cached for an hour; an unknown key id triggers one refresh
 *       (at most once a minute).</li>
 *   <li><b>HS256</b> (older projects): checked with the shared secret {@code app.supabase.jwt-secret}, when one is set.</li>
 * </ul>
 * The algorithm in the token only selects between these two; the key type must match (so an HS256 token can never be checked
 * against a public key). Only tokens of signed-in users are accepted: a subject and {@code role=authenticated} are required, so the
 * public "anon" token is never treated as a person.
 */
@Service
public class SupabaseJwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(SupabaseJwtVerifier.class);
    private static final long KEYS_TTL_MS = 3_600_000L;
    private static final long MIN_REFRESH_GAP_MS = 60_000L;

    private final SecretKey hmacKey;   // null if no shared secret
    private final String jwksUrl;      // blank = ES256 not configured
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile long fetchedAt;
    private volatile long lastAttemptAt;

    public SupabaseJwtVerifier(@Value("${app.supabase.jwt-secret:}") String secret,
                               @Value("${app.supabase.jwks-url:}") String jwksUrl) {
        this.hmacKey = (secret == null || secret.isBlank())
                ? null
                : Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.jwksUrl = jwksUrl == null ? "" : jwksUrl.trim();
    }

    public boolean isConfigured() {
        return hmacKey != null || !jwksUrl.isBlank();
    }

    /** @return claims if this is a valid token of a signed-in Supabase user, or null if not configured / invalid. */
    public Claims tryParse(String token) {
        if (!isConfigured()) return null;
        try {
            // The signature is the guarantee; the issuer varies by project version, so it is not pinned.
            Claims c = Jwts.parser()
                    .keyLocator(new LocatorAdapter<Key>() {
                        @Override
                        protected Key locate(JwsHeader header) {
                            String alg = header.getAlgorithm();
                            if ("ES256".equals(alg)) return publicKey(header.getKeyId());
                            if ("HS256".equals(alg)) return hmacKey;   // null (not configured) makes the token fail
                            return null;
                        }
                    })
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (c.getSubject() == null || !"authenticated".equals(c.get("role", String.class))) return null;
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private PublicKey publicKey(String kid) {
        if (jwksUrl.isBlank() || kid == null) return null;
        long now = System.currentTimeMillis();
        PublicKey k = keys.get(kid);
        if (k != null && now - fetchedAt < KEYS_TTL_MS) return k;
        refresh(now);
        return keys.get(kid);
    }

    private synchronized void refresh(long now) {
        if (now - lastAttemptAt < MIN_REFRESH_GAP_MS) return;   // never hammer the key server
        lastAttemptAt = now;
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(jwksUrl)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                log.warn("supabase signing keys could not be loaded (HTTP {})", r.statusCode());
                return;
            }
            Map<String, PublicKey> fresh = new HashMap<>();
            for (JsonNode jwk : json.readTree(r.body()).path("keys")) {
                if (!"EC".equals(jwk.path("kty").asText()) || !"P-256".equals(jwk.path("crv").asText())) continue;
                String kid = jwk.path("kid").asText(null);
                if (kid == null) continue;
                fresh.put(kid, toPublicKey(jwk.path("x").asText(), jwk.path("y").asText()));
            }
            if (!fresh.isEmpty()) {
                keys = Map.copyOf(fresh);
                fetchedAt = now;
            }
        } catch (Exception e) {
            log.warn("supabase signing keys could not be loaded ({})", e.getClass().getSimpleName());
        }
    }

    private static PublicKey toPublicKey(String x, String y) throws Exception {
        Base64.Decoder b64 = Base64.getUrlDecoder();
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(new BigInteger(1, b64.decode(x)), new BigInteger(1, b64.decode(y)));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
    }
}
