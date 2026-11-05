package org.smartgurukul.chhaon.scheduler;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The dose reminder names how the tablet looks when the family has set it (migration 0042). */
class SchedulerPillLookTest extends DbTest {

    @Autowired
    JdbcTemplate jdbc;

    private SchedulerService scheduler(Family f) {
        return new SchedulerService(jdbc, wa, true, 60, 180, 120, 24, f.recipientId().toString());
    }

    @Test
    void theReminderSaysTheColourAndShape() {
        Family f = fx.family("hi", true);
        Med m = fx.medicine(f, "Amlodipine", "5 mg", 20, LocalTime.of(3, 0));
        jdbc.update("update public.medicines set pill_colour = 'blue', pill_shape = 'round' where id = ?", m.medicineId());
        fx.dose(m.scheduleId(), Instant.now().minus(5, ChronoUnit.MINUTES), "pending", null, null);

        scheduler(f).scheduledTick();

        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("*Amlodipine*\nमात्रा: *5 mg*\n🔵 नीली गोल गोली\n");
    }

    @Test
    void withoutColourAndShapeTheReminderHasNoLookLine() {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        fx.dose(m.scheduleId(), Instant.now().minus(5, ChronoUnit.MINUTES), "pending", null, null);

        scheduler(f).scheduledTick();

        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("*Metformin*\nDose: *500 mg*\n\nReply *1*");
    }
}
