package app.classpool.api.dto;

import java.util.List;

public record InventorySummaryResponse(
        int studentsWithInventorySubmitted,
        int totalJoinedStudents,
        List<InventoryRequirementTotal> perRequirement
) {
}
