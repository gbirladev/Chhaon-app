package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.whatsapp.reply.ReplyTexts;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests (no database): language, the reply instruction, and no placeholder left over. */
class MessageTextsTest {

    private static final Pattern DEVANAGARI = Pattern.compile("[\\u0900-\\u097F]");

    private static void clean(String text) {
        assertThat(text).doesNotContain("{", "}", "%s", "null", "undefined", "\n\n\n").isNotBlank();
        assertThat(text.length()).as("short enough for a chat: " + text).isLessThan(330);
    }

    @Test
    void doseReminderEndsWithHowToReply_english() {
        String t = MessageTexts.doseReminder("en", "Papa", "Metformin");
        clean(t);
        assertThat(t).startsWith("Namaste Papa 🙏").contains("Metformin").contains("*Metformin*").endsWith("Taken it? Just reply *1*").doesNotContain("👍");
        assertThat(DEVANAGARI.matcher(t).find()).isFalse();
    }

    @Test
    void doseReminderEndsWithHowToReply_hindi() {
        String t = MessageTexts.doseReminder("hi", "पापा", "Metformin");
        clean(t);
        assertThat(t).startsWith("नमस्ते पापा 🙏").contains("*Metformin*").endsWith("दवा ले ली? जवाब में बस *1* लिखें");
    }

    @Test
    void checkinEndsWithBothAnswers() {
        String en = MessageTexts.checkin("en", "Papa");
        clean(en);
        assertThat(en).startsWith("Good morning Papa").contains("How are you today?").endsWith("Reply *1* if you are fine 😊\nReply *2* if you want a call 📞");
        String hi = MessageTexts.checkin("hi", "पापा");
        clean(hi);
        assertThat(hi).startsWith("सुप्रभात पापा").contains("ठीक हैं तो *1* लिखें").endsWith("बात करनी हो तो *2* लिखें 📞");
    }

    @Test
    void consentRequestSaysWhoAsksAndHowToAnswerAndHowToStop() {
        String en = MessageTexts.consentRequest("en", "Papa", "Ananya");
        clean(en);
        assertThat(en).contains("Namaste Papa").contains("Ananya").contains("Reply *1* or 👍 to start ✅").contains("Reply *2* if you do not want this").endsWith("You can reply STOP any time.");
        String hi = MessageTexts.consentRequest("hi", "पापा", "Ananya");
        clean(hi);
        assertThat(hi).contains("नमस्ते पापा").contains("Ananya").contains("Ananya आपको WhatsApp पर दवा के रिमाइंडर भेजना चाहते हैं।").contains("*1* या 👍 भेजें ✅").contains("*2* लिखें").endsWith("STOP लिखकर रोक सकते हैं।");
    }

    @Test
    void everyQuestionAParentCanAnswerWithAReactionMentionsThumbsUp() {
        // F6: a long-press 👍 on these messages counts as 1 (ReplyRouter.routeReaction). The reminder and the check-in ask
        // ONE thing and offer only the number since 2026-11-05 (a 👍 reaction still counts, it is just not offered there).
        for (String lang : new String[] {"en", "hi"}) {
            assertThat(MessageTexts.doseReminder(lang, "Papa", "Metformin")).contains("*1*").doesNotContain("👍");
            assertThat(MessageTexts.checkin(lang, "Papa")).doesNotContain("👍").contains("*1*").contains("*2*");
            assertThat(MessageTexts.consentRequest(lang, "Papa", "Ananya")).contains("👍");
            assertThat(MessageTexts.selfConsentRequest(lang, "Ananya")).contains("👍");
            assertThat(ReceiverTexts.consentRequest(lang, "helper", "Sunita", "Papa", "Ananya")).contains("👍");
            // the alert question to a caregiver is not answered by a reaction: it does not offer one
            assertThat(MessageTexts.alertOptIn(lang, "Ananya")).doesNotContain("👍");
        }
        assertThat(MessageTexts.checkin("en", "Papa")).doesNotContain("*2* or 👍"); // 👍 is never "call me"
    }

