package app.classpool.api.service;

import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.ProductOffer;
import app.classpool.api.domain.PurchasePlan;
import app.classpool.api.domain.PurchasePlanLine;
import app.classpool.api.domain.PurchasePlanState;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.ResidualDemandLine;
import app.classpool.api.dto.AddProductOfferRequest;
import app.classpool.api.dto.ProductOfferResponse;
import app.classpool.api.dto.PurchasePlanLineResponse;
import app.classpool.api.dto.PurchasePlanResponse;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.ProductOfferRepository;
import app.classpool.api.repository.PurchasePlanLineRepository;
import app.classpool.api.repository.PurchasePlanRepository;
import app.classpool.api.repository.RequirementRepository;
import app.classpool.api.repository.ResidualDemandLineRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The bulk-pack optimizer's surface (PRD §7.1/§9.4) — candidate {@link ProductOffer} CRUD plus
 * {@link #generate}, which runs {@link PackOptimizer}'s DP once per requirement with residual
 * demand and freezes the result as a {@link PurchasePlan}/{@link PurchasePlanLine} set. One
 * service for the whole feature, same size precedent as {@code ContributionService} scoping all of
 * Phase 5 into a single class.
 *
 * <p>Maps directly onto the V1 migration's already-present {@code product_offer}/{@code
 * purchase_plan}/{@code purchase_plan_line} tables — no schema changes needed for this phase
 * (unlike Phase 6/7's {@code allocation} table, which turned out schema-incompatible and got a
 * fresh migration instead).
 */
@Service
public class PurchasePlanService {

    private final ProductOfferRepository productOfferRepository;
    private final PurchasePlanRepository purchasePlanRepository;
    private final PurchasePlanLineRepository purchasePlanLineRepository;
    private final RequirementRepository requirementRepository;
    private final ResidualDemandLineRepository residualDemandLineRepository;
    private final PoolService poolService;

    public PurchasePlanService(ProductOfferRepository productOfferRepository,
                                PurchasePlanRepository purchasePlanRepository,
                                PurchasePlanLineRepository purchasePlanLineRepository,
                                RequirementRepository requirementRepository,
                                ResidualDemandLineRepository residualDemandLineRepository,
                                PoolService poolService) {
        this.productOfferRepository = productOfferRepository;
        this.purchasePlanRepository = purchasePlanRepository;
        this.purchasePlanLineRepository = purchasePlanLineRepository;
        this.requirementRepository = requirementRepository;
        this.residualDemandLineRepository = residualDemandLineRepository;
        this.poolService = poolService;
    }

    /**
     * Organizer/co-organizer only (contract). Only while the pool is {@code RECONCILING} — once a
     * plan has been generated ({@code PURCHASE_PROPOSED}+) adding more candidate offers isn't
     * supported in V1, same one-shot instinct as {@code reconcile} itself; and offers entered
     * before reconcile has run at all would have nothing to be validated against, so the single
     * {@code state != RECONCILING} check covers both directions in one line, per the contract.
     * {@code shippingCents} defaults to 0 when omitted — stored on the entity/response but, per
     * PRD §7.1's V1 scope, never fed into {@link PackOptimizer}'s cost function (see {@link
     * #generate}'s Javadoc for why).
     */
    @Transactional
    public ProductOfferResponse addProductOffer(UUID callerUserId, UUID poolId, UUID requirementId,
                                                 AddProductOfferRequest request) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        requireReconciling(pool);

        Requirement requirement = requirementRepository.findByIdAndPoolId(requirementId, poolId)
                .orElseThrow(() -> new NotFoundException("Requirement not found: " + requirementId));

