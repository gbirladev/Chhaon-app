package org.smartgurukul.chhaon.auth;

import org.smartgurukul.chhaon.auth.dto.AuthDtos.AuthResponse;
import org.smartgurukul.chhaon.auth.dto.AuthDtos.LoginRequest;
import org.smartgurukul.chhaon.auth.dto.AuthDtos.SignupRequest;
import org.smartgurukul.chhaon.profile.Profile;
import org.smartgurukul.chhaon.profile.ProfileRepository;
import org.smartgurukul.chhaon.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class AuthService {

    private final ProfileRepository profiles;
    private final UserCredentialRepository credentials;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(ProfileRepository profiles,
                       UserCredentialRepository credentials,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.profiles = profiles;
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse signup(SignupRequest req) {
        String email = req.email().trim().toLowerCase();
        if (profiles.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(CONFLICT, "An account with this email already exists");
        }

        OffsetDateTime now = OffsetDateTime.now();
        UUID userId = UUID.randomUUID();

        Profile profile = new Profile();
        profile.setId(userId);
        profile.setName(req.name().trim());
        profile.setEmail(email);
        profile.setPhone(emptyToNull(req.phone()));
        profile.setCountry(emptyToNull(req.country()));
        profile.setTimezone(req.timezone() == null || req.timezone().isBlank()
                ? "Asia/Kolkata" : req.timezone());
        profile.setLanguage(req.language() == null || req.language().isBlank()
                ? "en" : req.language());
        profile.setPlan("free");
        profile.setTrialEndsAt(now.plusDays(30));
        profile.setAdmin(false);
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        profiles.save(profile);

        UserCredential cred = new UserCredential();
        cred.setUserId(userId);
        cred.setPasswordHash(passwordEncoder.encode(req.password()));
        cred.setCreatedAt(now);
        cred.setUpdatedAt(now);
        credentials.save(cred);

        return toResponse(profile);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        String email = req.email().trim().toLowerCase();
        Profile profile = profiles.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Invalid email or password"));
        UserCredential cred = credentials.findByUserId(profile.getId())
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Invalid email or password"));
        if (!passwordEncoder.matches(req.password(), cred.getPasswordHash())) {
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid email or password");
        }
        return toResponse(profile);
    }

    private AuthResponse toResponse(Profile profile) {
        String token = jwtService.issueToken(profile.getId(), profile.isAdmin());
        return new AuthResponse(
                token,
                profile.getId().toString(),
                profile.getName(),
                profile.getEmail(),
                profile.getPlan());
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
