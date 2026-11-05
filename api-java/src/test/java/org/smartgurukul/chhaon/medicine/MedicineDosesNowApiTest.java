package org.smartgurukul.chhaon.medicine;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Saving a medicine makes today's upcoming doses at once, so the Today screen shows it without waiting for the next tick.
 * The scheduler is switched ON for this context (its own 5-minute tick is pushed a day away, so it never runs here).
 */
@TestPropertySource(properties = {"chhaon.scheduler.enabled=true", "chhaon.scheduler.initial-delay-ms=86400000"})
class MedicineDosesNowApiTest extends DbTest {

    @Autowired JwtService jwt;

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    /** Doses due before now + 3 hours (tomorrow's dose at an already-passed time is inside the 24 h plan, not "today"). */
    private int doses(UUID medicineId, String status) {
        Integer n = fx.jdbc().queryForObject("select count(*) from dose_events d join medicine_schedules s on s.id = d.schedule_id " +
                "where s.medicine_id = ? and d.status::text = ? and d.scheduled_at < now() + interval '3 hours'", Integer.class, medicineId, status);
        return n == null ? 0 : n;
    }

    private UUID create(Fixtures.Family f, String hhmm) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/recipients/{id}/medicines", f.recipientId())
                        .header("Authorization", bearer(f.userId())).contentType("application/json")
                        .content("{\"name\":\"Amlodipine\",\"dosage\":\"5 mg\",\"times\":[\"" + hhmm + "\"]}"))
                .andExpect(status().isCreated()).andReturn();
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(res.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return UUID.fromString(m.group(1));
    }

    private static String hhmm(Fixtures.Family f, Instant at) {
        return Fixtures.localTime(f, at).toString().substring(0, 5);
    }

    @Test
    void aNewMedicineHasItsUpcomingDoseRightAway() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        UUID id = create(f, hhmm(f, Instant.now().plus(2, ChronoUnit.HOURS)));
        assertThat(doses(id, "pending")).isEqualTo(1);
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void anEditAddingALaterTimeShowsItRightAway() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        UUID id = create(f, hhmm(f, Instant.now().minus(3, ChronoUnit.HOURS)));   // already passed: no dose today
        assertThat(doses(id, "pending")).isZero();

        mvc.perform(put("/api/v1/medicines/{id}", id).header("Authorization", bearer(f.userId())).contentType("application/json")
                        .content("{\"name\":\"Amlodipine\",\"dosage\":\"5 mg\",\"times\":[\"" + hhmm(f, Instant.now().minus(3, ChronoUnit.HOURS))
                                + "\",\"" + hhmm(f, Instant.now().plus(2, ChronoUnit.HOURS)) + "\"]}"))
                .andExpect(status().isOk());
        assertThat(doses(id, "pending")).isEqualTo(1);
    }

    @Test
    void pausingRemovesTheWaitingDoseAndResumingBringsItBack() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        UUID id = create(f, hhmm(f, Instant.now().plus(2, ChronoUnit.HOURS)));
        assertThat(doses(id, "pending")).isEqualTo(1);

        mvc.perform(patch("/api/v1/medicines/{id}", id).header("Authorization", bearer(f.userId())).contentType("application/json")
                .content("{\"active\":false}")).andExpect(status().isOk());
        assertThat(doses(id, "pending")).isZero();

        mvc.perform(patch("/api/v1/medicines/{id}", id).header("Authorization", bearer(f.userId())).contentType("application/json")
                .content("{\"active\":true}")).andExpect(status().isOk());
        assertThat(doses(id, "pending")).isEqualTo(1);
    }
}
