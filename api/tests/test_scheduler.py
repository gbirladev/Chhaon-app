"""The 5-minute tick, with a controlled clock. Parent lives in Asia/Kolkata (UTC+5:30); the medicine is at 08:00."""

from datetime import UTC, datetime, timedelta

from app.services import scheduler
from app.services.scheduler import run_tick as _run_tick
from tests.conftest import button, inbound, post_webhook, requires_db, wamid

pytestmark = requires_db

def run_tick(*args, **kwargs):
    """Same as the real tick, but a step that raised (and was swallowed) fails the test."""
    result = _run_tick(*args, **kwargs)
    if not kwargs.get("expect_errors"):
        assert "step_errors" not in result, f"a scheduler step failed: {result}"
    return result


NOW = datetime(2026, 10, 10, 2, 31, tzinfo=UTC)  # 08:01 in India: the 08:00 dose is due


def setup(db, family, consented=True):
    db.table("medicine_schedules").update({"created_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.schedule_id).execute()
    if consented:
        db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.recipient_id).execute()


def doses(db, family):
    return db.table("dose_events").select("*").eq("schedule_id", family.schedule_id).order("scheduled_at").execute().data


def alerts(db, family, type_):
    return db.table("notifications").select("payload").eq("user_id", family.user_id).eq("type", type_).execute().data


def checkins(db, family):
    return db.table("checkins").select("*").eq("recipient_id", family.recipient_id).execute().data


# ---------------------------------------------------------------- reminders
def test_due_reminder_is_created_and_sent_exactly_once(db, family, messenger, settings):
    setup(db, family)
    first = run_tick(db, messenger, settings, NOW)
    assert first["generated"] >= 1 and first["reminders_sent"] >= 1
    todays = [d for d in doses(db, family) if d["scheduled_at"].startswith("2026-10-10T02:30")]
    assert len(todays) == 1 and todays[0]["status"] == "sent" and todays[0]["sent_at"]

    sent_before = len(messenger.templates)
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, NOW + timedelta(minutes=5))
    assert len(messenger.templates) == sent_before  # more ticks never resend
    assert len([d for d in doses(db, family) if d["scheduled_at"].startswith("2026-10-10T02:30")]) == 1


def test_future_doses_are_created_but_not_sent_early(db, family, messenger, settings):
    setup(db, family)
    run_tick(db, messenger, settings, datetime(2026, 10, 10, 1, 30, tzinfo=UTC))  # 07:00 IST
    todays = [d for d in doses(db, family) if d["scheduled_at"].startswith("2026-10-10T02:30")]
    assert len(todays) == 1 and todays[0]["status"] == "pending"
    assert not any(to == "+919810012345" for to, _ in messenger.templates)


def test_parent_who_has_not_agreed_gets_nothing_and_it_is_not_a_missed_dose(db, family, messenger, settings):
    setup(db, family, consented=False)
    run_tick(db, messenger, settings, NOW)
    todays = [d for d in doses(db, family) if d["scheduled_at"].startswith("2026-10-10T02:30")]
    assert todays[0]["status"] == "skipped"
    assert not any(to == "+919810012345" for to, _ in messenger.templates)
    run_tick(db, messenger, settings, NOW + timedelta(hours=3))
    assert alerts(db, family, "dose_missed") == [] and alerts(db, family, "reminder_not_sent") == []


def test_paused_medicine_gets_no_reminder(db, family, messenger, settings):
    setup(db, family)
    db.table("medicines").update({"active": False}).eq("id", family.medicine_id).execute()
    run_tick(db, messenger, settings, NOW)
    assert doses(db, family) == []


# ---------------------------------------------------------------- missed doses
def test_unanswered_reminder_becomes_missed_after_the_window_and_alerts_once(db, family, messenger, settings):
    setup(db, family)
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, NOW + timedelta(minutes=59))
    assert alerts(db, family, "dose_missed") == []  # still inside the 60-minute window

    later = run_tick(db, messenger, settings, NOW + timedelta(minutes=61))
    assert later["doses_missed"] >= 1
    todays = [d for d in doses(db, family) if d["scheduled_at"].startswith("2026-10-10T02:30")]
    assert todays[0]["status"] == "missed"
    missed = alerts(db, family, "dose_missed")
    assert len(missed) == 1 and "phone" not in str(missed[0]["payload"])

    run_tick(db, messenger, settings, NOW + timedelta(minutes=70))
    assert len(alerts(db, family, "dose_missed")) == 1  # never alerts twice


def test_answered_reminder_is_never_marked_missed(client, db, family, messenger, settings):
    setup(db, family)
    run_tick(db, messenger, settings, NOW)
    dose = next(d for d in doses(db, family) if d["scheduled_at"].startswith("2026-10-10T02:30"))
    post_webhook(client, inbound(button(wamid(), f"dose:{dose['id']}:taken")))
    run_tick(db, messenger, settings, NOW + timedelta(hours=2))
    assert db.table("dose_events").select("status").eq("id", dose["id"]).single().execute().data["status"] == "taken"
    assert alerts(db, family, "dose_missed") == []


