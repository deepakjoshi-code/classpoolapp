package app.classpool.api.service;

import app.classpool.api.domain.Classroom;
import app.classpool.api.domain.Membership;
import app.classpool.api.domain.MembershipRole;
import app.classpool.api.domain.ParentInventory;
import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementState;
import app.classpool.api.domain.RequirementStrictness;
import app.classpool.api.domain.Student;
import app.classpool.api.dto.InventoryLineResponse;
import app.classpool.api.dto.InventorySummaryResponse;
import app.classpool.api.dto.SetInventoryRequest;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.ParentInventoryRepository;
import app.classpool.api.repository.PoolRepository;
import app.classpool.api.repository.RequirementRepository;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private ParentInventoryRepository parentInventoryRepository;
    @Mock
    private RequirementRepository requirementRepository;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private PoolRepository poolRepository;

    private InventoryService inventoryService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID classroomId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // PoolService/RequirementAssembler exercised for real (same pattern as
        // RequirementServiceTest/PoolServiceTest) — only their repository collaborators are mocked.
        PoolAssembler poolAssembler = new PoolAssembler(requirementRepository);
        RequirementAssembler requirementAssembler = new RequirementAssembler();
        PoolService poolService = new PoolService(poolRepository, requirementRepository, membershipRepository,
                poolAssembler, requirementAssembler);
        inventoryService = new InventoryService(parentInventoryRepository, requirementRepository,
                membershipRepository, poolService, requirementAssembler);
    }

    // ---- setInventory (upsert) ----

    @Test
    void setInventory_createsANewRow_whenNoneExistsYet() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_INVENTORY);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Student student = newStudent("Alex");
        Membership membership = newMembership(callerId, student);

        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.findByClassroom_IdAndParentUserIdAndStudent_Id(
                classroomId, callerId, student.getId())).thenReturn(Optional.of(membership));
        when(requirementRepository.findByIdAndPoolId(requirement.getId(), pool.getId()))
                .thenReturn(Optional.of(requirement));
        when(parentInventoryRepository.findByRequirementIdAndStudentId(requirement.getId(), student.getId()))
                .thenReturn(Optional.empty());
        when(parentInventoryRepository.save(any(ParentInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryLineResponse response = inventoryService.setInventory(callerId, pool.getId(), requirement.getId(),
                new SetInventoryRequest(student.getId(), 3));

        assertThat(response.ownedQuantity()).isEqualTo(3);
        assertThat(response.stillNeeded()).isEqualTo(1);
        assertThat(response.studentId()).isEqualTo(student.getId());
        assertThat(response.studentFirstName()).isEqualTo("Alex");

        ArgumentCaptor<ParentInventory> captor = ArgumentCaptor.forClass(ParentInventory.class);
        verify(parentInventoryRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull(); // a brand new row, never persisted before
        assertThat(captor.getValue().getOwnedQuantity()).isEqualTo(3);
        assertThat(captor.getValue().getParentUserId()).isEqualTo(callerId);
    }

    @Test
    void setInventory_updatesTheExistingRow_whenOneAlreadyExists() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_INVENTORY);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Student student = newStudent("Alex");
        Membership membership = newMembership(callerId, student);
        ParentInventory existing = newParentInventory(requirement.getId(), student.getId(), 1);

        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.findByClassroom_IdAndParentUserIdAndStudent_Id(
                classroomId, callerId, student.getId())).thenReturn(Optional.of(membership));
        when(requirementRepository.findByIdAndPoolId(requirement.getId(), pool.getId()))
                .thenReturn(Optional.of(requirement));
        when(parentInventoryRepository.findByRequirementIdAndStudentId(requirement.getId(), student.getId()))
                .thenReturn(Optional.of(existing));
        when(parentInventoryRepository.save(any(ParentInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryLineResponse response = inventoryService.setInventory(callerId, pool.getId(), requirement.getId(),
                new SetInventoryRequest(student.getId(), 4));

        assertThat(response.ownedQuantity()).isEqualTo(4);
        assertThat(response.stillNeeded()).isZero();

        ArgumentCaptor<ParentInventory> captor = ArgumentCaptor.forClass(ParentInventory.class);
        verify(parentInventoryRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing); // updated in place, not a second row
        assertThat(captor.getValue().getOwnedQuantity()).isEqualTo(4);
    }

    @Test
    void setInventory_clampsToZero_whenRequestedQuantityIsNegative() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_INVENTORY);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Student student = newStudent("Alex");
        Membership membership = newMembership(callerId, student);

        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.findByClassroom_IdAndParentUserIdAndStudent_Id(
                classroomId, callerId, student.getId())).thenReturn(Optional.of(membership));
        when(requirementRepository.findByIdAndPoolId(requirement.getId(), pool.getId()))
                .thenReturn(Optional.of(requirement));
        when(parentInventoryRepository.findByRequirementIdAndStudentId(requirement.getId(), student.getId()))
                .thenReturn(Optional.empty());
        when(parentInventoryRepository.save(any(ParentInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryLineResponse response = inventoryService.setInventory(callerId, pool.getId(), requirement.getId(),
                new SetInventoryRequest(student.getId(), -5));

        assertThat(response.ownedQuantity()).isZero();
        assertThat(response.stillNeeded()).isEqualTo(4);
    }

    @Test
    void setInventory_clampsToQuantityPerStudent_whenRequestedQuantityExceedsIt() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_INVENTORY);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Student student = newStudent("Alex");
        Membership membership = newMembership(callerId, student);

        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.findByClassroom_IdAndParentUserIdAndStudent_Id(
                classroomId, callerId, student.getId())).thenReturn(Optional.of(membership));
        when(requirementRepository.findByIdAndPoolId(requirement.getId(), pool.getId()))
                .thenReturn(Optional.of(requirement));
        when(parentInventoryRepository.findByRequirementIdAndStudentId(requirement.getId(), student.getId()))
                .thenReturn(Optional.empty());
        when(parentInventoryRepository.save(any(ParentInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryLineResponse response = inventoryService.setInventory(callerId, pool.getId(), requirement.getId(),
                new SetInventoryRequest(student.getId(), 99));

        // Owning more than required doesn't get recorded as more (surplus offering is a separate,
        // later action per PRD §5) — clamped to quantityPerStudent, not the raw 99.
        assertThat(response.ownedQuantity()).isEqualTo(4);
        assertThat(response.stillNeeded()).isZero();
    }

    @Test
    void setInventory_throwsForbidden_whenStudentIdIsNotTheCallersOwnChildInThisClassroom() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_INVENTORY);
        UUID someoneElsesStudentId = UUID.randomUUID();
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.findByClassroom_IdAndParentUserIdAndStudent_Id(
                classroomId, callerId, someoneElsesStudentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.setInventory(callerId, pool.getId(), UUID.randomUUID(),
                new SetInventoryRequest(someoneElsesStudentId, 2)))
                .isInstanceOf(ForbiddenException.class);
        verify(parentInventoryRepository, never()).save(any());
    }

    @Test
    void setInventory_throwsConflict_whenPoolIsStillDraft() {
        Pool pool = newPool(classroomId, PoolState.DRAFT);
        Student student = newStudent("Alex");
        Membership membership = newMembership(callerId, student);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.findByClassroom_IdAndParentUserIdAndStudent_Id(
                classroomId, callerId, student.getId())).thenReturn(Optional.of(membership));

        assertThatThrownBy(() -> inventoryService.setInventory(callerId, pool.getId(), UUID.randomUUID(),
                new SetInventoryRequest(student.getId(), 2)))
                .isInstanceOf(ConflictException.class);
        verify(parentInventoryRepository, never()).save(any());
    }

    // ---- getMyInventory ----

    @Test
    void getMyInventory_returnsEmptyList_forAStillDraftPool() {
        Pool pool = newPool(classroomId, PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(true);

        List<InventoryLineResponse> lines = inventoryService.getMyInventory(callerId, pool.getId());

        assertThat(lines).isEmpty();
        verifyNoInteractions(parentInventoryRepository);
    }

    @Test
    void getMyInventory_throwsForbidden_whenCallerHasNoMembershipOnThePoolsClassroom() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_INVENTORY);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> inventoryService.getMyInventory(callerId, pool.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getMyInventory_returnsOneLinePerStudent_forACallerWithTwoStudentsInTheClassroom() {
        // Twins scenario (PRD §4 update): a single household can have more than one student in the
        // same classroom, so this is a cross join of requirements x the caller's own students.
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_INVENTORY);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Student twinA = newStudent("Alex");
        Student twinB = newStudent("Sam");
        Membership membershipA = newMembership(callerId, twinA);
        Membership membershipB = newMembership(callerId, twinB);

        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(membershipRepository.findByClassroom_IdAndParentUserId(classroomId, callerId))
                .thenReturn(List.of(membershipA, membershipB));
        // Only twinA has recorded anything so far — twinB must still show up, defaulted to 0.
        when(parentInventoryRepository.findByRequirementIdInAndStudentIdIn(
                List.of(requirement.getId()), List.of(twinA.getId(), twinB.getId())))
                .thenReturn(List.of(newParentInventory(requirement.getId(), twinA.getId(), 2)));

        List<InventoryLineResponse> lines = inventoryService.getMyInventory(callerId, pool.getId());

        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(InventoryLineResponse::studentFirstName, InventoryLineResponse::ownedQuantity,
                        InventoryLineResponse::stillNeeded)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Alex", 2, 2),
                        org.assertj.core.groups.Tuple.tuple("Sam", 0, 4));
    }

    // ---- getSummary ----

    @Test
    void getSummary_throwsForbidden_whenCallerIsNotAnOrganizer() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_INVENTORY);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> inventoryService.getSummary(callerId, pool.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getSummary_aggregatesStudentsSubmittedAndTotalOwnedPerRequirement() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_INVENTORY);
        pool.setConfirmedStudentCount(3);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        setField(requirement, "state", RequirementState.CONFIRMED);

        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(membershipRepository.countDistinctStudentsByClassroom_Id(classroomId)).thenReturn(3L);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(parentInventoryRepository.countDistinctStudentsByRequirementIdIn(List.of(requirement.getId())))
                .thenReturn(2L);
        when(parentInventoryRepository.sumOwnedQuantityByRequirementIdIn(List.of(requirement.getId())))
                .thenReturn(List.of(new StubRequirementOwnedTotal(requirement.getId(), 5L)));

        InventorySummaryResponse summary = inventoryService.getSummary(callerId, pool.getId());

        assertThat(summary.studentsWithInventorySubmitted()).isEqualTo(2);
        assertThat(summary.totalJoinedStudents()).isEqualTo(3);
        assertThat(summary.perRequirement()).hasSize(1);
        assertThat(summary.perRequirement().get(0).requirementName()).isEqualTo("Glue Sticks");
        assertThat(summary.perRequirement().get(0).totalOwned()).isEqualTo(5);
        assertThat(summary.perRequirement().get(0).totalRequired()).isEqualTo(12); // 4 x 3 confirmed students
    }

    private record StubRequirementOwnedTotal(UUID requirementId, long total)
            implements ParentInventoryRepository.RequirementOwnedTotal {
        @Override
        public UUID getRequirementId() {
            return requirementId;
        }

        @Override
        public long getTotal() {
            return total;
        }
    }

    private static Pool newPool(UUID classroomId, PoolState state) {
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

    private Membership newMembership(UUID parentUserId, Student student) {
        Classroom classroom = new Classroom(UUID.randomUUID(), "Grade 1", "Ms. Smith", null, null);
        setField(classroom, "id", classroomId);
        Membership membership = new Membership(classroom, parentUserId, student, MembershipRole.PARENT, false);
        setField(membership, "id", UUID.randomUUID());
        setField(membership, "createdAt", Instant.now());
        return membership;
    }

    private static ParentInventory newParentInventory(UUID requirementId, UUID studentId, int ownedQuantity) {
        ParentInventory inventory = new ParentInventory(requirementId, studentId, UUID.randomUUID(), ownedQuantity);
        setField(inventory, "id", UUID.randomUUID());
        setField(inventory, "updatedAt", Instant.now());
        return inventory;
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
