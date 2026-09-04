package app.classpool.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Registers the "google" {@link ClientRegistration} directly (Spring Security's OAuth2 Client
 * SPI — ARCHITECTURE.md §2), rather than through Spring Boot's
 * spring.security.oauth2.client.registration.* property-binding auto-configuration.
 *
 * That auto-configuration validates client-id/client-secret as non-blank at application-startup
 * time and hard-fails the whole context if they're not — which would mean this app cannot start
 * at all in an environment with no live Google app configured (this sandbox, most local dev, CI),
 * even though nothing here requires Google sign-in to be actually usable until someone calls
 * /auth/google/callback. Building the registration manually keeps the same Spring Security types
 * (ClientRegistrationRepository, used as-is by {@link app.classpool.api.service.GoogleOAuthService})
 * while only failing at the moment someone actually attempts a Google sign-in with unconfigured
 * credentials (see GoogleOAuthService's own guard), not at boot.
 */
@Configuration
public class OAuth2Config {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${classpool.oauth2.google.client-id}") String clientId,
            @Value("${classpool.oauth2.google.client-secret}") String clientSecret,
            @Value("${classpool.app-base-url}") String appBaseUrl) {
        ClientRegistration google = ClientRegistration.withRegistrationId("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(appBaseUrl + "/api/v1/auth/google/callback")
                .scope("openid", "email", "profile")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .clientName("Google")
                .build();
        return new InMemoryClientRegistrationRepository(google);
    }

    /**
     * Shared between {@link app.classpool.api.config.SecurityConfig}'s
     * OAuth2AuthorizationRequestRedirectFilter (which writes the pending request + generated
     * `state` here when GET /oauth2/authorization/google redirects the browser to Google) and
     * {@link app.classpool.api.service.GoogleOAuthService} (which reads and removes it on the
     * callback to validate `state`). HttpSession-backed — the one piece of this app that briefly
     * uses a servlet session, scoped to just the OAuth2 handshake itself, separate from
     * ClassPool's own Redis-backed CLASSPOOL_SESSION cookie (see README's "Session mechanism").
     */
    @Bean
    public AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequestRepository() {
        return new HttpSessionOAuth2AuthorizationRequestRepository();
    }
}
