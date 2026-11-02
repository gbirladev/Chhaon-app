package org.smartgurukul.chhaon.scheduler;

import org.smartgurukul.chhaon.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/** Admin-only manual trigger for the reminder tick (ops + testing). */
@RestController
public class AdminTickController {

    private final SchedulerService scheduler;

    public AdminTickController(SchedulerService scheduler) {
        this.scheduler = scheduler;
    }

    @PostMapping("/api/v1/admin/tick")
    public Map<String, Integer> tick(@AuthenticationPrincipal AuthenticatedUser user,
                                     @RequestParam(required = false) UUID recipientId) {
        if (user == null || !user.admin()) {
            throw new ResponseStatusException(FORBIDDEN, "Admin only");
        }
        return scheduler.tick(recipientId);
    }
}
