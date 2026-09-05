package app.classpool.api.dto;

import java.util.UUID;

public record InventoryLineResponse(
        UUID requirementId,
        String requirementName,
        int quantityPerStudent,
        UUID studentId,
        String studentFirstName,
        int ownedQuantity,
        int stillNeeded
) {
}
