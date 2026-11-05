package org.smartgurukul.chhaon.scheduler;

import org.smartgurukul.chhaon.recipient.CareRecipientRepository;
import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.smartgurukul.chhaon.whatsapp.WeeklySummaryTexts.Week;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The "This week" card on the Family tab (feature F3, docs/weekly-summary.md): the same numbers as the Sunday WhatsApp summary,
 * for one person, read-only. Only members of the person's family circle (everyone else gets 404).
 *
 * <pre>
 * GET /api/v1/recipients/{id}/weekly-summary
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class WeeklySummaryController {

    private final WeeklySummary summary;
    private final CareRecipientRepository recipients;

    public WeeklySummaryController(WeeklySummary summary, CareRecipientRepository recipients) {
        this.summary = summary;
        this.recipients = recipients;
    }

    @GetMapping("/recipients/{id}/weekly-summary")
    public Week week(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        recipients.findForUser(id, user.id()).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Parent profile not found"));
        Week w = summary.weekFor(id, user.id(), Instant.now());
        if (w == null) throw new ResponseStatusException(NOT_FOUND, "Parent profile not found");
        return w;
    }
}
