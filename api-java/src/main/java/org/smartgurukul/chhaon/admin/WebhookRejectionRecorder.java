package org.smartgurukul.chhaon.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Counts the gateway webhook calls our server REFUSES, for the Monitor (admin_webhook_rejections). Watches only
 * {@code POST /webhooks/whatsapp-gateway} and looks only at the answer's status code, so the webhook controller itself is untouched:
 * 401 = missing / wrong signature, 503 = no secret configured, 500 = processing failed, 400 / 413 = not an event.
 * Only the reason and the time are stored: never the body, a header or a phone number. Best effort: a failure to record is
 * logged by class name and never changes the answer the gateway gets.
 */
@Component
public class WebhookRejectionRecorder extends OncePerRequestFilter {

    static final String PATH = "/webhooks/whatsapp-gateway";
    private static final Logger log = LoggerFactory.getLogger(WebhookRejectionRecorder.class);

    private final JdbcTemplate jdbc;

    public WebhookRejectionRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI()) || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            record(500);
            throw e;
        }
        record(response.getStatus());
    }

    static String reason(int status) {
        return switch (status) {
            case 401, 403 -> "bad_signature";
            case 503 -> "no_secret";
            case 400, 413, 415 -> "bad_request";
            default -> status >= 500 ? "server_error" : null;
        };
    }

    private void record(int status) {
        String reason = reason(status);
        if (reason == null) return;
        try {
            jdbc.update("insert into admin_webhook_rejections (reason, http_status) values (?, ?)", reason, (short) status);
        } catch (Exception e) {
            log.warn("could not record a refused webhook call ({})", e.getClass().getSimpleName());
        }
    }
}
