-- ---------------------------------------------------------------- signup -> profile
create function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id, name, email, phone)
  values (
    new.id,
    new.raw_user_meta_data ->> 'name',
    new.email,
    nullif(new.phone, '')
  );
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

-- ---------------------------------------------------------------- new circle -> creator is owner
create function public.handle_new_circle() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if new.created_by is not null then
    insert into public.care_circle_members (circle_id, user_id, role)
    values (new.id, new.created_by, 'owner');
  end if;
  return new;
end $$;
create trigger on_care_circle_created after insert on public.care_circles
  for each row execute function public.handle_new_circle();

-- ---------------------------------------------------------------- private storage buckets
-- Files live at <recipient_id>/<filename>. Access is by circle membership,
-- and the app serves them only through short-lived signed URLs.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types) values
  ('medicine-photos', 'medicine-photos', false, 5242880,
    array['image/jpeg', 'image/png', 'image/webp']),
  ('reports', 'reports', false, 15728640,
    array['image/jpeg', 'image/png', 'image/webp', 'application/pdf'])
on conflict (id) do nothing;

create function public.storage_recipient_id(p_name text) returns uuid
language plpgsql immutable set search_path = '' as $$
begin
  return (string_to_array(p_name, '/'))[1]::uuid;
exception when others then
  return null;   -- malformed path => no access
end $$;

create policy health_files_select on storage.objects for select to authenticated
using (
  bucket_id in ('medicine-photos', 'reports')
  and public.can_access_recipient(public.storage_recipient_id(name))
);
create policy health_files_insert on storage.objects for insert to authenticated
with check (
  bucket_id in ('medicine-photos', 'reports')
  and public.can_access_recipient(public.storage_recipient_id(name))
);
create policy health_files_update on storage.objects for update to authenticated
using (
  bucket_id in ('medicine-photos', 'reports')
  and public.can_access_recipient(public.storage_recipient_id(name))
);
create policy health_files_delete on storage.objects for delete to authenticated
using (
  bucket_id in ('medicine-photos', 'reports')
  and public.can_access_recipient(public.storage_recipient_id(name))
);
