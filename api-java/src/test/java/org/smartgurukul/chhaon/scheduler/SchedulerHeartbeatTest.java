package org.smartgurukul.chhaon.scheduler;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every tick records a heartbeat (best effort) so the app can tell when reminders have stopped. */
class SchedulerHeartbeatTest extends DbTest {

    @Autowired
    SchedulerService scheduler;

    private Instant lastTick() {
        return fx.jdbc().queryForObject("select last_tick_at from public.scheduler_heartbeat where id = 1",
                java.sql.Timestamp.class).toInstant();
    }

    @Test
    void aTickRecordsTheHeartbeatInTheSingleRow() {
        Family f = fx.family("en", true);

        scheduler.tick(f.recipientId());

        Integer rows = fx.jdbc().queryForObject("select count(*) from public.scheduler_heartbeat", Integer.class);
        assertThat(rows).isEqualTo(1);
        assertThat(Duration.between(lastTick(), Instant.now()).abs()).isLessThan(Duration.ofSeconds(10));

        // A second tick moves the same row forward (still one row).
        Instant first = lastTick();
        scheduler.tick(f.recipientId());
        assertThat(fx.jdbc().queryForObject("select count(*) from public.scheduler_heartbeat", Integer.class)).isEqualTo(1);
        assertThat(lastTick()).isAfterOrEqualTo(first);
    }

    @Test
    void aFailingHeartbeatNeverMakesTheTickFailOrChangesItsCounters() {
        Family f = fx.family("en", true);
        Map<String, Integer> normal = scheduler.tick(f.recipientId());

        JdbcTemplate broken = new JdbcTemplate(fx.jdbc().getDataSource()) {
            @Override
            public void execute(String sql) {
                if (sql.contains("touch_scheduler_heartbeat")) {
                    throw new org.springframework.jdbc.BadSqlGrammarException("test", sql,
                            new java.sql.SQLException("function does not exist"));
                }
                super.execute(sql);
            }
        };
        SchedulerService withBrokenHeartbeat =
                new SchedulerService(broken, wa, false, 60, 180, 120, 24);

        Map<String, Integer> counters = withBrokenHeartbeat.tick(f.recipientId());

        assertThat(counters).isEqualTo(normal);
        assertThat(counters).doesNotContainKey("step_errors");
    }
}
