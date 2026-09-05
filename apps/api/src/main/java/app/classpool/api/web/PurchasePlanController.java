package app.classpool.api.web;

import app.classpool.api.dto.AddProductOfferRequest;
import app.classpool.api.dto.ProductOfferResponse;
import app.classpool.api.dto.PurchasePlanResponse;
import app.classpool.api.service.PurchasePlanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Phase 8's bulk-pack optimizer surface (PRD §7.1/§9.4) — split out from {@link PoolController}
 * rather than added to it, since that file was already at 148 lines before these six routes (the
 * same size-driven judgment call the Phase 6/7 agent flagged as theirs to make before adding
 * three more routes there).
 */
@RestController
@RequestMapping("/api/v1/pools")
public class PurchasePlanController {

    private final PurchasePlanService purchasePlanService;

    public PurchasePlanController(PurchasePlanService purchasePlanService) {
        this.purchasePlanService = purchasePlanService;
    }

    @PostMapping("/{poolId}/requirements/{requirementId}/product-offers")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductOfferResponse addProductOffer(@AuthenticationPrincipal UUID callerUserId,
                                                 @PathVariable UUID poolId, @PathVariable UUID requirementId,
                                                 @Valid @RequestBody AddProductOfferRequest request) {
        return purchasePlanService.addProductOffer(callerUserId, poolId, requirementId, request);
    }

    @GetMapping("/{poolId}/product-offers")
    public List<ProductOfferResponse> listProductOffers(@AuthenticationPrincipal UUID callerUserId,
                                                          @PathVariable UUID poolId) {
        return purchasePlanService.listProductOffers(callerUserId, poolId);
    }

    @DeleteMapping("/{poolId}/product-offers/{offerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeProductOffer(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID poolId,
                                    @PathVariable UUID offerId) {
        purchasePlanService.removeProductOffer(callerUserId, poolId, offerId);
    }

    @PostMapping("/{poolId}/purchase-plan/generate")
    public PurchasePlanResponse generatePurchasePlan(@AuthenticationPrincipal UUID callerUserId,
                                                       @PathVariable UUID poolId) {
        return purchasePlanService.generate(callerUserId, poolId);
    }

    @GetMapping("/{poolId}/purchase-plan")
    public PurchasePlanResponse getPurchasePlan(@AuthenticationPrincipal UUID callerUserId,
                                                  @PathVariable UUID poolId) {
        return purchasePlanService.getPurchasePlan(callerUserId, poolId);
    }

    @PostMapping("/{poolId}/purchase-plan/approve")
    public PurchasePlanResponse approvePurchasePlan(@AuthenticationPrincipal UUID callerUserId,
                                                      @PathVariable UUID poolId) {
        return purchasePlanService.approve(callerUserId, poolId);
    }
}
