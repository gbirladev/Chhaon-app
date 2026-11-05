package org.smartgurukul.chhaon.privacy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartgurukul.chhaon.db.UserScopedSql;
import org.smartgurukul.chhaon.recipient.CareRecipientService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Data export and erasure for a caregiver (DPDP Act 2023: access, portability, erasure). Port of the Python
 * {@code api/app/services/privacy.py}.
 *
 * <p>Runs with the backend's own (RLS-bypassing) connection, so every query here is scoped to the caller's circles
 * by hand. Never log health data or phone numbers.
 *
 * <p>Stored files (buckets {@code medicine-photos}, {@code reports}, {@code feedback}) are NOT removed: the Java
 * backend has no Supabase Storage access yet. See docs/TODO.md ("Privacy: stored files are not erased").
 */
@Service
public class PrivacyService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);

    /** Circles the user belongs to. Every export query starts from here and takes the user id as its only parameter. */
    private static final String MY_CIRCLES = "select m.circle_id from public.care_circle_members m where m.user_id = ?";
    private static final String MY_RECIPIENTS = "select r.id from public.care_recipients r where r.circle_id in (" + MY_CIRCLES + ")";
    private static final String MY_MEDICINES = "select x.id from public.medicines x where x.recipient_id in (" + MY_RECIPIENTS + ")";
    private static final String MY_SCHEDULES = "select x.id from public.medicine_schedules x where x.medicine_id in (" + MY_MEDICINES + ")";
    private static final String MY_DOCUMENTS = "select x.id from public.documents x where x.recipient_id in (" + MY_RECIPIENTS + ")";
    private static final String MY_REPORTS = "select x.id from public.reports x where x.recipient_id in (" + MY_RECIPIENTS + ")";

    /** One row as JSON, minus columns that are secrets or internal plumbing (not the user's data). */
    private static final String CLEAN_ROW = "(to_jsonb(t) - 'token' - 'whatsapp_message_id')";

    private final UserScopedSql sql;
    private final CareRecipientService recipients;
    private final ObjectMapper json;

    public PrivacyService(UserScopedSql sql, CareRecipientService recipients, ObjectMapper json) {
        this.sql = sql;
        this.recipients = recipients;
        this.json = json;
    }

    // ---------------------------------------------------------------- export

    /** Everything the caller can see, as one JSON document (same sections as the Python export). */
    public ObjectNode export(UUID userId) {
        return sql.asService(jdbc -> {
            ObjectNode out = json.createObjectNode();
            out.put("exported_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
            out.put("about", "Everything Chhaon holds that you can see. Uploaded files (wallet documents, report photos/PDFs, "
                    + "medicine photos) are not included here; their storage paths are. Secret links and sign-in tokens are never exported.");
            JsonNode profile = parse(jdbc.queryForObject(
                    "select " + CLEAN_ROW + "::text from public.profiles t where t.id = ?", String.class, userId));
            out.set("profile", profile == null ? NullNode.getInstance() : profile);
            out.set("circles", rows(jdbc, "care_circles", "t.id in (" + MY_CIRCLES + ")", "t.created_at", userId));
            out.set("circle_members", parse(jdbc.queryForObject(
                    "select coalesce(jsonb_agg(jsonb_build_object('circle_id', t.circle_id, 'role', t.role, 'name', p.name, "
                            + "'is_you', t.user_id = ?, 'joined_at', t.created_at) order by t.created_at, t.user_id), '[]'::jsonb)::text "
                            + "from public.care_circle_members t left join public.profiles p on p.id = t.user_id "
                            + "where t.circle_id in (" + MY_CIRCLES + ")", String.class, userId, userId)));
            out.set("parents", rows(jdbc, "care_recipients", "t.circle_id in (" + MY_CIRCLES + ")", "t.created_at", userId));
            out.set("medicines", rows(jdbc, "medicines", "t.recipient_id in (" + MY_RECIPIENTS + ")", "t.created_at", userId));
            out.set("medicine_schedules", rows(jdbc, "medicine_schedules", "t.medicine_id in (" + MY_MEDICINES + ")", "t.created_at", userId));
            out.set("dose_events", rows(jdbc, "dose_events", "t.schedule_id in (" + MY_SCHEDULES + ")", "t.scheduled_at", userId));
            out.set("checkins", rows(jdbc, "checkins", "t.recipient_id in (" + MY_RECIPIENTS + ")", "t.date", userId));
            out.set("documents", rows(jdbc, "documents", "t.recipient_id in (" + MY_RECIPIENTS + ")", "t.created_at", userId));
            out.set("document_files", rows(jdbc, "document_files", "t.document_id in (" + MY_DOCUMENTS + ")", "t.id", userId));
            out.set("reports", rows(jdbc, "reports", "t.recipient_id in (" + MY_RECIPIENTS + ")", "t.created_at", userId));
            out.set("report_values", rows(jdbc, "report_values", "t.report_id in (" + MY_REPORTS + ")", "t.id", userId));
            out.set("appointments", rows(jdbc, "appointments", "t.recipient_id in (" + MY_RECIPIENTS + ")", "t.datetime", userId));
            out.set("child_vaccines", rows(jdbc, "child_vaccines", "t.recipient_id in (" + MY_RECIPIENTS + ")", "t.due_date", userId));
            // The secret link itself is never exported, only that one exists.
            out.set("emergency_links", parse(jdbc.queryForObject(
                    "select coalesce(jsonb_agg(jsonb_build_object('recipient_id', t.recipient_id, 'active', t.active, "
                            + "'created_at', t.created_at) order by t.created_at), '[]'::jsonb)::text "
                            + "from public.emergency_links t where t.recipient_id in (" + MY_RECIPIENTS + ")", String.class, userId)));
            out.set("notifications", rows(jdbc, "notifications", "t.user_id = ?", "t.created_at", userId));
            return out;
        });
    }

    /** All rows of {@code table} matching {@code where} (fixed SQL with one user-id parameter), as a JSON array. */
    private JsonNode rows(JdbcTemplate jdbc, String table, String where, String order, UUID userId) {
        String text = jdbc.queryForObject(
                "select coalesce(jsonb_agg(" + CLEAN_ROW + " order by " + order + "), '[]'::jsonb)::text from public."
                        + table + " t where " + where, String.class, userId);
        return parse(text);
    }

    private JsonNode parse(String text) {
        if (text == null) return null;
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("export: unreadable JSON from the database", e);
        }
    }

    // ---------------------------------------------------------------- erasure

    /**
     * Owner only: erases one parent with everything attached (the database cascades to medicines, schedules, doses,
     * check-ins, documents, reports, appointments, emergency links, ...). Not a member or not the owner = 404.
     */
    public void deleteRecipient(UUID userId, UUID recipientId) {
        recipients.delete(userId, recipientId);
        log.info("parent record erased (recipient {})", recipientId);
    }

    /**
     * Erases the caller's account, in one transaction (safe to repeat):
     * <ol>
     *   <li>feedback screenshots are unlinked (the anonymous feedback text stays, as in Python);</li>
     *   <li>{@code erase_caregiver}: circles where they are alone are deleted with their parents' data; in shared circles
     *       they just leave (the longest-standing member becomes owner if they were the only owner);</li>
     *   <li>their pending login codes and the profile itself go (the profile cascades to the password credential and
     *       notifications), so the same phone signs in to an empty new account, never to the old data.</li>
     * </ol>
     * Afterwards, best effort: the old Supabase Auth user of the same id (email login) is removed, as Python did.
     */
    public void deleteAccount(UUID userId) {
        sql.asService(jdbc -> {
            jdbc.update("update public.feedback set images = '{}' where user_id = ?", userId);
            jdbc.query("select public.erase_caregiver(?)", rs -> null, userId);
            jdbc.update("delete from public.otp_codes o using public.profiles p where p.id = ? and p.phone is not null and o.phone = p.phone", userId);
            jdbc.update("delete from public.profiles where id = ?", userId);
            return null;
        });
        deleteSupabaseAuthUser(userId);
        log.info("account erased (user {})", userId);
    }

    /** The account's row in Supabase Auth (email login), if this database has one. Never fails the erasure. */
    private void deleteSupabaseAuthUser(UUID userId) {
        try {
            sql.asService(jdbc -> {
                String table = jdbc.queryForObject("select to_regclass('auth.users')::text", String.class);
                if (table != null) jdbc.update("delete from auth.users where id = ?", userId);
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("account erased, but the Supabase Auth user could not be removed (user {}): {}", userId, e.getClass().getSimpleName());
        }
    }
}
