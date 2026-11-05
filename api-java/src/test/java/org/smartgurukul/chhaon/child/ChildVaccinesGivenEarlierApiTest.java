package org.smartgurukul.chhaon.child;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Which ones were already given?" (migration 0046): a child set up at 5 months has many doses whose date has passed. They are not called
 * overdue until the family says "not given yet"; the family marks the ones on the vaccination card given in one call (date not recorded).
 */
class ChildVaccinesGivenEarlierApiTest extends DbTest {

    @Autowired JwtService jwt;

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    /** A five-month-old with a guardian-confirmed plan. */
    private Fixtures.Family fiveMonthOld() throws Exception {
        Fixtures.Family f = fx.family("en", false);
        fx.jdbc().update("update care_recipients set care_type = 'child' where id = ?", f.recipientId());
        mvc.perform(put("/api/v1/recipients/{id}/child", f.recipientId()).header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content("{\"dob\":\"" + today().minusMonths(5) + "\",\"guardianConfirmed\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/recipients/{id}/vaccines/plan", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk());
        return f;
    }

    private Map<String, Object> dose(Fixtures.Family f, String key) {
        return fx.jdbc().queryForMap("select * from child_vaccines where recipient_id = ? and schedule_key = ?", f.recipientId(), key);
    }

    private String id(Fixtures.Family f, String key) {
        return dose(f, key).get("id").toString();
    }

    private static String ids(List<String> ids) {
        return ids.stream().map(i -> "\"" + i + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    private void givenEarlier(Fixtures.Family f, UUID userId, List<String> given, List<String> notYet, int expect) throws Exception {
        mvc.perform(post("/api/v1/recipients/{id}/vaccines/given-earlier", f.recipientId()).header("Authorization", bearer(userId))
                        .contentType("application/json").content("{\"given\":" + ids(given) + ",\"notGivenYet\":" + ids(notYet) + "}"))
                .andExpect(status().is(expect));
    }

    @Test
    void pastDosesAreAskedAboutNotCalledOverdueAndAreMarkedGivenInOneCall() throws Exception {
        Fixtures.Family f = fiveMonthOld();
        // Right after the plan: BCG (birth) is past its date, but the family has not said anything yet -> asked, not "overdue".
        mvc.perform(get("/api/v1/recipients/{id}/vaccines", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key", is("bcg")))
                .andExpect(jsonPath("$[0].askIfGiven", is(true)))
                .andExpect(jsonPath("$[0].givenDateUnknown", is(false)));

        List<String> given = List.of(id(f, "bcg"), id(f, "opv-0"), id(f, "hepb-1"), id(f, "dtp-1"));
        List<String> notYet = List.of(id(f, "dtp-2"));
        givenEarlier(f, f.userId(), given, notYet, 200);
        givenEarlier(f, f.userId(), given, notYet, 200);   // idempotent

        Map<String, Object> bcg = dose(f, "bcg");
        assertThat(bcg.get("given_on").toString()).isEqualTo(bcg.get("due_date").toString());   // placeholder, flagged
        assertThat(bcg.get("given_date_unknown")).isEqualTo(true);
        assertThat(dose(f, "dtp-2").get("given_on")).isNull();
        assertThat(dose(f, "dtp-2").get("not_yet_given_at")).isNotNull();
        assertThat(dose(f, "ipv-2").get("not_yet_given_at")).isNull();   // left alone ("Not sure")

        String body = mvc.perform(get("/api/v1/recipients/{id}/vaccines", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"givenDateUnknown\":true");
        mvc.perform(get("/api/v1/recipients/{id}/vaccines", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(jsonPath("$[?(@.key == 'bcg')].status", contains("given")))
                .andExpect(jsonPath("$[?(@.key == 'dtp-2')].status", contains("overdue")))
                .andExpect(jsonPath("$[?(@.key == 'dtp-2')].askIfGiven", contains(false)))
                .andExpect(jsonPath("$[?(@.key == 'ipv-2')].askIfGiven", contains(true)));

        // Adding the real date later clears the flag; a later bulk call never overwrites it.
        mvc.perform(put("/api/v1/vaccines/{id}", id(f, "bcg")).header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content("{\"status\":\"given\",\"givenOn\":\"" + today().minusMonths(5) + "\"}"))
                .andExpect(status().isOk());
        givenEarlier(f, f.userId(), List.of(id(f, "bcg")), List.of(), 200);
        assertThat(dose(f, "bcg").get("given_date_unknown")).isEqualTo(false);
        assertThat(dose(f, "bcg").get("given_on").toString()).isEqualTo(today().minusMonths(5).toString());

        // "Mark as not done yet" on a past dose is the family saying "not given yet".
        mvc.perform(put("/api/v1/vaccines/{id}", id(f, "dtp-1")).header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content("{\"status\":\"due\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("overdue")))
                .andExpect(jsonPath("$.askIfGiven", is(false)))
                .andExpect(jsonPath("$.givenDateUnknown", is(false)));
    }

    @Test
    void onlyPastDosesOfThisChildAndOnlyTheFamilyWithTheGuardianConfirmation() throws Exception {
        Fixtures.Family f = fiveMonthOld();
        Fixtures.Family other = fiveMonthOld();
        UUID stranger = fx.user("Stranger");
        UUID aunt = fx.member(f, "Aunt");
        String bcg = id(f, "bcg");

        givenEarlier(f, stranger, List.of(bcg), List.of(), 404);
        givenEarlier(f, f.userId(), List.of(id(other, "bcg")), List.of(), 400);   // another child's dose
        givenEarlier(f, f.userId(), List.of(id(f, "mmr-1")), List.of(), 400);     // date not passed yet
        givenEarlier(f, f.userId(), List.of(bcg), List.of(bcg), 400);             // both at once
        assertThat(dose(f, "bcg").get("given_on")).isNull();
        assertThat(dose(other, "bcg").get("given_on")).isNull();

        givenEarlier(f, aunt, List.of(bcg), List.of(), 200);   // any member of the family
        assertThat(dose(f, "bcg").get("given_date_unknown")).isEqualTo(true);

        // Without the guardian confirmation nothing is written.
        fx.jdbc().update("update care_recipients set guardian_confirmed_at = null where id = ?", f.recipientId());
        givenEarlier(f, f.userId(), List.of(id(f, "opv-0")), List.of(), 409);
        assertThat(dose(f, "opv-0").get("given_on")).isNull();

        // Not a child profile.
        Fixtures.Family self = fx.selfFamily(fx.track(Fixtures.randomPhone()), "en", false);
        givenEarlier(self, self.userId(), List.of(), List.of(), 409);
    }
}
