package app.classpool.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Matches {@code contracts/openapi.yaml}'s {@code PurchasePlan} schema exactly. {@code
 *  totalCostCents} is the sum of {@code totalCostCents} across every line — computed once at
 *  response-assembly time, same "derive, don't duplicate a stored total" instinct as {@code
 *  RequirementAssembler}'s totalDemand. */
public record PurchasePlanResponse(
        UUID id,
        UUID poolId,
        String state,
        int totalCostCents,
        List<PurchasePlanLineResponse> lines,
        Instant proposedAt,
        Instant approvedAt
) {
}
