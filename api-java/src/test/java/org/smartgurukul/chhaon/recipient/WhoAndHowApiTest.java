package org.smartgurukul.chhaon.recipient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.scheduler.SchedulerService;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Round 2 "who and how" (migration 20261105000050): a child never gets the reminders, the new relations, the per-person daily
 * check-in switch (new pregnancies off; the scheduler honours it) and the receiver's own language.
 */
class WhoAndHowApiTest extends DbTest {

    @Autowired JwtService jwt;

    private final List<UUID> owners = new ArrayList<>();

    @AfterEach
    void removeCirclesMadeThroughTheApi() {
        for (UUID u : owners) fx.jdbc().update("delete from care_circles where created_by = ?", u);
        owners.clear();
    }

    private UUID owner() {
        UUID u = fx.user("Meera");
        owners.add(u);
        return u;
    }

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    private static String body(String name, String extra) {
        return "{\"name\":\"" + name + "\",\"timezone\":\"Asia/Kolkata\",\"language\":\"en\"" + extra + "}";
    }

    private UUID create(UUID user, String json) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/recipients").header("Authorization", bearer(user))
                        .contentType("application/json").content(json))
                .andExpect(status().isCreated()).andReturn();
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(r.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return UUID.fromString(m.group(1));
    }

    private Map<String, Object> row(UUID id) {
        return fx.jdbc().queryForMap(
                "select relation, reminder_to, checkin_enabled, reminder_language, language from care_recipients where id = ?", id);
    }

    private static final String NANI = ",\"careType\":\"child\",\"reminderTo\":\"helper\",\"reminderName\":\"Kamla\",\"reminderRelation\":\"Nani\","
            + "\"whatsappPhone\":\"+919000000051\"";

    @Test
    void aChildIsSetUpWithTheAdultWhoGivesTheMedicine() throws Exception {
        UUID user = owner();
        UUID id = create(user, body("Aarav", ",\"relation\":\"son\"" + NANI + ",\"reminderLanguage\":\"hi\""));
        assertThat(row(id)).containsEntry("relation", "son").containsEntry("reminder_to", "helper").containsEntry("reminder_language", "hi");
        mvc.perform(get("/api/v1/recipients/{id}", id).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relation", is("son")))
                .andExpect(jsonPath("$.reminderTo", is("helper")))
                .andExpect(jsonPath("$.reminderLanguage", is("hi")))
                .andExpect(jsonPath("$.checkinEnabled", is(true)));
    }

    @Test
    void aChildNeverGetsTheRemindersThemself() throws Exception {
        UUID user = owner();
        // explicitly, and by leaving the choice out (the default is the person)
        for (String extra : new String[] {",\"careType\":\"child\",\"reminderTo\":\"person\",\"whatsappPhone\":\"+919000000052\"",
                ",\"careType\":\"child\""}) {
            mvc.perform(post("/api/v1/recipients").header("Authorization", bearer(user)).contentType("application/json")
                            .content(body("Aarav", extra)))
                    .andExpect(status().isBadRequest());
        }
        Integer n = fx.jdbc().queryForObject(
                "select count(*) from care_recipients r join care_circles c on c.id = r.circle_id where c.created_by = ?", Integer.class, user);
        assertThat(n).isZero();

        // switching an existing child back to "the person" is refused too; so is turning a parent who gets their own into a child
        UUID child = create(user, body("Aarav", NANI));
        mvc.perform(put("/api/v1/recipients/{id}", child).header("Authorization", bearer(user)).contentType("application/json")
                        .content(body("Aarav", ",\"careType\":\"child\",\"reminderTo\":\"person\",\"whatsappPhone\":\"+919000000051\"")))
                .andExpect(status().isBadRequest());
        UUID papa = create(user, body("Ramesh", ",\"relation\":\"father\",\"whatsappPhone\":\"+919000000053\""));
        mvc.perform(put("/api/v1/recipients/{id}", papa).header("Authorization", bearer(user)).contentType("application/json")
                        .content(body("Ramesh", ",\"careType\":\"child\",\"whatsappPhone\":\"+919000000053\"")))
                .andExpect(status().isBadRequest());
        assertThat(row(child)).containsEntry("reminder_to", "helper");
    }

    @Test
    void theNewRelationsAreKeptAndUnknownOnesBecomeOther() throws Exception {
        for (String rel : new String[] {"son", "daughter", "grandson", "granddaughter", "wife", "husband"}) {
            UUID id = create(owner(), body("X " + rel, ",\"relation\":\"" + rel + "\""));   // one owner each: the free plan's parent limit
            assertThat(row(id)).containsEntry("relation", rel);
        }
        UUID odd = create(owner(), body("Y", ",\"relation\":\"neighbour\""));
        assertThat(row(odd)).containsEntry("relation", "other");
    }

    @Test
    void aNewPregnancyStartsWithoutTheCheckinAndTheSwitchIsKeptOnEdits() throws Exception {
        UUID user = owner();
        UUID shivani = create(user, body("Shivani", ",\"relation\":\"wife\",\"careType\":\"pregnant\""));
        assertThat(row(shivani)).containsEntry("checkin_enabled", false);
        UUID papa = create(user, body("Ramesh", ""));
        assertThat(row(papa)).containsEntry("checkin_enabled", true);
        UUID asked = create(user, body("Priya", ",\"careType\":\"pregnant\",\"checkinEnabled\":true"));
        assertThat(row(asked)).containsEntry("checkin_enabled", true);

        // switched on; an older app version (no field) keeps it; switched off again
        String auth = bearer(user);
        mvc.perform(put("/api/v1/recipients/{id}", shivani).header("Authorization", auth).contentType("application/json")
                        .content(body("Shivani", ",\"checkinEnabled\":true"))).andExpect(status().isOk()).andExpect(jsonPath("$.checkinEnabled", is(true)));
        mvc.perform(put("/api/v1/recipients/{id}", shivani).header("Authorization", auth).contentType("application/json")
                        .content(body("Shivani", ""))).andExpect(status().isOk()).andExpect(jsonPath("$.checkinEnabled", is(true)));
        mvc.perform(put("/api/v1/recipients/{id}", shivani).header("Authorization", auth).contentType("application/json")
                        .content(body("Shivani", ",\"checkinEnabled\":false"))).andExpect(status().isOk()).andExpect(jsonPath("$.checkinEnabled", is(false)));
    }

    @Test
    void theReceiversLanguageIsValidatedKeptAndClearedForThePerson() throws Exception {
        UUID user = owner();
        String auth = bearer(user);
        String helper = ",\"reminderTo\":\"helper\",\"reminderName\":\"Sunita\",\"whatsappPhone\":\"+919000000054\"";
        mvc.perform(post("/api/v1/recipients").header("Authorization", auth).contentType("application/json")
                        .content(body("Ramesh", helper + ",\"reminderLanguage\":\"ta\"")))
                .andExpect(status().isBadRequest());
        UUID id = create(user, body("Ramesh", helper + ",\"reminderLanguage\":\"hi\""));
        assertThat(row(id)).containsEntry("reminder_language", "hi").containsEntry("language", "en");

        // left out: kept; "": back to the person's language
        mvc.perform(put("/api/v1/recipients/{id}", id).header("Authorization", auth).contentType("application/json")
                        .content(body("Ramesh", helper))).andExpect(status().isOk()).andExpect(jsonPath("$.reminderLanguage", is("hi")));
        mvc.perform(put("/api/v1/recipients/{id}", id).header("Authorization", auth).contentType("application/json")
                        .content(body("Ramesh", helper + ",\"reminderLanguage\":\"\""))).andExpect(status().isOk()).andExpect(jsonPath("$.reminderLanguage", nullValue()));

        // the person themself gets them: no receiver language is kept, whatever is sent
        mvc.perform(put("/api/v1/recipients/{id}", id).header("Authorization", auth).contentType("application/json")
                        .content(body("Ramesh", ",\"reminderTo\":\"person\",\"whatsappPhone\":\"+919000000054\",\"reminderLanguage\":\"hi\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reminderLanguage", nullValue()));
    }

    @Test
    void theLanguageHelperPicksTheReceiversLanguageOnlyForSomeoneElse() {
        assertThat(RecipientLanguage.forReceiver("helper", "hi", "en")).isEqualTo("hi");
        assertThat(RecipientLanguage.forReceiver("caregiver", "en", "hi")).isEqualTo("en");
        assertThat(RecipientLanguage.forReceiver("helper", null, "hi")).isEqualTo("hi");
        assertThat(RecipientLanguage.forReceiver("person", "hi", "en")).isEqualTo("en");   // stale value never wins for the person
        assertThat(RecipientLanguage.forReceiver("helper", "ta", "fr")).isEqualTo("en");
        Map<String, Object> row = new HashMap<>();   // a row selected before the column existed
        row.put("reminder_to", "helper");
        row.put("language", "hi");
        assertThat(RecipientLanguage.forReceiver(row)).isEqualTo("hi");
        assertThat(RecipientLanguage.forReceiver((Map<String, Object>) null)).isEqualTo("en");
    }

    @Test
    void theSchedulerSkipsTheCheckinWhenItIsSwitchedOff() {
        Family f = fx.family("en", true);
        fx.jdbc().update("update care_recipients set checkin_time = ?, checkin_enabled = false where id = ?",
                ZonedDateTime.now(f.zone()).minusMinutes(1).toLocalTime().withSecond(0).withNano(0), f.recipientId());
        SchedulerService scheduler = new SchedulerService(fx.jdbc(), wa, true, 60, 180, 120, 24, f.recipientId().toString());
        scheduler.scheduledTick();
        assertThat(wa.sentTo(f.phone())).isEmpty();

        fx.jdbc().update("update care_recipients set checkin_enabled = true where id = ?", f.recipientId());
        scheduler.scheduledTick();
        assertThat(wa.sentTo(f.phone())).hasSize(1);
    }
}
