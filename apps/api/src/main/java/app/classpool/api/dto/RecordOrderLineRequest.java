package app.classpool.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** One optional per-line override within {@link RecordOrderRequest} — {@code actualCostCents}/
 *  {@code actualDescription} left {@code null} means "no substitution, use the plan's own figure",
 *  matching the contract's own wording. */
public record RecordOrderLineRequest(
        @NotNull UUID purchasePlanLineId,
        Integer actualCostCents,
        String actualDescription
) {
}
