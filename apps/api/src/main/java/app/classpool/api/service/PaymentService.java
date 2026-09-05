package app.classpool.api.service;

import app.classpool.api.domain.AllocationLine;
import app.classpool.api.domain.AppUser;
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
import app.classpool.api.domain.PurchasePlanState;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.ResidualDemandLine;
import app.classpool.api.domain.Student;
import app.classpool.api.dto.FinalizePaymentsRequest;
import app.classpool.api.dto.OrganizerStripeAccountResponse;
import app.classpool.api.dto.OutstandingHouseholdResponse;
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
import app.classpool.api.repository.OrganizerStripeAccountRepository;
import app.classpool.api.repository.PaymentRepository;
import app.classpool.api.repository.PurchasePlanLineRepository;
import app.classpool.api.repository.PurchasePlanRepository;
import app.classpool.api.repository.RequirementRepository;
import app.classpool.api.repository.ResidualDemandLineRepository;
import app.classpool.api.repository.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Household billing for a pool's residual purchase demand (PRD §8.1-8.4) plus the lightweight
 * Stripe Express onboarding it depends on — one service for the whole feature, same size
 * precedent as {@code ContributionService}/{@code PurchasePlanService} scoping their entire phase
 * into a single class. Split into two controllers ({@code StripeOnboardingController} under
 * {@code /classrooms}, {@code PaymentController} under {@code /pools}) purely because the contract
 * puts the two route groups under different resource prefixes — the service boundary itself
 * follows the feature (billing), not the routing accident.
 *
 * <p>See {@link StripeGateway}'s Javadoc for the stub-vs-real-Stripe-SDK boundary this whole
 * service is built against, and apps/api/README.md's "Payment allocation (Phase 9)" section for
 * the cost-splitting math and the {@code Payment.method not null} schema/contract gap.
 */
@Service
public class PaymentService {

    /** Platform-set threshold (contract's own description: "not organizer-editable"). */
    static final double THRESHOLD_PERCENT = 90.0;

    private final OrganizerStripeAccountRepository organizerStripeAccountRepository;
    private final PaymentRepository paymentRepository;
    private final PurchasePlanRepository purchasePlanRepository;
    private final PurchasePlanLineRepository purchasePlanLineRepository;
    private final ResidualDemandLineRepository residualDemandLineRepository;
    private final AllocationLineRepository allocationLineRepository;
    private final RequirementRepository requirementRepository;
    private final StudentRepository studentRepository;
    private final HouseholdRepository householdRepository;
    private final AppUserRepository appUserRepository;
    private final StripeGateway stripeGateway;
    private final PoolService poolService;

    public PaymentService(OrganizerStripeAccountRepository organizerStripeAccountRepository,
                           PaymentRepository paymentRepository, PurchasePlanRepository purchasePlanRepository,
                           PurchasePlanLineRepository purchasePlanLineRepository,
                           ResidualDemandLineRepository residualDemandLineRepository,
                           AllocationLineRepository allocationLineRepository,
                           RequirementRepository requirementRepository, StudentRepository studentRepository,
                           HouseholdRepository householdRepository, AppUserRepository appUserRepository,
                           StripeGateway stripeGateway, PoolService poolService) {
        this.organizerStripeAccountRepository = organizerStripeAccountRepository;
        this.paymentRepository = paymentRepository;
        this.purchasePlanRepository = purchasePlanRepository;
        this.purchasePlanLineRepository = purchasePlanLineRepository;
        this.residualDemandLineRepository = residualDemandLineRepository;
        this.allocationLineRepository = allocationLineRepository;
        this.requirementRepository = requirementRepository;
        this.studentRepository = studentRepository;
        this.householdRepository = householdRepository;
        this.appUserRepository = appUserRepository;
        this.stripeGateway = stripeGateway;
        this.poolService = poolService;
    }

    // ==================== Stripe onboarding ====================

    /**
     * Organizer-only (contract). Idempotent: a {@code PENDING} account for this exact
     * (caller, classroom) pair is returned as-is rather than creating a second Stripe account; an
     * {@code ACTIVE}/{@code RESTRICTED} account is likewise just returned (still 200 — nothing
     * wrong with calling this again once onboarded).
     */
    @Transactional
    public OrganizerStripeAccountResponse startStripeOnboarding(UUID callerUserId, UUID classroomId) {
        poolService.requireOrganizer(callerUserId, classroomId);

        OrganizerStripeAccount account = organizerStripeAccountRepository
                .findByUserIdAndClassroomId(callerUserId, classroomId).orElse(null);
        if (account == null) {
            StripeGateway.ExpressAccountResult created = stripeGateway.createExpressAccount(callerUserId,
                    classroomId);
            account = organizerStripeAccountRepository
                    .save(new OrganizerStripeAccount(callerUserId, classroomId, created.stripeAccountId()));
        }
        return toAccountResponse(account);
    }

