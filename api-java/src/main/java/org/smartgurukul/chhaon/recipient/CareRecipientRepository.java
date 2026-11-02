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

    /**
     * The effective parent cap for the user's plan. plan_of_user(uuid) returns a whole plans row
     * (handling the free-trial -> plus logic), so we read max_parents off it. null = unlimited.
     */
    @Query(value = "select (public.plan_of_user(cast(:userId as uuid))).max_parents",
            nativeQuery = true)
    Integer maxParentsForUser(@Param("userId") UUID userId);
}
