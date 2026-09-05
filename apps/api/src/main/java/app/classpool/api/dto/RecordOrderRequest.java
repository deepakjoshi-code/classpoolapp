package app.classpool.api.dto;

import jakarta.validation.Valid;

import java.util.List;

/** {@code recordOrder}'s request body (contract) — every field is optional, including the whole
 *  body itself (an organizer recording an order that exactly matched the approved plan sends
 *  nothing at all, or an empty {@code lines} list). Any purchase-plan line not named in {@code
 *  lines} defaults to its own planned cost/no description, per the contract's own wording. */
public record RecordOrderRequest(
        String receiptS3Key,
        @Valid List<RecordOrderLineRequest> lines
) {
}
