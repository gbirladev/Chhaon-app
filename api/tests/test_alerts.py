"""WhatsApp alerts to the caregiver: double opt-in, delivery rules, STOP/START."""

from datetime import UTC, datetime, timedelta

import pytest

from app.services import alerts, scheduler
from app.services.scheduler import run_tick
from app.whatsapp import templates
from tests.conftest import button, inbound, post_webhook, requires_db, text, wamid

pytestmark = requires_db
ALERT_PHONE = "+919833300777"
NOW = datetime(2026, 10, 10, 2, 31, tzinfo=UTC)


@pytest.fixture(autouse=True)
def clean_alert_phone(db):
    for table in ("consent_requests", "phone_opt_outs"):
        db.table(table).delete().eq("phone", ALERT_PHONE).execute()
    yield
    for table in ("consent_requests", "phone_opt_outs"):
        db.table(table).delete().eq("phone", ALERT_PHONE).execute()


def profile(db, family):
    return db.table("profiles").select("*").eq("id", family.user_id).single().execute().data


def alerts_on(db, family, confirmed=True):
    patch = {"alert_phone": ALERT_PHONE, "alert_consent_at": "2026-10-01T00:00:00+00:00"}
    if confirmed:
        patch["alert_confirmed_at"] = "2026-10-01T00:00:00+00:00"
    db.table("profiles").update(patch).eq("id", family.user_id).execute()


def notify(db, family, type_="dose_missed", payload=None, age_hours=0):
    body = {"recipient_id": family.recipient_id, "medicine_id": family.medicine_id} | (payload or {})
    row = db.table("notifications").insert({"user_id": family.user_id, "type": type_, "payload": body}).execute().data[0]
    if age_hours:
        db.table("notifications").update({"created_at": (datetime.now(UTC) - timedelta(hours=age_hours)).isoformat()}).eq("id", row["id"]).execute()
    return row["id"]


def note(db, notification_id):
    return db.table("notifications").select("*").eq("id", notification_id).single().execute().data


def deliver(db, family, messenger, settings, now=None):
    return alerts.deliver_pending_alerts(db, messenger, settings, now=now, in_scope=lambda rid: rid in (None, family.recipient_id))


def sent_to_caregiver(messenger):
    return [t for to, t in messenger.templates if to == ALERT_PHONE]


# ---------------------------------------------------------------- opt-in (double opt-in)
def test_asking_for_alerts_sends_a_confirmation_and_changes_nothing_until_yes(client, db, family, messenger, as_caregiver, real_db_dep):
    response = client.post("/alerts/whatsapp/request", json={"phone": ALERT_PHONE})
    assert response.json() == {"status": "sent"}
    template = sent_to_caregiver(messenger)[0]
    assert template.name == templates.ALERT_OPTIN
    payloads = [b["parameters"][0]["payload"] for b in template.components[1:]]
    assert payloads == [f"alert:{family.user_id}:yes", f"alert:{family.user_id}:no"]
    row = profile(db, family)
    assert row["alert_phone"] == ALERT_PHONE and row["alert_consent_at"] is None   # not ON yet: the number has not agreed


def test_alerts_start_only_when_that_number_taps_yes(client, db, family, messenger):
    db.table("profiles").update({"alert_phone": ALERT_PHONE}).eq("id", family.user_id).execute()
    yes = templates.encode_payload("alert", family.user_id, "yes")
    post_webhook(client, inbound(button(wamid(), yes, sender=ALERT_PHONE)))
    row = profile(db, family)
    assert row["alert_consent_at"] and row["alert_confirmed_at"]
    assert "Chhaon" in messenger.texts[0][1]


def test_someone_elses_number_cannot_confirm_for_you(client, db, family, messenger):
    db.table("profiles").update({"alert_phone": ALERT_PHONE}).eq("id", family.user_id).execute()
    post_webhook(client, inbound(button(wamid(), templates.encode_payload("alert", family.user_id, "yes"), sender="+919000011111")))
    assert profile(db, family)["alert_consent_at"] is None and messenger.texts == []


