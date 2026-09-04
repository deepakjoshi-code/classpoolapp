package app.classpool.api.service;

import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementStrictness;
import app.classpool.api.dto.CreateRequirementRequest;
import app.classpool.api.dto.RequirementResponse;
import app.classpool.api.exception.BadRequestException;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.RequirementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Manual requirement CRUD within a pool (PRD §3 Phase 3 — "complete a supply list without AI").
 * Organizer/co-organizer only, and only while the pool is still DRAFT — requirements are locked
 * once the pool moves past confirmation (PRD §13.2: "after purchasing begins, no silent changes",
 * enforced here from the earliest point the contract requires it).
 */
@Service
public class RequirementService {

    private final RequirementRepository requirementRepository;
    private final PoolService poolService;
    private final RequirementAssembler requirementAssembler;

    public RequirementService(RequirementRepository requirementRepository, PoolService poolService,
                               RequirementAssembler requirementAssembler) {
        this.requirementRepository = requirementRepository;
        this.poolService = poolService;
        this.requirementAssembler = requirementAssembler;
    }

    @Transactional
    public RequirementResponse add(UUID callerUserId, UUID poolId, CreateRequirementRequest request) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        requireDraft(pool);

        Requirement requirement = requirementRepository.save(new Requirement(
                poolId, request.name(), request.quantityPerStudent(), request.brand(),
                parseStrictness(request.strictness())));
        return requirementAssembler.toResponse(requirement, pool.getClassroomId());
    }

    /** The "Correct" action (PRD §3.3). */
    @Transactional
    public RequirementResponse update(UUID callerUserId, UUID poolId, UUID requirementId,
                                       CreateRequirementRequest request) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        requireDraft(pool);

        Requirement requirement = getEntityOrThrow(poolId, requirementId);
        requirement.applyEdit(request.name(), request.quantityPerStudent(), request.brand(),
                parseStrictness(request.strictness()));
        requirementRepository.save(requirement);
        return requirementAssembler.toResponse(requirement, pool.getClassroomId());
    }

    /** The "Remove" action (PRD §3.3). */
    @Transactional
    public void remove(UUID callerUserId, UUID poolId, UUID requirementId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        requireDraft(pool);

        Requirement requirement = getEntityOrThrow(poolId, requirementId);
        requirementRepository.delete(requirement);
    }

    private Requirement getEntityOrThrow(UUID poolId, UUID requirementId) {
        return requirementRepository.findByIdAndPoolId(requirementId, poolId)
                .orElseThrow(() -> new NotFoundException("Requirement not found: " + requirementId));
    }

    private void requireDraft(Pool pool) {
        if (pool.getState() != PoolState.DRAFT) {
            throw new ConflictException("Pool is no longer in DRAFT — requirements are locked once confirmed");
        }
    }

    private RequirementStrictness parseStrictness(String raw) {
        if (raw == null || raw.isBlank()) {
            return RequirementStrictness.EQUIVALENT_ALLOWED;
        }
        try {
            return RequirementStrictness.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown strictness: " + raw);
        }
    }
}
