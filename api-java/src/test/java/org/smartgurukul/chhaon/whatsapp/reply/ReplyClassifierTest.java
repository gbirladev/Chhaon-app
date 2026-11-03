package org.smartgurukul.chhaon.whatsapp.reply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.smartgurukul.chhaon.whatsapp.reply.Intent.*;

/** Pure unit tests (no database): they always run. */
class ReplyClassifierTest {

    private static void is(Intent expected, String text) {
        assertThat(ReplyClassifier.classify(text)).as("%s -> %s", text, expected).isEqualTo(expected);
    }

    // ---------------------------------------------------------------- STOP (ported rules)

    @ParameterizedTest
    @ValueSource(strings = {
            "Stop.", "STOP!", "please stop", "stop messages", "stop", "STOP", "Stop 🙏", "STOP🙏", "Stop,please",
            "  Unsubscribe ", "ＳＴＯＰ", "stop​", "st​op", "s‍t⁠op", "﻿STOP", "'stop'", "\"stop\"",
            "रुको", "रुकिए", "रुकें", "रोकें", "बंद करो", "बन्द करो", "बंद करें", "बंद कीजिए",
            "stop रुको", "ＳＴＯＰ ＮＯＷ", "stop \n", "\n\nSTOP\n\n", "Stop!!!!", "stop.", "StOp"})
    void manyWaysOfSayingStopAllWork(String text) {
        is(STOP, text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I will stop by the pharmacy tomorrow to pick up my tablets",
            "please don't stop", "dont stop", "do not stop", "not stop", "never stop", "no stop needed",
            "दरवाज़ा बंद", "band karo", "बंद", "मत रुको", "रुको मत", "नहीं रुको",
            "one two three stop four five", "Cancel my appointment"})
    void ordinarySentencesAreNotOptOuts(String text) {
        assertThat(ReplyClassifier.classify(text)).as(text).isNotEqualTo(STOP);
    }

    @Test
    void stopMustNotBeHiddenBehindAnAnswerWord() {
        // "STOP always wins": an answer word next to stop does not change the intent.
        is(STOP, "1 stop");
        is(STOP, "stop taken");
        is(STOP, "yes stop");
    }

    @Test
    void stopWordLimitIsFourWords() {
        is(STOP, "please stop messages now");           // 4 words: opt-out
        // KNOWN LIMITATION kept from the Python rule on purpose (a chat sentence must not unsubscribe anyone by accident):
        // a polite sentence of five words or more is not an opt-out.
        is(UNKNOWN, "please stop sending me messages");
    }

    // ---------------------------------------------------------------- START

