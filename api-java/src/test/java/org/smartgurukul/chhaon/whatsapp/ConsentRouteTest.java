package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** GET /features and POST /recipients/{id}/consent-request, as the web app calls them. */
class ConsentRouteTest extends DbTest {

    @Autowired
    JwtService jwt;

    private MvcResult ask(UUID userId, UUID recipientId) throws Exception {
        var req = post("/recipients/" + recipientId + "/consent-request");
        if (userId != null) req = req.header("Authorization", "Bearer " + jwt.issueToken(userId, false));
        return mvc.perform(req).andReturn();
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    @Test
    void featuresIsPublicAndBothAreOff() throws Exception {
        MvcResult r = mvc.perform(get("/features")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).isEqualTo("{\"escalation\":false,\"love\":false}");
    }

    @Test
    void aMemberAsksOnce_theParentGetsTheTypedQuestion_andAnswering1GivesConsent() throws Exception {
        Family f = fx.family("en", false);

        MvcResult r = ask(f.userId(), f.recipientId());

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).isEqualTo("{\"status\":\"sent\"}");
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("Ananya").contains("Reply 1 to start, 2 for no").contains("STOP");
        assertThat(fx.recipient(f.recipientId()).get("consent_requested_at")).isNotNull();

        // the parent answers by typing 1
        gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "1"));
        assertThat(fx.recipient(f.recipientId()).get("consent_at")).isNotNull();
        assertThat(fx.notifications(f.userId(), "parent_consented")).isEqualTo(1);
    }

    @Test
    void aSecondRequestForTheSameNumberHasToWait() throws Exception {
        Family f = fx.family("en", false);
        ask(f.userId(), f.recipientId());

        MvcResult second = ask(f.userId(), f.recipientId());

        assertThat(body(second)).contains("\"status\":\"too_soon\"").contains("\"retry_after\":");
        assertThat(wa.sentTo(f.phone())).hasSize(1);
    }

    @Test
    void onlyCircleMembersCanAsk_andItNeedsALogin() throws Exception {
        Family f = fx.family("en", false);
        UUID stranger = fx.user("Stranger");

        assertThat(ask(stranger, f.recipientId()).getResponse().getStatus()).isEqualTo(404);
        assertThat(ask(f.userId(), UUID.randomUUID()).getResponse().getStatus()).isEqualTo(404);
        assertThat(ask(null, f.recipientId()).getResponse().getStatus()).isIn(401, 403);
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void alreadyConsentedNoPhoneAndOptedOutSendNothing() throws Exception {
        Family consented = fx.family("en", true);
        assertThat(body(ask(consented.userId(), consented.recipientId()))).isEqualTo("{\"status\":\"already_consented\"}");

        Family noPhone = fx.family("en", false);
        fx.jdbc().update("update care_recipients set whatsapp_phone = null where id = ?", noPhone.recipientId());
        assertThat(body(ask(noPhone.userId(), noPhone.recipientId()))).isEqualTo("{\"status\":\"no_phone\"}");

        Family stopped = fx.family("en", false);
        fx.optOutPhone(stopped.phone());
        assertThat(body(ask(stopped.userId(), stopped.recipientId()))).isEqualTo("{\"status\":\"opted_out\"}");

        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void aFailedSendIs502_andDoesNotCountAgainstTheLimit() throws Exception {
        Family f = fx.family("en", false);
        wa.failSends(true);
        assertThat(ask(f.userId(), f.recipientId()).getResponse().getStatus()).isEqualTo(502);
        assertThat(fx.recipient(f.recipientId()).get("consent_requested_at")).isNull();

        wa.failSends(false);
        assertThat(body(ask(f.userId(), f.recipientId()))).isEqualTo("{\"status\":\"sent\"}");
    }

    @Test
    void aStoppedNumberStaysStoppedEvenIfAnotherParentRecordCarriesIt() throws Exception {
        Family f = fx.family("en", true);
        gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "STOP"));
        Family other = fx.family(f.phone(), "en", false, f.zone());
        wa.reset();

        assertThat(body(ask(other.userId(), other.recipientId()))).isEqualTo("{\"status\":\"opted_out\"}");
        assertThat(wa.sent()).isEmpty();
    }
}
