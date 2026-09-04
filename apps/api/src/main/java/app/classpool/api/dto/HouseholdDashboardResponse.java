package app.classpool.api.dto;

import java.util.List;
import java.util.UUID;

public record HouseholdDashboardResponse(UUID householdId, List<MembershipResponse> memberships) {
}
