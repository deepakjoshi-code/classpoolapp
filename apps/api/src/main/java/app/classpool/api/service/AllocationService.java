package app.classpool.api.service;

import app.classpool.api.domain.AllocationLine;
import app.classpool.api.domain.AllocationStatus;
import app.classpool.api.domain.ContributionState;
import app.classpool.api.domain.Membership;
import app.classpool.api.domain.ParentInventory;
import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.ProductOffer;
import app.classpool.api.domain.PurchasePlan;
import app.classpool.api.domain.PurchasePlanLine;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementState;
import app.classpool.api.domain.ResidualDemandLine;
import app.classpool.api.domain.Student;
import app.classpool.api.dto.AllocationLineResponse;
import app.classpool.api.dto.AllocationSummaryResponse;
import app.classpool.api.dto.ResidualDemandLineResponse;
import app.classpool.api.dto.SavingsSummaryResponse;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.repository.AllocationLineRepository;
import app.classpool.api.repository.ContributionRepository;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.ParentInventoryRepository;
import app.classpool.api.repository.ProductOfferRepository;
import app.classpool.api.repository.PurchasePlanLineRepository;
import app.classpool.api.repository.PurchasePlanRepository;
import app.classpool.api.repository.RequirementRepository;
import app.classpool.api.repository.ResidualDemandLineRepository;
import app.classpool.api.repository.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The allocation & residual-demand engine (PRD §6 — "Deterministic business logic, not LLM
 * reasoning"). {@link #reconcile} freezes a snapshot, exactly once per pool: for every (requirement,
 * student) pair, how much the household's own recorded inventory (Phase 4) already covers, how
 * much the pool's RECEIVED surplus contributions (Phase 5) cover on top of that (allocated in
 * classroom-join order — first-joined-first-served, a deterministic tie-break, not a fairness
 * ranking), and how much still needs to be purchased. Aggregated per requirement, that last column
 * is the class's ResidualDemand.
 *
 * <p><b>Design note — no {@code OPEN_FOR_CONTRIBUTIONS} hop.</b> Nothing in this codebase
 * transitions a Pool from {@code OPEN_FOR_INVENTORY} to {@code OPEN_FOR_CONTRIBUTIONS} (Phase 4
 * inventory and Phase 5 contributions both already operate freely while a pool is
 * {@code OPEN_FOR_INVENTORY}). So {@link #reconcile} moves the pool directly
 * {@code OPEN_FOR_INVENTORY -> RECONCILING} — see apps/api/README.md's Phase 6/7 notes for the full
 * writeup of why {@code OPEN_FOR_CONTRIBUTIONS} is a dead enum value in this codebase.
 *
 * <p><b>Design note — reconcile re-reads classroom membership live, not the frozen
 * {@code confirmedStudentCount}.</b> Unlike {@code Requirement.totalDemand} (frozen once at
 * confirm), the allocation snapshot is built from whichever Memberships exist on the classroom at
 * the moment reconcile runs — a late joiner during {@code OPEN_FOR_INVENTORY} (allowed, since
 * nothing gates it — see the note above) gets their own {@link AllocationLine} rows. Each
 * requirement's {@code totalRequired} is derived as {@code quantityPerStudent × (number of
 * students actually processed)}, not {@code Pool.confirmedStudentCount} — this keeps the
 * {@code totalRequired = totalOwned + totalPoolFulfilled + residualDemand} accounting identity
 * exactly true by construction, in the common case (no late joins between confirm and reconcile)
 * this number is identical to {@code Requirement.totalDemand}, per the contract's own description
 * of the field.
 */
@Service
public class AllocationService {

    private final AllocationLineRepository allocationLineRepository;
    private final ResidualDemandLineRepository residualDemandLineRepository;
    private final RequirementRepository requirementRepository;
    private final MembershipRepository membershipRepository;
    private final ParentInventoryRepository parentInventoryRepository;
    private final ContributionRepository contributionRepository;
    private final StudentRepository studentRepository;
    private final PurchasePlanRepository purchasePlanRepository;
    private final PurchasePlanLineRepository purchasePlanLineRepository;
    private final ProductOfferRepository productOfferRepository;
    private final PoolService poolService;

