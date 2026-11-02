package org.smartgurukul.chhaon.recipient;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CareRecipientRepository extends JpaRepository<CareRecipient, UUID> {

    /** Recipients in any circle the user belongs to, oldest first (RLS-equivalent scoping). */
    @Query("""
            select r from CareRecipient r
            where r.circleId in (
                select m.circleId from CareCircleMember m where m.userId = :userId)
            order by r.createdAt asc
            """)
    List<CareRecipient> findAllForUser(@Param("userId") UUID userId);

    /** A single recipient, only if the user is a member of its circle. */
    @Query("""
            select r from CareRecipient r
            where r.id = :id and r.circleId in (
                select m.circleId from CareCircleMember m where m.userId = :userId)
            """)
    Optional<CareRecipient> findForUser(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Non-self recipients the user can see, for the plan limit (mirrors limit_parents count). */
    @Query("""
            select count(r) from CareRecipient r
            where r.self = false and r.circleId in (
                select m.circleId from CareCircleMember m where m.userId = :userId)
            """)
    long countNonSelfForUser(@Param("userId") UUID userId);

    /** The effective parent cap for the user's plan, via the existing DB function plan_of_user. */
    @Query(value = "select p.max_parents from plans p where p.key = public.plan_of_user(:userId)",
            nativeQuery = true)
    Integer maxParentsForUser(@Param("userId") UUID userId);
}
