-- QA round fixes (backend + database). Re-runnable where practical; never edits older migrations.

-- ---------------------------------------------------------------- helpers
-- True when the text holds a control character (newline, tab, ...) or a Unicode line separator.
create or replace function public.has_control_chars(p text) returns boolean
language sql immutable set search_path = '' as $$
  select coalesce(p ~ '[\x01-\x1f\x7f  ]', false);
$$;

-- Each element at most p_len characters and at most p_max elements.
create or replace function public.text_array_ok(a text[], p_max integer, p_len integer) returns boolean
language sql immutable set search_path = '' as $$
  select coalesce(cardinality(a), 0) <= p_max
     and not exists (select 1 from unnest(a) e where char_length(e) > p_len);
$$;

-- ---------------------------------------------------------------- 1. adjust_stock: NULL and overflow
create or replace function public.adjust_stock(p_medicine uuid, p_delta integer) returns integer
language plpgsql security invoker set search_path = '' as $$
declare
  v_new bigint;
  v_result integer;
begin
  if p_delta is null then
    raise exception 'delta is required' using errcode = '22004';
  end if;
  select stock_count::bigint + p_delta into v_new from public.medicines where id = p_medicine and stock_count is not null for update;
  if not found then
    return null;
  end if;
  if v_new > 2147483647 then
    raise exception 'stock count out of range' using errcode = '22003';
  end if;
  update public.medicines set stock_count = greatest(v_new, 0)::integer
   where id = p_medicine returning stock_count into v_result;
  return v_result;
end $$;
revoke execute on function public.adjust_stock(uuid, integer) from public, anon;
grant execute on function public.adjust_stock(uuid, integer) to authenticated;

-- ---------------------------------------------------------------- 2. medicines: clean names, limits
alter table public.medicines
  add constraint medicines_name_clean check (name = btrim(name) and char_length(name) between 1 and 100 and not public.has_control_chars(name)) not valid,
  add constraint medicines_dosage_clean check (dosage is null or not public.has_control_chars(dosage)) not valid;

-- At most 30 medicines per parent and 12 reminder times per medicine, whatever way the rows arrive.
create or replace function public.limit_medicines() returns trigger
language plpgsql set search_path = '' as $$
begin
  perform pg_advisory_xact_lock(hashtext('medicines:' || new.recipient_id::text));
  if (select count(*) from public.medicines where recipient_id = new.recipient_id) >= 30 then
    raise exception 'too many medicines' using errcode = 'P0001', hint = 'too_many_medicines';
  end if;
  return new;
end $$;
drop trigger if exists medicines_limit on public.medicines;
create trigger medicines_limit before insert on public.medicines for each row execute function public.limit_medicines();

create or replace function public.limit_schedules() returns trigger
language plpgsql set search_path = '' as $$
begin
  perform pg_advisory_xact_lock(hashtext('schedules:' || new.medicine_id::text));
  if (select count(*) from public.medicine_schedules where medicine_id = new.medicine_id) >= 12 then
    raise exception 'too many reminder times' using errcode = 'P0001', hint = 'too_many_times';
  end if;
  return new;
end $$;
drop trigger if exists medicine_schedules_limit on public.medicine_schedules;
create trigger medicine_schedules_limit before insert on public.medicine_schedules for each row execute function public.limit_schedules();

create or replace function public.check_medicine_input(p_recipient uuid, p_name text, p_dosage text, p_photo text, p_times time[])
returns void language plpgsql immutable set search_path = '' as $$
begin
  if p_name is null or char_length(btrim(p_name)) not between 1 and 100 or public.has_control_chars(p_name) then
    raise exception 'invalid medicine name' using errcode = '22023', hint = 'bad_name';
  end if;
  if p_dosage is not null and (char_length(p_dosage) > 60 or public.has_control_chars(p_dosage)) then
    raise exception 'invalid dosage' using errcode = '22023', hint = 'bad_dosage';
  end if;
  if coalesce(cardinality(p_times), 0) = 0 then
    raise exception 'at least one reminder time is required' using errcode = '22023';
  end if;
  if (select count(distinct t) from unnest(p_times) t) > 12 then
    raise exception 'too many reminder times' using errcode = 'P0001', hint = 'too_many_times';
  end if;
  if p_photo is not null and (left(p_photo, 37) <> p_recipient::text || '/' or p_photo like '%..%' or p_photo like '%//%' or char_length(p_photo) > 300) then
    raise exception 'invalid photo path' using errcode = '22023', hint = 'bad_photo';
  end if;
