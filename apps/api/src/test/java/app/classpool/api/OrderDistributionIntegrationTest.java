package app.classpool.api;

import app.classpool.api.dto.AddProductOfferRequest;
import app.classpool.api.dto.AllocationSummaryResponse;
import app.classpool.api.dto.ClassReserveEntryResponse;
import app.classpool.api.dto.ClassroomCreatedResponse;
import app.classpool.api.dto.CreateClassroomRequest;
import app.classpool.api.dto.CreatePoolRequest;
import app.classpool.api.dto.CreateRequirementRequest;
import app.classpool.api.dto.DistributionItemResponse;
import app.classpool.api.dto.DistributionSummaryResponse;
import app.classpool.api.dto.FinalizePaymentsRequest;
import app.classpool.api.dto.GenerateDistributionRequest;
import app.classpool.api.dto.InviteResponse;
import app.classpool.api.dto.JoinInviteRequest;
import app.classpool.api.dto.MembershipResponse;
import app.classpool.api.dto.OrderResponse;
import app.classpool.api.dto.OrganizerStripeAccountResponse;
import app.classpool.api.dto.PayPaymentRequest;
import app.classpool.api.dto.PaymentResponse;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.dto.PoolResponse;
import app.classpool.api.dto.ProductOfferResponse;
import app.classpool.api.dto.PurchasePlanResponse;
import app.classpool.api.dto.RecordOrderLineRequest;
import app.classpool.api.dto.RecordOrderRequest;
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

/**
 * End-to-end coverage of Phase 10's ordering/distribution surface (PRD §9.1-9.4), mirroring {@code
 * PaymentIntegrationTest}'s structure: from an {@code ORDERED} pool (built the same way {@code
 * PaymentIntegrationTest} gets there — onboard, generate payments, both households pay in full,
 * finalize), through recording an order with one substitution that triggers a top-up charge,
 * generating the distribution batch (with waste banked to Class Reserve), marking one item
 * delivered, reading Class Reserve back, and completing the pool.
 */
class OrderDistributionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestUsers testUsers;

    @Test
    void fullFlow_recordOrderWithTopUp_generateDistribution_deliverItem_classReserve_complete() {
        TestUsers.AuthedUser organizer = testUsers.create("distOrg@example.com", "Distribution Organizer");
        UUID classroomId = createClassroom(organizer, "Distribution School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        RequirementResponse pencils = addRequirement(organizer, poolId, "Pencils", 4);

        TestUsers.AuthedUser parent1 = testUsers.create("distParent1@example.com", "Distribution Parent One");
        TestUsers.AuthedUser parent2 = testUsers.create("distParent2@example.com", "Distribution Parent Two");
        join(organizer, classroomId, parent1, "Alex");
        join(organizer, classroomId, parent2, "Bailey");

        confirm(organizer, poolId);
        AllocationSummaryResponse reconciled = reconcile(organizer, poolId);
        assertThat(reconciled.residualDemand().get(0).residualDemand()).isEqualTo(8); // 4 + 4, no inventory/pool

        // Only a 12-pack is offered for 8 units needed -> 4 units of waste (feeds Class Reserve
        // later), planned total cost 900.
        addOffer(organizer, poolId, pencils.id(), "Amazon", 12, 900);
        PurchasePlanResponse plan = generatePlan(organizer, poolId);
        assertThat(plan.totalCostCents()).isEqualTo(900);
        assertThat(plan.lines().get(0).wasteQuantity()).isEqualTo(4);
        UUID purchasePlanLineId = plan.lines().get(0).id();
        approvePlan(organizer, poolId);

        startOnboarding(organizer, classroomId);
        completeOnboarding(organizer, classroomId);

        List<PaymentResponse> generated = generatePayments(organizer, poolId);
        assertThat(generated).hasSize(2);
        // unitCostCents = round(900/8) = 113; each household owes 4 units -> 452 each.
        assertThat(generated).extracting(PaymentResponse::amountCents).containsExactly(452, 452);

        PaymentResponse myPayment1 = getMyPayment(parent1, poolId);
        PaymentResponse myPayment2 = getMyPayment(parent2, poolId);
        payMyPayment(parent1, poolId, myPayment1.id(), "CARD");
        payMyPayment(parent2, poolId, myPayment2.id(), "CARD");

        // 904/904 collected -> 100%, finalize needs no acknowledgement.
        ResponseEntity<PoolDetailResponse> finalized = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/finalize", HttpMethod.POST,
                new HttpEntity<>(new FinalizePaymentsRequest(false), authHeaders(organizer)),
                PoolDetailResponse.class);
        assertThat(finalized.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(finalized.getBody().state()).isEqualTo("ORDERED");

        // ---- recordOrder: a substitution well past the 10% threshold triggers a top-up ----

        RecordOrderRequest orderRequest = new RecordOrderRequest("receipts/order-1.pdf",
                List.of(new RecordOrderLineRequest(purchasePlanLineId, 1200, "premium pencils")));

        OrderResponse order = recordOrder(organizer, poolId, orderRequest);
        assertThat(order.lines()).hasSize(1);
        assertThat(order.lines().get(0).plannedCostCents()).isEqualTo(900);
        assertThat(order.lines().get(0).actualCostCents()).isEqualTo(1200);
        assertThat(order.lines().get(0).substitutionDeltaCents()).isEqualTo(300);
        assertThat(order.lines().get(0).substitutionResolution()).isEqualTo("TOP_UP_CHARGED");

        // GET reads back the same recorded order.
        OrderResponse fetchedOrder = getOrder(organizer, poolId);
        assertThat(fetchedOrder.id()).isEqualTo(order.id());
        assertThat(fetchedOrder.receiptS3Key()).isEqualTo("receipts/order-1.pdf");

        // Recording a second order for the same pool is a 409.
        ResponseEntity<String> secondOrder = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/order", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), String.class);
        assertThat(secondOrder.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // The top-up split 300 cents proportionally by purchaseRequiredQuantity (4 each of 8) ->
        // 150/150, one new PENDING Payment per household alongside the two already-PAID originals.
        List<PaymentResponse> allPayments = listPaymentsForOrganizer(organizer, poolId);
        assertThat(allPayments).hasSize(4);
        List<PaymentResponse> pendingTopUps = allPayments.stream()
                .filter(p -> "PENDING".equals(p.state())).toList();
        assertThat(pendingTopUps).hasSize(2);
        assertThat(pendingTopUps).extracting(PaymentResponse::amountCents).containsExactly(150, 150);

        // ---- generateDistribution ----

        DistributionSummaryResponse distribution = generateDistribution(organizer, poolId, "CLASSROOM_DESK");
        assertThat(distribution.mode()).isEqualTo("CLASSROOM_DESK");
        assertThat(distribution.items()).hasSize(2); // both students need a physical hand-off
        assertThat(distribution.items()).allSatisfy(item -> assertThat(item.quantity()).isEqualTo(4));
        assertThat(distribution.pickLists()).hasSize(2); // two distinct households
        assertThat(distribution.pickLists()).allSatisfy(pl -> {
            assertThat(pl.lines()).hasSize(1);
            assertThat(pl.lines().get(0).requirementName()).isEqualTo("Pencils");
            assertThat(pl.lines().get(0).quantity()).isEqualTo(4);
        });
        assertThat(getPool(organizer, poolId).state()).isEqualTo("DISTRIBUTING");

        // Re-generating is a 409.
        ResponseEntity<String> secondGenerate = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/distribution/generate", HttpMethod.POST,
                new HttpEntity<>(new GenerateDistributionRequest("CLASSROOM_DESK"), authHeaders(organizer)),
                String.class);
        assertThat(secondGenerate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // ---- mark one item delivered ----

        UUID firstItemId = distribution.items().get(0).id();
        DistributionItemResponse delivered = markDelivered(organizer, poolId, firstItemId);
        assertThat(delivered.deliveredAt()).isNotNull();

        // Marking the same item delivered again is a 409.
        ResponseEntity<String> secondDeliver = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/distribution/items/" + firstItemId + "/deliver",
                HttpMethod.POST, new HttpEntity<>(authHeaders(organizer)), String.class);
        assertThat(secondDeliver.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Each parent's "mine" view shows only their own student's item.
        List<DistributionItemResponse> parent1Items = getMyDistribution(parent1, poolId);
        assertThat(parent1Items).hasSize(1);
        assertThat(parent1Items.get(0).studentFirstName()).isEqualTo("Alex");

        // ---- Class Reserve, from the 4 units of pack waste ----

        List<ClassReserveEntryResponse> reserve = getClassReserve(organizer, poolId);
        assertThat(reserve).hasSize(1);
        assertThat(reserve.get(0).classroomId()).isEqualTo(classroomId);
        assertThat(reserve.get(0).itemName()).isEqualTo("Pencils");
        assertThat(reserve.get(0).quantity()).isEqualTo(4);
        assertThat(reserve.get(0).custodianLocation()).isNull(); // V1 gap — never set

        // ---- complete ----

        ResponseEntity<PoolDetailResponse> completed = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/complete", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), PoolDetailResponse.class);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody().state()).isEqualTo("COMPLETED");
    }

    // ---- fixtures (mirroring PaymentIntegrationTest's helpers) ----

    private UUID createClassroom(TestUsers.AuthedUser organizer, String schoolName) {
        CreateClassroomRequest request = new CreateClassroomRequest(
                null, schoolName, "2026-2027", "Grade 1", "Ms. Ledger", null, 20);
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
                                           String retailer, int packQuantity, int priceCents) {
        ResponseEntity<ProductOfferResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirementId + "/product-offers",
                HttpMethod.POST,
                new HttpEntity<>(new AddProductOfferRequest(retailer, packQuantity, priceCents, null, null),
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

    private void approvePlan(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<PurchasePlanResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/purchase-plan/approve", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), PurchasePlanResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private OrganizerStripeAccountResponse startOnboarding(TestUsers.AuthedUser organizer, UUID classroomId) {
        ResponseEntity<OrganizerStripeAccountResponse> response = rest.exchange(
                baseUrl() + "/api/v1/classrooms/" + classroomId + "/stripe-onboarding", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), OrganizerStripeAccountResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private OrganizerStripeAccountResponse completeOnboarding(TestUsers.AuthedUser organizer, UUID classroomId) {
        ResponseEntity<OrganizerStripeAccountResponse> response = rest.exchange(
                baseUrl() + "/api/v1/classrooms/" + classroomId + "/stripe-onboarding/complete", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), OrganizerStripeAccountResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<PaymentResponse> generatePayments(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<PaymentResponse[]> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/generate", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), PaymentResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private List<PaymentResponse> listPaymentsForOrganizer(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<PaymentResponse[]> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments", HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), PaymentResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private PaymentResponse getMyPayment(TestUsers.AuthedUser parent, UUID poolId) {
        ResponseEntity<PaymentResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/mine", HttpMethod.GET,
                new HttpEntity<>(authHeaders(parent)), PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void payMyPayment(TestUsers.AuthedUser parent, UUID poolId, UUID paymentId, String method) {
        ResponseEntity<PaymentResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/" + paymentId + "/pay", HttpMethod.POST,
                new HttpEntity<>(new PayPaymentRequest(method), authHeaders(parent)), PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private OrderResponse recordOrder(TestUsers.AuthedUser organizer, UUID poolId, RecordOrderRequest request) {
        ResponseEntity<OrderResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/order", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(organizer)), OrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private OrderResponse getOrder(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<OrderResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/order", HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), OrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private DistributionSummaryResponse generateDistribution(TestUsers.AuthedUser organizer, UUID poolId,
                                                               String mode) {
        ResponseEntity<DistributionSummaryResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/distribution/generate", HttpMethod.POST,
                new HttpEntity<>(new GenerateDistributionRequest(mode), authHeaders(organizer)),
                DistributionSummaryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<DistributionItemResponse> getMyDistribution(TestUsers.AuthedUser parent, UUID poolId) {
        ResponseEntity<DistributionItemResponse[]> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/distribution/mine", HttpMethod.GET,
                new HttpEntity<>(authHeaders(parent)), DistributionItemResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private DistributionItemResponse markDelivered(TestUsers.AuthedUser organizer, UUID poolId, UUID itemId) {
        ResponseEntity<DistributionItemResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/distribution/items/" + itemId + "/deliver",
                HttpMethod.POST, new HttpEntity<>(authHeaders(organizer)), DistributionItemResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<ClassReserveEntryResponse> getClassReserve(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<ClassReserveEntryResponse[]> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/class-reserve", HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), ClassReserveEntryResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private PoolDetailResponse getPool(TestUsers.AuthedUser caller, UUID poolId) {
        ResponseEntity<PoolDetailResponse> response = rest.exchange(baseUrl() + "/api/v1/pools/" + poolId,
                HttpMethod.GET, new HttpEntity<>(authHeaders(caller)), PoolDetailResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders authHeaders(TestUsers.AuthedUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "CLASSPOOL_SESSION=" + user.sessionToken());
        return headers;
    }
}
