package app.classpool.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Matches {@code contracts/openapi.yaml}'s {@code DistributionSummary} schema exactly. */
public record DistributionSummaryResponse(
        UUID id,
        UUID poolId,
        String mode,
        Instant createdAt,
        List<DistributionItemResponse> items,
        List<HouseholdPickListResponse> pickLists
) {
}
