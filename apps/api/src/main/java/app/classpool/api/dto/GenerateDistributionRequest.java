package app.classpool.api.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code mode} is validated against {@code DistributionMode}'s enum values in the service (a
 *  malformed/unknown value 400s there), same pattern as {@code PaymentService
 *  .parsePayableMethod} — bean validation only enforces non-blank here since Jakarta Validation
 *  has no built-in "one of these strings" constraint without a custom annotation. */
public record GenerateDistributionRequest(
        @NotBlank String mode
) {
}
