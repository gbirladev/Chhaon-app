-- Step 9: family sharing. A circle member creates a single-use invite link; the invitee opens it, logs in and accepts.
-- Only a SHA-256 hash of the token is stored, so nobody (not even a circle member reading the table) can rebuild a link.
-- Everything goes through SECURITY DEFINER functions; the table has no write access for clients.

create table public.family_invites (
  id           uuid primary key default gen_random_uuid(),
  circle_id    uuid not null references public.care_circles (id) on delete cascade,
  created_by   uuid references public.profiles (id) on delete set null,
  token_hash   text not null unique,
  created_at   timestamptz not null default now(),
  expires_at   timestamptz not null default now() + interval '7 days',
  revoked_at   timestamptz,
  accepted_at  timestamptz,
  accepted_by  uuid references public.profiles (id) on delete set null
);
create index on public.family_invites (circle_id);
alter table public.family_invites enable row level security;

create policy family_invites_select on public.family_invites for select to authenticated
using (public.is_circle_member(circle_id));

-- Clients may read the list (never the hash) and nothing else.
revoke all on public.family_invites from public, anon, authenticated;
grant select (id, circle_id, created_by, created_at, expires_at, revoked_at, accepted_at, accepted_by)
  on public.family_invites to authenticated;
-- PostgREST embed (created_by -> profiles) needs the FK; profiles RLS already limits names to circle mates.

-- Membership changes only through the functions below (owner stays; nobody can demote or delete rows directly).
revoke update, delete on public.care_circle_members from authenticated;

create function public.invite_hash(p_token text) returns text
language sql immutable set search_path = '' as $$
  select encode(sha256(convert_to(p_token, 'utf8')), 'hex');
$$;
revoke execute on function public.invite_hash(text) from public, anon, authenticated;

-- ---------------------------------------------------------------- create
create function public.create_family_invite(p_circle uuid) returns text
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_token text;
begin
  if v_uid is null or not public.is_circle_member(p_circle) then
    raise exception 'not allowed' using errcode = '42501';
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

-- ---------------------------------------------------------------- revoke
create function public.revoke_family_invite(p_invite uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare v_circle uuid;
begin
  select circle_id into v_circle from public.family_invites where id = p_invite;
  if v_circle is null or not public.is_circle_member(v_circle) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  update public.family_invites set revoked_at = now()
   where id = p_invite and revoked_at is null and accepted_at is null;
end $$;

-- ---------------------------------------------------------------- preview (minimal, after login)
-- status: ok | invalid | expired | revoked | used | member. Details only when the link is usable (or you already belong).
create function public.preview_family_invite(p_token text) returns jsonb
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
  select r.name, r.relation, r.call_name into v_r
    from public.care_recipients r where r.circle_id = v_inv.circle_id order by r.created_at limit 1;
  return jsonb_build_object(
    'status', 'ok',
    'inviter', coalesce(nullif(btrim((select p.name from public.profiles p where p.id = v_inv.created_by)), ''), null),
    'parent_first_name', split_part(btrim(coalesce(v_r.name, '')), ' ', 1),
    'relation', v_r.relation,
    'call_name', v_r.call_name
  );
end $$;

-- ---------------------------------------------------------------- accept
create function public.accept_family_invite(p_token text) returns uuid
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
  if (select count(*) from public.care_circle_members where circle_id = v_inv.circle_id) >= 10 then
    raise exception 'circle is full' using errcode = 'P0001', hint = 'circle_full';
  end if;

  insert into public.care_circle_members (circle_id, user_id, role) values (v_inv.circle_id, v_uid, 'member');
  update public.family_invites set accepted_at = now(), accepted_by = v_uid where id = v_inv.id;

  select id into v_recipient from public.care_recipients where circle_id = v_inv.circle_id order by created_at limit 1;
  insert into public.notifications (user_id, type, payload)
  select m.user_id, 'member_joined', jsonb_build_object('recipient_id', v_recipient, 'circle_id', v_inv.circle_id, 'member_id', v_uid)
    from public.care_circle_members m
   where m.circle_id = v_inv.circle_id and m.user_id <> v_uid;
  return v_recipient;
end $$;

-- ---------------------------------------------------------------- remove / leave
create function public.remove_circle_member(p_circle uuid, p_user uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := (select auth.uid());
begin
  if v_uid is null or not public.is_circle_owner(p_circle) then
    raise exception 'only the owner can remove members' using errcode = '42501';
  end if;
  if p_user = v_uid then raise exception 'owner cannot remove themself' using errcode = 'P0001', hint = 'self'; end if;
  delete from public.care_circle_members where circle_id = p_circle and user_id = p_user and role = 'member';
  if not found then raise exception 'member not found' using errcode = 'P0001', hint = 'not_found'; end if;
end $$;

create function public.leave_circle(p_circle uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := (select auth.uid());
begin
  if v_uid is null or not public.is_circle_member(p_circle) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  -- An owner may not leave (no ownership transfer in v1), which also covers "last member".
  delete from public.care_circle_members where circle_id = p_circle and user_id = v_uid and role = 'member';
  if not found then raise exception 'the owner cannot leave' using errcode = 'P0001', hint = 'owner'; end if;
end $$;

revoke execute on function public.create_family_invite(uuid), public.revoke_family_invite(uuid), public.preview_family_invite(text),
  public.accept_family_invite(text), public.remove_circle_member(uuid, uuid), public.leave_circle(uuid) from public, anon;
grant execute on function public.create_family_invite(uuid), public.revoke_family_invite(uuid), public.preview_family_invite(text),
  public.accept_family_invite(text), public.remove_circle_member(uuid, uuid), public.leave_circle(uuid) to authenticated;
