package app.classpool.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Matches {@code contracts/openapi.yaml}'s {@code Order} schema exactly. */
public record OrderResponse(
        UUID id,
        UUID poolId,
        UUID orderedBy,
        Instant orderedAt,
        String receiptS3Key,
        List<OrderLineResponse> lines
) {
}
