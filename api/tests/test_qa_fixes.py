"""QA round fixes: database rules, atomic WhatsApp handling, alert deferral, emergency hardening, nested purge."""

import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest
from postgrest.exceptions import APIError

from app.routes import emergency
from app.services import alerts, appointments
from app.services.scheduler import _all
from app.services.scheduler import run_tick as _run_tick
from app.whatsapp import templates
from tests.conftest import button, inbound, post_webhook, requires_db, wamid

pytestmark = requires_db
ALERT_PHONE = "+919833300778"


def run_tick(*args, **kwargs):
    result = _run_tick(*args, **kwargs)
    assert "step_errors" not in result, result
    return result


def notifications(db, family, type_):
    return db.table("notifications").select("*").eq("user_id", family.user_id).eq("type", type_).execute().data


# ---------------------------------------------------------------- 1, 2: database rules
def test_adjust_stock_refuses_null_and_overflow(db, family):
    with pytest.raises(APIError) as e:
        db.rpc("adjust_stock", {"p_medicine": family.medicine_id, "p_delta": None}).execute()
    assert e.value.code == "22004"
    assert db.table("medicines").select("stock_count").eq("id", family.medicine_id).single().execute().data["stock_count"] == 20
    with pytest.raises(APIError) as e:
        db.rpc("adjust_stock", {"p_medicine": family.medicine_id, "p_delta": 2147483647}).execute()
    assert e.value.code == "22003"
    assert db.rpc("adjust_stock", {"p_medicine": family.medicine_id, "p_delta": -5}).execute().data == 15
    assert db.rpc("adjust_stock", {"p_medicine": family.medicine_id, "p_delta": -500}).execute().data == 0


def make_medicine(db, family, name="Aspirin", times=("08:00",), **kw):
    args = {"p_recipient": family.recipient_id, "p_name": name, "p_dosage": "1 tablet", "p_instructions": None, "p_photo_url": None,
            "p_stock": 5, "p_refill_threshold": 1, "p_times": list(times)} | kw
    return db.rpc("create_medicine", args).execute().data


@pytest.mark.parametrize("name", ["", "   ", "Met\nformin", "Met\tformin", "x" * 101])
def test_bad_medicine_names_are_refused(db, family, name):
    with pytest.raises(APIError) as e:
        make_medicine(db, family, name)
    assert e.value.code in {"22023", "23514"}


def test_medicine_limits(db, family):
    db.table("profiles").update({"plan": "family"}).eq("id", family.user_id).execute()  # 20 is the Family plan's limit
    with pytest.raises(APIError) as e:
        make_medicine(db, family, times=[f"{h:02d}:00" for h in range(13)])
    assert e.value.code == "P0001"
    assert len(db.table("medicine_schedules").select("id").eq("medicine_id", make_medicine(db, family, times=[f"{h:02d}:00" for h in range(12)])).execute().data) == 12
    for photo in ("other/x.jpg", f"{family.recipient_id}/../x.jpg", f"{family.recipient_id}//x.jpg"):
        with pytest.raises(APIError) as e:
            make_medicine(db, family, "Photo", p_photo_url=photo)
        assert e.value.code == "22023"
    assert make_medicine(db, family, "Photo", p_photo_url=f"{family.recipient_id}/a.jpg")
    # 1 (fixture) + 12-times one + photo one = 3 so far; fill up to 20 (the Family plan)
    for i in range(17):
        make_medicine(db, family, f"Drug {i}")
    with pytest.raises(APIError) as e:
        make_medicine(db, family, "One too many")
    assert e.value.code == "P0001"


def test_parent_text_is_cleaned_and_bounded(db, family):
    db.table("care_recipients").update({"name": "Ra\nj\tesh", "call_name": "Pa\npa"}).eq("id", family.recipient_id).execute()
    row = db.table("care_recipients").select("name, call_name").eq("id", family.recipient_id).single().execute().data
    assert row == {"name": "Ra j esh", "call_name": "Pa pa"}
    with pytest.raises(APIError):
        db.table("care_recipients").update({"allergies": ["x" * 81]}).eq("id", family.recipient_id).execute()
    with pytest.raises(APIError):
        db.table("care_recipients").update({"conditions": [f"c{i}" for i in range(31)]}).eq("id", family.recipient_id).execute()


