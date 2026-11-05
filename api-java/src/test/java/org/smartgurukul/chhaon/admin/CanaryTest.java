package org.smartgurukul.chhaon.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.scheduler.SchedulerService;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.FakeMedicinePhotoStore;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.GatewayClient;
import org.smartgurukul.chhaon.support.RecordingWhatsAppSender.Sent;
import org.smartgurukul.chhaon.whatsapp.AlertDelivery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The canary family (docs/canary.md, migration 20261106000055): a spare WhatsApp number that answers its own reminder by
 * auto-reply, so the Monitor's "canary" check proves the whole chain. Covers the setup function, the text-only reminder (also
 * with polls on, never a photo), that a missed canary dose alerts nobody, that the pilot numbers leave it out, and the check.
 */
class CanaryTest extends DbTest {

    /** Fake test numbers only (+91 99999 xxxxx). */
    private static final String CANARY_PHONE = "+919999900061";
    private static final String SIBLING_PHONE = "+919999900062";
    private static final UUID CANARY_USER = UUID.fromString("cacacaca-0000-4000-8000-000000000001");
    private static final UUID CANARY_CIRCLE = UUID.fromString("cacacaca-0000-4000-8000-000000000002");
    private static final UUID CANARY = UUID.fromString("cacacaca-0000-4000-8000-000000000003");
    private static final UUID CANARY_MEDICINE = UUID.fromString("cacacaca-0000-4000-8000-000000000004");
    /** The keyword of the spare phone's auto-reply rule (docs/canary.md). */
    private static final String KEYWORD = "Medicine time";

    @Autowired JdbcTemplate jdbc;
    @Autowired AlertDelivery alerts;
    @Autowired FakeMedicinePhotoStore storage;
    @Autowired AdminHealthService health;
    @Autowired AdminHealthChecker checker;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    private UUID admin;

    @BeforeEach
    void start() {
        removeCanary();
        jdbc.update("delete from admin_alerts");
        fx.track(CANARY_PHONE);
        fx.track(SIBLING_PHONE);
        admin = fx.user("Owner");
        jdbc.update("update profiles set is_admin = true where id = ?", admin);
    }

    @AfterEach
    void removeCanary() {
        wa.setPollsEnabled(false);
        storage.reset();
        jdbc.queryForObject("select public.canary_remove()", Integer.class);
        jdbc.update("delete from admin_alerts");
    }

    private UUID setUp() {
        return jdbc.queryForObject("select public.canary_setup(?)", UUID.class, CANARY_PHONE);
    }

    private UUID schedule(String time) {
        return jdbc.queryForObject("select id from medicine_schedules where medicine_id = ? and time_of_day = cast(? as time)",
                UUID.class, CANARY_MEDICINE, time);
    }

    private SchedulerService scheduler() {
        SchedulerService s = new SchedulerService(jdbc, wa, alerts, true, 60, 180, 120, 24, CANARY.toString());
        s.setMedicinePhotoStore(storage);
        return s;
    }

