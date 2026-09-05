package app.classpool.api;

import app.classpool.api.dto.ClassroomCreatedResponse;
import app.classpool.api.dto.ContributionResponse;
import app.classpool.api.dto.CreateClassroomRequest;
import app.classpool.api.dto.CreatePoolRequest;
import app.classpool.api.dto.CreateRequirementRequest;
import app.classpool.api.dto.InviteResponse;
import app.classpool.api.dto.JoinInviteRequest;
import app.classpool.api.dto.MembershipResponse;
import app.classpool.api.dto.OfferContributionRequest;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.dto.PoolResponse;
import app.classpool.api.dto.RequirementResponse;
import app.classpool.api.support.AbstractIntegrationTest;
import app.classpool.api.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * End-to-end coverage of the Phase 5 surplus contribution pool (PRD §5): offering, the organizer's
 * confirmation workflow, the offering parent's own withdraw path, and PRD §5.3's privacy model
 * (only the organizer's listing endpoint ever reveals contributor identity).
 */
class ContributionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestUsers testUsers;

    @Test
    void fullFlow_twoParentsPledgeSurplus_organizerReceivesOne_noIdentityLeakToOtherParents() {
        TestUsers.AuthedUser organizer = testUsers.create("contribOrg@example.com", "Contribution Organizer");
        UUID classroomId = createClassroom(organizer, "Contribution School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        RequirementResponse requirement = addRequirement(organizer, poolId, "Glue Sticks", 3);

        TestUsers.AuthedUser parent1 = testUsers.create("contribParent1@example.com", "Parent One");
        TestUsers.AuthedUser parent2 = testUsers.create("contribParent2@example.com", "Parent Two");
        MembershipResponse alex = join(organizer, classroomId, parent1, "Alex");
        MembershipResponse bailey = join(organizer, classroomId, parent2, "Bailey");
        confirm(organizer, poolId);

        ContributionResponse pledge1 = offer(parent1, poolId, requirement.id(), alex.studentId(), 2, null);
        ContributionResponse pledge2 = offer(parent2, poolId, requirement.id(), bailey.studentId(), 1, "DONATE");
        assertThat(pledge1.state()).isEqualTo("PLEDGED");
        assertThat(pledge2.state()).isEqualTo("PLEDGED");
        // Not even the offering parent's own create-response carries a leaked identity elsewhere —
        // there's simply nothing to leak here (the schema has no student_id on contribution; see
        // apps/api/README.md's Phase 5 notes).
        assertThat(pledge1.offeringParentDisplayName()).isNull();

        // Organizer's listing shows both, with contributor identity (PRD §5.3).
        List<ContributionResponse> organizerView = listForOrganizer(organizer, poolId);
        assertThat(organizerView).extracting(ContributionResponse::quantity, ContributionResponse::state,
                        ContributionResponse::offeringParentDisplayName)
                .containsExactlyInAnyOrder(
                        tuple(2, "PLEDGED", "Parent One"),
                        tuple(1, "PLEDGED", "Parent Two"));

        // Organizer marks parent1's pledge received.
        ContributionResponse received = markReceived(organizer, poolId, pledge1.id());
        assertThat(received.state()).isEqualTo("RECEIVED");

        List<ContributionResponse> organizerViewAfter = listForOrganizer(organizer, poolId);
        assertThat(organizerViewAfter).extracting(ContributionResponse::offeringParentDisplayName,
                        ContributionResponse::state)
                .containsExactlyInAnyOrder(
                        tuple("Parent One", "RECEIVED"),
                        tuple("Parent Two", "PLEDGED"));

        // Each parent's own "mine" view shows only their own pledge, and never carries the other
        // parent's identity anywhere in the payload — no offeringParentDisplayName field at all,
        // and no leaked display name string smuggled into any other field either.
        List<ContributionResponse> parent1Mine = getMine(parent1, poolId);
        assertThat(parent1Mine).hasSize(1);
        assertThat(parent1Mine.get(0).quantity()).isEqualTo(2);
        assertThat(parent1Mine.get(0).state()).isEqualTo("RECEIVED");
        assertThat(parent1Mine.get(0).offeringParentDisplayName()).isNull();

        List<ContributionResponse> parent2Mine = getMine(parent2, poolId);
        assertThat(parent2Mine).hasSize(1);
        assertThat(parent2Mine.get(0).quantity()).isEqualTo(1);
        assertThat(parent2Mine.get(0).offeringParentDisplayName()).isNull();
        assertThat(parent2Mine).noneMatch(c -> "Parent One".equals(c.offeringParentDisplayName()));
        assertThat(parent1Mine).noneMatch(c -> "Parent Two".equals(c.offeringParentDisplayName()));

        // A plain (non-organizer) parent calling the organizer-only listing endpoint is forbidden —
        // this is the actual privacy boundary, not just the field being null on "mine".
        ResponseEntity<String> forbidden = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/contributions", HttpMethod.GET,
                new HttpEntity<>(authHeaders(parent1)), String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void withdraw_beforeReceived_succeeds_butNotAfter() {
        TestUsers.AuthedUser organizer = testUsers.create("contribWithdrawOrg@example.com", "Withdraw Organizer");
        UUID classroomId = createClassroom(organizer, "Withdraw School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        RequirementResponse requirement = addRequirement(organizer, poolId, "Glue Sticks", 3);

        TestUsers.AuthedUser parent = testUsers.create("contribWithdrawParent@example.com", "Withdraw Parent");
        MembershipResponse alex = join(organizer, classroomId, parent, "Alex");
        confirm(organizer, poolId);

        ContributionResponse pledge = offer(parent, poolId, requirement.id(), alex.studentId(), 2, null);

        // Withdraw while still PLEDGED succeeds.
        ResponseEntity<Void> withdrawResponse = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/contributions/" + pledge.id(), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(parent)), Void.class);
        assertThat(withdrawResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(getMine(parent, poolId)).isEmpty();

        // Once RECEIVED, withdraw is a 409 — pledge again, then have the organizer receive it.
        ContributionResponse secondPledge = offer(parent, poolId, requirement.id(), alex.studentId(), 1, null);
        markReceived(organizer, poolId, secondPledge.id());

        ResponseEntity<String> conflict = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/contributions/" + secondPledge.id(), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(parent)), String.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void offeringForAnotherHouseholdsStudent_isForbidden() {
        TestUsers.AuthedUser organizer = testUsers.create("contribForbiddenOrg@example.com", "Forbidden Organizer");
        UUID classroomId = createClassroom(organizer, "Forbidden Contribution School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        RequirementResponse requirement = addRequirement(organizer, poolId, "Glue Sticks", 3);

        TestUsers.AuthedUser parent1 = testUsers.create("contribForbiddenParent1@example.com", "Forbidden Parent One");
        TestUsers.AuthedUser parent2 = testUsers.create("contribForbiddenParent2@example.com", "Forbidden Parent Two");
        MembershipResponse alex = join(organizer, classroomId, parent1, "Alex");
        join(organizer, classroomId, parent2, "Bailey");
        confirm(organizer, poolId);

        ResponseEntity<String> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirement.id() + "/contributions",
                HttpMethod.POST,
                new HttpEntity<>(new OfferContributionRequest(alex.studentId(), 1, null), authHeaders(parent2)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void offeringLendMode_isRejected() {
        TestUsers.AuthedUser organizer = testUsers.create("contribLendOrg@example.com", "Lend Organizer");
        UUID classroomId = createClassroom(organizer, "Lend School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        RequirementResponse requirement = addRequirement(organizer, poolId, "Glue Sticks", 3);

        TestUsers.AuthedUser parent = testUsers.create("contribLendParent@example.com", "Lend Parent");
        MembershipResponse alex = join(organizer, classroomId, parent, "Alex");
        confirm(organizer, poolId);

        ResponseEntity<String> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirement.id() + "/contributions",
                HttpMethod.POST,
                new HttpEntity<>(new OfferContributionRequest(alex.studentId(), 1, "LEND"), authHeaders(parent)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private UUID createClassroom(TestUsers.AuthedUser organizer, String schoolName) {
        CreateClassroomRequest request = new CreateClassroomRequest(
                null, schoolName, "2026-2027", "Grade 1", "Ms. Contribution", null, 20);
        ResponseEntity<ClassroomCreatedResponse> response = rest.exchange(
                baseUrl() + "/api/v1/classrooms", HttpMethod.POST, new HttpEntity<>(request, authHeaders(organizer)),
                ClassroomCreatedResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().classroom().id();
    }

    private UUID createPool(TestUsers.AuthedUser organizer, UUID classroomId) {
        ResponseEntity<PoolResponse> response = rest.exchange(
                baseUrl() + "/api/v1/classrooms/" + classroomId + "/pools", HttpMethod.POST,
                new HttpEntity<>(new CreatePoolRequest("Fall Supplies", null), authHeaders(organizer)),
                PoolResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private RequirementResponse addRequirement(TestUsers.AuthedUser organizer, UUID poolId, String name,
                                                int quantityPerStudent) {
        ResponseEntity<RequirementResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements", HttpMethod.POST,
                new HttpEntity<>(new CreateRequirementRequest(name, quantityPerStudent, null, null),
                        authHeaders(organizer)),
                RequirementResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private MembershipResponse join(TestUsers.AuthedUser organizer, UUID classroomId, TestUsers.AuthedUser parent,
                                     String studentFirstName) {
        String token = createInvite(organizer, classroomId);
        ResponseEntity<MembershipResponse> response = rest.exchange(
                baseUrl() + "/api/v1/invites/" + token + "/join", HttpMethod.POST,
                new HttpEntity<>(new JoinInviteRequest(studentFirstName), authHeaders(parent)),
                MembershipResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private String createInvite(TestUsers.AuthedUser organizer, UUID classroomId) {
        ResponseEntity<InviteResponse> response = rest.exchange(
                baseUrl() + "/api/v1/classrooms/" + classroomId + "/invites", HttpMethod.POST,
                new HttpEntity<>(null, authHeaders(organizer)), InviteResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().token();
    }

    private void confirm(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<PoolDetailResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), PoolDetailResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ContributionResponse offer(TestUsers.AuthedUser caller, UUID poolId, UUID requirementId, UUID studentId,
                                        int quantity, String mode) {
        ResponseEntity<ContributionResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirementId + "/contributions",
                HttpMethod.POST, new HttpEntity<>(new OfferContributionRequest(studentId, quantity, mode),
                        authHeaders(caller)),
                ContributionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private List<ContributionResponse> getMine(TestUsers.AuthedUser caller, UUID poolId) {
        ResponseEntity<ContributionResponse[]> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/contributions/mine", HttpMethod.GET,
                new HttpEntity<>(authHeaders(caller)), ContributionResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private List<ContributionResponse> listForOrganizer(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<ContributionResponse[]> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/contributions", HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), ContributionResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private ContributionResponse markReceived(TestUsers.AuthedUser organizer, UUID poolId, UUID contributionId) {
        ResponseEntity<ContributionResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/contributions/" + contributionId + "/receive",
                HttpMethod.POST, new HttpEntity<>(authHeaders(organizer)), ContributionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders authHeaders(TestUsers.AuthedUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "CLASSPOOL_SESSION=" + user.sessionToken());
        return headers;
    }
}
