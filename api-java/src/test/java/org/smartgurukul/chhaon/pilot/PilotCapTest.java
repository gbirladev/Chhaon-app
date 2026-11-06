package org.smartgurukul.chhaon.pilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The pilot cap (docs/pilot-cap.md, migration 20261107000060) through the routes the web app calls: a full pilot makes the
 * consent request wait with a place in line, the line moves when a seat frees, and the owner sees and reorders the line.
 * The limits are set relative to the seats already in use, and the settings row is removed afterwards (a fresh test database
 * has none, which means "cap off", so other tests are not affected).
 */
class PilotCapTest extends DbTest {

    @Autowired
    JwtService jwt;

    @Autowired
    PilotAdmissionService admission;

    @Autowired
    ObjectMapper json;

    /** Fake numbers only: +91 99999 xxxxx. */
    private String fakePhone() {
        return fx.track("+9199999" + String.format("%05d", ThreadLocalRandom.current().nextInt(100_000)));
    }

    private Family family() {
        return fx.family(fakePhone(), "en", false, ZoneId.of("Asia/Kolkata"));
    }

    private String bearer(UUID userId, boolean admin) {
        return "Bearer " + jwt.issueToken(userId, admin);
    }

    private String ask(Family f) throws Exception {
        MvcResult r = mvc.perform(post("/recipients/" + f.recipientId() + "/consent-request")
                .header("Authorization", bearer(f.userId(), false))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getContentAsString();
    }

    private void limits(boolean enabled, int freeSeats, int newPerDay) {
        Integer used = fx.jdbc().queryForObject("select public.pilot_seats_used()", Integer.class);
        fx.jdbc().update("insert into pilot_settings (id, enabled, max_active, max_new_per_day) values (true, ?, ?, ?) " +
                "on conflict (id) do update set enabled = excluded.enabled, max_active = excluded.max_active, " +
                "max_new_per_day = excluded.max_new_per_day", enabled, (used == null ? 0 : used) + freeSeats, newPerDay);
    }

    @BeforeEach
    void pilotOn() {
        assertThat(fx.jdbc().queryForObject("select current_database()", String.class)).contains("test");
        fx.jdbc().update("delete from pilot_queue");
        limits(true, 1, 10);
    }

    @AfterEach
    void pilotOff() {
        fx.jdbc().update("delete from pilot_settings");
        fx.jdbc().update("delete from pilot_queue");
    }

    @Test
    void whenThePilotIsFull_theRequestWaits_andTheAppShowsThePlaceInLine() throws Exception {
        Family first = family();
        Family second = family();

        assertThat(ask(first)).isEqualTo("{\"status\":\"sent\"}");
        assertThat(ask(second)).isEqualTo("{\"status\":\"pilot_full\",\"position\":1}");

        assertThat(wa.sentTo(first.phone())).hasSize(1);
        assertThat(wa.sentTo(second.phone())).isEmpty();   // nothing goes to a new number while full
        assertThat(fx.recipient(second.recipientId()).get("consent_requested_at")).isNull();

        String list = mvc.perform(get("/api/v1/recipients").header("Authorization", bearer(second.userId(), false)))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).contains("\"pilotPosition\":1");
        String mine = mvc.perform(get("/api/v1/recipients").header("Authorization", bearer(first.userId(), false)))
                .andReturn().getResponse().getContentAsString();
        assertThat(mine).contains("\"pilotPosition\":null");
    }

    @Test
    void aFreedSeatLetsTheFirstInLineIn_andTheirFamilyIsTold() throws Exception {
        Family first = family();
        Family second = family();
        ask(first);
        ask(second);
        assertThat(admission.runOnce()).isZero();   // still full: nobody moves

        fx.jdbc().update("update care_recipients set opted_out_at = now() where id = ?", first.recipientId());   // STOP frees the seat
        assertThat(admission.runOnce()).isEqualTo(1);

        assertThat(wa.sentTo(second.phone())).hasSize(1);
        assertThat(wa.sentTo(second.phone()).get(0).text()).contains("Reply *1*");
        assertThat(fx.notifications(second.userId(), "pilot_admitted")).isEqualTo(1);
        assertThat(admission.runOnce()).isZero();   // let in once: not asked again
        assertThat(wa.sentTo(second.phone())).hasSize(1);
    }

    @Test
    void theDailyLimitHoldsEvenWithFreeSeats() throws Exception {
        limits(true, 10, 1);
        Family first = family();
        Family second = family();

        assertThat(ask(first)).isEqualTo("{\"status\":\"sent\"}");
        assertThat(ask(second)).contains("\"status\":\"pilot_full\"");
    }

    @Test
    void withTheCapOff_everyoneIsAskedAtOnce() throws Exception {
        fx.jdbc().update("delete from pilot_settings");
        Family first = family();
        Family second = family();

        assertThat(ask(first)).isEqualTo("{\"status\":\"sent\"}");
        assertThat(ask(second)).isEqualTo("{\"status\":\"sent\"}");
    }

    @Test
    void theOwnerSeesTheLine_andCanMoveSomeoneToTheFront() throws Exception {
        Family first = family();
        Family second = family();
        Family third = family();
        ask(first);
        ask(second);
        ask(third);
        UUID admin = fx.user("Owner");
        fx.jdbc().update("update profiles set is_admin = true where id = ?", admin);

        mvc.perform(get("/api/v1/admin/pilot").header("Authorization", bearer(second.userId(), false)))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(403));

        JsonNode before = json.readTree(mvc.perform(get("/api/v1/admin/pilot").header("Authorization", bearer(admin, true)))
                .andReturn().getResponse().getContentAsString());
        assertThat(before.path("enabled").asBoolean()).isTrue();
        assertThat(before.path("waiting")).hasSize(2);
        assertThat(before.path("waiting").get(0).path("recipient_id").asText()).isEqualTo(second.recipientId().toString());
        assertThat(before.toString()).doesNotContain(second.phone()).doesNotContain(third.phone());

        MvcResult moved = mvc.perform(post("/api/v1/admin/pilot/" + third.recipientId() + "/move-up")
                .header("Authorization", bearer(admin, true))).andReturn();
        assertThat(moved.getResponse().getStatus()).isEqualTo(200);
        assertThat(ask(third)).isEqualTo("{\"status\":\"pilot_full\",\"position\":1}");
        assertThat(ask(second)).isEqualTo("{\"status\":\"pilot_full\",\"position\":2}");

        MvcResult notWaiting = mvc.perform(post("/api/v1/admin/pilot/" + first.recipientId() + "/move-up")
                .header("Authorization", bearer(admin, true))).andReturn();
        assertThat(notWaiting.getResponse().getStatus()).isEqualTo(404);
    }
}
