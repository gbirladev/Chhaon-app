package org.smartgurukul.chhaon.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Invite friends": a person's own referral code, choosing another, suggestions, and the sign-up check (migration 0061). */
class ReferralCodeApiTest extends DbTest {

    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private String bearer(UUID id) {
        return "Bearer " + jwt.issueToken(id, false);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private ResultActions mine(UUID id) throws Exception {
        return mvc.perform(get("/api/v1/me/referral").header("Authorization", bearer(id)));
    }

    private ResultActions change(UUID id, String code) throws Exception {
        return mvc.perform(put("/api/v1/me/referral").header("Authorization", bearer(id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"));
    }

    private ResultActions check(UUID id, String code) throws Exception {
        return mvc.perform(get("/api/v1/me/referral/check").param("code", code).header("Authorization", bearer(id)));
    }

    private ResultActions validate(UUID id, String code) throws Exception {
        return mvc.perform(get("/api/v1/referral/validate").param("code", code).header("Authorization", bearer(id)));
    }

    private ResultActions signUpWith(UUID id, String code) throws Exception {
        return mvc.perform(patch("/api/v1/me").header("Authorization", bearer(id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"referredBy\":\"" + code + "\"}"));
    }

    /** A code nobody else in the shared test database can have. */
    private static String unique(String prefix) {
        StringBuilder s = new StringBuilder(prefix);
        while (s.length() < 12) s.append((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
        return s.toString();
    }

    @Test
    void theFirstCodeIsMadeFromTheFirstNameAndStaysTheSame() throws Exception {
        UUID me = fx.user("Ananya Sharma");
        JsonNode first = body(mine(me).andExpect(status().isOk()));
        assertThat(first.get("installed").asBoolean()).isTrue();
        assertThat(first.get("code").asText()).matches("ANANYA[0-9]{2}");
        assertThat(first.get("link").asText()).isEqualTo("https://chhaon.site/?ref=" + first.get("code").asText());
        assertThat(first.get("families").asLong()).isZero();
        assertThat(first.get("changesLeft").asInt()).isEqualTo(3);
        assertThat(body(mine(me)).get("code").asText()).isEqualTo(first.get("code").asText());
    }

    @Test
    void aNameWithoutLatinLettersGetsFriend() throws Exception {
        UUID me = fx.user("अनन्या");
        assertThat(body(mine(me)).get("code").asText()).matches("FRIEND[0-9]{2}");
    }

    @Test
    void aTakenOrReservedCodeGetsThreeFreeSuggestions() throws Exception {
        UUID vishakha = fx.user("Vishakha Malik");
        String wanted = unique("FITV");
        change(vishakha, wanted).andExpect(status().isOk());

        UUID other = fx.user("Vishal Kumar");
        JsonNode taken = body(check(other, wanted.toLowerCase()).andExpect(status().isOk()));
        assertThat(taken.get("status").asText()).isEqualTo("taken");
        Set<String> seen = new HashSet<>();
        taken.get("suggestions").forEach(s -> seen.add(s.asText()));
        assertThat(seen).hasSize(3).doesNotContain(wanted);
        assertThat(seen).allMatch(s -> s.matches("[A-Z0-9]{4,15}") && !s.matches("[A-Z]{1,4}[0-9]{3,4}"));
        assertThat(seen).noneMatch(s -> s.startsWith("FITFIT") || s.startsWith("MYMY") || s.startsWith("TEAMTEAM"));
        for (String s : seen) assertThat(body(check(other, s)).get("status").asText()).isEqualTo("available");

        assertThat(body(check(other, "CHHAONFAN")).get("status").asText()).isEqualTo("reserved");
        assertThat(body(check(other, "Free")).get("status").asText()).isEqualTo("reserved");
        assertThat(body(check(other, "CRE022")).get("status").asText()).isEqualTo("format");
        assertThat(body(check(other, "ab")).get("status").asText()).isEqualTo("format");
        assertThat(body(check(vishakha, wanted)).get("status").asText()).isEqualTo("yours");
    }

    @Test
    void aChangeKeepsTheOldCodeCountingAndOnlyThreeChangesAreAllowed() throws Exception {
        UUID me = fx.user("Ravi Verma");
        String old = body(mine(me)).get("code").asText();
        String newer = unique("FITRAVI");
        JsonNode changed = body(change(me, newer).andExpect(status().isOk()));
        assertThat(changed.get("code").asText()).isEqualTo(newer);
        assertThat(changed.get("changesLeft").asInt()).isEqualTo(2);

        UUID friend1 = fx.user("Friend One");
        UUID friend2 = fx.user("Friend Two");
        signUpWith(friend1, old).andExpect(status().isOk());      // a link shared before the change
        signUpWith(friend2, newer).andExpect(status().isOk());
        assertThat(body(mine(me)).get("families").asLong()).isEqualTo(2);

        change(me, unique("RAVIB")).andExpect(status().isOk());
        change(me, unique("RAVIC")).andExpect(status().isOk());
        JsonNode refused = body(change(me, unique("RAVID")).andExpect(status().isTooManyRequests()));
        assertThat(refused.get("status").asText()).isEqualTo("too_many");

        change(me, old).andExpect(status().isOk());   // going back to an own old code is always fine
        assertThat(body(mine(me)).get("code").asText()).isEqualTo(old);
    }

    @Test
    void aTakenCodeCannotBeTakenAgain() throws Exception {
        UUID a = fx.user("Asha");
        UUID b = fx.user("Bina");
        String code = unique("TEAMA");
        change(a, code).andExpect(status().isOk());
        JsonNode r = body(change(b, code).andExpect(status().isConflict()));
        assertThat(r.get("status").asText()).isEqualTo("taken");
        assertThat(r.get("suggestions").size()).isEqualTo(3);
    }

    @Test
    void atSignUpAnUnknownOrOwnCodeIsRefusedAndCampaignCodesStillWork() throws Exception {
        UUID owner = fx.user("Meera Iyer");
        String code = body(mine(owner)).get("code").asText();

        UUID newcomer = fx.user("New Family");
        assertThat(body(validate(newcomer, "NOSUCHCODE42")).get("status").asText()).isEqualTo("unknown");
        assertThat(body(validate(newcomer, code.toLowerCase())).get("status").asText()).isEqualTo("ok");
        assertThat(body(validate(owner, code)).get("status").asText()).isEqualTo("own");
        assertThat(body(validate(newcomer, "CRE022")).get("status").asText()).isEqualTo("ok");
        assertThat(body(validate(newcomer, "<x>")).get("status").asText()).isEqualTo("unknown");

        signUpWith(newcomer, "NOSUCHCODE42").andExpect(status().isBadRequest());
        signUpWith(owner, code).andExpect(status().isBadRequest());
        signUpWith(newcomer, code).andExpect(status().isOk());
        assertThat(fx.jdbc().queryForObject("select referred_by from profiles where id = ?", String.class, newcomer)).isEqualTo(code);

        UUID creatorFan = fx.user("Fan");
        signUpWith(creatorFan, "CRE022").andExpect(status().isOk());   // a creator link from the emails
        assertThat(fx.jdbc().queryForObject("select referred_by from profiles where id = ?", String.class, creatorFan)).isEqualTo("CRE022");
    }

    @Test
    void theRoutesNeedALogin() throws Exception {
        mvc.perform(get("/api/v1/me/referral")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/referral/validate").param("code", "ABCD12")).andExpect(status().isUnauthorized());
    }
}
