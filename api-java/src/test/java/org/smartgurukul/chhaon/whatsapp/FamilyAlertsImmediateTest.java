package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With the scheduler switched ON (its own timer pushed a day away so it never runs by itself here), a parent's "call me"
 * reaches the family on WhatsApp right after the reply, without waiting for the next tick.
 */
@TestPropertySource(properties = {"chhaon.scheduler.enabled=true", "chhaon.scheduler.initial-delay-ms=86400000"})
class FamilyAlertsImmediateTest extends DbTest {

    @Test
    void callMeIsSentToTheFamilyAtOnce_andOnlyOnce() throws Exception {
        Family f = fx.family("en", true);
        String mine = fx.track(Fixtures.randomPhone());
        fx.alertsOn(f.userId(), mine);
        fx.checkin(f.recipientId(), LocalDate.now(f.zone()), "sent", Instant.now().minus(10, ChronoUnit.MINUTES));

        gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "2"));

        assertThat(wa.sentTo(mine)).hasSize(1);
        assertThat(wa.sentTo(mine).get(0).text()).startsWith("Chhaon alert").contains("Papa asked for a call");

        gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "2")); // asked again: no second alert
        assertThat(wa.sentTo(mine)).hasSize(1);
    }
}
