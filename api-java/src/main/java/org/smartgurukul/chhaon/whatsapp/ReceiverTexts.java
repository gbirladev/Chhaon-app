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

    /**
     * SQL for the language of the WhatsApp texts the receiver reads, for a care_recipients row aliased {@code alias}: a helper or a
     * family member may read another language than the person (care_recipients.reminder_language, en | hi, null = the person's
     * language). Read through to_jsonb so it works before that column exists.
     */
    public static String languageSql(String alias) {
        return "(case when " + alias + ".reminder_to in ('caregiver', 'helper') then coalesce(nullif(to_jsonb(" + alias
                + ") ->> 'reminder_language', ''), " + alias + ".language) else " + alias + ".language end)";
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
     * The dose reminder to a helper or the caregiver, with the dose and the pill look when known (wording in {@link ReminderTexts}):
     * "Namaste Sunita ji 🙏 / Time for Papa's medicine: *Metformin* / 500 mg · 🔵 Blue round tablet / / Given it to Papa? Just reply *1*".
     */
    public static String doseReminder(String language, String reminderTo, String receiverName, String person, String medicine,
                                      String pillColour, String pillShape) {
        return doseReminder(language, reminderTo, receiverName, person, medicine, pillColour, pillShape, null);
    }

    public static String doseReminder(String language, String reminderTo, String receiverName, String person, String medicine,
                                      String pillColour, String pillShape, String dosage) {
        return ReminderTexts.doseText(ReminderTexts.Reader.receiver(language, reminderTo, receiverName, person),
                java.util.List.of(new ReminderTexts.Item(medicine, dosage, pillColour, pillShape)), null);
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
            return hello + body + "\n\nशुरू करने के लिए *1* या 👍 भेजें ✅\nनहीं चाहिए तो *2* लिखें\n\nकभी भी STOP लिखकर रोक सकते हैं।";
        }
        String body;
        if (CAREGIVER.equals(reminderTo)) {
            body = "You chose to get " + possessive(p) + " medicine reminders here on WhatsApp.";
        } else {
            body = (by.isEmpty() ? possessive(p) + " family" : by) + " would like to send you reminders for "
                    + possessive(p) + " medicines here on WhatsApp.";
        }
        return "Namaste" + greet(language, reminderTo, receiverName) + " 🙏\n" + body
                + "\n\nReply *1* or 👍 to start ✅\nReply *2* if you do not want this\n\nYou can reply STOP any time.";
    }

    /**
     * A child's vaccine reminder to the adult who receives the child's reminders (never the child), about 3 days before the due date
     * and on the day: "Namaste Kamla ji 🙏 / 💉 Aarav's vaccine *MMR (dose 1)* is due on *Tue 11 Nov*, as advised by your doctor. /
     * Please remind the family, or take Aarav to the clinic. / No reply needed 🙏". Several doses due on one date are ONE message
     * ("vaccines *DTP (dose 1)*, *Hep B (dose 2)* are due"). No links, no numbers to call.
     *
     * @param vaccines the doses, each already worded by {@link #vaccineTitle}
     * @param today    true on the due day itself ("is due *today*")
     */
    public static String vaccineReminder(String language, String reminderTo, String receiverName, String child,
                                         java.util.List<String> vaccines, java.time.LocalDate due, boolean today) {
        boolean h = hi(language);
        String c = MessageTexts.clean(child, 40);
        java.util.List<String> list = vaccines.stream().map(v -> MessageTexts.clean(v, 60)).filter(v -> !v.isEmpty())
                .limit(8).map(v -> "*" + v + "*").toList();
        boolean many = list.size() > 1;
        String what = String.join(", ", list);
        String date = today ? (h ? "*आज*" : "*today*")
                : "*" + due.format(java.time.format.DateTimeFormatter.ofPattern(h ? "EEEE, d MMMM" : "EEE d MMM",
                        h ? Locale.forLanguageTag("hi-IN") : Locale.UK)) + "*";
        boolean helper = !CAREGIVER.equals(reminderTo);
        if (h) {
            String whose = c.isEmpty() ? "बच्चे" : c;
            return "नमस्ते" + greet(language, reminderTo, receiverName) + " 🙏\n💉 " + whose + (many ? " के टीके " : " का टीका ") + what + " "
                    + date + (today ? "" : " को") + (many ? " लगने हैं" : " लगना है") + ", जैसा डॉक्टर ने बताया है।\n"
                    + (helper ? "कृपया परिवार को याद दिला दें, या " + whose + " को क्लिनिक ले जाएँ।"
                              : "कृपया " + whose + " को क्लिनिक ले जाएँ।")
                    + "\n\nजवाब देने की ज़रूरत नहीं 🙏";
        }
        String whose = c.isEmpty() ? "the child" : c;
        return "Namaste" + greet(language, reminderTo, receiverName) + " 🙏\n💉 " + (c.isEmpty() ? "The child's" : c + "'s")
                + (many ? " vaccines " : " vaccine ") + what + (many ? " are" : " is") + " due " + (today ? "" : "on ") + date
                + ", as advised by your doctor.\n"
                + (helper ? "Please remind the family, or take " + whose + " to the clinic." : "Please take " + whose + " to the clinic.")
                + "\n\nNo reply needed 🙏";
    }

    /**
     * "MMR (dose 1)" / "एमएमआर (खुराक 1)", "Typhoid (booster)", "BCG" for a single dose. The plan's long name is shortened to the part
     * before its explanation in brackets ("MMR (measles, mumps, rubella)" becomes "MMR"), so a message with several doses stays short.
     */
    public static String vaccineTitle(String language, String vaccine, String doseLabel) {
        String full = vaccine == null ? "" : vaccine;
        int bracket = full.indexOf(" (");
        String v = MessageTexts.clean(bracket > 0 ? full.substring(0, bracket) : full, 50);
        String d = vaccineDose(language, doseLabel);
        return d.isEmpty() ? v : v + " (" + d + ")";
    }

    /** "dose 1" / "खुराक 1", "booster 2" / "बूस्टर 2", "booster" / "बूस्टर", "" (same rule as web/src/lib/vaccines.ts doseText). */
    static String vaccineDose(String language, String label) {
        String l = MessageTexts.clean(label, 20);
        if (l.isEmpty()) return "";
        java.util.regex.Matcher b = java.util.regex.Pattern.compile("^B(\\d*)$").matcher(l);
        if (b.matches()) {
            String n = b.group(1);
            return (hi(language) ? "बूस्टर" : "booster") + (n.isEmpty() ? "" : " " + n);
        }
        return (hi(language) ? "खुराक " : "dose ") + l;
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
                    ? "ठीक है 🙏 जब " + p + " दवा ले लें, तो 1 लिखकर भेज दीजिए।"
                    : "Okay 🙏 Please reply 1 once " + p + " has taken the medicine.";
        }
        return hi(language)
                ? "ठीक है 🙏 " + p + " को दवा देने के बाद 1 लिखकर भेज दीजिए।"
                : "Okay 🙏 Please reply 1 once you have given " + p + " the medicine.";
    }

    /** A "⏰ Later" vote on a helper's / caregiver's reminder poll: tap ✅ once Papa has had the medicine. */
    public static String dosePollLater(String language, String reminderTo, String person) {
        String p = MessageTexts.clean(person, 40);
        if (CAREGIVER.equals(reminderTo)) {
            return hi(language)
                    ? "ठीक है 🙏 जब " + p + " दवा ले लें, तो ✅ चुन लीजिए"
                    : "Okay 🙏 Tap ✅ once " + p + " has taken the medicine.";
        }
        return hi(language)
                ? "ठीक है 🙏 " + p + " को दवा देने के बाद ✅ चुन लीजिए"
                : "Okay 🙏 Tap ✅ once you have given " + p + " the medicine.";
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
