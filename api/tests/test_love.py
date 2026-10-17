"""The love loop (docs/love-loop.md). The parent lives in Asia/Kolkata (UTC+5:30), speaks Hindi and is called पापा.
NOW is 12:00 IST, outside the quiet hours (21:00 to 07:00)."""

import itertools
import uuid
from datetime import UTC, datetime, timedelta

import pytest

from app import love_copy
from app.config import Settings
from app.services import love, scheduler
from app.whatsapp.client import WhatsAppError
from tests.conftest import PARENT_PHONE, button, inbound, post_webhook, requires_db, text, wamid

NOW = datetime(2026, 10, 10, 6, 30, tzinfo=UTC)    # 12:00 IST
MIN = timedelta(minutes=1)
pytestmark = requires_db


def run_tick(db, messenger, settings, now):
    result = scheduler.run_tick(db, messenger, settings, now)
    assert "step_errors" not in result, f"a scheduler step failed: {result}"
    return result


@pytest.fixture
def lsettings(settings):
    settings.love_loop_enabled = True
    return settings


def setup(db, family, *, enabled=True, quiet=False, **extra):
    """The parent has agreed and the family switched the love loop on. quiet=False turns the quiet hours off (for tests that go
    through the webhook, which uses the real clock)."""
    db.table("care_recipients").update({"consent_at": "2026-10-01T00:00:00+00:00"}).eq("id", family.recipient_id).execute()
    db.table("profiles").update({"name": "Ananya Rao"}).eq("id", family.user_id).execute()
    row = {"recipient_id": family.recipient_id, "enabled": enabled, **extra}
    if not quiet:
        row |= {"quiet_start": "00:00", "quiet_end": "00:00"}
    db.table("love_settings").insert(row).execute()


def parent_wrote(db, family, when, kind="button"):
    db.table("parent_messages").insert({"recipient_id": family.recipient_id, "wamid": wamid(), "kind": kind, "created_at": when.isoformat()}).execute()


_dose_counter = itertools.count(1)


def taken_dose(db, family, when=None):
    when = when or NOW - 30 * MIN - next(_dose_counter) * 7 * MIN       # every dose of a schedule needs its own time
    return db.table("dose_events").insert({
        "schedule_id": family.schedule_id, "scheduled_at": (when - 10 * MIN).isoformat(), "status": "taken",
        "sent_at": (when - 10 * MIN).isoformat(), "responded_at": when.isoformat(),
    }).execute().data[0]["id"]


def react(db, family, *, sender=None, source_id=None, type_="emoji", emoji="❤️", note=None, queued_at=None, **extra):
    queued_at = queued_at or NOW - 5 * MIN
    row = {
        "recipient_id": family.recipient_id, "sender_id": sender or family.user_id, "source_type": "dose", "source_id": source_id or taken_dose(db, family),
        "type": type_, "emoji": emoji if type_ == "emoji" else None, "text": note, "queued_at": queued_at.isoformat(),
        "due_at": (queued_at + 2 * MIN).isoformat(), "expires_at": (queued_at + 48 * 60 * MIN).isoformat(),
    } | extra
    return db.table("reactions").insert(row).execute().data[0]


def reaction(db, id_):
    return db.table("reactions").select("*").eq("id", id_).single().execute().data


def second_caregiver(db, family, name="Rahul Rao", calls_me="Bittu"):
    user = db.auth.admin.create_user({"email": f"cg2-{uuid.uuid4().hex[:8]}@test.dev", "email_confirm": True})
    db.table("profiles").update({"name": name, "plan": "family"}).eq("id", user.user.id).execute()
    db.table("care_circle_members").insert({"circle_id": family.circle_id, "user_id": user.user.id, "role": "member"}).execute()
    if calls_me:
        db.table("care_circle_members").update({"parent_calls_me": calls_me}).eq("circle_id", family.circle_id).eq("user_id", user.user.id).execute()
    return user.user.id


def drop_user(db, user_id):
    db.auth.admin.delete_user(user_id)


def notes(db, user_id, type_):
    return db.table("notifications").select("payload").eq("user_id", user_id).eq("type", type_).execute().data