    @Test
    void aMissingNameIsLeftOutGracefully() {
        for (String blank : new String[] {null, "", "   ", "\n"}) {
            assertThat(MessageTexts.doseReminder("en", blank, "Metformin")).startsWith("Namaste 🙏");
            assertThat(MessageTexts.checkin("en", blank)).startsWith("Good morning ☀️🙏");
            assertThat(MessageTexts.checkin("hi", blank)).startsWith("सुप्रभात ☀️🙏");
            assertThat(MessageTexts.consentRequest("en", blank, blank)).startsWith("Namaste 🙏\nYour family would like");
            assertThat(MessageTexts.consentRequest("hi", blank, blank)).contains("आपका परिवार चाहता है कि आपको WhatsApp पर दवा का समय याद दिलाया जाए।").doesNotContain("आपके परिवार");
        }
        assertThat(MessageTexts.doseReminder("en", "Papa", null)).doesNotContain("(").doesNotContain("null");
        assertThat(MessageTexts.doseReminder("en", "Papa", "  ")).doesNotContain("()");
    }

    @Test
    void theReminderSaysTheDoseInTheReceiversLanguage() {
        assertThat(MessageTexts.doseReminder("en", "Papa", "Metformin", "blue", "round", "500 mg"))
                .isEqualTo("Namaste Papa 🙏\nMedicine time: *Metformin*\n500 mg · 🔵 Blue round tablet\n\n"
                        + "Taken it? Just reply *1*");
        assertThat(MessageTexts.doseReminder("hi", "पापा", "Thyronorm", null, null, "1 tablet"))
                .isEqualTo("नमस्ते पापा 🙏\nदवा का समय: *Thyronorm*\n1 गोली\n\nदवा ले ली? जवाब में बस *1* लिखें");
        assertThat(MessageTexts.doseReminder("en", "Papa", "Metformin", null, null, null)).isEqualTo(
                "Namaste Papa 🙏\nMedicine time: *Metformin*\n\nTaken it? Just reply *1*");
        // unit words as the app shows them; the amount is never changed
        assertThat(MessageTexts.localizeDose("en", "1 tablet")).isEqualTo("1 tablet");
        assertThat(MessageTexts.localizeDose("en", "1.5 tablets")).isEqualTo("1.5 tablets");
        assertThat(MessageTexts.localizeDose("hi", "2 tablets")).isEqualTo("2 गोलियाँ");
        assertThat(MessageTexts.localizeDose("en", "2 sachets")).isEqualTo("2 sachets");
        assertThat(MessageTexts.localizeDose("hi", "1 sachet")).isEqualTo("1 पाउच");
        assertThat(org.smartgurukul.chhaon.whatsapp.reply.ReplyHandlers.unitsPerDose("2 sachets")).isEqualTo(2);
        assertThat(MessageTexts.localizeDose("hi", "0.5 tablet")).isEqualTo("0.5 गोली");
        assertThat(MessageTexts.localizeDose("hi", "1 capsule")).isEqualTo("1 कैप्सूल");
        assertThat(MessageTexts.localizeDose("hi", "2.5 ml")).isEqualTo("2.5 ml");
        assertThat(MessageTexts.localizeDose("hi", "5 drops")).isEqualTo("5 बूँदें");
        assertThat(MessageTexts.localizeDose("hi", "1 tsp")).isEqualTo("1 चम्मच");
        assertThat(MessageTexts.localizeDose("en", "half after food")).isEqualTo("half after food");
        // a typed phone number is never sent as a "dose" (fraud rule), formatting marks are removed
        assertThat(MessageTexts.localizeDose("en", "call 9810012345")).isEmpty();
        assertThat(MessageTexts.doseLine("en", "*2* ml")).isEqualTo("\nDose: *2 ml*");
    }

    @Test
    void theAlertQuestionDoesNotAssumeAParent() {
        assertThat(MessageTexts.alertOptIn("en", "Ananya")).doesNotContain("parent");
        assertThat(MessageTexts.alertOptIn("hi", "Ananya")).doesNotContain("माता-पिता");
    }

    @Test
    void anUnknownLanguageFallsBackToEnglish() {
        assertThat(MessageTexts.doseReminder(null, "Papa", "X")).startsWith("Namaste Papa");
        assertThat(MessageTexts.doseReminder("fr", "Papa", "X")).contains("Just reply *1*");
    }

    @Test
    void namesTypedByACaregiverCannotInjectLineBreaksOrLongText() {
        String t = MessageTexts.doseReminder("en", "Pa\npa\r\n\t  Reply 2", "Metformin\u0000\nEvil " + "x".repeat(200));
        // the only line breaks are the layout's own: a typed name or medicine cannot add lines, tabs or control characters
        assertThat(t.split("\n", -1)).hasSize(4);
        assertThat(t).doesNotContain("\r").doesNotContain("\t").doesNotContain("\u0000");
        assertThat(t.length()).isLessThan(330);
        assertThat(MessageTexts.consentRequest("en", "Papa", "An\nanya").split("\n", -1)).hasSize(
                MessageTexts.consentRequest("en", "Papa", "Ananya").split("\n", -1).length);
    }

