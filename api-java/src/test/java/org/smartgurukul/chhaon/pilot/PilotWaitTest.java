package org.smartgurukul.chhaon.pilot;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.whatsapp.AlertTexts;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The pilot line's wait estimate and the "it's your turn" WhatsApp alert (docs/pilot-cap.md). No database. */
class PilotWaitTest {

    @Test
    void theEstimateIsTodayWhileTodaysRoomLasts_thenAboutADayPerDailyBatch() {
        // 50 seats, 10 used; 5 a day, 2 already let in during the last 24 hours: 3 more today
        assertThat(PilotService.waitDays(1, 50, 10, 5, 2)).isZero();
        assertThat(PilotService.waitDays(3, 50, 10, 5, 2)).isZero();
        assertThat(PilotService.waitDays(4, 50, 10, 5, 2)).isEqualTo(1);
        assertThat(PilotService.waitDays(8, 50, 10, 5, 2)).isEqualTo(1);
        assertThat(PilotService.waitDays(9, 50, 10, 5, 2)).isEqualTo(2);
        // today's quota used up
        assertThat(PilotService.waitDays(1, 50, 10, 5, 5)).isEqualTo(1);
        assertThat(PilotService.waitDays(11, 50, 10, 5, 7)).isEqualTo(3);
    }

    @Test
    void noEstimateWhenEverySeatIsTaken_orNobodyIsLetInPerDay() {
        assertThat(PilotService.waitDays(1, 50, 50, 5, 0)).isNull();     // full: depends on someone stopping
        assertThat(PilotService.waitDays(3, 50, 48, 5, 0)).isNull();     // only 2 seats free for #3
        assertThat(PilotService.waitDays(2, 50, 48, 5, 0)).isZero();
        assertThat(PilotService.waitDays(1, 50, 10, 0, 0)).isNull();     // daily pace 0
        assertThat(PilotService.waitDays(0, 50, 10, 5, 0)).isNull();
    }

    @Test
    void theAdmittedAlertSaysWhoWasAskedToAgree_inEnglishAndHindi() {
        assertThat(AlertTexts.TYPES).contains("pilot_admitted");
        String en = AlertTexts.alert("pilot_admitted", "en", "Papa", null, null, null);
        assertThat(en).isEqualTo("Chhaon alert 🔔\nGood news 🎉 Papa's reminders on Chhaon can start now.\n"
                + "We've sent Papa a WhatsApp message asking them to agree. The reminders start once Papa replies.\n\n"
                + "Reply STOP to turn off these alerts.");
        String hi = AlertTexts.alert("pilot_admitted", "hi", "पापा", null, null, null);
        assertThat(hi).startsWith("छाँव अलर्ट 🔔\nअच्छी ख़बर 🎉 अब छाँव पर पापा के रिमाइंडर शुरू हो सकते हैं।")
                .contains("हमने पापा को WhatsApp पर सहमति का संदेश भेज दिया है").contains("STOP");
        assertThat(AlertTexts.alert("pilot_admitted", "en", "Papa", null, null, null, "Sunita (maid)"))
                .contains("We've sent Sunita (maid), who will get the reminders, a WhatsApp message asking them to agree.");
        assertThat(AlertTexts.alert("pilot_admitted", "en", "Papa", null, null, null, "you"))
                .contains("We've sent you a WhatsApp message asking you to agree. Reply 1 to it");
        assertThat(AlertTexts.alert("pilot_admitted", "hi", "पापा", null, null, null, "आप")).contains("हमने आपको WhatsApp पर");
        assertThat(AlertTexts.pilotAdmittedSelf("en")).contains("Good news 🎉 your reminders on Chhaon can start now.");
        assertThat(AlertTexts.pilotAdmittedSelf("hi")).contains("अब छाँव पर आपके रिमाइंडर");
        // the owner's fraud rule: no links, and short enough for a chat
        for (String lang : List.of("en", "hi")) {
            for (String r : List.of("", "Sunita (maid)", "you", "आप")) {
                String t = AlertTexts.alert("pilot_admitted", lang, "Papa", null, null, null, r);
                assertThat(t).doesNotContain("http").doesNotContain(".site").doesNotContain("null");
                assertThat(t.length()).isLessThan(330);
            }
            assertThat(AlertTexts.pilotAdmittedSelf(lang)).doesNotContain("http").contains("STOP");
        }
    }
}
