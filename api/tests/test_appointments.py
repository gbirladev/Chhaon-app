"""Appointment reminders: parent's clock, once each, consent, retries, caregiver alert."""

from datetime import UTC, datetime, timedelta

import pytest

from app.services.appointments import when_text
from app.services.scheduler import run_tick as _run_tick
from tests.conftest import PARENT_PHONE, requires_db

CAREGIVER_PHONE = "+447700900123"
VISIT = datetime(2026, 10, 12, 5, 30, tzinfo=UTC)          # Mon 11:00 IST
EVENING = datetime(2026, 10, 11, 12, 30, tzinfo=UTC)       # Sun 18:00 IST
MORNING = datetime(2026, 10, 12, 2, 0, tzinfo=UTC)         # Mon 07:30 IST


def run_tick(*args, **kwargs):
    result = _run_tick(*args, **kwargs)
    assert "step_errors" not in result, f"a scheduler step failed: {result}"
    return result


def setup(db, family, consented=True, visit=VISIT, **fields):
    if consented:
        db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.recipient_id).execute()
    # booked long before the fixed test clock, so the "booked late" rule only applies in the tests that ask for it
    row = {"recipient_id": family.recipient_id, "doctor_name": "Dr. Mehta", "datetime": visit.isoformat(), "created_at": "2026-10-01T00:00:00+00:00"} | fields
    return db.table("appointments").insert(row).execute().data[0]["id"]


def appt(db, appt_id):
    return db.table("appointments").select("*").eq("id", appt_id).single().execute().data


def to_parent(messenger):
    return [t for to, t in messenger.templates if to == PARENT_PHONE and t.name == "carelink_appointment_reminder"]


def texts(template):
    return [p["text"] for p in template.components[0]["parameters"]]


def bell(db, family):
    return db.table("notifications").select("payload").eq("user_id", family.user_id).eq("type", "appointment_reminder").execute().data


# ---------------------------------------------------------------- wording (no database)
IST = datetime(2026, 10, 11, 18, 0, tzinfo=UTC).astimezone(__import__("zoneinfo").ZoneInfo("Asia/Kolkata"))


@pytest.mark.parametrize("language, visit, expected", [
    ("en", VISIT, "tomorrow at 11:00 AM"),
    ("hi", VISIT, "कल सुबह 11:00"),
    ("en", datetime(2026, 10, 11, 12, 30, tzinfo=UTC), "today at 6:00 PM"),
    ("hi", datetime(2026, 10, 11, 14, 0, tzinfo=UTC), "आज शाम 7:30"),
    ("en", datetime(2026, 10, 14, 5, 30, tzinfo=UTC), "on 14 Oct at 11:00 AM"),
    ("hi", datetime(2026, 10, 14, 5, 30, tzinfo=UTC), "14 अक्टूबर को सुबह 11:00"),
])
def test_when_text(language, visit, expected):
    assert when_text(visit, IST.replace(hour=18), language) == expected


def test_when_text_uses_the_parents_calendar_day():
    from zoneinfo import ZoneInfo
    new_york = datetime(2026, 10, 12, 1, 0, tzinfo=UTC).astimezone(ZoneInfo("America/New_York"))   # Sun 21:00 EDT
    assert when_text(datetime(2026, 10, 12, 15, 0, tzinfo=UTC), new_york, "en") == "tomorrow at 11:00 AM"


# ---------------------------------------------------------------- reminders
@requires_db
def test_day_before_reminder_at_6pm_parent_time_once(db, family, messenger, settings):
    a = setup(db, family)
    run_tick(db, messenger, settings, EVENING - timedelta(minutes=5))
    assert to_parent(messenger) == []
    result = run_tick(db, messenger, settings, EVENING)
    assert result["appointment_reminders"] == 1
    (template,) = to_parent(messenger)
    assert template.language == "hi" and texts(template) == ["पापा", "Dr. Mehta", "कल सुबह 11:00"]
    assert appt(db, a)["reminded_day_before_at"] and appt(db, a)["reminded_morning_at"] is None
    (note,) = bell(db, family)
    assert note["payload"] | {"doctor": 0, "when_en": 0, "when_hi": 0} == {"recipient_id": family.recipient_id, "appointment_id": a, "kind": "day_before", "doctor": 0, "when_en": 0, "when_hi": 0}
    run_tick(db, messenger, settings, EVENING + timedelta(minutes=5))
    run_tick(db, messenger, settings, EVENING + timedelta(hours=3))
    assert len(to_parent(messenger)) == 1 and len(bell(db, family)) == 1       # no duplicate on later ticks


