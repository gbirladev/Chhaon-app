import json

import pytest
from postgrest.exceptions import APIError

from app.routes import emergency
from tests.conftest import PARENT_PHONE, requires_db

pytestmark = requires_db

ALLOWED = {"name", "age", "blood_group", "conditions", "allergies", "medicines", "contacts", "doctor"}


@pytest.fixture(autouse=True)
def fresh_limit():
    emergency.reset_rate_limit()
    yield
    emergency.reset_rate_limit()


@pytest.fixture
def link(db, family):
    db.table("care_recipients").update({
        "name": "Rajesh Kumar Sharma", "age": 71, "dob": "1955-03-02", "city": "Lucknow", "blood_group": "B+",
        "conditions": ["Diabetes"], "allergies": ["Penicillin"],
        "doctor_contacts": [{"name": "Dr Mehta", "phone": "+919800000001"}],
        "emergency_contacts": [{"name": "Neha", "phone": "+14155550123"}, {"name": "bad", "phone": "12"}],
    }).eq("id", family.recipient_id).execute()
    db.table("profiles").update({"phone": "+919811100077", "name": "Ananya"}).eq("id", family.user_id).execute()
    paused = db.table("medicines").insert({"recipient_id": family.recipient_id, "name": "PausedDrug", "active": False, "stock_count": 3}).execute().data[0]["id"]
    db.table("medicine_schedules").insert({"medicine_id": paused, "time_of_day": "09:00"}).execute()
    row = db.table("emergency_links").insert({"recipient_id": family.recipient_id, "created_by": family.user_id}).execute().data[0]
    return row


def get(client, token):
    return client.get(f"/public/emergency/{token}")


def test_valid_token_returns_only_minimal_fields(client, link):
    r = get(client, link["token"])
    assert r.status_code == 200
    body = r.json()
    assert set(body) == ALLOWED
    assert body["name"] == "Rajesh" and body["age"] == 71 and body["blood_group"] == "B+"
    assert body["conditions"] == ["Diabetes"] and body["allergies"] == ["Penicillin"]
    assert body["medicines"] == [{"name": "Metformin", "dose": "500 mg", "times": ["08:00"]}]
    assert body["doctor"] == "Dr Mehta"
    phones = {c["phone"] for c in body["contacts"]}
    assert phones == {"+14155550123", "+919811100077"}
    text = json.dumps(body)
    for secret in (PARENT_PHONE, "Lucknow", "1955", "PausedDrug", "stock", "Sharma", "+919800000001"):
        assert secret not in text


def test_headers(client, link):
    r = get(client, link["token"])
    assert r.headers["cache-control"] == "no-store"
    assert "noindex" in r.headers["x-robots-tag"]
    assert r.headers["referrer-policy"] == "no-referrer"


def test_view_is_recorded(client, db, link):
    get(client, link["token"])
    get(client, link["token"])
    row = db.table("emergency_links").select("view_count, last_viewed_at").eq("id", link["id"]).execute().data[0]
    assert row["view_count"] == 2 and row["last_viewed_at"]


def test_unknown_revoked_malformed_are_identical(client, db, link):
    db.table("emergency_links").update({"active": False}).eq("id", link["id"]).execute()
    answers = [get(client, t) for t in (link["token"], "f" * 64, "short", "G" * 64, "a" * 500, "%20")]
    assert {r.status_code for r in answers} == {404}
    assert len({r.text for r in answers}) == 1 and answers[0].json() == {"detail": "Not found"}
    assert all(r.headers["cache-control"] == "no-store" for r in answers)


def test_rotation_kills_old_token(client, db, family, link):
    db.table("emergency_links").update({"active": False}).eq("id", link["id"]).execute()
    new = db.table("emergency_links").insert({"recipient_id": family.recipient_id}).execute().data[0]
    assert new["token"] != link["token"] and len(new["token"]) == 64
    assert get(client, link["token"]).status_code == 404
    assert get(client, new["token"]).status_code == 200


def test_only_one_active_link_per_parent(db, family, link):
    with pytest.raises(APIError):
        db.table("emergency_links").insert({"recipient_id": family.recipient_id}).execute()


def test_deleted_parent_is_not_found(client, db, family, link):
    db.table("care_recipients").delete().eq("id", family.recipient_id).execute()
    assert get(client, link["token"]).status_code == 404


def test_rate_limit(client, link):
    codes = [get(client, "e" * 64).status_code for _ in range(emergency.RATE_LIMIT + 3)]
    assert codes[: emergency.RATE_LIMIT] == [404] * emergency.RATE_LIMIT
    assert codes[-1] == 429
    r = get(client, link["token"])  # even a real token is refused while the limit holds
    assert r.status_code == 429 and r.headers["cache-control"] == "no-store"
