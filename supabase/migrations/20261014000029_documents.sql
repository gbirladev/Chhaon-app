-- Reports wallet: keep a parent's reports, prescriptions, scans and other papers in one place (photos or PDFs).
-- No reading or analysis: it only stores and shows the files. Files live in the private `reports` bucket at
-- <recipient_id>/<name>; the bucket rules from migration 0003 already limit access to the family circle.
-- (The older report-reading tables `reports` / `report_values` stay as they are, switched off.)

-- How many reports one parent's wallet can hold, per plan (NULL = unlimited, up to the system ceiling of 200).
alter table public.plans add column max_documents integer check (max_documents > 0);
update public.plans set max_documents = 5   where key = 'free';
update public.plans set max_documents = 25  where key = 'plus';
update public.plans set max_documents = 100 where key = 'family';
-- 'ultra' stays NULL (unlimited, capped by the system ceiling)

create table public.documents (
  id            uuid primary key default gen_random_uuid(),
  recipient_id  uuid not null references public.care_recipients (id) on delete cascade,
  uploaded_by   uuid references public.profiles (id) on delete set null,
  title         text not null check (char_length(btrim(title)) between 1 and 120 and not public.has_control_chars(title)),
  category      text not null default 'other'
                check (category in ('lab', 'prescription', 'scan', 'discharge', 'vaccination', 'insurance', 'bill', 'other')),
  doc_date      date,                                   -- the date ON the paper (not the upload date)
  doctor        text check (doctor is null or (char_length(doctor) <= 120 and not public.has_control_chars(doctor))),
  notes         text check (notes is null or char_length(notes) <= 1000),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index on public.documents (recipient_id, doc_date desc nulls last, created_at desc);

-- A document can have several pages (photos) or one PDF.
create table public.document_files (
  id           uuid primary key default gen_random_uuid(),
  document_id  uuid not null references public.documents (id) on delete cascade,
  path         text not null,                           -- storage path: <recipient_id>/<name>
  thumb_path   text,                                    -- small preview for the list (images only)
  mime         text not null check (mime in ('image/jpeg', 'image/png', 'image/webp', 'application/pdf')),
  size_bytes   integer not null check (size_bytes between 1 and 15728640),
  position     smallint not null default 0,
  created_at   timestamptz not null default now()
);
create index on public.document_files (document_id, position);

alter table public.documents enable row level security;
alter table public.document_files enable row level security;

create function public.can_access_document(p_document uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.documents d where d.id = p_document and public.can_access_recipient(d.recipient_id));
$$;
revoke execute on function public.can_access_document(uuid) from public, anon;
grant execute on function public.can_access_document(uuid) to authenticated;

create policy documents_all on public.documents for all to authenticated
using (public.can_access_recipient(recipient_id))
with check (public.can_access_recipient(recipient_id));
create policy document_files_all on public.document_files for all to authenticated
using (public.can_access_document(document_id))
with check (public.can_access_document(document_id));

-- The uploader is whoever is logged in; nobody can sign a document as someone else.
create function public.stamp_document_author() returns trigger
language plpgsql set search_path = '' as $$
begin
  if (select auth.role()) = 'authenticated' then new.uploaded_by := (select auth.uid()); end if;
  return new;
end $$;
create trigger documents_stamp_author before insert on public.documents for each row execute function public.stamp_document_author();
create trigger documents_updated_at before update on public.documents for each row execute function public.set_updated_at();

-- A file must live in this parent's own folder: no other parent's folder, no "..", no nested tricks.
create function public.check_document_file() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_recipient uuid;
begin
  select recipient_id into v_recipient from public.documents where id = new.document_id;
  if new.path !~ ('^' || v_recipient::text || '/[A-Za-z0-9._-]{1,120}$') or new.path ~ '\.\.'
     or (new.thumb_path is not null and (new.thumb_path !~ ('^' || v_recipient::text || '/[A-Za-z0-9._-]{1,120}$') or new.thumb_path ~ '\.\.')) then
    raise exception 'file path not allowed' using errcode = '22023', hint = 'bad_path';
  end if;
  return new;
end $$;
create trigger document_files_check before insert or update on public.document_files for each row execute function public.check_document_file();

-- Limits, whatever way rows arrive: the plan's number of reports per parent (the system ceiling is 200 for "unlimited").
-- Over the limit nothing is hidden or deleted: families can still open, download and export everything, they just cannot add more.
create function public.limit_documents() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_max integer;
begin
  perform pg_advisory_xact_lock(hashtext('documents:' || new.recipient_id::text));
  select (public.plan_for_circle(r.circle_id)).max_documents into v_max from public.care_recipients r where r.id = new.recipient_id;
  if (select count(*) from public.documents where recipient_id = new.recipient_id) >= coalesce(v_max, 200) then
    raise exception 'too many documents for this plan' using errcode = 'P0001', hint = case when v_max is null then 'too_many_documents' else 'plan_documents' end;
  end if;
  return new;
end $$;
create trigger documents_limit before insert on public.documents for each row execute function public.limit_documents();

create function public.limit_document_files() returns trigger
language plpgsql security definer set search_path = '' as $$
declare v_recipient uuid;
begin
  perform pg_advisory_xact_lock(hashtext('document_files:' || new.document_id::text));
  if (select count(*) from public.document_files where document_id = new.document_id) >= 10 then
    raise exception 'too many pages' using errcode = 'P0001', hint = 'too_many_files';
  end if;
  -- a hidden cost guard on every plan: at most 300 MB of files per parent
  select recipient_id into v_recipient from public.documents where id = new.document_id;
  perform pg_advisory_xact_lock(hashtext('document_bytes:' || v_recipient::text));
  if coalesce((select sum(f.size_bytes) from public.document_files f join public.documents d on d.id = f.document_id where d.recipient_id = v_recipient), 0) + new.size_bytes > 314572800 then
    raise exception 'storage is full' using errcode = 'P0001', hint = 'storage_full';
  end if;
  return new;
end $$;
create trigger document_files_limit before insert on public.document_files for each row execute function public.limit_document_files();

-- For the Reports screen: how many reports this parent has and how many the plan allows (max NULL = no plan limit).
create function public.wallet_usage(p_recipient uuid) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare v_circle uuid;
begin
  select circle_id into v_circle from public.care_recipients where id = p_recipient;
  if v_circle is null or not public.can_access_recipient(p_recipient) then
    raise exception 'not found' using errcode = '42501';
  end if;
  return jsonb_build_object(
    'used', (select count(*) from public.documents where recipient_id = p_recipient),
    'max', (public.plan_for_circle(v_circle)).max_documents);
end $$;
revoke execute on function public.wallet_usage(uuid) from public, anon;
grant execute on function public.wallet_usage(uuid) to authenticated;
