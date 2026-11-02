package org.smartgurukul.chhaon.recipient;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CareCircleMemberRepository
        extends JpaRepository<CareCircleMember, CareCircleMember.PK> {

    Optional<CareCircleMember> findByCircleIdAndUserId(UUID circleId, UUID userId);
}
