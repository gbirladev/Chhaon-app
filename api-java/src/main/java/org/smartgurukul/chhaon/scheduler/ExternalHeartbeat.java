package org.smartgurukul.chhaon.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Tells an outside watcher (Better Stack heartbeat, 2026-11-05) "the scheduler just finished a tick". When these calls stop
 * (the scheduler hangs, the database is unreachable, the server is down), the watcher emails the owner: it keeps working when
 * our own server cannot report anything. Off while {@code chhaon.scheduler.external-heartbeat-url} (env
 * BETTERSTACK_HEARTBEAT_URL) is blank. Fire-and-forget with a short timeout: it never slows down or breaks a tick, and the URL
 * (a secret) is never logged.
 */
@Component
public class ExternalHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(ExternalHeartbeat.class);

    private final URI url;
    private final HttpClient http;
    private volatile boolean warned;

    public ExternalHeartbeat(@Value("${chhaon.scheduler.external-heartbeat-url:}") String url) {
        URI parsed = null;
        String u = url == null ? "" : url.trim();
        if (!u.isEmpty()) {
            try {
                parsed = URI.create(u);
                boolean local = "http".equalsIgnoreCase(parsed.getScheme())
                        && ("localhost".equals(parsed.getHost()) || "127.0.0.1".equals(parsed.getHost())); // tests only
                if (!"https".equalsIgnoreCase(parsed.getScheme()) && !local) {
                    log.error("external heartbeat URL must start with https://; heartbeat is OFF");
                    parsed = null;
                }
            } catch (IllegalArgumentException e) {
                log.error("external heartbeat URL is not a valid URL; heartbeat is OFF");
            }
        }
        this.url = parsed;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        if (this.url != null) log.info("external scheduler heartbeat is ON");
    }

    public boolean enabled() {
        return url != null;
    }

    /** One ping, in the background. Failures are logged once until the next success, never thrown. */
    public void ping() {
        if (url == null) return;
        HttpRequest req = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).GET().build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((res, err) -> {
            boolean ok = err == null && res.statusCode() / 100 == 2;
            if (ok) {
                warned = false;
            } else if (!warned) {
                warned = true;
                log.warn("external heartbeat ping failed ({})", err != null ? err.getClass().getSimpleName() : "HTTP " + res.statusCode());
            }
        });
    }
}
