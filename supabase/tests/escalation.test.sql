-- Escalation ladder, phase 1 (migrations 0031 and 0032). Run with: npx supabase test db  (not alongside pytest)
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a6000000-0000-0000-0000-000000000001', 'esc-owner@test.dev',    '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('b6000000-0000-0000-0000-000000000002', 'esc-sibling@test.dev',  '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('c6000000-0000-0000-0000-000000000003', 'esc-stranger@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
insert into public.care_circles (id, created_by) values ('c0000000-0000-0000-0000-0000000000f1', 'a6000000-0000-0000-0000-000000000001');
insert into public.care_circle_members (circle_id, user_id, role) values ('c0000000-0000-0000-0000-0000000000f1', 'b6000000-0000-0000-0000-000000000002', 'member');
insert into public.care_recipients (id, circle_id, name, relation) values ('d0000000-0000-0000-0000-0000000000f1', 'c0000000-0000-0000-0000-0000000000f1', 'Papa', 'father');
insert into public.medicines (id, recipient_id, name, dosage) values
  ('e0000000-0000-0000-0000-0000000000f1', 'd0000000-0000-0000-0000-0000000000f1', 'Med A', '500 mg'),
  ('e0000000-0000-0000-0000-0000000000f2', 'd0000000-0000-0000-0000-0000000000f1', 'Med B', '1 tablet');
insert into public.escalations (id, recipient_id, kind, slot_key, base_at) values
  ('a7000000-0000-0000-0000-0000000000f1', 'd0000000-0000-0000-0000-0000000000f1', 'dose', 'dose:2026-10-10T02:30', now());

select ok('taken_late'::public.dose_status is not null, 'a late confirmation has its own status');

-- ============ the family sets up the policy
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"b6000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select lives_ok($$insert into public.escalation_policies (recipient_id) values ('d0000000-0000-0000-0000-0000000000f1')$$, 'a family member can create the policy');
select is((select enabled::text || ',' || dose_nudge_min || ',' || dose_alert_min || ',' || checkin_nudge_min || ',' || checkin_alert_min || ',' || alert_after_unanswered || ',' || silent_stop_days from public.escalation_policies), 'false,15,45,60,120,2,3', 'it starts OFF with the planned defaults');
select throws_ok($$update public.escalation_policies set dose_nudge_min = 50 where recipient_id = 'd0000000-0000-0000-0000-0000000000f1'$$, '23514', null, 'the nudge must come before the alert');
select throws_ok($$update public.escalation_policies set alert_after_unanswered = 4 where recipient_id = 'd0000000-0000-0000-0000-0000000000f1'$$, '23514', null, 'at most 3 unanswered prompts in a row');
select lives_ok($$update public.escalation_policies set enabled = true, alert_after_unanswered = 1 where recipient_id = 'd0000000-0000-0000-0000-0000000000f1'$$, 'valid changes are saved');

-- ============ other families see nothing and change nothing
select set_config('request.jwt.claims', '{"sub":"c6000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is((select count(*)::int from public.escalation_policies), 0, 'a stranger cannot see the policy');
select is((select count(*)::int from public.escalations), 0, 'or the escalations');
select throws_ok($$insert into public.escalation_policies (recipient_id) values ('d0000000-0000-0000-0000-0000000000f1') on conflict (recipient_id) do update set enabled = false$$, '42501', null, 'and cannot write a policy for someone else''s parent');

-- ============ escalations are written by the backend only
select set_config('request.jwt.claims', '{"sub":"b6000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select is((select count(*)::int from public.escalations), 1, 'a family member can read the escalations');
select throws_ok($$insert into public.escalations (recipient_id, kind, slot_key, base_at) values ('d0000000-0000-0000-0000-0000000000f1', 'dose', 'dose:fake', now())$$, '42501', null, 'but cannot write one');
update public.escalations set resolved_at = now() where id = 'a7000000-0000-0000-0000-0000000000f1';
select is((select resolved_at is null from public.escalations where id = 'a7000000-0000-0000-0000-0000000000f1'), true, 'or change one directly');

-- ============ "I've handled it"
select is(public.handle_escalation('a7000000-0000-0000-0000-0000000000f1'), true, 'a family member can mark it handled');
select is(public.handle_escalation('a7000000-0000-0000-0000-0000000000f1'), false, 'a second tap changes nothing');
select set_config('request.jwt.claims', '{"sub":"c6000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select throws_ok($$select public.handle_escalation('a7000000-0000-0000-0000-0000000000f1')$$, '42501', null, 'a stranger cannot');

-- ============ critical medicines
select set_config('request.jwt.claims', '{"sub":"b6000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select lives_ok($$select public.set_medicine_critical('e0000000-0000-0000-0000-0000000000f1', true)$$, 'a family member can mark a medicine critical');
select set_config('request.jwt.claims', '{"sub":"c6000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select throws_ok($$select public.set_medicine_critical('e0000000-0000-0000-0000-0000000000f2', true)$$, '42501', null, 'a stranger cannot');
reset role;
select set_config('request.jwt.claims', '', true);

select * from finish();
rollback;
