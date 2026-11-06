package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.whatsapp.ReminderTexts.Item;
import org.smartgurukul.chhaon.whatsapp.ReminderTexts.Poll;
import org.smartgurukul.chhaon.whatsapp.ReminderTexts.Reader;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** One reminder per time slot: the text (typed "1") and the poll (one tap), Hindi and English, person / helper / family member. */
class ReminderTextsTest {

    private static final List<Item> TWO = List.of(
            new Item("Argipreg", "1 sachet", null, null),
            new Item("Metformin 500", "1 tablet", "blue", "round"));
    private static final List<Item> ONE = List.of(new Item("Ecosprin 150", "1 tablet", "blue", "round"));

    @Test
    void samples() {
        // Printed for the report / a human check; the assertions below pin the important parts.
        String hi8 = ReminderTexts.slotTime("hi", LocalTime.of(20, 0));
        String en8 = ReminderTexts.slotTime("en", LocalTime.of(20, 0));
        for (String s : List.of(
                ReminderTexts.doseText(Reader.person("hi", "Shivani"), TWO, hi8),
                ReminderTexts.doseText(Reader.person("hi", "Papa"), ONE, hi8),
                ReminderTexts.doseText(Reader.person("en", "Papa"), TWO, en8),
                ReminderTexts.doseText(Reader.person("en", "Papa"), ONE, en8),
                ReminderTexts.doseText(Reader.receiver("hi", "helper", "Sunita", "Papa"), TWO, hi8),
                ReminderTexts.doseText(Reader.receiver("en", "helper", "Sunita", "Papa"), ONE, en8),
                ReminderTexts.doseText(Reader.receiver("en", "caregiver", "Ananya", "Papa"), TWO, en8),
                ReminderTexts.checkinText(Reader.person("hi", "Papa")),
                ReminderTexts.checkinText(Reader.person("en", "Papa")))) {
            System.out.println("----- text\n" + s);
        }
        for (Poll p : List.of(
                ReminderTexts.dosePoll(Reader.person("hi", "Shivani"), TWO, hi8),
                ReminderTexts.dosePoll(Reader.person("en", "Papa"), ONE, en8),
                ReminderTexts.dosePoll(Reader.receiver("hi", "helper", "Sunita", "Papa"), TWO, hi8),
                ReminderTexts.dosePoll(Reader.receiver("en", "helper", "Sunita", "Papa"), ONE, en8),
                ReminderTexts.dosePoll(Reader.receiver("hi", "caregiver", "Ananya", "Papa"), ONE, hi8),
                ReminderTexts.checkinPoll(Reader.person("hi", "Papa")),
                ReminderTexts.checkinPoll(Reader.person("en", "Papa")))) {
            System.out.println("----- poll\n" + p.question() + "\n" + p.options());
        }
    }

    @Test
    void severalMedicinesAreOneTextWithOneQuestionAndOnlyOne() {
        String hi = ReminderTexts.doseText(Reader.person("hi", "Shivani"), TWO, ReminderTexts.slotTime("hi", LocalTime.of(20, 0)));
        assertThat(hi).isEqualTo("नमस्ते Shivani 🙏\nरात 8:00 की दवाएं:\n• *Argipreg* · 1 पाउच\n"
                + "• *Metformin 500* · 1 गोली · 🔵 नीली गोल गोली\n\nसब ले लीं? जवाब में बस *1* लिखें");
        String en = ReminderTexts.doseText(Reader.person("en", "Papa"), TWO, ReminderTexts.slotTime("en", LocalTime.of(20, 0)));
        assertThat(en).isEqualTo("Namaste Papa 🙏\nYour 8:00 pm medicines:\n• *Argipreg* · 1 sachet\n"
                + "• *Metformin 500* · 1 tablet · 🔵 Blue round tablet\n\nTaken them all? Just reply *1*");
        for (String t : List.of(hi, en)) {
            assertThat(t).doesNotContain("👍").doesNotEndWith("✅").doesNotContain("*2*");
        }
    }

