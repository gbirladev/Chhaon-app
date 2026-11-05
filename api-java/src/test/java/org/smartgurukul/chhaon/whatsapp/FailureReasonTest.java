package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

/** The short reason shown for a failed WhatsApp send: it tells wrong address, wrong key, wrong session name and a refusal apart,
 *  and never carries a phone number, an address or a key. Pure unit test (no database). */
class FailureReasonTest {

    @Test
    void unreachableGateway() {
        assertThat(WhatsAppSender.failureReason(new ResourceAccessException("I/O error on GET request for \"http://10.0.0.5:3000/api/sessions\": Connection refused")))
                .isEqualTo("gateway_unreachable");
    }

    @Test
    void rejectedKeyIs401or403() {
        assertThat(WhatsAppSender.failureReason(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null)))
                .isEqualTo("gateway_key_rejected_401");
        assertThat(WhatsAppSender.failureReason(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], null)))
                .isEqualTo("gateway_key_rejected_403");
    }

    @Test
    void otherHttpStatusesAreNamed() {
        assertThat(WhatsAppSender.failureReason(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null)))
                .isEqualTo("gateway_http_404");
        assertThat(WhatsAppSender.failureReason(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY, new byte[0], null)))
                .isEqualTo("gateway_http_502");
    }

    @Test
    void noSessionWithThatName() {
        assertThat(WhatsAppSender.failureReason(new IllegalStateException("No WhatsApp session named 'Chhaon' on the gateway")))
                .isEqualTo("session_name_not_found");
    }

    @Test
    void aRefusalCarriesTheGatewaysWordsButNoDigits() {
        String reason = WhatsAppSender.failureReason(new IllegalStateException("WhatsApp gateway send failed: 919876543210 is not on WhatsApp"));
        assertThat(reason).isEqualTo("gateway_refused: ############ is not on WhatsApp");
        assertThat(reason).doesNotContain("9198");
    }

    @Test
    void aVeryLongRefusalIsShortened() {
        String reason = WhatsAppSender.failureReason(new IllegalStateException("WhatsApp gateway send failed: " + "x".repeat(300)));
        assertThat(reason.length()).isLessThan(120);
    }

    @Test
    void anythingElseIsNamedByItsClassOnly() {
        assertThat(WhatsAppSender.failureReason(new NullPointerException("secret detail"))).isEqualTo("other_NullPointerException");
    }
}
