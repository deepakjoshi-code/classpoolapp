package app.classpool.api.web;

import app.classpool.api.domain.InviteChannel;
import app.classpool.api.dto.ClassroomCreatedResponse;
import app.classpool.api.dto.ClassroomResponse;
import app.classpool.api.dto.CreateClassroomRequest;
import app.classpool.api.dto.CreateInviteRequest;
import app.classpool.api.dto.InviteResponse;
import app.classpool.api.exception.BadRequestException;
import app.classpool.api.service.ClassroomService;
import app.classpool.api.service.InviteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/classrooms")
public class ClassroomController {

    private final ClassroomService classroomService;
    private final InviteService inviteService;

    public ClassroomController(ClassroomService classroomService, InviteService inviteService) {
        this.classroomService = classroomService;
        this.inviteService = inviteService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClassroomCreatedResponse create(@AuthenticationPrincipal UUID callerUserId,
                                            @Valid @RequestBody CreateClassroomRequest request) {
        return classroomService.create(callerUserId, request);
    }

    @GetMapping("/{classroomId}")
    public ClassroomResponse get(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID classroomId) {
        return classroomService.getForCaller(callerUserId, classroomId);
    }

    @PostMapping("/{classroomId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse createInvite(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID classroomId,
                                        @RequestBody(required = false) CreateInviteRequest request) {
        InviteChannel channel = parseChannel(request == null ? null : request.channel());
        return inviteService.create(callerUserId, classroomId, channel);
    }

    private InviteChannel parseChannel(String raw) {
        if (raw == null || raw.isBlank()) {
            return InviteChannel.LINK;
        }
        try {
            return InviteChannel.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown invite channel: " + raw);
        }
    }
}
