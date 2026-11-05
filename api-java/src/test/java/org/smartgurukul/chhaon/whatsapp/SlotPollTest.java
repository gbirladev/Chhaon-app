package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.scheduler.SchedulerService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.FakeMedicinePhotoStore;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.smartgurukul.chhaon.support.GatewayClient;
import org.smartgurukul.chhaon.support.GatewayClient.Response;
import org.smartgurukul.chhaon.support.RecordingWhatsAppSender.Poll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One reminder per time slot (several medicines due at the same moment = ONE message, one "1" or one poll tap confirms them
 * all), the poll mode (app.whatsapp.polls-enabled) with its text fallback, and the gateway's poll.vote event.
 * Production bug this covers: three medicines at 20:00 were three reminders, and a "1" confirmed only the last one.
 */
class SlotPollTest extends DbTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeMedicinePhotoStore storage;

    @AfterEach
    void off() {
        wa.setPollsEnabled(false);
        storage.reset();
    }

    private SchedulerService scheduler(Family f) {
        SchedulerService s = new SchedulerService(jdbc, wa, true, 60, 180, 120, 24, f.recipientId().toString());
        s.setMedicinePhotoStore(storage);
        return s;
    }

    /** Three medicines due at the SAME moment (as generate_dose_events makes them), still pending. */
    private record Slot(Family f, List<Med> meds, List<UUID> doses) {}

    private Slot threeAtOnce(String language) {
        Family f = fx.family(language, true);
        Instant at = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
        Med a = fx.medicine(f, "Argipreg", "1 sachet", 10, LocalTime.of(3, 0));
        Med b = fx.medicine(f, "Metformin 500", "1 tablet", 20, LocalTime.of(3, 0));
        Med c = fx.medicine(f, "Ecosprin 150", "2 tablets", 30, LocalTime.of(3, 0));
        List<Med> meds = List.of(a, b, c);
        List<UUID> doses = meds.stream().map(m -> fx.dose(m.scheduleId(), at, "pending", null, null)).toList();
        return new Slot(f, meds, doses);
    }

    private Response vote(String pollId, String phone, String label, Integer index) throws Exception {
        return gateway.post(gateway.pollVote(pollId, phone, label, index));
    }

    private static byte[] jpeg() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpeg", out);
        return out.toByteArray();
    }

    private String messageIdOf(UUID dose) {
        return (String) fx.dose(dose).get("whatsapp_message_id");
    }

    // ================================================================ slot grouping (polls off)

    @Test
    void threeMedicinesAtOneTimeAreOneTextReminder_andOneConfirmsThemAll() throws Exception {
        Slot s = threeAtOnce("hi");

        var counters = scheduler(s.f()).tick(s.f().recipientId());

        assertThat(counters.get("doses_sent")).isEqualTo(3);
        assertThat(wa.sentTo(s.f().phone())).hasSize(1);
        String text = wa.sentTo(s.f().phone()).get(0).text();
        assertThat(text).startsWith("नमस्ते पापा 🙏\n").contains(" की दवाएं:\n• *Argipreg* · 1 sachet\n• *Ecosprin 150* · 2 गोलियाँ\n")
                .contains("• *Metformin 500* · 1 गोली").endsWith("सब ले लीं? जवाब में बस *1* लिखें");
        String id = messageIdOf(s.doses().get(0));
        assertThat(id).isNotBlank();
        for (UUID d : s.doses()) {
            assertThat(fx.doseStatus(d)).isEqualTo("sent");
            assertThat(messageIdOf(d)).isEqualTo(id);
        }
        assertThat(wa.images()).isEmpty();   // several medicines: no photo

        wa.reset();
        gateway.post(gateway.received(Fixtures.messageId(), s.f().phone(), "1"));

        for (UUID d : s.doses()) assertThat(fx.doseStatus(d)).isEqualTo("taken");
        assertThat(fx.stock(s.meds().get(0).medicineId())).isEqualTo(9);
        assertThat(fx.stock(s.meds().get(1).medicineId())).isEqualTo(19);
        assertThat(fx.stock(s.meds().get(2).medicineId())).isEqualTo(28);   // "2 tablets"
        assertThat(wa.sentTo(s.f().phone())).hasSize(1);
        assertThat(wa.sentTo(s.f().phone()).get(0).text()).contains("Argipreg").contains("Metformin 500").contains("Ecosprin 150")
                .doesNotContain("अगली दवा");

        // the same "1" again: nothing new, no stock used twice
        wa.reset();
        gateway.post(gateway.received(Fixtures.messageId(), s.f().phone(), "1"));
        assertThat(fx.stock(s.meds().get(0).medicineId())).isEqualTo(9);
    }

    @Test
    void aFailedSendHandsTheWholeSlotBack() {
        Slot s = threeAtOnce("en");
        wa.failSends(true);

        var counters = scheduler(s.f()).tick(s.f().recipientId());

        assertThat(counters.get("send_errors")).isEqualTo(1);
        for (UUID d : s.doses()) assertThat(fx.doseStatus(d)).isEqualTo("pending");
        wa.failSends(false);
        scheduler(s.f()).tick(s.f().recipientId());
        assertThat(wa.sentTo(s.f().phone())).hasSize(1);
        for (UUID d : s.doses()) assertThat(fx.doseStatus(d)).isEqualTo("sent");
    }

    @Test
    void aSwipeReplyToTheSlotReminderConfirmsTheSlotLate() throws Exception {
        Slot s = threeAtOnce("en");
        scheduler(s.f()).tick(s.f().recipientId());
        String id = messageIdOf(s.doses().get(0));
        jdbc.update("update dose_events set status = 'missed' where whatsapp_message_id = ?", id);

        gateway.post(gateway.received(Fixtures.messageId(), GatewayClient.jid(s.f().phone()), "1", id));

        for (UUID d : s.doses()) assertThat(fx.doseStatus(d)).isEqualTo("taken_late");
    }

    @Test
    void oneMedicineWithAPhotoKeepsThePhoto_butSeveralSendNone() throws Exception {
        storage.switchOn(true);
        Slot s = threeAtOnce("en");
        String path = s.f().recipientId() + "/strip.jpg";
        storage.put(path, jpeg());
        jdbc.update("update medicines set photo_url = ? where recipient_id = ?", path, s.f().recipientId());

        scheduler(s.f()).tick(s.f().recipientId());

        assertThat(wa.images()).isEmpty();
        assertThat(wa.sentTo(s.f().phone())).hasSize(1);
    }

    // ================================================================ polls

    @Test
    void withPollsOnTheSlotIsOnePoll_andATapConfirmsItAll() throws Exception {
        wa.setPollsEnabled(true);
        Slot s = threeAtOnce("hi");

        scheduler(s.f()).tick(s.f().recipientId());

        assertThat(wa.sentTo(s.f().phone())).isEmpty();   // no text
        List<Poll> polls = wa.pollsTo(s.f().phone());
        assertThat(polls).hasSize(1);
        Poll poll = polls.get(0);
        assertThat(poll.question()).startsWith("नमस्ते पापा 🙏\n💊 ").contains("Argipreg (1 sachet), Ecosprin 150 (2 गोलियाँ), Metformin 500 (1 गोली)")
                .endsWith("\nले लीं?").doesNotContain("*");
        assertThat(poll.options()).containsExactly("✅ ले ली", "⏰ थोड़ी देर में");
        for (UUID d : s.doses()) assertThat(messageIdOf(d)).isEqualTo(poll.messageId());

        Response r = vote(poll.messageId(), s.f().phone(), "✅ ले ली", 0);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("handled:POLL");
        for (UUID d : s.doses()) assertThat(fx.doseStatus(d)).isEqualTo("taken");
        assertThat(fx.stock(s.meds().get(2).medicineId())).isEqualTo(28);
        assertThat(wa.sentTo(s.f().phone())).hasSize(1);
        assertThat(wa.sentTo(s.f().phone()).get(0).text()).startsWith("धन्यवाद 🙏").doesNotContain("1 लिखें");

        // WhatsApp delivers the same vote twice: nothing more happens
        wa.reset();
        assertThat(vote(poll.messageId(), s.f().phone(), "✅ ले ली", 0).body()).contains("duplicate");
        assertThat(fx.stock(s.meds().get(2).medicineId())).isEqualTo(28);
        assertThat(wa.sent()).isEmpty();

        // the vote changed to Later after it was confirmed: NOT undone, no reply
        vote(poll.messageId(), s.f().phone(), "⏰ थोड़ी देर में", 1);
        for (UUID d : s.doses()) assertThat(fx.doseStatus(d)).isEqualTo("taken");
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void laterChangesNothingAndGetsAFriendlyAck_thenTakenConfirms() throws Exception {
        wa.setPollsEnabled(true);
        Slot s = threeAtOnce("hi");
        scheduler(s.f()).tick(s.f().recipientId());
        String pollId = wa.pollsTo(s.f().phone()).get(0).messageId();
        wa.reset();
        wa.setPollsEnabled(true);

        vote(pollId, s.f().phone(), "⏰ थोड़ी देर में", 1);

        for (UUID d : s.doses()) assertThat(fx.doseStatus(d)).isEqualTo("sent");
        assertThat(wa.sentTo(s.f().phone())).hasSize(1);
        assertThat(wa.sentTo(s.f().phone()).get(0).text()).isEqualTo("ठीक है 🙏 लेने के बाद ✅ चुन लीजिए");

        // a removed vote (empty arrays) is ignored
        assertThat(vote(pollId, s.f().phone(), null, null).body()).contains("ignored:vote-removed");

        vote(pollId, s.f().phone(), "✅ ले ली", 0);
        for (UUID d : s.doses()) assertThat(fx.doseStatus(d)).isEqualTo("taken");
    }

    @Test
    void aVoteFromAnotherNumberOrWithAForeignLabelDoesNothing() throws Exception {
        wa.setPollsEnabled(true);
        Slot s = threeAtOnce("en");
        scheduler(s.f()).tick(s.f().recipientId());
        String pollId = wa.pollsTo(s.f().phone()).get(0).messageId();
        String stranger = fx.track("+919999900001");

        vote(pollId, stranger, "✅ Taken", 0);
        vote(pollId, s.f().phone(), "Maybe", 0);
        vote(pollId, s.f().phone(), "✅ Taken", 1);   // label and index disagree
        vote("3EB0NOT-OUR-POLL", s.f().phone(), "✅ Taken", 0);

        for (UUID d : s.doses()) assertThat(fx.doseStatus(d)).isEqualTo("sent");
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void aHelperGetsTheGivenPoll() throws Exception {
        wa.setPollsEnabled(true);
        Family f = fx.family("en", true);
        String helper = fx.track(Fixtures.randomPhone());
        jdbc.update("update care_recipients set reminder_to = 'helper', reminder_name = 'Sunita', whatsapp_phone = ? where id = ?",
                helper, f.recipientId());
        fx.consent(f.recipientId());
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), Instant.now().minus(5, ChronoUnit.MINUTES), "pending", null, null);

        scheduler(f).tick(f.recipientId());

        Poll poll = wa.pollsTo(helper).get(0);
        assertThat(poll.question()).isEqualTo("Namaste Sunita ji 🙏\n💊 Time for Papa's medicine: Metformin (500 mg)\nGiven it to Papa?");
        assertThat(poll.options()).containsExactly("✅ Given", "⏰ Later");
        vote(poll.messageId(), helper, "⏰ Later", 1);
        assertThat(wa.sentTo(helper).get(0).text()).isEqualTo("Okay 🙏 Tap ✅ once you have given Papa the medicine.");
        vote(poll.messageId(), helper, "✅ Given", 0);
        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
        assertThat(wa.sentTo(helper).get(1).text()).isEqualTo("Thank you 🙏 Noted for Papa: Metformin ✅");
    }

    @Test
    void oneMedicineWithAPhotoInPollMode_photoFirstThenThePoll() throws Exception {
        wa.setPollsEnabled(true);
        storage.switchOn(true);
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        String path = f.recipientId() + "/strip.jpg";
        storage.put(path, jpeg());
        jdbc.update("update medicines set photo_url = ? where id = ?", path, m.medicineId());
        UUID dose = fx.dose(m.scheduleId(), Instant.now().minus(5, ChronoUnit.MINUTES), "pending", null, null);

        scheduler(f).tick(f.recipientId());

        assertThat(wa.images()).hasSize(1);
        assertThat(wa.images().get(0).caption()).isEqualTo("💊 *Metformin*");
        Poll poll = wa.pollsTo(f.phone()).get(0);
        assertThat(messageIdOf(dose)).isEqualTo(poll.messageId());   // the POLL id, so its votes match
    }

    // ================================================================ fallback

    @Test
    void aFailingPollFallsBackToTheTextReminder() {
        wa.setPollsEnabled(true);
        wa.pollMode("fail");
        Slot s = threeAtOnce("en");

        scheduler(s.f()).tick(s.f().recipientId());

        assertThat(wa.pollsTo(s.f().phone())).isEmpty();
        assertThat(wa.sentTo(s.f().phone())).hasSize(1);
        assertThat(wa.sentTo(s.f().phone()).get(0).text()).endsWith("Taken them all? Just reply *1*");
        for (UUID d : s.doses()) assertThat(fx.doseStatus(d)).isEqualTo("sent");
        assertThat(messageIdOf(s.doses().get(0))).startsWith("test-out-");
        assertThat(wa.pollsOn()).isTrue();   // a failure does not switch polls off
    }

    @Test
    void anOldGatewayWithoutMessageIdGetsTheTextToo_andPollsTurnOffUntilRestart() {
        wa.setPollsEnabled(true);
        wa.pollMode("no-id");
        Slot s = threeAtOnce("en");

        scheduler(s.f()).tick(s.f().recipientId());

        assertThat(wa.pollsTo(s.f().phone())).hasSize(1);   // delivered, but untrackable
        assertThat(wa.sentTo(s.f().phone())).hasSize(1);    // so the text goes too, and its id is stored
        assertThat(messageIdOf(s.doses().get(0))).startsWith("test-out-");
        assertThat(wa.pollsOn()).isFalse();

        // the next reminder does not even try a poll
        Family g = fx.family("en", true);
        Med m = fx.medicine(g, "Calcium", "1 tablet", 20, LocalTime.of(3, 0));
        fx.dose(m.scheduleId(), Instant.now().minus(5, ChronoUnit.MINUTES), "pending", null, null);
        scheduler(g).tick(g.recipientId());
        assertThat(wa.pollsTo(g.phone())).isEmpty();
        assertThat(wa.sentTo(g.phone())).hasSize(1);
    }

    // ================================================================ check-in poll

    private Family checkinDue(String language) {
        Family f = fx.family(language, true);
        jdbc.update("update care_recipients set checkin_time = ? where id = ?",
                ZonedDateTime.now(f.zone()).minusMinutes(1).toLocalTime().withSecond(0).withNano(0), f.recipientId());
        return f;
    }

    private UUID todaysCheckin(Family f) {
        return jdbc.queryForObject("select id from checkins where recipient_id = ? and date = ?", UUID.class, f.recipientId(),
                ZonedDateTime.now(f.zone()).toLocalDate());
    }

    @Test
    void theCheckinIsAPoll_callMeTellsTheFamily() throws Exception {
        wa.setPollsEnabled(true);
        Family f = checkinDue("hi");

        scheduler(f).tick(f.recipientId());

        Poll poll = wa.pollsTo(f.phone()).get(0);
        assertThat(poll.question()).isEqualTo("सुप्रभात पापा ☀️🙏\nआज आप कैसे हैं?");
        assertThat(poll.options()).containsExactly("😊 ठीक हूँ", "📞 फ़ोन कीजिए");
        UUID checkin = todaysCheckin(f);

        vote(poll.messageId(), f.phone(), "📞 फ़ोन कीजिए", 1);

        assertThat(fx.checkinStatus(checkin)).isEqualTo("replied");
        assertThat(fx.notifications(f.userId(), "checkin_call_requested")).isEqualTo(1);
        assertThat(wa.sentTo(f.phone()).get(0).text()).isEqualTo(org.smartgurukul.chhaon.whatsapp.reply.ReplyTexts.checkinCall("hi"));
    }

    @Test
    void fineThenChangedToCallMeStillTellsTheFamilyOnce() throws Exception {
        wa.setPollsEnabled(true);
        Family f = checkinDue("en");
        scheduler(f).tick(f.recipientId());
        Poll poll = wa.pollsTo(f.phone()).get(0);
        UUID checkin = todaysCheckin(f);

        vote(poll.messageId(), f.phone(), "😊 I'm fine", 0);
        assertThat(fx.checkinStatus(checkin)).isEqualTo("replied");
        assertThat(fx.notifications(f.userId(), "checkin_call_requested")).isEqualTo(0);

        vote(poll.messageId(), f.phone(), "📞 Please call me", 1);
        vote(poll.messageId(), f.phone(), "😊 I'm fine", 0);          // a different event? same claim as before: duplicate
        vote(poll.messageId(), f.phone(), "📞 Please call me", 1);    // duplicate
        assertThat(fx.notifications(f.userId(), "checkin_call_requested")).isEqualTo(1);
    }

    @Test
    void withPollsOffTheCheckinIsTheText() {
        Family f = checkinDue("en");
        scheduler(f).tick(f.recipientId());
        assertThat(wa.polls()).isEmpty();
        assertThat(wa.sentTo(f.phone()).get(0).text()).isEqualTo(
                "Good morning Papa ☀️🙏\nHow are you today?\n\nReply *1* if you are fine 😊\nReply *2* if you want a call 📞");
    }

    @Test
    void aPregnancyWeekIsSaidOnceInTheSlotReminder() {
        Family f = fx.family("en", true);
        jdbc.update("update care_recipients set care_type = 'pregnant', due_date = ?, call_name = null, name = 'Shivani Sharma' where id = ?",
                ZonedDateTime.now(f.zone()).toLocalDate().plusDays(112), f.recipientId());
        Instant at = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
        Med a = fx.medicine(f, "Iron + folic acid", "1 tablet", 20, LocalTime.of(3, 0));
        Med b = fx.medicine(f, "Calcium", "1 tablet", 20, LocalTime.of(3, 0));
        fx.dose(a.scheduleId(), at, "pending", null, null);
        fx.dose(b.scheduleId(), at, "pending", null, null);

        scheduler(f).tick(f.recipientId());

        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("\nWeek 24 starts today 💛\nYour ").contains("• *Calcium*")
                .contains("• *Iron + folic acid*");
    }
}