def test_reminder_that_keeps_failing_is_retried_then_the_caregiver_is_told(db, family, messenger, settings):
    setup(db, family)
    messenger.fail = True
    tick = run_tick(db, messenger, settings, NOW)
    assert tick["send_errors"] >= 1
    assert [d["status"] for d in doses(db, family) if d["scheduled_at"].startswith("2026-10-10T02:30")] == ["pending"]

    messenger.fail = False  # WhatsApp is back: the next tick delivers it
    run_tick(db, messenger, settings, NOW + timedelta(minutes=5))
    assert [d["status"] for d in doses(db, family) if d["scheduled_at"].startswith("2026-10-10T02:30")] == ["sent"]


def test_reminder_never_delivered_in_the_window_alerts_the_caregiver(db, family, messenger, settings):
    setup(db, family)
    messenger.fail = True
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, NOW + timedelta(minutes=65))  # window is over, still failing
    todays = [d for d in doses(db, family) if d["scheduled_at"].startswith("2026-10-10T02:30")]
    assert todays[0]["status"] == "skipped"
    assert len(alerts(db, family, "reminder_not_sent")) == 1
    assert alerts(db, family, "dose_missed") == []  # we never asked, so it is not "missed"


# ---------------------------------------------------------------- daily check-in
def test_checkin_goes_out_at_the_parents_morning_once_and_missed_after_three_hours(db, family, messenger, settings):
    setup(db, family)
    run_tick(db, messenger, settings, datetime(2026, 10, 10, 2, 0, tzinfo=UTC))  # 07:30 IST: too early
    assert checkins(db, family) == []

    run_tick(db, messenger, settings, datetime(2026, 10, 10, 2, 35, tzinfo=UTC))  # 08:05 IST
    assert [c["status"] for c in checkins(db, family)] == ["sent"]
    run_tick(db, messenger, settings, datetime(2026, 10, 10, 2, 40, tzinfo=UTC))
    assert len(checkins(db, family)) == 1 and len([t for _, t in messenger.templates if t.name == "carelink_daily_checkin"]) == 1

    run_tick(db, messenger, settings, datetime(2026, 10, 10, 5, 0, tzinfo=UTC))  # 10:30 IST: only ~2h25 later
    assert alerts(db, family, "checkin_missed") == []
    run_tick(db, messenger, settings, datetime(2026, 10, 10, 5, 40, tzinfo=UTC))  # 11:10 IST: over 3 hours
    assert [c["status"] for c in checkins(db, family)] == ["missed"]
    run_tick(db, messenger, settings, datetime(2026, 10, 10, 6, 0, tzinfo=UTC))
    assert len(alerts(db, family, "checkin_missed")) == 1


def test_no_good_morning_message_in_the_afternoon_after_downtime(db, family, messenger, settings):
    setup(db, family)
    run_tick(db, messenger, settings, datetime(2026, 10, 10, 9, 0, tzinfo=UTC))  # 14:30 IST
    assert checkins(db, family) == []


def test_checkin_respects_the_parents_timezone(db, family, messenger, settings):
    setup(db, family)
    db.table("care_recipients").update({"timezone": "America/New_York", "checkin_time": "08:00"}).eq("id", family.recipient_id).execute()
    run_tick(db, messenger, settings, datetime(2026, 10, 10, 2, 35, tzinfo=UTC))  # 22:35 the evening before in New York
    assert checkins(db, family) == []
    run_tick(db, messenger, settings, datetime(2026, 10, 10, 12, 5, tzinfo=UTC))  # 08:05 EDT
    assert len(checkins(db, family)) == 1


def test_no_checkin_without_consent_or_after_stop(db, family, messenger, settings):
    setup(db, family, consented=False)
    run_tick(db, messenger, settings, datetime(2026, 10, 10, 2, 35, tzinfo=UTC))
    assert checkins(db, family) == []
    db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00", "opted_out_at": "2026-10-05T00:00:00+00:00"}).eq("id", family.recipient_id).execute()
    run_tick(db, messenger, settings, datetime(2026, 10, 10, 2, 35, tzinfo=UTC))
    assert checkins(db, family) == []


# ---------------------------------------------------------------- resilience + endpoint
def test_one_broken_step_does_not_stop_the_rest_of_the_tick(db, family, messenger, settings, monkeypatch):
    setup(db, family)
    run_tick(db, messenger, settings, datetime(2026, 10, 10, 1, 30, tzinfo=UTC))  # dose created, pending

    def boom(*args, **kwargs):
        raise RuntimeError("database hiccup")

    monkeypatch.setattr(scheduler, "_generate", boom)
    result = _run_tick(db, messenger, settings, NOW)
    assert result["step_errors"] == 1 and result["reminders_sent"] >= 1


def test_cron_endpoint_needs_the_secret(client, family, settings, real_db_dep):
    settings.cron_secret = ""  # explicit: the dev .env may define one
    assert client.post("/cron/tick").status_code == 503  # no secret configured: refuses to run
    settings.cron_secret = "s3cret"
    assert client.post("/cron/tick").status_code == 403
    assert client.post("/cron/tick", headers={"X-Cron-Secret": "wrong"}).status_code == 403
    ok = client.post("/cron/tick", headers={"X-Cron-Secret": "s3cret"})
    assert ok.status_code == 200 and "generated" in ok.json()
