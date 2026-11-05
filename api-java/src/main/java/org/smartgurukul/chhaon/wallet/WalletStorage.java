package org.smartgurukul.chhaon.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The private Storage bucket {@code reports} (the Reports wallet), reached by the BACKEND with the service-role key, so the web
 * app no longer needs Supabase to accept its login token for files (see docs/TODO.md, "Reports tab and the login token"):
 * <ul>
 *   <li>{@link #uploadUrl}: a one-time signed upload address for ONE path the backend chose (the browser PUTs the file there);</li>
 *   <li>{@link #viewUrls}: short-lived signed links to read files;</li>
 *   <li>{@link #remove}: deletes files.</li>
 * </ul>
 * Every address returned is RELATIVE to {@code <Supabase URL>/storage/v1} (for example {@code /object/sign/reports/...?token=...}):
 * the browser puts its own Supabase address in front, so a backend that reaches Supabase by an internal name still works.
 *
 * <p>Config: the same two settings as the medicine photo in reminders, app.storage.supabase-url (SUPABASE_URL) and
 * app.storage.service-role-key (SUPABASE_SERVICE_ROLE_KEY). Either blank = off ({@link #isConfigured} false; the API answers
 * 503 "storage_off" for file actions). The key is never logged and never sent to the browser. Callers check that every path
 * is inside the person's own folder BEFORE calling here: this class runs with full rights.
 */
@Service
public class WalletStorage {

    private static final Logger log = LoggerFactory.getLogger(WalletStorage.class);

    public static final String BUCKET = "reports";

    private final String baseUrl;
    private final String serviceKey;
    private final boolean configured;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public WalletStorage(@Value("${app.storage.supabase-url:}") String supabaseUrl,
                         @Value("${app.storage.service-role-key:}") String serviceKey) {
        String u = supabaseUrl == null ? "" : supabaseUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        this.baseUrl = u;
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        boolean safeAddress = u.startsWith("https://")
                || u.matches("http://(localhost|127\\.0\\.0\\.1|host\\.docker\\.internal)(:[0-9]+)?");   // the key never travels unencrypted
        this.configured = !u.isEmpty() && !this.serviceKey.isEmpty() && safeAddress;
        this.http = configured
                ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
                : null;
        if (!configured) log.info("reports wallet files: off (storage not configured), file actions answer 503 storage_off");
    }

    public boolean isConfigured() {
        return configured;
    }

    /** A signed upload address for this one path (relative to /storage/v1), valid for about two hours (Supabase's rule). */
    public String uploadUrl(String path) throws IOException, InterruptedException {
        HttpResponse<String> resp = send(HttpRequest.newBuilder(uri("/storage/v1/object/upload/sign/" + BUCKET + "/" + encode(path)))
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        if (resp.statusCode() != 200) throw new IOException("storage_http_" + resp.statusCode());
        JsonNode url = json.readTree(resp.body()).get("url");
        if (url == null || !url.isTextual() || !url.asText().startsWith("/object/upload/sign/")) throw new IOException("storage_bad_answer");
        return url.asText();
    }

    /** Signed read links (relative to /storage/v1) by path; a path Storage cannot sign (missing file) is simply left out. */
    public Map<String, String> viewUrls(List<String> paths, int seconds) throws IOException, InterruptedException {
        Map<String, String> out = new LinkedHashMap<>();
        if (paths.isEmpty()) return out;
        String body = json.writeValueAsString(Map.of("expiresIn", seconds, "paths", paths));
        HttpResponse<String> resp = send(HttpRequest.newBuilder(uri("/storage/v1/object/sign/" + BUCKET))
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        if (resp.statusCode() != 200) throw new IOException("storage_http_" + resp.statusCode());
        for (JsonNode item : json.readTree(resp.body())) {
            JsonNode path = item.get("path");
            JsonNode url = item.get("signedURL");
            if (path != null && path.isTextual() && url != null && url.isTextual() && url.asText().startsWith("/object/sign/")) {
                out.put(path.asText(), url.asText());
            }
        }
        return out;
    }

    /** Deletes these files. Never throws: a file left behind costs a little space, never data. */
    public void remove(List<String> paths) {
        if (paths.isEmpty() || !isConfigured()) return;
        try {
            String body = json.writeValueAsString(Map.of("prefixes", paths));
            HttpResponse<String> resp = send(HttpRequest.newBuilder(uri("/storage/v1/object/" + BUCKET))
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(body)));
            if (resp.statusCode() != 200) log.warn("wallet files not removed (storage_http_{})", resp.statusCode());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("wallet files not removed ({})", e.getClass().getSimpleName());
        }
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws IOException, InterruptedException {
        HttpRequest req = b.timeout(Duration.ofSeconds(15))
                .header("apikey", serviceKey)
                .header("Authorization", "Bearer " + serviceKey)
                .header("Content-Type", "application/json")
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String path) {
        return Arrays.stream(path.split("/"))
                .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    @Override
    public String toString() {
        return "WalletStorage[" + (isConfigured() ? "on" : "off") + "]";   // never the key
    }
}
