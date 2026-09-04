package app.classpool.api;

import app.classpool.api.dto.ClassroomCreatedResponse;
import app.classpool.api.dto.CreateClassroomRequest;
import app.classpool.api.dto.InviteResponse;
import app.classpool.api.dto.JoinInviteRequest;
import app.classpool.api.dto.MembershipResponse;
import app.classpool.api.repository.HouseholdRepository;
import app.classpool.api.repository.StudentRepository;
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

class InviteJoinIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestUsers testUsers;
    @Autowired
    private HouseholdRepository householdRepository;
    @Autowired
    private StudentRepository studentRepository;

    @Test
    void joiningViaInvite_createsHouseholdStudentAndMembership() {
        TestUsers.AuthedUser organizer = testUsers.create("joinOrg@example.com", "Organizer");
        UUID classroomId = createClassroom(organizer);
        String token = createInvite(organizer, classroomId);

        TestUsers.AuthedUser parent = testUsers.create("joinParent@example.com", "Parent");
        assertThat(householdRepository.findByPrimaryParentId(parent.userId())).isEmpty();

        JoinInviteRequest joinRequest = new JoinInviteRequest("Alex");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "CLASSPOOL_SESSION=" + parent.sessionToken());
        ResponseEntity<MembershipResponse> response = rest.exchange(
                baseUrl() + "/api/v1/invites/" + token + "/join", HttpMethod.POST,
                new HttpEntity<>(joinRequest, headers), MembershipResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MembershipResponse membership = response.getBody();
        assertThat(membership.role()).isEqualTo("PARENT");
        assertThat(membership.classroomId()).isEqualTo(classroomId);
        assertThat(membership.studentFirstName()).isEqualTo("Alex");
        assertThat(membership.lateJoin()).isFalse(); // no pool exists yet for this classroom

        var household = householdRepository.findByPrimaryParentId(parent.userId());
        assertThat(household).isPresent();
        var students = studentRepository.findByHouseholdId(household.get().getId());
        assertThat(students).hasSize(1);
        assertThat(students.get(0).getFirstName()).isEqualTo("Alex");
    }

    @Test
    void previewingAnInvite_isPublicAndRequiresNoAuth() {
        TestUsers.AuthedUser organizer = testUsers.create("previewOrg@example.com", "Organizer");
        UUID classroomId = createClassroom(organizer);
        String token = createInvite(organizer, classroomId);

        ResponseEntity<String> response = rest.getForEntity(baseUrl() + "/api/v1/invites/" + token, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private UUID createClassroom(TestUsers.AuthedUser organizer) {
        CreateClassroomRequest request = new CreateClassroomRequest(
                null, "Invite School " + System.nanoTime(), "2026-2027", "Grade 2", "Mr. Lee", null, 15);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "CLASSPOOL_SESSION=" + organizer.sessionToken());
        ResponseEntity<ClassroomCreatedResponse> response = rest.exchange(
                baseUrl() + "/api/v1/classrooms", HttpMethod.POST, new HttpEntity<>(request, headers),
                ClassroomCreatedResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().classroom().id();
    }

    private String createInvite(TestUsers.AuthedUser organizer, UUID classroomId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "CLASSPOOL_SESSION=" + organizer.sessionToken());
        ResponseEntity<InviteResponse> response = rest.exchange(
                baseUrl() + "/api/v1/classrooms/" + classroomId + "/invites", HttpMethod.POST,
                new HttpEntity<>(null, headers), InviteResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().token();
    }
}
