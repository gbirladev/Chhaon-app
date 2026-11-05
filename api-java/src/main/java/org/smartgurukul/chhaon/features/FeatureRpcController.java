package org.smartgurukul.chhaon.features;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartgurukul.chhaon.db.UserScopedSql;
import org.smartgurukul.chhaon.medicine.MedicineRepository;
import org.smartgurukul.chhaon.recipient.CareRecipientRepository;
import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Thin pass-throughs to the EXISTING SQL functions the web app used to call through Supabase RPC
 * (emergency links, family invites, plans, love loop, escalation, admin stats, scheduler health).
 * Each runs through {@link UserScopedSql#asUser}, which reproduces PostgREST's environment
 * (auth.uid() + Row Level Security), so the database's own rules keep deciding everything — the Java
 * side only authenticates and relays. PG error hints are surfaced in the error detail because the web
 * client's wording keys off them.
 */
@RestController
@RequestMapping("/api/v1")
public class FeatureRpcController {

    public record EmergencyLink(String token, OffsetDateTime createdAt, OffsetDateTime lastViewedAt, int viewCount) {}
    public record TokenBody(String token) {}
    public record EnabledBody(Boolean enabled) {}
    public record CriticalBody(Boolean critical) {}
    public record ReactionBody(String sourceType, UUID sourceId, String type, String emoji, String text) {}

    private final UserScopedSql db;
    private final ObjectMapper json;
    private final CareRecipientRepository recipients;
    private final MedicineRepository medicines;

    public FeatureRpcController(UserScopedSql db, ObjectMapper json,
                                CareRecipientRepository recipients, MedicineRepository medicines) {
        this.db = db;
        this.json = json;
        this.recipients = recipients;
        this.medicines = medicines;
    }

    // ---------------------------------------------------------------- shared helpers

    private void requireMember(AuthenticatedUser user, UUID recipientId) {
        recipients.findForUser(recipientId, user.id())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Parent profile not found"));
    }

    /** Run `select fn(...)` as the user and hand the jsonb result through untouched. */
    private JsonNode jsonCall(UUID userId, String sql, Object... args) {
        String text = db.asUser(userId, jdbc -> jdbc.queryForObject(sql, String.class, args));
        try {
            return json.readTree(text == null ? "null" : text);
        } catch (Exception e) {
            throw new ResponseStatusException(BAD_REQUEST, "Unexpected answer from the database");
        }
    }

    /** Map a refused SQL call onto a response the web client's copy understands (hint first). */
    private ResponseStatusException refused(DataAccessException e) {
        String state = PgErrors.sqlState(e);
        if ("23505".equals(state)) return new ResponseStatusException(CONFLICT, "already");
        String hint = PgErrors.hint(e);
        if (hint != null) return new ResponseStatusException(BAD_REQUEST, hint);
        // Keep the raise message's tag words (love_off, love_not_available, ...) without echoing row data.
        String msg = PgErrors.rootMessage(e);
        return new ResponseStatusException(BAD_REQUEST, msg.length() > 160 ? msg.substring(0, 160) : msg);
    }

    private static OffsetDateTime odt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------- emergency links

    private static final String LINK_SELECT =
            "select token, created_at, last_viewed_at, view_count from ";

    private EmergencyLink mapLink(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EmergencyLink(rs.getString(1), odt(rs.getTimestamp(2)), odt(rs.getTimestamp(3)), rs.getInt(4));
    }

    @GetMapping("/recipients/{id}/emergency-link")
    public org.springframework.http.ResponseEntity<EmergencyLink> emergencyLink(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        requireMember(user, id);
        List<EmergencyLink> rows = db.asUser(user.id(), jdbc -> jdbc.query(
                LINK_SELECT + "emergency_links where recipient_id = ? and active", (rs, i) -> mapLink(rs), id));
        // 204 when the link is off, so the web client gets a clean "none" instead of an empty 200 body.
        return rows.isEmpty() ? org.springframework.http.ResponseEntity.noContent().build()
                              : org.springframework.http.ResponseEntity.ok(rows.get(0));
    }

    @PostMapping("/recipients/{id}/emergency-link/enable")
    public EmergencyLink enableLink(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        requireMember(user, id);
        try {
            List<EmergencyLink> rows = db.asUser(user.id(), jdbc -> jdbc.query(
                    LINK_SELECT + "enable_emergency_link(?)", (rs, i) -> mapLink(rs), id));
            if (rows.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "No link was made");
            return rows.get(0);
        } catch (DataAccessException e) {
            throw refused(e);
        }
    }

    @PostMapping("/recipients/{id}/emergency-link/rotate")
    public EmergencyLink rotateLink(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        requireMember(user, id);
        try {
            List<EmergencyLink> rows = db.asUser(user.id(), jdbc -> jdbc.query(
                    LINK_SELECT + "rotate_emergency_link(?)", (rs, i) -> mapLink(rs), id));
            if (rows.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "No link was made");
            return rows.get(0);
        } catch (DataAccessException e) {
            throw refused(e);
        }
    }

    @PostMapping("/recipients/{id}/emergency-link/revoke")
    public Map<String, Object> revokeLink(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        requireMember(user, id);
        try {
            db.asUser(user.id(), jdbc -> { jdbc.queryForObject("select revoke_emergency_link(?)::text", String.class, id); return null; });
        } catch (DataAccessException e) {
            throw refused(e);
        }
        return Map.of("ok", Boolean.TRUE);
    }

    // ---------------------------------------------------------------- family invites

    @PostMapping("/invites/preview")
    public JsonNode previewInvite(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody TokenBody body) {
        return jsonCall(user.id(), "select preview_family_invite(?)::text", body.token());
    }

    @PostMapping("/invites/accept")
    public Map<String, Object> acceptInvite(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody TokenBody body) {
        try {
            String recipientId = db.asUser(user.id(),
                    jdbc -> jdbc.queryForObject("select accept_family_invite(?)::text", String.class, body.token()));
            return Map.of("recipientId", String.valueOf(recipientId));
        } catch (DataAccessException e) {
            throw refused(e);
        }
    }

    @PostMapping("/circles/{circleId}/invites")
    public Map<String, Object> createInvite(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID circleId) {
        try {
            String token = db.asUser(user.id(),
                    jdbc -> jdbc.queryForObject("select create_family_invite(?)", String.class, circleId));
            return Map.of("token", String.valueOf(token));
        } catch (DataAccessException e) {
            throw refused(e);
        }
    }

    @GetMapping("/circles/{circleId}/invites")
    public List<Map<String, Object>> pendingInvites(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID circleId) {
        return db.asUser(user.id(), jdbc -> jdbc.query("""
                        select i.id, i.created_at, i.expires_at, p.name
                        from family_invites i left join profiles p on p.id = i.created_by
                        where i.circle_id = ? and i.revoked_at is null and i.accepted_at is null and i.expires_at > now()
                        order by i.created_at desc
                        """,
                (rs, n) -> Map.of(
                        "id", rs.getString(1),
                        "createdAt", String.valueOf(odt(rs.getTimestamp(2))),
                        "expiresAt", String.valueOf(odt(rs.getTimestamp(3))),
                        "by", (Object) (rs.getString(4) == null ? "" : rs.getString(4))),
                circleId));
    }

    @DeleteMapping("/invites/{id}")
    public Map<String, Object> revokeInvite(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        try {
            db.asUser(user.id(), jdbc -> { jdbc.queryForObject("select revoke_family_invite(?)::text", String.class, id); return null; });
        } catch (DataAccessException e) {
            throw refused(e);
        }
        return Map.of("ok", Boolean.TRUE);
    }

    @DeleteMapping("/circles/{circleId}/members/{memberId}")
    public Map<String, Object> removeMember(@AuthenticationPrincipal AuthenticatedUser user,
                                            @PathVariable UUID circleId, @PathVariable UUID memberId) {
        try {
            db.asUser(user.id(), jdbc -> { jdbc.queryForObject("select remove_circle_member(?, ?)::text", String.class, circleId, memberId); return null; });
        } catch (DataAccessException e) {
            throw refused(e);
        }
        return Map.of("ok", Boolean.TRUE);
    }

    @PostMapping("/circles/{circleId}/leave")
    public Map<String, Object> leaveCircle(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID circleId) {
        try {
            db.asUser(user.id(), jdbc -> { jdbc.queryForObject("select leave_circle(?)::text", String.class, circleId); return null; });
        } catch (DataAccessException e) {
            throw refused(e);
        }
        return Map.of("ok", Boolean.TRUE);
    }

    // ---------------------------------------------------------------- plans

    @GetMapping("/plans")
    public List<Map<String, Object>> plans() {
        return db.asService(jdbc -> jdbc.query(
                "select key, name, max_parents, max_members, max_medicines, max_documents from plans order by sort_order",
                (rs, n) -> {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("key", rs.getString(1));
                    row.put("name", rs.getString(2));
                    row.put("maxParents", (Integer) rs.getObject(3));
                    row.put("maxMembers", (Integer) rs.getObject(4));
                    row.put("maxMedicines", (Integer) rs.getObject(5));
                    row.put("maxDocuments", (Integer) rs.getObject(6));
                    return row;
                }));
    }

    @GetMapping("/plans/overview")
    public JsonNode planOverview(@AuthenticationPrincipal AuthenticatedUser user) {
        return jsonCall(user.id(), "select plan_overview()::text");
    }

    @GetMapping("/plans/pauses")
    public JsonNode planPauses(@AuthenticationPrincipal AuthenticatedUser user) {
        return jsonCall(user.id(), "select plan_pauses()::text");
    }

    // ---------------------------------------------------------------- love loop actions

    @PostMapping("/recipients/{id}/love")
    public Map<String, Object> setLoveEnabled(@AuthenticationPrincipal AuthenticatedUser user,
                                              @PathVariable UUID id, @RequestBody EnabledBody body) {
        requireMember(user, id);
        try {
            boolean enabled = Boolean.TRUE.equals(body.enabled());
            db.asUser(user.id(), jdbc -> { jdbc.queryForObject("select set_love_enabled(?, ?)::text", String.class, id, enabled); return null; });
        } catch (DataAccessException e) {
            throw refused(e);
        }
        return Map.of("ok", Boolean.TRUE);
    }

    @PostMapping("/circles/{circleId}/call-name")
    public Map<String, Object> setCallName(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable UUID circleId, @RequestBody Map<String, String> body) {
        try {
            String name = body.getOrDefault("name", "");
            db.asUser(user.id(), jdbc -> { jdbc.queryForObject("select set_parent_calls_me(?, ?)::text", String.class, circleId, name); return null; });
        } catch (DataAccessException e) {
            throw refused(e);
        }
        return Map.of("ok", Boolean.TRUE);
    }

    @PostMapping("/recipients/{id}/reactions")
    public Map<String, Object> sendReaction(@AuthenticationPrincipal AuthenticatedUser user,
                                            @PathVariable UUID id, @RequestBody ReactionBody body) {
        requireMember(user, id);
        try {
            String reactionId = db.asUser(user.id(), jdbc -> jdbc.queryForObject(
                    "select send_reaction(?, ?, ?, ?, ?, ?)::text", String.class,
                    id, body.sourceType(), body.sourceId(), body.type(), body.emoji(), body.text()));
            return Map.of("id", String.valueOf(reactionId));
        } catch (DataAccessException e) {
            throw refused(e);
        }
    }

    // ---------------------------------------------------------------- escalation actions

    @PostMapping("/medicines/{id}/critical")
    public Map<String, Object> setCritical(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable UUID id, @RequestBody CriticalBody body) {
        // set_medicine_critical is SECURITY INVOKER with no own check: membership is enforced here.
        medicines.findForUser(id, user.id())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Medicine not found"));
        try {
            boolean critical = Boolean.TRUE.equals(body.critical());
            db.asUser(user.id(), jdbc -> { jdbc.queryForObject("select set_medicine_critical(?, ?)::text", String.class, id, critical); return null; });
        } catch (DataAccessException e) {
            throw refused(e);
        }
        return Map.of("ok", Boolean.TRUE);
    }

    @PostMapping("/escalations/{id}/handle")
    public Map<String, Object> handleEscalation(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        try {
            Boolean handled = db.asUser(user.id(),
                    jdbc -> jdbc.queryForObject("select handle_escalation(?)", Boolean.class, id));
            return Map.of("handled", Boolean.TRUE.equals(handled));
        } catch (DataAccessException e) {
            throw refused(e);
        }
    }

    // ---------------------------------------------------------------- owner's numbers + scheduler

    @GetMapping("/admin/stats")
    public JsonNode adminStats(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(defaultValue = "14") int days) {
        if (!user.admin()) throw new ResponseStatusException(FORBIDDEN, "Admin only");
        return jsonCall(user.id(), "select admin_stats(?)::text", Math.max(1, Math.min(days, 90)));
    }

    @GetMapping("/admin/love-stats")
    public JsonNode loveStats(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(defaultValue = "14") int days) {
        if (!user.admin()) throw new ResponseStatusException(FORBIDDEN, "Admin only");
        return jsonCall(user.id(), "select love_stats(?)::text", Math.max(1, Math.min(days, 90)));
    }

    @GetMapping("/admin/feedback")
    public JsonNode adminFeedback(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(defaultValue = "100") int limit) {
        if (!user.admin()) throw new ResponseStatusException(FORBIDDEN, "Admin only");
        return jsonCall(user.id(), "select coalesce(jsonb_agg(t), '[]'::jsonb)::text from admin_feedback(?) t",
                Math.max(1, Math.min(limit, 500)));
    }

    /** Heartbeat age of the reminder scheduler; no user data, no impersonation needed. */
    @GetMapping("/scheduler-health")
    public JsonNode schedulerHealth() {
        String text = db.asService(jdbc -> jdbc.queryForObject("select scheduler_health()::text", String.class));
        try {
            return json.readTree(text == null ? "null" : text);
        } catch (Exception e) {
            return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        }
    }
}
