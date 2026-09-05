package app.classpool.api.dto;

import java.time.Instant;
import java.util.UUID;

/** Matches {@code contracts/openapi.yaml}'s {@code DistributionItem} schema exactly. */
public record DistributionItemResponse(
        UUID id,
        UUID studentId,
        String studentFirstName,
        UUID requirementId,
        String requirementName,
        int quantity,
        Instant deliveredAt
) {
}
