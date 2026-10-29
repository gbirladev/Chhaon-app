"""When WhatsApp refuses the PHOTO version of the medicine reminder (e.g. code 132018: an issue with the template's parameters), the parent still
gets the plain reminder, so a template problem never silences a parent. Other failures are NOT retried (the dose goes back to pending as before)."""

from datetime import UTC, datetime

import pytest

from app.services import messaging
from app.whatsapp import templates
from app.whatsapp.client import WhatsAppError
from tests.conftest import FakeMessenger, requires_db

pytestmark = requires_db
NOW = datetime(2026, 10, 10, 2, 31, tzinfo=UTC)


class PickyMessenger(FakeMessenger):
    """Refuses the photo template with the given Meta error code (None = no code); can also refuse the plain one."""

    def __init__(self, code=132018, refuse_plain=False):
        super().__init__()
        self.code, self.refuse_plain = code, refuse_plain
        self.attempts = []

    def send_template(self, to, template):
        self.attempts.append(template.name)
        if template.name == templates.DOSE_REMINDER_PHOTO or self.refuse_plain:
            raise WhatsAppError("WhatsApp API error 400", code=self.code)
        return super().send_template(to, template)


@pytest.fixture
def pending_dose(db, family, monkeypatch):
    db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.recipient_id).execute()
    monkeypatch.setattr(messaging, "_signed_photo_url", lambda db_, path, rid: "https://example.test/strip.jpg")   # the medicine has a photo
    return db.table("dose_events").insert({"schedule_id": family.schedule_id, "scheduled_at": "2026-10-10T02:30:00+00:00"}).execute().data[0]["id"]


def status(db, dose):
    return db.table("dose_events").select("status, whatsapp_message_id").eq("id", dose).single().execute().data


@pytest.mark.parametrize("code", [132018, 132000, 132012, 131053, 132001, 132015, 132016])
def test_a_refused_photo_template_falls_back_to_the_plain_reminder(db, pending_dose, code):
    wa = PickyMessenger(code=code)
    assert messaging.send_dose_reminder(db, wa, pending_dose, now=NOW) == "sent"
    assert wa.attempts == [templates.DOSE_REMINDER_PHOTO, templates.DOSE_REMINDER]
    ((_, template),) = wa.templates
    assert template.name == templates.DOSE_REMINDER and not any(c["type"] == "header" for c in template.components)
    row = status(db, pending_dose)
    assert row["status"] == "sent" and row["whatsapp_message_id"]
    buttons = [c for c in template.components if c["type"] == "button"]
    assert buttons[0]["parameters"][0]["payload"] == f"dose:{pending_dose}:taken"            # the Taken button still confirms this dose


def test_other_failures_are_not_retried_and_the_dose_goes_back_to_pending(db, pending_dose):
    wa = PickyMessenger(code=131049)
    with pytest.raises(WhatsAppError):
        messaging.send_dose_reminder(db, wa, pending_dose, now=NOW)
    assert wa.attempts == [templates.DOSE_REMINDER_PHOTO]
    assert status(db, pending_dose)["status"] == "pending"


def test_if_the_plain_reminder_fails_too_the_dose_goes_back_to_pending(db, pending_dose):
    wa = PickyMessenger(code=132018, refuse_plain=True)
    with pytest.raises(WhatsAppError):
        messaging.send_dose_reminder(db, wa, pending_dose, now=NOW)
    assert wa.attempts == [templates.DOSE_REMINDER_PHOTO, templates.DOSE_REMINDER]
    assert status(db, pending_dose)["status"] == "pending"


def test_without_a_photo_nothing_changes(db, family, monkeypatch):
    db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.recipient_id).execute()
    dose = db.table("dose_events").insert({"schedule_id": family.schedule_id, "scheduled_at": "2026-10-10T02:30:00+00:00"}).execute().data[0]["id"]
    wa = PickyMessenger(code=132018)
    assert messaging.send_dose_reminder(db, wa, dose, now=NOW) == "sent"
    assert wa.attempts == [templates.DOSE_REMINDER]


def test_the_client_keeps_metas_error_code(monkeypatch):
    import httpx

    from app.config import Settings
    from app.whatsapp.client import WhatsAppClient
    handler = httpx.MockTransport(lambda request: httpx.Response(400, json={"error": {"code": 132018, "message": "secret medicine text"}}))
    client = WhatsAppClient(Settings(whatsapp_access_token="t", whatsapp_phone_number_id="1"), httpx.Client(transport=handler))
    with pytest.raises(WhatsAppError) as caught:
        client.send_text("+919810012345", "hello")
    assert caught.value.code == 132018 and "secret" not in str(caught.value)
