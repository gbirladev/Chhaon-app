"""Data export, account erasure and parent erasure."""

import contextlib
import uuid

import pytest

from app.deps import current_user_id
from app.main import app
from tests.conftest import requires_db

pytestmark = requires_db
OPT_OUT_PHONE = "+919800055501"


def make_user(db, label="x"):
    tag = uuid.uuid4().hex[:8]
    return db.auth.admin.create_user({"email": f"{label}-{tag}@test.dev", "email_confirm": True, "user_metadata": {"name": f"User {label}"}}).user.id


def make_circle(db, owner, with_parent=True):
    circle = db.table("care_circles").insert({"created_by": owner}).execute().data[0]["id"]
    rid = None
    if with_parent:
        rid = db.table("care_recipients").insert({"circle_id": circle, "name": "Rajesh", "whatsapp_phone": "+919810012399"}).execute().data[0]["id"]
    return circle, rid


def add_member(db, circle, user, role="member"):
    db.table("care_circle_members").insert({"circle_id": circle, "user_id": user, "role": role}).execute()


def as_user(user_id):
    app.dependency_overrides[current_user_id] = lambda: user_id


@pytest.fixture
def cleanup(db):
    users, circles = [], []
    yield users, circles
    app.dependency_overrides.pop(current_user_id, None)
    for c in circles:
        db.table("care_circles").delete().eq("id", c).execute()
    for u in users:
        with contextlib.suppress(Exception):  # already deleted by the test
            db.auth.admin.delete_user(u)
    db.table("phone_opt_outs").delete().eq("phone", OPT_OUT_PHONE).execute()


def user_exists(db, uid):
    return bool(db.table("profiles").select("id").eq("id", uid).execute().data)


def upload(db, bucket, rid):
    path = f"{rid}/{uuid.uuid4().hex}.png"
    db.storage.from_(bucket).upload(path, b"\x89PNG\r\n", {"content-type": "image/png"})
    return path


def stored(db, bucket, rid):
    return [f for f in db.storage.from_(bucket).list(rid) if f.get("id")]


# ---------------------------------------------------------------- export
def test_export_requires_login(client):
    assert client.get("/privacy/export").status_code == 401
    assert client.post("/privacy/delete-account", json={"confirm": "DELETE"}).status_code == 401


def test_export_has_my_data_only_and_no_secrets(client, db, family, as_caregiver, real_db_dep):
    other = make_user(db, "other")
    other_circle, _ = make_circle(db, other)
    db.table("emergency_links").insert({"recipient_id": family.recipient_id}).execute()
    doc = db.table("documents").insert({"recipient_id": family.recipient_id, "title": "Blood test", "category": "lab"}).execute().data[0]["id"]
    db.table("document_files").insert({"document_id": doc, "path": f"{family.recipient_id}/page1.jpg", "mime": "image/jpeg", "size_bytes": 1000}).execute()
    try:
        response = client.get("/privacy/export")
        assert response.status_code == 200
        assert "attachment" in response.headers["content-disposition"]
        assert response.headers["cache-control"] == "no-store"
        data = response.json()
        assert data["profile"]["id"] == family.user_id
        assert [p["id"] for p in data["parents"]] == [family.recipient_id]
        assert [m["id"] for m in data["medicines"]] == [family.medicine_id]
        assert len(data["medicine_schedules"]) == 1
        assert [d["title"] for d in data["documents"]] == ["Blood test"] and len(data["document_files"]) == 1   # the wallet is exported too
        assert other_circle not in [c["id"] for c in data["circles"]]
        text = response.text
        assert '"token"' not in text and "whatsapp_message_id" not in text
        assert data["emergency_links"] and set(data["emergency_links"][0]) == {"recipient_id", "active", "created_at"}
    finally:
        db.table("care_circles").delete().eq("id", other_circle).execute()
        db.auth.admin.delete_user(other)


# ---------------------------------------------------------------- account erasure
def test_wrong_confirmation_deletes_nothing(client, db, cleanup, real_db_dep):
    users, _circles = cleanup
    u = make_user(db)
    users.append(u)
    as_user(u)
    assert client.post("/privacy/delete-account", json={"confirm": "no"}).status_code == 422
    assert user_exists(db, u)


