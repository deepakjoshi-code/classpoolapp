package app.classpool.api.security;

import app.classpool.api.service.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the CLASSPOOL_SESSION cookie, resolves it against Redis via {@link SessionService}, and
 * — when valid — sets an Authentication whose principal is the caller's {@link UUID} user id.
 * Leaves the SecurityContext empty for a missing/invalid/expired cookie; Spring Security's
 * authorizeHttpRequests rules (see SecurityConfig) turn that into a 401 for protected routes.
 */
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private final SessionService sessionService;
    private final String cookieName;

    public SessionAuthenticationFilter(SessionService sessionService,
                                        @Value("${classpool.session.cookie-name:CLASSPOOL_SESSION}") String cookieName) {
        this.sessionService = sessionService;
        this.cookieName = cookieName;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        readCookie(request).flatMap(sessionService::resolve).ifPresent(userId -> {
            var auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);
        });
        filterChain.doFilter(request, response);
    }

    private Optional<String> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return Optional.ofNullable(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
