package org.smartgurukul.chhaon.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Validates the Bearer JWT on each request and sets the security context. Stateless; no session.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SupabaseJwtVerifier supabaseVerifier;

    public JwtAuthenticationFilter(JwtService jwtService, SupabaseJwtVerifier supabaseVerifier) {
        this.jwtService = jwtService;
        this.supabaseVerifier = supabaseVerifier;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                boolean admin;
                UUID userId;
                Claims own = tryOwn(token);
                if (own != null) {
                    userId = UUID.fromString(own.getSubject());
                    admin = Boolean.TRUE.equals(own.get("admin", Boolean.class));
                } else {
                    // Fall back to a Supabase-issued access token (frontend logs in via Supabase).
                    Claims sb = supabaseVerifier.tryParse(token);
                    if (sb == null) throw new io.jsonwebtoken.JwtException("unrecognized token");
                    userId = UUID.fromString(sb.getSubject());
                    admin = false; // Supabase tokens carry no app admin claim
                }
                var authorities = admin
                        ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                                  new SimpleGrantedAuthority("ROLE_USER"))
                        : List.of(new SimpleGrantedAuthority("ROLE_USER"));
                var principal = new AuthenticatedUser(userId, admin);
                var authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ex) {
                // Invalid/expired token -> leave the context unauthenticated; entry point 401s.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    /** Parse as one of our own tokens, or null if it isn't one / is invalid. */
    private Claims tryOwn(String token) {
        try {
            return jwtService.parse(token);
        } catch (Exception e) {
            return null;
        }
    }
}
