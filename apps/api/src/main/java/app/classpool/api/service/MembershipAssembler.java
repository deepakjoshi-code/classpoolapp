package app.classpool.api.service;

import app.classpool.api.domain.Classroom;
import app.classpool.api.domain.Membership;
import app.classpool.api.dto.ClassroomResponse;
import app.classpool.api.dto.MembershipResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MembershipAssembler {

    private final ClassroomAssembler classroomAssembler;

    public MembershipAssembler(ClassroomAssembler classroomAssembler) {
        this.classroomAssembler = classroomAssembler;
    }

    public MembershipResponse toResponse(Membership membership) {
        ClassroomResponse classroomResponse = classroomAssembler.toResponse(membership.getClassroom());
        return build(membership, classroomResponse);
    }

    public List<MembershipResponse> toResponses(List<Membership> memberships) {
        List<Classroom> classrooms = memberships.stream().map(Membership::getClassroom).distinct().toList();
        Map<UUID, ClassroomResponse> classroomResponses = classroomAssembler.toResponses(classrooms);
        return memberships.stream()
                .map(m -> build(m, classroomResponses.get(m.getClassroom().getId())))
                .toList();
    }

    private MembershipResponse build(Membership membership, ClassroomResponse classroomResponse) {
        return new MembershipResponse(
                membership.getId(),
                membership.getClassroom().getId(),
                membership.getRole().name(),
                membership.getStudent() == null ? null : membership.getStudent().getId(),
                membership.getStudent() == null ? null : membership.getStudent().getFirstName(),
                membership.isLateJoin(),
                classroomResponse
        );
    }
}
