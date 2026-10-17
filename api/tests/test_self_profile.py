"""The caregiver's own profile ("Me", docs/self-profile.md): medicine reminders on their own WhatsApp, nothing else.
Lives in Asia/Kolkata (UTC+5:30); the medicine is at 08:00."""

from datetime import UTC, datetime, timedelta

import pytest

from app.services import love, scheduler
from app.whatsapp import templates
from tests.conftest import button, inbound, post_webhook, requires_db, wamid

pytestmark = requires_db

SELF_PHONE = "+919810099999"
NOW = datetime(2026, 10, 10, 2, 31, tzinfo=UTC)   # 08:01 IST
MIN = timedelta(minutes=1)


def run_tick(db, messenger, settings, now):
    result = scheduler.run_tick(db, messenger, settings, now)
    assert "step_errors" not in result, f"a scheduler step failed: {result}"
    return result


@pytest.fixture
def me(db, family):
    """The caregiver's own profile in its own private circle, with one medicine at 08:00. Removed afterwards."""
    circle = db.table("care_circles").insert({"created_by": family.user_id}).execute().data[0]["id"]
    rid = db.table("care_recipients").insert({
        "circle_id": circle, "name": "Ananya Rao", "relation": "self", "is_self": True, "language": "en",
        "whatsapp_phone": SELF_PHONE, "consent_at": "2026-10-01T00:00:00+00:00", "checkin_time": "07:30",
    }).execute().data[0]["id"]
    med = db.table("medicines").insert({"recipient_id": rid, "name": "Thyronorm", "dosage": "50 mcg", "stock_count": 20}).execute().data[0]["id"]
    db.table("medicine_schedules").insert({"medicine_id": med, "time_of_day": "08:00", "start_date": "2026-01-01", "created_at": "2026-10-01T00:00:00+00:00"}).execute()
    scheduler.DEFAULT_SCOPE = {rid}
    db.table("consent_requests").delete().eq("phone", SELF_PHONE).execute()
    yield {"circle": circle, "id": rid, "medicine": med}
    scheduler.DEFAULT_SCOPE = None
    db.table("care_circles").delete().eq("id", circle).execute()
    db.table("phone_opt_outs").delete().eq("phone", SELF_PHONE).execute()
    db.table("consent_requests").delete().eq("phone", SELF_PHONE).execute()


def notes(db, family, rid, type_):
    rows = db.table("notifications").select("payload").eq("user_id", family.user_id).eq("type", type_).execute().data
    return [r for r in rows if r["payload"].get("recipient_id") == rid]


def test_the_own_number_is_asked_with_its_own_wording(db, family, me, client, messenger, as_caregiver, real_db_dep):
    db.table("care_recipients").update({"consent_at": None}).eq("id", me["id"]).execute()
    response = client.post(f"/recipients/{me['id']}/consent-request")
    assert response.status_code == 200 and response.json() == {"status": "sent"}
    ((to, template),) = messenger.templates
    assert to == SELF_PHONE and template.name == templates.SELF_OPTIN
    assert template.components[0]["parameters"][0]["text"] == "Ananya"              # the first name, not "Your family has set up ..."
    payloads = [c["parameters"][0]["payload"] for c in template.components if c["type"] == "button"]
    assert payloads == [f"consent:{me['id']}:yes", f"consent:{me['id']}:no"]


def test_yes_starts_the_reminders_without_telling_the_family_about_yourself(db, family, me, client, messenger, settings):
    db.table("care_recipients").update({"consent_at": None}).eq("id", me["id"]).execute()
    post_webhook(client, inbound(button(wamid(), f"consent:{me['id']}:yes", sender=SELF_PHONE)))
    assert db.table("care_recipients").select("consent_at").eq("id", me["id"]).single().execute().data["consent_at"]
    assert notes(db, family, me["id"], "parent_consented") == []
    ((to, reply),) = messenger.texts
    assert to == SELF_PHONE and "reminders" in reply


def test_the_reminder_goes_to_their_own_whatsapp(db, family, me, messenger, settings):
    run_tick(db, messenger, settings, NOW)
    ((to, template),) = [(t, x) for t, x in messenger.templates if x.name.startswith("carelink_dose_reminder")]
    assert to == SELF_PHONE
    assert template.components[0]["parameters"][0]["text"] == "Ananya Rao"            # greeted by name


def test_there_is_no_daily_check_in_for_yourself(db, family, me, messenger, settings):
    run_tick(db, messenger, settings, datetime(2026, 10, 10, 2, 5, tzinfo=UTC))      # 07:35 IST: after the check-in time
    assert [t for _, t in messenger.templates if t.name == templates.DAILY_CHECKIN] == []
    assert db.table("checkins").select("id").eq("recipient_id", me["id"]).execute().data == []


def test_a_missed_dose_does_not_alert_you_about_yourself(db, family, me, messenger, settings):
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, NOW + 61 * MIN)
    status = db.table("dose_events").select("status, scheduled_at, medicine_schedules(medicine_id)").eq("scheduled_at", "2026-10-10T02:30:00+00:00").execute().data
    mine = [d for d in status if d["medicine_schedules"]["medicine_id"] == me["medicine"]]
    assert [d["status"] for d in mine] == ["missed"]                                   # still recorded, so Today can show it
    assert notes(db, family, me["id"], "dose_missed") == []


def test_a_refill_alert_still_comes(db, family, me, client, messenger, settings):
    db.table("medicines").update({"stock_count": 2, "refill_threshold": 1}).eq("id", me["medicine"]).execute()
    run_tick(db, messenger, settings, NOW)
    dose = next(d for d in db.table("dose_events").select("id, medicine_schedules(medicine_id)").eq("scheduled_at", "2026-10-10T02:30:00+00:00").execute().data if d["medicine_schedules"]["medicine_id"] == me["medicine"])
    post_webhook(client, inbound(button(wamid(), f"dose:{dose['id']}:taken", sender=SELF_PHONE)))
    assert len(notes(db, family, me["id"], "refill_needed")) == 1


def test_the_escalation_ladder_never_nudges_you(db, family, me, messenger, settings):
    settings.escalation_enabled = True
    db.table("escalation_policies").insert({"recipient_id": me["id"], "enabled": True}).execute()
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, NOW + 16 * MIN)
    run_tick(db, messenger, settings, NOW + 46 * MIN)
    assert [t for _, t in messenger.templates if t.name == templates.PARENT_DOSE_NUDGE] == []
    assert db.table("escalations").select("id").eq("recipient_id", me["id"]).execute().data == []


def test_love_messages_are_never_active_for_yourself(db, family, me, settings):
    settings.love_loop_enabled = True
    db.table("love_settings").insert({"recipient_id": me["id"], "enabled": True}).execute()
    row = db.table("care_recipients").select("*").eq("id", me["id"]).single().execute().data
    assert love.love_active(db, settings, row) is None