# ---------------------------------------------------------------- the message
def test_the_message_uses_the_name_the_parent_uses_and_her_language():
    r = {"sender_id": "a", "source_type": "dose", "type": "emoji", "emoji": "❤️", "text": None}
    assert love.build_message([r], {"a": "Riya"}, "en") == "Riya saw that you took your medicine ❤️"
    assert love.build_message([r], {"a": "रिया"}, "hi") == "रिया ने देखा कि आपने दवाई ले ली ❤️"
    c = r | {"source_type": "checkin", "emoji": "🙏"}
    assert love.build_message([c], {"a": "Riya"}, "en") == "Riya is happy to know you're well today 🙏"
    assert love.build_message([c], {"a": "रिया"}, "hi") == "रिया को जानकर खुशी हुई कि आप आज ठीक हैं 🙏"


def test_presets_and_notes_are_written_in_the_parents_language():
    preset = {"sender_id": "a", "source_type": "dose", "type": "preset", "emoji": None, "text": "thank_you"}
    assert love.build_message([preset], {"a": "Riya"}, "en") == "Riya says: Thank you for taking care of yourself"
    assert love.build_message([preset], {"a": "रिया"}, "hi") == "रिया ने कहा: अपना ख्याल रखने के लिए धन्यवाद"
    note = {"sender_id": "a", "source_type": "dose", "type": "custom", "emoji": None, "text": "Call me at 7"}
    assert love.build_message([note], {"a": "Riya"}, "en") == "Riya says: Call me at 7"
    both = {"sender_id": "a", "source_type": "dose", "type": "emoji", "emoji": "❤️", "text": "See you Sunday"}
    assert love.build_message([both], {"a": "Riya"}, "en").splitlines() == ["Riya saw that you took your medicine ❤️", "Riya says: See you Sunday"]


def test_several_caregivers_become_one_message():
    a = {"sender_id": "a", "source_type": "dose", "type": "emoji", "emoji": "❤️", "text": None}
    b = {"sender_id": "b", "source_type": "checkin", "type": "emoji", "emoji": "🙏", "text": None}
    assert love.build_message([a, b], {"a": "Riya", "b": "Rahul"}, "en") == "Riya and Rahul sent you ❤️🙏"
    assert love.build_message([a, b], {"a": "रिया", "b": "राहुल"}, "hi") == "रिया और राहुल ने आपको ❤️🙏 भेजा"
    c = {"sender_id": "c", "source_type": "dose", "type": "emoji", "emoji": "❤️", "text": None}
    assert love.build_message([a, b, c], {"a": "Riya", "b": "Rahul", "c": "Bittu"}, "en") == "Riya, Rahul and Bittu sent you ❤️🙏"
    p = {"sender_id": "b", "source_type": "dose", "type": "preset", "emoji": None, "text": "talk_tonight"}
    assert love.build_message([a, p], {"a": "Riya", "b": "Rahul"}, "en").splitlines() == ["Riya sent you ❤️", "Rahul says: Talk to you tonight"]


def test_a_caregiver_without_a_name_is_still_a_warm_message():
    r = {"sender_id": "a", "source_type": "dose", "type": "emoji", "emoji": "❤️", "text": None}
    assert love.build_message([r], {}, "en").startswith("Your family saw")


def test_the_copy_is_respectful_never_praise():
    for language, c in love_copy.COPY.items():
        strings = [v for v in c.values() if isinstance(v, str)] + list(c["presets"].values())
        for string in strings:
            assert not any(word in string.lower() for word in love_copy.BANNED_WORDS), (language, string)


# ---------------------------------------------------------------- sending
def test_inside_the_open_window_it_is_sent_at_once(db, family, messenger, lsettings):
    setup(db, family)
    parent_wrote(db, family, NOW - 3 * 60 * MIN)
    r = react(db, family)
    result = run_tick(db, messenger, lsettings, NOW)
    assert result["love_sent"] == 1
    ((to, body),) = messenger.texts
    assert to == PARENT_PHONE and body == "Ananya ने देखा कि आपने दवाई ले ली ❤️"
    row = reaction(db, r["id"])
    assert row["status"] == "sent" and row["in_window"] is True and row["category"] == "service" and row["wamid"] and row["batch_id"]


def test_the_name_the_parent_uses_wins_over_the_profile_name(db, family, messenger, lsettings):
    setup(db, family)
    db.table("care_circle_members").update({"parent_calls_me": "Bittu"}).eq("circle_id", family.circle_id).eq("user_id", family.user_id).execute()
    parent_wrote(db, family, NOW - 60 * MIN)
    react(db, family)
    run_tick(db, messenger, lsettings, NOW)
    assert messenger.texts[0][1].startswith("Bittu ने")


