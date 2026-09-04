package app.classpool.api.service;

import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementState;
import app.classpool.api.dto.RequirementResponse;
import app.classpool.api.repository.MembershipRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Builds the API-facing RequirementResponse, including {@code totalDemand} (PRD §3.4: per-student
 * quantity × confirmed participating students).
 *
 * <p><b>Known schema gap (flagged rather than fixed — see apps/api/README.md "Pools and
 * requirements design notes"):</b> the V1 migration's {@code requirement} table has no column to
 * persist a totalDemand/confirmedStudentCount snapshot, so — unlike the rest of this codebase's
 * "compute once, store the result" style — this value is recomputed from live Membership rows on
 * every read rather than frozen at confirm time. In practice this only matters once a classroom
 * gains members after its pool leaves DRAFT, which is an edge case later phases (late-join
 * billing, §13.3 update) already have to reckon with separately.
 */
@Component
public class RequirementAssembler {

    private final MembershipRepository membershipRepository;

    public RequirementAssembler(MembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    public RequirementResponse toResponse(Requirement requirement, UUID classroomId) {
        return toResponses(List.of(requirement), classroomId).get(0);
    }

    public List<RequirementResponse> toResponses(List<Requirement> requirements, UUID classroomId) {
        if (requirements.isEmpty()) {
            return List.of();
        }
        // Only fetch the classroom's confirmed-student count if at least one requirement has
        // actually been confirmed — avoids the query entirely for a still-DRAFT pool.
        boolean anyConfirmed = requirements.stream().anyMatch(RequirementAssembler::isAtLeastConfirmed);
        Long confirmedStudentCount = anyConfirmed
                ? membershipRepository.countDistinctStudentsByClassroom_Id(classroomId)
                : null;
        return requirements.stream().map(r -> build(r, confirmedStudentCount)).toList();
    }

    private RequirementResponse build(Requirement requirement, Long confirmedStudentCount) {
        Integer totalDemand = (confirmedStudentCount != null && isAtLeastConfirmed(requirement))
                ? requirement.getQuantityPerStudent() * confirmedStudentCount.intValue()
                : null;
        return new RequirementResponse(
                requirement.getId(),
                requirement.getPoolId(),
                requirement.getName(),
                requirement.getQuantityPerStudent(),
                requirement.getBrand(),
                requirement.getStrictness().name(),
                requirement.getState().name(),
                requirement.getSourceEvidence(),
                requirement.getConfidence() == null ? null : requirement.getConfidence().doubleValue(),
                totalDemand,
                requirement.getCreatedAt()
        );
    }

    /** True once a Requirement has passed organizer verification (PRD §3.3) — CONFIRMED or later. */
    private static boolean isAtLeastConfirmed(Requirement requirement) {
        RequirementState state = requirement.getState();
        return state != RequirementState.EXTRACTED && state != RequirementState.NEEDS_REVIEW;
    }
}
