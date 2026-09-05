package app.classpool.api.dto;

import java.time.Instant;
import java.util.UUID;

/** Matches {@code contracts/openapi.yaml}'s {@code ProductOffer} schema exactly. */
public record ProductOfferResponse(
        UUID id,
        UUID requirementId,
        String requirementName,
        String retailer,
        int packQuantity,
        int priceCents,
        int shippingCents,
        String affiliateUrl,
        Instant createdAt
) {
}
