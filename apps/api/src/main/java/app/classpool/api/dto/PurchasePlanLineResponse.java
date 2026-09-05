package app.classpool.api.dto;

import java.util.UUID;

/**
 * Matches {@code contracts/openapi.yaml}'s {@code PurchasePlanLine} schema exactly. {@code
 * retailer}/{@code packQuantity} describe the {@link ProductOfferResponse} that {@code
 * productOfferId} points at — looked up once by {@code PurchasePlanService} at response-assembly
 * time, same "batch-fetch and denormalize into the response" instinct as {@code
 * AllocationLineResponse.requirementName}.
 *
 * <p>{@code id} was added to the contract alongside Phase 10 ("the value recordOrder's request
 * body references as purchasePlanLineId") — Phase 8's original cut of this record predates that
 * and omitted it, since nothing before Phase 10 needed to address one specific line. Populated in
 * {@code PurchasePlanService.toLineResponse} as a Phase 10 dependency fix, not a schema/migration
 * change — {@code purchase_plan_line.id} already exists as the table's primary key, only the
 * response DTO was missing it.
 */
public record PurchasePlanLineResponse(
        UUID id,
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
