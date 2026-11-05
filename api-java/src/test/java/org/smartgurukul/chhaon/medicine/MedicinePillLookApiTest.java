package org.smartgurukul.chhaon.medicine;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

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
 * Tablet colour and shape (migration 0042) through the medicines API: saved on create, returned on read,
 * kept / cleared / changed on update, and only the fixed words are accepted.
 */
class MedicinePillLookApiTest extends DbTest {

    @Autowired JwtService jwt;

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    private Map<String, Object> look(UUID medicineId) {
        return fx.jdbc().queryForMap("select pill_colour, pill_shape from medicines where id = ?", medicineId);
    }

    private UUID create(Fixtures.Family f, String lookJson) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/recipients/{id}/medicines", f.recipientId())
                        .header("Authorization", bearer(f.userId())).contentType("application/json")
                        .content("{\"name\":\"Amlodipine\",\"dosage\":\"5 mg\",\"times\":[\"08:00\"]" + lookJson + "}"))
                .andExpect(status().isCreated()).andReturn();
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(created.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return UUID.fromString(m.group(1));
    }

    private String update(UUID id, String lookJson) {
        return "{\"name\":\"Amlodipine\",\"dosage\":\"5 mg\",\"times\":[\"08:00\"]" + lookJson + "}";
    }

    @Test
    void createSavesColourAndShapeAndTheListReturnsThem() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        UUID id = create(f, ",\"pillColour\":\"blue\",\"pillShape\":\"round\"");

        assertThat(look(id)).containsEntry("pill_colour", "blue").containsEntry("pill_shape", "round");
        mvc.perform(get("/api/v1/recipients/{id}/medicines", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pillColour", is("blue")))
                .andExpect(jsonPath("$[0].pillShape", is("round")));
    }

    @Test
    void createWithoutALookOrWithEmptyStringsLeavesItUnset() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        UUID plain = create(f, "");
        UUID empty = create(f, ",\"pillColour\":\"\",\"pillShape\":null");

        assertThat(look(plain)).containsEntry("pill_colour", null).containsEntry("pill_shape", null);
        assertThat(look(empty)).containsEntry("pill_colour", null).containsEntry("pill_shape", null);
        mvc.perform(get("/api/v1/recipients/{id}/medicines", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(jsonPath("$[0].pillColour", nullValue()))
                .andExpect(jsonPath("$[0].pillShape", nullValue()));
    }

    @Test
    void updateKeepsWhenLeftOutClearsOnEmptyStringAndChangesOnAValue() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        String auth = bearer(f.userId());
        UUID id = create(f, ",\"pillColour\":\"white\",\"pillShape\":\"capsule\"");

        // An older app version that does not know the fields: nothing is wiped.
        mvc.perform(put("/api/v1/medicines/{id}", id).header("Authorization", auth)
                        .contentType("application/json").content(update(id, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillColour", is("white")))
                .andExpect(jsonPath("$.pillShape", is("capsule")));

        // Change one, clear the other.
        mvc.perform(put("/api/v1/medicines/{id}", id).header("Authorization", auth)
                        .contentType("application/json").content(update(id, ",\"pillColour\":\"yellow\",\"pillShape\":\"\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillColour", is("yellow")))
                .andExpect(jsonPath("$.pillShape", nullValue()));
        assertThat(look(id)).containsEntry("pill_colour", "yellow").containsEntry("pill_shape", null);

        // Clear both.
        mvc.perform(put("/api/v1/medicines/{id}", id).header("Authorization", auth)
                        .contentType("application/json").content(update(id, ",\"pillColour\":\"\",\"pillShape\":\"\"")))
                .andExpect(status().isOk());
        assertThat(look(id)).containsEntry("pill_colour", null).containsEntry("pill_shape", null);
    }

    @Test
    void unknownWordsAreRefusedWith400AndNothingChanges() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        String auth = bearer(f.userId());

        for (String bad : new String[] {
                ",\"pillColour\":\"Blue\"",               // only the lower-case words
                ",\"pillColour\":\"blue\\n*1*\"",         // free text must never reach the WhatsApp message
                ",\"pillShape\":\"triangle\"" }) {
            mvc.perform(post("/api/v1/recipients/{id}/medicines", f.recipientId()).header("Authorization", auth)
                            .contentType("application/json")
                            .content("{\"name\":\"X\",\"times\":[\"08:00\"]" + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
        Integer n = fx.jdbc().queryForObject("select count(*) from medicines where recipient_id = ?", Integer.class, f.recipientId());
        assertThat(n).isZero();

        UUID id = create(f, ",\"pillColour\":\"red\",\"pillShape\":\"oval\"");
        mvc.perform(put("/api/v1/medicines/{id}", id).header("Authorization", auth)
                        .contentType("application/json").content(update(id, ",\"pillColour\":\"purple\",\"pillShape\":\"square\"")))
                .andExpect(status().isBadRequest());
        assertThat(look(id)).containsEntry("pill_colour", "red").containsEntry("pill_shape", "oval");
    }

    @Test
    void aStrangerCannotSetTheLook() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        UUID id = create(f, ",\"pillColour\":\"green\"");
        UUID stranger = fx.user("Stranger");
        mvc.perform(put("/api/v1/medicines/{id}", id).header("Authorization", bearer(stranger))
                        .contentType("application/json").content(update(id, ",\"pillColour\":\"pink\"")))
                .andExpect(status().isNotFound());
        assertThat(look(id)).containsEntry("pill_colour", "green");
    }
}
