package org.smartgurukul.chhaon.feed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartgurukul.chhaon.child.VaccineSchedule;
import org.smartgurukul.chhaon.recipient.CareRecipientRepository;
import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Family activity feed (feature 4): what happened in the last 7 days for EVERYONE the caller cares for, newest first, read-only.
 * Built from the existing tables (doses, check-ins, vaccines, appointments, medicines, consent), nothing new is recorded except the
 * optional ♥ ({@code feed_hearts}, migration 20261105000049). Scoped to the circles the caller belongs to (the service role bypasses
 * RLS, so every branch joins the caller's recipients). Never returns a phone number.
 */
@RestController
@RequestMapping("/api/v1/activity")
public class FeedController {

    private static final Logger log = LoggerFactory.getLogger(FeedController.class);
    static final int DAYS = 7;
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    static final Pattern ITEM_KEY = Pattern.compile(
            "^(dose|checkin|vaccine|appt|med_add|med_pause|consent|stop):[0-9a-f-]{36}(:[0-9]{1,12})?$");

    /** One person in the feed: what the family calls them and who gets their reminders (never a number). */
    public record Person(String id, String call, String careType, String timezone, boolean isSelf, String reminderTo, String reminderName,
                         String reminderRelation, boolean reminderIsMe) {}

    /** Who did it. kind: me | member | person | helper | caregiver (another family member who gets the reminders) | app. */
    public record Actor(String kind, String name) {}

    public record Item(String key, String kind, String recipientId, OffsetDateTime at, String title, String titleHi,
                       String detail, OffsetDateTime dueAt, String answer, Actor actor, int hearts, boolean heartedByMe) {}

    public record FeedResponse(List<Person> people, List<Item> items, String next) {}

    public record HeartRequest(UUID recipientId, String itemKey, Boolean on) {}

    public record HeartResponse(int hearts, boolean heartedByMe) {}

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final CareRecipientRepository recipients;
    private final VaccineSchedule vaccines;

    public FeedController(JdbcTemplate jdbc, CareRecipientRepository recipients, VaccineSchedule vaccines) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.recipients = recipients;
        this.vaccines = vaccines;
    }

    // One row per thing that happened. confirm_source / confirmed_by are read through to_jsonb so the query works with or without
    // those dose_events columns (added by another change).
    private static final String EVENTS = """
            with mine as (
                select r.id, r.reminder_to, r.reminder_user_id
                from care_recipients r
                where r.circle_id in (select cm.circle_id from care_circle_members cm where cm.user_id = :uid)
                  and (cast(:only as uuid) is null or r.id = cast(:only as uuid))
            ), ev as (
                select 'dose:' || d.id as key,
                       case when d.status in ('taken', 'taken_late') then 'dose_taken' else 'dose_missed' end as kind,
                       mine.id as recipient_id,
                       case when d.status in ('taken', 'taken_late') then coalesce(d.responded_at, d.scheduled_at)
                            else d.scheduled_at end as at,
                       m.name as title, m.dosage as detail, d.scheduled_at as due_at, null::text as answer,
                       to_jsonb(d) ->> 'confirm_source' as source, to_jsonb(d) ->> 'confirmed_by' as actor_id
                from dose_events d
                join medicine_schedules s on s.id = d.schedule_id
                join medicines m on m.id = s.medicine_id
                join mine on mine.id = m.recipient_id
                where d.status in ('taken', 'taken_late', 'missed')
                  and coalesce(d.responded_at, d.scheduled_at) >= cast(:from as timestamptz) and d.scheduled_at >= cast(:from as timestamptz) - interval '2 days'
              union all
                select 'checkin:' || c.id,
                       case when c.status = 'replied' then 'checkin_answered' else 'checkin_missed' end,
                       mine.id, coalesce(c.responded_at, c.sent_at), null, null, null,
                       case when c.status <> 'replied' then null
                            when exists (select 1 from notifications n
                                         where n.type = 'checkin_call_requested'
                                           and n.payload ->> 'recipient_id' = c.recipient_id::text
                                           and n.created_at between c.responded_at - interval '5 minutes'
                                                                and c.responded_at + interval '5 minutes') then 'call'
                            else 'fine' end,
                       null, null
                from checkins c join mine on mine.id = c.recipient_id
                where c.status in ('replied', 'missed') and coalesce(c.responded_at, c.sent_at) >= cast(:from as timestamptz)
              union all
                select 'vaccine:' || v.id, 'vaccine_given', mine.id, v.updated_at, v.vaccine_code, v.dose_label,
                       v.given_on::timestamp at time zone 'UTC', null, null, null
                from child_vaccines v join mine on mine.id = v.recipient_id
                where v.given_on is not null and v.updated_at >= cast(:from as timestamptz)
              union all
                select 'appt:' || a.id, 'appointment_added', mine.id, a.created_at, a.doctor_name, null, a.datetime, null,
                       null, a.created_by::text
                from appointments a join mine on mine.id = a.recipient_id
                where a.created_at >= cast(:from as timestamptz)
              union all
                select 'med_add:' || m.id, 'medicine_added', mine.id, m.created_at, m.name, m.dosage, null, null, null, null
                from medicines m join mine on mine.id = m.recipient_id
                where m.created_at >= cast(:from as timestamptz)
              union all
                select 'med_pause:' || m.id, 'medicine_paused', mine.id, m.updated_at, m.name, m.dosage, null, null, null, null
                from medicines m join mine on mine.id = m.recipient_id
                where not m.active and m.archived_at is null and m.updated_at >= cast(:from as timestamptz) and m.updated_at > m.created_at
              union all
                select 'med_stop:' || m.id, 'medicine_stopped', mine.id, m.archived_at, m.name, m.dosage, null, null, null, null
                from medicines m join mine on mine.id = m.recipient_id
                where m.archived_at >= cast(:from as timestamptz)
              union all
                select 'consent:' || r.id || ':' || floor(extract(epoch from r.consent_at))::bigint, 'consent_given', mine.id,
                       r.consent_at, null, null, null, null, null, null
                from care_recipients r join mine on mine.id = r.id
                where r.consent_at >= cast(:from as timestamptz) and not r.is_self
              union all
                select 'stop:' || r.id || ':' || floor(extract(epoch from r.opted_out_at))::bigint, 'consent_stopped', mine.id,
                       r.opted_out_at, null, null, null, null, null, null
                from care_recipients r join mine on mine.id = r.id
                where r.opted_out_at >= cast(:from as timestamptz) and not r.is_self
            )
            select ev.key, ev.kind, ev.recipient_id, ev.at, ev.title, ev.detail, ev.due_at, ev.answer, ev.source,
                   ev.actor_id, p.name as actor_name
            from ev
            left join profiles p on p.id::text = ev.actor_id
            where ev.at is not null and ev.at <= now() + interval '1 minute'
              and (cast(:before_at as timestamptz) is null or (ev.at, ev.key) < (cast(:before_at as timestamptz), :before_key))
            order by ev.at desc, ev.key desc
            limit :lim
            """;

    private record Row(String key, String kind, UUID recipientId, OffsetDateTime at, String title, String detail,
                       OffsetDateTime dueAt, String answer, String source, String actorId, String actorName) {}

    @GetMapping
    public FeedResponse feed(@AuthenticationPrincipal AuthenticatedUser user,
                             @RequestParam(required = false) UUID recipient,
                             @RequestParam(required = false) String cursor,
                             @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int lim = Math.max(1, Math.min(limit, MAX_LIMIT));
        Instant beforeAt = null;
        String beforeKey = null;
        if (cursor != null && !cursor.isBlank()) {
            int cut = cursor.indexOf('~');
            try {
                beforeAt = Instant.parse(cursor.substring(0, cut));
                beforeKey = cursor.substring(cut + 1);
            } catch (RuntimeException e) {
                throw new ResponseStatusException(BAD_REQUEST, "cursor_invalid");
            }
        }

        List<Person> people = people(user.id());
        if (recipient != null && people.stream().noneMatch(p -> p.id().equals(recipient.toString()))) {
            throw new ResponseStatusException(NOT_FOUND, "Parent profile not found");
        }
        Map<String, Person> byId = new HashMap<>();
        for (Person p : people) byId.put(p.id(), p);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("uid", user.id())
                .addValue("only", recipient == null ? null : recipient.toString())
                .addValue("from", Timestamp.from(Instant.now().minusSeconds(DAYS * 86_400L)))
                .addValue("before_at", beforeAt == null ? null : Timestamp.from(beforeAt))
                .addValue("before_key", beforeKey == null ? "" : beforeKey)
                .addValue("lim", lim + 1);
        List<Row> rows = named.query(EVENTS, params, FeedController::row);
        boolean more = rows.size() > lim;
        if (more) rows = rows.subList(0, lim);

        Map<String, int[]> hearts = hearts(user.id(), rows);
        List<Item> items = new ArrayList<>(rows.size());
        for (Row r : rows) {
            Person person = byId.get(r.recipientId().toString());
            int[] h = hearts.getOrDefault(r.recipientId() + "|" + r.key(), new int[] {0, 0});
            String title = r.title();
            String titleHi = null;
            if ("vaccine_given".equals(r.kind())) {
                VaccineSchedule.Name n = vaccines.name(r.title());
                title = n.en();
                titleHi = n.hi();
            }
            items.add(new Item(r.key(), r.kind(), r.recipientId().toString(), r.at(), title, titleHi, r.detail(), r.dueAt(),
                    r.answer(), actor(r, person, user.id()), h[0], h[1] > 0));
        }
        String next = more && !rows.isEmpty()
                ? rows.get(rows.size() - 1).at().toInstant() + "~" + rows.get(rows.size() - 1).key()
                : null;
        return new FeedResponse(people, items, next);
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getString("key"), rs.getString("kind"), UUID.fromString(rs.getString("recipient_id")),
                odt(rs.getTimestamp("at")), rs.getString("title"), rs.getString("detail"), odt(rs.getTimestamp("due_at")),
                rs.getString("answer"), rs.getString("source"), rs.getString("actor_id"), rs.getString("actor_name"));
    }

    private static OffsetDateTime odt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) return null;
        return name.trim().split("\\s+")[0];
    }

    /**
     * Who did it. A dose: the family member who marked it (confirmed_by), else whoever got the WhatsApp reminder; a dose marked in
     * the app without a name says so. An appointment: who added it. The rest has no author.
     */
    static Actor actor(Row r, Person person, UUID me) {
        switch (r.kind()) {
            case "dose_taken" -> {
                if (r.actorId() != null) {
                    return r.actorId().equals(me.toString()) ? new Actor("me", null) : new Actor("member", firstName(r.actorName()));
                }
                if (r.source() != null && !r.source().toLowerCase().startsWith("whatsapp")) return new Actor("app", null);
                return receiver(person);
            }
            case "checkin_answered", "consent_given", "consent_stopped" -> {
                return receiver(person);
            }
            case "appointment_added" -> {
                if (r.actorId() == null) return null;
                return r.actorId().equals(me.toString()) ? new Actor("me", null) : new Actor("member", firstName(r.actorName()));
            }
            default -> {
                return null;
            }
        }
    }

    private static Actor receiver(Person person) {
        if (person == null) return null;
        return switch (person.reminderTo()) {
            case "helper" -> new Actor("helper", person.reminderName());
            case "caregiver" -> person.reminderIsMe() ? new Actor("me", null) : new Actor("caregiver", firstName(person.reminderName()));
            default -> new Actor("person", null);
        };
    }

    private List<Person> people(UUID userId) {
        return jdbc.query("""
                        select r.id, coalesce(nullif(btrim(r.call_name), ''), r.name), r.care_type, r.timezone, r.is_self, r.reminder_to,
                               r.reminder_name, r.reminder_relation, r.reminder_user_id = ? as reminder_is_me
                        from care_recipients r
                        where r.circle_id in (select cm.circle_id from care_circle_members cm where cm.user_id = ?)
                        order by r.is_self desc, r.created_at
                        """,
                (rs, i) -> new Person(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getBoolean(9)),
                userId, userId);
    }

    /** Heart counts and "mine" for the page. Without the feed_hearts table (migration not applied yet) the feed still works. */
    private Map<String, int[]> hearts(UUID userId, List<Row> rows) {
        Map<String, int[]> out = new HashMap<>();
        if (rows.isEmpty()) return out;
        List<String> keys = rows.stream().map(Row::key).distinct().toList();
        List<UUID> ids = rows.stream().map(Row::recipientId).distinct().toList();
        try {
            jdbc.query("""
                            select recipient_id, item_key, count(*), count(*) filter (where user_id = ?)
                            from feed_hearts where recipient_id = any(?) and item_key = any(?)
                            group by recipient_id, item_key
                            """,
                    ps -> {
                        ps.setObject(1, userId);
                        ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
                        ps.setArray(3, ps.getConnection().createArrayOf("text", keys.toArray()));
                    },
                    rs -> {
                        out.put(rs.getString(1) + "|" + rs.getString(2), new int[] {rs.getInt(3), rs.getInt(4)});
                    });
        } catch (DataAccessException e) {
            log.warn("feed hearts unavailable: {}", e.getClass().getSimpleName());
        }
        return out;
    }

    /** Give (on = true, the default) or take back your ♥ on one item. Only inside your own circles. */
    @PutMapping("/hearts")
    public HeartResponse heart(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody HeartRequest req) {
        if (req == null || req.recipientId() == null || req.itemKey() == null || !ITEM_KEY.matcher(req.itemKey()).matches()) {
            throw new ResponseStatusException(BAD_REQUEST, "item_invalid");
        }
        recipients.findForUser(req.recipientId(), user.id())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Parent profile not found"));
        if (req.on() == null || req.on()) {
            jdbc.update("insert into feed_hearts (recipient_id, item_key, user_id) values (?, ?, ?) on conflict do nothing",
                    req.recipientId(), req.itemKey(), user.id());
        } else {
            jdbc.update("delete from feed_hearts where recipient_id = ? and item_key = ? and user_id = ?",
                    req.recipientId(), req.itemKey(), user.id());
        }
        return jdbc.queryForObject("""
                        select count(*), count(*) filter (where user_id = ?) from feed_hearts
                        where recipient_id = ? and item_key = ?
                        """,
                (rs, i) -> new HeartResponse(rs.getInt(1), rs.getInt(2) > 0),
                user.id(), req.recipientId(), req.itemKey());
    }
}
