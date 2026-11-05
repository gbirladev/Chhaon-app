package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The "how the tablet looks" line in the dose reminder (migration 0042). Pure unit tests, no database. */
class PillLookTextTest {

    @Test
    void colourAndShapeInEnglish() {
        assertThat(MessageTexts.pillLook("en", "blue", "round")).isEqualTo("🔵 Blue round tablet");
        assertThat(MessageTexts.pillLook("en", "white", "capsule")).isEqualTo("⚪ White capsule");
    }

    @Test
    void hindiColourAgreesWithTheNoun() {
        // गोली is feminine (नीली गोली), कैप्सूल is masculine (नीला कैप्सूल)
        assertThat(MessageTexts.pillLook("hi", "blue", "round")).isEqualTo("🔵 नीली गोल गोली");
        assertThat(MessageTexts.pillLook("hi", "blue", "capsule")).isEqualTo("🔵 नीला कैप्सूल");
        assertThat(MessageTexts.pillLook("hi", "yellow", "long")).isEqualTo("🟡 पीली लंबी गोली");
        assertThat(MessageTexts.pillLook("hi", "red", "oval")).isEqualTo("🔴 लाल अंडाकार गोली");
    }

    @Test
    void onlyOneOfThemKnown() {
        assertThat(MessageTexts.pillLook("en", null, "oval")).isEqualTo("Oval tablet");
        assertThat(MessageTexts.pillLook("hi", null, "round")).isEqualTo("गोल गोली");
        assertThat(MessageTexts.pillLook("en", "green", null)).isEqualTo("🟢 Colour: green");
        assertThat(MessageTexts.pillLook("hi", "green", null)).isEqualTo("🟢 रंग: हरा");
    }

    @Test
    void pinkHasNoDotAndUnknownValuesAreIgnored() {
        assertThat(MessageTexts.pillLook("en", "pink", "round")).isEqualTo("Pink round tablet");
        assertThat(MessageTexts.pillLook("en", null, null)).isEmpty();
        assertThat(MessageTexts.pillLook("en", "Blue*1*", "triangle")).isEmpty();
    }

    @Test
    void theReminderCarriesTheLookOnItsOwnLine() {
        String en = MessageTexts.doseReminder("en", "Papa", "Amlodipine", "blue", "round");
        assertThat(en).isEqualTo("Namaste Papa 🙏\nMedicine time: *Amlodipine*\n🔵 Blue round tablet\n\nTaken it? Just reply *1*");
        String hi = MessageTexts.doseReminder("hi", "पापा", "Amlodipine", "white", "round");
        assertThat(hi).contains("*Amlodipine*\n⚪ सफ़ेद गोल गोली\n\n").endsWith("जवाब में बस *1* लिखें");
    }

    @Test
    void withoutALookTheReminderIsUnchanged() {
        assertThat(MessageTexts.doseReminder("en", "Papa", "Metformin", null, null))
                .isEqualTo(MessageTexts.doseReminder("en", "Papa", "Metformin"))
                .isEqualTo("Namaste Papa 🙏\nMedicine time: *Metformin*\n\nTaken it? Just reply *1*");
    }
}