# ---------------------------------------------------------------- 3: template parameters
def test_alert_parameters_are_clean(db, family, messenger, settings):
    db.table("care_recipients").update({"call_name": "Pa pa  x"}).eq("id", family.recipient_id).execute()
    db.table("medicines").update({"name": "Metformin " + "y" * 80}).eq("id", family.medicine_id).execute()
    params = alerts._params(db, "dose_missed", {"recipient_id": family.recipient_id, "medicine_id": family.medicine_id}, "en")
    assert all("\n" not in p and "\t" not in p and "  " not in p and len(p) <= 40 for p in params)
    payload = {"recipient_id": family.recipient_id, "doctor": "Dr\nMehta\t  Jr", "when_en": "tomorrow at 11:00 AM", "when_hi": "कल सुबह 11:00"}
    assert alerts._params(db, "appointment_reminder", payload, "en")[1:] == ["Dr Mehta Jr", "tomorrow at 11:00 AM"]
    assert alerts._params(db, "appointment_reminder", payload, "hi")[2] == "कल सुबह 11:00"


# ---------------------------------------------------------------- 4: parallel WhatsApp taps
def tap_in_parallel(client, payload, times=4):
    def one(_):
        return post_webhook(client, inbound(button(wamid(), payload))).status_code
    with ThreadPoolExecutor(times) as pool:
        return list(pool.map(one, range(times)))


def test_parallel_call_me_taps_create_one_notification(client, db, family):
    db.table("care_recipients").update({"consent_at": "2026-01-01T00:00:00+00:00"}).eq("id", family.recipient_id).execute()
    checkin = db.table("checkins").insert({"recipient_id": family.recipient_id, "date": "2026-10-11", "status": "sent"}).execute().data[0]["id"]
    assert tap_in_parallel(client, templates.encode_payload("checkin", checkin, "call")) == [200] * 4
    assert len(notifications(db, family, "checkin_call_requested")) == 1
    assert db.table("checkins").select("status").eq("id", checkin).single().execute().data["status"] == "replied"


def test_parallel_consent_yes_notifies_once(client, db, family, messenger):
    assert tap_in_parallel(client, templates.encode_payload("consent", family.recipient_id, "yes")) == [200] * 4
    assert len(notifications(db, family, "parent_consented")) == 1
    assert len(messenger.texts) == 1


def test_parallel_consent_no_notifies_once(client, db, family, messenger):
    assert tap_in_parallel(client, templates.encode_payload("consent", family.recipient_id, "no")) == [200] * 4
    assert len(notifications(db, family, "parent_declined")) == 1 and len(messenger.texts) == 1


