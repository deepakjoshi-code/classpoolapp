package app.classpool.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ImportRequirementsRequest(
        @NotBlank String sourceType,
        @NotBlank String rawText
) {
}