        int shippingCents = request.shippingCents() == null ? 0 : request.shippingCents();
        ProductOffer offer = productOfferRepository.save(new ProductOffer(requirement.getId(), request.retailer(),
                request.packQuantity(), request.priceCents(), shippingCents, request.affiliateUrl()));
        return toOfferResponse(offer, requirement);
    }

    /** Every candidate offer across the pool, organizer-only (contract) — the frontend groups
     *  these by requirement itself, same "fetch once, group client-side" pattern as the allocation
     *  summary. */
    @Transactional(readOnly = true)
    public List<ProductOfferResponse> listProductOffers(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());

        List<Requirement> requirements = requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId);
        if (requirements.isEmpty()) {
            return List.of();
        }
        Map<UUID, Requirement> requirementsById = toMapById(requirements);
        List<UUID> requirementIds = requirements.stream().map(Requirement::getId).toList();
        List<ProductOffer> offers = productOfferRepository.findByRequirementIdInOrderByCreatedAtAsc(requirementIds);
        return offers.stream().map(o -> toOfferResponse(o, requirementsById.get(o.getRequirementId()))).toList();
    }

    /** Organizer removes a candidate offer before generating the plan (contract) — 409 once a plan
     *  already exists for this pool, same one-shot boundary as {@link #addProductOffer}. */
    @Transactional
    public void removeProductOffer(UUID callerUserId, UUID poolId, UUID offerId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        if (purchasePlanRepository.existsByPoolId(poolId)) {
            throw new ConflictException("A purchase plan has already been generated for this pool");
        }

        List<UUID> requirementIds = requirementIdsForPool(poolId);
        ProductOffer offer = productOfferRepository.findByIdAndRequirementIdIn(offerId, requirementIds)
                .orElseThrow(() -> new NotFoundException("Product offer not found: " + offerId));
        productOfferRepository.delete(offer);
    }

    /**
     * Organizer runs the optimizer (PRD §7.1). Requires the pool to be {@code RECONCILING} and, for
     * every requirement with {@code residualDemand > 0} (Phase 6/7's frozen {@link
     * ResidualDemandLine} snapshot — never recomputed here), at least one {@link ProductOffer};
     * missing any 409s naming every such requirement at once, rather than failing on the first one
     * found, so the organizer can fix all of them in one pass. One-way: on success, creates the
     * {@link PurchasePlan} ({@code PROPOSED}) and its {@link PurchasePlanLine}s, then moves the pool
     * {@code RECONCILING -> PURCHASE_PROPOSED} ({@link PoolService#transitionToPurchaseProposed}).
     * Re-running is not supported in V1 — 409 if a plan already exists for this pool.
     *
     * <p><b>Design note — shipping is not in the cost function.</b> {@link PackOptimizer} compares
     * offers on {@code priceCents} alone; {@code shippingCents} is stored on {@link ProductOffer}
     * and returned in every response, but folding it into the optimizer's cost (or modeling
     * per-order shipping amortization across multiple lines) is explicitly out of scope for this
     * phase — a V1 simplification in the same spirit as the contract's "cost first, waste as
     * tie-break" rule not yet reasoning about tax/fees either. Flagged in apps/api/README.md.
     */
    @Transactional
    public PurchasePlanResponse generate(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        requireReconciling(pool);
        if (purchasePlanRepository.existsByPoolId(poolId)) {
            throw new ConflictException("A purchase plan has already been generated for this pool");
        }

        List<Requirement> requirements = requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId);
        Map<UUID, Requirement> requirementsById = toMapById(requirements);
        List<UUID> requirementIds = requirements.stream().map(Requirement::getId).toList();

        List<ResidualDemandLine> needingPurchase = requirementIds.isEmpty() ? List.of()
                : residualDemandLineRepository.findByRequirementIdInOrderByRequirementIdAsc(requirementIds).stream()
                        .filter(rl -> rl.getResidualDemand() > 0)
                        .toList();

        // Validate BEFORE running the DP: every requirement with residual demand must already have
        // at least one candidate offer, named all at once rather than failing on the first miss.
        List<String> missingRequirementNames = new ArrayList<>();
        Map<UUID, List<ProductOffer>> offersByRequirementId = new LinkedHashMap<>();
        for (ResidualDemandLine residualLine : needingPurchase) {
            List<ProductOffer> offers = productOfferRepository
                    .findByRequirementIdOrderByCreatedAtAsc(residualLine.getRequirementId());
            if (offers.isEmpty()) {
                Requirement requirement = requirementsById.get(residualLine.getRequirementId());
                missingRequirementNames
                        .add(requirement == null ? residualLine.getRequirementId().toString() : requirement.getName());
            } else {
                offersByRequirementId.put(residualLine.getRequirementId(), offers);
            }
        }
        if (!missingRequirementNames.isEmpty()) {
            throw new ConflictException(
                    "Missing ProductOffer for requirement(s) with residual demand: "
                            + String.join(", ", missingRequirementNames));
        }

        PurchasePlan plan = purchasePlanRepository.save(new PurchasePlan(poolId));
        List<PurchasePlanLine> lines = new ArrayList<>();
        for (ResidualDemandLine residualLine : needingPurchase) {
            List<ProductOffer> offers = offersByRequirementId.get(residualLine.getRequirementId());
            List<PackOptimizer.OfferInput> offerInputs = offers.stream()
                    .map(o -> new PackOptimizer.OfferInput(o.getId(), o.getPackQuantity(), o.getPriceCents()))
                    .toList();
            PackOptimizer.OptimizationResult result = PackOptimizer.optimize(residualLine.getResidualDemand(),
                    offerInputs);

            // The whole requirement's waste is attributed to exactly one line — the first, sorted
            // by product_offer_id (PackOptimizer's own deterministic ordering) — never split or
            // double-counted across the other lines for the same requirement.
            List<PackOptimizer.ChosenOffer> chosenOffers = result.chosenOffers();
            for (int i = 0; i < chosenOffers.size(); i++) {
                PackOptimizer.ChosenOffer chosen = chosenOffers.get(i);
                int wasteQuantity = i == 0 ? result.wasteQuantity() : 0;
                lines.add(new PurchasePlanLine(plan.getId(), residualLine.getRequirementId(), chosen.offerId(),
                        chosen.packCount(), chosen.lineCostCents(), wasteQuantity));
            }
        }
        purchasePlanLineRepository.saveAll(lines);
        poolService.transitionToPurchaseProposed(pool);

        return buildPlanResponse(plan);
    }

    /** Organizer-only (contract). 409 if {@link #generate} hasn't run yet for this pool. */
    @Transactional(readOnly = true)
    public PurchasePlanResponse getPurchasePlan(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        PurchasePlan plan = purchasePlanRepository.findByPoolId(poolId)
                .orElseThrow(() -> new ConflictException("No purchase plan has been generated for this pool yet"));
        return buildPlanResponse(plan);
    }

    /**
     * Organizer approves the generated plan (contract's "organizer selects plan" V1 flow step),
     * {@code PROPOSED -> APPROVED}. Deliberately does <em>not</em> change {@code Pool.state} —
     * billing/payment (Phase 9) owns the next pool-state transition once an approved plan exists,
     * per the contract's own summary. 409 if no plan exists yet, or it's already {@code APPROVED}.
     */
    @Transactional
    public PurchasePlanResponse approve(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        PurchasePlan plan = purchasePlanRepository.findByPoolId(poolId)
                .orElseThrow(() -> new ConflictException("No purchase plan has been generated for this pool yet"));
        if (plan.getState() == PurchasePlanState.APPROVED) {
            throw new ConflictException("Purchase plan is already approved");
        }
        plan.approve();
        purchasePlanRepository.save(plan);
        return buildPlanResponse(plan);
    }

    private void requireReconciling(Pool pool) {
        if (pool.getState() != PoolState.RECONCILING) {
            throw new ConflictException(
                    "Pool is not RECONCILING — reconcile may not have run yet, or a plan is already generated");
        }
    }

    private List<UUID> requirementIdsForPool(UUID poolId) {
        return requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId).stream().map(Requirement::getId)
                .toList();
    }

    private PurchasePlanResponse buildPlanResponse(PurchasePlan plan) {
        List<PurchasePlanLine> lines = purchasePlanLineRepository
                .findByPurchasePlanIdOrderByRequirementIdAscProductOfferIdAsc(plan.getId());
        if (lines.isEmpty()) {
            return toPlanResponse(plan, List.of());
        }

        List<UUID> requirementIds = lines.stream().map(PurchasePlanLine::getRequirementId).distinct().toList();
        Map<UUID, Requirement> requirementsById = requirementRepository.findAllById(requirementIds).stream()
                .collect(Collectors.toMap(Requirement::getId, r -> r));
        List<UUID> offerIds = lines.stream().map(PurchasePlanLine::getProductOfferId).filter(Objects::nonNull)
                .distinct().toList();
        Map<UUID, ProductOffer> offersById = productOfferRepository.findAllById(offerIds).stream()
                .collect(Collectors.toMap(ProductOffer::getId, o -> o));

        List<PurchasePlanLineResponse> lineResponses = lines.stream()
                .map(line -> toLineResponse(line, requirementsById.get(line.getRequirementId()),
                        offersById.get(line.getProductOfferId())))
                .toList();
        return toPlanResponse(plan, lineResponses);
    }

    private static PurchasePlanResponse toPlanResponse(PurchasePlan plan, List<PurchasePlanLineResponse> lines) {
        int totalCostCents = lines.stream().mapToInt(PurchasePlanLineResponse::totalCostCents).sum();
        return new PurchasePlanResponse(plan.getId(), plan.getPoolId(), plan.getState().name(), totalCostCents,
                lines, plan.getProposedAt(), plan.getApprovedAt());
    }

    private static PurchasePlanLineResponse toLineResponse(PurchasePlanLine line, Requirement requirement,
                                                             ProductOffer offer) {
        return new PurchasePlanLineResponse(
                line.getRequirementId(),
                requirement == null ? null : requirement.getName(),
                line.getProductOfferId(),
                offer == null ? null : offer.getRetailer(),
                offer == null ? 0 : offer.getPackQuantity(),
                line.getPackCount(),
                line.getTotalCostCents(),
                line.getWasteQuantity());
    }

    private static ProductOfferResponse toOfferResponse(ProductOffer offer, Requirement requirement) {
        return new ProductOfferResponse(
                offer.getId(),
                offer.getRequirementId(),
                requirement == null ? null : requirement.getName(),
                offer.getRetailer(),
                offer.getPackQuantity(),
                offer.getPriceCents(),
                offer.getShippingCents(),
                offer.getAffiliateUrl(),
                offer.getCreatedAt());
    }

    private static Map<UUID, Requirement> toMapById(List<Requirement> requirements) {
        return requirements.stream().collect(Collectors.toMap(Requirement::getId, r -> r));
    }
}
