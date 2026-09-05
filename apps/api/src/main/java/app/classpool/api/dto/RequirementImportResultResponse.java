package app.classpool.api.dto;

import java.util.List;

public record RequirementImportResultResponse(
        RequirementSourceResponse source,
        List<RequirementResponse> requirements
) {
}
