package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.scheduler.SchedulerService;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.smartgurukul.chhaon.support.GatewayClient;
import org.smartgurukul.chhaon.support.RecordingWhatsAppSender.Sent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WhatsApp alerts to the family: the "Get alerts on WhatsApp" opt-in (POST /alerts/whatsapp/request + the typed answer),
 * STOP / START, delivery of missed dose / missed check-in / call me / parent stopped / low stock, no duplicates, the "Me"
 * profile, gateway failures, and a number that is both a caregiver's and a parent's.
 */
class FamilyAlertsTest extends DbTest {

    @Autowired
    JwtService jwt;

    @Autowired
    SchedulerService scheduler;

    private static Instant ago(int minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES);
    }

    private MvcResult ask(UUID userId, String body) throws Exception {
        var req = post("/alerts/whatsapp/request").contentType(MediaType.APPLICATION_JSON).content(body);
        if (userId != null) req = req.header("Authorization", "Bearer " + jwt.issueToken(userId, false));
        return mvc.perform(req).andReturn();
    }

    private MvcResult askFor(UUID userId, String phone) throws Exception {
        return ask(userId, "{\"phone\":\"" + phone + "\"}");
    }

    private void say(String phone, String text) throws Exception {
        assertThat(gateway.post(gateway.received(Fixtures.messageId(), phone, text)).status()).isEqualTo(200);
    }

    /** A medicine whose own schedule is far from now, so the tick never makes or sends a dose of it by itself. */
    private Med medicine(Family f, String name) {
        return fx.medicine(f, name, "1 tablet", 30, Fixtures.localTime(f, Instant.now().plus(6, ChronoUnit.HOURS)));
    }

    /** A reminder sent long enough ago that the next tick marks it missed. */
    private UUID unansweredDose(Med m, Instant due) {
        return fx.dose(m.scheduleId(), due, "sent", due.plus(1, ChronoUnit.MINUTES), Fixtures.messageId());
    }

    private List<Sent> alertsTo(String phone) {
        return wa.sentTo(phone).stream().filter(s -> s.text().startsWith("Chhaon alert 🔔") || s.text().startsWith("छाँव अलर्ट 🔔")).toList();
    }

    // ================================================================ opt-in

    @Test
    void theButtonSendsTheQuestion_andTyping1TurnsAlertsOn() throws Exception {
        Family f = fx.family("en", true);
        String mine = fx.track(Fixtures.randomPhone());

        MvcResult r = askFor(f.userId(), mine);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentAsString()).isEqualTo("{\"status\":\"sent\"}");
        assertThat(wa.sentTo(mine)).hasSize(1);
        assertThat(wa.sentTo(mine).get(0).text()).contains("Ananya").contains("Reply *1* to turn alerts on").contains("STOP")
                .doesNotContain("http");
        Map<String, Object> waiting = fx.alerts(f.userId());
        assertThat(waiting.get("alert_phone")).isEqualTo(mine);
        assertThat(waiting.get("alert_requested_at")).isNotNull();
        assertThat(waiting.get("alert_consent_at")).isNull();

        say(mine, "1");

        Map<String, Object> on = fx.alerts(f.userId());
        assertThat(on.get("alert_consent_at")).isNotNull();
        assertThat(on.get("alert_confirmed_at")).isNotNull();
        assertThat(wa.sentTo(mine)).hasSize(2);
        assertThat(wa.sentTo(mine).get(1).text()).contains("alerts are on");

        // A second "1" changes nothing and gets no second reply.
        say(mine, "1");
        assertThat(wa.sentTo(mine)).hasSize(2);
    }

    @Test
    void thumbsUpAndOkAlsoAgree_inHindiToo() throws Exception {
        Family f = fx.family("hi", true);
        fx.jdbc().update("update profiles set language = 'hi' where id = ?", f.userId());
        String mine = fx.track(Fixtures.randomPhone());
        askFor(f.userId(), mine);
        assertThat(wa.sentTo(mine).get(0).text()).contains("*1*").contains("STOP");

        say(mine, "👍");

        assertThat(fx.alerts(f.userId()).get("alert_consent_at")).isNotNull();
        assertThat(wa.sentTo(mine).get(1).text()).contains("अलर्ट चालू");
    }

    @Test
    void badNumbersAndNoLoginAreRefused() throws Exception {
        Family f = fx.family("en", true);
        assertThat(ask(f.userId(), "{\"phone\":\"98765\"}").getResponse().getStatus()).isEqualTo(422);
        assertThat(ask(f.userId(), "{}").getResponse().getStatus()).isEqualTo(422);
        assertThat(askFor(null, Fixtures.randomPhone()).getResponse().getStatus()).isIn(401, 403);
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void aSecondRequestWaits_andAStoppedNumberIsNotAsked() throws Exception {
        Family f = fx.family("en", true);
        String mine = fx.track(Fixtures.randomPhone());
        askFor(f.userId(), mine);
        assertThat(askFor(f.userId(), mine).getResponse().getContentAsString()).contains("\"status\":\"too_soon\"").contains("retry_after");

        String stopped = Fixtures.randomPhone();
        fx.optOutPhone(stopped);
        assertThat(askFor(f.userId(), stopped).getResponse().getContentAsString()).isEqualTo("{\"status\":\"opted_out\"}");
        assertThat(wa.sentTo(stopped)).isEmpty();
    }

    @Test
    void whenWhatsAppIsDown_theRequestFails502_andDoesNotCountOrChangeAnything() throws Exception {
        Family f = fx.family("en", true);
        String mine = fx.track(Fixtures.randomPhone());
        wa.failSends(true);

        assertThat(askFor(f.userId(), mine).getResponse().getStatus()).isEqualTo(502);
        assertThat(fx.alerts(f.userId()).get("alert_phone")).isNull();

        wa.failSends(false);
        assertThat(askFor(f.userId(), mine).getResponse().getContentAsString()).isEqualTo("{\"status\":\"sent\"}");
    }

    @Test
    void typing2DeclinesAndThatNumberIsNotAskedAgain_untilItTypesStart() throws Exception {
        Family f = fx.family("en", true);
        String mine = fx.track(Fixtures.randomPhone());
        askFor(f.userId(), mine);

        say(mine, "2");

        Map<String, Object> off = fx.alerts(f.userId());
        assertThat(off.get("alert_consent_at")).isNull();
        assertThat(off.get("alert_requested_at")).isNull(); // the app no longer shows "waiting"
        assertThat(fx.phoneOptedOut(mine)).isTrue();         // not a parent's number: never asked again without START
        assertThat(wa.sentTo(mine).get(1).text()).contains("stay off");

        fx.jdbc().update("delete from consent_requests where phone = ?", mine); // skip the 10 minute wait
        assertThat(askFor(f.userId(), mine).getResponse().getContentAsString()).isEqualTo("{\"status\":\"opted_out\"}");

        say(mine, "START");
        assertThat(fx.phoneOptedOut(mine)).isFalse();
        assertThat(wa.sentTo(mine).get(wa.sentTo(mine).size() - 1).text()).contains("turn on WhatsApp alerts again");
    }

    @Test
    void stopTurnsAlertsOff_andStartBringsThemBack() throws Exception {
        Family f = fx.family("en", true);
        String mine = fx.track(Fixtures.randomPhone());
        fx.alertsOn(f.userId(), mine);

        say(mine, "STOP");
        assertThat(fx.alerts(f.userId()).get("alert_consent_at")).isNull();
        assertThat(wa.sentTo(mine).get(0).text()).contains("alerts are off");

        // A missed dose now: the bell still rings in the app, but nothing goes to WhatsApp.
        Med m = medicine(f, "Metformin");
        unansweredDose(m, ago(90));
        scheduler.tick(f.recipientId());
        assertThat(fx.notifications(f.userId(), "dose_missed")).isEqualTo(1);
        assertThat(alertsTo(mine)).isEmpty();
        assertThat(fx.outbox(f.userId(), "dose_missed").get(0).get("whatsapp_skipped_at")).isNotNull();

        say(mine, "START");
        assertThat(fx.alerts(f.userId()).get("alert_consent_at")).isNotNull();
        assertThat(wa.sentTo(mine).get(wa.sentTo(mine).size() - 1).text()).contains("alerts are on again");
    }

    @Test
    void stopUnderAnUnansweredQuestionClosesIt() throws Exception {
        Family f = fx.family("en", true);
        String mine = fx.track(Fixtures.randomPhone());
        askFor(f.userId(), mine);

        say(mine, "STOP");

        assertThat(fx.phoneOptedOut(mine)).isTrue();
        assertThat(fx.alerts(f.userId()).get("alert_requested_at")).isNull();
        assertThat(wa.sentTo(mine).get(1).text()).contains("alerts are off");
        say(mine, "1"); // too late: the number said STOP
        assertThat(fx.alerts(f.userId()).get("alert_consent_at")).isNull();
    }

    // ================================================================ delivery

    @Test
    void aMissedDoseAlertsEveryAgreedCaregiverOnce_andNobodyElse() throws Exception {
        Family f = fx.family("en", true);
        UUID sister = fx.member(f, "Priya");
        UUID uncle = fx.member(f, "Vikram");
        String ownerPhone = fx.track(Fixtures.randomPhone());
        String sisterPhone = fx.track(Fixtures.randomPhone());
        fx.alertsOn(f.userId(), ownerPhone);
        fx.alertsOn(sister, sisterPhone);
        // the uncle never turned alerts on
        Med m = medicine(f, "Metformin");
        unansweredDose(m, ago(90));

        Map<String, Integer> first = scheduler.tick(f.recipientId());

        assertThat(first.get("doses_missed")).isEqualTo(1);
        assertThat(first.get("alerts_sent")).isEqualTo(2);
        assertThat(first.get("alerts_skipped")).isEqualTo(1);
        for (String phone : List.of(ownerPhone, sisterPhone)) {
            assertThat(alertsTo(phone)).hasSize(1);
            assertThat(alertsTo(phone).get(0).text()).contains("Papa has not confirmed *Metformin* (due ")
                    .contains("Reply STOP to turn off these alerts").doesNotContain("http").doesNotContain(f.phone());
        }
        assertThat(fx.notifications(uncle, "dose_missed")).isEqualTo(1); // the bell still rings for him
        assertThat(fx.outbox(uncle, "dose_missed").get(0).get("whatsapp_skipped_at")).isNotNull();
        assertThat(fx.outbox(f.userId(), "dose_missed").get(0).get("whatsapp_sent_at")).isNotNull();

        // No duplicates across ticks.
        Map<String, Integer> second = scheduler.tick(f.recipientId());
        assertThat(second.get("alerts_sent")).isEqualTo(0);
        assertThat(alertsTo(ownerPhone)).hasSize(1);
        assertThat(alertsTo(sisterPhone)).hasSize(1);
    }

    @Test
    void severalMedicinesDueTogetherAreOneAlert() throws Exception {
        Family f = fx.family("en", true);
        String mine = fx.track(Fixtures.randomPhone());
        fx.alertsOn(f.userId(), mine);
        Instant due = ago(90).truncatedTo(ChronoUnit.MINUTES);
        unansweredDose(medicine(f, "Metformin"), due);
        unansweredDose(medicine(f, "Amlodipine"), due);

        scheduler.tick(f.recipientId());

        assertThat(fx.notifications(f.userId(), "dose_missed")).isEqualTo(1);
        assertThat(fx.outbox(f.userId(), "dose_missed").get(0).get("payload").toString()).contains("\"count\": 2");
        assertThat(alertsTo(mine)).hasSize(1);
        assertThat(alertsTo(mine).get(0).text()).contains("has not confirmed 2 medicines");
    }

    @Test
    void aMissedCheckinAlertsInTheCaregiversLanguage() throws Exception {
        Family f = fx.family("en", true);
        fx.jdbc().update("update profiles set language = 'hi' where id = ?", f.userId());
        String mine = fx.track(Fixtures.randomPhone());
        fx.alertsOn(f.userId(), mine);
        fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", ago(200));

        scheduler.tick(f.recipientId());

        assertThat(fx.notifications(f.userId(), "checkin_missed")).isEqualTo(1);
        assertThat(alertsTo(mine)).hasSize(1);
        assertThat(alertsTo(mine).get(0).text()).startsWith("छाँव अलर्ट").contains("Papa ने आज सुबह के चेक-इन").contains("STOP");
    }

    @Test
    void callMeFromTheParentAlertsTheFamily() throws Exception {
        Family f = fx.family("en", true);
        String mine = fx.track(Fixtures.randomPhone());
        fx.alertsOn(f.userId(), mine);
        fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", ago(10));

        say(f.phone(), "2");
        assertThat(fx.notifications(f.userId(), "checkin_call_requested")).isEqualTo(1);
        scheduler.tick(f.recipientId());

        assertThat(alertsTo(mine)).hasSize(1);
        assertThat(alertsTo(mine).get(0).text()).contains("Papa asked for a call");
        scheduler.tick(f.recipientId());
        assertThat(alertsTo(mine)).hasSize(1);
    }

    @Test
    void aParentWhoTypesStopIsReportedToTheFamily() throws Exception {
        Family f = fx.family("en", true);
        String mine = fx.track(Fixtures.randomPhone());
        fx.alertsOn(f.userId(), mine);

        say(f.phone(), "STOP");
        scheduler.tick(f.recipientId());

        assertThat(alertsTo(mine)).hasSize(1);
        assertThat(alertsTo(mine).get(0).text()).contains("chose to stop Chhaon messages");
    }

    @Test
    void lowStockAlerts() throws Exception {
        Family f = fx.family("en", true);
        String mine = fx.track(Fixtures.randomPhone());
        fx.alertsOn(f.userId(), mine);
        Med m = medicine(f, "Thyronorm");
        fx.jdbc().update("update medicines set refill_threshold = 5, stock_count = 3 where id = ?", m.medicineId());

        scheduler.tick(f.recipientId());

        assertThat(alertsTo(mine)).hasSize(1);
        assertThat(alertsTo(mine).get(0).text()).contains("Papa's medicine *Thyronorm* is running low");
    }

    @Test
    void oldNotificationsAreNeverSentLate() throws Exception {
        Family f = fx.family("en", true);
        String mine = fx.track(Fixtures.randomPhone());
        fx.alertsOn(f.userId(), mine);
        fx.jdbc().update("insert into notifications (user_id, type, payload, created_at) values (?, 'checkin_missed', " +
                "jsonb_build_object('recipient_id', ?::text), now() - interval '7 hours')", f.userId(), f.recipientId().toString());

        scheduler.tick(f.recipientId());

        assertThat(alertsTo(mine)).isEmpty();
    }

    // ================================================================ the "Me" profile

    @Test
    void myOwnProfile_noFamilyAlertsAboutMyself_butLowStockStillComes() throws Exception {
        String mine = Fixtures.randomPhone();
        Family me = fx.selfFamily(mine, "en", true);
        fx.alertsOn(me.userId(), mine);
        Med m = medicine(me, "Vitamin D");
        unansweredDose(m, ago(90));

        scheduler.tick(me.recipientId());
        assertThat(fx.notifications(me.userId(), "dose_missed")).isEqualTo(0);
        assertThat(alertsTo(mine)).isEmpty();

        fx.jdbc().update("update medicines set refill_threshold = 5, stock_count = 0 where id = ?", m.medicineId());
        scheduler.tick(me.recipientId());
        assertThat(alertsTo(mine)).hasSize(1);
        assertThat(alertsTo(mine).get(0).text()).contains("has run out");
    }

    // ================================================================ failures

    @Test
    void aGatewayFailureKeepsTheInAppNotification_andRetriesAtMostThreeTimes() throws Exception {
        Family f = fx.family("en", true);
        String mine = fx.track(Fixtures.randomPhone());
        fx.alertsOn(f.userId(), mine);
        unansweredDose(medicine(f, "Metformin"), ago(90));
        wa.failSends(true);

        Map<String, Integer> r = scheduler.tick(f.recipientId());

        assertThat(r.get("doses_missed")).isEqualTo(1);
        assertThat(r.get("alerts_failed")).isEqualTo(1);
        assertThat(r).doesNotContainKey("step_errors");
        Map<String, Object> row = fx.outbox(f.userId(), "dose_missed").get(0);
        assertThat(row.get("whatsapp_sent_at")).isNull();
        assertThat(row.get("whatsapp_attempts")).isEqualTo(1);

        // the gateway is back: sent once
        wa.failSends(false);
        scheduler.tick(f.recipientId());
        assertThat(alertsTo(mine)).hasSize(1);

        // a second alert that keeps failing is given up after three attempts
        fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", ago(200));
        wa.failSends(true);
        for (int i = 0; i < 4; i++) scheduler.tick(f.recipientId());
        Map<String, Object> gaveUp = fx.outbox(f.userId(), "checkin_missed").get(0);
        assertThat(gaveUp.get("whatsapp_attempts")).isEqualTo(3);
        assertThat(gaveUp.get("whatsapp_skipped_at")).isNotNull();
        assertThat(fx.notifications(f.userId(), "checkin_missed")).isEqualTo(1);
    }

    // ================================================================ one number for a caregiver AND a parent

    @Test
    void sharedNumber_theAlertQuestionAskedLastWins_thenTheNext1AnswersTheDose() throws Exception {
        Family f = fx.family("en", true);
        Med m = medicine(f, "Metformin");
        UUID dose = fx.dose(m.scheduleId(), ago(22), "sent", ago(20), Fixtures.messageId()); // reminder BEFORE the question
        askFor(f.userId(), f.phone());                                                    // the caregiver uses the parent's number

        say(f.phone(), "1");

        assertThat(fx.alerts(f.userId()).get("alert_consent_at")).isNotNull();
        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
        assertThat(wa.sentTo(f.phone()).get(1).text()).contains("alerts are on").contains("Reply 1 again");

        say(f.phone(), "1");
        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
    }

    @Test
    void sharedNumber_aReminderSentAfterTheQuestionIsAnsweredFirst() throws Exception {
        Family f = fx.family("en", true);
        askFor(f.userId(), f.phone());
        fx.jdbc().update("update profiles set alert_requested_at = now() - interval '30 minutes' where id = ?", f.userId());
        Med m = medicine(f, "Metformin");
        UUID dose = fx.dose(m.scheduleId(), ago(7), "sent", ago(5), Fixtures.messageId()); // reminder AFTER the question

        say(f.phone(), "1");

        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
        assertThat(fx.alerts(f.userId()).get("alert_consent_at")).isNull();

        say(f.phone(), "1"); // nothing else is open for the parent now: this one turns alerts on
        assertThat(fx.alerts(f.userId()).get("alert_consent_at")).isNotNull();
    }

    @Test
    void sharedNumber_aSwipeReplyToTheReminderAlwaysAnswersTheReminder() throws Exception {
        Family f = fx.family("en", true);
        Med m = medicine(f, "Metformin");
        String reminderId = Fixtures.messageId();
        UUID dose = fx.dose(m.scheduleId(), ago(22), "sent", ago(20), reminderId);
        askFor(f.userId(), f.phone()); // asked last

        gateway.post(gateway.received(Fixtures.messageId(), GatewayClient.jid(f.phone()), "1", reminderId));

        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
        assertThat(fx.alerts(f.userId()).get("alert_consent_at")).isNull();
    }

    @Test
    void sharedNumber_declining2DoesNotStopTheParentsReminders() throws Exception {
        Family f = fx.family("en", true);
        askFor(f.userId(), f.phone());

        say(f.phone(), "2");

        assertThat(fx.alerts(f.userId()).get("alert_consent_at")).isNull();
        assertThat(fx.phoneOptedOut(f.phone())).isFalse();
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNull();
    }

    @Test
    void sharedNumber_aPendingParentConsentAskedLaterWins() throws Exception {
        Family f = fx.family("en", false);
        askFor(f.userId(), f.phone());
        fx.jdbc().update("update profiles set alert_requested_at = now() - interval '20 minutes' where id = ?", f.userId());
        fx.jdbc().update("update care_recipients set consent_requested_at = now() where id = ?", f.recipientId());

        say(f.phone(), "1");

        assertThat(fx.recipient(f.recipientId()).get("consent_at")).isNotNull();
        assertThat(fx.alerts(f.userId()).get("alert_consent_at")).isNull();
    }

    @Test
    void sharedNumber_alertsGoToTheCaregiverEvenWhenItIsAlsoAParentsNumber() throws Exception {
        // Test data often uses one phone for everything: the caregiver's alert number = their parent's number.
        Family f = fx.family("en", true);
        fx.alertsOn(f.userId(), f.phone());
        unansweredDose(medicine(f, "Metformin"), ago(90));

        scheduler.tick(f.recipientId());

        assertThat(alertsTo(f.phone())).hasSize(1);
    }

    // ================================================================ texts

    @Test
    void textsAreShortPlainAndEndWithHowToReply() {
        for (String type : AlertTexts.TYPES) {
            for (String lang : List.of("en", "hi")) {
                String t = AlertTexts.alert(type, lang, "Papa", "Metformin", null, "8:00 AM");
                assertThat(t).as(type + "/" + lang).isNotNull().doesNotContain("http").doesNotContain("www").hasSizeLessThan(400);
                assertThat(t.trim()).as(type + "/" + lang).endsWith(lang.equals("hi") ? "STOP लिखें।" : "turn off these alerts.");
            }
        }
        assertThat(AlertTexts.alert("dose_missed", "en", "", null, null, null))
                .contains("Your parent has not confirmed a medicine today.");
        assertThat(AlertTexts.alert("dose_missed", "en", "*Papa*\nhi", "Met_formin", null, null)).contains("Papa hi has not confirmed *Metformin*");
        assertThat(AlertTexts.alert("unknown", "en", "Papa", null, null, null)).isNull();
        Instant eight = LocalDate.of(2026, 11, 5).atTime(LocalTime.of(8, 0)).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        assertThat(AlertTexts.clock(eight, "Asia/Kolkata", "Asia/Kolkata", "en")).isEqualTo("8:00 AM");
        assertThat(AlertTexts.clock(eight, "Asia/Kolkata", "Europe/London", "en")).isEqualTo("8:00 AM (their time)");
        assertThat(AlertTexts.clock(eight, "Asia/Kolkata", null, "hi")).isEqualTo("सुबह 8:00");
    }
}
