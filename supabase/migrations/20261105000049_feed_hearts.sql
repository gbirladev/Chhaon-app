-- Family activity feed (feature 4): a single ♥ per family member per feed item, shown as a count to the family.
-- Not the love loop (migration 0033): those reactions go to the parent on WhatsApp, only for a confirmed dose or check-in, with a
-- daily cap and an opt-in. A feed heart never leaves the app: it is a quiet "thank you / seen" between family members, for any item
-- (a dose Sunita gave, a vaccine marked given, an appointment added ...). No text, no comments.
-- Idempotent. Deploy: paste into the SQL editor before (or after) the backend; the feed works without it (no hearts shown).

create table if not exists public.feed_hearts (
  recipient_id  uuid not null references public.care_recipients (id) on delete cascade,
  -- what was hearted: '<kind>:<uuid>' or '<kind>:<uuid>:<epoch seconds>' (consent / stop moments), built by the backend
  item_key      text not null check (item_key ~ '^[a-z_]{1,20}:[0-9a-f-]{36}(:[0-9]{1,12})?$'),
  user_id       uuid not null references public.profiles (id) on delete cascade,
  created_at    timestamptz not null default now(),
  primary key (recipient_id, item_key, user_id)
);
create index if not exists feed_hearts_user on public.feed_hearts (user_id);

comment on table public.feed_hearts is
  'Family activity feed: one heart per family member per item, visible to the family circle only. Never sent on WhatsApp.';

alter table public.feed_hearts enable row level security;
drop policy if exists feed_hearts_select on public.feed_hearts;
create policy feed_hearts_select on public.feed_hearts for select to authenticated
  using (public.can_access_recipient(recipient_id));
drop policy if exists feed_hearts_insert on public.feed_hearts;
create policy feed_hearts_insert on public.feed_hearts for insert to authenticated
  with check (user_id = (select auth.uid()) and public.can_access_recipient(recipient_id));
drop policy if exists feed_hearts_delete on public.feed_hearts;
create policy feed_hearts_delete on public.feed_hearts for delete to authenticated
  using (user_id = (select auth.uid()));

-- Permissions written out in full (the Sydney copy once lost its GRANTs). Nothing for anonymous visitors; no updates.
revoke all on table public.feed_hearts from public, anon, authenticated, service_role;
grant select, insert, delete on table public.feed_hearts to authenticated;
grant select, insert, update, delete, references, trigger, truncate on table public.feed_hearts to service_role;
