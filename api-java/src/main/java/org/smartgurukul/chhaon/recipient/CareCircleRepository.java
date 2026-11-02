package org.smartgurukul.chhaon.recipient;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CareCircleRepository extends JpaRepository<CareCircle, UUID> {
}
