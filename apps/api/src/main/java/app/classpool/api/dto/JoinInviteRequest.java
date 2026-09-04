package app.classpool.api.dto;

import jakarta.validation.constraints.NotBlank;

public record JoinInviteRequest(@NotBlank String studentFirstName) {
}
