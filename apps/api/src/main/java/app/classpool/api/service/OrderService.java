package app.classpool.api.service;

import app.classpool.api.domain.Order;
import app.classpool.api.domain.OrderLine;
import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.PurchasePlan;
import app.classpool.api.domain.PurchasePlanLine;
import app.classpool.api.domain.Requirement;
import app.classpool.api.dto.OrderLineResponse;
import app.classpool.api.dto.OrderResponse;
import app.classpool.api.dto.RecordOrderLineRequest;
import app.classpool.api.dto.RecordOrderRequest;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.repository.OrderLineRepository;
import app.classpool.api.repository.OrderRepository;
import app.classpool.api.repository.PurchasePlanLineRepository;
import app.classpool.api.repository.PurchasePlanRepository;
import app.classpool.api.repository.RequirementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The organizer's "record what was actually bought" surface (PRD §9.1), one service same size
 * precedent as {@code PurchasePlanService}/{@code ContributionService} scoping their whole phase
 * into a single class. Maps directly onto the V1 migration's already-present {@code "order"}/
 * {@code order_line} tables — no schema changes needed, except the flagged
 * {@code substitutionDeltaCents}/{@code substitutionResolution} gap (see {@link
 * app.classpool.api.domain.SubstitutionResolution}'s Javadoc and apps/api/README.md's Phase 10
 * notes).
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;
    private final PurchasePlanRepository purchasePlanRepository;
    private final PurchasePlanLineRepository purchasePlanLineRepository;
    private final RequirementRepository requirementRepository;
    private final PaymentService paymentService;
    private final PoolService poolService;

    public OrderService(OrderRepository orderRepository, OrderLineRepository orderLineRepository,
                         PurchasePlanRepository purchasePlanRepository,
                         PurchasePlanLineRepository purchasePlanLineRepository,
                         RequirementRepository requirementRepository, PaymentService paymentService,
                         PoolService poolService) {
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.purchasePlanRepository = purchasePlanRepository;
        this.purchasePlanLineRepository = purchasePlanLineRepository;
        this.requirementRepository = requirementRepository;
        this.paymentService = paymentService;
        this.poolService = poolService;
    }

    /**
     * Organizer-only (contract). Requires {@code pool.state == ORDERED} — the hand-off point
     * {@code PaymentService.finalizePayments} left the pool at — and 409s if an {@link Order}
     * already exists for this pool (one-shot, same instinct as every other generate/record-style
     * action in this codebase). One {@link OrderLine} per {@link PurchasePlanLine} on the pool's
     * approved plan; {@code actualCostCents} defaults to the plan line's own {@code
     * totalCostCents} and {@code actualDescription} defaults to {@code null} (no substitution)
     * unless the request names that {@code purchasePlanLineId} with an override.
     *
     * <p>Per line, {@code delta = actualCostCents - plannedCostCents}. {@code delta == 0} records
     * no substitution at all. Otherwise the 10% threshold is evaluated as {@code 10 * abs(delta)
     * <= plannedCostCents} — algebraically identical to {@code abs(delta) <= 10% of
     * plannedCostCents} but exact integer comparison, avoiding any floating-point boundary surprise
     * right at 10% (the contract's own "<=10%" wording is inclusive — exactly 10% is {@code
     * ABSORBED}, not {@code TOP_UP_CHARGED}). A delta over the threshold calls {@link
     * PaymentService#createTopUpPayments} for that line's requirement, splitting the delta across
     * every household that owed a purchase share of it — the exact same proportional math {@code
     * generatePayments} used, reused rather than duplicated (see {@code PaymentService
     * .splitAmountByPurchaseShare}'s Javadoc).
     */
    @Transactional
    public OrderResponse recordOrder(UUID callerUserId, UUID poolId, RecordOrderRequest request) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        if (pool.getState() != PoolState.ORDERED) {
            throw new ConflictException("Pool is not ORDERED");
        }
        if (orderRepository.existsByPoolId(poolId)) {
            throw new ConflictException("An order has already been recorded for this pool");
        }

        PurchasePlan plan = purchasePlanRepository.findByPoolId(poolId)
                .orElseThrow(() -> new ConflictException("No purchase plan exists for this pool"));
        List<PurchasePlanLine> planLines = purchasePlanLineRepository
                .findByPurchasePlanIdOrderByRequirementIdAscProductOfferIdAsc(plan.getId());

        Map<UUID, RecordOrderLineRequest> overridesByPlanLineId = (request == null || request.lines() == null)
                ? Map.of()
                : request.lines().stream()
                        .collect(Collectors.toMap(RecordOrderLineRequest::purchasePlanLineId, l -> l));
        String receiptS3Key = request == null ? null : request.receiptS3Key();

        Order order = orderRepository.save(new Order(poolId, callerUserId, receiptS3Key));

        List<OrderLine> orderLines = new ArrayList<>();
        for (PurchasePlanLine planLine : planLines) {
            RecordOrderLineRequest override = overridesByPlanLineId.get(planLine.getId());
            int plannedCostCents = planLine.getTotalCostCents();
            Integer actualCostCents = (override != null && override.actualCostCents() != null)
                    ? override.actualCostCents() : plannedCostCents;
            String actualDescription = override == null ? null : override.actualDescription();

            orderLines.add(new OrderLine(order.getId(), planLine.getId(), actualDescription, actualCostCents));

            int delta = actualCostCents - plannedCostCents;
            // A genuine overage (delta > 0) past the threshold bills households more, via
            // PaymentService.createTopUpPayments — matching "TOP_UP_CHARGED"'s plain-English name.
            // A large NEGATIVE delta (the substitution came in well UNDER budget) is classified the
            // same way by the abs()-based threshold below (the contract's own math is symmetric),
            // but there is nothing to bill — a Payment can't sensibly carry a negative amountCents,
            // and V1's OrderLine schema has no third "refund due" resolution to route it to instead.
            // Flagged here and in apps/api/README.md rather than silently inventing a refund flow
            // this phase wasn't asked to build.
            if (delta > 0 && 10 * delta > plannedCostCents) {
                paymentService.createTopUpPayments(poolId, planLine.getRequirementId(), delta);
            }
        }
        orderLineRepository.saveAll(orderLines);

        return buildOrderResponse(order);
    }

    /** Organizer-only (contract). 409 if no order has been recorded for this pool yet. */
    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        Order order = orderRepository.findByPoolId(poolId)
                .orElseThrow(() -> new ConflictException("No order has been recorded for this pool yet"));
        return buildOrderResponse(order);
    }

    /**
     * Re-reads the just-saved (or previously saved) {@link OrderLine}s and joins them back against
     * their {@link PurchasePlanLine}s/{@link Requirement}s to compute the substitution fields live
     * — same "assemble the response from the DB after mutation" pattern as {@code
     * PurchasePlanService.buildPlanResponse}, rather than threading computed values through the
     * creation loop.
     */
    private OrderResponse buildOrderResponse(Order order) {
        List<OrderLine> lines = orderLineRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        if (lines.isEmpty()) {
            return toOrderResponse(order, List.of());
        }

        List<UUID> planLineIds = lines.stream().map(OrderLine::getPurchasePlanLineId).distinct().toList();
        Map<UUID, PurchasePlanLine> planLinesById = purchasePlanLineRepository.findAllById(planLineIds).stream()
                .collect(Collectors.toMap(PurchasePlanLine::getId, l -> l));
        List<UUID> requirementIds = planLinesById.values().stream().map(PurchasePlanLine::getRequirementId)
                .distinct().toList();
        Map<UUID, Requirement> requirementsById = requirementRepository.findAllById(requirementIds).stream()
                .collect(Collectors.toMap(Requirement::getId, r -> r));

        List<OrderLineResponse> lineResponses = lines.stream()
                .map(line -> toLineResponse(line, planLinesById.get(line.getPurchasePlanLineId()), requirementsById))
                .toList();
        return toOrderResponse(order, lineResponses);
    }

    private static OrderLineResponse toLineResponse(OrderLine line, PurchasePlanLine planLine,
                                                      Map<UUID, Requirement> requirementsById) {
        int plannedCostCents = planLine == null ? 0 : planLine.getTotalCostCents();
        int actualCostCents = line.getActualCostCents() == null ? plannedCostCents : line.getActualCostCents();
        int delta = actualCostCents - plannedCostCents;
        Integer deltaResponse = delta == 0 ? null : delta;
        String resolution = delta == 0 ? null
                : (10 * Math.abs(delta) <= plannedCostCents ? "ABSORBED" : "TOP_UP_CHARGED");
        Requirement requirement = planLine == null ? null : requirementsById.get(planLine.getRequirementId());
        return new OrderLineResponse(
                line.getId(),
                line.getPurchasePlanLineId(),
                planLine == null ? null : planLine.getRequirementId(),
                requirement == null ? null : requirement.getName(),
                plannedCostCents,
                actualCostCents,
                line.getActualDescription(),
                deltaResponse,
                resolution);
    }

    private static OrderResponse toOrderResponse(Order order, List<OrderLineResponse> lines) {
        return new OrderResponse(order.getId(), order.getPoolId(), order.getOrderedBy(), order.getOrderedAt(),
                order.getReceiptS3Key(), lines);
    }
}
