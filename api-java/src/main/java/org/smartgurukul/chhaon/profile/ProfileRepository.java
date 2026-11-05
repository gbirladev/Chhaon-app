package org.smartgurukul.chhaon.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {
    Optional<Profile> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);

    /** Resolve a caregiver by their own (normalised E.164) phone — the phone-OTP login key. */
    Optional<Profile> findByPhone(String phone);
}
