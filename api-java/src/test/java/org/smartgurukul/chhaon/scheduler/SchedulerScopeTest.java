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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first switch-on of reminders is for ONE parent: with chhaon.scheduler.only-recipient set, the scheduled tick touches only that
 * recipient. An unreadable value must never mean "everyone".
 */
class SchedulerScopeTest extends DbTest {

    @Autowired
    JdbcTemplate jdbc;

    private SchedulerService scheduler(boolean enabled, String onlyRecipient) {
        return new SchedulerService(jdbc, wa, enabled, 60, 180, 120, 24, onlyRecipient);
    }

    private UUID dueDose(Family f) {
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        return fx.dose(m.scheduleId(), Instant.now().minus(5, ChronoUnit.MINUTES), "pending", null, null);
    }

    @Test
    void withOneRecipientSetOnlyThatParentIsMessaged() {
        Family mine = fx.family("en", true);
        Family other = fx.family("en", true);
        UUID myDose = dueDose(mine);
        UUID otherDose = dueDose(other);

        scheduler(true, mine.recipientId().toString()).scheduledTick();

        assertThat(fx.doseStatus(myDose)).isEqualTo("sent");
        assertThat(wa.sentTo(mine.phone())).hasSize(1);
        assertThat(fx.doseStatus(otherDose)).isEqualTo("pending");   // untouched
        assertThat(wa.sentTo(other.phone())).isEmpty();
    }

    @Test
    void anUnreadableValueRunsNothingAtAll() {
        Family f = fx.family("en", true);
        UUID dose = dueDose(f);

        for (String bad : new String[] {"not-an-id", "123", "all but one", "00000000-0000"}) {
            scheduler(true, bad).scheduledTick();
        }

        assertThat(fx.doseStatus(dose)).isEqualTo("pending");
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void disabledNeverRunsEvenWithAValidRecipient() {
        Family f = fx.family("en", true);
        UUID dose = dueDose(f);

        scheduler(false, f.recipientId().toString()).scheduledTick();

        assertThat(fx.doseStatus(dose)).isEqualTo("pending");
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void theEnvironmentVariablesReachTheSettings() {
        // How the deploy's env file values become the properties the scheduler reads (standard Spring mapping).
        var env = new org.springframework.core.env.SystemEnvironmentPropertySource("test", java.util.Map.of(
                "CHHAON_SCHEDULER_ENABLED", "true",
                "CHHAON_SCHEDULER_ONLY_RECIPIENT", "11111111-2222-3333-4444-555555555555"));
        assertThat(env.getProperty("chhaon.scheduler.enabled")).isEqualTo("true");
        assertThat(env.getProperty("chhaon.scheduler.only-recipient")).isEqualTo("11111111-2222-3333-4444-555555555555");
    }

    @Test
    void emptyOrAllMeansEveryFamilyAndIsParsedWithoutError() {
        // Not run (it would tick every row of the database); only that the values are accepted. A scoped explicit tick still works.
        assertThat(scheduler(false, "").tick(UUID.randomUUID())).isNotNull();
        assertThat(scheduler(false, "ALL").tick(UUID.randomUUID())).isNotNull();
        assertThat(scheduler(false, null).tick(UUID.randomUUID())).isNotNull();
    }
}
