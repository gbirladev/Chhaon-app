package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.smartgurukul.chhaon.support.GatewayClient.Response;
import org.smartgurukul.chhaon.whatsapp.reply.ReplyRouter;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F6: a long-press 👍 on one of our messages counts exactly like typing 1 for THAT message (dose reminder, daily check-in,
 * consent request). Other emoji, removed reactions and reactions to unknown or old messages are ignored without a reply.
 */
class ReactionReplyTest extends DbTest {

    @Autowired
    JwtService jwt;

    private static Instant ago(int minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES);
    }

    private Response react(String phone, String emoji, String reactedId) throws Exception {
        return gateway.post(gateway.reaction(Fixtures.messageId(), phone, emoji, reactedId));
    }

    private UUID sentDose(Med m, int sentMinutesAgo, String messageId) {
        return fx.dose(m.scheduleId(), ago(sentMinutesAgo + 2), "sent", ago(sentMinutesAgo), messageId);
    }

    // ================================================================ which emoji count

    @Test
    void onlyYesEmojiCount() {
        for (String yes : List.of("👍", "👍🏻", "👍🏽", "👍🏿", "✅", "✔️", "☑️")) {
            assertThat(ReplyRouter.reactionMeansYes(yes)).as(yes).isTrue();
        }
        for (String no : List.of("❤️", "🙏", "😂", "😮", "😢", "👎", "❌", "👌", "", "1", "yes", "👍👎", "👍 no")) {
            assertThat(ReplyRouter.reactionMeansYes(no)).as(no).isFalse();
        }
        assertThat(ReplyRouter.reactionMeansYes(null)).isFalse();
    }

    // ================================================================ dose reminder

    @Test
    void thumbsUpOnADoseReminderMarksItTaken_usesStock_andThanks() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "2 tablets", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-R1");

        Response r = react(f.phone(), "👍", "wamid-R1");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("handled:REACTION");
        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
        assertThat(fx.dose(dose).get("responded_at")).isNotNull();
        assertThat(fx.stock(m.medicineId())).isEqualTo(18);
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("Metformin");
    }

    @Test
    void thumbsUpAnswersTheReactedReminder_notTheLatestOne() throws Exception {
        Family f = fx.family("en", true);
        Med morning = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        Med other = fx.medicine(f, "Amlodipine", "5 mg", 10, LocalTime.of(3, 5));
        UUID older = sentDose(morning, 40, "wamid-OLD");
        UUID newer = sentDose(other, 5, "wamid-NEW");

        react(f.phone(), "👍🏽", "wamid-OLD");

        assertThat(fx.doseStatus(older)).isEqualTo("taken");
        assertThat(fx.doseStatus(newer)).isEqualTo("sent");
        assertThat(fx.stock(morning.medicineId())).isEqualTo(19);
        assertThat(fx.stock(other.medicineId())).isEqualTo(10);
        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("Metformin").contains("Reply 1 again for the next medicine");
    }

    @Test
    void thumbsUpOnAMissedReminderIsALateTake() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), ago(90), "missed", ago(88), "wamid-MISSED");

        react(f.phone(), "✅", "wamid-MISSED");

        assertThat(fx.doseStatus(dose)).isEqualTo("taken_late");
        assertThat(fx.stock(m.medicineId())).isEqualTo(19);
    }

    @Test
    void aDuplicateDeliveryAndARepeatAfterTyping1UseStockOnce() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-DUP");
        String reactionId = Fixtures.messageId();
        var event = gateway.reaction(reactionId, f.phone(), "👍", "wamid-DUP");

        assertThat(gateway.post(event).body()).contains("handled");
        assertThat(gateway.post(event).body()).contains("duplicate");
        assertThat(fx.eventCount("gw:in:" + reactionId)).isEqualTo(1);

        // the parent reacts again (changed to ❤️ and back: a new reaction id): nothing new
        react(f.phone(), "👍", "wamid-DUP");

        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
        assertThat(fx.stock(m.medicineId())).isEqualTo(19);
        assertThat(wa.sentTo(f.phone())).hasSize(1); // thanked once
    }

    @Test
    void typing1FirstThenThumbsUpChangesNothing() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-T1");

        gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "1"));
        wa.reset();
        Response r = react(f.phone(), "👍", "wamid-T1");

        assertThat(r.status()).isEqualTo(200);
        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
        assertThat(fx.stock(m.medicineId())).isEqualTo(19);
        assertThat(wa.sent()).isEmpty();
    }

    // ================================================================ ignored

    @Test
    void otherEmojiAreIgnoredWithoutAReply() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-E");

        for (String emoji : List.of("❤️", "🙏", "😂", "👎", "❌", "😢")) {
            Response r = react(f.phone(), emoji, "wamid-E");
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.body()).as(emoji).contains("ignored:reaction-emoji");
        }

        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
        assertThat(fx.stock(m.medicineId())).isEqualTo(20);
        assertThat(wa.sent()).isEmpty();
        assertThat(fx.notifications(f.userId(), "checkin_call_requested")).isZero();
    }

    @Test
    void removingTheReactionAfterwardsDoesNotUndoTheTake() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-RM");

        react(f.phone(), "👍", "wamid-RM");
        wa.reset();
        Response removed = react(f.phone(), "", "wamid-RM");
        Response changed = react(f.phone(), "👎", "wamid-RM"); // changing it to thumbs down is not "not taken" either

        assertThat(removed.status()).isEqualTo(200);
        assertThat(removed.body()).contains("ignored:reaction-removed");
        assertThat(changed.body()).contains("ignored:reaction-emoji");
        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
        assertThat(fx.stock(m.medicineId())).isEqualTo(19);
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void aReactionToAnUnknownOrOldMessageIsIgnoredWithoutHelpText() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID open = sentDose(m, 5, "wamid-OPEN");
        UUID old = fx.dose(m.scheduleId(), ago(14 * 60), "missed", ago(14 * 60 - 1), "wamid-YESTERDAY");

        Response unknown = react(f.phone(), "👍", "wamid-NOT-OURS");
        Response tooOld = react(f.phone(), "👍", "wamid-YESTERDAY");

        assertThat(unknown.status()).isEqualTo(200);
        assertThat(tooOld.status()).isEqualTo(200);
        assertThat(fx.doseStatus(open)).isEqualTo("sent"); // never "the latest reminder" for a reaction
        assertThat(fx.doseStatus(old)).isEqualTo("missed");
        assertThat(fx.stock(m.medicineId())).isEqualTo(20);
        assertThat(wa.sent()).isEmpty(); // no help text
    }

    @Test
    void aReactionFromAnotherNumberOrAStoppedNumberDoesNothing() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-X");
        String stranger = fx.track(Fixtures.randomPhone());

        react(stranger, "👍", "wamid-X");
        assertThat(fx.doseStatus(dose)).isEqualTo("sent");

        fx.optOutPhone(f.phone());
        react(f.phone(), "👍", "wamid-X");
        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
        assertThat(wa.sent()).isEmpty();
    }

    // ================================================================ check-in

    @Test
    void thumbsUpOnTheCheckinMeansIAmFine_evenWithADoseOpen() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 3, "wamid-D");
        UUID checkin = fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", ago(10));
        fx.jdbc().update("update checkins set whatsapp_message_id = 'wamid-CHECKIN' where id = ?", checkin);

        react(f.phone(), "👍", "wamid-CHECKIN");

        assertThat(fx.checkinStatus(checkin)).isEqualTo("replied");
        assertThat(fx.doseStatus(dose)).isEqualTo("sent"); // the reaction was on the check-in, not on the reminder
        assertThat(fx.notifications(f.userId(), "checkin_call_requested")).isZero();
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("Glad you are okay");
    }

    // ================================================================ consent

    @Test
    void thumbsUpOnTheConsentRequestAgrees() throws Exception {
        Family f = fx.family("en", false);
        var req = post("/recipients/" + f.recipientId() + "/consent-request")
                .header("Authorization", "Bearer " + jwt.issueToken(f.userId(), false));
        assertThat(mvc.perform(req).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("Reply *1* or 👍 to start");
        String prefix = "gw:out:consent:" + f.recipientId() + ":";
        List<String> markers = fx.jdbc().queryForList("select id from whatsapp_events where id like ?", String.class, prefix + "%");
        assertThat(markers).hasSize(1);
        String consentMessageId = markers.get(0).substring(prefix.length());
        wa.reset();

        // a reaction to some other message does not agree
        react(f.phone(), "👍", "wamid-SOMETHING-ELSE");
        assertThat(fx.recipient(f.recipientId()).get("consent_at")).isNull();
        assertThat(wa.sent()).isEmpty();

        react(f.phone(), "👍", consentMessageId);
        react(f.phone(), "👍", consentMessageId); // again: nothing new

        assertThat(fx.recipient(f.recipientId()).get("consent_at")).isNotNull();
        assertThat(fx.notifications(f.userId(), "parent_consented")).isEqualTo(1);
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("Thank you");
    }

    @Test
    void aHeartOnTheConsentRequestDoesNotAgree() throws Exception {
        Family f = fx.family("en", false);
        fx.jdbc().update("update care_recipients set consent_requested_at = now() where id = ?", f.recipientId());
        fx.jdbc().update("insert into whatsapp_events (id) values (?)", ReplyRouter.consentMarker("wamid-C", f.recipientId()));

        react(f.phone(), "❤️", "wamid-C");
        react(f.phone(), "🙏", "wamid-C");

        assertThat(fx.recipient(f.recipientId()).get("consent_at")).isNull();
        assertThat(wa.sent()).isEmpty();
    }

    // ================================================================ shared number

    @Test
    void oneHelperForTwoPeople_theReactionConfirmsWhoseReminderItWasOn() throws Exception {
        String shared = Fixtures.randomPhone();
        Family papa = fx.family(shared, "en", true, java.time.ZoneId.of("Asia/Kolkata"));
        Family mummy = fx.addRecipient(papa, "Kamla", shared, "en", true);
        Med papaMed = fx.medicine(papa, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        Med mummyMed = fx.medicine(mummy, "Thyronorm", "50 mcg", 30, LocalTime.of(3, 0));
        UUID papaDose = sentDose(papaMed, 30, "wamid-PAPA");
        UUID mummyDose = sentDose(mummyMed, 2, "wamid-MUMMY");

        react(shared, "👍", "wamid-PAPA");

        assertThat(fx.doseStatus(papaDose)).isEqualTo("taken");
        assertThat(fx.doseStatus(mummyDose)).isEqualTo("sent");
        assertThat(fx.stock(papaMed.medicineId())).isEqualTo(19);
        assertThat(fx.stock(mummyMed.medicineId())).isEqualTo(30);

        react(shared, "👍", "wamid-MUMMY");
        assertThat(fx.doseStatus(mummyDose)).isEqualTo("taken");
        assertThat(fx.stock(mummyMed.medicineId())).isEqualTo(29);
    }

    // ================================================================ old backend contract

    @Test
    void aReactionIsNeverReadAsTypedText() throws Exception {
        // The reaction envelope carries the emoji in "content" too; it must not go through the typed-reply path
        // (which would answer "the latest reminder").
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5, "wamid-L");

        Response r = react(f.phone(), "👍", "wamid-UNRELATED");

        assertThat(r.body()).contains("handled:REACTION");
        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
    }
}
