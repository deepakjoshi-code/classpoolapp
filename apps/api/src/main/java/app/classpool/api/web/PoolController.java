package app.classpool.api.web;

import app.classpool.api.dto.AllocationLineResponse;
import app.classpool.api.dto.AllocationSummaryResponse;
import app.classpool.api.dto.ContributionResponse;
import app.classpool.api.dto.CreateRequirementRequest;
import app.classpool.api.dto.InventoryLineResponse;
import app.classpool.api.dto.InventorySummaryResponse;
import app.classpool.api.dto.OfferContributionRequest;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.dto.RequirementResponse;
import app.classpool.api.dto.SetInventoryRequest;
import app.classpool.api.service.AllocationService;
import app.classpool.api.service.ContributionService;
import app.classpool.api.service.InventoryService;
import app.classpool.api.service.PoolService;
import app.classpool.api.service.RequirementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pools")
public class PoolController {

    private final PoolService poolService;
    private final RequirementService requirementService;
    private final InventoryService inventoryService;
    private final ContributionService contributionService;
    private final AllocationService allocationService;

    public PoolController(PoolService poolService, RequirementService requirementService,
                           InventoryService inventoryService, ContributionService contributionService,
                           AllocationService allocationService) {
        this.poolService = poolService;
        this.requirementService = requirementService;
        this.inventoryService = inventoryService;
        this.contributionService = contributionService;
        this.allocationService = allocationService;
    }

    @GetMapping("/{poolId}")
    public PoolDetailResponse get(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID poolId) {
        return poolService.getForCaller(callerUserId, poolId);
    }

    @PostMapping("/{poolId}/requirements")
    @ResponseStatus(HttpStatus.CREATED)
    public RequirementResponse addRequirement(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID poolId,
                                               @Valid @RequestBody CreateRequirementRequest request) {
        return requirementService.add(callerUserId, poolId, request);
    }

    @PatchMapping("/{poolId}/requirements/{requirementId}")
    public RequirementResponse updateRequirement(@AuthenticationPrincipal UUID callerUserId,
                                                  @PathVariable UUID poolId, @PathVariable UUID requirementId,
                                                  @Valid @RequestBody CreateRequirementRequest request) {
        return requirementService.update(callerUserId, poolId, requirementId, request);
    }

    @DeleteMapping("/{poolId}/requirements/{requirementId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRequirement(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID poolId,
                                   @PathVariable UUID requirementId) {
        requirementService.remove(callerUserId, poolId, requirementId);
    }

    @PostMapping("/{poolId}/confirm")
    public PoolDetailResponse confirm(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID poolId) {
        return poolService.confirm(callerUserId, poolId);
    }

    @GetMapping("/{poolId}/inventory")
    public List<InventoryLineResponse> getMyInventory(@AuthenticationPrincipal UUID callerUserId,
                                                        @PathVariable UUID poolId) {
        return inventoryService.getMyInventory(callerUserId, poolId);
    }

    @PutMapping("/{poolId}/requirements/{requirementId}/inventory")
    public InventoryLineResponse setInventory(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID poolId,
                                               @PathVariable UUID requirementId,
                                               @Valid @RequestBody SetInventoryRequest request) {
        return inventoryService.setInventory(callerUserId, poolId, requirementId, request);
    }

    @GetMapping("/{poolId}/inventory/summary")
    public InventorySummaryResponse getInventorySummary(@AuthenticationPrincipal UUID callerUserId,
                                                          @PathVariable UUID poolId) {
        return inventoryService.getSummary(callerUserId, poolId);
    }

    @PostMapping("/{poolId}/requirements/{requirementId}/contributions")
    @ResponseStatus(HttpStatus.CREATED)
    public ContributionResponse offerContribution(@AuthenticationPrincipal UUID callerUserId,
                                                    @PathVariable UUID poolId, @PathVariable UUID requirementId,
                                                    @Valid @RequestBody OfferContributionRequest request) {
        return contributionService.offer(callerUserId, poolId, requirementId, request);
    }

    @GetMapping("/{poolId}/contributions/mine")
    public List<ContributionResponse> getMyContributions(@AuthenticationPrincipal UUID callerUserId,
                                                            @PathVariable UUID poolId) {
        return contributionService.getMine(callerUserId, poolId);
    }

    @DeleteMapping("/{poolId}/contributions/{contributionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdrawContribution(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID poolId,
                                      @PathVariable UUID contributionId) {
        contributionService.withdraw(callerUserId, poolId, contributionId);
    }

    @GetMapping("/{poolId}/contributions")
    public List<ContributionResponse> listContributionsForOrganizer(@AuthenticationPrincipal UUID callerUserId,
                                                                       @PathVariable UUID poolId) {
        return contributionService.listForOrganizer(callerUserId, poolId);
    }

    @PostMapping("/{poolId}/contributions/{contributionId}/receive")
    public ContributionResponse markContributionReceived(@AuthenticationPrincipal UUID callerUserId,
                                                            @PathVariable UUID poolId,
                                                            @PathVariable UUID contributionId) {
        return contributionService.markReceived(callerUserId, poolId, contributionId);
    }

    @PostMapping("/{poolId}/reconcile")
    public AllocationSummaryResponse reconcile(@AuthenticationPrincipal UUID callerUserId,
                                                @PathVariable UUID poolId) {
        return allocationService.reconcile(callerUserId, poolId);
    }

    @GetMapping("/{poolId}/allocation")
    public AllocationSummaryResponse getAllocation(@AuthenticationPrincipal UUID callerUserId,
                                                    @PathVariable UUID poolId) {
        return allocationService.getAllocationForOrganizer(callerUserId, poolId);
    }

    @GetMapping("/{poolId}/allocation/mine")
    public List<AllocationLineResponse> getMyAllocation(@AuthenticationPrincipal UUID callerUserId,
                                                          @PathVariable UUID poolId) {
        return allocationService.getMyAllocation(callerUserId, poolId);
    }
}
