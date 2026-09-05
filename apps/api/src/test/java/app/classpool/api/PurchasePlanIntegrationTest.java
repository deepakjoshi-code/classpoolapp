package app.classpool.api;

import app.classpool.api.dto.AddProductOfferRequest;
import app.classpool.api.dto.AllocationSummaryResponse;
import app.classpool.api.dto.ClassroomCreatedResponse;
import app.classpool.api.dto.CreateClassroomRequest;
import app.classpool.api.dto.CreatePoolRequest;
import app.classpool.api.dto.CreateRequirementRequest;
import app.classpool.api.dto.InviteResponse;
import app.classpool.api.dto.JoinInviteRequest;
import app.classpool.api.dto.MembershipResponse;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.dto.PoolResponse;
import app.classpool.api.dto.ProductOfferResponse;
import app.classpool.api.dto.PurchasePlanLineResponse;
import app.classpool.api.dto.PurchasePlanResponse;
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
 * End-to-end coverage of Phase 8's bulk-pack optimizer (PRD §7.1/§9.4), mirroring
 * {@code AllocationIntegrationTest}'s structure: reconcile a pool down to a residual demand,
 * enter the PRD's own worked-example offers for it, generate the purchase plan over real HTTP,
 * read it back, and approve it — checking along the way that approval never touches the pool's
 * own state (Phase 9's job).
 */
class PurchasePlanIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestUsers testUsers;

    @Test
    void fullFlow_reconcileAddOffersGenerateGetApprove_matchesPrdWorkedExample() {
        TestUsers.AuthedUser organizer = testUsers.create("planOrg@example.com", "Plan Organizer");
        UUID classroomId = createClassroom(organizer, "Purchase Plan School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        RequirementResponse pencils = addRequirement(organizer, poolId, "Pencils", 320);

        TestUsers.AuthedUser parent = testUsers.create("planParent@example.com", "Plan Parent");
        join(organizer, classroomId, parent, "Riley");

        confirm(organizer, poolId);

        // Nobody owns any pencils and there's no pool supply — reconcile leaves the full 320 as
        // residual demand for one student.
        AllocationSummaryResponse reconciled = reconcile(organizer, poolId);
        assertThat(reconciled.residualDemand()).hasSize(1);
        assertThat(reconciled.residualDemand().get(0).residualDemand()).isEqualTo(320);

        // Before any offers exist, generate 409s naming the requirement.
        ResponseEntity<String> generateWithNoOffers = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/purchase-plan/generate", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), String.class);
        assertThat(generateWithNoOffers.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(generateWithNoOffers.getBody()).contains("Pencils");

        // Before any plan exists, GET 409s too.
        ResponseEntity<String> noPlanYet = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/purchase-plan", HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), String.class);
        assertThat(noPlanYet.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // The PRD §7.1 worked example's three candidate offers for 320 pencils.
        ProductOfferResponse pack24 = addOffer(organizer, poolId, pencils.id(), "Amazon", 24, 499, null);
        ProductOfferResponse pack48 = addOffer(organizer, poolId, pencils.id(), "Amazon", 48, 849, null);
        ProductOfferResponse pack144 = addOffer(organizer, poolId, pencils.id(), "Amazon", 144, 1899, null);

        // listProductOffers (organizer-only) sees all three.
        ResponseEntity<ProductOfferResponse[]> listed = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/product-offers", HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), ProductOfferResponse[].class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).hasSize(3);

        // A plain parent may not call the organizer-only offer-listing endpoint.
        ResponseEntity<String> forbiddenList = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/product-offers", HttpMethod.GET,
                new HttpEntity<>(authHeaders(parent)), String.class);
        assertThat(forbiddenList.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        PurchasePlanResponse generated = generatePlan(organizer, poolId);
        assertThat(generated.state()).isEqualTo("PROPOSED");
        assertThat(generated.totalCostCents()).isEqualTo(4647);
        assertThat(generated.lines()).hasSize(2);
        assertThat(generated.lines()).extracting(PurchasePlanLineResponse::productOfferId,
                        PurchasePlanLineResponse::packCount)
                .containsExactlyInAnyOrder(
                        tuple(pack144.id(), 2),
                        tuple(pack48.id(), 1));
        int totalWaste = generated.lines().stream().mapToInt(PurchasePlanLineResponse::wasteQuantity).sum();
        assertThat(totalWaste).isEqualTo(16);
        assertThat(generated.lines()).filteredOn(l -> l.wasteQuantity() > 0).hasSize(1);
        assertThat(generated.proposedAt()).isNotNull();
        assertThat(generated.approvedAt()).isNull();

        // Pool moved RECONCILING -> PURCHASE_PROPOSED.
        ResponseEntity<PoolDetailResponse> poolAfterGenerate = rest.exchange(baseUrl() + "/api/v1/pools/" + poolId,
                HttpMethod.GET, new HttpEntity<>(authHeaders(organizer)), PoolDetailResponse.class);
        assertThat(poolAfterGenerate.getBody().state()).isEqualTo("PURCHASE_PROPOSED");

        // Re-generating is not supported in V1.
        ResponseEntity<String> secondGenerate = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/purchase-plan/generate", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), String.class);
        assertThat(secondGenerate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Removing an offer once a plan exists is no longer supported.
        ResponseEntity<String> removeAfterGenerate = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/product-offers/" + pack24.id(), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(organizer)), String.class);
        assertThat(removeAfterGenerate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // GET reads back the identical frozen plan.
        PurchasePlanResponse fetched = getPlan(organizer, poolId);
        assertThat(fetched.id()).isEqualTo(generated.id());
        assertThat(fetched.totalCostCents()).isEqualTo(4647);
        assertThat(fetched.lines()).hasSameSizeAs(generated.lines());

        // Approve: PROPOSED -> APPROVED, without touching the pool's own state.
        PurchasePlanResponse approved = approvePlan(organizer, poolId);
        assertThat(approved.state()).isEqualTo("APPROVED");
        assertThat(approved.approvedAt()).isNotNull();

        ResponseEntity<PoolDetailResponse> poolAfterApprove = rest.exchange(baseUrl() + "/api/v1/pools/" + poolId,
                HttpMethod.GET, new HttpEntity<>(authHeaders(organizer)), PoolDetailResponse.class);
        assertThat(poolAfterApprove.getBody().state()).isEqualTo("PURCHASE_PROPOSED"); // unchanged

        // Approving a second time is a 409.
        ResponseEntity<String> secondApprove = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/purchase-plan/approve", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), String.class);
        assertThat(secondApprove.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private UUID createClassroom(TestUsers.AuthedUser organizer, String schoolName) {
        CreateClassroomRequest request = new CreateClassroomRequest(
                null, schoolName, "2026-2027", "Grade 1", "Ms. Optimizer", null, 20);
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

    private AllocationSummaryResponse reconcile(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<AllocationSummaryResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/reconcile", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), AllocationSummaryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ProductOfferResponse addOffer(TestUsers.AuthedUser organizer, UUID poolId, UUID requirementId,
                                           String retailer, int packQuantity, int priceCents, Integer shippingCents) {
        ResponseEntity<ProductOfferResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirementId + "/product-offers",
                HttpMethod.POST,
                new HttpEntity<>(new AddProductOfferRequest(retailer, packQuantity, priceCents, shippingCents, null),
                        authHeaders(organizer)),
                ProductOfferResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private PurchasePlanResponse generatePlan(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<PurchasePlanResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/purchase-plan/generate", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), PurchasePlanResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private PurchasePlanResponse getPlan(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<PurchasePlanResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/purchase-plan", HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), PurchasePlanResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private PurchasePlanResponse approvePlan(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<PurchasePlanResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/purchase-plan/approve", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), PurchasePlanResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders authHeaders(TestUsers.AuthedUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "CLASSPOOL_SESSION=" + user.sessionToken());
        return headers;
    }
}
