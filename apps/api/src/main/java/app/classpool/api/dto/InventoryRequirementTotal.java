package app.classpool.api.dto;

import java.util.UUID;

public record InventoryRequirementTotal(
        UUID requirementId,
        String requirementName,
        int totalOwned,
        Integer totalRequired
) {
}