    /**
     * Organizer-only (contract). 409 if there's no {@code PENDING} onboarding for this caller on
     * this classroom to complete (never started, or already {@code ACTIVE}/{@code RESTRICTED}).
     */
    @Transactional
    public OrganizerStripeAccountResponse completeStripeOnboarding(UUID callerUserId, UUID classroomId) {
        poolService.requireOrganizer(callerUserId, classroomId);

        OrganizerStripeAccount account = organizerStripeAccountRepository
                .findByUserIdAndClassroomId(callerUserId, classroomId)
                .orElseThrow(() -> new ConflictException("No pending onboarding to complete"));
        if (account.getStatus() != OrganizerStripeAccountStatus.PENDING) {
            throw new ConflictException("No pending onboarding to complete");
        }
        account.activate();
        organizerStripeAccountRepository.save(account);
        return toAccountResponse(account);
    }

    /** Organizer-only (contract). 404 if never started for this caller on this classroom. */
    @Transactional(readOnly = true)
    public OrganizerStripeAccountResponse getStripeOnboardingStatus(UUID callerUserId, UUID classroomId) {
        poolService.requireOrganizer(callerUserId, classroomId);

        OrganizerStripeAccount account = organizerStripeAccountRepository
                .findByUserIdAndClassroomId(callerUserId, classroomId)
                .orElseThrow(() -> new NotFoundException("Onboarding has not been started for this classroom"));
        return toAccountResponse(account);
    }

    private OrganizerStripeAccountResponse toAccountResponse(OrganizerStripeAccount account) {
        String onboardingUrl = account.getStatus() == OrganizerStripeAccountStatus.PENDING
                ? stripeGateway.onboardingUrlFor(account.getStripeAccountId())
                : null;
        return new OrganizerStripeAccountResponse(account.getClassroomId(), account.getStatus().name(),
                onboardingUrl);
    }

    // ==================== Payment generation & reads ====================

    /**
     * Organizer-only (contract). Per requirement with {@code residualDemand > 0} (Phase 6/7's
     * frozen {@link ResidualDemandLine} snapshot), splits that requirement's total purchase cost
     * (the sum of Phase 8's frozen {@link PurchasePlanLine}s for it) across only the units
     * actually needed — never units purchased, so pack waste/leftover is never billed to anyone
     * (PRD §7.2's own worked example). See apps/api/README.md for the full write-up and the
     * accepted per-household rounding drift this implies. One {@link Payment} row per household
     * with a nonzero share, {@code state = PENDING}. Requires an {@code APPROVED} {@link
     * PurchasePlan} and an {@code ACTIVE} {@link OrganizerStripeAccount} for this classroom — 409
     * naming whichever is missing; 409 if payments already exist for this pool (one-shot, same
     * instinct as every other generate-style action in this codebase).
     */
    @Transactional
    public List<PaymentResponse> generatePayments(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());

        PurchasePlan plan = purchasePlanRepository.findByPoolId(poolId).orElse(null);
        if (plan == null || plan.getState() != PurchasePlanState.APPROVED) {
            throw new ConflictException("No approved purchase plan for this pool");
        }
        if (!organizerStripeAccountRepository.existsByClassroomIdAndStatus(pool.getClassroomId(),
                OrganizerStripeAccountStatus.ACTIVE)) {
            throw new ConflictException("Stripe onboarding isn't ACTIVE for this classroom");
        }
        if (paymentRepository.existsByPoolId(poolId)) {
            throw new ConflictException("Payments have already been generated for this pool");
        }

