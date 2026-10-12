"""Regression tests for the QA round: abuse limits, malformed input, resilience."""

import json
from datetime import UTC, datetime, timedelta
from zoneinfo import ZoneInfo

import pytest
from postgrest.exceptions import APIError

from app.services import messaging, scheduler
from app.services.inbound import _units_per_dose
from app.services.scheduler import run_tick
from app.whatsapp import templates
from app.whatsapp.security import sign
from tests.conftest import (
    APP_SECRET,
    PARENT_PHONE,
    button,
    inbound,
    post_webhook,
    requires_db,
    text,
    wamid,
)

pytestmark = requires_db
NOW = datetime(2026, 10, 10, 2, 31, tzinfo=UTC)  # 08:01 IST


def recipient(db, family):
    return db.table("care_recipients").select("*").eq("id", family.recipient_id).single().execute().data


def consent(db, family):
    db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.recipient_id).execute()


def ready(db, family):
    consent(db, family)
    db.table("medicine_schedules").update({"created_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.schedule_id).execute()


def new_dose(db, family, status="pending", day=9):
    return db.table("dose_events").insert({"schedule_id": family.schedule_id, "scheduled_at": f"2026-10-{day:02d}T02:30:00+00:00", "status": status}).execute().data[0]["id"]


def alerts(db, family, type_):
    return db.table("notifications").select("payload").eq("user_id", family.user_id).eq("type", type_).execute().data


# ---------------------------------------------------------------- opt-out must be easy
@pytest.mark.parametrize("words", ["Stop.", "STOP!", "please stop", "stop messages", "रुको", "रुकिए", "बंद करो", "बन्द करो", "  Unsubscribe ", "STOP🙏", "Stop,please", "ＳＴＯＰ", "stop\u200b"])
def test_many_ways_of_saying_stop_all_work(client, db, family, words):
    consent(db, family)
    post_webhook(client, inbound(text(wamid(), words)))
    assert recipient(db, family)["opted_out_at"], f"{words!r} should opt out"


def test_a_long_chat_message_that_mentions_stop_is_not_an_opt_out(client, db, family):
    consent(db, family)
    post_webhook(client, inbound(text(wamid(), "I will stop by the pharmacy tomorrow to pick up my tablets")))
    assert recipient(db, family)["opted_out_at"] is None


def test_start_after_declining_gives_consent(client, db, family, messenger):
    post_webhook(client, inbound(button(wamid(), templates.encode_payload("consent", family.recipient_id, "no"))))
    assert recipient(db, family)["opted_out_at"] and recipient(db, family)["consent_at"] is None
    post_webhook(client, inbound(text(wamid(), "START")))
    row = recipient(db, family)
    assert row["consent_at"] and row["opted_out_at"] is None
    assert len(alerts(db, family, "parent_consented")) == 1


# ---------------------------------------------------------------- poisoned input must not block real events
def test_malformed_events_are_skipped_and_never_block_a_real_stop(client, db, family):
    consent(db, family)
    poison = [
        None, "x", 5, [],
        {"id": wamid(), "from": PARENT_PHONE.lstrip("+"), "type": "button", "button": {"payload": 5}},
        {"id": wamid(), "from": PARENT_PHONE.lstrip("+"), "type": "text", "text": {"body": 12345}},
        {"id": wamid(), "type": "interactive", "interactive": {"button_reply": {"id": {"nested": 1}}}},
        {"id": wamid(), "from": None, "type": "text", "text": None},
        text(wamid(), "STOP"),   # the real one, last in the same delivery
    ]
    assert post_webhook(client, inbound(*poison)).status_code == 200
    assert recipient(db, family)["opted_out_at"]


@pytest.mark.parametrize("shape", [[], "x", 5, None, {"entry": None}, {"entry": "x"}, {"entry": [None]}, {"entry": [{"changes": None}]},
                                   {"entry": [{"changes": [{"value": None}]}]}, {"entry": [{"changes": [{"value": {"messages": None, "statuses": [None, "x"]}}]}]}])
def test_odd_payload_shapes_never_return_500(client, shape):
    assert post_webhook(client, shape).status_code == 200


def test_failed_status_without_an_id_is_ignored(client):
    assert post_webhook(client, inbound(statuses=[{"status": "failed"}])).status_code == 200


def test_non_ascii_secrets_are_rejected_not_crashed(client, settings):
    settings.cron_secret = "s3cret"
    assert client.post("/cron/tick", headers={b"X-Cron-Secret": "é".encode()}).status_code == 403
    body = b"{}"
    assert client.post("/webhooks/whatsapp", content=body, headers={b"X-Hub-Signature-256": "sha256=é".encode()}).status_code == 403
    assert client.get("/webhooks/whatsapp", params={"hub.mode": "subscribe", "hub.verify_token": "é", "hub.challenge": "1"}).status_code == 403


def test_oversized_webhook_bodies_are_refused(client):
    body = json.dumps({"pad": "x" * 1_200_000}).encode()
    response = client.post("/webhooks/whatsapp", content=body, headers={"X-Hub-Signature-256": sign(body, APP_SECRET)})
    assert response.status_code == 413


# ---------------------------------------------------------------- stock
def test_units_per_dose_handles_strange_text():
    assert [_units_per_dose(x) for x in ["2 tablets", "2 Tablets", "1.5 capsules", "500 mg", "5 ml", None, "", "inf tablets", "1e9 tablets", "2 tablet daily", "12 tablets", "0 tablets"]] == [2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 10, 1]


def test_a_tap_on_a_reminder_that_was_never_sent_does_nothing(client, db, family):
    consent(db, family)
    db.table("medicines").update({"stock_count": 10, "refill_threshold": None}).eq("id", family.medicine_id).execute()
    for day, status in enumerate(("pending", "skipped"), start=1):
        dose = new_dose(db, family, status, day=day)
        post_webhook(client, inbound(button(wamid(), templates.encode_payload("dose", dose, "taken"))))
        assert db.table("dose_events").select("status").eq("id", dose).single().execute().data["status"] == status
    assert db.table("medicines").select("stock_count").eq("id", family.medicine_id).single().execute().data["stock_count"] == 10


def test_adjust_stock_is_a_delta_and_never_negative(db, family):
    db.table("medicines").update({"stock_count": 3}).eq("id", family.medicine_id).execute()
    assert db.rpc("adjust_stock", {"p_medicine": family.medicine_id, "p_delta": -1}).execute().data == 2
    assert db.rpc("adjust_stock", {"p_medicine": family.medicine_id, "p_delta": -50}).execute().data == 0
    assert db.rpc("adjust_stock", {"p_medicine": family.medicine_id, "p_delta": 5}).execute().data == 5


# ---------------------------------------------------------------- abuse of the consent request
def test_one_phone_number_cannot_be_asked_repeatedly_through_several_accounts(client, db, family, messenger, as_caregiver, real_db_dep):
    other = db.table("care_recipients").insert({"circle_id": family.circle_id, "name": "Duplicate", "whatsapp_phone": PARENT_PHONE}).execute().data[0]
    assert client.post(f"/recipients/{family.recipient_id}/consent-request").json() == {"status": "sent"}
    assert client.post(f"/recipients/{other['id']}/consent-request").json() == {"status": "too_soon"}  # same number, different parent record
    assert len(messenger.templates) == 1


def test_a_caregiver_has_a_daily_limit_on_numbers_they_can_message(client, db, family, settings, messenger, as_caregiver, real_db_dep):
    settings.consent_requests_per_day = 1
    phones = ["+919800000001", "+919800000002"]
    ids = [db.table("care_recipients").insert({"circle_id": family.circle_id, "name": f"P{i}", "whatsapp_phone": p}).execute().data[0]["id"] for i, p in enumerate(phones)]
    try:
        assert client.post(f"/recipients/{ids[0]}/consent-request").json() == {"status": "sent"}
        assert client.post(f"/recipients/{ids[1]}/consent-request").json() == {"status": "too_many"}
    finally:
        db.table("consent_requests").delete().in_("phone", phones).execute()


def test_names_are_cleaned_before_they_go_into_a_template(db, family, messenger, settings):
    consent(db, family)
    db.table("medicines").update({"name": "Evil   name  " + "x" * 60}).eq("id", family.medicine_id).execute()
    db.table("medicine_schedules").update({"created_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.schedule_id).execute()
    dose = new_dose(db, family)
    messaging.send_dose_reminder(db, messenger, dose)
    params = [p["text"] for p in messenger.templates[0][1].components[0]["parameters"]]
    assert all("\n" not in p and "\t" not in p and "  " not in p and len(p) <= 40 for p in params)  # newlines are refused by the database now


# ---------------------------------------------------------------- data integrity
def test_unknown_timezone_is_rejected_and_cannot_break_dose_generation(db, family):
    with pytest.raises(APIError) as error:
        db.table("care_recipients").update({"timezone": "Mars/Olympus"}).eq("id", family.recipient_id).execute()
    assert error.value.code == "22023"
    assert isinstance(db.rpc("generate_dose_events", {"p_now": "2026-09-01T00:00:00+00:00"}).execute().data, int)


def test_a_photo_path_must_be_in_the_medicines_own_folder(db, family):
    with pytest.raises(APIError) as error:
        db.table("medicines").update({"photo_url": "00000000-0000-0000-0000-000000000000/stolen.jpg"}).eq("id", family.medicine_id).execute()
    assert error.value.code == "23514"
    db.table("medicines").update({"photo_url": f"{family.recipient_id}/mine.jpg"}).eq("id", family.medicine_id).execute()  # allowed
    assert messaging._signed_photo_url(db, "someone-else/x.jpg", family.recipient_id) is None
    assert messaging._signed_photo_url(db, f"{family.recipient_id}/../other/x.jpg", family.recipient_id) is None


def test_a_membership_cannot_be_pointed_at_another_user(db, family):
    stranger = db.auth.admin.create_user({"email": "member-swap@test.dev", "email_confirm": True}).user.id
    try:
        with pytest.raises(APIError) as error:
            db.table("care_circle_members").update({"user_id": stranger}).eq("circle_id", family.circle_id).execute()
        assert error.value.code == "42501"
    finally:
        db.auth.admin.delete_user(stranger)


def test_create_medicine_dedupes_times_and_starts_on_the_parents_local_date(db, family):
    db.table("care_recipients").update({"timezone": "Pacific/Auckland"}).eq("id", family.recipient_id).execute()
    medicine_id = db.rpc("create_medicine", {
        "p_recipient": family.recipient_id, "p_name": "Dup", "p_dosage": "1 tablet", "p_instructions": None, "p_photo_url": None,
        "p_stock": 5, "p_refill_threshold": None, "p_times": ["08:00", "08:00", "08:00:00"],
    }).execute().data
    schedules = db.table("medicine_schedules").select("start_date, time_of_day").eq("medicine_id", medicine_id).execute().data
    assert len(schedules) == 1
    assert schedules[0]["start_date"] == datetime.now(ZoneInfo("Pacific/Auckland")).date().isoformat()


def test_changing_the_parents_timezone_removes_reminders_in_the_old_timezone(db, family):
    future = (datetime.now(UTC) + timedelta(hours=5)).isoformat()
    dose = db.table("dose_events").insert({"schedule_id": family.schedule_id, "scheduled_at": future}).execute().data[0]["id"]
    db.table("care_recipients").update({"timezone": "America/New_York"}).eq("id", family.recipient_id).execute()
    assert db.table("dose_events").select("id").eq("id", dose).execute().data == []


# ---------------------------------------------------------------- scheduler resilience
def test_a_delayed_first_tick_still_creates_and_sends_the_dose(db, family, messenger, settings):
    ready(db, family)
    late = datetime(2026, 10, 10, 3, 15, tzinfo=UTC)  # 08:45 IST: tick was down since before 08:00
    result = run_tick(db, messenger, settings, late)
    assert result["reminders_sent"] >= 1
    assert any(to == PARENT_PHONE for to, _ in messenger.templates)


def test_an_unexpected_error_while_sending_never_leaves_a_dose_stuck_as_sent(db, family, settings):
    ready(db, family)

    class Exploding:
        def send_template(self, to, template):
            raise RuntimeError("surprise")

        def send_text(self, to, body):
            raise RuntimeError("surprise")

    result = run_tick(db, Exploding(), settings, NOW)
    assert result.get("send_errors", 0) >= 1 and "step_errors" not in result
    statuses = [d["status"] for d in db.table("dose_events").select("status").eq("schedule_id", family.schedule_id).execute().data]
    assert "sent" not in statuses


def test_a_parent_who_stopped_does_not_trigger_missed_alerts(db, family, messenger, settings):
    ready(db, family)
    run_tick(db, messenger, settings, NOW)
    db.table("care_recipients").update({"opted_out_at": "2026-10-10T02:40:00+00:00"}).eq("id", family.recipient_id).execute()
    run_tick(db, messenger, settings, NOW + timedelta(hours=2))
    assert alerts(db, family, "dose_missed") == []


def test_missed_dose_alert_names_the_medicine_by_id(db, family, messenger, settings):
    ready(db, family)
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, NOW + timedelta(minutes=61))
    missed = alerts(db, family, "dose_missed")
    assert len(missed) == 1 and missed[0]["payload"]["medicine_id"] == family.medicine_id


def test_paging_helper_reads_past_the_1000_row_limit():
    class Query:
        def __init__(self):
            self.window = (0, 0)

        def order(self, _):
            return self

        def range(self, start, end):
            self.window = (start, end)
            return self

        def execute(self):
            start, end = self.window
            return type("R", (), {"data": [{"id": i} for i in range(start, min(end + 1, 2500))]})()

    assert len(scheduler._all(Query)) == 2500


@pytest.mark.parametrize("words", ["Cancel my appointment", "please don't stop", "no stop needed", "दरवाज़ा बंद", "I will stop by tomorrow", "band karo"])
def test_ordinary_sentences_are_not_opt_outs(client, db, family, words):
    consent(db, family)
    post_webhook(client, inbound(text(wamid(), words)))
    assert recipient(db, family)["opted_out_at"] is None, f"{words!r} must not opt out"


def test_start_inside_a_chat_sentence_does_not_grant_consent(client, db, family):
    post_webhook(client, inbound(button(wamid(), templates.encode_payload("consent", family.recipient_id, "no"))))
    post_webhook(client, inbound(text(wamid(), "Start taking medicine at 9")))
    assert recipient(db, family)["consent_at"] is None


# ---------------------------------------------------------------- STOP belongs to the phone number
def test_stop_survives_editing_the_number_and_changing_it_back(client, db, family, messenger, as_caregiver, real_db_dep):
    consent(db, family)
    post_webhook(client, inbound(text(wamid(), "STOP")))
    db.table("care_recipients").update({"whatsapp_phone": "+919000000042"}).eq("id", family.recipient_id).execute()
    db.table("care_recipients").update({"whatsapp_phone": PARENT_PHONE}).eq("id", family.recipient_id).execute()
    assert recipient(db, family)["opted_out_at"] is None   # the record was reset by the number change...
    assert client.post(f"/recipients/{family.recipient_id}/consent-request").json() == {"status": "opted_out"}   # ...the NUMBER was not
    assert messenger.templates == []


def test_stop_survives_enrolling_the_same_number_on_another_parent(client, db, family, messenger, as_caregiver, real_db_dep):
    consent(db, family)
    post_webhook(client, inbound(text(wamid(), "STOP")))
    other = db.table("care_recipients").insert({"circle_id": family.circle_id, "name": "Again", "whatsapp_phone": PARENT_PHONE}).execute().data[0]
    assert client.post(f"/recipients/{other['id']}/consent-request").json() == {"status": "opted_out"}
    db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00"}).eq("id", other["id"]).execute()
    ready(db, family)
    dose = new_dose(db, family)
    assert messaging.send_dose_reminder(db, messenger, dose) == "not_allowed"   # still blocked, even with consent on the record


def test_only_the_parent_can_lift_a_stop(client, db, family, messenger):
    consent(db, family)
    post_webhook(client, inbound(text(wamid(), "STOP")))
    assert messaging.phone_opted_out(db, PARENT_PHONE)
    post_webhook(client, inbound(text(wamid(), "START")))
    assert not messaging.phone_opted_out(db, PARENT_PHONE)


# ---------------------------------------------------------------- consent request is atomic
def test_simultaneous_taps_send_exactly_one_consent_message(db, family, settings, messenger):
    from concurrent.futures import ThreadPoolExecutor

    def tap(_):
        return messaging.send_consent_request(db, messenger, settings, family.recipient_id, family.user_id)

    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(tap, range(4)))
    assert sorted(results) == ["sent", "too_soon", "too_soon", "too_soon"]
    assert len(messenger.templates) == 1


def test_a_time_zone_the_scheduler_cannot_use_is_rejected(db, family):
    for bad in ("IST", "asia/kolkata", "+05:30", "Nowhere/Land"):
        with pytest.raises(APIError):
            db.table("care_recipients").update({"timezone": bad}).eq("id", family.recipient_id).execute()
    db.table("care_recipients").update({"timezone": "Asia/Kolkata"}).eq("id", family.recipient_id).execute()


# ---------------------------------------------------------------- a tick can be limited to chosen parents
def test_a_scoped_tick_never_touches_other_families(db, family, messenger, settings):
    ready(db, family)
    stranger = db.auth.admin.create_user({"email": "scope-other@test.dev", "email_confirm": True}).user.id
    circle = db.table("care_circles").insert({"created_by": stranger}).execute().data[0]["id"]
    other = db.table("care_recipients").insert({"circle_id": circle, "name": "Bystander", "whatsapp_phone": "+919833300001", "consent_at": "2026-10-01T00:00:00+00:00"}).execute().data[0]["id"]
    try:
        before = db.table("checkins").select("id", count="exact").eq("recipient_id", other).execute().count
        run_tick(db, messenger, settings, datetime(2026, 10, 10, 2, 35, tzinfo=UTC))   # 08:05 IST: check-in time
        assert db.table("checkins").select("id", count="exact").eq("recipient_id", other).execute().count == before
        assert db.table("checkins").select("id", count="exact").eq("recipient_id", family.recipient_id).execute().count == 1
    finally:
        db.table("care_circles").delete().eq("id", circle).execute()
        db.auth.admin.delete_user(stranger)


# ---------------------------------------------------------------- relations and call names
def test_the_name_the_caregiver_chose_is_what_the_parent_is_called(db, family, messenger, settings):
    from app.services.messaging import call_name

    assert call_name(recipient(db, family), "hi") == "पापा"        # the name the caregiver chose
    db.table("care_recipients").update({"call_name": None}).eq("id", family.recipient_id).execute()
    assert call_name(recipient(db, family), "hi") == "Rajesh"      # none chosen: the name they typed, not a word we picked
    db.table("care_recipients").update({"call_name": "  Appa  "}).eq("id", family.recipient_id).execute()
    assert call_name(recipient(db, family), "hi") == "Appa"
    ready(db, family)
    dose = new_dose(db, family)
    messaging.send_dose_reminder(db, messenger, dose)
    assert messenger.templates[0][1].components[0]["parameters"][0]["text"] == "Appa"


def test_many_relations_are_accepted_and_unknown_ones_are_not(db, family):
    for relation in ("grandmother", "father_in_law", "spouse", "aunt", "other"):
        db.table("care_recipients").update({"relation": relation}).eq("id", family.recipient_id).execute()
    with pytest.raises(APIError):
        db.table("care_recipients").update({"relation": "cousin"}).eq("id", family.recipient_id).execute()
