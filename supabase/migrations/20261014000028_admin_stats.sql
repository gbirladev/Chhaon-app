-- Pilot stats for the owner of the app. Totals only: no names, phone numbers, emails or health details leave this function.
-- Only an account marked is_admin can call it, and nobody can mark themselves (set by hand in the Supabase SQL editor).

alter table public.profiles add column is_admin boolean not null default false;

-- Same guard as the plan: a caregiver cannot change their own plan, trial or admin flag.
create or replace function public.guard_profile_plan() returns trigger
language plpgsql set search_path = '' as $$
begin
  if (select auth.role()) = 'authenticated'
     and (new.plan is distinct from old.plan or new.trial_ends_at is distinct from old.trial_ends_at or new.is_admin is distinct from old.is_admin) then
    raise exception 'the plan can only be changed by the system' using errcode = '42501';
  end if;
  return new;
end $$;

create function public.admin_stats(p_days integer default 14) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_days integer := least(greatest(coalesce(p_days, 14), 1), 90);
  v_today date := (now() at time zone 'Asia/Kolkata')::date;
  v_result jsonb;
begin
  if v_uid is null or not coalesce((select p.is_admin from public.profiles p where p.id = v_uid), false) then
    raise exception 'not allowed' using errcode = '42501';
  end if;

  with seen as (   -- when each caregiver last used the app: last login or last time the phone refreshed the session
    select p.id, p.created_at, p.plan, p.trial_ends_at,
           greatest(u.last_sign_in_at, (select max(s.refreshed_at) from auth.sessions s where s.user_id = p.id)) as last_seen
      from public.profiles p join auth.users u on u.id = p.id
  )
  select jsonb_build_object(
    'generated_at', now(),
    'caregivers', jsonb_build_object(
      'total', (select count(*) from seen),
      'new_7d', (select count(*) from seen where created_at > now() - interval '7 days'),
      'active_7d', (select count(*) from seen where last_seen > now() - interval '7 days'),
      'joined_4w_ago', (select count(*) from seen where created_at <= now() - interval '28 days'),
      'still_active_of_those', (select count(*) from seen where created_at <= now() - interval '28 days' and last_seen > now() - interval '7 days')
    ),
    'parents', jsonb_build_object(
      'total', (select count(*) from public.care_recipients),
      'agreed', (select count(*) from public.care_recipients where consent_at is not null and opted_out_at is null),
      'waiting', (select count(*) from public.care_recipients where consent_at is null and opted_out_at is null and whatsapp_phone is not null),
      'stopped', (select count(*) from public.care_recipients where opted_out_at is not null),
      'paused_by_plan', (select count(*) from public.paused_recipients())
    ),
    'medicines', jsonb_build_object(
      'active', (select count(*) from public.medicines where active),
      'paused_by_plan', (select count(*) from public.paused_medicines())
    ),
    'reminders_7d', jsonb_build_object(
      'sent', (select count(*) from public.dose_events where sent_at > now() - interval '7 days'),
      'taken', (select count(*) from public.dose_events where sent_at > now() - interval '7 days' and status = 'taken'),
      'missed', (select count(*) from public.dose_events where sent_at > now() - interval '7 days' and status = 'missed')
    ),
    'checkins_7d', jsonb_build_object(
      'sent', (select count(*) from public.checkins where sent_at > now() - interval '7 days'),
      'replied', (select count(*) from public.checkins where sent_at > now() - interval '7 days' and status = 'replied'),
      'missed', (select count(*) from public.checkins where sent_at > now() - interval '7 days' and status = 'missed')
    ),
    'whatsapp_7d', jsonb_build_object(   -- messages we sent: Utility (reminders, check-ins, alerts, appointment reminders) and Marketing (consent / opt-in requests)
      'utility', (select count(*) from public.dose_events where sent_at > now() - interval '7 days')
               + (select count(*) from public.checkins where sent_at > now() - interval '7 days')
               + (select count(*) from public.notifications where whatsapp_sent_at > now() - interval '7 days')
               + (select count(*) from public.appointments where reminded_day_before_at > now() - interval '7 days')
               + (select count(*) from public.appointments where reminded_morning_at > now() - interval '7 days'),
      'marketing', (select count(*) from public.consent_requests where requested_at > now() - interval '7 days')
    ),
    'plans', jsonb_build_object(
      'by_plan', coalesce((select jsonb_object_agg(plan, n) from (select plan, count(*) as n from seen group by plan) x), '{}'::jsonb),
      'on_trial', (select count(*) from seen where plan = 'free' and trial_ends_at > now()),
      'trial_ending_7d', (select count(*) from seen where plan = 'free' and trial_ends_at > now() and trial_ends_at <= now() + interval '7 days'),
      'trial_ended_free', (select count(*) from seen where plan = 'free' and trial_ends_at <= now())
    ),
    'family', jsonb_build_object(
      'members_joined', (select count(*) from public.family_invites where accepted_at is not null),
      'circles_with_more_than_one', (select count(*) from (select circle_id from public.care_circle_members group by circle_id having count(*) > 1) c)
    ),
    'daily', coalesce((
      select jsonb_agg(jsonb_build_object(
        'date', d.day, 'reminders_sent', coalesce(r.sent, 0), 'reminders_taken', coalesce(r.taken, 0), 'new_caregivers', coalesce(n.new, 0)) order by d.day)
      from generate_series(v_today - (v_days - 1), v_today, interval '1 day') as g(day_ts)
      cross join lateral (select g.day_ts::date as day) d
      left join (select (sent_at at time zone 'Asia/Kolkata')::date as day, count(*) as sent, count(*) filter (where status = 'taken') as taken
                   from public.dose_events where sent_at is not null group by 1) r on r.day = d.day
      left join (select (created_at at time zone 'Asia/Kolkata')::date as day, count(*) as new from public.profiles group by 1) n on n.day = d.day
    ), '[]'::jsonb)
  ) into v_result;
  return v_result;
end $$;
revoke execute on function public.admin_stats(integer) from public, anon;
grant execute on function public.admin_stats(integer) to authenticated;
