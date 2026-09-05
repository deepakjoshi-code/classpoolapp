package app.classpool.api.service;

import app.classpool.api.domain.Membership;
import app.classpool.api.domain.ParentInventory;
import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.Requirement;
import app.classpool.api.dto.InventoryLineResponse;
import app.classpool.api.dto.InventoryRequirementTotal;
import app.classpool.api.dto.InventorySummaryResponse;
import app.classpool.api.dto.RequirementResponse;
import app.classpool.api.dto.SetInventoryRequest;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.ParentInventoryRepository;
import app.classpool.api.repository.RequirementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Shop Your Home First" (PRD §4) — recording and reading back how much of each requirement a
 * household already owns. Only {@code owned_quantity} is touched this phase; surplus offering
 * (PRD §5) is a distinct, later action against the same {@code parent_inventory} table.
 */
@Service
public class InventoryService {

    private final ParentInventoryRepository parentInventoryRepository;
    private final RequirementRepository requirementRepository;
    private final MembershipRepository membershipRepository;
    private final PoolService poolService;
    private final RequirementAssembler requirementAssembler;

    public InventoryService(ParentInventoryRepository parentInventoryRepository,
                             RequirementRepository requirementRepository, MembershipRepository membershipRepository,
                             PoolService poolService, RequirementAssembler requirementAssembler) {
        this.parentInventoryRepository = parentInventoryRepository;
        this.requirementRepository = requirementRepository;
        this.membershipRepository = membershipRepository;
        this.poolService = poolService;
        this.requirementAssembler = requirementAssembler;
    }

    /**
     * One {@link InventoryLineResponse} per (requirement, caller's own student) pair in this pool
     * — a cross join of "this pool's requirements" x "this caller's own students in this
     * classroom" (a household can have more than one student in the same classroom, e.g. twins),
     * left-joined against any existing {@link ParentInventory} row (default owned quantity 0 if
     * none exists yet). Any member may call this for their own household; there is nothing to view
     * for anyone else's (contract). Returns an empty list for a still-DRAFT pool rather than
     * erroring — {@code quantityPerStudent} isn't meaningfully "required" until confirmed.
     */
    @Transactional(readOnly = true)
    public List<InventoryLineResponse> getMyInventory(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireMembership(callerUserId, pool.getClassroomId());

        if (pool.getState() == PoolState.DRAFT) {
            return List.of();
        }

        List<Requirement> requirements = requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId);
        List<Membership> ownStudents = membershipRepository
                .findByClassroom_IdAndParentUserId(pool.getClassroomId(), callerUserId).stream()
                .filter(m -> m.getStudent() != null)
                .toList();
        if (requirements.isEmpty() || ownStudents.isEmpty()) {
            return List.of();
        }

        List<UUID> requirementIds = requirements.stream().map(Requirement::getId).toList();
        List<UUID> studentIds = ownStudents.stream().map(m -> m.getStudent().getId()).toList();
        Map<LineKey, Integer> owned = parentInventoryRepository
                .findByRequirementIdInAndStudentIdIn(requirementIds, studentIds).stream()
                .collect(Collectors.toMap(pi -> new LineKey(pi.getRequirementId(), pi.getStudentId()),
                        ParentInventory::getOwnedQuantity));

