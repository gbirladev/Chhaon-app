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
            Integer stock,
            Integer refillThreshold,
            @NotEmpty List<LocalTime> times,     // 1..12 distinct
            String photoPath,                    // <recipientId>/<name>; null = none
            LocalDate startDate,
            LocalDate endDate) {
    }

    public record UpdateMedicineRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 60) String dosage,
            String instructions,
            Integer refillThreshold,
            @NotEmpty List<LocalTime> times,
            String photoPath) {                  // null = keep existing photo
    }

    public record StockAdjustRequest(Integer delta) {}

    public record StockResponse(Integer stock) {}

    /** Partial flags; only non-null fields are applied. */
    public record MedicineFlagsRequest(Boolean active, Boolean critical) {}

    /** Mirrors the web client's Medicine type. */
    public record MedicineResponse(
            String id,
            String name,
            String dose,            // = dosage ?? ""
            List<LocalTime> times,
            int stock,              // = stockCount ?? 0
            Integer threshold,      // = refillThreshold
            String instructions,
            boolean active,
            String photoUrl,        // storage path
            boolean critical,
            OffsetDateTime createdAt) {
    }
}
