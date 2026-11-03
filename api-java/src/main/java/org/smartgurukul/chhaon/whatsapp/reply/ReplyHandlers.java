package org.smartgurukul.chhaon.whatsapp.reply;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartgurukul.chhaon.whatsapp.WhatsAppSender;
import org.smartgurukul.chhaon.whatsapp.reply.ReplyRouter.Checkin;
import org.smartgurukul.chhaon.whatsapp.reply.ReplyRouter.Consent;
import org.smartgurukul.chhaon.whatsapp.reply.ReplyRouter.Dose;
import org.smartgurukul.chhaon.whatsapp.reply.ReplyRouter.DoseNotYet;
import org.smartgurukul.chhaon.whatsapp.reply.ReplyRouter.Help;
import org.smartgurukul.chhaon.whatsapp.reply.ReplyRouter.Route;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Does what the {@link ReplyRouter} decided, using the same database functions and guarded updates as the Python
 * webhook ({@code api/app/services/inbound.py}). Runs inside ONE database transaction owned by the caller and returns
 * the replies to send after it committed. Never logs message text; phone numbers only masked.
 */
@Component
public class ReplyHandlers {

    private static final Logger log = LoggerFactory.getLogger(ReplyHandlers.class);

    /** At most one "I did not understand" text per number in this many hours, so a chat can never loop. */
    static final int HELP_RATE_LIMIT_HOURS = 6;

    private static final Pattern TABLETS = Pattern.compile("\\s*(\\d+(?:\\.\\d+)?)\\s+(?:tablets?|capsules?)\\s*");

    private final JdbcTemplate jdbc;
    private final CircleNotifier notifier;

    public ReplyHandlers(JdbcTemplate jdbc, CircleNotifier notifier) {
        this.jdbc = jdbc;
        this.notifier = notifier;
    }

    public List<OutboundReply> handle(Route route, InboundMessage m) {
        List<OutboundReply> out = new ArrayList<>();
        switch (route) {
            case ReplyRouter.Stop s -> stop(m, out);
            case ReplyRouter.Start s -> start(m, out);
            case Consent c -> consent(c, m, out);
            case Dose d -> dose(d, m, out);
            case DoseNotYet n -> out.add(new OutboundReply(m.phone(), ReplyTexts.doseNotYet(n.language())));
            case Checkin c -> checkin(c, m, out);
            case Help h -> help(h, m, out);
            case ReplyRouter.Silent s -> { }
            case ReplyRouter.Ignore i -> log.debug("reply ignored: {}", i.reason());
        }
        return out;
    }

    // ---------------------------------------------------------------- STOP / START (phone semantics)

