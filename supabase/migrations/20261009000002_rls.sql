-- Row Level Security: users only see data of circles they belong to.
-- The backend (FastAPI) uses the service role, which bypasses RLS, for
-- scheduler / webhook writes (dose_events, checkins, notifications, consent).

-- ---------------------------------------------------------------- access helpers
-- security definer so policies can read care_circle_members without recursing
-- into its own RLS. search_path is pinned for safety.
create function public.is_circle_member(p_circle uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.care_circle_members
    where circle_id = p_circle and user_id = (select auth.uid())
  );
$$;

create function public.is_circle_owner(p_circle uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.care_circle_members
    where circle_id = p_circle and user_id = (select auth.uid()) and role = 'owner'
  );
$$;

create function public.can_access_recipient(p_recipient uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1
    from public.care_recipients r
    join public.care_circle_members m on m.circle_id = r.circle_id
    where r.id = p_recipient and m.user_id = (select auth.uid())
  );
$$;

create function public.can_access_medicine(p_medicine uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.medicines md
    where md.id = p_medicine and public.can_access_recipient(md.recipient_id)
  );
$$;

create function public.can_access_schedule(p_schedule uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.medicine_schedules s
    where s.id = p_schedule and public.can_access_medicine(s.medicine_id)
  );
$$;

create function public.can_access_report(p_report uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.reports rp
    where rp.id = p_report and public.can_access_recipient(rp.recipient_id)
  );
$$;

revoke execute on function
  public.is_circle_member(uuid), public.is_circle_owner(uuid),
  public.can_access_recipient(uuid), public.can_access_medicine(uuid),
  public.can_access_schedule(uuid), public.can_access_report(uuid)
from public, anon;
grant execute on function
  public.is_circle_member(uuid), public.is_circle_owner(uuid),
  public.can_access_recipient(uuid), public.can_access_medicine(uuid),
  public.can_access_schedule(uuid), public.can_access_report(uuid)
to authenticated;

-- ---------------------------------------------------------------- enable RLS everywhere
alter table public.profiles            enable row level security;
alter table public.care_circles        enable row level security;
alter table public.care_circle_members enable row level security;
alter table public.care_recipients     enable row level security;
alter table public.medicines           enable row level security;
alter table public.medicine_schedules  enable row level security;
alter table public.dose_events         enable row level security;
alter table public.checkins            enable row level security;
alter table public.reports             enable row level security;
alter table public.report_values       enable row level security;
alter table public.appointments        enable row level security;
alter table public.emergency_links     enable row level security;
alter table public.notifications       enable row level security;
alter table public.whatsapp_events     enable row level security;  -- no policies: service role only

-- ---------------------------------------------------------------- profiles
-- A user sees themself and the other members of their circles (to show sibling names).
create policy profiles_select on public.profiles for select to authenticated
using (
  id = (select auth.uid())
  or exists (
    select 1
    from public.care_circle_members mine
    join public.care_circle_members theirs on theirs.circle_id = mine.circle_id
    where mine.user_id = (select auth.uid()) and theirs.user_id = profiles.id
  )
);
create policy profiles_update on public.profiles for update to authenticated
using (id = (select auth.uid())) with check (id = (select auth.uid()));
-- insert: by trigger only. delete: by deleting the auth user (cascade).

-- ---------------------------------------------------------------- circles
create policy circles_select on public.care_circles for select to authenticated
using (public.is_circle_member(id) or created_by = (select auth.uid()));
create policy circles_insert on public.care_circles for insert to authenticated
with check (created_by = (select auth.uid()));
create policy circles_delete on public.care_circles for delete to authenticated
using (public.is_circle_owner(id));

-- ---------------------------------------------------------------- circle members
create policy members_select on public.care_circle_members for select to authenticated
using (public.is_circle_member(circle_id));
-- insert: by trigger (owner) and invite acceptance (step 9, via backend). No client insert.
create policy members_update on public.care_circle_members for update to authenticated
using (public.is_circle_owner(circle_id)) with check (public.is_circle_owner(circle_id));
-- owner can remove anyone; any member can leave.
create policy members_delete on public.care_circle_members for delete to authenticated
using (public.is_circle_owner(circle_id) or user_id = (select auth.uid()));

-- ---------------------------------------------------------------- recipients
create policy recipients_select on public.care_recipients for select to authenticated
using (public.is_circle_member(circle_id));
create policy recipients_insert on public.care_recipients for insert to authenticated
with check (public.is_circle_member(circle_id));
create policy recipients_update on public.care_recipients for update to authenticated
using (public.is_circle_member(circle_id)) with check (public.is_circle_member(circle_id));
create policy recipients_delete on public.care_recipients for delete to authenticated
using (public.is_circle_owner(circle_id));

-- consent_at / opted_out_at come from the parent's WhatsApp reply, so only the
-- backend (service role) may set them. Caregivers cannot fake consent.
create function public.guard_recipient_consent() returns trigger
language plpgsql set search_path = '' as $$
begin
  if (select auth.role()) = 'authenticated' then
    if tg_op = 'INSERT' then
      new.consent_at := null;
      new.opted_out_at := null;
    elsif new.consent_at is distinct from old.consent_at
       or new.opted_out_at is distinct from old.opted_out_at then
      raise exception 'consent fields can only be changed by the system' using errcode = '42501';
    end if;
  end if;
  return new;
end $$;
create trigger care_recipients_guard_consent
  before insert or update on public.care_recipients
  for each row execute function public.guard_recipient_consent();

-- ---------------------------------------------------------------- caregiver-managed health data
create policy medicines_all on public.medicines for all to authenticated
using (public.can_access_recipient(recipient_id))
with check (public.can_access_recipient(recipient_id));

create policy schedules_all on public.medicine_schedules for all to authenticated
using (public.can_access_medicine(medicine_id))
with check (public.can_access_medicine(medicine_id));

create policy reports_all on public.reports for all to authenticated
using (public.can_access_recipient(recipient_id))
with check (public.can_access_recipient(recipient_id));

create policy report_values_all on public.report_values for all to authenticated
using (public.can_access_report(report_id))
with check (public.can_access_report(report_id));

create policy appointments_all on public.appointments for all to authenticated
using (public.can_access_recipient(recipient_id))
with check (public.can_access_recipient(recipient_id));

create policy emergency_links_all on public.emergency_links for all to authenticated
using (public.can_access_recipient(recipient_id))
with check (public.can_access_recipient(recipient_id));
-- Public (no-login) emergency card access is served by the backend using the
-- service role, looking up by token. No anon policy here on purpose. (step 10)

-- ---------------------------------------------------------------- system-written, caregiver read-only
create policy dose_events_select on public.dose_events for select to authenticated
using (public.can_access_schedule(schedule_id));

create policy checkins_select on public.checkins for select to authenticated
using (public.can_access_recipient(recipient_id));

-- ---------------------------------------------------------------- notifications
create policy notifications_select on public.notifications for select to authenticated
using (user_id = (select auth.uid()));
create policy notifications_update on public.notifications for update to authenticated
using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy notifications_delete on public.notifications for delete to authenticated
using (user_id = (select auth.uid()));
-- insert: backend only.
