package org.smartgurukul.chhaon.child;

import org.smartgurukul.chhaon.db.UserScopedSql;
import org.smartgurukul.chhaon.recipient.CareRecipientRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * A child's date of birth, the guardian confirmation and the vaccination plan (docs/use-cases.md, step 3).
 *
 * <p>Rules: a child's health data is saved only after a family member confirmed "I am the parent or legal guardian" (DPDP Act 2023 +
 * Rules 2025); only members of the child's family circle read or change it (membership check here, Row Level Security in the database
 * for every write and read of the plan); dates are SUGGESTED from the IAP-ACVIP timetable and the family can change every one; nothing
 * changes without the family (the plan is made or re-suggested only when the family asks, and a date the family set is never moved).
 * No analytics, tracking or ads here.
 */
@Service
public class ChildService {

    /** "Due soon" on the screen = due within this many days. */
    public static final int DUE_SOON_DAYS = 30;

    public record ChildInfo(LocalDate dob, OffsetDateTime guardianConfirmedAt, String careType, boolean isSelf,
                            int planDoses, String edition, String source, boolean reviewedByPaediatrician) {}

    public record ChildInput(LocalDate dob, Boolean guardianConfirmed) {}

    public record VaccineRow(String id, String key, String vaccine, String vaccineEn, String vaccineHi, String dose, String group,
                             String note, LocalDate suggestedDueDate, LocalDate dueDate, boolean dueDateByFamily,
                             LocalDate givenOn, boolean notGiven, String notes, String status, String edition,
                             boolean givenDateUnknown, boolean askIfGiven) {}

    /** status: "given" (givenOn required), "not_given", or "due" (open again). dueDate / notes / resetDueDate are optional. */
    public record VaccineInput(String status, LocalDate givenOn, LocalDate dueDate, String notes, Boolean resetDueDate) {}

    /** "Which ones were already given?": doses to mark given (date not recorded) and doses the family says are not given yet. */
    public record GivenEarlierInput(List<UUID> given, List<UUID> notGivenYet) {}

    /** Most ids in one "already given?" call (the whole schedule has fewer doses). */
    static final int GIVEN_EARLIER_MAX = 100;

    private record Recip(UUID id, String timezone, LocalDate dob, OffsetDateTime guardianAt, String careType, boolean isSelf) {
        LocalDate today() {
            ZoneId z;
            try { z = ZoneId.of(timezone == null || timezone.isBlank() ? "Asia/Kolkata" : timezone); }
            catch (Exception e) { z = ZoneId.of("Asia/Kolkata"); }
            return LocalDate.now(z);
        }
    }

    private final UserScopedSql db;
    private final CareRecipientRepository recipients;
    private final VaccineSchedule schedule;

    public ChildService(UserScopedSql db, CareRecipientRepository recipients, VaccineSchedule schedule) {
        this.db = db;
        this.recipients = recipients;
        this.schedule = schedule;
    }

    // ---------------------------------------------------------------- recipient side