    public AllocationService(AllocationLineRepository allocationLineRepository,
                              ResidualDemandLineRepository residualDemandLineRepository,
                              RequirementRepository requirementRepository, MembershipRepository membershipRepository,
                              ParentInventoryRepository parentInventoryRepository,
                              ContributionRepository contributionRepository, StudentRepository studentRepository,
                              PurchasePlanRepository purchasePlanRepository,
                              PurchasePlanLineRepository purchasePlanLineRepository,
                              ProductOfferRepository productOfferRepository, PoolService poolService) {
        this.allocationLineRepository = allocationLineRepository;
        this.residualDemandLineRepository = residualDemandLineRepository;
        this.requirementRepository = requirementRepository;
        this.membershipRepository = membershipRepository;
        this.parentInventoryRepository = parentInventoryRepository;
        this.contributionRepository = contributionRepository;
        this.studentRepository = studentRepository;
        this.purchasePlanRepository = purchasePlanRepository;
        this.purchasePlanLineRepository = purchasePlanLineRepository;
        this.productOfferRepository = productOfferRepository;
        this.poolService = poolService;
    }

    /**
     * Organizer/co-organizer only (contract). Requires the pool to currently be
     * {@code OPEN_FOR_INVENTORY} — 409 otherwise (still {@code DRAFT}, or already reconciled).
     * Re-running is not supported in V1: once a pool leaves {@code OPEN_FOR_INVENTORY} this always
     * 409s, there is no way back to re-snapshot.
     */
    @Transactional
    public AllocationSummaryResponse reconcile(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        if (pool.getState() != PoolState.OPEN_FOR_INVENTORY) {
            throw new ConflictException(
                    "Pool is not OPEN_FOR_INVENTORY — it may already be reconciled, or still DRAFT");
        }

        List<Requirement> requirements = requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId).stream()
                .filter(r -> r.getState() == RequirementState.CONFIRMED)
                .toList();
        List<Membership> orderedMemberships = dedupeByStudentPreservingJoinOrder(
                membershipRepository.findByClassroom_IdAndStudentIsNotNullOrderByCreatedAtAsc(pool.getClassroomId()));

        if (requirements.isEmpty() || orderedMemberships.isEmpty()) {
            poolService.transitionToReconciling(pool);
            return new AllocationSummaryResponse(List.of(), List.of());
        }

        List<UUID> requirementIds = requirements.stream().map(Requirement::getId).toList();
        List<UUID> studentIds = orderedMemberships.stream().map(m -> m.getStudent().getId()).toList();

        Map<UUID, Long> receivedByRequirement = contributionRepository
                .sumQuantityByRequirementIdInAndState(requirementIds, ContributionState.RECEIVED).stream()
                .collect(Collectors.toMap(ContributionRepository.RequirementQuantityTotal::getRequirementId,
                        ContributionRepository.RequirementQuantityTotal::getTotal));
        Map<LineKey, Integer> ownedByLine = parentInventoryRepository
                .findByRequirementIdInAndStudentIdIn(requirementIds, studentIds).stream()
                .collect(Collectors.toMap(pi -> new LineKey(pi.getRequirementId(), pi.getStudentId()),
                        ParentInventory::getOwnedQuantity));

        List<AllocationLine> allocationLines = new ArrayList<>();
        List<ResidualDemandLine> residualDemandLines = new ArrayList<>();

        for (Requirement requirement : requirements) {
            long poolAvailable = receivedByRequirement.getOrDefault(requirement.getId(), 0L);
            int totalOwned = 0;
            int totalPoolFulfilled = 0;
            int totalPurchaseRequired = 0;

            for (Membership membership : orderedMemberships) {
                UUID studentId = membership.getStudent().getId();
                int owned = ownedByLine.getOrDefault(new LineKey(requirement.getId(), studentId), 0);
                int remainingAfterOwned = Math.max(0, requirement.getQuantityPerStudent() - owned);
                int poolFulfilled = (int) Math.min(remainingAfterOwned, poolAvailable);
                poolAvailable -= poolFulfilled;
                int purchaseRequired = remainingAfterOwned - poolFulfilled;
                AllocationStatus status = statusFor(poolFulfilled, purchaseRequired);

                allocationLines.add(new AllocationLine(requirement.getId(), studentId,
                        requirement.getQuantityPerStudent(), owned, poolFulfilled, purchaseRequired, status));

                totalOwned += owned;
                totalPoolFulfilled += poolFulfilled;
                totalPurchaseRequired += purchaseRequired;
            }

            int totalRequired = requirement.getQuantityPerStudent() * orderedMemberships.size();
            residualDemandLines.add(new ResidualDemandLine(requirement.getId(), totalRequired, totalOwned,
                    totalPoolFulfilled, Math.max(0, totalPurchaseRequired)));
        }

