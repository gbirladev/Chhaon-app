package org.smartgurukul.chhaon.whatsapp.reply;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decides WHAT a typed reply answers. Without buttons the same word ("1", "yes") can mean different things, so the
 * answer depends on what we asked last. Reads only; {@link ReplyHandlers} acts on the decision.
 *
 * <p>Priority (first match wins):
 * <ol>
 *   <li>STOP always wins (START too, for opting back in).</li>
 *   <li>A caregiver's "turn on alerts?" question is open for this number and is the LAST question we asked on it
 *       (see {@code alertQuestion}): yes / no (or a bare ok) answers it.</li>
 *   <li>A consent question is open for this number: yes / no (or a bare ok) answers it.</li>
 *   <li>The message QUOTES one of our messages (swipe-reply): its id equals the stored whatsapp_message_id of a dose
 *       reminder or a daily check-in, so it answers exactly that one.</li>
 *   <li>Otherwise the most recent dose reminder still awaiting a reply ({@value #SENT_DOSE_HOURS} h at most).</li>
 *   <li>Otherwise today's daily check-in still awaiting a reply; but a plain YES goes to a dose marked missed in the
 *       last {@value #LATE_DOSE_HOURS} h if that reminder was sent AFTER the check-in (it answers what we sent last).</li>
 *   <li>Otherwise a dose that was marked missed in the last {@value #LATE_DOSE_HOURS} h, confirmed late ("taken_late").</li>
 *   <li>Otherwise unknown: one short friendly help text, at most once every few hours per number.</li>
 * </ol>
 * Only the number registered on a parent can be answered; any other sender is ignored without a reply. A number that
 * said STOP (or declined) gets no replies at all except to START.
 *
 * <p>What each intent can answer: a dose accepts YES / TAKEN / TAKEN_ALL (a bare "ok" never counts as taken; NO means
 * "not yet"); a check-in accepts YES / ACK / CHECKIN_OK (I am okay) and NO / CALL_ME (call me); consent accepts YES / ACK and NO.
 */
@Component
public class ReplyRouter {

    static final int SENT_DOSE_HOURS = 12;
    static final int CHECKIN_HOURS = 12;
    static final int LATE_DOSE_HOURS = 6;

    // ---------------------------------------------------------------- the decision

    public sealed interface Route {}

    /** Nothing to do, and no reply (unregistered sender, opted out, no consent...). */
    public record Ignore(String reason) implements Route {}

    /** Friendly message that needs no answer (thanks). */
    public record Silent(String reason) implements Route {}

    public record Stop() implements Route {}

    public record Start() implements Route {}

    public record Consent(UUID recipientId, boolean agree) implements Route {}

    /** A caregiver answered the "turn on alerts?" question we sent to their own number (1 = on, 2 = no). */
    public record AlertOptIn(UUID profileId, boolean agree) implements Route {}

    /** Confirm a dose; {@code wholeSlot} confirms every dose of that parent due at the same moment. */
    public record Dose(UUID doseId, boolean wholeSlot, String via) implements Route {}

    /** "Not yet": nothing is recorded, the parent is gently told how to answer later. */
    public record DoseNotYet(String language) implements Route {}

    public record Checkin(UUID checkinId, boolean callMe, String via) implements Route {}

    /** We could not tell what this answers. Rate-limited reply. */
    public record Help(String language, boolean consentPending) implements Route {}

    private final JdbcTemplate jdbc;
    private volatile boolean checkinIdsKnown;
    private volatile long checkinIdsCheckedAt;

    public ReplyRouter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Route route(InboundMessage m, Intent intent) {
        if (intent == Intent.STOP) return new Stop();

        List<Map<String, Object>> recipients = jdbc.queryForList(
                "select id, language, consent_at, opted_out_at, consent_requested_at " +
                        "from care_recipients where whatsapp_phone = ?", m.phone());
        Map<String, Object> pending = pendingConsent(recipients);

        if (intent == Intent.START) {
            // Typing START under the consent question means "yes, start".
            return pending != null ? new Consent((UUID) pending.get("id"), true) : new Start();
        }

        // (1b) a caregiver's "turn on alerts?" question (the number may ALSO be a parent's number: see alertQuestion)
        if (intent == Intent.YES || intent == Intent.ACK || intent == Intent.NO) {
            Route alert = alertQuestion(m, intent, pending);
            if (alert != null) return alert;
        }

        if (recipients.isEmpty()) return new Ignore("unregistered number");
        if (isOptedOut(m.phone()) || recipients.stream().allMatch(r -> r.get("opted_out_at") != null)) {
            return new Ignore("number opted out");
        }

        // (2) an open consent question
        if (pending != null) {
            if (intent == Intent.YES || intent == Intent.ACK) return new Consent((UUID) pending.get("id"), true);
            if (intent == Intent.NO) return new Consent((UUID) pending.get("id"), false);
        }

        List<Map<String, Object>> active = recipients.stream()
                .filter(r -> r.get("consent_at") != null && r.get("opted_out_at") == null).toList();
        if (active.isEmpty()) {
            return pending != null && intent != Intent.THANKS
                    ? new Help((String) pending.get("language"), true)
                    : new Ignore("no consent given");
        }
        if (intent == Intent.THANKS) return new Silent("thanks");
        String language = (String) active.get(0).get("language");

        // (3) a swipe-reply to one of our messages
        if (m.quotedId() != null) {
            Route viaQuote = routeQuoted(m, intent);
            if (viaQuote != null) return viaQuote;
        }

        // (4) the most recent dose reminder awaiting a reply
        Map<String, Object> sentDose = firstOrNull(jdbc.queryForList(
                "select de.id, r.language from dose_events de " + DOSE_JOIN +
                        "where de.status = 'sent' and de.sent_at > now() - make_interval(hours => ?) " +
                        "and " + ACTIVE_OF_PHONE + " order by de.sent_at desc limit 1",
                SENT_DOSE_HOURS, m.phone()));
        if (sentDose != null) {
            if (intent == Intent.YES || intent == Intent.TAKEN) return new Dose((UUID) sentDose.get("id"), false, "latest");
            if (intent == Intent.TAKEN_ALL) return new Dose((UUID) sentDose.get("id"), true, "latest");
            if (intent == Intent.NO) return new DoseNotYet((String) sentDose.get("language"));
        }

        // (5) today's check-in awaiting a reply
        if (intent == Intent.YES || intent == Intent.ACK || intent == Intent.CHECKIN_OK
                || intent == Intent.NO || intent == Intent.CHECKIN_CALL_ME) {
            Map<String, Object> checkin = firstOrNull(jdbc.queryForList(
                    "select c.id, c.sent_at from checkins c join care_recipients r on r.id = c.recipient_id " +
                            "where c.status in ('sent', 'missed') and c.sent_at > now() - make_interval(hours => ?) " +
                            "and " + ACTIVE_OF_PHONE + " order by (c.status = 'sent') desc, c.sent_at desc limit 1",
                    CHECKIN_HOURS, m.phone()));
            if (checkin != null) {
                // A plain "1" fits both the check-in and a dose that was just marked missed: it answers the message we sent
                // LAST. (Otherwise a late "1" to an afternoon reminder was read as the morning check-in and the dose stayed
                // missed: "0 of 1 taken".)
                if (intent == Intent.YES) {
                    Map<String, Object> missed = missedDose(m.phone());
                    if (missed != null && ((Timestamp) missed.get("sent_at")).after((Timestamp) checkin.get("sent_at"))) {
                        return new Dose((UUID) missed.get("id"), false, "late");
                    }
                }
                boolean call = intent == Intent.NO || intent == Intent.CHECKIN_CALL_ME;
                return new Checkin((UUID) checkin.get("id"), call, "latest");
            }
        }

        // (5b) a dose that was already marked missed a little while ago: a late "taken"
        if (intent == Intent.YES || intent == Intent.TAKEN || intent == Intent.TAKEN_ALL) {
            Map<String, Object> missed = missedDose(m.phone());
            if (missed != null) return new Dose((UUID) missed.get("id"), intent == Intent.TAKEN_ALL, "late");
        }

        // (6) we do not know what this answers
        return new Help(language, false);
    }

    /** The latest dose of this number marked missed in the last {@value #LATE_DOSE_HOURS} h, with when it was sent. */
    private Map<String, Object> missedDose(String phone) {
        return firstOrNull(jdbc.queryForList(
                "select de.id, coalesce(de.sent_at, de.scheduled_at) sent_at from dose_events de " + DOSE_JOIN +
                        "where de.status = 'missed' and de.scheduled_at > now() - make_interval(hours => ?) " +
                        "and " + ACTIVE_OF_PHONE + " order by de.scheduled_at desc limit 1",
                LATE_DOSE_HOURS, phone));
    }

    // ---------------------------------------------------------------- caregiver alert question

    /**
     * An open "turn on alerts?" question for this number: a caregiver asked for alerts on it ({@code profiles.alert_phone},
     * {@code alert_requested_at} set) and it has not answered yet. Only 1 / yes / ok and 2 / no answer it.
     *
     * <p>The same number can also be a parent's number (a caregiver who added themselves, or test data with one phone everywhere).
     * Rule: <b>the reply answers the question we asked LAST.</b> The alert question wins only when it was sent after every
     * question still open for the parent on that number: a pending consent request, a dose reminder or check-in sent in the
     * last {@value #SENT_DOSE_HOURS} h that is still waiting (or was marked missed). A swipe-reply that quotes a dose reminder
     * or check-in always answers that message. A number in {@code phone_opt_outs} is never answered here.
     */
    private Route alertQuestion(InboundMessage m, Intent intent, Map<String, Object> pendingConsent) {
        Map<String, Object> ask = firstOrNull(jdbc.queryForList(
                "select id, alert_requested_at from profiles where alert_phone = ? and alert_requested_at is not null " +
                        "and alert_confirmed_at is null and alert_consent_at is null order by alert_requested_at desc limit 1",
                m.phone()));
        if (ask == null || isOptedOut(m.phone())) return null;
        Timestamp asked = (Timestamp) ask.get("alert_requested_at");
        if (pendingConsent != null && ((Timestamp) pendingConsent.get("consent_requested_at")).after(asked)) return null;
        if (m.quotedId() != null && routeQuoted(m, intent) != null) return null;
        Timestamp lastParentQuestion = jdbc.queryForObject(
                "select greatest(" +
                        "(select max(de.sent_at) from dose_events de " + DOSE_JOIN +
                        " where de.status in ('sent', 'missed') and de.sent_at > now() - make_interval(hours => ?) and " + ACTIVE_OF_PHONE + "), " +
                        "(select max(c.sent_at) from checkins c join care_recipients r on r.id = c.recipient_id " +
                        " where c.status in ('sent', 'missed') and c.sent_at > now() - make_interval(hours => ?) and " + ACTIVE_OF_PHONE + "))",
                Timestamp.class, SENT_DOSE_HOURS, m.phone(), CHECKIN_HOURS, m.phone());
        if (lastParentQuestion != null && lastParentQuestion.after(asked)) return null;
        return new AlertOptIn((UUID) ask.get("id"), intent != Intent.NO);
    }

    // ---------------------------------------------------------------- quote

    private Route routeQuoted(InboundMessage m, Intent intent) {
        Map<String, Object> dose = firstOrNull(jdbc.queryForList(
                "select de.id, r.language from dose_events de " + DOSE_JOIN +
                        "where de.whatsapp_message_id = ? and " + ACTIVE_OF_PHONE + " limit 1",
                m.quotedId(), m.phone()));
        if (dose != null) {
            UUID id = (UUID) dose.get("id");
            return switch (intent) {
                case YES, TAKEN -> new Dose(id, false, "quote");
                case TAKEN_ALL -> new Dose(id, true, "quote");
                case NO -> new DoseNotYet((String) dose.get("language"));
                default -> new Help((String) dose.get("language"), false);
            };
        }
        if (checkinMessageIdsAvailable()) {
            Map<String, Object> checkin = firstOrNull(jdbc.queryForList(
                    "select c.id, r.language from checkins c join care_recipients r on r.id = c.recipient_id " +
                            "where c.whatsapp_message_id = ? and " + ACTIVE_OF_PHONE + " limit 1",
                    m.quotedId(), m.phone()));
            if (checkin != null) {
                UUID id = (UUID) checkin.get("id");
                return switch (intent) {
                    case YES, ACK, CHECKIN_OK -> new Checkin(id, false, "quote");
                    case NO, CHECKIN_CALL_ME -> new Checkin(id, true, "quote");
                    default -> new Help((String) checkin.get("language"), false);
                };
            }
        }
        return null; // quoted something we do not track (a consent message, an old message): fall back to "latest"
    }

    /** True once checkins.whatsapp_message_id exists (migration 20261104000041); re-checked every minute while missing. */
    boolean checkinMessageIdsAvailable() {
        if (checkinIdsKnown) return true;
        long now = System.currentTimeMillis();
        if (now - checkinIdsCheckedAt < 60_000) return false;
        checkinIdsCheckedAt = now;
        Integer n = jdbc.queryForObject(
                "select count(*) from information_schema.columns " +
                        "where table_schema = 'public' and table_name = 'checkins' and column_name = 'whatsapp_message_id'",
                Integer.class);
        checkinIdsKnown = n != null && n > 0;
        return checkinIdsKnown;
    }

    // ---------------------------------------------------------------- helpers

    private static final String DOSE_JOIN =
            "join medicine_schedules s on s.id = de.schedule_id join medicines m on m.id = s.medicine_id " +
                    "join care_recipients r on r.id = m.recipient_id ";

    private static final String ACTIVE_OF_PHONE =
            "r.whatsapp_phone = ? and r.consent_at is not null and r.opted_out_at is null";

    private boolean isOptedOut(String phone) {
        Integer n = jdbc.queryForObject("select count(*) from phone_opt_outs where phone = ?", Integer.class, phone);
        return n != null && n > 0;
    }

    /** The parent record that was last asked for consent and has not answered (several parents can share a number). */
    private static Map<String, Object> pendingConsent(List<Map<String, Object>> recipients) {
        Map<String, Object> best = null;
        for (Map<String, Object> r : recipients) {
            if (r.get("consent_at") != null || r.get("opted_out_at") != null || r.get("consent_requested_at") == null) continue;
            if (best == null || ((Timestamp) r.get("consent_requested_at")).after((Timestamp) best.get("consent_requested_at"))) {
                best = r;
            }
        }
        return best;
    }

    private static Map<String, Object> firstOrNull(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }
}
