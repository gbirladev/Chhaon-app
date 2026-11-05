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
