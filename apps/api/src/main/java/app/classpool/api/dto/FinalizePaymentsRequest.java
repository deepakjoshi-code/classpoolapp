package app.classpool.api.dto;

/** Request body is optional in the contract (no {@code required: true|[...]}) — {@code
 *  acknowledgeBelowThreshold} defaults to {@code false} (contract's own default) when the body or
 *  the field itself is omitted; the controller passes {@code null} through untouched and {@code
 *  PaymentService} treats null the same as false. */
public record FinalizePaymentsRequest(
        Boolean acknowledgeBelowThreshold
) {
}
