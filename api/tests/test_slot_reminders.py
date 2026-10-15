"""One reminder per time slot: every medicine due at the same moment goes in ONE WhatsApp message, and one tap confirms them all.
Parent lives in Asia/Kolkata (UTC+5:30); the fixture medicine and the extra ones are at 08:00 (02:30 UTC)."""

from datetime import UTC, datetime, timedelta

from app.services import alerts, scheduler
from tests.conftest import PARENT_PHONE, button, inbound, post_webhook, requires_db, wamid
from tests.test_qa_fixes import make_medicine

pytestmark = requires_db

NOW = datetime(2026, 10, 10, 2, 31, tzinfo=UTC)   # 08:01 IST: the 08:00 slot is due
SLOT = "2026-10-10T02:30:00+00:00"


def run_tick(*args, **kwargs):
    result = scheduler.run_tick(*args, **kwargs)
    assert "step_errors" not in result, f"a scheduler step failed: {result}"
    return result


def setup(db, family, extra=("Amlodipine", "Atorvastatin"), consented=True):
    for name in extra:
        make_medicine(db, family, name, p_stock=10)
    ids = [m["id"] for m in db.table("medicines").select("id").eq("recipient_id", family.recipient_id).execute().data]
    db.table("medicine_schedules").update({"created_at": "2026-10-01T00:00:00+00:00", "start_date": "2026-01-01"}).in_("medicine_id", ids).execute()
    if consented:
        db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.recipient_id).execute()


def doses(db, family, at=SLOT):
    rows = (
        db.table("dose_events").select("id, status, whatsapp_message_id, medicine_schedules(medicines(name, recipient_id))")
        .eq("scheduled_at", at).execute().data
    )
    return sorted((r for r in rows if r["medicine_schedules"]["medicines"]["recipient_id"] == family.recipient_id), key=lambda r: r["medicine_schedules"]["medicines"]["name"])


def reminders(messenger):
    return [t for _, t in messenger.templates if t.name.startswith("carelink_dose_reminder")]


def stock(db, family):
    return {m["name"]: m["stock_count"] for m in db.table("medicines").select("name, stock_count").eq("recipient_id", family.recipient_id).execute().data}


def missed_alerts(db, family):
    return db.table("notifications").select("payload").eq("user_id", family.user_id).eq("type", "dose_missed").execute().data


# ---------------------------------------------------------------- sending
def test_with_the_switch_off_every_dose_is_still_reminded_on_its_own(db, family, messenger, settings):
    setup(db, family)
    run_tick(db, messenger, settings, NOW)
    assert [t.name for t in reminders(messenger)] == ["carelink_dose_reminder"] * 3


def test_three_medicines_due_together_are_one_message(db, family, messenger, settings):
    settings.slot_reminders_enabled = True
    setup(db, family)
    result = run_tick(db, messenger, settings, NOW)
    assert result["reminders_sent"] == 1 and result["slot_reminders"] == 1
    (template,) = reminders(messenger)
    assert template.name == "carelink_dose_reminder_multi" and template.language == "hi"
    listed = next(c for c in template.components if c["type"] == "body")["parameters"][1]["text"]
    assert all(name in listed for name in ("Metformin", "Amlodipine", "Atorvastatin")) and listed.count(",") == 2
    rows = doses(db, family)
    assert [r["status"] for r in rows] == ["sent"] * 3
    assert len({r["whatsapp_message_id"] for r in rows}) == 1 and rows[0]["whatsapp_message_id"]   # one message covers all three
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, NOW + timedelta(minutes=5))
    assert len(reminders(messenger)) == 1   # later ticks never resend


def test_a_single_medicine_keeps_its_own_reminder(db, family, messenger, settings):
    settings.slot_reminders_enabled = True
    setup(db, family, extra=())
    run_tick(db, messenger, settings, NOW)
    assert [t.name for t in reminders(messenger)] == ["carelink_dose_reminder"]


def test_different_times_are_different_slots(db, family, messenger, settings):
    settings.slot_reminders_enabled = True
    make_medicine(db, family, "Early", times=("07:45",), p_stock=10)
    setup(db, family, extra=("Amlodipine",))
    run_tick(db, messenger, settings, NOW)
    assert sorted(t.name for t in reminders(messenger)) == ["carelink_dose_reminder", "carelink_dose_reminder_multi"]   # 07:45 alone, 08:00 together


def test_a_failed_send_hands_every_dose_back_and_the_retry_sends_once(db, family, messenger, settings):
    settings.slot_reminders_enabled = True
    setup(db, family)
    messenger.fail = True
    result = run_tick(db, messenger, settings, NOW)
    assert result["send_errors"] >= 1 and [r["status"] for r in doses(db, family)] == ["pending"] * 3
    messenger.fail = False
    run_tick(db, messenger, settings, NOW + timedelta(minutes=1))
    assert len(reminders(messenger)) == 1 and [r["status"] for r in doses(db, family)] == ["sent"] * 3


def test_a_parent_who_has_not_agreed_gets_nothing_and_every_dose_is_skipped(db, family, messenger, settings):
    settings.slot_reminders_enabled = True
    setup(db, family, consented=False)
    result = run_tick(db, messenger, settings, NOW)
    assert reminders(messenger) == [] and result["doses_skipped"] == 3
    assert [r["status"] for r in doses(db, family)] == ["skipped"] * 3


