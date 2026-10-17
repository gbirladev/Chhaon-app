-- Feedback with pictures: a caregiver can attach up to 3 screenshots / photos to a feedback message.
-- Files live in the PRIVATE `feedback` bucket at <user_id>/<name>. A caregiver can add files to their own folder and read
-- only their own; nobody can change or delete a file (same as the feedback rows). The owner (is_admin) can read all of them,
-- and the app only ever shows them through short-lived signed links.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values
  ('feedback', 'feedback', false, 5242880, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do update
  set public = false, file_size_limit = excluded.file_size_limit, allowed_mime_types = excluded.allowed_mime_types;

-- Is the logged-in account the owner of the app? (same rule as admin_stats / admin_feedback)
create function public.is_admin_user() returns boolean
language sql stable security definer set search_path = '' as $$
  select coalesce((select p.is_admin from public.profiles p where p.id = (select auth.uid())), false);
$$;
revoke execute on function public.is_admin_user() from public, anon;
grant execute on function public.is_admin_user() to authenticated;

-- How many pictures this account uploaded in the last day (a function, because a policy cannot count its own table).
create function public.feedback_files_today() returns integer
language sql stable security definer set search_path = '' as $$
  select count(*)::int from storage.objects o
   where o.bucket_id = 'feedback' and o.name like (select auth.uid())::text || '/%' and o.created_at > now() - interval '1 day';
$$;
revoke execute on function public.feedback_files_today() from public, anon;
grant execute on function public.feedback_files_today() to authenticated;

-- Upload only into your own folder, directly under it (no sub-folders, no ".."), and at most 30 pictures a day.
create policy feedback_files_insert on storage.objects for insert to authenticated
with check (
  bucket_id = 'feedback'
  and name ~ ('^' || (select auth.uid())::text || '/[A-Za-z0-9._-]{1,120}$')
  and name !~ '\.\.'
  and public.feedback_files_today() < 30
);
-- Read your own files; the owner reads everyone's. There is no update or delete policy, so files cannot be changed or removed by users.
create policy feedback_files_select_own on storage.objects for select to authenticated
using (bucket_id = 'feedback' and name like (select auth.uid())::text || '/%');
create policy feedback_files_select_admin on storage.objects for select to authenticated
using (bucket_id = 'feedback' and public.is_admin_user());

-- The picture paths on a feedback message: at most 3, each one in the sender's own folder.
alter table public.feedback add column images text[] not null default '{}' check (cardinality(images) <= 3);

create function public.check_feedback_images() returns trigger
language plpgsql set search_path = '' as $$
declare v_path text;
begin
  if cardinality(new.images) = 0 then return new; end if;
  if new.user_id is null then
    raise exception 'pictures need a sender' using errcode = '22023', hint = 'bad_path';
  end if;
  foreach v_path in array new.images loop
    if v_path is null or v_path !~ ('^' || new.user_id::text || '/[A-Za-z0-9._-]{1,120}$') or v_path ~ '\.\.' then
      raise exception 'picture path not allowed' using errcode = '22023', hint = 'bad_path';
    end if;
  end loop;
  return new;
end $$;
create trigger feedback_images_check before insert on public.feedback
  for each row execute function public.check_feedback_images();

-- Owner view: the same columns as before plus the picture paths. (The result shape changes, so the old function is replaced.)
drop function public.admin_feedback(integer);
create function public.admin_feedback(p_limit integer default 100)
returns table (id uuid, created_at timestamptz, kind text, message text, rating smallint, screen text, app_language text, sender_name text, sender_email text, images text[])
language plpgsql stable security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
begin
  if v_uid is null or not coalesce((select p.is_admin from public.profiles p where p.id = v_uid), false) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  return query
    select f.id, f.created_at, f.kind, f.message, f.rating, f.screen, f.app_language, p.name, p.email, f.images
      from public.feedback f left join public.profiles p on p.id = f.user_id
     order by f.created_at desc
     limit least(greatest(coalesce(p_limit, 100), 1), 500);
end $$;
revoke execute on function public.admin_feedback(integer) from public, anon;
grant execute on function public.admin_feedback(integer) to authenticated;
