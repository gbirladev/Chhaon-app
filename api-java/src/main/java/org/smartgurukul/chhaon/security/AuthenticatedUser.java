package org.smartgurukul.chhaon.security;

import java.util.UUID;

/**
 * The authenticated caregiver, resolved from a validated JWT and placed in the security context.
 * Retrieve it in controllers via {@code @AuthenticationPrincipal AuthenticatedUser user}.
 */
public record AuthenticatedUser(UUID id, boolean admin) {
}
