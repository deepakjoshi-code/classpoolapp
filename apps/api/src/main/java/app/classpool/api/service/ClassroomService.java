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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ClassroomService {

    private final ClassroomRepository classroomRepository;
    private final SchoolYearRepository schoolYearRepository;
    private final MembershipRepository membershipRepository;
    private final SchoolService schoolService;
    private final HouseholdService householdService;
    private final ClassroomAssembler classroomAssembler;

    public ClassroomService(ClassroomRepository classroomRepository, SchoolYearRepository schoolYearRepository,
                             MembershipRepository membershipRepository, SchoolService schoolService,
                             HouseholdService householdService, ClassroomAssembler classroomAssembler) {
        this.classroomRepository = classroomRepository;
        this.schoolYearRepository = schoolYearRepository;
        this.membershipRepository = membershipRepository;
        this.schoolService = schoolService;
        this.householdService = householdService;
        this.classroomAssembler = classroomAssembler;
    }

    @Transactional
    public ClassroomCreatedResponse create(UUID callerUserId, CreateClassroomRequest request) {
        School school = schoolService.resolveForClassroomCreation(request.schoolId(), request.schoolName());
        SchoolYear schoolYear = schoolYearRepository.findBySchoolIdAndLabel(school.getId(), request.schoolYearLabel())
                .orElseGet(() -> schoolYearRepository.save(new SchoolYear(school.getId(), request.schoolYearLabel())));

        // Dedup fuzzy-match (PRD §2.3 update) — run BEFORE inserting the new row, against existing
        // classrooms in the same school year, so the new classroom never matches itself.
        String dedupQuery = request.grade() + " " + request.teacherLabel();
        List<Classroom> similar = classroomRepository.fuzzySearchInSchoolYear(schoolYear.getId(), dedupQuery);

        Classroom classroom = classroomRepository.save(new Classroom(
                schoolYear.getId(), request.grade(), request.teacherLabel(), request.teacherEmail(),
                request.studentCountEstimate()));

        // Creator becomes ORGANIZER (PRD §2.1) — not a distinct account type, a Membership row.
        // No student attached to this grant (organizers need not have a child in the class).
        membershipRepository.save(new Membership(classroom, callerUserId, null, MembershipRole.ORGANIZER, false));
        // Every Membership grant must be backed by a Household so /household/dashboard (§12 update)
        // never 404s for a legitimate member — see HouseholdService.getOrCreateHousehold's Javadoc
        // for the bug this fixes (found live: an organizer with no child in their own class had no
        // way to see the class they'd just created).
        householdService.getOrCreateHousehold(callerUserId);

        ClassroomResponse classroomResponse = classroomAssembler.toResponse(classroom);
        List<ClassroomResponse> dedupWarning = similar.isEmpty()
                ? null
                : classroomAssembler.toResponses(similar).values().stream().toList();
        return new ClassroomCreatedResponse(classroomResponse, dedupWarning);
    }

    /**
     * Returns the classroom only if the caller has a Membership on it — the PRD §14 tenant
     * isolation boundary. Throws ForbiddenException (never a bare 404, which would let a caller
     * distinguish "not mine" from "doesn't exist" by timing/response-shape) for both the
     * no-membership and the classroom-owned-by-another-tenant cases.
     */
    @Transactional(readOnly = true)
    public ClassroomResponse getForCaller(UUID callerUserId, UUID classroomId) {
        Classroom classroom = classroomRepository.findById(classroomId)
                .orElseThrow(() -> new NotFoundException("Classroom not found: " + classroomId));
        boolean hasMembership = membershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerUserId);
        if (!hasMembership) {
            throw new ForbiddenException("You do not have access to this classroom");
        }
        return classroomAssembler.toResponse(classroom);
    }

    @Transactional(readOnly = true)
    public Classroom getEntityOrThrow(UUID classroomId) {
        return classroomRepository.findById(classroomId)
                .orElseThrow(() -> new NotFoundException("Classroom not found: " + classroomId));
    }
}
