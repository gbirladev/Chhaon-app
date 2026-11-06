package org.smartgurukul.chhaon.recipient;

import jakarta.validation.Valid;
import org.smartgurukul.chhaon.recipient.dto.RecipientDtos.CreateRecipientRequest;
import org.smartgurukul.chhaon.recipient.dto.RecipientDtos.DoctorsRequest;
import org.smartgurukul.chhaon.recipient.dto.RecipientDtos.RecipientResponse;
import org.smartgurukul.chhaon.recipient.dto.RecipientDtos.UpdateRecipientRequest;
import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Parent profiles (care recipients). All endpoints are scoped to the caller's care circles. */
@RestController
@RequestMapping("/api/v1/recipients")
public class CareRecipientController {

    private final CareRecipientService service;

    public CareRecipientController(CareRecipientService service) {
        this.service = service;
    }

    @GetMapping
    public List<RecipientResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.id());
    }

    @GetMapping("/{id}")
    public RecipientResponse get(@AuthenticationPrincipal AuthenticatedUser user,
                                 @PathVariable UUID id) {
        return service.get(user.id(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecipientResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                    @Valid @RequestBody CreateRecipientRequest request) {
        return service.create(user.id(), request);
    }

    @PutMapping("/{id}")
    public RecipientResponse update(@AuthenticationPrincipal AuthenticatedUser user,
                                    @PathVariable UUID id,
                                    @Valid @RequestBody UpdateRecipientRequest request) {
        return service.update(user.id(), id, request);
    }

    /** "Your doctors": the whole list at once (at most 5). */
    @PutMapping("/{id}/doctors")
    public RecipientResponse updateDoctors(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable UUID id,
                                           @RequestBody DoctorsRequest request) {
        return service.updateDoctors(user.id(), id, request == null ? null : request.doctors());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        service.delete(user.id(), id);
    }
}
