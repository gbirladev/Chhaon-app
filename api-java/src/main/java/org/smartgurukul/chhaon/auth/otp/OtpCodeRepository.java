package org.smartgurukul.chhaon.auth.otp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {

    /** The newest still-usable code for a phone (not consumed, not expired). */
    Optional<OtpCode> findFirstByPhoneAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            String phone, OffsetDateTime now);

    /** The newest code of any state for a phone (for the send cooldown). */
    Optional<OtpCode> findFirstByPhoneOrderByCreatedAtDesc(String phone);

    /** How many codes were requested for a phone since the given instant (hourly cap). */
    long countByPhoneAndCreatedAtAfter(String phone, OffsetDateTime since);
}
