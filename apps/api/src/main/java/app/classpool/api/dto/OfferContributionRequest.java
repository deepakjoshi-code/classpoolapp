package app.classpool.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record OfferContributionRequest(
        @NotNull UUID studentId,
        @NotNull @Min(1) Integer quantity,
        String mode
) {
}
