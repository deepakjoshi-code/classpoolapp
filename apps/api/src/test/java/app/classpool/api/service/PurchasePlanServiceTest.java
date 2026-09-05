package app.classpool.api.service;

import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.ProductOffer;
import app.classpool.api.domain.PurchasePlan;
import app.classpool.api.domain.PurchasePlanLine;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementStrictness;
import app.classpool.api.domain.ResidualDemandLine;
import app.classpool.api.dto.AddProductOfferRequest;
import app.classpool.api.dto.ProductOfferResponse;
import app.classpool.api.dto.PurchasePlanResponse;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.PoolRepository;
import app.classpool.api.repository.ProductOfferRepository;
import app.classpool.api.repository.PurchasePlanLineRepository;
import app.classpool.api.repository.PurchasePlanRepository;
import app.classpool.api.repository.RequirementRepository;
import app.classpool.api.repository.ResidualDemandLineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit coverage of {@link PurchasePlanService}'s HTTP/authorization/state-machine layer — see
 * {@link PackOptimizerTest} for the DP algorithm itself, exercised independently.
 */
@ExtendWith(MockitoExtension.class)
class PurchasePlanServiceTest {

    @Mock
    private ProductOfferRepository productOfferRepository;
    @Mock
    private PurchasePlanRepository purchasePlanRepository;
    @Mock
    private PurchasePlanLineRepository purchasePlanLineRepository;
    @Mock
    private RequirementRepository requirementRepository;
    @Mock
    private ResidualDemandLineRepository residualDemandLineRepository;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private PoolRepository poolRepository;

