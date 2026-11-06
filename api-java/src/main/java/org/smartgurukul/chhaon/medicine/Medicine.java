package org.smartgurukul.chhaon.medicine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A medicine for a recipient. Maps public.medicines. */
@Entity
@Table(name = "medicines")
@Getter
@Setter
public class Medicine {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "dosage")
    private String dosage;

    /** Storage path in the private medicine-photos bucket (not a public URL). */
    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "stock_count")
    private Integer stockCount;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** When the family archived it ("stopped taking it", migration 0056); null = not archived. Archived = always inactive. */
    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    /** one of after_food | before_food | with_food, or null. */
    @Column(name = "instructions")
    private String instructions;

    @Column(name = "refill_threshold")
    private Integer refillThreshold;

    @Column(name = "critical", nullable = false)
    private boolean critical = false;

    /** How the tablet looks (migration 0042): one of MedicineService.PILL_COLOURS, or null = not set. */
    @Column(name = "pill_colour")
    private String pillColour;

    /** round | oval | long | capsule, or null = not set. */
    @Column(name = "pill_shape")
    private String pillShape;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
