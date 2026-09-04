package app.classpool.api;

import app.classpool.api.domain.MembershipRole;
import app.classpool.api.dto.ClassroomCreatedResponse;
import app.classpool.api.dto.CreateClassroomRequest;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.support.AbstractIntegrationTest;
import app.classpool.api.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ClassroomCreationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestUsers testUsers;
    @Autowired
    private MembershipRepository membershipRepository;

    @Test
    void creatingAClassroom_makesTheCreatorItsOrganizer() {
        TestUsers.AuthedUser organizer = testUsers.create("organizer1@example.com", "Priya");

        CreateClassroomRequest request = new CreateClassroomRequest(
                null, "Lincoln Elementary " + System.nanoTime(), "2026-2027", "Grade 1", "Ms. Smith", null, 24);

        ResponseEntity<ClassroomCreatedResponse> response = post(organizer, "/api/v1/classrooms", request,
                ClassroomCreatedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        var classroom = response.getBody().classroom();
        assertThat(classroom.id()).isNotNull();
        assertThat(classroom.grade()).isEqualTo("Grade 1");
        assertThat(classroom.teacherLabel()).isEqualTo("Ms. Smith");

        var memberships = membershipRepository.findByClassroom_IdAndParentUserId(classroom.id(), organizer.userId());
        assertThat(memberships).hasSize(1);
        assertThat(memberships.get(0).getRole()).isEqualTo(MembershipRole.ORGANIZER);
    }

    @Test
    void creatingANearDuplicateClassroom_triggersADedupWarning() {
        TestUsers.AuthedUser first = testUsers.create("organizer2@example.com", "Priya");
        TestUsers.AuthedUser second = testUsers.create("organizer3@example.com", "Sam");

        String schoolName = "Lincoln Elementary " + System.nanoTime();
        String yearLabel = "2026-2027";

        CreateClassroomRequest original = new CreateClassroomRequest(
                null, schoolName, yearLabel, "Grade 1", "Ms. Smith", null, 24);
        ResponseEntity<ClassroomCreatedResponse> originalResponse =
                post(first, "/api/v1/classrooms", original, ClassroomCreatedResponse.class);
        assertThat(originalResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var schoolId = originalResponse.getBody().classroom().schoolId();

        // Same school, same year, near-identical grade/teacher — the PRD §2.3 "two parents
        // unknowingly start the same class twice" scenario.
        CreateClassroomRequest duplicate = new CreateClassroomRequest(
                schoolId, null, yearLabel, "Grade 1", "Ms Smith", null, 24);
        ResponseEntity<ClassroomCreatedResponse> duplicateResponse =
                post(second, "/api/v1/classrooms", duplicate, ClassroomCreatedResponse.class);

        assertThat(duplicateResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(duplicateResponse.getBody().dedupWarning())
                .as("near-duplicate classroom should surface the earlier one as a dedup warning")
                .isNotNull()
                .isNotEmpty();
        assertThat(duplicateResponse.getBody().dedupWarning().get(0).id())
                .isEqualTo(original == null ? null : originalResponse.getBody().classroom().id());
    }

    private <T> ResponseEntity<T> post(TestUsers.AuthedUser user, String path, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "CLASSPOOL_SESSION=" + user.sessionToken());
        return rest.exchange(baseUrl() + path, HttpMethod.POST, new HttpEntity<>(body, headers), responseType);
    }
}
