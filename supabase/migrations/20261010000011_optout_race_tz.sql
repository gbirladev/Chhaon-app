-- Round-2 review fixes. Written to be re-runnable (create if not exists / create or replace).

-- 1. STOP belongs to the PHONE NUMBER, not to one parent record. Editing the number, deleting and re-adding the parent,
--    or enrolling the same number elsewhere must never make a number that said STOP messageable again.
--    Only the parent can lift it (START, or tapping Yes on a consent message). Backend only: RLS on, no policies.
create table if not exists public.phone_opt_outs (
  phone         text primary key,
  opted_out_at  timestamptz not null default now()
);
alter table public.phone_opt_outs enable row level security;

insert into public.phone_opt_outs (phone, opted_out_at)
select distinct on (whatsapp_phone) whatsapp_phone, opted_out_at
from public.care_recipients
where opted_out_at is not null and whatsapp_phone is not null
on conflict (phone) do nothing;

-- 2. Time zones: accept only the exact names the scheduler can use ('IST', 'asia/kolkata' and '+05:30' are valid
--    to Postgres but are not in pg_timezone_names, so doses would silently never be generated).
create or replace function public.validate_recipient_timezone() returns trigger
language plpgsql set search_path = '' as $$
begin
  if not exists (select 1 from pg_catalog.pg_timezone_names n where n.name = new.timezone) then
    raise exception 'unknown time zone: %', new.timezone using errcode = '22023';
  end if;
  return new;
end $$;

-- 3. "Created by" is stamped on insert AND frozen on update.
create or replace function public.stamp_author() returns trigger
language plpgsql set search_path = '' as $$
begin
  if (select auth.role()) = 'authenticated' then
    if tg_op = 'UPDATE' then
      if tg_table_name = 'reports' then new.uploaded_by := old.uploaded_by; else new.created_by := old.created_by; end if;
    elsif tg_table_name = 'reports' then new.uploaded_by := (select auth.uid());
    else new.created_by := (select auth.uid());
    end if;
  end if;
  return new;
end $$;
drop trigger if exists reports_stamp_author on public.reports;
drop trigger if exists appointments_stamp_author on public.appointments;
create trigger reports_stamp_author before insert or update on public.reports for each row execute function public.stamp_author();
create trigger appointments_stamp_author before insert or update on public.appointments for each row execute function public.stamp_author();

-- 4. Consent-request limits as ONE atomic step. Counting and inserting in separate calls let two quick taps cancel
--    each other (both thought they were the second one, so nothing was sent).
create or replace function public.claim_consent_request(p_phone text, p_user uuid, p_window interval, p_daily_cap integer)
returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_id uuid;
begin
  perform pg_advisory_xact_lock(hashtext(p_phone));   -- one request per phone at a time
  if exists (select 1 from public.consent_requests where phone = p_phone and requested_at > now() - p_window) then
    return jsonb_build_object('status', 'too_soon');
  end if;
  if (select count(*) from public.consent_requests where requested_by = p_user and requested_at > now() - interval '24 hours') >= p_daily_cap then
    return jsonb_build_object('status', 'too_many');
  end if;
  insert into public.consent_requests (requested_by, phone) values (p_user, p_phone) returning id into v_id;
  return jsonb_build_object('status', 'ok', 'id', v_id);
end $$;
revoke execute on function public.claim_consent_request(text, uuid, interval, integer) from public, anon, authenticated;
grant execute on function public.claim_consent_request(text, uuid, interval, integer) to service_role;
