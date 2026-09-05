package app.classpool.api.service;

import app.classpool.api.domain.AllocationLine;
import app.classpool.api.domain.AppUser;
import app.classpool.api.domain.ClassReserveEntry;
import app.classpool.api.domain.DistributionBatch;
import app.classpool.api.domain.DistributionItem;
import app.classpool.api.domain.DistributionMode;
import app.classpool.api.domain.Household;
import app.classpool.api.domain.Membership;
import app.classpool.api.domain.NotificationType;
import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.PurchasePlanLine;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.Student;
import app.classpool.api.dto.ClassReserveEntryResponse;
import app.classpool.api.dto.DistributionItemResponse;
import app.classpool.api.dto.DistributionSummaryResponse;
import app.classpool.api.dto.GenerateDistributionRequest;
import app.classpool.api.dto.HouseholdPickListLineResponse;
import app.classpool.api.dto.HouseholdPickListResponse;
import app.classpool.api.exception.BadRequestException;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.AllocationLineRepository;
import app.classpool.api.repository.AppUserRepository;
import app.classpool.api.repository.ClassReserveEntryRepository;
import app.classpool.api.repository.DistributionBatchRepository;
import app.classpool.api.repository.DistributionItemRepository;
import app.classpool.api.repository.HouseholdRepository;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.OrderRepository;
import app.classpool.api.repository.PurchasePlanLineRepository;
import app.classpool.api.repository.PurchasePlanRepository;
import app.classpool.api.repository.RequirementRepository;
import app.classpool.api.repository.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The organizer's physical-handoff surface (PRD §9.2/§9.3/§9.4): generating a {@link
 * DistributionBatch} of per-(requirement, student) {@link DistributionItem}s from the Phase 6/7
 * allocation snapshot, marking them delivered, and banking pack leftover into {@link
 * ClassReserveEntry} rows. One service for the whole feature (same size precedent as {@code
 * PurchasePlanService}), separate from {@code OrderService} purely because the contract's route
 * prefixes split cleanly (recording a purchase vs. handing it out) — see apps/api/README.md's
 * Phase 10 notes.
 */
@Service
public class DistributionService {

    private final DistributionBatchRepository distributionBatchRepository;
    private final DistributionItemRepository distributionItemRepository;
    private final ClassReserveEntryRepository classReserveEntryRepository;
    private final OrderRepository orderRepository;
    private final PurchasePlanRepository purchasePlanRepository;
    private final PurchasePlanLineRepository purchasePlanLineRepository;
    private final AllocationLineRepository allocationLineRepository;
    private final RequirementRepository requirementRepository;
    private final StudentRepository studentRepository;
    private final MembershipRepository membershipRepository;
    private final HouseholdRepository householdRepository;
    private final AppUserRepository appUserRepository;
    private final PoolService poolService;
    private final NotificationService notificationService;

    public DistributionService(DistributionBatchRepository distributionBatchRepository,
                                DistributionItemRepository distributionItemRepository,
                                ClassReserveEntryRepository classReserveEntryRepository,
                                OrderRepository orderRepository, PurchasePlanRepository purchasePlanRepository,
                                PurchasePlanLineRepository purchasePlanLineRepository,
                                AllocationLineRepository allocationLineRepository,
                                RequirementRepository requirementRepository, StudentRepository studentRepository,
                                MembershipRepository membershipRepository, HouseholdRepository householdRepository,
                                AppUserRepository appUserRepository, PoolService poolService,
                                NotificationService notificationService) {
        this.distributionBatchRepository = distributionBatchRepository;
        this.distributionItemRepository = distributionItemRepository;
        this.classReserveEntryRepository = classReserveEntryRepository;
        this.orderRepository = orderRepository;
        this.purchasePlanRepository = purchasePlanRepository;
        this.purchasePlanLineRepository = purchasePlanLineRepository;
        this.allocationLineRepository = allocationLineRepository;
        this.requirementRepository = requirementRepository;
        this.studentRepository = studentRepository;
        this.membershipRepository = membershipRepository;
        this.householdRepository = householdRepository;
        this.appUserRepository = appUserRepository;
        this.poolService = poolService;
        this.notificationService = notificationService;
    }

