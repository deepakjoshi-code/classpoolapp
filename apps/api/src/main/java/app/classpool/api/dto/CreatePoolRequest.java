package app.classpool.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreatePoolRequest(
        @NotBlank String name,
        String poolType
) {
}
