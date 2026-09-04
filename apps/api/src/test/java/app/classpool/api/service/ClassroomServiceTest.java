package app.classpool.api.service;

import app.classpool.api.domain.Classroom;
import app.classpool.api.domain.Membership;
import app.classpool.api.domain.MembershipRole;
import app.classpool.api.domain.School;
import app.classpool.api.domain.SchoolYear;
import app.classpool.api.dto.ClassroomCreatedResponse;
import app.classpool.api.dto.ClassroomResponse;
import app.classpool.api.dto.CreateClassroomRequest;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.ClassroomRepository;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.SchoolYearRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClassroomServiceTest {

    @Mock
    private ClassroomRepository classroomRepository;
    @Mock
    private SchoolYearRepository schoolYearRepository;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private SchoolService schoolService;

    private ClassroomService classroomService;

    private final UUID callerId = UUID.randomUUID();
    private School school;
    private SchoolYear schoolYear;

    @BeforeEach
    void setUp() {
        // ClassroomAssembler is exercised for real (it just needs school/schoolYear lookups),
        // no need to mock it separately — only its two repository collaborators.
        classroomService = new ClassroomService(classroomRepository, schoolYearRepository, membershipRepository,
                schoolService, new ClassroomAssembler(schoolYearRepository, mock(app.classpool.api.repository.SchoolRepository.class)));

        school = new School("Lincoln Elementary");
        setId(school, UUID.randomUUID());
        schoolYear = new SchoolYear(school.getId(), "2026-2027");
        setId(schoolYear, UUID.randomUUID());
    }

    @Test
    void create_makesTheCallerAnOrganizer_andReturnsNoDedupWarningWhenNothingSimilarExists() {
        CreateClassroomRequest request = new CreateClassroomRequest(
                school.getId(), null, "2026-2027", "Grade 1", "Ms. Smith", null, 24);

        when(schoolService.resolveForClassroomCreation(school.getId(), null)).thenReturn(school);
        when(schoolYearRepository.findBySchoolIdAndLabel(school.getId(), "2026-2027"))
                .thenReturn(Optional.of(schoolYear));
        when(classroomRepository.fuzzySearchInSchoolYear(eq(schoolYear.getId()), anyString()))
                .thenReturn(List.of());

        Classroom saved = newClassroom(schoolYear.getId(), "Grade 1", "Ms. Smith");
        when(classroomRepository.save(any(Classroom.class))).thenReturn(saved);
        when(membershipRepository.save(any(Membership.class))).thenAnswer(inv -> inv.getArgument(0));

        ClassroomCreatedResponse response = classroomService.create(callerId, request);

        assertThat(response.dedupWarning()).isNull();
        assertThat(response.classroom().id()).isEqualTo(saved.getId());

        ArgumentCaptor<Membership> membershipCaptor = ArgumentCaptor.forClass(Membership.class);
        verify(membershipRepository).save(membershipCaptor.capture());
        Membership persisted = membershipCaptor.getValue();
        assertThat(persisted.getRole()).isEqualTo(MembershipRole.ORGANIZER);
        assertThat(persisted.getParentUserId()).isEqualTo(callerId);
        assertThat(persisted.getStudent()).isNull();
    }

    @Test
    void create_surfacesSimilarExistingClassroomsAsADedupWarning() {
        CreateClassroomRequest request = new CreateClassroomRequest(
                school.getId(), null, "2026-2027", "Grade 1", "Ms Smith", null, 24);

        when(schoolService.resolveForClassroomCreation(school.getId(), null)).thenReturn(school);
        when(schoolYearRepository.findBySchoolIdAndLabel(school.getId(), "2026-2027"))
                .thenReturn(Optional.of(schoolYear));

        Classroom existing = newClassroom(schoolYear.getId(), "Grade 1", "Ms. Smith");
        when(classroomRepository.fuzzySearchInSchoolYear(eq(schoolYear.getId()), anyString()))
                .thenReturn(List.of(existing));

        Classroom saved = newClassroom(schoolYear.getId(), "Grade 1", "Ms Smith");
        when(classroomRepository.save(any(Classroom.class))).thenReturn(saved);
        when(membershipRepository.save(any(Membership.class))).thenAnswer(inv -> inv.getArgument(0));

        ClassroomCreatedResponse response = classroomService.create(callerId, request);

        assertThat(response.dedupWarning()).isNotNull().hasSize(1);
        assertThat(response.dedupWarning().get(0).id()).isEqualTo(existing.getId());
    }

    @Test
    void getForCaller_throwsForbidden_whenCallerHasNoMembership() {
        Classroom classroom = newClassroom(schoolYear.getId(), "Grade 1", "Ms. Smith");
        when(classroomRepository.findById(classroom.getId())).thenReturn(Optional.of(classroom));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroom.getId(), callerId))
                .thenReturn(false);

        assertThatThrownBy(() -> classroomService.getForCaller(callerId, classroom.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getForCaller_throwsNotFound_whenClassroomDoesNotExist() {
        UUID missingId = UUID.randomUUID();
        when(classroomRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classroomService.getForCaller(callerId, missingId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getForCaller_returnsClassroom_whenCallerHasMembership() {
        Classroom classroom = newClassroom(schoolYear.getId(), "Grade 1", "Ms. Smith");
        when(classroomRepository.findById(classroom.getId())).thenReturn(Optional.of(classroom));
        when(membershipRepository.existsByClassroom_IdAndParentUserId(classroom.getId(), callerId))
                .thenReturn(true);

        ClassroomResponse response = classroomService.getForCaller(callerId, classroom.getId());

        assertThat(response.id()).isEqualTo(classroom.getId());
    }

    private static Classroom newClassroom(UUID schoolYearId, String grade, String teacherLabel) {
        Classroom classroom = new Classroom(schoolYearId, grade, teacherLabel, null, 24);
        setId(classroom, UUID.randomUUID());
        setCreatedAt(classroom);
        return classroom;
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

    private static void setCreatedAt(Object entity) {
        try {
            var field = entity.getClass().getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(entity, Instant.now());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
