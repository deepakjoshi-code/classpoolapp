package app.classpool.api.web;

import app.classpool.api.dto.ImportRequirementsRequest;
import app.classpool.api.dto.RequirementImportResultResponse;
import app.classpool.api.dto.RequirementSourceResponse;
import app.classpool.api.service.RequirementImportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Phase 11's AI-assisted import surface — split out from {@code PoolController} (already 147
 * lines before these two routes, the same size-driven judgment call every prior phase since
 * Phase 8 has made before adding its own controller).
 */
@RestController
@RequestMapping("/api/v1/pools")
public class RequirementSourceController {

    private final RequirementImportService requirementImportService;

    public RequirementSourceController(RequirementImportService requirementImportService) {
        this.requirementImportService = requirementImportService;
    }

    @PostMapping("/{poolId}/requirement-sources")
    @ResponseStatus(HttpStatus.CREATED)
    public RequirementImportResultResponse importRequirementsFromText(@AuthenticationPrincipal UUID callerUserId,
                                                                        @PathVariable UUID poolId,
                                                                        @Valid @RequestBody ImportRequirementsRequest request) {
        return requirementImportService.importFromText(callerUserId, poolId, request);
    }

    @GetMapping("/{poolId}/requirement-sources")
    public List<RequirementSourceResponse> listRequirementSources(@AuthenticationPrincipal UUID callerUserId,
                                                                     @PathVariable UUID poolId) {
        return requirementImportService.listSources(callerUserId, poolId);
    }
}
