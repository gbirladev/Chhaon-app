package org.smartgurukul.chhaon.recipient;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Membership of a caregiver in a care circle. Composite PK (circle_id, user_id). */
@Entity
@Table(name = "care_circle_members")
@IdClass(CareCircleMember.PK.class)
@Getter
@Setter
public class CareCircleMember {

    @Id
    @Column(name = "circle_id", nullable = false)
    private UUID circleId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "role", nullable = false, columnDefinition = "member_role")
    private MemberRole role = MemberRole.member;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "parent_calls_me")
    private String parentCallsMe;

    /** Composite key. */
    public static class PK implements Serializable {
        private UUID circleId;
        private UUID userId;

        public PK() {}

        public PK(UUID circleId, UUID userId) {
            this.circleId = circleId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(circleId, pk.circleId) && Objects.equals(userId, pk.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(circleId, userId);
        }
    }
}
