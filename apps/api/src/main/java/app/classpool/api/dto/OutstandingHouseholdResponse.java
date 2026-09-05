package app.classpool.api.dto;

import java.util.UUID;

/** Matches {@code contracts/openapi.yaml}'s {@code OutstandingHousehold} schema exactly. */
public record OutstandingHouseholdResponse(
        UUID householdId,
        String householdDisplayName,
        int amountCents
) {
}
