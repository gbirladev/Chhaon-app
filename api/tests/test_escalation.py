"""The escalation ladder, phase 1 (docs/escalation.md). Parent lives in Asia/Kolkata (UTC+5:30); the fixture medicine is at 08:00.
The reminder goes out at the first tick (NOW = 08:01 IST), and every step is measured from that moment."""

from datetime import UTC, datetime, timedelta

import pytest

from app.config import Settings
from app.services import escalation, scheduler
from app.whatsapp import templates
from tests.conftest import button, inbound, post_webhook, requires_db, wamid
from tests.test_qa_fixes import make_medicine

NOW = datetime(2026, 10, 10, 2, 31, tzinfo=UTC)   # 08:01 IST
MIN = timedelta(minutes=1)


def run_tick(*args, **kwargs):
    result = scheduler.run_tick(*args, **kwargs)
    assert "step_errors" not in result, f"a scheduler step failed: {result}"
    return result


def setup(db, family, settings, *, policy=None, extra=(), checkin_time="20:00", doses=True, server_switch=True):
    """The parent has agreed, escalation is on (server switch AND her own policy) and the check-in is out of the way unless asked."""
    settings.escalation_enabled = server_switch
    for name, time in extra:
        make_medicine(db, family, name, times=(time,), p_stock=10)
    ids = [m["id"] for m in db.table("medicines").select("id").eq("recipient_id", family.recipient_id).execute().data]
    db.table("medicine_schedules").update({"created_at": "2026-10-01T00:00:00+00:00", "start_date": "2026-01-01"}).in_("medicine_id", ids).execute()
    if not doses:
        db.table("medicines").update({"active": False}).eq("recipient_id", family.recipient_id).execute()
    db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00", "checkin_time": checkin_time}).eq("id", family.recipient_id).execute()
    db.table("escalation_policies").insert({"recipient_id": family.recipient_id, "enabled": True, **(policy or {})}).execute()


def at(minutes):
    return NOW + minutes * MIN


def sent(messenger, name):
    return [t for _, t in messenger.templates if t.name == name]


def notes(db, family, type_):
    return db.table("notifications").select("payload").eq("user_id", family.user_id).eq("type", type_).execute().data


def rows(db, family):
    return db.table("escalations").select("*").eq("recipient_id", family.recipient_id).order("base_at").execute().data


def doses(db, family):
    data = db.table("dose_events").select("id, status, scheduled_at, medicine_schedules(medicines(name, recipient_id))").execute().data
    return sorted((d for d in data if d["medicine_schedules"]["medicines"]["recipient_id"] == family.recipient_id), key=lambda d: d["scheduled_at"])


def tap(client, dose_id, action="taken"):
    return post_webhook(client, inbound(button(wamid(), f"dose:{dose_id}:{action}")))


# ---------------------------------------------------------------- off means off
pytestmark = requires_db


def test_with_the_server_switch_off_the_old_rule_still_applies(db, family, messenger, settings):
    setup(db, family, settings, server_switch=False)
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(16))
    assert sent(messenger, "carelink_parent_dose_nudge") == [] and rows(db, family) == []
    run_tick(db, messenger, settings, at(61))
    assert len(notes(db, family, "dose_missed")) == 1                        # the plain "missed after 60 minutes" alert


def test_a_parent_whose_policy_is_off_is_left_alone(db, family, messenger, settings):
    setup(db, family, settings, policy={"enabled": False})
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(16))
    assert sent(messenger, "carelink_parent_dose_nudge") == [] and rows(db, family) == []


# ---------------------------------------------------------------- nudge
def test_one_gentle_nudge_at_15_minutes_and_never_again(db, family, messenger, settings):
    setup(db, family, settings)
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(14))
    assert sent(messenger, "carelink_parent_dose_nudge") == []
    result = run_tick(db, messenger, settings, at(15))
    assert result["nudges_sent"] == 1
    (nudge,) = sent(messenger, "carelink_parent_dose_nudge")
    (button_,) = [c for c in nudge.components if c["type"] == "button"]
    assert templates.decode_payload(button_["parameters"][0]["payload"])[2] == "taken"   # one medicine: the plain Taken button
    for minutes in (16, 20, 30):
        run_tick(db, messenger, settings, at(minutes))
    assert len(sent(messenger, "carelink_parent_dose_nudge")) == 1
    assert rows(db, family)[0]["nudged_at"]


