package app.classpool.api.web;

import app.classpool.api.dto.MagicLinkRequest;
import app.classpool.api.dto.SessionResponse;
import app.classpool.api.security.SessionCookieHelper;
import app.classpool.api.service.AuthService;
import app.classpool.api.service.GoogleOAuthService;
import app.classpool.api.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final GoogleOAuthService googleOAuthService;
    private final SessionCookieHelper cookieHelper;

    public AuthController(AuthService authService, GoogleOAuthService googleOAuthService,
                           SessionCookieHelper cookieHelper) {
        this.authService = authService;
        this.googleOAuthService = googleOAuthService;
        this.cookieHelper = cookieHelper;
    }

    @PostMapping("/magic-link/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestMagicLink(@Valid @RequestBody MagicLinkRequest request) {
        authService.requestMagicLink(request.email());
    }

    @GetMapping("/magic-link/verify")
    public ResponseEntity<SessionResponse> verifyMagicLink(@RequestParam String token, HttpServletResponse response) {
        SessionService.Session session = authService.verifyMagicLink(token);
        cookieHelper.set(response, session.token());
        return ResponseEntity.ok(new SessionResponse(session.userId(), session.expiresAt()));
    }

    @GetMapping("/google/callback")
    public ResponseEntity<SessionResponse> googleCallback(@RequestParam String code,
                                                            @RequestParam String state,
                                                            HttpServletRequest request,
                                                            HttpServletResponse response) {
        SessionService.Session session = googleOAuthService.handleCallback(code, state, request, response);
        cookieHelper.set(response, session.token());
        return ResponseEntity.ok(new SessionResponse(session.userId(), session.expiresAt()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String token = readCookie(request, cookieHelper.cookieName());
        authService.logout(token);
        cookieHelper.clear(response);
    }

    private static String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
