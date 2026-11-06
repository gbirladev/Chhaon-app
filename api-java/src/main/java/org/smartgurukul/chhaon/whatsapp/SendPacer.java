package org.smartgurukul.chhaon.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Spaces out the scheduler's bulk sends (docs/pilot-cap.md): a random gap of pace-min-ms to pace-max-ms (default 3-8 s) between
 * two messages, so 08:00 with many parents becomes a steady trickle from the one WhatsApp number instead of a burst. Only the
 * scheduler's loops call it (reminders, check-ins); a family pressing a button in the app never waits. 0 / 0 switches it off
 * (tests). Shared by every loop, so a reminder and a check-in in the same minute are spaced too.
 */
@Component
public class SendPacer {

    private final long minMs;
    private final long maxMs;
    private long lastSendNanos;   // 0 = nothing sent yet

    public SendPacer(@Value("${chhaon.whatsapp.pace-min-ms:3000}") long minMs,
                     @Value("${chhaon.whatsapp.pace-max-ms:8000}") long maxMs) {
        this.minMs = Math.max(0, minMs);
        this.maxMs = Math.max(this.minMs, maxMs);
    }

    /** Call right before each send: waits until a random gap has passed since the previous one. */
    public synchronized void beforeSend() {
        if (maxMs == 0) return;
        long gapMs = minMs == maxMs ? minMs : ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
        if (lastSendNanos != 0) {
            long waitMs = gapMs - (System.nanoTime() - lastSendNanos) / 1_000_000;
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();   // shutting down: send without waiting
                }
            }
        }
        lastSendNanos = System.nanoTime();
    }
}