def test_a_nudge_for_several_medicines_uses_taken_all(db, family, messenger, settings):
    setup(db, family, settings, extra=[("Amlodipine", "08:00")])
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(15))
    (nudge,) = sent(messenger, "carelink_parent_dose_nudge")
    body = next(c for c in nudge.components if c["type"] == "body")["parameters"][1]["text"]
    assert "Metformin" in body and "Amlodipine" in body
    (button_,) = [c for c in nudge.components if c["type"] == "button"]
    assert templates.decode_payload(button_["parameters"][0]["payload"])[2] == "all"


def test_a_failed_nudge_is_handed_back_and_retried(db, family, messenger, settings):
    setup(db, family, settings)
    run_tick(db, messenger, settings, NOW)
    messenger.fail = True
    result = run_tick(db, messenger, settings, at(15))
    assert result["send_errors"] >= 1 and rows(db, family)[0]["nudged_at"] is None
    messenger.fail = False
    run_tick(db, messenger, settings, at(16))
    assert len(sent(messenger, "carelink_parent_dose_nudge")) == 1


def test_no_nudge_is_sent_late_after_the_alert_time(db, family, messenger, settings):
    setup(db, family, settings)
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(50))                                # the scheduler was down until the alert time had passed
    assert sent(messenger, "carelink_parent_dose_nudge") == [] and rows(db, family)[0]["decided_at"]


def test_nothing_is_nudged_or_alerted_about_yesterday(db, family, messenger, settings):
    setup(db, family, settings)
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(25 * 60))                           # a day-long outage
    assert sent(messenger, "carelink_parent_dose_nudge") == [] and notes(db, family, "dose_missed") == []
    assert [d["status"] for d in doses(db, family) if d["scheduled_at"].startswith("2026-10-10T02:30")] == ["missed"]   # but the record is right


# ---------------------------------------------------------------- the alert decision
def test_one_missed_dose_is_recorded_but_does_not_alert_the_family(db, family, messenger, settings):
    setup(db, family, settings)
    run_tick(db, messenger, settings, NOW)
    result = run_tick(db, messenger, settings, at(45))
    assert result["doses_missed"] == 1 and result["alerts_suppressed"] == 1
    assert notes(db, family, "dose_missed") == []
    (row,) = rows(db, family)
    assert row["decided_at"] and row["alerted_at"] is None
    assert [d["status"] for d in doses(db, family) if d["scheduled_at"].startswith("2026-10-10T02:30")] == ["missed"]


def test_two_unanswered_slots_in_a_row_alert_the_family(db, family, messenger, settings):
    setup(db, family, settings, extra=[("Evening", "09:00")])
    for minutes in (0, 45, 60, 75):                                           # 08:00 slot unanswered, then the 09:00 slot goes out
        run_tick(db, messenger, settings, at(minutes))
    run_tick(db, messenger, settings, at(104))
    assert notes(db, family, "dose_missed") == []                             # not yet: the 09:00 slot is still within its 45 minutes
    result = run_tick(db, messenger, settings, at(105))
    assert result["escalation_alerts"] == 1 and len(notes(db, family, "dose_missed")) == 1


def test_a_reply_in_between_resets_the_count(db, family, messenger, settings, client):
    setup(db, family, settings, extra=[("Evening", "09:00")])
    for minutes in (0, 45, 60):
        run_tick(db, messenger, settings, at(minutes))
    first = next(d for d in doses(db, family) if d["scheduled_at"].startswith("2026-10-10T02:30"))
    tap(client, first["id"])                                                  # a late tap on the first slot: she is reachable
    run_tick(db, messenger, settings, at(75))
    run_tick(db, messenger, settings, at(105))
    assert notes(db, family, "dose_missed") == []


