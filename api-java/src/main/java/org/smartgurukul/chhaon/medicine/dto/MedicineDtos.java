package org.smartgurukul.chhaon.medicine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/** Request/response payloads for medicines + their schedules. */
public final class MedicineDtos {

    private MedicineDtos() {}

    public record CreateMedicineRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 60) String dosage,
            String instructions,                 // after_food | before_food | with_food | null
            Integer stock,                       // null = stock not tracked (no refill or out-of-stock alerts)
            Integer refillThreshold,
            @NotEmpty List<LocalTime> times,     // 1..12 distinct
            String photoPath,                    // <recipientId>/<name>; null = none
            LocalDate startDate,
            LocalDate endDate,
            String pillColour,                   // white|yellow|...|purple; null or "" = not set
            String pillShape) {                  // round|oval|long|capsule; null or "" = not set
    }

    public record UpdateMedicineRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 60) String dosage,
            String instructions,
            Integer refillThreshold,
            @NotEmpty List<LocalTime> times,
            String photoPath,                    // null = keep existing photo
            String pillColour,                   // null (or absent) = keep, "" = clear, a listed value = set
            String pillShape) {                  // same rule as pillColour
    }

    /**
     * One of: {@code delta} (add / take off, never below 0; nothing happens while stock is not tracked), {@code count} (the exact
     * number, 0..100000; this also STARTS tracking a medicine whose stock was not tracked), or {@code untrack: true} (stop tracking:
     * stock becomes null, no refill or out-of-stock alerts any more).
     */
    public record StockAdjustRequest(Integer delta, Integer count, Boolean untrack) {
        public StockAdjustRequest(Integer delta) {
            this(delta, null, null);
        }
    }

    /** null = stock not tracked. */
    public record StockResponse(Integer stock) {}

    /** Partial flags; only non-null fields are applied. */
    public record MedicineFlagsRequest(Boolean active, Boolean critical) {}

    /** Mirrors the web client's Medicine type. */
    public record MedicineResponse(
            String id,
            String name,
            String dose,            // = dosage ?? ""
            List<LocalTime> times,
            Integer stock,          // = stockCount; null = not tracked (the family left the count empty)
            Integer threshold,      // = refillThreshold
            String instructions,
            boolean active,
            String photoUrl,        // storage path
            boolean critical,
            OffsetDateTime createdAt,
            String pillColour,      // null = not set
            String pillShape,       // null = not set
            OffsetDateTime archivedAt) {   // null = not archived; archived = "stopped taking it", history kept
    }
}