def test_nothing_is_sent_during_the_two_minute_wait(db, family, messenger, lsettings):
    setup(db, family)
    parent_wrote(db, family, NOW - 60 * MIN)
    r = react(db, family, queued_at=NOW - 1 * MIN)
    run_tick(db, messenger, lsettings, NOW)
    assert messenger.texts == [] and reaction(db, r["id"])["status"] == "queued"
    run_tick(db, messenger, lsettings, NOW + 2 * MIN)
    assert len(messenger.texts) == 1


def test_a_closed_window_queues_and_the_next_reply_sends_it(db, family, client, messenger, lsettings):
    setup(db, family)
    parent_wrote(db, family, NOW - 30 * 60 * MIN)                         # 30 hours ago: closed
    r = react(db, family, queued_at=datetime.now(UTC) - 10 * MIN)
    scheduler_result = run_tick(db, messenger, lsettings, datetime.now(UTC))
    assert not scheduler_result.get("love_sent") and messenger.texts == []
    assert reaction(db, r["id"])["status"] == "queued"
    post_webhook(client, inbound(text(wamid(), "Thik hoon beta")))
    assert len(messenger.texts) == 1 and reaction(db, r["id"])["status"] == "sent"


def test_a_reaction_expires_after_48_hours_and_the_sender_is_told(db, family, messenger, lsettings):
    setup(db, family)
    r = react(db, family, queued_at=NOW - 49 * 60 * MIN)
    result = run_tick(db, messenger, lsettings, NOW)
    assert result["love_expired"] == 1 and messenger.texts == []
    assert reaction(db, r["id"])["status"] == "expired"
    ((note,),) = [[n["payload"] for n in notes(db, family.user_id, "love_not_delivered")]]
    assert note["reason"] == "expired" and note["reaction_id"] == r["id"]
    run_tick(db, messenger, lsettings, NOW + MIN)
    assert len(notes(db, family.user_id, "love_not_delivered")) == 1          # told once


def test_caregivers_who_react_together_send_one_message(db, family, messenger, lsettings):
    setup(db, family)
    parent_wrote(db, family, NOW - 60 * MIN)
    second = second_caregiver(db, family)
    try:
        a = react(db, family, queued_at=NOW - 3 * MIN)
        b = react(db, family, sender=second, emoji="🙏", queued_at=NOW - 2 * MIN)
        result = run_tick(db, messenger, lsettings, NOW)
        assert result["love_sent"] == 1 and len(messenger.texts) == 1
        assert messenger.texts[0][1] == "Ananya और Bittu ने आपको ❤️🙏 भेजा"
        ra, rb = reaction(db, a["id"]), reaction(db, b["id"])
        assert ra["batch_id"] == rb["batch_id"] and ra["wamid"] == rb["wamid"] and ra["status"] == rb["status"] == "sent"
    finally:
        drop_user(db, second)


def test_two_overlapping_ticks_send_it_once(db, family, messenger, lsettings):
    setup(db, family)
    parent_wrote(db, family, NOW - 60 * MIN)
    react(db, family)
    run_tick(db, messenger, lsettings, NOW)
    run_tick(db, messenger, lsettings, NOW)
    assert len(messenger.texts) == 1


def test_the_daily_limit_holds_the_rest_until_tomorrow(db, family, messenger, lsettings):
    setup(db, family, daily_cap=1)
    parent_wrote(db, family, NOW - 60 * MIN)
    first = react(db, family, queued_at=NOW - 20 * MIN)
    run_tick(db, messenger, lsettings, NOW - 10 * MIN)
    assert reaction(db, first["id"])["status"] == "sent"
    parent_wrote(db, family, NOW - 5 * MIN)
    second = react(db, family, queued_at=NOW - 5 * MIN)
    run_tick(db, messenger, lsettings, NOW)
    assert len(messenger.texts) == 1 and reaction(db, second["id"])["status"] == "queued"
    parent_wrote(db, family, NOW + 23 * 60 * MIN)                              # 11:00 IST the next day, window open again
    run_tick(db, messenger, lsettings, NOW + 24 * 60 * MIN)
    assert len(messenger.texts) == 2 and reaction(db, second["id"])["status"] == "sent"


