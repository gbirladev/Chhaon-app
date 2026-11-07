-- Referral codes for everyone (migration 20261107000061, docs/referral-codes.md). Run with: npx supabase test db
begin;
create extension if not exists pgtap with schema extensions;
select plan(13);

insert into auth.users (id, email, instance_id, aud, role) values
  ('a6100000-0000-0000-0000-000000000001', 'rc-admin@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('a6100000-0000-0000-0000-000000000002', 'rc-ananya@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated'),
  ('a6100000-0000-0000-0000-000000000003', 'rc-friend@test.dev', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated');
insert into public.profiles (id, name) values
  ('a6100000-0000-0000-0000-000000000001', 'RC Admin'), ('a6100000-0000-0000-0000-000000000002', 'Ananya Test'),
  ('a6100000-0000-0000-0000-000000000003', 'Friend Test')
  on conflict (id) do update set name = excluded.name;
update public.profiles set is_admin = true where id = 'a6100000-0000-0000-0000-000000000001';

-- 1-2 the table and its privacy
select has_table('public', 'referral_codes', 'referral_codes exists');
select is((select relrowsecurity from pg_class where oid = 'public.referral_codes'::regclass), true, 'row level security is on');

-- 3-7 the format rules
select lives_ok($$insert into public.referral_codes (code, owner) values ('ANANYA27', 'a6100000-0000-0000-0000-000000000002')$$,
  'a normal code is accepted');
select throws_ok($$insert into public.referral_codes (code, owner, is_primary) values ('ana', 'a6100000-0000-0000-0000-000000000003', false)$$,
  '23514', null, 'lower case or too short is refused');
select throws_ok($$insert into public.referral_codes (code, owner, is_primary) values ('ANA-27', 'a6100000-0000-0000-0000-000000000003', false)$$,
  '23514', null, 'symbols are refused');
select throws_ok($$insert into public.referral_codes (code, owner, is_primary) values ('CRE022', 'a6100000-0000-0000-0000-000000000003', false)$$,
  '23514', null, 'the campaign pattern (creator / doctor codes) is refused');
select throws_ok($$insert into public.referral_codes (code, owner) values ('ANANYA27', 'a6100000-0000-0000-0000-000000000003')$$,
  '23505', null, 'a code belongs to one person');

-- 8-9 one primary code per person, old codes kept as aliases
select throws_ok($$insert into public.referral_codes (code, owner) values ('ANANYA99', 'a6100000-0000-0000-0000-000000000002')$$,
  '23505', null, 'only one primary code per person');
update public.referral_codes set is_primary = false where owner = 'a6100000-0000-0000-0000-000000000002';
insert into public.referral_codes (code, owner) values ('FITANANYA', 'a6100000-0000-0000-0000-000000000002');
select is((select count(*)::int from public.referral_codes where owner = 'a6100000-0000-0000-0000-000000000002'), 2,
  'the old code stays as an alias after a change');

-- 10 nobody reads the table through Supabase
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a6100000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select throws_ok($$select code from public.referral_codes$$, '42501', null, 'signed-in users cannot read the codes, not even their own');
reset role;
select set_config('request.jwt.claims', '', true);

-- 11-12 the admin's counts name the owner of a user code (and not of a campaign code)
update public.profiles set referred_by = 'ANANYA27' where id = 'a6100000-0000-0000-0000-000000000003';
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"a6100000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select is((select e->>'owner' from jsonb_array_elements(public.admin_referral_stats()->'by_code') e where e->>'code' = 'ANANYA27'),
  'Ananya Test', 'the admin sees who owns a user code');
select ok(not exists (select 1 from jsonb_array_elements(public.admin_referral_stats()->'by_code') e
                       where e->>'code' ~ '^[A-Z]{1,4}[0-9]{3,4}$' and e->>'owner' is not null),
  'campaign codes have no owner');
reset role;
select set_config('request.jwt.claims', '', true);

-- 13 a deleted account takes its codes with it
delete from public.profiles where id = 'a6100000-0000-0000-0000-000000000002';
select is((select count(*)::int from public.referral_codes where owner = 'a6100000-0000-0000-0000-000000000002'), 0,
  'codes go when the account is deleted');

select * from finish();
rollback;
