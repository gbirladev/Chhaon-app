package org.smartgurukul.chhaon.dose;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.scheduler.SchedulerService;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * "Mark as given" on Today (migration 20261105000047): who/how is recorded, stock is used exactly like a WhatsApp "1", a later
 * WhatsApp reply changes nothing, Undo restores status and stock for 10 minutes, only circle members, and no "missed" alert
 * for a dose that was given.
 */
class DoseGivenApiTest extends DbTest {

    @Autowired JwtService jwt;
    @Autowired SchedulerService scheduler;

    private static Instant ago(int minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES);
    }

    private MvcResult mark(UUID userId, UUID doseId, String source) throws Exception {
        return mvc.perform(post("/api/v1/doses/{id}/given", doseId).header("Authorization", "Bearer " + jwt.issueToken(userId, false))
                .contentType("application/json").content("{\"source\":\"" + source + "\"}")).andReturn();
    }

    private MvcResult undo(UUID userId, UUID doseId) throws Exception {
        return mvc.perform(post("/api/v1/doses/{id}/given/undo", doseId)
                .header("Authorization", "Bearer " + jwt.issueToken(userId, false))).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private Map<String, Object> row(UUID doseId) {
        return fx.jdbc().queryForMap("select status::text status, responded_at, confirmed_by, confirm_source, confirm_prev_status::text prev, " +
                "confirm_stock_used from dose_events where id = ?", doseId);
    }

    /** A medicine whose own schedule is far from now, so a tick never makes or sends a dose of it by itself. */
    private Med medicine(Family f, String name, String dosage, Integer stock) {
        return fx.medicine(f, name, dosage, stock, Fixtures.localTime(f, Instant.now().plus(6, ChronoUnit.HOURS)));
    }

    @Test
    void aWaitingDoseMarkedInPersonIsTaken_usesStockLikeWhatsApp_andTodayShowsWhoAndHow() throws Exception {
        Family f = fx.family("en", true);
        Med m = medicine(f, "Metformin", "2 tablets", 20);
        UUID dose = fx.dose(m.scheduleId(), ago(10), "sent", ago(9), Fixtures.messageId());

        MvcResult r = mark(f.userId(), dose, "caregiver_in_person");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).contains("\"result\":\"marked\"").contains("\"status\":\"taken\"");
        Map<String, Object> d = row(dose);
        assertThat(d.get("status")).isEqualTo("taken");
        assertThat(d.get("responded_at")).isNotNull();
        assertThat(d.get("confirmed_by")).isEqualTo(f.userId());
        assertThat(d.get("confirm_source")).isEqualTo("caregiver_in_person");
        assertThat(d.get("prev")).isEqualTo("sent");
        assertThat(d.get("confirm_stock_used")).isEqualTo(2);
        assertThat(fx.stock(m.medicineId())).isEqualTo(18);

        String today = mvc.perform(get("/api/v1/recipients/{id}/today", f.recipientId())
                .header("Authorization", "Bearer " + jwt.issueToken(f.userId(), false))).andReturn().getResponse().getContentAsString();
        assertThat(today).contains("\"confirmSource\":\"caregiver_in_person\"").contains("\"confirmedByName\":\"Ananya\"")
                .contains("\"confirmedBy\":\"" + f.userId() + "\"");
        String activity = mvc.perform(get("/api/v1/recipients/{id}/activity?days=3", f.recipientId())
                .header("Authorization", "Bearer " + jwt.issueToken(f.userId(), false))).andReturn().getResponse().getContentAsString();
        assertThat(activity).contains("\"confirmSource\":\"caregiver_in_person\"").contains("\"confirmedByName\":\"Ananya\"");
    }

    @Test
    void aWhatsAppReplyAfterAManualMarkChangesNothing_andUsesNoStock() throws Exception {
        Family f = fx.family("en", true);
        Med m = medicine(f, "Metformin", "1 tablet", 10);
        UUID dose = fx.dose(m.scheduleId(), ago(10), "sent", ago(9), Fixtures.messageId());
        mark(f.userId(), dose, "caregiver_told");
        assertThat(fx.stock(m.medicineId())).isEqualTo(9);

        assertThat(gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "1")).status()).isEqualTo(200);
        assertThat(gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "all taken")).status()).isEqualTo(200);

        assertThat(fx.stock(m.medicineId())).isEqualTo(9);
        Map<String, Object> d = row(dose);
        assertThat(d.get("status")).isEqualTo("taken");
        assertThat(d.get("confirm_source")).isEqualTo("caregiver_told");
        assertThat(wa.sentTo(f.phone()).stream().filter(s -> s.text().contains("Metformin"))).isEmpty();
    }

    @Test
    void aDoseConfirmedOnWhatsAppIsRecordedAsWhatsApp_andCannotBeMarkedAgain() throws Exception {
        Family f = fx.family("en", true);
        Med m = medicine(f, "Amlodipine", "1 tablet", 10);
        UUID dose = fx.dose(m.scheduleId(), ago(10), "sent", ago(9), Fixtures.messageId());
        gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "1"));
        assertThat(row(dose).get("confirm_source")).isEqualTo("whatsapp");
        assertThat(row(dose).get("confirmed_by")).isNull();
        assertThat(fx.stock(m.medicineId())).isEqualTo(9);

        MvcResult r = mark(f.userId(), dose, "caregiver_in_person");

        assertThat(body(r)).contains("\"result\":\"already\"").contains("\"source\":\"whatsapp\"");
        assertThat(fx.stock(m.medicineId())).isEqualTo(9);
        assertThat(row(dose).get("confirm_source")).isEqualTo("whatsapp");
        assertThat(body(undo(f.userId(), dose))).contains("\"result\":\"not_marked\"");
    }

    @Test
    void aMissedDoseBecomesTakenLate_andTheMissedAlertIsNeitherShownNorSent() throws Exception {
        Family f = fx.family("en", true);
        String mine = Fixtures.randomPhone();
        fx.alertsOn(f.userId(), mine);
        Med m = medicine(f, "Metformin", "1 tablet", 10);
        UUID dose = fx.dose(m.scheduleId(), ago(120), "missed", ago(119), Fixtures.messageId());
        fx.jdbc().update("insert into notifications (user_id, type, payload) values (?, 'dose_missed', " +
                        "jsonb_build_object('recipient_id', ?::text, 'medicine_id', ?::text, 'dose_event_id', ?::text))",
                f.userId(), f.recipientId().toString(), m.medicineId().toString(), dose.toString());

        assertThat(body(mark(f.userId(), dose, "caregiver_in_person"))).contains("\"status\":\"taken_late\"");

        assertThat(row(dose).get("prev")).isEqualTo("missed");
        assertThat(fx.notifications(f.userId(), "dose_missed")).isZero();
        scheduler.tick(f.recipientId());
        assertThat(wa.sentTo(mine)).isEmpty();
    }

    @Test
    void oneDoseOfASlotGivenKeepsTheAlertForTheOtherStillMissed() throws Exception {
        Family f = fx.family("en", true);
        Med a = medicine(f, "Metformin", "1 tablet", 10);
        Med b = medicine(f, "Aspirin", "1 tablet", 10);
        Instant due = ago(120);
        UUID first = fx.dose(a.scheduleId(), due, "missed", ago(119), Fixtures.messageId());
        fx.dose(b.scheduleId(), due, "missed", ago(119), Fixtures.messageId());
        fx.jdbc().update("insert into notifications (user_id, type, payload) values (?, 'dose_missed', " +
                        "jsonb_build_object('recipient_id', ?::text, 'dose_event_id', ?::text, 'count', 2))",
                f.userId(), f.recipientId().toString(), first.toString());

        mark(f.userId(), first, "caregiver_told");

        assertThat(fx.notifications(f.userId(), "dose_missed")).isEqualTo(1);
    }

    @Test
    void undoWithinTenMinutesRestoresStatusAndStock_onlyForWhoMarkedIt() throws Exception {
        Family f = fx.family("en", true);
        UUID sister = fx.member(f, "Meera");
        Med m = medicine(f, "Metformin", "2 tablets", 20);
        UUID dose = fx.dose(m.scheduleId(), ago(10), "sent", ago(9), Fixtures.messageId());

        assertThat(body(mark(sister, dose, "caregiver_in_person"))).contains("\"result\":\"marked\""); // any member can mark
        assertThat(fx.stock(m.medicineId())).isEqualTo(18);
        assertThat(body(undo(f.userId(), dose))).contains("\"result\":\"not_yours\"");
        assertThat(fx.doseStatus(dose)).isEqualTo("taken");

        assertThat(body(undo(sister, dose))).contains("\"result\":\"undone\"");

        Map<String, Object> d = row(dose);
        assertThat(d.get("status")).isEqualTo("sent");
        assertThat(d.get("responded_at")).isNull();
        assertThat(d.get("confirmed_by")).isNull();
        assertThat(d.get("confirm_source")).isNull();
        assertThat(fx.stock(m.medicineId())).isEqualTo(20);
        assertThat(body(undo(sister, dose))).contains("\"result\":\"not_marked\"");

        // After the undo a WhatsApp reply counts again (once).
        gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "1"));
        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
        assertThat(row(dose).get("confirm_source")).isEqualTo("whatsapp");
        assertThat(fx.stock(m.medicineId())).isEqualTo(18);
    }

    @Test
    void undoAfterTenMinutesIsRefused() throws Exception {
        Family f = fx.family("en", true);
        Med m = medicine(f, "Metformin", "1 tablet", 10);
        UUID dose = fx.dose(m.scheduleId(), ago(30), "missed", ago(29), Fixtures.messageId());
        mark(f.userId(), dose, "caregiver_in_person");
        fx.jdbc().update("update dose_events set responded_at = now() - interval '11 minutes' where id = ?", dose);

        assertThat(body(undo(f.userId(), dose))).contains("\"result\":\"too_late\"");
        assertThat(fx.doseStatus(dose)).isEqualTo("taken_late");
        assertThat(fx.stock(m.medicineId())).isEqualTo(9);
    }

    @Test
    void emptyStockStaysAtZero_andUndoDoesNotInventTablets() throws Exception {
        Family f = fx.family("en", true);
        Med empty = medicine(f, "Metformin", "1 tablet", 0);
        Med untracked = medicine(f, "Aspirin", "1 tablet", null);
        UUID d1 = fx.dose(empty.scheduleId(), ago(5), "pending", null, null);
        UUID d2 = fx.dose(untracked.scheduleId(), ago(5), "skipped", null, null);

        mark(f.userId(), d1, "caregiver_in_person");
        mark(f.userId(), d2, "caregiver_in_person");
        assertThat(fx.doseStatus(d1)).isEqualTo("taken");
        assertThat(fx.doseStatus(d2)).isEqualTo("taken");
        assertThat(fx.stock(empty.medicineId())).isZero();
        assertThat(fx.stock(untracked.medicineId())).isNull();

        undo(f.userId(), d1);
        undo(f.userId(), d2);
        assertThat(fx.doseStatus(d1)).isEqualTo("pending");
        assertThat(fx.doseStatus(d2)).isEqualTo("skipped");
        assertThat(fx.stock(empty.medicineId())).isZero();
        assertThat(fx.stock(untracked.medicineId())).isNull();
    }

    @Test
    void strangersBadSourcesAndFarAwayDosesAreRefused() throws Exception {
        Family f = fx.family("en", true);
        UUID stranger = fx.user("Stranger");
        Med m = medicine(f, "Metformin", "1 tablet", 10);
        UUID dose = fx.dose(m.scheduleId(), ago(10), "sent", ago(9), Fixtures.messageId());
        UUID later = fx.dose(m.scheduleId(), Instant.now().plus(3, ChronoUnit.HOURS), "pending", null, null);
        UUID old = fx.dose(m.scheduleId(), ago(60 * 48), "missed", ago(60 * 48 - 1), Fixtures.messageId());

        assertThat(mark(stranger, dose, "caregiver_in_person").getResponse().getStatus()).isEqualTo(404);
        assertThat(undo(stranger, dose).getResponse().getStatus()).isEqualTo(404);
        assertThat(mark(f.userId(), UUID.randomUUID(), "caregiver_in_person").getResponse().getStatus()).isEqualTo(404);
        assertThat(mark(f.userId(), dose, "whatsapp").getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(post("/api/v1/doses/{id}/given", dose).contentType("application/json").content("{\"source\":\"caregiver_told\"}"))
                .andReturn().getResponse().getStatus()).isIn(401, 403);
        assertThat(body(mark(f.userId(), later, "caregiver_in_person"))).contains("\"result\":\"too_early\"");
        assertThat(body(mark(f.userId(), old, "caregiver_in_person"))).contains("\"result\":\"too_old\"");

        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
        assertThat(fx.doseStatus(later)).isEqualTo("pending");
        assertThat(fx.doseStatus(old)).isEqualTo("missed");
        assertThat(fx.stock(m.medicineId())).isEqualTo(10);
    }
}
