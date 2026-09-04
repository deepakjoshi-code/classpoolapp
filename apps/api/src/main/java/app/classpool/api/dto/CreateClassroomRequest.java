package app.classpool.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CreateClassroomRequest(
        UUID schoolId,
        String schoolName,
        @NotBlank String schoolYearLabel,
        @NotBlank String grade,
        @NotBlank String teacherLabel,
        String teacherEmail,
        Integer studentCountEstimate
) {
}
