package org.smartgurukul.chhaon.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartgurukul.chhaon.db.UserScopedSql;
import org.smartgurukul.chhaon.pilot.PilotService;
import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The owner's Monitor (docs/admin-monitor.md). Every endpoint: signed in AND profiles.is_admin, else 401 / 403 ({@link AdminGuard}).
 * Plain JSON (snake_case keys, UTC ISO times) so a separate admin website can reuse it later. Read-only, except that opening one
 * family records an admin_audit row, and "Move to front" in the pilot line changes that line (docs/pilot-cap.md), and a feedback's status (New / Looking / Fixed) can be set (audited).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminGuard guard;
    private final AdminHealthService health;
    private final AdminHealthChecker checker;
    private final AdminMonitorService monitor;
    private final GatewayProbe gateway;
    private final ServerInfo server;
    private final UserScopedSql userSql;
    private final ObjectMapper json;
    private final PilotService pilot;

    public AdminController(AdminGuard guard, AdminHealthService health, AdminHealthChecker checker, AdminMonitorService monitor,
                           GatewayProbe gateway, ServerInfo server, UserScopedSql userSql, ObjectMapper json, PilotService pilot) {
        this.guard = guard;
        this.health = health;
        this.checker = checker;
        this.monitor = monitor;
        this.gateway = gateway;
        this.server = server;
        this.userSql = userSql;
        this.json = json;
        this.pilot = pilot;
    }

    /** Every check with green / amber / red, plus the server build and the WhatsApp gateway / webhook / volume details. */
    @GetMapping("/health")
    public Map<String, Object> health(@AuthenticationPrincipal AuthenticatedUser user) {
        guard.require(user);
        List<HealthCheck> checks = health.evaluate();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated_at", Instant.now().toString());
        out.put("overall", HealthCheck.worst(checks));
        out.put("checks", checks);
        out.put("server", server.describe());
        out.put("volume", health.volume());
        out.put("webhook_rejections_24h", health.rejectionsByReason());
        return out;
    }

    /** Open problems and the latest history recorded by the 5-minute checker. */
    @GetMapping("/alerts")
    public Map<String, Object> alerts(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(defaultValue = "30") int limit) {
        guard.require(user);
        return checker.alerts(limit);
    }

    /** One IST day's totals across all families (default today), plus the existing pilot stats (admin_stats()). */
    @GetMapping("/overview")
    public Map<String, Object> overview(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(required = false) String date) {
        UUID adminId = guard.require(user);
        LocalDate day;
        try {
            day = date == null || date.isBlank() ? LocalDate.now(AdminHealthService.IST) : LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(BAD_REQUEST, "date must be YYYY-MM-DD");
        }
        Map<String, Object> out = monitor.overview(day);
        out.put("volume", health.volume());
        out.put("pilot", pilotStats(adminId));
        return out;
    }

    private JsonNode pilotStats(UUID adminId) {
        try {
            String text = userSql.asUser(adminId, jdbc -> jdbc.queryForObject("select admin_stats(14)::text", String.class));
            return json.readTree(text == null ? "null" : text);
        } catch (Exception e) {
            return null; // the Monitor still works without the pilot totals
        }
    }

    @GetMapping("/families")
    public List<Map<String, Object>> families(@AuthenticationPrincipal AuthenticatedUser user) {
        guard.require(user);
        return monitor.families();
    }

    /** One person cared for, in full. Writes an admin_audit row (who looked, at which family, when). */
    @GetMapping("/families/{recipientId}")
    public Map<String, Object> family(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID recipientId) {
        UUID adminId = guard.require(user);
        return monitor.family(adminId, recipientId);
    }

    @GetMapping("/activity")
    public List<Map<String, Object>> activity(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(defaultValue = "50") int limit) {
        guard.require(user);
        return monitor.activity(limit);
    }

    /** Doses and check-ins due in the next hours (default 2). */
    @GetMapping("/upcoming")
    public List<Map<String, Object>> upcoming(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(defaultValue = "2") int hours) {
        guard.require(user);
        return monitor.upcoming(hours);
    }

    /** Caregiver feedback, newest first, with who sent it (name, email, phone). */
    @GetMapping("/feedback-inbox")
    public List<Map<String, Object>> feedback(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(defaultValue = "50") int limit) {
        guard.require(user);
        return monitor.feedback(limit);
    }

    public record FeedbackStatus(String status) {}

    /**
     * "Report a problem": New -> Looking -> Fixed. Audited (admin_audit, action feedback_&lt;status&gt;); Fixed tells the sender in the
     * app. 400 for an unknown status, 404 for an unknown feedback, 409 before migration 20261108000062 is applied.
     */
    @PostMapping("/feedback/{feedbackId}/status")
    public Map<String, Object> feedbackStatus(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID feedbackId,
                                              @RequestBody FeedbackStatus in) {
        UUID adminId = guard.require(user);
        if (in == null || !AdminMonitorService.FEEDBACK_STATUSES.contains(in.status())) throw new ResponseStatusException(BAD_REQUEST, "Unknown status");
        try {
            return monitor.setFeedbackStatus(adminId, feedbackId, in.status());
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            throw new ResponseStatusException(CONFLICT, "Not installed yet");
        }
    }

    /** Ask the gateway again now instead of using the 60-second cache. */
    @GetMapping("/gateway")
    public GatewayProbe.Status gateway(@AuthenticationPrincipal AuthenticatedUser user,
                                       @RequestParam(defaultValue = "false") boolean fresh) {
        guard.require(user);
        if (fresh) gateway.forget();
        return gateway.status();
    }

    /** The pilot cap (docs/pilot-cap.md): seats used / allowed, new today / allowed, and who waits (names only). null = not installed. */
    @GetMapping("/pilot")
    public JsonNode pilot(@AuthenticationPrincipal AuthenticatedUser user) {
        guard.require(user);
        return pilot.summary();
    }

    /** Puts one waiting family at the front of the pilot line. 404 when they are not waiting. */
    @PostMapping("/pilot/{recipientId}/move-up")
    public Map<String, Object> pilotMoveUp(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID recipientId) {
        guard.require(user);
        if (!pilot.moveUp(recipientId)) throw new ResponseStatusException(NOT_FOUND, "Not waiting");
        return Map.of("moved", true);
    }
}
