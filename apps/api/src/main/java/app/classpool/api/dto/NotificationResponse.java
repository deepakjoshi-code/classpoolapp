package app.classpool.api.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String type,
        UUID poolId,
        String message,
        Instant readAt,
        Instant createdAt
) {
}