@requires_db
def test_morning_reminder_at_730_after_the_evening_one(db, family, messenger, settings):
    a = setup(db, family)
    run_tick(db, messenger, settings, EVENING)
    run_tick(db, messenger, settings, MORNING - timedelta(minutes=5))
    assert len(to_parent(messenger)) == 1
    run_tick(db, messenger, settings, MORNING)
    run_tick(db, messenger, settings, MORNING + timedelta(minutes=5))
    assert [texts(t)[2] for t in to_parent(messenger)] == ["कल सुबह 11:00", "आज सुबह 11:00"]
    assert appt(db, a)["reminded_morning_at"]
    assert sorted(n["payload"]["kind"] for n in bell(db, family)) == ["day_before", "morning"]


@requires_db
def test_late_tick_in_the_morning_sends_only_the_morning_reminder(db, family, messenger, settings):
    setup(db, family)
    run_tick(db, messenger, settings, MORNING + timedelta(minutes=10))
    assert [texts(t)[2] for t in to_parent(messenger)] == ["आज सुबह 11:00"]


@requires_db
def test_booked_the_same_morning_after_730_is_reminded_at_once(db, family, messenger, settings):
    run_tick(db, messenger, settings, MORNING + timedelta(minutes=90))   # 09:00 IST, nothing booked yet
    setup(db, family)
    run_tick(db, messenger, settings, MORNING + timedelta(minutes=95))
    assert len(to_parent(messenger)) == 1


@requires_db
def test_morning_reminder_needs_the_visit_to_be_30_minutes_away(db, family, messenger, settings):
    setup(db, family)
    run_tick(db, messenger, settings, VISIT - timedelta(minutes=25))
    assert to_parent(messenger) == []
    run_tick(db, messenger, settings, VISIT - timedelta(minutes=31))
    assert len(to_parent(messenger)) == 1


@requires_db
def test_early_visit_gets_no_morning_reminder_but_the_evening_one_still_goes(db, family, messenger, settings):
    setup(db, family, visit=datetime(2026, 10, 12, 2, 15, tzinfo=UTC))   # 07:45 IST
    run_tick(db, messenger, settings, EVENING)
    assert len(to_parent(messenger)) == 1
    run_tick(db, messenger, settings, MORNING)
    assert len(to_parent(messenger)) == 1


@requires_db
def test_visit_the_same_evening_gets_only_a_same_day_reminder(db, family, messenger, settings):
    a = setup(db, family, visit=datetime(2026, 10, 11, 14, 0, tzinfo=UTC))   # 19:30 IST, booked after 7:30 that day
    run_tick(db, messenger, settings, EVENING)
    assert [texts(t)[2] for t in to_parent(messenger)] == ["आज शाम 7:30"]
    assert appt(db, a)["reminded_day_before_at"] is None and appt(db, a)["reminded_morning_at"]


@requires_db
def test_parent_in_another_timezone(db, family, messenger, settings):
    db.table("care_recipients").update({"timezone": "America/New_York", "language": "en"}).eq("id", family.recipient_id).execute()
    setup(db, family, visit=datetime(2026, 10, 12, 15, 0, tzinfo=UTC))   # Mon 11:00 EDT
    run_tick(db, messenger, settings, datetime(2026, 10, 11, 21, 59, tzinfo=UTC))   # 17:59 EDT
    assert to_parent(messenger) == []
    run_tick(db, messenger, settings, datetime(2026, 10, 11, 22, 0, tzinfo=UTC))    # 18:00 EDT
    (template,) = to_parent(messenger)
    assert template.language == "en" and texts(template)[2] == "tomorrow at 11:00 AM"


@requires_db
def test_past_appointment_and_remind_false_are_ignored(db, family, messenger, settings):
    setup(db, family, visit=datetime(2026, 10, 11, 3, 0, tzinfo=UTC))   # 08:30 IST today: over by the evening tick
    b = setup(db, family, remind=False)
    run_tick(db, messenger, settings, EVENING)
    assert to_parent(messenger) == []
    run_tick(db, messenger, settings, MORNING)
    assert to_parent(messenger) == [] and appt(db, b)["reminded_day_before_at"] is None and bell(db, family) == []