def test_a_critical_medicine_alerts_on_the_first_miss(db, family, messenger, settings):
    setup(db, family, settings)
    db.table("medicines").update({"critical": True}).eq("id", family.medicine_id).execute()
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(45))
    (alert,) = notes(db, family, "dose_missed")
    assert alert["payload"]["medicine_id"] == family.medicine_id


def test_the_threshold_can_be_one(db, family, messenger, settings):
    setup(db, family, settings, policy={"alert_after_unanswered": 1})
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(45))
    assert len(notes(db, family, "dose_missed")) == 1


def test_several_medicines_of_one_slot_are_one_alert_with_a_count(db, family, messenger, settings):
    setup(db, family, settings, policy={"alert_after_unanswered": 1}, extra=[("Amlodipine", "08:00"), ("Atorvastatin", "08:00")])
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(45))
    (alert,) = notes(db, family, "dose_missed")
    assert alert["payload"]["count"] == 3


def test_two_ticks_at_the_same_time_alert_once(db, family, messenger, settings):
    setup(db, family, settings, policy={"alert_after_unanswered": 1})
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(45))
    run_tick(db, messenger, settings, at(45))
    run_tick(db, messenger, settings, at(50))
    assert len(notes(db, family, "dose_missed")) == 1


# ---------------------------------------------------------------- the daily check-in
def test_check_in_is_nudged_at_60_minutes_and_alerts_at_120_when_the_threshold_is_one(db, family, messenger, settings):
    setup(db, family, settings, policy={"alert_after_unanswered": 1}, checkin_time="08:00", doses=False)
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(59))
    assert sent(messenger, "carelink_parent_checkin_nudge_v2") == []
    run_tick(db, messenger, settings, at(60))
    (nudge,) = sent(messenger, "carelink_parent_checkin_nudge_v2")
    assert [templates.decode_payload(c["parameters"][0]["payload"])[2] for c in nudge.components if c["type"] == "button"] == ["ok", "call"]
    run_tick(db, messenger, settings, at(119))
    assert notes(db, family, "checkin_missed") == []
    run_tick(db, messenger, settings, at(120))
    assert len(notes(db, family, "checkin_missed")) == 1


def test_a_silent_check_in_plus_an_unanswered_dose_counts_as_two(db, family, messenger, settings):
    setup(db, family, settings, checkin_time="08:00")                         # threshold 2 (the default)
    run_tick(db, messenger, settings, NOW)                                    # the 08:00 dose reminder and the check-in go out together
    run_tick(db, messenger, settings, at(45))
    assert notes(db, family, "dose_missed") == []                             # the dose on its own is just one
    run_tick(db, messenger, settings, at(120))
    assert len(notes(db, family, "checkin_missed")) == 1 and notes(db, family, "dose_missed") == []


# ---------------------------------------------------------------- the parent answers
def test_a_reply_after_the_family_was_alerted_tells_them_once(db, family, messenger, settings, client):
    setup(db, family, settings, policy={"alert_after_unanswered": 1})
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(45))
    assert len(notes(db, family, "dose_missed")) == 1
    dose = doses(db, family)[0]
    tap(client, dose["id"])
    assert doses(db, family)[0]["status"] == "taken_late"
    run_tick(db, messenger, settings, at(50))
    run_tick(db, messenger, settings, at(55))
    assert len(notes(db, family, "parent_responded")) == 1
    (row,) = rows(db, family)
    assert row["resolved_by"] == "parent" and row["resolved_at"]


def test_a_reply_after_the_nudge_ends_it_quietly(db, family, messenger, settings, client):
    setup(db, family, settings, policy={"alert_after_unanswered": 1})
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(15))
    tap(client, doses(db, family)[0]["id"])
    run_tick(db, messenger, settings, at(20))
    run_tick(db, messenger, settings, at(50))
    assert notes(db, family, "dose_missed") == [] and notes(db, family, "parent_responded") == []   # nobody was alerted, so nobody is told
    assert rows(db, family)[0]["resolved_by"] == "parent"


