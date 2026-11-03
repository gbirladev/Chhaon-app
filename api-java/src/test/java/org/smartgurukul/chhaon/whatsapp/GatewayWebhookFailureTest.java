package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.smartgurukul.chhaon.support.GatewayClient.Response;
import org.smartgurukul.chhaon.whatsapp.reply.ReplyRouter;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A processing failure (database trouble) must answer an error and leave NO claim behind and NO half-done work, so a
 * later delivery of the same message can still act. Uses a spy on the router to make processing fail once.
 */
class GatewayWebhookFailureTest extends DbTest {

    @MockitoSpyBean
    ReplyRouter router;

    @Test
    void aFailureAnswers500_releasesTheClaim_andALaterDeliveryStillWorks() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        Instant now = Instant.now();
        UUID dose = fx.dose(m.scheduleId(), now.minus(7, ChronoUnit.MINUTES), "sent", now.minus(5, ChronoUnit.MINUTES), "wamid-A");
        String id = Fixtures.messageId();
        var event = gateway.received(id, f.phone(), "1");

        Mockito.doThrow(new IllegalStateException("simulated database trouble")).when(router).route(Mockito.any(), Mockito.any());
        Response failed = gateway.post(event);

        assertThat(failed.status()).isEqualTo(500);
        assertThat(fx.eventCount("gw:in:" + id)).isEqualTo(0);      // claim released (rolled back with the work)
        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
        assertThat(wa.sent()).isEmpty();

        Mockito.reset(router); // the gateway (or its operator) delivers the same message again
        Response retry = gateway.post(event);

        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body()).contains("handled");
        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
        assertThat(fx.stock(m.medicineId())).isEqualTo(19);
        assertThat(wa.sentTo(f.phone())).hasSize(1);
    }

    @Test
    void aFailedReplySendNeverUndoesTheWork() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        Instant now = Instant.now();
        UUID dose = fx.dose(m.scheduleId(), now.minus(7, ChronoUnit.MINUTES), "sent", now.minus(5, ChronoUnit.MINUTES), "wamid-A");
        wa.failSends(true);

        Response r = gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "1"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
        assertThat(fx.stock(m.medicineId())).isEqualTo(19);
    }
}
