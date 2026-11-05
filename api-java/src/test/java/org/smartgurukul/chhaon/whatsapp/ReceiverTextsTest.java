package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.whatsapp.reply.Intent;
import org.smartgurukul.chhaon.whatsapp.reply.ReplyClassifier;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Texts for a helper / the caregiver receiving the reminders (docs/reminder-receiver.md). No database. */
class ReceiverTextsTest {

    private static final Pattern DEVANAGARI = Pattern.compile("[\\u0900-\\u097F]");
    /** The owner's fraud rule: no links, no payments, no codes, no numbers to call. */
    private static final Pattern FORBIDDEN = Pattern.compile("(?i)https?://|www\\.|\\bpay\\b|upi|otp|\\+?\\d{7,}");

    private static void clean(String text) {
        assertThat(text).doesNotContain("{", "}", "%s", "null", "undefined", "\n\n\n").isNotBlank();
        assertThat(text.length()).as("short enough for a chat: " + text).isLessThan(330);
        assertThat(FORBIDDEN.matcher(text).find()).as(text).isFalse();
    }

    @Test
    void helperReminder_english_andHindi() {
        String en = ReceiverTexts.doseReminder("en", "helper", "Sunita", "Papa", "Metformin", "white", "round");
        clean(en);
        assertThat(en).isEqualTo("Namaste Sunita ji 🙏\nTime for Papa's medicine 💊 *Metformin*\n⚪ White round tablet\n\n"
                + "Reply *1* or 👍 once you have given it ✅");
        String hi = ReceiverTexts.doseReminder("hi", "helper", "सुनीता", "पापा", "Metformin", null, null);
        clean(hi);
        assertThat(hi).isEqualTo("नमस्ते सुनीता जी 🙏\nपापा की दवा का समय हो गया है 💊 *Metformin*\n\nदवा देने के बाद *1* या 👍 भेजिए ✅");
    }

    @Test
    void caregiverReminderUsesTheFirstNameAndAsksOnceTaken() {
        String en = ReceiverTexts.doseReminder("en", "caregiver", "Ganesh Sharma", "Papa", "Metformin", null, null);
        clean(en);
        assertThat(en).startsWith("Namaste Ganesh 🙏").endsWith("Reply *1* or 👍 once Papa has taken it ✅").doesNotContain(" ji");
        String hi = ReceiverTexts.doseReminder("hi", "caregiver", "Ganesh", "पापा", "Metformin", null, null);
        assertThat(hi).endsWith("\n\nजब पापा दवा ले लें, तो *1* या 👍 भेजें ✅").doesNotContain("के दवा");
        assertThat(ReceiverTexts.doseNotYet("hi", "caregiver", "पापा")).isEqualTo("ठीक है 🙏 जब पापा दवा ले लें, तो 1 लिखकर भेज दीजिए।");
    }

    @Test
    void everyReceiverReminderSaysTheDose() {
        String nani = ReceiverTexts.doseReminder("hi", "helper", "Kamla", "Aarav", "Calpol syrup", null, null, "2.5 ml");
        clean(nani);
        assertThat(nani).isEqualTo("नमस्ते Kamla जी 🙏\nAarav की दवा का समय हो गया है 💊 *Calpol syrup*\nमात्रा: *2.5 ml*\n\n"
                + "दवा देने के बाद *1* या 👍 भेजिए ✅");
        String maid = ReceiverTexts.doseReminder("en", "helper", "Sunita", "Papa", "Metformin", "white", "round", "500 mg");
        clean(maid);
        assertThat(maid).isEqualTo("Namaste Sunita ji 🙏\nTime for Papa's medicine 💊 *Metformin*\nDose: *500 mg*\n⚪ White round tablet\n\n"
                + "Reply *1* or 👍 once you have given it ✅");
        String me = ReceiverTexts.doseReminder("hi", "caregiver", "Ganesh", "Papa", "Thyronorm", null, null, "1 tablet");
        clean(me);
        assertThat(me).contains("\nमात्रा: *1 गोली*\n\nजब Papa दवा ले लें, तो *1* या 👍 भेजें ✅");
        // no dose stored: no dose line
        assertThat(ReceiverTexts.doseReminder("en", "helper", "Sunita", "Papa", "Metformin", null, null, null)).doesNotContain("Dose");
        assertThat(ReceiverTexts.doseReminder("en", "helper", "Sunita", "Papa", "Metformin", null, null, " ")).doesNotContain("Dose");
    }