@requires_db
def test_moving_the_visit_starts_the_reminders_again(db, family, messenger, settings):
    a = setup(db, family)
    run_tick(db, messenger, settings, EVENING)
    db.table("appointments").update({"datetime": (VISIT + timedelta(days=1)).isoformat()}).eq("id", a).execute()
    assert appt(db, a)["reminded_day_before_at"] is None
    run_tick(db, messenger, settings, EVENING + timedelta(days=1))
    assert len(to_parent(messenger)) == 2


# ---------------------------------------------------------------- consent and failures
@requires_db
def test_no_consent_sends_nothing_and_claims_nothing(db, family, messenger, settings):
    a = setup(db, family, consented=False)
    run_tick(db, messenger, settings, EVENING)
    assert to_parent(messenger) == [] and appt(db, a)["reminded_day_before_at"] is None
    db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.recipient_id).execute()
    run_tick(db, messenger, settings, EVENING + timedelta(minutes=5))      # agreed since: now it goes
    assert len(to_parent(messenger)) == 1


@requires_db
def test_stop_on_the_record_or_the_phone_sends_nothing(db, family, messenger, settings):
    a = setup(db, family)
    db.table("care_recipients").update({"opted_out_at": "2026-10-05T00:00:00+00:00"}).eq("id", family.recipient_id).execute()
    run_tick(db, messenger, settings, EVENING)
    assert to_parent(messenger) == [] and appt(db, a)["reminded_day_before_at"] is None and bell(db, family) == []
    db.table("care_recipients").update({"opted_out_at": None}).eq("id", family.recipient_id).execute()
    db.table("phone_opt_outs").insert({"phone": PARENT_PHONE}).execute()
    run_tick(db, messenger, settings, EVENING)
    assert to_parent(messenger) == [] and appt(db, a)["reminded_day_before_at"] is None


@requires_db
def test_failed_send_is_handed_back_and_retried_until_it_goes(db, family, messenger, settings):
    a = setup(db, family)
    messenger.fail = True
    result = run_tick(db, messenger, settings, EVENING)
    assert result["send_errors"] == 1 and appt(db, a)["reminded_day_before_at"] is None and bell(db, family) == []
    messenger.fail = False
    run_tick(db, messenger, settings, EVENING + timedelta(minutes=5))
    assert len(to_parent(messenger)) == 1 and appt(db, a)["reminded_day_before_at"]


@requires_db
def test_scoped_tick_leaves_other_parents_alone(db, family, messenger, settings):
    a = setup(db, family)
    run_tick(db, messenger, settings, EVENING, only_recipients=["00000000-0000-0000-0000-000000000000"])
    assert to_parent(messenger) == [] and appt(db, a)["reminded_day_before_at"] is None


# ---------------------------------------------------------------- caregiver alert
@requires_db
def test_caregiver_gets_a_whatsapp_alert_in_their_language_on_the_parents_clock(db, family, messenger, settings):
    db.table("profiles").update({"alert_phone": CAREGIVER_PHONE, "alert_consent_at": "2026-10-01T00:00:00+00:00",
                                 "alert_confirmed_at": "2026-10-01T00:00:00+00:00", "language": "en"}).eq("id", family.user_id).execute()
    db.table("phone_opt_outs").delete().eq("phone", CAREGIVER_PHONE).execute()
    setup(db, family)
    result = run_tick(db, messenger, settings, EVENING)
    assert result["appointment_reminders"] == 1 and result["alerts_sent"] == 1
    (template,) = [t for to, t in messenger.templates if to == CAREGIVER_PHONE]
    assert template.name == "carelink_alert_appointment" and template.language == "en"
    assert texts(template) == ["पापा", "Dr. Mehta", "tomorrow at 11:00 AM"]


@requires_db
def test_doctor_name_is_cleaned(db, family, messenger, settings):
    setup(db, family, doctor_name="Dr.\nMehta" + "x" * 80)
    run_tick(db, messenger, settings, EVENING)
    doctor = texts(to_parent(messenger)[0])[1]
    assert "\n" not in doctor and len(doctor) <= 40
