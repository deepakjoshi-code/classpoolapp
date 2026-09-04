package app.classpool.api.config;

import app.classpool.api.security.SessionAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * sessionCookie auth only (see apps/api/README.md "Session mechanism"). CSRF is disabled: this is
 * a same-origin JSON API with no cookie-authenticated HTML form submissions, and the session
 * cookie is SameSite=Lax, which already blocks the cross-site POST case CSRF protection exists
 * for. Session management is STATELESS from Spring Security's point of view — our own
 * SessionAuthenticationFilter (backed by Redis) is the actual session store, not
 * HttpSession/JSESSIONID.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_GET = {
            "/api/v1/auth/magic-link/verify",
            "/api/v1/auth/google/callback",
            "/api/v1/invites/*",
            "/oauth2/authorization/*"
    };

    private static final String[] PUBLIC_POST = {
            "/api/v1/auth/magic-link/request"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SessionAuthenticationFilter sessionFilter,
                                            ClientRegistrationRepository clientRegistrationRepository,
                                            AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequestRepository)
            throws Exception {
        // Serves GET /oauth2/authorization/google — Spring Security's conventional
        // "start the OAuth2 login" redirect endpoint (redirects the browser to Google's consent
        // screen and stores the generated `state`/PKCE verifier for the callback to check). This
        // is intentionally the *only* piece of Spring Security's built-in oauth2Login() machinery
        // wired in here: the full oauth2Login() DSL would also claim
        // /api/v1/auth/google/callback for its own default handling, which would conflict with
        // AuthController's contract-shaped JSON response for that path (see GoogleOAuthService's
        // Javadoc). The frontend (apps/web) is built against this exact conventional path — see
        // apps/web/README.md's "Known discrepancies" #1.
        OAuth2AuthorizationRequestRedirectFilter authorizationRedirectFilter =
                new OAuth2AuthorizationRequestRedirectFilter(
                        new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository,
                                "/oauth2/authorization"));
        authorizationRedirectFilter.setAuthorizationRequestRepository(authorizationRequestRepository);

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.GET, PUBLIC_GET).permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, PUBLIC_POST).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) ->
                        response.sendError(401, "Not authenticated")))
                .addFilterBefore(authorizationRedirectFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