def test_quiet_hours_hold_it_until_morning(db, family, messenger, lsettings):
    setup(db, family, quiet=True)
    night = datetime(2026, 10, 10, 17, 0, tzinfo=UTC)                          # 22:30 IST
    parent_wrote(db, family, night - 60 * MIN)
    r = react(db, family, queued_at=night - 10 * MIN)
    run_tick(db, messenger, lsettings, night)
    assert messenger.texts == [] and reaction(db, r["id"])["status"] == "queued"
    run_tick(db, messenger, lsettings, datetime(2026, 10, 10, 23, 0, tzinfo=UTC))      # 04:30 IST, still quiet
    assert messenger.texts == []
    run_tick(db, messenger, lsettings, datetime(2026, 10, 11, 1, 45, tzinfo=UTC))      # 07:15 IST
    assert len(messenger.texts) == 1


def test_quiet_hours_wrap_midnight_and_respect_the_parents_clock():
    row = {"quiet_start": "21:00:00", "quiet_end": "07:00:00"}
    assert love.in_quiet_hours(row, "Asia/Kolkata", datetime(2026, 10, 10, 16, 0, tzinfo=UTC))        # 21:30 IST
    assert love.in_quiet_hours(row, "Asia/Kolkata", datetime(2026, 10, 10, 22, 0, tzinfo=UTC))        # 03:30 IST
    assert not love.in_quiet_hours(row, "Asia/Kolkata", datetime(2026, 10, 10, 6, 30, tzinfo=UTC))    # 12:00 IST
    assert not love.in_quiet_hours(row, "America/New_York", datetime(2026, 10, 10, 16, 0, tzinfo=UTC))   # noon in New York


def test_switched_off_means_off(db, family, messenger, lsettings, settings):
    setup(db, family, enabled=False)
    parent_wrote(db, family, NOW - 60 * MIN)
    r = react(db, family)
    run_tick(db, messenger, lsettings, NOW)
    assert messenger.texts == [] and reaction(db, r["id"])["status"] == "skipped" and reaction(db, r["id"])["fail_reason"] == "love_off"
    assert len(notes(db, family.user_id, "love_not_delivered")) == 1


def test_with_the_server_switch_off_nothing_moves(db, family, messenger, settings):
    settings.love_loop_enabled = False
    setup(db, family)
    parent_wrote(db, family, NOW - 60 * MIN)
    r = react(db, family)
    run_tick(db, messenger, settings, NOW)
    assert messenger.texts == [] and reaction(db, r["id"])["status"] == "queued"


def test_a_parent_who_did_not_agree_or_said_stop_gets_nothing(db, family, messenger, lsettings):
    setup(db, family)
    db.table("care_recipients").update({"opted_out_at": NOW.isoformat()}).eq("id", family.recipient_id).execute()
    parent_wrote(db, family, NOW - 60 * MIN)
    r = react(db, family)
    run_tick(db, messenger, lsettings, NOW)
    assert messenger.texts == [] and reaction(db, r["id"])["status"] == "skipped"


def test_a_control_family_never_gets_love_messages(db, family, messenger, lsettings):
    setup(db, family, pilot_group="control")
    parent_wrote(db, family, NOW - 60 * MIN)
    r = react(db, family)
    run_tick(db, messenger, lsettings, NOW)
    assert messenger.texts == [] and reaction(db, r["id"])["status"] == "skipped"


def test_a_failed_send_is_retried_on_the_next_tick(db, family, messenger, lsettings):
    setup(db, family)
    parent_wrote(db, family, NOW - 60 * MIN)
    r = react(db, family)
    def broken(to, body):
        raise WhatsAppError("boom")
    working, messenger.send_text = messenger.send_text, broken
    assert run_tick(db, messenger, lsettings, NOW)["love_send_errors"] == 1
    assert reaction(db, r["id"])["status"] == "queued" and reaction(db, r["id"])["batch_id"] is None
    messenger.send_text = working
    run_tick(db, messenger, lsettings, NOW + MIN)
    assert reaction(db, r["id"])["status"] == "sent"


