package org.smartgurukul.chhaon.scheduler;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** generateNow: a parent's upcoming doses are made at once after a medicine change, nothing is sent, and only when a tick would handle them. */
class SchedulerGenerateNowTest extends DbTest {

    @Autowired
    JdbcTemplate jdbc;

    private SchedulerService scheduler(boolean enabled, String onlyRecipient) {
        return new SchedulerService(jdbc, wa, enabled, 60, 180, 120, 24, onlyRecipient);
    }

    private int doses(UUID scheduleId) {
        Integer n = jdbc.queryForObject("select count(*) from dose_events where schedule_id = ?", Integer.class, scheduleId);
        return n == null ? 0 : n;
    }

    private Med inTwoHours(Family f) {
        return fx.medicine(f, "Metformin", "500 mg", 20, Fixtures.localTime(f, Instant.now().plus(2, ChronoUnit.HOURS)));
    }

    @Test
    void makesTheUpcomingDoseAtOnceAndSendsNothing() {
        Family f = fx.family("en", true);
        Med m = inTwoHours(f);

        assertThat(scheduler(true, "all").generateNow(f.recipientId())).isGreaterThanOrEqualTo(1);

        assertThat(doses(m.scheduleId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status::text from dose_events where schedule_id = ?", String.class, m.scheduleId()))
                .isEqualTo("pending");
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void doesNothingWhenTheSchedulerIsOffOrScopedToSomeoneElse() {
        Family f = fx.family("en", true);
        Med m = inTwoHours(f);

        assertThat(scheduler(false, "all").generateNow(f.recipientId())).isZero();
        assertThat(scheduler(true, UUID.randomUUID().toString()).generateNow(f.recipientId())).isZero();
        assertThat(scheduler(true, "not-an-id").generateNow(f.recipientId())).isZero();
        assertThat(doses(m.scheduleId())).isZero();

        assertThat(scheduler(true, f.recipientId().toString()).generateNow(f.recipientId())).isGreaterThanOrEqualTo(1);
        assertThat(doses(m.scheduleId())).isEqualTo(1);
    }

    @Test
    void aTimeThatAlreadyPassedTodayGetsNoDose() {
        Family f = fx.family("en", true);
        UUID medicineId = UUID.randomUUID();
        jdbc.update("insert into medicines (id, recipient_id, name, dosage) values (?, ?, 'Thyroxine', '50 mcg')", medicineId, f.recipientId());
        UUID scheduleId = UUID.randomUUID();
        // created NOW (not backdated), for a time 30 minutes ago: no dose today, the first one is tomorrow (outside this check)
        jdbc.update("insert into medicine_schedules (id, medicine_id, time_of_day, start_date) values (?, ?, ?, ?)",
                scheduleId, medicineId, Fixtures.localTime(f, Instant.now().minus(30, ChronoUnit.MINUTES)), LocalDate.now().minusDays(1));

        scheduler(true, "all").generateNow(f.recipientId());

        Integer past = jdbc.queryForObject("select count(*) from dose_events where schedule_id = ? and scheduled_at < now()",
                Integer.class, scheduleId);
        assertThat(past).isZero();
        assertThat(wa.sent()).isEmpty();
    }
}
