package app.classpool.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SetInventoryRequest(
        @NotNull UUID studentId,
        @NotNull @Min(0) Integer ownedQuantity
) {
}