    /** STOP belongs to the NUMBER: it is recorded even if no parent carries it (now), and outlives any parent record. */
    private void stop(InboundMessage m, List<OutboundReply> out) {
        jdbc.update("insert into phone_opt_outs (phone) values (?) on conflict (phone) do nothing", m.phone());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, circle_id, language from care_recipients where whatsapp_phone = ? and opted_out_at is null",
                m.phone());
        for (Map<String, Object> r : rows) {
            jdbc.update("update care_recipients set opted_out_at = now() where id = ?", r.get("id"));
            notifier.notifyCircle((UUID) r.get("circle_id"), "parent_stopped", Map.of("recipient_id", r.get("id").toString()));
        }
        List<Map<String, Object>> alerts = jdbc.queryForList(
                "select id, language from profiles where alert_phone = ? and alert_consent_at is not null", m.phone());
        for (Map<String, Object> p : alerts) {
            jdbc.update("update profiles set alert_consent_at = null where id = ?", p.get("id"));
        }
        if (!rows.isEmpty()) {
            out.add(new OutboundReply(m.phone(), ReplyTexts.stopped((String) rows.get(0).get("language"))));
        } else if (!alerts.isEmpty()) {
            out.add(new OutboundReply(m.phone(), ReplyTexts.alertsOff((String) alerts.get(0).get("language"))));
        }
    }

    /** START from a parent who had opted out (STOP) or declined (No). Writing START is an explicit agreement. */
    private void start(InboundMessage m, List<OutboundReply> out) {
        jdbc.update("delete from phone_opt_outs where phone = ?", m.phone());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, circle_id, language, consent_at from care_recipients " +
                        "where whatsapp_phone = ? and opted_out_at is not null", m.phone());
        for (Map<String, Object> r : rows) {
            boolean hadConsent = r.get("consent_at") != null;
            jdbc.update("update care_recipients set opted_out_at = null, consent_at = coalesce(consent_at, now()) where id = ?",
                    r.get("id"));
            notifier.notifyCircle((UUID) r.get("circle_id"), hadConsent ? "parent_resumed" : "parent_consented",
                    Map.of("recipient_id", r.get("id").toString()));
        }
        // Caregiver alerts come back only for a number that confirmed them once (never for one merely typed in).
        List<Map<String, Object>> alerts = jdbc.queryForList(
                "select id, language from profiles where alert_phone = ? " +
                        "and alert_confirmed_at is not null and alert_consent_at is null", m.phone());
        for (Map<String, Object> p : alerts) {
            jdbc.update("update profiles set alert_consent_at = now() where id = ?", p.get("id"));
        }
        if (!rows.isEmpty()) {
            out.add(new OutboundReply(m.phone(), ReplyTexts.resumed((String) rows.get(0).get("language"))));
        } else if (!alerts.isEmpty()) {
            out.add(new OutboundReply(m.phone(), ReplyTexts.alertsResumed((String) alerts.get(0).get("language"))));
        }
    }

    // ---------------------------------------------------------------- consent

    private void consent(Consent c, InboundMessage m, List<OutboundReply> out) {
        Map<String, Object> r = firstOrNull(jdbc.queryForList(
                "select id, circle_id, language, whatsapp_phone from care_recipients where id = ?", c.recipientId()));
        if (r == null || !m.phone().equals(r.get("whatsapp_phone"))) {
            log.warn("ignored a consent reply from an unexpected number {}", WhatsAppSender.mask(m.phone()));
            return;
        }
        UUID circleId = (UUID) r.get("circle_id");
        String language = (String) r.get("language");
        String recipientId = r.get("id").toString();
        if (c.agree()) {
            jdbc.update("delete from phone_opt_outs where phone = ?", m.phone());
            // Each transition is claimed with a guarded UPDATE, so a repeated answer never notifies or replies twice.
            int claimed = jdbc.update("update care_recipients set consent_at = now(), opted_out_at = null " +
                    "where id = ? and (consent_at is null or opted_out_at is not null)", r.get("id"));
            if (claimed > 0) {
                notifier.notifyCircle(circleId, "parent_consented", Map.of("recipient_id", recipientId));
                out.add(new OutboundReply(m.phone(), ReplyTexts.consentYes(language)));
            }
        } else {
            jdbc.update("insert into phone_opt_outs (phone) values (?) on conflict (phone) do nothing", m.phone());
            int claimed = jdbc.update("update care_recipients set opted_out_at = now() where id = ? and opted_out_at is null",
                    r.get("id"));
            if (claimed > 0) {
                notifier.notifyCircle(circleId, "parent_declined", Map.of("recipient_id", recipientId));
                out.add(new OutboundReply(m.phone(), ReplyTexts.consentNo(language)));
            }
        }
    }

    // ---------------------------------------------------------------- doses

    /** Tablets used by one dose: "2 tablets" -> 2, "1.5 capsules" -> 2 (rounded up); anything else counts as 1; capped at 10. */
    static int unitsPerDose(String dosage) {
        Matcher match = TABLETS.matcher(dosage == null ? "" : dosage.toLowerCase());
        if (!match.matches()) return 1;
        return Math.min(Math.max(1, (int) Math.ceil(Double.parseDouble(match.group(1)))), 10);
    }

    private void dose(Dose d, InboundMessage m, List<OutboundReply> out) {
        Map<String, Object> row = firstOrNull(jdbc.queryForList(
                "select m.id medicine_id, m.name, m.dosage, r.language, r.whatsapp_phone " +
                        "from dose_events de join medicine_schedules s on s.id = de.schedule_id " +
                        "join medicines m on m.id = s.medicine_id join care_recipients r on r.id = m.recipient_id " +
                        "where de.id = ?", d.doseId()));
        if (row == null || !m.phone().equals(row.get("whatsapp_phone"))) {
            log.warn("ignored a dose reply from an unexpected number {}", WhatsAppSender.mask(m.phone()));
            return;
        }
        String language = (String) row.get("language");
        List<String> noted = new ArrayList<>();
        if (d.wholeSlot()) {
            // Every dose of this parent due at that same moment, in one atomic step; stock is used once per medicine newly confirmed.
            for (Map<String, Object> confirmed : jdbc.queryForList(
                    "select medicine_id, dosage from public.confirm_dose_slot(?)", d.doseId())) {
                useStock((UUID) confirmed.get("medicine_id"), unitsPerDose((String) confirmed.get("dosage")));
                noted.add(medicineName((UUID) confirmed.get("medicine_id")));
            }
        } else {
            // Only a reminder we actually SENT can be confirmed; a late reply after 'missed' is kept apart as taken_late;
            // only the FIRST confirmation uses up stock (answers can repeat).
            int first = jdbc.update("update dose_events set status = 'taken', responded_at = now() " +
                    "where id = ? and status = 'sent'", d.doseId());
            if (first == 0) {
                first = jdbc.update("update dose_events set status = 'taken_late', responded_at = now() " +
                        "where id = ? and status = 'missed'", d.doseId());
            }
            if (first > 0) {
                useStock((UUID) row.get("medicine_id"), unitsPerDose((String) row.get("dosage")));
                noted.add((String) row.get("name"));
            }
        }
        if (noted.isEmpty()) return; // a repeated answer: nothing new, nothing to say
        String text = ReplyTexts.doseTaken(language, String.join(", ", noted));
        if (remainingSentDoses(m.phone()) > 0) {
            text += "\n" + ReplyTexts.nextDoseHint(language);
        } else if (openCheckin(m.phone())) {
            text += "\n" + ReplyTexts.checkinHint(language);
        }
        out.add(new OutboundReply(m.phone(), text));
    }

    private void useStock(UUID medicineId, int units) {
        // The database raises the refill alert by itself when this drops stock to the refill level.
        jdbc.queryForList("select public.consume_stock(?, ?)", medicineId, units);
    }

    private String medicineName(UUID medicineId) {
        return jdbc.queryForObject("select name from medicines where id = ?", String.class, medicineId);
    }

    private int remainingSentDoses(String phone) {
        Integer n = jdbc.queryForObject(
                "select count(*) from dose_events de join medicine_schedules s on s.id = de.schedule_id " +
                        "join medicines m on m.id = s.medicine_id join care_recipients r on r.id = m.recipient_id " +
                        "where de.status = 'sent' and de.sent_at > now() - make_interval(hours => ?) " +
                        "and r.whatsapp_phone = ? and r.consent_at is not null and r.opted_out_at is null",
                Integer.class, ReplyRouter.SENT_DOSE_HOURS, phone);
        return n == null ? 0 : n;
    }

    private boolean openCheckin(String phone) {
        Integer n = jdbc.queryForObject(
                "select count(*) from checkins c join care_recipients r on r.id = c.recipient_id " +
                        "where c.status = 'sent' and c.sent_at > now() - make_interval(hours => ?) " +
                        "and r.whatsapp_phone = ? and r.consent_at is not null and r.opted_out_at is null",
                Integer.class, ReplyRouter.CHECKIN_HOURS, phone);
        return n != null && n > 0;
    }

    // ---------------------------------------------------------------- check-in

    private void checkin(Checkin c, InboundMessage m, List<OutboundReply> out) {
        Map<String, Object> row = firstOrNull(jdbc.queryForList(
                "select r.id recipient_id, r.circle_id, r.language, r.whatsapp_phone " +
                        "from checkins c join care_recipients r on r.id = c.recipient_id where c.id = ?", c.checkinId()));
        if (row == null || !m.phone().equals(row.get("whatsapp_phone"))) {
            log.warn("ignored a check-in reply from an unexpected number {}", WhatsAppSender.mask(m.phone()));
            return;
        }
        // Atomic: only the update that really flips the status to 'replied' is the first reply, so a repeated answer
        // (a panicking parent, a duplicate) creates exactly ONE call-request notification.
        int first = jdbc.update("update checkins set status = 'replied', responded_at = now() " +
                "where id = ? and status <> 'replied'", c.checkinId());
        if (first == 0) return;
        String language = (String) row.get("language");
        if (c.callMe()) {
            notifier.notifyCircle((UUID) row.get("circle_id"), "checkin_call_requested",
                    Map.of("recipient_id", row.get("recipient_id").toString()));
            out.add(new OutboundReply(m.phone(), ReplyTexts.checkinCall(language)));
        } else {
            out.add(new OutboundReply(m.phone(), ReplyTexts.checkinOk(language)));
        }
    }

    // ---------------------------------------------------------------- help (rate limited)

    private void help(Help h, InboundMessage m, List<OutboundReply> out) {
        // One row per number in whatsapp_events; it only counts as "free again" after HELP_RATE_LIMIT_HOURS.
        String key = "help:" + hash(m.phone());
        int claimed = jdbc.update("insert into whatsapp_events (id, received_at) values (?, now()) " +
                        "on conflict (id) do update set received_at = now() " +
                        "where whatsapp_events.received_at < now() - make_interval(hours => ?)",
                key, HELP_RATE_LIMIT_HOURS);
        if (claimed == 0) return;
        out.add(new OutboundReply(m.phone(),
                h.consentPending() ? ReplyTexts.consentHelp(h.language()) : ReplyTexts.help(h.language())));
    }

    private static String hash(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> firstOrNull(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }
}