# ---------------------------------------------------------------- 5: time zone change
def test_timezone_change_replaces_pending_doses(db, family, messenger, settings):
    db.table("care_recipients").update({"consent_at": "2026-01-01T00:00:00+00:00"}).eq("id", family.recipient_id).execute()
    db.table("medicine_schedules").update({"time_of_day": "20:00", "created_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.schedule_id).execute()
    now = datetime(2026, 10, 11, 12, 0, tzinfo=UTC)  # a clock far from the database's own: the old trigger missed these
    run_tick(db, messenger, settings, now=now, only_recipients=[family.recipient_id])

    def pending():
        return db.table("dose_events").select("scheduled_at, status").eq("schedule_id", family.schedule_id).eq("status", "pending").execute().data

    assert [datetime.fromisoformat(d["scheduled_at"]) for d in pending()].count(datetime(2026, 10, 11, 14, 30, tzinfo=UTC)) == 1  # 20:00 IST
    db.table("care_recipients").update({"timezone": "America/Los_Angeles"}).eq("id", family.recipient_id).execute()
    assert pending() == []
    run_tick(db, messenger, settings, now=now, only_recipients=[family.recipient_id])
    times = [datetime.fromisoformat(d["scheduled_at"]) for d in pending()]
    assert datetime(2026, 10, 11, 14, 30, tzinfo=UTC) not in times and datetime(2026, 10, 12, 3, 0, tzinfo=UTC) in times  # 20:00 PDT


# ---------------------------------------------------------------- 6: appointments
VISIT = datetime(2026, 10, 12, 5, 30, tzinfo=UTC)  # Mon 11:00 IST


def test_day_before_is_skipped_close_to_the_morning_reminder():
    from zoneinfo import ZoneInfo
    zone = ZoneInfo("Asia/Kolkata")
    row = {"datetime": VISIT.isoformat()}
    assert appointments.due_kind(row, datetime(2026, 10, 11, 12, 30, tzinfo=UTC), zone) == "day_before"          # 18:00 IST
    assert appointments.due_kind(row, datetime(2026, 10, 12, 1, 0, tzinfo=UTC), zone) is None                   # 06:30 IST, too close to the morning one
    assert appointments.due_kind(row, datetime(2026, 10, 12, 2, 0, tzinfo=UTC), zone) == "morning"              # 07:30 IST




def test_edit_between_read_and_claim_sends_nothing_old(db, family, messenger):
    db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.recipient_id).execute()
    aid = db.table("appointments").insert({"recipient_id": family.recipient_id, "doctor_name": "Dr Mehta", "datetime": VISIT.isoformat()}).execute().data[0]["id"]
    evening = datetime(2026, 10, 11, 12, 30, tzinfo=UTC)
    moved = VISIT + timedelta(days=1)

    def edit_after_read(build):
        rows = _all(build)
        db.table("appointments").update({"datetime": moved.isoformat()}).eq("id", aid).execute()  # the edit lands after the read
        return rows

    counts = appointments.remind_due(db, messenger, evening, edit_after_read, lambda rid: rid == family.recipient_id)
    assert counts["appointment_reminders"] == 0 and messenger.templates == []
    assert db.table("appointments").select("reminded_day_before_at").eq("id", aid).single().execute().data["reminded_day_before_at"] is None


def test_notification_keeps_the_wording_sent_to_the_parent(db, family, messenger):
    db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.recipient_id).execute()
    db.table("appointments").insert({"recipient_id": family.recipient_id, "doctor_name": "Dr Mehta", "datetime": VISIT.isoformat(), "created_at": "2026-10-01T00:00:00+00:00"}).execute()
    appointments.remind_due(db, messenger, datetime(2026, 10, 11, 12, 30, tzinfo=UTC), _all, lambda rid: rid == family.recipient_id)
    payload = notifications(db, family, "appointment_reminder")[0]["payload"]
    assert payload["when_en"] == "tomorrow at 11:00 AM" and payload["when_hi"] == "कल सुबह 11:00" and payload["doctor"] == "Dr Mehta"
    # a later send time does not change the words
    assert alerts._params(db, "appointment_reminder", payload, "en", now=datetime(2026, 10, 12, 6, 0, tzinfo=UTC))[2] == "tomorrow at 11:00 AM"


# ---------------------------------------------------------------- 7: reports / profiles grants (service role still works)
def test_profile_checks(db, family):
    for patch in ({"phone": "call me"}, {"email": "nope"}, {"timezone": "Mars/Base"}, {"policy_version": "v" * 41}):
        with pytest.raises(APIError):
            db.table("profiles").update(patch).eq("id", family.user_id).execute()
    db.table("profiles").update({"phone": "+447700900123", "timezone": "Europe/London", "policy_version": "2026-10"}).eq("id", family.user_id).execute()


# ---------------------------------------------------------------- 8: invites
def invite_user(db, label):
    return db.auth.admin.create_user({"email": f"{label}-{uuid.uuid4().hex[:8]}@test.dev", "email_confirm": True}).user.id


def test_circle_without_parent_and_gone_inviter(db, family):
    # the SQL functions read auth.uid(), so their behaviour is asserted in supabase/tests/qa_fixes.test.sql;
    # here: the database still lets the service role seed a circle member and the invite table shape is intact
    other = invite_user(db, "inv")
    try:
        db.table("care_circle_members").insert({"circle_id": family.circle_id, "user_id": other, "role": "member"}).execute()
        assert db.table("family_invites").select("id").eq("circle_id", family.circle_id).execute().data == []
    finally:
        db.auth.admin.delete_user(other)


# ---------------------------------------------------------------- 9: emergency card
@pytest.fixture
def link(db, family):
    emergency.reset_rate_limit()
    db.table("care_recipients").update({
        "name": "Rajesh Kumar", "blood_group": "B+", "conditions": ["Diabetes" + chr(0x202E), "Asth\u200bma"], "allergies": ["Penicillin"],
    }).eq("id", family.recipient_id).execute()
    row = db.table("emergency_links").insert({"recipient_id": family.recipient_id, "created_by": family.user_id}).execute().data[0]
    yield row
    emergency.reset_rate_limit()


def test_token_is_normalised_and_invisible_characters_are_removed(client, link):
    r = client.get(f"/public/emergency/%20{link['token'].upper()}%20")
    assert r.status_code == 200
    assert r.json()["conditions"] == ["Diabetes", "Asthma"]


