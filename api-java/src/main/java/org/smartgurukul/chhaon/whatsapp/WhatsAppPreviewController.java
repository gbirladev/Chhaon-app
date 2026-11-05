package org.smartgurukul.chhaon.whatsapp;

import org.smartgurukul.chhaon.child.VaccineSchedule;
import org.smartgurukul.chhaon.recipient.Pregnancy;
import org.smartgurukul.chhaon.scheduler.SchedulerService;
import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The in-app "Preview WhatsApp" screen (R2): the EXACT texts the receiver of one person's reminders would get now, built by the same
 * code as the real send (ConsentService#consentText, SchedulerService#doseText / #receiverDoseText / #checkinText,
 * ReceiverTexts#vaccineReminder, AlertTexts#alert), so the preview can never promise a message that is not sent. Read-only: nothing
 * is sent or changed. Family circle members only (404 otherwise). The response carries no phone numbers.
 */
@RestController
public class WhatsAppPreviewController {

    /** One chat bubble. kind: consent | dose | checkin | vaccine | alert; to: receiver (the reminder number) | family (an alert). */
    public record Message(String kind, String to, String text, Instant at, boolean photo) {}
    /** The reminder text of one active medicine (the screen shows it for the reminders that were really sent). */
    public record DoseText(String medicine, String text) {}
    /**
     * receiverKind: person | caregiver | helper; receiverName in the caller's language ("Kamla (Nani)", "you", "Papa");
     * language: the language the receiver reads; consent: agreed | waiting | stopped | no_number; checkin: whether a daily check-in
     * is sent at all.
     */
    public record Preview(String receiverKind, String receiverName, boolean receiverIsYou, String language, String consent,
                          boolean checkin, List<Message> messages, List<DoseText> doses) {}

    private final JdbcTemplate jdbc;
    private final VaccineSchedule vaccines;

    public WhatsAppPreviewController(JdbcTemplate jdbc, VaccineSchedule vaccines) {
        this.jdbc = jdbc;
        this.vaccines = vaccines;
    }

    @GetMapping("/api/v1/recipients/{recipientId}/whatsapp-preview")
    public Preview preview(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID recipientId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select r.id recipient_id, r.name, r.name recipient_name, r.name person_name, r.call_name, r.is_self, r.reminder_to, " +
                "r.reminder_name, r.reminder_relation, r.reminder_user_id, r.care_type, r.due_date, r.timezone, r.checkin_time, " +
                "to_jsonb(r) ->> 'checkin_enabled' checkin_enabled, " +
                ReceiverTexts.languageSql("r") + " language, (r.whatsapp_phone is not null) has_phone, " +
                "(r.consent_at is not null) consented, (r.opted_out_at is not null or exists (select 1 from phone_opt_outs o " +
                " where o.phone = r.whatsapp_phone)) stopped " +
                "from care_recipients r where r.id = ? and exists (select 1 from care_circle_members m " +
                " where m.circle_id = r.circle_id and m.user_id = ?)", recipientId, user.id());
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Parent profile not found");
        Map<String, Object> r = new HashMap<>(rows.get(0));
        Map<String, Object> me = jdbc.queryForList("select name, language, timezone from profiles where id = ?", user.id())
                .stream().findFirst().orElse(Map.of());
        String uiLanguage = "hi".equals(me.get("language")) ? "hi" : "en";
        String myName = (String) me.get("name");
        boolean self = Boolean.TRUE.equals(r.get("is_self"));
        String reminderTo = self || r.get("reminder_to") == null ? ReceiverTexts.PERSON : (String) r.get("reminder_to");
        boolean isYou = self || (ReceiverTexts.CAREGIVER.equals(reminderTo) && user.id().equals(r.get("reminder_user_id")));
        String person = ReceiverTexts.personName((String) r.get("call_name"), (String) r.get("name"));
        String receiverName = ReceiverTexts.notThePerson(reminderTo)
                ? ReceiverTexts.receiverLabel(uiLanguage, reminderTo, (String) r.get("reminder_name"),
                        (String) r.get("reminder_relation"), isYou)
                : person;
        if (ReceiverTexts.CAREGIVER.equals(reminderTo) && !isYou && r.get("reminder_user_id") != null) {
            // "Me" chosen by another family member: name them (their first name), as the family alerts do
            String theirs = jdbc.queryForList("select name from profiles where id = ?", String.class, r.get("reminder_user_id"))
                    .stream().findFirst().orElse(null);
            receiverName = ReceiverTexts.receiverLabel(uiLanguage, reminderTo, theirs, null, false);
        }
        String consent = !Boolean.TRUE.equals(r.get("has_phone")) ? "no_number"
                : Boolean.TRUE.equals(r.get("stopped")) ? "stopped"
                : Boolean.TRUE.equals(r.get("consented")) ? "agreed" : "waiting";
        ZoneId zone = zoneOf((String) r.get("timezone"));
        Instant now = Instant.now();
        String language = (String) r.get("language");

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("consent", "receiver", ConsentService.consentText(r, myName), null, false));

        // The next dose (its real time), else the first active medicine.
        List<Map<String, Object>> next = jdbc.queryForList(
                "select de.scheduled_at, m.name medicine_name, m.dosage, m.photo_url, to_jsonb(m) ->> 'pill_colour' pill_colour, " +
                "to_jsonb(m) ->> 'pill_shape' pill_shape from dose_events de join medicine_schedules s on s.id = de.schedule_id " +
                "join medicines m on m.id = s.medicine_id where m.recipient_id = ? and m.active and de.status = 'pending' " +
                "and de.scheduled_at > now() order by de.scheduled_at, m.name limit 1", recipientId);
        List<Map<String, Object>> active = jdbc.queryForList(
                "select m.name medicine_name, m.dosage, m.photo_url, to_jsonb(m) ->> 'pill_colour' pill_colour, " +
                "to_jsonb(m) ->> 'pill_shape' pill_shape, (select min(s.time_of_day) from medicine_schedules s " +
                " where s.medicine_id = m.id) first_time " +
                "from medicines m where m.recipient_id = ? and m.active order by m.name limit 30", recipientId);
        Map<String, Object> dose = !next.isEmpty() ? next.get(0) : active.isEmpty() ? null : active.get(0);
        if (dose != null) {
            Instant at = dose.get("scheduled_at") instanceof Timestamp ts ? ts.toInstant() : atTime(dose.get("first_time"), zone, now);
            Map<String, Object> row = new HashMap<>(r);
            row.putAll(dose);
            boolean firstOfItsDay = dose.get("scheduled_at") != null && firstDoseOfDay(recipientId, at, zone);
            messages.add(new Message("dose", "receiver", doseText(row, reminderTo, firstOfItsDay ? newWeek(row, at, zone) : null, at),
                    at, dose.get("photo_url") != null));
        }
        List<DoseText> doses = new ArrayList<>();
        for (Map<String, Object> m : active) {
            Map<String, Object> row = new HashMap<>(r);
            row.putAll(m);
            doses.add(new DoseText((String) m.get("medicine_name"), doseText(row, reminderTo, null, atTime(m.get("first_time"), zone, now))));
        }

        // The daily check-in: only to the person themself, never on the "Me" profile (same rule as SchedulerService#sendCheckins).
        boolean checkin = !self && ReceiverTexts.PERSON.equals(reminderTo)
                && !"false".equals(r.get("checkin_enabled"));   // the per-person switch (migration 0050), same rule as the scheduler
        if (checkin) {
            Instant at = atTime(r.get("checkin_time"), zone, now);
            messages.add(new Message("checkin", "receiver", SchedulerService.checkinText(r, at.atZone(zone).toLocalDate()), at, false));
        }

        // A child's next vaccine (the "due in a few days" text), when someone other than the child gets the reminders.
        if ("child".equals(r.get("care_type")) && ReceiverTexts.notThePerson(reminderTo)) {
            try {
                List<Map<String, Object>> v = jdbc.queryForList(
                        "select vaccine_code, dose_label, due_date from child_vaccines where recipient_id = ? and given_on is null " +
                        "and not not_given and due_date >= ? order by due_date, vaccine_code", recipientId,
                        java.sql.Date.valueOf(now.atZone(zone).toLocalDate()));
                if (!v.isEmpty()) {
                    LocalDate due = ((java.sql.Date) v.get(0).get("due_date")).toLocalDate();
                    List<String> titles = v.stream().filter(x -> due.equals(((java.sql.Date) x.get("due_date")).toLocalDate())).map(x -> {
                        VaccineSchedule.Name n = vaccines.name((String) x.get("vaccine_code"));
                        return ReceiverTexts.vaccineTitle(language, "hi".equals(language) ? n.hi() : n.en(), (String) x.get("dose_label"));
                    }).toList();
                    messages.add(new Message("vaccine", "receiver", ReceiverTexts.vaccineReminder(language, reminderTo,
                            (String) r.get("reminder_name"), person, titles, due, false), null, false));
                }
            } catch (Exception ignored) {
                // no vaccine plan table on this database: no vaccine example
            }
        }

        // An example of the family alert when that dose is not confirmed (in the caller's language; not for the "Me" profile).
        if (dose != null && !self) {
            Instant at = messages.stream().filter(m -> "dose".equals(m.kind())).map(Message::at).findFirst().orElse(now);
            String receiver = ReceiverTexts.receiverLabel(uiLanguage, reminderTo, (String) r.get("reminder_name"),
                    (String) r.get("reminder_relation"), isYou);
            String text = AlertTexts.alert("dose_missed", uiLanguage, person, (String) dose.get("medicine_name"), null,
                    AlertTexts.clock(at, (String) r.get("timezone"), (String) me.get("timezone"), uiLanguage), receiver);
            messages.add(new Message("alert", "family", text, null, false));
        }
        return new Preview(reminderTo, receiverName, isYou, "hi".equals(language) ? "hi" : "en", consent, checkin, messages, doses);
    }

    private static String doseText(Map<String, Object> row, String reminderTo, Integer newWeek, Instant at) {
        return ReceiverTexts.notThePerson(reminderTo) ? SchedulerService.receiverDoseText(row) : SchedulerService.doseText(row, newWeek, at);
    }

    /** True when no other dose of this person is planned earlier on that day (the scheduler says the new week in that one). */
    private boolean firstDoseOfDay(UUID recipientId, Instant at, ZoneId zone) {
        Instant dayStart = at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        Integer earlier = jdbc.queryForObject("select count(*) from dose_events de join medicine_schedules s on s.id = de.schedule_id " +
                        "join medicines m on m.id = s.medicine_id where m.recipient_id = ? and de.scheduled_at >= ? and de.scheduled_at < ?",
                Integer.class, recipientId, Timestamp.from(dayStart), Timestamp.from(at));
        return earlier == null || earlier == 0;
    }

    private static Integer newWeek(Map<String, Object> row, Instant at, ZoneId zone) {
        if (!"pregnant".equals(row.get("care_type")) || !(row.get("due_date") instanceof java.sql.Date d)) return null;
        LocalDate day = at.atZone(zone).toLocalDate();
        Integer week = Pregnancy.week(d.toLocalDate(), day);
        return week == null || week.equals(Pregnancy.week(d.toLocalDate(), day.minusDays(1))) ? null : week;
    }

    /** The next moment the clock in {@code zone} shows that time (today or tomorrow); now when the time is unknown. */
    private static Instant atTime(Object time, ZoneId zone, Instant now) {
        if (!(time instanceof Time t)) return now;
        LocalTime lt = t.toLocalTime();
        ZonedDateTime local = now.atZone(zone);
        ZonedDateTime at = local.toLocalDate().atTime(lt).atZone(zone);
        return (at.isBefore(local) ? at.plusDays(1) : at).toInstant();
    }

    private static ZoneId zoneOf(String tz) {
        try {
            return ZoneId.of(tz == null || tz.isBlank() ? "Asia/Kolkata" : tz);
        } catch (Exception e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }
}