def test_taken_all_stops_the_escalation_for_every_medicine(db, family, messenger, settings, client):
    setup(db, family, settings, policy={"alert_after_unanswered": 1}, extra=[("Amlodipine", "08:00")])
    settings.slot_reminders_enabled = True
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(15))
    tap(client, doses(db, family)[0]["id"], "all")
    run_tick(db, messenger, settings, at(50))
    assert notes(db, family, "dose_missed") == []
    assert {d["status"] for d in doses(db, family) if d["scheduled_at"].startswith("2026-10-10T02:30")} == {"taken"}   # (tomorrow's doses are already created)


def test_a_family_member_who_handled_it_stops_the_alert_but_the_record_stays_true(db, family, messenger, settings):
    setup(db, family, settings, policy={"alert_after_unanswered": 1})
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, at(15))
    db.table("escalations").update({"resolved_at": at(20).isoformat(), "resolved_by": "caregiver"}).eq("recipient_id", family.recipient_id).execute()
    run_tick(db, messenger, settings, at(50))
    assert notes(db, family, "dose_missed") == []
    assert [d["status"] for d in doses(db, family) if d["scheduled_at"].startswith("2026-10-10T02:30")] == ["missed"]


# ---------------------------------------------------------------- a parent who says nothing for days
def test_a_silent_parent_stops_being_nudged_and_the_family_hears_once(db, family, messenger, settings):
    setup(db, family, settings, policy={"silent_stop_days": 1, "alert_after_unanswered": 1})
    old = (NOW - timedelta(hours=26)).isoformat()
    db.table("dose_events").insert({"schedule_id": family.schedule_id, "scheduled_at": old, "status": "missed", "sent_at": old}).execute()
    for minutes in (0, 15, 45, 60):
        run_tick(db, messenger, settings, at(minutes))
    assert sent(messenger, "carelink_parent_dose_nudge") == [] and notes(db, family, "dose_missed") == []
    (note,) = notes(db, family, "parent_silent")
    assert note["payload"]["days"] == 1


# ---------------------------------------------------------------- the pure pieces
def prompt(kind, minutes_ago, responded=False, now=NOW):
    return {"kind": kind, "key": f"{kind}:{minutes_ago}", "base_at": now - minutes_ago * MIN, "responded": responded, "critical": False, "doses": []}


@pytest.mark.parametrize("answers,expected", [
    ([False], 1),
    ([False, False], 2),
    ([False, False, False], 3),
    ([True, False], 1),                  # she answered the earlier one: the run starts again
    ([False, True, False], 1),
    ([False, False, True, False, False], 2),
])
def test_unanswered_run_counts_back_to_the_last_answer(answers, expected):
    prompts = [prompt("dose", 600 - i * 100, responded=r) for i, r in enumerate(answers)]
    assert escalation.unanswered_run(prompts, len(prompts) - 1, lambda _k: {}, NOW) == expected


def test_an_earlier_prompt_that_has_not_had_time_to_be_answered_does_not_count():
    prompts = [prompt("dose", 10), prompt("dose", 1)]                         # the first was sent only 10 minutes ago: nudge time (15) not reached
    assert escalation.unanswered_run(prompts, 1, lambda _k: {}, NOW) == 1


def test_silent_needs_a_full_window_with_not_one_answer():
    policy = {"silent_stop_days": 3}
    day = 24 * 60
    assert escalation.is_silent([prompt("dose", 3 * day + 60), prompt("dose", 600)], policy, NOW)[0] is True
    assert escalation.is_silent([prompt("dose", 3 * day + 60), prompt("dose", 2 * day, responded=True), prompt("dose", 600)], policy, NOW)[0] is False
    assert escalation.is_silent([prompt("dose", 2 * day), prompt("dose", 600)], policy, NOW)[0] is False   # not prompted for 3 days yet
    assert escalation.is_silent([], policy, NOW)[0] is False


def test_the_new_alert_types_are_sent_to_the_family_on_whatsapp():
    assert templates.caregiver_alert("parent_responded", "en", ["Papa"]).name == "carelink_alert_parent_replied"
    assert templates.caregiver_alert("parent_silent", "hi", ["पापा", "3"]).name == "carelink_alert_parent_silent_v2"
    assert Settings(_env_file=None).escalation_enabled is False                          # off unless the server says so
