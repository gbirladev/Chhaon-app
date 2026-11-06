package org.smartgurukul.chhaon.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Read-only views across EVERY family for the owner's Monitor (service role, RLS bypassed: the caller was checked by
 * {@link AdminGuard}). Names are shown (the owner needs them to help a family); every phone number is masked by {@link PhoneMask}
 * before it leaves (country code + last 4 digits), so the full number never reaches the browser. Nothing here is ever logged.
 */
@Service
public class AdminMonitorService {

    /** Same "is this a parent's own WhatsApp answer" rule everywhere: a caregiver tick in the app is not a reply. */
    private static final String BY_WHATSAPP = "coalesce(de.confirm_source, 'whatsapp') = 'whatsapp'";

    private static final String ALERT_TYPES = "('dose_missed','checkin_missed','checkin_call_requested','parent_stopped','parent_declined'," +
            "'parent_consented','parent_resumed','whatsapp_failed','reminder_not_sent','refill_needed','out_of_stock')";

    /**
     * Is care recipient {@code r} the Monitor's canary family (docs/canary.md, migration 0055)? Read through to_jsonb so the
     * Monitor keeps working on a database where that migration has not run yet (then nobody is the canary).
     */
    private static final String R_CANARY = "coalesce((to_jsonb(r) ->> 'is_canary')::boolean, false)";

    /** The canary family's schedules: left out of the day's totals. */
    private static final String CANARY_SCHEDULES = "(select s.id from medicine_schedules s join medicines m on m.id = s.medicine_id " +
            "join care_recipients r on r.id = m.recipient_id where " + R_CANARY + ")";

    private final JdbcTemplate jdbc;
    private final int doseMissedMinutes;
    private final int checkinMissedMinutes;

    public AdminMonitorService(JdbcTemplate jdbc,
                               @Value("${chhaon.scheduler.dose-missed-minutes:60}") int doseMissedMinutes,
                               @Value("${chhaon.scheduler.checkin-missed-minutes:180}") int checkinMissedMinutes) {
        this.jdbc = jdbc;
        this.doseMissedMinutes = doseMissedMinutes;
        this.checkinMissedMinutes = checkinMissedMinutes;
    }

    // ---------------------------------------------------------------- overview

