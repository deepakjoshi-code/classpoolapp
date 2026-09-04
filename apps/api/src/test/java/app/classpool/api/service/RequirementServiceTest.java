package app.classpool.api.service;

import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementState;
import app.classpool.api.domain.RequirementStrictness;
import app.classpool.api.dto.CreateRequirementRequest;
import app.classpool.api.dto.RequirementResponse;
import app.classpool.api.exception.BadRequestException;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.ForbiddenException;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequirementServiceTest {

    @Mock
    private RequirementRepository requirementRepository;
    @Mock
    private PoolRepository poolRepository;
    @Mock
    private MembershipRepository membershipRepository;

    private RequirementService requirementService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID classroomId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // PoolService/RequirementAssembler exercised for real (same pattern as elsewhere in this
        // suite) — only their repository collaborators are mocked.
        PoolAssembler poolAssembler = new PoolAssembler(requirementRepository);
        RequirementAssembler requirementAssembler = new RequirementAssembler();
        PoolService poolService = new PoolService(poolRepository, requirementRepository, membershipRepository,
                poolAssembler, requirementAssembler);
        requirementService = new RequirementService(requirementRepository, poolService, requirementAssembler);
    }

    // ---- add ----

    @Test
    void add_throwsForbidden_whenCallerIsNotAnOrganizer() {
        Pool pool = newPool(classroomId, PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> requirementService.add(callerId, pool.getId(), glueSticksRequest()))
                .isInstanceOf(ForbiddenException.class);
        verify(requirementRepository, never()).save(any());
    }

    @Test
    void add_throwsConflict_whenPoolIsNoLongerDraft() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_INVENTORY);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);

        assertThatThrownBy(() -> requirementService.add(callerId, pool.getId(), glueSticksRequest()))
                .isInstanceOf(ConflictException.class);
        verify(requirementRepository, never()).save(any());
    }

    @Test
    void add_startsInExtracted_withNullSourceEvidenceAndConfidence_forAnOrganizerOnADraftPool() {
        Pool pool = newPool(classroomId, PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.save(any(Requirement.class))).thenAnswer(inv -> {
            Requirement r = inv.getArgument(0);
            setField(r, "id", UUID.randomUUID());
            setField(r, "createdAt", Instant.now());
            setField(r, "updatedAt", Instant.now());
            return r;
        });

        RequirementResponse response = requirementService.add(callerId, pool.getId(), glueSticksRequest());

        assertThat(response.state()).isEqualTo("EXTRACTED");
        assertThat(response.sourceEvidence()).isNull();
        assertThat(response.confidence()).isNull();
        assertThat(response.totalDemand()).isNull();
        assertThat(response.name()).isEqualTo("Glue Sticks");
        assertThat(response.quantityPerStudent()).isEqualTo(4);
    }

    @Test
    void add_throwsBadRequest_forAnUnknownStrictness() {
        Pool pool = newPool(classroomId, PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);

        CreateRequirementRequest bad = new CreateRequirementRequest("Glue Sticks", 4, null, "NOT_A_REAL_STRICTNESS");

        assertThatThrownBy(() -> requirementService.add(callerId, pool.getId(), bad))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- update ("Correct") ----

    @Test
    void update_throwsForbidden_whenCallerIsNotAnOrganizer() {
        Pool pool = newPool(classroomId, PoolState.DRAFT);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> requirementService.update(callerId, pool.getId(), requirement.getId(),
                glueSticksRequest())).isInstanceOf(ForbiddenException.class);
        verify(requirementRepository, never()).save(any());
    }

    @Test
    void update_throwsConflict_whenPoolIsNoLongerDraft() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_INVENTORY);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);

        assertThatThrownBy(() -> requirementService.update(callerId, pool.getId(), requirement.getId(),
                glueSticksRequest())).isInstanceOf(ConflictException.class);
        verify(requirementRepository, never()).save(any());
    }

    @Test
    void update_appliesEdits_forAnOrganizerOnADraftPool() {
        Pool pool = newPool(classroomId, PoolState.DRAFT);
        Requirement requirement = newRequirement(pool.getId(), "Glue Stick", 2);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.findByIdAndPoolId(requirement.getId(), pool.getId()))
                .thenReturn(Optional.of(requirement));
        when(requirementRepository.save(any(Requirement.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateRequirementRequest edit = new CreateRequirementRequest("Glue Sticks (corrected)", 5, "Elmer's", "EXACT");
        RequirementResponse response = requirementService.update(callerId, pool.getId(), requirement.getId(), edit);

        assertThat(response.name()).isEqualTo("Glue Sticks (corrected)");
        assertThat(response.quantityPerStudent()).isEqualTo(5);
        assertThat(response.brand()).isEqualTo("Elmer's");
        assertThat(response.strictness()).isEqualTo("EXACT");
    }

    @Test
    void update_throwsNotFound_whenRequirementDoesNotBelongToThePool() {
        Pool pool = newPool(classroomId, PoolState.DRAFT);
        UUID missingRequirementId = UUID.randomUUID();
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.findByIdAndPoolId(missingRequirementId, pool.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> requirementService.update(callerId, pool.getId(), missingRequirementId,
                glueSticksRequest())).isInstanceOf(app.classpool.api.exception.NotFoundException.class);
    }

    // ---- remove ----

    @Test
    void remove_throwsForbidden_whenCallerIsNotAnOrganizer() {
        Pool pool = newPool(classroomId, PoolState.DRAFT);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> requirementService.remove(callerId, pool.getId(), requirement.getId()))
                .isInstanceOf(ForbiddenException.class);
        verify(requirementRepository, never()).delete(any());
    }

    @Test
    void remove_throwsConflict_whenPoolIsNoLongerDraft() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_INVENTORY);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);

        assertThatThrownBy(() -> requirementService.remove(callerId, pool.getId(), requirement.getId()))
                .isInstanceOf(ConflictException.class);
        verify(requirementRepository, never()).delete(any());
    }

    @Test
    void remove_deletesTheRequirement_forAnOrganizerOnADraftPool() {
        Pool pool = newPool(classroomId, PoolState.DRAFT);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.findByIdAndPoolId(requirement.getId(), pool.getId()))
                .thenReturn(Optional.of(requirement));

        requirementService.remove(callerId, pool.getId(), requirement.getId());

        verify(requirementRepository).delete(requirement);
    }

    private static CreateRequirementRequest glueSticksRequest() {
        return new CreateRequirementRequest("Glue Sticks", 4, "Elmer's", null);
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
        setField(requirement, "state", RequirementState.EXTRACTED);
        return requirement;
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