    @Test
    void vaccineReminderToTheReceiver_englishAndHindi() {
        java.time.LocalDate tue = java.time.LocalDate.of(2026, 11, 10);
        String en = ReceiverTexts.vaccineReminder("en", "helper", "Kamla", "Aarav",
                List.of(ReceiverTexts.vaccineTitle("en", "MMR", "1")), tue, false);
        clean(en);
        assertThat(en).isEqualTo("Namaste Kamla ji 🙏\n💉 Aarav's vaccine *MMR (dose 1)* is due on *Tue 10 Nov*, as advised by your doctor.\n"
                + "Please remind the family, or take Aarav to the clinic.\n\nNo reply needed 🙏");
        String today = ReceiverTexts.vaccineReminder("en", "caregiver", "Priya Sharma", "Aarav",
                List.of(ReceiverTexts.vaccineTitle("en", "DTP", "1"), ReceiverTexts.vaccineTitle("en", "Typhoid", "B")), tue, true);
        clean(today);
        assertThat(today).isEqualTo("Namaste Priya 🙏\n💉 Aarav's vaccines *DTP (dose 1)*, *Typhoid (booster)* are due *today*, as advised by your doctor.\n"
                + "Please take Aarav to the clinic.\n\nNo reply needed 🙏");
        String hi = ReceiverTexts.vaccineReminder("hi", "helper", "Kamla", "Aarav",
                List.of(ReceiverTexts.vaccineTitle("hi", "एमएमआर", "1")), tue, false);
        clean(hi);
        assertThat(hi).isEqualTo("नमस्ते Kamla जी 🙏\n💉 Aarav का टीका *एमएमआर (खुराक 1)* *मंगलवार, 10 नवंबर* को लगना है, जैसा डॉक्टर ने बताया है।\n"
                + "कृपया परिवार को याद दिला दें, या Aarav को क्लिनिक ले जाएँ।\n\nजवाब देने की ज़रूरत नहीं 🙏");
        String hiToday = ReceiverTexts.vaccineReminder("hi", "helper", "Kamla", "Aarav",
                List.of("A (खुराक 1)", "B (बूस्टर 2)"), tue, true);
        clean(hiToday);
        assertThat(hiToday).contains("Aarav के टीके *A (खुराक 1)*, *B (बूस्टर 2)* *आज* लगने हैं,");
        assertThat(ReceiverTexts.vaccineTitle("en", "BCG", "")).isEqualTo("BCG");
        assertThat(ReceiverTexts.vaccineTitle("en", "Hep B", "B2")).isEqualTo("Hep B (booster 2)");
    }

    @Test
    void jiIsNotDoubled_andAFormattingNameCannotBreakTheMessage() {
        assertThat(ReceiverTexts.doseReminder("en", "helper", "Sunita ji", "Papa", "X", null, null)).startsWith("Namaste Sunita ji 🙏\n");
        assertThat(ReceiverTexts.doseReminder("hi", "helper", "सुनीता जी", "पापा", "X", null, null)).startsWith("नमस्ते सुनीता जी 🙏\n");
        String t = ReceiverTexts.doseReminder("en", "helper", "*Sunita*\n_x_", "~Papa~", "Metformin", null, null);
        assertThat(t.chars().filter(c -> c == '*').count()).isEqualTo(4);
        assertThat(t).doesNotContain("_").doesNotContain("~");
    }

    @Test
    void consentQuestionsSayForWhomAndOnWhoseBehalf() {
        for (String lang : List.of("en", "hi")) {
            for (String to : List.of("helper", "caregiver")) {
                for (String by : List.of("Ganesh", "")) {
                    String t = ReceiverTexts.consentRequest(lang, to, "Sunita", "Papa", by);
                    clean(t);
                    assertThat(t).contains("Papa").contains("*1*").contains("*2*").contains("STOP");
                    assertThat(DEVANAGARI.matcher(t).find()).isEqualTo(lang.equals("hi"));
                }
            }
        }
        assertThat(ReceiverTexts.consentRequest("en", "helper", "Sunita", "Papa", "Ganesh"))
                .contains("Ganesh would like to send you reminders for Papa's medicines here on WhatsApp.");
        assertThat(ReceiverTexts.consentRequest("en", "helper", "Sunita", "Papa", ""))
                .contains("Papa's family would like to send you reminders");
        assertThat(ReceiverTexts.consentRequest("en", "caregiver", "Ganesh", "Papa", "Ganesh"))
                .contains("You chose to get Papa's medicine reminders here on WhatsApp.");
    }