        Map<UUID, Integer> householdTotals = computeHouseholdTotals(poolId, plan.getId());
        List<Payment> payments = householdTotals.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> new Payment(poolId, e.getKey(), e.getValue()))
                .toList();
        paymentRepository.saveAll(payments);
        poolService.transitionToPaymentOpen(pool);

        Map<UUID, String> displayNames = householdDisplayNames(
                payments.stream().map(Payment::getHouseholdId).distinct().toList());
        return payments.stream().map(p -> toResponse(p, displayNames.get(p.getHouseholdId()))).toList();
    }

    /**
     * The cost-splitting rule (PRD §8.1-8.3): for each requirement with residual demand, {@code
     * unitCostCents = round(sum(PurchasePlanLine.totalCostCents for it) / residualDemand)} — divide
     * by units actually needed, not units purchased (waste is absorbed, never billed). Then for
     * every {@link AllocationLine} with {@code purchaseRequiredQuantity > 0} for that requirement,
     * {@code lineShareCents = unitCostCents * purchaseRequiredQuantity}, summed per household (via
     * the line's student -&gt; {@link Student#getHouseholdId()}).
     */
    private Map<UUID, Integer> computeHouseholdTotals(UUID poolId, UUID purchasePlanId) {
        List<UUID> requirementIds = requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId).stream()
                .map(Requirement::getId).toList();
        if (requirementIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Integer> residualDemandByRequirement = residualDemandLineRepository
                .findByRequirementIdInOrderByRequirementIdAsc(requirementIds).stream()
                .filter(rl -> rl.getResidualDemand() > 0)
                .collect(Collectors.toMap(ResidualDemandLine::getRequirementId, ResidualDemandLine::getResidualDemand));
        if (residualDemandByRequirement.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Integer> totalCostByRequirement = purchasePlanLineRepository
                .findByPurchasePlanIdOrderByRequirementIdAscProductOfferIdAsc(purchasePlanId).stream()
                .collect(Collectors.groupingBy(PurchasePlanLine::getRequirementId,
                        Collectors.summingInt(PurchasePlanLine::getTotalCostCents)));

        Map<UUID, Integer> unitCostByRequirement = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> entry : residualDemandByRequirement.entrySet()) {
            int totalCost = totalCostByRequirement.getOrDefault(entry.getKey(), 0);
            int residualDemand = entry.getValue();
            unitCostByRequirement.put(entry.getKey(), (int) Math.round((double) totalCost / residualDemand));
        }

        List<AllocationLine> purchaseLines = allocationLineRepository
                .findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(requirementIds).stream()
                .filter(l -> l.getPurchaseRequiredQuantity() > 0)
                .filter(l -> unitCostByRequirement.containsKey(l.getRequirementId()))
                .toList();
        if (purchaseLines.isEmpty()) {
            return Map.of();
        }

        List<UUID> studentIds = purchaseLines.stream().map(AllocationLine::getStudentId).distinct().toList();
        Map<UUID, UUID> householdByStudent = studentRepository.findAllById(studentIds).stream()
                .collect(Collectors.toMap(Student::getId, Student::getHouseholdId));

        Map<UUID, Integer> householdTotals = new LinkedHashMap<>();
        for (AllocationLine line : purchaseLines) {
            UUID householdId = householdByStudent.get(line.getStudentId());
            if (householdId == null) {
                continue;
            }
            int unitCost = unitCostByRequirement.get(line.getRequirementId());
            int lineShare = unitCost * line.getPurchaseRequiredQuantity();
            householdTotals.merge(householdId, lineShare, Integer::sum);
        }
        return householdTotals;
    }

    // ==================== Substitution top-up charges (Phase 10) ====================

    /**
     * Splits {@code amountCents} across households proportionally to their share of {@code
     * purchaseRequiredQuantity} for exactly one requirement — the same need-based-proportional math
     * {@link #computeHouseholdTotals} uses per requirement, generalized here to an arbitrary total
     * rather than {@code unitCostCents * purchaseRequiredQuantity} derived from the full purchase
     * plan. Reused by {@code OrderService.recordOrder} for a substitution top-up charge (PRD §9.1
     * update, contract's {@code OrderLine.substitutionResolution} description) rather than
     * duplicating this math a second time — see apps/api/README.md's Phase 10 notes for why this
     * lives here instead of in {@code OrderService}.
     *
     * <p>Rounds each household's share half-up (same as {@link #computeHouseholdTotals}); a
     * household whose rounded share is exactly 0 is omitted from the result entirely, per the
     * contract's own wording ("skip creating a $0 top-up"). As with {@link #computeHouseholdTotals},
     * the sum across households need not equal {@code amountCents} to the cent — accepted V1
     * rounding drift, not a bug.
     */
    Map<UUID, Integer> splitAmountByPurchaseShare(UUID requirementId, int amountCents) {
        List<AllocationLine> purchaseLines = allocationLineRepository
                .findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(List.of(requirementId)).stream()
                .filter(l -> l.getPurchaseRequiredQuantity() > 0)
                .toList();
        if (purchaseLines.isEmpty()) {
            return Map.of();
        }
        int totalQuantity = purchaseLines.stream().mapToInt(AllocationLine::getPurchaseRequiredQuantity).sum();
        if (totalQuantity == 0) {
            return Map.of();
        }

        List<UUID> studentIds = purchaseLines.stream().map(AllocationLine::getStudentId).distinct().toList();
        Map<UUID, UUID> householdByStudent = studentRepository.findAllById(studentIds).stream()
                .collect(Collectors.toMap(Student::getId, Student::getHouseholdId));

        Map<UUID, Integer> quantityByHousehold = new LinkedHashMap<>();
        for (AllocationLine line : purchaseLines) {
            UUID householdId = householdByStudent.get(line.getStudentId());
            if (householdId == null) {
                continue;
            }
            quantityByHousehold.merge(householdId, line.getPurchaseRequiredQuantity(), Integer::sum);
        }

        Map<UUID, Integer> shareByHousehold = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> entry : quantityByHousehold.entrySet()) {
            int share = (int) Math.round(amountCents * (double) entry.getValue() / totalQuantity);
            if (share != 0) {
                shareByHousehold.put(entry.getKey(), share);
            }
        }
        return shareByHousehold;
    }

    /**
     * Creates and persists one new {@code PENDING} {@link Payment} per household affected by a
     * substitution top-up charge (PRD §9.1 update) — reuses {@link #splitAmountByPurchaseShare} for
     * the proportional math and this class's existing {@link Payment} construction exactly as-is,
     * so {@code OrderService.recordOrder} adds no new payment concept and needs no new frontend
     * surface (rides the existing collection UI). Returns the empty list if every household's
     * rounded share came out to $0.
     */
    @Transactional
    List<Payment> createTopUpPayments(UUID poolId, UUID requirementId, int deltaCents) {
        Map<UUID, Integer> shareByHousehold = splitAmountByPurchaseShare(requirementId, deltaCents);
        List<Payment> payments = shareByHousehold.entrySet().stream()
                .map(e -> new Payment(poolId, e.getKey(), e.getValue()))
                .toList();
        return paymentRepository.saveAll(payments);
    }

    /** Organizer-only (contract) — includes household identity, same
     *  organizer-sees-identity-for-coordination precedent as {@code ContributionService
     *  .listForOrganizer}. */
    @Transactional(readOnly = true)
    public List<PaymentResponse> listPaymentsForOrganizer(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());

        List<Payment> payments = paymentRepository.findByPoolIdOrderByCreatedAtAsc(poolId);
        Map<UUID, String> displayNames = householdDisplayNames(
                payments.stream().map(Payment::getHouseholdId).distinct().toList());
        return payments.stream().map(p -> toResponse(p, displayNames.get(p.getHouseholdId()))).toList();
    }

    /** Any member (contract) — the caller's own household's payment for this pool, or {@code
     *  null} (200, not 404) if payments haven't been generated yet or this household has no
     *  residual demand. No {@code householdDisplayName} — it would just be the caller's own
     *  name, same precedent as {@code ContributionService.getMine}. */
    @Transactional(readOnly = true)
    public PaymentResponse getMyPayment(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireMembership(callerUserId, pool.getClassroomId());

        Household household = householdRepository.findByPrimaryParentId(callerUserId).orElse(null);
        if (household == null) {
            return null;
        }
        Payment payment = paymentRepository.findByPoolIdAndHouseholdId(poolId, household.getId()).orElse(null);
        return payment == null ? null : toResponse(payment, null);
    }

    // ==================== Payment actions ====================

    /**
     * The owing household pays via card/Apple Pay/Google Pay (PRD §8.4). Only the household that
     * owns this payment may call it (403 otherwise) — ownership resolved via the caller's own
     * {@link Household}, same "the household's sole parent in V1" model {@code
     * HouseholdService}/{@code ContributionService.withdraw} already rely on. 409 if not currently
     * {@code PENDING}. Calls {@link StripeGateway#createDestinationCharge} against whichever {@code
     * ACTIVE} account this classroom currently has — see {@link OrganizerStripeAccount}'s Javadoc
     * for why no specific account is pinned per-payment.
     */
    @Transactional
    public PaymentResponse payMyPayment(UUID callerUserId, UUID poolId, UUID paymentId, PayPaymentRequest request) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        Payment payment = paymentRepository.findByIdAndPoolId(paymentId, poolId)
                .orElseThrow(() -> new NotFoundException("Payment not found: " + paymentId));

        Household household = householdRepository.findByPrimaryParentId(callerUserId).orElse(null);
        if (household == null || !household.getId().equals(payment.getHouseholdId())) {
            throw new ForbiddenException("Caller's household does not own this payment");
        }
        if (payment.getState() != PaymentState.PENDING) {
            throw new ConflictException("Payment is not PENDING");
        }

        PaymentMethod method = parsePayableMethod(request.method());
        OrganizerStripeAccount account = anyActiveAccount(pool.getClassroomId());
        String paymentIntentId = stripeGateway.createDestinationCharge(account.getStripeAccountId(),
                payment.getAmountCents(), payment.getId());
        payment.markPaid(method, paymentIntentId);
        paymentRepository.save(payment);
        return toResponse(payment, null);
    }

    /** Organizer-only (contract) — {@code PENDING -> PENDING_CASH}. 409 if not {@code PENDING}. */
    @Transactional
    public PaymentResponse markPaymentCashPending(UUID callerUserId, UUID poolId, UUID paymentId) {
        Payment payment = requirePaymentAsOrganizer(callerUserId, poolId, paymentId);
        if (payment.getState() != PaymentState.PENDING) {
            throw new ConflictException("Payment is not PENDING");
        }
        payment.markCashPending();
        paymentRepository.save(payment);
        return toResponse(payment, null);
    }

    /** Organizer-only (contract) — {@code PENDING_CASH -> PAID_CASH_RECEIVED}. 409 if not
     *  {@code PENDING_CASH}. */
    @Transactional
    public PaymentResponse markPaymentCashReceived(UUID callerUserId, UUID poolId, UUID paymentId) {
        Payment payment = requirePaymentAsOrganizer(callerUserId, poolId, paymentId);
        if (payment.getState() != PaymentState.PENDING_CASH) {
            throw new ConflictException("Payment is not PENDING_CASH");
        }
        payment.markCashReceived();
        paymentRepository.save(payment);
        return toResponse(payment, null);
    }

    /**
     * Organizer-only (contract). Full refund only, and only before the pool reaches {@code
     * ORDERED} (PRD §8.4 update's minimum V1 rule — 409 once the pool is {@code ORDERED} or later).
     * 409 if the payment isn't {@code PAID}/{@code PAID_CASH_RECEIVED}. Skips the {@link
     * StripeGateway#refund} call entirely for a {@code CASH} payment — there is nothing to refund
     * via Stripe for money that never moved through it.
     */
    @Transactional
    public PaymentResponse refundPayment(UUID callerUserId, UUID poolId, UUID paymentId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        if (pool.getState().ordinal() >= PoolState.ORDERED.ordinal()) {
            throw new ConflictException("Pool has already reached ORDERED — refunds are no longer available");
        }

        Payment payment = paymentRepository.findByIdAndPoolId(paymentId, poolId)
                .orElseThrow(() -> new NotFoundException("Payment not found: " + paymentId));
        if (payment.getState() != PaymentState.PAID && payment.getState() != PaymentState.PAID_CASH_RECEIVED) {
            throw new ConflictException("Payment is not in a refundable state");
        }

        if (payment.getMethod() != PaymentMethod.CASH) {
            stripeGateway.refund(payment.getStripePaymentIntentId());
        }
        payment.markRefunded();
        paymentRepository.save(payment);
        return toResponse(payment, null);
    }

    // ==================== Summary & finalize ====================

    /**
     * Organizer-only (contract). {@code totalOwedCents}/{@code totalCollectedCents} sum every
     * payment's {@code amountCents} for this pool ({@code PAID}/{@code PAID_CASH_RECEIVED} count as
     * collected); {@code percentCollected} is 100 when nothing is owed (an empty/fully-covered pool
     * trivially satisfies the gate, rather than dividing by zero); {@code outstandingHouseholds} is
     * every payment not in a collected state.
     */
    @Transactional(readOnly = true)
    public PaymentsSummaryResponse getPaymentsSummary(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        return buildSummary(poolId);
    }

    private PaymentsSummaryResponse buildSummary(UUID poolId) {
        List<Payment> payments = paymentRepository.findByPoolIdOrderByCreatedAtAsc(poolId);

        int totalOwed = payments.stream().mapToInt(Payment::getAmountCents).sum();
        int totalCollected = payments.stream().filter(PaymentService::isCollected)
                .mapToInt(Payment::getAmountCents).sum();
        double percentCollected = totalOwed == 0 ? 100.0 : 100.0 * totalCollected / totalOwed;
        boolean meetsThreshold = percentCollected >= THRESHOLD_PERCENT;

        List<Payment> outstanding = payments.stream().filter(p -> !isCollected(p)).toList();
        Map<UUID, String> displayNames = householdDisplayNames(
                outstanding.stream().map(Payment::getHouseholdId).distinct().toList());
        List<OutstandingHouseholdResponse> outstandingHouseholds = outstanding.stream()
                .map(p -> new OutstandingHouseholdResponse(p.getHouseholdId(), displayNames.get(p.getHouseholdId()),
                        p.getAmountCents()))
                .toList();

        return new PaymentsSummaryResponse(totalOwed, totalCollected, percentCollected, THRESHOLD_PERCENT,
                meetsThreshold, outstandingHouseholds);
    }

    private static boolean isCollected(Payment payment) {
        return payment.getState() == PaymentState.PAID || payment.getState() == PaymentState.PAID_CASH_RECEIVED;
    }

    /**
     * Organizer-only (contract). Requires {@code pool.state == PAYMENT_OPEN} (409 otherwise). The
     * threshold summary is recomputed here rather than trusting a stale client-sent value; below
     * 90% requires {@code acknowledgeBelowThreshold: true} in the request (an explicit override,
     * never a lowered threshold) or 409s. One-way: {@code PAYMENT_OPEN -> ORDERED}.
     */
    @Transactional
    public PoolDetailResponse finalizePayments(UUID callerUserId, UUID poolId, FinalizePaymentsRequest request) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        if (pool.getState() != PoolState.PAYMENT_OPEN) {
            throw new ConflictException("Pool is not PAYMENT_OPEN");
        }

        PaymentsSummaryResponse summary = buildSummary(poolId);
        boolean acknowledged = request != null && Boolean.TRUE.equals(request.acknowledgeBelowThreshold());
        if (!summary.meetsThreshold() && !acknowledged) {
            throw new ConflictException(
                    "Below the " + (int) THRESHOLD_PERCENT + "% payment threshold and not acknowledged");
        }

        poolService.transitionToOrdered(pool);
        return poolService.toDetailResponse(pool);
    }

    // ==================== helpers ====================

    private Payment requirePaymentAsOrganizer(UUID callerUserId, UUID poolId, UUID paymentId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        return paymentRepository.findByIdAndPoolId(paymentId, poolId)
                .orElseThrow(() -> new NotFoundException("Payment not found: " + paymentId));
    }

    private OrganizerStripeAccount anyActiveAccount(UUID classroomId) {
        List<OrganizerStripeAccount> active = organizerStripeAccountRepository
                .findByClassroomIdAndStatusOrderByCreatedAtAsc(classroomId, OrganizerStripeAccountStatus.ACTIVE);
        if (active.isEmpty()) {
            throw new ConflictException("Stripe onboarding isn't ACTIVE for this classroom");
        }
        return active.get(0);
    }

    private static PaymentMethod parsePayableMethod(String raw) {
        PaymentMethod method;
        try {
            method = PaymentMethod.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown method: " + raw);
        }
        if (method == PaymentMethod.CASH) {
            throw new BadRequestException("CASH is recorded via mark-cash-pending, not pay");
        }
        return method;
    }

    private Map<UUID, String> householdDisplayNames(List<UUID> householdIds) {
        if (householdIds.isEmpty()) {
            return Map.of();
        }
        List<Household> households = householdRepository.findAllById(householdIds);
        List<UUID> primaryParentIds = households.stream().map(Household::getPrimaryParentId).distinct().toList();
        Map<UUID, String> nameByUserId = appUserRepository.findAllById(primaryParentIds).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getDisplayName));
        return households.stream()
                .collect(Collectors.toMap(Household::getId, h -> nameByUserId.get(h.getPrimaryParentId())));
    }

    private static PaymentResponse toResponse(Payment payment, String householdDisplayName) {
        String method = payment.getState() == PaymentState.PENDING ? null : payment.getMethod().name();
        return new PaymentResponse(
                payment.getId(),
                payment.getPoolId(),
                payment.getHouseholdId(),
                householdDisplayName,
                payment.getAmountCents(),
                method,
                payment.getState().name(),
                payment.getCreatedAt());
    }
}
