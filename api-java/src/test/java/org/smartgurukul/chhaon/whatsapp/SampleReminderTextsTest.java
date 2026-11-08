package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The sample reminder's words (docs/sample-reminder.md); no database. */
class SampleReminderTextsTest {

    @Test
    void label() {
        assertThat(ReminderTexts.sampleLabel("en")).isEqualTo("🔔 Sample reminder from Chhaon (so you know what it looks like)");
        assertThat(ReminderTexts.sampleLabel("hi")).isEqualTo("🔔 छाँव का नमूना रिमाइंडर (ताकि आप देख लें कि यह कैसा दिखेगा)");
    }

    @Test
    void theLabelledPollStillFitsAndKeepsItsQuestion() {
        List<ReminderTexts.Item> many = new ArrayList<>();
        for (int i = 0; i < 9; i++) many.add(new ReminderTexts.Item("Medicine number " + i, "1 tablet", "white", "round"));
        for (String lang : List.of("en", "hi")) {
            ReminderTexts.Poll p = ReminderTexts.dosePoll(ReminderTexts.Reader.person(lang, "Papa"), many, "8:00 pm",
                    ReminderTexts.sampleLabel(lang));
            assertThat(p.question()).startsWith(ReminderTexts.sampleLabel(lang) + "\n").hasSizeLessThanOrEqualTo(ReminderTexts.POLL_QUESTION_MAX)
                    .endsWith("en".equals(lang) ? "Taken them?" : "ले लीं?");
        }
        // without a label: unchanged
        ReminderTexts.Poll plain = ReminderTexts.dosePoll(ReminderTexts.Reader.person("en", "Papa"), many.subList(0, 1), "8:00 pm");
        assertThat(plain.question()).startsWith("Namaste Papa 🙏\n");
    }

    @Test
    void answerToATap() {
        assertThat(ReminderTexts.sampleAnswer("en", "8:00 pm", false))
                .isEqualTo("👍 That was only a sample, so no medicine was marked as taken. The real reminders start at 8:00 pm 🙏");
        assertThat(ReminderTexts.sampleAnswer("en", "8:00 am", true)).endsWith("start at 8:00 am tomorrow 🙏");
        assertThat(ReminderTexts.sampleAnswer("hi", "रात 8:00", false))
                .isEqualTo("👍 यह सिर्फ़ एक नमूना था, इसलिए कोई दवा दर्ज नहीं हुई। असली रिमाइंडर रात 8:00 से आएँगे 🙏");
        assertThat(ReminderTexts.sampleAnswer("hi", "सुबह 8:00", true)).contains("कल सुबह 8:00 से");
        assertThat(ReminderTexts.sampleAnswer("en", null, false)).endsWith("will come at the medicine times 🙏");
    }
}
