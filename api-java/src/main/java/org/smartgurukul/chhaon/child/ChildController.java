package org.smartgurukul.chhaon.child;

import org.smartgurukul.chhaon.child.ChildService.ChildInfo;
import org.smartgurukul.chhaon.child.ChildService.ChildInput;
import org.smartgurukul.chhaon.child.ChildService.GivenEarlierInput;
import org.smartgurukul.chhaon.child.ChildService.VaccineInput;
import org.smartgurukul.chhaon.child.ChildService.VaccineRow;
import org.smartgurukul.chhaon.child.ChildService.WeightInput;
import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Child profiles: date of birth + guardian confirmation, and the vaccination plan. Only members of the child's family circle
 * (everything else is 404). A dedicated endpoint instead of new fields on the recipient create/update, so it stays apart from the
 * care-type and reminder-receiver changes to those DTOs.
 *
 * <pre>
 * GET  /api/v1/recipients/{id}/child            date of birth, guardian confirmation, plan size, schedule edition
 * PUT  /api/v1/recipients/{id}/child            {dob, guardianConfirmed}  (confirmation required the first time)
 * PUT  /api/v1/recipients/{id}/child/weight     {weightKg, weightOn}  (0.5-200 kg; both null clears; confirmation needed)
 * GET  /api/v1/recipients/{id}/vaccines         the plan (sorted by date), with a status per dose
 * POST /api/v1/recipients/{id}/vaccines/plan    make the plan from the date of birth (idempotent)
 * POST /api/v1/recipients/{id}/vaccines/given-earlier  {given: [ids], notGivenYet: [ids]}: past doses already given (date not
 *                                               recorded) or not given yet, in one call (idempotent); returns the plan
 * PUT  /api/v1/vaccines/{vaccineId}             {status: given|not_given|due, givenOn, dueDate, notes, resetDueDate}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class ChildController {

    private final ChildService service;

    public ChildController(ChildService service) {
        this.service = service;
    }

    @GetMapping("/recipients/{id}/child")
    public ChildInfo child(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.info(user.id(), id);
    }

    @PutMapping("/recipients/{id}/child")
    public ChildInfo saveChild(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @RequestBody ChildInput in) {
        return service.saveChild(user.id(), id, in);
    }

    @PutMapping("/recipients/{id}/child/weight")
    public ChildInfo saveWeight(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @RequestBody WeightInput in) {
        return service.saveWeight(user.id(), id, in);
    }

    @GetMapping("/recipients/{id}/vaccines")
    public List<VaccineRow> vaccines(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.list(user.id(), id);
    }

    @PostMapping("/recipients/{id}/vaccines/plan")
    public List<VaccineRow> makePlan(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.makePlan(user.id(), id);
    }

    @PostMapping("/recipients/{id}/vaccines/given-earlier")
    public List<VaccineRow> givenEarlier(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                         @RequestBody GivenEarlierInput in) {
        return service.givenEarlier(user.id(), id, in);
    }

    @PutMapping("/vaccines/{vaccineId}")
    public VaccineRow update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID vaccineId,
                             @RequestBody VaccineInput in) {
        return service.update(user.id(), vaccineId, in);
    }
}
