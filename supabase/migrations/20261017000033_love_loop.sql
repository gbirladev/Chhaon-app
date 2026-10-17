-- Love loop, step 1 (database): caregivers send a small warm reaction that reaches the parent on WhatsApp.
-- Reactions go out as free session messages inside the open 24-hour window (no template). Everything is OFF until a family
-- turns it on, and the server has its own switch (LOVE_LOOP_ENABLED). The backend (service role) does all sending and status
-- changes; caregivers only create a reaction through send_reaction() and read the rest.

-- 1. What the parent calls each caregiver ("Riya", "Bittu", "Beta"). One name per member of the circle; the caregiver sets their own.
alter table public.care_circle_members add column parent_calls_me text check (char_length(parent_calls_me) <= 40);

create function public.set_parent_calls_me(p_circle uuid, p_name text) returns void
language plpgsql security definer set search_path = '' as $$
declare v_name text := nullif(btrim(regexp_replace(regexp_replace(coalesce(p_name, ''), '[\x01-\x1f\x7f]+', ' ', 'g'), ' {2,}', ' ', 'g')), '');
begin
  if char_length(coalesce(v_name, '')) > 40 then raise exception 'name too long' using errcode = '22001'; end if;
  update public.care_circle_members set parent_calls_me = v_name
   where circle_id = p_circle and user_id = (select auth.uid());
  if not found then raise exception 'not found' using errcode = '42501'; end if;
end $$;
revoke execute on function public.set_parent_calls_me(uuid, text) from public, anon;
grant execute on function public.set_parent_calls_me(uuid, text) to authenticated;

-- 2. Per parent: on/off, limits, and the pilot group. Caregivers read it and switch it with set_love_enabled(); the rest is
--    changed by the backend or by hand in the SQL editor (an unknown row means "off, defaults").
create table public.love_settings (
  recipient_id          uuid primary key references public.care_recipients (id) on delete cascade,
  enabled               boolean not null default false,
  daily_cap             integer not null default 3 check (daily_cap between 1 and 10),     -- love messages per parent per parent-day
  quiet_start           time not null default '21:00',                                      -- parent's local time
  quiet_end             time not null default '07:00',
  parent_opted_out_at   timestamptz,                                                        -- the parent wrote NO HEARTS (STOP is separate)
  pilot_group           text not null default 'love' check (pilot_group in ('love', 'control')),
  first_enabled_at      timestamptz,                                                        -- the "after" mark for the pilot comparison
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now()
);
alter table public.love_settings enable row level security;
create policy love_settings_read on public.love_settings for select to authenticated using (public.can_access_recipient(recipient_id));
create trigger love_settings_updated_at before update on public.love_settings for each row execute function public.set_updated_at();
-- no write policy: caregivers use set_love_enabled()

