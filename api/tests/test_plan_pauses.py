"""Reminders above the plan's limits are paused (migration 0027) and resume when the plan allows them again.
Parent lives in Asia/Kolkata (UTC+5:30); every medicine is at 08:00."""

from datetime import UTC, datetime

from app.services import scheduler
from tests.conftest import requires_db
from tests.test_qa_fixes import make_medicine

pytestmark = requires_db

NOW = datetime(2026, 10, 10, 2, 31, tzinfo=UTC)  # 08:01 in India: the 08:00 dose is due


def old_schedules(db, recipient_id):
    ids = [m["id"] for m in db.table("medicines").select("id").eq("recipient_id", recipient_id).execute().data]
    db.table("medicine_schedules").update({"created_at": "2026-10-01T00:00:00+00:00", "start_date": "2026-01-01"}).in_("medicine_id", ids).execute()


def consent(db, recipient_id):
    db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00"}).eq("id", recipient_id).execute()


def set_plan(db, user_id, plan, trial_ended=True):
    db.table("profiles").update({"plan": plan, "trial_ends_at": "2020-01-01T00:00:00+00:00" if trial_ended else "2099-01-01T00:00:00+00:00"}).eq("id", user_id).execute()


def todays_doses(db, recipient_id):
    rows = (
        db.table("dose_events").select("status, scheduled_at, medicine_schedules(medicines(name, recipient_id))")
        .gte("scheduled_at", "2026-10-10T00:00:00+00:00").lt("scheduled_at", "2026-10-11T00:00:00+00:00").execute().data
    )
    return sorted(r["medicine_schedules"]["medicines"]["name"] for r in rows if r["medicine_schedules"]["medicines"]["recipient_id"] == recipient_id)


def test_medicines_above_the_free_limit_are_paused_and_resume_on_upgrade(db, family, messenger, settings):
    # three medicines while the account is big, then the account drops to Free (2 medicines per parent)
    make_medicine(db, family, "Second")
    make_medicine(db, family, "Third")
    old_schedules(db, family.recipient_id)
    consent(db, family.recipient_id)

    set_plan(db, family.user_id, "free")
    scheduler.run_tick(db, messenger, settings, NOW)
    assert todays_doses(db, family.recipient_id) == ["Metformin", "Second"]          # the newest one is paused
    assert len([t for _, t in messenger.templates if t.name.startswith("carelink_dose_reminder")]) == 2   # and only two reminders went out

    paused = db.rpc("paused_medicines").execute().data
    third = db.table("medicines").select("id").eq("recipient_id", family.recipient_id).eq("name", "Third").single().execute().data["id"]
    assert [p["medicine_id"] for p in paused if p["medicine_id"] == third] == [third]

    set_plan(db, family.user_id, "family")                                            # upgrading resumes it on the next tick
    scheduler.run_tick(db, messenger, settings, NOW)
    assert todays_doses(db, family.recipient_id) == ["Metformin", "Second", "Third"]


def test_pending_doses_of_a_newly_paused_medicine_are_removed(db, family, messenger, settings):
    make_medicine(db, family, "Second")
    make_medicine(db, family, "Third")
    old_schedules(db, family.recipient_id)
    consent(db, family.recipient_id)
    set_plan(db, family.user_id, "family")
    scheduler.run_tick(db, messenger, settings, datetime(2026, 10, 10, 1, 30, tzinfo=UTC))   # 07:00: all three are created, still pending
    assert todays_doses(db, family.recipient_id) == ["Metformin", "Second", "Third"]

    set_plan(db, family.user_id, "free")                                                      # the trial ends before 08:00
    scheduler.run_tick(db, messenger, settings, datetime(2026, 10, 10, 1, 40, tzinfo=UTC))
    assert todays_doses(db, family.recipient_id) == ["Metformin", "Second"]


def test_medicines_paused_by_the_family_do_not_use_up_the_limit(db, family, messenger, settings):
    make_medicine(db, family, "Second")
    make_medicine(db, family, "Third")
    old_schedules(db, family.recipient_id)
    consent(db, family.recipient_id)
    set_plan(db, family.user_id, "family")
    db.table("medicines").update({"active": False}).eq("recipient_id", family.recipient_id).eq("name", "Metformin").execute()
    set_plan(db, family.user_id, "free")
    scheduler.run_tick(db, messenger, settings, NOW)
    assert todays_doses(db, family.recipient_id) == ["Second", "Third"]                       # 2 active medicines fit the Free limit


