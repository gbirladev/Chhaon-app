-- Sydney update bundle, 2026-11-05: migrations 0041 and 0043-0053 in order, then the permission repair scripts.
-- Paste the WHOLE file into the Sydney SQL Editor and press Run once. All-or-nothing: if any statement fails,
-- everything is rolled back and Sydney stays as it was. Every part is idempotent (safe to run again).
-- Sydney already had 0042 (pill colour/shape) when this was made.
begin;

-- ===================== 20261104000041_checkin_message_id.sql =====================
-- Check-in messages remember the WhatsApp message id they were sent with (like dose_events.whatsapp_message_id).
--
-- WHY: the new WhatsApp route (self-hosted gateway) has no quick-reply buttons, so a parent answers by TYPING. When the
-- parent swipes "reply" on a message, WhatsApp tells us which message was quoted; matching that id to the dose reminder or the
-- daily check-in it was sent as tells us exactly what the answer is for ("1" under the morning check-in is not "Taken").
-- Doses already had the column; check-ins did not.
--
-- The Java code does not depend on this column to run: it stores the id best-effort and only looks quotes up when the column exists.
alter table public.checkins add column if not exists whatsapp_message_id text;
create index if not exists checkins_whatsapp_message_id_idx
  on public.checkins (whatsapp_message_id) where whatsapp_message_id is not null;


