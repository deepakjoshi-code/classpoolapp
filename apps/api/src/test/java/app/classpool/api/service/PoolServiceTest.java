package app.classpool.api.service;

import app.classpool.api.domain.Classroom;
import app.classpool.api.domain.Membership;
import app.classpool.api.domain.MembershipRole;
import app.classpool.api.domain.NotificationType;
import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementState;
import app.classpool.api.domain.RequirementStrictness;
import app.classpool.api.dto.CreatePoolRequest;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.dto.PoolResponse;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.PoolRepository;
import app.classpool.api.repository.RequirementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PoolServiceTest {

    @Mock
    private PoolRepository poolRepository;
    @Mock
    private RequirementRepository requirementRepository;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private NotificationService notificationService;

    private PoolService poolService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID classroomId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // PoolAssembler/RequirementAssembler are exercised for real (same pattern as
        // ClassroomServiceTest exercising ClassroomAssembler) — only their repository
        // collaborators are mocked.
        PoolAssembler poolAssembler = new PoolAssembler(requirementRepository);
        RequirementAssembler requirementAssembler = new RequirementAssembler();
        poolService = new PoolService(poolRepository, requirementRepository, membershipRepository, poolAssembler,
                requirementAssembler, notificationService);
        lenient().when(requirementRepository.countByPoolIdIn(anyList())).thenReturn(List.of());
    }

    @Test
    void create_throwsForbidden_whenCallerIsNotAnOrganizer() {
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> poolService.create(callerId, classroomId, new CreatePoolRequest("Fall Supplies", null)))
                .isInstanceOf(ForbiddenException.class);

        verify(poolRepository, never()).save(any());
    }

    @Test
    void create_startsInDraft_forAnOrganizer() {
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(poolRepository.save(any(Pool.class))).thenAnswer(inv -> {
            Pool pool = inv.getArgument(0);
            setId(pool, UUID.randomUUID());
            setCreatedAt(pool);
            return pool;
        });

        PoolResponse response = poolService.create(callerId, classroomId, new CreatePoolRequest("Fall Supplies", null));

        assertThat(response.state()).isEqualTo("DRAFT");
        assertThat(response.poolType()).isEqualTo("SUPPLIES");
        assertThat(response.name()).isEqualTo("Fall Supplies");
    }

    @Test
    void getForCaller_throwsForbidden_whenCallerHasNoMembershipOnThePoolsClassroom() {
        Pool pool = newPool(classroomId, PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> poolService.getForCaller(callerId, pool.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getForCaller_throwsNotFound_whenPoolDoesNotExist() {
        UUID missingId = UUID.randomUUID();
        when(poolRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> poolService.getForCaller(callerId, missingId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void confirm_throwsConflict_whenPoolHasZeroRequirements() {
        Pool pool = newPool(classroomId, PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> poolService.confirm(callerId, pool.getId())).isInstanceOf(ConflictException.class);
        assertThat(pool.getState()).isEqualTo(PoolState.DRAFT);
    }

    @Test
    void confirm_throwsConflict_whenPoolAlreadyPastDraft() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_INVENTORY);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);

        assertThatThrownBy(() -> poolService.confirm(callerId, pool.getId())).isInstanceOf(ConflictException.class);
    }

    @Test
    void confirm_throwsForbidden_whenCallerIsNotAnOrganizer() {
        Pool pool = newPool(classroomId, PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> poolService.confirm(callerId, pool.getId())).isInstanceOf(ForbiddenException.class);
        verify(requirementRepository, never()).saveAll(any());
    }

    @Test
    void confirm_movesRequirementsToConfirmed_poolToOpenForInventory_andComputesTotalDemand() {
        Pool pool = newPool(classroomId, PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);

        Requirement glueSticks = newRequirement(pool.getId(), "Glue Sticks", 4, RequirementState.EXTRACTED);
        Requirement folders = newRequirement(pool.getId(), "Folders", 2, RequirementState.NEEDS_REVIEW);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId()))
                .thenReturn(List.of(glueSticks, folders));
        when(requirementRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(poolRepository.save(any(Pool.class))).thenAnswer(inv -> inv.getArgument(0));

        // 3 joined students — quantityPerStudent 4 -> totalDemand 12 (the example in the task spec).
        when(membershipRepository.countDistinctStudentsByClassroom_Id(classroomId)).thenReturn(3L);

        PoolDetailResponse response = poolService.confirm(callerId, pool.getId());

        assertThat(pool.getState()).isEqualTo(PoolState.OPEN_FOR_INVENTORY);
        assertThat(glueSticks.getState()).isEqualTo(RequirementState.CONFIRMED);
        assertThat(folders.getState()).isEqualTo(RequirementState.CONFIRMED);
        assertThat(response.state()).isEqualTo("OPEN_FOR_INVENTORY");
        assertThat(response.requirements()).hasSize(2);
        assertThat(response.requirements()).extracting("totalDemand").containsExactly(12, 6);
    }

    // ---- complete (Phase 10) ----

    @Test
    void complete_throwsForbidden_whenCallerIsNotAnOrganizer() {
        Pool pool = newPool(classroomId, PoolState.DISTRIBUTING);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> poolService.complete(callerId, pool.getId())).isInstanceOf(ForbiddenException.class);
        verify(poolRepository, never()).save(any());
    }

    @Test
    void complete_throwsConflict_whenPoolIsNotDistributing() {
        Pool pool = newPool(classroomId, PoolState.ORDERED);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);

        assertThatThrownBy(() -> poolService.complete(callerId, pool.getId())).isInstanceOf(ConflictException.class);
        assertThat(pool.getState()).isEqualTo(PoolState.ORDERED);
    }

    @Test
    void complete_movesDistributingToCompleted_withoutRequiringEveryItemDelivered() {
        Pool pool = newPool(classroomId, PoolState.DISTRIBUTING);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of());
        when(poolRepository.save(any(Pool.class))).thenAnswer(inv -> inv.getArgument(0));
        when(membershipRepository.findByClassroom_IdAndStudentIsNotNullOrderByCreatedAtAsc(classroomId))
                .thenReturn(List.of());

        PoolDetailResponse response = poolService.complete(callerId, pool.getId());

        assertThat(pool.getState()).isEqualTo(PoolState.COMPLETED);
        assertThat(response.state()).isEqualTo("COMPLETED");
    }

    /**
     * Phase 12: {@code complete} still transitions/returns exactly as above, and now additionally
     * emits one {@code POOL_COMPLETED} notification per distinct parent with a
     * participating-student Membership on the classroom — every household in the class, not just
     * ones that owed a purchase payment (no {@code Payment}/allocation data is even read here).
     */
    @Test
    void complete_notifiesEveryDistinctParentOnTheClassroom() {
        Pool pool = newPool(classroomId, PoolState.DISTRIBUTING);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of());
        when(poolRepository.save(any(Pool.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID parent1 = UUID.randomUUID();
        UUID parent2 = UUID.randomUUID();
        // parent1 has two Memberships (e.g. two kids in the class) — must be notified only once.
        when(membershipRepository.findByClassroom_IdAndStudentIsNotNullOrderByCreatedAtAsc(classroomId))
                .thenReturn(List.of(newMembership(parent1), newMembership(parent1), newMembership(parent2)));

        poolService.complete(callerId, pool.getId());

        verify(notificationService, times(1)).notify(eq(parent1), eq(NotificationType.POOL_COMPLETED),
                eq(pool.getId()), contains("Fall Supplies"));
        verify(notificationService, times(1)).notify(eq(parent2), eq(NotificationType.POOL_COMPLETED),
                eq(pool.getId()), contains("Fall Supplies"));
    }

    private Membership newMembership(UUID parentUserId) {
        Classroom classroom = new Classroom(UUID.randomUUID(), "Grade 1", "Ms. Smith", null, null);
        setId(classroom, classroomId);
        Membership membership = new Membership(classroom, parentUserId, null, MembershipRole.PARENT, false);
        setId(membership, UUID.randomUUID());
        setCreatedAt(membership);
        return membership;
    }

    private static Pool newPool(UUID classroomId, PoolState state) {
        Pool pool = new Pool(classroomId, "Fall Supplies", "SUPPLIES");
        setId(pool, UUID.randomUUID());
        setCreatedAt(pool);
        pool.setState(state);
        return pool;
    }

    private static Requirement newRequirement(UUID poolId, String name, int quantityPerStudent,
                                               RequirementState state) {
        Requirement requirement = new Requirement(poolId, name, quantityPerStudent, null,
                RequirementStrictness.EQUIVALENT_ALLOWED);
        setId(requirement, UUID.randomUUID());
        setField(requirement, "createdAt", Instant.now());
        setField(requirement, "updatedAt", Instant.now());
        setField(requirement, "state", state);
        return requirement;
    }

    private static void setId(Object entity, UUID id) {
        setField(entity, "id", id);
    }

    private static void setCreatedAt(Object entity) {
        setField(entity, "createdAt", Instant.now());
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