def test_a_second_parent_is_paused_on_free(db, family, messenger, settings):
    set_plan(db, family.user_id, "family")
    second = db.table("care_recipients").insert({
        "circle_id": db.table("care_circles").insert({"created_by": family.user_id}).execute().data[0]["id"],
        "name": "Mummy", "relation": "mother", "language": "en", "whatsapp_phone": "+919810099999",
    }).execute().data[0]
    scheduler.DEFAULT_SCOPE = {family.recipient_id, second["id"]}
    try:
        med = make_medicine(db, type("F", (), {"recipient_id": second["id"]})(), "Mummy medicine")
        old_schedules(db, family.recipient_id)
        old_schedules(db, second["id"])
        consent(db, family.recipient_id)
        consent(db, second["id"])
        set_plan(db, family.user_id, "free")
        assert [p["recipient_id"] for p in db.rpc("paused_recipients").execute().data if p["recipient_id"] in (family.recipient_id, second["id"])] == [second["id"]]
        scheduler.run_tick(db, messenger, settings, NOW)
        assert todays_doses(db, family.recipient_id) == ["Metformin"]
        assert todays_doses(db, second["id"]) == []                                           # the newer parent gets nothing
        assert med
    finally:
        db.table("care_circles").delete().eq("id", second["circle_id"]).execute()


# ---------------------------------------------------------------- the other things a parent receives are paused too
def _second_parent(db, family):
    circle = db.table("care_circles").insert({"created_by": family.user_id}).execute().data[0]
    parent = db.table("care_recipients").insert({
        "circle_id": circle["id"], "name": "Mummy", "relation": "mother", "language": "en", "whatsapp_phone": "+919810099999",
    }).execute().data[0]
    scheduler.DEFAULT_SCOPE = {family.recipient_id, parent["id"]}
    return circle["id"], parent


def test_a_paused_parent_gets_no_daily_checkin_but_the_first_parent_does(db, family, messenger, settings):
    set_plan(db, family.user_id, "family")
    circle_id, second = _second_parent(db, family)
    try:
        consent(db, family.recipient_id)
        consent(db, second["id"])
        set_plan(db, family.user_id, "free")
        scheduler.run_tick(db, messenger, settings, datetime(2026, 10, 10, 2, 35, tzinfo=UTC))   # 08:05 IST: check-in time
        sent_to = sorted(to for to, t in messenger.templates if t.name == "carelink_daily_checkin")
        assert sent_to == ["+919810012345"]                                                       # only the first parent
        assert db.table("checkins").select("id").eq("recipient_id", second["id"]).execute().data == []

        set_plan(db, family.user_id, "family")                                                    # upgrading brings the check-in back
        scheduler.run_tick(db, messenger, settings, datetime(2026, 10, 10, 2, 40, tzinfo=UTC))
        assert sorted(to for to, t in messenger.templates if t.name == "carelink_daily_checkin") == ["+919810012345", "+919810099999"]
    finally:
        db.table("care_circles").delete().eq("id", circle_id).execute()


def test_a_paused_parent_gets_no_appointment_reminder(db, family, messenger, settings):
    from tests.test_appointments import EVENING, VISIT

    set_plan(db, family.user_id, "family")
    circle_id, second = _second_parent(db, family)
    try:
        consent(db, second["id"])
        db.table("appointments").insert({"recipient_id": second["id"], "doctor_name": "Dr. Rao", "datetime": VISIT.isoformat(), "created_at": "2026-10-01T00:00:00+00:00"}).execute()
        set_plan(db, family.user_id, "free")
        scheduler.run_tick(db, messenger, settings, EVENING)
        assert [to for to, t in messenger.templates if to == "+919810099999"] == []              # nothing for the paused parent

        set_plan(db, family.user_id, "family")
        scheduler.run_tick(db, messenger, settings, EVENING)
        assert len([to for to, t in messenger.templates if to == "+919810099999" and t.name == "carelink_appointment_reminder"]) == 1
    finally:
        db.table("care_circles").delete().eq("id", circle_id).execute()
