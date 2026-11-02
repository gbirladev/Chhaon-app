package org.smartgurukul.chhaon.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Verifies Supabase-issued access tokens (HS256, signed with the project's JWT secret). The
 * frontend logs in via Supabase Auth and sends those tokens; this lets the Java backend accept
 * them. The token's `sub` claim is the auth.users id, which equals profiles.id in our schema.
 *
 * Enabled only when app.supabase.jwt-secret is set; otherwise returns null (not configured).
 */
@Service
public class SupabaseJwtVerifier {

    private final SecretKey key; // null if not configured

    public SupabaseJwtVerifier(@Value("${app.supabase.jwt-secret:}") String secret) {
        this.key = (secret == null || secret.isBlank())
                ? null
                : Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isConfigured() {
        return key != null;
    }

    /** @return claims if this is a valid Supabase token, or null if not configured/invalid. */
    public Claims tryParse(String token) {
        if (key == null) return null;
        try {
            // Signature (HS256 with the project secret) is the guarantee; issuer varies by
            // project version (iss="supabase" or the auth URL), so we don't pin it.
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return null;
        }
    }
}