    /**
     * Organizer-only (contract). Requires an {@link app.classpool.api.domain.Order} already
     * recorded and {@code pool.state == ORDERED}; 409 if a batch already exists for this pool
     * (one-shot). One {@link DistributionItem} per {@link AllocationLine} with {@code
     * poolFulfilledQuantity + purchaseRequiredQuantity > 0} — a line where both are zero is fully
     * self-fulfilled from household inventory and needs no physical hand-off, so it's skipped
     * entirely. Every {@link PurchasePlanLine} with {@code wasteQuantity > 0} becomes one {@link
     * ClassReserveEntry} scoped to this pool's {@code classroom_id} (never {@code school_id} — see
     * {@code ClassReserveEntry}'s Javadoc). One-way: moves the pool {@code ORDERED ->
     * DISTRIBUTING} ({@link PoolService#transitionToDistributing}).
     */
    @Transactional
    public DistributionSummaryResponse generateDistribution(UUID callerUserId, UUID poolId,
                                                              GenerateDistributionRequest request) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        if (pool.getState() != PoolState.ORDERED) {
            throw new ConflictException("Pool is not ORDERED");
        }
        if (!orderRepository.existsByPoolId(poolId)) {
            throw new ConflictException("No order has been recorded for this pool yet");
        }
        if (distributionBatchRepository.existsByPoolId(poolId)) {
            throw new ConflictException("A distribution batch already exists for this pool");
        }
        DistributionMode mode = parseMode(request == null ? null : request.mode());

        DistributionBatch batch = distributionBatchRepository.save(new DistributionBatch(poolId, mode));

