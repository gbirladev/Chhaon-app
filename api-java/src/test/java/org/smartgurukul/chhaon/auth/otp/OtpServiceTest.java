package org.smartgurukul.chhaon.auth.otp;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.auth.dto.AuthDtos.OtpLoginResponse;
import org.smartgurukul.chhaon.profile.Profile;
import org.smartgurukul.chhaon.profile.ProfileRepository;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration tests for phone-OTP login (against the throwaway test database). */
class OtpServiceTest extends DbTest {

    private static final Pattern DIGITS = Pattern.compile("\\d{4,}");

    @Autowired OtpService otp;
    @Autowired OtpCodeRepository codes;
    @Autowired ProfileRepository profiles;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;

    private final List<String> usedPhones = new ArrayList<>();

    private String freshPhone() {
        String p = "+9197" + String.format("%08d", (int) (Math.random() * 1e8));
        usedPhones.add(p);
        return p;
    }

    /** The code the gateway "sent", read back from the recording sender. */
    private String sentCode(String phone) {
        var sent = wa.sentTo(phone);
        assertThat(sent).isNotEmpty();
        Matcher m = DIGITS.matcher(sent.get(sent.size() - 1).text());
        assertThat(m.find()).isTrue();
        return m.group();
    }

    @AfterEach
    void cleanupOtp() {
        for (String p : usedPhones) {
            jdbc.update("delete from otp_codes where phone = ?", p);
            jdbc.update("delete from profiles where phone = ?", p);
        }
        usedPhones.clear();
    }

    @Test
    void requestThenVerifyLogsInAndCreatesAProfileForANewPhone() {
        String phone = freshPhone();
        otp.requestCode(phone);

        OtpLoginResponse resp = otp.verify(phone, sentCode(phone));

        assertThat(resp.isNew()).isTrue();
        assertThat(resp.token()).isNotBlank();
        assertThat(profiles.findByPhone(phone)).isPresent();

        // The minted token is one of OUR JWTs (APP_JWT_SECRET): the API's own filter accepts it.
        Claims claims = jwtService.parse(resp.token());
        assertThat(claims.getSubject()).isEqualTo(resp.userId());
        assertThat(claims.get("admin", Boolean.class)).isFalse();
    }

    @Test
    void loginAlsoHandsOutASupabaseCompatibleHs256Token() throws Exception {
        String phone = freshPhone();
        otp.requestCode(phone);

        OtpLoginResponse resp = otp.verify(phone, sentCode(phone));

        // Supabase (PostgREST + Storage) only accepts HS256 tokens signed with the project's legacy JWT secret, with
        // role = aud = "authenticated" and sub = the user id. Our own API token does not qualify.
        String sb = resp.supabaseToken();
        assertThat(sb).isNotBlank().isNotEqualTo(resp.token());
        String header = new String(java.util.Base64.getUrlDecoder().decode(sb.split("\\.")[0]), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(header).contains("\"alg\":\"HS256\"");
        javax.crypto.SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                "test-only-supabase-secret-0123456789-abcdefghijklmnop".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Claims c = io.jsonwebtoken.Jwts.parser().verifyWith(key).build().parseSignedClaims(sb).getPayload();
        assertThat(c.getSubject()).isEqualTo(resp.userId());
        assertThat(c.get("role", String.class)).isEqualTo("authenticated");
        assertThat(c.getAudience()).contains("authenticated");
    }

    @Test
    void verifyReturnsTheExistingProfileWhenThePhoneIsKnown() {
        String phone = freshPhone();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("insert into profiles (id, name, phone, plan, trial_ends_at, is_admin, created_at, updated_at) "
                        + "values (?, 'Existing', ?, 'family', ?, false, ?, ?)",
                id, phone,
                java.sql.Timestamp.from(now.plusDays(30).toInstant()),
                java.sql.Timestamp.from(now.toInstant()),
                java.sql.Timestamp.from(now.toInstant()));

        otp.requestCode(phone);
        OtpLoginResponse resp = otp.verify(phone, sentCode(phone));

        assertThat(resp.isNew()).isFalse();
        assertThat(resp.userId()).isEqualTo(id.toString());
        assertThat(resp.name()).isEqualTo("Existing");
    }

    @Test
    void aWrongCodeIsRejectedAndBurnsAfterTooManyAttempts() {
        String phone = freshPhone();
        otp.requestCode(phone);
        String wrong = sentCode(phone).equals("000000") ? "111111" : "000000";

        // 5 wrong guesses (max-failed-attempts), each rejected.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> otp.verify(phone, wrong))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // Now even the RIGHT code is dead: the code was burned.
        String right = sentCode(phone);
        assertThatThrownBy(() -> otp.verify(phone, right))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void verifyWithoutARequestedCodeIs401() {
        String phone = freshPhone();
        assertThatThrownBy(() -> otp.verify(phone, "123456"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aSecondRequestWithinTheCooldownIsRateLimited() {
        String phone = freshPhone();
        otp.requestCode(phone);
        assertThatThrownBy(() -> otp.requestCode(phone))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void anInvalidPhoneIs400() {
        assertThatThrownBy(() -> otp.requestCode("not-a-phone"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
