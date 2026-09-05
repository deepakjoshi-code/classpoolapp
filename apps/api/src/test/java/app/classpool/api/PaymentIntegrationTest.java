package app.classpool.api;

import app.classpool.api.dto.AddProductOfferRequest;
import app.classpool.api.dto.AllocationSummaryResponse;
import app.classpool.api.dto.ClassroomCreatedResponse;
import app.classpool.api.dto.CreateClassroomRequest;
import app.classpool.api.dto.CreatePoolRequest;
import app.classpool.api.dto.CreateRequirementRequest;
import app.classpool.api.dto.FinalizePaymentsRequest;
import app.classpool.api.dto.InviteResponse;
import app.classpool.api.dto.JoinInviteRequest;
import app.classpool.api.dto.MembershipResponse;
import app.classpool.api.dto.OrganizerStripeAccountResponse;
import app.classpool.api.dto.PayPaymentRequest;
import app.classpool.api.dto.PaymentResponse;
import app.classpool.api.dto.PaymentsSummaryResponse;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.dto.PoolResponse;
import app.classpool.api.dto.ProductOfferResponse;
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
 * End-to-end coverage of Phase 9's payment allocation surface (PRD §8.1-8.4), mirroring {@code
 * PurchasePlanIntegrationTest}'s structure: from an approved purchase plan, through Stripe
 * onboarding (stubbed), payment generation, one household paying by card and the other going cash
 * (deliberately left uncollected so the pool sits below the 90% threshold — a 2-household test
 * pool wouldn't reach it by design, exactly what {@code finalize}'s acknowledge-override path
 * exists for), the summary reflecting both, and finalize moving the pool to {@code ORDERED}.
 */
class PaymentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestUsers testUsers;

    @Test
    void fullFlow_onboardGenerateCardAndCashFinalizeBelowThresholdWithAck() {
        TestUsers.AuthedUser organizer = testUsers.create("paymentOrg@example.com", "Payment Organizer");
        UUID classroomId = createClassroom(organizer, "Payment School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        RequirementResponse pencils = addRequirement(organizer, poolId, "Pencils", 4);

        TestUsers.AuthedUser parent1 = testUsers.create("paymentParent1@example.com", "Payment Parent One");
        TestUsers.AuthedUser parent2 = testUsers.create("paymentParent2@example.com", "Payment Parent Two");
        join(organizer, classroomId, parent1, "Alex");
        join(organizer, classroomId, parent2, "Bailey");

        confirm(organizer, poolId);

        // generatePayments before an approved plan/active Stripe account exists is a 409.
        ResponseEntity<String> tooEarly = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/generate", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), String.class);
        assertThat(tooEarly.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        AllocationSummaryResponse reconciled = reconcile(organizer, poolId);
        assertThat(reconciled.residualDemand()).hasSize(1);
        assertThat(reconciled.residualDemand().get(0).residualDemand()).isEqualTo(8); // 4 + 4, no inventory/pool

        addOffer(organizer, poolId, pencils.id(), "Amazon", 8, 800); // exact fit, zero waste
        PurchasePlanResponse plan = generatePlan(organizer, poolId);
        assertThat(plan.totalCostCents()).isEqualTo(800);
        approvePlan(organizer, poolId);

        // Stripe onboarding: PENDING with a URL, then completed to ACTIVE with none.
        OrganizerStripeAccountResponse started = startOnboarding(organizer, classroomId);
        assertThat(started.status()).isEqualTo("PENDING");
        assertThat(started.onboardingUrl()).isNotBlank();

        // Idempotent: calling again while PENDING returns the same account, not a new one.
        OrganizerStripeAccountResponse startedAgain = startOnboarding(organizer, classroomId);
        assertThat(startedAgain.status()).isEqualTo("PENDING");

        OrganizerStripeAccountResponse completed = completeOnboarding(organizer, classroomId);
        assertThat(completed.status()).isEqualTo("ACTIVE");
        assertThat(completed.onboardingUrl()).isNull();

        OrganizerStripeAccountResponse status = getOnboardingStatus(organizer, classroomId);
        assertThat(status.status()).isEqualTo("ACTIVE");

        // Still gated: no approved-plan-and-active-Stripe check bypasses payments-already-exist.
        var generated = generatePayments(organizer, poolId);
        assertThat(generated).hasSize(2);
        assertThat(generated).extracting(PaymentResponse::amountCents).containsExactly(400, 400);
        assertThat(generated).allSatisfy(p -> assertThat(p.state()).isEqualTo("PENDING"));
        assertThat(generated).allSatisfy(p -> assertThat(p.method()).isNull()); // placeholder suppressed

        // Re-generating is not supported in V1.
        ResponseEntity<String> secondGenerate = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/generate", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), String.class);
        assertThat(secondGenerate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Pool moved PURCHASE_PROPOSED -> PAYMENT_OPEN.
        assertThat(getPool(organizer, poolId).state()).isEqualTo("PAYMENT_OPEN");

        PaymentResponse myPaymentParent1 = getMyPayment(parent1, poolId);
        PaymentResponse myPaymentParent2 = getMyPayment(parent2, poolId);
        assertThat(myPaymentParent1.householdDisplayName()).isNull(); // never shown on "mine"
        assertThat(myPaymentParent2.amountCents()).isEqualTo(400);

        // Parent2 may not pay parent1's payment.
        ResponseEntity<String> wrongHousehold = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/" + myPaymentParent1.id() + "/pay",
                HttpMethod.POST, new HttpEntity<>(new PayPaymentRequest("CARD"), authHeaders(parent2)), String.class);
        assertThat(wrongHousehold.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Parent1 pays by card (stub Stripe).
        PaymentResponse paid = payMyPayment(parent1, poolId, myPaymentParent1.id(), "CARD");
        assertThat(paid.state()).isEqualTo("PAID");
        assertThat(paid.method()).isEqualTo("CARD");

        // Paying again is a 409 (no longer PENDING).
        ResponseEntity<String> payAgain = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/" + myPaymentParent1.id() + "/pay",
                HttpMethod.POST, new HttpEntity<>(new PayPaymentRequest("CARD"), authHeaders(parent1)), String.class);
        assertThat(payAgain.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Organizer records parent2's household as going cash — left PENDING_CASH (not yet
        // received), so the pool stays below the 90% threshold by design.
        PaymentResponse cashPending = markCashPending(organizer, poolId, myPaymentParent2.id());
        assertThat(cashPending.state()).isEqualTo("PENDING_CASH");
        assertThat(cashPending.method()).isEqualTo("CASH");

        // Organizer's full listing includes household identity; "mine" never does.
        var organizerListing = listPaymentsForOrganizer(organizer, poolId);
        assertThat(organizerListing).hasSize(2);
        assertThat(organizerListing).extracting(PaymentResponse::householdDisplayName)
                .containsExactlyInAnyOrder("Payment Parent One", "Payment Parent Two");

        PaymentsSummaryResponse summary = getSummary(organizer, poolId);
        assertThat(summary.totalOwedCents()).isEqualTo(800);
        assertThat(summary.totalCollectedCents()).isEqualTo(400);
        assertThat(summary.percentCollected()).isEqualTo(50.0);
        assertThat(summary.thresholdPercent()).isEqualTo(90.0);
        assertThat(summary.meetsThreshold()).isFalse();
        assertThat(summary.outstandingHouseholds()).hasSize(1);
        assertThat(summary.outstandingHouseholds().get(0)).extracting(o -> tuple(o.amountCents(),
                        o.householdDisplayName()))
                .isEqualTo(tuple(400, "Payment Parent Two"));

        // Below threshold, finalize without acknowledgement 409s.
        ResponseEntity<String> finalizeWithoutAck = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/finalize", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), String.class);
        assertThat(finalizeWithoutAck.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Refund is still available (pool hasn't reached ORDERED yet) — exercised then left as-is
        // isn't needed for this flow; finalize with the override instead.
        ResponseEntity<PoolDetailResponse> finalized = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/finalize", HttpMethod.POST,
                new HttpEntity<>(new FinalizePaymentsRequest(true), authHeaders(organizer)),
                PoolDetailResponse.class);
        assertThat(finalized.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(finalized.getBody().state()).isEqualTo("ORDERED");

        // Refunding after ORDERED is now a 409.
        ResponseEntity<String> refundAfterOrdered = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/" + myPaymentParent1.id() + "/refund",
                HttpMethod.POST, new HttpEntity<>(authHeaders(organizer)), String.class);
        assertThat(refundAfterOrdered.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- fixtures (mirroring PurchasePlanIntegrationTest's helpers) ----

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

    private OrganizerStripeAccountResponse getOnboardingStatus(TestUsers.AuthedUser organizer, UUID classroomId) {
        ResponseEntity<OrganizerStripeAccountResponse> response = rest.exchange(
                baseUrl() + "/api/v1/classrooms/" + classroomId + "/stripe-onboarding/status", HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), OrganizerStripeAccountResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private PaymentResponse[] generatePayments(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<PaymentResponse[]> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/generate", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), PaymentResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private PaymentResponse[] listPaymentsForOrganizer(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<PaymentResponse[]> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments", HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), PaymentResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private PaymentResponse getMyPayment(TestUsers.AuthedUser parent, UUID poolId) {
        ResponseEntity<PaymentResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/mine", HttpMethod.GET,
                new HttpEntity<>(authHeaders(parent)), PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private PaymentResponse payMyPayment(TestUsers.AuthedUser parent, UUID poolId, UUID paymentId, String method) {
        ResponseEntity<PaymentResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/" + paymentId + "/pay", HttpMethod.POST,
                new HttpEntity<>(new PayPaymentRequest(method), authHeaders(parent)), PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private PaymentResponse markCashPending(TestUsers.AuthedUser organizer, UUID poolId, UUID paymentId) {
        ResponseEntity<PaymentResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/" + paymentId + "/mark-cash-pending",
                HttpMethod.POST, new HttpEntity<>(authHeaders(organizer)), PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private PaymentsSummaryResponse getSummary(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<PaymentsSummaryResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/summary", HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), PaymentsSummaryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
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
