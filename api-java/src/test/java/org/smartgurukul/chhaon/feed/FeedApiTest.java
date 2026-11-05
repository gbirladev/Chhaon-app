package org.smartgurukul.chhaon.feed;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The family activity feed (feature 4): across all of the caller's people, scoped, paginated, no phone numbers, and the ♥. */
class FeedApiTest extends DbTest {

    @Autowired JwtService jwt;

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    private void taken(UUID doseId, Instant at) {
        fx.jdbc().update("update dose_events set status = 'taken', responded_at = ? where id = ?", Timestamp.from(at), doseId);
    }

    @Test
    void feedCoversEveryPersonNewestFirstWithWhoDidIt() throws Exception {
        Fixtures.Family papa = fx.family("en", true);
        Fixtures.Family nani = fx.addRecipient(papa, "Kamla", Fixtures.randomPhone(), "hi", true);
        fx.jdbc().update("update care_recipients set reminder_to = 'helper', reminder_name = 'Sunita', reminder_relation = 'maid' where id = ?",
                nani.recipientId());

        Fixtures.Med met = fx.medicine(papa, "Metformin", "500 mg", 10, LocalTime.of(8, 0));
        UUID d1 = fx.dose(met.scheduleId(), Instant.now().minusSeconds(7200), "sent", Instant.now().minusSeconds(7200), Fixtures.messageId());
        taken(d1, Instant.now().minusSeconds(7000));
        Fixtures.Med iron = fx.medicine(nani, "Iron", "1 tab", 10, LocalTime.of(9, 0));
        UUID d2 = fx.dose(iron.scheduleId(), Instant.now().minusSeconds(3600), "sent", Instant.now().minusSeconds(3600), Fixtures.messageId());
        taken(d2, Instant.now().minusSeconds(3500));
        fx.dose(iron.scheduleId(), Instant.now().minusSeconds(86_400 * 2), "missed", Instant.now().minusSeconds(86_400 * 2), Fixtures.messageId());
        // older than 7 days: not in the feed
        UUID old = fx.dose(met.scheduleId(), Instant.now().minusSeconds(86_400 * 9), "sent", Instant.now().minusSeconds(86_400 * 9), Fixtures.messageId());
        taken(old, Instant.now().minusSeconds(86_400 * 9));
        // set-up rows (medicines added, consent given) happened "last week", so the newest items are the doses and the check-in
        fx.jdbc().update("update medicines set created_at = now() - interval '8 days' where recipient_id in (?, ?)", papa.recipientId(), nani.recipientId());
        fx.jdbc().update("update care_recipients set consent_at = now() - interval '8 days' where id in (?, ?)", papa.recipientId(), nani.recipientId());
        UUID c = fx.checkin(papa.recipientId(), LocalDate.now(papa.zone()), "sent", Instant.now().minusSeconds(1800));
        fx.jdbc().update("update checkins set status = 'replied', responded_at = now() - interval '20 minutes' where id = ?", c);

        String body = mvc.perform(get("/api/v1/activity").header("Authorization", bearer(papa.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(2)))
                .andExpect(jsonPath("$.people[*].call", hasItem("Papa")))
                .andExpect(jsonPath("$.items[0].kind", is("checkin_answered")))
                .andExpect(jsonPath("$.items[0].answer", is("fine")))
                .andExpect(jsonPath("$.items[0].actor.kind", is("person")))
                .andExpect(jsonPath("$.items[?(@.key == 'dose:" + d2 + "')].actor.kind", hasItem("helper")))
                .andExpect(jsonPath("$.items[?(@.key == 'dose:" + d2 + "')].actor.name", hasItem("Sunita")))
                .andExpect(jsonPath("$.items[?(@.key == 'dose:" + d1 + "')].title", hasItem("Metformin")))
                .andExpect(jsonPath("$.items[?(@.kind == 'dose_missed')].title", hasItem("Iron")))
                .andExpect(jsonPath("$.items[?(@.key == 'dose:" + old + "')]", hasSize(0)))
                .andReturn().getResponse().getContentAsString();
        // never a phone number (the receiver's or the person's)
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString(papa.phone().substring(1))));
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString(nani.phone().substring(1))));

        // one person only (the filter chip)
        mvc.perform(get("/api/v1/activity").param("recipient", nani.recipientId().toString()).header("Authorization", bearer(papa.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].recipientId", not(hasItem(papa.recipientId().toString()))))
                .andExpect(jsonPath("$.items[*].kind", hasItem("dose_taken")));
    }

    @Test
    void confirmedByAFamilyMemberAndAppointmentsNameWhoDidIt() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        UUID sister = fx.member(f, "Riya Sharma");
        Fixtures.Med med = fx.medicine(f, "Amlodipine", "5 mg", 10, LocalTime.of(8, 0));
        UUID d = fx.dose(med.scheduleId(), Instant.now().minusSeconds(3600), "sent", Instant.now().minusSeconds(3600), Fixtures.messageId());
        taken(d, Instant.now().minusSeconds(3000));
        fx.jdbc().update("insert into appointments (recipient_id, doctor_name, datetime, created_by) values (?, 'Dr. Mehta', now() + interval '3 days', ?)",
                f.recipientId(), sister);
        // "2 = call me": the answer category comes from the call-request notification, never the raw text
        UUID c = fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", Instant.now().minusSeconds(1800));
        fx.jdbc().update("update checkins set status = 'replied', responded_at = now() - interval '10 minutes' where id = ?", c);
        fx.jdbc().update("insert into notifications (user_id, type, payload, created_at) values (?, 'checkin_call_requested', " +
                "jsonb_build_object('recipient_id', ?::text), now() - interval '10 minutes')", f.userId(), f.recipientId().toString());
        boolean hasConfirmedBy = Boolean.TRUE.equals(fx.jdbc().queryForObject(
                "select exists (select 1 from information_schema.columns where table_name = 'dose_events' and column_name = 'confirmed_by')",
                Boolean.class));
        if (hasConfirmedBy) {
            fx.jdbc().update("update dose_events set confirmed_by = ? where id = ?", sister, d);
        }

        mvc.perform(get("/api/v1/activity").header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.kind == 'appointment_added')].actor.name", hasItem("Riya")))
                .andExpect(jsonPath("$.items[?(@.kind == 'medicine_added')].title", hasItem("Amlodipine")))
                .andExpect(jsonPath("$.items[?(@.key == 'checkin:" + c + "')].answer", hasItem("call")))
                .andExpect(jsonPath("$.items[?(@.key == 'dose:" + d + "')].actor.kind", hasItem(hasConfirmedBy ? "member" : "person")));
        // the sister sees herself as "me"
        mvc.perform(get("/api/v1/activity").header("Authorization", bearer(sister)))
                .andExpect(jsonPath("$.items[?(@.kind == 'appointment_added')].actor.kind", hasItem("me")));
    }

    @Test
    void strangersSeeNothingAndCannotHeart() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        Fixtures.Med med = fx.medicine(f, "Metformin", "500 mg", 10, LocalTime.of(8, 0));
        UUID d = fx.dose(med.scheduleId(), Instant.now().minusSeconds(3600), "missed", Instant.now().minusSeconds(3600), Fixtures.messageId());
        UUID stranger = fx.user("Stranger");

        mvc.perform(get("/api/v1/activity").header("Authorization", bearer(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(0)))
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/v1/activity").param("recipient", f.recipientId().toString()).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/activity/hearts").header("Authorization", bearer(stranger)).contentType("application/json")
                        .content("{\"recipientId\":\"" + f.recipientId() + "\",\"itemKey\":\"dose:" + d + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/activity")).andExpect(status().isUnauthorized());
    }

    @Test
    void heartsCountPerItemAndCanBeTakenBack() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        UUID sister = fx.member(f, "Riya");
        Fixtures.Med med = fx.medicine(f, "Metformin", "500 mg", 10, LocalTime.of(8, 0));
        UUID d = fx.dose(med.scheduleId(), Instant.now().minusSeconds(3600), "sent", Instant.now().minusSeconds(3600), Fixtures.messageId());
        taken(d, Instant.now().minusSeconds(3000));
        String heart = "{\"recipientId\":\"" + f.recipientId() + "\",\"itemKey\":\"dose:" + d + "\"}";

        mvc.perform(put("/api/v1/activity/hearts").header("Authorization", bearer(f.userId())).contentType("application/json").content(heart))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hearts", is(1))).andExpect(jsonPath("$.heartedByMe", is(true)));
        // a double tap changes nothing
        mvc.perform(put("/api/v1/activity/hearts").header("Authorization", bearer(f.userId())).contentType("application/json").content(heart))
                .andExpect(jsonPath("$.hearts", is(1)));
        mvc.perform(put("/api/v1/activity/hearts").header("Authorization", bearer(sister)).contentType("application/json").content(heart))
                .andExpect(jsonPath("$.hearts", is(2)));

        mvc.perform(get("/api/v1/activity").header("Authorization", bearer(sister)))
                .andExpect(jsonPath("$.items[?(@.key == 'dose:" + d + "')].hearts", hasItem(2)))
                .andExpect(jsonPath("$.items[?(@.key == 'dose:" + d + "')].heartedByMe", hasItem(true)));

        mvc.perform(put("/api/v1/activity/hearts").header("Authorization", bearer(f.userId())).contentType("application/json")
                        .content("{\"recipientId\":\"" + f.recipientId() + "\",\"itemKey\":\"dose:" + d + "\",\"on\":false}"))
                .andExpect(jsonPath("$.hearts", is(1))).andExpect(jsonPath("$.heartedByMe", is(false)));

        mvc.perform(put("/api/v1/activity/hearts").header("Authorization", bearer(f.userId())).contentType("application/json")
                        .content("{\"recipientId\":\"" + f.recipientId() + "\",\"itemKey\":\"drop table\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pagesFollowTheCursorWithoutRepeats() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        Fixtures.Med med = fx.medicine(f, "Metformin", "500 mg", 10, LocalTime.of(8, 0));
        for (int i = 1; i <= 5; i++) {
            UUID d = fx.dose(med.scheduleId(), Instant.now().minusSeconds(3600L * i), "sent", Instant.now().minusSeconds(3600L * i), Fixtures.messageId());
            taken(d, Instant.now().minusSeconds(3600L * i - 60));
        }
        int total = com.jayway.jsonpath.JsonPath.<Integer>read(mvc.perform(get("/api/v1/activity").param("limit", "100")
                .header("Authorization", bearer(f.userId()))).andReturn().getResponse().getContentAsString(), "$.items.length()");
        org.junit.jupiter.api.Assertions.assertTrue(total >= 6, "5 doses + medicine added");
        MvcResult first = mvc.perform(get("/api/v1/activity").param("limit", "4").header("Authorization", bearer(f.userId())))
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.next", notNullValue()))
                .andReturn();
        String next = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.next");
        String lastKey = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.items[3].key");
        mvc.perform(get("/api/v1/activity").param("limit", "4").param("cursor", next).header("Authorization", bearer(f.userId())))
                .andExpect(jsonPath("$.items", hasSize(total - 4)))
                .andExpect(jsonPath("$.items[*].key", not(hasItem(lastKey))))
                .andExpect(jsonPath("$.next", nullValue()));
        mvc.perform(get("/api/v1/activity").param("cursor", "garbage").header("Authorization", bearer(f.userId())))
                .andExpect(status().isBadRequest());
    }
}
