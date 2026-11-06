package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The gap between the scheduler's sends (docs/pilot-cap.md). No database. */
class SendPacerTest {

    private static long timeTwoSends(SendPacer pacer) {
        pacer.beforeSend();
        long start = System.nanoTime();
        pacer.beforeSend();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void theFirstSendNeverWaits_theNextOneWaitsTheGap() {
        SendPacer pacer = new SendPacer(80, 80);
        long start = System.nanoTime();
        pacer.beforeSend();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(40);
        long start2 = System.nanoTime();
        pacer.beforeSend();
        assertThat((System.nanoTime() - start2) / 1_000_000).isBetween(70L, 400L);
    }

    @Test
    void theGapIsRandomWithinTheRange() {
        long waited = timeTwoSends(new SendPacer(50, 120));
        assertThat(waited).isBetween(40L, 450L);
    }

    @Test
    void zeroSwitchesItOff() {
        assertThat(timeTwoSends(new SendPacer(0, 0))).isLessThan(30);
    }

    @Test
    void timeAlreadySpentCountsTowardsTheGap() throws InterruptedException {
        SendPacer pacer = new SendPacer(60, 60);
        pacer.beforeSend();
        Thread.sleep(80);   // the send itself took longer than the gap
        long start = System.nanoTime();
        pacer.beforeSend();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(30);
    }
}
