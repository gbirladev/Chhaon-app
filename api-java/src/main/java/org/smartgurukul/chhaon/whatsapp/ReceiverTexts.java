package org.smartgurukul.chhaon.whatsapp;

import java.util.Locale;

/**
 * WhatsApp texts for when the reminders do NOT go to the person themself (care_recipients.reminder_to = caregiver | helper,
 * docs/reminder-receiver.md). They are written in the third person and always NAME the person, because one number may get
 * reminders for two people (a maid for Papa and Mummy) or for the caregiver's own medicines too.
 *
 * <p>Same rules as {@link MessageTexts}: short, warm, respectful; every message we start ends with how to reply
 * (1 = given / yes, 2 = no / not yet, STOP any time); no links, payments, codes or numbers to call. Names are cleaned with
 * {@link MessageTexts#clean}. Hindi needs a native review (docs/hindi-review.md).
 *
 * <p>For {@code reminder_to = person} nothing here is used: {@link MessageTexts} and the reply texts stay exactly as before.
 * TODO(care-type): these are written for an older parent ("Time for Papa's medicine"). A child's reminder ("Time for
 * Aarav's syrup") and pregnancy wording may need their own variants once care types exist.
 */
public final class ReceiverTexts {

    public static final String PERSON = "person";
    public static final String CAREGIVER = "caregiver";
    public static final String HELPER = "helper";

    private ReceiverTexts() {}

    private static boolean hi(String language) {
        return "hi".equals(language);
    }

    /** True when the reminders go to someone other than the person (a null / unknown value is the person, as before). */
    public static boolean notThePerson(String reminderTo) {
        return CAREGIVER.equals(reminderTo) || HELPER.equals(reminderTo);
    }

    /** What the receiver calls the person: the family's call name, else the name; "" when unknown. */
    public static String personName(String callName, String name) {
        String c = MessageTexts.clean(callName, 40);
        return c.isEmpty() ? MessageTexts.clean(name, 40) : c;
    }