end $$;

create or replace function public.create_medicine(
  p_recipient uuid, p_name text, p_dosage text, p_instructions text, p_photo_url text,
  p_stock integer, p_refill_threshold integer, p_times time[],
  p_start date default current_date, p_end date default null
) returns uuid
language plpgsql security invoker set search_path = '' as $$
declare
  v_medicine uuid;
begin
  perform public.check_medicine_input(p_recipient, p_name, p_dosage, p_photo_url, p_times);

  insert into public.medicines (recipient_id, name, dosage, instructions, photo_url, stock_count, refill_threshold)
  values (p_recipient, btrim(p_name), p_dosage, p_instructions, p_photo_url, p_stock, p_refill_threshold)
  returning id into v_medicine;   -- the trigger enforces "at most 30 medicines per parent"

  insert into public.medicine_schedules (medicine_id, time_of_day, start_date, end_date)
  select v_medicine, t, p_start, p_end from (select distinct unnest(p_times) as t) x;

  return v_medicine;
end $$;

create or replace function public.update_medicine(
  p_medicine uuid, p_name text, p_dosage text, p_instructions text, p_photo_url text,
  p_refill_threshold integer, p_times time[]
) returns void
language plpgsql security invoker set search_path = '' as $$
declare
  v_recipient uuid;
begin
  select recipient_id into v_recipient from public.medicines where id = p_medicine;
  if v_recipient is null then
    raise exception 'medicine not found' using errcode = '42501';
  end if;
  perform public.check_medicine_input(v_recipient, p_name, p_dosage, p_photo_url, p_times);

  update public.medicines
     set name = btrim(p_name), dosage = p_dosage, instructions = p_instructions,
         photo_url = coalesce(p_photo_url, photo_url), refill_threshold = p_refill_threshold
   where id = p_medicine;
  if not found then
    raise exception 'medicine not found' using errcode = '42501';
  end if;

  delete from public.medicine_schedules where medicine_id = p_medicine and not (time_of_day = any (p_times));

  insert into public.medicine_schedules (medicine_id, time_of_day, start_date)
  select p_medicine, t, current_date
    from (select distinct unnest(p_times) as t) x
   where not exists (select 1 from public.medicine_schedules s where s.medicine_id = p_medicine and s.time_of_day = x.t);
end $$;

revoke execute on function public.check_medicine_input(uuid, text, text, text, time[]) from public, anon;
grant execute on function public.check_medicine_input(uuid, text, text, text, time[]) to authenticated;

-- Parent names: control characters become spaces (instead of an error); arrays are bounded.
create or replace function public.clean_recipient_text() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.name := btrim(regexp_replace(regexp_replace(new.name, '[\x01-\x1f\x7f  ]+', ' ', 'g'), ' {2,}', ' ', 'g'));
  if new.call_name is not null then
    new.call_name := nullif(btrim(regexp_replace(regexp_replace(new.call_name, '[\x01-\x1f\x7f  ]+', ' ', 'g'), ' {2,}', ' ', 'g')), '');
  end if;
  return new;
end $$;
drop trigger if exists care_recipients_clean_text on public.care_recipients;
create trigger care_recipients_clean_text before insert or update of name, call_name on public.care_recipients
  for each row execute function public.clean_recipient_text();

alter table public.care_recipients
  add constraint recipients_name_clean check (not public.has_control_chars(name) and (call_name is null or not public.has_control_chars(call_name))) not valid,
  add constraint recipients_conditions_ok check (public.text_array_ok(conditions, 30, 80)) not valid,
  add constraint recipients_allergies_ok check (public.text_array_ok(allergies, 30, 80)) not valid;

-- ---------------------------------------------------------------- 5. time zone change clears every pending dose
-- (the old version only removed events still in the future by the database clock, so events created for a later
--  moment by a test/recovery clock, or already inside their sending window, survived and fired in the OLD zone.)
create or replace function public.clear_pending_doses_on_timezone_change() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if new.timezone is distinct from old.timezone then
    delete from public.dose_events de
    using public.medicine_schedules s, public.medicines m
    where de.schedule_id = s.id and s.medicine_id = m.id and m.recipient_id = new.id
      and de.status = 'pending';
  end if;
  return new;
