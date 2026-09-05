package app.classpool.api.dto;

import java.util.UUID;

/** Matches {@code contracts/openapi.yaml}'s {@code AllocationLine} schema exactly. */
public record AllocationLineResponse(
        UUID requirementId,
        String requirementName,
        UUID studentId,
        String studentFirstName,
        int quantityNeeded,
        int ownedQuantity,
        int poolFulfilledQuantity,
        int purchaseRequiredQuantity,
        String status
) {
}
