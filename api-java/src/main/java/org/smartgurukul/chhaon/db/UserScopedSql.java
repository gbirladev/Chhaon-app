package org.smartgurukul.chhaon.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Function;

/**
 * Runs SQL "as" a signed-in caregiver, reproducing the environment PostgREST gave the old
 * frontend-to-Supabase calls:
 * <ul>
 *   <li>{@code set_config('request.jwt.claims', ..., true)} makes {@code auth.uid()} return the user's id, so
 *       every existing SQL function (invites, love, emergency links, plan_overview, ...) and the
 *       {@code stamp_author}-style triggers behave exactly as before;</li>
 *   <li>{@code SET LOCAL ROLE authenticated} re-enables Row Level Security with the same grants the web
 *       client had, so the database's own access rules keep protecting every row.</li>
 * </ul>
 * Both settings are transaction-local: they vanish when the transaction ends, so nothing leaks back into the
 * connection pool. Work given to {@link #asUser} therefore runs in its own transaction.
 */
@Component
public class UserScopedSql {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public UserScopedSql(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Run the given work as this user (auth.uid() set, RLS on) inside one transaction. */
    public <T> T asUser(UUID userId, Function<JdbcTemplate, T> work) {
        return tx.execute(status -> {
            jdbc.queryForObject("select set_config('request.jwt.claims', ?, true)", String.class,
                    "{\"sub\":\"" + userId + "\",\"role\":\"authenticated\"}");
            jdbc.execute("set local role authenticated");
            return work.apply(jdbc);
        });
    }

    /** Plain service-role access (RLS bypassed): for queries whose scoping the Java service does itself. */
    public <T> T asService(Function<JdbcTemplate, T> work) {
        return tx.execute(status -> work.apply(jdbc));
    }
}