    /** 404 unless the user is in the person's family circle. */
    private Recip load(UUID userId, UUID recipientId) {
        recipients.findForUser(recipientId, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Profile not found"));
        // care_type comes with migration 0043 (branch care-type). Read through to_jsonb so this works with and without that column.
        return db.asService(jdbc -> jdbc.queryForObject("""
                        select r.id, r.timezone, r.dob, r.guardian_confirmed_at, to_jsonb(r) ->> 'care_type', r.is_self
                        from care_recipients r where r.id = ?
                        """,
                (rs, i) -> new Recip((UUID) rs.getObject(1), rs.getString(2),
                        rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate(), odt(rs.getTimestamp(4)), rs.getString(5),
                        rs.getBoolean(6)),
                recipientId));
    }

    private static OffsetDateTime odt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    /** A profile that is clearly not a child (the caregiver's own "Me", or a care type other than child once 0043 is in). */
    private static void requireChildProfile(Recip r) {
        if (r.isSelf() || (r.careType() != null && !"child".equals(r.careType()))) {
            throw new ResponseStatusException(CONFLICT, "not_a_child");
        }
    }

    public ChildInfo info(UUID userId, UUID recipientId) {
        Recip r = load(userId, recipientId);
        Integer n = db.asUser(userId, jdbc -> jdbc.queryForObject(
                "select count(*) from child_vaccines where recipient_id = ?", Integer.class, recipientId));
        return new ChildInfo(r.dob(), r.guardianAt(), r.careType(), r.isSelf(), n == null ? 0 : n,
                schedule.edition(), schedule.source(), schedule.reviewedByPaediatrician());
    }

    /**
     * Saves the date of birth and records the guardian confirmation (the first confirmation is kept). The confirmation is needed
     * unless it is already recorded. A changed date of birth moves only the suggested dates the family has not changed or closed.
     */
    public ChildInfo saveChild(UUID userId, UUID recipientId, ChildInput in) {
        Recip r = load(userId, recipientId);
        requireChildProfile(r);
        boolean confirming = in != null && Boolean.TRUE.equals(in.guardianConfirmed());
        if (!confirming && r.guardianAt() == null) throw new ResponseStatusException(BAD_REQUEST, "guardian_confirmation_required");
        LocalDate dob = in == null ? null : in.dob();
        LocalDate today = r.today();
        if (dob == null || dob.isAfter(today) || !dob.isAfter(today.minusYears(18))) {
            throw new ResponseStatusException(BAD_REQUEST, "dob_invalid");   // a child: born, and under 18
        }
        db.asUser(userId, jdbc -> {
            jdbc.update("""
                            update care_recipients set dob = ?,
                                   guardian_confirmed_by = case when guardian_confirmed_at is null then ? else guardian_confirmed_by end,
                                   guardian_confirmed_at = coalesce(guardian_confirmed_at, now())
                            where id = ?
                            """, Date.valueOf(dob), userId, recipientId);
            resuggest(jdbc, recipientId, dob, false);
            return null;
        });
        return info(userId, recipientId);
    }

    // ---------------------------------------------------------------- the plan

    /** Makes the plan from the date of birth (only the doses that are missing), and re-suggests untouched open dates. Idempotent. */
    public List<VaccineRow> makePlan(UUID userId, UUID recipientId) {
        Recip r = load(userId, recipientId);
        requireChildProfile(r);
        if (r.guardianAt() == null) throw new ResponseStatusException(CONFLICT, "guardian_confirmation_required");
        if (r.dob() == null) throw new ResponseStatusException(CONFLICT, "dob_required");
        db.asUser(userId, jdbc -> {
            resuggest(jdbc, recipientId, r.dob(), true);
            return null;
        });
        return list(userId, recipientId);
    }

    /**
     * Inserts missing doses (when {@code insertMissing}) and moves the suggested date of rows whose date the family did not change
     * and that are still open. Runs as the user, so Row Level Security (family member + guardian confirmed) guards every write.
     */
    private void resuggest(JdbcTemplate jdbc, UUID recipientId, LocalDate dob, boolean insertMissing) {
        for (VaccineSchedule.Dose d : schedule.doses()) {
            Date due = Date.valueOf(d.dueFrom(dob));
            if (insertMissing) {
                jdbc.update("""
                                insert into child_vaccines (recipient_id, schedule_key, vaccine_code, dose_label, schedule_edition,
                                                            suggested_due_date, due_date)
                                values (?, ?, ?, ?, ?, ?, ?)
                                on conflict (recipient_id, schedule_key) do nothing
                                """,
                        recipientId, d.key(), d.vaccine(), d.dose(), schedule.edition(), due, due);
            }
            jdbc.update("""
                            update child_vaccines set suggested_due_date = ?,
                                   due_date = case when due_date_by_family or given_on is not null or not_given then due_date else ? end
                            where recipient_id = ? and schedule_key = ? and suggested_due_date is distinct from ?
                            """,
                    due, due, recipientId, d.key(), due);
        }
    }

    public List<VaccineRow> list(UUID userId, UUID recipientId) {
        Recip r = load(userId, recipientId);
        LocalDate today = r.today();
        List<VaccineRow> rows = db.asUser(userId, jdbc -> jdbc.query("""
                        select id, schedule_key, vaccine_code, dose_label, suggested_due_date, due_date, due_date_by_family,
                               given_on, not_given, notes, schedule_edition, given_date_unknown, not_yet_given_at is not null
                        from child_vaccines where recipient_id = ?
                        """,
                (rs, i) -> row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDate(5).toLocalDate(), rs.getDate(6).toLocalDate(), rs.getBoolean(7),
                        rs.getDate(8) == null ? null : rs.getDate(8).toLocalDate(), rs.getBoolean(9), rs.getString(10),
                        rs.getString(11), rs.getBoolean(12), rs.getBoolean(13), today),
                recipientId));
        rows.sort(Comparator.comparing(VaccineRow::dueDate).thenComparing(v -> order(v.key())));
        return rows;
    }

