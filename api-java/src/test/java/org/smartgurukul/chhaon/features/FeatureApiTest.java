package org.smartgurukul.chhaon.features;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Phase-2 pass-throughs AND the mechanism they stand on: {@code UserScopedSql.asUser} must reproduce
 * PostgREST's environment (auth.uid() + SET LOCAL ROLE authenticated), or every SQL function below refuses.
 */
class FeatureApiTest extends DbTest {

    @Autowired JwtService jwt;

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    /** plan_overview() reads `where p.id = auth.uid()`: a non-null answer proves the impersonation works. */
    @Test
    void planOverviewSeesTheCallerThroughAuthUid() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        mvc.perform(get("/api/v1/plans/overview").header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan", notNullValue()))
                .andExpect(jsonPath("$.parents_used", is(1)));
    }

    @Test
    void emergencyLinkEnableReadRevoke() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        String auth = bearer(f.userId());

        mvc.perform(get("/api/v1/recipients/{id}/emergency-link", f.recipientId()).header("Authorization", auth))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/recipients/{id}/emergency-link/enable", f.recipientId()).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", matchesPattern("[0-9a-f]{64}")));
        mvc.perform(get("/api/v1/recipients/{id}/emergency-link", f.recipientId()).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount", is(0)));
        mvc.perform(post("/api/v1/recipients/{id}/emergency-link/revoke", f.recipientId()).header("Authorization", auth))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/recipients/{id}/emergency-link", f.recipientId()).header("Authorization", auth))
                .andExpect(status().isNoContent());

        UUID stranger = fx.user("Stranger");
        mvc.perform(post("/api/v1/recipients/{id}/emergency-link/enable", f.recipientId()).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void inviteFlowCreatePreviewAcceptAndHints() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        UUID joiner = fx.user("Sister");

        MvcResult created = mvc.perform(post("/api/v1/circles/{id}/invites", f.circleId())
                        .header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk()).andReturn();
        Matcher m = Pattern.compile("\"token\":\"([0-9a-f]{64})\"").matcher(created.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        String token = m.group(1);

        mvc.perform(post("/api/v1/invites/preview").header("Authorization", bearer(joiner))
                        .contentType("application/json").content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")));

        mvc.perform(post("/api/v1/invites/accept").header("Authorization", bearer(joiner))
                        .contentType("application/json").content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientId", is(f.recipientId().toString())));

        mvc.perform(get("/api/v1/circles/{id}/members", f.circleId()).header("Authorization", bearer(joiner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // A used token refuses with the DB's own hint, which the web copy keys off.
        UUID third = fx.user("Third");
        mvc.perform(post("/api/v1/invites/accept").header("Authorization", bearer(third))
                        .contentType("application/json").content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", is("used")));

        // The joiner can leave again.
        mvc.perform(post("/api/v1/circles/{id}/leave", f.circleId()).header("Authorization", bearer(joiner)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/circles/{id}/members", f.circleId()).header("Authorization", bearer(f.userId())))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void appointmentsCrudStampsTheAuthorAndEnforcesMembership() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        String auth = bearer(f.userId());

        MvcResult created = mvc.perform(post("/api/v1/recipients/{id}/appointments", f.recipientId())
                        .header("Authorization", auth).contentType("application/json")
                        .content("{\"doctor\":\"Dr. Mehta\",\"at\":\"2027-01-05T10:30:00Z\",\"location\":\"Clinic\",\"notes\":null,\"remind\":true}"))
                .andExpect(status().isOk()).andReturn();
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(created.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        String id = m.group(1);

        // The stamp_author trigger ran as 'authenticated': created_by is the caller.
        String createdBy = fx.jdbc().queryForObject("select created_by::text from appointments where id = ?::uuid", String.class, id);
        assertThat(createdBy).isEqualTo(f.userId().toString());

        mvc.perform(get("/api/v1/recipients/{id}/appointments", f.recipientId()).header("Authorization", auth))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].doctor", is("Dr. Mehta")));

        mvc.perform(put("/api/v1/appointments/{id}", id).header("Authorization", auth).contentType("application/json")
                        .content("{\"doctor\":\"Dr. Shah\",\"at\":\"2027-01-06T09:00:00Z\",\"location\":null,\"notes\":\"fasting\",\"remind\":false}"))
                .andExpect(status().isOk());

        UUID stranger = fx.user("Stranger");
        mvc.perform(delete("/api/v1/appointments/{id}", id).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());   // RLS hides it: 0 rows deleted
        mvc.perform(delete("/api/v1/appointments/{id}", id).header("Authorization", auth))
                .andExpect(status().isNoContent());
    }

    @Test
    void escalationPolicyUpsertAndCriticalFlag() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        Fixtures.Med med = fx.medicine(f, "Insulin", "10 units", 10, LocalTime.of(8, 0));
        String auth = bearer(f.userId());

        mvc.perform(get("/api/v1/recipients/{id}/escalation-policy", f.recipientId()).header("Authorization", auth))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/recipients/{id}/escalation-policy", f.recipientId()).header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"doseNudgeMin\":15,\"doseAlertMin\":45,\"checkinNudgeMin\":60,\"checkinAlertMin\":120,\"alertAfterUnanswered\":2,\"silentStopDays\":3}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/recipients/{id}/escalation-policy", f.recipientId()).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.doseAlertMin", is(45)));

        mvc.perform(post("/api/v1/medicines/{id}/critical", med.medicineId()).header("Authorization", auth)
                        .contentType("application/json").content("{\"critical\":true}"))
                .andExpect(status().isOk());
        Boolean critical = fx.jdbc().queryForObject("select critical from medicines where id = ?", Boolean.class, med.medicineId());
        assertThat(critical).isTrue();
    }

    @Test
    void feedbackInsertWorksAsTheUser() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        mvc.perform(post("/api/v1/feedback").header("Authorization", bearer(f.userId()))
                        .contentType("application/json")
                        .content("{\"kind\":\"idea\",\"message\":\"More layouts please\",\"rating\":5,\"screen\":\"today\",\"appLanguage\":\"en\",\"images\":[]}"))
                .andExpect(status().isOk());
        Integer n = fx.jdbc().queryForObject("select count(*) from feedback where user_id = ?", Integer.class, f.userId());
        assertThat(n).isEqualTo(1);
        fx.jdbc().update("delete from feedback where user_id = ?", f.userId());
    }

    /** "Report a problem": the topic is stored, the context keeps only allowed short codes and the caller's own person, polls added. */
    @Test
    void problemReportKeepsOnlyTheAllowedContext() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        Fixtures.Family other = fx.family("en", true);
        String body = "{\"kind\":\"problem\",\"message\":\"No message came\",\"screen\":\"today\",\"appLanguage\":\"hi\",\"images\":[]," +
                "\"topic\":\"no_message\",\"context\":{\"app_build\":\"abc1234\",\"browser\":\"Chrome\",\"os\":\"Android\",\"agreed\":\"no\"," +
                "\"reminders_to\":\"person\",\"last_reminder_at\":\"2026-11-08T02:30:00Z\",\"last_reminder_status\":\"sent\"," +
                "\"recipient_id\":\"" + f.recipientId() + "\",\"phone\":\"+919999900001\",\"medicine\":\"Metformin\"," +
                "\"screen\":\"Rajesh Kumar\",\"language\":\"9999900001\",\"polls\":\"maybe\"}}";
        mvc.perform(post("/api/v1/feedback").header("Authorization", bearer(f.userId())).contentType("application/json").content(body))
                .andExpect(status().isOk());
        var row = fx.jdbc().queryForMap("select topic, context::text ctx, status from feedback where user_id = ?", f.userId());
        assertThat(row.get("topic")).isEqualTo("no_message");
        assertThat(row.get("status")).isEqualTo("new");
        String ctx = (String) row.get("ctx");
        assertThat(ctx).contains("\"browser\": \"Chrome\"", "\"agreed\": \"no\"", "\"polls\": false", f.recipientId().toString());
        assertThat(ctx).doesNotContain("phone", "Metformin", "Rajesh", "9999900001", "medicine", "maybe");

        // someone else's person is dropped from the context; an unknown topic is refused
        String foreign = "{\"kind\":\"problem\",\"message\":\"x\",\"topic\":\"reminder\",\"context\":{\"recipient_id\":\"" + other.recipientId() + "\"}}";
        mvc.perform(post("/api/v1/feedback").header("Authorization", bearer(f.userId())).contentType("application/json").content(foreign))
                .andExpect(status().isOk());
        String second = fx.jdbc().queryForObject("select context::text from feedback where user_id = ? and topic = 'reminder'", String.class, f.userId());
        assertThat(second).doesNotContain(other.recipientId().toString());
        mvc.perform(post("/api/v1/feedback").header("Authorization", bearer(f.userId())).contentType("application/json")
                        .content("{\"kind\":\"problem\",\"message\":\"x\",\"topic\":\"printer_on_fire\"}"))
                .andExpect(status().isBadRequest());

        // an idea never carries a topic or context
        mvc.perform(post("/api/v1/feedback").header("Authorization", bearer(f.userId())).contentType("application/json")
                        .content("{\"kind\":\"idea\",\"message\":\"Nice\",\"topic\":\"login\",\"context\":{\"os\":\"iOS\"}}"))
                .andExpect(status().isOk());
        var idea = fx.jdbc().queryForMap("select topic, context from feedback where user_id = ? and kind = 'idea'", f.userId());
        assertThat(idea.get("topic")).isNull();
        assertThat(idea.get("context")).isNull();
        fx.jdbc().update("delete from feedback where user_id in (?, ?)", f.userId(), other.userId());
    }
}
