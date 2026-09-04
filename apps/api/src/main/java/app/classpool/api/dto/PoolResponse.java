package app.classpool.api.dto;

import java.time.Instant;
import java.util.UUID;

public record PoolResponse(
        UUID id,
        UUID classroomId,
        String name,
        String poolType,
        String state,
        int requirementCount,
        Instant createdAt
) {
}
