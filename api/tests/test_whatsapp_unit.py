from uuid import uuid4

from app.privacy import mask_phone
from app.whatsapp import templates
from app.whatsapp.security import sign, verify_signature


def test_signature_accepts_valid_and_rejects_everything_else():
    body = b'{"hello":"world"}'
    good = sign(body, "secret")
    assert verify_signature(body, good, "secret")
    assert not verify_signature(body + b" ", good, "secret")  # body changed
    assert not verify_signature(body, good, "other-secret")
    assert not verify_signature(body, None, "secret")
    assert not verify_signature(body, "sha256=", "secret")
    assert not verify_signature(body, good.replace("sha256=", "md5="), "secret")
    assert not verify_signature(body, good, "")  # no secret configured: never valid


def test_payload_round_trip_and_rejects_foreign_payloads():
    entity = uuid4()
    assert templates.decode_payload(templates.encode_payload("dose", entity, "taken")) == ("dose", entity, "taken")
    for junk in ["", "hello", "dose:not-a-uuid:taken", f"admin:{entity}:x", f"dose:{entity}", f"dose:{entity}:a:b"]:
        assert templates.decode_payload(junk) is None


def test_dose_template_has_photo_header_only_when_photo_exists():
    event = uuid4()
    plain = templates.dose_reminder("hi", "पापा", "Metformin", "500 mg", "खाने के बाद", event)
    assert plain.name == templates.DOSE_REMINDER and plain.language == "hi"
    assert [c["type"] for c in plain.components] == ["body", "button"]
    with_photo = templates.dose_reminder("en", "Papa", "Metformin", "500 mg", "After food", event, "https://example.test/p.jpg")
    assert with_photo.name == templates.DOSE_REMINDER_PHOTO
    assert with_photo.components[0]["type"] == "header"
    button = with_photo.components[-1]
    assert button["sub_type"] == "quick_reply" and button["parameters"][0]["payload"] == f"dose:{event}:taken"


def test_unknown_language_falls_back_to_english():
    assert templates.language_code("fr") == "en"
    assert templates.reply_text("fr", "stopped") == templates.reply_text("en", "stopped")


def test_phone_numbers_are_masked_in_logs():
    assert mask_phone("+919810012345") == "+91••••••••45"
    assert "9810012" not in mask_phone("+919810012345")
    assert mask_phone(None) == "<none>"
