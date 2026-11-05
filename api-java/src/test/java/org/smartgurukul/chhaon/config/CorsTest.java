package org.smartgurukul.chhaon.config;

import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.support.DbTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/** The browser at https://chhaon.site must be allowed to call the API (pre-flight first); other sites must not. */
class CorsTest extends DbTest {

    private static final String SITE = "https://chhaon.site";

    @Test
    void thePreflightFromTheLiveSiteIsAnswered() throws Exception {
        var r = mvc.perform(options("/recipients/00000000-0000-0000-0000-000000000000/consent-request")
                .header("Origin", SITE).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("Access-Control-Allow-Origin")).isEqualTo(SITE);
        assertThat(r.getHeader("Access-Control-Allow-Methods")).contains("POST");
        assertThat(r.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("authorization");
        assertThat(r.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void aPreflightFromAnotherSiteIsRefused() throws Exception {
        var r = mvc.perform(options("/recipients/00000000-0000-0000-0000-000000000000/consent-request")
                .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST")).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(r.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void theRealAnswerCarriesTheAllowOriginHeaderForTheLiveSiteOnly() throws Exception {
        var ok = mvc.perform(get("/features").header("Origin", SITE)).andReturn().getResponse();
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getHeader("Access-Control-Allow-Origin")).isEqualTo(SITE);
        var other = mvc.perform(get("/features").header("Origin", "https://evil.example")).andReturn().getResponse();
        assertThat(other.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void protectedRoutesStillNeedALogin() throws Exception {
        var r = mvc.perform(get("/recipients/00000000-0000-0000-0000-000000000000/consent-request").header("Origin", SITE)).andReturn().getResponse();
        assertThat(r.getStatus()).isIn(401, 403, 405);
    }
}