        allocationLineRepository.saveAll(allocationLines);
        residualDemandLineRepository.saveAll(residualDemandLines);
        poolService.transitionToReconciling(pool);

        Map<UUID, Requirement> requirementsById = toMapById(requirements);
        Map<UUID, String> studentFirstNames = orderedMemberships.stream()
                .collect(Collectors.toMap(m -> m.getStudent().getId(), m -> m.getStudent().getFirstName(),
                        (a, b) -> a));
        return toSummary(allocationLines, residualDemandLines, requirementsById, studentFirstNames);
    }

    /**
     * Organizer/co-organizer only (contract) — reads back the frozen snapshot from
     * {@link #reconcile}. 409 if reconcile hasn't run yet (pool is still {@code DRAFT} or
     * {@code OPEN_FOR_INVENTORY}).
     */
    @Transactional(readOnly = true)
    public AllocationSummaryResponse getAllocationForOrganizer(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        requireReconciled(pool);

        List<Requirement> requirements = requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId);
        if (requirements.isEmpty()) {
            return new AllocationSummaryResponse(List.of(), List.of());
        }
        List<UUID> requirementIds = requirements.stream().map(Requirement::getId).toList();
        Map<UUID, Requirement> requirementsById = toMapById(requirements);

        List<AllocationLine> lines = allocationLineRepository
                .findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(requirementIds);
        List<ResidualDemandLine> residualLines = residualDemandLineRepository
                .findByRequirementIdInOrderByRequirementIdAsc(requirementIds);
        Map<UUID, String> studentFirstNames = studentFirstNamesFor(
                lines.stream().map(AllocationLine::getStudentId).distinct().toList());

        return toSummary(lines, residualLines, requirementsById, studentFirstNames);
    }

    /**
     * Any member of this pool's classroom may call this (contract) — but only ever sees their own
     * students' lines, never another household's, and no class-level ResidualDemand aggregate
     * (that's an organizer/class-level figure, not a household one). Empty array — not an error —
     * if reconcile hasn't run yet, same "nothing to show yet" pattern as
     * {@code InventoryService.getMyInventory} on a still-DRAFT pool.
     */
    @Transactional(readOnly = true)
    public List<AllocationLineResponse> getMyAllocation(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireMembership(callerUserId, pool.getClassroomId());

        if (pool.getState() == PoolState.DRAFT || pool.getState() == PoolState.OPEN_FOR_INVENTORY) {
            return List.of();
        }

        List<UUID> ownStudentIds = membershipRepository
                .findByClassroom_IdAndParentUserId(pool.getClassroomId(), callerUserId).stream()
                .filter(m -> m.getStudent() != null)
                .map(m -> m.getStudent().getId())
                .toList();
        if (ownStudentIds.isEmpty()) {
            return List.of();
        }

        List<Requirement> requirements = requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId);
        if (requirements.isEmpty()) {
            return List.of();
        }
        List<UUID> requirementIds = requirements.stream().map(Requirement::getId).toList();
        Map<UUID, Requirement> requirementsById = toMapById(requirements);

        List<AllocationLine> lines = allocationLineRepository
                .findByRequirementIdInAndStudentIdInOrderByRequirementIdAscStudentIdAsc(requirementIds, ownStudentIds);
        Map<UUID, String> studentFirstNames = studentFirstNamesFor(ownStudentIds);

        return lines.stream().map(line -> toLineResponse(line, requirementsById.get(line.getRequirementId()),
                studentFirstNames.get(line.getStudentId()))).toList();
    }

    /**
     * PRD §16.3's shareable "how much this pool saved" result. Any member of this pool's
     * classroom may call it (contract) — {@link PoolService#requireMembership}, not {@code
     * requireOrganizer}, since this is meant to be seen and shared broadly, not restricted to the
     * organizer. Requires the pool to have been reconciled — same 409 gate as {@link
     * #getAllocationForOrganizer} (mirrored rather than shared, since this method needs no other
     * part of that one's organizer-only response assembly).
     *
     * <p>{@code itemsReused} sums every {@link AllocationLine}'s {@code ownedQuantity +
     * poolFulfilledQuantity} (self-owned or community-donated — never bought new);
     * {@code itemsPurchased} sums {@code purchaseRequiredQuantity}. If a {@link PurchasePlan}
     * exists, {@code avgUnitCostCents = round(sum(PurchasePlanLine.totalCostCents) /
     * sum(ProductOffer.packQuantity * PurchasePlanLine.packCount))} — total spent divided by total
     * units actually purchased across every line, deliberately NOT divided by {@code
     * itemsPurchased}, since a pack purchase usually buys more than the exact residual need (see
     * apps/api/README.md's Phase 12 notes for the full write-up of why this is "what we actually
     * paid per unit," the only real price signal available, and a real approximation rather than
     * a market price). {@code estimatedSavingsCents = round(avgUnitCostCents * itemsReused)}. No
     * plan yet -&gt; both are {@code 0} (no price signal to estimate from).
     */
    @Transactional(readOnly = true)
    public SavingsSummaryResponse getSavingsSummary(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireMembership(callerUserId, pool.getClassroomId());
        requireReconciled(pool);

        List<UUID> requirementIds = requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId).stream()
                .map(Requirement::getId).toList();

        int itemsReused = 0;
        int itemsPurchased = 0;
        if (!requirementIds.isEmpty()) {
            List<AllocationLine> lines = allocationLineRepository
                    .findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(requirementIds);
            itemsReused = lines.stream().mapToInt(l -> l.getOwnedQuantity() + l.getPoolFulfilledQuantity()).sum();
            itemsPurchased = lines.stream().mapToInt(AllocationLine::getPurchaseRequiredQuantity).sum();
        }

        int estimatedSavingsCents = 0;
        PurchasePlan plan = purchasePlanRepository.findByPoolId(poolId).orElse(null);
        if (plan != null) {
            List<PurchasePlanLine> planLines = purchasePlanLineRepository
                    .findByPurchasePlanIdOrderByRequirementIdAscProductOfferIdAsc(plan.getId());
            if (!planLines.isEmpty()) {
                Map<UUID, Integer> packQuantityByOfferId = productOfferRepository
                        .findAllById(planLines.stream().map(PurchasePlanLine::getProductOfferId).distinct().toList())
                        .stream()
                        .collect(Collectors.toMap(ProductOffer::getId, ProductOffer::getPackQuantity));

                long totalSpentCents = planLines.stream().mapToLong(PurchasePlanLine::getTotalCostCents).sum();
                long totalUnitsPurchased = planLines.stream()
                        .mapToLong(l -> (long) packQuantityByOfferId.getOrDefault(l.getProductOfferId(), 0)
                                * l.getPackCount())
                        .sum();
                if (totalUnitsPurchased > 0) {
                    int avgUnitCostCents = (int) Math.round((double) totalSpentCents / totalUnitsPurchased);
                    estimatedSavingsCents = (int) Math.round(avgUnitCostCents * (double) itemsReused);
                }
            }
        }

        return new SavingsSummaryResponse(pool.getId(), pool.getName(), itemsReused, itemsPurchased,
                estimatedSavingsCents, shareableMessage(pool.getName(), itemsReused, estimatedSavingsCents));
    }

    private static String shareableMessage(String poolName, int itemsReused, int estimatedSavingsCents) {
        String itemWord = itemsReused == 1 ? "item" : "items";
        StringBuilder message = new StringBuilder()
                .append('"').append(poolName).append('"')
                .append(" reused ").append(itemsReused).append(' ').append(itemWord);
        if (estimatedSavingsCents > 0) {
            message.append(" and saved an estimated ").append(formatCents(estimatedSavingsCents));
        }
        message.append(" with ClassPool!");
        return message.toString();
    }

    private static String formatCents(int cents) {
        return String.format("$%.2f", cents / 100.0);
    }

    private void requireReconciled(Pool pool) {
        if (pool.getState() == PoolState.DRAFT || pool.getState() == PoolState.OPEN_FOR_INVENTORY) {
            throw new ConflictException("Pool hasn't been reconciled yet — call POST /reconcile first");
        }
    }

    /**
     * A student could in principle have two Membership rows on the same classroom (e.g. two
     * co-parents each independently joined with the same child) — the {@code allocation_line}
     * table's unique constraint is {@code (requirement_id, student_id)}, so exactly one line per
     * student is needed regardless. Keeping the first (earliest-{@code createdAt}) Membership row
     * per student mirrors {@code MembershipRepository.countDistinctStudentsByClassroom_Id}'s
     * "distinct student" instinct — the same one {@code Pool.confirmedStudentCount} is built on.
     * The input list is already ordered by {@code createdAt} ascending, so a {@link LinkedHashMap}
     * keyed by student id, kept on first-seen, both dedupes and preserves join order in one pass.
     */
    private static List<Membership> dedupeByStudentPreservingJoinOrder(List<Membership> memberships) {
        Map<UUID, Membership> byStudent = new LinkedHashMap<>();
        for (Membership membership : memberships) {
            byStudent.putIfAbsent(membership.getStudent().getId(), membership);
        }
        return new ArrayList<>(byStudent.values());
    }

    private static AllocationStatus statusFor(int poolFulfilled, int purchaseRequired) {
        if (purchaseRequired > 0) {
            return AllocationStatus.PURCHASE_REQUIRED;
        }
        return poolFulfilled > 0 ? AllocationStatus.POOL_FULFILLED : AllocationStatus.SELF_FULFILLED;
    }

    private Map<UUID, String> studentFirstNamesFor(List<UUID> studentIds) {
        return studentRepository.findAllById(studentIds).stream()
                .collect(Collectors.toMap(Student::getId, Student::getFirstName));
    }

    private static Map<UUID, Requirement> toMapById(List<Requirement> requirements) {
        return requirements.stream().collect(Collectors.toMap(Requirement::getId, r -> r));
    }

    private static AllocationSummaryResponse toSummary(List<AllocationLine> lines,
                                                         List<ResidualDemandLine> residualLines,
                                                         Map<UUID, Requirement> requirementsById,
                                                         Map<UUID, String> studentFirstNames) {
        List<AllocationLineResponse> allocationResponses = lines.stream()
                .map(line -> toLineResponse(line, requirementsById.get(line.getRequirementId()),
                        studentFirstNames.get(line.getStudentId())))
                .toList();
        List<ResidualDemandLineResponse> residualResponses = residualLines.stream()
                .map(rl -> toResidualResponse(rl, requirementsById.get(rl.getRequirementId())))
                .toList();
        return new AllocationSummaryResponse(allocationResponses, residualResponses);
    }

    private static AllocationLineResponse toLineResponse(AllocationLine line, Requirement requirement,
                                                           String studentFirstName) {
        return new AllocationLineResponse(
                line.getRequirementId(),
                requirement == null ? null : requirement.getName(),
                line.getStudentId(),
                studentFirstName,
                line.getQuantityNeeded(),
                line.getOwnedQuantity(),
                line.getPoolFulfilledQuantity(),
                line.getPurchaseRequiredQuantity(),
                line.getStatus().name());
    }

    private static ResidualDemandLineResponse toResidualResponse(ResidualDemandLine line, Requirement requirement) {
        return new ResidualDemandLineResponse(
                line.getRequirementId(),
                requirement == null ? null : requirement.getName(),
                line.getTotalRequired(),
                line.getTotalOwned(),
                line.getTotalPoolFulfilled(),
                line.getResidualDemand());
    }

    private record LineKey(UUID requirementId, UUID studentId) {
    }
}