    @Test
    void repliesNameThePerson() {
        for (String lang : List.of("en", "hi")) {
            for (String t : List.of(ReceiverTexts.consentYes(lang, "Papa"), ReceiverTexts.doseTaken(lang, "Papa", "Metformin"),
                    ReceiverTexts.doseNotYet(lang, "helper", "Papa"), ReceiverTexts.doseNotYet(lang, "caregiver", "Papa"))) {
                clean(t);
                assertThat(t).contains("Papa");
                assertThat(t.toLowerCase()).doesNotContain("button", "tap");
            }
        }
        assertThat(ReceiverTexts.doseTaken("en", "Mummy", "Thyronorm")).isEqualTo("Thank you 🙏 Noted for Mummy: Thyronorm ✅");
    }

    @Test
    void receiverLabels() {
        assertThat(ReceiverTexts.receiverLabel("en", "person", "x", "y", false)).isNull();
        assertThat(ReceiverTexts.receiverLabel("en", null, "x", "y", false)).isNull();
        assertThat(ReceiverTexts.receiverLabel("en", "helper", "Sunita", "maid", false)).isEqualTo("Sunita (maid)");
        assertThat(ReceiverTexts.receiverLabel("en", "helper", "Sunita", null, false)).isEqualTo("Sunita");
        assertThat(ReceiverTexts.receiverLabel("en", "caregiver", "Ganesh Sharma", null, false)).isEqualTo("Ganesh");
        assertThat(ReceiverTexts.receiverLabel("en", "caregiver", "Ganesh", null, true)).isEqualTo("you");
        assertThat(ReceiverTexts.receiverLabel("hi", "caregiver", "Ganesh", null, true)).isEqualTo("आप");
    }

    @Test
    void familyAlertsNameWhoWasReminded_andPersonModeIsUnchanged() {
        String person = AlertTexts.alert("dose_missed", "en", "Papa", "Metformin", null, "8:00 AM");
        assertThat(AlertTexts.alert("dose_missed", "en", "Papa", "Metformin", null, "8:00 AM", null)).isEqualTo(person);
        for (String lang : List.of("en", "hi")) {
            for (String type : AlertTexts.TYPES) {
                for (String r : List.of("Sunita (maid)", lang.equals("hi") ? "आप" : "you")) {
                    String t = AlertTexts.alert(type, lang, "Papa", "Metformin", type.equals("dose_missed") ? 3 : null, "8:00 AM", r);
                    clean(t);
                    assertThat(t).contains("STOP");
                }
            }
        }
        assertThat(AlertTexts.alert("dose_missed", "en", "Papa", "Metformin", null, "8:00 AM", "Sunita (maid)"))
                .contains("Papa's medicine *Metformin* (due 8:00 AM) was not confirmed. The reminder went to Sunita (maid).");
        assertThat(AlertTexts.alert("dose_missed", "hi", "पापा", "Metformin", null, "सुबह 8:00", "आप"))
                .contains("रिमाइंडर आपको भेजा गया था");
        assertThat(AlertTexts.alert("parent_stopped", "en", "Papa", null, null, null, "you"))
                .contains("You stopped Chhaon messages on WhatsApp, so Papa's reminders are paused.");
        assertThat(AlertTexts.alert("parent_declined", "en", "Papa", null, null, null, "Sunita (maid)"))
                .contains("Sunita (maid) said no to getting Papa's medicine reminders");
        // refills are about the medicine, not about who was reminded: unchanged
        assertThat(AlertTexts.alert("refill_needed", "en", "Papa", "Metformin", null, null, "Sunita (maid)"))
                .isEqualTo(AlertTexts.alert("refill_needed", "en", "Papa", "Metformin", null, null));
    }

    @Test
    void giverWordsCountAsTaken_butAQuestionDoesNot() {
        for (String w : List.of("diya", "de diya", "दिया", "दे दिया", "given", "ho gaya", "हो गया", "Ho gaya ji", "dawai de di")) {
            assertThat(ReplyClassifier.classify(w)).as(w).isEqualTo(Intent.TAKEN);
        }
        assertThat(ReplyClassifier.classify("sab de diya")).isEqualTo(Intent.TAKEN_ALL);
        assertThat(ReplyClassifier.classify("nahi diya")).isEqualTo(Intent.NO);
        assertThat(ReplyClassifier.classify("not given")).isEqualTo(Intent.NO);
        assertThat(ReplyClassifier.classify("kya ho gaya")).isNotIn(Intent.TAKEN, Intent.TAKEN_ALL);
        assertThat(ReplyClassifier.classify("1")).isEqualTo(Intent.YES);
    }
}
