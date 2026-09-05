package app.classpool.api.dto;

import java.util.UUID;

public record SavingsSummaryResponse(
        UUID poolId,
        String poolName,
        int itemsReused,
        int itemsPurchased,
        int estimatedSavingsCents,
        String shareableMessage
) {
}
