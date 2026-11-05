package org.smartgurukul.chhaon.admin;

import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * The one gate in front of every {@code /api/v1/admin/*} Monitor endpoint: the caller must be signed in AND their profile must
 * carry {@code profiles.is_admin} (set by hand in the SQL editor; nobody can set it for themselves). The database flag decides,
 * not the token's {@code admin} claim: a claim minted before the flag was removed would otherwise keep working until it expires,
 * and Supabase-issued tokens carry no claim at all.
 */
@Component
public class AdminGuard {

    private final JdbcTemplate jdbc;

    public AdminGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the admin's id; 401 without a user, 403 for everyone who is not an admin. */
    public UUID require(AuthenticatedUser user) {
        if (user == null) throw new ResponseStatusException(UNAUTHORIZED, "Sign in first");
        Boolean admin = jdbc.query("select is_admin from profiles where id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, user.id());
        if (!Boolean.TRUE.equals(admin)) throw new ResponseStatusException(FORBIDDEN, "Admin only");
        return user.id();
    }
}
