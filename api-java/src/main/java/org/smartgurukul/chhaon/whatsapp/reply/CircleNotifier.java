package org.smartgurukul.chhaon.whatsapp.reply;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-app notification for every caregiver in a circle (port of {@code api/app/services/notify.py}).
 * The payload carries ids only: never health data or phone numbers.
 */
@Component
public class CircleNotifier {

    /**
     * Things that only make sense when the person is somebody ELSE (a parent). For the caregiver's own profile
     * ("Me", its own private circle) they would only tell you about yourself, so they are not created.
     */
    static final Set<String> SELF_QUIET = Set.of(
            "dose_missed", "checkin_missed", "checkin_call_requested", "parent_consented", "parent_declined",
            "parent_stopped", "parent_resumed", "parent_responded", "parent_silent", "appointment_reminder",
            "love_parent_opted_out", "love_parent_opted_in", "parent_message");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CircleNotifier(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void notifyCircle(UUID circleId, String type, Map<String, ?> payload) {
        if (SELF_QUIET.contains(type)) {
            Integer self = jdbc.queryForObject(
                    "select count(*) from care_recipients where circle_id = ? and is_self", Integer.class, circleId);
            if (self != null && self > 0) return;
        }
        String payloadJson;
        try {
            payloadJson = json.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("insert into notifications (id, user_id, type, payload, created_at) " +
                        "select gen_random_uuid(), m.user_id, ?, cast(? as jsonb), now() " +
                        "from care_circle_members m where m.circle_id = ?",
                type, payloadJson, circleId);
    }
}
