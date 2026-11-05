package org.smartgurukul.chhaon.scheduler;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests: they pin down what the 6-step {@link SchedulerService#tick} does TODAY, step
 * by step, so the reply pipeline can be built next to it without changing it by accident. Times are
 * relative to now; every tick is scoped to one test family.
 */
class SchedulerTickCharacterizationTest extends DbTest {

    @Autowired
    SchedulerService scheduler;

    private static Instant minutesAgo(int m) {
        return Instant.now().minus(m, ChronoUnit.MINUTES);
    }

    private static Instant minutesAhead(int m) {
        return Instant.now().plus(m, ChronoUnit.MINUTES);
    }

    private Map<String, Integer> tick(Family f) {
        return scheduler.tick(f.recipientId());
    }

    // ---------------------------------------------------------------- 1. generate

    @Test
    void step1_generatesTheDoseForASchedulePointInTheNearFuture() {
        ZoneId zone = Fixtures.safeZone();
        Family f = fx.family(Fixtures.randomPhone(), "en", true, zone);
        fx.medicine(f, "Metformin", "500 mg", 20, Fixtures.localTime(f, minutesAhead(30)));

        Map<String, Integer> first = tick(f);
        assertThat(first.get("generated")).isEqualTo(1);
        assertThat(first).doesNotContainKey("step_errors");
        // A dose in the future is only created, never sent.
        assertThat(wa.sent()).isEmpty();

        // Idempotent: a second tick creates nothing new.
        assertThat(tick(f).get("generated")).isEqualTo(0);
    }

    // ---------------------------------------------------------------- 2. send due doses

    @Test
    void step2_sendsADueDoseOnceAndRecordsTheProviderMessageId() {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), minutesAgo(5), "pending", null, null);

        Map<String, Integer> r = tick(f);

        assertThat(r.get("doses_sent")).isEqualTo(1);
        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
        assertThat(fx.dose(dose).get("whatsapp_message_id")).isEqualTo("test-out-1");
        assertThat(wa.sent()).hasSize(1);
        assertThat(wa.sent().get(0).to()).isEqualTo(f.phone());
        assertThat(wa.sent().get(0).text()).contains("Namaste").contains("Metformin").endsWith("Taken it? Just reply *1*"); // text changed in part 3 (typed replies) and again 2026-11-05

        // A second tick does not send it again (the claim is a conditional update).
        assertThat(tick(f).get("doses_sent")).isEqualTo(0);
        assertThat(wa.sent()).hasSize(1);
    }

    @Test
    void step2_usesTheParentsLanguageAndCallName() {
        Family f = fx.family("hi", true);
        Med m = fx.medicine(f, "Amlodipine", "5 mg", 20, LocalTime.of(3, 0));
        fx.dose(m.scheduleId(), minutesAgo(2), "pending", null, null);

        tick(f);

        assertThat(wa.sent()).hasSize(1);
        assertThat(wa.sent().get(0).text()).contains("पापा").contains("Amlodipine");
    }

    @Test
    void step2_neverMessagesAParentWhoHasNotConsented_thePendingDoseBecomesSkipped() {
        Family f = fx.family("en", false);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), minutesAgo(5), "pending", null, null);

        Map<String, Integer> r = tick(f);

        assertThat(r.get("doses_skipped")).isEqualTo(1);
        assertThat(fx.doseStatus(dose)).isEqualTo("skipped");
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void step2_neverMessagesANumberThatSaidStop_evenIfTheParentRecordLooksConsented() {
        Family f = fx.family("en", true);
        fx.optOutPhone(f.phone());
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), minutesAgo(5), "pending", null, null);

        tick(f);

        assertThat(fx.doseStatus(dose)).isEqualTo("skipped");
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void step2_aFailedSendHandsTheDoseBackToPendingForARetry() {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), minutesAgo(5), "pending", null, null);
        wa.failSends(true);

        Map<String, Integer> r = tick(f);

        assertThat(r.get("send_errors")).isEqualTo(1);
        assertThat(fx.doseStatus(dose)).isEqualTo("pending");
        assertThat(fx.dose(dose).get("sent_at")).isNull();

        wa.failSends(false);
        assertThat(tick(f).get("doses_sent")).isEqualTo(1);
        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
    }

    // ---------------------------------------------------------------- 3. unsent doses

    @Test
    void step3_aDoseThatWasNeverSentAndIsPastTheWindowBecomesSkippedAndTheFamilyIsTold() {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), minutesAgo(90), "pending", null, null);

        Map<String, Integer> r = tick(f);

        assertThat(r.get("reminders_not_sent")).isEqualTo(1);
        assertThat(fx.doseStatus(dose)).isEqualTo("skipped");
        assertThat(fx.notifications(f.userId(), "reminder_not_sent")).isEqualTo(1);
        assertThat(wa.sent()).isEmpty();
    }

    // ---------------------------------------------------------------- 4. missed doses

    @Test
    void step4_aDoseSentOverAnHourAgoWithoutAnswerBecomesMissedAndTheFamilyIsTold() {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), minutesAgo(80), "sent", minutesAgo(75), "test-out-x");

        Map<String, Integer> r = tick(f);

        assertThat(r.get("doses_missed")).isEqualTo(1);
        assertThat(fx.doseStatus(dose)).isEqualTo("missed");
        assertThat(fx.notifications(f.userId(), "dose_missed")).isEqualTo(1);
    }

    @Test
    void step4_aDoseSentRecentlyStaysSent() {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), minutesAgo(20), "sent", minutesAgo(15), "test-out-x");

        assertThat(tick(f).get("doses_missed")).isEqualTo(0);
        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
    }

    @Test
    void step4_aParentWhoOptedOutDoesNotTriggerMissedAlerts() {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), minutesAgo(80), "sent", minutesAgo(75), "test-out-x");
        fx.jdbc().update("update care_recipients set opted_out_at = now() where id = ?", f.recipientId());

        tick(f);

        assertThat(fx.doseStatus(dose)).isEqualTo("missed");
        assertThat(fx.notifications(f.userId(), "dose_missed")).isEqualTo(0);
    }

    // ---------------------------------------------------------------- 5. check-ins sent

    @Test
    void step5_sendsTheDailyCheckinOnceInsideTheStartWindow() {
        ZoneId zone = Fixtures.safeZone();
        Family f = fx.family(Fixtures.randomPhone(), "en", true, zone);
        LocalTime tenMinutesAgo = ZonedDateTime.now(zone).minusMinutes(10).toLocalTime().withSecond(0).withNano(0);
        fx.jdbc().update("update care_recipients set checkin_time = ? where id = ?", tenMinutesAgo, f.recipientId());

        Map<String, Integer> r = tick(f);

        assertThat(r.get("checkins_sent")).isEqualTo(1);
        Map<String, Object> row = fx.jdbc().queryForMap(
                "select status::text status, sent_at from checkins where recipient_id = ? and date = ?",
                f.recipientId(), LocalDate.now(zone));
        assertThat(row.get("status")).isEqualTo("sent");
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(wa.sent()).hasSize(1);
        assertThat(wa.sent().get(0).text()).startsWith("Good morning").contains("Papa").endsWith("Reply *2* if you want a call 📞"); // text changed in part 3

        // Once per day.
        assertThat(tick(f).get("checkins_sent")).isEqualTo(0);
        assertThat(wa.sent()).hasSize(1);
    }

    @Test
    void step5_doesNotSendBeforeTheCheckinTimeOrToANonConsentedParent() {
        ZoneId zone = Fixtures.safeZone();
        Family early = fx.family(Fixtures.randomPhone(), "en", true, zone);
        LocalTime inThirty = ZonedDateTime.now(zone).plusMinutes(30).toLocalTime().withSecond(0).withNano(0);
        fx.jdbc().update("update care_recipients set checkin_time = ? where id = ?", inThirty, early.recipientId());
        assertThat(tick(early).get("checkins_sent")).isEqualTo(0);

        Family noConsent = fx.family(Fixtures.randomPhone(), "en", false, zone);
        LocalTime tenAgo = ZonedDateTime.now(zone).minusMinutes(10).toLocalTime().withSecond(0).withNano(0);
        fx.jdbc().update("update care_recipients set checkin_time = ? where id = ?", tenAgo, noConsent.recipientId());
        assertThat(tick(noConsent).get("checkins_sent")).isEqualTo(0);
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void step5_neverSendsACheckinToANumberThatSaidStop() {
        // Found while writing the reply tests: the check-in step used to ignore phone_opt_outs (only doses checked it).
        ZoneId zone = Fixtures.safeZone();
        Family f = fx.family(Fixtures.randomPhone(), "en", true, zone);
        fx.optOutPhone(f.phone());
        LocalTime tenAgo = ZonedDateTime.now(zone).minusMinutes(10).toLocalTime().withSecond(0).withNano(0);
        fx.jdbc().update("update care_recipients set checkin_time = ? where id = ?", tenAgo, f.recipientId());

        assertThat(tick(f).get("checkins_sent")).isEqualTo(0);
        assertThat(wa.sent()).isEmpty();
    }

    // ---------------------------------------------------------------- 6. check-ins missed

    @Test
    void step6_aCheckinSentOverThreeHoursAgoWithoutAReplyBecomesMissedAndTheFamilyIsTold() {
        Family f = fx.family("en", true);
        UUID checkin = fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", minutesAgo(200));

        Map<String, Integer> r = tick(f);

        assertThat(r.get("checkins_missed")).isEqualTo(1);
        assertThat(fx.checkinStatus(checkin)).isEqualTo("missed");
        assertThat(fx.notifications(f.userId(), "checkin_missed")).isEqualTo(1);
    }

    @Test
    void step6_aCheckinSentAWhileAgoStaysSent() {
        Family f = fx.family("en", true);
        UUID checkin = fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", minutesAgo(30));

        assertThat(tick(f).get("checkins_missed")).isEqualTo(0);
        assertThat(fx.checkinStatus(checkin)).isEqualTo("sent");
    }

    // ---------------------------------------------------------------- scope and counters

    @Test
    void aScopedTickNeverTouchesAnotherFamily() {
        Family mine = fx.family("en", true);
        Family other = fx.family("en", true);
        Med m = fx.medicine(other, "Other", "1 tablet", 5, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), minutesAgo(5), "pending", null, null);

        tick(mine);

        assertThat(fx.doseStatus(dose)).isEqualTo("pending");
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void theSchedulerIsOffByDefault() throws Exception {
        // The shipped properties never switch it on: the code default (false) applies unless the environment says otherwise.
        for (String file : new String[] {"application.properties", "application-prod.properties"}) {
            String content = new String(new org.springframework.core.io.ClassPathResource(file)
                    .getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(content).doesNotContain("chhaon.scheduler.enabled");
        }
        // scheduledTick() is a no-op while disabled: nothing is sent and nothing changes.
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), minutesAgo(5), "pending", null, null);
        scheduler.scheduledTick();
        assertThat(fx.doseStatus(dose)).isEqualTo("pending");
        assertThat(wa.sent()).isEmpty();
    }
}
