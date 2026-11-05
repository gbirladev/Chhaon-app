package org.smartgurukul.chhaon.admin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which build is running and since when. The commit comes from META-INF/build-info.properties (spring-boot-maven-plugin
 * build-info, property {@code chhaon.git-sha} passed by .github/workflows/backend-deploy.yml as {@code -Dchhaon.git-sha=$GITHUB_SHA}),
 * else the environment variable GIT_SHA, else "unknown".
 */
@Component
public class ServerInfo {

    private final BuildProperties build;
    private final String envSha;

    public ServerInfo(ObjectProvider<BuildProperties> build, @Value("${GIT_SHA:}") String envSha) {
        this.build = build.getIfAvailable();
        this.envSha = envSha;
    }

    public Map<String, Object> describe() {
        String sha = build == null ? null : build.get("gitSha");
        if (sha == null || sha.isBlank() || "unknown".equals(sha)) sha = envSha == null || envSha.isBlank() ? "unknown" : envSha;
        Instant started = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("git_sha", sha);
        m.put("version", build == null ? null : build.getVersion());
        m.put("build_time", build == null || build.getTime() == null ? null : build.getTime().toString());
        m.put("started_at", started.toString());
        m.put("uptime_seconds", Duration.between(started, Instant.now()).toSeconds());
        m.put("java", System.getProperty("java.version"));
        return m;
    }
}
