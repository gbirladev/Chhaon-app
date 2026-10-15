"""The submit-ready template pack (docs/whatsapp-templates.json) must match what the code actually sends."""
import json
import re
from pathlib import Path
from uuid import uuid4

from app.whatsapp import templates

PACK = json.loads((Path(__file__).resolve().parents[2] / "docs" / "whatsapp-templates.json").read_text(encoding="utf-8"))
BY_NAME = {t["name"]: t for t in PACK["templates"]}
VARIABLE = re.compile(r"\{\{(\d+)\}\}")


def sent(template) -> tuple[int, int]:
    """(number of body variables, number of quick-reply buttons) of what the code sends."""
    body = next(c for c in template.components if c["type"] == "body")
    return len(body["parameters"]), sum(1 for c in template.components if c["type"] == "button")


def test_pack_covers_exactly_the_templates_the_code_uses():
    in_code = {templates.CONSENT_REQUEST, templates.DOSE_REMINDER, templates.DOSE_REMINDER_PHOTO, templates.DOSE_REMINDER_MULTI, templates.DAILY_CHECKIN,
               templates.APPOINTMENT_REMINDER, templates.ALERT_OPTIN, *templates.ALERT_TEMPLATES.values()}
    assert set(BY_NAME) == in_code


def test_pack_follows_metas_rules():
    for t in PACK["templates"]:
        for lang in ("en", "hi"):
            L = t["languages"][lang]
            numbers = [int(n) for n in VARIABLE.findall(L["body"])]
            assert numbers == list(range(1, len(numbers) + 1)), f"{t['name']} {lang}: variables must be 1, 2, 3 in order"
            assert not L["body"].startswith("{{") and not L["body"].rstrip().endswith("}}"), f"{t['name']} {lang}: starts or ends with a variable"
            assert len(L["examples"]) == len(numbers), f"{t['name']} {lang}: one example per variable"
            assert len(L["body"]) <= 1024
            assert all(len(b) <= 25 for b in L["buttons"]), f"{t['name']} {lang}: button text too long"
        assert [len(VARIABLE.findall(t["languages"][l]["body"])) for l in ("en", "hi")][0] == len(VARIABLE.findall(t["languages"]["hi"]["body"]))
        assert len(t["languages"]["en"]["buttons"]) == len(t["languages"]["hi"]["buttons"])


def test_pack_variables_and_buttons_match_what_the_code_sends():
    some = uuid4()
    built = {
        templates.CONSENT_REQUEST: templates.consent_request("en", "Papa", "Ananya", some),
        templates.DOSE_REMINDER: templates.dose_reminder("en", "Papa", "Metformin", "500 mg", "After food", some),
        templates.DOSE_REMINDER_PHOTO: templates.dose_reminder("en", "Papa", "Metformin", "500 mg", "After food", some, photo_url="https://x/y.jpg"),
        templates.DOSE_REMINDER_MULTI: templates.dose_reminder_multi("en", "Papa", ["Metformin (500 mg)", "Amlodipine (5 mg)"], some),
        templates.DAILY_CHECKIN: templates.daily_checkin("en", "Papa", some),
        templates.APPOINTMENT_REMINDER: templates.appointment_reminder("en", "Papa", "Dr. Mehta", "tomorrow at 11:00 AM"),
        templates.ALERT_OPTIN: templates.alert_optin("en", "Ananya", some),
    }
    params = {"dose_missed": 3, "checkin_missed": 1, "checkin_call_requested": 1, "refill_needed": 2, "out_of_stock": 2,
              "reminder_not_sent": 2, "appointment_reminder": 3, "parent_stopped": 1, "parent_declined": 1}
    for kind, count in params.items():
        built[templates.ALERT_TEMPLATES[kind]] = templates.caregiver_alert(kind, "en", ["x"] * count)
    for name, template in built.items():
        variables, buttons = sent(template)
        pack = BY_NAME[name]["languages"]["en"]
        assert variables == len(VARIABLE.findall(pack["body"])), f"{name}: the code sends {variables} variables"
        assert buttons == len(pack["buttons"]), f"{name}: the code sends {buttons} buttons"
    assert BY_NAME[templates.DOSE_REMINDER_PHOTO]["header"] == "IMAGE" and BY_NAME[templates.DOSE_REMINDER]["header"] is None
