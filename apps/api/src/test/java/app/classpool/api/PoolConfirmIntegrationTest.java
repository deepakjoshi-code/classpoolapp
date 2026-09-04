package app.classpool.api;

import app.classpool.api.dto.ClassroomCreatedResponse;
import app.classpool.api.dto.CreateClassroomRequest;
import app.classpool.api.dto.CreatePoolRequest;
import app.classpool.api.dto.CreateRequirementRequest;
import app.classpool.api.dto.InviteResponse;
import app.classpool.api.dto.JoinInviteRequest;
import app.classpool.api.dto.MembershipResponse;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * End-to-end coverage of the Phase 3 confirm flow (PRD §3.3/§3.4/§13.3): organizer verification
 * moves every Requirement to CONFIRMED and the Pool from DRAFT to OPEN_FOR_INVENTORY, and
 * aggregate class demand is computed against the classroom's actual joined-student count, not its
 * `studentCountEstimate`.
 */
class PoolConfirmIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestUsers testUsers;

    @Test
    void confirming_movesRequirementsAndPool_andComputesTotalDemandAgainstActualJoinedStudents() {
        TestUsers.AuthedUser organizer = testUsers.create("confirmOrg@example.com", "Confirm Organizer");
        UUID classroomId = createClassroom(organizer, "Confirm School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);

        RequirementResponse glueSticks = addRequirement(organizer, poolId, "Glue Sticks", 4);
        RequirementResponse folders = addRequirement(organizer, poolId, "Folders", 1);

        // 3 joined parents/students (studentCountEstimate on the classroom was 20 — confirming
        // must use the real joined count, not that estimate).
        joinAsNewParent(organizer, classroomId, "parent1@example.com", "Alex");
        joinAsNewParent(organizer, classroomId, "parent2@example.com", "Bailey");
        joinAsNewParent(organizer, classroomId, "parent3@example.com", "Casey");

        ResponseEntity<PoolDetailResponse> confirmResponse = confirm(organizer, poolId);

        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        PoolDetailResponse detail = confirmResponse.getBody();
        assertThat(detail.state()).isEqualTo("OPEN_FOR_INVENTORY");
        assertThat(detail.requirements()).extracting(RequirementResponse::state)
                .containsOnly("CONFIRMED");
        assertThat(detail.requirements())
                .extracting(RequirementResponse::name, RequirementResponse::totalDemand)
                .containsExactlyInAnyOrder(tuple("Glue Sticks", 12), tuple("Folders", 3));

        // Confirming twice is a one-time transition (contract: 409 "Pool already confirmed").
        ResponseEntity<String> secondConfirm = confirmRaw(organizer, poolId);
        assertThat(secondConfirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void totalDemand_doesNotChange_whenAFamilyJoinsAfterConfirm() {
        // Regression test for a real bug found in integration review: totalDemand was originally
        // computed live from current Membership rows on every read, which meant a family joining
        // after confirmation (e.g. a late joiner, PRD §13.3) would silently inflate an
        // already-"confirmed" requirement's total — exactly the moving target the residual-demand
        // engine (Phase 6/7) cannot be built against. Migration V2 + Pool.confirmedStudentCount
        // freeze it at confirm time instead; this proves that holds.
        TestUsers.AuthedUser organizer = testUsers.create("frozenOrg@example.com", "Frozen Organizer");
        UUID classroomId = createClassroom(organizer, "Frozen School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        addRequirement(organizer, poolId, "Pencils", 2);

        joinAsNewParent(organizer, classroomId, "frozenParent1@example.com", "Alex");
        joinAsNewParent(organizer, classroomId, "frozenParent2@example.com", "Bailey");

        ResponseEntity<PoolDetailResponse> confirmResponse = confirm(organizer, poolId);
        assertThat(confirmResponse.getBody().requirements())
                .extracting(RequirementResponse::totalDemand)
                .containsExactly(4); // 2 students x 2 per student

        // A late joiner arrives after confirmation — must not retroactively change the total above.
        joinAsNewParent(organizer, classroomId, "frozenParent3@example.com", "Casey");

        ResponseEntity<PoolDetailResponse> afterLateJoin = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId, HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), PoolDetailResponse.class);
        assertThat(afterLateJoin.getBody().requirements())
                .extracting(RequirementResponse::totalDemand)
                .containsExactly(4); // still 4, not 6 — frozen, not recomputed live
    }

    @Test
    void confirming_anEmptyPool_returnsConflict() {
        TestUsers.AuthedUser organizer = testUsers.create("emptyPoolOrg@example.com", "Empty Pool Organizer");
        UUID classroomId = createClassroom(organizer, "Empty Pool School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);

        ResponseEntity<String> response = confirmRaw(organizer, poolId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void addingEditingRemovingRequirements_afterConfirm_returnsConflict() {
        TestUsers.AuthedUser organizer = testUsers.create("lockedPoolOrg@example.com", "Locked Pool Organizer");
        UUID classroomId = createClassroom(organizer, "Locked Pool School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        RequirementResponse requirement = addRequirement(organizer, poolId, "Glue Sticks", 4);

        ResponseEntity<PoolDetailResponse> confirmResponse = confirm(organizer, poolId);
        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders headers = authHeaders(organizer);

        ResponseEntity<String> addAfterConfirm = rest.exchange(baseUrl() + "/api/v1/pools/" + poolId + "/requirements",
                HttpMethod.POST, new HttpEntity<>(new CreateRequirementRequest("Extra", 1, null, null), headers),
                String.class);
        assertThat(addAfterConfirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> editAfterConfirm = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirement.id(), HttpMethod.PATCH,
                new HttpEntity<>(new CreateRequirementRequest("Renamed", 4, null, null), headers), String.class);
        assertThat(editAfterConfirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> removeAfterConfirm = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirement.id(), HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
        assertThat(removeAfterConfirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void addingEditingRemovingRequirements_asANonOrganizer_isForbidden() {
        TestUsers.AuthedUser organizer = testUsers.create("nonOrgPoolOrg@example.com", "Non-Org Pool Organizer");
        TestUsers.AuthedUser outsider = testUsers.create("nonOrgOutsider@example.com", "Outsider Parent");
        UUID classroomId = createClassroom(organizer, "Non-Organizer School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        RequirementResponse requirement = addRequirement(organizer, poolId, "Glue Sticks", 4);

        HttpHeaders outsiderHeaders = authHeaders(outsider);

        ResponseEntity<String> createPoolAsOutsider = rest.exchange(
                baseUrl() + "/api/v1/classrooms/" + classroomId + "/pools", HttpMethod.POST,
                new HttpEntity<>(new CreatePoolRequest("Sneaky Pool", null), outsiderHeaders), String.class);
        assertThat(createPoolAsOutsider.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> addAsOutsider = rest.exchange(baseUrl() + "/api/v1/pools/" + poolId + "/requirements",
                HttpMethod.POST, new HttpEntity<>(new CreateRequirementRequest("Extra", 1, null, null),
                        outsiderHeaders), String.class);
        assertThat(addAsOutsider.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> editAsOutsider = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirement.id(), HttpMethod.PATCH,
                new HttpEntity<>(new CreateRequirementRequest("Renamed", 4, null, null), outsiderHeaders),
                String.class);
        assertThat(editAsOutsider.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> removeAsOutsider = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirement.id(), HttpMethod.DELETE,
                new HttpEntity<>(outsiderHeaders), String.class);
        assertThat(removeAsOutsider.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private UUID createClassroom(TestUsers.AuthedUser organizer, String schoolName) {
        CreateClassroomRequest request = new CreateClassroomRequest(
                null, schoolName, "2026-2027", "Grade 1", "Ms. Confirm", null, 20);
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
        assertThat(response.getBody().state()).isEqualTo("DRAFT");
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

    private void joinAsNewParent(TestUsers.AuthedUser organizer, UUID classroomId, String email,
                                  String studentFirstName) {
        TestUsers.AuthedUser parent = testUsers.create(email, studentFirstName + "'s Parent");
        String token = createInvite(organizer, classroomId);
        ResponseEntity<MembershipResponse> response = rest.exchange(
                baseUrl() + "/api/v1/invites/" + token + "/join", HttpMethod.POST,
                new HttpEntity<>(new JoinInviteRequest(studentFirstName), authHeaders(parent)),
                MembershipResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private String createInvite(TestUsers.AuthedUser organizer, UUID classroomId) {
        ResponseEntity<InviteResponse> response = rest.exchange(
                baseUrl() + "/api/v1/classrooms/" + classroomId + "/invites", HttpMethod.POST,
                new HttpEntity<>(null, authHeaders(organizer)), InviteResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().token();
    }

    private HttpHeaders authHeaders(TestUsers.AuthedUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "CLASSPOOL_SESSION=" + user.sessionToken());
        return headers;
    }

    private ResponseEntity<PoolDetailResponse> confirm(TestUsers.AuthedUser organizer, UUID poolId) {
        return rest.exchange(baseUrl() + "/api/v1/pools/" + poolId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), PoolDetailResponse.class);
    }

    private ResponseEntity<String> confirmRaw(TestUsers.AuthedUser organizer, UUID poolId) {
        return rest.exchange(baseUrl() + "/api/v1/pools/" + poolId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), String.class);
    }
}
