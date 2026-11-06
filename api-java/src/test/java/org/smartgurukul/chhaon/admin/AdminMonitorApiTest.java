package org.smartgurukul.chhaon.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin Monitor: access rules, the checks that would have caught the 2026-11-05 incidents, the audit trail. */
class AdminMonitorApiTest extends DbTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired JwtService jwt;
    @Autowired AdminHealthService health;
    @Autowired AdminHealthChecker checker;
    @Autowired ObjectMapper json;

    private UUID admin;

    @BeforeEach
    void makeAdmin() {
        wipeMonitorTables();
        admin = fx.user("Owner");
        fx.jdbc().update("update profiles set is_admin = true where id = ?", admin);
    }

    @AfterEach
    void wipeMonitorTables() {
        fx.jdbc().update("delete from admin_alerts");
        fx.jdbc().update("delete from admin_audit");
        fx.jdbc().update("delete from admin_webhook_rejections");
    }

    private String bearer(UUID userId, boolean adminClaim) {
        return "Bearer " + jwt.issueToken(userId, adminClaim);
    }

    private JsonNode getJson(String url) throws Exception {
        String body = mvc.perform(get(url).header("Authorization", bearer(admin, true)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private HealthCheck check(String key) {
        return health.evaluate().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    /** A consented family (fake number) with one medicine whose schedule time does not matter (doses are inserted by hand). */
    private Family family(String phone) {
        return fx.family(fx.track(phone), "en", true, IST);
    }

    // ---------------------------------------------------------------- access

    @Test
    void onlyAnAdminProfileGetsIn() throws Exception {
        Family f = family("+919999900001");
        UUID stranger = fx.user("Stranger");
        List<String> urls = List.of("/api/v1/admin/health", "/api/v1/admin/alerts", "/api/v1/admin/overview", "/api/v1/admin/families",
                "/api/v1/admin/families/" + f.recipientId(), "/api/v1/admin/activity", "/api/v1/admin/upcoming",
                "/api/v1/admin/feedback-inbox", "/api/v1/admin/gateway");
        for (String url : urls) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).header("Authorization", bearer(stranger, false))).andExpect(status().isForbidden());
            // a token that CLAIMS admin is not enough: the database flag decides
            mvc.perform(get(url).header("Authorization", bearer(stranger, true))).andExpect(status().isForbidden());
            mvc.perform(get(url).header("Authorization", bearer(admin, false))).andExpect(status().isOk());
        }
        // a refused look at a family leaves no audit row; the admin's look above left exactly one
        assertThat(fx.jdbc().queryForObject("select count(*) from admin_audit", Integer.class)).isEqualTo(1);
    }

    @Test
    void openingAFamilyIsRecordedInTheAuditTable() throws Exception {
        Family f = family("+919999900002");
        Fixtures.Med med = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(8, 0));
        fx.dose(med.scheduleId(), Instant.now().minus(2, ChronoUnit.HOURS), "taken", Instant.now().minus(2, ChronoUnit.HOURS), null);

        JsonNode detail = getJson("/api/v1/admin/families/" + f.recipientId());
        assertThat(detail.path("recipient").path("name").asText()).isEqualTo("Rajesh");
        assertThat(detail.path("medicines").get(0).path("name").asText()).isEqualTo("Metformin");
        assertThat(detail.path("doses").size()).isEqualTo(1);
        assertThat(detail.path("members").size()).isEqualTo(1);

        Map<String, Object> audit = fx.jdbc().queryForMap("select admin_id, action, recipient_id, circle_id, viewed_at from admin_audit");
        assertThat(audit.get("admin_id")).isEqualTo(admin);
        assertThat(audit.get("action")).isEqualTo("view_family");
        assertThat(audit.get("recipient_id")).isEqualTo(f.recipientId());
        assertThat(audit.get("circle_id")).isEqualTo(f.circleId());

        mvc.perform(get("/api/v1/admin/families/" + UUID.randomUUID()).header("Authorization", bearer(admin, true)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- replies coming in (the 12-hour incident)

    @Test
    void remindersGoingOutWithNoReplyComingInTurnsRedAndOpensThenResolvesAnAlert() throws Exception {
        Family f = family("+919999900003");
        Fixtures.Med med = fx.medicine(f, "Amlodipine", "5 mg", 30, LocalTime.of(9, 0));
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        fx.dose(med.scheduleId(), twoHoursAgo, "sent", twoHoursAgo, Fixtures.messageId());
        fx.dose(med.scheduleId(), twoHoursAgo.plus(15, ChronoUnit.MINUTES), "sent", twoHoursAgo.plus(15, ChronoUnit.MINUTES), Fixtures.messageId());

        HealthCheck replies = check("replies_in");
        assertThat(replies.status()).isEqualTo(HealthCheck.RED);
        assertThat(replies.reason()).isEqualTo("silent");

        List<AdminHealthChecker.Change> changes = checker.run();
        assertThat(changes).contains(new AdminHealthChecker.Change("replies_in", "opened", "red"));
        // a second run keeps the same one open problem (no duplicate row)
        checker.run();
        assertThat(fx.jdbc().queryForObject("select count(*) from admin_alerts where check_key = 'replies_in'", Integer.class)).isEqualTo(1);
        JsonNode alerts = getJson("/api/v1/admin/alerts");
        assertThat(alerts.path("open").toString()).contains("replies_in");

        // the families list flags the silent family too
        JsonNode families = getJson("/api/v1/admin/families");
        JsonNode row = null;
        for (JsonNode r : families) if (r.path("recipient_id").asText().equals(f.recipientId().toString())) row = r;
        assertThat(row).isNotNull();
        assertThat(row.path("badges").toString()).contains("no_reply_2d");
        assertThat(row.path("messages_today").asInt() + row.path("messages_7d").asInt()).isGreaterThan(0);

        // a parent's message arrives: green again, the alert is resolved
        fx.jdbc().update("insert into whatsapp_events (id) values ('gw:in:TESTMSG1')");
        assertThat(check("replies_in").status()).isEqualTo(HealthCheck.GREEN);
        assertThat(checker.run()).contains(new AdminHealthChecker.Change("replies_in", "resolved", "green"));
        assertThat(fx.jdbc().queryForObject(
                "select count(*) from admin_alerts where check_key = 'replies_in' and resolved_at is not null", Integer.class)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- reminders on time (the 14:00 doses sent at 14:31)

    @Test
    void aReminderSentLateTodayIsAmberAndAStuckOneIsRed() {
        Instant scheduled = Instant.now().minus(40, ChronoUnit.MINUTES);
        assumeTrue(LocalDate.ofInstant(scheduled, IST).equals(LocalDate.now(IST)), "too close to midnight IST");
        Family f = family("+919999900004");
        Fixtures.Med med = fx.medicine(f, "Telmisartan", "40 mg", 30, LocalTime.of(14, 0));
        fx.dose(med.scheduleId(), scheduled, "sent", scheduled.plus(31, ChronoUnit.MINUTES), Fixtures.messageId());

        HealthCheck late = check("reminders_on_time");
        assertThat(late.status()).isEqualTo(HealthCheck.AMBER);
        assertThat(late.reason()).isEqualTo("late");
        assertThat(((Number) late.facts().get("late_today")).longValue()).isEqualTo(1);
        assertThat(((Number) late.facts().get("worst_late_minutes")).longValue()).isGreaterThanOrEqualTo(30);

        // a dose 20 minutes overdue that never went out: red
        Fixtures.Med other = fx.medicine(f, "Aspirin", "75 mg", 30, LocalTime.of(14, 15));
        fx.dose(other.scheduleId(), Instant.now().minus(20, ChronoUnit.MINUTES), "pending", null, null);
        HealthCheck stuck = check("reminders_on_time");
        assertThat(stuck.status()).isEqualTo(HealthCheck.RED);
        assertThat(stuck.reason()).isEqualTo("stuck");
    }

    @Test
    void theSchedulerCheckReadsTheHeartbeat() {
        fx.jdbc().update("insert into scheduler_heartbeat (id, last_tick_at) values (1, now() - interval '25 minutes') " +
                "on conflict (id) do update set last_tick_at = excluded.last_tick_at");
        assertThat(check("scheduler").status()).isEqualTo(HealthCheck.RED);
        fx.jdbc().update("update scheduler_heartbeat set last_tick_at = now() where id = 1");
        assertThat(check("scheduler").status()).isEqualTo(HealthCheck.GREEN);
    }

    // ---------------------------------------------------------------- refused webhooks

    @Test
    void refusedWebhookCallsAreCountedWithoutTheirContent() throws Exception {
        mvc.perform(post("/webhooks/whatsapp-gateway").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"message.received\",\"data\":{\"from\":\"919999900005@s.whatsapp.net\"}}")
                        .header("X-Webhook-Signature", "sha256=wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/whatsapp-gateway").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        List<Map<String, Object>> rows = fx.jdbc().queryForList("select * from admin_webhook_rejections");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("reason")).isEqualTo("bad_signature");
        assertThat(rows.get(0).keySet()).containsExactlyInAnyOrder("id", "reason", "http_status", "rejected_at");

        HealthCheck c = check("webhook_rejections");
        assertThat(c.status()).isEqualTo(HealthCheck.RED); // nothing got through in the last hour
        assertThat(c.reason()).isEqualTo("all_rejected");

        fx.jdbc().update("insert into whatsapp_events (id) values ('gw:in:TESTMSG2')");
        assertThat(check("webhook_rejections").status()).isEqualTo(HealthCheck.AMBER);

        JsonNode h = getJson("/api/v1/admin/health");
        assertThat(h.path("webhook_rejections_24h").get(0).path("reason").asText()).isEqualTo("bad_signature");
        assertThat(h.path("webhook_rejections_24h").get(0).path("n").asInt()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- the other views

    @Test
    void healthOverviewUpcomingActivityAndFeedbackAnswer() throws Exception {
        Family f = family("+919999900006");
        Fixtures.Med med = fx.medicine(f, "Thyronorm", "50 mcg", 2, LocalTime.of(7, 0));
        fx.jdbc().update("update medicines set refill_threshold = 5 where id = ?", med.medicineId());
        fx.dose(med.scheduleId(), Instant.now().plus(1, ChronoUnit.HOURS), "pending", null, null);
        fx.dose(med.scheduleId(), Instant.now().minus(3, ChronoUnit.HOURS), "taken", Instant.now().minus(3, ChronoUnit.HOURS), null);
        fx.jdbc().update("update dose_events set responded_at = now() - interval '170 minutes' where schedule_id = ? and status = 'taken'",
                med.scheduleId());
        fx.jdbc().update("insert into feedback (user_id, kind, message) values (?, 'idea', 'Please add a bigger font')", f.userId());

        JsonNode h = getJson("/api/v1/admin/health");
        assertThat(h.path("overall").asText()).isIn("green", "amber", "red");
        assertThat(h.path("checks").size()).isEqualTo(10);
        assertThat(h.path("server").path("git_sha").asText()).isNotBlank();
        assertThat(h.path("server").path("uptime_seconds").asLong()).isGreaterThanOrEqualTo(0);
        JsonNode gw = null;
        for (JsonNode c : h.path("checks")) if (c.path("key").asText().equals("gateway")) gw = c;
        assertThat(gw.path("reason").asText()).isEqualTo("not_configured"); // tests run without a gateway
        assertThat(h.path("volume").path("daily").size()).isEqualTo(8);
        assertThat(check("low_stock").status()).isEqualTo(HealthCheck.AMBER);

        JsonNode o = getJson("/api/v1/admin/overview");
        assertThat(o.path("doses").path("scheduled").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(o.has("pilot")).isTrue();
        mvc.perform(get("/api/v1/admin/overview?date=not-a-date").header("Authorization", bearer(admin, true)))
                .andExpect(status().isBadRequest());

        JsonNode up = getJson("/api/v1/admin/upcoming");
        assertThat(up.toString()).contains("Thyronorm").contains(f.recipientId().toString());

        JsonNode act = getJson("/api/v1/admin/activity?limit=20");
        assertThat(act.toString()).contains("dose_taken");

        JsonNode fb = getJson("/api/v1/admin/feedback-inbox");
        assertThat(fb.get(0).path("message").asText()).isEqualTo("Please add a bigger font");
        assertThat(fb.get(0).path("name").asText()).isEqualTo("Ananya");

        JsonNode families = getJson("/api/v1/admin/families");
        JsonNode row = null;
        for (JsonNode r : families) if (r.path("recipient_id").asText().equals(f.recipientId().toString())) row = r;
        assertThat(row.path("badges").toString()).contains("low_stock");
        assertThat(row.path("median_reply_minutes_7d").asDouble()).isBetween(9.0, 11.0);
        assertThat(row.path("caregivers").get(0).path("name").asText()).isEqualTo("Ananya");
    }

    // ---------------------------------------------------------------- masked phone numbers

    /** A phone-shaped run of digits on its own (not inside a uuid, not the fraction of a timestamp): 8 or more, maybe after "+". */
    private static final java.util.regex.Pattern LONG_DIGITS = java.util.regex.Pattern.compile("(?<![.\\w-])\\+?\\d{8,}(?![\\w-])");

    @Test
    void noAdminEndpointSendsAFullPhoneNumber() throws Exception {
        String parent = "+919999900011";
        String caregiver = "+919999900012";
        String alerts = "+919999900013";
        String emergency = "+919999900014";
        String doctor = "+919999900015";
        String reminderUser = "+919999900016";
        Family f = family(parent);
        fx.jdbc().update("update profiles set phone = ? where id = ?", caregiver, f.userId());
        fx.alertsOn(f.userId(), fx.track(alerts));
        UUID sibling = fx.member(f, "Rohan");
        fx.jdbc().update("update profiles set phone = ? where id = ?", fx.track(reminderUser), sibling);
        fx.jdbc().update("update care_recipients set reminder_user_id = ?, emergency_contacts = ?::jsonb, doctor_contacts = ?::jsonb where id = ?",
                sibling, "[{\"name\":\"Uncle\",\"phone\":\"" + emergency + "\"}]", "[{\"name\":\"Dr Rao\",\"phone\":\"" + doctor + "\"}]",
                f.recipientId());
        Fixtures.Med med = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(8, 0));
        fx.dose(med.scheduleId(), Instant.now().plus(1, ChronoUnit.HOURS), "pending", null, null);
        fx.dose(med.scheduleId(), Instant.now().minus(2, ChronoUnit.HOURS), "taken", Instant.now().minus(2, ChronoUnit.HOURS), null);
        fx.jdbc().update("insert into feedback (user_id, kind, message) values (?, 'idea', 'Bigger buttons please')", f.userId());

        List<String> urls = List.of("/api/v1/admin/health", "/api/v1/admin/alerts", "/api/v1/admin/overview", "/api/v1/admin/families",
                "/api/v1/admin/families/" + f.recipientId(), "/api/v1/admin/activity", "/api/v1/admin/upcoming",
                "/api/v1/admin/feedback-inbox", "/api/v1/admin/gateway?fresh=true", "/api/v1/admin/stats", "/api/v1/admin/love-stats",
                "/api/v1/admin/feedback");
        for (String url : urls) {
            String body = mvc.perform(get(url).header("Authorization", bearer(admin, true)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(LONG_DIGITS.matcher(body).find()).as("a full number in %s: %s", url, body).isFalse();
            for (String p : List.of(parent, caregiver, alerts, emergency, doctor, reminderUser)) {
                assertThat(body).as(url).doesNotContain(p.substring(3));   // not even without the country code
            }
        }

        // what the Monitor shows instead: country code + last 4 digits
        JsonNode detail = getJson("/api/v1/admin/families/" + f.recipientId());
        assertThat(detail.path("recipient").path("whatsapp_phone").asText()).isEqualTo("+91 ••••• •0011");
        assertThat(detail.path("recipient").path("emergency_contacts").get(0).path("phone").asText()).isEqualTo("+91 ••••• •0014");
        assertThat(detail.path("recipient").path("emergency_contacts").get(0).path("name").asText()).isEqualTo("Uncle");
        assertThat(detail.path("recipient").path("doctor_contacts").get(0).path("phone").asText()).isEqualTo("+91 ••••• •0015");
        assertThat(detail.path("recipient").path("name").asText()).isEqualTo("Rajesh");   // names stay
        JsonNode owner = null;
        for (JsonNode m : detail.path("members")) if (m.path("user_id").asText().equals(f.userId().toString())) owner = m;
        assertThat(owner.path("phone").asText()).isEqualTo("+91 ••••• •0012");
        assertThat(owner.path("alert_phone").asText()).isEqualTo("+91 ••••• •0013");

        JsonNode row = null;
        for (JsonNode r : getJson("/api/v1/admin/families")) if (r.path("recipient_id").asText().equals(f.recipientId().toString())) row = r;
        assertThat(row.path("whatsapp_phone").asText()).isEqualTo("+91 ••••• •0011");
        assertThat(row.path("reminder_user_phone").asText()).isEqualTo("+91 ••••• •0016");
        assertThat(row.path("caregivers").toString()).contains("+91 ••••• •0012").contains("Ananya");
        assertThat(row.path("badges").toString()).doesNotContain("consent_pending");   // badges still read the real values

        assertThat(getJson("/api/v1/admin/upcoming").toString()).contains("+91 ••••• •0011").contains("Metformin");
        assertThat(getJson("/api/v1/admin/feedback-inbox").get(0).path("phone").asText()).isEqualTo("+91 ••••• •0012");
    }

    @Test
    void timestampsComeBackAsUtcIsoText() {
        assertThat(Rows.value(Timestamp.from(Instant.parse("2026-11-05T08:30:00Z")))).isEqualTo("2026-11-05T08:30:00Z");
    }
}
