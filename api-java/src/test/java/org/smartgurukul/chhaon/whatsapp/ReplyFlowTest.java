package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.smartgurukul.chhaon.support.GatewayClient.Response;
import org.smartgurukul.chhaon.support.RecordingWhatsAppSender.Sent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every handler of the reply pipeline, driven through the real webhook with the exact gateway payload shape:
 * consent, dose confirmation (with stock), late confirmation, check-in, STOP / START, unknown answers.
 */
class ReplyFlowTest extends DbTest {

    private static Instant ago(int minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES);
    }

    private Response say(Family f, String text) throws Exception {
        return gateway.post(gateway.received(Fixtures.messageId(), f.phone(), text));
    }

    private Response sayTo(String phone, String text, String quotedId) throws Exception {
        return gateway.post(gateway.received(Fixtures.messageId(), org.smartgurukul.chhaon.support.GatewayClient.jid(phone), text, quotedId));
    }

    /** A dose reminder that was sent {@code sentMinutesAgo} minutes ago and is waiting for an answer. */
    private UUID sentDose(Med m, int sentMinutesAgo, String messageId) {
        return fx.dose(m.scheduleId(), ago(sentMinutesAgo + 2), "sent", ago(sentMinutesAgo), messageId);
    }

    private void awaitConsent(Family f) {
        fx.jdbc().update("update care_recipients set consent_at = null, opted_out_at = null, consent_requested_at = now() where id = ?",
                f.recipientId());
    }

    private void assertReplied(Family f, String contains) {
        List<Sent> sent = wa.sentTo(f.phone());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).text()).contains(contains);
    }

    // ================================================================ doses

    @Test
    void oneConfirmsTheLatestSentDose_usesStockOnce_andThanksTheParent() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "2 tablets", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-A");

        assertThat(say(f, "1").status()).isEqualTo(200);

        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
        assertThat(fx.dose(dose).get("responded_at")).isNotNull();
        assertThat(fx.stock(m.medicineId())).isEqualTo(18); // "2 tablets" -> 2 used
        assertReplied(f, "Metformin");

        // The same answer again changes nothing (no second stock use, no second "taken").
        wa.reset();
        say(f, "1");
        assertThat(fx.stock(m.medicineId())).isEqualTo(18);
        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
    }

    @Test
    void wordsInEnglishAndHindiConfirmADose() throws Exception {
        for (String word : List.of("taken", "✅ Taken", "le li", "ले ली", "haan", "✅", "👍", "१")) {
            Family f = fx.family("hi", true);
            Med m = fx.medicine(f, "Amlodipine", "5 mg", 10, LocalTime.of(3, 0));
            UUID dose = sentDose(m, 5, "wamid-" + UUID.randomUUID());
            say(f, word);
            assertThat(fx.doseStatus(dose)).as(word).isEqualTo("taken");
            assertThat(fx.stock(m.medicineId())).as(word).isEqualTo(9);
        }
    }

    @Test
    void aBareOkIsNotTaken_theParentIsToldHowToAnswer() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-A");

        say(f, "ok");

        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
        assertThat(fx.stock(m.medicineId())).isEqualTo(20);
        assertReplied(f, "type 1");
    }

    @Test
    void twoMeansNotYet_nothingIsRecorded() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-A");

        say(f, "2");

        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
        assertReplied(f, "once you have taken it");
    }

    @Test
    void aSwipeReplyAnswersExactlyTheQuotedReminder() throws Exception {
        Family f = fx.family("en", true);
        Med a = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        Med b = fx.medicine(f, "Amlodipine", "5 mg", 20, LocalTime.of(3, 5));
        UUID doseA = sentDose(a, 8, "wamid-A");
        UUID doseB = sentDose(b, 3, "wamid-B"); // the newest: what an unquoted "1" would answer

        sayTo(f.phone(), "1", "wamid-A");

        assertThat(fx.doseStatus(doseA)).isEqualTo("taken");
        assertThat(fx.doseStatus(doseB)).isEqualTo("sent");
        assertThat(fx.stock(a.medicineId())).isEqualTo(19);
        assertThat(fx.stock(b.medicineId())).isEqualTo(20);
    }

    @Test
    void anUnquotedOneAnswersTheMostRecentReminderThenTheNext_andTheReplyHintsAtTheRest() throws Exception {
        Family f = fx.family("en", true);
        Med a = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        Med b = fx.medicine(f, "Amlodipine", "5 mg", 20, LocalTime.of(3, 5));
        UUID doseA = sentDose(a, 8, "wamid-A");
        UUID doseB = sentDose(b, 3, "wamid-B");

        say(f, "1");
        assertThat(fx.doseStatus(doseB)).isEqualTo("taken");
        assertThat(fx.doseStatus(doseA)).isEqualTo("sent");
        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("Amlodipine").contains("Reply 1 again for the next medicine");

        say(f, "1");
        assertThat(fx.doseStatus(doseA)).isEqualTo("taken");
    }

    @Test
    void takenAllConfirmsEveryDoseOfThatMoment_oneStockUsePerMedicine() throws Exception {
        Family f = fx.family("en", true);
        Med a = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        Med b = fx.medicine(f, "Amlodipine", "5 mg", 20, LocalTime.of(3, 5));
        Instant at = ago(7);
        UUID doseA = fx.dose(a.scheduleId(), at, "sent", ago(5), "wamid-A");
        UUID doseB = fx.dose(b.scheduleId(), at, "sent", ago(5), "wamid-B");

        say(f, "all taken");

        assertThat(fx.doseStatus(doseA)).isEqualTo("taken");
        assertThat(fx.doseStatus(doseB)).isEqualTo("taken");
        assertThat(fx.stock(a.medicineId())).isEqualTo(19);
        assertThat(fx.stock(b.medicineId())).isEqualTo(19);
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("Metformin").contains("Amlodipine");
    }

    @Test
    void aLateAnswerToAMissedDoseBecomesTakenLate() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), ago(90), "missed", ago(85), "wamid-A");

        say(f, "taken");

        assertThat(fx.doseStatus(dose)).isEqualTo("taken_late");
        assertThat(fx.stock(m.medicineId())).isEqualTo(19);
    }

    @Test
    void aQuotedAnswerToAnOldMissedDoseIsStillTakenLate_butAnUnquotedOneIsNot() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID old = fx.dose(m.scheduleId(), ago(600), "missed", ago(595), "wamid-OLD");

        say(f, "1"); // unquoted, ten hours later: not matched to anything
        assertThat(fx.doseStatus(old)).isEqualTo("missed");

        sayTo(f.phone(), "1", "wamid-OLD");
        assertThat(fx.doseStatus(old)).isEqualTo("taken_late");
    }

    @Test
    void aSentReminderOlderThanTheWindowIsNotMatchedByAnUnquotedAnswer() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), ago(14 * 60), "sent", ago(14 * 60 - 2), "wamid-A");

        say(f, "1");

        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
    }

    @Test
    void anAnswerNeverTouchesAnotherParentsDose() throws Exception {
        Family f = fx.family("en", true);
        Family other = fx.family("en", true);
        Med m = fx.medicine(other, "Other", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-OTHER");

        say(f, "1");                                       // f has nothing waiting
        sayTo(f.phone(), "1", "wamid-OTHER");              // f quotes somebody else's reminder

        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
    }

    @Test
    void aReminderThatWasNeverSentCannotBeConfirmed() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), ago(5), "pending", null, null);

        say(f, "1");

        assertThat(fx.doseStatus(dose)).isEqualTo("pending");
    }

    // ================================================================ daily check-in

    @Test
    void oneAnswersTodaysCheckin() throws Exception {
        Family f = fx.family("en", true);
        UUID checkin = fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", ago(10));

        say(f, "1");

        assertThat(fx.checkinStatus(checkin)).isEqualTo("replied");
        assertThat(fx.notifications(f.userId(), "checkin_call_requested")).isEqualTo(0);
        assertReplied(f, "Glad you are okay");
    }

    @Test
    void checkinWordsInHinglish() throws Exception {
        Family f = fx.family("hi", true);
        UUID checkin = fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", ago(10));

        say(f, "👍 theek hoon");

        assertThat(fx.checkinStatus(checkin)).isEqualTo("replied");
        assertReplied(f, "ठीक हैं");
    }

    @Test
    void twoMeansCallMe_theFamilyIsToldExactlyOnce() throws Exception {
        Family f = fx.family("en", true);
        UUID checkin = fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", ago(10));

        say(f, "2");
        say(f, "call me"); // a panicking parent repeats herself
        say(f, "2");

        assertThat(fx.checkinStatus(checkin)).isEqualTo("replied");
        assertThat(fx.notifications(f.userId(), "checkin_call_requested")).isEqualTo(1);
    }

    @Test
    void feelingUnwellIsACallRequest() throws Exception {
        Family f = fx.family("en", true);
        fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", ago(10));

        say(f, "I am not well");

        assertThat(fx.notifications(f.userId(), "checkin_call_requested")).isEqualTo(1);
    }

    @Test
    void aLateAnswerToAMissedCheckinStillCounts() throws Exception {
        Family f = fx.family("en", true);
        UUID checkin = fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "missed", ago(200));

        say(f, "1");

        assertThat(fx.checkinStatus(checkin)).isEqualTo("replied");
    }

    @Test
    void checkinSpecificWordsSkipAnOpenDose() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-A");
        UUID checkin = fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", ago(10));

        say(f, "I am okay");

        assertThat(fx.checkinStatus(checkin)).isEqualTo("replied");
        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
    }

    @Test
    void aDoseAnswerComesBeforeTheCheckin_andTheReplyRemindsAboutTheCheckin() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-A");
        UUID checkin = fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", ago(10));

        say(f, "1");

        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
        assertThat(fx.checkinStatus(checkin)).isEqualTo("sent");
        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("Are you okay today?");

        say(f, "1"); // the second "1" is the check-in answer
        assertThat(fx.checkinStatus(checkin)).isEqualTo("replied");
    }

    @Test
    void aSwipeReplyToTheCheckinMessageAnswersTheCheckinEvenWhileADoseIsOpen() throws Exception {
        Family f = fx.family("en", true);
        assumeTrue(columnExists("checkins", "whatsapp_message_id"), "migration 20261104000041 not applied to the test database");
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-A");
        UUID checkin = fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", ago(10));
        fx.jdbc().update("update checkins set whatsapp_message_id = 'wamid-CHECKIN' where id = ?", checkin);

        sayTo(f.phone(), "1", "wamid-CHECKIN");

        assertThat(fx.checkinStatus(checkin)).isEqualTo("replied");
        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
    }

    private boolean columnExists(String table, String column) {
        Integer n = fx.jdbc().queryForObject("select count(*) from information_schema.columns where table_schema='public' " +
                "and table_name=? and column_name=?", Integer.class, table, column);
        return n != null && n > 0;
    }

    // ================================================================ consent

    @Test
    void oneAnswersAnOpenConsentQuestion() throws Exception {
        Family f = fx.family("en", false);
        awaitConsent(f);

        say(f, "1");

        assertThat(fx.recipient(f.recipientId()).get("consent_at")).isNotNull();
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNull();
        assertThat(fx.notifications(f.userId(), "parent_consented")).isEqualTo(1);
        assertReplied(f, "gentle medicine reminders");

        // A repeated yes notifies and replies nothing more.
        wa.reset();
        say(f, "1");
        assertThat(fx.notifications(f.userId(), "parent_consented")).isEqualTo(1);
    }

    @Test
    void anOkOrHaanOrAGreenTickAlsoAgrees() throws Exception {
        for (String word : List.of("ok", "haan", "✅", "हाँ")) {
            Family f = fx.family("en", false);
            awaitConsent(f);
            say(f, word);
            assertThat(fx.recipient(f.recipientId()).get("consent_at")).as(word).isNotNull();
        }
    }

    @Test
    void twoDeclines_andTheNumberIsNeverMessagedAgain() throws Exception {
        Family f = fx.family("en", false);
        awaitConsent(f);

        say(f, "2");

        assertThat(fx.recipient(f.recipientId()).get("consent_at")).isNull();
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNotNull();
        assertThat(fx.phoneOptedOut(f.phone())).isTrue();
        assertThat(fx.notifications(f.userId(), "parent_declined")).isEqualTo(1);
        assertReplied(f, "will not send you any messages");
    }

    @Test
    void startAfterDecliningGivesConsent() throws Exception {
        Family f = fx.family("en", false);
        awaitConsent(f);
        say(f, "2");
        wa.reset();

        say(f, "START");

        assertThat(fx.recipient(f.recipientId()).get("consent_at")).isNotNull();
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNull();
        assertThat(fx.phoneOptedOut(f.phone())).isFalse();
        assertThat(fx.notifications(f.userId(), "parent_consented")).isEqualTo(1);
        assertReplied(f, "Reminders have started again");
    }

    @Test
    void startUnderAnOpenConsentQuestionMeansYes() throws Exception {
        Family f = fx.family("en", false);
        awaitConsent(f);

        say(f, "start");

        assertThat(fx.recipient(f.recipientId()).get("consent_at")).isNotNull();
    }

    @Test
    void somethingElseUnderAnOpenConsentQuestionGetsTheQuestionAgain_once() throws Exception {
        Family f = fx.family("en", false);
        awaitConsent(f);

        say(f, "who is this?");
        say(f, "hello?");

        assertThat(fx.recipient(f.recipientId()).get("consent_at")).isNull();
        assertReplied(f, "Reply 1 to start reminders, 2 for no");
    }

    @Test
    void aParentNobodyAskedGetsNoReplyAndNoChange() throws Exception {
        Family f = fx.family("en", false); // no consent request was ever sent

        say(f, "1");
        say(f, "hello");

        assertThat(fx.recipient(f.recipientId()).get("consent_at")).isNull();
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void consentNeverOverridesAnEarlierStop() throws Exception {
        Family f = fx.family("en", true);
        say(f, "STOP");
        awaitConsent(f); // somebody re-requested consent in the app afterwards
        fx.jdbc().update("update care_recipients set opted_out_at = now() where id = ?", f.recipientId());
        wa.reset();

        say(f, "1"); // only START can bring the number back

        assertThat(fx.recipient(f.recipientId()).get("consent_at")).isNull();
        assertThat(wa.sent()).isEmpty();
    }

    // ================================================================ STOP / START

    @Test
    void stopOptsTheParentOutTellsTheFamilyAndThanks_inTheParentsLanguage() throws Exception {
        Family f = fx.family("hi", true);

        say(f, "STOP");

        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNotNull();
        assertThat(fx.phoneOptedOut(f.phone())).isTrue();
        assertThat(fx.notifications(f.userId(), "parent_stopped")).isEqualTo(1);
        assertReplied(f, "हमने सभी संदेश बंद कर दिए हैं");
    }

    @Test
    void stopWorksInManyWays_andStopTwiceNotifiesOnce() throws Exception {
        for (String word : List.of("Stop.", "ＳＴＯＰ", "रुको", "बंद करो", "st​op", "please stop")) {
            Family f = fx.family("en", true);
            say(f, word);
            assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).as(word).isNotNull();
        }
        Family f = fx.family("en", true);
        say(f, "stop");
        say(f, "stop");
        assertThat(fx.notifications(f.userId(), "parent_stopped")).isEqualTo(1);
    }

    @Test
    void aLongSentenceMentioningStopOptsNobodyOut() throws Exception {
        Family f = fx.family("en", true);

        say(f, "I will stop by the pharmacy tomorrow to pick up my tablets");
        say(f, "please don't stop");

        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNull();
        assertThat(fx.phoneOptedOut(f.phone())).isFalse();
    }

    @Test
    void afterStopNothingIsAnsweredExceptStart() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-A");
        say(f, "STOP");
        wa.reset();

        say(f, "1");
        say(f, "hello");

        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
        assertThat(wa.sent()).isEmpty();

        say(f, "START");
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNull();
        assertReplied(f, "Welcome back");
    }

    @Test
    void startCannotReplaceTheOriginalConsentTime() throws Exception {
        Family f = fx.family("en", true);
        Object before = fx.recipient(f.recipientId()).get("consent_at");
        say(f, "STOP");

        say(f, "START");

        assertThat(fx.recipient(f.recipientId()).get("consent_at")).isEqualTo(before);
        assertThat(fx.notifications(f.userId(), "parent_resumed")).isEqualTo(1);
    }

    @Test
    void startInsideASentenceDoesNotGrantAnything() throws Exception {
        Family f = fx.family("en", false);
        say(f, "Start taking medicine at 9");
        assertThat(fx.recipient(f.recipientId()).get("consent_at")).isNull();
    }

    // ================================================================ unknown / friendly

    @Test
    void anUnknownAnswerGetsOneShortHelpText_neverMoreThanOneEverySixHours() throws Exception {
        Family f = fx.family("en", true); // nothing is waiting

        say(f, "what is this");
        say(f, "kab aaoge");
        say(f, "hmm");

        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("type 1").contains("STOP");
    }

    @Test
    void thanksNeedsNoAnswer() throws Exception {
        Family f = fx.family("en", true);

        say(f, "thank you 🙏");
        say(f, "🙏");

        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void aNumberThatIsNoParentGetsNothing_butItsStopIsRemembered() throws Exception {
        String stranger = Fixtures.randomPhone();

        gateway.post(gateway.received(Fixtures.messageId(), stranger, "1"));
        gateway.post(gateway.received(Fixtures.messageId(), stranger, "hello"));
        assertThat(wa.sent()).isEmpty();

        gateway.post(gateway.received(Fixtures.messageId(), stranger, "STOP"));
        assertThat(fx.phoneOptedOut(stranger)).isTrue(); // the number is protected before any parent carries it
        assertThat(wa.sent()).isEmpty();
        fx.optOutPhone(stranger); // register for cleanup
    }
}
