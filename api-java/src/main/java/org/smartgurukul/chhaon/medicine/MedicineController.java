package org.smartgurukul.chhaon.medicine;

import jakarta.validation.Valid;
import org.smartgurukul.chhaon.medicine.dto.MedicineDtos.CreateMedicineRequest;
import org.smartgurukul.chhaon.medicine.dto.MedicineDtos.MedicineFlagsRequest;
import org.smartgurukul.chhaon.medicine.dto.MedicineDtos.MedicineResponse;
import org.smartgurukul.chhaon.medicine.dto.MedicineDtos.StockAdjustRequest;
import org.smartgurukul.chhaon.medicine.dto.MedicineDtos.StockResponse;
import org.smartgurukul.chhaon.medicine.dto.MedicineDtos.UpdateMedicineRequest;
import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Medicines + their schedules, scoped to the caller's care circles. */
@RestController
public class MedicineController {

    private final MedicineService service;

    public MedicineController(MedicineService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/recipients/{recipientId}/medicines")
    public List<MedicineResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable UUID recipientId) {
        return service.list(user.id(), recipientId);
    }

    @PostMapping("/api/v1/recipients/{recipientId}/medicines")
    @ResponseStatus(HttpStatus.CREATED)
    public MedicineResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                   @PathVariable UUID recipientId,
                                   @Valid @RequestBody CreateMedicineRequest request) {
        return service.create(user.id(), recipientId, request);
    }

    @PutMapping("/api/v1/medicines/{id}")
    public MedicineResponse update(@AuthenticationPrincipal AuthenticatedUser user,
                                   @PathVariable UUID id,
                                   @Valid @RequestBody UpdateMedicineRequest request) {
        return service.update(user.id(), id, request);
    }

    @PostMapping("/api/v1/medicines/{id}/stock")
    public StockResponse adjustStock(@AuthenticationPrincipal AuthenticatedUser user,
                                     @PathVariable UUID id,
                                     @RequestBody StockAdjustRequest request) {
        return service.adjustStock(user.id(), id, request);
    }

    @PatchMapping("/api/v1/medicines/{id}")
    public MedicineResponse setFlags(@AuthenticationPrincipal AuthenticatedUser user,
                                     @PathVariable UUID id,
                                     @RequestBody MedicineFlagsRequest request) {
        return service.setFlags(user.id(), id, request);
    }

    /** "Stopped taking it": hidden, no reminders, history kept. Idempotent. */
    @PostMapping("/api/v1/medicines/{id}/archive")
    public MedicineResponse archive(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.archive(user.id(), id);
    }

    /** An archived medicine back on the list, active again. 400 when the plan's limit is reached. Idempotent. */
    @PostMapping("/api/v1/medicines/{id}/restore")
    public MedicineResponse restore(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.restore(user.id(), id);
    }

    /** For a medicine added by mistake only: removes its dose history too. */
    @DeleteMapping("/api/v1/medicines/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        service.delete(user.id(), id);
    }
}
