package org.smartgurukul.chhaon.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.child.VaccineReminderService;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R2 "what the receiver reads": the WhatsApp vaccine reminder to a child's reminder receiver, the in-app WhatsApp preview built from
 * the real templates, and the receiver's language.
 */
class ReceiverWhatsAppTest extends DbTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired VaccineReminderService vaccines;
    @Autowired ObjectMapper json;

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    /** A fixed-offset zone where it is about noon right now, so the daytime-only vaccine WhatsApp may go out whenever the test runs. */
    private static ZoneId noonZone() {
        int offset = 12 - Instant.now().atZone(ZoneOffset.UTC).getHour();
        return ZoneId.of(offset == 0 ? "Etc/GMT" : offset > 0 ? "Etc/GMT-" + offset : "Etc/GMT+" + (-offset));
    }

    /** A child (Aarav) whose reminders go to Kamla (Nani) on her own number, agreed. */
    private Family childWithNani(ZoneId zone, String language) throws Exception {
        Family f = fx.family(Fixtures.randomPhone(), language, false, zone);
        String nani = fx.track(Fixtures.randomPhone());
        jdbc.update("update care_recipients set care_type = 'child', name = 'Aarav Sharma', call_name = 'Aarav', timezone = ?, " +
                "reminder_to = 'helper', reminder_name = 'Kamla', reminder_relation = 'Nani', whatsapp_phone = ? where id = ?",
                zone.getId(), nani, f.recipientId());
        fx.consent(f.recipientId());
        return new Family(f.userId(), f.circleId(), f.recipientId(), nani, zone);
    }

    private void plan(Family f, LocalDate dob) throws Exception {
        mvc.perform(put("/api/v1/recipients/{id}/child", f.recipientId()).header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content("{\"dob\":\"" + dob + "\",\"guardianConfirmed\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/recipients/{id}/vaccines/plan", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk());
    }

    @Test
    void theNaniGetsOneVaccineMessageAFewDaysBeforeAndOneOnTheDay_neverTwice() throws Exception {
        ZoneId zone = noonZone();
        Family f = childWithNani(zone, "en");
        LocalDate today = LocalDate.now(zone);
        plan(f, today.minusWeeks(6).plusDays(2));   // the 6-week doses are due in 2 days

        vaccines.run(f.recipientId());
        assertThat(wa.sentTo(f.phone())).hasSize(1);   // all doses of that date in ONE message
        String before = wa.sentTo(f.phone()).get(0).text();
        assertThat(before).startsWith("Namaste Kamla ji 🙏\n💉 Aarav's vaccines ").contains(" are due on *")
                .contains("as advised by your doctor").contains("Please remind the family, or take Aarav to the clinic.")
                .doesNotContain("http").doesNotContainPattern("\\d{7,}");

        vaccines.run(f.recipientId());
        assertThat(wa.sentTo(f.phone())).hasSize(1);   // idempotent

        // the day itself: one more message, then nothing
        jdbc.update("update child_vaccines set due_date = ? where recipient_id = ? and schedule_key = 'mmr-1'", today, f.recipientId());
        vaccines.run(f.recipientId());
        vaccines.run(f.recipientId());
        assertThat(wa.sentTo(f.phone())).hasSize(2);
        assertThat(wa.sentTo(f.phone()).get(1).text()).contains("Aarav's vaccine *MMR (dose 1)* is due *today*");
    }

    @Test
    void noVaccineMessageWithoutAgreement_afterStop_atNight_orToTheChild() throws Exception {
        ZoneId zone = noonZone();
        Family f = childWithNani(zone, "hi");
        LocalDate today = LocalDate.now(zone);
        plan(f, today.minusWeeks(6).plusDays(2));
        jdbc.update("update care_recipients set consent_at = null where id = ?", f.recipientId());
        vaccines.run(f.recipientId());
        assertThat(wa.sentTo(f.phone())).isEmpty();

        fx.consent(f.recipientId());
        fx.optOutPhone(f.phone());
        vaccines.run(f.recipientId());
        assertThat(wa.sentTo(f.phone())).isEmpty();
        jdbc.update("delete from phone_opt_outs where phone = ?", f.phone());

        // the reminders go to "the person": that would be the child, so never
        jdbc.update("update care_recipients set reminder_to = 'person', reminder_name = null, reminder_relation = null where id = ?",
                f.recipientId());
        fx.consent(f.recipientId());
        vaccines.run(f.recipientId());
        assertThat(wa.sentTo(f.phone())).isEmpty();

        // back to Nani, but it is 2 a.m. on the child's clock: wait for the day
        int offset = 2 - Instant.now().atZone(ZoneOffset.UTC).getHour();
        offset = offset < -12 ? offset + 24 : offset > 14 ? offset - 24 : offset;
        String night = offset == 0 ? "Etc/GMT" : offset > 0 ? "Etc/GMT-" + offset : "Etc/GMT+" + (-offset);
        jdbc.update("update care_recipients set reminder_to = 'helper', reminder_name = 'Kamla', timezone = ? where id = ?",
                night, f.recipientId());
        fx.consent(f.recipientId());
        vaccines.run(f.recipientId());
        assertThat(wa.sentTo(f.phone())).isEmpty();

        // daytime again: now it goes, in Hindi
        jdbc.update("update care_recipients set timezone = ? where id = ?", zone.getId(), f.recipientId());
        vaccines.run(f.recipientId());
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(wa.sentTo(f.phone()).get(0).text()).startsWith("नमस्ते Kamla जी 🙏\n💉 Aarav के टीके ");
    }

    @Test
    void thePreviewShowsExactlyWhatTheReceiverGets() throws Exception {
        Family f = childWithNani(ZoneId.of("Asia/Kolkata"), "hi");
        Fixtures.Med m = fx.medicine(f, "Calpol syrup", "2.5 ml", 50, LocalTime.of(20, 0));
        fx.dose(m.scheduleId(), Instant.now().plus(2, ChronoUnit.HOURS), "pending", null, null);

        String body = mvc.perform(get("/api/v1/recipients/{id}/whatsapp-preview", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode p = json.readTree(body);
        assertThat(p.path("receiverKind").asText()).isEqualTo("helper");
        assertThat(p.path("receiverName").asText()).isEqualTo("Kamla (Nani)");
        assertThat(p.path("language").asText()).isEqualTo("hi");
        assertThat(p.path("consent").asText()).isEqualTo("agreed");
        assertThat(p.path("checkin").asBoolean()).isFalse();
        assertThat(body).doesNotContain(f.phone()).doesNotContain(f.phone().substring(1));
        JsonNode dose = null;
        for (JsonNode msg : p.path("messages")) if ("dose".equals(msg.path("kind").asText())) dose = msg;
        assertThat(dose).isNotNull();
        assertThat(dose.path("text").asText()).isEqualTo(ReceiverTexts.doseReminder("hi", "helper", "Kamla", "Aarav", "Calpol syrup",
                null, null, "2.5 ml")).contains("मात्रा: *2.5 ml*");
        assertThat(body).doesNotContain("\"kind\":\"checkin\"").contains("\"kind\":\"consent\"").contains("\"kind\":\"alert\"");

        // a stranger sees nothing
        UUID stranger = fx.user("Stranger");
        mvc.perform(get("/api/v1/recipients/{id}/whatsapp-preview", f.recipientId()).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePreviewForAParentHasTheCheckinAndTheTextOfEveryMedicine() throws Exception {
        Family f = fx.family("en", true);
        fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(8, 0));
        JsonNode p = json.readTree(mvc.perform(get("/api/v1/recipients/{id}/whatsapp-preview", f.recipientId())
                        .header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(p.path("receiverKind").asText()).isEqualTo("person");
        assertThat(p.path("checkin").asBoolean()).isTrue();
        assertThat(p.path("doses").get(0).path("text").asText())
                .isEqualTo(MessageTexts.doseReminder("en", "Papa", "Metformin", null, null, "500 mg"));
        assertThat(p.toString()).contains("How are you today?").contains("You may want to call Papa.");
    }

    @Test
    void aHelperReadsTheReminderLanguageWhenSet_elseThePersonsLanguage() {
        String sql = "select " + ReceiverTexts.languageSql("r") + " from (select ?::text reminder_to, 'en'::text language, ?::text reminder_language) r";
        assertThat(jdbc.queryForObject(sql, String.class, "helper", "hi")).isEqualTo("hi");
        assertThat(jdbc.queryForObject(sql, String.class, "caregiver", "hi")).isEqualTo("hi");
        assertThat(jdbc.queryForObject(sql, String.class, "helper", null)).isEqualTo("en");
        assertThat(jdbc.queryForObject(sql, String.class, "person", "hi")).isEqualTo("en");   // the person reads their own language
        // and before the column exists at all
        assertThat(jdbc.queryForObject("select " + ReceiverTexts.languageSql("r") + " from (select 'helper'::text reminder_to, 'hi'::text language) r",
                String.class)).isEqualTo("hi");
    }

    @Test
    void theSchedulerSendsTheNaniTheDoseInHerLanguage() throws Exception {
        Family f = childWithNani(ZoneId.of("Asia/Kolkata"), "hi");
        Fixtures.Med m = fx.medicine(f, "Calpol syrup", "2.5 ml", 50, LocalTime.of(3, 0));
        fx.dose(m.scheduleId(), Instant.now().minus(5, ChronoUnit.MINUTES), "pending", null, null);
        new org.smartgurukul.chhaon.scheduler.SchedulerService(jdbc, wa, true, 60, 180, 120, 24, f.recipientId().toString()).scheduledTick();
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(wa.sentTo(f.phone()).get(0).text())
                .isEqualTo("नमस्ते Kamla जी 🙏\nAarav की दवा का समय हो गया है 💊 *Calpol syrup*\nमात्रा: *2.5 ml*\n\nदवा देने के बाद *1* या 👍 भेजिए ✅");
    }
}