def test_sole_member_circle_files_and_login_are_erased(client, db, cleanup, real_db_dep):
    users, circles = cleanup
    u = make_user(db)
    users.append(u)
    circle, rid = make_circle(db, u)
    circles.append(circle)
    medicine = db.table("medicines").insert({"recipient_id": rid, "name": "Metformin"}).execute().data[0]["id"]
    upload(db, "medicine-photos", rid)
    upload(db, "reports", rid)
    doc = db.table("documents").insert({"recipient_id": rid, "title": "Prescription", "category": "prescription"}).execute().data[0]["id"]
    db.table("phone_opt_outs").upsert({"phone": OPT_OUT_PHONE}).execute()
    as_user(u)
    assert client.post("/privacy/delete-account", json={"confirm": " delete "}).json() == {"status": "deleted"}
    assert not user_exists(db, u)
    assert not db.table("care_circles").select("id").eq("id", circle).execute().data
    assert not db.table("medicines").select("id").eq("id", medicine).execute().data
    assert not db.table("documents").select("id").eq("id", doc).execute().data
    assert stored(db, "medicine-photos", rid) == [] and stored(db, "reports", rid) == []
    assert db.table("phone_opt_outs").select("phone").eq("phone", OPT_OUT_PHONE).execute().data  # STOP outlives records


def test_shared_circle_stays_and_longest_member_becomes_owner(client, db, cleanup, real_db_dep):
    users, circles = cleanup
    owner, first, second = make_user(db, "o"), make_user(db, "a"), make_user(db, "b")
    users += [owner, first, second]
    circle, rid = make_circle(db, owner)
    circles.append(circle)
    add_member(db, circle, first)
    add_member(db, circle, second)
    upload(db, "reports", rid)
    db.table("profiles").update({"alert_phone": "+919833300999", "alert_consent_at": "2026-10-01T00:00:00+00:00"}).eq("id", owner).execute()
    as_user(owner)
    assert client.post("/privacy/delete-account", json={"confirm": "DELETE"}).status_code == 200
    roles = {m["user_id"]: m["role"] for m in db.table("care_circle_members").select("*").eq("circle_id", circle).execute().data}
    assert roles == {first: "owner", second: "member"}
    assert db.table("care_recipients").select("id").eq("id", rid).execute().data  # the parent's records survive
    assert len(stored(db, "reports", rid)) == 1


def test_leaving_member_does_not_change_other_roles(client, db, cleanup, real_db_dep):
    users, circles = cleanup
    owner, member = make_user(db, "o"), make_user(db, "m")
    users += [owner, member]
    circle, _ = make_circle(db, owner)
    circles.append(circle)
    add_member(db, circle, member)
    as_user(member)
    assert client.post("/privacy/delete-account", json={"confirm": "DELETE"}).status_code == 200
    roles = {m["user_id"]: m["role"] for m in db.table("care_circle_members").select("*").eq("circle_id", circle).execute().data}
    assert roles == {owner: "owner"}


def test_erasing_twice_is_harmless(db, cleanup):
    from app.services import privacy
    users, circles = cleanup
    u = make_user(db)
    users.append(u)
    circle, _ = make_circle(db, u)
    circles.append(circle)
    privacy.delete_account(db, u)
    privacy.delete_account(db, u)   # second run: nothing left, no error
    assert not user_exists(db, u)


# ---------------------------------------------------------------- one parent
def test_only_the_owner_can_erase_a_parent(client, db, cleanup, real_db_dep):
    users, circles = cleanup
    owner, member, stranger = make_user(db, "o"), make_user(db, "m"), make_user(db, "s")
    users += [owner, member, stranger]
    circle, rid = make_circle(db, owner)
    circles.append(circle)
    add_member(db, circle, member)
    upload(db, "reports", rid)
    for who in (member, stranger):
        as_user(who)
        assert client.post(f"/privacy/recipients/{rid}/delete").status_code == 404
    assert db.table("care_recipients").select("id").eq("id", rid).execute().data
    as_user(owner)
    assert client.post(f"/privacy/recipients/{rid}/delete").json() == {"status": "deleted"}
    assert not db.table("care_recipients").select("id").eq("id", rid).execute().data
    assert stored(db, "reports", rid) == []
    assert client.post(f"/privacy/recipients/{rid}/delete").status_code == 404   # again: gone
