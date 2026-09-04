package app.classpool.api.service;

import app.classpool.api.domain.Pool;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementState;
import app.classpool.api.dto.RequirementResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the API-facing RequirementResponse, including {@code totalDemand} (PRD §3.4: per-student
 * quantity × confirmed participating students). {@code totalDemand} is derived from
 * {@link Pool#getConfirmedStudentCount()} — frozen once at {@code PoolService.confirm()}, not
 * recomputed live — so it can never silently drift after confirmation (see migration V2's
 * comment and {@code Pool.confirmedStudentCount}'s Javadoc for the bug this avoids).
 */
@Component
public class RequirementAssembler {

    public RequirementResponse toResponse(Requirement requirement, Pool pool) {
        return build(requirement, pool.getConfirmedStudentCount());
    }

    public List<RequirementResponse> toResponses(List<Requirement> requirements, Pool pool) {
        Integer confirmedStudentCount = pool.getConfirmedStudentCount();
        return requirements.stream().map(r -> build(r, confirmedStudentCount)).toList();
    }

    private RequirementResponse build(Requirement requirement, Integer confirmedStudentCount) {
        Integer totalDemand = (confirmedStudentCount != null && isAtLeastConfirmed(requirement))
                ? requirement.getQuantityPerStudent() * confirmedStudentCount
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
