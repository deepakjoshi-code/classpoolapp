package app.classpool.api.dto;

import java.util.UUID;

/**
 * Matches {@code contracts/openapi.yaml}'s {@code OrderLine} schema exactly. {@code
 * substitutionDeltaCents}/{@code substitutionResolution} are computed live by {@code OrderService}
 * from {@code actualCostCents - plannedCostCents} rather than read back from a stored column — see
 * {@code app.classpool.api.domain.SubstitutionResolution}'s Javadoc for the flagged schema gap this
 * works around.
 */
public record OrderLineResponse(
        UUID id,
        UUID purchasePlanLineId,
        UUID requirementId,
        String requirementName,
        int plannedCostCents,
        int actualCostCents,
        String actualDescription,
        Integer substitutionDeltaCents,
        String substitutionResolution
) {
}
