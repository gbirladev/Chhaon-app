package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.smartgurukul.chhaon.support.GatewayClient;
import org.smartgurukul.chhaon.support.RecordingWhatsAppSender.Poll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** "Send a sample reminder now" (docs/sample-reminder.md): who may send it, the gates, the daily limit, and taps on the sample. */
class SampleReminderTest extends DbTest {

    @Autowired
    JwtService jwt;

    @AfterEach
    void off() {
        wa.setPollsEnabled(false);
    }

    private MvcResult send(UUID userId, UUID recipientId) throws Exception {
        return mvc.perform(post("/api/v1/recipients/{id}/sample-reminder", recipientId)
                .header("Authorization", "Bearer " + jwt.issueToken(userId, false))).andReturn();
    }

    private MvcResult status(UUID userId, UUID recipientId) throws Exception {
        return mvc.perform(get("/api/v1/recipients/{id}/sample-reminder", recipientId)
                .header("Authorization", "Bearer " + jwt.issueToken(userId, false))).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private int doseRows(Family f) {
        Integer n = fx.jdbc().queryForObject("select count(*) from dose_events d join medicine_schedules s on s.id = d.schedule_id " +
                "join medicines m on m.id = s.medicine_id where m.recipient_id = ?", Integer.class, f.recipientId());
        return n == null ? 0 : n;
    }

    /** A medicine whose next real reminder is a waiting dose two hours from now. */
    private UUID waitingDose(Family f, String name) {
        Instant at = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        Med m = fx.medicine(f, name, "1 tablet", 10, Fixtures.localTime(f, at));
        return fx.dose(m.scheduleId(), at, "pending", null, null);
    }

    // ================================================================ who may send, and the gates

    @Test
    void onlyAMemberOfTheCircle() throws Exception {
        Family f = fx.family("en", true);
        waitingDose(f, "Metformin");
        UUID stranger = fx.user("Stranger");
        assertThat(send(stranger, f.recipientId()).getResponse().getStatus()).isEqualTo(404);
        assertThat(status(stranger, f.recipientId()).getResponse().getStatus()).isEqualTo(404);
        assertThat(wa.sent()).isEmpty();

        UUID sibling = fx.member(f, "Arjun");   // a family member (role member) may
        assertThat(send(sibling, f.recipientId()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void notBeforeTheReceiverAgreed() throws Exception {
        Family f = fx.family("en", false);
        waitingDose(f, "Metformin");
        MvcResult r = send(f.userId(), f.recipientId());
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r)).contains("\"status\":\"not_consented\"");
        assertThat(body(status(f.userId(), f.recipientId()))).contains("\"reason\":\"not_consented\"");
        assertThat(wa.sent()).isEmpty();
        assertThat(wa.polls()).isEmpty();
    }

    @Test
    void notToANumberThatSaidStop() throws Exception {
        Family f = fx.family("en", true);
        waitingDose(f, "Metformin");
        fx.optOutPhone(f.phone());
        MvcResult r = send(f.userId(), f.recipientId());
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r)).contains("\"status\":\"opted_out\"");
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void nothingToSendWithoutAnActiveMedicine() throws Exception {
        Family f = fx.family("en", true);
        MvcResult r = send(f.userId(), f.recipientId());
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r)).contains("\"status\":\"no_medicines\"");

