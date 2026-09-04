package app.classpool.api.dto;

import java.util.UUID;

public record MembershipResponse(
        UUID id,
        UUID classroomId,
        String role,
        UUID studentId,
        String studentFirstName,
        boolean lateJoin,
        ClassroomResponse classroom
) {
}
