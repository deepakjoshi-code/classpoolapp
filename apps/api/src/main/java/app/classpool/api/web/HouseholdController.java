package app.classpool.api.web;

import app.classpool.api.dto.HouseholdDashboardResponse;
import app.classpool.api.service.HouseholdService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/household")
public class HouseholdController {

    private final HouseholdService householdService;

    public HouseholdController(HouseholdService householdService) {
        this.householdService = householdService;
    }

    @GetMapping("/dashboard")
    public HouseholdDashboardResponse getDashboard(@AuthenticationPrincipal UUID callerUserId) {
        return householdService.getDashboard(callerUserId);
    }
}
