package app.classpool.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Matches {@code contracts/openapi.yaml}'s {@code Contribution} schema, with one deliberate gap:
 * {@code studentId}/{@code studentFirstName} are always null. The V1 migration's {@code
 * contribution} table has no {@code student_id} column (only {@code offering_parent_id}) — see
 * {@code Contribution}'s and {@code ContributionService.offer}'s Javadoc — so there is nothing to
 * populate them from once a pledge is more than momentarily in flight. Flagged in
 * apps/api/README.md rather than worked around by adding a column here, per this task's
 * schema-changes-are-reviewed-separately boundary.
 */
public record ContributionResponse(
        UUID id,
        UUID requirementId,
        String requirementName,
        UUID studentId,
        String studentFirstName,
        String offeringParentDisplayName,
        int quantity,
        String mode,
        String state,
        Instant createdAt
) {
}
