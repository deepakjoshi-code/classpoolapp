package app.classpool.api.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(UUID userId, Instant expiresAt) {
}
