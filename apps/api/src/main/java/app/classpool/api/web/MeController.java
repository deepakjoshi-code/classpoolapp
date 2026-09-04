package app.classpool.api.web;

import app.classpool.api.dto.CurrentUserResponse;
import app.classpool.api.service.AuthService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class MeController {

    private final AuthService authService;

    public MeController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/api/v1/me")
    public CurrentUserResponse getCurrentUser(@AuthenticationPrincipal UUID userId) {
        return authService.getCurrentUser(userId);
    }
}
