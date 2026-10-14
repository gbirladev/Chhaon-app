"""End-to-end WhatsApp behaviour against the local Supabase, with Meta replaced by FakeMessenger.
Run `npx supabase start` first; otherwise these tests are skipped."""

from datetime import date

import pytest

from app.services import messaging
from app.whatsapp import templates
from tests.conftest import (
    PARENT_PHONE,
    VERIFY_TOKEN,
    button,
    inbound,
    post_webhook,
    requires_db,
    text,
    wamid,
)

pytestmark = requires_db
OTHER_PHONE = "+919000099999"


def recipient(db, family):
    return db.table("care_recipients").select("*").eq("id", family.recipient_id).single().execute().data


def notifications(db, family, type_):
    return db.table("notifications").select("*").eq("user_id", family.user_id).eq("type", type_).execute().data


def consent(db, family):
    db.table("care_recipients").update({"consent_at": "2026-01-01T00:00:00+00:00"}).eq("id", family.recipient_id).execute()


def new_dose(db, family, status="pending"):
    return db.table("dose_events").insert({"schedule_id": family.schedule_id, "scheduled_at": "2026-10-09T02:30:00+00:00", "status": status}).execute().data[0]["id"]


# ---------------------------------------------------------------- webhook security
def test_handshake_accepts_only_the_right_token(client):
    ok = client.get("/webhooks/whatsapp", params={"hub.mode": "subscribe", "hub.verify_token": VERIFY_TOKEN, "hub.challenge": "12345"})
    assert ok.status_code == 200 and ok.text == "12345"
    assert client.get("/webhooks/whatsapp", params={"hub.mode": "subscribe", "hub.verify_token": "nope", "hub.challenge": "1"}).status_code == 403
    assert client.get("/webhooks/whatsapp").status_code == 403


def test_unsigned_or_wrongly_signed_webhooks_are_rejected(client, db, family):
    consent(db, family)
    payload = inbound(text(wamid(), "STOP"))
    assert client.post("/webhooks/whatsapp", json=payload).status_code == 403
    assert post_webhook(client, payload, secret="wrong").status_code == 403
    assert recipient(db, family)["opted_out_at"] is None  # nothing happened


def test_webhook_refuses_to_run_without_an_app_secret(client, settings):
    settings.whatsapp_app_secret = ""
    assert post_webhook(client, inbound()).status_code == 503


# ---------------------------------------------------------------- consent
def test_consent_request_then_parent_agrees(client, db, family, messenger, as_caregiver, real_db_dep):
    response = client.post(f"/recipients/{family.recipient_id}/consent-request")
    assert response.json() == {"status": "sent"}
    to, template = messenger.templates[0]
    assert to == PARENT_PHONE and template.name == templates.CONSENT_REQUEST and template.language == "hi"
    body_params = [p["text"] for p in template.components[0]["parameters"]]
    assert body_params == ["पापा", "Ananya"]  # greets Papa in Hindi, names the caregiver
    yes_payload = template.components[1]["parameters"][0]["payload"]

    # asking again right away must not spam the parent
    too_soon = client.post(f"/recipients/{family.recipient_id}/consent-request").json()
    assert too_soon["status"] == "too_soon" and 1 <= too_soon["retry_after"] <= 600
    assert len(messenger.templates) == 1

    message_id = wamid()
    assert post_webhook(client, inbound(button(message_id, yes_payload))).status_code == 200
    row = recipient(db, family)
    assert row["consent_at"] and row["opted_out_at"] is None
    assert len(notifications(db, family, "parent_consented")) == 1
    assert "धन्यवाद" in messenger.texts[0][1]  # confirmation in the parent's language

    # Meta resends the same webhook: nothing is duplicated
    assert post_webhook(client, inbound(button(message_id, yes_payload))).status_code == 200
    assert len(notifications(db, family, "parent_consented")) == 1 and len(messenger.texts) == 1
    assert client.post(f"/recipients/{family.recipient_id}/consent-request").json() == {"status": "already_consented"}


def test_reply_from_a_different_number_is_ignored(client, db, family):
    payload = templates.encode_payload("consent", family.recipient_id, "yes")
    post_webhook(client, inbound(button(wamid(), payload, sender=OTHER_PHONE)))
    assert recipient(db, family)["consent_at"] is None


def test_parent_declines(client, db, family, messenger):
    post_webhook(client, inbound(button(wamid(), templates.encode_payload("consent", family.recipient_id, "no"))))
    row = recipient(db, family)
    assert row["consent_at"] is None and row["opted_out_at"]
    assert len(notifications(db, family, "parent_declined")) == 1
    assert "संदेश नहीं" in messenger.texts[0][1]


def test_only_circle_members_can_request_consent(client, db, family, messenger, real_db_dep):
    from app.deps import current_user_id
    from app.main import app

    stranger = db.auth.admin.create_user({"email": "stranger-x@test.dev", "email_confirm": True}).user.id
    app.dependency_overrides[current_user_id] = lambda: stranger
    try:
        assert client.post(f"/recipients/{family.recipient_id}/consent-request").status_code == 404
    finally:
        app.dependency_overrides.pop(current_user_id, None)
        db.auth.admin.delete_user(stranger)
    assert messenger.templates == []


