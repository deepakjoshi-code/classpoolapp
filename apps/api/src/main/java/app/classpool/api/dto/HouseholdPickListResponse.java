package app.classpool.api.dto;

import java.util.List;
import java.util.UUID;

/** Matches {@code contracts/openapi.yaml}'s {@code HouseholdPickList} schema exactly —
 *  {@code householdDisplayName} follows the same organizer-sees-identity precedent as {@code
 *  ContributionResponse.offeringParentDisplayName}/{@code PaymentResponse.householdDisplayName},
 *  populated here since {@code getDistribution} is organizer-only. */
public record HouseholdPickListResponse(
        UUID householdId,
        String householdDisplayName,
        List<HouseholdPickListLineResponse> lines
) {
}
