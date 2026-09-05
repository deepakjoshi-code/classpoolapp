package app.classpool.api.dto;

import java.util.UUID;

/** Matches {@code contracts/openapi.yaml}'s {@code OrganizerStripeAccount} schema exactly.
 *  {@code onboardingUrl} is only ever non-null while {@code status == PENDING} (contract's own
 *  description) — see {@code PaymentService} for where it's (re-)derived. */
public record OrganizerStripeAccountResponse(
        UUID classroomId,
        String status,
        String onboardingUrl
) {
}
