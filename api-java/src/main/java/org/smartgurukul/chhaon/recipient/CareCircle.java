package org.smartgurukul.chhaon.recipient;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A care circle — the group around one recipient. Inserting a circle fires the DB trigger
 * {@code handle_new_circle}, which adds the creator as an owner member automatically.
 */
@Entity
@Table(name = "care_circles")
@Getter
@Setter
public class CareCircle {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
