package org.smartgurukul.chhaon.privacy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/**
 * "Download my data" and "Delete my account / this parent's data" (web: {@code web/src/lib/privacy.ts}). The paths sit at
 * the root (no /api/v1), exactly where the web app and the old Python API have them.
 */
@RestController
@RequestMapping("/privacy")
public class PrivacyController {

    public record DeleteAccountRequest(String confirm) {}

    private static final Map<String, String> DELETED = Map.of("status", "deleted");

    private final PrivacyService service;
    private final ObjectMapper json;

    public PrivacyController(PrivacyService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    /** A JSON file with everything the caller can access. Sent as a download, never cached. */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedUser user) throws JsonProcessingException {
        byte[] body = json.writerWithDefaultPrettyPrinter().writeValueAsString(service.export(user.id()))
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"carelink-my-data.json\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    /** Erases the caller. The confirmation word is checked here too, so a stray request cannot delete anything. */
    @PostMapping("/delete-account")
    public Map<String, String> deleteAccount(@AuthenticationPrincipal AuthenticatedUser user,
                                             @RequestBody(required = false) DeleteAccountRequest body) {
        if (body == null || body.confirm() == null || !"DELETE".equals(body.confirm().strip().toUpperCase(java.util.Locale.ROOT))) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "Type DELETE to confirm");
        }
        try {
            service.deleteAccount(user.id());
        } catch (DataAccessException e) {
            throw new ResponseStatusException(BAD_GATEWAY, "Could not finish. Please try again.");
        }
        return DELETED;
    }

    /** Owner only: removes one parent with everything attached to them. 404 for "not yours" and "not there" alike. */
    @PostMapping("/recipients/{recipientId}/delete")
    public Map<String, String> deleteRecipient(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID recipientId) {
        try {
            service.deleteRecipient(user.id(), recipientId);
        } catch (DataAccessException e) {
            throw new ResponseStatusException(BAD_GATEWAY, "Could not finish. Please try again.");
        }
        return DELETED;
    }
}
