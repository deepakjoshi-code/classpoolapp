package app.classpool.api.service;

import app.classpool.api.domain.AppUser;
import app.classpool.api.domain.AuthProvider;
import app.classpool.api.domain.Classroom;
import app.classpool.api.domain.Contribution;
import app.classpool.api.domain.ContributionMode;
import app.classpool.api.domain.Membership;
import app.classpool.api.domain.MembershipRole;
import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementStrictness;
import app.classpool.api.domain.Student;
import app.classpool.api.dto.ContributionResponse;
import app.classpool.api.dto.OfferContributionRequest;
import app.classpool.api.exception.BadRequestException;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.AppUserRepository;
import app.classpool.api.repository.ContributionRepository;
import app.classpool.api.repository.MembershipRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContributionServiceTest {

    @Mock
    private ContributionRepository contributionRepository;
    @Mock
    private RequirementRepository requirementRepository;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private PoolRepository poolRepository;

    private ContributionService contributionService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID classroomId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // PoolService exercised for real (same pattern as InventoryServiceTest) — only its
        // repository collaborators are mocked.
        PoolAssembler poolAssembler = new PoolAssembler(requirementRepository);
        RequirementAssembler requirementAssembler = new RequirementAssembler();
        PoolService poolService = new PoolService(poolRepository, requirementRepository, membershipRepository,
                poolAssembler, requirementAssembler, notificationService);
        contributionService = new ContributionService(contributionRepository, requirementRepository,
                membershipRepository, appUserRepository, poolService);
    }

    // ---- offer ----

    @Test
    void offer_createsAPledgedContribution_forAMemberOfTheGivenStudent() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Student student = newStudent("Alex");
        Membership membership = newMembership(callerId, student);

        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.findByClassroom_IdAndParentUserIdAndStudent_Id(
                classroomId, callerId, student.getId())).thenReturn(Optional.of(membership));
        when(requirementRepository.findByIdAndPoolId(requirement.getId(), pool.getId()))
                .thenReturn(Optional.of(requirement));
        when(contributionRepository.save(any(Contribution.class))).thenAnswer(inv -> inv.getArgument(0));

        ContributionResponse response = contributionService.offer(callerId, pool.getId(), requirement.getId(),
                new OfferContributionRequest(student.getId(), 5, null));

        assertThat(response.quantity()).isEqualTo(5);
        assertThat(response.mode()).isEqualTo("DONATE");
        assertThat(response.state()).isEqualTo("PLEDGED");
        assertThat(response.requirementName()).isEqualTo("Glue Sticks");
        assertThat(response.offeringParentDisplayName()).isNull();

        ArgumentCaptor<Contribution> captor = ArgumentCaptor.forClass(Contribution.class);
        verify(contributionRepository).save(captor.capture());
        assertThat(captor.getValue().getOfferingParentId()).isEqualTo(callerId);
        assertThat(captor.getValue().getRequirementId()).isEqualTo(requirement.getId());
    }

    @Test
    void offer_throwsForbidden_whenCallerHasNoMembershipOnThatStudent() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        UUID someoneElsesStudentId = UUID.randomUUID();
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.findByClassroom_IdAndParentUserIdAndStudent_Id(
                classroomId, callerId, someoneElsesStudentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> contributionService.offer(callerId, pool.getId(), UUID.randomUUID(),
                new OfferContributionRequest(someoneElsesStudentId, 2, null)))
                .isInstanceOf(ForbiddenException.class);
        verify(contributionRepository, never()).save(any());
    }

    @Test
    void offer_throwsBadRequest_forLendMode() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        Student student = newStudent("Alex");
        Membership membership = newMembership(callerId, student);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.findByClassroom_IdAndParentUserIdAndStudent_Id(
                classroomId, callerId, student.getId())).thenReturn(Optional.of(membership));

        assertThatThrownBy(() -> contributionService.offer(callerId, pool.getId(), UUID.randomUUID(),
                new OfferContributionRequest(student.getId(), 2, "LEND")))
                .isInstanceOf(BadRequestException.class);
        verify(contributionRepository, never()).save(any());
    }

    @Test
    void offer_throwsBadRequest_forSellMode() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        Student student = newStudent("Alex");
        Membership membership = newMembership(callerId, student);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.findByClassroom_IdAndParentUserIdAndStudent_Id(
                classroomId, callerId, student.getId())).thenReturn(Optional.of(membership));

        assertThatThrownBy(() -> contributionService.offer(callerId, pool.getId(), UUID.randomUUID(),
                new OfferContributionRequest(student.getId(), 2, "SELL")))
                .isInstanceOf(BadRequestException.class);
        verify(contributionRepository, never()).save(any());
    }

    // ---- getMine ----

    @Test
    void getMine_returnsOnlyTheCallersOwnContributions() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Contribution mine = newContribution(requirement.getId(), callerId, 3);

        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(contributionRepository.findByOfferingParentIdAndRequirementIdInOrderByCreatedAtAsc(
                callerId, List.of(requirement.getId()))).thenReturn(List.of(mine));

        List<ContributionResponse> responses = contributionService.getMine(callerId, pool.getId());

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).quantity()).isEqualTo(3);
        assertThat(responses.get(0).offeringParentDisplayName()).isNull();
        verifyNoInteractions(appUserRepository);
    }

    @Test
    void getMine_throwsForbidden_whenCallerHasNoMembershipOnThePoolsClassroom() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> contributionService.getMine(callerId, pool.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- withdraw ----

    @Test
    void withdraw_deletesAPledgedContribution_ownedByTheCaller() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Contribution contribution = newContribution(requirement.getId(), callerId, 3);

        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(contributionRepository.findByIdAndRequirementIdIn(contribution.getId(), List.of(requirement.getId())))
                .thenReturn(Optional.of(contribution));

        contributionService.withdraw(callerId, pool.getId(), contribution.getId());

        verify(contributionRepository).delete(contribution);
    }

    @Test
    void withdraw_throwsForbidden_forSomeoneElsesPledge() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Contribution someoneElses = newContribution(requirement.getId(), UUID.randomUUID(), 3);

        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(contributionRepository.findByIdAndRequirementIdIn(someoneElses.getId(), List.of(requirement.getId())))
                .thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> contributionService.withdraw(callerId, pool.getId(), someoneElses.getId()))
                .isInstanceOf(ForbiddenException.class);
        verify(contributionRepository, never()).delete(any());
    }

    @Test
    void withdraw_throwsConflict_whenAlreadyReceived() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Contribution received = newContribution(requirement.getId(), callerId, 3);
        received.markReceived();

        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(contributionRepository.findByIdAndRequirementIdIn(received.getId(), List.of(requirement.getId())))
                .thenReturn(Optional.of(received));

        assertThatThrownBy(() -> contributionService.withdraw(callerId, pool.getId(), received.getId()))
                .isInstanceOf(ConflictException.class);
        verify(contributionRepository, never()).delete(any());
    }

    @Test
    void withdraw_throwsNotFound_whenContributionIsNotInThisPool() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        UUID otherContributionId = UUID.randomUUID();

        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(contributionRepository.findByIdAndRequirementIdIn(otherContributionId, List.of(requirement.getId())))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> contributionService.withdraw(callerId, pool.getId(), otherContributionId))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- listForOrganizer ----

    @Test
    void listForOrganizer_includesOfferingParentDisplayName() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        UUID offeringParentId = UUID.randomUUID();
        Contribution contribution = newContribution(requirement.getId(), offeringParentId, 3);
        AppUser offeringParent = new AppUser("parent@example.com", "Parent One", AuthProvider.MAGIC_LINK, null);
        setField(offeringParent, "id", offeringParentId);

        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(contributionRepository.findByRequirementIdInOrderByCreatedAtAsc(List.of(requirement.getId())))
                .thenReturn(List.of(contribution));
        when(appUserRepository.findAllById(List.of(offeringParentId))).thenReturn(List.of(offeringParent));

        List<ContributionResponse> responses = contributionService.listForOrganizer(callerId, pool.getId());

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).offeringParentDisplayName()).isEqualTo("Parent One");
    }

    @Test
    void listForOrganizer_throwsForbidden_forANonOrganizer() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> contributionService.listForOrganizer(callerId, pool.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- markReceived ----

    @Test
    void markReceived_transitionsPledgedToReceived() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Contribution contribution = newContribution(requirement.getId(), UUID.randomUUID(), 3);

        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(contributionRepository.findByIdAndRequirementIdIn(contribution.getId(), List.of(requirement.getId())))
                .thenReturn(Optional.of(contribution));
        when(requirementRepository.findById(requirement.getId())).thenReturn(Optional.of(requirement));
        when(contributionRepository.save(any(Contribution.class))).thenAnswer(inv -> inv.getArgument(0));

        ContributionResponse response = contributionService.markReceived(callerId, pool.getId(), contribution.getId());

        assertThat(response.state()).isEqualTo("RECEIVED");
        verify(contributionRepository).save(contribution);
    }

    @Test
    void markReceived_throwsConflict_whenAlreadyReceived() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        Requirement requirement = newRequirement(pool.getId(), "Glue Sticks", 4);
        Contribution contribution = newContribution(requirement.getId(), UUID.randomUUID(), 3);
        contribution.markReceived();

        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(contributionRepository.findByIdAndRequirementIdIn(contribution.getId(), List.of(requirement.getId())))
                .thenReturn(Optional.of(contribution));

        assertThatThrownBy(() -> contributionService.markReceived(callerId, pool.getId(), contribution.getId()))
                .isInstanceOf(ConflictException.class);
        verify(contributionRepository, never()).save(any());
    }

    @Test
    void markReceived_throwsForbidden_forANonOrganizer() {
        Pool pool = newPool(classroomId, PoolState.OPEN_FOR_CONTRIBUTIONS);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> contributionService.markReceived(callerId, pool.getId(), UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
        verify(contributionRepository, never()).save(any());
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

    private static Contribution newContribution(UUID requirementId, UUID offeringParentId, int quantity) {
        Contribution contribution = new Contribution(requirementId, offeringParentId, quantity,
                ContributionMode.DONATE);
        setField(contribution, "id", UUID.randomUUID());
        setField(contribution, "createdAt", Instant.now());
        setField(contribution, "updatedAt", Instant.now());
        return contribution;
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
