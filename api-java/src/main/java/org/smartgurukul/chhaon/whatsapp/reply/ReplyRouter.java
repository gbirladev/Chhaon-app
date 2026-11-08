package org.smartgurukul.chhaon.whatsapp.reply;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartgurukul.chhaon.whatsapp.ReminderTexts;
import org.smartgurukul.chhaon.whatsapp.SampleReminderService;
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
 * Only the number registered on a parent can be answered; any other sender is ignored without a reply. That number is the
 * one that RECEIVES the reminders (care_recipients.whatsapp_phone): the person, the caregiver ("Me") or a helper
 * (docs/reminder-receiver.md). One number can receive reminders for several people (a maid for Papa and Mummy, a caregiver for
 * a parent and for their own medicines): a reply answers the most recent open reminder sent to that number, whoever it is
 * for, and the confirmation names the person. A number that
 * said STOP (or declined) gets no replies at all except to START.
 *
 * <p>What each intent can answer: a dose accepts YES / TAKEN / TAKEN_ALL (a bare "ok" never counts as taken; NO means
 * "not yet"); a check-in accepts YES / ACK / CHECKIN_OK (I am okay) and NO / CALL_ME (call me); consent accepts YES / ACK and NO.
 * One reminder covers one time slot (every medicine of the person due at that moment), so a "yes" to a dose confirms the
 * whole slot ({@code confirm_dose_slot}).
 *
 * <p>Poll votes ({@link #routePollVote}) answer exactly the poll they were cast on, like a reaction.
 */
@Component
public class ReplyRouter {

    private static final Logger log = LoggerFactory.getLogger(ReplyRouter.class);

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

    /**
     * Confirm a dose; {@code wholeSlot} confirms every dose of that parent due at the same moment. Since one reminder covers one
     * time slot, every route below now confirms the whole slot; {@code wholeSlot = false} is kept for callers that need one dose.
     * {@code via}: latest | quote | late | reaction | poll (a poll vote gets no "reply 1 again" hint).
     */
    public record Dose(UUID doseId, boolean wholeSlot, String via) implements Route {}

    /**
     * "Not yet": nothing is recorded, the parent is gently told how to answer later. {@code doseId} = the reminder it answers
     * (so a helper is told whose medicine; may be null).
     */
    public record DoseNotYet(String language, UUID doseId) implements Route {
        public DoseNotYet(String language) {
            this(language, null);
        }
    }

    public record Checkin(UUID checkinId, boolean callMe, String via) implements Route {}

    /** A poll vote "⏰ Later" on a dose reminder: nothing is recorded (the missed window still runs), a short friendly ack. */
    public record DoseLater(UUID doseId) implements Route {}

    /**
     * A reply, tap or reaction on the SAMPLE reminder (docs/sample-reminder.md): nothing is recorded, the receiver is told it was a
     * sample and when the real reminders start.
     */
    public record Sample(UUID recipientId) implements Route {}

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

        // (2b) an answer to the sample reminder: never a dose (docs/sample-reminder.md)
        UUID sample = sampleAnswered(m, intent);
        if (sample != null) return new Sample(sample);

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
            // One reminder = one time slot: "1" confirms every medicine of that reminder (TAKEN_ALL means the same).
            if (intent == Intent.YES || intent == Intent.TAKEN || intent == Intent.TAKEN_ALL) {
                return new Dose((UUID) sentDose.get("id"), true, "latest");
            }
            if (intent == Intent.NO) return new DoseNotYet((String) sentDose.get("language"), (UUID) sentDose.get("id"));
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
                        return new Dose((UUID) missed.get("id"), true, "late");
                    }
                }
                boolean call = intent == Intent.NO || intent == Intent.CHECKIN_CALL_ME;
                return new Checkin((UUID) checkin.get("id"), call, "latest");
            }
        }

        // (5b) a dose that was already marked missed a little while ago: a late "taken"
        if (intent == Intent.YES || intent == Intent.TAKEN || intent == Intent.TAKEN_ALL) {
            Map<String, Object> missed = missedDose(m.phone());
            if (missed != null) return new Dose((UUID) missed.get("id"), true, "late");
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

    // ---------------------------------------------------------------- 👍 reaction (F6)

    /** How long a reaction to a reminder / check-in still counts (on when it was sent). Older: ignored. */
    static final int REACTION_HOURS = 12;

    /**
     * True when a reaction emoji means "yes": exactly the emoji a typed reply already reads as YES (👍 in any skin tone, ✅,
     * ✔️, ☑️, 🆗) and nothing else. Deliberately NOT yes: ❤️ (affection, often put on any family message), 🙏 (thanks /
     * namaste, read as THANKS when typed), 😂 😮 😢, and of course 👎 / ❌. Never letters or digits.
     */
    public static boolean reactionMeansYes(String emoji) {
        return emoji != null && emoji.length() <= 16 && ReplyClassifier.wordsOf(emoji).isEmpty()
                && ReplyClassifier.classify(emoji) == Intent.YES;
    }

    /**
     * What a 👍 reaction answers: ONLY the message it was put on ({@code m.quotedId()} = the reacted message id), never "the
     * latest open question". Checked in this order:
     * <ol>
     *   <li>a consent request still open for this number (its id was remembered when it was sent, {@link #consentMarker}) -> agree;</li>
     *   <li>a dose reminder sent to this number in the last {@value #REACTION_HOURS} h, still sent or missed -> taken (taken_late);</li>
     *   <li>a daily check-in sent to this number in the last {@value #REACTION_HOURS} h, still sent or missed -> "I am fine".</li>
     * </ol>
     * Anything else (an old, answered or unknown message, a number that said STOP) is ignored without any reply. A reaction
     * never starts messages again (START must be typed) and never asks for a call.
     */
    public Route routeReaction(InboundMessage m) {
        String target = m.quotedId();
        if (target == null) return new Ignore("reaction without a target");
        List<Map<String, Object>> recipients = jdbc.queryForList(
                "select id, consent_at, opted_out_at, consent_requested_at from care_recipients where whatsapp_phone = ? " +
                        "order by consent_requested_at desc nulls last", m.phone());
        if (recipients.isEmpty()) return new Ignore("unregistered number");
        if (isOptedOut(m.phone()) || recipients.stream().allMatch(r -> r.get("opted_out_at") != null)) {
            return new Ignore("number opted out");
        }
        for (Map<String, Object> r : recipients) {
            if (r.get("consent_at") != null || r.get("opted_out_at") != null || r.get("consent_requested_at") == null) continue;
            Integer n = jdbc.queryForObject("select count(*) from whatsapp_events where id = ?", Integer.class,
                    consentMarker(target, (UUID) r.get("id")));
            if (n != null && n > 0) return new Consent((UUID) r.get("id"), true);
        }
        Map<String, Object> dose = firstOrNull(jdbc.queryForList(
                "select de.id from dose_events de " + DOSE_JOIN +
                        "where de.whatsapp_message_id = ? and de.status in ('sent', 'missed') " +
                        "and coalesce(de.sent_at, de.scheduled_at) > now() - make_interval(hours => ?) and " + ACTIVE_OF_PHONE +
                        " limit 1", target, REACTION_HOURS, m.phone()));
        if (dose != null) return new Dose((UUID) dose.get("id"), true, "reaction");
        UUID sample = sampleByMessage(m.phone(), target);
        if (sample != null) return new Sample(sample);
        if (checkinMessageIdsAvailable()) {
            Map<String, Object> checkin = firstOrNull(jdbc.queryForList(
                    "select c.id from checkins c join care_recipients r on r.id = c.recipient_id " +
                            "where c.whatsapp_message_id = ? and c.status in ('sent', 'missed') " +
                            "and c.sent_at > now() - make_interval(hours => ?) and " + ACTIVE_OF_PHONE + " limit 1",
                    target, REACTION_HOURS, m.phone()));
            if (checkin != null) return new Checkin((UUID) checkin.get("id"), false, "reaction");
        }
        return new Ignore("reaction to an unknown, old or answered message");
    }

    /**
     * The {@code whatsapp_events} row that remembers which WhatsApp message asked a parent for consent (no migration needed:
     * the table already holds non-inbound keys such as the help rate limit). Written by ConsentService after the send.
     */
    public static String consentMarker(String messageId, UUID recipientId) {
        return "gw:out:consent:" + recipientId + ":" + messageId;
    }

    // ---------------------------------------------------------------- poll vote

    /** How long a vote on a reminder / check-in poll still counts (on when it was sent). Older: ignored. */
    static final int POLL_HOURS = 12;

    /**
     * What a vote on one of our polls answers: ONLY that poll ({@code pollKeyId} = the message id stored on every dose of the
     * slot, or on the check-in), sent to this number in the last {@value #POLL_HOURS} h. The option is read by label and index
     * ({@link ReminderTexts#doseVote}); an option we cannot recognise is ignored.
     * <ul>
     *   <li>Dose poll, "✅ Taken / Given": the whole slot is confirmed (taken, or taken_late after missed).</li>
     *   <li>Dose poll, "⏰ Later": nothing changes, a short ack. A vote changed to Later AFTER the slot was confirmed is NOT
     *       undone (logged only).</li>
     *   <li>Check-in poll: "😊 I'm fine" = okay, "📞 Please call me" = the family is told (also after an earlier "fine").</li>
     * </ul>
     * Numbers that said STOP, other numbers and unknown / old polls are ignored without any reply.
     */
    public Route routePollVote(String phone, String pollKeyId, String label, Integer index) {
        if (pollKeyId == null) return new Ignore("vote without a poll id");
        List<Map<String, Object>> recipients = jdbc.queryForList(
                "select id, opted_out_at from care_recipients where whatsapp_phone = ?", phone);
        if (recipients.isEmpty()) return new Ignore("unregistered number");
        if (isOptedOut(phone) || recipients.stream().allMatch(r -> r.get("opted_out_at") != null)) {
            return new Ignore("number opted out");
        }
        List<Map<String, Object>> doses = jdbc.queryForList(
                "select de.id, de.status::text status from dose_events de " + DOSE_JOIN +
                        "where de.whatsapp_message_id = ? and coalesce(de.sent_at, de.scheduled_at) > now() - make_interval(hours => ?) " +
                        "and " + ACTIVE_OF_PHONE + " order by (de.status in ('sent', 'missed')) desc, de.scheduled_at",
                pollKeyId, POLL_HOURS, phone);
        if (!doses.isEmpty()) {
            int choice = ReminderTexts.doseVote(label, index);
            if (choice < 0) return new Ignore("unknown poll option");
            UUID id = (UUID) doses.get(0).get("id");
            boolean open = doses.stream().anyMatch(d -> "sent".equals(d.get("status")) || "missed".equals(d.get("status")));
            if (choice == ReminderTexts.YES) return open ? new Dose(id, true, "poll") : new Ignore("slot already answered");
            if (!open) {
                log.info("a dose poll vote changed to Later after the slot was confirmed: kept as confirmed (not undone)");
                return new Ignore("later after confirmed");
            }
            return new DoseLater(id);
        }
        if (checkinMessageIdsAvailable()) {
            Map<String, Object> checkin = firstOrNull(jdbc.queryForList(
                    "select c.id from checkins c join care_recipients r on r.id = c.recipient_id " +
                            "where c.whatsapp_message_id = ? and c.sent_at > now() - make_interval(hours => ?) and " + ACTIVE_OF_PHONE +
                            " limit 1", pollKeyId, POLL_HOURS, phone));
            if (checkin != null) {
                int choice = ReminderTexts.checkinVote(label, index);
                if (choice < 0) return new Ignore("unknown poll option");
                return new Checkin((UUID) checkin.get("id"), choice == ReminderTexts.SECOND, "poll");
            }
        }
        UUID sample = sampleByMessage(phone, pollKeyId);
        if (sample != null) {
            return ReminderTexts.doseVote(label, index) < 0 ? new Ignore("unknown poll option") : new Sample(sample);
        }
        return new Ignore("vote on an unknown or old poll");
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
        Timestamp lastParentQuestion = lastParentQuestion(m.phone());
        if (lastParentQuestion != null && lastParentQuestion.after(asked)) return null;
        return new AlertOptIn((UUID) ask.get("id"), intent != Intent.NO);
    }

    /** When a dose reminder or check-in still waiting (or missed) was last sent to this number in the last few hours; null = none. */
    private Timestamp lastParentQuestion(String phone) {
        return jdbc.queryForObject(
                "select greatest(" +
                        "(select max(de.sent_at) from dose_events de " + DOSE_JOIN +
                        " where de.status in ('sent', 'missed') and de.sent_at > now() - make_interval(hours => ?) and " + ACTIVE_OF_PHONE + "), " +
                        "(select max(c.sent_at) from checkins c join care_recipients r on r.id = c.recipient_id " +
                        " where c.status in ('sent', 'missed') and c.sent_at > now() - make_interval(hours => ?) and " + ACTIVE_OF_PHONE + "))",
                Timestamp.class, SENT_DOSE_HOURS, phone, CHECKIN_HOURS, phone);
    }

    // ---------------------------------------------------------------- the sample reminder (docs/sample-reminder.md)

    /** How long a typed answer can still be meant for the sample reminder. */
    public static final int SAMPLE_HOURS = 3;

    /**
     * The person whose SAMPLE reminder a typed yes / taken / no answers, or null. A swipe-reply quoting the sample always does; a
     * swipe-reply quoting a real reminder never does. Otherwise only when the sample went to this number in the last
     * {@value #SAMPLE_HOURS} h AND after every real reminder / check-in still open there (a reply answers what we sent last).
     */
    private UUID sampleAnswered(InboundMessage m, Intent intent) {
        if (intent != Intent.YES && intent != Intent.TAKEN && intent != Intent.TAKEN_ALL && intent != Intent.NO) return null;
        if (m.quotedId() != null) {
            UUID quoted = sampleByMessage(m.phone(), m.quotedId());
            if (quoted != null) return quoted;
            if (routeQuoted(m, intent) != null) return null;
        }
        // The once-a-day key of the person's local today / yesterday (SampleReminderService#dayKey) says when the sample went out.
        // (Computed here, not in SQL: a bad time zone must never fail the reply's transaction.)
        Map<String, UUID> keys = new java.util.HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("select r.id, r.timezone from care_recipients r where " + ACTIVE_OF_PHONE, m.phone())) {
            UUID id = (UUID) r.get("id");
            java.time.LocalDate today = java.time.LocalDate.now(zoneOf((String) r.get("timezone")));
            keys.put(SampleReminderService.dayKey(id, today), id);
            keys.put(SampleReminderService.dayKey(id, today.minusDays(1)), id);
        }
        if (keys.isEmpty()) return null;
        Map<String, Object> latest = firstOrNull(jdbc.queryForList(
                "select id, received_at from whatsapp_events where id = any(cast(? as text[])) and received_at > now() - make_interval(hours => ?) " +
                        "order by received_at desc limit 1", "{" + String.join(",", keys.keySet()) + "}", SAMPLE_HOURS));
        if (latest == null) return null;
        Timestamp last = lastParentQuestion(m.phone());
        if (last != null && !((Timestamp) latest.get("received_at")).after(last)) return null;
        return keys.get((String) latest.get("id"));
    }

    private static java.time.ZoneId zoneOf(String tz) {
        try {
            return java.time.ZoneId.of(tz == null || tz.isBlank() ? "Asia/Kolkata" : tz);
        } catch (Exception e) {
            return java.time.ZoneId.of("Asia/Kolkata");
        }
    }

    /** The person whose sample reminder was the WhatsApp message {@code messageId} (to this number, recently), or null. */
    private UUID sampleByMessage(String phone, String messageId) {
        if (messageId == null || messageId.isBlank()) return null;
        return jdbc.queryForList(
                "select r.id from care_recipients r where " + ACTIVE_OF_PHONE + " and exists (select 1 from whatsapp_events e " +
                        "where e.id = 'gw:out:sample:' || r.id::text || ':' || ? and e.received_at > now() - make_interval(hours => ?))",
                UUID.class, phone, messageId, POLL_HOURS).stream().findFirst().orElse(null);
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
                case YES, TAKEN, TAKEN_ALL -> new Dose(id, true, "quote");
                case NO -> new DoseNotYet((String) dose.get("language"), id);
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