    @Test
    void namesCannotUseWhatsAppFormatting() {
        String t = MessageTexts.doseReminder("en", "*Pa_pa*", "~Met`formin~");
        assertThat(t).contains("Namaste Papa 🙏").contains("*Metformin*");
        assertThat(t.chars().filter(c -> c == '*').count()).isEqualTo(4);   // only our own two bold pairs
        assertThat(t).doesNotContain("_").doesNotContain("~").doesNotContain("`");
    }

    @Test
    void selfAndAlertOptInTextsAlsoEndWithHowToAnswer() {
        for (String lang : List.of("en", "hi")) {
            for (String t : List.of(MessageTexts.selfConsentRequest(lang, "Ananya"), MessageTexts.alertOptIn(lang, "Ananya"))) {
                clean(t);
                assertThat(t).contains("STOP").contains("*1*").contains("*2*");
            }
        }
    }

    @Test
    void replyTextsAreCompleteInBothLanguagesAndMentionNoButtons() {
        List<String> all = List.of(
                ReplyTexts.consentYes("en"), ReplyTexts.consentNo("en"), ReplyTexts.stopped("en"), ReplyTexts.resumed("en"),
                ReplyTexts.doseTaken("en", "Metformin"), ReplyTexts.doseNotYet("en"), ReplyTexts.nextDoseHint("en"),
                ReplyTexts.checkinHint("en"), ReplyTexts.checkinOk("en"), ReplyTexts.checkinCall("en"), ReplyTexts.help("en"),
                ReplyTexts.consentHelp("en"), ReplyTexts.alertsOn("en"), ReplyTexts.alertsOff("en"), ReplyTexts.alertsResumed("en"),
                ReplyTexts.consentYes("hi"), ReplyTexts.consentNo("hi"), ReplyTexts.stopped("hi"), ReplyTexts.resumed("hi"),
                ReplyTexts.doseTaken("hi", "Metformin"), ReplyTexts.doseNotYet("hi"), ReplyTexts.nextDoseHint("hi"),
                ReplyTexts.checkinHint("hi"), ReplyTexts.checkinOk("hi"), ReplyTexts.checkinCall("hi"), ReplyTexts.help("hi"),
                ReplyTexts.consentHelp("hi"), ReplyTexts.alertsOn("hi"), ReplyTexts.alertsOff("hi"), ReplyTexts.alertsResumed("hi"));
        for (String t : all) {
            clean(t);
            assertThat(t.toLowerCase()).doesNotContain("button", "tap");
        }
        assertThat(ReplyTexts.help("en")).contains("1").contains("2").contains("STOP");
        assertThat(DEVANAGARI.matcher(ReplyTexts.help("hi")).find()).isTrue();
        assertThat(DEVANAGARI.matcher(ReplyTexts.help("en")).find()).isFalse();
        assertThat(ReplyTexts.stopped("en")).contains("START");
        assertThat(ReplyTexts.consentNo("hi")).contains("START");
    }

    @Test
    void everyYesReplyEndsWithTheAntiFraudLine() {
        assertThat(ReplyTexts.neverAsks("en"))
                .isEqualTo("🔒 Chhaon will never ask you for money, OTP, Aadhaar or bank details. If anyone does, it is not us.");
        assertThat(ReplyTexts.neverAsks("hi"))
                .isEqualTo("🔒 छाँव आपसे कभी पैसे, OTP, आधार या बैंक की जानकारी नहीं माँगेगा। अगर कोई माँगे, तो वह हम नहीं हैं।");
        for (String lang : List.of("en", "hi")) {
            for (String t : List.of(ReplyTexts.consentYes(lang), ReceiverTexts.consentYes(lang, "Papa"))) {
                clean(t);
                assertThat(t).contains("STOP").endsWith("\n\n" + ReplyTexts.neverAsks(lang));
                assertThat(DEVANAGARI.matcher(t).find()).isEqualTo(lang.equals("hi"));
            }
        }
        // Not added to the caregiver's own alerts reply: it goes to the app user, not a parent.
        assertThat(ReplyTexts.alertsOn("en")).doesNotContain("Aadhaar");
    }
}