# ---------------------------------------------------------------- the parent's own commands
@pytest.mark.parametrize("words", ["NO HEARTS", "no hearts", "No Hearts please", "stop hearts", "दिल बंद", "hearts off", "❤️ no hearts"])
def test_no_hearts_stops_only_the_love_messages(db, family, client, messenger, lsettings, words):
    setup(db, family)
    parent_wrote(db, family, datetime.now(UTC) - 60 * MIN)
    r = react(db, family, queued_at=datetime.now(UTC) - 10 * MIN, due_at=(datetime.now(UTC) + 60 * MIN).isoformat())
    post_webhook(client, inbound(text(wamid(), words)))
    settings_row = db.table("love_settings").select("*").eq("recipient_id", family.recipient_id).single().execute().data
    assert settings_row["parent_opted_out_at"] is not None
    parent = db.table("care_recipients").select("consent_at, opted_out_at").eq("id", family.recipient_id).single().execute().data
    assert parent["opted_out_at"] is None and parent["consent_at"]                         # reminders and check-ins go on
    assert not db.table("phone_opt_outs").select("phone").eq("phone", PARENT_PHONE).execute().data
    assert reaction(db, r["id"])["status"] == "skipped" and reaction(db, r["id"])["fail_reason"] == "parent_opted_out"
    ((_, reply),) = messenger.texts
    assert "दिल चालू" in reply                                                              # polite, in her language, says how to undo
    assert len(notes(db, family.user_id, "love_parent_opted_out")) == 1


def test_no_hearts_twice_tells_the_family_once(db, family, client, messenger, lsettings):
    setup(db, family)
    post_webhook(client, inbound(text(wamid(), "no hearts")))
    post_webhook(client, inbound(text(wamid(), "no hearts")))
    assert len(notes(db, family.user_id, "love_parent_opted_out")) == 1 and len(messenger.texts) == 1


def test_hearts_brings_them_back(db, family, client, messenger, lsettings):
    setup(db, family, parent_opted_out_at=NOW.isoformat())
    post_webhook(client, inbound(text(wamid(), "HEARTS")))
    assert db.table("love_settings").select("parent_opted_out_at").eq("recipient_id", family.recipient_id).single().execute().data["parent_opted_out_at"] is None
    assert len(notes(db, family.user_id, "love_parent_opted_in")) == 1


def test_stop_is_still_the_full_opt_out(db, family, client, messenger, lsettings):
    setup(db, family)
    post_webhook(client, inbound(text(wamid(), "STOP")))
    assert db.table("care_recipients").select("opted_out_at").eq("id", family.recipient_id).single().execute().data["opted_out_at"]
    assert db.table("phone_opt_outs").select("phone").eq("phone", PARENT_PHONE).execute().data


def test_a_chat_sentence_about_hearts_is_not_a_command(db, family, client, messenger, lsettings):
    setup(db, family)
    post_webhook(client, inbound(text(wamid(), "I do not like the hearts on the cake")))
    assert db.table("love_settings").select("parent_opted_out_at").eq("recipient_id", family.recipient_id).single().execute().data["parent_opted_out_at"] is None


def test_the_hearts_command_does_nothing_with_the_server_switch_off(db, family, client, messenger, settings):
    settings.love_loop_enabled = False
    setup(db, family)
    post_webhook(client, inbound(text(wamid(), "no hearts")))
    assert db.table("love_settings").select("parent_opted_out_at").eq("recipient_id", family.recipient_id).single().execute().data["parent_opted_out_at"] is None


# ---------------------------------------------------------------- what the parent writes back
def test_an_emoji_and_a_sentence_are_stored_and_linked_to_the_reaction(db, family, client, messenger, lsettings):
    setup(db, family)
    parent_wrote(db, family, datetime.now(UTC) - 60 * MIN)
    r = react(db, family, queued_at=datetime.now(UTC) - 10 * MIN)
    run_tick(db, messenger, lsettings, datetime.now(UTC))
    assert reaction(db, r["id"])["status"] == "sent"
    post_webhook(client, inbound(text(wamid(), "🙏")))
    post_webhook(client, inbound(text(wamid(), "Khush raho beta")))
    rows = db.table("parent_messages").select("kind, body, reaction_id").eq("recipient_id", family.recipient_id).not_.is_("body", "null").order("created_at").execute().data
    assert [(m["kind"], m["body"]) for m in rows] == [("emoji", "🙏"), ("text", "Khush raho beta")]
    assert all(m["reaction_id"] == r["id"] for m in rows)