    /**
     * " Sunita ji" / " सुनीता जी" for a helper (respectful "ji" unless they already wrote it), " Ganesh" (first name) for the
     * caregiver, "" when no name is known.
     */
    static String greet(String language, String reminderTo, String receiverName) {
        String n = MessageTexts.clean(receiverName, 40);
        if (n.isEmpty()) return "";
        if (CAREGIVER.equals(reminderTo)) return " " + n.split("\\s+")[0];
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.endsWith("ji") || n.endsWith("जी")) return " " + n;
        return " " + n + (hi(language) ? " जी" : " ji");
    }

    /** "Papa's" (English possessive); "the" when no name is known (the person's name is required, so this is a safety net). */
    private static String possessive(String person) {
        return person.isEmpty() ? "the" : person + "'s";
    }

    // ---------------------------------------------------------------- messages we start

    /**
     * The dose reminder to a helper or the caregiver, with the pill-look line when known:
     * "Namaste Sunita ji 🙏 / Time for Papa's medicine 💊 *Metformin* / 🔵 Blue round tablet / Reply *1* or 👍 once you have given it ✅".
     */
    public static String doseReminder(String language, String reminderTo, String receiverName, String person, String medicine,
                                      String pillColour, String pillShape) {
        String p = MessageTexts.clean(person, 40);
        String med = MessageTexts.clean(medicine, 60);
        String look = MessageTexts.pillLook(language, pillColour, pillShape);
        String lookLine = look.isEmpty() ? "" : "\n" + look;
        boolean helper = !CAREGIVER.equals(reminderTo);
        if (hi(language)) {
            return "नमस्ते" + greet(language, reminderTo, receiverName) + " 🙏\n"
                    + (p.isEmpty() ? "" : p + " की ") + "दवा का समय हो गया है 💊" + (med.isEmpty() ? "" : " *" + med + "*")
                    + lookLine
                    + (helper ? "\n\nदवा देने के बाद *1* या 👍 भेजिए ✅"
                              : "\n\n" + (p.isEmpty() ? "दवा" : p + " के दवा") + " लेने के बाद *1* या 👍 भेजिए ✅");
        }
        return "Namaste" + greet(language, reminderTo, receiverName) + " 🙏\nTime for " + possessive(p) + " medicine 💊"
                + (med.isEmpty() ? "" : " *" + med + "*") + lookLine
                + (helper ? "\n\nReply *1* or 👍 once you have given it ✅"
                          : "\n\nReply *1* or 👍 once " + (p.isEmpty() ? "it has been taken" : p + " has taken it") + " ✅");
    }

    /** The first message to a new receiver: on whose behalf, for whom, and how to agree. */
    public static String consentRequest(String language, String reminderTo, String receiverName, String person, String caregiverName) {
        String p = MessageTexts.clean(person, 40);
        String by = MessageTexts.clean(caregiverName, 40);
        if (hi(language)) {
            String hello = "नमस्ते" + greet(language, reminderTo, receiverName) + " 🙏\n";
            String body;
            if (CAREGIVER.equals(reminderTo)) {
                body = "आपने " + p + " की दवाओं के रिमाइंडर यहाँ WhatsApp पर पाना चुना है।";
            } else {
                body = by.isEmpty()
                        ? p + " का परिवार आपको यहाँ WhatsApp पर " + p + " की दवाओं के रिमाइंडर भेजना चाहता है।"
                        : by + " आपको यहाँ WhatsApp पर " + p + " की दवाओं के रिमाइंडर भेजना चाहते हैं।";
            }
            return hello + body + "\n\nशुरू करने के लिए *1* लिखें ✅\nनहीं चाहिए तो *2* लिखें\n\nकभी भी STOP लिखकर रोक सकते हैं।";
        }
        String body;
        if (CAREGIVER.equals(reminderTo)) {
            body = "You chose to get " + possessive(p) + " medicine reminders here on WhatsApp.";
        } else {
            body = (by.isEmpty() ? possessive(p) + " family" : by) + " would like to send you reminders for "
                    + possessive(p) + " medicines here on WhatsApp.";
        }
        return "Namaste" + greet(language, reminderTo, receiverName) + " 🙏\n" + body
                + "\n\nReply *1* to start ✅\nReply *2* if you do not want this\n\nYou can reply STOP any time.";
    }

    // ---------------------------------------------------------------- answers to the receiver's replies

    public static String consentYes(String language, String person) {
        String p = MessageTexts.clean(person, 40);
        return hi(language)
                ? "धन्यवाद 🙏 अब आपको यहाँ " + p + " की दवा के रिमाइंडर मिलेंगे। रोकने के लिए कभी भी STOP लिखें।"
                : "Thank you 🙏 You will now get " + possessive(p) + " medicine reminders here. Reply STOP any time to stop.";
    }

    /** After a dose was recorded: names the person, so a number that serves two people knows whose medicine was noted. */
    public static String doseTaken(String language, String person, String medicine) {
        String p = MessageTexts.clean(person, 40);
        String med = medicine == null || medicine.isBlank() ? "" : " " + medicine.trim();
        return hi(language)
                ? "धन्यवाद 🙏 " + p + " की दवा" + med + " नोट कर ली ✅"
                : "Thank you 🙏 Noted for " + p + ":" + (med.isEmpty() ? " the medicine" : med) + " ✅";
    }

    public static String doseNotYet(String language, String reminderTo, String person) {
        String p = MessageTexts.clean(person, 40);
        if (CAREGIVER.equals(reminderTo)) {
            return hi(language)
                    ? "ठीक है 🙏 " + p + " के दवा लेने के बाद 1 लिखकर भेज दीजिए।"
                    : "Okay 🙏 Please reply 1 once " + p + " has taken the medicine.";
        }
        return hi(language)
                ? "ठीक है 🙏 " + p + " को दवा देने के बाद 1 लिखकर भेज दीजिए।"
                : "Okay 🙏 Please reply 1 once you have given " + p + " the medicine.";
    }

    // ---------------------------------------------------------------- the family's alerts

    /**
     * How a family alert names who was reminded: "you" / "आप" when the alert goes to the caregiver who gets the reminders,
     * "Sunita (maid)" for a helper, the caregiver's first name otherwise. Null when the reminders go to the person themself.
     */
    public static String receiverLabel(String language, String reminderTo, String receiverName, String relation, boolean isYou) {
        if (!notThePerson(reminderTo)) return null;
        if (CAREGIVER.equals(reminderTo)) {
            if (isYou) return hi(language) ? "आप" : "you";
            String n = MessageTexts.clean(receiverName, 40);
            return n.isEmpty() ? (hi(language) ? "परिवार के एक सदस्य" : "a family member") : n.split("\\s+")[0];
        }
        String n = MessageTexts.clean(receiverName, 40);
        String rel = MessageTexts.clean(relation, 30);
        if (n.isEmpty()) n = hi(language) ? "सहायक" : "the helper";
        return rel.isEmpty() ? n : n + " (" + rel + ")";
    }
}