        Med paused = fx.medicine(f, "Metformin", "1 tablet", 10, LocalTime.of(8, 0));
        fx.jdbc().update("update medicines set active = false where id = ?", paused.medicineId());
        assertThat(body(send(f.userId(), f.recipientId()))).contains("\"status\":\"no_medicines\"");
        assertThat(wa.sent()).isEmpty();
    }

    // ================================================================ the message, the daily limit, no dose rows

    @Test
    void oneLabelledReminderForTheNextSlot_oncePerDay_noDoseRows() throws Exception {
        Family f = fx.family("en", true);
        UUID dose = waitingDose(f, "Metformin");
        int before = doseRows(f);
        assertThat(body(status(f.userId(), f.recipientId()))).contains("\"reason\":\"ok\"").contains("\"realReminderSent\":false");

        MvcResult r = send(f.userId(), f.recipientId());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("\"status\":\"sent\"").contains("\"slotTime\"");
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        String text = wa.sentTo(f.phone()).get(0).text();
        assertThat(text).startsWith("🔔 Sample reminder from Chhaon (so you know what it looks like)\nNamaste Papa 🙏\n")
                .contains("*Metformin*").contains("Just reply *1*");
        assertThat(doseRows(f)).isEqualTo(before);
        assertThat(fx.doseStatus(dose)).isEqualTo("pending");
        assertThat(fx.dose(dose).get("whatsapp_message_id")).isNull();

        MvcResult again = send(f.userId(), f.recipientId());
        assertThat(again.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(again)).contains("\"status\":\"limited\"");
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(body(status(f.userId(), f.recipientId()))).contains("\"reason\":\"limited\"").contains("\"sentToday\":true");
    }

    @Test
    void hindiWithoutAWaitingDoseUsesTheScheduleTime() throws Exception {
        Family f = fx.family("hi", true);
        fx.medicine(f, "Ecosprin 150", "1 tablet", 10, LocalTime.of(20, 0));
        fx.medicine(f, "Metformin 500", "1 tablet", 10, LocalTime.of(20, 0));
        assertThat(send(f.userId(), f.recipientId()).getResponse().getStatus()).isEqualTo(200);
        String text = wa.sentTo(f.phone()).get(0).text();
        assertThat(text).startsWith("🔔 छाँव का नमूना रिमाइंडर (ताकि आप देख लें कि यह कैसा दिखेगा)\nनमस्ते पापा 🙏\nरात 8:00 की दवाएं:")
                .contains("*Ecosprin 150*").contains("*Metformin 500*");
        assertThat(doseRows(f)).isZero();
    }

    @Test
    void aFailedSendDoesNotUseUpTheDay() throws Exception {
        Family f = fx.family("en", true);
        waitingDose(f, "Metformin");
        wa.failSends(true);
        MvcResult r = send(f.userId(), f.recipientId());
        assertThat(r.getResponse().getStatus()).isEqualTo(502);
        assertThat(body(r)).contains("\"status\":\"send_failed\"");
        wa.failSends(false);
        assertThat(send(f.userId(), f.recipientId()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theGlobalGuard() throws Exception {
        Family f = fx.family("en", true);
        waitingDose(f, "Metformin");
        for (int i = 0; i < 60; i++) {
            fx.jdbc().update("insert into whatsapp_events (id) values (?)", "gw:sample:" + UUID.randomUUID() + ":2026-11-08");
        }
        MvcResult r = send(f.userId(), f.recipientId());
        assertThat(r.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(r)).contains("\"status\":\"busy\"");
        assertThat(wa.sent()).isEmpty();
    }

    // ================================================================ a tap on the sample never confirms a real dose

    @Test
    void aPollVoteOnTheSampleIsAnsweredButRecordsNothing() throws Exception {
        wa.setPollsEnabled(true);
        Family f = fx.family("en", true);
        UUID dose = waitingDose(f, "Metformin");
        assertThat(send(f.userId(), f.recipientId()).getResponse().getStatus()).isEqualTo(200);
        assertThat(wa.sentTo(f.phone())).isEmpty();
        Poll poll = wa.pollsTo(f.phone()).get(0);
        assertThat(poll.question()).startsWith("🔔 Sample reminder from Chhaon (so you know what it looks like)\nNamaste Papa 🙏\n")
                .contains("Metformin").hasSizeLessThanOrEqualTo(ReminderTexts.POLL_QUESTION_MAX);
        assertThat(poll.options()).containsExactly("✅ Taken", "⏰ Later");

        gateway.post(gateway.pollVote(poll.messageId(), f.phone(), "✅ Taken", 0));
        assertThat(fx.doseStatus(dose)).isEqualTo("pending");
        assertThat(fx.stock(fx.jdbc().queryForObject("select s.medicine_id from dose_events d join medicine_schedules s " +
                "on s.id = d.schedule_id where d.id = ?", UUID.class, dose))).isEqualTo(10);
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(wa.sentTo(f.phone()).get(0).text()).startsWith("👍 That was only a sample, so no medicine was marked as taken.")
                .contains("The real reminders start at ");
        assertThat(fx.jdbc().queryForObject("select count(*) from notifications where user_id = ?", Integer.class, f.userId())).isZero();

        // a second tap: no second answer, still nothing recorded
        gateway.post(gateway.pollVote(poll.messageId(), f.phone(), "⏰ Later", 1));
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(fx.doseStatus(dose)).isEqualTo("pending");
    }

    @Test
    void aTyped1AfterTheSampleAnswersTheSample_notAnOlderOpenReminder() throws Exception {
        Family f = fx.family("en", true);
        Instant earlier = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
        Med m = fx.medicine(f, "Amlodipine", "5 mg", 10, Fixtures.localTime(f, earlier));
        UUID open = fx.dose(m.scheduleId(), earlier, "sent", earlier, "real-reminder-1");
        waitingDose(f, "Metformin");
        assertThat(send(f.userId(), f.recipientId()).getResponse().getStatus()).isEqualTo(200);
        wa.reset();

        gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "1"));
        assertThat(fx.doseStatus(open)).isEqualTo("sent");
        assertThat(wa.sentTo(f.phone()).get(0).text()).startsWith("👍 That was only a sample");

        // a swipe-reply quoting the real reminder still confirms that one
        wa.reset();
        gateway.post(gateway.received(Fixtures.messageId(), GatewayClient.jid(f.phone()), "1", "real-reminder-1"));
        assertThat(fx.doseStatus(open)).isEqualTo("taken");
    }

    @Test
    void aRealReminderSentAfterTheSampleWinsATyped1() throws Exception {
        Family f = fx.family("en", true);
        waitingDose(f, "Metformin");
        assertThat(send(f.userId(), f.recipientId()).getResponse().getStatus()).isEqualTo(200);
        // make the sample look 10 minutes old, then a real reminder goes out
        fx.jdbc().update("update whatsapp_events set received_at = now() - interval '10 minutes' where id like 'gw:sample:%'");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Med m = fx.medicine(f, "Amlodipine", "5 mg", 10, Fixtures.localTime(f, now));
        UUID real = fx.dose(m.scheduleId(), now, "sent", Instant.now(), "real-reminder-2");
        wa.reset();

        gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "1"));
        assertThat(fx.doseStatus(real)).isEqualTo("taken");
    }
}
