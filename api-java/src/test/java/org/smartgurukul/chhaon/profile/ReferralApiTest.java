package org.smartgurukul.chhaon.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Doctor code (?ref=D001) and "How did you hear about Chhaon?" through PATCH /api/v1/me, and the admin counts (migration 0058). */
class ReferralApiTest extends DbTest {

    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    private ResultActions patchMe(UUID userId, String body) throws Exception {
        return mvc.perform(patch("/api/v1/me").header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String stored(UUID userId, String column) {
        return fx.jdbc().queryForObject("select " + column + " from profiles where id = ?", String.class, userId);
    }

    @Test
    void aNewAccountKeepsTheFirstCodeAndAnswer() throws Exception {
        UUID me = fx.user("Asha");
        patchMe(me, "{\"referredBy\":\" d001 \",\"heardFrom\":\"Doctor\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referredBy", is("D001")))
                .andExpect(jsonPath("$.heardFrom", is("doctor")));
        patchMe(me, "{\"referredBy\":\"D002\",\"heardFrom\":\"google\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referredBy", is("D001")))
                .andExpect(jsonPath("$.heardFrom", is("doctor")));
        assertThat(stored(me, "referred_by")).isEqualTo("D001");
        assertThat(stored(me, "heard_from")).isEqualTo("doctor");
    }

    @Test
    void otherUpdatesLeaveTheValuesAlone() throws Exception {
        UUID me = fx.user("Asha");
        patchMe(me, "{\"referredBy\":\"D_01-A\"}").andExpect(status().isOk());
        patchMe(me, "{\"todayLayout\":\"simple\"}").andExpect(status().isOk());
        assertThat(stored(me, "referred_by")).isEqualTo("D_01-A");
        assertThat(stored(me, "heard_from")).isNull();
    }

    @Test
    void badValuesAreRefused() throws Exception {
        UUID me = fx.user("Asha");
        patchMe(me, "{\"referredBy\":\"D 001\"}").andExpect(status().isBadRequest());
        patchMe(me, "{\"referredBy\":\"<script>\"}").andExpect(status().isBadRequest());
        patchMe(me, "{\"referredBy\":\"" + "A".repeat(33) + "\"}").andExpect(status().isBadRequest());
        patchMe(me, "{\"referredBy\":\"\"}").andExpect(status().isBadRequest());
        patchMe(me, "{\"heardFrom\":\"facebook\"}").andExpect(status().isBadRequest());
        assertThat(stored(me, "referred_by")).isNull();
        assertThat(stored(me, "heard_from")).isNull();
    }

    @Test
    void anOldAccountIsNotCreditedToADoctor() throws Exception {
        UUID me = fx.user("Asha");
        fx.jdbc().update("update profiles set created_at = now() - interval '30 days' where id = ?", me);
        patchMe(me, "{\"referredBy\":\"D001\",\"heardFrom\":\"friend_family\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referredBy", nullValue()))
                .andExpect(jsonPath("$.heardFrom", is("friend_family")));   // an answer is still welcome
        assertThat(stored(me, "referred_by")).isNull();
    }

    @Test
    void onlyAnAdminSeesTheCountsAndThereAreNoNames() throws Exception {
        UUID family = fx.user("Secret Caregiver");
        patchMe(family, "{\"referredBy\":\"ZT0091\",\"heardFrom\":\"doctor\"}").andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/referrals")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/referrals").header("Authorization", bearer(family))).andExpect(status().isForbidden());

        UUID admin = fx.user("Owner");
        fx.jdbc().update("update profiles set is_admin = true where id = ?", admin);
        String body = mvc.perform(get("/api/v1/admin/referrals").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode stats = json.readTree(body);
        JsonNode row = null;
        for (JsonNode r : stats.get("by_code")) if ("ZT0091".equals(r.get("code").asText())) row = r;
        assertThat(row).isNotNull();
        assertThat(row.get("signups").asInt()).isEqualTo(1);
        assertThat(row.get("with_person").asInt()).isZero();
        assertThat(stats.get("by_heard_from").isArray()).isTrue();
        assertThat(body).doesNotContain("Secret Caregiver", "@test.invalid", family.toString());
    }
}
