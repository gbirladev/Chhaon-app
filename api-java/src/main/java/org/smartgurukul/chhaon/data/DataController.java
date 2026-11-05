package org.smartgurukul.chhaon.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartgurukul.chhaon.recipient.CareCircleMemberRepository;
import org.smartgurukul.chhaon.recipient.CareRecipient;
import org.smartgurukul.chhaon.recipient.CareRecipientRepository;
import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Read endpoints for the web app's core screens — everything it used to read straight from Supabase
 * (members, notifications, today's doses + check-in, recent activity, medicine names). Authorization is the
 * same as the other controllers: every query is scoped to the signed-in caregiver or guarded by a
 * circle-membership check (the service role bypasses RLS, so Java must scope).
 */
@RestController
@RequestMapping("/api/v1")
public class DataController {

    public record MemberRow(String userId, String role, String name, String email, String phone) {}
    public record NotificationRow(String id, String type, JsonNode payload, OffsetDateTime readAt, OffsetDateTime createdAt) {}
    /** confirmSource: whatsapp | caregiver_in_person | caregiver_told (null while not confirmed); confirmedBy* = the family member
     *  who marked it given (migration 20261105000047). */
    public record DoseRow(String id, String status, OffsetDateTime scheduledAt, String name, String dosage, boolean active,
                          OffsetDateTime respondedAt, String confirmSource, String confirmedBy, String confirmedByName) {}
    public record CheckinRow(String id, String status, OffsetDateTime sentAt, OffsetDateTime respondedAt) {}
    public record ActivityDoseRow(String id, String status, OffsetDateTime sentAt, OffsetDateTime respondedAt, String name, String dosage,
                                  String confirmSource, String confirmedBy, String confirmedByName, OffsetDateTime scheduledAt) {}
    public record TodayResponse(List<DoseRow> doses, CheckinRow checkin) {}
    public record ActivityResponse(List<ActivityDoseRow> doses, List<CheckinRow> checkins) {}
    public record MedicineName(String id, String name) {}
    public record ReadRequest(List<UUID> ids) {}

    private final JdbcTemplate jdbc;
    private final CareRecipientRepository recipients;
    private final CareCircleMemberRepository members;
    private final ObjectMapper json;

    public DataController(JdbcTemplate jdbc, CareRecipientRepository recipients,
                          CareCircleMemberRepository members, ObjectMapper json) {
        this.jdbc = jdbc;
        this.recipients = recipients;
        this.members = members;
        this.json = json;
    }

    private static OffsetDateTime odt(Object ts) {
        return ts == null ? null : ((Timestamp) ts).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    private CareRecipient requireRecipient(AuthenticatedUser user, UUID recipientId) {
        return recipients.findForUser(recipientId, user.id())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Parent profile not found"));
    }

    // ---------------------------------------------------------------- members

    @GetMapping("/circles/{circleId}/members")
    public List<MemberRow> membersOf(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID circleId) {
        if (members.findByCircleIdAndUserId(circleId, user.id()).isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "Family not found");
        }
        return jdbc.query("""
                        select m.user_id, m.role, p.name, p.email, p.phone
                        from care_circle_members m left join profiles p on p.id = m.user_id
                        where m.circle_id = ? order by m.created_at
                        """,
                (rs, i) -> new MemberRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                circleId);
    }

    // ---------------------------------------------------------------- notifications

    @GetMapping("/notifications")
    public List<NotificationRow> notifications(@AuthenticationPrincipal AuthenticatedUser user) {
        return jdbc.query("""
                        select id, type, payload::text, read_at, created_at from notifications
                        where user_id = ? order by created_at desc limit 30
                        """,
                (rs, i) -> new NotificationRow(rs.getString(1), rs.getString(2), parse(rs.getString(3)),
                        odt(rs.getTimestamp(4)), odt(rs.getTimestamp(5))),
                user.id());
    }

    private JsonNode parse(String payload) {
        try {
            return json.readTree(payload == null ? "{}" : payload);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    @PostMapping("/notifications/read")
    public ResponseEntity<Map<String, Object>> markRead(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @RequestBody ReadRequest req) {
        List<UUID> ids = req.ids() == null ? List.of() : req.ids();
        if (!ids.isEmpty() && ids.size() <= 100) {
            jdbc.update("update notifications set read_at = now() where user_id = ? and read_at is null and id = any(?)",
                    ps -> {
                        ps.setObject(1, user.id());
                        ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
                    });
        }
        Map<String, Object> ok = Map.of("ok", Boolean.TRUE);
        return ResponseEntity.ok(ok);
    }

    @DeleteMapping("/notifications/{id}")
    public ResponseEntity<Void> deleteNotification(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        jdbc.update("delete from notifications where user_id = ? and id = ?", user.id(), id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- today + activity

    /** Doses within ±36 h (the web filters to the parent's local day, as before) and today's check-in. */
    @GetMapping("/recipients/{recipientId}/today")
    public TodayResponse today(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID recipientId) {
        CareRecipient r = requireRecipient(user, recipientId);
        OffsetDateTime now = OffsetDateTime.now();
        List<DoseRow> doses = jdbc.query("""
                        select d.id, d.status, d.scheduled_at, m.name, m.dosage, m.active,
                               d.responded_at, d.confirm_source, d.confirmed_by, cp.name
                        from dose_events d
                        join medicine_schedules s on s.id = d.schedule_id
                        join medicines m on m.id = s.medicine_id
                        left join profiles cp on cp.id = d.confirmed_by
                        where m.recipient_id = ? and d.scheduled_at between ? and ?
                        order by d.scheduled_at
                        """,
                (rs, i) -> new DoseRow(rs.getString(1), rs.getString(2), odt(rs.getTimestamp(3)),
                        rs.getString(4), rs.getString(5), rs.getBoolean(6),
                        odt(rs.getTimestamp(7)), rs.getString(8), rs.getString(9), rs.getString(10)),
                recipientId, Timestamp.from(now.minusHours(36).toInstant()), Timestamp.from(now.plusHours(36).toInstant()));
        LocalDate localToday = LocalDate.now(zoneOf(r.getTimezone()));
        CheckinRow checkin = jdbc.query("""
                        select id, status, sent_at, responded_at from checkins
                        where recipient_id = ? and date = ?
                        """,
                (rs, i) -> new CheckinRow(rs.getString(1), rs.getString(2), odt(rs.getTimestamp(3)), odt(rs.getTimestamp(4))),
                recipientId, java.sql.Date.valueOf(localToday)).stream().findFirst().orElse(null);
        return new TodayResponse(doses, checkin);
    }

    /** What was really sent in the last few days (dose reminders + check-ins), newest first. Doses a family member marked given
     *  (never sent, e.g. given before the reminder) are included too, with sentAt null. */
    @GetMapping("/recipients/{recipientId}/activity")
    public ActivityResponse activity(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID recipientId,
                                     @RequestParam(defaultValue = "3") int days) {
        requireRecipient(user, recipientId);
        int span = Math.max(1, Math.min(days, 14));
        Timestamp from = Timestamp.from(OffsetDateTime.now().minusDays(span).toInstant());
        List<ActivityDoseRow> doses = jdbc.query("""
                        select d.id, d.status, d.sent_at, d.responded_at, m.name, m.dosage,
                               d.confirm_source, d.confirmed_by, cp.name, d.scheduled_at
                        from dose_events d
                        join medicine_schedules s on s.id = d.schedule_id
                        join medicines m on m.id = s.medicine_id
                        left join profiles cp on cp.id = d.confirmed_by
                        where m.recipient_id = ?
                          and ((d.sent_at is not null and d.sent_at >= ?)
                               or (d.confirmed_by is not null and d.responded_at >= ?))
                        order by coalesce(d.sent_at, d.responded_at) desc limit 300
                        """,
                (rs, i) -> new ActivityDoseRow(rs.getString(1), rs.getString(2), odt(rs.getTimestamp(3)),
                        odt(rs.getTimestamp(4)), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), odt(rs.getTimestamp(10))),
                recipientId, from, from);
        List<CheckinRow> checkins = jdbc.query("""
                        select id, status, sent_at, responded_at from checkins
                        where recipient_id = ? and sent_at is not null and sent_at >= ?
                        order by sent_at desc limit 50
                        """,
                (rs, i) -> new CheckinRow(rs.getString(1), rs.getString(2), odt(rs.getTimestamp(3)), odt(rs.getTimestamp(4))),
                recipientId, from);
        return new ActivityResponse(doses, checkins);
    }

    // ---------------------------------------------------------------- medicine names

    /** Medicine names by id for every parent of the caller (notifications name medicines of other parents too). */
    @GetMapping("/medicine-names")
    public List<MedicineName> medicineNames(@AuthenticationPrincipal AuthenticatedUser user) {
        return jdbc.query("""
                        select m.id, m.name from medicines m
                        where m.recipient_id in (
                            select r.id from care_recipients r where r.circle_id in (
                                select cm.circle_id from care_circle_members cm where cm.user_id = ?))
                        """,
                (rs, i) -> new MedicineName(rs.getString(1), rs.getString(2)),
                user.id());
    }

    private static ZoneId zoneOf(String tz) {
        try {
            return ZoneId.of(tz == null || tz.isBlank() ? "Asia/Kolkata" : tz);
        } catch (Exception e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }
}
