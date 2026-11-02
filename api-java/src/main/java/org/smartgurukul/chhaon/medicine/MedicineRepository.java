package org.smartgurukul.chhaon.medicine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MedicineRepository extends JpaRepository<Medicine, UUID> {

    List<Medicine> findByRecipientIdOrderByCreatedAtAsc(UUID recipientId);

    long countByRecipientId(UUID recipientId);

    /** A medicine, only if the caller is a member of its recipient's circle. */
    @Query("""
            select md from Medicine md
            where md.id = :id and md.recipientId in (
                select r.id from CareRecipient r where r.circleId in (
                    select m.circleId from CareCircleMember m where m.userId = :userId))
            """)
    Optional<Medicine> findForUser(@Param("id") UUID id, @Param("userId") UUID userId);
}
