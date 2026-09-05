package app.classpool.api.dto;

import java.time.Instant;
import java.util.UUID;

/** Matches {@code contracts/openapi.yaml}'s {@code ClassReserveEntry} schema exactly. {@code
 *  custodianLocation} is always null in V1 — see {@code
 *  app.classpool.api.domain.ClassReserveEntry}'s Javadoc. */
public record ClassReserveEntryResponse(
        UUID id,
        UUID classroomId,
        String itemName,
        int quantity,
        String custodianLocation,
        Instant createdAt
) {
}
