package app.classpool.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ClassroomResponse(
        UUID id,
        UUID schoolId,
        String schoolName,
        String schoolYearLabel,
        String grade,
        String teacherLabel,
        Integer studentCountEstimate,
        Instant createdAt
) {
}
