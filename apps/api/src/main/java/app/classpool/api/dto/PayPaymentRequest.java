package app.classpool.api.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code method} is validated/parsed in {@code PaymentService} (only CARD/APPLE_PAY/GOOGLE_PAY
 *  are accepted here — CASH goes through {@code markPaymentCashPending} instead), same
 *  string-in-DTO/enum-parsed-in-service pattern as {@code OfferContributionRequest.mode}. */
public record PayPaymentRequest(
        @NotBlank String method
) {
}