    private int order(String key) {
        VaccineSchedule.Dose d = schedule.dose(key);
        return d == null ? Integer.MAX_VALUE : d.order();
    }

    private VaccineRow row(String id, String key, String code, String dose, LocalDate suggested, LocalDate due, boolean byFamily,
                           LocalDate givenOn, boolean notGiven, String notes, String edition, boolean givenDateUnknown,
                           boolean saidNotYetGiven, LocalDate today) {
        VaccineSchedule.Dose d = schedule.dose(key);
        VaccineSchedule.Name name = schedule.name(code);
        String status = givenOn != null ? "given"
                : notGiven ? "not_given"
                : due.isBefore(today) ? "overdue"
                : ChronoUnit.DAYS.between(today, due) <= DUE_SOON_DAYS ? "due_soon"
                : "later";
        return new VaccineRow(id, key, code, name.en(), name.hi(), dose, d == null ? null : d.group(), d == null ? null : d.note(),
                suggested, due, byFamily, givenOn, notGiven, notes, status, edition, givenOn != null && givenDateUnknown,
                // askIfGiven: the date has passed and the family has not said "not given yet" -> the screen asks instead of "overdue".
                "overdue".equals(status) && !saidNotYetGiven);
    }

    /** Mark a dose given (with the date) / not given / open again, change its date, or go back to the suggested date. */
    public VaccineRow update(UUID userId, UUID vaccineId, VaccineInput in) {
        if (in == null) throw new ResponseStatusException(BAD_REQUEST, "empty");
        // Row Level Security limits this read to the user's families: someone else's id is simply "not found".
        List<Map<String, Object>> found = db.asUser(userId, jdbc -> jdbc.queryForList(
                "select recipient_id, notes from child_vaccines where id = ?", vaccineId));
        if (found.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Vaccine not found");
        UUID recipientId = (UUID) found.get(0).get("recipient_id");
        Recip r = load(userId, recipientId);
        if (r.guardianAt() == null) throw new ResponseStatusException(CONFLICT, "guardian_confirmation_required");
        LocalDate today = r.today();
        LocalDate dob = r.dob();

        String status = in.status() == null ? null : in.status().trim();
        if (status != null && !List.of("given", "not_given", "due").contains(status)) {
            throw new ResponseStatusException(BAD_REQUEST, "status_invalid");
        }
        if ("given".equals(status)) {
            LocalDate g = in.givenOn();
            if (g == null || g.isAfter(today.plusDays(1)) || (dob != null && g.isBefore(dob))) {
                throw new ResponseStatusException(BAD_REQUEST, "given_on_invalid");
            }
        }
        boolean reset = Boolean.TRUE.equals(in.resetDueDate());
        LocalDate due = in.dueDate();
        if (due != null && !reset) {
            LocalDate min = dob != null ? dob : today.minusYears(19);
            if (due.isBefore(min) || due.isAfter(min.plusYears(19))) throw new ResponseStatusException(BAD_REQUEST, "due_date_invalid");
        }
        String notes = in.notes() == null ? (String) found.get(0).get("notes")
                : in.notes().replaceAll("[\\x00-\\x1f\\x7f]+", " ").trim();
        if (notes != null && notes.isEmpty()) notes = null;
        if (notes != null && notes.length() > 300) throw new ResponseStatusException(BAD_REQUEST, "notes_too_long");

        String finalNotes = notes;
        db.asUser(userId, jdbc -> {
            if (status != null) {
                // A date given here is a real date (given_date_unknown off). "Open again" for a dose whose date has passed is the family
                // saying "not given yet": from then on the screen calls it overdue (migration 0046).
                jdbc.update("""
                                update child_vaccines set given_on = ?, not_given = ?, given_date_unknown = false,
                                       not_yet_given_at = case when ? and due_date < ? then coalesce(not_yet_given_at, now())
                                                               else not_yet_given_at end
                                where id = ?
                                """,
                        "given".equals(status) ? Date.valueOf(in.givenOn()) : null, "not_given".equals(status),
                        "due".equals(status), Date.valueOf(today), vaccineId);
            }
            if (reset) {
                jdbc.update("update child_vaccines set due_date = suggested_due_date, due_date_by_family = false where id = ?", vaccineId);
            } else if (due != null) {
                jdbc.update("update child_vaccines set due_date = ?, due_date_by_family = true where id = ? and due_date is distinct from ?",
                        Date.valueOf(due), vaccineId, Date.valueOf(due));
            }
            jdbc.update("update child_vaccines set notes = ? where id = ?", finalNotes, vaccineId);
            return null;
        });
        return list(userId, recipientId).stream().filter(v -> v.id().equals(vaccineId.toString())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Vaccine not found"));
    }

    /**
     * "Which ones were already given?" for a child set up after birth: marks many past doses given in one call (the date is not recorded:
     * given_on holds the plan date as a placeholder, flagged by given_date_unknown) and records "not given yet" for others. Same rules as
     * {@link #update}: family circle only (404 otherwise), guardian confirmation required (409), every write as the user under Row Level
     * Security. Only doses of this child whose date has passed; doses already given or marked "not given" are left as they are, so the
     * call is idempotent (a dose given with a real date keeps it).
     */
    public List<VaccineRow> givenEarlier(UUID userId, UUID recipientId, GivenEarlierInput in) {
        Recip r = load(userId, recipientId);
        requireChildProfile(r);
        if (r.guardianAt() == null) throw new ResponseStatusException(CONFLICT, "guardian_confirmation_required");
        List<UUID> given = in == null || in.given() == null ? List.of() : in.given().stream().distinct().toList();
        List<UUID> notYet = in == null || in.notGivenYet() == null ? List.of() : in.notGivenYet().stream().distinct().toList();
        if (given.contains(null) || notYet.contains(null)) throw new ResponseStatusException(BAD_REQUEST, "vaccine_invalid");
        if (given.size() + notYet.size() > GIVEN_EARLIER_MAX) throw new ResponseStatusException(BAD_REQUEST, "too_many");
        if (given.stream().anyMatch(notYet::contains)) throw new ResponseStatusException(BAD_REQUEST, "given_and_not_given");
        if (given.isEmpty() && notYet.isEmpty()) return list(userId, recipientId);
        LocalDate today = r.today();
        List<UUID> all = new ArrayList<>(given);
        all.addAll(notYet);
        db.asUser(userId, jdbc -> {
            // Every id must be a dose of THIS child whose date has passed (Row Level Security hides other families' doses anyway).
            List<Object> args = new ArrayList<>(all);
            args.add(recipientId);
            args.add(Date.valueOf(today));
            Integer ok = jdbc.queryForObject("select count(*) from child_vaccines where id in (" + marks(all.size())
                    + ") and recipient_id = ? and due_date < ?", Integer.class, args.toArray());
            if (ok == null || ok != all.size()) throw new ResponseStatusException(BAD_REQUEST, "vaccine_not_past_due");
            if (!given.isEmpty()) {
                List<Object> a = new ArrayList<>(given);
                a.add(recipientId);
                jdbc.update("update child_vaccines set given_on = due_date, given_date_unknown = true where id in (" + marks(given.size())
                        + ") and recipient_id = ? and given_on is null and not not_given", a.toArray());
            }
            if (!notYet.isEmpty()) {
                List<Object> a = new ArrayList<>(notYet);
                a.add(recipientId);
                jdbc.update("update child_vaccines set not_yet_given_at = now() where id in (" + marks(notYet.size())
                        + ") and recipient_id = ? and given_on is null and not not_given and not_yet_given_at is null", a.toArray());
            }
            return null;
        });
        return list(userId, recipientId);
    }

    private static String marks(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }
}
