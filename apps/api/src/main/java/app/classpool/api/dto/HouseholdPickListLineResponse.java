package app.classpool.api.dto;

/** Matches {@code contracts/openapi.yaml}'s {@code HouseholdPickListLine} schema exactly. */
public record HouseholdPickListLineResponse(
        String requirementName,
        int quantity
) {
}