def test_a_button_tap_is_kept_as_a_tap_and_opens_the_window(db, family, client, messenger, lsettings):
    setup(db, family)
    dose = taken_dose(db, family)
    db.table("dose_events").update({"status": "sent", "responded_at": None}).eq("id", dose).execute()
    post_webhook(client, inbound(button(wamid(), f"dose:{dose}:taken")))
    ((m,),) = [db.table("parent_messages").select("kind, body").eq("recipient_id", family.recipient_id).execute().data]
    assert m == {"kind": "button", "body": None}
    assert love.window_open(db, family.recipient_id, datetime.now(UTC))


def test_a_voice_note_is_noted_but_never_kept(db, family, client, messenger, lsettings):
    setup(db, family)
    post_webhook(client, inbound({"from": PARENT_PHONE.lstrip("+"), "id": wamid(), "timestamp": "1", "type": "audio", "audio": {"id": "x"}}))
    ((m,),) = [db.table("parent_messages").select("kind, body").eq("recipient_id", family.recipient_id).execute().data]
    assert m == {"kind": "other", "body": None}


def test_a_duplicate_webhook_event_stores_and_sends_once(db, family, client, messenger, lsettings):
    setup(db, family)
    react(db, family, queued_at=datetime.now(UTC) - 10 * MIN)
    message = text(wamid(), "Thik hoon")
    post_webhook(client, inbound(message))
    post_webhook(client, inbound(message))
    assert len(db.table("parent_messages").select("id").eq("recipient_id", family.recipient_id).execute().data) == 1
    assert len(messenger.texts) == 1
    assert len(notes(db, family.user_id, "parent_message")) == 1


def test_many_emoji_make_one_notice_for_the_family(db, family, client, messenger, lsettings):
    setup(db, family)
    for emoji in ("❤️", "🙏", "👍"):
        post_webhook(client, inbound(text(wamid(), emoji)))
    assert len(db.table("parent_messages").select("id").eq("recipient_id", family.recipient_id).execute().data) == 3
    assert len(notes(db, family.user_id, "parent_message")) == 1


def test_nothing_is_kept_for_a_family_that_did_not_switch_it_on(db, family, client, messenger, lsettings):
    setup(db, family, enabled=False)
    post_webhook(client, inbound(text(wamid(), "hello")))
    assert db.table("parent_messages").select("id").eq("recipient_id", family.recipient_id).execute().data == []


# ---------------------------------------------------------------- delivery statuses
def status(message_id, name, ts="1760000000"):
    return {"id": message_id, "status": name, "timestamp": ts, "recipient_id": PARENT_PHONE.lstrip("+")}


def sent_reaction(db, family):
    """A love message that went out. Its WhatsApp id is new each time: Meta event ids are remembered for good."""
    parent_wrote(db, family, NOW - 60 * MIN)
    r = react(db, family, status="sent", wamid=wamid(), sent_at=NOW.isoformat(), batch_id=str(uuid.uuid4()), in_window=True)
    return r


def test_delivered_then_read_move_the_status_forward_only(db, family, client, lsettings):
    setup(db, family)
    r = sent_reaction(db, family)
    post_webhook(client, inbound(statuses=[status(r["wamid"], "delivered")]))
    assert reaction(db, r["id"])["status"] == "delivered" and reaction(db, r["id"])["delivered_at"]
    post_webhook(client, inbound(statuses=[status(r["wamid"], "read")]))
    assert reaction(db, r["id"])["status"] == "read" and reaction(db, r["id"])["read_at"]
    post_webhook(client, inbound(statuses=[status(r["wamid"], "delivered", "1760000100")]))   # late and out of order
    assert reaction(db, r["id"])["status"] == "read"


def test_read_without_a_delivered_event_still_counts(db, family, client, lsettings):
    setup(db, family)
    r = sent_reaction(db, family)
    post_webhook(client, inbound(statuses=[status(r["wamid"], "read")]))
    row = reaction(db, r["id"])
    assert row["status"] == "read" and row["delivered_at"]


def test_a_failed_delivery_tells_the_sender(db, family, client, lsettings):
    setup(db, family)
    r = sent_reaction(db, family)
    failed = status(r["wamid"], "failed") | {"errors": [{"code": 131047}]}
    post_webhook(client, inbound(statuses=[failed]))
    post_webhook(client, inbound(statuses=[failed]))
    assert reaction(db, r["id"])["status"] == "failed"
    assert [n["payload"]["reason"] for n in notes(db, family.user_id, "love_not_delivered")] == ["whatsapp_failed"]


def test_the_love_loop_is_off_unless_the_server_says_so():
    assert Settings(_env_file=None).love_loop_enabled is False