        List<InventoryLineResponse> lines = new ArrayList<>();
        for (Requirement requirement : requirements) {
            for (Membership membership : ownStudents) {
                int ownedQuantity = owned.getOrDefault(
                        new LineKey(requirement.getId(), membership.getStudent().getId()), 0);
                lines.add(toLine(requirement, membership.getStudent().getId(),
                        membership.getStudent().getFirstName(), ownedQuantity));
            }
        }
        return lines;
    }

    /**
     * Upsert on (requirementId, studentId) — PRD §4's quick +/- stepper. The caller must hold a
     * Membership on this pool's classroom for the specific {@code studentId} in the request, not
     * just any membership on the classroom (contract) — this is what stops one household from
     * recording inventory against another's child. {@code ownedQuantity} is clamped server-side to
     * {@code [0, requirement.quantityPerStudent]}: owning more than required doesn't get recorded
     * as more, since surplus offering (PRD §5) is a distinct, later action.
     */
    @Transactional
    public InventoryLineResponse setInventory(UUID callerUserId, UUID poolId, UUID requirementId,
                                               SetInventoryRequest request) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        Membership membership = membershipRepository
                .findByClassroom_IdAndParentUserIdAndStudent_Id(pool.getClassroomId(), callerUserId,
                        request.studentId())
                .orElseThrow(() -> new ForbiddenException(
                        "Caller has no Membership on this classroom for that student"));

        if (pool.getState() == PoolState.DRAFT) {
            throw new ConflictException("Pool is still DRAFT — nothing to record inventory against yet");
        }

        Requirement requirement = requirementRepository.findByIdAndPoolId(requirementId, poolId)
                .orElseThrow(() -> new NotFoundException("Requirement not found: " + requirementId));

        int clamped = clamp(request.ownedQuantity(), requirement.getQuantityPerStudent());
        ParentInventory inventory = parentInventoryRepository
                .findByRequirementIdAndStudentId(requirementId, request.studentId())
                .orElseGet(() -> new ParentInventory(requirementId, request.studentId(), callerUserId, clamped));
        inventory.applyOwnedQuantity(clamped, callerUserId);
        parentInventoryRepository.save(inventory);

        return toLine(requirement, membership.getStudent().getId(), membership.getStudent().getFirstName(),
                inventory.getOwnedQuantity());
    }

    /**
     * Organizer/co-organizer only (contract) — PRD §12.3's "Inventory completed 19/25" dashboard
     * aggregate. {@code totalRequired} per requirement reuses {@link RequirementAssembler}'s
     * {@code totalDemand} rather than recomputing it, so the two numbers can never drift apart.
     */
    @Transactional(readOnly = true)
    public InventorySummaryResponse getSummary(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());

        long totalJoinedStudents = membershipRepository.countDistinctStudentsByClassroom_Id(pool.getClassroomId());
        List<Requirement> requirements = requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId);
        if (requirements.isEmpty()) {
            return new InventorySummaryResponse(0, (int) totalJoinedStudents, List.of());
        }

        List<UUID> requirementIds = requirements.stream().map(Requirement::getId).toList();
        long studentsSubmitted = parentInventoryRepository.countDistinctStudentsByRequirementIdIn(requirementIds);
        Map<UUID, Long> totalOwnedByRequirement = parentInventoryRepository
                .sumOwnedQuantityByRequirementIdIn(requirementIds).stream()
                .collect(Collectors.toMap(ParentInventoryRepository.RequirementOwnedTotal::getRequirementId,
                        ParentInventoryRepository.RequirementOwnedTotal::getTotal));

        List<RequirementResponse> requirementResponses = requirementAssembler.toResponses(requirements, pool);
        List<InventoryRequirementTotal> perRequirement = requirementResponses.stream()
                .map(r -> new InventoryRequirementTotal(r.id(), r.name(),
                        totalOwnedByRequirement.getOrDefault(r.id(), 0L).intValue(), r.totalDemand()))
                .toList();

        return new InventorySummaryResponse((int) studentsSubmitted, (int) totalJoinedStudents, perRequirement);
    }

    private static int clamp(int ownedQuantity, int quantityPerStudent) {
        return Math.max(0, Math.min(ownedQuantity, quantityPerStudent));
    }

    private static InventoryLineResponse toLine(Requirement requirement, UUID studentId, String studentFirstName,
                                                 int ownedQuantity) {
        int stillNeeded = Math.max(0, requirement.getQuantityPerStudent() - ownedQuantity);
        return new InventoryLineResponse(requirement.getId(), requirement.getName(),
                requirement.getQuantityPerStudent(), studentId, studentFirstName, ownedQuantity, stillNeeded);
    }

    private record LineKey(UUID requirementId, UUID studentId) {
    }
}
