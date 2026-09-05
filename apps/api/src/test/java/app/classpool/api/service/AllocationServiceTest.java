package app.classpool.api.service;

import app.classpool.api.domain.AllocationLine;
import app.classpool.api.domain.Classroom;
import app.classpool.api.domain.Contribution;
import app.classpool.api.domain.ContributionMode;
import app.classpool.api.domain.ContributionState;
import app.classpool.api.domain.Membership;
import app.classpool.api.domain.MembershipRole;
import app.classpool.api.domain.ParentInventory;
import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.ProductOffer;
import app.classpool.api.domain.PurchasePlan;
import app.classpool.api.domain.PurchasePlanLine;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementState;
import app.classpool.api.domain.RequirementStrictness;
import app.classpool.api.domain.ResidualDemandLine;
import app.classpool.api.domain.Student;
import app.classpool.api.dto.AllocationLineResponse;
import app.classpool.api.dto.AllocationSummaryResponse;
import app.classpool.api.dto.SavingsSummaryResponse;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.repository.AllocationLineRepository;
import app.classpool.api.repository.ContributionRepository;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.ParentInventoryRepository;
import app.classpool.api.repository.PoolRepository;
import app.classpool.api.repository.ProductOfferRepository;
import app.classpool.api.repository.PurchasePlanLineRepository;
import app.classpool.api.repository.PurchasePlanRepository;
import app.classpool.api.repository.RequirementRepository;
import app.classpool.api.repository.ResidualDemandLineRepository;
import app.classpool.api.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit coverage of the Phase 6/7 allocation engine's algorithm (PRD §6). Only repository
 * collaborators are mocked — {@link PoolService} is exercised for real, same pattern as
 * {@code ContributionServiceTest}/{@code InventoryServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class AllocationServiceTest {

    @Mock
    private AllocationLineRepository allocationLineRepository;
    @Mock
    private ResidualDemandLineRepository residualDemandLineRepository;
    @Mock
    private RequirementRepository requirementRepository;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ParentInventoryRepository parentInventoryRepository;
    @Mock
    private ContributionRepository contributionRepository;
    @Mock
    private StudentRepository studentRepository;
    @Mock
    private PurchasePlanRepository purchasePlanRepository;
    @Mock
    private PurchasePlanLineRepository purchasePlanLineRepository;
    @Mock
    private ProductOfferRepository productOfferRepository;
    @Mock
    private PoolRepository poolRepository;

    private AllocationService allocationService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID classroomId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PoolAssembler poolAssembler = new PoolAssembler(requirementRepository);
        RequirementAssembler requirementAssembler = new RequirementAssembler();
        PoolService poolService = new PoolService(poolRepository, requirementRepository, membershipRepository,
                poolAssembler, requirementAssembler, notificationService);
        allocationService = new AllocationService(allocationLineRepository, residualDemandLineRepository,
                requirementRepository, membershipRepository, parentInventoryRepository, contributionRepository,
                studentRepository, purchasePlanRepository, purchasePlanLineRepository, productOfferRepository,
                poolService);
    }

    // ---- reconcile: per-status outcomes ----

    @Test
    void reconcile_selfFulfilledStudent_needsNoPoolOrPurchase() {
        Pool pool = newPool(PoolState.OPEN_FOR_INVENTORY);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Student student = newStudent("Alex");
        Membership membership = newMembership(student, Instant.now());

        stubOrganizer(pool);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(membershipRepository.findByClassroom_IdAndStudentIsNotNullOrderByCreatedAtAsc(classroomId))
                .thenReturn(List.of(membership));
        when(contributionRepository.sumQuantityByRequirementIdInAndState(List.of(requirement.getId()),
                ContributionState.RECEIVED)).thenReturn(List.of());
        // Owns all 4 needed already — no pool contribution exists at all.
        when(parentInventoryRepository.findByRequirementIdInAndStudentIdIn(List.of(requirement.getId()),
                List.of(student.getId()))).thenReturn(List.of(newInventory(requirement.getId(), student.getId(), 4)));

        AllocationSummaryResponse summary = allocationService.reconcile(callerId, pool.getId());

        assertThat(summary.allocations()).hasSize(1);
        AllocationLineResponse line = summary.allocations().get(0);
        assertThat(line.ownedQuantity()).isEqualTo(4);
        assertThat(line.poolFulfilledQuantity()).isZero();
        assertThat(line.purchaseRequiredQuantity()).isZero();
        assertThat(line.status()).isEqualTo("SELF_FULFILLED");

        assertThat(summary.residualDemand()).hasSize(1);
        assertThat(summary.residualDemand().get(0).residualDemand()).isZero();
        assertThat(pool.getState()).isEqualTo(PoolState.RECONCILING);
    }

    @Test
    void reconcile_poolFulfillsRemainderAfterOwnedInventory() {
        Pool pool = newPool(PoolState.OPEN_FOR_INVENTORY);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Student student = newStudent("Alex");
        Membership membership = newMembership(student, Instant.now());

        stubOrganizer(pool);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(membershipRepository.findByClassroom_IdAndStudentIsNotNullOrderByCreatedAtAsc(classroomId))
                .thenReturn(List.of(membership));
        // Owns 1 of 4; RECEIVED pool supply of 5 easily covers the remaining 3.
        when(contributionRepository.sumQuantityByRequirementIdInAndState(List.of(requirement.getId()),
                ContributionState.RECEIVED))
                .thenReturn(List.of(new StubQuantityTotal(requirement.getId(), 5L)));
        when(parentInventoryRepository.findByRequirementIdInAndStudentIdIn(List.of(requirement.getId()),
                List.of(student.getId()))).thenReturn(List.of(newInventory(requirement.getId(), student.getId(), 1)));

        AllocationSummaryResponse summary = allocationService.reconcile(callerId, pool.getId());

        AllocationLineResponse line = summary.allocations().get(0);
        assertThat(line.ownedQuantity()).isEqualTo(1);
        assertThat(line.poolFulfilledQuantity()).isEqualTo(3);
        assertThat(line.purchaseRequiredQuantity()).isZero();
        assertThat(line.status()).isEqualTo("POOL_FULFILLED");
        assertThat(summary.residualDemand().get(0).residualDemand()).isZero();
    }

    @Test
    void reconcile_purchaseRequired_afterPoolExhausted() {
        Pool pool = newPool(PoolState.OPEN_FOR_INVENTORY);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Student student = newStudent("Alex");
        Membership membership = newMembership(student, Instant.now());

        stubOrganizer(pool);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(membershipRepository.findByClassroom_IdAndStudentIsNotNullOrderByCreatedAtAsc(classroomId))
                .thenReturn(List.of(membership));
        // Owns 1 of 4; pool only has 1 RECEIVED unit — 2 still needs purchasing.
        when(contributionRepository.sumQuantityByRequirementIdInAndState(List.of(requirement.getId()),
                ContributionState.RECEIVED))
                .thenReturn(List.of(new StubQuantityTotal(requirement.getId(), 1L)));
        when(parentInventoryRepository.findByRequirementIdInAndStudentIdIn(List.of(requirement.getId()),
                List.of(student.getId()))).thenReturn(List.of(newInventory(requirement.getId(), student.getId(), 1)));

        AllocationSummaryResponse summary = allocationService.reconcile(callerId, pool.getId());

        AllocationLineResponse line = summary.allocations().get(0);
        assertThat(line.ownedQuantity()).isEqualTo(1);
        assertThat(line.poolFulfilledQuantity()).isEqualTo(1);
        assertThat(line.purchaseRequiredQuantity()).isEqualTo(2);
        assertThat(line.status()).isEqualTo("PURCHASE_REQUIRED");
        assertThat(summary.residualDemand().get(0).residualDemand()).isEqualTo(2);
    }

    @Test
    void reconcile_allocatesScarcePoolSupplyInJoinOrder_firstJoinedFirstServed() {
        Pool pool = newPool(PoolState.OPEN_FOR_INVENTORY);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Student first = newStudent("Alex");
        Student second = newStudent("Bailey");
        Instant now = Instant.now();
        // second's Membership is created before first's in mock setup order below, but the
        // repository call itself returns them already ordered by createdAt ascending (that's the
        // contract of findByClassroom_IdAndStudentIsNotNullOrderByCreatedAtAsc) — first joined
        // first.
        Membership firstMembership = newMembership(first, now.minus(1, ChronoUnit.HOURS));
        Membership secondMembership = newMembership(second, now);

        stubOrganizer(pool);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(membershipRepository.findByClassroom_IdAndStudentIsNotNullOrderByCreatedAtAsc(classroomId))
                .thenReturn(List.of(firstMembership, secondMembership));
        // Neither owns anything; pool only has enough RECEIVED supply (4) for one full student's
        // need, not both (8 total needed).
        when(contributionRepository.sumQuantityByRequirementIdInAndState(List.of(requirement.getId()),
                ContributionState.RECEIVED))
                .thenReturn(List.of(new StubQuantityTotal(requirement.getId(), 4L)));
        when(parentInventoryRepository.findByRequirementIdInAndStudentIdIn(eq(List.of(requirement.getId())), any()))
                .thenReturn(List.of());

        AllocationSummaryResponse summary = allocationService.reconcile(callerId, pool.getId());

        assertThat(summary.allocations()).hasSize(2);
        assertThat(summary.allocations()).extracting(AllocationLineResponse::studentId,
                        AllocationLineResponse::poolFulfilledQuantity, AllocationLineResponse::purchaseRequiredQuantity,
                        AllocationLineResponse::status)
                .containsExactlyInAnyOrder(
                        tuple(first.getId(), 4, 0, "POOL_FULFILLED"),
                        tuple(second.getId(), 0, 4, "PURCHASE_REQUIRED"));

        ArgumentCaptor<List<AllocationLine>> captor = ArgumentCaptor.forClass(List.class);
        verify(allocationLineRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(AllocationLine::getStudentId)
                .containsExactly(first.getId(), second.getId()); // persisted in join order too
    }

    @Test
    void reconcile_pledgedContributions_doNotCountTowardPoolAvailability() {
        Pool pool = newPool(PoolState.OPEN_FOR_INVENTORY);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Student student = newStudent("Alex");
        Membership membership = newMembership(student, Instant.now());

        stubOrganizer(pool);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(membershipRepository.findByClassroom_IdAndStudentIsNotNullOrderByCreatedAtAsc(classroomId))
                .thenReturn(List.of(membership));
        // The query is explicitly scoped to state = RECEIVED (verified below) — a PLEDGED-only
        // pool has nothing RECEIVED yet, so the repository returns an empty total, same as if no
        // contribution existed at all (PRD §5.4/§6.1).
        when(contributionRepository.sumQuantityByRequirementIdInAndState(List.of(requirement.getId()),
                ContributionState.RECEIVED)).thenReturn(List.of());
        when(parentInventoryRepository.findByRequirementIdInAndStudentIdIn(List.of(requirement.getId()),
                List.of(student.getId()))).thenReturn(List.of());

        AllocationSummaryResponse summary = allocationService.reconcile(callerId, pool.getId());

        AllocationLineResponse line = summary.allocations().get(0);
        assertThat(line.poolFulfilledQuantity()).isZero();
        assertThat(line.purchaseRequiredQuantity()).isEqualTo(4);
        assertThat(line.status()).isEqualTo("PURCHASE_REQUIRED");
        verify(contributionRepository).sumQuantityByRequirementIdInAndState(List.of(requirement.getId()),
                ContributionState.RECEIVED);
    }

    // ---- reconcile: guards ----

    @Test
    void reconcile_throwsConflict_whenPoolIsAlreadyReconciling() {
        Pool pool = newPool(PoolState.RECONCILING);
        stubOrganizer(pool);

        assertThatThrownBy(() -> allocationService.reconcile(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(allocationLineRepository, residualDemandLineRepository);
    }

    @Test
    void reconcile_throwsConflict_whenPoolIsStillDraft() {
        Pool pool = newPool(PoolState.DRAFT);
        stubOrganizer(pool);

        assertThatThrownBy(() -> allocationService.reconcile(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(allocationLineRepository, residualDemandLineRepository);
    }

    @Test
    void reconcile_throwsForbidden_forANonOrganizer() {
        Pool pool = newPool(PoolState.OPEN_FOR_INVENTORY);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> allocationService.reconcile(callerId, pool.getId()))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(allocationLineRepository);
    }

    // ---- getAllocationForOrganizer ----

    @Test
    void getAllocationForOrganizer_throwsForbidden_forANonOrganizer() {
        Pool pool = newPool(PoolState.RECONCILING);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> allocationService.getAllocationForOrganizer(callerId, pool.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getAllocationForOrganizer_throwsConflict_whenReconcileHasNotRunYet() {
        Pool pool = newPool(PoolState.OPEN_FOR_INVENTORY);
        stubOrganizer(pool);

        assertThatThrownBy(() -> allocationService.getAllocationForOrganizer(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void getAllocationForOrganizer_readsBackThePersistedSnapshot() {
        Pool pool = newPool(PoolState.RECONCILING);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        UUID studentId = UUID.randomUUID();
        AllocationLine line = new AllocationLine(requirement.getId(), studentId, 4, 1, 3, 0,
                app.classpool.api.domain.AllocationStatus.POOL_FULFILLED);
        ResidualDemandLine residual = new ResidualDemandLine(requirement.getId(), 4, 1, 3, 0);
        Student student = newStudent("Alex");
        setField(student, "id", studentId);

        stubOrganizer(pool);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(allocationLineRepository.findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(
                List.of(requirement.getId()))).thenReturn(List.of(line));
        when(residualDemandLineRepository.findByRequirementIdInOrderByRequirementIdAsc(List.of(requirement.getId())))
                .thenReturn(List.of(residual));
        when(studentRepository.findAllById(List.of(studentId))).thenReturn(List.of(student));

        AllocationSummaryResponse summary = allocationService.getAllocationForOrganizer(callerId, pool.getId());

        assertThat(summary.allocations()).hasSize(1);
        assertThat(summary.allocations().get(0).studentFirstName()).isEqualTo("Alex");
        assertThat(summary.allocations().get(0).requirementName()).isEqualTo("Glue Sticks");
        assertThat(summary.residualDemand()).hasSize(1);
        assertThat(summary.residualDemand().get(0).requirementName()).isEqualTo("Glue Sticks");
    }

    // ---- getMyAllocation ----

    @Test
    void getMyAllocation_returnsEmptyList_whenReconcileHasNotRunYet() {
        Pool pool = newPool(PoolState.OPEN_FOR_INVENTORY);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(true);

        List<AllocationLineResponse> lines = allocationService.getMyAllocation(callerId, pool.getId());

        assertThat(lines).isEmpty();
        verifyNoInteractions(allocationLineRepository);
    }

    @Test
    void getMyAllocation_returnsOnlyTheCallersOwnStudentsLines() {
        Pool pool = newPool(PoolState.RECONCILING);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Student ownStudent = newStudent("Alex");
        Membership ownMembership = newMembership(ownStudent, Instant.now());

        AllocationLine myLine = new AllocationLine(requirement.getId(), ownStudent.getId(), 4, 1, 3, 0,
                app.classpool.api.domain.AllocationStatus.POOL_FULFILLED);

        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(true);
        when(membershipRepository.findByClassroom_IdAndParentUserId(classroomId, callerId))
                .thenReturn(List.of(ownMembership));
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(allocationLineRepository.findByRequirementIdInAndStudentIdInOrderByRequirementIdAscStudentIdAsc(
                List.of(requirement.getId()), List.of(ownStudent.getId()))).thenReturn(List.of(myLine));
        when(studentRepository.findAllById(List.of(ownStudent.getId()))).thenReturn(List.of(ownStudent));

        List<AllocationLineResponse> lines = allocationService.getMyAllocation(callerId, pool.getId());

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).studentId()).isEqualTo(ownStudent.getId());
        assertThat(lines.get(0).studentFirstName()).isEqualTo("Alex");
    }

    @Test
    void getMyAllocation_throwsForbidden_whenCallerHasNoMembershipOnThePoolsClassroom() {
        Pool pool = newPool(PoolState.RECONCILING);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> allocationService.getMyAllocation(callerId, pool.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- getSavingsSummary (Phase 12) ----

    @Test
    void getSavingsSummary_throwsForbidden_whenCallerHasNoMembership() {
        Pool pool = newPool(PoolState.RECONCILING);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> allocationService.getSavingsSummary(callerId, pool.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getSavingsSummary_throwsConflict_whenPoolHasNotBeenReconciledYet() {
        Pool pool = newPool(PoolState.OPEN_FOR_INVENTORY);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(true);

        assertThatThrownBy(() -> allocationService.getSavingsSummary(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class);
    }

    /**
     * Hand-verified: two requirements' AllocationLines sum to itemsReused = (3+2)+(0+1) = 6,
     * itemsPurchased = 4+2 = 6. No PurchasePlan yet -&gt; estimatedSavingsCents is 0 and the
     * shareable message omits the "saved an estimated" clause entirely.
     */
    @Test
    void getSavingsSummary_sumsItemsReusedAndPurchased_zeroSavings_whenNoPurchasePlanYet() {
        Pool pool = newPool(PoolState.RECONCILING);
        Requirement glueSticks = newRequirement(pool.getId(), "Glue Sticks", 9);
        Requirement folders = newRequirement(pool.getId(), "Folders", 3);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId()))
                .thenReturn(List.of(glueSticks, folders));
        when(allocationLineRepository.findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(
                List.of(glueSticks.getId(), folders.getId())))
                .thenReturn(List.of(
                        new AllocationLine(glueSticks.getId(), UUID.randomUUID(), 9, 3, 2, 4,
                                app.classpool.api.domain.AllocationStatus.PURCHASE_REQUIRED),
                        new AllocationLine(folders.getId(), UUID.randomUUID(), 3, 0, 1, 2,
                                app.classpool.api.domain.AllocationStatus.PURCHASE_REQUIRED)));
        when(purchasePlanRepository.findByPoolId(pool.getId())).thenReturn(Optional.empty());

        SavingsSummaryResponse summary = allocationService.getSavingsSummary(callerId, pool.getId());

        assertThat(summary.poolId()).isEqualTo(pool.getId());
        assertThat(summary.poolName()).isEqualTo("Fall Supplies");
        assertThat(summary.itemsReused()).isEqualTo(6);
        assertThat(summary.itemsPurchased()).isEqualTo(6);
        assertThat(summary.estimatedSavingsCents()).isZero();
        assertThat(summary.shareableMessage()).isEqualTo("\"Fall Supplies\" reused 6 items with ClassPool!");
    }

    /**
     * Same 6 itemsReused as above, now with a PurchasePlan: line 1 costs 300 cents across a
     * 5-pack x2 (10 units), line 2 costs 200 cents across a 2-pack x1 (2 units) — total spent 500
     * across 12 units purchased. {@code avgUnitCostCents = round(500/12) = 42} (NOT divided by
     * itemsPurchased=6), so {@code estimatedSavingsCents = round(42*6) = 252}.
     */
    @Test
    void getSavingsSummary_computesEstimatedSavings_fromAvgActualUnitCost_whenAPurchasePlanExists() {
        Pool pool = newPool(PoolState.PURCHASE_PROPOSED);
        Requirement glueSticks = newRequirement(pool.getId(), "Glue Sticks", 9);
        Requirement folders = newRequirement(pool.getId(), "Folders", 3);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId()))
                .thenReturn(List.of(glueSticks, folders));
        when(allocationLineRepository.findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(
                List.of(glueSticks.getId(), folders.getId())))
                .thenReturn(List.of(
                        new AllocationLine(glueSticks.getId(), UUID.randomUUID(), 9, 3, 2, 4,
                                app.classpool.api.domain.AllocationStatus.PURCHASE_REQUIRED),
                        new AllocationLine(folders.getId(), UUID.randomUUID(), 3, 0, 1, 2,
                                app.classpool.api.domain.AllocationStatus.PURCHASE_REQUIRED)));

        PurchasePlan plan = new PurchasePlan(pool.getId());
        setField(plan, "id", UUID.randomUUID());
        when(purchasePlanRepository.findByPoolId(pool.getId())).thenReturn(Optional.of(plan));

        ProductOffer offerA = newProductOffer(glueSticks.getId(), 5);
        ProductOffer offerB = newProductOffer(folders.getId(), 2);
        PurchasePlanLine lineA = new PurchasePlanLine(plan.getId(), glueSticks.getId(), offerA.getId(), 2, 300, 1);
        PurchasePlanLine lineB = new PurchasePlanLine(plan.getId(), folders.getId(), offerB.getId(), 1, 200, 0);
        when(purchasePlanLineRepository.findByPurchasePlanIdOrderByRequirementIdAscProductOfferIdAsc(plan.getId()))
                .thenReturn(List.of(lineA, lineB));
        when(productOfferRepository.findAllById(List.of(offerA.getId(), offerB.getId())))
                .thenReturn(List.of(offerA, offerB));

        SavingsSummaryResponse summary = allocationService.getSavingsSummary(callerId, pool.getId());

        assertThat(summary.itemsReused()).isEqualTo(6);
        assertThat(summary.estimatedSavingsCents()).isEqualTo(252); // round(round(500/12) * 6) = round(42*6)
        assertThat(summary.shareableMessage())
                .isEqualTo("\"Fall Supplies\" reused 6 items and saved an estimated $2.52 with ClassPool!");
    }

    @Test
    void getSavingsSummary_shareableMessage_usesSingularWording_forExactlyOneItemReused() {
        Pool pool = newPool(PoolState.RECONCILING);
        Requirement glueSticks = newRequirement(pool.getId(), "Glue Sticks", 1);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(glueSticks));
        when(allocationLineRepository.findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(
                List.of(glueSticks.getId())))
                .thenReturn(List.of(new AllocationLine(glueSticks.getId(), UUID.randomUUID(), 1, 1, 0, 0,
                        app.classpool.api.domain.AllocationStatus.SELF_FULFILLED)));
        when(purchasePlanRepository.findByPoolId(pool.getId())).thenReturn(Optional.empty());

        SavingsSummaryResponse summary = allocationService.getSavingsSummary(callerId, pool.getId());

        assertThat(summary.itemsReused()).isEqualTo(1);
        assertThat(summary.shareableMessage()).isEqualTo("\"Fall Supplies\" reused 1 item with ClassPool!");
    }

    // ---- fixtures ----

    private void stubOrganizer(Pool pool) {
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
    }

    private Pool newPool(PoolState state) {
        Pool pool = new Pool(classroomId, "Fall Supplies", "SUPPLIES");
        setField(pool, "id", UUID.randomUUID());
        setField(pool, "createdAt", Instant.now());
        pool.setState(state);
        return pool;
    }

    private static Requirement newRequirement(UUID poolId, String name, int quantityPerStudent) {
        Requirement requirement = new Requirement(poolId, name, quantityPerStudent, null,
                RequirementStrictness.EQUIVALENT_ALLOWED);
        setField(requirement, "id", UUID.randomUUID());
        setField(requirement, "state", RequirementState.CONFIRMED);
        setField(requirement, "createdAt", Instant.now());
        setField(requirement, "updatedAt", Instant.now());
        return requirement;
    }

    private static Student newStudent(String firstName) {
        Student student = new Student(UUID.randomUUID(), firstName);
        setField(student, "id", UUID.randomUUID());
        setField(student, "createdAt", Instant.now());
        return student;
    }

    private Membership newMembership(Student student, Instant createdAt) {
        Classroom classroom = new Classroom(UUID.randomUUID(), "Grade 1", "Ms. Smith", null, null);
        setField(classroom, "id", classroomId);
        Membership membership = new Membership(classroom, UUID.randomUUID(), student, MembershipRole.PARENT, false);
        setField(membership, "id", UUID.randomUUID());
        setField(membership, "createdAt", createdAt);
        return membership;
    }

    private static ParentInventory newInventory(UUID requirementId, UUID studentId, int ownedQuantity) {
        ParentInventory inventory = new ParentInventory(requirementId, studentId, UUID.randomUUID(), ownedQuantity);
        setField(inventory, "id", UUID.randomUUID());
        setField(inventory, "updatedAt", Instant.now());
        return inventory;
    }

    private static ProductOffer newProductOffer(UUID requirementId, int packQuantity) {
        ProductOffer offer = new ProductOffer(requirementId, "Amazon", packQuantity, 100, 0, null);
        setField(offer, "id", UUID.randomUUID());
        setField(offer, "createdAt", Instant.now());
        return offer;
    }

    private static Contribution newContribution(UUID requirementId, int quantity, ContributionState state) {
        Contribution contribution = new Contribution(requirementId, UUID.randomUUID(), quantity,
                ContributionMode.DONATE);
        setField(contribution, "id", UUID.randomUUID());
        setField(contribution, "state", state);
        setField(contribution, "createdAt", Instant.now());
        setField(contribution, "updatedAt", Instant.now());
        return contribution;
    }

    private record StubQuantityTotal(UUID requirementId, long total)
            implements ContributionRepository.RequirementQuantityTotal {
        @Override
        public UUID getRequirementId() {
            return requirementId;
        }

        @Override
        public long getTotal() {
            return total;
        }
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            var field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
