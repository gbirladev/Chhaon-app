package org.smartgurukul.chhaon.data;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The read endpoints that replaced the web app's direct Supabase queries (members, notifications, today, activity, names, /me). */
class DataApiTest extends DbTest {

    @Autowired JwtService jwt;

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    @Test
    void membersAreVisibleToCircleMembersOnly() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        UUID stranger = fx.user("Stranger");

        mvc.perform(get("/api/v1/circles/{id}/members", f.circleId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].userId", is(f.userId().toString())))
                .andExpect(jsonPath("$[0].role", is("owner")));

        mvc.perform(get("/api/v1/circles/{id}/members", f.circleId()).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void notificationsListMarkReadAndDeleteAreScopedToTheCaller() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        UUID stranger = fx.user("Stranger");
        UUID id = UUID.randomUUID();
        fx.jdbc().update("insert into notifications (id, user_id, type, payload) values (?, ?, 'missed_dose', '{\"x\":1}'::jsonb)",
                id, f.userId());

        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type", is("missed_dose")))
                .andExpect(jsonPath("$[0].payload.x", is(1)))
                .andExpect(jsonPath("$[0].readAt", nullValue()));

        // A stranger sees nothing and cannot delete someone else's notification.
        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(stranger)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(delete("/api/v1/notifications/{id}", id).header("Authorization", bearer(stranger)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(f.userId())))
                .andExpect(jsonPath("$", hasSize(1)));

        mvc.perform(post("/api/v1/notifications/read").header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content("{\"ids\":[\"" + id + "\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(f.userId())))
                .andExpect(jsonPath("$[0].readAt", notNullValue()));

        mvc.perform(delete("/api/v1/notifications/{id}", id).header("Authorization", bearer(f.userId())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(f.userId())))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void todayReturnsDosesAndTheLocalCheckin() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        Fixtures.Med med = fx.medicine(f, "Metformin", "500 mg", 10, LocalTime.of(8, 0));
        fx.dose(med.scheduleId(), Instant.now(), "sent", Instant.now(), Fixtures.messageId());
        fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", Instant.now());

        mvc.perform(get("/api/v1/recipients/{id}/today", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doses", hasSize(1)))
                .andExpect(jsonPath("$.doses[0].name", is("Metformin")))
                .andExpect(jsonPath("$.doses[0].status", is("sent")))
                .andExpect(jsonPath("$.checkin.status", is("sent")));

        UUID stranger = fx.user("Stranger");
        mvc.perform(get("/api/v1/recipients/{id}/today", f.recipientId()).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void activityReturnsWhatWasSent() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        Fixtures.Med med = fx.medicine(f, "Amlodipine", "5 mg", 10, LocalTime.of(8, 0));
        fx.dose(med.scheduleId(), Instant.now().minusSeconds(3600), "taken", Instant.now().minusSeconds(3600), Fixtures.messageId());
        fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "replied", Instant.now().minusSeconds(1800));

        mvc.perform(get("/api/v1/recipients/{id}/activity?days=3", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doses", hasSize(1)))
                .andExpect(jsonPath("$.doses[0].name", is("Amlodipine")))
                .andExpect(jsonPath("$.checkins", hasSize(1)));
    }

    @Test
    void medicineNamesCoverEveryParentOfTheCaller() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        fx.medicine(f, "Telmisartan", "40 mg", 10, LocalTime.of(9, 0));

        mvc.perform(get("/api/v1/medicine-names").header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Telmisartan")));
    }

    @Test
    void meReadsAndUpdatesTheOwnProfile() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        fx.jdbc().update("update profiles set alert_consent_at = now() where id = ?", f.userId());

        mvc.perform(get("/api/v1/me").header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(f.userId().toString())))
                .andExpect(jsonPath("$.isAdmin", is(false)))
                .andExpect(jsonPath("$.alertConsentAt", notNullValue()));

        mvc.perform(patch("/api/v1/me").header("Authorization", bearer(f.userId()))
                        .contentType("application/json")
                        .content("{\"todayLayout\":\"route\",\"acceptPrivacy\":true,\"policyVersion\":\"v1\",\"alertsOff\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todayLayout", is("route")))
                .andExpect(jsonPath("$.policyVersion", is("v1")))
                .andExpect(jsonPath("$.privacyAcceptedAt", notNullValue()))
                .andExpect(jsonPath("$.alertConsentAt", nullValue()));
    }
}
