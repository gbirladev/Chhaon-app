-- Restore the function permissions the migrations intend.
--
-- WHY: on 2026-11-03 the Sydney database was found to let an anonymous visitor run 48 functions in the public schema; the migrations
-- allow 4 (has_control_chars, public_emergency_card, storage_recipient_id, text_array_ok). Five server-only functions
-- (erase_caregiver, claim_consent_request, confirm_dose_slot, consume_stock, generate_dose_events) are SECURITY DEFINER and do not check
-- who calls them, so they were open to anyone holding the public key. The GRANT / REVOKE statements were lost when the database was copied.
--
-- WHAT IT DOES: for every function below it removes all execute permission (from PUBLIC, anon, authenticated, service_role) and gives back
-- exactly the roles the correct database gives (generated from the local database built from the migrations). A function that does not exist
-- in the target database is skipped with a notice. Nothing is created, changed or deleted: only who may RUN each function.
-- It can be run again at any time. Trigger functions are not touched (they cannot be called directly).
--
-- AFTER NEW MIGRATIONS: Supabase gives every new function to anon, authenticated and service_role by default, so a migration that adds a
-- server-only function MUST revoke it (the migrations do). If this script is run again later it fixes any that were missed, but only for
-- the functions listed here: regenerate it after adding functions.

do $$
declare
  r record;
begin
  for r in select * from (values
    ('accept_family_invite(text)', array['authenticated','service_role']::text[]),
    ('adjust_stock(uuid,integer)', array['authenticated','service_role']::text[]),
    ('admin_feedback(integer)', array['authenticated','service_role']::text[]),
    ('admin_stats(integer)', array['authenticated','service_role']::text[]),
    ('can_access_document(uuid)', array['authenticated','service_role']::text[]),
    ('can_access_medicine(uuid)', array['authenticated','service_role']::text[]),
    ('can_access_recipient(uuid)', array['authenticated','service_role']::text[]),
    ('can_access_report(uuid)', array['authenticated','service_role']::text[]),
    ('can_access_schedule(uuid)', array['authenticated','service_role']::text[]),
    ('can_write_child_data(uuid)', array['authenticated','service_role']::text[]),
    ('card_clean(text,integer)', array['service_role']::text[]),
    ('check_medicine_input(uuid,text,text,text,time without time zone[])', array['authenticated','service_role']::text[]),
    ('claim_consent_request(text,uuid,interval,integer)', array['service_role']::text[]),
    ('confirm_dose_slot(uuid)', array['service_role']::text[]),
    ('consume_stock(uuid,integer)', array['service_role']::text[]),
    ('create_care_recipient(jsonb)', array['authenticated','service_role']::text[]),
    ('create_family_invite(uuid)', array['authenticated','service_role']::text[]),
    ('create_medicine(uuid,text,text,text,text,integer,integer,time without time zone[],date,date)', array['authenticated','service_role']::text[]),
    ('enable_emergency_link(uuid)', array['authenticated','service_role']::text[]),
    ('erase_caregiver(uuid)', array['service_role']::text[]),
    ('feedback_files_today()', array['authenticated','service_role']::text[]),
    ('generate_dose_events(timestamp with time zone,interval,interval,uuid[])', array['service_role']::text[]),
    ('handle_escalation(uuid)', array['authenticated','service_role']::text[]),
    ('has_control_chars(text)', array['anon','authenticated','service_role']::text[]),
    ('invite_hash(text)', array['service_role']::text[]),
    ('is_admin_user()', array['authenticated','service_role']::text[]),
    ('is_circle_member(uuid)', array['authenticated','service_role']::text[]),
    ('is_circle_owner(uuid)', array['authenticated','service_role']::text[]),
    ('leave_circle(uuid)', array['authenticated','service_role']::text[]),
    ('love_stats(integer)', array['authenticated','service_role']::text[]),
    ('paused_medicines()', array['service_role']::text[]),
    ('paused_recipients()', array['service_role']::text[]),
    ('plan_for_circle(uuid)', array['authenticated','service_role']::text[]),
    ('plan_of_user(uuid)', array['authenticated','service_role']::text[]),
    ('plan_overview()', array['authenticated','service_role']::text[]),
    ('plan_pauses()', array['authenticated','service_role']::text[]),
    ('preview_family_invite(text)', array['authenticated','service_role']::text[]),
    ('public_emergency_card(text)', array['anon','authenticated','service_role']::text[]),
    ('public_emergency_doctors(text)', array['anon','authenticated','service_role']::text[]),   -- 0059 "Your doctors"
    ('record_emergency_view(uuid)', array['service_role']::text[]),
    ('remove_circle_member(uuid,uuid)', array['authenticated','service_role']::text[]),
    ('revoke_emergency_link(uuid)', array['authenticated','service_role']::text[]),
    ('revoke_family_invite(uuid)', array['authenticated','service_role']::text[]),
    ('rotate_emergency_link(uuid)', array['authenticated','service_role']::text[]),
    ('send_reaction(uuid,text,uuid,text,text,text)', array['authenticated','service_role']::text[]),
    ('set_love_enabled(uuid,boolean)', array['authenticated','service_role']::text[]),
    ('set_medicine_critical(uuid,boolean)', array['authenticated','service_role']::text[]),
    ('set_parent_calls_me(uuid,text)', array['authenticated','service_role']::text[]),
    ('sole_member_recipients(uuid)', array['service_role']::text[]),
    ('storage_recipient_id(text)', array['anon','authenticated','service_role']::text[]),
    ('text_array_ok(text[],integer,integer)', array['anon','authenticated','service_role']::text[]),
    ('update_medicine(uuid,text,text,text,text,integer,time without time zone[])', array['authenticated','service_role']::text[]),
    ('wallet_usage(uuid)', array['authenticated','service_role']::text[])
  ) as t(sig, roles)
  loop
    begin
      execute format('revoke all on function %s from public, anon, authenticated, service_role', r.sig);
      if cardinality(r.roles) > 0 then
        execute format('grant execute on function %s to %s', r.sig, array_to_string(r.roles, ', '));
      end if;
    exception when undefined_function then
      raise notice 'skipped (not in this database): %', r.sig;
    end;
  end loop;
end $$;
