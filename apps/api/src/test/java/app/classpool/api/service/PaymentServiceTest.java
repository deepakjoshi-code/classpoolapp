package app.classpool.api.service;

import app.classpool.api.domain.AllocationLine;
import app.classpool.api.domain.AllocationStatus;
import app.classpool.api.domain.Household;
import app.classpool.api.domain.OrganizerStripeAccount;
import app.classpool.api.domain.OrganizerStripeAccountStatus;
import app.classpool.api.domain.Payment;
import app.classpool.api.domain.PaymentMethod;
import app.classpool.api.domain.PaymentState;
import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.PurchasePlan;
import app.classpool.api.domain.PurchasePlanLine;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementStrictness;
import app.classpool.api.domain.ResidualDemandLine;
import app.classpool.api.domain.Student;
import app.classpool.api.dto.FinalizePaymentsRequest;
import app.classpool.api.dto.OrganizerStripeAccountResponse;
import app.classpool.api.dto.PayPaymentRequest;
import app.classpool.api.dto.PaymentResponse;
import app.classpool.api.dto.PaymentsSummaryResponse;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.exception.BadRequestException;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.AllocationLineRepository;
import app.classpool.api.repository.AppUserRepository;
import app.classpool.api.repository.HouseholdRepository;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.OrganizerStripeAccountRepository;
import app.classpool.api.repository.PaymentRepository;
import app.classpool.api.repository.PoolRepository;
import app.classpool.api.repository.PurchasePlanLineRepository;
import app.classpool.api.repository.PurchasePlanRepository;
import app.classpool.api.repository.RequirementRepository;
import app.classpool.api.repository.ResidualDemandLineRepository;
import app.classpool.api.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit coverage of {@link PaymentService} — the cost-splitting math (PRD §8.1-8.3), every
 * state-gate 409/403, the payments-summary math, and the finalize threshold gate. Only repository
 * and {@link StripeGateway} collaborators are mocked; {@link PoolService} is exercised for real
 * over mocked repositories, same pattern as {@code PurchasePlanServiceTest}/{@code
 * AllocationServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private OrganizerStripeAccountRepository organizerStripeAccountRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PurchasePlanRepository purchasePlanRepository;
    @Mock
    private PurchasePlanLineRepository purchasePlanLineRepository;
    @Mock
    private ResidualDemandLineRepository residualDemandLineRepository;
    @Mock
    private AllocationLineRepository allocationLineRepository;
    @Mock
    private RequirementRepository requirementRepository;
    @Mock
    private StudentRepository studentRepository;
    @Mock
    private HouseholdRepository householdRepository;
    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private StripeGateway stripeGateway;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private PoolRepository poolRepository;

    private PaymentService paymentService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID classroomId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PoolAssembler poolAssembler = new PoolAssembler(requirementRepository);
        RequirementAssembler requirementAssembler = new RequirementAssembler();
        PoolService poolService = new PoolService(poolRepository, requirementRepository, membershipRepository,
                poolAssembler, requirementAssembler);
        paymentService = new PaymentService(organizerStripeAccountRepository, paymentRepository,
                purchasePlanRepository, purchasePlanLineRepository, residualDemandLineRepository,
                allocationLineRepository, requirementRepository, studentRepository, householdRepository,
                appUserRepository, stripeGateway, poolService);
    }

    // ==================== Stripe onboarding ====================

    @Test
    void startStripeOnboarding_createsAccount_whenNoneExistsYet() {
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(organizerStripeAccountRepository.findByUserIdAndClassroomId(callerId, classroomId))
                .thenReturn(Optional.empty());
        when(stripeGateway.createExpressAccount(callerId, classroomId))
                .thenReturn(new StripeGateway.ExpressAccountResult("acct_stub_1", "https://onboard/acct_stub_1"));
        when(organizerStripeAccountRepository.save(any(OrganizerStripeAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(stripeGateway.onboardingUrlFor("acct_stub_1")).thenReturn("https://onboard/acct_stub_1");

        OrganizerStripeAccountResponse response = paymentService.startStripeOnboarding(callerId, classroomId);

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.onboardingUrl()).isEqualTo("https://onboard/acct_stub_1");
        verify(stripeGateway, times(1)).createExpressAccount(callerId, classroomId);
    }

    @Test
    void startStripeOnboarding_isIdempotent_whenAPendingAccountAlreadyExists() {
        OrganizerStripeAccount existing = new OrganizerStripeAccount(callerId, classroomId, "acct_existing");
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(organizerStripeAccountRepository.findByUserIdAndClassroomId(callerId, classroomId))
                .thenReturn(Optional.of(existing));
        when(stripeGateway.onboardingUrlFor("acct_existing")).thenReturn("https://onboard/acct_existing");

        OrganizerStripeAccountResponse response = paymentService.startStripeOnboarding(callerId, classroomId);

        assertThat(response.status()).isEqualTo("PENDING");
        // Never creates a second Stripe account for an already-PENDING one.
        verify(stripeGateway, never()).createExpressAccount(any(), any());
        verify(organizerStripeAccountRepository, never()).save(any());
    }

    @Test
    void startStripeOnboarding_returnsActiveAccountAsIs_withNoOnboardingUrl() {
        OrganizerStripeAccount existing = new OrganizerStripeAccount(callerId, classroomId, "acct_active");
        existing.activate();
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(organizerStripeAccountRepository.findByUserIdAndClassroomId(callerId, classroomId))
                .thenReturn(Optional.of(existing));

        OrganizerStripeAccountResponse response = paymentService.startStripeOnboarding(callerId, classroomId);

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.onboardingUrl()).isNull();
        verify(stripeGateway, never()).createExpressAccount(any(), any());
    }

    @Test
    void completeStripeOnboarding_throwsConflict_whenNothingIsPending() {
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(organizerStripeAccountRepository.findByUserIdAndClassroomId(callerId, classroomId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.completeStripeOnboarding(callerId, classroomId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void completeStripeOnboarding_activatesAPendingAccount() {
        OrganizerStripeAccount existing = new OrganizerStripeAccount(callerId, classroomId, "acct_existing");
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(organizerStripeAccountRepository.findByUserIdAndClassroomId(callerId, classroomId))
                .thenReturn(Optional.of(existing));
        when(organizerStripeAccountRepository.save(any(OrganizerStripeAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        OrganizerStripeAccountResponse response = paymentService.completeStripeOnboarding(callerId, classroomId);

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.onboardingUrl()).isNull();
    }

    @Test
    void getStripeOnboardingStatus_throwsNotFound_whenNeverStarted() {
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(organizerStripeAccountRepository.findByUserIdAndClassroomId(callerId, classroomId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getStripeOnboardingStatus(callerId, classroomId))
                .isInstanceOf(NotFoundException.class);
    }

    // ==================== generatePayments — cost-splitting math ====================

    /**
     * Two requirements, two households, hand-verified numbers:
     * <ul>
     *   <li>Pencils: residualDemand 12 (needed), bought as a single 24-pack for 500 cents (waste
     *       12) — unitCost must be {@code round(500 / 12) = 42}, NOT {@code round(500 / 24) = 21}.
     *       Proves the split divides by units NEEDED, not units purchased, and that the 12 wasted
     *       pencils are never billed to anyone. Student A needs 6 (household H1: 6*42=252),
     *       student B needs 6 (household H2: 6*42=252).
     *   <li>Folders: residualDemand 7, total cost 500 cents — unitCost = {@code round(500/7) = 71}.
     *       Only student A needs any (7, all to household H1: 7*71=497).
     * </ul>
     * Household totals: H1 = 252 + 497 = 749; H2 = 252. Plan total cost is 500+500=1000, but
     * 749+252=1001 — a 1-cent rounding drift is expected and accepted (README), never reconciled
     * to the exact plan total.
     */
    @Test
    void generatePayments_splitsCostByUnitsNeeded_notUnitsPurchased_acrossHouseholds() {
        Pool pool = newPool(PoolState.PURCHASE_PROPOSED);
        Requirement pencils = newRequirement(pool.getId(), "Pencils");
        Requirement folders = newRequirement(pool.getId(), "Folders");
        stubOrganizer(pool);

        PurchasePlan plan = new PurchasePlan(pool.getId());
        setField(plan, "id", UUID.randomUUID());
        plan.approve();
        when(purchasePlanRepository.findByPoolId(pool.getId())).thenReturn(Optional.of(plan));
        when(organizerStripeAccountRepository.existsByClassroomIdAndStatus(classroomId,
                OrganizerStripeAccountStatus.ACTIVE)).thenReturn(true);
        when(paymentRepository.existsByPoolId(pool.getId())).thenReturn(false);

        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId()))
                .thenReturn(List.of(pencils, folders));
        when(residualDemandLineRepository.findByRequirementIdInOrderByRequirementIdAsc(
                List.of(pencils.getId(), folders.getId())))
                .thenReturn(List.of(
                        new ResidualDemandLine(pencils.getId(), 12, 0, 0, 12),
                        new ResidualDemandLine(folders.getId(), 7, 0, 0, 7)));

        UUID offerId = UUID.randomUUID();
        when(purchasePlanLineRepository.findByPurchasePlanIdOrderByRequirementIdAscProductOfferIdAsc(plan.getId()))
                .thenReturn(List.of(
                        new PurchasePlanLine(plan.getId(), pencils.getId(), offerId, 1, 500, 12), // 24-pack, 12 waste
                        new PurchasePlanLine(plan.getId(), folders.getId(), offerId, 1, 500, 0)));

        UUID studentA = UUID.randomUUID();
        UUID studentB = UUID.randomUUID();
        when(allocationLineRepository.findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(
                List.of(pencils.getId(), folders.getId())))
                .thenReturn(List.of(
                        new AllocationLine(pencils.getId(), studentA, 6, 0, 0, 6, AllocationStatus.PURCHASE_REQUIRED),
                        new AllocationLine(pencils.getId(), studentB, 6, 0, 0, 6, AllocationStatus.PURCHASE_REQUIRED),
                        new AllocationLine(folders.getId(), studentA, 7, 0, 0, 7, AllocationStatus.PURCHASE_REQUIRED)));

        UUID householdH1 = UUID.randomUUID();
        UUID householdH2 = UUID.randomUUID();
        Student studentAEntity = newStudent(householdH1);
        setField(studentAEntity, "id", studentA);
        Student studentBEntity = newStudent(householdH2);
        setField(studentBEntity, "id", studentB);
        when(studentRepository.findAllById(any())).thenReturn(List.of(studentAEntity, studentBEntity));

        when(paymentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(householdRepository.findAllById(any())).thenReturn(List.of());

        ArgumentCaptor<List<Payment>> savedCaptor = ArgumentCaptor.forClass(List.class);

        List<PaymentResponse> responses = paymentService.generatePayments(callerId, pool.getId());

        verify(paymentRepository).saveAll(savedCaptor.capture());
        Map<UUID, Integer> totalsByHousehold = savedCaptor.getValue().stream()
                .collect(Collectors.toMap(Payment::getHouseholdId, Payment::getAmountCents));
        assertThat(totalsByHousehold).hasSize(2);
        assertThat(totalsByHousehold.get(householdH1)).isEqualTo(749); // 6*42 + 7*71
        assertThat(totalsByHousehold.get(householdH2)).isEqualTo(252); // 6*42

        int grandTotal = totalsByHousehold.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(grandTotal).isNotEqualTo(1000); // rounding drift vs the exact plan total is expected
        assertThat(grandTotal).isEqualTo(1001);

        assertThat(responses).allSatisfy(r -> assertThat(r.state()).isEqualTo("PENDING"));
        assertThat(responses).allSatisfy(r -> assertThat(r.method()).isNull()); // PENDING suppresses the placeholder
        // Pool moved PURCHASE_PROPOSED -> PAYMENT_OPEN.
        assertThat(pool.getState()).isEqualTo(PoolState.PAYMENT_OPEN);
    }

    @Test
    void generatePayments_throwsConflict_whenNoApprovedPlanExists() {
        Pool pool = newPool(PoolState.PURCHASE_PROPOSED);
        stubOrganizer(pool);
        when(purchasePlanRepository.findByPoolId(pool.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.generatePayments(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("approved");
        verify(paymentRepository, never()).saveAll(any());
    }

    @Test
    void generatePayments_throwsConflict_whenPlanExistsButIsNotApproved() {
        Pool pool = newPool(PoolState.PURCHASE_PROPOSED);
        stubOrganizer(pool);
        PurchasePlan plan = new PurchasePlan(pool.getId()); // still PROPOSED
        setField(plan, "id", UUID.randomUUID());
        when(purchasePlanRepository.findByPoolId(pool.getId())).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> paymentService.generatePayments(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void generatePayments_throwsConflict_whenStripeOnboardingIsNotActive() {
        Pool pool = newPool(PoolState.PURCHASE_PROPOSED);
        stubOrganizer(pool);
        PurchasePlan plan = new PurchasePlan(pool.getId());
        setField(plan, "id", UUID.randomUUID());
        plan.approve();
        when(purchasePlanRepository.findByPoolId(pool.getId())).thenReturn(Optional.of(plan));
        when(organizerStripeAccountRepository.existsByClassroomIdAndStatus(classroomId,
                OrganizerStripeAccountStatus.ACTIVE)).thenReturn(false);

        assertThatThrownBy(() -> paymentService.generatePayments(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Stripe");
        verify(paymentRepository, never()).saveAll(any());
    }

    @Test
    void generatePayments_throwsConflict_whenPaymentsAlreadyExist() {
        Pool pool = newPool(PoolState.PURCHASE_PROPOSED);
        stubOrganizer(pool);
        PurchasePlan plan = new PurchasePlan(pool.getId());
        setField(plan, "id", UUID.randomUUID());
        plan.approve();
        when(purchasePlanRepository.findByPoolId(pool.getId())).thenReturn(Optional.of(plan));
        when(organizerStripeAccountRepository.existsByClassroomIdAndStatus(classroomId,
                OrganizerStripeAccountStatus.ACTIVE)).thenReturn(true);
        when(paymentRepository.existsByPoolId(pool.getId())).thenReturn(true);

        assertThatThrownBy(() -> paymentService.generatePayments(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class);
        verify(paymentRepository, never()).saveAll(any());
    }

    @Test
    void generatePayments_throwsForbidden_forANonOrganizer() {
        Pool pool = newPool(PoolState.PURCHASE_PROPOSED);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> paymentService.generatePayments(callerId, pool.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    // ==================== payMyPayment ====================

    @Test
    void payMyPayment_throwsForbidden_whenCallerDoesNotOwnTheHousehold() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        Payment payment = newPayment(pool.getId(), UUID.randomUUID(), 500);
        setField(payment, "id", UUID.randomUUID());
        when(paymentRepository.findByIdAndPoolId(payment.getId(), pool.getId())).thenReturn(Optional.of(payment));
        when(householdRepository.findByPrimaryParentId(callerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.payMyPayment(callerId, pool.getId(), payment.getId(),
                new PayPaymentRequest("CARD")))
                .isInstanceOf(ForbiddenException.class);
        verify(stripeGateway, never()).createDestinationCharge(any(), anyInt(), any());
    }

    @Test
    void payMyPayment_throwsConflict_whenNotPending() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        Household household = newHousehold(callerId);
        Payment payment = newPayment(pool.getId(), household.getId(), 500);
        setField(payment, "id", UUID.randomUUID());
        payment.markCashPending(); // no longer PENDING
        when(paymentRepository.findByIdAndPoolId(payment.getId(), pool.getId())).thenReturn(Optional.of(payment));
        when(householdRepository.findByPrimaryParentId(callerId)).thenReturn(Optional.of(household));

        assertThatThrownBy(() -> paymentService.payMyPayment(callerId, pool.getId(), payment.getId(),
                new PayPaymentRequest("CARD")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void payMyPayment_chargesAndMarksPaid_whenOwnedAndPending() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        Household household = newHousehold(callerId);
        Payment payment = newPayment(pool.getId(), household.getId(), 500);
        setField(payment, "id", UUID.randomUUID());
        when(paymentRepository.findByIdAndPoolId(payment.getId(), pool.getId())).thenReturn(Optional.of(payment));
        when(householdRepository.findByPrimaryParentId(callerId)).thenReturn(Optional.of(household));
        OrganizerStripeAccount account = new OrganizerStripeAccount(UUID.randomUUID(), classroomId, "acct_active");
        account.activate();
        when(organizerStripeAccountRepository.findByClassroomIdAndStatusOrderByCreatedAtAsc(classroomId,
                OrganizerStripeAccountStatus.ACTIVE)).thenReturn(List.of(account));
        when(stripeGateway.createDestinationCharge("acct_active", 500, payment.getId())).thenReturn("pi_stub_123");
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.payMyPayment(callerId, pool.getId(), payment.getId(),
                new PayPaymentRequest("CARD"));

        assertThat(response.state()).isEqualTo("PAID");
        assertThat(response.method()).isEqualTo("CARD");
        assertThat(payment.getStripePaymentIntentId()).isEqualTo("pi_stub_123");
    }

    // ==================== cash flow ====================

    @Test
    void markPaymentCashPending_throwsConflict_whenNotPending() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        stubOrganizer(pool);
        Payment payment = newPayment(pool.getId(), UUID.randomUUID(), 500);
        setField(payment, "id", UUID.randomUUID());
        payment.markCashPending();
        when(paymentRepository.findByIdAndPoolId(payment.getId(), pool.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.markPaymentCashPending(callerId, pool.getId(), payment.getId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void markPaymentCashReceived_throwsConflict_whenNotPendingCash() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        stubOrganizer(pool);
        Payment payment = newPayment(pool.getId(), UUID.randomUUID(), 500); // still PENDING
        setField(payment, "id", UUID.randomUUID());
        when(paymentRepository.findByIdAndPoolId(payment.getId(), pool.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.markPaymentCashReceived(callerId, pool.getId(), payment.getId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void cashFlow_pendingToPendingCashToPaidCashReceived() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        stubOrganizer(pool);
        Payment payment = newPayment(pool.getId(), UUID.randomUUID(), 500);
        setField(payment, "id", UUID.randomUUID());
        when(paymentRepository.findByIdAndPoolId(payment.getId(), pool.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse pending = paymentService.markPaymentCashPending(callerId, pool.getId(), payment.getId());
        assertThat(pending.state()).isEqualTo("PENDING_CASH");
        assertThat(pending.method()).isEqualTo("CASH");

        PaymentResponse received = paymentService.markPaymentCashReceived(callerId, pool.getId(), payment.getId());
        assertThat(received.state()).isEqualTo("PAID_CASH_RECEIVED");
    }

    // ==================== refund ====================

    @Test
    void refundPayment_throwsConflict_oncePoolHasReachedOrdered() {
        Pool pool = newPool(PoolState.ORDERED);
        stubOrganizer(pool);

        assertThatThrownBy(() -> paymentService.refundPayment(callerId, pool.getId(), UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void refundPayment_throwsConflict_whenPaymentIsNotInARefundableState() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        stubOrganizer(pool);
        Payment payment = newPayment(pool.getId(), UUID.randomUUID(), 500); // still PENDING
        setField(payment, "id", UUID.randomUUID());
        when(paymentRepository.findByIdAndPoolId(payment.getId(), pool.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.refundPayment(callerId, pool.getId(), payment.getId()))
                .isInstanceOf(ConflictException.class);
        verify(stripeGateway, never()).refund(any());
    }

    @Test
    void refundPayment_skipsGatewayCall_forACashPayment() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        stubOrganizer(pool);
        Payment payment = newPayment(pool.getId(), UUID.randomUUID(), 500);
        setField(payment, "id", UUID.randomUUID());
        payment.markCashPending();
        payment.markCashReceived();
        when(paymentRepository.findByIdAndPoolId(payment.getId(), pool.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.refundPayment(callerId, pool.getId(), payment.getId());

        assertThat(response.state()).isEqualTo("REFUNDED");
        verify(stripeGateway, never()).refund(any());
    }

    @Test
    void refundPayment_callsGateway_forAStripePayment() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        stubOrganizer(pool);
        Payment payment = newPayment(pool.getId(), UUID.randomUUID(), 500);
        setField(payment, "id", UUID.randomUUID());
        payment.markPaid(PaymentMethod.CARD, "pi_stub_1");
        when(paymentRepository.findByIdAndPoolId(payment.getId(), pool.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stripeGateway.refund("pi_stub_1")).thenReturn("re_stub_1");

        PaymentResponse response = paymentService.refundPayment(callerId, pool.getId(), payment.getId());

        assertThat(response.state()).isEqualTo("REFUNDED");
        verify(stripeGateway).refund("pi_stub_1");
    }

    // ==================== summary ====================

    @Test
    void getPaymentsSummary_computesPercentAndOutstandingList() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        stubOrganizer(pool);
        Payment paid = newPayment(pool.getId(), UUID.randomUUID(), 700);
        paid.markPaid(PaymentMethod.CARD, "pi_1");
        Payment cashReceived = newPayment(pool.getId(), UUID.randomUUID(), 200);
        cashReceived.markCashPending();
        cashReceived.markCashReceived();
        Payment pending = newPayment(pool.getId(), UUID.randomUUID(), 100);
        when(paymentRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId()))
                .thenReturn(List.of(paid, cashReceived, pending));
        when(householdRepository.findAllById(any())).thenReturn(List.of());

        PaymentsSummaryResponse summary = paymentService.getPaymentsSummary(callerId, pool.getId());

        assertThat(summary.totalOwedCents()).isEqualTo(1000);
        assertThat(summary.totalCollectedCents()).isEqualTo(900);
        assertThat(summary.percentCollected()).isEqualTo(90.0);
        assertThat(summary.thresholdPercent()).isEqualTo(90.0);
        assertThat(summary.meetsThreshold()).isTrue();
        assertThat(summary.outstandingHouseholds()).hasSize(1);
        assertThat(summary.outstandingHouseholds().get(0).amountCents()).isEqualTo(100);
    }

    @Test
    void getPaymentsSummary_treatsZeroOwedAsFullyMet() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        stubOrganizer(pool);
        when(paymentRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of());

        PaymentsSummaryResponse summary = paymentService.getPaymentsSummary(callerId, pool.getId());

        assertThat(summary.totalOwedCents()).isZero();
        assertThat(summary.percentCollected()).isEqualTo(100.0);
        assertThat(summary.meetsThreshold()).isTrue();
        assertThat(summary.outstandingHouseholds()).isEmpty();
    }

    // ==================== finalize ====================

    @Test
    void finalizePayments_throwsConflict_belowThresholdWithoutAcknowledgement() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        stubOrganizer(pool);
        Payment paid = newPayment(pool.getId(), UUID.randomUUID(), 500);
        paid.markPaid(PaymentMethod.CARD, "pi_1");
        Payment pending = newPayment(pool.getId(), UUID.randomUUID(), 500); // 50% collected
        when(paymentRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(paid, pending));
        when(householdRepository.findAllById(any())).thenReturn(List.of());

        assertThatThrownBy(() -> paymentService.finalizePayments(callerId, pool.getId(),
                new FinalizePaymentsRequest(null)))
                .isInstanceOf(ConflictException.class);
        assertThat(pool.getState()).isEqualTo(PoolState.PAYMENT_OPEN); // never transitioned
    }

    @Test
    void finalizePayments_succeeds_belowThreshold_whenAcknowledged() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        stubOrganizer(pool);
        Payment paid = newPayment(pool.getId(), UUID.randomUUID(), 500);
        paid.markPaid(PaymentMethod.CARD, "pi_1");
        Payment pending = newPayment(pool.getId(), UUID.randomUUID(), 500);
        when(paymentRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(paid, pending));
        when(householdRepository.findAllById(any())).thenReturn(List.of());
        when(poolRepository.save(any(Pool.class))).thenAnswer(inv -> inv.getArgument(0));
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of());

        PoolDetailResponse response = paymentService.finalizePayments(callerId, pool.getId(),
                new FinalizePaymentsRequest(true));

        assertThat(response.state()).isEqualTo("ORDERED");
        assertThat(pool.getState()).isEqualTo(PoolState.ORDERED);
    }

    @Test
    void finalizePayments_succeeds_atOrAboveThreshold_withoutNeedingAcknowledgement() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        stubOrganizer(pool);
        Payment paid = newPayment(pool.getId(), UUID.randomUUID(), 900);
        paid.markPaid(PaymentMethod.CARD, "pi_1");
        Payment pending = newPayment(pool.getId(), UUID.randomUUID(), 100); // 90% collected
        when(paymentRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(paid, pending));
        when(householdRepository.findAllById(any())).thenReturn(List.of());
        when(poolRepository.save(any(Pool.class))).thenAnswer(inv -> inv.getArgument(0));
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of());

        PoolDetailResponse response = paymentService.finalizePayments(callerId, pool.getId(),
                new FinalizePaymentsRequest(false));

        assertThat(response.state()).isEqualTo("ORDERED");
    }

    @Test
    void finalizePayments_throwsConflict_whenPoolIsNotPaymentOpen() {
        Pool pool = newPool(PoolState.PURCHASE_PROPOSED);
        stubOrganizer(pool);

        assertThatThrownBy(() -> paymentService.finalizePayments(callerId, pool.getId(), null))
                .isInstanceOf(ConflictException.class);
    }

    // ==================== substitution top-up charges (Phase 10) ====================

    @Test
    void splitAmountByPurchaseShare_splitsProportionally_withExpectedRoundingDrift() {
        UUID requirementId = UUID.randomUUID();
        UUID studentA = UUID.randomUUID();
        UUID studentB = UUID.randomUUID();
        UUID studentC = UUID.randomUUID();
        // Three households, evenly split need (1 unit each of 3 total) — 100 cents doesn't divide
        // evenly by 3, so this also exercises the same accepted per-household rounding drift as
        // computeHouseholdTotals (33 + 33 + 33 = 99, not 100).
        when(allocationLineRepository.findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(List.of(requirementId)))
                .thenReturn(List.of(
                        new AllocationLine(requirementId, studentA, 1, 0, 0, 1, AllocationStatus.PURCHASE_REQUIRED),
                        new AllocationLine(requirementId, studentB, 1, 0, 0, 1, AllocationStatus.PURCHASE_REQUIRED),
                        new AllocationLine(requirementId, studentC, 1, 0, 0, 1, AllocationStatus.PURCHASE_REQUIRED)));

        UUID householdA = UUID.randomUUID();
        UUID householdB = UUID.randomUUID();
        UUID householdC = UUID.randomUUID();
        Student a = newStudent(householdA);
        setField(a, "id", studentA);
        Student b = newStudent(householdB);
        setField(b, "id", studentB);
        Student c = newStudent(householdC);
        setField(c, "id", studentC);
        when(studentRepository.findAllById(any())).thenReturn(List.of(a, b, c));

        Map<UUID, Integer> shares = paymentService.splitAmountByPurchaseShare(requirementId, 100);

        assertThat(shares).hasSize(3);
        assertThat(shares.get(householdA)).isEqualTo(33);
        assertThat(shares.get(householdB)).isEqualTo(33);
        assertThat(shares.get(householdC)).isEqualTo(33);
        int grandTotal = shares.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(grandTotal).isNotEqualTo(100); // rounding drift vs the exact delta is expected
        assertThat(grandTotal).isEqualTo(99);
    }

    @Test
    void splitAmountByPurchaseShare_omitsAHousehold_whoseRoundedShareIsZero() {
        UUID requirementId = UUID.randomUUID();
        UUID studentBig = UUID.randomUUID();
        UUID studentTiny = UUID.randomUUID();
        when(allocationLineRepository.findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(List.of(requirementId)))
                .thenReturn(List.of(
                        new AllocationLine(requirementId, studentBig, 999, 0, 0, 999, AllocationStatus.PURCHASE_REQUIRED),
                        new AllocationLine(requirementId, studentTiny, 1, 0, 0, 1, AllocationStatus.PURCHASE_REQUIRED)));

        UUID householdBig = UUID.randomUUID();
        UUID householdTiny = UUID.randomUUID();
        Student big = newStudent(householdBig);
        setField(big, "id", studentBig);
        Student tiny = newStudent(householdTiny);
        setField(tiny, "id", studentTiny);
        when(studentRepository.findAllById(any())).thenReturn(List.of(big, tiny));

        // householdTiny's exact share is 100 * 1/1000 = 0.1, which rounds to 0 — skipped entirely.
        Map<UUID, Integer> shares = paymentService.splitAmountByPurchaseShare(requirementId, 100);

        assertThat(shares).hasSize(1);
        assertThat(shares).containsOnlyKeys(householdBig);
        assertThat(shares.get(householdBig)).isEqualTo(100);
    }

    @Test
    void createTopUpPayments_createsOnePendingPaymentPerHousehold_reusingTheProportionalSplit() {
        UUID poolId = UUID.randomUUID();
        UUID requirementId = UUID.randomUUID();
        UUID studentA = UUID.randomUUID();
        UUID studentB = UUID.randomUUID();
        when(allocationLineRepository.findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(List.of(requirementId)))
                .thenReturn(List.of(
                        new AllocationLine(requirementId, studentA, 6, 0, 0, 6, AllocationStatus.PURCHASE_REQUIRED),
                        new AllocationLine(requirementId, studentB, 3, 0, 0, 3, AllocationStatus.PURCHASE_REQUIRED)));
        UUID householdA = UUID.randomUUID();
        UUID householdB = UUID.randomUUID();
        Student a = newStudent(householdA);
        setField(a, "id", studentA);
        Student b = newStudent(householdB);
        setField(b, "id", studentB);
        when(studentRepository.findAllById(any())).thenReturn(List.of(a, b));
        when(paymentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<List<Payment>> captor = ArgumentCaptor.forClass(List.class);
        List<Payment> created = paymentService.createTopUpPayments(poolId, requirementId, 90);

        verify(paymentRepository).saveAll(captor.capture());
        Map<UUID, Integer> byHousehold = captor.getValue().stream()
                .collect(Collectors.toMap(Payment::getHouseholdId, Payment::getAmountCents));
        assertThat(byHousehold.get(householdA)).isEqualTo(60); // 90 * 6/9
        assertThat(byHousehold.get(householdB)).isEqualTo(30); // 90 * 3/9
        assertThat(created).allSatisfy(p -> assertThat(p.getPoolId()).isEqualTo(poolId));
        assertThat(created).allSatisfy(p -> assertThat(p.getState()).isEqualTo(PaymentState.PENDING));
    }

    // ==================== fixtures ====================

    private void stubOrganizer(Pool pool) {
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
    }

    private Pool newPool(PoolState state) {
        Pool pool = new Pool(classroomId, "Fall Supplies", "SUPPLIES");
        setField(pool, "id", UUID.randomUUID());
        setField(pool, "createdAt", Instant.now());
        pool.setState(state);
        return pool;
    }

    private static Requirement newRequirement(UUID poolId, String name) {
        Requirement requirement = new Requirement(poolId, name, 1, null, RequirementStrictness.EQUIVALENT_ALLOWED);
        setField(requirement, "id", UUID.randomUUID());
        setField(requirement, "createdAt", Instant.now());
        setField(requirement, "updatedAt", Instant.now());
        return requirement;
    }

    private static Student newStudent(UUID householdId) {
        Student student = new Student(householdId, "Kid");
        setField(student, "id", UUID.randomUUID());
        setField(student, "createdAt", Instant.now());
        return student;
    }

    private static Household newHousehold(UUID primaryParentId) {
        Household household = new Household(primaryParentId);
        setField(household, "id", UUID.randomUUID());
        setField(household, "createdAt", Instant.now());
        return household;
    }

    private static Payment newPayment(UUID poolId, UUID householdId, int amountCents) {
        Payment payment = new Payment(poolId, householdId, amountCents);
        setField(payment, "createdAt", Instant.now());
        setField(payment, "updatedAt", Instant.now());
        return payment;
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            var field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