def test_a_no_blocks_that_number_for_good(client, db, family, messenger):
    db.table("profiles").update({"alert_phone": ALERT_PHONE}).eq("id", family.user_id).execute()
    post_webhook(client, inbound(button(wamid(), templates.encode_payload("alert", family.user_id, "no"), sender=ALERT_PHONE)))
    assert profile(db, family)["alert_consent_at"] is None
    assert messaging_opted_out(db)


def messaging_opted_out(db):
    from app.services.messaging import phone_opted_out

    return phone_opted_out(db, ALERT_PHONE)


def test_a_number_that_said_no_cannot_be_asked_again(client, db, family, messenger, as_caregiver, real_db_dep):
    db.table("phone_opt_outs").upsert({"phone": ALERT_PHONE}).execute()
    assert client.post("/alerts/whatsapp/request", json={"phone": ALERT_PHONE}).json() == {"status": "opted_out"}
    assert sent_to_caregiver(messenger) == []


def test_request_needs_login_and_a_valid_number(client, family, as_caregiver, real_db_dep):
    assert client.post("/alerts/whatsapp/request", json={"phone": "98765"}).status_code == 422
    assert client.post("/alerts/whatsapp/request", json={"phone": "+1"}).status_code == 422


def test_request_without_a_login_is_refused(client, family, real_db_dep):
    assert client.post("/alerts/whatsapp/request", json={"phone": ALERT_PHONE}).status_code == 401


# ---------------------------------------------------------------- delivery
def test_a_missed_dose_reaches_the_caregiver_on_whatsapp_once(db, family, messenger, settings):
    alerts_on(db, family)
    dose = db.table("dose_events").insert({"schedule_id": family.schedule_id, "scheduled_at": "2026-10-10T02:30:00+00:00", "status": "missed"}).execute().data[0]["id"]
    nid = notify(db, family, "dose_missed", {"dose_event_id": dose})
    result = deliver(db, family, messenger, settings)
    assert result["alerts_sent"] == 1
    template = sent_to_caregiver(messenger)[0]
    assert template.name == "carelink_alert_dose_missed" and template.language == "en"
    # parent's name as the caregiver chose it, the medicine, and the dose time on the PARENT's clock
    assert [p["text"] for p in template.components[0]["parameters"]] == ["पापा", "Metformin", "8:00 AM"]
    assert note(db, nid)["whatsapp_sent_at"]
    assert deliver(db, family, messenger, settings)["alerts_sent"] == 0          # never twice
    assert len(sent_to_caregiver(messenger)) == 1


@pytest.mark.parametrize("type_, expected", [
    ("checkin_missed", ["पापा"]), ("checkin_call_requested", ["पापा"]), ("parent_stopped", ["पापा"]),
    ("refill_needed", ["Metformin", "पापा"]), ("out_of_stock", ["Metformin", "पापा"]), ("reminder_not_sent", ["Metformin", "पापा"]),
])
def test_each_alert_type_uses_its_own_template_and_parameters(db, family, messenger, settings, type_, expected):
    alerts_on(db, family)
    notify(db, family, type_)
    deliver(db, family, messenger, settings)
    template = sent_to_caregiver(messenger)[0]
    assert template.name == templates.ALERT_TEMPLATES[type_]
    assert [p["text"] for p in template.components[0]["parameters"]] == expected


def test_nothing_is_sent_without_consent_and_it_is_not_retried(db, family, messenger, settings):
    db.table("profiles").update({"alert_phone": ALERT_PHONE}).eq("id", family.user_id).execute()   # asked, never confirmed
    nid = notify(db, family)
    assert deliver(db, family, messenger, settings)["alerts_skipped"] == 1
    assert sent_to_caregiver(messenger) == [] and note(db, nid)["whatsapp_skipped_at"]


