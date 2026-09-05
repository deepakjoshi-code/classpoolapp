package app.classpool.api.dto;

import java.util.List;

/** Matches {@code contracts/openapi.yaml}'s {@code PaymentsSummary} schema exactly. {@code
 *  thresholdPercent} is a platform constant (90) — see {@code PaymentService}, not
 *  organizer-editable, per the contract's own description. */
public record PaymentsSummaryResponse(
        int totalOwedCents,
        int totalCollectedCents,
        double percentCollected,
        double thresholdPercent,
        boolean meetsThreshold,
        List<OutstandingHouseholdResponse> outstandingHouseholds
) {
}
