package org.smartgurukul.chhaon.whatsapp;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Messages WE start (dose reminder, daily check-in, consent request, alert opt-in). The self-hosted gateway has no
 * quick-reply buttons, so every one of them ENDS WITH how to reply: type a number. They are laid out to look as much like
 * buttons as plain text can: a short greeting line, one line of news, then the answers on lines of their own with the
 * number in bold (*1*) and a small picture.
 * Convention: 1 = yes / taken / I am okay, 2 = no / call me, STOP any time (see docs/java-whatsapp-replies.md).
 *
 * <p>Short, warm and respectful ("Namaste Papa"). Hindi is written in Devanagari; please have a native speaker review the
 * wording before real families rely on it (docs/hindi-review.md).
 */
public final class MessageTexts {

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029]+");
    private static final Pattern SPACES = Pattern.compile(" {2,}");
    /** WhatsApp turns *bold*, _italic_, ~strike~ and `code` into formatting: a typed name must not be able to use it. */
    private static final Pattern FORMATTING = Pattern.compile("[*_~`]");

    private MessageTexts() {}

    private static boolean hi(String language) {
        return "hi".equals(language);
    }

    /** Caregiver-supplied names go into a message the parent reads: no line breaks, no formatting marks, no runs of spaces, shortened. */
    static String clean(String value, int limit) {
        if (value == null) return "";
        String v = SPACES.matcher(FORMATTING.matcher(CONTROL.matcher(value).replaceAll(" ")).replaceAll("")).replaceAll(" ").trim();
        return v.length() > limit ? v.substring(0, limit).trim() : v;
    }

    /** " Papa" (with the leading space) or "" when no name is known. */
    private static String who(String callName) {
        String n = clean(callName, 40);
        return n.isEmpty() ? "" : " " + n;
    }

    public static String doseReminder(String language, String callName, String medicine) {
        return doseReminder(language, callName, medicine, null, null);
    }

    /** With how the tablet looks (migration 0042), so a parent who cannot read the strip can still pick it: "🔵 Blue round tablet". */
    public static String doseReminder(String language, String callName, String medicine, String pillColour, String pillShape) {
        String med = clean(medicine, 60);
        String look = pillLook(language, pillColour, pillShape);
        String lookLine = look.isEmpty() ? "" : "\n" + look;
        if (hi(language)) {
            return "नमस्ते" + who(callName) + " 🙏\nदवा लेने का समय हो गया है 💊" + (med.isEmpty() ? "" : " *" + med + "*")
                    + lookLine + "\n\nलेने के बाद *1* लिखकर भेजिए ✅";
        }
        return "Namaste" + who(callName) + " 🙏\nIt's time for your medicine 💊" + (med.isEmpty() ? "" : " *" + med + "*")
                + lookLine + "\n\nReply *1* once you have taken it ✅";
    }

    // Colour words in English, Hindi with a feminine noun (गोली) and Hindi with a masculine noun (कैप्सूल), plus a coloured dot.
    // Only these fixed values are used (the database refuses anything else), so no caregiver text reaches the parent here.
    private static final Map<String, String[]> COLOURS = Map.of(
            "white", new String[] {"White", "सफ़ेद", "सफ़ेद", "⚪"},
            "yellow", new String[] {"Yellow", "पीली", "पीला", "🟡"},
            "orange", new String[] {"Orange", "नारंगी", "नारंगी", "🟠"},
            "pink", new String[] {"Pink", "गुलाबी", "गुलाबी", ""},   // no pink dot that older phones can show
            "red", new String[] {"Red", "लाल", "लाल", "🔴"},
            "blue", new String[] {"Blue", "नीली", "नीला", "🔵"},
            "green", new String[] {"Green", "हरी", "हरा", "🟢"},
            "brown", new String[] {"Brown", "भूरी", "भूरा", "🟤"},
            "purple", new String[] {"Purple", "बैंगनी", "बैंगनी", "🟣"});
    // English words, Hindi words (the noun is included), whether the Hindi noun is masculine.
    private static final Map<String, String[]> SHAPES = Map.of(
            "round", new String[] {"round tablet", "गोल गोली", ""},
            "oval", new String[] {"oval tablet", "अंडाकार गोली", ""},
            "long", new String[] {"long tablet", "लंबी गोली", ""},
            "capsule", new String[] {"capsule", "कैप्सूल", "m"});

    /** "🔵 Blue round tablet" / "🔵 नीली गोल गोली"; colour only: "🔵 Colour: blue"; nothing known: "". */
    static String pillLook(String language, String pillColour, String pillShape) {
        String[] c = pillColour == null ? null : COLOURS.get(pillColour);
        String[] sh = pillShape == null ? null : SHAPES.get(pillShape);
        if (c == null && sh == null) return "";
        String dot = c == null || c[3].isEmpty() ? "" : c[3] + " ";
        if (hi(language)) {
            if (sh == null) return dot + "रंग: " + c[2];
            String colour = c == null ? "" : ("m".equals(sh[2]) ? c[2] : c[1]) + " ";
            return dot + colour + sh[1];
        }
        if (sh == null) return dot + "Colour: " + c[0].toLowerCase(Locale.ROOT);
        String text = c == null ? sh[0] : c[0] + " " + sh[0];
        return dot + Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    public static String checkin(String language, String callName) {
        if (hi(language)) {
            return "सुप्रभात" + who(callName) + " ☀️🙏\nआज आप कैसे हैं?\n\nठीक हैं तो *1* लिखें 😊\nफ़ोन चाहिए तो *2* लिखें 📞";
        }
        return "Good morning" + who(callName) + " ☀️🙏\nHow are you today?\n\nReply *1* if you are fine 😊\nReply *2* if you want a call 📞";
    }

    /** The first message to a parent: asks for agreement (consent) before anything else is ever sent. */
    public static String consentRequest(String language, String callName, String caregiverName) {
        String by = clean(caregiverName, 40);
        if (hi(language)) {
            return "नमस्ते" + who(callName) + " 🙏\n"
                    + (by.isEmpty() ? "आपका परिवार आपको WhatsApp पर दवा के हल्के रिमाइंडर भेजना चाहता है।"
                                    : by + " आपको WhatsApp पर दवा के हल्के रिमाइंडर भेजना चाहते हैं।")
                    + "\n\nशुरू करने के लिए *1* लिखें ✅\nनहीं चाहिए तो *2* लिखें\n\nकभी भी STOP लिखकर रोक सकते हैं।";
        }
        return "Namaste" + who(callName) + " 🙏\n" + (by.isEmpty() ? "Your family" : by)
                + " would like to send you gentle medicine reminders on WhatsApp."
                + "\n\nReply *1* to start ✅\nReply *2* if you do not want this\n\nYou can reply STOP any time.";
    }

    /** For the caregiver's own profile ("Me"): they add their own number for their own medicines. */
    public static String selfConsentRequest(String language, String firstName) {
        String n = clean(firstName, 40);
        String name = n.isEmpty() ? "" : " " + n;
        if (hi(language)) {
            return "नमस्ते" + name + " 🙏\nआपने अपनी दवाओं के रिमाइंडर के लिए अपना नंबर जोड़ा है।"
                    + "\n\nशुरू करने के लिए *1* लिखें ✅\nनहीं चाहिए तो *2* लिखें\n\nकभी भी STOP लिखकर रोक सकते हैं।";
        }
        return "Namaste" + name + " 🙏\nYou added this number for your own medicine reminders on Chhaon."
                + "\n\nReply *1* to start ✅\nReply *2* if you do not want this\n\nYou can reply STOP any time.";
    }

    /** Asks a caregiver to confirm WhatsApp alerts about their parent. */
    public static String alertOptIn(String language, String firstName) {
        String n = clean(firstName, 40);
        String name = n.isEmpty() ? "" : " " + n;
        if (hi(language)) {
            return "नमस्ते" + name + " 🙏\nछाँव आपको यहाँ ज़रूरी अलर्ट भेजना चाहता है (जैसे माता-पिता की छूटी हुई दवा)।"
                    + "\n\nचालू करने के लिए *1* लिखें ✅\nनहीं चाहिए तो *2* लिखें\n\nकभी भी STOP लिखकर रोक सकते हैं।";
        }
        return "Namaste" + name + " 🙏\nChhaon would like to send you important alerts here (for example a missed medicine of your parent)."
                + "\n\nReply *1* to turn alerts on ✅\nReply *2* if you do not want this\n\nYou can reply STOP any time.";
    }

    /**
     * The login one-time code, delivered over WhatsApp (Supabase "Send SMS" hook). Bilingual, because at login time
     * we do not yet know the user's language. The code is Supabase-generated digits; it is sanitised defensively so a
     * surprising value can never inject WhatsApp formatting.
     */
    public static String loginOtp(String code) {
        String c = clean(code, 12);
        return "Chhaon login code: *" + c + "*\nDo not share this code with anyone."
                + "\n\nछाँव लॉगिन कोड: *" + c + "*\nयह कोड किसी के साथ साझा न करें।";
    }
}