def test_a_number_that_opted_out_is_never_messaged(db, family, messenger, settings):
    alerts_on(db, family)
    db.table("phone_opt_outs").upsert({"phone": ALERT_PHONE}).execute()
    notify(db, family)
    deliver(db, family, messenger, settings)
    assert sent_to_caregiver(messenger) == []


def test_old_alerts_are_never_sent_late(db, family, messenger, settings):
    alerts_on(db, family)
    notify(db, family, age_hours=12)
    assert deliver(db, family, messenger, settings)["alerts_sent"] == 0 and sent_to_caregiver(messenger) == []


def test_only_so_many_alerts_per_hour(db, family, messenger, settings):
    alerts_on(db, family)
    settings.alerts_per_hour = 2
    for _ in range(5):
        notify(db, family, "checkin_missed")
    result = deliver(db, family, messenger, settings)
    assert result["alerts_sent"] == 2 and result["alerts_skipped"] == 0 and result["alerts_deferred"] == 3  # waiting, not lost


def test_a_failing_send_is_retried_then_given_up(db, family, messenger, settings):
    alerts_on(db, family)
    nid = notify(db, family)
    messenger.fail = True
    for attempt in (1, 2):
        assert deliver(db, family, messenger, settings)["alerts_failed"] == 1
        row = note(db, nid)
        assert row["whatsapp_attempts"] == attempt and row["whatsapp_sent_at"] is None and row["whatsapp_skipped_at"] is None
    messenger.fail = False
    assert deliver(db, family, messenger, settings)["alerts_sent"] == 1           # recovered on the third try

    nid2 = notify(db, family)
    messenger.fail = True
    for _ in range(3):
        deliver(db, family, messenger, settings)
    assert note(db, nid2)["whatsapp_skipped_at"]                                    # three failures: stop trying


def test_other_families_alerts_are_left_alone_by_a_scoped_delivery(db, family, messenger, settings):
    alerts_on(db, family)
    nid = notify(db, family, payload={"recipient_id": "00000000-0000-0000-0000-000000000000"})
    deliver(db, family, messenger, settings)
    assert note(db, nid)["whatsapp_sent_at"] is None and note(db, nid)["whatsapp_skipped_at"] is None


def test_the_tick_delivers_the_alerts_it_just_created(db, family, messenger, settings):
    """End to end: unanswered reminder -> missed -> alert, all in the tick that notices it."""
    alerts_on(db, family)
    consent = {"consent_at": "2026-10-01T00:00:00+00:00"}
    db.table("care_recipients").update(consent).eq("id", family.recipient_id).execute()
    db.table("medicine_schedules").update({"created_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.schedule_id).execute()
    run_tick(db, messenger, settings, NOW)
    assert sent_to_caregiver(messenger) == []
    result = run_tick(db, messenger, settings, NOW + timedelta(minutes=61))
    assert result.get("doses_missed", 0) >= 1 and result.get("alerts_sent", 0) >= 1
    assert any(t.name == "carelink_alert_dose_missed" for t in sent_to_caregiver(messenger))
    assert scheduler.DEFAULT_SCOPE == {family.recipient_id}


# ---------------------------------------------------------------- STOP / START from the caregiver's number
def test_stop_turns_alerts_off_and_start_brings_them_back(client, db, family, messenger):
    alerts_on(db, family)
    post_webhook(client, inbound(text(wamid(), "STOP", sender=ALERT_PHONE)))
    assert profile(db, family)["alert_consent_at"] is None
    assert "off" in messenger.texts[0][1].lower() or "बंद" in messenger.texts[0][1]
    post_webhook(client, inbound(text(wamid(), "START", sender=ALERT_PHONE)))
    assert profile(db, family)["alert_consent_at"]


def test_start_does_not_switch_on_a_number_that_never_confirmed(client, db, family, messenger):
    alerts_on(db, family, confirmed=False)
    db.table("profiles").update({"alert_consent_at": None}).eq("id", family.user_id).execute()
    post_webhook(client, inbound(text(wamid(), "START", sender=ALERT_PHONE)))
    assert profile(db, family)["alert_consent_at"] is None
