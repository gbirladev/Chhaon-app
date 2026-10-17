import json
import uuid
from dataclasses import dataclass, field

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.db import service_client
from app.deps import current_user_id, get_db, get_messenger
from app.main import app
from app.whatsapp.client import WhatsAppError
from app.whatsapp.security import sign

APP_SECRET = "test-app-secret"
VERIFY_TOKEN = "test-verify-token"
PARENT_PHONE = "+919810012345"


def _supabase_up() -> bool:
    try:
        return httpx.get(f"{get_settings().supabase_url}/auth/v1/health", timeout=2).status_code < 500
    except httpx.HTTPError:
        return False


requires_db = pytest.mark.skipif(not _supabase_up(), reason="local Supabase is not running (npx supabase start)")


@dataclass
class FakeMessenger:
    """Records what we would have sent to WhatsApp."""

    templates: list = field(default_factory=list)  # (to, Template)
    texts: list = field(default_factory=list)  # (to, body)
    fail: bool = False

    def send_template(self, to, template):
        if self.fail:
            raise WhatsAppError("boom")
        self.templates.append((to, template))
        return f"wamid.OUT{uuid.uuid4().hex}"

    def send_text(self, to, body):
        self.texts.append((to, body))
        return f"wamid.TXT{uuid.uuid4().hex}"


@pytest.fixture
def settings():
    # the optional features are set explicitly: a developer's own .env (escalation on for the browser, say) must not change test results
    return Settings(whatsapp_app_secret=APP_SECRET, whatsapp_verify_token=VERIFY_TOKEN, consent_resend_minutes=10,
                    escalation_enabled=False, slot_reminders_enabled=False, love_loop_enabled=False)


@pytest.fixture
def messenger():
    return FakeMessenger()


@pytest.fixture
def client(settings, messenger):
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_messenger] = lambda: messenger
    yield TestClient(app)
    app.dependency_overrides.clear()


@pytest.fixture
def db():
    return service_client()


@dataclass
class Family:
    user_id: str
    circle_id: str
    recipient_id: str
    medicine_id: str
    schedule_id: str


@pytest.fixture
def family(db):
    """A caregiver with a circle, a parent (+91 98100 12345, Hindi, father) and one medicine. Removed afterwards."""
    tag = uuid.uuid4().hex[:8]
    from app.services import scheduler
    db.table("consent_requests").delete().eq("phone", PARENT_PHONE).execute()
    db.table("phone_opt_outs").delete().eq("phone", PARENT_PHONE).execute()   # STOP outlives parent records: reset between tests
    user = db.auth.admin.create_user({"email": f"cg-{tag}@test.dev", "email_confirm": True, "user_metadata": {"name": "Ananya"}})
    user_id = user.user.id
    db.table("profiles").update({"plan": "family"}).eq("id", user_id).execute()  # these tests are not about plan limits (see test_plans / pgTAP)
    circle_id = db.table("care_circles").insert({"created_by": user_id}).execute().data[0]["id"]  # trigger adds owner
    recipient_id = db.table("care_recipients").insert({
        "circle_id": circle_id, "name": "Rajesh", "call_name": "पापा", "relation": "father", "language": "hi", "whatsapp_phone": PARENT_PHONE,
    }).execute().data[0]["id"]
    medicine_id = db.table("medicines").insert({
        "recipient_id": recipient_id, "name": "Metformin", "dosage": "500 mg", "instructions": "after_food", "stock_count": 20,
    }).execute().data[0]["id"]
    schedule_id = db.table("medicine_schedules").insert({"medicine_id": medicine_id, "time_of_day": "08:00", "start_date": "2026-01-01"}).execute().data[0]["id"]
    scheduler.DEFAULT_SCOPE = {recipient_id}   # a tick in a test may only ever touch THIS family (the database is shared)
    yield Family(user_id, circle_id, recipient_id, medicine_id, schedule_id)
    scheduler.DEFAULT_SCOPE = None
    db.table("care_circles").delete().eq("id", circle_id).execute()  # cascades to everything below it
    db.table("consent_requests").delete().eq("phone", PARENT_PHONE).execute()
    db.auth.admin.delete_user(user_id)


@pytest.fixture
def as_caregiver(family):
    app.dependency_overrides[current_user_id] = lambda: family.user_id
    yield
    app.dependency_overrides.pop(current_user_id, None)


@pytest.fixture
def real_db_dep():
    app.dependency_overrides[get_db] = service_client
    yield
    app.dependency_overrides.pop(get_db, None)


def post_webhook(client, payload, secret=APP_SECRET):
    body = json.dumps(payload).encode()
    return client.post("/webhooks/whatsapp", content=body, headers={"X-Hub-Signature-256": sign(body, secret), "Content-Type": "application/json"})


def inbound(*messages, statuses=()):
    """Shape of a real WhatsApp Cloud API webhook."""
    value = {"messaging_product": "whatsapp", "metadata": {"phone_number_id": "111"}}
    if messages:
        value["messages"] = list(messages)
    if statuses:
        value["statuses"] = list(statuses)
    return {"object": "whatsapp_business_account", "entry": [{"id": "999", "changes": [{"field": "messages", "value": value}]}]}


def button(wamid, payload, sender=PARENT_PHONE):
    return {"from": sender.lstrip("+"), "id": wamid, "timestamp": "1", "type": "button", "button": {"payload": payload, "text": "tap"}}


def text(wamid, body, sender=PARENT_PHONE):
    return {"from": sender.lstrip("+"), "id": wamid, "timestamp": "1", "type": "text", "text": {"body": body}}


def wamid():
    return f"wamid.{uuid.uuid4().hex}"
