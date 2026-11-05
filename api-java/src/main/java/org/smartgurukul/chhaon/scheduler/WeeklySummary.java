package org.smartgurukul.chhaon.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartgurukul.chhaon.child.VaccineSchedule;
import org.smartgurukul.chhaon.recipient.Pregnancy;
import org.smartgurukul.chhaon.whatsapp.ReceiverTexts;
import org.smartgurukul.chhaon.whatsapp.WeeklySummaryTexts;
import org.smartgurukul.chhaon.whatsapp.WeeklySummaryTexts.Giver;
import org.smartgurukul.chhaon.whatsapp.WeeklySummaryTexts.Missed;
import org.smartgurukul.chhaon.whatsapp.WeeklySummaryTexts.NextVaccine;
import org.smartgurukul.chhaon.whatsapp.WeeklySummaryTexts.Refill;
import org.smartgurukul.chhaon.whatsapp.WeeklySummaryTexts.Visit;
import org.smartgurukul.chhaon.whatsapp.WeeklySummaryTexts.Week;
import org.smartgurukul.chhaon.whatsapp.WhatsAppSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Weekly summary to the family (feature F3, docs/weekly-summary.md). Every Sunday from about 9:00 on the CAREGIVER's own clock,
 * each caregiver who turned WhatsApp alerts on (and confirmed by typing 1) gets ONE short message covering every person they care
 * for: medicines confirmed / missed over the last 7 days (on each person's clock), who gave them, refills due, the next doctor
 * visit, the pregnancy week, a child's next vaccine.
 *
 * <p>Rules:
 * <ul>
 *   <li>only to caregivers with alert consent, never to a number that typed STOP ({@code phone_opt_outs}); never about the
 *       caregiver's own "Me" profile, and never to the person themself or their helper (a caregiver whose alert number IS the
 *       person's / the helper's reminder number gets no summary about that person);</li>
 *   <li>a person with no counted dose and no check-in that week is left out; nobody left = no message;</li>
 *   <li>at most one per caregiver per week, across restarts and overlapping runs: the row in {@code weekly_summary_sends}
 *       (migration 20261105000048) is claimed before sending; a WhatsApp failure is retried at the next run, at most
 *       {@code max-attempts} (3) times; a run that died between claiming and sending is NOT retried (better none than two);</li>
 *   <li>runs on its own timer, never inside SchedulerService.tick, so nothing here can break the reminders; same switches
 *       (chhaon.scheduler.enabled, chhaon.scheduler.only-recipient);</li>
 *   <li>logs carry neither phone numbers (masked) nor names or medicines.</li>
 * </ul>
 */
@Service
public class WeeklySummary {

    private static final Logger log = LoggerFactory.getLogger(WeeklySummary.class);

    /** Counted statuses: confirmed = taken + taken_late; missed = not confirmed. pending / sent / skipped are not counted. */
    private static final Set<String> CONFIRMED = Set.of("taken", "taken_late");
    /** Never a summary about the Monitor's canary family (docs/canary.md); read defensively (column from migration 0055). */
    private static final String NOT_CANARY = "not coalesce((to_jsonb(r) ->> 'is_canary')::boolean, false)";
    /** confirm_source values that mean "the reply on WhatsApp" (anything else with no confirmed_by is "the family, in the app"). */
    private static final Set<String> WHATSAPP_SOURCES = Set.of("whatsapp", "reply", "wa", "button");

    private final JdbcTemplate jdbc;
    private final WhatsAppSender wa;
    private final VaccineSchedule vaccines;
    private final boolean enabled;
    private final UUID onlyRecipient;
    private final boolean onlyRecipientInvalid;
    private final int fromHour;
    private final int untilHour;
    private final int maxAttempts;

