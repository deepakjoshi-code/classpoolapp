package app.classpool.api.service;

import app.classpool.api.domain.Pool;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.dto.PoolResponse;
import app.classpool.api.dto.RequirementResponse;
import app.classpool.api.repository.RequirementRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the API-facing PoolResponse/PoolDetailResponse from the Pool entity, batching the
 * requirementCount lookup across a list of pools to avoid N+1 queries — mirrors
 * {@link ClassroomAssembler}'s batching pattern for school/school-year lookups.
 */
@Component
public class PoolAssembler {

    private final RequirementRepository requirementRepository;

    public PoolAssembler(RequirementRepository requirementRepository) {
        this.requirementRepository = requirementRepository;
    }

    public PoolResponse toResponse(Pool pool) {
        return toResponses(List.of(pool)).get(pool.getId());
    }

    public Map<UUID, PoolResponse> toResponses(List<Pool> pools) {
        if (pools.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> requirementCounts = requirementCounts(pools);
        return pools.stream().collect(Collectors.toMap(Pool::getId,
                pool -> build(pool, requirementCounts.getOrDefault(pool.getId(), 0L))));
    }

    /** GET /pools/{poolId} — the requirements list already loaded by the caller. */
    public PoolDetailResponse toDetail(Pool pool, List<RequirementResponse> requirements) {
        PoolResponse summary = build(pool, (long) requirements.size());
        return new PoolDetailResponse(summary.id(), summary.classroomId(), summary.name(), summary.poolType(),
                summary.state(), summary.requirementCount(), summary.createdAt(), requirements);
    }

    private Map<UUID, Long> requirementCounts(List<Pool> pools) {
        List<UUID> poolIds = pools.stream().map(Pool::getId).toList();
        return requirementRepository.countByPoolIdIn(poolIds).stream()
                .collect(Collectors.toMap(RequirementRepository.PoolRequirementCount::getPoolId,
                        RequirementRepository.PoolRequirementCount::getTotal));
    }

    private PoolResponse build(Pool pool, long requirementCount) {
        return new PoolResponse(
                pool.getId(),
                pool.getClassroomId(),
                pool.getName(),
                pool.getPoolType(),
                pool.getState().name(),
                (int) requirementCount,
                pool.getCreatedAt()
        );
    }
}