def test_consent_request_needs_a_login(client, family, real_db_dep):
    assert client.post(f"/recipients/{family.recipient_id}/consent-request").status_code == 401


# ---------------------------------------------------------------- dose reminders
def test_no_reminder_without_consent(db, family, messenger):
    dose = new_dose(db, family)
    assert messaging.send_dose_reminder(db, messenger, dose) == "not_allowed"
    assert messenger.templates == []
    assert db.table("dose_events").select("status").eq("id", dose).single().execute().data["status"] == "pending"


def test_reminder_is_sent_once_and_in_the_parents_language(db, family, messenger):
    consent(db, family)
    dose = new_dose(db, family)
    assert messaging.send_dose_reminder(db, messenger, dose) == "sent"
    assert messaging.send_dose_reminder(db, messenger, dose) == "skipped"  # a retry never double-sends
    assert len(messenger.templates) == 1
    _, template = messenger.templates[0]
    assert template.name == templates.DOSE_REMINDER and template.language == "hi"
    assert [p["text"] for p in template.components[0]["parameters"]] == ["पापा", "Metformin", "500 mg", "खाने के बाद"]
    row = db.table("dose_events").select("*").eq("id", dose).single().execute().data
    assert row["status"] == "sent" and row["sent_at"] and row["whatsapp_message_id"].startswith("wamid.OUT")


def test_failed_send_returns_the_dose_to_pending_for_retry(db, family, messenger):
    consent(db, family)
    dose = new_dose(db, family)
    messenger.fail = True
    with pytest.raises(Exception, match="boom"):
        messaging.send_dose_reminder(db, messenger, dose)
    assert db.table("dose_events").select("status").eq("id", dose).single().execute().data["status"] == "pending"
    messenger.fail = False
    assert messaging.send_dose_reminder(db, messenger, dose) == "sent"


def test_tapping_taken_marks_the_dose_and_is_idempotent(client, db, family, messenger):
    consent(db, family)
    dose = new_dose(db, family)
    messaging.send_dose_reminder(db, messenger, dose)
    payload = templates.encode_payload("dose", dose, "taken")
    assert post_webhook(client, inbound(button(wamid(), payload))).status_code == 200
    row = db.table("dose_events").select("status, responded_at").eq("id", dose).single().execute().data
    assert row["status"] == "taken" and row["responded_at"]
    first = row["responded_at"]
    post_webhook(client, inbound(button(wamid(), payload)))  # a second tap
    assert db.table("dose_events").select("responded_at").eq("id", dose).single().execute().data["responded_at"] == first


def test_late_tap_after_missed_still_counts(client, db, family):
    consent(db, family)
    dose = new_dose(db, family, status="missed")
    post_webhook(client, inbound(button(wamid(), templates.encode_payload("dose", dose, "taken"))))
    assert db.table("dose_events").select("status").eq("id", dose).single().execute().data["status"] == "taken"


def test_taken_from_someone_elses_number_is_ignored(client, db, family):
    consent(db, family)
    dose = new_dose(db, family, status="sent")
    post_webhook(client, inbound(button(wamid(), templates.encode_payload("dose", dose, "taken"), sender=OTHER_PHONE)))
    assert db.table("dose_events").select("status").eq("id", dose).single().execute().data["status"] == "sent"


def test_delivery_failure_alerts_the_caregiver(client, db, family, messenger):
    consent(db, family)
    dose = new_dose(db, family)
    messaging.send_dose_reminder(db, messenger, dose)
    sent_id = db.table("dose_events").select("whatsapp_message_id").eq("id", dose).single().execute().data["whatsapp_message_id"]
    failed = {"id": sent_id, "status": "failed", "recipient_id": PARENT_PHONE.lstrip("+")}
    post_webhook(client, inbound(statuses=[failed]))
    alerts = notifications(db, family, "whatsapp_failed")
    assert len(alerts) == 1 and alerts[0]["payload"]["dose_event_id"] == dose
    assert PARENT_PHONE not in str(alerts[0]["payload"])  # ids only, never a phone number
    post_webhook(client, inbound(statuses=[failed]))  # Meta retry
    assert len(notifications(db, family, "whatsapp_failed")) == 1


# ---------------------------------------------------------------- STOP / START
def test_stop_blocks_all_further_messages_and_start_resumes(client, db, family, messenger):
    consent(db, family)
    assert post_webhook(client, inbound(text(wamid(), "  stop "))).status_code == 200
    row = recipient(db, family)
    assert row["opted_out_at"] and row["consent_at"]
    assert len(notifications(db, family, "parent_stopped")) == 1
    assert "बंद" in messenger.texts[0][1]

    dose = new_dose(db, family)
    assert messaging.send_dose_reminder(db, messenger, dose) == "not_allowed"
    assert messaging.send_checkin(db, messenger, family.recipient_id, date(2026, 10, 9)) == "not_allowed"

    post_webhook(client, inbound(text(wamid(), "START")))
    assert recipient(db, family)["opted_out_at"] is None
    assert messaging.send_dose_reminder(db, messenger, dose) == "sent"