    public WeeklySummary(JdbcTemplate jdbc, WhatsAppSender wa, VaccineSchedule vaccines,
                         @Value("${chhaon.scheduler.enabled:false}") boolean enabled,
                         @Value("${chhaon.scheduler.only-recipient:}") String onlyRecipient,
                         @Value("${chhaon.weekly.from-hour:9}") int fromHour,
                         @Value("${chhaon.weekly.until-hour:13}") int untilHour,
                         @Value("${chhaon.weekly.max-attempts:3}") int maxAttempts) {
        this.jdbc = jdbc;
        this.wa = wa;
        this.vaccines = vaccines;
        this.enabled = enabled;
        String o = onlyRecipient == null ? "" : onlyRecipient.trim();
        UUID parsed = null;
        boolean bad = false;
        if (!o.isEmpty() && !"all".equalsIgnoreCase(o)) {
            try {
                parsed = UUID.fromString(o);
            } catch (IllegalArgumentException e) {
                bad = true;
            }
        }
        this.onlyRecipient = parsed;
        this.onlyRecipientInvalid = bad;
        this.fromHour = fromHour;
        this.untilHour = untilHour;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${chhaon.weekly.tick-interval-ms:300000}",
               initialDelayString = "${chhaon.weekly.initial-delay-ms:180000}")
    public void scheduledRun() {
        if (!enabled || onlyRecipientInvalid) return;   // an unreadable scope never means "everyone"
        try {
            Map<String, Integer> c = run(onlyRecipient, Instant.now());
            if (!c.isEmpty()) log.info("weekly summary {}", c);
        } catch (Exception e) {
            log.warn("weekly summary run failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * One pass at {@code now}. scope = one recipient id (tests, first switch-on) or null for every family. Returns counters
     * (weekly_sent / _skipped / _failed). Never throws for one caregiver: each is handled on its own.
     */
    public Map<String, Integer> run(UUID scope, Instant now) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Object> p = new ArrayList<>();
        String scopeSql = "";
        if (scope != null) {
            scopeSql = " and r.id = ?";
            p.add(scope);
        }
        List<Map<String, Object>> caregivers = jdbc.queryForList(
                "select p.id, p.language, p.timezone, p.alert_phone from profiles p " +
                        "where p.alert_consent_at is not null and p.alert_phone is not null " +
                        "and not exists (select 1 from phone_opt_outs o where o.phone = p.alert_phone) " +
                        "and exists (select 1 from care_circle_members m join care_recipients r on r.circle_id = m.circle_id " +
                        "            where m.user_id = p.id and not r.is_self and " + NOT_CANARY + scopeSql + ")", p.toArray());
        for (Map<String, Object> cg : caregivers) {
            ZonedDateTime local = now.atZone(zone(cg.get("timezone")));
            if (local.getDayOfWeek() != DayOfWeek.SUNDAY || local.getHour() < fromHour || local.getHour() >= untilHour) continue;
            try {
                sendOne(cg, local.toLocalDate(), scope, now, counts);
            } catch (Exception e) {
                bump(counts, "weekly_failed");
                log.warn("weekly summary for one caregiver could not be handled ({})", e.getClass().getSimpleName());
            }
        }
        return counts;
    }

    private void sendOne(Map<String, Object> cg, LocalDate weekOf, UUID scope, Instant now, Map<String, Integer> counts) {
        UUID userId = (UUID) cg.get("id");
        // Claim (or re-claim a failed one with tries left). No row back = sent, skipped, in progress or out of tries.
        List<Map<String, Object>> claim = jdbc.queryForList(
                "insert into weekly_summary_sends (user_id, week_of, status, attempts, claimed_at) values (?, ?, 'sending', 0, now()) " +
                        "on conflict (user_id, week_of) do update set status = 'sending', claimed_at = now() " +
                        "where weekly_summary_sends.status = 'failed' and weekly_summary_sends.attempts < ? returning attempts",
                userId, weekOf, maxAttempts);
        if (claim.isEmpty()) return;
        int attempts = ((Number) claim.get(0).get("attempts")).intValue();
        String language = (String) cg.get("language");
        String phone = (String) cg.get("alert_phone");
        String text;
        int people;
        try {
            List<Week> weeks = new ArrayList<>();
            for (Map<String, Object> r : recipientsFor(userId, scope)) {
                if (isThePersonOrHelper(r, phone)) continue;
                Week w = week(r, userId, now);
                if (!w.quiet()) weeks.add(w);
            }
            people = weeks.size();
            text = WeeklySummaryTexts.message(language, weeks, (String) cg.get("timezone"));
        } catch (RuntimeException e) {
            fail(userId, weekOf, attempts);
            throw e;
        }
        if (text == null) {
            jdbc.update("update weekly_summary_sends set status = 'skipped', people = 0 where user_id = ? and week_of = ?", userId, weekOf);
            bump(counts, "weekly_skipped");
            return;
        }
        try {
            wa.send(phone, text);
        } catch (Exception e) {
            fail(userId, weekOf, attempts);
            bump(counts, "weekly_failed");
            log.warn("could not send a weekly summary to {} (attempt {}, {})", WhatsAppSender.mask(phone), attempts + 1,
                    WhatsAppSender.failureReason(e));
            return;
        }
        jdbc.update("update weekly_summary_sends set status = 'sent', sent_at = now(), people = ? where user_id = ? and week_of = ?",
                people, userId, weekOf);
        bump(counts, "weekly_sent");
    }

    private void fail(UUID userId, LocalDate weekOf, int attempts) {
        jdbc.update("update weekly_summary_sends set status = 'failed', attempts = ? where user_id = ? and week_of = ?",
                attempts + 1, userId, weekOf);
    }

    private List<Map<String, Object>> recipientsFor(UUID userId, UUID scope) {
        List<Object> p = new ArrayList<>();
        p.add(userId);
        String scopeSql = "";
        if (scope != null) {
            scopeSql = " and r.id = ?";
            p.add(scope);
        }
        return jdbc.queryForList(RECIPIENT_COLUMNS + "from care_recipients r join care_circle_members m on m.circle_id = r.circle_id " +
                "where m.user_id = ? and not r.is_self and " + NOT_CANARY + scopeSql + " order by r.created_at", p.toArray());
    }

    private static final String RECIPIENT_COLUMNS = "select r.id, r.name, r.call_name, r.timezone, r.is_self, r.whatsapp_phone, " +
            "r.care_type, r.due_date, r.reminder_to, r.reminder_name, r.reminder_user_id ";

    /** The caregiver's alert number is the number that gets this person's reminders as the person or their helper. */
    private static boolean isThePersonOrHelper(Map<String, Object> r, String alertPhone) {
        String to = (String) r.get("reminder_to");
        return alertPhone != null && alertPhone.equals(r.get("whatsapp_phone")) && !ReceiverTexts.CAREGIVER.equals(to);
    }

    /** The in-app card: the same week for one person, as {@code viewer} sees it (null when the person does not exist). */
    public Week weekFor(UUID recipientId, UUID viewer, Instant now) {
        List<Map<String, Object>> rows = jdbc.queryForList(RECIPIENT_COLUMNS + "from care_recipients r where r.id = ?", recipientId);
        return rows.isEmpty() ? null : week(rows.get(0), viewer, now);
    }

    /** One person's last 7 full days (their clock), as {@code viewer} reads it ("you"). */
    Week week(Map<String, Object> r, UUID viewer, Instant now) {
        UUID rid = (UUID) r.get("id");
        String zoneId = (String) r.get("timezone");
        ZoneId zone = zone(zoneId);
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate from = today.minusDays(7);
        LocalDate to = today.minusDays(1);
        Timestamp start = Timestamp.from(from.atStartOfDay(zone).toInstant());
        Timestamp end = Timestamp.from(today.atStartOfDay(zone).toInstant());
        String personName = ReceiverTexts.personName((String) r.get("call_name"), (String) r.get("name"));

        // confirm_source / confirmed_by come from another branch: read through to_jsonb so this works with or without them.
        List<Map<String, Object>> doses = jdbc.queryForList(
                "select de.status::text status, de.scheduled_at, m.name medicine, " +
                        "to_jsonb(de) ->> 'confirm_source' source, to_jsonb(de) ->> 'confirmed_by' confirmed_by " +
                        "from dose_events de join medicine_schedules s on s.id = de.schedule_id join medicines m on m.id = s.medicine_id " +
                        "where m.recipient_id = ? and de.scheduled_at >= ? and de.scheduled_at < ? " +
                        "and de.status in ('taken', 'taken_late', 'missed') order by de.scheduled_at", rid, start, end);
        int confirmed = 0;
        List<Missed> missed = new ArrayList<>();
        Map<String, int[]> giverCounts = new LinkedHashMap<>();
        Map<String, Giver> giverKinds = new HashMap<>();
        for (Map<String, Object> d : doses) {
            String status = (String) d.get("status");
            if (CONFIRMED.contains(status)) {
                confirmed++;
                Giver g = giver(r, d, viewer);
                String key = g.kind() + "|" + (g.name() == null ? "" : g.name());
                giverCounts.computeIfAbsent(key, k -> new int[1])[0]++;
                giverKinds.putIfAbsent(key, g);
            } else {
                ZonedDateTime at = ((Timestamp) d.get("scheduled_at")).toInstant().atZone(zone);
                missed.add(new Missed((String) d.get("medicine"), at.toLocalDate(), WeeklySummaryTexts.partOfDay(at.getHour())));
            }
        }
        List<Giver> givers = new ArrayList<>();
        giverCounts.forEach((k, c) -> givers.add(new Giver(giverKinds.get(k).kind(), giverKinds.get(k).name(), c[0])));
        givers.sort(Comparator.comparingInt(Giver::count).reversed());

        List<String> checkins = jdbc.queryForList("select status::text from checkins where recipient_id = ? and date between ? and ? " +
                "and status in ('replied', 'missed')", String.class, rid, from, to);
        int answered = (int) checkins.stream().filter("replied"::equals).count();

        List<Refill> refills = jdbc.query("select name, stock_count from medicines where recipient_id = ? and active " +
                        "and stock_count is not null and refill_threshold is not null and stock_count <= refill_threshold " +
                        "order by stock_count, name limit 3",
                (rs, i) -> new Refill(rs.getString(1), rs.getInt(2)), rid);

        List<Visit> visits = jdbc.query("select doctor_name, datetime from appointments where recipient_id = ? and datetime > ? " +
                        "and datetime < ? order by datetime limit 1",
                (rs, i) -> new Visit(rs.getString(1), rs.getTimestamp(2).toInstant()), rid,
                Timestamp.from(now), Timestamp.from(now.plus(Duration.ofDays(60))));

        String careType = (String) r.get("care_type");
        Integer pregnancyWeek = null;
        if ("pregnant".equals(careType)) {
            Object due = r.get("due_date");
            LocalDate dueDate = due instanceof java.sql.Date sql ? sql.toLocalDate() : due instanceof LocalDate ld ? ld : null;
            pregnancyWeek = Pregnancy.week(dueDate, today);
        }
        NextVaccine vaccine = "child".equals(careType) ? nextVaccine(rid, today) : null;

        return new Week(rid, personName, zone.getId(), from, to, pregnancyWeek, confirmed, doses.size(), missed, givers, refills,
                visits.isEmpty() ? null : visits.get(0), vaccine, answered, checkins.size());
    }

    /**
     * Who gave / confirmed one dose: the family member who marked it in the app when known (confirmed_by), else whoever gets the
     * reminders today (the person, the helper, or the family member who chose "Me"). The receiver is today's setting: a dose from
     * before a change of receiver is counted for the new one (docs/weekly-summary.md, known limits).
     */
    private Giver giver(Map<String, Object> r, Map<String, Object> d, UUID viewer) {
        String by = (String) d.get("confirmed_by");
        String source = (String) d.get("source");
        if (by != null && !by.isBlank()) return member(by.trim(), viewer);
        if (source != null && !source.isBlank() && !WHATSAPP_SOURCES.contains(source.trim().toLowerCase())) {
            return new Giver("family", null, 0);
        }
        String to = (String) r.get("reminder_to");
        if (ReceiverTexts.HELPER.equals(to)) return new Giver("helper", (String) r.get("reminder_name"), 0);
        if (ReceiverTexts.CAREGIVER.equals(to)) {
            Object uid = r.get("reminder_user_id");
            return uid == null ? new Giver("member", (String) r.get("reminder_name"), 0) : member(uid.toString(), viewer);
        }
        return new Giver("person", null, 0);
    }

    private Giver member(String userId, UUID viewer) {
        if (viewer != null && viewer.toString().equals(userId)) return new Giver("you", null, 0);
        try {
            List<String> names = jdbc.queryForList("select name from profiles where id = ?::uuid", String.class, userId);
            String n = names.isEmpty() || names.get(0) == null ? "" : names.get(0).trim();
            return n.isEmpty() ? new Giver("family", null, 0) : new Giver("member", n.split("\\s+")[0], 0);
        } catch (Exception e) {
            return new Giver("family", null, 0);
        }
    }

    private NextVaccine nextVaccine(UUID rid, LocalDate today) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("select vaccine_code, dose_label, due_date from child_vaccines " +
                    "where recipient_id = ? and given_on is null and not not_given and due_date >= ? order by due_date, schedule_key limit 1",
                    rid, today);
            if (rows.isEmpty()) return null;
            Map<String, Object> v = rows.get(0);
            VaccineSchedule.Name n = vaccines.name((String) v.get("vaccine_code"));
            return new NextVaccine(n.en(), n.hi(), (String) v.get("dose_label"), ((java.sql.Date) v.get("due_date")).toLocalDate());
        } catch (Exception e) {   // no vaccine table yet: leave the line out
            return null;
        }
    }

    private static ZoneId zone(Object tz) {
        try {
            return ZoneId.of((String) tz);
        } catch (Exception e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }

    private static void bump(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }
}
