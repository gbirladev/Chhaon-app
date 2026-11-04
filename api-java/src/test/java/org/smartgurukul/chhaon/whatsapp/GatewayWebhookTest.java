package org.smartgurukul.chhaon.whatsapp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.smartgurukul.chhaon.support.GatewayClient;
import org.smartgurukul.chhaon.support.GatewayClient.Response;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The webhook endpoint itself: signature (fails closed), idempotency, what is ignored, and the number rules. */
class GatewayWebhookTest extends DbTest {

    private static Instant ago(int minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES);
    }

    // ================================================================ signature

    @Test
    void aGoodSignatureIsAccepted() throws Exception {
        Family f = fx.family("en", true);

        Response r = gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "STOP"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNotNull();
    }

    @Test
    void aWrongSignatureIsRefusedAndNothingHappens() throws Exception {
        Family f = fx.family("en", true);
        String id = Fixtures.messageId();
        byte[] body = gateway.mapper().writeValueAsBytes(gateway.received(id, f.phone(), "STOP"));

        Response wrong = gateway.post(body, WebhookSignature.sign("some-other-secret", body));
        Response malformed = gateway.post(body, "sha256=zzzz");
        Response noPrefix = gateway.post(body, WebhookSignature.sign(GatewayClient.SECRET, body).substring(7));

        assertThat(wrong.status()).isEqualTo(401);
        assertThat(malformed.status()).isEqualTo(401);
        assertThat(noPrefix.status()).isEqualTo(401);
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNull();
        assertThat(fx.phoneOptedOut(f.phone())).isFalse();
        assertThat(fx.eventCount("gw:in:" + id)).isEqualTo(0);
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void aMissingSignatureIsRefused() throws Exception {
        Family f = fx.family("en", true);
        byte[] body = gateway.mapper().writeValueAsBytes(gateway.received(Fixtures.messageId(), f.phone(), "STOP"));

        assertThat(gateway.post(body, null).status()).isEqualTo(401);
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNull();
    }

    @Test
    void aSignatureForADifferentBodyIsRefused() throws Exception {
        Family f = fx.family("en", true);
        byte[] original = gateway.mapper().writeValueAsBytes(gateway.received(Fixtures.messageId(), f.phone(), "hello"));
        byte[] tampered = gateway.mapper().writeValueAsBytes(gateway.received(Fixtures.messageId(), f.phone(), "STOP"));

        assertThat(gateway.post(tampered, WebhookSignature.sign(GatewayClient.SECRET, original)).status()).isEqualTo(401);
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNull();
    }

    @Test
    void withoutAConfiguredSecretTheEndpointRefusesEverything() throws Exception {
        // A controller built with a blank secret (the production default until WHATSAPP_WEBHOOK_SECRET is set).
        var controller = new GatewayWebhookController("", gateway.mapper(), null);
        var standalone = MockMvcBuilders.standaloneSetup(controller).build();
        Family f = fx.family("en", true);
        byte[] body = gateway.mapper().writeValueAsBytes(gateway.received(Fixtures.messageId(), f.phone(), "STOP"));

        int status = standalone.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(GatewayClient.URL).contentType("application/json").content(body)
                        .header("X-Webhook-Signature", "sha256=00"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(503);
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNull();
    }

    @Test
    void theEndpointIsPublicButAnythingElseStillNeedsALogin() throws Exception {
        byte[] empty = "{}".getBytes(StandardCharsets.UTF_8);
        // A correctly signed body gets through without any login (the controller, not the login wall, decides).
        assertThat(gateway.post(empty, WebhookSignature.sign(GatewayClient.SECRET, empty)).status()).isEqualTo(200);
        int other = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/nothing"))
                .andReturn().getResponse().getStatus();
        assertThat(other).isIn(401, 403);
    }

    @Test
    void nonJsonAndNonObjectBodiesAreRefused() throws Exception {
        byte[] junk = "not json".getBytes(StandardCharsets.UTF_8);
        assertThat(gateway.post(junk, WebhookSignature.sign(GatewayClient.SECRET, junk)).status()).isEqualTo(400);
        byte[] array = "[1,2]".getBytes(StandardCharsets.UTF_8);
        assertThat(gateway.post(array, WebhookSignature.sign(GatewayClient.SECRET, array)).status()).isEqualTo(400);
    }

    @Test
    void unknownAndOddEventsAreAcceptedAndIgnored() throws Exception {
        for (String body : List.of("{}", "{\"event\":\"connection.update\",\"data\":{}}", "{\"event\":\"test\"}",
                "{\"event\":\"message.received\"}", "{\"event\":\"message.received\",\"data\":{\"key\":5}}",
                "{\"event\":\"message.received\",\"data\":{\"key\":{\"id\":\"x\"},\"content\":12}}",
                "{\"event\":\"message.status\",\"data\":null}", "{\"event\":null}")) {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            assertThat(gateway.post(b, WebhookSignature.sign(GatewayClient.SECRET, b)).status()).as(body).isEqualTo(200);
        }
    }

    // ================================================================ idempotency

    @Test
    void aDuplicateDeliveryActsOnlyOnce() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), ago(7), "sent", ago(5), "wamid-A");
        String id = Fixtures.messageId();
        ObjectNode event = gateway.received(id, f.phone(), "1");

        Response first = gateway.post(event);
        Response second = gateway.post(event);
        Response third = gateway.post(event);

        assertThat(first.body()).contains("handled");
        assertThat(second.body()).contains("duplicate");
        assertThat(third.status()).isEqualTo(200);
        assertThat(fx.stock(m.medicineId())).isEqualTo(19);   // used once
        assertThat(wa.sentTo(f.phone())).hasSize(1);          // thanked once
        assertThat(fx.eventCount("gw:in:" + id)).isEqualTo(1);
        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
    }

    @Test
    void aDuplicateStopNotifiesTheFamilyOnce() throws Exception {
        Family f = fx.family("en", true);
        ObjectNode event = gateway.received(Fixtures.messageId(), f.phone(), "STOP");

        gateway.post(event);
        gateway.post(event);

        assertThat(fx.notifications(f.userId(), "parent_stopped")).isEqualTo(1);
        assertThat(wa.sentTo(f.phone())).hasSize(1);
    }

    // ================================================================ what is ignored

    @Test
    void groupMessagesAreIgnored_evenAStop() throws Exception {
        Family f = fx.family("en", true);
        ObjectNode event = gateway.received(Fixtures.messageId(), f.phone(), "STOP");
        ObjectNode data = (ObjectNode) event.get("data");
        data.put("isGroup", true);
        data.put("chatType", "GROUP");
        ((ObjectNode) data.get("key")).put("remoteJid", "120363000000000000@g.us");
        ((ObjectNode) data.get("key")).put("participant", GatewayClient.jid(f.phone()));

        Response r = gateway.post(event);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("not-one-to-one");
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNull();
        assertThat(fx.phoneOptedOut(f.phone())).isFalse();
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void ourOwnMessagesAreIgnored() throws Exception {
        Family f = fx.family("en", true);
        ObjectNode event = gateway.received(Fixtures.messageId(), f.phone(), "STOP");
        ((ObjectNode) event.get("data").get("key")).put("fromMe", true);

        assertThat(gateway.post(event).body()).contains("from-me");
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNull();
    }

    @Test
    void mediaAndEmptyMessagesAreIgnoredGracefully() throws Exception {
        Family f = fx.family("en", true);
        ObjectNode image = gateway.received(Fixtures.messageId(), f.phone(), "STOP"); // a photo captioned STOP is not an opt-out
        ((ObjectNode) image.get("data")).put("type", "IMAGE");
        ((ObjectNode) image.get("data")).put("caption", "STOP");
        ObjectNode empty = gateway.received(Fixtures.messageId(), f.phone(), "");
        ObjectNode audio = gateway.received(Fixtures.messageId(), f.phone(), "");
        ((ObjectNode) audio.get("data")).put("type", "AUDIO");

        assertThat(gateway.post(image).status()).isEqualTo(200);
        assertThat(gateway.post(empty).status()).isEqualTo(200);
        assertThat(gateway.post(audio).status()).isEqualTo(200);
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNull();
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void aSenderWithOnlyALidAndNoPhoneNumberCannotBeMatched() throws Exception {
        Family f = fx.family("en", true);
        ObjectNode event = gateway.received(Fixtures.messageId(), "184467440737095@lid", "STOP", null);

        Response r = gateway.post(event);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("no-phone");
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNull(); // KNOWN GAP: documented in docs/java-whatsapp-replies.md
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void aDeviceSuffixOnTheJidIsStripped() throws Exception {
        Family f = fx.family("en", true);
        String jid = f.phone().substring(1) + ":17@s.whatsapp.net";

        gateway.post(gateway.received(Fixtures.messageId(), jid, "STOP", null));

        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNotNull();
    }

    @Test
    void jidParsing() {
        assertThat(GatewayEventService.phoneFromJid("919810012345@s.whatsapp.net")).isEqualTo("+919810012345");
        assertThat(GatewayEventService.phoneFromJid("919810012345:3@s.whatsapp.net")).isEqualTo("+919810012345");
        assertThat(GatewayEventService.phoneFromJid("919810012345@c.us")).isEqualTo("+919810012345");
        assertThat(GatewayEventService.phoneFromJid("1234@lid")).isNull();
        assertThat(GatewayEventService.phoneFromJid("120363000@g.us")).isNull();
        assertThat(GatewayEventService.phoneFromJid("status@broadcast")).isNull();
        assertThat(GatewayEventService.phoneFromJid("abc@s.whatsapp.net")).isNull();
        assertThat(GatewayEventService.phoneFromJid("0123456789@s.whatsapp.net")).isNull();
        assertThat(GatewayEventService.phoneFromJid("12@s.whatsapp.net")).isNull();
        assertThat(GatewayEventService.phoneFromJid("")).isNull();
        assertThat(GatewayEventService.phoneFromJid(null)).isNull();
    }

    // ================================================================ only the registered number may answer

    @Test
    void anAnswerFromADifferentNumberDoesNothing() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), ago(7), "sent", ago(5), "wamid-A");
        String stranger = Fixtures.randomPhone();

        gateway.post(gateway.received(Fixtures.messageId(), GatewayClient.jid(stranger), "1", "wamid-A")); // even quoting our message
        gateway.post(gateway.received(Fixtures.messageId(), stranger, "taken"));

        assertThat(fx.doseStatus(dose)).isEqualTo("sent");
        assertThat(wa.sent()).isEmpty();
    }

    // ================================================================ STOP belongs to the number

    @Test
    void stopOutlivesTheParentRecord() throws Exception {
        Family f = fx.family("en", true);
        gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "STOP"));
        assertThat(fx.phoneOptedOut(f.phone())).isTrue();
        wa.reset(); // forget the "we have stopped" confirmation; nothing may be sent from here on

        // the parent record disappears (account deleted, number edited away, ...)
        fx.jdbc().update("delete from care_circles where id = ?", f.circleId());

        assertThat(fx.phoneOptedOut(f.phone())).isTrue();

        // the same number enrolled again, with consent recorded by a caregiver: the reminder is still never sent
        Family again = fx.family(f.phone(), "en", true, f.zone());
        Med m = fx.medicine(again, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        UUID dose = fx.dose(m.scheduleId(), ago(5), "pending", null, null);
        new org.smartgurukul.chhaon.scheduler.SchedulerService(fx.jdbc(), wa, false, 60, 180, 120, 24).tick(again.recipientId());
        assertThat(fx.doseStatus(dose)).isEqualTo("skipped");
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void onlyTheParentCanLiftAStop_andStartDoes() throws Exception {
        Family f = fx.family("en", true);
        gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "STOP"));
        assertThat(fx.phoneOptedOut(f.phone())).isTrue();
        wa.reset();

        gateway.post(gateway.received(Fixtures.messageId(), f.phone(), "START"));

        assertThat(fx.phoneOptedOut(f.phone())).isFalse();
        assertThat(fx.recipient(f.recipientId()).get("opted_out_at")).isNull();
    }

    // ================================================================ a number shared by two parents

    @Test
    void stopFromASharedPhoneStopsBothWithOneReply_andStartBringsBothBack() throws Exception {
        String shared = Fixtures.randomPhone();
        Family papa = fx.family(shared, "hi", true, Fixtures.safeZone());
        Family mummy = fx.addRecipient(papa, "Sunita", shared, "hi", true);

        gateway.post(gateway.received(Fixtures.messageId(), shared, "STOP"));

        assertThat(fx.recipient(papa.recipientId()).get("opted_out_at")).isNotNull();
        assertThat(fx.recipient(mummy.recipientId()).get("opted_out_at")).isNotNull();
        assertThat(wa.sentTo(shared)).hasSize(1);                      // one polite reply, not two

        wa.reset();
        gateway.post(gateway.received(Fixtures.messageId(), shared, "START"));

        assertThat(fx.recipient(papa.recipientId()).get("opted_out_at")).isNull();
        assertThat(fx.recipient(mummy.recipientId()).get("opted_out_at")).isNull();
        assertThat(wa.sentTo(shared)).hasSize(1);
    }

    @Test
    void onSharedPhoneASwipeReplyConfirmsOnlyThatParentsDose_andAnUnquotedOneTheNewest() throws Exception {
        String shared = Fixtures.randomPhone();
        Family papa = fx.family(shared, "hi", true, Fixtures.safeZone());
        Family mummy = fx.addRecipient(papa, "Sunita", shared, "hi", true);
        Med papaMed = fx.medicine(papa, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        Med mummyMed = fx.medicine(mummy, "Calcium", "1 tablet", 20, LocalTime.of(3, 0));
        UUID papaDose = fx.dose(papaMed.scheduleId(), ago(9), "sent", ago(8), "wamid-PAPA");
        UUID mummyDose = fx.dose(mummyMed.scheduleId(), ago(6), "sent", ago(5), "wamid-MUMMY");

        gateway.post(gateway.received(Fixtures.messageId(), GatewayClient.jid(shared), "1", "wamid-PAPA"));
        assertThat(fx.doseStatus(papaDose)).isEqualTo("taken");
        assertThat(fx.doseStatus(mummyDose)).isEqualTo("sent");

        gateway.post(gateway.received(Fixtures.messageId(), shared, "1"));
        assertThat(fx.doseStatus(mummyDose)).isEqualTo("taken");
    }

    @Test
    void onSharedPhoneAYesAnswersTheMostRecentlyAskedConsentOnly() throws Exception {
        String shared = Fixtures.randomPhone();
        Family papa = fx.family(shared, "en", false, Fixtures.safeZone());
        Family mummy = fx.addRecipient(papa, "Sunita", shared, "en", false);
        fx.jdbc().update("update care_recipients set consent_requested_at = now() - interval '1 hour' where id = ?", papa.recipientId());
        fx.jdbc().update("update care_recipients set consent_requested_at = now() where id = ?", mummy.recipientId());

        gateway.post(gateway.received(Fixtures.messageId(), shared, "1"));

        assertThat(fx.recipient(mummy.recipientId()).get("consent_at")).isNotNull();
        assertThat(fx.recipient(papa.recipientId()).get("consent_at")).isNull();
    }

    // ================================================================ delivery status

    @Test
    void deliveredAndReadStatusesAreAcceptedAndChangeNothing() throws Exception {
        Family f = fx.family("en", true);
        for (String status : List.of("PENDING", "SENT", "DELIVERED", "READ")) {
            assertThat(gateway.post(gateway.status("wamid-A", status)).status()).isEqualTo(200);
        }
        assertThat(fx.notifications(f.userId(), "whatsapp_failed")).isEqualTo(0);
    }

    @Test
    void aFailedStatusTellsTheFamilyOncePerParent() throws Exception {
        Family f = fx.family("en", true);
        Med a = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        Med b = fx.medicine(f, "Amlodipine", "5 mg", 20, LocalTime.of(3, 5));
        fx.dose(a.scheduleId(), ago(7), "sent", ago(5), "wamid-SLOT");
        fx.dose(b.scheduleId(), ago(7), "sent", ago(5), "wamid-SLOT"); // one message covering two doses

        gateway.post(gateway.status("wamid-SLOT", "FAILED"));
        gateway.post(gateway.status("wamid-SLOT", "FAILED")); // a repeat

        assertThat(fx.notifications(f.userId(), "whatsapp_failed")).isEqualTo(1);
    }

    @Test
    void aFailedStatusForAnUnknownMessageNotifiesNobody() throws Exception {
        Family f = fx.family("en", true);
        assertThat(gateway.post(gateway.status("wamid-NOBODY", "FAILED")).status()).isEqualTo(200);
        assertThat(fx.notifications(f.userId(), "whatsapp_failed")).isEqualTo(0);
    }
}
