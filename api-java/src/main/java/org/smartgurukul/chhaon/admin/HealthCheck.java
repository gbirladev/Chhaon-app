package org.smartgurukul.chhaon.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One Monitor health check.
 *
 * @param key    stable id ("scheduler", "replies_in", ...), also the admin_alerts.check_key
 * @param status "green", "amber" or "red"
 * @param reason a short code the web app translates ("stale", "silent", ...)
 * @param detail one English sentence for logs-free reading (no phone numbers, no health data)
 * @param since  when the current state began, when known
 * @param facts  the numbers behind it (counts, minutes, times as ISO text)
 */
public record HealthCheck(String key, String status, String reason, String detail, Instant since, Map<String, Object> facts) {

    public static final String GREEN = "green";
    public static final String AMBER = "amber";
    public static final String RED = "red";

    public boolean problem() {
        return !GREEN.equals(status);
    }

    static int rank(String status) {
        return RED.equals(status) ? 2 : AMBER.equals(status) ? 1 : 0;
    }

    /** The worst status of a list ("green" when empty). */
    public static String worst(List<HealthCheck> checks) {
        String w = GREEN;
        for (HealthCheck c : checks) if (rank(c.status()) > rank(w)) w = c.status();
        return w;
    }
}
