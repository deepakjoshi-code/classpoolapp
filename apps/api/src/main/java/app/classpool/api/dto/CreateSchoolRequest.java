package app.classpool.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSchoolRequest(@NotBlank String name) {
}
