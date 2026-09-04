package app.classpool.api;

import app.classpool.api.dto.ClassroomCreatedResponse;
import app.classpool.api.dto.CreateClassroomRequest;
import app.classpool.api.support.AbstractIntegrationTest;
import app.classpool.api.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD §14's own stated bar for correctness, verbatim: "Changing a class, pool, membership or
 * requirement ID in an API request must never allow a parent from Class A to read or modify Class
 * B." This is the single most important test in this codebase — every other endpoint's
 * correctness is negotiable in a way this one is not.
 */
class CrossTenantAuthorizationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestUsers testUsers;

    @Test
    void aParentInClassA_getsForbidden_readingClassBsClassroom() {
        TestUsers.AuthedUser organizerA = testUsers.create("orgA@example.com", "Organizer A");
        TestUsers.AuthedUser organizerB = testUsers.create("orgB@example.com", "Organizer B");

        UUID classroomA = createClassroom(organizerA, "School A " + System.nanoTime(), "Grade 1", "Ms. A");
        UUID classroomB = createClassroom(organizerB, "School B " + System.nanoTime(), "Grade 2", "Ms. B");

        // Organizer A (a real member of classroom A, zero relationship to classroom B) tries to
        // read classroom B by ID substitution.
        ResponseEntity<String> response = get(organizerA, "/api/v1/classrooms/" + classroomB);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Must not leak any of Class B's data in the body of a 403.
        assertThat(response.getBody()).doesNotContain("Ms. B");
    }

    @Test
    void theSameOrganizer_canReadTheirOwnClassroom() {
        TestUsers.AuthedUser organizer = testUsers.create("orgC@example.com", "Organizer C");
        UUID classroomId = createClassroom(organizer, "School C " + System.nanoTime(), "Grade 3", "Ms. C");

        ResponseEntity<String> response = get(organizer, "/api/v1/classrooms/" + classroomId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Ms. C");
    }

    @Test
    void anAuthenticatedUserWithNoMembershipAtAll_getsForbidden_notLeakedData() {
        TestUsers.AuthedUser owner = testUsers.create("orgD@example.com", "Owner");
        TestUsers.AuthedUser outsider = testUsers.create("outsider@example.com", "Outsider");
        UUID classroomId = createClassroom(owner, "School D " + System.nanoTime(), "Grade 4", "Ms. D");

        ResponseEntity<String> response = get(outsider, "/api/v1/classrooms/" + classroomId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anUnauthenticatedCaller_getsUnauthorized_notForbiddenOrData() {
        TestUsers.AuthedUser owner = testUsers.create("orgE@example.com", "Owner");
        UUID classroomId = createClassroom(owner, "School E " + System.nanoTime(), "Grade 5", "Ms. E");

        ResponseEntity<String> response = rest.getForEntity(baseUrl() + "/api/v1/classrooms/" + classroomId,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private UUID createClassroom(TestUsers.AuthedUser organizer, String schoolName, String grade, String teacher) {
        CreateClassroomRequest request = new CreateClassroomRequest(
                null, schoolName, "2026-2027", grade, teacher, null, 20);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "CLASSPOOL_SESSION=" + organizer.sessionToken());
        ResponseEntity<ClassroomCreatedResponse> response = rest.exchange(
                baseUrl() + "/api/v1/classrooms", HttpMethod.POST, new HttpEntity<>(request, headers),
                ClassroomCreatedResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().classroom().id();
    }

    private ResponseEntity<String> get(TestUsers.AuthedUser user, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "CLASSPOOL_SESSION=" + user.sessionToken());
        return rest.exchange(baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
