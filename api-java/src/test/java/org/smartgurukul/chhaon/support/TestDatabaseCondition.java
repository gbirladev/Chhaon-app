package org.smartgurukul.chhaon.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Properties;

/** Evaluated once: can we reach the test database, and is it really a throwaway one? */
public class TestDatabaseCondition implements ExecutionCondition {

    public static final String DEFAULT_URL = "jdbc:postgresql://host.docker.internal:54322/chhaon_java_test";

    private static volatile String verdict; // null = not evaluated yet, "" = ok, otherwise the reason

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String v = verdict;
        if (v == null) {
            v = check();
            verdict = v;
        }
        return v.isEmpty()
                ? ConditionEvaluationResult.enabled("test database reachable")
                : ConditionEvaluationResult.disabled("SKIPPED: " + v
                        + " (create it with api-java/scripts/make-test-db.sh; see docs/java-tests.md)");
    }

    private static String check() {
        String url = env("TEST_DB_URL", DEFAULT_URL);
        Properties p = new Properties();
        p.setProperty("user", env("TEST_DB_USER", "postgres"));
        p.setProperty("password", env("TEST_DB_PASSWORD", "postgres"));
        p.setProperty("connectTimeout", "3");
        try (Connection c = DriverManager.getConnection(url, p);
             ResultSet rs = c.createStatement().executeQuery(
                     "select current_database(), to_regclass('public.whatsapp_events') is not null")) {
            rs.next();
            String db = rs.getString(1);
            if (!db.contains("test")) {
                return "refusing to run against database '" + db + "': the name must contain 'test'";
            }
            if (!rs.getBoolean(2)) {
                return "database '" + db + "' has no Chhaon schema yet";
            }
            return "";
        } catch (Exception e) {
            return "test database not reachable at " + url + " (" + e.getClass().getSimpleName() + ")";
        }
    }
}
