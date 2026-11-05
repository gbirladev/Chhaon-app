package org.smartgurukul.chhaon.privacy;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Download my data", "Delete my account" and "Delete this parent's data" (port of api/tests/test_privacy.py). */
class PrivacyApiTest extends DbTest {

    @Autowired JwtService jwt;

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    private int count(String sql, Object... args) {
        Integer n = fx.jdbc().queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private void addMember(UUID circleId, UUID userId, String role) {
        fx.jdbc().update("insert into care_circle_members (circle_id, user_id, role) values (?, ?, cast(? as member_role))",
                circleId, userId, role);
    }

    private String role(UUID circleId, UUID userId) {
        return fx.jdbc().queryForObject("select role::text from care_circle_members where circle_id = ? and user_id = ?",
                String.class, circleId, userId);
    }

    private org.springframework.test.web.servlet.ResultActions deleteAccount(UUID userId, String body) throws Exception {
        var req = post("/privacy/delete-account").header("Authorization", bearer(userId));
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    @Test
    void everyEndpointNeedsALogin() throws Exception {
        mvc.perform(get("/privacy/export")).andExpect(status().isUnauthorized());
        mvc.perform(post("/privacy/delete-account").contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\"DELETE\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/privacy/recipients/{id}/delete", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void exportHasOnlyMyDataAndNoSecrets() throws Exception {
        Fixtures.Family mine = fx.family("en", true);
        Fixtures.Med med = fx.medicine(mine, "Metformin", "500 mg", 20, LocalTime.of(9, 0));
        fx.dose(med.scheduleId(), Instant.now().minusSeconds(3600), "sent", Instant.now().minusSeconds(3600), Fixtures.messageId());
        String secret = fx.jdbc().queryForObject("insert into emergency_links (recipient_id) values (?) returning token",
                String.class, mine.recipientId());
        Fixtures.Family other = fx.family("en", true);
        fx.medicine(other, "OtherPill", "1 mg", 5, LocalTime.of(9, 0));

        String text = mvc.perform(get("/privacy/export").header("Authorization", bearer(mine.userId())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.profile.id", is(mine.userId().toString())))
                .andExpect(jsonPath("$.parents", hasSize(1)))
                .andExpect(jsonPath("$.parents[0].id", is(mine.recipientId().toString())))
                .andExpect(jsonPath("$.medicines", hasSize(1)))
                .andExpect(jsonPath("$.medicines[0].name", is("Metformin")))
                .andExpect(jsonPath("$.dose_events", hasSize(1)))
                .andExpect(jsonPath("$.circle_members", hasSize(1)))
                .andExpect(jsonPath("$.circle_members[0].is_you", is(true)))
                .andExpect(jsonPath("$.emergency_links", hasSize(1)))
                .andReturn().getResponse().getContentAsString();

        assertThat(text).doesNotContain(other.recipientId().toString(), other.userId().toString(), "OtherPill", other.phone());
        assertThat(text).doesNotContain("whatsapp_message_id", "\"token\"", secret);
    }

    @Test
    void deleteAccountRefusesWithoutTheConfirmWord() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        deleteAccount(f.userId(), null).andExpect(status().isUnprocessableEntity());
        deleteAccount(f.userId(), "{}").andExpect(status().isUnprocessableEntity());
        deleteAccount(f.userId(), "{\"confirm\":\"yes\"}").andExpect(status().isUnprocessableEntity());
        assertThat(count("select count(*) from profiles where id = ?", f.userId())).isEqualTo(1);
        assertThat(count("select count(*) from care_recipients where id = ?", f.recipientId())).isEqualTo(1);
    }

    @Test
    void deleteAccountErasesMyFamilyAndLoginButNotOthers() throws Exception {
        Fixtures.Family mine = fx.family("en", true);
        Fixtures.Med med = fx.medicine(mine, "Metformin", "500 mg", 20, LocalTime.of(9, 0));
        String myPhone = Fixtures.randomPhone();
        fx.jdbc().update("update profiles set phone = ? where id = ?", myPhone, mine.userId());
        fx.jdbc().update("insert into user_credentials (user_id, password_hash) values (?, 'x')", mine.userId());
        fx.jdbc().update("insert into otp_codes (phone, code_hash, expires_at) values (?, 'x', now() + interval '5 minutes')", myPhone);
        fx.jdbc().update("insert into notifications (user_id, type, payload) values (?, 'dose_missed', '{}'::jsonb)", mine.userId());
        Fixtures.Family other = fx.family("en", true);
        Fixtures.Med otherMed = fx.medicine(other, "OtherPill", "1 mg", 5, LocalTime.of(9, 0));

        deleteAccount(mine.userId(), "{\"confirm\":\" delete \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("deleted")));

        assertThat(count("select count(*) from profiles where id = ?", mine.userId())).isZero();
        assertThat(count("select count(*) from user_credentials where user_id = ?", mine.userId())).isZero();
        assertThat(count("select count(*) from otp_codes where phone = ?", myPhone)).isZero();
        assertThat(count("select count(*) from notifications where user_id = ?", mine.userId())).isZero();
        assertThat(count("select count(*) from care_circles where id = ?", mine.circleId())).isZero();
        assertThat(count("select count(*) from care_recipients where id = ?", mine.recipientId())).isZero();
        assertThat(count("select count(*) from medicines where id = ?", med.medicineId())).isZero();

        assertThat(count("select count(*) from profiles where id = ?", other.userId())).isEqualTo(1);
        assertThat(count("select count(*) from care_recipients where id = ?", other.recipientId())).isEqualTo(1);
        assertThat(count("select count(*) from medicines where id = ?", otherMed.medicineId())).isEqualTo(1);

        // Repeating is harmless (the token outlives the profile until it expires).
        deleteAccount(mine.userId(), "{\"confirm\":\"DELETE\"}").andExpect(status().isOk());
    }

    @Test
    void deleteAccountInASharedFamilyLeavesItAndHandsOverOwnership() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        UUID sister = fx.user("Sister");
        addMember(f.circleId(), sister, "member");

        deleteAccount(f.userId(), "{\"confirm\":\"DELETE\"}").andExpect(status().isOk());

        assertThat(count("select count(*) from care_circles where id = ?", f.circleId())).isEqualTo(1);
        assertThat(count("select count(*) from care_recipients where id = ?", f.recipientId())).isEqualTo(1);
        assertThat(count("select count(*) from care_circle_members where circle_id = ? and user_id = ?", f.circleId(), f.userId())).isZero();
        assertThat(role(f.circleId(), sister)).isEqualTo("owner");
    }

    @Test
    void onlyTheOwnerCanDeleteAParent() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        Fixtures.Med med = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(9, 0));
        UUID sister = fx.user("Sister");
        addMember(f.circleId(), sister, "member");
        UUID stranger = fx.user("Stranger");

        mvc.perform(post("/privacy/recipients/{id}/delete", f.recipientId()).header("Authorization", bearer(sister)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/privacy/recipients/{id}/delete", f.recipientId()).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/privacy/recipients/{id}/delete", UUID.randomUUID()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isNotFound());
        assertThat(count("select count(*) from care_recipients where id = ?", f.recipientId())).isEqualTo(1);

        mvc.perform(post("/privacy/recipients/{id}/delete", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("deleted")));
        assertThat(count("select count(*) from care_recipients where id = ?", f.recipientId())).isZero();
        assertThat(count("select count(*) from medicines where id = ?", med.medicineId())).isZero();
        assertThat(count("select count(*) from medicine_schedules where id = ?", med.scheduleId())).isZero();
        // The family itself and both caregivers stay.
        assertThat(count("select count(*) from care_circle_members where circle_id = ?", f.circleId())).isEqualTo(2);
    }
}
