package app.classpool.api;

import app.classpool.api.dto.AllocationLineResponse;
import app.classpool.api.dto.AllocationSummaryResponse;
import app.classpool.api.dto.ClassroomCreatedResponse;
import app.classpool.api.dto.ContributionResponse;
import app.classpool.api.dto.CreateClassroomRequest;
import app.classpool.api.dto.CreatePoolRequest;
import app.classpool.api.dto.CreateRequirementRequest;
import app.classpool.api.dto.InventoryLineResponse;
import app.classpool.api.dto.InviteResponse;
import app.classpool.api.dto.JoinInviteRequest;
import app.classpool.api.dto.MembershipResponse;
import app.classpool.api.dto.OfferContributionRequest;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.dto.PoolResponse;
import app.classpool.api.dto.RequirementResponse;
import app.classpool.api.dto.SetInventoryRequest;
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
 * End-to-end coverage of the Phase 6/7 allocation & residual-demand engine (PRD §6): confirming a
 * pool with two students, one fully self-fulfilled from household inventory and the other only
 * partially covered by a RECEIVED surplus contribution (still needing a purchase), then reading
 * the frozen snapshot back through both the organizer view and each parent's own "mine" view —
 * including the privacy boundary that a parent's "mine" view never contains the other family's
 * student.
 */
class AllocationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestUsers testUsers;

    @Test
    void fullFlow_reconcileThenReadBackOrganizerAndMineViews_withPrivacyBoundary() {
        TestUsers.AuthedUser organizer = testUsers.create("allocOrg@example.com", "Allocation Organizer");
        UUID classroomId = createClassroom(organizer, "Allocation School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        RequirementResponse requirement = addRequirement(organizer, poolId, "Glue Sticks", 3);

        TestUsers.AuthedUser parent1 = testUsers.create("allocParent1@example.com", "Allocation Parent One");
        TestUsers.AuthedUser parent2 = testUsers.create("allocParent2@example.com", "Allocation Parent Two");
        MembershipResponse alex = join(organizer, classroomId, parent1, "Alex");
        MembershipResponse bailey = join(organizer, classroomId, parent2, "Bailey");

        confirm(organizer, poolId);

        // Alex's household already owns all 3 needed — fully self-fulfilled, no pool/purchase
        // needed at all.
        setInventory(parent1, poolId, requirement.id(), alex.studentId(), 3);
        // Bailey's household owns only 1 of 3; a RECEIVED pledge of 1 covers one more, leaving 1
        // that still needs to be purchased.
        setInventory(parent2, poolId, requirement.id(), bailey.studentId(), 1);
        ContributionResponse pledge = offer(parent2, poolId, requirement.id(), bailey.studentId(), 1);
        markReceived(organizer, poolId, pledge.id());

        // Before reconcile, the organizer/mine allocation endpoints reflect "nothing yet".
        assertThat(getMyAllocation(parent1, poolId)).isEmpty();
        ResponseEntity<String> notYetReconciled = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/allocation", HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), String.class);
        assertThat(notYetReconciled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        AllocationSummaryResponse reconciled = reconcile(organizer, poolId);

        // Pool moved OPEN_FOR_INVENTORY -> RECONCILING directly (no OPEN_FOR_CONTRIBUTIONS hop —
        // see apps/api/README.md's Phase 6/7 design notes).
        ResponseEntity<PoolDetailResponse> poolAfter = rest.exchange(baseUrl() + "/api/v1/pools/" + poolId,
                HttpMethod.GET, new HttpEntity<>(authHeaders(organizer)), PoolDetailResponse.class);
        assertThat(poolAfter.getBody().state()).isEqualTo("RECONCILING");

        assertThat(reconciled.allocations()).hasSize(2);
        assertThat(reconciled.allocations()).extracting(AllocationLineResponse::studentFirstName,
                        AllocationLineResponse::ownedQuantity, AllocationLineResponse::poolFulfilledQuantity,
                        AllocationLineResponse::purchaseRequiredQuantity, AllocationLineResponse::status)
                .containsExactlyInAnyOrder(
                        tuple("Alex", 3, 0, 0, "SELF_FULFILLED"),
                        tuple("Bailey", 1, 1, 1, "PURCHASE_REQUIRED"));

        assertThat(reconciled.residualDemand()).hasSize(1);
        assertThat(reconciled.residualDemand().get(0).requirementId()).isEqualTo(requirement.id());
        assertThat(reconciled.residualDemand().get(0).totalRequired()).isEqualTo(6); // 3 x 2 students
        assertThat(reconciled.residualDemand().get(0).totalOwned()).isEqualTo(4);    // 3 + 1
        assertThat(reconciled.residualDemand().get(0).totalPoolFulfilled()).isEqualTo(1);
        assertThat(reconciled.residualDemand().get(0).residualDemand()).isEqualTo(1);

        // Re-running reconcile is not supported in V1.
        ResponseEntity<String> secondReconcile = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/reconcile", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), String.class);
        assertThat(secondReconcile.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // GET /allocation (organizer) reads back the identical frozen snapshot.
        AllocationSummaryResponse organizerView = getAllocationForOrganizer(organizer, poolId);
        assertThat(organizerView.allocations()).hasSize(2);
        assertThat(organizerView.residualDemand()).isEqualTo(reconciled.residualDemand());

        // A plain parent calling the organizer-only endpoint is forbidden.
        ResponseEntity<String> forbidden = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/allocation", HttpMethod.GET,
                new HttpEntity<>(authHeaders(parent1)), String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Each parent's own "mine" view shows only their own student's line — never the other
        // family's, and no residual-demand aggregate at all (that's an organizer/class figure).
        List<AllocationLineResponse> parent1Mine = getMyAllocation(parent1, poolId);
        assertThat(parent1Mine).hasSize(1);
        assertThat(parent1Mine.get(0).studentFirstName()).isEqualTo("Alex");
        assertThat(parent1Mine.get(0).status()).isEqualTo("SELF_FULFILLED");
        assertThat(parent1Mine).noneMatch(l -> "Bailey".equals(l.studentFirstName()));
        assertThat(parent1Mine).noneMatch(l -> bailey.studentId().equals(l.studentId()));

        List<AllocationLineResponse> parent2Mine = getMyAllocation(parent2, poolId);
        assertThat(parent2Mine).hasSize(1);
        assertThat(parent2Mine.get(0).studentFirstName()).isEqualTo("Bailey");
        assertThat(parent2Mine.get(0).purchaseRequiredQuantity()).isEqualTo(1);
        assertThat(parent2Mine).noneMatch(l -> "Alex".equals(l.studentFirstName()));
        assertThat(parent2Mine).noneMatch(l -> alex.studentId().equals(l.studentId()));
    }

    private UUID createClassroom(TestUsers.AuthedUser organizer, String schoolName) {
        CreateClassroomRequest request = new CreateClassroomRequest(
                null, schoolName, "2026-2027", "Grade 1", "Ms. Allocation", null, 20);
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

    private void setInventory(TestUsers.AuthedUser caller, UUID poolId, UUID requirementId, UUID studentId,
                               int ownedQuantity) {
        ResponseEntity<InventoryLineResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirementId + "/inventory",
                HttpMethod.PUT, new HttpEntity<>(new SetInventoryRequest(studentId, ownedQuantity),
                        authHeaders(caller)),
                InventoryLineResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ContributionResponse offer(TestUsers.AuthedUser caller, UUID poolId, UUID requirementId, UUID studentId,
                                        int quantity) {
        ResponseEntity<ContributionResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirementId + "/contributions",
                HttpMethod.POST,
                new HttpEntity<>(new OfferContributionRequest(studentId, quantity, "DONATE"), authHeaders(caller)),
                ContributionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void markReceived(TestUsers.AuthedUser organizer, UUID poolId, UUID contributionId) {
        ResponseEntity<ContributionResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/contributions/" + contributionId + "/receive",
                HttpMethod.POST, new HttpEntity<>(authHeaders(organizer)), ContributionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private AllocationSummaryResponse reconcile(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<AllocationSummaryResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/reconcile", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), AllocationSummaryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private AllocationSummaryResponse getAllocationForOrganizer(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<AllocationSummaryResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/allocation", HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), AllocationSummaryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<AllocationLineResponse> getMyAllocation(TestUsers.AuthedUser caller, UUID poolId) {
        ResponseEntity<AllocationLineResponse[]> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/allocation/mine", HttpMethod.GET,
                new HttpEntity<>(authHeaders(caller)), AllocationLineResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private HttpHeaders authHeaders(TestUsers.AuthedUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "CLASSPOOL_SESSION=" + user.sessionToken());
        return headers;
    }
}