create function public.set_love_enabled(p_recipient uuid, p_enabled boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare v_group text;
begin
  if not public.can_access_recipient(p_recipient) then raise exception 'not found' using errcode = '42501'; end if;
  select pilot_group into v_group from public.love_settings where recipient_id = p_recipient;
  if coalesce(p_enabled, false) and v_group = 'control' then
    raise exception 'love_not_available' using errcode = 'P0001';     -- a control family of the pilot stays without it
  end if;
  insert into public.love_settings (recipient_id, enabled, first_enabled_at)
  values (p_recipient, coalesce(p_enabled, false), case when coalesce(p_enabled, false) then now() end)
  on conflict (recipient_id) do update
     set enabled = excluded.enabled,
         first_enabled_at = coalesce(public.love_settings.first_enabled_at, excluded.first_enabled_at);
end $$;
revoke execute on function public.set_love_enabled(uuid, boolean) from public, anon;
grant execute on function public.set_love_enabled(uuid, boolean) to authenticated;

-- 3. Reactions. type: emoji (text holds an optional short note), preset (text holds the preset key) or custom (text is the note).
--    status: queued -> sent -> delivered -> read, or failed / expired (48 h in the queue) / skipped (parent said NO HEARTS, or it was turned off).
create table public.reactions (
  id            uuid primary key default gen_random_uuid(),
  recipient_id  uuid not null references public.care_recipients (id) on delete cascade,
  sender_id     uuid references public.profiles (id) on delete set null,
  source_type   text not null check (source_type in ('dose', 'checkin')),
  source_id     uuid not null,
  type          text not null check (type in ('emoji', 'preset', 'custom')),
  emoji         text check (emoji in (E'❤️', E'\U0001F64F', E'\U0001F917')),
  text          text check (char_length(text) <= 150),
  status        text not null default 'queued' check (status in ('queued', 'sent', 'delivered', 'read', 'failed', 'expired', 'skipped')),
  fail_reason   text,                              -- a short code the app turns into a plain explanation
  batch_id      uuid,                              -- reactions sent together as one message share this
  wamid         text,                              -- WhatsApp message id (shared inside a batch)
  in_window     boolean,                           -- was it sent inside the open 24-hour window (cost tracking)
  category      text not null default 'service',   -- WhatsApp conversation category (cost tracking)
  queued_at     timestamptz not null default now(),
  due_at        timestamptz not null default now() + interval '2 minutes',   -- the batching wait
  expires_at    timestamptz not null default now() + interval '48 hours',
  sent_at       timestamptz,
  delivered_at  timestamptz,
  read_at       timestamptz,
  check ((type = 'emoji' and emoji is not null) or (type <> 'emoji' and emoji is null)),
  check (type = 'emoji' or text is not null),
  check (type <> 'preset' or text in ('love_you', 'thank_you', 'talk_tonight')),
  unique (sender_id, source_type, source_id)       -- one reaction per caregiver per moment: a double tap changes nothing
);
create index on public.reactions (recipient_id, queued_at desc);
create index on public.reactions (recipient_id, sent_at) where sent_at is not null;
create index on public.reactions (status, due_at) where status = 'queued';
create index on public.reactions (wamid) where wamid is not null;
create index on public.reactions (batch_id) where batch_id is not null;
alter table public.reactions enable row level security;
create policy reactions_read on public.reactions for select to authenticated using (public.can_access_recipient(recipient_id));
-- no insert / update / delete policy: send_reaction() creates, the backend moves the status

create function public.send_reaction(p_recipient uuid, p_source_type text, p_source_id uuid, p_type text, p_emoji text default null, p_text text default null)
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_text text := nullif(btrim(regexp_replace(regexp_replace(coalesce(p_text, ''), '[\x01-\x1f\x7f]+', ' ', 'g'), ' {2,}', ' ', 'g')), '');
  v_ok boolean := false;
  v_id uuid;
begin
  if v_uid is null or not public.can_access_recipient(p_recipient) then raise exception 'not found' using errcode = '42501'; end if;
  if not coalesce((select s.enabled from public.love_settings s where s.recipient_id = p_recipient), false) then
    raise exception 'love_off' using errcode = 'P0001';
  end if;
  if (select s.parent_opted_out_at from public.love_settings s where s.recipient_id = p_recipient) is not null then
    raise exception 'love_parent_opted_out' using errcode = 'P0001';
  end if;
  if char_length(coalesce(v_text, '')) > 150 then raise exception 'love_text_invalid' using errcode = 'P0001'; end if;

  -- the moment must be this parent's, confirmed, and recent
  if p_source_type = 'dose' then
    select true into v_ok from public.dose_events de
      join public.medicine_schedules ms on ms.id = de.schedule_id
      join public.medicines m on m.id = ms.medicine_id
     where de.id = p_source_id and m.recipient_id = p_recipient and de.status in ('taken', 'taken_late')
       and de.responded_at > now() - interval '48 hours';
  elsif p_source_type = 'checkin' then
    select true into v_ok from public.checkins c
     where c.id = p_source_id and c.recipient_id = p_recipient and c.status = 'replied' and c.responded_at > now() - interval '48 hours';
  end if;
  if not coalesce(v_ok, false) then raise exception 'love_source_invalid' using errcode = 'P0001'; end if;

  insert into public.reactions (recipient_id, sender_id, source_type, source_id, type, emoji, text)
  values (p_recipient, v_uid, p_source_type, p_source_id, p_type,
          case when p_type = 'emoji' then p_emoji end,
          case when p_type = 'emoji' then v_text else coalesce(v_text, null) end)
  returning id into v_id;      -- a repeat raises 23505 (unique) or 23514 (check), which the app explains
  return v_id;
end $$;
revoke execute on function public.send_reaction(uuid, text, uuid, text, text, text) from public, anon;
grant execute on function public.send_reaction(uuid, text, uuid, text, text, text) to authenticated;

-- 4. What the parent writes back (free text, emoji, a tap). The newest message also marks the 24-hour window as open.
create table public.parent_messages (
  id            uuid primary key default gen_random_uuid(),
  recipient_id  uuid not null references public.care_recipients (id) on delete cascade,
  wamid         text not null unique,                 -- Meta can resend: the same message is stored once
  kind          text not null check (kind in ('text', 'emoji', 'button', 'other')),
  body          text check (char_length(body) <= 500),
  reaction_id   uuid references public.reactions (id) on delete set null,   -- set when it answers a reaction
  created_at    timestamptz not null default now()
);
create index on public.parent_messages (recipient_id, created_at desc);
create index on public.parent_messages (reaction_id) where reaction_id is not null;
alter table public.parent_messages enable row level security;
create policy parent_messages_read on public.parent_messages for select to authenticated using (public.can_access_recipient(recipient_id));

-- 5. Pilot numbers for the owner of the app. Totals only. Compare the families with the love loop and the control families:
--    reactions by status, parent replies to reactions, and the share of reminders confirmed in the p_days before and after each
--    family's start mark (first time switched on; for control families the day they were put in the group).
create function public.love_stats(p_days integer default 14) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v_days integer := least(greatest(coalesce(p_days, 14), 1), 60);
  v_result jsonb;
begin
  if v_uid is null or not coalesce((select p.is_admin from public.profiles p where p.id = v_uid), false) then
    raise exception 'not allowed' using errcode = '42501';
  end if;

  with fam as (
    select s.recipient_id, s.pilot_group, s.enabled, coalesce(s.first_enabled_at, s.created_at) as mark
      from public.love_settings s
  ),
  doses as (
    select m.recipient_id, de.sent_at, (de.status in ('taken', 'taken_late')) as confirmed
      from public.dose_events de
      join public.medicine_schedules ms on ms.id = de.schedule_id
      join public.medicines m on m.id = ms.medicine_id
     where de.sent_at is not null
  ),
  rate as (
    select f.pilot_group,
           count(*) filter (where d.sent_at >= f.mark - make_interval(days => v_days) and d.sent_at < f.mark) as before_sent,
           count(*) filter (where d.sent_at >= f.mark - make_interval(days => v_days) and d.sent_at < f.mark and d.confirmed) as before_ok,
           count(*) filter (where d.sent_at >= f.mark and d.sent_at < f.mark + make_interval(days => v_days)) as after_sent,
           count(*) filter (where d.sent_at >= f.mark and d.sent_at < f.mark + make_interval(days => v_days) and d.confirmed) as after_ok
      from fam f join doses d on d.recipient_id = f.recipient_id
     group by f.pilot_group
  )
  select jsonb_build_object(
    'generated_at', now(),
    'days', v_days,
    'families', jsonb_build_object(
      'love_on', (select count(*) from fam where pilot_group = 'love' and enabled),
      'love_group', (select count(*) from fam where pilot_group = 'love'),
      'control', (select count(*) from fam where pilot_group = 'control'),
      'parent_opted_out', (select count(*) from public.love_settings where parent_opted_out_at is not null)
    ),
    'reactions', jsonb_build_object(
      'created', (select count(*) from public.reactions),
      'sent', (select count(*) from public.reactions where status in ('sent', 'delivered', 'read')),
      'delivered', (select count(*) from public.reactions where status in ('delivered', 'read')),
      'read', (select count(*) from public.reactions where status = 'read'),
      'queued', (select count(*) from public.reactions where status = 'queued'),
      'expired', (select count(*) from public.reactions where status = 'expired'),
      'failed', (select count(*) from public.reactions where status = 'failed'),
      'skipped', (select count(*) from public.reactions where status = 'skipped'),
      'sent_outside_window', (select count(*) from public.reactions where in_window = false)
    ),
    'parent_replies_to_reactions', (select count(*) from public.parent_messages where reaction_id is not null),
    'confirmation', coalesce((select jsonb_object_agg(r.pilot_group, jsonb_build_object(
        'before_sent', r.before_sent, 'before_confirmed', r.before_ok, 'after_sent', r.after_sent, 'after_confirmed', r.after_ok)) from rate r), '{}'::jsonb)
  ) into v_result;
  return v_result;
end $$;
revoke execute on function public.love_stats(integer) from public, anon;
grant execute on function public.love_stats(integer) to authenticated;
