package org.smartgurukul.chhaon.auth.otp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartgurukul.chhaon.auth.dto.AuthDtos.OtpLoginResponse;
import org.smartgurukul.chhaon.profile.Profile;
import org.smartgurukul.chhaon.profile.ProfileRepository;
import org.smartgurukul.chhaon.recipient.PhoneNormalizer;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.whatsapp.MessageTexts;
import org.smartgurukul.chhaon.whatsapp.WhatsAppSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Phone-OTP login, owned by this backend (mirrors Smart Gurukul). We generate a one-time code, store only
 * its BCrypt hash, deliver it over WhatsApp, verify it, and mint a Supabase-compatible session token. A
 * brand-new phone creates a profile on first successful verify.
 *
 * <p>Never logs the phone number or the code (DPDP): only {@link WhatsAppSender#mask} where unavoidable.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpCodeRepository codes;
    private final ProfileRepository profiles;
    private final PasswordEncoder passwordEncoder;
    private final WhatsAppSender whatsApp;
    private final JwtService jwt;

    private final int codeLength;
    private final long expiryMinutes;
    private final long cooldownSeconds;
    private final int maxPerHour;
    private final int maxFailedAttempts;
    private final String reviewPhone;
    private final String reviewCode;

    public OtpService(OtpCodeRepository codes,
                      ProfileRepository profiles,
                      PasswordEncoder passwordEncoder,
                      WhatsAppSender whatsApp,
                      JwtService jwt,
                      @Value("${app.otp.code-length:6}") int codeLength,
                      @Value("${app.otp.expiry-minutes:5}") long expiryMinutes,
                      @Value("${app.otp.request-cooldown-seconds:30}") long cooldownSeconds,
                      @Value("${app.otp.max-requests-per-hour:5}") int maxPerHour,
                      @Value("${app.otp.max-failed-attempts:5}") int maxFailedAttempts,
                      @Value("${app.otp.review-phone:}") String reviewPhone,
                      @Value("${app.otp.review-code:}") String reviewCode) {
        this.codes = codes;
        this.profiles = profiles;
        this.passwordEncoder = passwordEncoder;
        this.whatsApp = whatsApp;
        this.jwt = jwt;
        this.codeLength = codeLength;
        this.expiryMinutes = expiryMinutes;
        this.cooldownSeconds = cooldownSeconds;
        this.maxPerHour = maxPerHour;
        this.maxFailedAttempts = maxFailedAttempts;
        String normalizedReviewPhone = PhoneNormalizer.normalize(reviewPhone);
        // The review login exists only when BOTH are set and the code is at least 8 characters: fails closed.
        boolean reviewOn = normalizedReviewPhone != null && reviewCode != null && reviewCode.trim().length() >= 8;
        this.reviewPhone = reviewOn ? normalizedReviewPhone : null;
        this.reviewCode = reviewOn ? reviewCode.trim() : null;
    }

    /**
     * The Play/App Store review account: ONE configured phone whose fixed code is checked against config instead of
     * a sent OTP. No WhatsApp message is ever sent to it, and no code row is written for it.
     */
    private boolean isReviewAccount(String normalizedPhone) {
        return reviewPhone != null && reviewPhone.equals(normalizedPhone);
    }

    /** Generate, store (hashed) and send a code. Rate-limited per phone. */
    @Transactional
    public void requestCode(String rawPhone) {
        String phone = normalizeOr400(rawPhone);
        if (isReviewAccount(phone)) return; // fixed code, nothing to send or store
        OffsetDateTime now = OffsetDateTime.now();

        // Cooldown between sends.
        codes.findFirstByPhoneOrderByCreatedAtDesc(phone).ifPresent(last -> {
            if (last.getCreatedAt().plusSeconds(cooldownSeconds).isAfter(now)) {
                throw new ResponseStatusException(TOO_MANY_REQUESTS, "Please wait a little before asking for another code");
            }
        });
        // Hourly cap.
        if (codes.countByPhoneAndCreatedAtAfter(phone, now.minusHours(1)) >= maxPerHour) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "Too many codes requested. Please try again later");
        }

        String code = generateCode();
        OtpCode row = new OtpCode();
        row.setId(UUID.randomUUID());
        row.setPhone(phone);
        row.setCodeHash(passwordEncoder.encode(code));
        row.setExpiresAt(now.plusMinutes(expiryMinutes));
        row.setCreatedAt(now);
        codes.save(row);

        try {
            whatsApp.send(phone, MessageTexts.loginOtp(code));
        } catch (Exception e) {
            String reason = WhatsAppSender.failureReason(e);
            log.error("otp request: delivery failed to {} ({})", WhatsAppSender.mask(phone), reason);
            throw new ResponseStatusException(BAD_GATEWAY, "Could not send the code right now. Please try again");
        }
    }

    /**
     * Verify a code; on success resolve or create the profile and mint a session token. A wrong guess saves the higher failed count
     * (and burns the code at the limit) and THEN answers 401, so the 401 must not roll that save back.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public OtpLoginResponse verify(String rawPhone, String rawCode) {
        String phone = normalizeOr400(rawPhone);
        String code = rawCode == null ? "" : rawCode.trim();
        OffsetDateTime now = OffsetDateTime.now();

        if (isReviewAccount(phone)) {
            if (!java.security.MessageDigest.isEqual(
                    code.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    reviewCode.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                throw new ResponseStatusException(UNAUTHORIZED, "This code is wrong or has expired");
            }
            return loginFor(phone, now);
        }

        OtpCode row = codes
                .findFirstByPhoneAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(phone, now)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "This code is wrong or has expired"));

        if (row.getFailedAttempts() >= maxFailedAttempts) {
            row.setConsumedAt(now); // burn it
            codes.save(row);
            throw new ResponseStatusException(UNAUTHORIZED, "This code is wrong or has expired");
        }

        if (!passwordEncoder.matches(code, row.getCodeHash())) {
            row.setFailedAttempts(row.getFailedAttempts() + 1);
            if (row.getFailedAttempts() >= maxFailedAttempts) {
                row.setConsumedAt(now); // burn after too many wrong guesses
            }
            codes.save(row);
            throw new ResponseStatusException(UNAUTHORIZED, "This code is wrong or has expired");
        }

        // Correct: consume the code and log the user in.
        row.setConsumedAt(now);
        codes.save(row);
        return loginFor(phone, now);
    }

    /** Resolve or create the profile for a verified phone and mint its session token. */
    private OtpLoginResponse loginFor(String phone, OffsetDateTime now) {
        Optional<Profile> existing = profiles.findByPhone(phone);
        Profile profile = existing.orElseGet(() -> createProfile(phone, now));
        // Our own JWT (APP_JWT_SECRET) for this API; plus a Supabase-compatible one for the app's direct Supabase calls.
        String token = jwt.issueToken(profile.getId(), profile.isAdmin());
        return new OtpLoginResponse(
                token,
                profile.getId().toString(),
                profile.getName(),
                profile.getPlan(),
                existing.isEmpty(),
                supabaseTokenFor(profile.getId()));
    }

    private SupabaseCompatibleTokenService supabaseTokens;

    /** Optional: when present, login also hands out a Supabase-compatible token for the app's direct Supabase calls. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSupabaseTokens(SupabaseCompatibleTokenService supabaseTokens) {
        this.supabaseTokens = supabaseTokens;
    }

    /** Never blocks login: no secret configured (or any error) means no Supabase token, and only direct Supabase calls fail. */
    private String supabaseTokenFor(java.util.UUID userId) {
        if (supabaseTokens == null) return null;
        try {
            return supabaseTokens.issue(userId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Profile createProfile(String phone, OffsetDateTime now) {
        Profile p = new Profile();
        p.setId(UUID.randomUUID());
        p.setPhone(phone);
        p.setTimezone("Asia/Kolkata");
        p.setLanguage("en");
        p.setPlan("free");
        p.setTrialEndsAt(now.plusDays(30));
        p.setAdmin(false);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return profiles.save(p);
    }

    private String normalizeOr400(String raw) {
        String phone = PhoneNormalizer.normalize(raw);
        if (phone == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Please enter a valid phone number");
        }
        return phone;
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }
}