end $$;

-- ---------------------------------------------------------------- 6. appointments: bookkeeping columns are backend-only
revoke insert on public.appointments from authenticated;
grant insert (recipient_id, doctor_name, datetime, notes, location, remind) on public.appointments to authenticated;

-- Backend-only atomic counter for the public emergency card.
create or replace function public.record_emergency_view(p_link uuid) returns void
language sql security definer set search_path = '' as $$
  update public.emergency_links set view_count = view_count + 1, last_viewed_at = now() where id = p_link;
$$;
revoke execute on function public.record_emergency_view(uuid) from public, anon, authenticated;
grant execute on function public.record_emergency_view(uuid) to service_role;

-- ---------------------------------------------------------------- 7. reports and profiles
revoke update on public.reports from authenticated;
grant update (report_date, type) on public.reports to authenticated;

alter table public.profiles
  add constraint profiles_phone_shape check (phone is null or phone ~ '^\+?[0-9][0-9 ()-]{5,19}$') not valid,
  add constraint profiles_email_shape check (email is null or (char_length(email) <= 254 and email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$')) not valid,
  add constraint profiles_policy_version_len check (policy_version is null or char_length(policy_version) <= 40) not valid;

create or replace function public.validate_profile_timezone() returns trigger
language plpgsql set search_path = '' as $$
begin
  if not exists (select 1 from pg_catalog.pg_timezone_names n where n.name = new.timezone) then
    raise exception 'unknown time zone: %', new.timezone using errcode = '22023';
  end if;
  return new;
end $$;
drop trigger if exists profiles_validate_timezone on public.profiles;
create trigger profiles_validate_timezone before insert or update of timezone on public.profiles
  for each row execute function public.validate_profile_timezone();

-- ---------------------------------------------------------------- 8. family invites
create or replace function public.create_family_invite(p_circle uuid) returns text
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_token text;
begin
  if v_uid is null or not public.is_circle_member(p_circle) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  if not exists (select 1 from public.care_recipients where circle_id = p_circle) then
    raise exception 'no parent in this circle' using errcode = 'P0001', hint = 'no_parent';
  end if;
  if (select count(*) from public.family_invites
       where circle_id = p_circle and revoked_at is null and accepted_at is null and expires_at > now()) >= 10 then
    raise exception 'too many pending invites' using errcode = 'P0001', hint = 'too_many_invites';
  end if;
  if (select count(*) from public.care_circle_members where circle_id = p_circle) >= 10 then
    raise exception 'circle is full' using errcode = 'P0001', hint = 'circle_full';
  end if;
  v_token := replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '');
  insert into public.family_invites (circle_id, created_by, token_hash)
  values (p_circle, v_uid, public.invite_hash(v_token));
  return v_token;
end $$;

create or replace function public.preview_family_invite(p_token text) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_inv public.family_invites;
  v_r record;
begin
  if v_uid is null then raise exception 'not allowed' using errcode = '42501'; end if;
  if p_token is null or p_token !~ '^[0-9a-f]{64}$' then return jsonb_build_object('status', 'invalid'); end if;
  select * into v_inv from public.family_invites where token_hash = public.invite_hash(p_token);
  if not found then return jsonb_build_object('status', 'invalid'); end if;
  if exists (select 1 from public.care_circle_members where circle_id = v_inv.circle_id and user_id = v_uid) then
    return jsonb_build_object('status', 'member');
  end if;
  if v_inv.accepted_at is not null then return jsonb_build_object('status', 'used'); end if;
  if v_inv.revoked_at is not null then return jsonb_build_object('status', 'revoked'); end if;
  if v_inv.expires_at <= now() then return jsonb_build_object('status', 'expired'); end if;
  if v_inv.created_by is null or not exists (select 1 from public.care_circle_members where circle_id = v_inv.circle_id and user_id = v_inv.created_by) then
    return jsonb_build_object('status', 'invalid');   -- the person who made this link is no longer in the family
  end if;
  select r.name, r.relation, r.call_name into v_r
    from public.care_recipients r where r.circle_id = v_inv.circle_id order by r.created_at limit 1;
  if not found then return jsonb_build_object('status', 'no_parent'); end if;
  return jsonb_build_object(
    'status', 'ok',
    'inviter', coalesce(nullif(btrim((select p.name from public.profiles p where p.id = v_inv.created_by)), ''), null),
    'parent_first_name', split_part(btrim(coalesce(v_r.name, '')), ' ', 1),
    'relation', v_r.relation,
    'call_name', v_r.call_name
  );
end $$;

create or replace function public.accept_family_invite(p_token text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_inv public.family_invites;
  v_recipient uuid;
begin
  if v_uid is null then raise exception 'not allowed' using errcode = '42501'; end if;
  if p_token is null or p_token !~ '^[0-9a-f]{64}$' then
    raise exception 'invalid invite' using errcode = 'P0001', hint = 'invalid';
  end if;
  select * into v_inv from public.family_invites where token_hash = public.invite_hash(p_token) for update;
  if not found then raise exception 'invalid invite' using errcode = 'P0001', hint = 'invalid'; end if;
  if exists (select 1 from public.care_circle_members where circle_id = v_inv.circle_id and user_id = v_uid) then
    raise exception 'already a member' using errcode = 'P0001', hint = 'member';
  end if;
  if v_inv.accepted_at is not null then raise exception 'invite used' using errcode = 'P0001', hint = 'used'; end if;
  if v_inv.revoked_at is not null then raise exception 'invite revoked' using errcode = 'P0001', hint = 'revoked'; end if;
  if v_inv.expires_at <= now() then raise exception 'invite expired' using errcode = 'P0001', hint = 'expired'; end if;
  if v_inv.created_by is null or not exists (select 1 from public.care_circle_members where circle_id = v_inv.circle_id and user_id = v_inv.created_by) then
    raise exception 'invalid invite' using errcode = 'P0001', hint = 'invalid';   -- the inviter left or was removed
  end if;
  select id into v_recipient from public.care_recipients where circle_id = v_inv.circle_id order by created_at limit 1;
  if v_recipient is null then
    raise exception 'no parent in this circle' using errcode = 'P0001', hint = 'no_parent';
  end if;
  if (select count(*) from public.care_circle_members where circle_id = v_inv.circle_id) >= 10 then
    raise exception 'circle is full' using errcode = 'P0001', hint = 'circle_full';
  end if;

  insert into public.care_circle_members (circle_id, user_id, role) values (v_inv.circle_id, v_uid, 'member');
  update public.family_invites set accepted_at = now(), accepted_by = v_uid where id = v_inv.id;

  insert into public.notifications (user_id, type, payload)
  select m.user_id, 'member_joined', jsonb_build_object('recipient_id', v_recipient, 'circle_id', v_inv.circle_id, 'member_id', v_uid)
    from public.care_circle_members m
   where m.circle_id = v_inv.circle_id and m.user_id <> v_uid;
  return v_recipient;
end $$;

create or replace function public.remove_circle_member(p_circle uuid, p_user uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := (select auth.uid());
begin
  if v_uid is null or not public.is_circle_owner(p_circle) then
    raise exception 'only the owner can remove members' using errcode = '42501';
  end if;
  if p_user = v_uid then raise exception 'owner cannot remove themself' using errcode = 'P0001', hint = 'self'; end if;
  delete from public.care_circle_members where circle_id = p_circle and user_id = p_user and role = 'member';
  if not found then raise exception 'member not found' using errcode = 'P0001', hint = 'not_found'; end if;
  update public.family_invites set revoked_at = now()
   where circle_id = p_circle and created_by = p_user and revoked_at is null and accepted_at is null;
end $$;

create or replace function public.leave_circle(p_circle uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := (select auth.uid());
begin
  if v_uid is null or not public.is_circle_member(p_circle) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  delete from public.care_circle_members where circle_id = p_circle and user_id = v_uid and role = 'member';
  if not found then raise exception 'the owner cannot leave' using errcode = 'P0001', hint = 'owner'; end if;
  update public.family_invites set revoked_at = now()
   where circle_id = p_circle and created_by = v_uid and revoked_at is null and accepted_at is null;
end $$;