# ---------------------------------------------------------------- "Taken all"
def test_one_tap_confirms_every_medicine_and_uses_stock_once(db, family, messenger, settings, client):
    settings.slot_reminders_enabled = True
    setup(db, family)
    run_tick(db, messenger, settings, NOW)
    before = stock(db, family)
    first = doses(db, family)[0]["id"]
    assert post_webhook(client, inbound(button(wamid(), f"dose:{first}:all"))).status_code == 200
    assert [r["status"] for r in doses(db, family)] == ["taken"] * 3
    after = stock(db, family)
    assert all(after[name] == before[name] - 1 for name in before)
    post_webhook(client, inbound(button(wamid(), f"dose:{first}:all")))   # a second tap confirms nothing new
    assert stock(db, family) == after


def test_taken_all_from_someone_else_changes_nothing(db, family, messenger, settings, client):
    settings.slot_reminders_enabled = True
    setup(db, family)
    run_tick(db, messenger, settings, NOW)
    first = doses(db, family)[0]["id"]
    post_webhook(client, inbound(button(wamid(), f"dose:{first}:all", sender="+919999999999")))
    assert [r["status"] for r in doses(db, family)] == ["sent"] * 3


def test_a_late_taken_all_after_the_doses_were_marked_missed_still_counts(db, family, messenger, settings, client):
    settings.slot_reminders_enabled = True
    setup(db, family)
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, NOW + timedelta(minutes=61))
    assert [r["status"] for r in doses(db, family)] == ["missed"] * 3
    post_webhook(client, inbound(button(wamid(), f"dose:{doses(db, family)[0]['id']}:all")))
    assert [r["status"] for r in doses(db, family)] == ["taken_late"] * 3   # confirmed, but after the missed mark


def test_a_late_tap_on_the_old_taken_button_is_taken_late(db, family, messenger, settings, client):
    setup(db, family)
    run_tick(db, messenger, settings, NOW)
    run_tick(db, messenger, settings, NOW + timedelta(minutes=61))
    one = doses(db, family)[0]["id"]
    before = stock(db, family)
    post_webhook(client, inbound(button(wamid(), f"dose:{one}:taken")))
    assert sorted(r["status"] for r in doses(db, family)) == ["missed", "missed", "taken_late"]
    assert sum(before.values()) - sum(stock(db, family).values()) == 1   # stock is used up once
    post_webhook(client, inbound(button(wamid(), f"dose:{one}:taken")))
    assert sum(before.values()) - sum(stock(db, family).values()) == 1   # and not again


def test_the_old_taken_button_still_confirms_only_its_own_dose(db, family, messenger, settings, client):
    setup(db, family)   # switch off: three separate reminders, each with its own button
    run_tick(db, messenger, settings, NOW)
    one = doses(db, family)[0]["id"]
    post_webhook(client, inbound(button(wamid(), f"dose:{one}:taken")))
    assert sorted(r["status"] for r in doses(db, family)) == ["sent", "sent", "taken"]


# ---------------------------------------------------------------- alerts
def test_unanswered_slot_is_one_alert_that_says_how_many(db, family, messenger, settings):
    settings.slot_reminders_enabled = True
    setup(db, family)
    run_tick(db, messenger, settings, NOW)
    result = run_tick(db, messenger, settings, NOW + timedelta(minutes=61))
    assert result["doses_missed"] == 3
    (alert,) = missed_alerts(db, family)
    assert alert["payload"]["count"] == 3
    assert alerts._params(db, "dose_missed", alert["payload"], "en")[1] == "3 medicines"
    assert alerts._params(db, "dose_missed", alert["payload"], "hi")[1] == "3 दवाइयाँ"


def test_partly_confirmed_slot_alerts_for_the_one_left_by_name(db, family, messenger, settings, client):
    setup(db, family)   # switch off: separate reminders; the parent answers two of three
    run_tick(db, messenger, settings, NOW)
    rows = doses(db, family)
    for row in rows[:2]:
        post_webhook(client, inbound(button(wamid(), f"dose:{row['id']}:taken")))
    run_tick(db, messenger, settings, NOW + timedelta(minutes=61))
    (alert,) = missed_alerts(db, family)
    assert "count" not in alert["payload"]
    assert alerts._params(db, "dose_missed", alert["payload"], "en")[1] == rows[2]["medicine_schedules"]["medicines"]["name"]


def test_failed_delivery_of_a_shared_message_tells_the_family_once(db, family, messenger, settings, client):
    settings.slot_reminders_enabled = True
    setup(db, family)
    run_tick(db, messenger, settings, NOW)
    message_id = doses(db, family)[0]["whatsapp_message_id"]
    failed = {"id": message_id, "status": "failed", "recipient_id": PARENT_PHONE.lstrip("+"), "errors": [{"code": 131026}]}
    post_webhook(client, inbound(statuses=[failed]))
    notes = db.table("notifications").select("id").eq("user_id", family.user_id).eq("type", "whatsapp_failed").execute().data
    assert len(notes) == 1
