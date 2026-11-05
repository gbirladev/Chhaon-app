package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.recipient.Pregnancy;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Pregnancy wording (care_type = pregnant): week line, gentle greeting, same reply convention; older texts unchanged. */
class PregnancyTextTest {

    private static void chatSized(String text) {
        assertThat(text).doesNotContain("{", "}", "null", "\n\n\n").isNotBlank();
        assertThat(text.length()).isLessThan(330);
    }

    @Test
    void englishDoseReminderHasTheWeekTheMedicineAndTheUsualReply() {
        String t = MessageTexts.pregnancyDoseReminder("en", "Shivani", "Iron + folic acid", null, null, 24, 8);
        chatSized(t);
        assertThat(t).isEqualTo("Good morning Shivani ☀️\nWeek 24 today 💛\nTime for your *Iron + folic acid* 💊\n\nReply *1* once you have taken it ✅");
    }

    @Test
    void hindiDoseReminderHasTheWeekTheMedicineAndTheUsualReply() {
        String t = MessageTexts.pregnancyDoseReminder("hi", "शिवानी", "आयरन + फ़ोलिक एसिड", "red", "round", 24, 20);
        chatSized(t);
        assertThat(t).isEqualTo("शुभ संध्या शिवानी 🌙\nआज आपका 24वाँ हफ़्ता है 💛\n*आयरन + फ़ोलिक एसिड* लेने का समय हो गया है 💊\n🔴 लाल गोल गोली\n\nलेने के बाद *1* लिखकर भेजिए ✅");
    }

    @Test
    void theGreetingFollowsHerClockAndTheWeekLineIsLeftOutWithoutADueDate() {
        assertThat(MessageTexts.pregnancyDoseReminder("en", "Shivani", "Calcium", null, null, null, 14))
                .isEqualTo("Good afternoon Shivani 🌤️\nTime for your *Calcium* 💊\n\nReply *1* once you have taken it ✅");
        assertThat(MessageTexts.pregnancyDoseReminder("en", "Shivani", "Calcium", null, null, null, 2)).startsWith("Good evening Shivani 🌙");
        assertThat(MessageTexts.pregnancyDoseReminder("hi", null, "", null, null, 0, 9))
                .isEqualTo("सुप्रभात ☀️\nदवा लेने का समय हो गया है 💊\n\nलेने के बाद *1* लिखकर भेजिए ✅");
        assertThat(MessageTexts.pregnancyDoseReminder("en", "Shivani", "Calcium", null, null, 43, 9)).doesNotContain("Week");
    }

    @Test
    void pregnancyCheckinIsGentleAndKeepsBothAnswers() {
        String en = MessageTexts.pregnancyCheckin("en", "Shivani", 30);
        chatSized(en);
        assertThat(en).isEqualTo("Good morning Shivani ☀️\nWeek 30 today 💛\nHow are you feeling today?\n\nReply *1* if you are fine 😊\nReply *2* if you want a call 📞");
        String hi = MessageTexts.pregnancyCheckin("hi", "शिवानी", 1);
        chatSized(hi);
        assertThat(hi).isEqualTo("सुप्रभात शिवानी ☀️\nआज आपका पहला हफ़्ता है 💛\nआज आप कैसा महसूस कर रही हैं?\n\nठीक हैं तो *1* लिखें 😊\nफ़ोन चाहिए तो *2* लिखें 📞");
    }

    @Test
    void hindiOrdinalsAreWrittenProperly() {
        assertThat(MessageTexts.hindiOrdinal(2)).isEqualTo("दूसरा");
        assertThat(MessageTexts.hindiOrdinal(3)).isEqualTo("तीसरा");
        assertThat(MessageTexts.hindiOrdinal(4)).isEqualTo("चौथा");
        assertThat(MessageTexts.hindiOrdinal(5)).isEqualTo("5वाँ");
        assertThat(MessageTexts.hindiOrdinal(6)).isEqualTo("छठा");
        assertThat(MessageTexts.hindiOrdinal(40)).isEqualTo("40वाँ");
    }

    @Test
    void aTypedNameCannotInjectFormattingOrLines() {
        String t = MessageTexts.pregnancyDoseReminder("en", "Shi\nvani *2*", "Iron\n*x*", null, null, 24, 8);
        assertThat(t.split("\n", -1)).hasSize(5);
        assertThat(t).contains("Good morning Shi vani 2 ☀️").contains("*Iron x*");
    }

    @Test
    void olderParentsGetExactlyTheTextTheyGotBefore() {
        assertThat(MessageTexts.doseReminder("en", "Papa", "Metformin"))
                .isEqualTo("Namaste Papa 🙏\nIt's time for your medicine 💊 *Metformin*\n\nReply *1* once you have taken it ✅");
        assertThat(MessageTexts.checkin("hi", "पापा"))
                .isEqualTo("सुप्रभात पापा ☀️🙏\nआज आप कैसे हैं?\n\nठीक हैं तो *1* लिखें 😊\nफ़ोन चाहिए तो *2* लिखें 📞");
    }

    @Test
    void weekIsCountedInCompletedWeeksFromTheDueDate() {
        LocalDate due = LocalDate.of(2027, 3, 14);
        assertThat(Pregnancy.week(due, due.minusDays(112))).isEqualTo(24);   // 24w0d
        assertThat(Pregnancy.week(due, due.minusDays(106))).isEqualTo(24);   // 24w6d
        assertThat(Pregnancy.week(due, due.minusDays(105))).isEqualTo(25);
        assertThat(Pregnancy.week(due, due)).isEqualTo(40);
        assertThat(Pregnancy.week(due, due.plusDays(20))).isEqualTo(42);
        assertThat(Pregnancy.week(due, due.plusDays(21))).isNull();          // after week 42: no number
        assertThat(Pregnancy.week(due, due.minusDays(274))).isNull();        // before week 1
        assertThat(Pregnancy.week(due, due.minusDays(273))).isEqualTo(1);
        assertThat(Pregnancy.week(null, due)).isNull();
    }
}