    @Test
    void oneMedicineHasItsNameBoldOnItsOwnLine() {
        assertThat(ReminderTexts.doseText(Reader.person("hi", "Papa"), ONE, "रात 8:00"))
                .isEqualTo("नमस्ते Papa 🙏\nदवा का समय: *Ecosprin 150*\n1 गोली · 🔵 नीली गोल गोली\n\nदवा ले ली? जवाब में बस *1* लिखें");
        assertThat(ReminderTexts.doseText(Reader.person("en", "Papa"), List.of(new Item("Ecosprin 150", null, null, null)), "8:00 pm"))
                .isEqualTo("Namaste Papa 🙏\nMedicine time: *Ecosprin 150*\n\nTaken it? Just reply *1*");
    }

    @Test
    void helperAndFamilyMemberVariantsNameThePerson() {
        assertThat(ReminderTexts.doseText(Reader.receiver("hi", "helper", "Sunita", "Papa"), TWO, "रात 8:00"))
                .startsWith("नमस्ते Sunita जी 🙏\nPapa की रात 8:00 की दवाएं:\n").endsWith("Papa को सब दवाएं दे दीं? जवाब में बस *1* लिखें");
        assertThat(ReminderTexts.doseText(Reader.receiver("en", "helper", "Sunita", "Papa"), ONE, "8:00 pm"))
                .isEqualTo("Namaste Sunita ji 🙏\nTime for Papa's medicine: *Ecosprin 150*\n1 tablet · 🔵 Blue round tablet\n\n"
                        + "Given it to Papa? Just reply *1*");
        assertThat(ReminderTexts.doseText(Reader.receiver("en", "caregiver", "Ananya Rao", "Papa"), TWO, "8:00 pm"))
                .startsWith("Namaste Ananya 🙏\nPapa's 8:00 pm medicines:\n").endsWith("Has Papa taken them all? Just reply *1*");
    }

    @Test
    void theDosePollHasNoFormattingAndTheAgreedOptions() {
        Poll hi = ReminderTexts.dosePoll(Reader.person("hi", "Shivani"), TWO, "रात 8:00");
        assertThat(hi.question()).isEqualTo("नमस्ते Shivani 🙏\n💊 रात 8:00 की दवाएं: Argipreg (1 पाउच), Metformin 500 (1 गोली)\nले लीं?");
        assertThat(hi.options()).containsExactly("✅ ले ली", "⏰ थोड़ी देर में");
        Poll en = ReminderTexts.dosePoll(Reader.person("en", "Papa"), ONE, "8:00 pm");
        assertThat(en.question()).isEqualTo("Namaste Papa 🙏\n💊 Medicine time: Ecosprin 150 (1 tablet, 🔵 Blue round tablet)\nTaken it?");
        assertThat(en.options()).containsExactly("✅ Taken", "⏰ Later");
        Poll helper = ReminderTexts.dosePoll(Reader.receiver("hi", "helper", "Sunita", "Papa"), ONE, "रात 8:00");
        assertThat(helper.question()).endsWith("\nPapa को दवा दे दी?").doesNotContain("*");
        assertThat(helper.options()).containsExactly("✅ दे दी", "⏰ थोड़ी देर में");
        assertThat(ReminderTexts.dosePoll(Reader.receiver("en", "helper", "Sunita", "Papa"), ONE, "8:00 pm").options())
                .containsExactly("✅ Given", "⏰ Later");
        Poll checkin = ReminderTexts.checkinPoll(Reader.person("hi", "Papa"));
        assertThat(checkin.question()).isEqualTo("सुप्रभात Papa ☀️🙏\nआज आपकी तबीयत कैसी है?");
        assertThat(checkin.options()).containsExactly("😊 ठीक हूँ", "📞 बात करनी है");
        assertThat(ReminderTexts.checkinPoll(Reader.person("en", "Papa")).options()).containsExactly("😊 I'm fine", "📞 Please call me");
    }

