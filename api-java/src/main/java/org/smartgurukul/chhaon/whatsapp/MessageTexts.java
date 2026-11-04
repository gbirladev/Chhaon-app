package org.smartgurukul.chhaon.whatsapp;

import java.util.regex.Pattern;

/**
 * Messages WE start (dose reminder, daily check-in, consent request, alert opt-in). The self-hosted gateway has no
 * quick-reply buttons, so every one of them ENDS WITH how to reply: type a number.
 * Convention: 1 = yes / taken / I am okay, 2 = no / call me, STOP any time (see docs/java-whatsapp-replies.md).
 *
 * <p>Short, warm and respectful ("Namaste Papa"). Hindi is written in Devanagari, as the Python templates were;
 * please have a native speaker review the wording before real families rely on it.
 */
public final class MessageTexts {

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029]+");
    private static final Pattern SPACES = Pattern.compile(" {2,}");

    private MessageTexts() {}

    private static boolean hi(String language) {
        return "hi".equals(language);
    }

    /** Caregiver-supplied names go into a message the parent reads: no line breaks, no runs of spaces, shortened. */
    static String clean(String value, int limit) {
        if (value == null) return "";
        String v = SPACES.matcher(CONTROL.matcher(value).replaceAll(" ")).replaceAll(" ").trim();
        return v.length() > limit ? v.substring(0, limit).trim() : v;
    }

    /** " Papa" (with the leading space) or "" when no name is known. */
    private static String who(String callName) {
        String n = clean(callName, 40);
        return n.isEmpty() ? "" : " " + n;
    }

    public static String doseReminder(String language, String callName, String medicine) {
        String med = clean(medicine, 60);
        if (hi(language)) {
            return "नमस्ते" + who(callName) + " 🙏 दवा लेने का समय हो गया है" + (med.isEmpty() ? "" : " (" + med + ")")
                    + "। लेने के बाद 1 लिखकर भेजिए।";
        }
        return "Namaste" + who(callName) + " 🙏 It's time for your medicine" + (med.isEmpty() ? "" : " (" + med + ")")
                + ". Reply 1 when you have taken it.";
    }

    public static String checkin(String language, String callName) {
        if (hi(language)) {
            return "सुप्रभात" + who(callName) + " 🙏 आज आप कैसे हैं? ठीक हैं तो 1 लिखें, फ़ोन चाहिए तो 2।";
        }
        return "Good morning" + who(callName) + " 🙏 Are you okay today? Reply 1 if you are okay, 2 if you want a call.";
    }

    /** The first message to a parent: asks for agreement (consent) before anything else is ever sent. */
    public static String consentRequest(String language, String callName, String caregiverName) {
        String by = clean(caregiverName, 40);
        if (hi(language)) {
            return "नमस्ते" + who(callName) + " 🙏 " + (by.isEmpty() ? "आपके परिवार" : by)
                    + " आपको WhatsApp पर दवा के हल्के रिमाइंडर भेजना चाहते हैं। शुरू करने के लिए 1 लिखें, नहीं चाहिए तो 2। कभी भी STOP लिखकर रोक सकते हैं।";
        }
        return "Namaste" + who(callName) + " 🙏 " + (by.isEmpty() ? "Your family" : by)
                + " would like to send you gentle medicine reminders on WhatsApp. Reply 1 to start, 2 for no. You can reply STOP any time.";
    }

    /** For the caregiver's own profile ("Me"): they add their own number for their own medicines. */
    public static String selfConsentRequest(String language, String firstName) {
        String n = clean(firstName, 40);
        if (hi(language)) {
            return "नमस्ते" + (n.isEmpty() ? "" : " " + n) + " 🙏 आपने अपनी दवाओं के रिमाइंडर के लिए अपना नंबर जोड़ा है। शुरू करने के लिए 1 लिखें, नहीं चाहिए तो 2। कभी भी STOP लिखकर रोक सकते हैं।";
        }
        return "Namaste" + (n.isEmpty() ? "" : " " + n) + " 🙏 You added this number for your own medicine reminders on Chhaon. Reply 1 to start, 2 for no. You can reply STOP any time.";
    }

    /** Asks a caregiver to confirm WhatsApp alerts about their parent. */
    public static String alertOptIn(String language, String firstName) {
        String n = clean(firstName, 40);
        if (hi(language)) {
            return "नमस्ते" + (n.isEmpty() ? "" : " " + n) + " 🙏 छाँव आपको यहाँ ज़रूरी अलर्ट भेजना चाहता है (जैसे माता-पिता की छूटी हुई दवा)। चालू करने के लिए 1 लिखें, नहीं चाहिए तो 2। कभी भी STOP लिखकर रोक सकते हैं।";
        }
        return "Namaste" + (n.isEmpty() ? "" : " " + n) + " 🙏 Chhaon would like to send you important alerts here (for example a missed medicine of your parent). Reply 1 to turn alerts on, 2 for no. You can reply STOP any time.";
    }
}
