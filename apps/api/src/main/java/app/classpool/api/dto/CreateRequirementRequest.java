package app.classpool.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateRequirementRequest(
        @NotBlank String name,
        @NotNull @Min(1) Integer quantityPerStudent,
        String brand,
        String strictness
) {
}
