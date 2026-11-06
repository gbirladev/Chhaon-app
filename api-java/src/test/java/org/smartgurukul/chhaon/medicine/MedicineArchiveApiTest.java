package org.smartgurukul.chhaon.medicine;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Archive instead of delete (migration 0056): an archived medicine is inactive, its waiting doses go, its history stays, it does
 * not count towards the plan limit, and it can be restored (refused while the plan is full).
 */
class MedicineArchiveApiTest extends DbTest {

    @Autowired JwtService jwt;

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    private int doses(UUID medicineId, String status) {
        Integer n = fx.jdbc().queryForObject("select count(*) from dose_events d join medicine_schedules s on s.id = d.schedule_id " +
                "where s.medicine_id = ? and d.status::text = ?", Integer.class, medicineId, status);
        return n == null ? 0 : n;
    }

    @Test
    void archiveKeepsHistory_dropsWaitingDoses_andRestoreBringsItBack() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        Fixtures.Med m = fx.medicine(f, "Antibiotic", "1 tablet", 10, LocalTime.of(8, 0));
        fx.dose(m.scheduleId(), Instant.now().minus(1, ChronoUnit.DAYS), "taken", Instant.now().minus(1, ChronoUnit.DAYS), null);
        fx.dose(m.scheduleId(), Instant.now().plus(2, ChronoUnit.HOURS), "pending", null, null);

        mvc.perform(post("/api/v1/medicines/{id}/archive", m.medicineId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(false)))
                .andExpect(jsonPath("$.archivedAt", notNullValue()));
        assertThat(doses(m.medicineId(), "pending")).isZero();
        assertThat(doses(m.medicineId(), "taken")).isEqualTo(1);

        // archiving twice changes nothing; the list still returns it (the app shows it under "Archived")
        mvc.perform(post("/api/v1/medicines/{id}/archive", m.medicineId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/recipients/{id}/medicines", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].archivedAt", notNullValue()));

        // "resume" (the pause switch) cannot bring back an archived medicine: restore is the way
        mvc.perform(patch("/api/v1/medicines/{id}", m.medicineId()).header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content("{\"active\":true}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/medicines/{id}/restore", m.medicineId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.archivedAt", nullValue()));
        assertThat(doses(m.medicineId(), "taken")).isEqualTo(1);
    }

    @Test
    void archivedMedicinesDoNotCountTowardsThePlan_andRestoreIsRefusedWhenFull() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        fx.jdbc().update("update profiles set plan = 'free', trial_ends_at = now() - interval '1 day' where id = ?", f.userId());   // 2 medicines
        Fixtures.Med a = fx.medicine(f, "Telma", "40 mg", null, LocalTime.of(8, 0));
        fx.medicine(f, "Vitamin D", "1 tablet", null, LocalTime.of(9, 0));
        String third = "{\"name\":\"Iron\",\"dosage\":\"1 tablet\",\"times\":[\"10:00\"]}";
        mvc.perform(post("/api/v1/recipients/{id}/medicines", f.recipientId()).header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content(third))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/medicines/{id}/archive", a.medicineId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/recipients/{id}/medicines", f.recipientId()).header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content(third))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/medicines/{id}/restore", a.medicineId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isBadRequest());
        Boolean stillArchived = fx.jdbc().queryForObject("select archived_at is not null from medicines where id = ?", Boolean.class, a.medicineId());
        assertThat(stillArchived).isTrue();
    }

    @Test
    void someoneOutsideTheFamilyCannotArchiveOrRestore() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        Fixtures.Family other = fx.family("en", true);
        Fixtures.Med m = fx.medicine(f, "Telma", "40 mg", null, LocalTime.of(8, 0));
        mvc.perform(post("/api/v1/medicines/{id}/archive", m.medicineId()).header("Authorization", bearer(other.userId())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/medicines/{id}/restore", m.medicineId()).header("Authorization", bearer(other.userId())))
                .andExpect(status().isNotFound());
        Boolean archived = fx.jdbc().queryForObject("select archived_at is not null from medicines where id = ?", Boolean.class, m.medicineId());
        assertThat(archived).isFalse();
    }
}
