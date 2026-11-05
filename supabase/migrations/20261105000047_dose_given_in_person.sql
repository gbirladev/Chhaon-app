-- "Mark as given": a family member marks a dose as given in person, or as told on the phone.
--
-- Until now a dose only became "taken" through a WhatsApp reply. A caregiver on Today can now mark a dose that is waiting
-- (pending / sent), skipped (reminder not sent) or missed as given. It records WHO marked it and HOW, uses stock exactly like a
-- WhatsApp "1", and can be undone for 10 minutes by the person who marked it (status and stock come back).
--
--   confirmed_by        the family member who marked it (null for a WhatsApp reply)
--   confirm_source      whatsapp | caregiver_in_person ("I gave it / I saw it taken") | caregiver_told ("Sunita told me on the phone")
--   confirm_prev_status the status before the manual mark (what Undo restores)
--   confirm_stock_used  tablets actually taken off the stock by the manual mark (what Undo gives back; 0 when no stock is tracked
--                       or it was already 0)
--
-- WhatsApp stays idempotent: every reply path only changes a dose that is still 'sent' or 'missed' (ReplyHandlers,
-- confirm_dose_slot), so a "1" arriving after a manual mark changes nothing and uses no stock. A trigger fills
-- confirm_source = 'whatsapp' for every other way a dose becomes taken, without touching the reply code.
--
-- Sydney: paste into the SQL editor BEFORE deploying the backend (the backend reads these columns; it validates its mapped
-- tables at start-up). Safe to run twice.

alter table public.dose_events add column if not exists confirmed_by uuid references public.profiles (id) on delete set null;
alter table public.dose_events add column if not exists confirm_source text;
alter table public.dose_events add column if not exists confirm_prev_status public.dose_status;
alter table public.dose_events add column if not exists confirm_stock_used integer;

alter table public.dose_events drop constraint if exists dose_events_confirm_source_known;
alter table public.dose_events add constraint dose_events_confirm_source_known check (
  confirm_source is null or confirm_source in ('whatsapp', 'caregiver_in_person', 'caregiver_told')
);
alter table public.dose_events drop constraint if exists dose_events_confirm_stock_used_valid;
alter table public.dose_events add constraint dose_events_confirm_stock_used_valid check (
  confirm_stock_used is null or confirm_stock_used between 0 and 10
);

create index if not exists dose_events_confirmed_by_idx on public.dose_events (confirmed_by) where confirmed_by is not null;

-- Every dose that becomes taken without saying how was confirmed on WhatsApp (the reply pipeline, "all taken", the Python webhook).
create or replace function public.dose_events_default_confirm_source() returns trigger
language plpgsql set search_path = '' as $$
begin
  if new.status in ('taken', 'taken_late') and old.status not in ('taken', 'taken_late') and new.confirm_source is null then
    new.confirm_source := 'whatsapp';
  end if;
  return new;
end $$;

drop trigger if exists dose_events_default_confirm_source on public.dose_events;
create trigger dose_events_default_confirm_source before update of status on public.dose_events
  for each row execute function public.dose_events_default_confirm_source();

-- Doses confirmed before this migration were all WhatsApp replies.
update public.dose_events set confirm_source = 'whatsapp'
 where status in ('taken', 'taken_late') and confirm_source is null;

-- ---------------------------------------------------------------- mark as given
-- Called by the backend (service role) after it checked the caller; checks the circle again itself.
-- Answers: {"result": "marked", "status": ...} | {"result": "already", "source": ...} | {"result": "not_allowed"} |
--          {"result": "too_early"} | {"result": "too_old"}. Raises 42501 'not found' for a dose outside the caller's circles.
create or replace function public.mark_dose_given(p_dose uuid, p_user uuid, p_source text, p_units integer default 1)
returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_dose record;
  v_status public.dose_status;
  v_used integer := 0;
  v_units integer := least(greatest(coalesce(p_units, 1), 1), 10);
  v_old integer;
  v_new integer;
