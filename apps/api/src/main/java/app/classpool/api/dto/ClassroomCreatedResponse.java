package app.classpool.api.dto;

import java.util.List;

public record ClassroomCreatedResponse(ClassroomResponse classroom, List<ClassroomResponse> dedupWarning) {
}
