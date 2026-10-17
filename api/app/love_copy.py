"""Everything the PARENT reads from the love loop, in one place so it can be reviewed and changed without touching the logic.

Tone rules (tests enforce the word list below):
  * warm and respectful, one adult writing to another: never childish, never patronising;
  * no praise for doing something ordinary ("good job", "well done", "शाबाश"), no exclamation marks, no baby talk;
  * short; the first name is what the parent calls the child ("Riya", "Bittu", "Beta"), never a transliteration we invent.
Placeholders: {who} the name(s), {emoji} one or more emoji, {note} the caregiver's own words.
"""

BANNED_WORDS = ("good job", "well done", "proud of you", "शाबाश", "बहुत अच्छे", "गुड जॉब", "!")

COPY = {
    "en": {
        # one reaction, built from the moment it answers
        "dose": "{who} saw that you took your medicine {emoji}",
        "checkin": "{who} is happy to know you're well today {emoji}",
        # several reactions in one message
        "emoji_many": "{who} sent you {emoji}",
        "says": "{who} says: {note}",
        "and": " and ",
        "comma": ", ",
        "presets": {
            "love_you": "Love you ❤️",
            "thank_you": "Thank you for taking care of yourself",
            "talk_tonight": "Talk to you tonight",
        },
        "fallback_name": "Your family",
        # replies to the parent's own commands
        "hearts_off": "Understood 🙏 We will not send these small messages any more. Your reminders continue as usual. "
                      "Write HEARTS any time to get them again.",
        "hearts_on": "Thank you 🙏 You will get these small messages again.",
    },
    "hi": {
        "dose": "{who} ने देखा कि आपने दवाई ले ली {emoji}",
        "checkin": "{who} को जानकर खुशी हुई कि आप आज ठीक हैं {emoji}",
        "emoji_many": "{who} ने आपको {emoji} भेजा",
        "says": "{who} ने कहा: {note}",
        "and": " और ",
        "comma": ", ",
        "presets": {
            "love_you": "आपसे बहुत प्यार ❤️",
            "thank_you": "अपना ख्याल रखने के लिए धन्यवाद",
            "talk_tonight": "आज रात बात करेंगे",
        },
        "fallback_name": "आपका परिवार",
        "hearts_off": "ठीक है 🙏 अब हम ये छोटे संदेश नहीं भेजेंगे। आपकी दवाई के रिमाइंडर पहले की तरह आते रहेंगे। "
                      "दोबारा चाहें तो कभी भी \"दिल चालू\" लिखें।",
        "hearts_on": "धन्यवाद 🙏 अब आपको ये छोटे संदेश फिर मिलेंगे।",
    },
}

# What a parent can write to switch ONLY these messages off or on (STOP stays the full opt-out).
HEART_WORDS = {"heart", "hearts", "दिल", "हार्ट", "हार्ट्स"}
OFF_WORDS = {"no", "stop", "off", "बंद", "बन्द", "नहीं", "मत"}
ON_WORDS = {"on", "yes", "start", "चालू", "शुरू", "शुरु"}


def copy(language: str | None) -> dict:
    return COPY["hi" if language == "hi" else "en"]
