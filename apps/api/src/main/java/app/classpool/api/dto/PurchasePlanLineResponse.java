package app.classpool.api.dto;

import java.util.UUID;

/**
 * Matches {@code contracts/openapi.yaml}'s {@code PurchasePlanLine} schema exactly. {@code
 * retailer}/{@code packQuantity} describe the {@link ProductOfferResponse} that {@code
 * productOfferId} points at — looked up once by {@code PurchasePlanService} at response-assembly
 * time, same "batch-fetch and denormalize into the response" instinct as {@code
 * AllocationLineResponse.requirementName}.
 */
public record PurchasePlanLineResponse(
        UUID requirementId,
        String requirementName,
        UUID productOfferId,
        String retailer,
        int packQuantity,
        int packCount,
        int totalCostCents,
        int wasteQuantity
) {
}
