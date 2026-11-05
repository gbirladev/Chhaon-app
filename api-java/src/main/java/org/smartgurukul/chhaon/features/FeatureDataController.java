package org.smartgurukul.chhaon.features;

import org.smartgurukul.chhaon.db.UserScopedSql;
import org.smartgurukul.chhaon.recipient.CareRecipient;
import org.smartgurukul.chhaon.recipient.CareRecipientRepository;
import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * Reads and simple writes the web app used to do straight on Supabase tables (love settings/reactions,
 * escalation policy + open escalations, appointments, feedback, dose history for the doctor summary).
 * Everything runs through {@link UserScopedSql#asUser}, so Row Level Security and the author-stamping
 * triggers behave exactly as they did for the old direct calls.
 */
@RestController
@RequestMapping("/api/v1")
public class FeatureDataController {

    public record LoveSettings(boolean enabled, boolean parentOptedOut, boolean control) {}
    public record Reaction(String id, String senderId, String sourceType, String sourceId, String type,
                           String emoji, String text, String status, String failReason,
                           OffsetDateTime queuedAt, OffsetDateTime sentAt) {}
    public record ParentMessage(String id, String kind, String body, String reactionId, OffsetDateTime createdAt) {}
    public record Policy(boolean enabled, int doseNudgeMin, int doseAlertMin, int checkinNudgeMin,
                         int checkinAlertMin, int alertAfterUnanswered, int silentStopDays) {}
    public record Escalation(String id, String kind, OffsetDateTime baseAt, OffsetDateTime nudgedAt,
                             OffsetDateTime decidedAt, OffsetDateTime alertedAt, OffsetDateTime resolvedAt) {}
    public record Appointment(String id, String doctor, OffsetDateTime at, String location, String notes, boolean remind) {}
    public record AppointmentInput(String doctor, OffsetDateTime at, String location, String notes, Boolean remind) {}
    public record FeedbackInput(String kind, String message, Integer rating, String screen, String appLanguage, List<String> images) {}
    public record AdherenceDose(String status, String medicineId, String name, String dosage, boolean active) {}
    public record AdherenceResponse(List<AdherenceDose> doses, List<String> checkins) {}

    private final UserScopedSql db;
    private final CareRecipientRepository recipients;

    public FeatureDataController(UserScopedSql db, CareRecipientRepository recipients) {
        this.db = db;
        this.recipients = recipients;
    }

    private CareRecipient requireMember(AuthenticatedUser user, UUID recipientId) {
        return recipients.findForUser(recipientId, user.id())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Parent profile not found"));
    }

    private static OffsetDateTime odt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------- love loop reads

    @GetMapping("/recipients/{id}/love")
    public LoveSettings loveSettings(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        requireMember(user, id);
        List<LoveSettings> rows = db.asUser(user.id(), jdbc -> jdbc.query(
                "select enabled, parent_opted_out_at, pilot_group from love_settings where recipient_id = ?",
                (rs, i) -> new LoveSettings(rs.getBoolean(1), rs.getTimestamp(2) != null, "control".equals(rs.getString(3))),
                id));
        return rows.isEmpty() ? new LoveSettings(false, false, false) : rows.get(0);
    }

    @GetMapping("/circles/{circleId}/call-name")
    public Map<String, Object> callName(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID circleId) {
        List<String> rows = db.asUser(user.id(), jdbc -> jdbc.query(
                "select coalesce(parent_calls_me, '') from care_circle_members where circle_id = ? and user_id = ?",
                (rs, i) -> rs.getString(1), circleId, user.id()));
        Map<String, Object> out = Map.of("name", rows.isEmpty() ? "" : rows.get(0));
        return out;
    }

    @GetMapping("/recipients/{id}/reactions")
    public List<Reaction> reactions(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        requireMember(user, id);
        return db.asUser(user.id(), jdbc -> jdbc.query("""
                        select id, sender_id, source_type, source_id, type, emoji, text, status, fail_reason, queued_at, sent_at
                        from reactions where recipient_id = ? and queued_at >= now() - interval '3 days'
                        order by queued_at desc limit 100
                        """,
                (rs, i) -> new Reaction(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        odt(rs.getTimestamp(10)), odt(rs.getTimestamp(11))),
                id));
    }

    @GetMapping("/recipients/{id}/parent-messages")
    public List<ParentMessage> parentMessages(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                              @RequestParam(defaultValue = "3") int limit) {
        requireMember(user, id);
        int n = Math.max(1, Math.min(limit, 20));
        return db.asUser(user.id(), jdbc -> jdbc.query("""
                        select id, kind, body, reaction_id, created_at from parent_messages
                        where recipient_id = ? and kind in ('text','emoji') order by created_at desc limit %d
                        """.formatted(n),
                (rs, i) -> new ParentMessage(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), odt(rs.getTimestamp(5))),
                id));
    }

    // ---------------------------------------------------------------- escalation policy + open items

    @GetMapping("/recipients/{id}/escalation-policy")
    public ResponseEntity<Policy> policy(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        requireMember(user, id);
        List<Policy> rows = db.asUser(user.id(), jdbc -> jdbc.query("""
                        select enabled, dose_nudge_min, dose_alert_min, checkin_nudge_min, checkin_alert_min,
                               alert_after_unanswered, silent_stop_days
                        from escalation_policies where recipient_id = ?
                        """,
                (rs, i) -> new Policy(rs.getBoolean(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7)),
                id));
        return rows.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(rows.get(0));
    }

    @PutMapping("/recipients/{id}/escalation-policy")
    public Policy savePolicy(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @RequestBody Policy p) {
        requireMember(user, id);
        try {
            db.asUser(user.id(), jdbc -> {
                jdbc.update("""
                                insert into escalation_policies
                                  (recipient_id, enabled, dose_nudge_min, dose_alert_min, checkin_nudge_min, checkin_alert_min,
                                   alert_after_unanswered, silent_stop_days)
                                values (?, ?, ?, ?, ?, ?, ?, ?)
                                on conflict (recipient_id) do update set
                                  enabled = excluded.enabled, dose_nudge_min = excluded.dose_nudge_min,
                                  dose_alert_min = excluded.dose_alert_min, checkin_nudge_min = excluded.checkin_nudge_min,
                                  checkin_alert_min = excluded.checkin_alert_min,
                                  alert_after_unanswered = excluded.alert_after_unanswered,
                                  silent_stop_days = excluded.silent_stop_days
                                """,
                        id, p.enabled(), p.doseNudgeMin(), p.doseAlertMin(), p.checkinNudgeMin(), p.checkinAlertMin(),
                        p.alertAfterUnanswered(), p.silentStopDays());
                return null;
            });
        } catch (DataAccessException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Could not save these settings");
        }
        return p;
    }

    @GetMapping("/recipients/{id}/escalations")
    public List<Escalation> openEscalations(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        requireMember(user, id);
        return db.asUser(user.id(), jdbc -> jdbc.query("""
                        select id, kind, base_at, nudged_at, decided_at, alerted_at, resolved_at
                        from escalations
                        where recipient_id = ? and resolved_at is null and base_at >= now() - interval '24 hours'
                        order by base_at
                        """,
                (rs, i) -> new Escalation(rs.getString(1), rs.getString(2), odt(rs.getTimestamp(3)), odt(rs.getTimestamp(4)),
                        odt(rs.getTimestamp(5)), odt(rs.getTimestamp(6)), odt(rs.getTimestamp(7))),
                id));
    }

    // ---------------------------------------------------------------- appointments

    @GetMapping("/recipients/{id}/appointments")
    public List<Appointment> appointments(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        requireMember(user, id);
        return db.asUser(user.id(), jdbc -> jdbc.query("""
                        select id, doctor_name, datetime, location, notes, remind from appointments
                        where recipient_id = ? order by datetime asc limit 200
                        """,
                (rs, i) -> new Appointment(rs.getString(1), rs.getString(2), odt(rs.getTimestamp(3)),
                        rs.getString(4), rs.getString(5), rs.getBoolean(6)),
                id));
    }

    @PostMapping("/recipients/{id}/appointments")
    public Map<String, Object> createAppointment(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                                 @RequestBody AppointmentInput in) {
        requireMember(user, id);
        validate(in);
        try {
            // Insert as the user: RLS applies and the stamp_author trigger records created_by.
            String newId = db.asUser(user.id(), jdbc -> jdbc.queryForObject("""
                            insert into appointments (recipient_id, doctor_name, datetime, location, notes, remind)
                            values (?, ?, ?, ?, ?, ?) returning id::text
                            """, String.class,
                    id, in.doctor().trim(), Timestamp.from(in.at().toInstant()), blankToNull(in.location()),
                    blankToNull(in.notes()), !Boolean.FALSE.equals(in.remind())));
            return Map.of("id", String.valueOf(newId));
        } catch (DataAccessException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Could not save this visit");
        }
    }

    @PutMapping("/appointments/{id}")
    public Map<String, Object> updateAppointment(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                                 @RequestBody AppointmentInput in) {
        validate(in);
        int changed;
        try {
            changed = db.asUser(user.id(), jdbc -> jdbc.update("""
                            update appointments set doctor_name = ?, datetime = ?, location = ?, notes = ?, remind = ?
                            where id = ?
                            """,
                    in.doctor().trim(), Timestamp.from(in.at().toInstant()), blankToNull(in.location()),
                    blankToNull(in.notes()), !Boolean.FALSE.equals(in.remind()), id));
        } catch (DataAccessException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Could not save this visit");
        }
        if (changed == 0) throw new ResponseStatusException(NOT_FOUND, "Visit not found");
        return Map.of("ok", Boolean.TRUE);
    }

    @DeleteMapping("/appointments/{id}")
    public ResponseEntity<Void> deleteAppointment(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        Integer changed = db.asUser(user.id(), jdbc -> jdbc.update("delete from appointments where id = ?", id));
        if (changed == null || changed == 0) throw new ResponseStatusException(NOT_FOUND, "Visit not found");
        return ResponseEntity.noContent().build();
    }

    private static void validate(AppointmentInput in) {
        if (in.doctor() == null || in.doctor().isBlank() || in.doctor().length() > 120 || in.at() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Doctor and time are required");
        }
        if ((in.location() != null && in.location().length() > 200) || (in.notes() != null && in.notes().length() > 1000)) {
            throw new ResponseStatusException(BAD_REQUEST, "Too long");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ---------------------------------------------------------------- feedback

    @PostMapping("/feedback")
    public Map<String, Object> sendFeedback(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody FeedbackInput in) {
        if (in.kind() == null || in.message() == null || in.message().isBlank() || in.message().length() > 1000) {
            throw new ResponseStatusException(BAD_REQUEST, "A short message is required");
        }
        try {
            db.asUser(user.id(), jdbc -> {
                jdbc.update("""
                                insert into feedback (user_id, kind, message, rating, screen, app_language, images)
                                values (?, ?, ?, ?, ?, ?, ?)
                                """,
                        ps -> {
                            ps.setObject(1, user.id());
                            ps.setString(2, in.kind());
                            ps.setString(3, in.message().trim());
                            if (in.rating() == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, in.rating());
                            ps.setString(5, in.screen() == null ? null : in.screen().substring(0, Math.min(40, in.screen().length())));
                            ps.setString(6, "hi".equals(in.appLanguage()) ? "hi" : "en");
                            String[] images = in.images() == null ? new String[0] : in.images().toArray(new String[0]);
                            ps.setArray(7, ps.getConnection().createArrayOf("text", images));
                        });
                return null;
            });
        } catch (DataAccessException e) {
            if ("53400".equals(PgErrors.sqlState(e))) throw new ResponseStatusException(TOO_MANY_REQUESTS, "limit");
            throw new ResponseStatusException(BAD_REQUEST, "Could not send this");
        }
        return Map.of("ok", Boolean.TRUE);
    }

    // ---------------------------------------------------------------- adherence history (doctor summary)

    @GetMapping("/recipients/{id}/adherence")
    public AdherenceResponse adherence(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                       @RequestParam(defaultValue = "30") int days) {
        CareRecipient r = requireMember(user, id);
        int span = Math.max(1, Math.min(days, 90));
        Timestamp from = Timestamp.from(OffsetDateTime.now().minusDays(span).toInstant());
        List<AdherenceDose> doses = db.asService(jdbc -> jdbc.query("""
                        select d.status, m.id, m.name, m.dosage, m.active
                        from dose_events d
                        join medicine_schedules s on s.id = d.schedule_id
                        join medicines m on m.id = s.medicine_id
                        where m.recipient_id = ? and d.scheduled_at between ? and now()
                        order by d.scheduled_at
                        """,
                (rs, i) -> new AdherenceDose(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)),
                id, from));
        ZoneId zone = zoneOf(r.getTimezone());
        LocalDate fromDay = LocalDate.now(zone).minusDays(span);
        List<String> checkins = db.asService(jdbc -> jdbc.query(
                "select status from checkins where recipient_id = ? and date between ? and ?",
                (rs, i) -> rs.getString(1),
                id, java.sql.Date.valueOf(fromDay), java.sql.Date.valueOf(LocalDate.now(zone))));
        return new AdherenceResponse(doses, checkins);
    }

    private static ZoneId zoneOf(String tz) {
        try {
            return ZoneId.of(tz == null || tz.isBlank() ? "Asia/Kolkata" : tz);
        } catch (Exception e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }
}
