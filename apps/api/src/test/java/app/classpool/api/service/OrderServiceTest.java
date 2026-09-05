package app.classpool.api.service;

import app.classpool.api.domain.Order;
import app.classpool.api.domain.OrderLine;
import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.PurchasePlan;
import app.classpool.api.domain.PurchasePlanLine;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementStrictness;
import app.classpool.api.dto.OrderResponse;
import app.classpool.api.dto.RecordOrderLineRequest;
import app.classpool.api.dto.RecordOrderRequest;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.OrderLineRepository;
import app.classpool.api.repository.OrderRepository;
import app.classpool.api.repository.PoolRepository;
import app.classpool.api.repository.PurchasePlanLineRepository;
import app.classpool.api.repository.PurchasePlanRepository;
import app.classpool.api.repository.RequirementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Unit coverage of {@link OrderService} — the 10% substitution threshold math (inclusive boundary,
 * per the contract's "&lt;=10%" wording), the top-up-payment hand-off to {@link PaymentService}
 * (mocked here — its own proportional-split math is covered directly in {@link
 * PaymentServiceTest}), and every 403/409 gate.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderLineRepository orderLineRepository;
    @Mock
    private PurchasePlanRepository purchasePlanRepository;
    @Mock
    private PurchasePlanLineRepository purchasePlanLineRepository;
    @Mock
    private RequirementRepository requirementRepository;
    @Mock
    private PaymentService paymentService;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private PoolRepository poolRepository;

    private OrderService orderService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID classroomId = UUID.randomUUID();

    private List<OrderLine> savedOrderLines;

    @BeforeEach
    void setUp() {
        PoolAssembler poolAssembler = new PoolAssembler(requirementRepository);
        RequirementAssembler requirementAssembler = new RequirementAssembler();
        PoolService poolService = new PoolService(poolRepository, requirementRepository, membershipRepository,
                poolAssembler, requirementAssembler);
        orderService = new OrderService(orderRepository, orderLineRepository, purchasePlanRepository,
                purchasePlanLineRepository, requirementRepository, paymentService, poolService);
    }

    // ---- authorization / state gates ----

    @Test
    void recordOrder_throwsForbidden_whenCallerIsNotOrganizer() {
        Pool pool = newPool(PoolState.ORDERED);
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> orderService.recordOrder(callerId, pool.getId(), null))
                .isInstanceOf(ForbiddenException.class);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void recordOrder_throwsConflict_whenPoolIsNotOrdered() {
        Pool pool = newPool(PoolState.PAYMENT_OPEN);
        stubOrganizer(pool);

        assertThatThrownBy(() -> orderService.recordOrder(callerId, pool.getId(), null))
                .isInstanceOf(ConflictException.class);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void recordOrder_throwsConflict_whenAnOrderAlreadyExists() {
        Pool pool = newPool(PoolState.ORDERED);
        stubOrganizer(pool);
        when(orderRepository.existsByPoolId(pool.getId())).thenReturn(true);

        assertThatThrownBy(() -> orderService.recordOrder(callerId, pool.getId(), null))
                .isInstanceOf(ConflictException.class);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void getOrder_throwsConflict_whenNoOrderRecordedYet() {
        Pool pool = newPool(PoolState.ORDERED);
        stubOrganizer(pool);
        when(orderRepository.findByPoolId(pool.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(callerId, pool.getId()))
                .isInstanceOf(ConflictException.class);
    }

    // ---- substitution threshold math ----

    @Test
    void recordOrder_defaultsToPlannedCost_andRecordsNoSubstitution_whenNoOverrideGiven() {
        Pool pool = newPool(PoolState.ORDERED);
        Requirement pencils = newRequirement(pool.getId(), "Pencils");
        PurchasePlanLine planLine = newPlanLine(pencils.getId(), 1000);
        stubOrganizer(pool);
        stubPlanAndPersistence(pool.getId(), planLine);
        when(requirementRepository.findAllById(any())).thenReturn(List.of(pencils));

        OrderResponse response = orderService.recordOrder(callerId, pool.getId(), null);

        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().get(0).actualCostCents()).isEqualTo(1000);
        assertThat(response.lines().get(0).actualDescription()).isNull();
        assertThat(response.lines().get(0).substitutionDeltaCents()).isNull();
        assertThat(response.lines().get(0).substitutionResolution()).isNull();
        verify(paymentService, never()).createTopUpPayments(any(), any(), anyInt());
    }

    @Test
    void recordOrder_absorbsDelta_atExactlyTenPercentThreshold_inclusive() {
        Pool pool = newPool(PoolState.ORDERED);
        Requirement pencils = newRequirement(pool.getId(), "Pencils");
        PurchasePlanLine planLine = newPlanLine(pencils.getId(), 1000);
        stubOrganizer(pool);
        stubPlanAndPersistence(pool.getId(), planLine);
        when(requirementRepository.findAllById(any())).thenReturn(List.of(pencils));

        RecordOrderRequest request = new RecordOrderRequest(null,
                List.of(new RecordOrderLineRequest(planLine.getId(), 1100, "off-brand pencils")));
        OrderResponse response = orderService.recordOrder(callerId, pool.getId(), request);

        assertThat(response.lines().get(0).substitutionDeltaCents()).isEqualTo(100);
        assertThat(response.lines().get(0).substitutionResolution()).isEqualTo("ABSORBED");
        verify(paymentService, never()).createTopUpPayments(any(), any(), anyInt());
    }

    @Test
    void recordOrder_topUpCharges_justOverTenPercentThreshold() {
        Pool pool = newPool(PoolState.ORDERED);
        Requirement pencils = newRequirement(pool.getId(), "Pencils");
        PurchasePlanLine planLine = newPlanLine(pencils.getId(), 1000);
        stubOrganizer(pool);
        stubPlanAndPersistence(pool.getId(), planLine);
        when(requirementRepository.findAllById(any())).thenReturn(List.of(pencils));

        RecordOrderRequest request = new RecordOrderRequest(null,
                List.of(new RecordOrderLineRequest(planLine.getId(), 1101, "premium pencils")));
        OrderResponse response = orderService.recordOrder(callerId, pool.getId(), request);

        assertThat(response.lines().get(0).substitutionDeltaCents()).isEqualTo(101);
        assertThat(response.lines().get(0).substitutionResolution()).isEqualTo("TOP_UP_CHARGED");
        verify(paymentService, times(1)).createTopUpPayments(pool.getId(), pencils.getId(), 101);
    }

    @Test
    void recordOrder_negativeDeltaBeyondThreshold_isLabeledTopUpCharged_butNeverBillsAnyone() {
        Pool pool = newPool(PoolState.ORDERED);
        Requirement pencils = newRequirement(pool.getId(), "Pencils");
        PurchasePlanLine planLine = newPlanLine(pencils.getId(), 1000);
        stubOrganizer(pool);
        stubPlanAndPersistence(pool.getId(), planLine);
        when(requirementRepository.findAllById(any())).thenReturn(List.of(pencils));

        // Came in well UNDER budget (a much cheaper substitution) — still >10% away from planned in
        // absolute terms, so the same threshold math labels it TOP_UP_CHARGED, but there is nothing
        // to bill (see OrderService.recordOrder's Javadoc for why this doesn't create a Payment).
        RecordOrderRequest request = new RecordOrderRequest(null,
                List.of(new RecordOrderLineRequest(planLine.getId(), 800, "cheaper pencils")));
        OrderResponse response = orderService.recordOrder(callerId, pool.getId(), request);

        assertThat(response.lines().get(0).substitutionDeltaCents()).isEqualTo(-200);
        assertThat(response.lines().get(0).substitutionResolution()).isEqualTo("TOP_UP_CHARGED");
        verify(paymentService, never()).createTopUpPayments(any(), any(), anyInt());
    }

    // ---- helpers ----

    private void stubOrganizer(Pool pool) {
        when(poolRepository.findById(pool.getId())).thenReturn(Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
    }

    private void stubPlanAndPersistence(UUID poolId, PurchasePlanLine... planLines) {
        PurchasePlan plan = new PurchasePlan(poolId);
        setField(plan, "id", UUID.randomUUID());
        when(purchasePlanRepository.findByPoolId(poolId)).thenReturn(Optional.of(plan));
        when(purchasePlanLineRepository.findByPurchasePlanIdOrderByRequirementIdAscProductOfferIdAsc(plan.getId()))
                .thenReturn(List.of(planLines));
        when(purchasePlanLineRepository.findAllById(any())).thenReturn(List.of(planLines));

        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            setField(order, "id", UUID.randomUUID());
            setField(order, "orderedAt", Instant.now());
            return order;
        });
        when(orderLineRepository.saveAll(any())).thenAnswer(inv -> {
            List<OrderLine> lines = inv.getArgument(0);
            for (OrderLine line : lines) {
                setField(line, "id", UUID.randomUUID());
                setField(line, "createdAt", Instant.now());
            }
            savedOrderLines = lines;
            return lines;
        });
        when(orderLineRepository.findByOrderIdOrderByCreatedAtAsc(any())).thenAnswer(inv -> savedOrderLines);
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

    private static PurchasePlanLine newPlanLine(UUID requirementId, int totalCostCents) {
        PurchasePlanLine line = new PurchasePlanLine(UUID.randomUUID(), requirementId, UUID.randomUUID(), 1,
                totalCostCents, 0);
        setField(line, "id", UUID.randomUUID());
        setField(line, "createdAt", Instant.now());
        return line;
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
