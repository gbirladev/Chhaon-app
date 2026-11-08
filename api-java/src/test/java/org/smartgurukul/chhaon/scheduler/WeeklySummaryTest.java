package org.smartgurukul.chhaon.scheduler;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.smartgurukul.chhaon.support.RecordingWhatsAppSender.Sent;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Weekly summary to the family (feature F3, migration 20261105000048): Sunday ~9:00 on the caregiver's clock, only with alert
 * consent, one message per caregiver per week, counts over the person's last 7 days, who gave the doses, refills, next visit,
 * pregnancy week, a child's next vaccine, retries, and the in-app card.
 */
class WeeklySummaryTest extends DbTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired WeeklySummary weekly;
    @Autowired JwtService jwt;

    /** The most recent Sunday (or today) in that zone. */
    private static LocalDate sunday(ZoneId zone) {
        return LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
    }

    private static Instant at(LocalDate day, int hour, int minute, ZoneId zone) {
        return day.atTime(hour, minute).atZone(zone).toInstant();
    }

    /** Sunday 9:30 India time. */
    private static Instant sundayMorning() {
        return at(sunday(IST), 9, 30, IST);
    }

    private String alertsOn(Family f) {
        String phone = fx.track(Fixtures.randomPhone());
        fx.alertsOn(f.userId(), phone);
        return phone;
    }

    /**
     * Metformin for the week before {@link #sundayMorning}: 5 taken + 1 taken late (Mon-Sat mornings), missed Tue night and
     * Thu night, plus rows that must NOT count: a pending, a sent and a skipped dose in the week, and a taken dose 8 days back.
     */
    private Med metforminWeek(Family f) {
        Med m = fx.medicine(f, "Metformin", "500 mg", 6, LocalTime.of(3, 0));
        fx.jdbc().update("update medicines set refill_threshold = 7 where id = ?", m.medicineId());
        LocalDate sun = sunday(IST);
        for (int i = 1; i <= 6; i++) {   // Mon..Sat 8:00
            fx.dose(m.scheduleId(), at(sun.minusDays(7 - i), 8, 0, IST), i == 6 ? "taken_late" : "taken", null, null);
        }
        fx.dose(m.scheduleId(), at(sun.minusDays(5), 21, 0, IST), "missed", null, null);   // Tue night
        fx.dose(m.scheduleId(), at(sun.minusDays(3), 21, 0, IST), "missed", null, null);   // Thu night
        fx.dose(m.scheduleId(), at(sun.minusDays(2), 13, 0, IST), "pending", null, null);
        fx.dose(m.scheduleId(), at(sun.minusDays(2), 14, 0, IST), "sent", null, null);
        fx.dose(m.scheduleId(), at(sun.minusDays(2), 15, 0, IST), "skipped", null, null);
        fx.dose(m.scheduleId(), at(sun.minusDays(8), 8, 0, IST), "taken", null, null);   // the week before: not counted
        return m;
    }

    private void visit(Family f, Instant when) {
        fx.jdbc().update("insert into appointments (recipient_id, doctor_name, datetime) values (?, 'Dr Mehta', ?)",
                f.recipientId(), Timestamp.from(when));
    }

    private Map<String, Object> sendRow(UUID userId) {
        List<Map<String, Object>> rows = fx.jdbc().queryForList(
                "select status, attempts, people, sent_at from weekly_summary_sends where user_id = ?", userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    void sundayMorning_oneMessageWithTheWeek_andNeverASecondOne() {
        Family f = fx.family("en", true);
        fx.jdbc().update("update care_recipients set call_name = 'Mummy', reminder_to = 'helper', reminder_name = 'Sunita' where id = ?",
                f.recipientId());
        fx.consent(f.recipientId());
        String phone = alertsOn(f);
        metforminWeek(f);
        visit(f, at(sunday(IST).plusDays(2), 11, 0, IST));

        Map<String, Integer> c = weekly.run(f.recipientId(), sundayMorning());

        assertThat(c.get("weekly_sent")).isEqualTo(1);
        List<Sent> sent = wa.sentTo(phone);
        assertThat(sent).hasSize(1);
        String text = sent.get(0).text();
        assertThat(text).startsWith("Chhaon weekly 🗓️ · Mummy\n")
                .contains("Medicines confirmed: 6 of 8")
                .contains("Missed: Metformin Tue night, Thu night")
                .contains("Given by: Sunita 6")
                .contains("Refill soon: Metformin (6 left)")
                .contains("Next visit: Dr Mehta, Tue ")
                .contains("11:00 AM")
                .contains("Full details: chhaon.site/app")
                .contains("Reply STOP")
                .doesNotContain("http").doesNotContain(f.phone()).doesNotContain(phone);
        assertThat(sendRow(f.userId())).containsEntry("status", "sent");

        // The same morning again, and later that Sunday: nothing more.
        weekly.run(f.recipientId(), sundayMorning());
        weekly.run(f.recipientId(), sundayMorning().plusSeconds(3600));
        assertThat(wa.sentTo(phone)).hasSize(1);
    }

    @Test
    void onlyOnSundayFromNineOnTheCaregiversOwnClock() {
        Family f = fx.family("en", true);
        String phone = alertsOn(f);
        metforminWeek(f);
        LocalDate sun = sunday(IST);

        weekly.run(f.recipientId(), at(sun.minusDays(1), 9, 30, IST));   // Saturday
        weekly.run(f.recipientId(), at(sun, 8, 30, IST));                // too early
        weekly.run(f.recipientId(), at(sun, 13, 30, IST));               // too late
        assertThat(wa.sentTo(phone)).isEmpty();
        assertThat(sendRow(f.userId())).isNull();

        // A caregiver in New York: Sunday 9:30 in India is still Saturday night for them; Sunday 9:30 their time it goes.
        ZoneId ny = ZoneId.of("America/New_York");
        fx.jdbc().update("update profiles set timezone = ? where id = ?", ny.getId(), f.userId());
        weekly.run(f.recipientId(), sundayMorning());
        assertThat(wa.sentTo(phone)).isEmpty();
        weekly.run(f.recipientId(), at(sunday(ny), 9, 30, ny));
        assertThat(wa.sentTo(phone)).hasSize(1);
    }

    @Test
    void neverWithoutAlertConsent_norToAStoppedNumber_norToThePersonThemself() {
        Family noConsent = fx.family("en", true);
        metforminWeek(noConsent);
        weekly.run(noConsent.recipientId(), sundayMorning());

        Family stopped = fx.family("en", true);
        String stoppedPhone = alertsOn(stopped);
        fx.optOutPhone(stoppedPhone);
        metforminWeek(stopped);
        weekly.run(stopped.recipientId(), sundayMorning());

        // The caregiver's alert number is the person's own reminder number (the person is in the family circle): not about them.
        Family self = fx.family("en", true);
        fx.alertsOn(self.userId(), self.phone());
        metforminWeek(self);
        weekly.run(self.recipientId(), sundayMorning());

        assertThat(wa.sent()).isEmpty();
        assertThat(sendRow(self.userId())).containsEntry("status", "skipped");
    }

    @Test
    void aQuietWeekSendsNothing_andIsNotTriedAgain() {
        Family f = fx.family("en", true);
        String phone = alertsOn(f);
        fx.medicine(f, "Metformin", "500 mg", 30, LocalTime.of(3, 0));   // a medicine, but no counted dose that week

        weekly.run(f.recipientId(), sundayMorning());
        weekly.run(f.recipientId(), sundayMorning().plusSeconds(600));

        assertThat(wa.sentTo(phone)).isEmpty();
        assertThat(sendRow(f.userId())).containsEntry("status", "skipped");
    }

    @Test
    void checkInsAloneAreEnoughForASummary() {
        Family f = fx.family("en", true);
        String phone = alertsOn(f);
        LocalDate sun = sunday(IST);
        for (int i = 1; i <= 7; i++) fx.checkin(f.recipientId(), sun.minusDays(i), i == 3 ? "missed" : "replied", null);
        fx.checkin(f.recipientId(), sun, "pending", null);   // today: not in the week

        weekly.run(f.recipientId(), sundayMorning());

        assertThat(wa.sentTo(phone)).hasSize(1);
        assertThat(wa.sentTo(phone).get(0).text()).contains("Check-ins answered: 6 of 7").doesNotContain("Medicines confirmed");
    }

    @Test
    void aFailedSendIsTriedAgain_atMostThreeTimes() {
        Family f = fx.family("en", true);
        String phone = alertsOn(f);
        metforminWeek(f);
        wa.failSends(true);

        for (int i = 0; i < 4; i++) weekly.run(f.recipientId(), sundayMorning().plusSeconds(300L * i));

        assertThat(sendRow(f.userId())).containsEntry("status", "failed");
        assertThat(((Number) sendRow(f.userId()).get("attempts")).intValue()).isEqualTo(3);
        wa.failSends(false);
        weekly.run(f.recipientId(), sundayMorning().plusSeconds(1500));
        assertThat(wa.sentTo(phone)).isEmpty();   // out of tries: never a late one
    }

    @Test
    void oneFailureThenSuccess_sendsExactlyOnce() {
        Family f = fx.family("en", true);
        String phone = alertsOn(f);
        metforminWeek(f);
        wa.failSends(true);
        weekly.run(f.recipientId(), sundayMorning());
        wa.failSends(false);
        weekly.run(f.recipientId(), sundayMorning().plusSeconds(300));
        weekly.run(f.recipientId(), sundayMorning().plusSeconds(600));

        assertThat(wa.sentTo(phone)).hasSize(1);
        assertThat(sendRow(f.userId())).containsEntry("status", "sent");
    }

    @Test
    void hindiForAHindiSpeakingCaregiver() {
        Family f = fx.family("hi", true);
        fx.jdbc().update("update profiles set language = 'hi' where id = ?", f.userId());
        String phone = alertsOn(f);
        metforminWeek(f);

        weekly.run(f.recipientId(), sundayMorning());

        String text = wa.sentTo(phone).get(0).text();
        assertThat(text).startsWith("छाँव साप्ताहिक 🗓️ · पापा").contains("दवा की पुष्टि: 8 में से 6")
                .contains("छूटी: Metformin मंगल रात, गुरु रात").contains("जल्द मँगाएँ: Metformin (6 बची)")
                .contains("पूरी जानकारी: chhaon.site/app").doesNotContain("किसने दी");   // Papa confirms himself: no "given by"
    }

    @Test
    void severalPeopleInOneMessage_withPregnancyWeekAndAChildsNextVaccine() {
        Family papa = fx.family("en", true);
        String phone = alertsOn(papa);
        metforminWeek(papa);
        Family shivani = fx.addRecipient(papa, "Shivani", Fixtures.randomPhone(), "en", true);
        LocalDate sun = sunday(IST);
        fx.jdbc().update("update care_recipients set call_name = 'Shivani', care_type = 'pregnant', due_date = ? where id = ?",
                sun.plusDays(280 - 24 * 7 - 3), shivani.recipientId());
        Med iron = fx.medicine(shivani, "Iron", "1 tablet", null, LocalTime.of(3, 0));
        fx.dose(iron.scheduleId(), at(sun.minusDays(2), 9, 0, IST), "taken", null, null);
        Family aarav = fx.addRecipient(papa, "Aarav", Fixtures.randomPhone(), "en", true);
        fx.jdbc().update("update care_recipients set call_name = 'Aarav', care_type = 'child', guardian_confirmed_at = now() where id = ?",
                aarav.recipientId());
        Med syrup = fx.medicine(aarav, "Syrup", "5 ml", null, LocalTime.of(3, 0));
        fx.dose(syrup.scheduleId(), at(sun.minusDays(4), 9, 0, IST), "missed", null, null);
        fx.jdbc().update("insert into child_vaccines (recipient_id, schedule_key, vaccine_code, dose_label, schedule_edition, " +
                "suggested_due_date, due_date) values (?, 'mmr-1', 'mmr', '1', 'IAP-ACVIP 2023', ?, ?)",
                aarav.recipientId(), sun.plusDays(9), sun.plusDays(9));

        // No scope: every family of this caregiver (the other test caregivers' rows are not in the Sunday window anyway).
        weekly.run(null, sundayMorning());

        List<Sent> sent = wa.sentTo(phone);
        assertThat(sent).hasSize(1);
        String text = sent.get(0).text();
        assertThat(text).startsWith("Chhaon weekly 🗓️\n").contains("*Papa*").contains("*Shivani* · Week 24")
                .contains("*Aarav*").contains("Missed: Syrup Wed morning").contains("Next vaccine: ").contains("(dose 1)");
        assertThat(sendRow(papa.userId())).containsEntry("status", "sent");
        assertThat(((Number) sendRow(papa.userId()).get("people")).intValue()).isEqualTo(3);
    }

    @Test
    void givenBy_you_whenTheCaregiverGetsTheReminders_andASiblingByFirstName() {
        Family f = fx.family("en", true);
        UUID sister = fx.member(f, "Priya Sharma");
        String phone = alertsOn(f);
        String sisterPhone = fx.track(Fixtures.randomPhone());
        fx.alertsOn(sister, sisterPhone);
        fx.jdbc().update("update care_recipients set reminder_to = 'caregiver', reminder_user_id = ?, reminder_name = 'Ananya' where id = ?",
                f.userId(), f.recipientId());
        metforminWeek(f);

        weekly.run(f.recipientId(), sundayMorning());

        assertThat(wa.sentTo(phone).get(0).text()).contains("Given by: you 6");
        assertThat(wa.sentTo(sisterPhone).get(0).text()).contains("Given by: Ananya 6");
    }

    @Test
    void theInAppCardShowsTheSameNumbers_toTheFamilyOnly() throws Exception {
        Family f = fx.family("en", true);
        metforminWeek(f);
        UUID stranger = fx.user("Stranger");

        // The card counts the 7 full days before today; the test week ends on the most recent Sunday, so only check the shape
        // and that numbers come from counted statuses.
        mvc.perform(get("/api/v1/recipients/{id}/weekly-summary", f.recipientId())
                        .header("Authorization", "Bearer " + jwt.issueToken(f.userId(), false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientId", is(f.recipientId().toString())))
                .andExpect(jsonPath("$.name", is("Papa")));
        mvc.perform(get("/api/v1/recipients/{id}/weekly-summary", f.recipientId())
                        .header("Authorization", "Bearer " + jwt.issueToken(stranger, false)))
                .andExpect(status().isNotFound());

        var w = weekly.weekFor(f.recipientId(), f.userId(), sundayMorning());
        assertThat(w.confirmed()).isEqualTo(6);
        assertThat(w.total()).isEqualTo(8);
        assertThat(w.missed()).hasSize(2);
        assertThat(w.refills()).hasSize(1);
    }
}
