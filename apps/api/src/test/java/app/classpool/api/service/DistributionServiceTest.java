package app.classpool.api.service;

import app.classpool.api.domain.AllocationLine;
import app.classpool.api.domain.AllocationStatus;
import app.classpool.api.domain.Classroom;
import app.classpool.api.domain.ClassReserveEntry;
import app.classpool.api.domain.DistributionBatch;
import app.classpool.api.domain.DistributionItem;
import app.classpool.api.domain.DistributionMode;
import app.classpool.api.domain.Membership;
import app.classpool.api.domain.MembershipRole;
import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.PurchasePlan;
import app.classpool.api.domain.PurchasePlanLine;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementStrictness;
import app.classpool.api.domain.Student;
import app.classpool.api.dto.ClassReserveEntryResponse;
import app.classpool.api.dto.DistributionItemResponse;
import app.classpool.api.dto.DistributionSummaryResponse;
import app.classpool.api.dto.GenerateDistributionRequest;
import app.classpool.api.dto.HouseholdPickListResponse;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.AllocationLineRepository;
import app.classpool.api.repository.AppUserRepository;
import app.classpool.api.repository.ClassReserveEntryRepository;
import app.classpool.api.repository.DistributionBatchRepository;
import app.classpool.api.repository.DistributionItemRepository;
import app.classpool.api.repository.HouseholdRepository;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.OrderRepository;
import app.classpool.api.repository.PoolRepository;
import app.classpool.api.repository.PurchasePlanLineRepository;
import app.classpool.api.repository.PurchasePlanRepository;
import app.classpool.api.repository.RequirementRepository;
import app.classpool.api.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit coverage of {@link DistributionService} — the skip-fully-self-fulfilled distribution-item
 * rule, waste-to-class-reserve creation, the cross-sibling household pick-list aggregation, the
 * "mine" privacy filter, and every 403/409 gate.
 */
@ExtendWith(MockitoExtension.class)
class DistributionServiceTest {

    @Mock
    private DistributionBatchRepository distributionBatchRepository;
    @Mock
    private DistributionItemRepository distributionItemRepository;
    @Mock
    private ClassReserveEntryRepository classReserveEntryRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PurchasePlanRepository purchasePlanRepository;
    @Mock
    private PurchasePlanLineRepository purchasePlanLineRepository;
    @Mock
    private AllocationLineRepository allocationLineRepository;
    @Mock
    private RequirementRepository requirementRepository;
    @Mock
    private StudentRepository studentRepository;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private HouseholdRepository householdRepository;
    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private PoolRepository poolRepository;

