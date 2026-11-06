-- Archive a medicine instead of deleting it ("Stopped taking it").
--
-- Deleting a medicine removes its whole dose history (medicines -> medicine_schedules -> dose_events cascade), so the
-- doctor summary and the stats lose it. An ARCHIVED medicine:
--   * is hidden from the active list and from Today, gets no reminders, no new dose events and no low-stock alerts;
--   * keeps its dose history, which is still counted where history is shown (doctor summary, activity, stats);
--   * does not count towards the plan's medicine limit, and can be restored (then it counts again).
-- Delete stays, for "added by mistake" only.
--
-- How: medicines.archived_at (NULL = not archived). An archived medicine is always also inactive (check constraint
-- below), so everything that already skips paused medicines (`m.active`: generate_dose_events, paused_medicines,
-- notify_low_stock, public_emergency_card, plan_overview, weekly summary, monitor) skips archived ones too, without
-- rewriting those functions. Pause (active = false, archived_at NULL) stays the short "pause for a while" switch.
--
-- Owner: paste into the SQL Editor BEFORE deploying the backend (it maps archived_at and validates the schema at
-- startup), then deploy the web app. Safe to run twice.

alter table public.medicines add column if not exists archived_at timestamptz;

comment on column public.medicines.archived_at is
  'When the family archived the medicine ("stopped taking it"); NULL = not archived. Archived = always inactive, '
  'no reminders or alerts, history kept, not counted in the plan limit. Migration 0056.';

-- An archived medicine can never be active (restore = archived_at NULL and active true together).
alter table public.medicines drop constraint if exists medicines_archived_not_active;
alter table public.medicines add constraint medicines_archived_not_active check (archived_at is null or not active);

-- ---------------------------------------------------------------- plan limit: archived medicines do not count
-- At most max_medicines NOT archived medicines per parent (paused ones still count, as before). Archived ones are free,
-- but a parent can hold at most 200 medicines in all (a system ceiling, so a runaway client cannot add rows forever).
create or replace function public.limit_medicines() returns trigger
language plpgsql set search_path = '' as $$
declare v_max integer;
begin
  perform pg_advisory_xact_lock(hashtext('medicines:' || new.recipient_id::text));
  select (public.plan_for_circle(r.circle_id)).max_medicines into v_max from public.care_recipients r where r.id = new.recipient_id;
  if (select count(*) from public.medicines where recipient_id = new.recipient_id and archived_at is null) >= coalesce(v_max, 100)   -- 100 = system ceiling for "unlimited"
     or (select count(*) from public.medicines where recipient_id = new.recipient_id) >= 200 then
    raise exception 'too many medicines' using errcode = 'P0001', hint = 'too_many_medicines';
  end if;
  return new;
end $$;

-- ---------------------------------------------------------------- restoring checks the plan limit again
create or replace function public.medicine_restore_limit() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_max integer;
begin
  if old.archived_at is not null and new.archived_at is null then
    perform pg_advisory_xact_lock(hashtext('medicines:' || new.recipient_id::text));
    select (public.plan_for_circle(r.circle_id)).max_medicines into v_max from public.care_recipients r where r.id = new.recipient_id;
    if (select count(*) from public.medicines
         where recipient_id = new.recipient_id and archived_at is null and id <> new.id) >= coalesce(v_max, 100) then
      raise exception 'too many medicines' using errcode = 'P0001', hint = 'too_many_medicines';
    end if;
  end if;
  return new;
end $$;
revoke execute on function public.medicine_restore_limit() from public, anon, authenticated;

drop trigger if exists medicines_restore_limit on public.medicines;
create trigger medicines_restore_limit before update of archived_at on public.medicines
  for each row execute function public.medicine_restore_limit();

-- ---------------------------------------------------------------- archiving removes the doses still waiting to be sent
-- Doses already sent, answered or missed stay (that is the history). The scheduler also drops pending doses of inactive
-- medicines on its next run; this makes it immediate, whichever way the row is archived.
create or replace function public.medicine_archived_drop_pending() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if old.archived_at is null and new.archived_at is not null then
    delete from public.dose_events de
     using public.medicine_schedules s
     where de.schedule_id = s.id and s.medicine_id = new.id and de.status = 'pending';
  end if;
  return null;
end $$;
revoke execute on function public.medicine_archived_drop_pending() from public, anon, authenticated;

drop trigger if exists medicines_archived_drop_pending on public.medicines;
create trigger medicines_archived_drop_pending after update of archived_at on public.medicines
  for each row execute function public.medicine_archived_drop_pending();
