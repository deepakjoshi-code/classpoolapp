package app.classpool.api.dto;

public record InvitePreviewResponse(ClassroomResponse classroom, long membersJoinedCount,
                                     Integer studentCountEstimate) {
}
