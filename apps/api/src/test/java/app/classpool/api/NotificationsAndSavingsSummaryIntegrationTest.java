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
import app.classpool.api.dto.NotificationResponse;
import app.classpool.api.dto.OrganizerStripeAccountResponse;
import app.classpool.api.dto.PaymentResponse;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.dto.PoolResponse;
import app.classpool.api.dto.ProductOfferResponse;
import app.classpool.api.dto.PurchasePlanResponse;
import app.classpool.api.dto.RequirementResponse;
import app.classpool.api.dto.SavingsSummaryResponse;
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

/**
 * End-to-end coverage of Phase 12's notifications and savings-summary surface (PRD §11.3/§16.3):
 * a two-household pool where one household is fully self-fulfilled from home inventory and the
 * other needs a purchase, driven through confirm/reconcile/purchase-plan/payments over real HTTP,
 * asserting {@code GET /pools/{poolId}/savings-summary} (before and after a purchase plan exists)
 * and {@code GET /notifications/mine}/{@code POST /notifications/{id}/read} the same way {@code
 * OrderDistributionIntegrationTest} exercises its own phase's surface end to end.
 */
class NotificationsAndSavingsSummaryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestUsers testUsers;

    @Test
    void savingsSummaryAndNotifications_reflectRealComputationsOverHttp() {
        TestUsers.AuthedUser organizer = testUsers.create("savingsOrg@example.com", "Savings Organizer");
        UUID classroomId = createClassroom(organizer, "Savings School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);
        RequirementResponse pencils = addRequirement(organizer, poolId, "Pencils", 4);

        TestUsers.AuthedUser parent1 = testUsers.create("savingsParent1@example.com", "Savings Parent One");
        TestUsers.AuthedUser parent2 = testUsers.create("savingsParent2@example.com", "Savings Parent Two");
        UUID student1Id = join(organizer, classroomId, parent1, "Alex").studentId();
        join(organizer, classroomId, parent2, "Bailey");

        // Parent 1 already owns all 4 pencils at home — fully self-fulfilled, nothing to buy.
        setInventory(parent1, poolId, pencils.id(), student1Id, 4);

        confirm(organizer, poolId);

        // Not reconciled yet -> 409.
        ResponseEntity<String> tooEarly = rest.exchange(baseUrl() + "/api/v1/pools/" + poolId + "/savings-summary",
                HttpMethod.GET, new HttpEntity<>(authHeaders(organizer)), String.class);
        assertThat(tooEarly.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        reconcile(organizer, poolId);

        // itemsReused = parent1's 4 owned; itemsPurchased = parent2's 4 residual. No plan yet ->
        // zero savings, and any classroom member (not just the organizer) can read this.
        SavingsSummaryResponse beforePlan = getSavingsSummary(parent1, poolId);
        assertThat(beforePlan.poolName()).isEqualTo("Fall Supplies");
        assertThat(beforePlan.itemsReused()).isEqualTo(4);
        assertThat(beforePlan.itemsPurchased()).isEqualTo(4);
        assertThat(beforePlan.estimatedSavingsCents()).isZero();
        assertThat(beforePlan.shareableMessage()).isEqualTo("\"Fall Supplies\" reused 4 items with ClassPool!");

        // One 6-pack at 600 cents covers the 4 needed (2 units of waste).
        addOffer(organizer, poolId, pencils.id(), "Amazon", 6, 600);
        PurchasePlanResponse plan = generatePlan(organizer, poolId);
        assertThat(plan.totalCostCents()).isEqualTo(600);
        approvePlan(organizer, poolId);

        // avgUnitCostCents = round(600/6) = 100 -> estimatedSavingsCents = round(100*4) = 400.
        SavingsSummaryResponse afterPlan = getSavingsSummary(organizer, poolId);
        assertThat(afterPlan.itemsReused()).isEqualTo(4);
        assertThat(afterPlan.estimatedSavingsCents()).isEqualTo(400);
        assertThat(afterPlan.shareableMessage())
                .isEqualTo("\"Fall Supplies\" reused 4 items and saved an estimated $4.00 with ClassPool!");

        // ---- generatePayments emits a PAYMENT_DUE notification to the paying household only ----

        startOnboarding(organizer, classroomId);
        completeOnboarding(organizer, classroomId);
        List<PaymentResponse> payments = generatePayments(organizer, poolId);
        assertThat(payments).hasSize(1); // only parent2's household owes anything
        // unitCostCents = round(600/4) = 150; parent2 needs all 4 -> 600.
        assertThat(payments.get(0).amountCents()).isEqualTo(600);

        List<NotificationResponse> parent1Notifications = getMyNotifications(parent1);
        assertThat(parent1Notifications).isEmpty(); // parent1's household never owed anything

        List<NotificationResponse> parent2Notifications = getMyNotifications(parent2);
        assertThat(parent2Notifications).hasSize(1);
        NotificationResponse notification = parent2Notifications.get(0);
        assertThat(notification.type()).isEqualTo("PAYMENT_DUE");
        assertThat(notification.poolId()).isEqualTo(poolId);
        assertThat(notification.message()).contains("$6.00").contains("Fall Supplies");
        assertThat(notification.readAt()).isNull();

        // A different user can't mark someone else's notification read.
        ResponseEntity<String> forbiddenRead = rest.exchange(
                baseUrl() + "/api/v1/notifications/" + notification.id() + "/read", HttpMethod.POST,
                new HttpEntity<>(authHeaders(parent1)), String.class);
        assertThat(forbiddenRead.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The owner marks it read...
        NotificationResponse readOnce = markNotificationRead(parent2, notification.id());
        assertThat(readOnce.readAt()).isNotNull();

        // ...and calling it again is idempotent: same result, no error.
        NotificationResponse readTwice = markNotificationRead(parent2, notification.id());
        assertThat(readTwice.readAt()).isEqualTo(readOnce.readAt());
    }

    // ---- fixtures ----

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

    private void setInventory(TestUsers.AuthedUser parent, UUID poolId, UUID requirementId, UUID studentId,
                               int ownedQuantity) {
        ResponseEntity<String> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirements/" + requirementId + "/inventory",
                HttpMethod.PUT, new HttpEntity<>(new SetInventoryRequest(studentId, ownedQuantity),
                        authHeaders(parent)),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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

    private SavingsSummaryResponse getSavingsSummary(TestUsers.AuthedUser caller, UUID poolId) {
        ResponseEntity<SavingsSummaryResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/savings-summary", HttpMethod.GET,
                new HttpEntity<>(authHeaders(caller)), SavingsSummaryResponse.class);
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

    private void startOnboarding(TestUsers.AuthedUser organizer, UUID classroomId) {
        ResponseEntity<OrganizerStripeAccountResponse> response = rest.exchange(
                baseUrl() + "/api/v1/classrooms/" + classroomId + "/stripe-onboarding", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), OrganizerStripeAccountResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void completeOnboarding(TestUsers.AuthedUser organizer, UUID classroomId) {
        ResponseEntity<OrganizerStripeAccountResponse> response = rest.exchange(
                baseUrl() + "/api/v1/classrooms/" + classroomId + "/stripe-onboarding/complete", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), OrganizerStripeAccountResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private List<PaymentResponse> generatePayments(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<PaymentResponse[]> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/payments/generate", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), PaymentResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private List<NotificationResponse> getMyNotifications(TestUsers.AuthedUser caller) {
        ResponseEntity<NotificationResponse[]> response = rest.exchange(
                baseUrl() + "/api/v1/notifications/mine", HttpMethod.GET,
                new HttpEntity<>(authHeaders(caller)), NotificationResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private NotificationResponse markNotificationRead(TestUsers.AuthedUser caller, UUID notificationId) {
        ResponseEntity<NotificationResponse> response = rest.exchange(
                baseUrl() + "/api/v1/notifications/" + notificationId + "/read", HttpMethod.POST,
                new HttpEntity<>(authHeaders(caller)), NotificationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders authHeaders(TestUsers.AuthedUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "CLASSPOOL_SESSION=" + user.sessionToken());
        return headers;
    }
}
