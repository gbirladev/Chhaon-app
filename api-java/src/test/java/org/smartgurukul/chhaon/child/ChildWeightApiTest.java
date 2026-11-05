package org.smartgurukul.chhaon.child;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A child's weight (migration 0052): a recorded fact, guardian confirmation first, 0.5 to 200 kg, family circle only. */
class ChildWeightApiTest extends DbTest {

    @Autowired JwtService jwt;

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    private Fixtures.Family child(boolean guardian) {
        Fixtures.Family f = fx.family("en", false);
        fx.jdbc().update("update care_recipients set care_type = 'child', dob = ?, guardian_confirmed_at = case when ? then now() end where id = ?",
                today().minusMonths(5), guardian, f.recipientId());
        return f;
    }

    private void weight(Fixtures.Family f, UUID userId, String body, int expect) throws Exception {
        mvc.perform(put("/api/v1/recipients/{id}/child/weight", f.recipientId()).header("Authorization", bearer(userId))
                        .contentType("application/json").content(body))
                .andExpect(status().is(expect));
    }

    private Map<String, Object> saved(Fixtures.Family f) {
        return fx.jdbc().queryForMap("select weight_kg, weight_on from care_recipients where id = ?", f.recipientId());
    }

    @Test
    void aWeightIsSavedWithItsDay_returnedWithTheChild_andCanBeCleared() throws Exception {
        Fixtures.Family f = child(true);
        LocalDate on = today().minusDays(4);
        weight(f, f.userId(), "{\"weightKg\":6.8,\"weightOn\":\"" + on + "\"}", 200);
        assertThat((BigDecimal) saved(f).get("weight_kg")).isEqualByComparingTo("6.8");
        assertThat(saved(f).get("weight_on").toString()).isEqualTo(on.toString());
        mvc.perform(get("/api/v1/recipients/{id}/child", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightKg", is(6.8)))
                .andExpect(jsonPath("$.weightOn", is(on.toString())));

        weight(f, f.userId(), "{\"weightKg\":7.1}", 200);   // no day = today on the child's clock
        assertThat(saved(f).get("weight_on").toString()).isEqualTo(today().toString());

        weight(f, f.userId(), "{\"weightKg\":null,\"weightOn\":null}", 200);
        assertThat(saved(f).get("weight_kg")).isNull();
        mvc.perform(get("/api/v1/recipients/{id}/child", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(jsonPath("$.weightKg", nullValue()));
    }

    @Test
    void outOfRangeWeightsAndDaysAreRefused() throws Exception {
        Fixtures.Family f = child(true);
        weight(f, f.userId(), "{\"weightKg\":0.4}", 400);
        weight(f, f.userId(), "{\"weightKg\":200.5}", 400);
        weight(f, f.userId(), "{\"weightKg\":6.123}", 400);
        weight(f, f.userId(), "{\"weightKg\":6.8,\"weightOn\":\"" + today().plusDays(2) + "\"}", 400);
        weight(f, f.userId(), "{\"weightKg\":6.8,\"weightOn\":\"" + today().minusYears(1) + "\"}", 400);   // before the date of birth
        weight(f, f.userId(), "{\"weightKg\":null,\"weightOn\":\"" + today() + "\"}", 400);
        assertThat(saved(f).get("weight_kg")).isNull();
        weight(f, f.userId(), "{\"weightKg\":0.5}", 200);
        weight(f, f.userId(), "{\"weightKg\":200}", 200);
    }

    @Test
    void guardianConfirmationFirst_childProfilesOnly_familyCircleOnly() throws Exception {
        Fixtures.Family unconfirmed = child(false);
        weight(unconfirmed, unconfirmed.userId(), "{\"weightKg\":6.8}", 409);

        Fixtures.Family older = fx.family("en", false);
        mvc.perform(put("/api/v1/recipients/{id}/child/weight", older.recipientId()).header("Authorization", bearer(older.userId()))
                        .contentType("application/json").content("{\"weightKg\":60}"))
                .andExpect(status().isConflict());

        Fixtures.Family f = child(true);
        Fixtures.Family stranger = fx.family("en", false);
        weight(f, stranger.userId(), "{\"weightKg\":6.8}", 404);
        assertThat(saved(f).get("weight_kg")).isNull();
    }
}
