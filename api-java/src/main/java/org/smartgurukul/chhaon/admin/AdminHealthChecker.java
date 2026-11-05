package org.smartgurukul.chhaon.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every 5 minutes: evaluates the Monitor's health checks and records CHANGES in admin_alerts: a problem (amber/red) opens one
 * row, which stays open (status, detail and last_seen_at kept current) until the check is green again, when it is resolved.
 * Opening and resolving call {@link AdminAlertNotifier} (no-op today: the dashboard shows open alerts and the history).
 * Off with chhaon.admin.health-checker.enabled=false (tests). Never throws: a failing run is logged by class name only.
 */
@Component
public class AdminHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(AdminHealthChecker.class);

    public record Change(String checkKey, String change, String status) {}

    private final AdminHealthService health;
    private final JdbcTemplate jdbc;
    private final AdminAlertNotifier notifier;
    private final boolean enabled;

    public AdminHealthChecker(AdminHealthService health, JdbcTemplate jdbc, AdminAlertNotifier notifier,
                              @Value("${chhaon.admin.health-checker.enabled:true}") boolean enabled) {
        this.health = health;
        this.jdbc = jdbc;
        this.notifier = notifier;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${chhaon.admin.health-check-interval-ms:300000}",
               initialDelayString = "${chhaon.admin.health-check-initial-delay-ms:120000}")
    public void scheduled() {
        if (!enabled) return;
        try {
            List<Change> changes = run();
            if (!changes.isEmpty()) log.info("admin monitor: {}", changes);
            jdbc.update("delete from admin_webhook_rejections where rejected_at < now() - interval '30 days'");
        } catch (Exception e) {
            log.warn("admin health check run failed: {}", e.getClass().getSimpleName());
        }
    }

    /** One evaluation; returns what changed (opened / escalated / resolved). Public for tests. */
    public List<Change> run() {
        List<Change> changes = new ArrayList<>();
        for (HealthCheck c : health.evaluate()) {
            List<Map<String, Object>> open = jdbc.queryForList(
                    "select id, status, worst_status, detail from admin_alerts where check_key = ? and resolved_at is null", c.key());
            if (c.problem()) {
                if (open.isEmpty()) {
                    int n = jdbc.update("insert into admin_alerts (check_key, status, worst_status, detail) values (?, ?, ?, ?) " +
                            "on conflict (check_key) where resolved_at is null do nothing", c.key(), c.status(), c.status(), c.detail());
                    if (n == 1) {
                        changes.add(new Change(c.key(), "opened", c.status()));
                        safeNotify(() -> notifier.opened(c));
                    }
                } else {
                    Map<String, Object> row = open.get(0);
                    String worst = HealthCheck.rank(c.status()) > HealthCheck.rank((String) row.get("worst_status"))
                            ? c.status() : (String) row.get("worst_status");
                    jdbc.update("update admin_alerts set status = ?, worst_status = ?, detail = ?, last_seen_at = now() where id = ?",
                            c.status(), worst, c.detail(), row.get("id"));
                    if (!c.status().equals(row.get("status"))) changes.add(new Change(c.key(), "changed", c.status()));
                }
            } else if (!open.isEmpty()) {
                Map<String, Object> row = open.get(0);
                jdbc.update("update admin_alerts set resolved_at = now(), last_seen_at = now() where id = ?", row.get("id"));
                changes.add(new Change(c.key(), "resolved", c.status()));
                safeNotify(() -> notifier.resolved(c.key(), (String) row.get("detail")));
            }
        }
        return changes;
    }

    private static void safeNotify(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("admin alert notifier failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Open problems first, then the latest history. */
    public Map<String, Object> alerts(int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        return Map.of(
                "open", Rows.clean(jdbc.queryForList(
                        "select id, check_key, status, worst_status, detail, opened_at, last_seen_at, resolved_at from admin_alerts " +
                        "where resolved_at is null order by opened_at desc")),
                "recent", Rows.clean(jdbc.queryForList(
                        "select id, check_key, status, worst_status, detail, opened_at, last_seen_at, resolved_at from admin_alerts " +
                        "order by opened_at desc limit ?", n)));
    }
}