    private static byte[] jpeg() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpeg", out);
        return out.toByteArray();
    }

    private HealthCheck check() {
        return health.evaluate().stream().filter(c -> c.key().equals("canary")).findFirst().orElseThrow();
    }

    private JsonNode getJson(String url) throws Exception {
        String body = mvc.perform(get(url).header("Authorization", "Bearer " + jwt.issueToken(admin, true)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    // ---------------------------------------------------------------- setup

    @Test
    void theSetupMakesOneConsistentFamilyAndCanRunTwice() {
        assertThat(setUp()).isEqualTo(CANARY);
        assertThat(setUp()).isEqualTo(CANARY);   // idempotent: same rows

        Map<String, Object> r = jdbc.queryForMap("select * from care_recipients where id = ?", CANARY);
        assertThat(r.get("name")).isEqualTo("Test Papa");
        assertThat(r.get("whatsapp_phone")).isEqualTo(CANARY_PHONE);
        assertThat(r.get("timezone")).isEqualTo("Asia/Kolkata");
        assertThat(r.get("language")).isEqualTo("en");
        assertThat(r.get("consent_at")).isNotNull();
        assertThat(r.get("opted_out_at")).isNull();
        assertThat(r.get("checkin_enabled")).isEqualTo(false);
        assertThat(r.get("reminder_to")).isEqualTo("person");
        assertThat(r.get("is_canary")).isEqualTo(true);
        assertThat(r.get("circle_id")).isEqualTo(CANARY_CIRCLE);

        Map<String, Object> p = jdbc.queryForMap("select name, is_canary, alert_phone, alert_consent_at, plan from profiles where id = ?", CANARY_USER);
        assertThat(p.get("name")).isEqualTo("Chhaon Canary");
        assertThat(p.get("is_canary")).isEqualTo(true);
        assertThat(p.get("alert_phone")).isNull();
        assertThat(p.get("alert_consent_at")).isNull();
        assertThat(jdbc.queryForList("select user_id::text || ':' || role from care_circle_members where circle_id = ?", String.class, CANARY_CIRCLE))
                .containsExactly(CANARY_USER + ":owner");

        Map<String, Object> m = jdbc.queryForMap("select name, stock_count, refill_threshold, photo_url, active from medicines where recipient_id = ?", CANARY);
        assertThat(m.get("name")).isEqualTo("Test tablet");
        assertThat(m.get("stock_count")).isNull();
        assertThat(m.get("refill_threshold")).isNull();
        assertThat(m.get("photo_url")).isNull();
        assertThat(jdbc.queryForList("select to_char(time_of_day, 'HH24:MI') from medicine_schedules where medicine_id = ? order by 1",
                String.class, CANARY_MEDICINE)).containsExactly("02:00", "06:00", "10:00", "14:00", "18:00", "22:00");
        assertThat(jdbc.queryForObject("select count(*) from paused_recipients() where recipient_id = ?", Integer.class, CANARY)).isZero();

        // a fake or placeholder number is refused; a real family's number too
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.queryForObject("select public.canary_setup('+9199999xxxxx')", UUID.class))
                .hasMessageContaining("E.164");
        Fixtures.Family real = fx.family(fx.track("+919999900063"), "en", true, java.time.ZoneId.of("Asia/Kolkata"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.queryForObject("select public.canary_setup(?)", UUID.class, real.phone()))
                .hasMessageContaining("real person");

        assertThat(jdbc.queryForObject("select public.canary_remove()", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from medicine_schedules where medicine_id = ?", Integer.class, CANARY_MEDICINE)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from profiles where is_canary", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select public.canary_remove()", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- the reminder: always text

    @Test
    void theCanaryAlwaysGetsTheTextNeverAPollNorAPhotoAndItsAutoReplyIsUnderstood() throws Exception {
        setUp();
        wa.setPollsEnabled(true);
        storage.switchOn(true);
        storage.put(CANARY + "/strip.jpg", jpeg());
        jdbc.update("update medicines set photo_url = ? where id = ?", CANARY + "/strip.jpg", CANARY_MEDICINE);
        UUID dose = fx.dose(schedule("02:00"), Instant.now().minus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES), "pending", null, null);

        scheduler().tick(CANARY);

        assertThat(wa.pollsTo(CANARY_PHONE)).isEmpty();
        assertThat(wa.images()).isEmpty();
        List<Sent> sent = wa.sentTo(CANARY_PHONE);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).text()).contains(KEYWORD);
        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
        String messageId = (String) fx.dose(dose).get("whatsapp_message_id");
        assertThat(messageId).isNotBlank();

        // the spare phone's auto-reply: "1", quoting the reminder (the gateway's AutoReply sends it with quoted: msg)
        assertThat(gateway.post(gateway.received(Fixtures.messageId(), GatewayClient.jid(CANARY_PHONE), "1", messageId)).status()).isEqualTo(200);
        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
        // our thank-you must NOT contain the auto-reply keyword, or the phone would answer it again (a reply loop)
        List<Sent> after = wa.sentTo(CANARY_PHONE);
        assertThat(after).hasSizeGreaterThan(1);
        after.subList(1, after.size()).forEach(s -> assertThat(s.text().toLowerCase()).doesNotContain(KEYWORD.toLowerCase()));

        HealthCheck c = check();
        assertThat(c.status()).isEqualTo(HealthCheck.GREEN);
        assertThat(c.reason()).isEqualTo("ok");
        assertThat(((Number) c.facts().get("ok_24h")).longValue()).isEqualTo(1);
        assertThat(((Number) c.facts().get("round_trip_seconds")).longValue()).isLessThan(600);
    }

    // ---------------------------------------------------------------- nobody is alerted

    @Test
    void aMissedCanaryDoseAlertsNobodyEvenACaregiverWithWhatsAppAlertsOn() {
        setUp();
        // worst case: someone with alerts confirmed on WhatsApp joined the canary circle, and the dummy caregiver has alerts on
        UUID sibling = fx.user("Sibling");
        jdbc.update("insert into care_circle_members (circle_id, user_id, role) values (?, ?, 'member')", CANARY_CIRCLE, sibling);
        fx.alertsOn(sibling, SIBLING_PHONE);
        Instant sentAt = Instant.now().minus(2, ChronoUnit.HOURS);
        UUID dose = fx.dose(schedule("06:00"), sentAt, "sent", sentAt, Fixtures.messageId());
        UUID notSent = fx.dose(schedule("10:00"), Instant.now().minus(3, ChronoUnit.HOURS), "pending", null, null);

        scheduler().tick(CANARY);

        assertThat(fx.doseStatus(dose)).isEqualTo("missed");
        assertThat(fx.doseStatus(notSent)).isEqualTo("skipped");
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id in (?, ?)", Integer.class, CANARY_USER, sibling)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notifications where payload->>'recipient_id' = ?", Integer.class, CANARY.toString())).isZero();
        assertThat(wa.sentTo(SIBLING_PHONE)).isEmpty();
        // the Monitor sees it instead
        assertThat(check().status()).isEqualTo(HealthCheck.RED);
        assertThat(check().reason()).isEqualTo("no_answer");
    }

    // ---------------------------------------------------------------- the check

    @Test
    void theCheckIsGreyWithoutACanaryAndNeverOpensAnAlertThen() {
        HealthCheck c = check();
        assertThat(c.status()).isEqualTo(HealthCheck.GREY);
        assertThat(c.reason()).isEqualTo("not_set_up");
        assertThat(c.problem()).isFalse();
        checker.run();
        assertThat(jdbc.queryForObject("select count(*) from admin_alerts where check_key = 'canary'", Integer.class)).isZero();

        setUp();   // just set up, nothing sent yet: still grey
        assertThat(check().status()).isEqualTo(HealthCheck.GREY);
        assertThat(check().reason()).isEqualTo("waiting_first");
    }

    @Test
    void noAnswerWithinTenMinutesIsRedOpensAnAlertAndAnAnswerResolvesIt() {
        setUp();
        Instant sentAt = Instant.now().minus(15, ChronoUnit.MINUTES);
        UUID dose = fx.dose(schedule("14:00"), sentAt, "sent", sentAt, Fixtures.messageId());

        HealthCheck red = check();
        assertThat(red.status()).isEqualTo(HealthCheck.RED);
        assertThat(red.reason()).isEqualTo("no_answer");
        assertThat(checker.run()).contains(new AdminHealthChecker.Change("canary", "opened", "red"));

        // a reminder sent 3 minutes ago and not answered yet does not count yet; the older one still decides
        fx.dose(schedule("18:00"), Instant.now().minus(3, ChronoUnit.MINUTES), "sent", Instant.now().minus(3, ChronoUnit.MINUTES), Fixtures.messageId());
        assertThat(check().reason()).isEqualTo("no_answer");

        // answered, but 15 minutes late: amber (the 3-minute one still does not count)
        jdbc.update("update dose_events set status = 'taken', responded_at = now() where id = ?", dose);
        assertThat(check().status()).isEqualTo(HealthCheck.AMBER);
        assertThat(check().reason()).isEqualTo("slow");

        // answered within a minute: green, the alert is resolved
        jdbc.update("delete from dose_events where schedule_id = ?", schedule("18:00"));
        jdbc.update("update dose_events set responded_at = sent_at + interval '40 seconds' where id = ?", dose);
        HealthCheck green = check();
        assertThat(green.status()).isEqualTo(HealthCheck.GREEN);
        assertThat(((Number) green.facts().get("round_trip_seconds")).longValue()).isEqualTo(40);
        assertThat(((Number) green.facts().get("sent_24h")).longValue()).isEqualTo(1);
        assertThat(((Number) green.facts().get("ok_24h")).longValue()).isEqualTo(1);
        assertThat(checker.run()).contains(new AdminHealthChecker.Change("canary", "resolved", "green"));
    }

    @Test
    void noCanaryReminderForFiveHoursIsRed() {
        setUp();
        jdbc.update("update care_recipients set created_at = now() - interval '2 days' where id = ?", CANARY);
        HealthCheck never = check();
        assertThat(never.status()).isEqualTo(HealthCheck.RED);
        assertThat(never.reason()).isEqualTo("not_sent");

        Instant old = Instant.now().minus(6, ChronoUnit.HOURS);
        UUID dose = fx.dose(schedule("22:00"), old, "taken", old, Fixtures.messageId());
        jdbc.update("update dose_events set responded_at = sent_at + interval '20 seconds' where id = ?", dose);
        assertThat(check().reason()).isEqualTo("not_sent");   // answered, but that was 6 hours ago: the scheduler stopped
    }

    // ---------------------------------------------------------------- left out of the numbers

    @Test
    void theCanaryIsLeftOutOfThePilotNumbersTheDayTotalsAndTheActivityButMarkedInTheFamilies() throws Exception {
        JsonNode before = getJson("/api/v1/admin/overview");
        assertThat(before.path("pilot").isObject()).isTrue();

        setUp();
        Instant sentAt = Instant.now().minus(30, ChronoUnit.MINUTES);
        UUID dose = fx.dose(schedule("02:00"), sentAt, "taken", sentAt, Fixtures.messageId());
        jdbc.update("update dose_events set responded_at = sent_at + interval '30 seconds' where id = ?", dose);

        JsonNode after = getJson("/api/v1/admin/overview");
        assertThat(after.path("pilot").path("parents").path("total").asInt()).isEqualTo(before.path("pilot").path("parents").path("total").asInt());
        assertThat(after.path("pilot").path("reminders_7d").path("sent").asInt()).isEqualTo(before.path("pilot").path("reminders_7d").path("sent").asInt());
        assertThat(after.path("pilot").path("medicines").path("active").asInt()).isEqualTo(before.path("pilot").path("medicines").path("active").asInt());
        assertThat(after.path("doses").path("sent").asInt()).isEqualTo(before.path("doses").path("sent").asInt());

        JsonNode activity = getJson("/api/v1/admin/activity");
        for (JsonNode a : activity) assertThat(a.path("recipient_id").asText()).isNotEqualTo(CANARY.toString());

        JsonNode families = getJson("/api/v1/admin/families");
        JsonNode lastRow = families.get(families.size() - 1);
        assertThat(lastRow.path("recipient_id").asText()).isEqualTo(CANARY.toString());
        assertThat(lastRow.path("is_canary").asBoolean()).isTrue();
        assertThat(lastRow.path("badges").toString()).contains("canary");
    }
}
