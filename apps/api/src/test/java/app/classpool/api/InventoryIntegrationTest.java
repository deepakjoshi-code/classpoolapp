package app.classpool.api;

import app.classpool.api.dto.ClassroomCreatedResponse;
import app.classpool.api.dto.CreateClassroomRequest;
import app.classpool.api.dto.CreatePoolRequest;
import app.classpool.api.dto.CreateRequirementRequest;
import app.classpool.api.dto.InventoryLineResponse;
import app.classpool.api.dto.InventorySummaryResponse;
import app.classpool.api.dto.InviteResponse;
import app.classpool.api.dto.JoinInviteRequest;
import app.classpool.api.dto.MembershipResponse;
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
 * End-to-end coverage of the Phase 4 "Shop Your Home First" flow (PRD §4): recording owned
 * quantities per (requirement, student), a household's own inventory view, and the organizer
 * summary aggregate.
 */
class InventoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestUsers testUsers;

    @Test
    void fullFlow_recordingInventoryAcrossHouseholds_includingTwins_andOrganizerSummary() {
        TestUsers.AuthedUser organizer = testUsers.create("invOrg@example.com", "Inventory Organizer");
        UUID classroomId = createClassroom(organizer, "Inventory School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        RequirementResponse requirement = addRequirement(organizer, poolId, "Glue Sticks", 3);

        TestUsers.AuthedUser parent1 = testUsers.create("invParent1@example.com", "Parent One");
        TestUsers.AuthedUser parent2 = testUsers.create("invParent2@example.com", "Parent Two");

        // parent1 has two students in this classroom (twins) — parent2 has one.
        MembershipResponse alex = join(organizer, classroomId, parent1, "Alex");
        MembershipResponse casey = join(organizer, classroomId, parent1, "Casey");
        MembershipResponse bailey = join(organizer, classroomId, parent2, "Bailey");

        confirm(organizer, poolId);

        // Before recording anything, parent1's inventory already lists both of their students at
        // owned=0/stillNeeded=quantityPerStudent (a parent shouldn't have to explicitly zero
        // something out).
        List<InventoryLineResponse> parent1Initial = getInventory(parent1, poolId);
        assertThat(parent1Initial).extracting(InventoryLineResponse::studentFirstName,
                        InventoryLineResponse::ownedQuantity, InventoryLineResponse::stillNeeded)
                .containsExactlyInAnyOrder(tuple("Alex", 0, 3), tuple("Casey", 0, 3));

        setInventory(parent1, poolId, requirement.id(), alex.studentId(), 1);
        setInventory(parent1, poolId, requirement.id(), casey.studentId(), 2);
        // parent2 tries to own more than required (5) — must clamp to quantityPerStudent (3), not
        // reduce anyone else's demand (surplus offering is a separate, later action per PRD §5).
        InventoryLineResponse baileyLine = setInventory(parent2, poolId, requirement.id(), bailey.studentId(), 5);
        assertThat(baileyLine.ownedQuantity()).isEqualTo(3);
        assertThat(baileyLine.stillNeeded()).isZero();

        // GET /pools/{poolId}/inventory only ever shows the caller's own student(s).
        List<InventoryLineResponse> parent1Lines = getInventory(parent1, poolId);
        assertThat(parent1Lines).extracting(InventoryLineResponse::studentFirstName,
                        InventoryLineResponse::ownedQuantity, InventoryLineResponse::stillNeeded)
                .containsExactlyInAnyOrder(tuple("Alex", 1, 2), tuple("Casey", 2, 1));

        List<InventoryLineResponse> parent2Lines = getInventory(parent2, poolId);
        assertThat(parent2Lines).extracting(InventoryLineResponse::studentFirstName,
                        InventoryLineResponse::ownedQuantity, InventoryLineResponse::stillNeeded)
                .containsExactly(tuple("Bailey", 3, 0));

        // Organizer summary: all 3 joined students have submitted something; total owned across
        // every household is 1 + 2 + 3 = 6 (the clamp, not the raw 5); totalRequired mirrors
        // Requirement.totalDemand (3 students x 3 per student = 9).
        InventorySummaryResponse summary = getSummary(organizer, poolId);
        assertThat(summary.studentsWithInventorySubmitted()).isEqualTo(3);
        assertThat(summary.totalJoinedStudents()).isEqualTo(3);
        assertThat(summary.perRequirement()).hasSize(1);
        assertThat(summary.perRequirement().get(0).requirementId()).isEqualTo(requirement.id());
        assertThat(summary.perRequirement().get(0).totalOwned()).isEqualTo(6);
        assertThat(summary.perRequirement().get(0).totalRequired()).isEqualTo(9);
    }

    @Test
    void gettingInventory_onAStillDraftPool_returnsEmptyListRatherThanErroring() {
        TestUsers.AuthedUser organizer = testUsers.create("invDraftOrg@example.com", "Draft Organizer");
        UUID classroomId = createClassroom(organizer, "Draft Inventory School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        addRequirement(organizer, poolId, "Glue Sticks", 3);

        TestUsers.AuthedUser parent = testUsers.create("invDraftParent@example.com", "Draft Parent");
        join(organizer, classroomId, parent, "Alex");

        assertThat(getInventory(parent, poolId)).isEmpty();
    }

    @Test
    void settingInventory_onAStillDraftPool_returnsConflict() {
        TestUsers.AuthedUser organizer = testUsers.create("invConflictOrg@example.com", "Conflict Organizer");
        UUID classroomId = createClassroom(organizer, "Conflict Inventory School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        RequirementResponse requirement = addRequirement(organizer, poolId, "Glue Sticks", 3);

        TestUsers.AuthedUser parent = testUsers.create("invConflictParent@example.com", "Conflict Parent");
        MembershipResponse alex = join(organizer, classroomId, parent, "Alex");

        ResponseEntity<String> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirement.id() + "/inventory",
                HttpMethod.PUT, new HttpEntity<>(new SetInventoryRequest(alex.studentId(), 1), authHeaders(parent)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void settingInventory_forAnotherHouseholdsStudent_isForbidden() {
        TestUsers.AuthedUser organizer = testUsers.create("invForbiddenOrg@example.com", "Forbidden Organizer");
        UUID classroomId = createClassroom(organizer, "Forbidden Inventory School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        RequirementResponse requirement = addRequirement(organizer, poolId, "Glue Sticks", 3);

        TestUsers.AuthedUser parent1 = testUsers.create("invForbiddenParent1@example.com", "Forbidden Parent One");
        TestUsers.AuthedUser parent2 = testUsers.create("invForbiddenParent2@example.com", "Forbidden Parent Two");
        MembershipResponse alex = join(organizer, classroomId, parent1, "Alex");
        join(organizer, classroomId, parent2, "Bailey");

        confirm(organizer, poolId);

        // parent2 tries to record inventory against parent1's child.
        ResponseEntity<String> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirement.id() + "/inventory",
                HttpMethod.PUT, new HttpEntity<>(new SetInventoryRequest(alex.studentId(), 1), authHeaders(parent2)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void gettingSummary_asANonOrganizer_isForbidden() {
        TestUsers.AuthedUser organizer = testUsers.create("invSummaryOrg@example.com", "Summary Organizer");
        UUID classroomId = createClassroom(organizer, "Summary Inventory School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        addRequirement(organizer, poolId, "Glue Sticks", 3);

        TestUsers.AuthedUser parent = testUsers.create("invSummaryParent@example.com", "Summary Parent");
        join(organizer, classroomId, parent, "Alex");
        confirm(organizer, poolId);

        ResponseEntity<String> response = rest.exchange(baseUrl() + "/api/v1/pools/" + poolId + "/inventory/summary",
                HttpMethod.GET, new HttpEntity<>(authHeaders(parent)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private UUID createClassroom(TestUsers.AuthedUser organizer, String schoolName) {
        CreateClassroomRequest request = new CreateClassroomRequest(
                null, schoolName, "2026-2027", "Grade 1", "Ms. Inventory", null, 20);
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

    /** Joins {@code parent} to the classroom for a new student — a fresh Invite per call so two
     *  joins by the same parent (twins) each succeed independently. */
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

    private List<InventoryLineResponse> getInventory(TestUsers.AuthedUser caller, UUID poolId) {
        ResponseEntity<InventoryLineResponse[]> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/inventory", HttpMethod.GET,
                new HttpEntity<>(authHeaders(caller)), InventoryLineResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private InventoryLineResponse setInventory(TestUsers.AuthedUser caller, UUID poolId, UUID requirementId,
                                                UUID studentId, int ownedQuantity) {
        ResponseEntity<InventoryLineResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirementId + "/inventory",
                HttpMethod.PUT, new HttpEntity<>(new SetInventoryRequest(studentId, ownedQuantity),
                        authHeaders(caller)),
                InventoryLineResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private InventorySummaryResponse getSummary(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<InventorySummaryResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/inventory/summary", HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), InventorySummaryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders authHeaders(TestUsers.AuthedUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "CLASSPOOL_SESSION=" + user.sessionToken());
        return headers;
    }
}