    private PurchasePlanService purchasePlanService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID classroomId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PoolAssembler poolAssembler = new PoolAssembler(requirementRepository);
        RequirementAssembler requirementAssembler = new RequirementAssembler();
        PoolService poolService = new PoolService(poolRepository, requirementRepository, membershipRepository,
                poolAssembler, requirementAssembler);
        purchasePlanService = new PurchasePlanService(productOfferRepository, purchasePlanRepository,
                purchasePlanLineRepository, requirementRepository, residualDemandLineRepository, poolService);
    }

    // ---- addProductOffer ----

    @Test
    void addProductOffer_createsOffer_whenPoolIsReconciling() {
        Pool pool = newPool(PoolState.RECONCILING);
        Requirement requirement = newRequirement(pool.getId(), "Pencils", 320);
        stubOrganizer(pool);
        when(requirementRepository.findByIdAndPoolId(requirement.getId(), pool.getId()))
                .thenReturn(Optional.of(requirement));
        when(productOfferRepository.save(any(ProductOffer.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductOfferResponse response = purchasePlanService.addProductOffer(callerId, pool.getId(),
                requirement.getId(), new AddProductOfferRequest("Amazon", 24, 499, null, null));

        assertThat(response.retailer()).isEqualTo("Amazon");
        assertThat(response.packQuantity()).isEqualTo(24);
        assertThat(response.priceCents()).isEqualTo(499);
        assertThat(response.shippingCents()).isZero(); // defaults to 0 when omitted
        assertThat(response.requirementName()).isEqualTo("Pencils");
    }

    @Test
    void addProductOffer_throwsConflict_whenPoolIsNotReconciling() {
        Pool pool = newPool(PoolState.OPEN_FOR_INVENTORY);
        stubOrganizer(pool);

        assertThatThrownBy(() -> purchasePlanService.addProductOffer(callerId, pool.getId(), UUID.randomUUID(),
                new AddProductOfferRequest("Amazon", 24, 499, null, null)))
                .isInstanceOf(ConflictException.class);
        verify(productOfferRepository, never()).save(any());
    }

    @Test
    void addProductOffer_throwsConflict_oncePlanAlreadyGenerated() {
        Pool pool = newPool(PoolState.PURCHASE_PROPOSED);
        stubOrganizer(pool);

        assertThatThrownBy(() -> purchasePlanService.addProductOffer(callerId, pool.getId(), UUID.randomUUID(),
                new AddProductOfferRequest("Amazon", 24, 499, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void addProductOffer_throwsForbidden_forANonOrganizer() {
        Pool pool = newPool(PoolState.RECONCILING);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> purchasePlanService.addProductOffer(callerId, pool.getId(), UUID.randomUUID(),
                new AddProductOfferRequest("Amazon", 24, 499, null, null)))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- removeProductOffer ----

    @Test
    void removeProductOffer_throwsConflict_oncePlanAlreadyExists() {
        Pool pool = newPool(PoolState.PURCHASE_PROPOSED);
        stubOrganizer(pool);
        when(purchasePlanRepository.existsByPoolId(pool.getId())).thenReturn(true);

        assertThatThrownBy(() -> purchasePlanService.removeProductOffer(callerId, pool.getId(), UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);
        verify(productOfferRepository, never()).delete(any());
    }

    @Test
    void removeProductOffer_deletesOffer_whenNoPlanExistsYet() {
        Pool pool = newPool(PoolState.RECONCILING);
        Requirement requirement = newRequirement(pool.getId(), "Pencils", 320);
        ProductOffer offer = newOffer(requirement.getId(), "Amazon", 24, 499);
        stubOrganizer(pool);
        when(purchasePlanRepository.existsByPoolId(pool.getId())).thenReturn(false);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(requirement));
        when(productOfferRepository.findByIdAndRequirementIdIn(offer.getId(), List.of(requirement.getId())))
                .thenReturn(Optional.of(offer));

        purchasePlanService.removeProductOffer(callerId, pool.getId(), offer.getId());

        verify(productOfferRepository).delete(offer);
    }

    // ---- generate ----

    @Test
    void generate_throwsConflict_namingEveryRequirementMissingAnOffer() {
        Pool pool = newPool(PoolState.RECONCILING);
        Requirement pencils = newRequirement(pool.getId(), "Pencils", 320);
        Requirement erasers = newRequirement(pool.getId(), "Erasers", 10);
        stubOrganizer(pool);
        when(purchasePlanRepository.existsByPoolId(pool.getId())).thenReturn(false);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId()))
                .thenReturn(List.of(pencils, erasers));
        when(residualDemandLineRepository.findByRequirementIdInOrderByRequirementIdAsc(
                List.of(pencils.getId(), erasers.getId())))
                .thenReturn(List.of(new ResidualDemandLine(pencils.getId(), 320, 0, 0, 320),
                        new ResidualDemandLine(erasers.getId(), 10, 0, 0, 10)));
        when(productOfferRepository.findByRequirementIdOrderByCreatedAtAsc(pencils.getId())).thenReturn(List.of());
        when(productOfferRepository.findByRequirementIdOrderByCreatedAtAsc(erasers.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> purchasePlanService.generate(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Pencils")
                .hasMessageContaining("Erasers");
        verify(purchasePlanRepository, never()).save(any());
    }

    @Test
    void generate_skipsRequirementsWithZeroResidualDemand_evenWithNoOffers() {
        Pool pool = newPool(PoolState.RECONCILING);
        Requirement fullyOwned = newRequirement(pool.getId(), "Glue Sticks", 4);
        stubOrganizer(pool);
        when(purchasePlanRepository.existsByPoolId(pool.getId())).thenReturn(false);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(fullyOwned));
        when(residualDemandLineRepository.findByRequirementIdInOrderByRequirementIdAsc(List.of(fullyOwned.getId())))
                .thenReturn(List.of(new ResidualDemandLine(fullyOwned.getId(), 4, 4, 0, 0))); // residualDemand = 0
        PurchasePlan savedPlan = new PurchasePlan(pool.getId());
        setField(savedPlan, "id", UUID.randomUUID());
        setField(savedPlan, "proposedAt", Instant.now());
        when(purchasePlanRepository.save(any(PurchasePlan.class))).thenReturn(savedPlan);
        when(purchasePlanLineRepository.findByPurchasePlanIdOrderByRequirementIdAscProductOfferIdAsc(
                savedPlan.getId())).thenReturn(List.of());

        PurchasePlanResponse response = purchasePlanService.generate(callerId, pool.getId());

        assertThat(response.lines()).isEmpty();
        assertThat(response.totalCostCents()).isZero();
        // Never even asked for offers on a requirement with nothing left to purchase.
        verify(productOfferRepository, never()).findByRequirementIdOrderByCreatedAtAsc(any());
        assertThat(pool.getState()).isEqualTo(PoolState.PURCHASE_PROPOSED);
    }

    @Test
    void generate_runsTheOptimizerAndFreezesLines_forThePencilExample() {
        Pool pool = newPool(PoolState.RECONCILING);
        Requirement pencils = newRequirement(pool.getId(), "Pencils", 320);
        ProductOffer pack24 = newOffer(pencils.getId(), "Amazon", 24, 499);
        ProductOffer pack48 = newOffer(pencils.getId(), "Amazon", 48, 849);
        ProductOffer pack144 = newOffer(pencils.getId(), "Amazon", 144, 1899);

        stubOrganizer(pool);
        when(purchasePlanRepository.existsByPoolId(pool.getId())).thenReturn(false);
        when(requirementRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of(pencils));
        when(residualDemandLineRepository.findByRequirementIdInOrderByRequirementIdAsc(List.of(pencils.getId())))
                .thenReturn(List.of(new ResidualDemandLine(pencils.getId(), 320, 0, 0, 320)));
        when(productOfferRepository.findByRequirementIdOrderByCreatedAtAsc(pencils.getId()))
                .thenReturn(List.of(pack24, pack48, pack144));

        PurchasePlan savedPlan = new PurchasePlan(pool.getId());
        setField(savedPlan, "id", UUID.randomUUID());
        setField(savedPlan, "proposedAt", Instant.now());
        when(purchasePlanRepository.save(any(PurchasePlan.class))).thenReturn(savedPlan);

        ArgumentCaptor<List<PurchasePlanLine>> linesCaptor = ArgumentCaptor.forClass(List.class);
        when(purchasePlanLineRepository.findByPurchasePlanIdOrderByRequirementIdAscProductOfferIdAsc(
                savedPlan.getId())).thenAnswer(inv -> List.of()); // response re-fetch not the focus of this test

        purchasePlanService.generate(callerId, pool.getId());

        verify(purchasePlanLineRepository).saveAll(linesCaptor.capture());
        List<PurchasePlanLine> lines = linesCaptor.getValue();
        assertThat(lines).hasSize(2);
        int totalCost = lines.stream().mapToInt(PurchasePlanLine::getTotalCostCents).sum();
        int totalWaste = lines.stream().mapToInt(PurchasePlanLine::getWasteQuantity).sum();
        assertThat(totalCost).isEqualTo(4647);
        assertThat(totalWaste).isEqualTo(16); // attributed to exactly one line, never split
        assertThat(lines).filteredOn(l -> l.getWasteQuantity() > 0).hasSize(1);
        assertThat(pool.getState()).isEqualTo(PoolState.PURCHASE_PROPOSED);
    }

    @Test
    void generate_throwsConflict_whenPoolIsNotReconciling() {
        Pool pool = newPool(PoolState.OPEN_FOR_INVENTORY);
        stubOrganizer(pool);

        assertThatThrownBy(() -> purchasePlanService.generate(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(residualDemandLineRepository);
    }

    @Test
    void generate_throwsConflict_whenAPlanAlreadyExists() {
        Pool pool = newPool(PoolState.RECONCILING);
        stubOrganizer(pool);
        when(purchasePlanRepository.existsByPoolId(pool.getId())).thenReturn(true);

        assertThatThrownBy(() -> purchasePlanService.generate(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(residualDemandLineRepository);
    }

    @Test
    void generate_throwsForbidden_forANonOrganizer() {
        Pool pool = newPool(PoolState.RECONCILING);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> purchasePlanService.generate(callerId, pool.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- getPurchasePlan ----

    @Test
    void getPurchasePlan_throwsConflict_whenNoPlanExistsYet() {
        Pool pool = newPool(PoolState.RECONCILING);
        stubOrganizer(pool);
        when(purchasePlanRepository.findByPoolId(pool.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> purchasePlanService.getPurchasePlan(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class);
    }

    // ---- approve ----

    @Test
    void approve_transitionsProposedToApproved_withoutTouchingPoolState() {
        Pool pool = newPool(PoolState.PURCHASE_PROPOSED);
        PurchasePlan plan = new PurchasePlan(pool.getId());
        setField(plan, "id", UUID.randomUUID());
        setField(plan, "proposedAt", Instant.now());
        stubOrganizer(pool);
        when(purchasePlanRepository.findByPoolId(pool.getId())).thenReturn(Optional.of(plan));
        when(purchasePlanRepository.save(any(PurchasePlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(purchasePlanLineRepository.findByPurchasePlanIdOrderByRequirementIdAscProductOfferIdAsc(plan.getId()))
                .thenReturn(List.of());

        PurchasePlanResponse response = purchasePlanService.approve(callerId, pool.getId());

        assertThat(response.state()).isEqualTo("APPROVED");
        assertThat(response.approvedAt()).isNotNull();
        // Approving the plan never mutates the Pool's own state (Phase 9's job).
        assertThat(pool.getState()).isEqualTo(PoolState.PURCHASE_PROPOSED);
        verify(poolRepository, never()).save(pool);
    }

    @Test
    void approve_throwsConflict_whenAlreadyApproved() {
        Pool pool = newPool(PoolState.PURCHASE_PROPOSED);
        PurchasePlan plan = new PurchasePlan(pool.getId());
        setField(plan, "id", UUID.randomUUID());
        plan.approve();
        stubOrganizer(pool);
        when(purchasePlanRepository.findByPoolId(pool.getId())).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> purchasePlanService.approve(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class);
        verify(purchasePlanRepository, never()).save(any());
    }

    @Test
    void approve_throwsConflict_whenNoPlanExistsYet() {
        Pool pool = newPool(PoolState.RECONCILING);
        stubOrganizer(pool);
        when(purchasePlanRepository.findByPoolId(pool.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> purchasePlanService.approve(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void approve_throwsForbidden_forANonOrganizer() {
        Pool pool = newPool(PoolState.PURCHASE_PROPOSED);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> purchasePlanService.approve(callerId, pool.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- fixtures ----

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

    private static Requirement newRequirement(UUID poolId, String name, int quantityPerStudent) {
        Requirement requirement = new Requirement(poolId, name, quantityPerStudent, null,
                RequirementStrictness.EQUIVALENT_ALLOWED);
        setField(requirement, "id", UUID.randomUUID());
        setField(requirement, "createdAt", Instant.now());
        setField(requirement, "updatedAt", Instant.now());
        return requirement;
    }

    private static ProductOffer newOffer(UUID requirementId, String retailer, int packQuantity, int priceCents) {
        ProductOffer offer = new ProductOffer(requirementId, retailer, packQuantity, priceCents, 0, null);
        setField(offer, "id", UUID.randomUUID());
        setField(offer, "createdAt", Instant.now());
        return offer;
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
