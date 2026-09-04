package app.classpool.api.dto;

import java.util.List;
import java.util.UUID;

public record CurrentUserResponse(
        UUID id,
        String email,
        String displayName,
        UUID householdId,
        List<MembershipResponse> memberships
) {
}
