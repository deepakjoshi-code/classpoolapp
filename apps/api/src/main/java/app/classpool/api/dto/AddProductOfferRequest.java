package app.classpool.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddProductOfferRequest(
        @NotBlank String retailer,
        @NotNull @Min(1) Integer packQuantity,
        @NotNull @Min(1) Integer priceCents,
        @Min(0) Integer shippingCents,
        String affiliateUrl
) {
}
