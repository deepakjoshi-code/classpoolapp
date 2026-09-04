package app.classpool.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PoolDetailResponse(
        UUID id,
        UUID classroomId,
        String name,
        String poolType,
        String state,
        int requirementCount,
        Instant createdAt,
        List<RequirementResponse> requirements
) {
}
