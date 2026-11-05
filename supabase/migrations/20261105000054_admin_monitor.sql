-- Admin Monitor (docs/admin-monitor.md): three small bookkeeping tables for the owner's "Monitor" screen.
--
-- WHY: on 2026-11-05 a WhatsApp number change left the gateway session without a webhook, so for about 12 hours no parent
-- reply reached the backend while reminders still went out, and nobody noticed. The Monitor's health checker (Java,
-- org.smartgurukul.chhaon.admin) now looks every 5 minutes and remembers what it found here.
--
--   admin_alerts             one row per problem, from "opened" until "resolved" (the dashboard shows open ones + history)
--   admin_audit              who (admin) looked at which family, when: every admin view of a family's details is recorded
--   admin_webhook_rejections one row per gateway webhook call our server REFUSED (reason + time only: no body, no phone number)
--
-- Access: Row Level Security ON with NO policies and no grants for anon / authenticated (like whatsapp_events): only the
-- backend (database owner / service role) reads or writes them. The app reaches them only through /api/v1/admin/*.
--
-- Sydney: paste into the SQL Editor (no migration history there) BEFORE deploying the backend that uses these tables.
-- Safe to run twice. Order vs other migrations: any (it only creates its own tables).

create table if not exists public.admin_alerts (
  id            bigint generated always as identity primary key,
  check_key     text not null,                                  -- e.g. 'replies_in', 'scheduler', 'gateway'
  status        text not null check (status in ('amber', 'red')),
  worst_status  text not null check (worst_status in ('amber', 'red')),
  detail        text not null default '',                       -- short, no phone numbers, no health data
  opened_at     timestamptz not null default now(),
  last_seen_at  timestamptz not null default now(),
  resolved_at   timestamptz
);
-- at most one OPEN problem per check
create unique index if not exists admin_alerts_one_open on public.admin_alerts (check_key) where resolved_at is null;
create index if not exists admin_alerts_opened_at on public.admin_alerts (opened_at desc);

create table if not exists public.admin_audit (
  id            bigint generated always as identity primary key,
  admin_id      uuid not null,                                  -- no foreign key: the record outlives a deleted account
  action        text not null,                                  -- e.g. 'view_family'
  recipient_id  uuid,
  circle_id     uuid,
  viewed_at     timestamptz not null default now()
);
create index if not exists admin_audit_viewed_at on public.admin_audit (viewed_at desc);

create table if not exists public.admin_webhook_rejections (
  id            bigint generated always as identity primary key,
  reason        text not null check (reason in ('bad_signature', 'no_secret', 'server_error', 'bad_request')),
  http_status   smallint not null,
  rejected_at   timestamptz not null default now()
);
create index if not exists admin_webhook_rejections_rejected_at on public.admin_webhook_rejections (rejected_at desc);

alter table public.admin_alerts             enable row level security;   -- no policies: service role only
alter table public.admin_audit              enable row level security;
alter table public.admin_webhook_rejections enable row level security;

revoke all on table public.admin_alerts             from public, anon, authenticated;
revoke all on table public.admin_audit              from public, anon, authenticated;
revoke all on table public.admin_webhook_rejections from public, anon, authenticated;
grant select, insert, update, delete on table public.admin_alerts             to service_role;
grant select, insert, update, delete on table public.admin_audit              to service_role;
grant select, insert, update, delete on table public.admin_webhook_rejections to service_role;
