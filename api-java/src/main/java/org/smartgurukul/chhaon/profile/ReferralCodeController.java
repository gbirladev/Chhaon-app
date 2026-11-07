package org.smartgurukul.chhaon.profile;

import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * "Invite friends" (docs/referral-codes.md): the caregiver's own referral code, choosing another one, how many families
 * joined with it, and the sign-up check of a code someone typed. Codes are never shown to anyone but their owner.
 *
 *   GET /api/v1/me/referral                  {installed, code, link, families, changesLeft} (creates the first code)
 *   GET /api/v1/me/referral/check?code=X     {code, status: available|yours|taken|reserved|format, suggestions[]}
 *   PUT /api/v1/me/referral {code}           200 as GET; 409 {status, suggestions}; 429 when no changes are left
 *   GET /api/v1/referral/validate?code=X     {code, status: ok|unknown|own} for the sign-up field
 */
@RestController
public class ReferralCodeController {

    private final ReferralCodeService codes;
    private final ProfileRepository profiles;

    public ReferralCodeController(ReferralCodeService codes, ProfileRepository profiles) {
        this.codes = codes;
        this.profiles = profiles;
    }

    private String name(UUID id) {
        return profiles.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Profile not found")).getName();
    }

    @GetMapping("/api/v1/me/referral")
    public Map<String, Object> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return toMap(codes.mine(user.id(), name(user.id())));
    }

    @GetMapping("/api/v1/me/referral/check")
    public Map<String, Object> check(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam("code") String code) {
        return toMap(codes.check(user.id(), code, name(user.id())));
    }

    @PutMapping("/api/v1/me/referral")
    public ResponseEntity<Map<String, Object>> change(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("code");
        String wanted = raw instanceof String s ? s : "";
        ReferralCodeService.Change c = codes.change(user.id(), wanted, name(user.id()));
        if (c.tooMany()) {
            Map<String, Object> m = toMap(c.check());
            m.put("status", "too_many");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(m);
        }
        if (c.mine() == null) return ResponseEntity.status(HttpStatus.CONFLICT).body(toMap(c.check()));
        return ResponseEntity.ok(toMap(c.mine()));
    }

    @GetMapping("/api/v1/referral/validate")
    public Map<String, Object> validate(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam("code") String raw) {
        String code = ReferralCodeService.normalise(raw);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        if (!MeController.REFERRAL_CODE.matcher(code).matches()) {
            m.put("status", "unknown");
            return m;
        }
        m.put("status", switch (codes.forSignup(user.id(), code)) {
            case OK -> "ok";
            case UNKNOWN -> "unknown";
            case OWN -> "own";
        });
        return m;
    }

    private static Map<String, Object> toMap(ReferralCodeService.Mine m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("installed", m.installed());
        out.put("code", m.code());
        out.put("link", m.link());
        out.put("families", m.families());
        out.put("changesLeft", m.changesLeft());
        return out;
    }

    private static Map<String, Object> toMap(ReferralCodeService.Check c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", c.code());
        out.put("status", c.status().name().toLowerCase());
        out.put("suggestions", c.suggestions() == null ? List.of() : c.suggestions());
        return out;
    }
}