    @ParameterizedTest
    @ValueSource(strings = {"START", "Start", "start", "start.", "शुरू", "शुरु", "ＳＴＡＲＴ", "START 🙏", "please start"})
    void startOptsBackIn(String text) {
        is(START, text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Start taking medicine at 9", "don't start", "do not start", "never start", "I will start tomorrow"})
    void startInsideASentenceOrNegatedIsNotAnOptIn(String text) {
        assertThat(ReplyClassifier.classify(text)).as(text).isNotEqualTo(START);
    }

    // ---------------------------------------------------------------- numbers: 1 = yes, 2 = no

    @ParameterizedTest
    @ValueSource(strings = {"1", " 1 ", "1.", "1)", "1️⃣", "①", "१", "１", "one", "ek", "एक", "1 ji", "1 जी", "1 1", "1​"})
    void oneMeansYes(String text) {
        is(YES, text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", " 2 ", "2.", "2️⃣", "②", "२", "２", "two", "दो", "2 ji"})
    void twoMeansNo(String text) {
        is(NO, text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "10", "11", "1 2", "12", "0"})
    void otherNumbersAreNotAnswers(String text) {
        is(UNKNOWN, text);
    }

    // ---------------------------------------------------------------- yes / no

    @ParameterizedTest
    @ValueSource(strings = {"yes", "Yes!", "YES", "y", "yeah", "yep", "haan", "Haan ji", "haan haan", "han", "हाँ", "हां", "हा", "जी हाँ",
            "जी हां", "bilkul", "बिलकुल", "सही", "sure", "I agree", "✅", "👍", "👍🏽", "✔️", "☑️", "✅✅", "haan 👍", "yes हाँ", "haan yes"})
    void yesInEnglishHindiAndHinglish(String text) {
        is(YES, text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"no", "No!", "NO", "n", "nope", "nahi", "Nahin", "नहीं", "नही", "नहीं चाहिए", "nahi chahiye", "not now",
            "not yet", "later", "abhi nahi", "अभी नहीं", "baad mein", "बाद में", "❌", "👎", "no thanks"})
    void noInEnglishHindiAndHinglish(String text) {
        is(NO, text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ok", "OK", "Okay", "okk", "k", "theek hai", "thik hai", "ठीक है", "ठीक", "accha", "अच्छा", "got it", "noted",
            "👌", "ok ji", "ok theek hai", "alright"})
    void bareAcknowledgementsAreNotYes(String text) {
        // "ok" may just acknowledge the reminder: it must never count as "taken".
        is(ACK, text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"thanks", "Thank you", "thank you!", "dhanyavad", "धन्यवाद", "shukriya", "शुक्रिया", "🙏", "🙏🙏", "God bless you"})
    void thanksIsFriendlyAndNeedsNoAnswer(String text) {
        is(THANKS, text);
    }

    // ---------------------------------------------------------------- taken

    @ParameterizedTest
    @ValueSource(strings = {"taken", "Taken", "TAKEN", "✅ Taken", "✅ taken", "i took it", "I have taken", "done", "Done!",
            "liya", "Liya", "✅ Liya", "le li", "le liya", "maine le li", "maine le liya", "kha li", "dawai le li", "dawa le li",
            "ले ली", "ले लिया", "मैंने ले ली", "मैंने दवा ले ली", "खा ली", "दवा ले ली है", "ली", "yes taken", "haan le li"})
    void takenInEnglishHindiAndHinglish(String text) {
        is(TAKEN, text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"all taken", "taken all", "sab le li", "sab liya", "सब ले ली", "सभी ले ली", "saari le li", "all done"})
    void takenAll(String text) {
        is(TAKEN_ALL, text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not taken", "haven't taken", "have not taken", "didn't take it, not taken", "nahi li", "nahi liya", "नहीं ली",
            "abhi nahi li", "not yet taken", "never taken", "not done"})
    void aNegatedTakenIsNeverTaken(String text) {
        assertThat(ReplyClassifier.classify(text)).as(text).isNotIn(TAKEN, TAKEN_ALL, YES);
    }

    // ---------------------------------------------------------------- check-in

    @ParameterizedTest
    @ValueSource(strings = {"I am okay", "i am ok", "I'm okay", "I’m okay", "Im fine", "I am fine", "fine", "all good", "all fine",
            "👍 I'm okay", "theek hoon", "Theek hoon", "theek hu", "thik hoon", "main theek hoon", "mein theek hun", "ठीक हूँ", "ठीक हूं",
            "मैं ठीक हूँ", "सब ठीक है", "सब ठीक", "badhiya", "बढ़िया", "👍 Theek hoon", "sab theek hai", "doing well"})
    void checkinOk(String text) {
        is(CHECKIN_OK, text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"call me", "Call me!", "please call me", "call me please", "CALL ME", "call karo", "phone karo", "फोन करो", "फ़ोन करो",
            "मुझे फोन करो", "कॉल करो", "call", "help", "madad chahiye", "मदद", "🆘", "pain", "dard ho raha hai", "सीने में दर्द",
            "I am not well", "not okay", "I'm not fine", "theek nahi hoon", "ठीक नहीं हूँ", "tabiyat kharab hai", "तबीयत खराब है", "unwell",
            "I am sick"})
    void callMeAndAnythingThatSoundsLikeTroubleAlertsTheFamily(String text) {
        is(CHECKIN_CALL_ME, text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"don't call me", "no need to call me", "I will call you later", "I will call tomorrow", "no pain", "nahi dard nahi"})
    void callWordsInsideOtherSentencesAreNotACallRequest(String text) {
        assertThat(ReplyClassifier.classify(text)).as(text).isNotEqualTo(CHECKIN_CALL_ME);
    }

    // ---------------------------------------------------------------- unknown / nasty

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n", "​​", "hello", "good morning", "Good morning beta", "kab aaoge?", "what is this",
            "who are you", "😀", "❤️", "🙂🙂", "?", "...", "asdf", "मैं कल बाजार जाऊँगा",
            "I took my walk and then ate breakfast and watched the news and called my sister and then ok"})
    void everythingElseIsUnknown(String text) {
        is(UNKNOWN, text);
    }

    @Test
    void nullIsUnknown() {
        is(UNKNOWN, null);
    }

    @Test
    void aTickAndACrossTogetherIsNotAnAnswer() {
        is(UNKNOWN, "✅❌");
        is(UNKNOWN, "👍👎");
    }

    @Test
    void shoutingSpacesAndInvisibleCharactersDoNotMatter() {
        is(YES, "   YES   ");
        is(YES, "​Y​E​S");
        is(TAKEN, "T A K E N".replace(" ", "​"));
        is(NO, "N­O");
        is(CHECKIN_OK, "I  AM   OKAY");
        is(YES, "ＹＥＳ");
    }

    @Test
    void hindiSpellingVariantsLineUp() {
        // chandrabindu vs anusvara, composed vs decomposed nukta
        is(YES, "हाँ");
        is(YES, "हां");
        is(CHECKIN_CALL_ME, "फ़ोन करो");      // precomposed U+095E
        is(CHECKIN_CALL_ME, "फ़ोन करो"); // decomposed
        is(CHECKIN_OK, "ठीक हूँ");
        is(CHECKIN_OK, "ठीक हूं");
    }

    @Test
    void veryLongTextIsHandledQuicklyAndIsNotAnAnswer() {
        String huge = "stop ".repeat(5000);
        is(UNKNOWN, huge);
        is(UNKNOWN, "x".repeat(100_000));
    }

    @Test
    void normaliseFoldsFullWidthAndDigits() {
        assertThat(ReplyClassifier.normalise("ＳＴＯＰ １２")).isEqualTo("stop 12");
        assertThat(ReplyClassifier.normalise("१२")).isEqualTo("12");
        assertThat(ReplyClassifier.wordsOf("Stop, please!")).containsExactly("stop", "please");
    }
}
