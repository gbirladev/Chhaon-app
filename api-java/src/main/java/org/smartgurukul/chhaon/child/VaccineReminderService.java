package org.smartgurukul.chhaon.child;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p>TODO(reminder-receiver merge): also send a WhatsApp message to the person who receives this child's reminders (the adult who gives
 * the medicine: mother, Nani, a nanny), using the receiver table of migration 20261105000044, its consent and a text in MessageTexts.
 * Never to the child. Not built here so as not to touch that work. The notification payload carries no health details beyond the vaccine
 * name, and the AlertDelivery WhatsApp outbox ignores this type, so nothing goes to WhatsApp today.
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
    private final boolean enabled;
    private final String onlyRecipient;

    public VaccineReminderService(JdbcTemplate jdbc, PlatformTransactionManager txm, VaccineSchedule schedule, ObjectMapper json,
                                  @Value("${chhaon.scheduler.enabled:false}") boolean enabled,
                                  @Value("${chhaon.scheduler.only-recipient:}") String onlyRecipient) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.schedule = schedule;
        this.json = json;
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
        return count;
    }
}
