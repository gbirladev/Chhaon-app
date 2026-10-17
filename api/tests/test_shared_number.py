"""Two parents who share one phone (a couple with one WhatsApp). Both profiles use PARENT_PHONE; the fixture parent is Papa (08:00 medicine),
a second profile Mummy has her own medicine at 08:00."""

from datetime import UTC, datetime

import pytest

from app.services import messaging, scheduler
from app.whatsapp import templates
from tests.conftest import PARENT_PHONE, button, inbound, post_webhook, requires_db, text, wamid

pytestmark = requires_db
NOW = datetime(2026, 10, 10, 2, 31, tzinfo=UTC)   # 08:01 IST


@pytest.fixture
def mummy(db, family):
    circle = db.table("care_circles").insert({"created_by": family.user_id}).execute().data[0]["id"]
    rid = db.table("care_recipients").insert({
        "circle_id": circle, "name": "Sunita", "call_name": "मम्मी", "relation": "mother", "language": "hi", "whatsapp_phone": PARENT_PHONE,
    }).execute().data[0]["id"]
    med = db.table("medicines").insert({"recipient_id": rid, "name": "Calcium", "dosage": "1 tablet", "stock_count": 20}).execute().data[0]["id"]
    db.table("medicine_schedules").insert({"medicine_id": med, "time_of_day": "08:00", "start_date": "2026-01-01", "created_at": "2026-10-01T00:00:00+00:00"}).execute()
    scheduler.DEFAULT_SCOPE = scheduler.DEFAULT_SCOPE | {rid}
    yield {"id": rid, "circle": circle, "medicine": med}
    db.table("care_circles").delete().eq("id", circle).execute()


def agree(db, *ids):
    for i in ids:
        db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00"}).eq("id", i).execute()
    meds = [m["id"] for m in db.table("medicines").select("id").in_("recipient_id", list(ids)).execute().data]   # only THESE parents' medicines
    db.table("medicine_schedules").update({"created_at": "2026-10-01T00:00:00+00:00", "start_date": "2026-01-01"}).in_("medicine_id", meds).execute()


def test_both_can_agree_with_their_own_yes_tap(db, family, mummy, client, messenger):
    post_webhook(client, inbound(button(wamid(), f"consent:{family.recipient_id}:yes")))
    state = {r["id"]: r["consent_at"] for r in db.table("care_recipients").select("id, consent_at").in_("id", [family.recipient_id, mummy["id"]]).execute().data}
    assert state[family.recipient_id] and not state[mummy["id"]]                  # Papa's Yes is Papa's only
    post_webhook(client, inbound(button(wamid(), f"consent:{mummy['id']}:yes")))
    state = {r["id"]: r["consent_at"] for r in db.table("care_recipients").select("id, consent_at").in_("id", [family.recipient_id, mummy["id"]]).execute().data}
    assert state[family.recipient_id] and state[mummy["id"]]


def test_the_second_consent_request_to_the_same_number_has_to_wait(db, family, mummy, messenger, settings):
    first = messaging.send_consent_request(db, messenger, settings, family.recipient_id, family.user_id)
    second = messaging.send_consent_request(db, messenger, settings, mummy["id"], family.user_id)
    assert first == "sent" and second == "too_soon"     # one request per number per window, so nobody is spammed


def test_each_parent_gets_her_own_reminder_and_a_tap_confirms_only_that_dose(db, family, mummy, client, messenger, settings):
    agree(db, family.recipient_id, mummy["id"])
    result = scheduler.run_tick(db, messenger, settings, NOW)
    assert "step_errors" not in result
    sent = [t for to, t in messenger.templates if t.name.startswith("carelink_dose_reminder")]
    names = sorted(t.components[0]["parameters"][0]["text"] for t in sent)
    assert names == ["पापा", "मम्मी"]                                              # two messages, each addressed by name
    doses = db.table("dose_events").select("id, status, medicine_schedules(medicine_id)").eq("scheduled_at", "2026-10-10T02:30:00+00:00").execute().data
    papa = next(d for d in doses if d["medicine_schedules"]["medicine_id"] == family.medicine_id)
    post_webhook(client, inbound(button(wamid(), f"dose:{papa['id']}:taken")))
    after = {d["medicine_schedules"]["medicine_id"]: d["status"] for d in db.table("dose_events").select("id, status, medicine_schedules(medicine_id)").eq("scheduled_at", "2026-10-10T02:30:00+00:00").execute().data}
    assert after[family.medicine_id] == "taken" and after[mummy["medicine"]] == "sent"


def test_stop_from_the_shared_phone_stops_both(db, family, mummy, client, messenger):
    agree(db, family.recipient_id, mummy["id"])
    post_webhook(client, inbound(text(wamid(), "STOP")))
    rows = db.table("care_recipients").select("id, opted_out_at").in_("id", [family.recipient_id, mummy["id"]]).execute().data
    assert all(r["opted_out_at"] for r in rows)
    assert len(messenger.texts) == 1                                               # one polite reply, not two


def test_start_brings_both_back_with_one_reply(db, family, mummy, client, messenger):
    agree(db, family.recipient_id, mummy["id"])
    post_webhook(client, inbound(text(wamid(), "STOP")))
    messenger.texts.clear()
    post_webhook(client, inbound(text(wamid(), "START")))
    rows = db.table("care_recipients").select("id, opted_out_at").in_("id", [family.recipient_id, mummy["id"]]).execute().data
    assert not any(r["opted_out_at"] for r in rows)
    assert len(messenger.texts) == 1


def test_the_check_in_goes_to_each_and_a_reply_belongs_to_one(db, family, mummy, client, messenger, settings):
    agree(db, family.recipient_id, mummy["id"])
    db.table("care_recipients").update({"checkin_time": "07:30"}).in_("id", [family.recipient_id, mummy["id"]]).execute()
    scheduler.run_tick(db, messenger, settings, datetime(2026, 10, 10, 2, 5, tzinfo=UTC))   # 07:35 IST
    sent = [t for _, t in messenger.templates if t.name == templates.DAILY_CHECKIN]
    assert len(sent) == 2
    checkins = db.table("checkins").select("id, recipient_id, status").in_("recipient_id", [family.recipient_id, mummy["id"]]).execute().data
    papa = next(c for c in checkins if c["recipient_id"] == family.recipient_id)
    post_webhook(client, inbound(button(wamid(), f"checkin:{papa['id']}:ok")))
    status = {c["recipient_id"]: c["status"] for c in db.table("checkins").select("recipient_id, status").in_("recipient_id", [family.recipient_id, mummy["id"]]).execute().data}
    assert status[family.recipient_id] == "replied" and status[mummy["id"]] == "sent"