begin
  if p_source not in ('caregiver_in_person', 'caregiver_told') then
    raise exception 'unknown source' using errcode = '22023';
  end if;
  select de.id, de.status, de.scheduled_at, m.id medicine_id, m.recipient_id, r.circle_id
    into v_dose
    from public.dose_events de
    join public.medicine_schedules s on s.id = de.schedule_id
    join public.medicines m on m.id = s.medicine_id
    join public.care_recipients r on r.id = m.recipient_id
   where de.id = p_dose
     and exists (select 1 from public.care_circle_members cm where cm.circle_id = r.circle_id and cm.user_id = p_user)
     for update of de;
  if not found then
    raise exception 'not found' using errcode = '42501';
  end if;
  if v_dose.status in ('taken', 'taken_late') then
    return jsonb_build_object('result', 'already',
      'source', (select confirm_source from public.dose_events where id = p_dose));
  end if;
  if v_dose.status not in ('pending', 'sent', 'missed', 'skipped') then
    return jsonb_build_object('result', 'not_allowed');
  end if;
  if v_dose.scheduled_at > now() + interval '1 hour' then
    return jsonb_build_object('result', 'too_early');
  end if;
  if v_dose.scheduled_at < now() - interval '36 hours' then
    return jsonb_build_object('result', 'too_old');
  end if;

  v_status := case when v_dose.status = 'missed' then 'taken_late'::public.dose_status else 'taken'::public.dose_status end;

  -- Stock, like consume_stock(): never below 0. Remember what really came off, so Undo gives back exactly that.
  select stock_count into v_old from public.medicines where id = v_dose.medicine_id for update;
  if v_old is not null then
    update public.medicines set stock_count = greatest(stock_count - v_units, 0)
     where id = v_dose.medicine_id returning stock_count into v_new;
    v_used := v_old - v_new;
  end if;

  update public.dose_events
     set status = v_status, responded_at = now(), confirmed_by = p_user, confirm_source = p_source,
         confirm_prev_status = v_dose.status, confirm_stock_used = v_used
   where id = p_dose;

  -- "Missed" is no longer true: the bell (and the family WhatsApp alert still waiting in the outbox) must not say it.
  -- One alert covers every dose of that person due at the same moment, so it goes only when none of them is still missed.
  if v_dose.status = 'missed' and not exists (
      select 1 from public.dose_events de2
        join public.medicine_schedules s2 on s2.id = de2.schedule_id
        join public.medicines m2 on m2.id = s2.medicine_id
       where m2.recipient_id = v_dose.recipient_id and de2.scheduled_at = v_dose.scheduled_at and de2.status = 'missed') then
    delete from public.notifications n
     where n.type = 'dose_missed'
       and n.payload->>'recipient_id' = v_dose.recipient_id::text
       and n.payload->>'dose_event_id' in (
         select de3.id::text from public.dose_events de3
           join public.medicine_schedules s3 on s3.id = de3.schedule_id
           join public.medicines m3 on m3.id = s3.medicine_id
          where m3.recipient_id = v_dose.recipient_id and de3.scheduled_at = v_dose.scheduled_at);
  end if;

  return jsonb_build_object('result', 'marked', 'status', v_status);
end $$;
revoke execute on function public.mark_dose_given(uuid, uuid, text, integer) from public, anon, authenticated;
grant execute on function public.mark_dose_given(uuid, uuid, text, integer) to service_role;

-- ---------------------------------------------------------------- undo
-- Only the family member who marked it, only a manual mark, only within p_minutes. Restores status and stock.
-- Answers {"result": "undone" | "not_marked" | "not_yours" | "too_late"}; 42501 'not found' outside the caller's circles.
create or replace function public.undo_dose_given(p_dose uuid, p_user uuid, p_minutes integer default 10)
returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_dose record;
begin
  select de.id, de.status, de.responded_at, de.confirmed_by, de.confirm_source, de.confirm_prev_status,
         de.confirm_stock_used, m.id medicine_id
    into v_dose
    from public.dose_events de
    join public.medicine_schedules s on s.id = de.schedule_id
    join public.medicines m on m.id = s.medicine_id
    join public.care_recipients r on r.id = m.recipient_id
   where de.id = p_dose
     and exists (select 1 from public.care_circle_members cm where cm.circle_id = r.circle_id and cm.user_id = p_user)
     for update of de;
  if not found then
    raise exception 'not found' using errcode = '42501';
  end if;
  if v_dose.confirm_source is null or v_dose.confirm_source not in ('caregiver_in_person', 'caregiver_told')
     or v_dose.status not in ('taken', 'taken_late') or v_dose.confirm_prev_status is null then
    return jsonb_build_object('result', 'not_marked');
  end if;
  if v_dose.confirmed_by is distinct from p_user then
    return jsonb_build_object('result', 'not_yours');
  end if;
  if v_dose.responded_at is null or v_dose.responded_at < now() - make_interval(mins => greatest(p_minutes, 0)) then
    return jsonb_build_object('result', 'too_late');
  end if;

  if coalesce(v_dose.confirm_stock_used, 0) > 0 then
    update public.medicines set stock_count = stock_count + v_dose.confirm_stock_used
     where id = v_dose.medicine_id and stock_count is not null;
  end if;
  update public.dose_events
     set status = v_dose.confirm_prev_status, responded_at = null, confirmed_by = null, confirm_source = null,
         confirm_prev_status = null, confirm_stock_used = null
   where id = p_dose;
  return jsonb_build_object('result', 'undone', 'status', v_dose.confirm_prev_status);
end $$;
revoke execute on function public.undo_dose_given(uuid, uuid, integer) from public, anon, authenticated;
grant execute on function public.undo_dose_given(uuid, uuid, integer) to service_role;
