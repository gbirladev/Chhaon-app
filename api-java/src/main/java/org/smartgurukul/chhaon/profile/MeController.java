package org.smartgurukul.chhaon.profile;

import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The signed-in caregiver's own profile row — everything the web app used to read/write on
 * {@code profiles} directly via Supabase (today-layout choice, privacy acceptance, WhatsApp-alert state), plus the
 * doctor code and "How did you hear about Chhaon?" answer saved at sign-up (migration 0058; counted for the admin only).
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    /** The fields the app edits. Only the pieces that are present are applied. */
    public record MeUpdate(
            String todayLayout,      // set the Today drawing style
            Boolean acceptPrivacy,   // true = record acceptance of policyVersion now
            String policyVersion,
            Boolean alertsOff,       // true = switch WhatsApp alerts off (clears alert_consent_at)
            String referredBy,       // doctor code from the QR card (?ref=D001); first value wins, new accounts only
            String heardFrom) {      // "How did you hear about Chhaon?"; first value wins
    }

    public record MeResponse(
            String userId, String name, String email, String phone, String language, String timezone,
            String plan, boolean isAdmin, String todayLayout,
            OffsetDateTime privacyAcceptedAt, String policyVersion,
            String alertPhone, OffsetDateTime alertRequestedAt, OffsetDateTime alertConfirmedAt,
            OffsetDateTime alertConsentAt, String referredBy, String heardFrom) {
    }

    /** A doctor code as printed on the QR cards (web/public/doctors-page.js allows the same characters). */
    static final Pattern REFERRAL_CODE = Pattern.compile("^[A-Z0-9_-]{1,32}$");
    /** The fixed answers to "How did you hear about Chhaon?" (same list as the database check). */
    static final Set<String> HEARD_FROM = Set.of("doctor", "friend_family", "whatsapp", "google", "other");
    /** A code only counts for an account this new: an existing family that later scans a card was not brought by that doctor. */
    static final Duration REFERRAL_WINDOW = Duration.ofDays(7);

    private final ProfileRepository profiles;
    private final ReferralCodeService referralCodes;

    public MeController(ProfileRepository profiles, ReferralCodeService referralCodes) {
        this.profiles = profiles;
        this.referralCodes = referralCodes;
    }

    private Profile require(AuthenticatedUser user) {
        return profiles.findById(user.id())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Profile not found"));
    }

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return toResponse(require(user));
    }

    @PatchMapping
    public MeResponse update(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody MeUpdate req) {
        Profile p = require(user);
        if (req.todayLayout() != null) {
            if (req.todayLayout().length() > 20) throw new ResponseStatusException(BAD_REQUEST, "Unknown layout");
            p.setTodayLayout(req.todayLayout());   // the DB check constraint rejects unknown names
        }
        if (Boolean.TRUE.equals(req.acceptPrivacy())) {
            if (req.policyVersion() == null || req.policyVersion().isBlank() || req.policyVersion().length() > 40) {
                throw new ResponseStatusException(BAD_REQUEST, "policyVersion is required");
            }
            p.setPrivacyAcceptedAt(OffsetDateTime.now());
            p.setPolicyVersion(req.policyVersion().trim());
        }
        if (Boolean.TRUE.equals(req.alertsOff())) {
            p.setAlertConsentAt(null);
        }
        if (req.referredBy() != null) {
            String code = req.referredBy().trim().toUpperCase(Locale.ROOT);
            if (!REFERRAL_CODE.matcher(code).matches()) throw new ResponseStatusException(BAD_REQUEST, "Unknown referral code");
            // A code shaped like a person's own code (migration 0061) must exist and not be the person's own;
            // campaign codes (doctor D001, creator CRE022, anything with symbols) are taken as they are, as before.
            switch (referralCodes.forSignup(p.getId(), code)) {
                case UNKNOWN -> throw new ResponseStatusException(BAD_REQUEST, "Unknown referral code");
                case OWN -> throw new ResponseStatusException(BAD_REQUEST, "Own referral code");
                case OK -> { }
            }
            boolean newAccount = p.getCreatedAt() == null || p.getCreatedAt().isAfter(OffsetDateTime.now().minus(REFERRAL_WINDOW));
            if (p.getReferredBy() == null && newAccount) p.setReferredBy(code);   // otherwise quietly ignored: first value wins
        }
        if (req.heardFrom() != null) {
            String answer = req.heardFrom().trim().toLowerCase(Locale.ROOT);
            if (!HEARD_FROM.contains(answer)) throw new ResponseStatusException(BAD_REQUEST, "Unknown answer");
            if (p.getHeardFrom() == null) p.setHeardFrom(answer);
        }
        p.setUpdatedAt(OffsetDateTime.now());
        return toResponse(profiles.save(p));
    }

    private static MeResponse toResponse(Profile p) {
        return new MeResponse(
                p.getId().toString(), p.getName(), p.getEmail(), p.getPhone(), p.getLanguage(), p.getTimezone(),
                p.getPlan(), p.isAdmin(), p.getTodayLayout(),
                p.getPrivacyAcceptedAt(), p.getPolicyVersion(),
                p.getAlertPhone(), p.getAlertRequestedAt(), p.getAlertConfirmedAt(), p.getAlertConsentAt(),
                p.getReferredBy(), p.getHeardFrom());
    }
}
