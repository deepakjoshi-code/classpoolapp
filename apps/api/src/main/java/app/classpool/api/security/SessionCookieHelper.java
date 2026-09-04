package app.classpool.api.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class SessionCookieHelper {

    private final String cookieName;
    private final boolean secure;
    private final long ttlDays;

    public SessionCookieHelper(@Value("${classpool.session.cookie-name:CLASSPOOL_SESSION}") String cookieName,
                                @Value("${classpool.session.cookie-secure:true}") boolean secure,
                                @Value("${classpool.session.ttl-days:30}") long ttlDays) {
        this.cookieName = cookieName;
        this.secure = secure;
        this.ttlDays = ttlDays;
    }

    public void set(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofDays(ttlDays))
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public String cookieName() {
        return cookieName;
    }
}
