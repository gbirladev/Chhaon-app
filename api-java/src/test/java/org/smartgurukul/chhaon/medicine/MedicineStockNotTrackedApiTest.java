package org.smartgurukul.chhaon.medicine;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.whatsapp.AlertDelivery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A stock left empty means "not tracked" (round 2, R3): null in the database and the API, never shown or alerted as "out of
 * stock". A count can be started later and stopped again; a delta never turns "not tracked" into 0.
 */
class MedicineStockNotTrackedApiTest extends DbTest {

    @Autowired JwtService jwt;
    @Autowired AlertDelivery alerts;

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    private UUID create(Fixtures.Family f, String stockJson, int expect) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/recipients/{id}/medicines", f.recipientId())
                        .header("Authorization", bearer(f.userId())).contentType("application/json")
                        .content("{\"name\":\"Telma\",\"dosage\":\"1 tablet\",\"times\":[\"08:00\"],\"refillThreshold\":5" + stockJson + "}"))
                .andExpect(status().is(expect)).andReturn();
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(created.getResponse().getContentAsString());
        return m.find() ? UUID.fromString(m.group(1)) : null;
    }

    private void stock(Fixtures.Family f, UUID id, String body, int expect) throws Exception {
        mvc.perform(post("/api/v1/medicines/{id}/stock", id).header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content(body))
                .andExpect(status().is(expect));
    }

    private int stockAlerts(Fixtures.Family f) {
        return fx.notifications(f.userId(), "refill_needed") + fx.notifications(f.userId(), "out_of_stock");
    }

    @Test
    void anEmptyStockIsNotTracked_noOutOfStock_andADeltaKeepsItNull() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        UUID id = create(f, "", 201);
        assertThat(fx.stock(id)).isNull();
        mvc.perform(get("/api/v1/recipients/{id}/medicines", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stock", nullValue()));

        stock(f, id, "{\"delta\":-1}", 200);
        assertThat(fx.stock(id)).isNull();
        assertThat(stockAlerts(f)).isZero();

        // Start counting: an exact number. Then a delta works as before and the refill alert comes at the threshold.
        stock(f, id, "{\"count\":6}", 200);
        assertThat(fx.stock(id)).isEqualTo(6);
        stock(f, id, "{\"delta\":-1}", 200);
        assertThat(fx.stock(id)).isEqualTo(5);
        assertThat(fx.notifications(f.userId(), "refill_needed")).isEqualTo(1);

        // Stop counting: back to null, and an alert still waiting for WhatsApp is not sent ("has run out" about nothing counted).
        stock(f, id, "{\"untrack\":true}", 200);
        assertThat(fx.stock(id)).isNull();
        fx.alertsOn(f.userId(), fx.track(Fixtures.randomPhone()));
        Map<String, Integer> counts = alerts.deliver(f.recipientId());
        assertThat(counts.getOrDefault("alerts_sent", 0)).isZero();
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void aStockOfZeroIsStillAllowedAndTracked_andBadNumbersAreRefused() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        UUID zero = create(f, ",\"stock\":0", 201);
        assertThat(fx.stock(zero)).isZero();
        assertThat(fx.notifications(f.userId(), "out_of_stock")).isEqualTo(1);   // really counted and really empty

        create(f, ",\"stock\":-1", 400);
        create(f, ",\"stock\":100001", 400);
        stock(f, zero, "{\"count\":-3}", 400);
        stock(f, zero, "{\"delta\":1,\"count\":3}", 400);
        stock(f, zero, "{}", 400);
        mvc.perform(post("/api/v1/medicines/{id}/stock", zero).header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content("{\"count\":12}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stock", is(12)));
    }

    @Test
    void aTakenDoseNeverCreatesAStockFromNotTracked() {
        Fixtures.Family f = fx.family("en", true);
        Fixtures.Med m = fx.medicine(f, "Iron", "1 tablet", null, java.time.LocalTime.of(8, 0));
        fx.jdbc().queryForList("select public.consume_stock(?, 1)", m.medicineId());
        assertThat(fx.stock(m.medicineId())).isNull();
    }
}
