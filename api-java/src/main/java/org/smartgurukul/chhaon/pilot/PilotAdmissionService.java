package org.smartgurukul.chhaon.pilot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartgurukul.chhaon.whatsapp.ConsentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lets the line move (docs/pilot-cap.md): every few minutes, while there is room, the first people in line get their WhatsApp
 * consent request, exactly as if the family had pressed "Send again", and the caregiver gets a 'pilot_admitted' notification.
 * Stops at the first person who still has to wait (everyone behind them waits too) or at the first error (gateway down: the
 * next run tries again). Runs when the scheduler runs (chhaon.scheduler.enabled) unless chhaon.pilot.admission-enabled says
 * otherwise; off in tests, which call {@link #runOnce()} themselves.
 */
@Service
public class PilotAdmissionService {

    private static final Logger log = LoggerFactory.getLogger(PilotAdmissionService.class);
    private static final int BATCH = 20;

    private final JdbcTemplate jdbc;
    private final ConsentService consent;
    private final PilotService pilot;
    private final boolean enabled;

    public PilotAdmissionService(JdbcTemplate jdbc, ConsentService consent, PilotService pilot,
                                 @Value("${chhaon.pilot.admission-enabled:${chhaon.scheduler.enabled:false}}") boolean enabled) {
        this.jdbc = jdbc;
        this.consent = consent;
        this.pilot = pilot;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${chhaon.pilot.admission-interval-ms:600000}",
               initialDelayString = "${chhaon.pilot.admission-initial-delay-ms:240000}")
    public void scheduledRun() {
        if (!enabled) return;
        try {
            int n = runOnce();
            if (n > 0) log.info("pilot: {} waiting family(ies) let in", n);
        } catch (Exception e) {
            log.warn("pilot admission run failed ({})", e.getClass().getSimpleName());
        }
    }

    /** One pass over the line, in line order. Returns how many consent requests were sent. */
    public int runOnce() {
        if (!pilot.installed()) return 0;
        // Ask as the person who joined the line while they are still in the circle, else as the circle's owner.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select q.recipient_id, coalesce(" +
                "  (select m.user_id from care_circle_members m where m.circle_id = r.circle_id and m.user_id = q.requested_by), " +
                "  (select m.user_id from care_circle_members m where m.circle_id = r.circle_id " +
                "    order by (m.role::text = 'owner') desc, m.created_at limit 1)) user_id " +
                "from pilot_queue q join care_recipients r on r.id = q.recipient_id " +
                "where q.admitted_at is null and r.whatsapp_phone is not null and r.consent_at is null " +
                "  and r.opted_out_at is null and not r.is_canary " +
                "order by q.priority desc, q.moved_up_at desc nulls last, q.queued_at, q.recipient_id limit " + BATCH);
        int sent = 0;
        for (Map<String, Object> row : rows) {
            UUID recipientId = (UUID) row.get("recipient_id");
            UUID userId = (UUID) row.get("user_id");
            if (userId == null) { // nobody left in that circle
                jdbc.update("delete from pilot_queue where recipient_id = ?", recipientId);
                continue;
            }
            ConsentService.Result result;
            try {
                result = consent.requestConsent(userId, recipientId);
            } catch (ResponseStatusException e) {
                if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                    jdbc.update("delete from pilot_queue where recipient_id = ?", recipientId);
                    continue;
                }
                log.warn("pilot admission stopped: WhatsApp unavailable ({})", e.getStatusCode().value());
                break;
            }
            if ("pilot_full".equals(result.status())) break;   // no room yet: everyone behind waits too
            if ("sent".equals(result.status())) {
                sent++;
                notifyAdmitted(recipientId);
            }
            // too_soon / too_many / already_consented / opted_out / no_phone: let in or not ours to send; go on
        }
        return sent;
    }

    /**
     * "It's your turn" for everyone in the circle (web: notif.pilot_admitted). The bell rings in the app, and AlertDelivery (the
     * scheduler's last step) also sends it on WhatsApp to each member who switched WhatsApp alerts on and confirmed it
     * (AlertTexts pilot_admitted), so a family who closed the tab still hears. The canary trigger drops it for the canary family.
     */
    private void notifyAdmitted(UUID recipientId) {
        try {
            jdbc.update("insert into notifications (user_id, type, payload) " +
                    "select m.user_id, 'pilot_admitted', jsonb_build_object('recipient_id', ?::text) " +
                    "from care_circle_members m join care_recipients r on r.circle_id = m.circle_id where r.id = ?",
                    recipientId, recipientId);
        } catch (Exception e) {
            log.warn("pilot_admitted notification not stored ({})", e.getClass().getSimpleName());
        }
    }
}
