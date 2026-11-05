package org.smartgurukul.chhaon.child;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartgurukul.chhaon.whatsapp.ReceiverTexts;
import org.smartgurukul.chhaon.whatsapp.WhatsAppSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Vaccine reminders to the FAMILY: an in-app notification ("vaccine_due") to every member of the child's circle, a few days before
 * a dose's due date (in the child's time zone). One reminder per due date: if the family changes the date, the new date is reminded
 * again. Doses already given / marked not given are never reminded; a date already in the past is not reminded either (it shows as
 * overdue on the Vaccines screen).
 *
 * <p>Runs on its own timer (not inside SchedulerService.tick, to stay apart from the care-type changes there), with the same switches:
 * chhaon.scheduler.enabled and chhaon.scheduler.only-recipient.
 *
 * <p>WhatsApp (R2): the person who receives this child's reminders (care_recipients.reminder_to = helper | caregiver: mother, Nani, a
 * nanny; NEVER the child) also gets a short text ({@link ReceiverTexts#vaccineReminder}) about 3 days before a due date and on the day,
 * only while that number agreed (consent) and did not say STOP, and only in the child's daytime (9:00-20:00 on the child's clock). All doses
 * of one child due on one date are one message. Idempotent through child_vaccines.whatsapp_before_for / whatsapp_day_for (migration
 * 20261105000051, same "the due date it was sent for" style as reminded_due_date): claimed before sending, handed back when the send
 * fails (tried again at the next run, still only on the right days). Without that migration nothing goes to WhatsApp. A WhatsApp problem
 * never touches the in-app reminder. The in-app notification itself stays out of the caregiver WhatsApp outbox (whatsapp_skipped_at).
 */
@Service
public class VaccineReminderService {

    private static final Logger log = LoggerFactory.getLogger(VaccineReminderService.class);

    /** Remind when the due date is within this many days from today (0 = the due day itself). */
    public static final int DAYS_BEFORE = 3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final VaccineSchedule schedule;
    private final ObjectMapper json;
    private final WhatsAppSender wa;
    private final boolean enabled;
    private final String onlyRecipient;

    public VaccineReminderService(JdbcTemplate jdbc, PlatformTransactionManager txm, VaccineSchedule schedule, ObjectMapper json,
                                  WhatsAppSender wa,
                                  @Value("${chhaon.scheduler.enabled:false}") boolean enabled,
                                  @Value("${chhaon.scheduler.only-recipient:}") String onlyRecipient) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.schedule = schedule;
        this.json = json;
        this.wa = wa;
        this.enabled = enabled;
        this.onlyRecipient = onlyRecipient == null ? "" : onlyRecipient.trim();
    }

    @Scheduled(fixedDelayString = "${chhaon.vaccines.tick-interval-ms:3600000}",
               initialDelayString = "${chhaon.vaccines.initial-delay-ms:120000}")
    public void scheduledRun() {
        if (!enabled) return;
        UUID scope = null;
        if (!onlyRecipient.isEmpty() && !"all".equalsIgnoreCase(onlyRecipient)) {
            try {
                scope = UUID.fromString(onlyRecipient);
            } catch (IllegalArgumentException e) {
                return;   // an unreadable scope never means "everyone" (same rule as the reminder tick)
            }
        }
        try {
            run(scope);
        } catch (Exception e) {
            log.warn("vaccine reminders failed: {}", e.toString());
        }
    }

    /** One pass. scope = one recipient id (tests, first switch-on) or null for all. Returns how many doses were reminded. */
    public int run(UUID scope) {
        Integer n = tx.execute(status -> {
            List<Object> params = new ArrayList<>();
            params.add(DAYS_BEFORE);
            String scopeSql = "";
            if (scope != null) {
                scopeSql = " and r.id = ?";
                params.add(scope);
            }
            // Claim first (guarded update), so overlapping runs never remind twice.
            List<Map<String, Object>> due = jdbc.queryForList("""
                    update child_vaccines v set reminded_due_date = v.due_date
                    from care_recipients r
                    where r.id = v.recipient_id
                      and r.guardian_confirmed_at is not null
                      and v.given_on is null and not v.not_given
                      and v.reminded_due_date is distinct from v.due_date
                      and v.due_date >= (now() at time zone coalesce(r.timezone, 'Asia/Kolkata'))::date
                      and v.due_date <= (now() at time zone coalesce(r.timezone, 'Asia/Kolkata'))::date + ?
                    """ + scopeSql + """
                     returning v.id, v.recipient_id, r.circle_id, v.vaccine_code, v.dose_label, v.due_date
                    """, params.toArray());
            for (Map<String, Object> row : due) {
                VaccineSchedule.Name name = schedule.name((String) row.get("vaccine_code"));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("recipient_id", row.get("recipient_id").toString());
                payload.put("vaccine_id", row.get("id").toString());
                payload.put("vaccine_en", name.en());
                payload.put("vaccine_hi", name.hi());
                payload.put("dose", row.get("dose_label"));
                payload.put("due_date", row.get("due_date").toString());
                String text;
                try {
                    text = json.writeValueAsString(payload);
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
                // whatsapp_skipped_at: the caregiver WhatsApp outbox never picks this type (it is not in AlertTexts), stated explicitly.
                jdbc.update("""
                        insert into notifications (id, user_id, type, payload, created_at, whatsapp_skipped_at)
                        select gen_random_uuid(), m.user_id, 'vaccine_due', cast(? as jsonb), now(), now()
                        from care_circle_members m where m.circle_id = ?
                        """, text, row.get("circle_id"));
            }
            return due.size();
        });
        int count = n == null ? 0 : n;
        if (count > 0) log.info("vaccine reminders: {} dose(s)", count);   // a count only, never names or dates
        try {
            int sent = sendWhatsApp(scope, false) + sendWhatsApp(scope, true);
            if (sent > 0) log.info("vaccine reminders on WhatsApp: {} message(s)", sent);
        } catch (Exception e) {   // e.g. migration 0051 not applied yet: the in-app reminder above is already done
            log.warn("vaccine WhatsApp reminders skipped: {}", e.getClass().getSimpleName());
        }
        return count;
    }

    /**
     * The WhatsApp to the child's reminder receiver: "due in a few days" (today = false: due 1-3 days from now) or "due today". Returns
     * how many messages went out. Each child + due date is claimed in the database first, then sent; a failed send hands the claim back.
     */
    int sendWhatsApp(UUID scope, boolean dueToday) {
        String column = dueToday ? "whatsapp_day_for" : "whatsapp_before_for";
        String window = dueToday ? "v.due_date = l.today" : "v.due_date between l.today + 1 and l.today + " + DAYS_BEFORE;
        List<Object> params = new ArrayList<>();
        String scopeSql = "";
        if (scope != null) {
            scopeSql = " and r.id = ?";
            params.add(scope);
        }
        List<Map<String, Object>> claimed = jdbc.queryForList(
                "with l as (select r.id, (now() at time zone coalesce(r.timezone, 'Asia/Kolkata'))::date today, " +
                "        extract(hour from now() at time zone coalesce(r.timezone, 'Asia/Kolkata')) local_hour " +
                "        from care_recipients r where r.reminder_to in ('helper', 'caregiver')" + scopeSql + ") " +
                "update child_vaccines v set " + column + " = v.due_date " +
                "from care_recipients r join l on l.id = r.id " +
                "where r.id = v.recipient_id and r.guardian_confirmed_at is not null " +
                "  and r.reminder_to in ('helper', 'caregiver') " +                       // never the child
                "  and r.consent_at is not null and r.opted_out_at is null and r.whatsapp_phone is not null " +
                "  and not exists (select 1 from phone_opt_outs o where o.phone = r.whatsapp_phone) " +
                "  and l.local_hour >= 9 and l.local_hour < 20 " +                                    // the child's daytime
                "  and v.given_on is null and not v.not_given " +
                "  and v." + column + " is distinct from v.due_date and " + window +
                " returning v.id, v.recipient_id, v.vaccine_code, v.dose_label, v.due_date", params.toArray());
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : claimed) {
            groups.computeIfAbsent(row.get("recipient_id") + "|" + row.get("due_date"), k -> new ArrayList<>()).add(row);
        }
        int sent = 0;
        for (List<Map<String, Object>> doses : groups.values()) {
            Map<String, Object> first = doses.get(0);
            try {
                Map<String, Object> r = jdbc.queryForMap(
                        "select r.whatsapp_phone, r.reminder_to, r.reminder_name, r.name, r.call_name, " +
                        ReceiverTexts.languageSql("r") + " language from care_recipients r where r.id = ?", first.get("recipient_id"));
                String language = (String) r.get("language");
                List<String> titles = doses.stream().map(d -> {
                    VaccineSchedule.Name name = schedule.name((String) d.get("vaccine_code"));
                    return ReceiverTexts.vaccineTitle(language, "hi".equals(language) ? name.hi() : name.en(), (String) d.get("dose_label"));
                }).toList();
                LocalDate due = ((java.sql.Date) first.get("due_date")).toLocalDate();
                String text = ReceiverTexts.vaccineReminder(language, (String) r.get("reminder_to"), (String) r.get("reminder_name"),
                        ReceiverTexts.personName((String) r.get("call_name"), (String) r.get("name")), titles, due, dueToday);
                wa.send((String) r.get("whatsapp_phone"), text);
                sent++;
            } catch (Exception e) {
                for (Map<String, Object> d : doses) {
                    jdbc.update("update child_vaccines set " + column + " = null where id = ? and " + column + " = due_date", d.get("id"));
                }
                log.warn("vaccine WhatsApp reminder not sent ({})", WhatsAppSender.failureReason(e));
            }
        }
        return sent;
    }
}
