package app.classpool.api.web;

import app.classpool.api.dto.CreateRequirementRequest;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.dto.RequirementResponse;
import app.classpool.api.service.PoolService;
import app.classpool.api.service.RequirementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pools")
public class PoolController {

    private final PoolService poolService;
    private final RequirementService requirementService;

    public PoolController(PoolService poolService, RequirementService requirementService) {
        this.poolService = poolService;
        this.requirementService = requirementService;
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
}