    @Test
    void aLongListIsShortenedToFitThePollLimit() {
        List<Item> many = new ArrayList<>();
        for (int i = 1; i <= 12; i++) many.add(new Item("Medicine number " + i + " with a long name", "2 tablets", null, null));
        Poll en = ReminderTexts.dosePoll(Reader.person("en", "Papa"), many, "8:00 am");
        assertThat(en.question().length()).isLessThanOrEqualTo(ReminderTexts.POLL_QUESTION_MAX);
        assertThat(en.question()).contains(" more\n").endsWith("Taken them?");
        Poll hi = ReminderTexts.dosePoll(Reader.person("hi", "Papa"), many, "सुबह 8:00");
        assertThat(hi.question().length()).isLessThanOrEqualTo(ReminderTexts.POLL_QUESTION_MAX);
        assertThat(hi.question()).contains(" और\n").endsWith("ले लीं?");
        // and the text lists at most 10 lines
        assertThat(ReminderTexts.doseText(Reader.person("en", "Papa"), many, "8:00 am")).contains("\n• +2 more\n");
    }

    @Test
    void votesAreReadByLabelAndIndex() {
        assertThat(ReminderTexts.doseVote("✅ Taken", 0)).isEqualTo(0);
        assertThat(ReminderTexts.doseVote("✅ दे दी", 0)).isEqualTo(0);
        assertThat(ReminderTexts.doseVote("⏰ थोड़ी देर में", 1)).isEqualTo(1);
        assertThat(ReminderTexts.doseVote(" ✅ ले ली ", null)).isEqualTo(0);   // label alone
        assertThat(ReminderTexts.doseVote(null, 1)).isEqualTo(1);              // index alone
        assertThat(ReminderTexts.doseVote("✅ Taken", 1)).isEqualTo(-1);       // contradicting
        assertThat(ReminderTexts.doseVote("Maybe", 0)).isEqualTo(-1);          // not our label
        assertThat(ReminderTexts.doseVote(null, 2)).isEqualTo(-1);
        assertThat(ReminderTexts.checkinVote("📞 फ़ोन कीजिए", 1)).isEqualTo(1);   // the label on polls sent before 6 Nov 2026
        assertThat(ReminderTexts.checkinVote("📞 बात करनी है", 1)).isEqualTo(1);
        assertThat(ReminderTexts.checkinVote("😊 I'm fine", 0)).isEqualTo(0);
        assertThat(ReminderTexts.checkinVote("✅ Taken", 0)).isEqualTo(-1);    // a dose label on a check-in
    }

    @Test
    void slotTimeOnThePersonsClock() {
        assertThat(ReminderTexts.slotTime("hi", LocalTime.of(8, 0))).isEqualTo("सुबह 8:00");
        assertThat(ReminderTexts.slotTime("hi", LocalTime.of(13, 30))).isEqualTo("दोपहर 1:30");
        assertThat(ReminderTexts.slotTime("hi", LocalTime.of(18, 0))).isEqualTo("शाम 6:00");
        assertThat(ReminderTexts.slotTime("hi", LocalTime.of(0, 15))).isEqualTo("रात 12:15");
        assertThat(ReminderTexts.slotTime("en", LocalTime.of(12, 5))).isEqualTo("12:05 pm");
        assertThat(ReminderTexts.slotTime("en", LocalTime.of(7, 0))).isEqualTo("7:00 am");
    }

    @Test
    void namesCannotInjectFormattingIntoAPoll() {
        Poll p = ReminderTexts.dosePoll(Reader.person("en", "*Pa\npa*"), List.of(new Item("_Met*formin_\n", null, null, null)), "8:00 pm");
        assertThat(p.question()).doesNotContain("*").doesNotContain("_").isEqualTo("Namaste Pa pa 🙏\n💊 Medicine time: Metformin\nTaken it?");
    }
}