    /** Totals for one IST day (default today) across all families. */
    public Map<String, Object> overview(LocalDate day) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("doses", Rows.clean(jdbc.queryForMap(
                "select count(*) scheduled, " +
                "count(*) filter (where status = 'pending') not_sent_yet, " +
                "count(*) filter (where sent_at is not null) sent, " +
                "count(*) filter (where status = 'sent') waiting, " +
                "count(*) filter (where status = 'taken') taken, " +
                "count(*) filter (where status = 'taken_late') taken_late, " +
                "count(*) filter (where status = 'missed') missed, " +
                "count(*) filter (where status = 'skipped') skipped, " +
                "count(*) filter (where sent_at - scheduled_at > interval '15 minutes') sent_late " +
                "from dose_events where (scheduled_at at time zone 'Asia/Kolkata')::date = ? " +
                "and schedule_id not in " + CANARY_SCHEDULES, day)));   // the canary has its own check and card
        out.put("checkins", Rows.clean(jdbc.queryForMap(
                "select count(*) filter (where sent_at is not null) sent, " +
                "count(*) filter (where status = 'replied') answered, " +
                "count(*) filter (where status = 'sent') waiting, " +
                "count(*) filter (where status = 'missed') missed, " +
                "(select count(distinct payload->>'recipient_id') from notifications where type = 'checkin_call_requested' " +
                "   and (created_at at time zone 'Asia/Kolkata')::date = ?) call_me " +
                "from checkins where date = ?", day, day)));
        out.put("alerts", Rows.clean(jdbc.queryForMap(
                "select count(*) total, " +
                "coalesce(jsonb_object_agg(type, n) filter (where type is not null), '{}'::jsonb) by_type, " +
                "(select count(*) from notifications where whatsapp_sent_at is not null " +
                "   and (whatsapp_sent_at at time zone 'Asia/Kolkata')::date = ?) whatsapp_sent " +
                "from (select type, count(*) n from (select distinct type, payload::text, date_trunc('minute', created_at) " +
                "        from notifications where type in " + ALERT_TYPES + " and (created_at at time zone 'Asia/Kolkata')::date = ?) d " +
                "      group by type) x", day, day)));
        // count(*) above counts types, not alerts: fix the total to the sum of the per-type counts
        @SuppressWarnings("unchecked")
        Map<String, Object> alerts = (Map<String, Object>) out.get("alerts");
        Object byType = alerts.get("by_type");
        long total = 0;
        if (byType instanceof com.fasterxml.jackson.databind.JsonNode node) {
            for (var it = node.elements(); it.hasNext(); ) total += it.next().asLong();
        }
        alerts.put("total", total);
        return out;
    }

    // ---------------------------------------------------------------- families

    /** Supabase login traces (older accounts); left out when this database user may not read the auth schema. */
    private static final String AUTH_SEEN =
            "((select max(s.refreshed_at) from auth.sessions s where s.user_id = p.id)), " +
            "((select u.last_sign_in_at from auth.users u where u.id = p.id)), ";

    public List<Map<String, Object>> families() {
        List<Map<String, Object>> rows;
        try {
            rows = familyRows(FAMILIES_SQL.replace("AUTH_SEEN", AUTH_SEEN));
        } catch (org.springframework.dao.DataAccessException e) {
            rows = familyRows(FAMILIES_SQL.replace("AUTH_SEEN", ""));
        }
        for (Map<String, Object> row : rows) row.put("badges", badges(row));
        return PhoneMask.maskRows(rows);
    }

    private List<Map<String, Object>> familyRows(String sql) {
        return Rows.clean(jdbc.queryForList(sql));
    }

    private static final String FAMILIES_SQL = (
                "select r.id recipient_id, r.circle_id, r.name, r.call_name, r.relation, r.care_type, r.is_self, r.whatsapp_phone, " +
                "r.timezone, r.language, r.reminder_to, r.reminder_name, r.reminder_relation, ru.name reminder_user_name, " +
                "ru.phone reminder_user_phone, r.consent_at, r.consent_requested_at, r.opted_out_at, r.checkin_enabled, r.created_at, " + R_CANARY + " is_canary, " +
                "exists(select 1 from phone_opt_outs o where o.phone = r.whatsapp_phone) phone_opted_out, " +
                "(select coalesce(jsonb_agg(jsonb_build_object('user_id', p.id, 'name', p.name, 'email', p.email, 'phone', p.phone, " +
                "    'role', m.role, 'plan', p.plan) order by m.role, p.name), '[]'::jsonb) " +
                "   from care_circle_members m join profiles p on p.id = m.user_id where m.circle_id = r.circle_id) caregivers, " +
                "(select p.plan from care_circles c join profiles p on p.id = c.created_by where c.id = r.circle_id) plan, " +
                "r.id in (select recipient_id from paused_recipients()) paused_by_plan, " +
                "(select count(*) from medicines m where m.recipient_id = r.id and m.active) active_medicines, " +
                "(select count(*) from medicines m where m.recipient_id = r.id and m.active and m.stock_count is not null " +
                "   and m.refill_threshold is not null and m.stock_count <= m.refill_threshold) low_stock, " +
                "t.total today_total, t.confirmed today_confirmed, t.missed today_missed, t.waiting today_waiting, " +
                "(select c.status::text from checkins c where c.recipient_id = r.id and c.date = (now() at time zone r.timezone)::date) checkin_today, " +
                "greatest((select max(de.responded_at) from dose_events de join medicine_schedules s on s.id = de.schedule_id " +
                "            join medicines m on m.id = s.medicine_id where m.recipient_id = r.id and " + BY_WHATSAPP + "), " +
                "         (select max(c.responded_at) from checkins c where c.recipient_id = r.id)) last_reply_at, " +
                "w.sent_48h, w.replied_48h, w.median_reply_minutes_7d, " +
                "v.messages_today, v.messages_7d, " +
                "(select count(*) from notifications n where n.type = 'whatsapp_failed' and n.payload->>'recipient_id' = r.id::text " +
                "   and n.created_at > now() - interval '24 hours') failed_24h, " +
                "(select max(a) from care_circle_members m join profiles p on p.id = m.user_id cross join lateral (values " +
                "   ((select max(o.consumed_at) from otp_codes o where o.phone = p.phone)), " +
                "   ((select max(de.responded_at) from dose_events de where de.confirmed_by = p.id)), " +
                "   ((select max(x.sent_at) from reactions x where x.sender_id = p.id)), " +
                "   ((select max(h.created_at) from feed_hearts h where h.user_id = p.id)), " +
                "   ((select max(fb.created_at) from feedback fb where fb.user_id = p.id)), " +
                "   ((select max(ap.created_at) from appointments ap where ap.created_by = p.id)), " +
                "   AUTH_SEEN" +
                "   ((select max(ap.created_at) from appointments ap where ap.created_by = p.id and false))) act(a) " +
                "   where m.circle_id = r.circle_id) last_caregiver_activity_at " +
                "from care_recipients r " +
                "left join profiles ru on ru.id = r.reminder_user_id " +
                "cross join lateral (select count(*) filter (where de.status <> 'skipped') total, " +
                "    count(*) filter (where de.status in ('taken', 'taken_late')) confirmed, " +
                "    count(*) filter (where de.status = 'missed') missed, count(*) filter (where de.status = 'sent') waiting " +
                "  from dose_events de join medicine_schedules s on s.id = de.schedule_id join medicines m on m.id = s.medicine_id " +
                "  where m.recipient_id = r.id and (de.scheduled_at at time zone r.timezone)::date = (now() at time zone r.timezone)::date) t " +
                "cross join lateral (select count(*) filter (where y.sent_at > now() - interval '48 hours') sent_48h, " +
                "    count(*) filter (where y.responded_at > now() - interval '48 hours') replied_48h, " +
                "    round((percentile_cont(0.5) within group (order by extract(epoch from y.responded_at - y.sent_at) / 60.0) " +
                "        filter (where y.responded_at is not null and y.sent_at > now() - interval '7 days'))::numeric, 1) median_reply_minutes_7d " +
                "  from (select de.sent_at, case when " + BY_WHATSAPP + " then de.responded_at end responded_at " +
                "          from dose_events de join medicine_schedules s on s.id = de.schedule_id join medicines m on m.id = s.medicine_id " +
                "          where m.recipient_id = r.id and de.sent_at > now() - interval '7 days' " +
                "        union all select c.sent_at, c.responded_at from checkins c where c.recipient_id = r.id and c.sent_at > now() - interval '7 days') y) w " +
                "cross join lateral (select count(*) filter (where (z.ts at time zone 'Asia/Kolkata')::date = (now() at time zone 'Asia/Kolkata')::date) messages_today, " +
                "    count(*) messages_7d " +
                "  from (select de.sent_at ts from dose_events de join medicine_schedules s on s.id = de.schedule_id " +
                "          join medicines m on m.id = s.medicine_id where m.recipient_id = r.id and de.sent_at > now() - interval '7 days' " +
                "        union all select c.sent_at from checkins c where c.recipient_id = r.id and c.sent_at > now() - interval '7 days' " +
                "        union all select ap.reminded_day_before_at from appointments ap where ap.recipient_id = r.id and ap.reminded_day_before_at > now() - interval '7 days' " +
                "        union all select ap.reminded_morning_at from appointments ap where ap.recipient_id = r.id and ap.reminded_morning_at > now() - interval '7 days') z) v " +
                "order by " + R_CANARY + ", r.created_at");   // the canary family (docs/canary.md) last

    /** Warning badges of one families row (codes the web app translates). */
    static List<String> badges(Map<String, Object> row) {
        List<String> b = new ArrayList<>();
        if (Boolean.TRUE.equals(row.get("is_canary"))) b.add("canary");   // the test family (docs/canary.md), not a client
        boolean optedOut = row.get("opted_out_at") != null || Boolean.TRUE.equals(row.get("phone_opted_out"));
        if (optedOut) b.add("opted_out");
        if (!optedOut && row.get("consent_at") == null && row.get("whatsapp_phone") != null) b.add("consent_pending");
        if (Rows.num(row.get("sent_48h")) >= 2 && Rows.num(row.get("replied_48h")) == 0) b.add("no_reply_2d");
        if ("missed".equals(row.get("checkin_today"))) b.add("checkin_missed");
        if (Rows.num(row.get("low_stock")) > 0) b.add("low_stock");
        if (Boolean.TRUE.equals(row.get("paused_by_plan"))) b.add("paused_by_plan");
        if (Rows.num(row.get("failed_24h")) > 0) b.add("send_failed");
        return b;
    }

    // ---------------------------------------------------------------- one family

    /** Everything about one person cared for. Records the view in admin_audit (who, which, when) BEFORE answering. */
    public Map<String, Object> family(UUID adminId, UUID recipientId) {
        List<Map<String, Object>> found = jdbc.queryForList(
                "select to_jsonb(r) - 'updated_at' recipient, r.circle_id from care_recipients r where r.id = ?", recipientId);
        if (found.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Not found");
        Object circleId = found.get(0).get("circle_id");
        jdbc.update("insert into admin_audit (admin_id, action, recipient_id, circle_id) values (?, 'view_family', ?, ?)",
                adminId, recipientId, circleId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recipient", PhoneMask.maskFields(Rows.value(found.get(0).get("recipient"))));   // whatsapp_phone, contacts' phones
        out.put("members", PhoneMask.maskRows(Rows.clean(jdbc.queryForList(
                "select p.id user_id, p.name, p.email, p.phone, m.role::text role, m.parent_calls_me, m.created_at joined_at, p.plan, " +
                "p.trial_ends_at, p.language, p.timezone, p.alert_phone, p.alert_consent_at, p.is_admin, p.created_at signed_up_at " +
                "from care_circle_members m join profiles p on p.id = m.user_id where m.circle_id = ? order by m.role, p.name", circleId))));
        out.put("medicines", Rows.clean(jdbc.queryForList(
                "select m.id, m.name, m.dosage, m.instructions, m.active, m.critical, m.stock_count, m.refill_threshold, " +
                "m.photo_url is not null has_photo, m.pill_colour, m.pill_shape, m.created_at, " +
                "m.id in (select medicine_id from paused_medicines()) paused_by_plan, " +
                "(select coalesce(jsonb_agg(jsonb_build_object('time_of_day', s.time_of_day, 'days_of_week', s.days_of_week, " +
                "    'start_date', s.start_date, 'end_date', s.end_date) order by s.time_of_day), '[]'::jsonb) " +
                "   from medicine_schedules s where s.medicine_id = m.id) schedules " +
                "from medicines m where m.recipient_id = ? order by m.active desc, m.name", recipientId)));
        out.put("doses", Rows.clean(jdbc.queryForList(
                "select de.id, m.name medicine, m.dosage, de.scheduled_at, de.status::text status, de.sent_at, de.responded_at, " +
                "de.confirm_source, (de.whatsapp_message_id is not null) has_message_id, " +
                "case when de.sent_at is not null then floor(extract(epoch from de.sent_at - de.scheduled_at) / 60)::int end send_delay_minutes " +
                "from dose_events de join medicine_schedules s on s.id = de.schedule_id join medicines m on m.id = s.medicine_id " +
                "where m.recipient_id = ? and de.scheduled_at > now() - interval '7 days' and de.scheduled_at < now() + interval '1 day' " +
                "order by de.scheduled_at desc, m.name", recipientId)));
        out.put("checkins", Rows.clean(jdbc.queryForList(
                "select id, date, status::text status, sent_at, responded_at from checkins " +
                "where recipient_id = ? and date > current_date - 8 order by date desc", recipientId)));
        out.put("alerts", Rows.clean(jdbc.queryForList(
                "select type, min(created_at) created_at, max(whatsapp_sent_at) whatsapp_sent_at, count(*) recipients, payload " +
                "from notifications where payload->>'recipient_id' = ? and created_at > now() - interval '7 days' " +
                "group by type, payload, date_trunc('minute', created_at) order by min(created_at) desc limit 50", recipientId.toString())));
        out.put("consent_requests", Rows.clean(jdbc.queryForList(
                "select q.requested_at, p.name requested_by_name from consent_requests q left join profiles p on p.id = q.requested_by " +
                "where q.phone = (select whatsapp_phone from care_recipients where id = ?) order by q.requested_at desc limit 10", recipientId)));
        out.put("escalations", Rows.clean(jdbc.queryForList(
                "select kind, slot_key, base_at, nudged_at, alerted_at, resolved_at, resolved_by from escalations " +
                "where recipient_id = ? and created_at > now() - interval '7 days' order by base_at desc limit 30", recipientId)));
        out.put("appointments", Rows.clean(jdbc.queryForList(
                "select doctor_name, datetime, location, remind, reminded_day_before_at, reminded_morning_at from appointments " +
                "where recipient_id = ? and datetime > now() - interval '7 days' order by datetime limit 20", recipientId)));
        return out;
    }

    // ---------------------------------------------------------------- activity

    public List<Map<String, Object>> activity(int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        String doseFrom = "from dose_events de join medicine_schedules s on s.id = de.schedule_id join medicines m on m.id = s.medicine_id " +
                "join care_recipients r on r.id = m.recipient_id and not " + R_CANARY + " ";   // the canary would fill the list every 4 h
        return Rows.clean(jdbc.queryForList(
                "select * from (" +
                " (select 'dose_sent' kind, de.sent_at at_time, r.id recipient_id, r.name recipient_name, m.name detail " + doseFrom +
                "   where de.sent_at > now() - interval '7 days' order by de.sent_at desc limit ?)" +
                " union all (select case when de.status = 'taken_late' then 'dose_taken_late' else 'dose_taken' end, de.responded_at, r.id, r.name, " +
                "   m.name || coalesce(' · ' || de.confirm_source, '') " + doseFrom +
                "   where de.status in ('taken', 'taken_late') and de.responded_at > now() - interval '7 days' order by de.responded_at desc limit ?)" +
                " union all (select 'dose_missed', de.sent_at + make_interval(mins => ?), r.id, r.name, m.name " + doseFrom +
                "   where de.status = 'missed' and de.sent_at > now() - interval '7 days' order by de.sent_at desc limit ?)" +
                " union all (select 'checkin_sent', c.sent_at, r.id, r.name, null from checkins c join care_recipients r on r.id = c.recipient_id " +
                "   where c.sent_at > now() - interval '7 days' order by c.sent_at desc limit ?)" +
                " union all (select 'checkin_answered', c.responded_at, r.id, r.name, null from checkins c join care_recipients r on r.id = c.recipient_id " +
                "   where c.status = 'replied' and c.responded_at > now() - interval '7 days' order by c.responded_at desc limit ?)" +
                " union all (select 'checkin_missed', c.sent_at + make_interval(mins => ?), r.id, r.name, null from checkins c " +
                "   join care_recipients r on r.id = c.recipient_id where c.status = 'missed' and c.sent_at > now() - interval '7 days' " +
                "   order by c.sent_at desc limit ?)" +
                " union all (select 'consent_given', r.consent_at, r.id, r.name, null from care_recipients r " +
                "   where not " + R_CANARY + " and r.consent_at > now() - interval '30 days' order by r.consent_at desc limit ?)" +
                " union all (select 'opted_out', r.opted_out_at, r.id, r.name, null from care_recipients r " +
                "   where r.opted_out_at > now() - interval '30 days' order by r.opted_out_at desc limit ?)" +
                " union all (select 'alert', a.created_at, rr.id, rr.name, a.type from (" +
                "     select distinct on (type, payload::text, date_trunc('minute', created_at)) type, created_at, payload->>'recipient_id' rid " +
                "     from notifications where type in " + ALERT_TYPES + " and created_at > now() - interval '7 days' " +
                "     order by type, payload::text, date_trunc('minute', created_at), created_at) a " +
                "   left join care_recipients rr on rr.id::text = a.rid order by a.created_at desc limit ?)" +
                " union all (select 'sign_up', p.created_at, null, null, p.name from profiles p " +
                "   where not coalesce((to_jsonb(p) ->> 'is_canary')::boolean, false) and p.created_at > now() - interval '30 days' order by p.created_at desc limit ?)" +
                " union all (select 'message_in', e.received_at, null, null, null from whatsapp_events e " +
                "   where e.id like 'gw:in:%' and e.received_at > now() - interval '7 days' order by e.received_at desc limit ?)" +
                ") x where at_time is not null order by at_time desc limit ?",
                n, n, doseMissedMinutes, n, n, n, checkinMissedMinutes, n, n, n, n, n, n, n));
    }

    // ---------------------------------------------------------------- upcoming

    /** Doses and check-ins due in the next {@code hours} hours (default 2), soonest first. */
    public List<Map<String, Object>> upcoming(int hours) {
        int h = Math.max(1, Math.min(hours, 24));
        return PhoneMask.maskRows(Rows.clean(jdbc.queryForList(
                "select * from (" +
                " select 'dose' kind, de.scheduled_at due_at, r.id recipient_id, r.name recipient_name, r.timezone, r.reminder_to, " +
                "   r.reminder_name, r.whatsapp_phone, " +
                "   (r.consent_at is not null and r.opted_out_at is null and r.whatsapp_phone is not null " +
                "    and not exists(select 1 from phone_opt_outs o where o.phone = r.whatsapp_phone)) will_send, " +
                "   jsonb_agg(m.name || coalesce(' ' || m.dosage, '') order by m.name) medicines " +
                " from dose_events de join medicine_schedules s on s.id = de.schedule_id join medicines m on m.id = s.medicine_id " +
                " join care_recipients r on r.id = m.recipient_id " +
                " where de.status = 'pending' and de.scheduled_at > now() - interval '5 minutes' and de.scheduled_at <= now() + make_interval(hours => ?) " +
                " group by de.scheduled_at, r.id " +
                " union all " +
                " select 'checkin', x.due_at, r.id, r.name, r.timezone, r.reminder_to, r.reminder_name, r.whatsapp_phone, " +
                "   not exists(select 1 from phone_opt_outs o where o.phone = r.whatsapp_phone), '[]'::jsonb " +
                " from care_recipients r cross join lateral (select (((now() at time zone r.timezone)::date + r.checkin_time) " +
                "     at time zone r.timezone) due_at) x " +
                " where r.consent_at is not null and r.opted_out_at is null and r.whatsapp_phone is not null and not r.is_self " +
                "   and r.reminder_to = 'person' and r.checkin_enabled " +
                "   and x.due_at > now() - interval '5 minutes' and x.due_at <= now() + make_interval(hours => ?) " +
                "   and not exists(select 1 from checkins c where c.recipient_id = r.id and c.date = (now() at time zone r.timezone)::date " +
                "     and c.status <> 'pending')" +
                ") u order by due_at, recipient_name", h, h)));
    }

    // ---------------------------------------------------------------- feedback

    public List<Map<String, Object>> feedback(int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        return PhoneMask.maskRows(Rows.clean(jdbc.queryForList(
                "select f.id, f.created_at, f.kind, f.message, f.rating, f.screen, f.app_language, cardinality(f.images) images, " +
                "p.id user_id, p.name, p.email, p.phone from feedback f left join profiles p on p.id = f.user_id " +
                "order by f.created_at desc limit ?", n)));
    }
}
