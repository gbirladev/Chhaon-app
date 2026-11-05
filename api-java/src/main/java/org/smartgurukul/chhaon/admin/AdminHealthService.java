package org.smartgurukul.chhaon.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.smartgurukul.chhaon.admin.HealthCheck.AMBER;
import static org.smartgurukul.chhaon.admin.HealthCheck.GREEN;
import static org.smartgurukul.chhaon.admin.HealthCheck.RED;

/**
 * The Monitor's health checks (docs/admin-monitor.md explains each one and what to do when it is red). Whole-database, read-only
 * (service role), totals only: nothing here names a family, so the results can be stored in admin_alerts and shown anywhere.
 * Any single check that fails to run becomes an amber "check_failed" instead of breaking the others.
 */
@Service
public class AdminHealthService {

    private static final Logger log = LoggerFactory.getLogger(AdminHealthService.class);
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final GatewayProbe gateway;
    private final boolean schedulerEnabledHere;
    private final int volumeAmberPerDay;

    public AdminHealthService(JdbcTemplate jdbc, GatewayProbe gateway,
                              @Value("${chhaon.scheduler.enabled:false}") boolean schedulerEnabledHere,
                              @Value("${chhaon.admin.volume-amber-per-day:200}") int volumeAmberPerDay) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.schedulerEnabledHere = schedulerEnabledHere;
        this.volumeAmberPerDay = volumeAmberPerDay;
    }

    public List<HealthCheck> evaluate() {
        List<HealthCheck> out = new ArrayList<>();
        out.add(safe("scheduler", this::scheduler));
        out.add(safe("reminders_on_time", this::remindersOnTime));
        out.add(safe("replies_in", this::repliesIn));
        out.add(safe("gateway", this::gatewayCheck));
        out.add(safe("webhook_rejections", this::webhookRejections));
        out.add(safe("failed_sends", this::failedSends));
        out.add(safe("whatsapp_volume", this::volumeCheck));
        out.add(safe("consent_pending", this::consentPending));
        out.add(safe("low_stock", this::lowStock));
        return out;
    }

    private interface CheckFn { HealthCheck run(); }

    private HealthCheck safe(String key, CheckFn fn) {
        try {
            return fn.run();
        } catch (Exception e) {
            log.warn("admin health check {} failed: {}", key, e.getClass().getSimpleName());
            return new HealthCheck(key, AMBER, "check_failed", "The check itself could not run (" + e.getClass().getSimpleName() + ").",
                    null, facts());
        }
    }

    static Map<String, Object> facts(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], v instanceof Instant t ? t.toString() : v);
        }
        return m;
    }

    private static Instant instant(Object v) {
        return v instanceof Timestamp t ? t.toInstant() : null;
    }

    // ---------------------------------------------------------------- scheduler

    HealthCheck scheduler() {
        List<Timestamp> rows = jdbc.queryForList("select last_tick_at from scheduler_heartbeat where id = 1", Timestamp.class);
        Instant last = rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toInstant();
        if (last == null) {
            return new HealthCheck("scheduler", AMBER, "never", "The reminder job has never recorded a tick.", null,
                    facts("enabled_here", schedulerEnabledHere));
        }
        long minutes = Duration.between(last, Instant.now()).toMinutes();
        Map<String, Object> f = facts("last_tick_at", last, "minutes_ago", minutes, "enabled_here", schedulerEnabledHere);
        if (minutes > 10) {
            return new HealthCheck("scheduler", RED, "stale", "The reminder job last ran " + minutes + " minutes ago (it should run every 5).",
                    last, f);
        }
        return new HealthCheck("scheduler", GREEN, "ok", "The reminder job ran " + minutes + " minutes ago.", last, f);
    }

    // ---------------------------------------------------------------- reminders on time

    HealthCheck remindersOnTime() {
        Map<String, Object> stuck = jdbc.queryForMap(
                "select count(*) n, min(scheduled_at) oldest from dose_events " +
                "where status = 'pending' and scheduled_at < now() - interval '15 minutes' and scheduled_at > now() - interval '24 hours'");
        Map<String, Object> late = jdbc.queryForMap(
                "select count(*) n, coalesce(max(floor(extract(epoch from sent_at - scheduled_at) / 60)), 0)::int worst " +
                "from dose_events where sent_at is not null and sent_at - scheduled_at > interval '15 minutes' " +
                "and (scheduled_at at time zone 'Asia/Kolkata')::date = (now() at time zone 'Asia/Kolkata')::date");
        Long notSent = jdbc.queryForObject(
                "select count(*) from (select distinct payload::text, date_trunc('minute', created_at) from notifications " +
                "where type = 'reminder_not_sent' and created_at > now() - interval '24 hours') x", Long.class);
        long stuckN = Rows.num(stuck.get("n"));
        long lateN = Rows.num(late.get("n"));
        long worst = Rows.num(late.get("worst"));
        long notSentN = notSent == null ? 0 : notSent;
        Map<String, Object> f = facts("stuck", stuckN, "late_today", lateN, "worst_late_minutes", worst, "not_sent_24h", notSentN,
                "oldest_stuck_at", instant(stuck.get("oldest")));
        if (stuckN > 0) {
            return new HealthCheck("reminders_on_time", RED, "stuck",
                    stuckN + " reminder(s) are more than 15 minutes overdue and still not sent.", instant(stuck.get("oldest")), f);
        }
        if (lateN > 0 || notSentN > 0) {
            return new HealthCheck("reminders_on_time", AMBER, "late",
                    lateN + " reminder(s) went out more than 15 minutes late today (worst " + worst + " min); "
                            + notSentN + " were never sent in 24 h.", null, f);
        }
        return new HealthCheck("reminders_on_time", GREEN, "ok", "Every reminder due went out on time.", null, f);
    }

    // ---------------------------------------------------------------- replies coming in

    HealthCheck repliesIn() {
        Map<String, Object> r = jdbc.queryForMap(
                "select " +
                "(select count(*) from dose_events where sent_at > now() - interval '3 hours' and sent_at < now() - interval '15 minutes') " +
                " + (select count(*) from checkins where sent_at > now() - interval '3 hours' and sent_at < now() - interval '15 minutes') sent3h, " +
                "(select count(*) from dose_events where sent_at > now() - interval '24 hours') " +
                " + (select count(*) from checkins where sent_at > now() - interval '24 hours') sent24h, " +
                "(select count(*) from whatsapp_events where id like 'gw:in:%' and received_at > now() - interval '3 hours') in3h, " +
                "(select count(*) from whatsapp_events where id like 'gw:in:%' and received_at > now() - interval '24 hours') in24h, " +
                "(select max(received_at) from whatsapp_events where id like 'gw:in:%') last_in, " +
                "(select min(sent_at) from dose_events where sent_at > now() - interval '3 hours') first_sent3h");
        long sent3h = Rows.num(r.get("sent3h"));
        long sent24h = Rows.num(r.get("sent24h"));
        long in3h = Rows.num(r.get("in3h"));
        long in24h = Rows.num(r.get("in24h"));
        Instant lastIn = instant(r.get("last_in"));
        Map<String, Object> f = facts("sent_3h", sent3h, "inbound_3h", in3h, "sent_24h", sent24h, "inbound_24h", in24h,
                "last_inbound_at", lastIn);
        if (sent3h >= 2 && in3h == 0) {
            return new HealthCheck("replies_in", RED, "silent",
                    sent3h + " reminders went out in the last 3 hours but no WhatsApp message came in. Is the gateway webhook set?",
                    lastIn != null ? lastIn : instant(r.get("first_sent3h")), f);
        }
        if (sent24h >= 2 && in24h == 0) {
            return new HealthCheck("replies_in", AMBER, "quiet",
                    sent24h + " reminders went out in 24 hours and no WhatsApp message came in.", lastIn, f);
        }
        return new HealthCheck("replies_in", GREEN, "ok", in3h + " message(s) came in during the last 3 hours.", lastIn, f);
    }

    // ---------------------------------------------------------------- gateway

    HealthCheck gatewayCheck() {
        GatewayProbe.Status s = gateway.status();
        Instant lastOut = lastOutbound();
        Map<String, Object> f = facts("configured", s.configured(), "reachable", s.reachable(), "session_name", s.sessionName(),
                "session_status", s.sessionStatus(), "number", s.number(), "webhooks", s.webhooks(),
                "webhook_targets", s.webhookTargets(), "error", s.error(), "checked_at", s.checkedAt(), "last_outbound_at", lastOut);
        if (!s.configured()) {
            return new HealthCheck("gateway", AMBER, "not_configured",
                    "No gateway address is set on this server (dry-run: nothing is really sent).", null, f);
        }
        if (!s.reachable()) {
            return new HealthCheck("gateway", RED, "unreachable", "The gateway did not answer (" + s.error() + ").", null, f);
        }
        if ("session_name_not_found".equals(s.error())) {
            return new HealthCheck("gateway", RED, "no_session", "The gateway has no session named '" + s.sessionName() + "'.", null, f);
        }
        if (!"CONNECTED".equals(s.sessionStatus())) {
            return new HealthCheck("gateway", RED, "logged_out",
                    "The WhatsApp session is " + s.sessionStatus() + " (not connected): scan the QR code again.", null, f);
        }
        if (s.webhooks() == null) {
            return new HealthCheck("gateway", AMBER, "webhook_unknown", "Connected, but the webhook list could not be read.", null, f);
        }
        if (s.webhooks() == 0) {
            return new HealthCheck("gateway", RED, "no_webhook",
                    "Connected, but no active webhook sends incoming messages to us: parent replies are lost.", null, f);
        }
        return new HealthCheck("gateway", GREEN, "ok", "Connected, " + s.webhooks() + " webhook(s) for incoming messages.", null, f);
    }

    Instant lastOutbound() {
        return instant(jdbc.queryForMap("select greatest((select max(sent_at) from dose_events), (select max(sent_at) from checkins), " +
                "(select max(whatsapp_sent_at) from notifications)) last_at").get("last_at"));
    }

    // ---------------------------------------------------------------- webhook rejections

    HealthCheck webhookRejections() {
        Map<String, Object> r = jdbc.queryForMap(
                "select (select count(*) from admin_webhook_rejections where rejected_at > now() - interval '1 hour') rej1h, " +
                "(select count(*) from admin_webhook_rejections where rejected_at > now() - interval '24 hours') rej24h, " +
                "(select max(rejected_at) from admin_webhook_rejections) last_at, " +
                "(select count(*) from whatsapp_events where id like 'gw:in:%' and received_at > now() - interval '1 hour') in1h");
        long rej1h = Rows.num(r.get("rej1h"));
        long in1h = Rows.num(r.get("in1h"));
        Map<String, Object> f = facts("rejected_1h", rej1h, "rejected_24h", Rows.num(r.get("rej24h")), "accepted_messages_1h", in1h,
                "last_rejected_at", instant(r.get("last_at")), "by_reason_24h", rejectionsByReason());
        if (rej1h > 0 && in1h == 0) {
            return new HealthCheck("webhook_rejections", RED, "all_rejected",
                    "Every gateway webhook call in the last hour was refused (" + rej1h + "). Check WHATSAPP_WEBHOOK_SECRET.",
                    instant(r.get("last_at")), f);
        }
        if (rej1h > 0) {
            return new HealthCheck("webhook_rejections", AMBER, "some_rejected",
                    rej1h + " gateway webhook call(s) were refused in the last hour.", instant(r.get("last_at")), f);
        }
        return new HealthCheck("webhook_rejections", GREEN, "ok", "No gateway webhook call was refused in the last hour.", null, f);
    }

    public List<Map<String, Object>> rejectionsByReason() {
        return Rows.clean(jdbc.queryForList(
                "select reason, max(http_status) http_status, count(*) n, max(rejected_at) last_at from admin_webhook_rejections " +
                "where rejected_at > now() - interval '24 hours' group by reason order by n desc"));
    }

    // ---------------------------------------------------------------- failed sends

    HealthCheck failedSends() {
        Map<String, Object> r = jdbc.queryForMap(
                "select (select count(distinct coalesce(payload->>'dose_event_id', id::text)) from notifications " +
                "        where type = 'whatsapp_failed' and created_at > now() - interval '24 hours') failed, " +
                "(select max(created_at) from notifications where type = 'whatsapp_failed') last_failed, " +
                "(select count(*) from notifications where whatsapp_attempts > 0 and whatsapp_sent_at is null " +
                "        and whatsapp_skipped_at is null and created_at > now() - interval '24 hours') alerts_retrying");
        long failed = Rows.num(r.get("failed"));
        long retrying = Rows.num(r.get("alerts_retrying"));
        Map<String, Object> f = facts("failed_24h", failed, "alerts_retrying", retrying, "last_failed_at", instant(r.get("last_failed")));
        if (failed > 0 || retrying > 0) {
            return new HealthCheck("failed_sends", AMBER, "failures",
                    failed + " reminder(s) reported as not delivered and " + retrying + " family alert(s) failing to send in 24 h.",
                    instant(r.get("last_failed")), f);
        }
        return new HealthCheck("failed_sends", GREEN, "ok", "No failed WhatsApp sends in 24 hours.", null, f);
    }

    // ---------------------------------------------------------------- volume

    /** Outbound WhatsApp messages per IST day, last 8 days (today included), from every table that records a send. */
    public Map<String, Object> volume() {
        List<Map<String, Object>> days = jdbc.queryForList(
                "with sends(ts) as (" +
                "  select sent_at from dose_events where sent_at > now() - interval '9 days'" +
                "  union all select sent_at from checkins where sent_at > now() - interval '9 days'" +
                "  union all select whatsapp_sent_at from notifications where whatsapp_sent_at > now() - interval '9 days'" +
                "  union all select reminded_day_before_at from appointments where reminded_day_before_at > now() - interval '9 days'" +
                "  union all select reminded_morning_at from appointments where reminded_morning_at > now() - interval '9 days'" +
                "  union all select requested_at from consent_requests where requested_at > now() - interval '9 days'" +
                "  union all select sent_at from weekly_summary_sends where sent_at > now() - interval '9 days'" +
                "  union all select sent_at from reactions where sent_at > now() - interval '9 days'" +
                ") select (ts at time zone 'Asia/Kolkata')::date d, count(*) n from sends group by 1");
        Map<LocalDate, Long> byDay = new LinkedHashMap<>();
        for (Map<String, Object> d : days) byDay.put(((java.sql.Date) d.get("d")).toLocalDate(), Rows.num(d.get("n")));
        LocalDate today = LocalDate.now(IST);
        long todayN = byDay.getOrDefault(today, 0L);
        long prev7 = 0;
        List<Map<String, Object>> series = new ArrayList<>();
        for (int i = 7; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            long n = byDay.getOrDefault(day, 0L);
            if (i > 0) prev7 += n;
            series.add(facts("date", day.toString(), "count", n));
        }
        double avg = prev7 / 7.0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("today", todayN);
        m.put("last_7_days", prev7);
        m.put("daily_average_7d", Math.round(avg * 10) / 10.0);
        m.put("amber_per_day", volumeAmberPerDay);
        m.put("daily", series);
        return m;
    }

    HealthCheck volumeCheck() {
        Map<String, Object> v = volume();
        long today = Rows.num(v.get("today"));
        double avg = ((Number) v.get("daily_average_7d")).doubleValue();
        Map<String, Object> f = facts("today", today, "daily_average_7d", avg, "amber_per_day", volumeAmberPerDay);
        if (today > volumeAmberPerDay) {
            return new HealthCheck("whatsapp_volume", AMBER, "high",
                    today + " messages sent today (over " + volumeAmberPerDay + "): high volume on an unofficial number risks a ban.", null, f);
        }
        if (today >= 30 && avg > 0 && today > 3 * avg) {
            return new HealthCheck("whatsapp_volume", AMBER, "jump",
                    today + " messages today, more than 3x the 7-day average (" + avg + ").", null, f);
        }
        return new HealthCheck("whatsapp_volume", GREEN, "ok", today + " messages sent today.", null, f);
    }

    // ---------------------------------------------------------------- consent, stock

    HealthCheck consentPending() {
        Map<String, Object> r = jdbc.queryForMap(
                "select count(*) n, count(*) filter (where coalesce(consent_requested_at, created_at) < now() - interval '24 hours') old, " +
                "min(coalesce(consent_requested_at, created_at)) oldest from care_recipients " +
                "where consent_at is null and opted_out_at is null and whatsapp_phone is not null");
        long n = Rows.num(r.get("n"));
        long old = Rows.num(r.get("old"));
        Map<String, Object> f = facts("waiting", n, "waiting_over_24h", old);
        if (old > 0) {
            return new HealthCheck("consent_pending", AMBER, "waiting",
                    old + " person(s) have not said yes on WhatsApp for more than a day: no reminders reach them.", instant(r.get("oldest")), f);
        }
        return new HealthCheck("consent_pending", GREEN, n > 0 ? "recent" : "ok", n + " consent request(s) waiting (all recent).", null, f);
    }

    HealthCheck lowStock() {
        Map<String, Object> r = jdbc.queryForMap(
                "select count(*) filter (where stock_count <= refill_threshold) low, count(*) filter (where stock_count = 0) empty " +
                "from medicines where active and stock_count is not null and refill_threshold is not null");
        long low = Rows.num(r.get("low"));
        long empty = Rows.num(r.get("empty"));
        Map<String, Object> f = facts("low", low, "empty", empty);
        if (low > 0) {
            return new HealthCheck("low_stock", AMBER, "low", low + " medicine(s) are at or below the refill level (" + empty + " empty).", null, f);
        }
        return new HealthCheck("low_stock", GREEN, "ok", "No medicine is running low.", null, f);
    }
}