    private DistributionService distributionService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID classroomId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PoolAssembler poolAssembler = new PoolAssembler(requirementRepository);
        RequirementAssembler requirementAssembler = new RequirementAssembler();
        PoolService poolService = new PoolService(poolRepository, requirementRepository, membershipRepository,
                poolAssembler, requirementAssembler);
        distributionService = new DistributionService(distributionBatchRepository, distributionItemRepository,
                classReserveEntryRepository, orderRepository, purchasePlanRepository, purchasePlanLineRepository,
                allocationLineRepository, requirementRepository, studentRepository, membershipRepository,
                householdRepository, appUserRepository, poolService);
    }

    // ---- generateDistribution: gates ----

    @Test
    void generateDistribution_throwsForbidden_whenCallerIsNotOrganizer() {
        Pool pool = newPool(PoolState.ORDERED);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> distributionService.generateDistribution(callerId, pool.getId(),
                new GenerateDistributionRequest("CLASSROOM_DESK")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void generateDistribution_throwsConflict_whenPoolIsNotOrdered() {
        Pool pool = newPool(PoolState.DISTRIBUTING);
        stubOrganizer(pool);

        assertThatThrownBy(() -> distributionService.generateDistribution(callerId, pool.getId(),
                new GenerateDistributionRequest("CLASSROOM_DESK")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void generateDistribution_throwsConflict_whenNoOrderHasBeenRecordedYet() {
        Pool pool = newPool(PoolState.ORDERED);
        stubOrganizer(pool);
        when(orderRepository.existsByPoolId(pool.getId())).thenReturn(false);

        assertThatThrownBy(() -> distributionService.generateDistribution(callerId, pool.getId(),
                new GenerateDistributionRequest("CLASSROOM_DESK")))
                .isInstanceOf(ConflictException.class);
        verify(distributionBatchRepository, never()).save(any());
    }

    @Test
    void generateDistribution_throwsConflict_whenABatchAlreadyExists() {
        Pool pool = newPool(PoolState.ORDERED);
        stubOrganizer(pool);
        when(orderRepository.existsByPoolId(pool.getId())).thenReturn(true);
        when(distributionBatchRepository.existsByPoolId(pool.getId())).thenReturn(true);

        assertThatThrownBy(() -> distributionService.generateDistribution(callerId, pool.getId(),
                new GenerateDistributionRequest("CLASSROOM_DESK")))
                .isInstanceOf(ConflictException.class);
        verify(distributionBatchRepository, never()).save(any());
    }

    // ---- generateDistribution: item/waste creation ----

    @Test
    void generateDistribution_createsOneItemPerNonZeroLine_skippingFullySelfFulfilledLines() {
        Pool pool = newPool(PoolState.ORDERED);
        Requirement pencils = newRequirement(pool.getId(), "Pencils");
        stubOrganizer(pool);
        when(orderRepository.existsByPoolId(pool.getId())).thenReturn(true);
        when(distributionBatchRepository.existsByPoolId(pool.getId())).thenReturn(false);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(pencils));

        UUID studentSelfFulfilled = UUID.randomUUID();
        UUID studentNeedsHandoff = UUID.randomUUID();
        when(allocationLineRepository.findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(
                List.of(pencils.getId())))
                .thenReturn(List.of(
                        // Fully self-fulfilled from household inventory — both pool/purchase are 0.
                        new AllocationLine(pencils.getId(), studentSelfFulfilled, 4, 4, 0, 0,
                                AllocationStatus.SELF_FULFILLED),
                        // Needs a physical hand-off: 1 from the pool + 3 purchased = 4.
                        new AllocationLine(pencils.getId(), studentNeedsHandoff, 4, 0, 1, 3,
                                AllocationStatus.PURCHASE_REQUIRED)));
        when(purchasePlanRepository.findByPoolId(pool.getId())).thenReturn(Optional.empty());

        when(distributionBatchRepository.save(any(DistributionBatch.class))).thenAnswer(inv -> {
            DistributionBatch batch = inv.getArgument(0);
            setField(batch, "id", UUID.randomUUID());
            setField(batch, "createdAt", Instant.now());
            return batch;
        });

        ArgumentCaptor<List<DistributionItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        when(distributionItemRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(distributionItemRepository.findByDistributionBatchIdOrderByRequirementIdAscStudentIdAsc(any()))
                .thenReturn(List.of()); // response assembly re-query not the focus of this test

        distributionService.generateDistribution(callerId, pool.getId(), new GenerateDistributionRequest("CLASSROOM_DESK"));

        verify(distributionItemRepository).saveAll(itemsCaptor.capture());
        List<DistributionItem> saved = itemsCaptor.getValue();
        assertThat(saved).hasSize(1); // the self-fulfilled line was skipped
        assertThat(saved.get(0).getStudentId()).isEqualTo(studentNeedsHandoff);
        assertThat(saved.get(0).getQuantity()).isEqualTo(4); // 1 pool + 3 purchase
        assertThat(pool.getState()).isEqualTo(PoolState.DISTRIBUTING);
    }

    @Test
    void generateDistribution_createsClassReserveEntry_fromPlanLineWaste() {
        Pool pool = newPool(PoolState.ORDERED);
        Requirement pencils = newRequirement(pool.getId(), "Pencils");
        stubOrganizer(pool);
        when(orderRepository.existsByPoolId(pool.getId())).thenReturn(true);
        when(distributionBatchRepository.existsByPoolId(pool.getId())).thenReturn(false);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(pencils));
        when(allocationLineRepository.findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(
                List.of(pencils.getId()))).thenReturn(List.of());

        PurchasePlan plan = new PurchasePlan(pool.getId());
        setField(plan, "id", UUID.randomUUID());
        when(purchasePlanRepository.findByPoolId(pool.getId())).thenReturn(Optional.of(plan));
        PurchasePlanLine wastefulLine = new PurchasePlanLine(plan.getId(), pencils.getId(), UUID.randomUUID(), 2,
                900, 16); // 16 units of waste
        when(purchasePlanLineRepository.findByPurchasePlanIdOrderByRequirementIdAscProductOfferIdAsc(plan.getId()))
                .thenReturn(List.of(wastefulLine));

        when(distributionBatchRepository.save(any(DistributionBatch.class))).thenAnswer(inv -> {
            DistributionBatch batch = inv.getArgument(0);
            setField(batch, "id", UUID.randomUUID());
            setField(batch, "createdAt", Instant.now());
            return batch;
        });
        when(distributionItemRepository.findByDistributionBatchIdOrderByRequirementIdAscStudentIdAsc(any()))
                .thenReturn(List.of());

        ArgumentCaptor<List<ClassReserveEntry>> reserveCaptor = ArgumentCaptor.forClass(List.class);
        when(classReserveEntryRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        distributionService.generateDistribution(callerId, pool.getId(), new GenerateDistributionRequest("HOUSEHOLD_BAG"));

        verify(classReserveEntryRepository).saveAll(reserveCaptor.capture());
        assertThat(reserveCaptor.getValue()).hasSize(1);
        ClassReserveEntry entry = reserveCaptor.getValue().get(0);
        assertThat(entry.getClassroomId()).isEqualTo(classroomId);
        assertThat(entry.getItemName()).isEqualTo("Pencils");
        assertThat(entry.getQuantity()).isEqualTo(16);
        assertThat(entry.getCustodianLocation()).isNull(); // V1 gap — never set
    }

    // ---- getDistribution ----

    @Test
    void getDistribution_throwsConflict_whenNoBatchGeneratedYet() {
        Pool pool = newPool(PoolState.ORDERED);
        stubOrganizer(pool);
        when(distributionBatchRepository.findByPoolId(pool.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> distributionService.getDistribution(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void getDistribution_pickLists_sumQuantityPerRequirement_acrossSiblingsInTheSameHousehold() {
        Pool pool = newPool(PoolState.DISTRIBUTING);
        stubOrganizer(pool);
        DistributionBatch batch = newBatch(pool.getId());
        when(distributionBatchRepository.findByPoolId(pool.getId())).thenReturn(Optional.of(batch));

        UUID householdId = UUID.randomUUID();
        Student kid1 = newStudent(householdId, "Alex");
        Student kid2 = newStudent(householdId, "Bailey"); // sibling, same household
        Requirement pencils = newRequirement(pool.getId(), "Pencils");

        DistributionItem item1 = new DistributionItem(batch.getId(), kid1.getId(), pencils.getId(), 2);
        DistributionItem item2 = new DistributionItem(batch.getId(), kid2.getId(), pencils.getId(), 2);
        setField(item1, "id", UUID.randomUUID());
        setField(item2, "id", UUID.randomUUID());
        when(distributionItemRepository.findByDistributionBatchIdOrderByRequirementIdAscStudentIdAsc(batch.getId()))
                .thenReturn(List.of(item1, item2));
        when(requirementRepository.findAllById(any())).thenReturn(List.of(pencils));
        when(studentRepository.findAllById(any())).thenReturn(List.of(kid1, kid2));
        when(householdRepository.findAllById(any())).thenReturn(List.of());

        DistributionSummaryResponse summary = distributionService.getDistribution(callerId, pool.getId());

        assertThat(summary.items()).hasSize(2);
        assertThat(summary.pickLists()).hasSize(1);
        HouseholdPickListResponse pickList = summary.pickLists().get(0);
        assertThat(pickList.householdId()).isEqualTo(householdId);
        assertThat(pickList.lines()).hasSize(1); // one line, not two — summed across siblings
        assertThat(pickList.lines().get(0).requirementName()).isEqualTo("Pencils");
        assertThat(pickList.lines().get(0).quantity()).isEqualTo(4); // 2 + 2
    }

    // ---- getMyDistribution ----

    @Test
    void getMyDistribution_returnsEmptyList_whenNoBatchGeneratedYet() {
        Pool pool = newPool(PoolState.ORDERED);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(true);
        when(distributionBatchRepository.findByPoolId(pool.getId())).thenReturn(Optional.empty());

        List<DistributionItemResponse> items = distributionService.getMyDistribution(callerId, pool.getId());

        assertThat(items).isEmpty();
    }

    @Test
    void getMyDistribution_returnsOnlyTheCallersOwnStudentsItems() {
        Pool pool = newPool(PoolState.DISTRIBUTING);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(true);
        DistributionBatch batch = newBatch(pool.getId());
        when(distributionBatchRepository.findByPoolId(pool.getId())).thenReturn(Optional.of(batch));

        Student ownStudent = newStudent(UUID.randomUUID(), "Alex");
        Membership ownMembership = newMembership(ownStudent);
        when(membershipRepository.findByClassroom_IdAndParentUserId(classroomId, callerId))
                .thenReturn(List.of(ownMembership));

        Requirement pencils = newRequirement(pool.getId(), "Pencils");
        DistributionItem myItem = new DistributionItem(batch.getId(), ownStudent.getId(), pencils.getId(), 4);
        setField(myItem, "id", UUID.randomUUID());
        when(distributionItemRepository.findByDistributionBatchIdAndStudentIdInOrderByRequirementIdAscStudentIdAsc(
                batch.getId(), List.of(ownStudent.getId()))).thenReturn(List.of(myItem));
        when(requirementRepository.findAllById(any())).thenReturn(List.of(pencils));
        when(studentRepository.findAllById(List.of(ownStudent.getId()))).thenReturn(List.of(ownStudent));

        List<DistributionItemResponse> items = distributionService.getMyDistribution(callerId, pool.getId());

        assertThat(items).hasSize(1);
        assertThat(items.get(0).studentFirstName()).isEqualTo("Alex");
        assertThat(items.get(0).requirementName()).isEqualTo("Pencils");
        assertThat(items.get(0).quantity()).isEqualTo(4);
    }

    // ---- markDistributionItemDelivered ----

    @Test
    void markDistributionItemDelivered_throwsConflict_whenAlreadyDelivered() {
        Pool pool = newPool(PoolState.DISTRIBUTING);
        stubOrganizer(pool);
        DistributionBatch batch = newBatch(pool.getId());
        when(distributionBatchRepository.findByPoolId(pool.getId())).thenReturn(Optional.of(batch));
        DistributionItem item = new DistributionItem(batch.getId(), UUID.randomUUID(), UUID.randomUUID(), 2);
        setField(item, "id", UUID.randomUUID());
        item.markDelivered();
        when(distributionItemRepository.findByIdAndDistributionBatchId(item.getId(), batch.getId()))
                .thenReturn(Optional.of(item));

        assertThatThrownBy(() -> distributionService.markDistributionItemDelivered(callerId, pool.getId(),
                item.getId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void markDistributionItemDelivered_setsDeliveredAt_whenStillPending() {
        Pool pool = newPool(PoolState.DISTRIBUTING);
        stubOrganizer(pool);
        DistributionBatch batch = newBatch(pool.getId());
        when(distributionBatchRepository.findByPoolId(pool.getId())).thenReturn(Optional.of(batch));
        DistributionItem item = new DistributionItem(batch.getId(), UUID.randomUUID(), UUID.randomUUID(), 2);
        setField(item, "id", UUID.randomUUID());
        when(distributionItemRepository.findByIdAndDistributionBatchId(item.getId(), batch.getId()))
                .thenReturn(Optional.of(item));
        when(distributionItemRepository.save(any(DistributionItem.class))).thenAnswer(inv -> inv.getArgument(0));

        DistributionItemResponse response = distributionService.markDistributionItemDelivered(callerId, pool.getId(),
                item.getId());

        assertThat(response.deliveredAt()).isNotNull();
    }

    @Test
    void markDistributionItemDelivered_throwsNotFound_forAnItemFromAnotherBatch() {
        Pool pool = newPool(PoolState.DISTRIBUTING);
        stubOrganizer(pool);
        DistributionBatch batch = newBatch(pool.getId());
        when(distributionBatchRepository.findByPoolId(pool.getId())).thenReturn(Optional.of(batch));
        UUID otherItemId = UUID.randomUUID();
        when(distributionItemRepository.findByIdAndDistributionBatchId(otherItemId, batch.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> distributionService.markDistributionItemDelivered(callerId, pool.getId(),
                otherItemId))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- getClassReserve ----

    @Test
    void getClassReserve_returnsEveryEntryForThePoolsClassroom() {
        Pool pool = newPool(PoolState.DISTRIBUTING);
        stubOrganizer(pool);
        ClassReserveEntry entry = new ClassReserveEntry(classroomId, "Pencils", 16);
        setField(entry, "id", UUID.randomUUID());
        setField(entry, "createdAt", Instant.now());
        when(classReserveEntryRepository.findByClassroomIdOrderByCreatedAtAsc(classroomId))
                .thenReturn(List.of(entry));

        List<ClassReserveEntryResponse> entries = distributionService.getClassReserve(callerId, pool.getId());

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).itemName()).isEqualTo("Pencils");
        assertThat(entries.get(0).quantity()).isEqualTo(16);
        assertThat(entries.get(0).custodianLocation()).isNull();
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

    private static Requirement newRequirement(UUID poolId, String name) {
        Requirement requirement = new Requirement(poolId, name, 1, null, RequirementStrictness.EQUIVALENT_ALLOWED);
        setField(requirement, "id", UUID.randomUUID());
        setField(requirement, "createdAt", Instant.now());
        setField(requirement, "updatedAt", Instant.now());
        return requirement;
    }

    private static Student newStudent(UUID householdId, String firstName) {
        Student student = new Student(householdId, firstName);
        setField(student, "id", UUID.randomUUID());
        setField(student, "createdAt", Instant.now());
        return student;
    }

    private DistributionBatch newBatch(UUID poolId) {
        DistributionBatch batch = new DistributionBatch(poolId, DistributionMode.CLASSROOM_DESK);
        setField(batch, "id", UUID.randomUUID());
        setField(batch, "createdAt", Instant.now());
        return batch;
    }

    private Membership newMembership(Student student) {
        Classroom classroom = new Classroom(UUID.randomUUID(), "Grade 1", "Ms. Smith", null, null);
        setField(classroom, "id", classroomId);
        Membership membership = new Membership(classroom, callerId, student, MembershipRole.PARENT, false);
        setField(membership, "id", UUID.randomUUID());
        setField(membership, "createdAt", Instant.now());
        return membership;
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
