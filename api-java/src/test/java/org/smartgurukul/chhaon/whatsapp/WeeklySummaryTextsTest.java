package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.whatsapp.WeeklySummaryTexts.Giver;
import org.smartgurukul.chhaon.whatsapp.WeeklySummaryTexts.Missed;
import org.smartgurukul.chhaon.whatsapp.WeeklySummaryTexts.Refill;
import org.smartgurukul.chhaon.whatsapp.WeeklySummaryTexts.Visit;
import org.smartgurukul.chhaon.whatsapp.WeeklySummaryTexts.Week;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The weekly summary text (no database): exact wording in English and Hindi, limits, and the fraud rule. */
class WeeklySummaryTextsTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 30);
    private static final LocalDate TO = LocalDate.of(2026, 11, 5);

    private static Week mummy() {
        return new Week(UUID.randomUUID(), "Mummy", "Asia/Kolkata", FROM, TO, null, 26, 28,
                List.of(new Missed("Metformin", LocalDate.of(2026, 11, 3), "night"), new Missed("Metformin", LocalDate.of(2026, 11, 5), "night")),
                List.of(new Giver("helper", "Sunita", 24), new Giver("you", null, 2)),
                List.of(new Refill("Metformin", 6)),
                new Visit("Dr Mehta", LocalDateTime.of(2026, 11, 10, 11, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant()),
                null, 0, 0);
    }

    @Test
    void english() {
        assertThat(WeeklySummaryTexts.message("en", List.of(mummy()), "Asia/Kolkata")).isEqualTo("""
                Chhaon weekly 🗓️ · Mummy
                30 Oct – 5 Nov
                Medicines confirmed: 26 of 28
                Missed: Metformin Tue night, Thu night
                Given by: Sunita 24 · you 2
                Refill soon: Metformin (6 left)
                Next visit: Dr Mehta, Tue 10 Nov, 11:00 AM

                Full details in the Chhaon app.
                Reply STOP to turn off these alerts.""");
    }

    @Test
    void hindi() {
        assertThat(WeeklySummaryTexts.message("hi", List.of(mummy()), "Asia/Kolkata")).isEqualTo("""
                छाँव साप्ताहिक 🗓️ · Mummy
                30 अक्टू – 5 नव
                दवा की पुष्टि: 28 में से 26
                छूटी: Metformin मंगल रात, गुरु रात
                किसने दी: Sunita 24 · आप 2
                जल्द मँगाएँ: Metformin (6 बची)
                अगली मुलाक़ात: Dr Mehta, मंगल 10 नव, सुबह 11:00

                पूरी जानकारी छाँव ऐप में।
                ये अलर्ट बंद करने के लिए STOP लिखें।""");
    }

    @Test
    void aVisitInAnotherTimeZoneSaysTheirTime() {
        assertThat(WeeklySummaryTexts.message("en", List.of(mummy()), "Europe/London")).contains("11:00 AM (their time)");
    }

    @Test
    void quietPeopleAreLeftOut_andNobodyMeansNoMessage() {
        Week quiet = new Week(UUID.randomUUID(), "Papa", "Asia/Kolkata", FROM, TO, null, 0, 0, List.of(), List.of(), List.of(), null, null, 0, 0);
        assertThat(WeeklySummaryTexts.message("en", List.of(quiet), "Asia/Kolkata")).isNull();
        assertThat(WeeklySummaryTexts.message("en", List.of(quiet, mummy()), "Asia/Kolkata")).startsWith("Chhaon weekly 🗓️ · Mummy\n")
                .doesNotContain("Papa");
    }

    @Test
    void longListsAreCut_andNoLinksOrNumbersEver() {
        List<Missed> missed = new ArrayList<>();
        for (int i = 0; i < 7; i++) missed.add(new Missed("Amlodipine", FROM.plusDays(i), "morning"));
        List<Week> many = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            many.add(new Week(UUID.randomUUID(), "P" + i, "Asia/Kolkata", FROM, TO, null, 0, 7, missed, List.of(), List.of(), null, null, 0, 0));
        }
        String text = WeeklySummaryTexts.message("en", many, "Asia/Kolkata");
        assertThat(text).contains("(+3 more)").contains("+2 more in the app.").contains("*P4*").doesNotContain("*P5*")
                .doesNotContain("http").doesNotContain("+91");
    }
}
