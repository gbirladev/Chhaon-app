package org.smartgurukul.chhaon.recipient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Care type + due date (migration 0043) through the recipients API: older by default, pregnancy with a due date,
 * old clients that never send the fields, the due-date window, and clearing the date when it stops being a pregnancy.
 */
class CareTypeApiTest extends DbTest {

    @Autowired JwtService jwt;

    private final List<UUID> owners = new ArrayList<>();

    @AfterEach
    void removeCirclesMadeThroughTheApi() {
        for (UUID u : owners) fx.jdbc().update("delete from care_circles where created_by = ?", u);
        owners.clear();
    }

    private UUID owner() {
        UUID u = fx.user("Rohit");
        owners.add(u);
        return u;
    }

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private static String body(String extra) {
        return "{\"name\":\"Shivani Sharma\",\"relation\":\"spouse\",\"callName\":\"Shivani\",\"timezone\":\"Asia/Kolkata\",\"language\":\"hi\""
                + extra + "}";
    }

    private UUID create(UUID user, String extra) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/recipients").header("Authorization", bearer(user))
                        .contentType("application/json").content(body(extra)))
                .andExpect(status().isCreated()).andReturn();
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(r.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return UUID.fromString(m.group(1));
    }

    private Map<String, Object> row(UUID id) {
        return fx.jdbc().queryForMap("select care_type, due_date::text due from care_recipients where id = ?", id);
    }

    @Test
    void anOldClientThatSendsNeitherFieldGetsAnOlderParent() throws Exception {
        UUID user = owner();
        UUID id = create(user, "");
        assertThat(row(id)).containsEntry("care_type", "older").containsEntry("due", null);
        mvc.perform(get("/api/v1/recipients/{id}", id).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.careType", is("older")))
                .andExpect(jsonPath("$.dueDate", nullValue()));
    }

    @Test
    void aPregnancyIsSavedWithItsDueDateAndReadBackInTheList() throws Exception {
        UUID user = owner();
        LocalDate due = TODAY.plusDays(112);
        UUID id = create(user, ",\"careType\":\"pregnant\",\"dueDate\":\"" + due + "\"");
        assertThat(row(id)).containsEntry("care_type", "pregnant").containsEntry("due", due.toString());
        mvc.perform(get("/api/v1/recipients").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].careType", is("pregnant")))
                .andExpect(jsonPath("$[0].dueDate", is(due.toString())));
    }

    @Test
    void updateKeepsWhatIsLeftOutClearsOnEmptyAndSwitchingAwayClearsTheDueDate() throws Exception {
        UUID user = owner();
        String auth = bearer(user);
        LocalDate due = TODAY.plusDays(100);
        UUID id = create(user, ",\"careType\":\"pregnant\",\"dueDate\":\"" + due + "\"");

        // An older app version (no careType, no dueDate): nothing changes.
        mvc.perform(put("/api/v1/recipients/{id}", id).header("Authorization", auth).contentType("application/json").content(body("")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.careType", is("pregnant")))
                .andExpect(jsonPath("$.dueDate", is(due.toString())));

        // A new date, then "" clears it but keeps the pregnancy.
        LocalDate later = due.plusDays(7);
        mvc.perform(put("/api/v1/recipients/{id}", id).header("Authorization", auth).contentType("application/json")
                        .content(body(",\"careType\":\"pregnant\",\"dueDate\":\"" + later + "\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dueDate", is(later.toString())));
        mvc.perform(put("/api/v1/recipients/{id}", id).header("Authorization", auth).contentType("application/json")
                        .content(body(",\"dueDate\":\"\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.careType", is("pregnant")))
                .andExpect(jsonPath("$.dueDate", nullValue()));

        // Back to a date, then switching to older clears it (even if a date is sent along).
        mvc.perform(put("/api/v1/recipients/{id}", id).header("Authorization", auth).contentType("application/json")
                        .content(body(",\"dueDate\":\"" + due + "\""))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/recipients/{id}", id).header("Authorization", auth).contentType("application/json")
                        .content(body(",\"careType\":\"older\",\"dueDate\":\"" + due + "\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.careType", is("older")))
                .andExpect(jsonPath("$.dueDate", nullValue()));
        assertThat(row(id)).containsEntry("care_type", "older").containsEntry("due", null);
    }

    @Test
    void theDueDateMustBeInASaneWindow() throws Exception {
        UUID user = owner();
        String auth = bearer(user);
        for (String bad : new String[] {
                TODAY.plusDays(301).toString(),   // more than ~10 months ahead
                TODAY.minusDays(61).toString(),   // more than ~2 months ago
                "14/03/2027", "soon" }) {
            mvc.perform(post("/api/v1/recipients").header("Authorization", auth).contentType("application/json")
                            .content(body(",\"careType\":\"pregnant\",\"dueDate\":\"" + bad + "\"")))
                    .andExpect(status().isBadRequest());
        }
        // the edges are fine
        create(user, ",\"careType\":\"pregnant\",\"dueDate\":\"" + TODAY.plusDays(300) + "\"");
        create(user, ",\"careType\":\"pregnant\",\"dueDate\":\"" + TODAY.minusDays(60) + "\"");
        // and a pregnancy without a due date is allowed (the card simply waits for it)
        UUID none = create(user, ",\"careType\":\"pregnant\"");
        assertThat(row(none)).containsEntry("care_type", "pregnant").containsEntry("due", null);
    }

    @Test
    void anUnchangedOldDueDateNeverBlocksEditingSomethingElse() throws Exception {
        UUID user = owner();
        UUID id = create(user, ",\"careType\":\"pregnant\",\"dueDate\":\"" + TODAY.plusDays(10) + "\"");
        // Months later the stored date is far in the past; the app sends it back unchanged with another edit.
        LocalDate old = TODAY.minusDays(90);
        fx.jdbc().update("update care_recipients set due_date = ? where id = ?", old, id);
        mvc.perform(put("/api/v1/recipients/{id}", id).header("Authorization", bearer(user)).contentType("application/json")
                        .content(body(",\"careType\":\"pregnant\",\"dueDate\":\"" + old + "\",\"city\":\"Pune\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city", is("Pune")))
                .andExpect(jsonPath("$.dueDate", is(old.toString())));
    }

    @Test
    void aChildCanBeAdded() throws Exception {
        UUID user = owner();
        mvc.perform(post("/api/v1/recipients").header("Authorization", bearer(user)).contentType("application/json")
                        .content(body(",\"careType\":\"child\"")))
                .andExpect(status().isCreated());
        String type = fx.jdbc().queryForObject(
                "select r.care_type from care_recipients r join care_circles c on c.id = r.circle_id where c.created_by = ?", String.class, user);
        assertThat(type).isEqualTo("child");
    }

    @Test
    void unknownCareTypesAreRefused() throws Exception {
        UUID user = owner();
        for (String bad : new String[] {"baby", "Pregnant", "kid", ""}) {
            mvc.perform(post("/api/v1/recipients").header("Authorization", bearer(user)).contentType("application/json")
                            .content(body(",\"careType\":\"" + bad + "\"")))
                    .andExpect(status().isBadRequest());
        }
        Integer n = fx.jdbc().queryForObject(
                "select count(*) from care_recipients r join care_circles c on c.id = r.circle_id where c.created_by = ?", Integer.class, user);
        assertThat(n).isZero();
    }

    @Test
    void theCaregiversOwnProfileStaysOlder() throws Exception {
        UUID user = owner();
        mvc.perform(post("/api/v1/recipients").header("Authorization", bearer(user)).contentType("application/json")
                        .content("{\"name\":\"Rohit\",\"isSelf\":true,\"whatsappPhone\":\"" + Fixtures.randomPhone() + "\",\"careType\":\"pregnant\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aStrangerCannotChangeTheCareType() throws Exception {
        UUID user = owner();
        UUID id = create(user, "");
        UUID stranger = owner();
        mvc.perform(put("/api/v1/recipients/{id}", id).header("Authorization", bearer(stranger)).contentType("application/json")
                        .content(body(",\"careType\":\"pregnant\"")))
                .andExpect(status().isNotFound());
        assertThat(row(id)).containsEntry("care_type", "older");
    }
}
