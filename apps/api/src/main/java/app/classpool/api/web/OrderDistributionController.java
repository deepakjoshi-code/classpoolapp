package app.classpool.api.web;

import app.classpool.api.dto.ClassReserveEntryResponse;
import app.classpool.api.dto.DistributionItemResponse;
import app.classpool.api.dto.DistributionSummaryResponse;
import app.classpool.api.dto.GenerateDistributionRequest;
import app.classpool.api.dto.OrderResponse;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.dto.RecordOrderRequest;
import app.classpool.api.service.DistributionService;
import app.classpool.api.service.OrderService;
import app.classpool.api.service.PoolService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Phase 10's ordering/distribution/class-reserve surface (PRD §9.1-9.4) — split out from {@link
 * PoolController} for the same size-driven reason {@code PurchasePlanController}/{@code
 * PaymentController} were (eight routes here alone). {@code completePool} rides along here too,
 * even though it's a one-line {@link PoolService} state transition with no order/distribution data
 * of its own — it's the phase's own terminal step, so it belongs with the rest of this phase's
 * surface rather than back in {@code PoolController}.
 */
@RestController
@RequestMapping("/api/v1/pools")
public class OrderDistributionController {

    private final OrderService orderService;
    private final DistributionService distributionService;
    private final PoolService poolService;

    public OrderDistributionController(OrderService orderService, DistributionService distributionService,
                                        PoolService poolService) {
        this.orderService = orderService;
        this.distributionService = distributionService;
        this.poolService = poolService;
    }

    @PostMapping("/{poolId}/order")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse recordOrder(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID poolId,
                                      @RequestBody(required = false) RecordOrderRequest request) {
        return orderService.recordOrder(callerUserId, poolId, request);
    }

    @GetMapping("/{poolId}/order")
    public OrderResponse getOrder(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID poolId) {
        return orderService.getOrder(callerUserId, poolId);
    }

    @PostMapping("/{poolId}/distribution/generate")
    public DistributionSummaryResponse generateDistribution(@AuthenticationPrincipal UUID callerUserId,
                                                              @PathVariable UUID poolId,
                                                              @Valid @RequestBody GenerateDistributionRequest request) {
        return distributionService.generateDistribution(callerUserId, poolId, request);
    }

    @GetMapping("/{poolId}/distribution")
    public DistributionSummaryResponse getDistribution(@AuthenticationPrincipal UUID callerUserId,
                                                         @PathVariable UUID poolId) {
        return distributionService.getDistribution(callerUserId, poolId);
    }

    @GetMapping("/{poolId}/distribution/mine")
    public List<DistributionItemResponse> getMyDistribution(@AuthenticationPrincipal UUID callerUserId,
                                                              @PathVariable UUID poolId) {
        return distributionService.getMyDistribution(callerUserId, poolId);
    }

    @PostMapping("/{poolId}/distribution/items/{itemId}/deliver")
    public DistributionItemResponse markDistributionItemDelivered(@AuthenticationPrincipal UUID callerUserId,
                                                                    @PathVariable UUID poolId,
                                                                    @PathVariable UUID itemId) {
        return distributionService.markDistributionItemDelivered(callerUserId, poolId, itemId);
    }

    @GetMapping("/{poolId}/class-reserve")
    public List<ClassReserveEntryResponse> getClassReserve(@AuthenticationPrincipal UUID callerUserId,
                                                             @PathVariable UUID poolId) {
        return distributionService.getClassReserve(callerUserId, poolId);
    }

    @PostMapping("/{poolId}/complete")
    public PoolDetailResponse completePool(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID poolId) {
        return poolService.complete(callerUserId, poolId);
    }
}