-- ===================== 20261105000043_care_type.sql =====================
-- Who the family cares for (docs/use-cases.md, step 1): an older parent (as before), a pregnancy, or (later) a child.
-- Every existing profile becomes 'older', so nothing changes for current families. A pregnancy may carry the due date the
-- doctor gave; the app shows the pregnancy week from it (in the person's own time zone) and words its texts for a pregnancy.
-- The due date belongs to a pregnancy only: switching the profile away from 'pregnant' clears it (the backend does this,
-- the check below makes sure). The "sane window" (about 10 months ahead, 2 months past) is checked by the backend, not
-- here, because it depends on today's date.
-- 'child' is accepted by the database already but the app does not offer it yet (step 3: date of birth, guardian, vaccines).
-- Saved by the Java backend (POST/PUT /api/v1/recipients).
-- Sydney: paste into the SQL Editor (no migration history there) BEFORE deploying the backend that maps these columns:
-- the backend validates its tables at start-up and would refuse to start without them. Safe to run twice.

alter table public.care_recipients add column if not exists care_type text not null default 'older';
alter table public.care_recipients add column if not exists due_date date;

alter table public.care_recipients drop constraint if exists care_recipients_care_type_known;
alter table public.care_recipients add constraint care_recipients_care_type_known check (
  care_type in ('older', 'pregnant', 'child')
);

alter table public.care_recipients drop constraint if exists care_recipients_due_date_pregnant_only;
alter table public.care_recipients add constraint care_recipients_due_date_pregnant_only check (
  due_date is null or care_type = 'pregnant'
);

comment on column public.care_recipients.care_type is 'older (default) | pregnant | child: who the family cares for; changes the wording in the app and on WhatsApp';
comment on column public.care_recipients.due_date is 'Expected due date given by the doctor (pregnant only); the pregnancy week is counted back from it';


-- ===================== 20261105000044_reminder_receiver.sql =====================
-- Who gets the WhatsApp reminders for a person (docs/use-cases.md decision 5, build order step 2; docs/reminder-receiver.md).
--
-- Until now the reminders always went to the person themself (care_recipients.whatsapp_phone). A family can now choose:
--   person    the person themself (default; every existing profile; exactly today's behaviour and texts)
--   caregiver "Me": the family member who chose it (reminder_user_id), on their own WhatsApp number
--   helper    someone else who gives the medicine: a maid, an attendant, Nani, the husband ... (reminder_name + reminder_relation)
--
-- whatsapp_phone keeps its meaning "the number that RECEIVES the reminders" (and therefore the number whose replies count),
-- so the reply routing, STOP and the consent bookkeeping stay per NUMBER, as before.
-- Consent belongs to that number AND to the role it was asked for: changing who gets the reminders resets consent (like a
-- changed number), so the new receiver is asked again.
--
-- Sydney: paste into the SQL Editor (no migration history there) BEFORE deploying the backend that maps these columns:
-- the backend validates its tables at start-up and would refuse to start without them. Safe to run twice.

alter table public.care_recipients add column if not exists reminder_to text not null default 'person';
alter table public.care_recipients add column if not exists reminder_name text;
alter table public.care_recipients add column if not exists reminder_relation text;
alter table public.care_recipients add column if not exists reminder_user_id uuid references public.profiles (id) on delete set null;

alter table public.care_recipients drop constraint if exists care_recipients_reminder_to_known;
alter table public.care_recipients add constraint care_recipients_reminder_to_known check (
  reminder_to in ('person', 'caregiver', 'helper')
);

-- The "Me" profile (is_self) always gets its own reminders. A helper needs a name to be greeted with.
alter table public.care_recipients drop constraint if exists care_recipients_reminder_receiver_valid;
alter table public.care_recipients add constraint care_recipients_reminder_receiver_valid check (
  (not is_self or reminder_to = 'person')
  and (reminder_to <> 'helper' or (reminder_name is not null and length(btrim(reminder_name)) > 0))
  and (reminder_name is null or length(reminder_name) <= 60)
  and (reminder_relation is null or length(reminder_relation) <= 30)
);

comment on column public.care_recipients.reminder_to is
  'Who gets the WhatsApp reminders on whatsapp_phone: person (themself, default) | caregiver (reminder_user_id) | helper (reminder_name)';
comment on column public.care_recipients.reminder_name is 'Name the reminder receiver is greeted with (helper, or the caregiver''s name)';
comment on column public.care_recipients.reminder_relation is 'Helper''s relation in the family''s own words: maid, Nani, nanny, attendant ...';
comment on column public.care_recipients.reminder_user_id is 'The family member who gets the reminders when reminder_to = caregiver';

-- Consent belongs to the number that receives the messages: a new number, OR a new receiver role on it, has agreed to nothing.
-- (Same trigger as migration 0006, one condition added. Name sorts after care_recipients_guard_consent, see 0006.)
create or replace function public.reset_consent_on_phone_change() returns trigger
language plpgsql set search_path = '' as $$
begin
  if new.whatsapp_phone is distinct from old.whatsapp_phone or new.reminder_to is distinct from old.reminder_to then
    new.consent_at := null;
    new.opted_out_at := null;
    new.consent_requested_at := null;
  end if;
  return new;
end $$;

-- The family member who got the reminders deleted their account (reminder_user_id is set to null by the foreign key):
-- their number must not keep receiving reminders about someone else. Back to "the person", without a number until the
-- family adds one. (Runs before the consent reset above, which then clears the consent.)
create or replace function public.reminder_receiver_account_gone() returns trigger
language plpgsql set search_path = '' as $$
begin
  if old.reminder_to = 'caregiver' and new.reminder_to = 'caregiver'
     and old.reminder_user_id is not null and new.reminder_user_id is null then
    new.reminder_to := 'person';
    new.reminder_name := null;
    new.reminder_relation := null;
    new.whatsapp_phone := null;
  end if;
  return new;
end $$;

drop trigger if exists care_recipients_receiver_account_gone on public.care_recipients;
create trigger care_recipients_receiver_account_gone
  before update on public.care_recipients
  for each row execute function public.reminder_receiver_account_gone();

-- "Me": your own number on a parent's profile used to be refused as a typing mistake (migration 0037). When you CHOOSE to get
-- that parent's reminders yourself (reminder_to = caregiver), it is exactly your own number: allowed. Everything else unchanged.
create or replace function public.guard_self_profile() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_owner uuid;
begin
  select created_by into v_owner from public.care_circles where id = new.circle_id;
  if tg_op = 'UPDATE' then
    if new.is_self is distinct from old.is_self or (new.is_self and new.circle_id is distinct from old.circle_id) then
      raise exception 'an own profile cannot be changed into a parent profile' using errcode = '42501';
    end if;
  elsif not new.is_self then
    if exists (select 1 from public.care_recipients r where r.circle_id = new.circle_id and r.is_self) then
      raise exception 'self_circle_private' using errcode = 'P0001', hint = 'self_private';
    end if;
  else
    if exists (select 1 from public.care_recipients r join public.care_circles c on c.id = r.circle_id where r.is_self and c.created_by = v_owner) then
      raise exception 'self_exists' using errcode = 'P0001', hint = 'self_exists';
    end if;
    if exists (select 1 from public.care_recipients r where r.circle_id = new.circle_id)
       or exists (select 1 from public.care_circle_members m where m.circle_id = new.circle_id and m.user_id is distinct from v_owner) then
      raise exception 'self_circle_not_private' using errcode = 'P0001', hint = 'self_private';
    end if;
  end if;

  if new.whatsapp_phone is not null and (tg_op = 'INSERT' or new.whatsapp_phone is distinct from old.whatsapp_phone)
     and exists (
       select 1 from public.care_recipients r join public.care_circles c on c.id = r.circle_id
        where c.created_by = v_owner and r.id <> new.id and r.whatsapp_phone = new.whatsapp_phone
          and ((r.is_self and new.reminder_to is distinct from 'caregiver')
               or (new.is_self and r.reminder_to is distinct from 'caregiver'))) then
    raise exception 'self_number_in_use' using errcode = 'P0001', hint = 'self_number';
  end if;
  return new;
end $$;


-- ===================== 20261105000045_child.sql =====================
-- Child care, step 3 of docs/use-cases.md: guardian confirmation and the vaccination plan of a child.
--
-- Sydney: paste into the SQL Editor (no migration history there) BEFORE deploying the backend that uses these tables: the backend's
-- vaccine endpoints and the vaccine reminder read them. Safe to run twice. Then also run supabase/maintenance/restore_table_permissions.sql
-- (it now lists child_vaccines) or check with table_permissions_check.sql.
--
-- Independent of 20261105000043 (care_type, due_date) and 20261105000044 (who gets the reminders); any order works.
--
-- 1. Date of birth: care_recipients ALREADY has a nullable column `dob` (since the first schema, sent by the recipient create/update
--    API and passed through by the profile form). It is reused for children instead of adding a second "date_of_birth" column.
--    Only a loose sanity rule is added here (not before 1900, not in the future; one day of slack for time zones). "A child is under 18"
--    is checked by the backend when the family saves a child's date of birth, not in the database, so adults with a dob are unaffected.
--    NOT VALID: rows that already exist are not checked (none are expected to break the rule).
-- 2. guardian_confirmed_at: the signed-in caregiver ticked "I am the parent or legal guardian of this child" (DPDP Act 2023 s.9 +
--    Rules 2025: verifiable consent of a parent or lawful guardian before processing a child's personal data). No child vaccine rows can
--    be written without it (the write policies below check it, and so does the backend).
-- 3. child_vaccines: one row per dose of the child's plan. The plan is SUGGESTED from the date of birth and the IAP-ACVIP 2023 timetable
--    (api-java/src/main/resources/vaccines/iap-acvip-2023.json, to be reviewed by a paediatrician before release). The family can change
--    every date, mark a dose given (with the date) or not given. Nothing changes without the family.
--    Read by every member of the child's family circle; nobody outside it. No analytics, tracking or ads columns, on purpose.

-- ---------------------------------------------------------------- 1. date of birth sanity
alter table public.care_recipients drop constraint if exists care_recipients_dob_sane;
alter table public.care_recipients add constraint care_recipients_dob_sane
  check (dob is null or (dob >= date '1900-01-01' and dob <= current_date + 1)) not valid;

-- ---------------------------------------------------------------- 2. guardian confirmation
alter table public.care_recipients add column if not exists guardian_confirmed_at timestamptz;
alter table public.care_recipients add column if not exists guardian_confirmed_by uuid references public.profiles (id) on delete set null;
comment on column public.care_recipients.guardian_confirmed_at is
  'When a family member confirmed "I am the parent or legal guardian of this child" (needed before a child''s health data is saved)';
comment on column public.care_recipients.guardian_confirmed_by is 'Who confirmed it (the profile, null if that account was deleted)';

-- ---------------------------------------------------------------- 3. the vaccination plan
create table if not exists public.child_vaccines (
  id                   uuid primary key default gen_random_uuid(),
  recipient_id         uuid not null references public.care_recipients (id) on delete cascade,
  schedule_key         text not null check (schedule_key ~ '^[a-z0-9-]{1,40}$'),   -- e.g. 'mmr-1', one per dose in the schedule file
  vaccine_code         text not null check (char_length(vaccine_code) between 1 and 40),  -- e.g. 'mmr'
  dose_label           text not null check (char_length(dose_label) <= 20),             -- e.g. '1', 'B1' (booster 1), '' for a single dose
  schedule_edition     text not null check (char_length(schedule_edition) <= 60),       -- e.g. 'IAP-ACVIP 2023'
  suggested_due_date   date not null,                     -- date of birth + the age in the schedule (what the app suggested)
  due_date             date not null,                     -- the date the family goes by (= suggested until they change it)
  due_date_by_family   boolean not null default false,    -- true once the family changed the date: the plan never moves it again
  given_on             date,                              -- the family marked it given on this date
  not_given            boolean not null default false,    -- the family marked it "not given" (for example the doctor chose another vaccine)
  notes                text check (char_length(notes) <= 300),
  reminded_due_date    date,                              -- the due date the family was last reminded about (a changed date is reminded again)
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now(),
  constraint child_vaccines_one_per_dose unique (recipient_id, schedule_key),
  constraint child_vaccines_given_or_not check (not (not_given and given_on is not null))
);
create index if not exists child_vaccines_upcoming on public.child_vaccines (due_date) where given_on is null and not not_given;

drop trigger if exists child_vaccines_updated_at on public.child_vaccines;
create trigger child_vaccines_updated_at before update on public.child_vaccines
  for each row execute function public.set_updated_at();

comment on table public.child_vaccines is
  'A child''s vaccination plan, suggested from the date of birth (IAP-ACVIP timetable), every date editable by the family. Family circle only.';

-- May this signed-in user write the child's vaccine plan? Member of the child's family AND the guardian confirmation is recorded.
create or replace function public.can_write_child_data(p_recipient uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select public.can_access_recipient(p_recipient)
     and exists (select 1 from public.care_recipients r where r.id = p_recipient and r.guardian_confirmed_at is not null);
$$;
revoke execute on function public.can_write_child_data(uuid) from public, anon;
grant execute on function public.can_write_child_data(uuid) to authenticated, service_role;

alter table public.child_vaccines enable row level security;
drop policy if exists child_vaccines_select on public.child_vaccines;
create policy child_vaccines_select on public.child_vaccines for select to authenticated
  using (public.can_access_recipient(recipient_id));
drop policy if exists child_vaccines_insert on public.child_vaccines;
create policy child_vaccines_insert on public.child_vaccines for insert to authenticated
  with check (public.can_write_child_data(recipient_id));
drop policy if exists child_vaccines_update on public.child_vaccines;
create policy child_vaccines_update on public.child_vaccines for update to authenticated
  using (public.can_write_child_data(recipient_id)) with check (public.can_write_child_data(recipient_id));
drop policy if exists child_vaccines_delete on public.child_vaccines;
create policy child_vaccines_delete on public.child_vaccines for delete to authenticated
  using (public.can_write_child_data(recipient_id));

-- Permissions (written out in full: the Sydney copy once lost its GRANTs). Nothing for anonymous visitors. A signed-in family member may
-- read; write only the plan and family columns (reminder bookkeeping stays with the backend); the backend (service_role) does everything.
revoke all on table public.child_vaccines from public, anon, authenticated, service_role;
grant select, delete on table public.child_vaccines to authenticated;
grant insert (recipient_id, schedule_key, vaccine_code, dose_label, schedule_edition, suggested_due_date, due_date, due_date_by_family,
              given_on, not_given, notes) on public.child_vaccines to authenticated;
grant update (suggested_due_date, due_date, due_date_by_family, given_on, not_given, notes) on public.child_vaccines to authenticated;
grant select, insert, update, delete, references, trigger, truncate on table public.child_vaccines to service_role;


-- ===================== 20261105000046_vaccines_given_earlier.sql =====================
-- Child vaccines: "Which ones were already given?" (docs/use-cases.md, child care). A family that sets up a child who is already a few
-- months old ticks the doses that are on the vaccination card; most were given in hospital or at the clinic and the exact dates are often
-- not at hand. Until the family says so, a dose whose date has passed is NOT called overdue (the app asks "was it given?" instead).
--
-- Sydney: paste into the SQL Editor (no migration history there) BEFORE deploying the backend that reads these columns (the vaccine list
-- and the new POST /recipients/{id}/vaccines/given-earlier). Needs 20261105000045_child.sql first. Safe to run twice. Then also run
-- supabase/maintenance/restore_table_permissions.sql (it now lists these columns) or check with table_permissions_check.sql.
--
-- 1. given_date_unknown: the dose was given but the family did not record the date. given_on then holds the date of the plan (the due date
--    the family went by) only as a PLACEHOLDER, so every "is it given?" check (given_on is not null) keeps working unchanged; the app shows
--    "Given (date not recorded)" and never shows that placeholder as the date it was given. The family can add the real date any time
--    (that sets given_date_unknown back to false).
-- 2. not_yet_given_at: when the family said "not given yet" about a dose whose date has passed (in the "already given?" step, or with
--    "Mark as not done yet"). Only then does the app say "Overdue since <date>".
-- No analytics, tracking or ads columns, on purpose.

alter table public.child_vaccines add column if not exists given_date_unknown boolean not null default false;
alter table public.child_vaccines add column if not exists not_yet_given_at timestamptz;
comment on column public.child_vaccines.given_date_unknown is
  'Given, but the family did not record the date: given_on then holds the plan date as a placeholder (shown as "date not recorded")';
comment on column public.child_vaccines.not_yet_given_at is
  'When the family said "not given yet" about this dose after its date had passed (only then is it called overdue)';

alter table public.child_vaccines drop constraint if exists child_vaccines_unknown_date_needs_given;
alter table public.child_vaccines add constraint child_vaccines_unknown_date_needs_given
  check (not given_date_unknown or given_on is not null);

-- Row Level Security is unchanged (the existing update policy: family member + guardian confirmed). The signed-in family may change the
-- two new columns; the backend (service_role) already has every right on the table.
grant update (given_date_unknown, not_yet_given_at) on public.child_vaccines to authenticated;


-- ===================== 20261105000047_dose_given_in_person.sql =====================
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


-- ===================== 20261105000048_weekly_summary.sql =====================
-- Weekly summary to the family (feature F3, docs/weekly-summary.md).
--
-- Sydney: paste into the SQL Editor (no migration history there) BEFORE deploying the backend: the backend's weekly summary timer
-- writes this table (without it the timer only logs a warning and sends nothing; the reminder tick is not affected). Safe to run twice.
-- Then also run supabase/maintenance/restore_table_permissions.sql (it now lists weekly_summary_sends) or check with
-- table_permissions_check.sql.
--
-- Independent of the other 2026-11-05 migrations; any order works.
--
-- weekly_summary_sends: one row per caregiver per summary week, so the Sunday WhatsApp summary goes out AT MOST ONCE per caregiver per
-- week, also across restarts and overlapping runs (the row is claimed before sending). week_of = the caregiver's own local Sunday.
-- Holds no health data (only who, which week, how it went). Deleted with the caregiver's account (on delete cascade).
-- Backend only: no access at all for anonymous visitors or signed-in users (row security on, no policies).

create table if not exists public.weekly_summary_sends (
  user_id     uuid not null references public.profiles (id) on delete cascade,
  week_of     date not null,                                   -- the caregiver's local Sunday the summary belongs to
  status      text not null default 'sending'
              check (status in ('sending', 'sent', 'failed', 'skipped')),
  attempts    smallint not null default 0 check (attempts >= 0), -- failed WhatsApp sends so far (at most 3, then it stays 'failed')
  people      smallint check (people >= 0),                    -- how many people the message covered
  claimed_at  timestamptz,
  sent_at     timestamptz,
  created_at  timestamptz not null default now(),
  primary key (user_id, week_of)
);

comment on table public.weekly_summary_sends is
  'Which weekly WhatsApp summary went to which caregiver (at most one per caregiver per week). Backend only, no health data.';

alter table public.weekly_summary_sends enable row level security;

revoke all on table public.weekly_summary_sends from public, anon, authenticated, service_role;
grant select, insert, update, delete, references, trigger, truncate on table public.weekly_summary_sends to service_role;


-- ===================== 20261105000049_feed_hearts.sql =====================
-- Family activity feed (feature 4): a single ♥ per family member per feed item, shown as a count to the family.
-- Not the love loop (migration 0033): those reactions go to the parent on WhatsApp, only for a confirmed dose or check-in, with a
-- daily cap and an opt-in. A feed heart never leaves the app: it is a quiet "thank you / seen" between family members, for any item
-- (a dose Sunita gave, a vaccine marked given, an appointment added ...). No text, no comments.
-- Idempotent. Deploy: paste into the SQL editor before (or after) the backend; the feed works without it (no hearts shown).

create table if not exists public.feed_hearts (
  recipient_id  uuid not null references public.care_recipients (id) on delete cascade,
  -- what was hearted: '<kind>:<uuid>' or '<kind>:<uuid>:<epoch seconds>' (consent / stop moments), built by the backend
  item_key      text not null check (item_key ~ '^[a-z_]{1,20}:[0-9a-f-]{36}(:[0-9]{1,12})?$'),
  user_id       uuid not null references public.profiles (id) on delete cascade,
  created_at    timestamptz not null default now(),
  primary key (recipient_id, item_key, user_id)
);
create index if not exists feed_hearts_user on public.feed_hearts (user_id);

comment on table public.feed_hearts is
  'Family activity feed: one heart per family member per item, visible to the family circle only. Never sent on WhatsApp.';

alter table public.feed_hearts enable row level security;
drop policy if exists feed_hearts_select on public.feed_hearts;
create policy feed_hearts_select on public.feed_hearts for select to authenticated
  using (public.can_access_recipient(recipient_id));
drop policy if exists feed_hearts_insert on public.feed_hearts;
create policy feed_hearts_insert on public.feed_hearts for insert to authenticated
  with check (user_id = (select auth.uid()) and public.can_access_recipient(recipient_id));
drop policy if exists feed_hearts_delete on public.feed_hearts;
create policy feed_hearts_delete on public.feed_hearts for delete to authenticated
  using (user_id = (select auth.uid()));

-- Permissions written out in full (the Sydney copy once lost its GRANTs). Nothing for anonymous visitors; no updates.
revoke all on table public.feed_hearts from public, anon, authenticated, service_role;
grant select, insert, delete on table public.feed_hearts to authenticated;
grant select, insert, update, delete, references, trigger, truncate on table public.feed_hearts to service_role;


-- ===================== 20261105000050_who_and_how.sql =====================
-- "Who and how" (round 2, R1): more relations, a per-person daily check-in switch, and the language of the person who gets the
-- reminders when that is not the person themself (a helper or a family member). See docs/reminder-receiver.md ("Round 2").
--
-- Sydney: paste into the SQL Editor (no migration history there) BEFORE deploying the backend: the backend maps the two new
-- columns and validates its tables at start-up, so it would refuse to start without them. Safe to run twice.
--
-- 1. Relations: son, daughter, grandson, granddaughter, wife, husband (a child or a pregnancy is not a "father").
--    'spouse' stays valid for existing rows; the app now offers wife / husband instead.
-- 2. checkin_enabled: the daily "How are you?" check-in for this person. Default true (every existing profile keeps it).
--    A NEW pregnancy is created with it off (the backend does that). Existing pregnancy rows are left as they are (on): the
--    families who already get it are not surprised by a message that silently stops; they can switch it off in the form.
--    The scheduler reads it defensively (to_jsonb(r)->>'checkin_enabled'), so the scheduler works before and after this file.
-- 3. reminder_language: 'en' | 'hi' | null. The language of the WhatsApp messages for the receiver when reminder_to is
--    caregiver or helper (a maid who reads Hindi while Papa's profile says English). null = use the person's language.
--    Message code picks it with coalesce(to_jsonb(r)->>'reminder_language', r.language) (only when reminder_to <> 'person').

-- ---------------------------------------------------------------- 1. relations
alter table public.care_recipients drop constraint if exists care_recipients_relation_check;
alter table public.care_recipients
  add constraint care_recipients_relation_check
  check (relation in ('father', 'mother', 'grandfather', 'grandmother', 'father_in_law', 'mother_in_law',
                      'spouse', 'uncle', 'aunt', 'brother', 'sister', 'other', 'self',
                      'son', 'daughter', 'grandson', 'granddaughter', 'wife', 'husband'));

-- ---------------------------------------------------------------- 2. daily check-in switch
alter table public.care_recipients add column if not exists checkin_enabled boolean not null default true;
comment on column public.care_recipients.checkin_enabled is
  'Daily "How are you?" check-in on WhatsApp for this person (only sent when the person gets the reminders themself). New pregnancies start off.';

-- ---------------------------------------------------------------- 3. the receiver's own language
alter table public.care_recipients add column if not exists reminder_language text;
alter table public.care_recipients drop constraint if exists care_recipients_reminder_language_known;
alter table public.care_recipients add constraint care_recipients_reminder_language_known check (
  reminder_language is null or reminder_language in ('en', 'hi')
);
comment on column public.care_recipients.reminder_language is
  'Language of the WhatsApp messages for a helper / family member who gets the reminders (en | hi); null = the person''s language';


-- ===================== 20261105000051_vaccine_whatsapp.sql =====================
-- WhatsApp vaccine reminders to the adult who receives a child's reminders (R2; api-java .../child/VaccineReminderService.java).
--
-- The in-app reminder keeps its own bookkeeping (child_vaccines.reminded_due_date, migration 0045). The WhatsApp message goes out twice
-- (about 3 days before the due date and on the day itself, both in the child's daytime), so it needs two more markers in the same
-- style: "the due date this message was already sent for". A changed date is reminded again; overlapping runs never send twice
-- (claimed with a guarded UPDATE before sending, handed back when the send fails).
--
-- Idempotent. Deploy: paste into the SQL editor before (or after) the backend: without these columns the backend simply sends no
-- vaccine WhatsApp (the in-app reminder is unaffected). Backend-only columns: no new grants for signed-in users (the column-level
-- INSERT/UPDATE grants of 0045 do not include them; service_role already has every privilege on the table).

alter table public.child_vaccines add column if not exists whatsapp_before_for date;
alter table public.child_vaccines add column if not exists whatsapp_day_for date;

comment on column public.child_vaccines.whatsapp_before_for is
  'The due date the "due in a few days" WhatsApp to the reminder receiver was sent for (backend bookkeeping)';
comment on column public.child_vaccines.whatsapp_day_for is
  'The due date the "due today" WhatsApp to the reminder receiver was sent for (backend bookkeeping)';


-- ===================== 20261105000052_key_facts.sql =====================
-- Round 2 (R3, "facts that must be right"): a child's weight, and the key facts on the public emergency card.
--
-- Sydney: paste into the SQL Editor BEFORE deploying the backend (the backend reads and writes weight_kg / weight_on for a
-- child, and the web app reads the two new keys of the card). Safe to run twice. Needs 0038 (card), 0043 (care_type, due_date)
-- and 0045 (child) first. Table grants are table-wide, so nothing to re-grant (table_permissions_check.sql stays the same).
--
-- 1. care_recipients.weight_kg + weight_on: the last weight the family recorded for a child, and the day it was measured.
--    A plain recorded fact (no chart, no judgement, no advice). Between 0.5 and 200 kg; a date only together with a weight.
--    Saved by the Java backend (PUT /api/v1/recipients/{id}/child/weight, guardian confirmation required like all child data).
-- 2. public_emergency_card: adds ONLY
--      pregnancy: {week, due_date}           for a pregnancy (week in the person's time zone; null when out of range)
--      child:     {born, weight_kg, weight_on} for a child (and then 'age' is null: "Born 8 Jun 2026 (5 months)" replaces it)
--    Both are null for everyone else. Nothing else changes: still the minimum a paramedic needs, still NULL for an unknown,
--    revoked or malformed link, still the same anon grant.

alter table public.care_recipients add column if not exists weight_kg numeric(5, 2);
alter table public.care_recipients add column if not exists weight_on date;

alter table public.care_recipients drop constraint if exists care_recipients_weight_sane;
alter table public.care_recipients add constraint care_recipients_weight_sane check (
  (weight_kg is null or weight_kg between 0.5 and 200)
  and (weight_on is null or (weight_kg is not null and weight_on >= date '1900-01-01' and weight_on <= current_date + 1))
);

comment on column public.care_recipients.weight_kg is 'Last weight the family recorded (kg, 0.5-200), shown on a child''s emergency card and doctor summary. A fact, never interpreted';
comment on column public.care_recipients.weight_on is 'The day weight_kg was measured (null = not said)';

create or replace function public.public_emergency_card(p_token text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_token    text := lower(btrim(coalesce(p_token, '')));
  v_link     public.emergency_links%rowtype;
  v_rec      public.care_recipients%rowtype;
  v_today    date := (now() at time zone 'utc')::date;
  v_phone_re constant text := '^\+[1-9][0-9]{7,14}$';
  v_meds     jsonb;
  v_contacts jsonb := '[]'::jsonb;
  v_doctor   text;
  v_age      jsonb;
  v_item     jsonb;
  v_phone    text;
  v_label    text;
  v_local    date;
  v_days     integer;
  v_preg     jsonb;
  v_child    jsonb;
  m          record;
begin
  if v_token !~ '^[0-9a-f]{64}$' then
    return null;
  end if;
  select * into v_link from public.emergency_links where token = v_token and active limit 1;
  if not found then
    return null;
  end if;
  select * into v_rec from public.care_recipients where id = v_link.recipient_id;
  if not found then
    return null;
  end if;

  -- Active medicines, oldest first (at most 40), with the times of the schedules that have not ended.
  select coalesce(jsonb_agg(jsonb_build_object(
           'name',  public.card_clean(x.name),
           'dose',  public.card_clean(x.dosage),
           'times', (select coalesce(jsonb_agg(t.hhmm order by t.hhmm), '[]'::jsonb)
                       from (select distinct to_char(s.time_of_day, 'HH24:MI') as hhmm
                               from public.medicine_schedules s
                              where s.medicine_id = x.id and (s.end_date is null or s.end_date >= v_today)) t)
         ) order by x.created_at), '[]'::jsonb)
    into v_meds
    from (select id, name, dosage, created_at from public.medicines
           where recipient_id = v_rec.id and active order by created_at limit 40) x;

  -- Emergency contacts the caregiver listed (valid international numbers only), then the family's own phones, at most 4.
  for v_item in select e from jsonb_array_elements(
      case when jsonb_typeof(v_rec.emergency_contacts) = 'array' then v_rec.emergency_contacts else '[]'::jsonb end) e loop
    if jsonb_typeof(v_item) = 'object' then
      v_phone := public.card_clean(case when jsonb_typeof(v_item -> 'phone') = 'string' then v_item ->> 'phone' end, 20);
      if v_phone ~ v_phone_re then
        v_contacts := v_contacts || jsonb_build_array(jsonb_build_object(
          'name', public.card_clean(case when jsonb_typeof(v_item -> 'name') = 'string' then v_item ->> 'name' end),
          'phone', v_phone));
      end if;
    end if;
  end loop;
  for m in select p.name as pname, p.phone as pphone
             from public.care_circle_members cm join public.profiles p on p.id = cm.user_id
            where cm.circle_id = v_rec.circle_id order by cm.created_at loop
    v_phone := public.card_clean(m.pphone, 20);
    if v_phone ~ v_phone_re and not exists (select 1 from jsonb_array_elements(v_contacts) c where c ->> 'phone' = v_phone) then
      v_contacts := v_contacts || jsonb_build_array(jsonb_build_object('name', public.card_clean(m.pname), 'phone', v_phone));
    end if;
  end loop;
  select coalesce(jsonb_agg(c order by o), '[]'::jsonb) into v_contacts
    from jsonb_array_elements(v_contacts) with ordinality as t(c, o) where o <= 4;

  -- The first doctor with a name.
  for v_item in select e from jsonb_array_elements(
      case when jsonb_typeof(v_rec.doctor_contacts) = 'array' then v_rec.doctor_contacts else '[]'::jsonb end) e loop
    v_label := case jsonb_typeof(v_item)
      when 'object' then public.card_clean(case when jsonb_typeof(v_item -> 'name') = 'string' then v_item ->> 'name' end)
      when 'string' then public.card_clean(v_item #>> '{}')
      else '' end;
    if v_label <> '' then
      v_doctor := v_label;
      exit;
    end if;
  end loop;

  v_age := case
    when v_rec.age is not null then to_jsonb(v_rec.age)
    when v_rec.dob is not null then to_jsonb(extract(year from age(v_today, v_rec.dob))::integer)
    else 'null'::jsonb end;

  -- Today on the person's own clock (the pregnancy week turns over at their midnight); an unknown zone falls back to India.
  begin
    v_local := (now() at time zone coalesce(nullif(v_rec.timezone, ''), 'Asia/Kolkata'))::date;
  exception when others then
    v_local := (now() at time zone 'Asia/Kolkata')::date;
  end;

  -- A pregnancy: the week (completed weeks, counted back from the due date like web/src/lib/pregnancy.ts and
  -- api-java recipient/Pregnancy.java; null before week 1 or after week 42) and the due date. Nothing else about it.
  if v_rec.care_type = 'pregnant' and not v_rec.is_self then
    v_days := case when v_rec.due_date is null then null else 280 - (v_rec.due_date - v_local) end;
    v_preg := jsonb_build_object(
      'week',     case when v_days is null or v_days < 7 or v_days >= 301 then null else v_days / 7 end,
      'due_date', v_rec.due_date);
  end if;

  -- A child: the date of birth (the card shows the age in months for a baby) and the last recorded weight with its date
  -- (useful for any dose a doctor works out by weight). The age in years is left out: "Born ..." says it better.
  if v_rec.care_type = 'child' and not v_rec.is_self then
    v_child := jsonb_build_object(
      'born',      v_rec.dob,
      'weight_kg', v_rec.weight_kg,
      'weight_on', case when v_rec.weight_kg is null then null else v_rec.weight_on end);
    v_age := 'null'::jsonb;
  end if;

  update public.emergency_links set view_count = view_count + 1, last_viewed_at = now() where id = v_link.id;

  return jsonb_build_object(
    'name',        coalesce((regexp_split_to_array(public.card_clean(v_rec.name), '\s+'))[1], ''),
    'age',         v_age,
    'blood_group', nullif(public.card_clean(v_rec.blood_group, 5), ''),
    'conditions',  coalesce((select jsonb_agg(public.card_clean(c) order by o)
                               from unnest(v_rec.conditions) with ordinality as u(c, o) where o <= 30), '[]'::jsonb),
    'allergies',   coalesce((select jsonb_agg(public.card_clean(a) order by o)
                               from unnest(v_rec.allergies) with ordinality as u(a, o) where o <= 30), '[]'::jsonb),
    'medicines',   v_meds,
    'contacts',    v_contacts,
    'doctor',      v_doctor,
    'pregnancy',   v_preg,
    'child',       v_child
  );
end $$;

revoke all on function public.public_emergency_card(text) from public, anon, authenticated;
grant execute on function public.public_emergency_card(text) to anon, authenticated;
comment on function public.public_emergency_card(text) is
  'Public (no login) emergency card by token: the minimum a paramedic needs. NULL for unknown, revoked, malformed or deleted links.';


-- ===================== 20261105000053_simple_list_layout.sql =====================
-- The "Simple list" Today style (R7, 2026-11-05) can be saved to the caregiver's profile like the 10 older styles,
-- so the choice follows them to another phone. Without this the app keeps the choice on that phone only.
-- Sydney: paste into the SQL Editor (no migration history there). Safe to run twice. Order vs other migrations: any.

alter table public.profiles drop constraint if exists profiles_today_layout_check;
alter table public.profiles add constraint profiles_today_layout_check check (
  today_layout is null or today_layout in ('route','curvy','timeline','arc','focus','ring','pillbox','ladder','clock','chat','simple')
);


-- ===================== restore_table_permissions.sql =====================
-- Restore the TABLE permissions the migrations intend (what each role may do with each table), without touching any data.
--
-- WHY: on 2026-11-03 the Sydney database showed full permissions for everyone on every table (the GRANT / REVOKE statements were lost when it was
-- copied). Row security (RLS) was still on and held in tests, but the migrations also limit what a signed-in user may WRITE, for example:
-- emergency_links (no direct writes, so nobody can choose a guessable link), care_circle_members (no updates or deletes, so a member cannot make
-- themselves owner), notifications (only read_at), appointments (not the reminder columns), reports.
--
-- WHAT IT DOES: for each table below it removes all permissions of PUBLIC, anon, authenticated and service_role, then gives back exactly what the
-- correct database gives (generated from the local database built from the migrations), including column-level grants. A table or column that does not
-- exist in the target database is skipped with a notice. Only permissions change, no rows. Safe to run again.
-- (The newest PostgreSQL versions also have a MAINTAIN privilege; it is left out on purpose so the script runs on older versions too.)
--
-- NOT COVERED: tables that are not in the migrations (user_credentials and flyway_schema_history from the Java backend): lock those separately
-- (see docs/TODO.md). Regenerate this file after a migration adds or changes a table or its permissions.

do $$
declare
  s text;
begin
  foreach s in array array[
    'revoke all on table public.appointments from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.appointments to anon',
    'grant DELETE, REFERENCES, SELECT, TRIGGER, TRUNCATE on table public.appointments to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.appointments to service_role',
    'grant INSERT (datetime, doctor_name, location, notes, recipient_id, remind) on public.appointments to authenticated',
    'grant UPDATE (datetime, doctor_name, location, notes, remind) on public.appointments to authenticated',
    'revoke all on table public.care_circle_members from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_circle_members to anon',
    'grant INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE on table public.care_circle_members to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_circle_members to service_role',
    'revoke all on table public.care_circles from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_circles to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_circles to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_circles to service_role',
    'revoke all on table public.care_recipients from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_recipients to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_recipients to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.care_recipients to service_role',
    'revoke all on table public.checkins from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.checkins to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.checkins to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.checkins to service_role',
    'revoke all on table public.child_vaccines from public, anon, authenticated, service_role',
    'grant DELETE, SELECT on table public.child_vaccines to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.child_vaccines to service_role',
    'grant INSERT (dose_label, due_date, due_date_by_family, given_on, not_given, notes, recipient_id, schedule_edition, schedule_key, suggested_due_date, vaccine_code) on public.child_vaccines to authenticated',
    'grant UPDATE (due_date, due_date_by_family, given_on, not_given, notes, suggested_due_date) on public.child_vaccines to authenticated',
    'grant UPDATE (given_date_unknown, not_yet_given_at) on public.child_vaccines to authenticated',   -- migration 0046
    'revoke all on table public.consent_requests from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.consent_requests to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.consent_requests to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.consent_requests to service_role',
    'revoke all on table public.document_files from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.document_files to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.document_files to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.document_files to service_role',
    'revoke all on table public.documents from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.documents to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.documents to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.documents to service_role',
    'revoke all on table public.dose_events from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.dose_events to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.dose_events to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.dose_events to service_role',
    'revoke all on table public.emergency_links from public, anon, authenticated, service_role',
    'grant REFERENCES, SELECT, TRIGGER, TRUNCATE on table public.emergency_links to anon',
    'grant REFERENCES, SELECT, TRIGGER, TRUNCATE on table public.emergency_links to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.emergency_links to service_role',
    'revoke all on table public.escalation_policies from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.escalation_policies to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.escalation_policies to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.escalation_policies to service_role',
    'revoke all on table public.escalations from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.escalations to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.escalations to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.escalations to service_role',
    'revoke all on table public.family_invites from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.family_invites to service_role',
    'grant SELECT (accepted_at, accepted_by, circle_id, created_at, created_by, expires_at, id, revoked_at) on public.family_invites to authenticated',
    'revoke all on table public.feedback from public, anon, authenticated, service_role',
    'grant INSERT, REFERENCES, SELECT, TRIGGER on table public.feedback to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.feedback to service_role',
    'revoke all on table public.love_settings from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.love_settings to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.love_settings to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.love_settings to service_role',
    'revoke all on table public.medicine_schedules from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.medicine_schedules to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.medicine_schedules to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.medicine_schedules to service_role',
    'revoke all on table public.medicines from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.medicines to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.medicines to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.medicines to service_role',
    'revoke all on table public.notifications from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.notifications to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE on table public.notifications to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.notifications to service_role',
    'grant UPDATE (read_at) on public.notifications to authenticated',
    'revoke all on table public.parent_messages from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.parent_messages to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.parent_messages to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.parent_messages to service_role',
    'revoke all on table public.phone_opt_outs from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.phone_opt_outs to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.phone_opt_outs to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.phone_opt_outs to service_role',
    'revoke all on table public.plans from public, anon, authenticated, service_role',
    'grant SELECT on table public.plans to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.plans to service_role',
    'revoke all on table public.profiles from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.profiles to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.profiles to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.profiles to service_role',
    'revoke all on table public.reactions from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.reactions to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.reactions to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.reactions to service_role',
    'revoke all on table public.report_values from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.report_values to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.report_values to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.report_values to service_role',
    'revoke all on table public.reports from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.reports to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE on table public.reports to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.reports to service_role',
    'grant UPDATE (report_date, type) on public.reports to authenticated',
    'revoke all on table public.weekly_summary_sends from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.weekly_summary_sends to service_role',
    'revoke all on table public.whatsapp_events from public, anon, authenticated, service_role',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.whatsapp_events to anon',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.whatsapp_events to authenticated',
    'grant DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on table public.whatsapp_events to service_role'
  ] loop
    begin
      execute s;
    exception when undefined_table or undefined_column then
      raise notice 'skipped (not in this database): %', s;
    end;
  end loop;
end $$;


-- ===================== restore_function_permissions.sql =====================
-- Restore the function permissions the migrations intend.
--
-- WHY: on 2026-11-03 the Sydney database was found to let an anonymous visitor run 48 functions in the public schema; the migrations
-- allow 4 (has_control_chars, public_emergency_card, storage_recipient_id, text_array_ok). Five server-only functions
-- (erase_caregiver, claim_consent_request, confirm_dose_slot, consume_stock, generate_dose_events) are SECURITY DEFINER and do not check
-- who calls them, so they were open to anyone holding the public key. The GRANT / REVOKE statements were lost when the database was copied.
--
-- WHAT IT DOES: for every function below it removes all execute permission (from PUBLIC, anon, authenticated, service_role) and gives back
-- exactly the roles the correct database gives (generated from the local database built from the migrations). A function that does not exist
-- in the target database is skipped with a notice. Nothing is created, changed or deleted: only who may RUN each function.
-- It can be run again at any time. Trigger functions are not touched (they cannot be called directly).
--
-- AFTER NEW MIGRATIONS: Supabase gives every new function to anon, authenticated and service_role by default, so a migration that adds a
-- server-only function MUST revoke it (the migrations do). If this script is run again later it fixes any that were missed, but only for
-- the functions listed here: regenerate it after adding functions.

do $$
declare
  r record;
begin
  for r in select * from (values
    ('accept_family_invite(text)', array['authenticated','service_role']::text[]),
    ('adjust_stock(uuid,integer)', array['authenticated','service_role']::text[]),
    ('admin_feedback(integer)', array['authenticated','service_role']::text[]),
    ('admin_stats(integer)', array['authenticated','service_role']::text[]),
    ('can_access_document(uuid)', array['authenticated','service_role']::text[]),
    ('can_access_medicine(uuid)', array['authenticated','service_role']::text[]),
    ('can_access_recipient(uuid)', array['authenticated','service_role']::text[]),
    ('can_access_report(uuid)', array['authenticated','service_role']::text[]),
    ('can_access_schedule(uuid)', array['authenticated','service_role']::text[]),
    ('can_write_child_data(uuid)', array['authenticated','service_role']::text[]),
    ('card_clean(text,integer)', array['service_role']::text[]),
    ('check_medicine_input(uuid,text,text,text,time without time zone[])', array['authenticated','service_role']::text[]),
    ('claim_consent_request(text,uuid,interval,integer)', array['service_role']::text[]),
    ('confirm_dose_slot(uuid)', array['service_role']::text[]),
    ('consume_stock(uuid,integer)', array['service_role']::text[]),
    ('create_care_recipient(jsonb)', array['authenticated','service_role']::text[]),
    ('create_family_invite(uuid)', array['authenticated','service_role']::text[]),
    ('create_medicine(uuid,text,text,text,text,integer,integer,time without time zone[],date,date)', array['authenticated','service_role']::text[]),
    ('enable_emergency_link(uuid)', array['authenticated','service_role']::text[]),
    ('erase_caregiver(uuid)', array['service_role']::text[]),
    ('feedback_files_today()', array['authenticated','service_role']::text[]),
    ('generate_dose_events(timestamp with time zone,interval,interval,uuid[])', array['service_role']::text[]),
    ('handle_escalation(uuid)', array['authenticated','service_role']::text[]),
    ('has_control_chars(text)', array['anon','authenticated','service_role']::text[]),
    ('invite_hash(text)', array['service_role']::text[]),
    ('is_admin_user()', array['authenticated','service_role']::text[]),
    ('is_circle_member(uuid)', array['authenticated','service_role']::text[]),
    ('is_circle_owner(uuid)', array['authenticated','service_role']::text[]),
    ('leave_circle(uuid)', array['authenticated','service_role']::text[]),
    ('love_stats(integer)', array['authenticated','service_role']::text[]),
    ('paused_medicines()', array['service_role']::text[]),
    ('paused_recipients()', array['service_role']::text[]),
    ('plan_for_circle(uuid)', array['authenticated','service_role']::text[]),
    ('plan_of_user(uuid)', array['authenticated','service_role']::text[]),
    ('plan_overview()', array['authenticated','service_role']::text[]),
    ('plan_pauses()', array['authenticated','service_role']::text[]),
    ('preview_family_invite(text)', array['authenticated','service_role']::text[]),
    ('public_emergency_card(text)', array['anon','authenticated','service_role']::text[]),
    ('record_emergency_view(uuid)', array['service_role']::text[]),
    ('remove_circle_member(uuid,uuid)', array['authenticated','service_role']::text[]),
    ('revoke_emergency_link(uuid)', array['authenticated','service_role']::text[]),
    ('revoke_family_invite(uuid)', array['authenticated','service_role']::text[]),
    ('rotate_emergency_link(uuid)', array['authenticated','service_role']::text[]),
    ('send_reaction(uuid,text,uuid,text,text,text)', array['authenticated','service_role']::text[]),
    ('set_love_enabled(uuid,boolean)', array['authenticated','service_role']::text[]),
    ('set_medicine_critical(uuid,boolean)', array['authenticated','service_role']::text[]),
    ('set_parent_calls_me(uuid,text)', array['authenticated','service_role']::text[]),
    ('sole_member_recipients(uuid)', array['service_role']::text[]),
    ('storage_recipient_id(text)', array['anon','authenticated','service_role']::text[]),
    ('text_array_ok(text[],integer,integer)', array['anon','authenticated','service_role']::text[]),
    ('update_medicine(uuid,text,text,text,text,integer,time without time zone[])', array['authenticated','service_role']::text[]),
    ('wallet_usage(uuid)', array['authenticated','service_role']::text[])
  ) as t(sig, roles)
  loop
    begin
      execute format('revoke all on function %s from public, anon, authenticated, service_role', r.sig);
      if cardinality(r.roles) > 0 then
        execute format('grant execute on function %s to %s', r.sig, array_to_string(r.roles, ', '));
      end if;
    exception when undefined_function then
      raise notice 'skipped (not in this database): %', r.sig;
    end;
  end loop;
end $$;

commit;
select 'Chhaon bundle 2026-11-05 applied' as done;
