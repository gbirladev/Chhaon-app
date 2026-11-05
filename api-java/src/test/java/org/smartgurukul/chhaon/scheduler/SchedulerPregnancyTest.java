package org.smartgurukul.chhaon.scheduler;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The reminder and the check-in for a pregnancy (migration 0043) use the pregnancy wording; older parents keep theirs. */
class SchedulerPregnancyTest extends DbTest {

    @Autowired
    JdbcTemplate jdbc;

    private SchedulerService scheduler(Family f) {
        return new SchedulerService(jdbc, wa, true, 60, 180, 120, 24, f.recipientId().toString());
    }

    private void pregnant(Family f, String callName, LocalDate due) {
        jdbc.update("update public.care_recipients set care_type = 'pregnant', due_date = ?, call_name = ?, name = 'Shivani Sharma' where id = ?",
                due, callName, f.recipientId());
    }

    @Test
    void aPregnancyReminderSaysTheWeekAndKeepsTheReplyLine() {
        Family f = fx.family("en", true);
        LocalDate today = ZonedDateTime.now(f.zone()).toLocalDate();
        pregnant(f, null, today.plusDays(112));   // 24 weeks today; no call name: her first name is used
        Med m = fx.medicine(f, "Iron + folic acid", "1 tablet", 20, LocalTime.of(3, 0));
        fx.dose(m.scheduleId(), Instant.now().minus(5, ChronoUnit.MINUTES), "pending", null, null);

        scheduler(f).scheduledTick();

        assertThat(wa.sentTo(f.phone())).hasSize(1);
        String text = wa.sentTo(f.phone()).get(0).text();
        assertThat(text).matches("(?s)Good (morning|afternoon|evening) Shivani .*")
                .contains("\nWeek 24 starts today 💛\nMedicine time: *Iron + folic acid*\n1 tablet")
                .endsWith("Taken it? Just reply *1*")
                .doesNotContain("Papa").doesNotContain("Namaste");
    }

    @Test
    void theWeekIsSaidOnlyInTheFirstReminderOfTheDayANewWeekStarts() {
        Family f = fx.family("en", true);
        LocalDate today = ZonedDateTime.now(f.zone()).toLocalDate();
        pregnant(f, null, today.plusDays(112));   // week 24 starts today
        Med iron = fx.medicine(f, "Iron + folic acid", "1 tablet", 20, LocalTime.of(3, 0));
        Med calcium = fx.medicine(f, "Calcium", "1 tablet", 20, LocalTime.of(3, 0));
        fx.dose(iron.scheduleId(), Instant.now().minus(5, ChronoUnit.MINUTES), "pending", null, null);
        fx.dose(calcium.scheduleId(), Instant.now().minus(5, ChronoUnit.MINUTES), "pending", null, null);

        scheduler(f).scheduledTick();

        assertThat(wa.sentTo(f.phone())).hasSize(2);
        assertThat(wa.sentTo(f.phone()).stream().filter(s -> s.text().contains("Week 24 starts today")).count()).isEqualTo(1);

        // one day into the week: no week line at all
        Family g = fx.family("en", true);
        pregnant(g, null, ZonedDateTime.now(g.zone()).toLocalDate().plusDays(111));
        Med m = fx.medicine(g, "Iron + folic acid", "1 tablet", 20, LocalTime.of(3, 0));
        fx.dose(m.scheduleId(), Instant.now().minus(5, ChronoUnit.MINUTES), "pending", null, null);
        scheduler(g).scheduledTick();
        assertThat(wa.sentTo(g.phone()).get(0).text()).doesNotContain("Week").contains("\n1 tablet\n");
    }

    @Test
    void aPregnancyCheckinIsTheGentleOneInHindi() {
        Family f = fx.family("hi", true);
        LocalDate today = ZonedDateTime.now(f.zone()).toLocalDate();
        pregnant(f, "शिवानी", today.plusDays(70));   // 30 weeks
        jdbc.update("update care_recipients set checkin_time = ? where id = ?",
                ZonedDateTime.now(f.zone()).minusMinutes(1).toLocalTime().withSecond(0).withNano(0), f.recipientId());

        scheduler(f).scheduledTick();

        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(wa.sentTo(f.phone()).get(0).text())
                .startsWith("सुप्रभात शिवानी ☀️\nआज आप कैसा महसूस कर रही हैं?")   // R2: the check-in never says the week
                .endsWith("फ़ोन चाहिए तो *2* लिखें 📞");
    }

    @Test
    void anOlderParentStillGetsNamastePapa() {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        fx.dose(m.scheduleId(), Instant.now().minus(5, ChronoUnit.MINUTES), "pending", null, null);

        scheduler(f).scheduledTick();

        assertThat(wa.sentTo(f.phone()).get(0).text())
                .isEqualTo("Namaste Papa 🙏\nMedicine time: *Metformin*\n500 mg\n\nTaken it? Just reply *1*");
    }
}
