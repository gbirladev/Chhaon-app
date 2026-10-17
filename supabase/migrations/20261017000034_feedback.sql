-- In-app feedback: a caregiver can send an idea, a problem or a thank-you from the Family tab.
-- A caregiver can write and read only their OWN rows. The owner of the app reads everything through admin_feedback()
-- (the one place that shows who wrote it). No health data or phone numbers are asked for; the app says so under the text box.

create table public.feedback (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid references public.profiles (id) on delete set null,   -- stays readable by the owner if the account is deleted
  kind          text not null check (kind in ('idea', 'problem', 'praise', 'other')),
  message       text not null check (char_length(btrim(message)) between 1 and 1000),
  rating        smallint check (rating between 1 and 5),
  screen        text check (screen is null or (char_length(screen) between 1 and 40 and not public.has_control_chars(screen))),   -- which screen they were on
  app_language  text not null default 'en' check (app_language in ('en', 'hi')),
  created_at    timestamptz not null default now()
);
create index on public.feedback (created_at desc);
create index on public.feedback (user_id, created_at desc);

alter table public.feedback enable row level security;

-- Insert only as yourself; read only your own. No update or delete policy, so nobody can change or remove feedback.
create policy feedback_insert_own on public.feedback for insert to authenticated
with check (user_id = (select auth.uid()));
create policy feedback_select_own on public.feedback for select to authenticated
using (user_id = (select auth.uid()));
revoke update, delete, truncate on public.feedback from anon, authenticated;
revoke all on public.feedback from anon;

-- Light rate limit: at most 10 feedback messages per caregiver per day.
create function public.limit_feedback() returns trigger
language plpgsql set search_path = '' as $$
begin
  if new.user_id is not null and (select count(*) from public.feedback f
        where f.user_id = new.user_id and f.created_at > now() - interval '1 day') >= 10 then
    raise exception 'too much feedback today' using errcode = '53400';
  end if;
  return new;
end $$;
create trigger feedback_limit before insert on public.feedback
  for each row execute function public.limit_feedback();

-- Owner only: newest feedback with the sender's name and email. Same guard as admin_stats().
create function public.admin_feedback(p_limit integer default 100)
returns table (id uuid, created_at timestamptz, kind text, message text, rating smallint, screen text, app_language text, sender_name text, sender_email text)
language plpgsql stable security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
begin
  if v_uid is null or not coalesce((select p.is_admin from public.profiles p where p.id = v_uid), false) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  return query
    select f.id, f.created_at, f.kind, f.message, f.rating, f.screen, f.app_language, p.name, p.email
      from public.feedback f left join public.profiles p on p.id = f.user_id
     order by f.created_at desc
     limit least(greatest(coalesce(p_limit, 100), 1), 500);
end $$;
revoke execute on function public.admin_feedback(integer) from public, anon;
grant execute on function public.admin_feedback(integer) to authenticated;
