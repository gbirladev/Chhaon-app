-- Archive a medicine instead of deleting it (migration 0056). Run with: npx supabase test db
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a5600000-0000-0000-0000-000000000001', 'archive-owner@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b5600000-0000-0000-0000-000000000002', 'archive-stranger@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
-- Free with the trial over: 2 medicines per parent, so the limit is easy to reach.
update public.profiles set plan = 'free', trial_ends_at = now() - interval '1 day' where id = 'a5600000-0000-0000-0000-000000000001';

create temp table t_ar (k text primary key, v uuid);
grant all on t_ar to authenticated, anon;
create temp table t_tok (k text primary key, v text);
grant all on t_tok to authenticated, anon;

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a5600000-0000-0000-0000-000000000001","role":"authenticated"}', true);
insert into public.care_circles (id, created_by) values ('c5600000-0000-0000-0000-0000000000a1', 'a5600000-0000-0000-0000-000000000001');
insert into public.care_recipients (id, circle_id, name, call_name, relation, timezone)
  values ('d5600000-0000-0000-0000-0000000000d1', 'c5600000-0000-0000-0000-0000000000a1', 'Ramesh', 'Papa', 'father', 'Asia/Kolkata');
insert into t_ar select 'keep', public.create_medicine('d5600000-0000-0000-0000-0000000000d1', 'Telma', '40 mg', null, null, 10, 2, array['08:00']::time[]);
insert into t_ar select 'stop', public.create_medicine('d5600000-0000-0000-0000-0000000000d1', 'Antibiotic', '1 tablet', null, null, 10, 2, array['08:00', '20:00']::time[]);
insert into t_tok select 'card', token from public.enable_emergency_link('d5600000-0000-0000-0000-0000000000d1');
reset role;

-- History: one dose already taken yesterday, plus the waiting doses the scheduler makes.
insert into public.dose_events (schedule_id, scheduled_at, status, responded_at)
select s.id, now() - interval '1 day', 'taken', now() - interval '23 hours'
  from public.medicine_schedules s where s.medicine_id = (select v from t_ar where k = 'stop') limit 1;
do $g$ begin perform public.generate_dose_events(now(), '48 hours', '60 minutes', array['d5600000-0000-0000-0000-0000000000d1']::uuid[]); end $g$;
select ok((select count(*) from public.dose_events de join public.medicine_schedules s on s.id = de.schedule_id
            where s.medicine_id = (select v from t_ar where k = 'stop') and de.status = 'pending') > 0, 'before archiving the medicine has waiting doses');

select throws_ok($$select public.create_medicine('d5600000-0000-0000-0000-0000000000d1', 'Third', null, null, null, 10, 2, array['08:00']::time[])$$,
  'P0001', null, 'Free: a 3rd medicine is refused while both count');

-- ============ archive (as the family, through RLS)
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a5600000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select throws_ok($$update public.medicines set archived_at = now() where id = (select v from t_ar where k = 'stop')$$,
  '23514', null, 'an archived medicine cannot stay active');
select lives_ok($$update public.medicines set archived_at = now(), active = false where id = (select v from t_ar where k = 'stop')$$,
  'the family archives a medicine');
reset role;

select is((select count(*)::int from public.dose_events de join public.medicine_schedules s on s.id = de.schedule_id
            where s.medicine_id = (select v from t_ar where k = 'stop') and de.status = 'pending'), 0, 'archiving removes its waiting doses at once');
select is((select count(*)::int from public.dose_events de join public.medicine_schedules s on s.id = de.schedule_id
            where s.medicine_id = (select v from t_ar where k = 'stop') and de.status = 'taken'), 1, 'its dose history is kept');
do $g$ begin perform public.generate_dose_events(now(), '48 hours', '60 minutes', array['d5600000-0000-0000-0000-0000000000d1']::uuid[]); end $g$;
select is((select count(*)::int from public.dose_events de join public.medicine_schedules s on s.id = de.schedule_id
            where s.medicine_id = (select v from t_ar where k = 'stop') and de.status = 'pending'), 0, 'the scheduler makes no new doses for it');
select ok((select count(*) from public.dose_events de join public.medicine_schedules s on s.id = de.schedule_id
            where s.medicine_id = (select v from t_ar where k = 'keep') and de.status = 'pending') > 0, 'the other medicine still gets its doses');

-- no low-stock alert for an archived medicine
update public.medicines set stock_count = 0 where id = (select v from t_ar where k = 'stop');
select is((select count(*)::int from public.notifications
            where type in ('refill_needed', 'out_of_stock') and payload ->> 'medicine_id' = (select v from t_ar where k = 'stop')::text), 0,
  'no refill or out-of-stock alert for an archived medicine');

-- the emergency card shows only the current medicine
set local role anon;
select is((select jsonb_agg(m ->> 'name') from jsonb_array_elements(public.public_emergency_card((select v from t_tok where k = 'card')) -> 'medicines') m),
  '["Telma"]'::jsonb, 'the emergency card leaves archived medicines out');
reset role;

-- ============ plan limit: archived medicines do not count
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a5600000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select lives_ok($$insert into t_ar select 'third', public.create_medicine('d5600000-0000-0000-0000-0000000000d1', 'Vitamin D', null, null, null, 10, 2, array['09:00']::time[])$$,
  'with one archived, a new medicine fits in the Free limit');
select throws_ok($$update public.medicines set archived_at = null, active = true where id = (select v from t_ar where k = 'stop')$$,
  'P0001', null, 'restoring is refused when it would go over the plan limit');
update public.medicines set archived_at = now(), active = false where id = (select v from t_ar where k = 'third');
select lives_ok($$update public.medicines set archived_at = null, active = true where id = (select v from t_ar where k = 'stop')$$,
  'once there is room again the medicine can be restored');
reset role;
select is((select active and archived_at is null from public.medicines where id = (select v from t_ar where k = 'stop')), true, 'restored = active again');
do $g$ begin perform public.generate_dose_events(now(), '48 hours', '60 minutes', array['d5600000-0000-0000-0000-0000000000d1']::uuid[]); end $g$;
select ok((select count(*) from public.dose_events de join public.medicine_schedules s on s.id = de.schedule_id
            where s.medicine_id = (select v from t_ar where k = 'stop') and de.status = 'pending') > 0, 'a restored medicine gets reminders again');
select is((select (plan_overview_count) from (select count(*)::int as plan_overview_count from public.paused_medicines() pm
            join public.medicines m on m.id = pm.medicine_id where m.recipient_id = 'd5600000-0000-0000-0000-0000000000d1') x), 0,
  'nothing is paused by the plan: the archived one does not count');

-- ============ a stranger cannot archive (the update matches no row)
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"b5600000-0000-0000-0000-000000000002","role":"authenticated"}', true);
update public.medicines set archived_at = now(), active = false where id = (select v from t_ar where k = 'keep');
reset role;
select is((select archived_at from public.medicines where id = (select v from t_ar where k = 'keep')), null, 'a stranger cannot archive a medicine');

select * from finish();
rollback;
