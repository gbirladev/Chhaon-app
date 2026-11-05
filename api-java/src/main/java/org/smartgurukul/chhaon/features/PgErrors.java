package org.smartgurukul.chhaon.features;

import org.springframework.dao.DataAccessException;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what a PL/pgSQL `raise exception ... using hint = '...'` wanted the app to know. The web client's
 * copy (invite failures, love-loop reasons) keys off these hints/messages, so they must survive the trip
 * through JDBC. The Postgres driver is runtime-scope, so the hint is read from the exception text instead of
 * PSQLException's typed API.
 */
final class PgErrors {

    private static final Pattern HINT = Pattern.compile("(?mi)^\\s*hint:\\s*(\\S+)\\s*$");

    private PgErrors() {}

    /** The deepest cause's message (where Postgres' ERROR/Detail/Hint text lives). */
    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return String.valueOf(t.getMessage());
    }

    /** The PG `hint`, or null. */
    static String hint(DataAccessException e) {
        Matcher m = HINT.matcher(rootMessage(e));
        return m.find() ? m.group(1) : null;
    }

    /** The SQLSTATE (e.g. 23505 unique violation, 53400 configuration limit), or null. */
    static String sqlState(DataAccessException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
            t = t.getCause() == t ? null : t.getCause();
        }
        return null;
    }
}
