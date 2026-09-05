package app.classpool.api.dto;

import java.util.UUID;

/** Matches {@code contracts/openapi.yaml}'s {@code ResidualDemandLine} schema exactly. */
public record ResidualDemandLineResponse(
        UUID requirementId,
        String requirementName,
        int totalRequired,
        int totalOwned,
        int totalPoolFulfilled,
        int residualDemand
) {
}
