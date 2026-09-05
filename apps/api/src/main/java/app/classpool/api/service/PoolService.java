package app.classpool.api.service;

import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementState;
import app.classpool.api.dto.CreatePoolRequest;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.dto.PoolResponse;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.PoolRepository;
import app.classpool.api.repository.RequirementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PoolService {

    private final PoolRepository poolRepository;
    private final RequirementRepository requirementRepository;
    private final MembershipRepository membershipRepository;
    private final PoolAssembler poolAssembler;
    private final RequirementAssembler requirementAssembler;

    public PoolService(PoolRepository poolRepository, RequirementRepository requirementRepository,
                        MembershipRepository membershipRepository, PoolAssembler poolAssembler,
                        RequirementAssembler requirementAssembler) {
        this.poolRepository = poolRepository;
        this.requirementRepository = requirementRepository;
        this.membershipRepository = membershipRepository;
        this.poolAssembler = poolAssembler;
        this.requirementAssembler = requirementAssembler;
    }

    /** Organizer/co-organizer only (contract) — starts the pool in DRAFT (PRD §13.3). */
    @Transactional
    public PoolResponse create(UUID callerUserId, UUID classroomId, CreatePoolRequest request) {
        requireOrganizer(callerUserId, classroomId);
        String poolType = (request.poolType() == null || request.poolType().isBlank())
                ? "SUPPLIES" : request.poolType();
        Pool pool = poolRepository.save(new Pool(classroomId, request.name(), poolType));
        return poolAssembler.toResponse(pool);
    }

    /** Any member may view (contract) — same tenant-isolation bar as ClassroomService.getForCaller. */
    @Transactional(readOnly = true)
    public List<PoolResponse> listForClassroom(UUID callerUserId, UUID classroomId) {
        requireMembership(callerUserId, classroomId);
        List<Pool> pools = poolRepository.findByClassroomIdOrderByCreatedAtDesc(classroomId);
        Map<UUID, PoolResponse> responses = poolAssembler.toResponses(pools);
        return pools.stream().map(pool -> responses.get(pool.getId())).toList();
    }

    /**
     * Returns the pool and its requirements only if the caller has a Membership on the pool's
     * classroom — same 403-not-404 tenant-isolation pattern as
     * {@link ClassroomService#getForCaller}: never let a 404 vs 403 distinguish "doesn't exist"
     * from "not yours".
     */
    @Transactional(readOnly = true)
    public PoolDetailResponse getForCaller(UUID callerUserId, UUID poolId) {
        Pool pool = getEntityOrThrow(poolId);
        requireMembership(callerUserId, pool.getClassroomId());
        return toDetail(pool);
    }

    /**
     * The organizer-verification step (PRD §3.3). Requires at least one requirement, moves every
     * requirement EXTRACTED/NEEDS_REVIEW -&gt; CONFIRMED, then the pool DRAFT -&gt;
     * OPEN_FOR_INVENTORY (PRD §13.3's stated mapping). A one-time transition — confirming a pool
     * that has already left DRAFT is a 409, same as confirming an empty pool.
     */
    @Transactional
    public PoolDetailResponse confirm(UUID callerUserId, UUID poolId) {
        Pool pool = getEntityOrThrow(poolId);
        requireOrganizer(callerUserId, pool.getClassroomId());
        if (pool.getState() != PoolState.DRAFT) {
            throw new ConflictException("Pool is not in DRAFT — it may already be confirmed");
        }
        List<Requirement> requirements = requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId);
        if (requirements.isEmpty()) {
            throw new ConflictException("Cannot confirm a pool with zero requirements");
        }
        requirements.stream()
                .filter(r -> r.getState() == RequirementState.EXTRACTED || r.getState() == RequirementState.NEEDS_REVIEW)
                .forEach(r -> r.setState(RequirementState.CONFIRMED));
        requirementRepository.saveAll(requirements);

        // Frozen here, once — see Pool.confirmedStudentCount's Javadoc for why this must not be
        // recomputed live on every later read.
        long confirmedStudentCount = membershipRepository.countDistinctStudentsByClassroom_Id(pool.getClassroomId());
        pool.setConfirmedStudentCount((int) confirmedStudentCount);
        pool.setState(PoolState.OPEN_FOR_INVENTORY);
        poolRepository.save(pool);

        return toDetail(pool, requirements);
    }

    /** Package-visible for RequirementService, mirroring ClassroomService.getEntityOrThrow. */
    @Transactional(readOnly = true)
    public Pool getEntityOrThrow(UUID poolId) {
        return poolRepository.findById(poolId)
                .orElseThrow(() -> new NotFoundException("Pool not found: " + poolId));
    }

    /**
     * Phase 6/7's reconcile action ({@code AllocationService.reconcile}) moves the pool straight
     * {@code OPEN_FOR_INVENTORY -> RECONCILING}, with no {@code OPEN_FOR_CONTRIBUTIONS} hop — see
     * apps/api/README.md's "Allocation & residual-demand engine" notes for why that state is
     * otherwise dead in this codebase. Package-visible, same instinct as
     * {@link #requireOrganizer}/{@link #requireMembership}: the Pool state machine's transitions
     * stay owned by this class even when another service's action drives them.
     */
    @Transactional
    void transitionToReconciling(Pool pool) {
        pool.setState(PoolState.RECONCILING);
        poolRepository.save(pool);
    }

    /**
     * Phase 8's {@code PurchasePlanService.generate} moves the pool {@code RECONCILING ->
     * PURCHASE_PROPOSED} once a purchase plan has been generated — package-visible, mirroring
     * {@link #transitionToReconciling} exactly (the Pool state machine's transitions stay owned by
     * this class even when another service's action drives them).
     */
    @Transactional
    void transitionToPurchaseProposed(Pool pool) {
        pool.setState(PoolState.PURCHASE_PROPOSED);
        poolRepository.save(pool);
    }

    void requireOrganizer(UUID callerUserId, UUID classroomId) {
        if (!membershipRepository.hasOrganizerRole(classroomId, callerUserId)) {
            throw new ForbiddenException("Caller is not an organizer on this classroom");
        }
    }

    void requireMembership(UUID callerUserId, UUID classroomId) {
        if (!membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerUserId)) {
            throw new ForbiddenException("You do not have access to this classroom's pool");
        }
    }

    private PoolDetailResponse toDetail(Pool pool) {
        List<Requirement> requirements = requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId());
        return toDetail(pool, requirements);
    }

    private PoolDetailResponse toDetail(Pool pool, List<Requirement> requirements) {
        return poolAssembler.toDetail(pool, requirementAssembler.toResponses(requirements, pool));
    }
}
