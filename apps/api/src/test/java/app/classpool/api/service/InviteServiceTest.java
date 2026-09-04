package app.classpool.api.service;

import app.classpool.api.domain.Classroom;
import app.classpool.api.domain.Household;
import app.classpool.api.domain.Invite;
import app.classpool.api.domain.Membership;
import app.classpool.api.domain.Student;
import app.classpool.api.dto.MembershipResponse;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.InviteRepository;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InviteServiceTest {

    @Mock
    private InviteRepository inviteRepository;
    @Mock
    private ClassroomService classroomService;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private HouseholdService householdService;
    @Mock
    private StudentRepository studentRepository;
    @Mock
    private ClassroomAssembler classroomAssembler;
    @Mock
    private MembershipAssembler membershipAssembler;
    @Mock
    private PoolGateway poolGateway;

    private InviteService inviteService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID classroomId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        inviteService = new InviteService(inviteRepository, classroomService, membershipRepository,
                householdService, studentRepository, classroomAssembler, membershipAssembler, poolGateway,
                "http://localhost:3000");
    }

    @Test
    void create_throwsForbidden_whenCallerIsNotAnOrganizerOnTheClassroom() {
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> inviteService.create(callerId, classroomId, null))
                .isInstanceOf(ForbiddenException.class);

        verify(inviteRepository, never()).save(any());
    }

    @Test
    void create_succeeds_forAnOrganizer() {
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(inviteRepository.findByToken(any())).thenReturn(Optional.empty());
        when(inviteRepository.save(any(Invite.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = inviteService.create(callerId, classroomId, app.classpool.api.domain.InviteChannel.LINK);

        assertThat(response.token()).isNotBlank();
        assertThat(response.joinUrl()).contains(response.token());
    }

    @Test
    void join_throwsNotFound_forAnUnknownToken() {
        when(inviteRepository.findByToken("bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inviteService.join(callerId, "bad-token", "Alex"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void join_createsANewHousehold_whenCallerHasNoneYet() {
        Invite invite = new Invite(classroomId, "TOKEN123", app.classpool.api.domain.InviteChannel.LINK, UUID.randomUUID());
        when(inviteRepository.findByToken("TOKEN123")).thenReturn(Optional.of(invite));

        Classroom classroom = new Classroom(UUID.randomUUID(), "Grade 1", "Ms. Smith", null, 24);
        setId(classroom, classroomId);
        when(classroomService.getEntityOrThrow(classroomId)).thenReturn(classroom);

        Household newHousehold = new Household(callerId);
        setId(newHousehold, UUID.randomUUID());
        when(householdService.getOrCreateHousehold(callerId)).thenReturn(newHousehold);

        Student savedStudent = new Student(newHousehold.getId(), "Alex");
        setId(savedStudent, UUID.randomUUID());
        when(studentRepository.save(any(Student.class))).thenReturn(savedStudent);

        when(poolGateway.hasPoolPastOpenForContributions(classroomId)).thenReturn(false);
        when(membershipRepository.save(any(Membership.class))).thenAnswer(inv -> inv.getArgument(0));
        when(membershipAssembler.toResponse(any(Membership.class))).thenAnswer(inv -> {
            Membership m = inv.getArgument(0);
            return new MembershipResponse(UUID.randomUUID(), classroomId, m.getRole().name(),
                    savedStudent.getId(), "Alex", m.isLateJoin(), null);
        });

        MembershipResponse response = inviteService.join(callerId, "TOKEN123", "Alex");

        assertThat(response.role()).isEqualTo("PARENT");
        assertThat(response.lateJoin()).isFalse();
        verify(householdService).getOrCreateHousehold(callerId);

        ArgumentCaptor<Invite> inviteCaptor = ArgumentCaptor.forClass(Invite.class);
        verify(inviteRepository).save(inviteCaptor.capture());
        assertThat(inviteCaptor.getValue().getConvertedUserId()).isEqualTo(callerId);
    }

    @Test
    void join_setsLateJoinTrue_whenThePoolHasLeftOpenForContributions() {
        Invite invite = new Invite(classroomId, "TOKEN456", app.classpool.api.domain.InviteChannel.LINK, UUID.randomUUID());
        when(inviteRepository.findByToken("TOKEN456")).thenReturn(Optional.of(invite));

        Classroom classroom = new Classroom(UUID.randomUUID(), "Grade 1", "Ms. Smith", null, 24);
        setId(classroom, classroomId);
        when(classroomService.getEntityOrThrow(classroomId)).thenReturn(classroom);

        Household household = new Household(callerId);
        setId(household, UUID.randomUUID());
        when(householdService.getOrCreateHousehold(callerId)).thenReturn(household);

        Student savedStudent = new Student(household.getId(), "Jamie");
        setId(savedStudent, UUID.randomUUID());
        when(studentRepository.save(any(Student.class))).thenReturn(savedStudent);

        // Pool already past OPEN_FOR_CONTRIBUTIONS — PRD §13.3 late-join rule.
        when(poolGateway.hasPoolPastOpenForContributions(classroomId)).thenReturn(true);

        ArgumentCaptor<Membership> membershipCaptor = ArgumentCaptor.forClass(Membership.class);
        when(membershipRepository.save(membershipCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(membershipAssembler.toResponse(any(Membership.class))).thenAnswer(inv -> {
            Membership m = inv.getArgument(0);
            return new MembershipResponse(UUID.randomUUID(), classroomId, m.getRole().name(),
                    savedStudent.getId(), "Jamie", m.isLateJoin(), null);
        });

        MembershipResponse response = inviteService.join(callerId, "TOKEN456", "Jamie");

        assertThat(response.lateJoin()).isTrue();
        assertThat(membershipCaptor.getValue().isLateJoin()).isTrue();
    }

    private static void setId(Object entity, UUID id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
