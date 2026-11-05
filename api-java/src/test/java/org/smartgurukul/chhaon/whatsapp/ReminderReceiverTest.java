package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.scheduler.SchedulerService;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.smartgurukul.chhaon.support.RecordingWhatsAppSender.Sent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * "Who gets the reminder" (migration 20261105000044, docs/reminder-receiver.md): the person, the caregiver ("Me") or a helper.
 * Saving the choice, the consent question, the third-person reminder, no check-in for a helper, replies from a helper
 * ("diya"), numbers shared by two people or by a caregiver's alerts and reminders, and the family's alerts naming who was
 * reminded.
 */
class ReminderReceiverTest extends DbTest {

    @Autowired
    JwtService jwt;

    @Autowired
    SchedulerService scheduler;

    private final List<UUID> createdRecipients = new ArrayList<>();

    @AfterEach
    void removeApiCreatedProfiles() {
        for (UUID id : createdRecipients) {
            fx.jdbc().update("delete from care_circles where id = (select circle_id from care_recipients where id = ?)", id);
        }
        createdRecipients.clear();
    }

    private static Instant ago(int minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES);
    }

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    /** Reminders for this person now go to a helper with this number (agreed already). */
    private void toHelper(Family f, String phone, String name, String relation) {
        fx.jdbc().update("update care_recipients set reminder_to = 'helper', reminder_name = ?, reminder_relation = ?, whatsapp_phone = ? " +
                "where id = ?", name, relation, phone, f.recipientId());
        fx.track(phone);
        fx.consent(f.recipientId());
    }

    /** Reminders for this person now go to the caregiver (user) on this number (agreed already). */
    private void toCaregiver(Family f, UUID userId, String phone, String name) {
        fx.jdbc().update("update care_recipients set reminder_to = 'caregiver', reminder_name = ?, reminder_user_id = ?, whatsapp_phone = ? " +
                "where id = ?", name, userId, phone, f.recipientId());
        fx.track(phone);
        fx.consent(f.recipientId());
    }

    private void callName(Family f, String call) {
        fx.jdbc().update("update care_recipients set call_name = ? where id = ?", call, f.recipientId());
    }

    private void say(String phone, String text) throws Exception {
        assertThat(gateway.post(gateway.received(Fixtures.messageId(), phone, text)).status()).isEqualTo(200);
    }

    private UUID sentDose(Med m, int sentMinutesAgo) {
        return fx.dose(m.scheduleId(), ago(sentMinutesAgo + 2), "sent", ago(sentMinutesAgo), Fixtures.messageId());
    }

    private Map<String, Object> receiver(UUID recipientId) {
        return fx.jdbc().queryForMap("select reminder_to, reminder_name, reminder_relation, reminder_user_id, whatsapp_phone, consent_at " +
                "from care_recipients where id = ?", recipientId);
    }

    private MvcResult createViaApi(UUID userId, String json) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/recipients").header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(r.getResponse().getContentAsString());
        if (r.getResponse().getStatus() == 201 || r.getResponse().getStatus() == 200) {
            assertThat(m.find()).isTrue();
            createdRecipients.add(UUID.fromString(m.group(1)));
        }
        return r;
    }

    private static UUID idOf(MvcResult r) throws Exception {
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(r.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return UUID.fromString(m.group(1));
    }

    private List<Sent> alertsTo(String phone) {
        return wa.sentTo(phone).stream().filter(s -> s.text().startsWith("Chhaon alert 🔔") || s.text().startsWith("छाँव अलर्ट 🔔")).toList();
    }

    // ================================================================ saving the choice (API)

    @Test
    void aHelperIsSavedWithNameAndRelation_andTheConsentQuestionSaysForWhomAndOnWhoseBehalf() throws Exception {
        UUID me = fx.user("Ganesh Sharma");
        String helper = fx.track(Fixtures.randomPhone());
        MvcResult r = createViaApi(me, "{\"name\":\"Ramesh\",\"relation\":\"father\",\"callName\":\"Papa\",\"language\":\"en\"," +
                "\"whatsappPhone\":\"" + helper + "\",\"reminderTo\":\"helper\",\"reminderName\":\"Sunita\",\"reminderRelation\":\"maid\"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(r.getResponse().getContentAsString()).contains("\"reminderTo\":\"helper\"").contains("\"reminderName\":\"Sunita\"");
        UUID id = idOf(r);
        assertThat(receiver(id)).containsEntry("reminder_to", "helper").containsEntry("reminder_relation", "maid")
                .containsEntry("whatsapp_phone", helper);

        MvcResult asked = mvc.perform(post("/recipients/{id}/consent-request", id).header("Authorization", bearer(me))).andReturn();
        assertThat(asked.getResponse().getContentAsString()).contains("sent");
        String text = wa.sentTo(helper).get(0).text();
        assertThat(text).startsWith("Namaste Sunita ji 🙏").contains("Ganesh Sharma would like to send you reminders for Papa's medicines")
                .contains("Reply *1* to start").contains("STOP").doesNotContain("http");

        say(helper, "1");
        assertThat(receiver(id).get("consent_at")).isNotNull();
        assertThat(wa.sentTo(helper).get(1).text()).contains("Papa's medicine reminders");
    }

    @Test
    void meUsesTheCaregiversOwnNumber_evenWhenItIsAlsoTheirOwnProfilesNumber() throws Exception {
        String mine = Fixtures.randomPhone();
        Family self = fx.selfFamily(mine, "en", true);   // "Me" profile with the caregiver's own number
        fx.jdbc().update("update profiles set name = 'Ananya Rao', phone = ? where id = ?", mine, self.userId());

        MvcResult r = createViaApi(self.userId(), "{\"name\":\"Ramesh\",\"relation\":\"father\",\"callName\":\"Papa\",\"language\":\"hi\"," +
                "\"reminderTo\":\"caregiver\"}");

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> rec = receiver(idOf(r));
        assertThat(rec).containsEntry("reminder_to", "caregiver").containsEntry("whatsapp_phone", mine)
                .containsEntry("reminder_name", "Ananya Rao").containsEntry("reminder_user_id", self.userId());

        mvc.perform(post("/recipients/{id}/consent-request", idOf(r)).header("Authorization", bearer(self.userId())));
        String text = wa.sentTo(mine).get(0).text();
        assertThat(text).startsWith("नमस्ते Ananya 🙏").contains("आपने Papa की दवाओं के रिमाइंडर").contains("*1*");
    }

    @Test
    void theOwnNumberOnAParentIsStillRefusedWhenItIsMeantForTheParentThemself() throws Exception {
        String mine = Fixtures.randomPhone();
        Family self = fx.selfFamily(mine, "en", true);
        MvcResult r = createViaApi(self.userId(), "{\"name\":\"Ramesh\",\"whatsappPhone\":\"" + mine + "\",\"reminderTo\":\"person\"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void meWithoutAnyKnownNumber_orAHelperWithoutName_isRefusedClearly() throws Exception {
        UUID me = fx.user("Ganesh");
        assertThat(createViaApi(me, "{\"name\":\"Ramesh\",\"reminderTo\":\"caregiver\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(createViaApi(me, "{\"name\":\"Ramesh\",\"whatsappPhone\":\"" + fx.track(Fixtures.randomPhone()) +
                "\",\"reminderTo\":\"helper\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(createViaApi(me, "{\"name\":\"Ramesh\",\"reminderTo\":\"neighbour\"}").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void anOlderAppThatSendsNoChoiceKeepsTheReceiver_andASiblingSavingTheFormDoesNotTakeItOver() throws Exception {
        Family f = fx.family("en", true);
        String hers = fx.track(Fixtures.randomPhone());
        toCaregiver(f, f.userId(), hers, "Ananya");
        UUID sibling = fx.member(f, "Rohit");
        String base = "{\"name\":\"Rajesh\",\"relation\":\"father\",\"callName\":\"Papa\",\"language\":\"en\",\"whatsappPhone\":\"" + hers + "\"";

        // older app version: no reminderTo at all
        assertThat(mvc.perform(put("/api/v1/recipients/{id}", f.recipientId()).header("Authorization", bearer(sibling))
                .contentType(MediaType.APPLICATION_JSON).content(base + "}")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(receiver(f.recipientId())).containsEntry("reminder_to", "caregiver").containsEntry("reminder_user_id", f.userId());
        assertThat(receiver(f.recipientId()).get("consent_at")).isNotNull();

        // the sibling saves the form unchanged ("caregiver", same number): Ananya keeps them, consent stays
        mvc.perform(put("/api/v1/recipients/{id}", f.recipientId()).header("Authorization", bearer(sibling))
                .contentType(MediaType.APPLICATION_JSON).content(base + ",\"reminderTo\":\"caregiver\"}"));
        assertThat(receiver(f.recipientId())).containsEntry("reminder_user_id", f.userId());
        assertThat(receiver(f.recipientId()).get("consent_at")).isNotNull();
    }

    @Test
    void switchingWhoGetsTheRemindersAsksAgain_evenOnTheSameNumber() {
        Family f = fx.family("en", true);
        assertThat(receiver(f.recipientId()).get("consent_at")).isNotNull();
        fx.jdbc().update("update care_recipients set reminder_to = 'helper', reminder_name = 'Rajesh' where id = ?", f.recipientId());
        assertThat(receiver(f.recipientId()).get("consent_at")).isNull();
        // a new helper NAME on the same number and role keeps the agreement
        fx.consent(f.recipientId());
        fx.jdbc().update("update care_recipients set reminder_name = 'Rajesh ji' where id = ?", f.recipientId());
        assertThat(receiver(f.recipientId()).get("consent_at")).isNotNull();
    }

    @Test
    void whenTheCaregiverWhoGetsTheRemindersDeletesTheirAccount_theNumberStopsGettingThem() {
        Family f = fx.family("en", true);
        UUID sibling = fx.member(f, "Rohit");
        String his = fx.track(Fixtures.randomPhone());
        toCaregiver(f, sibling, his, "Rohit");

        fx.jdbc().update("delete from profiles where id = ?", sibling);

        Map<String, Object> rec = receiver(f.recipientId());
        assertThat(rec.get("reminder_to")).isEqualTo("person");
        assertThat(rec.get("whatsapp_phone")).isNull();
        assertThat(rec.get("consent_at")).isNull();
    }

    // ================================================================ what the receiver gets (scheduler)

    @Test
    void aHelperGetsAThirdPersonReminderThatNamesThePersonAndKeepsThePillLook() {
        Family f = fx.family("en", true);
        String helper = Fixtures.randomPhone();
        toHelper(f, helper, "Sunita", "maid");
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        fx.jdbc().update("update medicines set pill_colour = 'blue', pill_shape = 'round' where id = ?", m.medicineId());
        fx.dose(m.scheduleId(), ago(3), "pending", null, null);

        scheduler.tick(f.recipientId());

        assertThat(wa.sentTo(f.phone())).isEmpty();
        List<Sent> sent = wa.sentTo(helper);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).text()).startsWith("Namaste Sunita ji 🙏").contains("Time for Papa's medicine 💊 *Metformin*")
                .contains("🔵 Blue round tablet").endsWith("Reply *1* or 👍 once you have given it ✅");
    }

    @Test
    void thePersonThemselfStillGetsExactlyTodaysText() {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        fx.dose(m.scheduleId(), ago(3), "pending", null, null);

        scheduler.tick(f.recipientId());

        assertThat(wa.sentTo(f.phone()).get(0).text()).isEqualTo(MessageTexts.doseReminder("en", "Papa", "Metformin"));
    }

    @Test
    void noDailyCheckinWhenAHelperOrTheCaregiverGetsTheReminders() {
        for (String to : List.of("helper", "caregiver")) {
            Family f = fx.family("en", true);
            String other = Fixtures.randomPhone();
            if (to.equals("helper")) toHelper(f, other, "Sunita", "maid"); else toCaregiver(f, f.userId(), other, "Ananya");
            LocalTime tenAgo = ZonedDateTime.now(f.zone()).minusMinutes(10).toLocalTime().withSecond(0).withNano(0);
            fx.jdbc().update("update care_recipients set checkin_time = ? where id = ?", tenAgo, f.recipientId());

            Map<String, Integer> r = scheduler.tick(f.recipientId());

            assertThat(r.getOrDefault("checkins_sent", 0)).as(to).isZero();
            assertThat(wa.sentTo(other)).as(to).isEmpty();
            Integer rows = fx.jdbc().queryForObject("select count(*) from checkins where recipient_id = ?", Integer.class, f.recipientId());
            assertThat(rows).as(to).isZero();
        }
    }

    // ================================================================ replies from the receiver

    @Test
    void aHelpersGiverWordsConfirmTheDose_andTheThanksNamesThePerson() throws Exception {
        for (String word : List.of("diya", "de diya", "दे दिया", "दिया", "given", "ho gaya", "हो गया", "1", "👍")) {
            Family f = fx.family("en", true);
            String helper = Fixtures.randomPhone();
            toHelper(f, helper, "Sunita", "maid");
            Med m = fx.medicine(f, "Metformin", "1 tablet", 10, LocalTime.of(3, 0));
            UUID dose = sentDose(m, 5);
            wa.reset();

            say(helper, word);

            assertThat(fx.doseStatus(dose)).as(word).isEqualTo("taken");
            assertThat(fx.stock(m.medicineId())).as(word).isEqualTo(9);
            assertThat(wa.sentTo(helper).get(0).text()).as(word).contains("Noted for Papa: Metformin");
        }
    }

    @Test
    void notYetFromAHelperIsAnsweredInTheThirdPerson() throws Exception {
        Family f = fx.family("hi", true);
        String helper = Fixtures.randomPhone();
        toHelper(f, helper, "Sunita", "maid");
        Med m = fx.medicine(f, "Metformin", "1 tablet", 10, LocalTime.of(3, 0));
        UUID dose = sentDose(m, 5);

        say(helper, "nahi diya");

        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
        assertThat(wa.sentTo(helper).get(0).text()).contains("पापा को दवा देने के बाद 1");
    }

    @Test
    void oneHelperForPapaAndMummy_eachOneAnswersTheLatestReminder_andTheReplyNamesWhose() throws Exception {
        String maid = Fixtures.randomPhone();
        Family papa = fx.family("en", true);
        Family mummy = fx.addRecipient(papa, "Kamla", Fixtures.randomPhone(), "en", true);
        callName(mummy, "Mummy");
        toHelper(papa, maid, "Sunita", "maid");
        toHelper(mummy, maid, "Sunita", "maid");
        Med pm = fx.medicine(papa, "Metformin", "1 tablet", 10, LocalTime.of(3, 0));
        Med mm = fx.medicine(mummy, "Thyronorm", "1 tablet", 10, LocalTime.of(3, 0));
        UUID papaDose = sentDose(pm, 8);
        UUID mummyDose = sentDose(mm, 3);   // the newest

        say(maid, "diya");
        assertThat(fx.doseStatus(mummyDose)).isEqualTo("taken");
        assertThat(fx.doseStatus(papaDose)).isEqualTo("sent");
        assertThat(wa.sentTo(maid).get(0).text()).contains("Noted for Mummy: Thyronorm").contains("Reply 1 again for the next medicine");

        say(maid, "1");
        assertThat(fx.doseStatus(papaDose)).isEqualTo("taken");
        assertThat(wa.sentTo(maid).get(1).text()).contains("Noted for Papa: Metformin");
        assertThat(fx.stock(pm.medicineId())).isEqualTo(9);
        assertThat(fx.stock(mm.medicineId())).isEqualTo(9);
    }

    @Test
    void theCaregiversOwnMedicineAndPapasOnOneNumber_theLatestReminderIsAnswered() throws Exception {
        String mine = Fixtures.randomPhone();
        Family self = fx.selfFamily(mine, "en", true);
        Family papa = fx.family("en", true);
        // same caregiver for both: move Papa's circle to the self profile's owner
        fx.jdbc().update("update care_circles set created_by = ? where id = ?", self.userId(), papa.circleId());
        fx.jdbc().update("insert into care_circle_members (circle_id, user_id, role) values (?, ?, 'owner') on conflict do nothing",
                papa.circleId(), self.userId());
        toCaregiver(papa, self.userId(), mine, "Ananya");
        Med own = fx.medicine(self, "Vitamin D", "1 tablet", 10, LocalTime.of(3, 0));
        Med his = fx.medicine(papa, "Metformin", "1 tablet", 10, LocalTime.of(3, 0));
        UUID ownDose = sentDose(own, 10);
        UUID hisDose = sentDose(his, 2);

        say(mine, "1");
        assertThat(fx.doseStatus(hisDose)).isEqualTo("taken");
        assertThat(fx.doseStatus(ownDose)).isEqualTo("sent");
        assertThat(wa.sentTo(mine).get(0).text()).contains("Noted for Papa: Metformin");

        say(mine, "1");
        assertThat(fx.doseStatus(ownDose)).isEqualTo("taken");
        assertThat(wa.sentTo(mine).get(1).text()).contains("Noted: Vitamin D");
    }

    // ================================================================ the family's alerts

    @Test
    void aMissedDoseAlertNamesTheHelperWhoWasReminded() {
        Family f = fx.family("en", true);
        String alerts = Fixtures.randomPhone();
        fx.alertsOn(f.userId(), alerts);
        toHelper(f, Fixtures.randomPhone(), "Sunita", "maid");
        Med m = fx.medicine(f, "Metformin", "1 tablet", 10, Fixtures.localTime(f, Instant.now().plus(6, ChronoUnit.HOURS)));
        fx.dose(m.scheduleId(), ago(90), "sent", ago(89), Fixtures.messageId());

        scheduler.tick(f.recipientId());

        List<Sent> got = alertsTo(alerts);
        assertThat(got).hasSize(1);
        assertThat(got.get(0).text()).contains("Papa's medicine *Metformin*").contains("was not confirmed")
                .contains("The reminder went to Sunita (maid).").doesNotContain("Papa has not confirmed");
    }

    @Test
    void theCaregiversNumberGetsRemindersAndAlerts_theAlertSaysYou_aSiblingsSaysTheirName() throws Exception {
        Family f = fx.family("en", true);
        String hers = Fixtures.randomPhone();
        fx.alertsOn(f.userId(), hers);
        toCaregiver(f, f.userId(), hers, "Ananya Rao");
        UUID sibling = fx.member(f, "Rohit");
        String his = Fixtures.randomPhone();
        fx.alertsOn(sibling, his);
        Med m = fx.medicine(f, "Metformin", "1 tablet", 10, Fixtures.localTime(f, Instant.now().plus(6, ChronoUnit.HOURS)));

        // a "1" on the shared number answers the dose reminder (alerts were confirmed long ago, no question is open)
        UUID answered = sentDose(m, 5);
        say(hers, "1");
        assertThat(fx.doseStatus(answered)).isEqualTo("taken");
        assertThat(fx.alerts(f.userId()).get("alert_consent_at")).isNotNull();

        fx.dose(m.scheduleId(), ago(90), "sent", ago(89), Fixtures.messageId());
        scheduler.tick(f.recipientId());

        assertThat(alertsTo(hers)).hasSize(1);
        assertThat(alertsTo(hers).get(0).text()).contains("The reminder went to you.");
        assertThat(alertsTo(his)).hasSize(1);
        assertThat(alertsTo(his).get(0).text()).contains("The reminder went to Ananya.");
    }

    @Test
    void aHelpersStopIsReportedAsTheHelpersNotThePersons() throws Exception {
        Family f = fx.family("hi", true);
        fx.jdbc().update("update profiles set language = 'hi' where id = ?", f.userId());
        String alerts = Fixtures.randomPhone();
        fx.alertsOn(f.userId(), alerts);
        String helper = Fixtures.randomPhone();
        toHelper(f, helper, "Sunita", "maid");

        say(helper, "STOP");
        scheduler.tick(f.recipientId());

        assertThat(fx.phoneOptedOut(helper)).isTrue();
        List<Sent> got = alertsTo(alerts);
        assertThat(got).hasSize(1);
        assertThat(got.get(0).text()).contains("पापा के रिमाइंडर पाने वाले Sunita (maid) ने").contains("पापा के रिमाइंडर रुके हुए हैं");
    }
}
