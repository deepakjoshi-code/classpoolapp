package app.classpool.api.dto;

import java.time.Instant;
import java.util.UUID;

public record RequirementSourceResponse(
        UUID id,
        UUID poolId,
        String sourceType,
        String rawText,
        int extractedRequirementCount,
        Instant createdAt
) {
}
