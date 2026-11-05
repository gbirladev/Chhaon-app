package org.smartgurukul.chhaon.child;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.db.UserScopedSql;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Child profiles (migration 0045): guardian confirmation before any child health data, the vaccination plan from the date of birth
 * (IAP-ACVIP 2023 data file), family edits, family-circle-only access, and the in-app vaccine reminder.
 */
class ChildVaccinesApiTest extends DbTest {

    @Autowired JwtService jwt;
    @Autowired VaccineSchedule schedule;
    @Autowired VaccineReminderService reminders;
    @Autowired UserScopedSql sql;

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    private void saveChild(Fixtures.Family f, LocalDate dob) throws Exception {
        mvc.perform(put("/api/v1/recipients/{id}/child", f.recipientId()).header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content("{\"dob\":\"" + dob + "\",\"guardianConfirmed\":true}"))
                .andExpect(status().isOk());
    }

    private void makePlan(Fixtures.Family f) throws Exception {
        mvc.perform(post("/api/v1/recipients/{id}/vaccines/plan", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk());
    }

    private Map<String, Object> dose(Fixtures.Family f, String key) {
        return fx.jdbc().queryForMap("select * from child_vaccines where recipient_id = ? and schedule_key = ?", f.recipientId(), key);
    }

    private int planSize(Fixtures.Family f) {
        Integer n = fx.jdbc().queryForObject("select count(*) from child_vaccines where recipient_id = ?", Integer.class, f.recipientId());
        return n == null ? 0 : n;
    }

    private void updateDose(Fixtures.Family f, UUID userId, Object vaccineId, String body, int expectStatus) throws Exception {
        mvc.perform(put("/api/v1/vaccines/{id}", vaccineId).header("Authorization", bearer(userId))
                        .contentType("application/json").content(body))
                .andExpect(status().is(expectStatus));
    }

    @Test
    void theGuardianConfirmationIsNeededBeforeTheDateOfBirthOrAPlanIsSaved() throws Exception {
        Fixtures.Family f = fx.family("en", false);
        LocalDate dob = today().minusMonths(2);
        mvc.perform(put("/api/v1/recipients/{id}/child", f.recipientId()).header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content("{\"dob\":\"" + dob + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/recipients/{id}/vaccines/plan", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isConflict());
        assertThat(planSize(f)).isZero();

        // The database itself refuses a plan row without the confirmation (Row Level Security), whatever the backend does.
        assertThatThrownBy(() -> sql.asUser(f.userId(), jdbc -> jdbc.update(
                "insert into child_vaccines (recipient_id, schedule_key, vaccine_code, dose_label, schedule_edition, suggested_due_date, due_date) " +
                        "values (?, 'bcg', 'bcg', '', 'x', current_date, current_date)", f.recipientId())))
                .hasStackTraceContaining("row-level security");

        saveChild(f, dob);
        Map<String, Object> r = fx.jdbc().queryForMap("select dob, guardian_confirmed_at, guardian_confirmed_by from care_recipients where id = ?",
                f.recipientId());
        assertThat(r.get("dob").toString()).isEqualTo(dob.toString());
        assertThat(r.get("guardian_confirmed_at")).isNotNull();
        assertThat(r.get("guardian_confirmed_by")).isEqualTo(f.userId());
        mvc.perform(get("/api/v1/recipients/{id}/child", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dob", is(dob.toString())))
                .andExpect(jsonPath("$.guardianConfirmedAt", notNullValue()))
                .andExpect(jsonPath("$.edition", is("IAP-ACVIP 2023")))
                .andExpect(jsonPath("$.reviewedByPaediatrician", is(false)));
    }

    @Test
    void aDateOfBirthMustBeAChildsAndTheOwnProfileIsNoChild() throws Exception {
        Fixtures.Family f = fx.family("en", false);
        for (LocalDate bad : List.of(today().plusDays(2), today().minusYears(18), today().minusYears(40))) {
            mvc.perform(put("/api/v1/recipients/{id}/child", f.recipientId()).header("Authorization", bearer(f.userId()))
                            .contentType("application/json").content("{\"dob\":\"" + bad + "\",\"guardianConfirmed\":true}"))
                    .andExpect(status().isBadRequest());
        }
        Fixtures.Family self = fx.selfFamily(fx.track(Fixtures.randomPhone()), "en", false);
        mvc.perform(put("/api/v1/recipients/{id}/child", self.recipientId()).header("Authorization", bearer(self.userId()))
                        .contentType("application/json").content("{\"dob\":\"" + today().minusYears(1) + "\",\"guardianConfirmed\":true}"))
                .andExpect(status().isConflict());
    }

    @Test
    void thePlanFollowsTheScheduleFileAndIsMadeOnlyOnce() throws Exception {
        Fixtures.Family f = fx.family("en", false);
        LocalDate dob = today().minusDays(10);
        saveChild(f, dob);
        makePlan(f);
        makePlan(f);   // idempotent
        assertThat(planSize(f)).isEqualTo(schedule.doses().size());
        assertThat(dose(f, "bcg").get("due_date").toString()).isEqualTo(dob.toString());
        assertThat(dose(f, "dtp-1").get("due_date").toString()).isEqualTo(dob.plusWeeks(6).toString());
        assertThat(dose(f, "mmr-1").get("due_date").toString()).isEqualTo(dob.plusMonths(9).toString());
        assertThat(dose(f, "hpv-2").get("due_date").toString()).isEqualTo(dob.plusYears(9).plusMonths(6).toString());
        assertThat(dose(f, "td").get("schedule_edition")).isEqualTo("IAP-ACVIP 2023");

        mvc.perform(get("/api/v1/recipients/{id}/vaccines", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(schedule.doses().size())))
                .andExpect(jsonPath("$[0].key", is("bcg")))
                .andExpect(jsonPath("$[0].vaccineEn", is("BCG")))
                .andExpect(jsonPath("$[0].status", is("overdue")))
                .andExpect(jsonPath("$[0].group", is("birth")));
    }

    @Test
    void theFamilyMarksGivenNotGivenAndChangesDatesAndTheirDatesAreNeverMoved() throws Exception {
        Fixtures.Family f = fx.family("en", false);
        LocalDate dob = today().minusDays(10);
        saveChild(f, dob);
        makePlan(f);
        Object bcg = dose(f, "bcg").get("id");
        Object opv = dose(f, "opv-0").get("id");
        Object mmr = dose(f, "mmr-1").get("id");

        updateDose(f, f.userId(), bcg, "{\"status\":\"given\",\"givenOn\":\"" + today().plusDays(5) + "\"}", 400);   // not in the future
        updateDose(f, f.userId(), bcg, "{\"status\":\"given\",\"givenOn\":\"" + dob.minusDays(1) + "\"}", 400);      // not before birth
        updateDose(f, f.userId(), bcg, "{\"status\":\"given\",\"givenOn\":\"" + dob + "\",\"notes\":\"City hospital\"}", 200);
        updateDose(f, f.userId(), opv, "{\"status\":\"not_given\"}", 200);
        LocalDate newDate = dob.plusMonths(10);
        updateDose(f, f.userId(), mmr, "{\"dueDate\":\"" + newDate + "\"}", 200);

        assertThat(dose(f, "bcg").get("given_on").toString()).isEqualTo(dob.toString());
        assertThat(dose(f, "bcg").get("notes")).isEqualTo("City hospital");
        assertThat(dose(f, "opv-0").get("not_given")).isEqualTo(true);
        assertThat(dose(f, "mmr-1").get("due_date_by_family")).isEqualTo(true);

        // The family corrects the date of birth: only the untouched open doses move.
        LocalDate dob2 = dob.minusDays(3);
        saveChild(f, dob2);
        assertThat(dose(f, "mmr-1").get("due_date").toString()).isEqualTo(newDate.toString());
        assertThat(dose(f, "mmr-1").get("suggested_due_date").toString()).isEqualTo(dob2.plusMonths(9).toString());
        assertThat(dose(f, "dtp-1").get("due_date").toString()).isEqualTo(dob2.plusWeeks(6).toString());
        assertThat(dose(f, "bcg").get("due_date").toString()).isEqualTo(dob.toString());

        // Back to the suggested date, and open again.
        updateDose(f, f.userId(), mmr, "{\"resetDueDate\":true}", 200);
        assertThat(dose(f, "mmr-1").get("due_date").toString()).isEqualTo(dob2.plusMonths(9).toString());
        updateDose(f, f.userId(), opv, "{\"status\":\"due\"}", 200);
        assertThat(dose(f, "opv-0").get("not_given")).isEqualTo(false);
        updateDose(f, f.userId(), mmr, "{\"status\":\"maybe\"}", 400);
    }

    @Test
    void onlyTheFamilyCircleSeesOrChangesTheChildsData() throws Exception {
        Fixtures.Family f = fx.family("en", false);
        saveChild(f, today().minusMonths(1));
        makePlan(f);
        Object bcg = dose(f, "bcg").get("id");
        UUID stranger = fx.user("Stranger");
        UUID aunt = fx.member(f, "Aunt");

        mvc.perform(get("/api/v1/recipients/{id}/child", f.recipientId()).header("Authorization", bearer(stranger))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/recipients/{id}/vaccines", f.recipientId()).header("Authorization", bearer(stranger))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/recipients/{id}/vaccines/plan", f.recipientId()).header("Authorization", bearer(stranger))).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/recipients/{id}/child", f.recipientId()).header("Authorization", bearer(stranger))
                .contentType("application/json").content("{\"dob\":\"" + today().minusMonths(1) + "\",\"guardianConfirmed\":true}"))
                .andExpect(status().isNotFound());
        updateDose(f, stranger, bcg, "{\"status\":\"given\",\"givenOn\":\"" + today() + "\"}", 404);
        assertThat(dose(f, "bcg").get("given_on")).isNull();

        mvc.perform(get("/api/v1/recipients/{id}/vaccines", f.recipientId()).header("Authorization", bearer(aunt)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()", is(schedule.doses().size())));
        updateDose(f, aunt, bcg, "{\"status\":\"given\",\"givenOn\":\"" + today() + "\"}", 200);
    }

    @Test
    void theFamilyIsRemindedAFewDaysBeforeADueDateOnceAndAgainAfterADateChange() throws Exception {
        Fixtures.Family f = fx.family("en", false);
        UUID aunt = fx.member(f, "Aunt");
        // Born 6 weeks minus 2 days ago: the 6-week doses are due in 2 days, the birth doses are past (not reminded).
        saveChild(f, today().minusWeeks(6).plusDays(2));
        makePlan(f);
        int sixWeekDoses = (int) schedule.doses().stream().filter(d -> "6w".equals(d.group())).count();
        fx.jdbc().update("update child_vaccines set given_on = current_date - 1 where recipient_id = ? and schedule_key = 'pcv-1'", f.recipientId());

        assertThat(reminders.run(f.recipientId())).isEqualTo(sixWeekDoses - 1);
        assertThat(reminders.run(f.recipientId())).isZero();
        assertThat(fx.notifications(f.userId(), "vaccine_due")).isEqualTo(sixWeekDoses - 1);
        assertThat(fx.notifications(aunt, "vaccine_due")).isEqualTo(sixWeekDoses - 1);
        String payload = fx.jdbc().queryForObject("select payload::text from notifications where user_id = ? and type = 'vaccine_due' " +
                "and payload->>'vaccine_id' = ?", String.class, f.userId(), dose(f, "dtp-1").get("id").toString());
        assertThat(payload).contains("\"vaccine_en\"").contains("\"due_date\"").contains(f.recipientId().toString());

        // The family moves one date by a day: that dose is reminded again.
        Object dtp = dose(f, "dtp-1").get("id");
        updateDose(f, f.userId(), dtp, "{\"dueDate\":\"" + today().plusDays(3) + "\"}", 200);
        assertThat(reminders.run(f.recipientId())).isEqualTo(1);
        // Nothing for WhatsApp: the caregiver alert outbox is told to skip it.
        Integer pending = fx.jdbc().queryForObject("select count(*) from notifications where user_id = ? and type = 'vaccine_due' " +
                "and whatsapp_skipped_at is null", Integer.class, f.userId());
        assertThat(pending).isZero();
    }

    @Test
    void theDataExportIncludesTheVaccinePlan() throws Exception {
        Fixtures.Family f = fx.family("en", false);
        saveChild(f, today().minusMonths(1));
        makePlan(f);
        String body = mvc.perform(get("/privacy/export").header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"child_vaccines\"").contains("mmr-1");
    }
}
