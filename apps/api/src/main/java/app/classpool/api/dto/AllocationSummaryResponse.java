package app.classpool.api.dto;

import java.util.List;

/** Matches {@code contracts/openapi.yaml}'s {@code AllocationSummary} schema exactly — the shape
 *  returned by both {@code POST /reconcile} and {@code GET /allocation}. */
public record AllocationSummaryResponse(
        List<AllocationLineResponse> allocations,
        List<ResidualDemandLineResponse> residualDemand
) {
}
