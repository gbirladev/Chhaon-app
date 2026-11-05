package org.smartgurukul.chhaon.dose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.smartgurukul.chhaon.whatsapp.reply.ReplyHandlers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * "Mark as given" on Today: a family member marks a waiting / missed dose as given in person or as told on the phone, and can
 * undo it for {@link #UNDO_MINUTES} minutes. The database functions (migration 20261105000047) do the work atomically: status,
 * who/how, stock (exactly like a WhatsApp "1": {@link ReplyHandlers#unitsPerDose}), and removing a "missed" alert that is no
 * longer true. Only members of the person's circle; anyone else gets 404.
 *
 * Answers 200 {"result": marked | already | not_allowed | too_early | too_old, "status"?, "source"?} for mark and
 * {"result": undone | not_marked | not_yours | too_late} for undo.
 */
@RestController
@RequestMapping("/api/v1/doses")
public class DoseGivenController {

    /** How long the family member who marked a dose can take it back. */
    public static final int UNDO_MINUTES = 10;

    public record GivenBody(String source) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public DoseGivenController(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @PostMapping("/{doseId}/given")
    @Transactional
    public JsonNode markGiven(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID doseId,
                              @RequestBody(required = false) GivenBody body) {
        String source = body == null ? null : body.source();
        if (!"caregiver_in_person".equals(source) && !"caregiver_told".equals(source)) {
            throw new ResponseStatusException(BAD_REQUEST, "source must be caregiver_in_person or caregiver_told");
        }
        String dosage = requireDosage(user, doseId);
        return call("select public.mark_dose_given(?, ?, ?, ?)::text", doseId, user.id(), source, ReplyHandlers.unitsPerDose(dosage));
    }

    @PostMapping("/{doseId}/given/undo")
    @Transactional
    public JsonNode undo(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID doseId) {
        requireDosage(user, doseId);
        return call("select public.undo_dose_given(?, ?, ?)::text", doseId, user.id(), UNDO_MINUTES);
    }

    /** The dose's medicine dosage, or 404 when the dose does not exist or is outside the caller's circles. */
    private String requireDosage(AuthenticatedUser user, UUID doseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select m.dosage from dose_events de
                join medicine_schedules s on s.id = de.schedule_id
                join medicines m on m.id = s.medicine_id
                join care_recipients r on r.id = m.recipient_id
                join care_circle_members cm on cm.circle_id = r.circle_id and cm.user_id = ?
                where de.id = ?
                """, user.id(), doseId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Dose not found");
        return (String) rows.get(0).get("dosage");
    }

    private JsonNode call(String sql, Object... args) {
        String text = jdbc.queryForObject(sql, String.class, args);
        try {
            return json.readTree(text == null ? "{}" : text);
        } catch (Exception e) {
            throw new ResponseStatusException(BAD_REQUEST, "Unexpected answer from the database");
        }
    }
}
