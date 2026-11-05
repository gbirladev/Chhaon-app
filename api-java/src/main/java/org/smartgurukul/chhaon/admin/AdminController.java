package org.smartgurukul.chhaon.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartgurukul.chhaon.db.UserScopedSql;
import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

/**
 * The owner's Monitor (docs/admin-monitor.md). Every endpoint: signed in AND profiles.is_admin, else 401 / 403 ({@link AdminGuard}).
 * Plain JSON (snake_case keys, UTC ISO times) so a separate admin website can reuse it later. Read-only, except that opening one
 * family records an admin_audit row.
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

    public AdminController(AdminGuard guard, AdminHealthService health, AdminHealthChecker checker, AdminMonitorService monitor,
                           GatewayProbe gateway, ServerInfo server, UserScopedSql userSql, ObjectMapper json) {
        this.guard = guard;
        this.health = health;
        this.checker = checker;
        this.monitor = monitor;
        this.gateway = gateway;
        this.server = server;
        this.userSql = userSql;
        this.json = json;
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

    /** Ask the gateway again now instead of using the 60-second cache. */
    @GetMapping("/gateway")
    public GatewayProbe.Status gateway(@AuthenticationPrincipal AuthenticatedUser user,
                                       @RequestParam(defaultValue = "false") boolean fresh) {
        guard.require(user);
        if (fresh) gateway.forget();
        return gateway.status();
    }
}
