"""WhatsApp message templates and button payloads.

Business-initiated messages must use pre-approved templates (docs/whatsapp-setup.md has the exact
text to submit to Meta). Each template exists in English and Hindi under the same name; the parent's
language picks which one is sent.

Quick-reply buttons carry a payload that comes back in the webhook: "<kind>:<id>:<action>".
"""

from dataclasses import dataclass
from uuid import UUID

CONSENT_REQUEST = "carelink_consent_request_v2"
DOSE_REMINDER = "carelink_dose_reminder"
DOSE_REMINDER_PHOTO = "carelink_dose_reminder_photo"
DAILY_CHECKIN = "carelink_daily_checkin"
APPOINTMENT_REMINDER = "carelink_appointment_reminder"

ALERT_OPTIN = "carelink_alert_optin"
# notification type -> template sent to the CAREGIVER. Parameters per type are listed in docs/whatsapp-setup.md.
ALERT_TEMPLATES = {
    "dose_missed": "carelink_alert_dose_missed",                # {{1}} parent, {{2}} medicine, {{3}} time
    "checkin_missed": "carelink_alert_checkin_missed",          # {{1}} parent
    "checkin_call_requested": "carelink_alert_call_requested",  # {{1}} parent
    "refill_needed": "carelink_alert_low_stock",                # {{1}} medicine, {{2}} parent
    "out_of_stock": "carelink_alert_out_of_stock",              # {{1}} medicine, {{2}} parent
    "reminder_not_sent": "carelink_alert_not_sent",             # {{1}} medicine, {{2}} parent
    "appointment_reminder": "carelink_alert_appointment",       # {{1}} parent, {{2}} doctor, {{3}} when
    "parent_stopped": "carelink_alert_parent_stopped",          # {{1}} parent
    "parent_declined": "carelink_alert_parent_stopped",         # {{1}} parent
}

LANGUAGES = {"en": "en", "hi": "hi"}  # app language -> WhatsApp template language code


@dataclass(frozen=True)
class Template:
    name: str
    language: str
    components: list[dict]


def language_code(app_language: str | None) -> str:
    return LANGUAGES.get(app_language or "", "en")


def encode_payload(kind: str, entity_id: UUID | str, action: str) -> str:
    return f"{kind}:{entity_id}:{action}"


def decode_payload(payload: str) -> tuple[str, UUID, str] | None:
    """Returns (kind, id, action) or None for anything we did not generate."""
    parts = payload.split(":")
    if len(parts) != 3 or parts[0] not in {"dose", "checkin", "consent", "alert"}:
        return None
    try:
        return parts[0], UUID(parts[1]), parts[2]
    except ValueError:
        return None


def _buttons(payloads: list[str]) -> list[dict]:
    return [
        {"type": "button", "sub_type": "quick_reply", "index": str(i), "parameters": [{"type": "payload", "payload": p}]}
        for i, p in enumerate(payloads)
    ]


def _body(*values: str) -> dict:
    return {"type": "body", "parameters": [{"type": "text", "text": v} for v in values]}


def consent_request(language: str, parent_name: str, caregiver_name: str, recipient_id: UUID | str) -> Template:
    """Body: {{1}} = how we greet the parent (Papa/Mummy/name), {{2}} = who asked (the caregiver)."""
    return Template(
        CONSENT_REQUEST,
        language_code(language),
        [_body(parent_name, caregiver_name), *_buttons([
            encode_payload("consent", recipient_id, "yes"),
            encode_payload("consent", recipient_id, "no"),
        ])],
    )


def dose_reminder(
    language: str, parent_name: str, medicine: str, dose: str, instruction: str,
    dose_event_id: UUID | str, photo_url: str | None = None,
) -> Template:
    """Body: {{1}} parent, {{2}} medicine, {{3}} dose, {{4}} instruction (e.g. 'After food'). Optional image header."""
    components: list[dict] = []
    if photo_url:
        components.append({"type": "header", "parameters": [{"type": "image", "image": {"link": photo_url}}]})
    components.append(_body(parent_name, medicine, dose, instruction))
    components += _buttons([encode_payload("dose", dose_event_id, "taken")])
    return Template(DOSE_REMINDER_PHOTO if photo_url else DOSE_REMINDER, language_code(language), components)


def daily_checkin(language: str, parent_name: str, checkin_id: UUID | str) -> Template:
    return Template(
        DAILY_CHECKIN,
        language_code(language),
        [_body(parent_name), *_buttons([
            encode_payload("checkin", checkin_id, "ok"),
            encode_payload("checkin", checkin_id, "call"),
        ])],
    )


def appointment_reminder(language: str, parent_name: str, doctor: str, when: str) -> Template:
    """Body: {{1}} parent's call name, {{2}} doctor, {{3}} when, in the parent's language ('tomorrow at 11:00 AM'). No buttons."""
    return Template(APPOINTMENT_REMINDER, language_code(language), [_body(parent_name, doctor, when)])


def alert_optin(language: str, caregiver_name: str, profile_id: UUID | str) -> Template:
    """Asks the caregiver to confirm WhatsApp alerts. Body: {{1}} = their first name."""
    return Template(
        ALERT_OPTIN,
        language_code(language),
        [_body(caregiver_name), *_buttons([
            encode_payload("alert", profile_id, "yes"),
            encode_payload("alert", profile_id, "no"),
        ])],
    )


def caregiver_alert(notification_type: str, language: str, params: list[str]) -> Template:
    return Template(ALERT_TEMPLATES[notification_type], language_code(language), [_body(*params)])


# Free-form replies. Allowed because the parent just messaged us (24-hour customer-service window).
REPLIES = {
    "en": {
        "consent_yes": "Thank you 🙏 You will now get gentle medicine reminders here. Reply STOP any time to stop.",
        "consent_no": "No problem. We will not send you any messages.",
        "stopped": "Okay, we have stopped all messages. Reply START if you want them again.",
        "resumed": "Welcome back 🙏 Reminders have started again.",
        "alerts_on": "Thank you 🙏 Chhaon alerts are on. We will message you here when something needs your attention. Reply STOP any time to turn them off.",
        "alerts_off": "Okay, Chhaon alerts are off. You can turn them on again in the app.",
        "alerts_resumed": "Chhaon alerts are on again. Reply STOP to turn them off.",
    },
    "hi": {
        "consent_yes": "धन्यवाद 🙏 अब आपको यहाँ दवा के हल्के रिमाइंडर मिलेंगे। रोकने के लिए कभी भी STOP लिखें।",
        "consent_no": "कोई बात नहीं। हम आपको कोई संदेश नहीं भेजेंगे।",
        "stopped": "ठीक है, हमने सभी संदेश बंद कर दिए हैं। दोबारा चाहिए तो START लिखें।",
        "resumed": "वापसी पर स्वागत है 🙏 रिमाइंडर फिर से शुरू हो गए हैं।",
        "alerts_on": "धन्यवाद 🙏 छाँव अलर्ट चालू हो गए हैं। जब किसी बात पर आपका ध्यान चाहिए होगा, हम यहाँ संदेश भेजेंगे। बंद करने के लिए कभी भी STOP लिखें।",
        "alerts_off": "ठीक है, छाँव अलर्ट बंद हैं। आप इन्हें ऐप में दोबारा चालू कर सकते हैं।",
        "alerts_resumed": "छाँव अलर्ट फिर से चालू हैं। बंद करने के लिए STOP लिखें।",
    },
}


def reply_text(language: str | None, key: str) -> str:
    return REPLIES[language_code(language)][key]
