package app.classpool.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches {@code contracts/openapi.yaml}'s {@code Payment} schema exactly. {@code
 * householdDisplayName} is the same organizer-only identity split as {@code
 * ContributionResponse.offeringParentDisplayName} — populated on {@code listPaymentsForOrganizer}/
 * {@code generatePayments}/{@code getPaymentsSummary}'s outstanding list, null on {@code
 * getMyPayment}. {@code method} is null whenever the underlying {@code Payment.state} is {@code
 * PENDING} — see {@link app.classpool.api.domain.Payment}'s Javadoc for why the entity can't
 * literally store a null there.
 */
public record PaymentResponse(
        UUID id,
        UUID poolId,
        UUID householdId,
        String householdDisplayName,
        int amountCents,
        String method,
        String state,
        Instant createdAt
) {
}