        List<Requirement> requirements = requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId);
        List<UUID> requirementIds = requirements.stream().map(Requirement::getId).toList();
        Map<UUID, Requirement> requirementsById = requirements.stream()
                .collect(Collectors.toMap(Requirement::getId, r -> r));

        List<DistributionItem> savedItems = List.of();
        if (!requirementIds.isEmpty()) {
            List<AllocationLine> allocationLines = allocationLineRepository
                    .findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(requirementIds);
            List<DistributionItem> items = allocationLines.stream()
                    .filter(l -> l.getPoolFulfilledQuantity() + l.getPurchaseRequiredQuantity() > 0)
                    .map(l -> new DistributionItem(batch.getId(), l.getStudentId(), l.getRequirementId(),
                            l.getPoolFulfilledQuantity() + l.getPurchaseRequiredQuantity()))
                    .toList();
            distributionItemRepository.saveAll(items);
            savedItems = items;
        }

        purchasePlanRepository.findByPoolId(poolId).ifPresent(plan -> {
            List<PurchasePlanLine> planLines = purchasePlanLineRepository
                    .findByPurchasePlanIdOrderByRequirementIdAscProductOfferIdAsc(plan.getId());
            List<ClassReserveEntry> reserveEntries = planLines.stream()
                    .filter(l -> l.getWasteQuantity() > 0)
                    .map(l -> new ClassReserveEntry(pool.getClassroomId(), itemNameFor(l, requirementsById),
                            l.getWasteQuantity()))
                    .toList();
            classReserveEntryRepository.saveAll(reserveEntries);
        });

        poolService.transitionToDistributing(pool);
        notifyHouseholdsOfBundleReady(pool, savedItems);
        return buildSummary(batch);
    }

    /**
     * Phase 12 (PRD §11.3): one {@link NotificationType#BUNDLE_READY} notification per parent in
     * each distinct household that has at least one {@link DistributionItem} in this new batch —
     * a household with every line {@code SELF_FULFILLED} (skipped above, nothing to hand off) gets
     * none. Resolves "every parent in the household" the same
     * {@code MembershipRepository.findByClassroom_IdAndStudent_HouseholdId} way {@code
     * PaymentService.notifyHouseholdsOfPaymentDue} does.
     */
    private void notifyHouseholdsOfBundleReady(Pool pool, List<DistributionItem> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<UUID, UUID> householdByStudent = studentRepository
                .findAllById(items.stream().map(DistributionItem::getStudentId).distinct().toList()).stream()
                .collect(Collectors.toMap(Student::getId, Student::getHouseholdId));
        Set<UUID> householdIds = items.stream()
                .map(item -> householdByStudent.get(item.getStudentId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        String message = "Your items for " + pool.getName() + " are ready — check the pick list.";
        for (UUID householdId : householdIds) {
            membershipRepository.findByClassroom_IdAndStudent_HouseholdId(pool.getClassroomId(), householdId)
                    .stream()
                    .map(Membership::getParentUserId)
                    .distinct()
                    .forEach(userId -> notificationService.notify(userId, NotificationType.BUNDLE_READY,
                            pool.getId(), message));
        }
    }

    /**
     * Organizer-only (contract). 409 if generate hasn't run yet. Includes both the raw per-student
     * {@code items} (for marking delivered) and {@code pickLists} — items grouped by household (via
     * {@link Student#getHouseholdId()}) and, within each household, summed per distinct requirement
     * so a two-student household shows one "Pencils: 4" line, not two separate "Pencils: 2" lines
     * (PRD §9.2 update's printable artifact).
     */
    @Transactional(readOnly = true)
    public DistributionSummaryResponse getDistribution(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        DistributionBatch batch = distributionBatchRepository.findByPoolId(poolId)
                .orElseThrow(() -> new ConflictException("No distribution has been generated for this pool yet"));
        return buildSummary(batch);
    }

    /**
     * Any member (contract) — the caller's own household's items only, across all of their
     * students on this classroom. Empty array (never an error) if generate hasn't run yet, or this
     * household has nothing to receive — same "nothing to show yet" precedent as {@code
     * AllocationService.getMyAllocation}.
     */
    @Transactional(readOnly = true)
    public List<DistributionItemResponse> getMyDistribution(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireMembership(callerUserId, pool.getClassroomId());

        DistributionBatch batch = distributionBatchRepository.findByPoolId(poolId).orElse(null);
        if (batch == null) {
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

        List<DistributionItem> items = distributionItemRepository
                .findByDistributionBatchIdAndStudentIdInOrderByRequirementIdAscStudentIdAsc(batch.getId(),
                        ownStudentIds);
        if (items.isEmpty()) {
            return List.of();
        }
        Map<UUID, Requirement> requirementsById = requirementRepository
                .findAllById(items.stream().map(DistributionItem::getRequirementId).distinct().toList()).stream()
                .collect(Collectors.toMap(Requirement::getId, r -> r));
        Map<UUID, Student> studentsById = studentRepository.findAllById(ownStudentIds).stream()
                .collect(Collectors.toMap(Student::getId, s -> s));
        return items.stream()
                .map(item -> toItemResponse(item, requirementsById.get(item.getRequirementId()),
                        studentsById.get(item.getStudentId())))
                .toList();
    }

    /** Organizer-only (contract). 409 if already marked delivered. */
    @Transactional
    public DistributionItemResponse markDistributionItemDelivered(UUID callerUserId, UUID poolId, UUID itemId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        DistributionBatch batch = distributionBatchRepository.findByPoolId(poolId)
                .orElseThrow(() -> new ConflictException("No distribution has been generated for this pool yet"));
        DistributionItem item = distributionItemRepository.findByIdAndDistributionBatchId(itemId, batch.getId())
                .orElseThrow(() -> new NotFoundException("Distribution item not found: " + itemId));
        if (item.getDeliveredAt() != null) {
            throw new ConflictException("Item is already marked delivered");
        }
        item.markDelivered();
        distributionItemRepository.save(item);

        Requirement requirement = requirementRepository.findById(item.getRequirementId()).orElse(null);
        Student student = studentRepository.findById(item.getStudentId()).orElse(null);
        return toItemResponse(item, requirement, student);
    }

    /**
     * Organizer-only (contract). Every {@link ClassReserveEntry} for this pool's classroom — not
     * just the ones this pool itself created, since Class Reserve is a classroom-level concept
     * multiple pools contribute to over time (this phase is its first appearance, so in practice
     * today it's only ever this pool's own entries).
     */
    @Transactional(readOnly = true)
    public List<ClassReserveEntryResponse> getClassReserve(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        return classReserveEntryRepository.findByClassroomIdOrderByCreatedAtAsc(pool.getClassroomId()).stream()
                .map(DistributionService::toReserveResponse)
                .toList();
    }

    // ==================== helpers ====================

    private DistributionSummaryResponse buildSummary(DistributionBatch batch) {
        List<DistributionItem> items = distributionItemRepository
                .findByDistributionBatchIdOrderByRequirementIdAscStudentIdAsc(batch.getId());
        if (items.isEmpty()) {
            return new DistributionSummaryResponse(batch.getId(), batch.getPoolId(), batch.getMode().name(),
                    batch.getCreatedAt(), List.of(), List.of());
        }

        Map<UUID, Requirement> requirementsById = requirementRepository
                .findAllById(items.stream().map(DistributionItem::getRequirementId).distinct().toList()).stream()
                .collect(Collectors.toMap(Requirement::getId, r -> r));
        Map<UUID, Student> studentsById = studentRepository
                .findAllById(items.stream().map(DistributionItem::getStudentId).distinct().toList()).stream()
                .collect(Collectors.toMap(Student::getId, s -> s));

        List<DistributionItemResponse> itemResponses = items.stream()
                .map(item -> toItemResponse(item, requirementsById.get(item.getRequirementId()),
                        studentsById.get(item.getStudentId())))
                .toList();
        List<HouseholdPickListResponse> pickLists = buildPickLists(items, requirementsById, studentsById);

        return new DistributionSummaryResponse(batch.getId(), batch.getPoolId(), batch.getMode().name(),
                batch.getCreatedAt(), itemResponses, pickLists);
    }

    /** Groups items by household, then sums {@code quantity} per distinct requirement within each
     *  household — the cross-sibling aggregation the contract's printable pick-list artifact needs
     *  (PRD §9.2 update). */
    private List<HouseholdPickListResponse> buildPickLists(List<DistributionItem> items,
                                                             Map<UUID, Requirement> requirementsById,
                                                             Map<UUID, Student> studentsById) {
        Map<UUID, Map<UUID, Integer>> quantityByHouseholdThenRequirement = new LinkedHashMap<>();
        for (DistributionItem item : items) {
            Student student = studentsById.get(item.getStudentId());
            if (student == null) {
                continue;
            }
            quantityByHouseholdThenRequirement
                    .computeIfAbsent(student.getHouseholdId(), k -> new LinkedHashMap<>())
                    .merge(item.getRequirementId(), item.getQuantity(), Integer::sum);
        }
        if (quantityByHouseholdThenRequirement.isEmpty()) {
            return List.of();
        }

        Map<UUID, String> displayNames = householdDisplayNames(
                List.copyOf(quantityByHouseholdThenRequirement.keySet()));

        List<HouseholdPickListResponse> pickLists = new ArrayList<>();
        for (Map.Entry<UUID, Map<UUID, Integer>> householdEntry : quantityByHouseholdThenRequirement.entrySet()) {
            List<HouseholdPickListLineResponse> lines = householdEntry.getValue().entrySet().stream()
                    .map(e -> new HouseholdPickListLineResponse(requirementNameOf(requirementsById.get(e.getKey())),
                            e.getValue()))
                    .toList();
            pickLists.add(new HouseholdPickListResponse(householdEntry.getKey(),
                    displayNames.get(householdEntry.getKey()), lines));
        }
        return pickLists;
    }

    private Map<UUID, String> householdDisplayNames(List<UUID> householdIds) {
        if (householdIds.isEmpty()) {
            return Map.of();
        }
        List<Household> households = householdRepository.findAllById(householdIds);
        List<UUID> primaryParentIds = households.stream().map(Household::getPrimaryParentId).distinct().toList();
        Map<UUID, String> nameByUserId = appUserRepository.findAllById(primaryParentIds).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getDisplayName));
        return households.stream()
                .collect(Collectors.toMap(Household::getId, h -> nameByUserId.get(h.getPrimaryParentId())));
    }

    private static String itemNameFor(PurchasePlanLine planLine, Map<UUID, Requirement> requirementsById) {
        Requirement requirement = requirementsById.get(planLine.getRequirementId());
        return requirement == null ? planLine.getRequirementId().toString() : requirement.getName();
    }

    private static String requirementNameOf(Requirement requirement) {
        return requirement == null ? null : requirement.getName();
    }

    private static DistributionItemResponse toItemResponse(DistributionItem item, Requirement requirement,
                                                             Student student) {
        return new DistributionItemResponse(
                item.getId(),
                item.getStudentId(),
                student == null ? null : student.getFirstName(),
                item.getRequirementId(),
                requirementNameOf(requirement),
                item.getQuantity(),
                item.getDeliveredAt());
    }

    private static ClassReserveEntryResponse toReserveResponse(ClassReserveEntry entry) {
        return new ClassReserveEntryResponse(entry.getId(), entry.getClassroomId(), entry.getItemName(),
                entry.getQuantity(), entry.getCustodianLocation(), entry.getCreatedAt());
    }

    private static DistributionMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("mode is required");
        }
        try {
            return DistributionMode.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown mode: " + raw);
        }
    }
}