@pytest.mark.parametrize("path", ["..%2f..%2fx", "a/b/c", "%2e%2e", "x%00y", "", "..%5c..%5cx"])
def test_path_like_tokens_get_the_same_404(client, link, path):
    unknown = client.get("/public/emergency/" + "f" * 64)
    r = client.get(f"/public/emergency/{path}")
    assert r.status_code == 404 and r.text == unknown.text
    assert r.headers["cache-control"] == "no-store" and "noindex" in r.headers["x-robots-tag"]


def test_view_counter_is_atomic(client, db, link):
    def one(_):
        return client.get(f"/public/emergency/{link['token']}").status_code
    with ThreadPoolExecutor(8) as pool:
        assert set(pool.map(one, range(8))) == {200}
    assert db.table("emergency_links").select("view_count").eq("id", link["id"]).single().execute().data["view_count"] == 8


def request_with(host, xff=None):
    headers = SimpleNamespace(get=lambda k, d="": xff if (k == "x-forwarded-for" and xff) else d)
    return SimpleNamespace(client=SimpleNamespace(host=host), headers=headers)


def test_forwarded_header_is_ignored_unless_proxies_are_trusted():
    spoof = request_with("10.0.0.9", "1.2.3.4, 5.6.7.8")
    assert emergency.client_ip(spoof) == "10.0.0.9"
    assert emergency.client_ip(spoof, 1) == "5.6.7.8"      # the entry our own proxy appended
    assert emergency.client_ip(spoof, 2) == "1.2.3.4"
    assert emergency.client_ip(spoof, 5) == "10.0.0.9"     # fewer hops than expected: fall back to the peer


def test_rate_limit_eviction_only_drops_expired_entries(monkeypatch):
    monkeypatch.setattr(emergency, "MAX_TRACKED_IPS", 5)
    emergency.reset_rate_limit()
    import time
    now = time.monotonic()
    for i in range(3):
        emergency._hits[f"old{i}"] = [now - 1000]
    for i in range(2):
        emergency._hits[f"live{i}"] = [now]
    assert emergency._limited("newcomer") is False
    assert "live0" in emergency._hits and "old0" not in emergency._hits
    for i in range(10):
        emergency._hits[f"more{i}"] = [time.monotonic()]
    assert emergency._limited("another") is True            # full of live entries: refuse newcomers, keep the evidence
    emergency.reset_rate_limit()


# ---------------------------------------------------------------- 10: nested storage purge
def test_purge_removes_nested_files(db):
    from app.services.privacy import _purge_folder
    rid = str(uuid.uuid4())
    store = db.storage.from_("reports")
    for path in (f"{rid}/a.pdf", f"{rid}/sub/y.pdf", f"{rid}/sub/deeper/z.pdf"):
        store.upload(path, b"%PDF-1.4\n", {"content-type": "application/pdf"})
    _purge_folder(db, "reports", rid)
    assert [f for f in store.list(rid) if f.get("name")] == []
    assert [f for f in store.list(f"{rid}/sub") if f.get("name")] == []


# ---------------------------------------------------------------- 12: alerts over the hourly cap wait
def test_alerts_over_the_cap_are_deferred_then_sent(db, family, messenger, settings):
    db.table("profiles").update({"alert_phone": ALERT_PHONE, "alert_consent_at": "2026-10-01T00:00:00+00:00", "alert_confirmed_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.user_id).execute()
    db.table("phone_opt_outs").delete().eq("phone", ALERT_PHONE).execute()
    settings.alerts_per_hour = 1
    ids = [db.table("notifications").insert({"user_id": family.user_id, "type": "dose_missed", "payload": {"recipient_id": family.recipient_id, "medicine_id": family.medicine_id}}).execute().data[0]["id"] for _ in range(2)]

    def deliver(now):
        return alerts.deliver_pending_alerts(db, messenger, settings, now=now, in_scope=lambda rid: rid in (None, family.recipient_id))

    first = deliver(datetime.now(UTC))
    assert first["alerts_sent"] == 1 and first["alerts_deferred"] == 1 and first["alerts_skipped"] == 0
    waiting = [db.table("notifications").select("*").eq("id", i).single().execute().data for i in ids]
    assert sum(1 for n in waiting if n["whatsapp_sent_at"] is None and n["whatsapp_skipped_at"] is None) == 1   # not marked skipped
    later = deliver(datetime.now(UTC) + timedelta(minutes=61))
    assert later["alerts_sent"] == 1 and later["alerts_deferred"] == 0
