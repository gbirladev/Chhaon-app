package org.smartgurukul.chhaon.profile;

import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The signed-in caregiver's own profile row — everything the web app used to read/write on
 * {@code profiles} directly via Supabase (today-layout choice, privacy acceptance, WhatsApp-alert state).
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    /** The fields the app edits. Only the pieces that are present are applied. */
    public record MeUpdate(
            String todayLayout,      // set the Today drawing style
            Boolean acceptPrivacy,   // true = record acceptance of policyVersion now
            String policyVersion,
            Boolean alertsOff) {     // true = switch WhatsApp alerts off (clears alert_consent_at)
    }

    public record MeResponse(
            String userId, String name, String email, String phone, String language, String timezone,
            String plan, boolean isAdmin, String todayLayout,
            OffsetDateTime privacyAcceptedAt, String policyVersion,
            String alertPhone, OffsetDateTime alertRequestedAt, OffsetDateTime alertConfirmedAt,
            OffsetDateTime alertConsentAt) {
    }

    private final ProfileRepository profiles;

    public MeController(ProfileRepository profiles) {
        this.profiles = profiles;
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
        p.setUpdatedAt(OffsetDateTime.now());
        return toResponse(profiles.save(p));
    }

    private static MeResponse toResponse(Profile p) {
        return new MeResponse(
                p.getId().toString(), p.getName(), p.getEmail(), p.getPhone(), p.getLanguage(), p.getTimezone(),
                p.getPlan(), p.isAdmin(), p.getTodayLayout(),
                p.getPrivacyAcceptedAt(), p.getPolicyVersion(),
                p.getAlertPhone(), p.getAlertRequestedAt(), p.getAlertConfirmedAt(), p.getAlertConsentAt());
    }
}
