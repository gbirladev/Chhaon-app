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


def test_multi_medicine_reminder_lists_every_medicine_and_has_one_taken_all_button():
    some = uuid4()
    t = templates.dose_reminder_multi("en", "Papa", ["Metformin (500 mg)", "Amlodipine (5 mg)", "Atorvastatin (10 mg)"], some)
    assert t.name == "carelink_dose_reminder_multi" and t.language == "en"
    body = next(c for c in t.components if c["type"] == "body")
    assert [p["text"] for p in body["parameters"]] == ["Papa", "Metformin (500 mg), Amlodipine (5 mg), Atorvastatin (10 mg)"]
    (button,) = [c for c in t.components if c["type"] == "button"]
    assert templates.decode_payload(button["parameters"][0]["payload"]) == ("dose", some, "all")


def test_long_medicine_lists_are_cut_and_say_how_many_more():
    meds = [f"Medicine number {i} (500 mg)" for i in range(1, 11)]
    en = templates.medicine_list("en", meds)
    assert en.startswith("Medicine number 1 (500 mg), Medicine number 2") and en.endswith("and 4 more")
    assert templates.medicine_list("hi", meds).endswith("और 4 दवाएँ")
    long_names = ["X" * 80] * 8   # entries are cut to 60 characters, at most 6 are listed, so the text always stays short
    text = templates.medicine_list("en", long_names)
    assert len(text) <= 420 and text.endswith("and 2 more") and text.count("X" * 60) == 6 and "X" * 61 not in text


def test_medicine_list_values_are_cleaned_like_other_template_values():
    text = templates.medicine_list("en", ["Met\nformin   (500 mg)", "", "Tab\tlet"])
    assert "\n" not in text and "\t" not in text and "   " not in text and text == "Met formin (500 mg), -, Tab let"
