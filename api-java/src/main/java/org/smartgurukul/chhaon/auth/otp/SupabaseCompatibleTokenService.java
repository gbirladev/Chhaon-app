package org.smartgurukul.chhaon.auth.otp;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * Mints the session token returned after a successful phone-OTP login.
 *
 * <p>The token is an HS256 JWT signed with the <b>Supabase project JWT secret</b>
 * ({@code app.supabase.jwt-secret} / env {@code SUPABASE_JWT_SECRET}) and shaped like a Supabase access
 * token: {@code sub} = profiles.id, {@code role} = {@code aud} = "authenticated". This single token is
 * accepted by BOTH:
 * <ul>
 *   <li>Supabase PostgREST + Storage, so the web app's direct {@code supabase.from/rpc/storage} calls and
 *       their RLS ({@code auth.uid()} = {@code sub}) keep working without change; and</li>
 *   <li>this backend — {@link org.smartgurukul.chhaon.security.JwtAuthenticationFilter} already verifies
 *       such tokens via {@code SupabaseJwtVerifier} (HS256 + {@code role=authenticated}).</li>
 * </ul>
 * This is the one place Supabase remains in auth after GoTrue/email login are removed: the shared secret
 * is required so the data layer keeps working.
 */
@Service
public class SupabaseCompatibleTokenService {

    private static final Logger log = LoggerFactory.getLogger(SupabaseCompatibleTokenService.class);

    private final SecretKey key; // null when the secret is not configured: login fails, the rest of the API keeps serving
    private final long expirySeconds;
    private final String issuer;

    public SupabaseCompatibleTokenService(
            @Value("${app.supabase.jwt-secret:}") String secret,
            @Value("${app.supabase.token-expiry-minutes:10080}") long expiryMinutes, // 7 days (no refresh tokens yet)
            @Value("${app.supabase.token-issuer:chhaon}") String issuer) {
        // Fail SOFT at startup: a missing secret must not take the whole backend down, only phone login.
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            log.error("SUPABASE_JWT_SECRET (app.supabase.jwt-secret) is not set (or <32 bytes): phone-OTP login is DISABLED until it is configured");
            this.key = null;
        } else {
            this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
        this.expirySeconds = expiryMinutes * 60;
        this.issuer = issuer;
    }

    /** A Supabase-compatible access token for the given profile. Fails closed if the secret is not configured. */
    public String issue(UUID userId) {
        if (key == null) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Login is temporarily unavailable");
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .audience().add("authenticated").and()
                .claim("role", "authenticated")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .signWith(key, Jwts.SIG.HS256)   // Supabase verifies its legacy secret as HS256; never let the key length pick HS384/512
                .compact();
    }
}
