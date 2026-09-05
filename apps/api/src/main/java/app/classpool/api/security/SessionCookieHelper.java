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
    private final String sameSite;
    private final long ttlDays;

    /**
     * {@code sameSite} defaults to {@code Lax}, which is fine as long as the frontend and this API
     * share a registrable domain (any two localhost ports, e.g.) — but a cross-origin deployment
     * (frontend on Vercel, this API on Render, different domains entirely) needs {@code None}
     * instead, since {@code Lax} is silently dropped on the frontend's cross-site fetch/XHR calls
     * (it's only sent on top-level navigation), which would 401 every authenticated request right
     * after a seemingly-successful sign-in. {@code None} requires {@code Secure}, which production
     * already sets via {@code classpool.session.cookie-secure}.
     */
    public SessionCookieHelper(@Value("${classpool.session.cookie-name:CLASSPOOL_SESSION}") String cookieName,
                                @Value("${classpool.session.cookie-secure:true}") boolean secure,
                                @Value("${classpool.session.cookie-same-site:Lax}") String sameSite,
                                @Value("${classpool.session.ttl-days:30}") long ttlDays) {
        this.cookieName = cookieName;
        this.secure = secure;
        this.sameSite = sameSite;
        this.ttlDays = ttlDays;
    }

    public void set(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ofDays(ttlDays))
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public String cookieName() {
        return cookieName;
    }
}
