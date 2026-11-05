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
        return doseReminder(language, callName, medicine, null, null, null);
    }

    public static String doseReminder(String language, String callName, String medicine, String pillColour, String pillShape) {
        return doseReminder(language, callName, medicine, pillColour, pillShape, null);
    }

    /**
     * With how the tablet looks (migration 0042), so a parent who cannot read the strip can still pick it ("🔵 Blue round tablet"),
     * and the dose the family typed ("Dose: *500 mg*", R2). No dose stored = no dose line. The dose is shown as stored, only the
     * unit word is translated ({@link #localizeDose}); it is never computed or changed.
     */
    public static String doseReminder(String language, String callName, String medicine, String pillColour, String pillShape,
                                      String dosage) {
        String med = clean(medicine, 60);
        String extra = doseLine(language, dosage) + lookLine(language, pillColour, pillShape);
        if (hi(language)) {
            return "नमस्ते" + who(callName) + " 🙏\nदवा लेने का समय हो गया है 💊" + (med.isEmpty() ? "" : " *" + med + "*")
                    + extra + "\n\nलेने के बाद *1* या 👍 भेजिए ✅";
        }
        return "Namaste" + who(callName) + " 🙏\nIt's time for your medicine 💊" + (med.isEmpty() ? "" : " *" + med + "*")
                + extra + "\n\nReply *1* or 👍 once you have taken it ✅";
    }

    /**
     * For a pregnancy (care_type = pregnant, migration 0043): a greeting for the time of day, the new pregnancy week ONCE a week,
     * then the medicine and its dose. Same pill-look line and the same reply as for everyone (*1* = taken), so the reply classifier
     * is unchanged. Example: "Good morning Shivani ☀️\nWeek 24 starts today 💛\nTime for your *Iron + folic acid* 💊\nDose: *1 tablet*".
     *
     * <p>Decision (R2): the week is said only in the FIRST reminder sent on the day a new week starts (the scheduler decides, see
     * SchedulerService#weekToSay); every other reminder and the daily check-in leave it out, so "Week 24" is not repeated twice a day.
     *
     * @param newWeek   the week that starts today when this is the first reminder of that day, else null (no week line)
     * @param localHour the hour on the PERSON's clock (0-23), for good morning / afternoon / evening
     */
    public static String pregnancyDoseReminder(String language, String callName, String medicine, String pillColour,
                                               String pillShape, Integer newWeek, int localHour) {
        return pregnancyDoseReminder(language, callName, medicine, pillColour, pillShape, null, newWeek, localHour);
    }

    public static String pregnancyDoseReminder(String language, String callName, String medicine, String pillColour,
                                               String pillShape, String dosage, Integer newWeek, int localHour) {
        String med = clean(medicine, 60);
        String extra = doseLine(language, dosage) + lookLine(language, pillColour, pillShape);
        String head = greeting(language, callName, localHour) + weekLine(language, newWeek);
        if (hi(language)) {
            return head + "\n" + (med.isEmpty() ? "दवा" : "*" + med + "*") + " लेने का समय हो गया है 💊"
                    + extra + "\n\nलेने के बाद *1* या 👍 भेजिए ✅";
        }
        return head + "\nTime for your " + (med.isEmpty() ? "medicine" : "*" + med + "*") + " 💊"
                + extra + "\n\nReply *1* or 👍 once you have taken it ✅";
    }

    /** The gentler daily check-in for a pregnancy: same answers as everyone (*1* fine, *2* call me). No week line (see above). */
    public static String pregnancyCheckin(String language, String callName) {
        String head = greeting(language, callName, 8);
        if (hi(language)) {
            return head + "\nआज आप कैसा महसूस कर रही हैं?\n\nठीक हैं तो *1* या 👍 भेजें 😊\nफ़ोन चाहिए तो *2* लिखें 📞";
        }
        return head + "\nHow are you feeling today?\n\nReply *1* or 👍 if you are fine 😊\nReply *2* if you want a call 📞";
    }

    /** "\n🔵 Blue round tablet" or "". */
    static String lookLine(String language, String pillColour, String pillShape) {
        String look = pillLook(language, pillColour, pillShape);
        return look.isEmpty() ? "" : "\n" + look;
    }

    /** "\nDose: *2.5 ml*" / "\nमात्रा: *2.5 ml*", or "" when no dose is stored (or it is not safe to show). */
    public static String doseLine(String language, String dosage) {
        String d = localizeDose(language, dosage);
        if (d.isEmpty()) return "";
        return hi(language) ? "\nमात्रा: *" + d + "*" : "\nDose: *" + d + "*";
    }

    private static final Pattern DOSE = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s+([a-z]+?)s?$");
    /** A run of digits this long is a phone number, not a dose: such a "dose" is never sent (owner's fraud rule). */
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{6,}");
    // unit -> {English one, English other, Hindi one, Hindi other}; same units and words as web/src/lib/format.ts localizeDose.
    private static final Map<String, String[]> UNITS = Map.of(
            "mg", new String[] {"mg", "mg", "mg", "mg"},
            "mcg", new String[] {"mcg", "mcg", "mcg", "mcg"},
            "g", new String[] {"g", "g", "g", "g"},
            "ml", new String[] {"ml", "ml", "ml", "ml"},
            "tablet", new String[] {"tablet", "tablets", "गोली", "गोलियाँ"},
            "capsule", new String[] {"capsule", "capsules", "कैप्सूल", "कैप्सूल"},
            "drop", new String[] {"drop", "drops", "बूँद", "बूँदें"},
            "unit", new String[] {"unit", "units", "यूनिट", "यूनिट"},
            "puff", new String[] {"puff", "puffs", "पफ़", "पफ़"},
            "tsp", new String[] {"tsp", "tsp", "चम्मच", "चम्मच"});

    /**
     * The stored dose ("2.5 ml", "1 tablet", "2 tablets": canonical English from the app) in the receiver's language: "1 गोली",
     * "2 गोलियाँ", "2.5 ml". Anything else the family typed is shown as typed (cleaned). Never computes or changes the amount.
     */
    public static String localizeDose(String language, String dosage) {
        String d = clean(dosage, 30);
        if (d.isEmpty() || LONG_NUMBER.matcher(d).find()) return "";
        java.util.regex.Matcher m = DOSE.matcher(d.toLowerCase(Locale.ROOT));
        if (!m.matches()) return d;
        String[] words = UNITS.get(m.group(2));
        if (words == null) return d;
        double n;
        try {
            n = Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return d;
        }
        boolean one = hi(language) ? n <= 1 : n == 1;
        return m.group(1) + " " + words[(hi(language) ? 2 : 0) + (one ? 0 : 1)];
    }

    /** "Good morning Shivani ☀️" / "सुप्रभात Shivani ☀️", by the hour on the person's clock. */
    private static String greeting(String language, String callName, int localHour) {
        String n = who(callName);
        boolean morning = localHour >= 4 && localHour < 12;
        boolean afternoon = localHour >= 12 && localHour < 17;
        if (hi(language)) {
            return morning ? "सुप्रभात" + n + " ☀️" : afternoon ? "नमस्ते" + n + " 🌤️" : "शुभ संध्या" + n + " 🌙";
        }
        return morning ? "Good morning" + n + " ☀️" : afternoon ? "Good afternoon" + n + " 🌤️" : "Good evening" + n + " 🌙";
    }

    /** "\nWeek 24 starts today 💛" / "\nआज से आपका 24वाँ हफ़्ता शुरू हो रहा है 💛", or "" without a week. */
    private static String weekLine(String language, Integer week) {
        if (week == null || week < 1 || week > 42) return "";
        return hi(language) ? "\nआज से आपका " + hindiOrdinal(week) + " हफ़्ता शुरू हो रहा है 💛" : "\nWeek " + week + " starts today 💛";
    }

    /** 1 पहला, 2 दूसरा, 3 तीसरा, 4 चौथा, 6 छठा, otherwise 24वाँ. */
    static String hindiOrdinal(int n) {
        return switch (n) {
            case 1 -> "पहला";
            case 2 -> "दूसरा";
            case 3 -> "तीसरा";
            case 4 -> "चौथा";
            case 6 -> "छठा";
            default -> n + "वाँ";
        };
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
            return "सुप्रभात" + who(callName) + " ☀️🙏\nआज आप कैसे हैं?\n\nठीक हैं तो *1* या 👍 भेजें 😊\nफ़ोन चाहिए तो *2* लिखें 📞";
        }
        return "Good morning" + who(callName) + " ☀️🙏\nHow are you today?\n\nReply *1* or 👍 if you are fine 😊\nReply *2* if you want a call 📞";
    }

    /** The first message to a parent: asks for agreement (consent) before anything else is ever sent. */
    public static String consentRequest(String language, String callName, String caregiverName) {
        String by = clean(caregiverName, 40);
        if (hi(language)) {
            return "नमस्ते" + who(callName) + " 🙏\n"
                    + (by.isEmpty() ? "आपका परिवार आपको WhatsApp पर दवा के हल्के रिमाइंडर भेजना चाहता है।"
                                    : by + " आपको WhatsApp पर दवा के हल्के रिमाइंडर भेजना चाहते हैं।")
                    + "\n\nशुरू करने के लिए *1* या 👍 भेजें ✅\nनहीं चाहिए तो *2* लिखें\n\nकभी भी STOP लिखकर रोक सकते हैं।";
        }
        return "Namaste" + who(callName) + " 🙏\n" + (by.isEmpty() ? "Your family" : by)
                + " would like to send you gentle medicine reminders on WhatsApp."
                + "\n\nReply *1* or 👍 to start ✅\nReply *2* if you do not want this\n\nYou can reply STOP any time.";
    }

    /** For the caregiver's own profile ("Me"): they add their own number for their own medicines. */
    public static String selfConsentRequest(String language, String firstName) {
        String n = clean(firstName, 40);
        String name = n.isEmpty() ? "" : " " + n;
        if (hi(language)) {
            return "नमस्ते" + name + " 🙏\nआपने अपनी दवाओं के रिमाइंडर के लिए अपना नंबर जोड़ा है।"
                    + "\n\nशुरू करने के लिए *1* या 👍 भेजें ✅\nनहीं चाहिए तो *2* लिखें\n\nकभी भी STOP लिखकर रोक सकते हैं।";
        }
        return "Namaste" + name + " 🙏\nYou added this number for your own medicine reminders on Chhaon."
                + "\n\nReply *1* or 👍 to start ✅\nReply *2* if you do not want this\n\nYou can reply STOP any time.";
    }

    /** Asks a caregiver to confirm WhatsApp alerts about their parent. */
    public static String alertOptIn(String language, String firstName) {
        String n = clean(firstName, 40);
        String name = n.isEmpty() ? "" : " " + n;
        if (hi(language)) {
            return "नमस्ते" + name + " 🙏\nछाँव आपको यहाँ ज़रूरी अलर्ट भेजना चाहता है (जैसे परिवार में किसी की छूटी हुई दवा)।"
                    + "\n\nचालू करने के लिए *1* लिखें ✅\nनहीं चाहिए तो *2* लिखें\n\nकभी भी STOP लिखकर रोक सकते हैं।";
        }
        return "Namaste" + name + " 🙏\nChhaon would like to send you important alerts here (for example a missed medicine in your family)."
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
