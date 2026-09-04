package app.classpool.api.dto;

import java.time.Instant;
import java.util.UUID;

public record RequirementResponse(
        UUID id,
        UUID poolId,
        String name,
        int quantityPerStudent,
        String brand,
        String strictness,
        String state,
        String sourceEvidence,
        Double confidence,
        Integer totalDemand,
        Instant createdAt
) {
}
