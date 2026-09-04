package app.classpool.api.web;

import app.classpool.api.dto.InvitePreviewResponse;
import app.classpool.api.dto.JoinInviteRequest;
import app.classpool.api.dto.MembershipResponse;
import app.classpool.api.service.InviteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invites")
public class InviteController {

    private final InviteService inviteService;

    public InviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @GetMapping("/{token}")
    public InvitePreviewResponse preview(@PathVariable String token) {
        return inviteService.preview(token);
    }

    @PostMapping("/{token}/join")
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipResponse join(@AuthenticationPrincipal UUID callerUserId, @PathVariable String token,
                                    @Valid @RequestBody JoinInviteRequest request) {
        return inviteService.join(callerUserId, token, request.studentFirstName());
    }
}
