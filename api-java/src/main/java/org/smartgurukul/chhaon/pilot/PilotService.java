package org.smartgurukul.chhaon.pilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * The pilot cap (docs/pilot-cap.md, migration 20261107000060): how many WhatsApp numbers get messages while we are in pilot, and
 * a line for the rest. The decisions live in the database (pilot_admit is serialized there); this class only calls them.
 *
 * <p>Read defensively: until the migration has run (Sydney: SQL first, then push) {@link #admit} lets everyone in and
 * {@link #position} answers null, so a backend deployed early behaves exactly as before.
 */
@Service
public class PilotService {

    private static final Logger log = LoggerFactory.getLogger(PilotService.class);

    /** ok, or pilot_full with the place in line (1 = next). */
    public record Admission(boolean ok, Integer position) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private volatile boolean installed;   // once seen, the functions stay (a migration is never rolled back)

    public PilotService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Lets this person start WhatsApp, or puts them in line. Called just before their consent request is sent. */
    public Admission admit(UUID recipientId, UUID userId) {
        if (!installed()) return new Admission(true, null);
        String raw = jdbc.queryForObject("select public.pilot_admit(?, ?)::text", String.class, recipientId, userId);
        JsonNode n = read(raw);
        if ("pilot_full".equals(n.path("status").asText())) {
            return new Admission(false, n.path("position").isNumber() ? n.path("position").asInt() : null);
        }
        return new Admission(true, null);
    }

    /** Place in line, or null when this person is not waiting (or the cap is off / not installed). Never throws. */
    public Integer position(UUID recipientId) {
        try {
            if (!installed()) return null;
            return jdbc.queryForObject("select public.pilot_position(?)", Integer.class, recipientId);
        } catch (Exception e) {
            log.warn("pilot position unavailable ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * An honest guess of how long someone at {@code position} still waits (docs/pilot-cap.md): 0 = today (there is room left in
     * today's quota), N = about N days at max_new_per_day a day, null = no guess (every seat is taken, so it depends on someone
     * stopping; or no daily room at all). "Today" is the rolling 24 hours pilot_admit counts. Never throws.
     */
    public Integer waitDays(Integer position) {
        if (position == null || position < 1) return null;
        try {
            if (!installed()) return null;
            Map<String, Object> s = jdbc.queryForList(
                    "select s.enabled, s.max_active, s.max_new_per_day, public.pilot_seats_used() used, " +
                    "(select count(*) from public.pilot_queue q where q.admitted_at > now() - interval '24 hours')::int new_24h " +
                    "from public.pilot_settings s where s.id").stream().findFirst().orElse(null);
            if (s == null || !Boolean.TRUE.equals(s.get("enabled"))) return null;
            return waitDays(position, num(s.get("max_active")), num(s.get("used")), num(s.get("max_new_per_day")), num(s.get("new_24h")));
        } catch (Exception e) {
            log.warn("pilot wait estimate unavailable ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /** The arithmetic of {@link #waitDays(Integer)}, kept pure for tests. */
    static Integer waitDays(int position, int maxActive, int seatsUsed, int maxNewPerDay, int new24h) {
        if (position < 1 || maxNewPerDay <= 0) return null;
        if (position > maxActive - seatsUsed) return null;   // all seats taken: depends on someone leaving, no date to promise
        int leftToday = Math.max(0, maxNewPerDay - new24h);
        if (position <= leftToday) return 0;
        return (position - leftToday + maxNewPerDay - 1) / maxNewPerDay;
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    /** The Monitor's Pilot card: numbers and the waiting line (names only, no phone numbers). Null when not installed. */
    public JsonNode summary() {
        if (!installed()) return null;
        return read(jdbc.queryForObject("select public.pilot_summary()::text", String.class));
    }

    /** The owner's "Move to front". false when that person is not waiting. */
    public boolean moveUp(UUID recipientId) {
        if (!installed()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("select public.pilot_move_up(?)", Boolean.class, recipientId));
    }

    /** to_regprocedure never fails, even when the function does not exist (unlike calling it inside a transaction). */
    boolean installed() {
        if (installed) return true;
        Boolean present = jdbc.queryForObject("select to_regprocedure('public.pilot_admit(uuid,uuid)') is not null", Boolean.class);
        installed = Boolean.TRUE.equals(present);
        return installed;
    }

    private JsonNode read(String raw) {
        try {
            return json.readTree(raw == null ? "null" : raw);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable pilot answer", e);
        }
    }
}
