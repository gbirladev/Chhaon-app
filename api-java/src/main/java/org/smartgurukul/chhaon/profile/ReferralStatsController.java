package org.smartgurukul.chhaon.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartgurukul.chhaon.admin.AdminGuard;
import org.smartgurukul.chhaon.db.UserScopedSql;
import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

/**
 * "Which doctor brought each family" for the admin's Pilot stats: sign-ups per doctor code and per "How did you hear about
 * Chhaon?" answer (SQL admin_referral_stats(), migration 0058). Counts only: no names, phones or emails. Admins only
 * ({@link AdminGuard}, and the SQL function checks is_admin again). Nothing here is ever shown to a doctor.
 */
@RestController
public class ReferralStatsController {

    private final AdminGuard guard;
    private final UserScopedSql sql;
    private final ObjectMapper json;

    public ReferralStatsController(AdminGuard guard, UserScopedSql sql, ObjectMapper json) {
        this.guard = guard;
        this.sql = sql;
        this.json = json;
    }

    @GetMapping("/api/v1/admin/referrals")
    public JsonNode referrals(@AuthenticationPrincipal AuthenticatedUser user) {
        UUID adminId = guard.require(user);
        String text = sql.asUser(adminId, jdbc -> jdbc.queryForObject("select admin_referral_stats()::text", String.class));
        try {
            return json.readTree(text == null ? "null" : text);
        } catch (Exception e) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Could not read the counts");
        }
    }
}