def test_start_cannot_replace_the_original_consent(client, db, family):
    post_webhook(client, inbound(text(wamid(), "START")))  # never agreed in the first place
    assert recipient(db, family)["consent_at"] is None


def test_unrelated_chat_is_ignored(client, db, family, messenger):
    consent(db, family)
    post_webhook(client, inbound(text(wamid(), "hello how are you")))
    assert recipient(db, family)["opted_out_at"] is None and messenger.texts == []


# ---------------------------------------------------------------- daily check-in
def test_checkin_is_sent_once_and_replies_are_recorded(client, db, family, messenger):
    consent(db, family)
    today = date(2026, 10, 9)
    assert messaging.send_checkin(db, messenger, family.recipient_id, today) == "sent"
    assert messaging.send_checkin(db, messenger, family.recipient_id, today) == "skipped"
    _, template = messenger.templates[0]
    assert template.name == templates.DAILY_CHECKIN
    ok, call = (b["parameters"][0]["payload"] for b in template.components[1:])

    post_webhook(client, inbound(button(wamid(), ok)))
    row = db.table("checkins").select("status, responded_at").eq("recipient_id", family.recipient_id).single().execute().data
    assert row["status"] == "replied" and row["responded_at"]
    assert notifications(db, family, "checkin_call_requested") == []

    post_webhook(client, inbound(button(wamid(), call)))  # "Call me" tapped afterwards
    assert notifications(db, family, "checkin_call_requested") == []  # already replied: no duplicate alert on a second tap


def test_call_me_alerts_the_caregiver_once(client, db, family, messenger):
    consent(db, family)
    messaging.send_checkin(db, messenger, family.recipient_id, date(2026, 10, 10))
    call = messenger.templates[0][1].components[2]["parameters"][0]["payload"]
    post_webhook(client, inbound(button(wamid(), call)))
    post_webhook(client, inbound(button(wamid(), call)))
    assert len(notifications(db, family, "checkin_call_requested")) == 1


# ---------------------------------------------------------------- changing the number
def test_changing_the_parents_number_requires_fresh_consent(db, family):
    consent(db, family)
    db.table("care_recipients").update({"whatsapp_phone": "+919811122233"}).eq("id", family.recipient_id).execute()
    row = recipient(db, family)
    assert row["consent_at"] is None and row["opted_out_at"] is None


# ---------------------------------------------------------------- the real client in dry-run mode
def test_real_client_in_dry_run_mode_sends_nothing_but_records_the_request(db, family, settings, as_caregiver, real_db_dep):
    """No fake messenger here: exercises the production dependency wiring (this once failed to start)."""
    from fastapi.testclient import TestClient

    from app.config import get_settings
    from app.main import app

    assert settings.whatsapp_dry_run  # no access token configured in tests
    app.dependency_overrides[get_settings] = lambda: settings
    try:
        response = TestClient(app).post(f"/recipients/{family.recipient_id}/consent-request")
    finally:
        app.dependency_overrides.pop(get_settings, None)
    assert response.status_code == 200 and response.json() == {"status": "sent"}
    assert recipient(db, family)["consent_requested_at"]


# ---------------------------------------------------------------- stock + refill alerts
def medicine(db, family):
    return db.table("medicines").select("stock_count").eq("id", family.medicine_id).single().execute().data


def test_confirming_a_dose_uses_up_stock_once_and_alerts_at_the_refill_level(client, db, family, messenger):
    consent(db, family)
    db.table("medicines").update({"stock_count": 6, "refill_threshold": 5}).eq("id", family.medicine_id).execute()
    dose = new_dose(db, family)
    messaging.send_dose_reminder(db, messenger, dose)
    payload = templates.encode_payload("dose", dose, "taken")

    post_webhook(client, inbound(button(wamid(), payload)))
    assert medicine(db, family)["stock_count"] == 5
    alerts = notifications(db, family, "refill_needed")
    assert len(alerts) == 1 and alerts[0]["payload"]["medicine_id"] == family.medicine_id

    post_webhook(client, inbound(button(wamid(), payload)))  # the parent taps again
    assert medicine(db, family)["stock_count"] == 5 and len(notifications(db, family, "refill_needed")) == 1


def test_tablet_doses_use_the_stated_number_and_stock_never_goes_negative(client, db, family, messenger):
    consent(db, family)
    db.table("medicines").update({"dosage": "2 tablets", "stock_count": 1, "refill_threshold": None}).eq("id", family.medicine_id).execute()
    dose = new_dose(db, family)
    messaging.send_dose_reminder(db, messenger, dose)
    post_webhook(client, inbound(button(wamid(), templates.encode_payload("dose", dose, "taken"))))
    assert medicine(db, family)["stock_count"] == 0
    assert notifications(db, family, "refill_needed") == []  # refill reminder is off for this medicine


def test_medicine_added_already_low_alerts_the_family_immediately(db, family):
    db.table("medicines").insert({"recipient_id": family.recipient_id, "name": "Low on arrival", "stock_count": 3, "refill_threshold": 5}).execute()
    assert len(notifications(db, family, "refill_needed")) == 1
