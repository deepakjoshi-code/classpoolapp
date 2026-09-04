package app.classpool.api.service;

import app.classpool.api.exception.BadRequestException;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Exchanges a Google authorization code for a ClassPool session.
 *
 * Credentials, scopes and endpoint URIs come from Spring Security's OAuth2 Client support
 * (application.yml's spring.security.oauth2.client.registration.google.*, resolved here through
 * the standard {@link ClientRegistrationRepository}) — that is the "configured via Spring
 * Security conventions" half of ARCHITECTURE.md §2's auth decision.
 *
 * The actual code->token and token->userinfo calls are made directly (RestClient) rather than via
 * Spring Security's built-in oauth2Login() filter chain, because the OpenAPI contract specifies a
 * custom callback shape (GET /auth/google/callback returning a JSON Session body at a path this
 * app owns) rather than Spring Security's default redirect-based
 * /login/oauth2/code/{registrationId} handling.
 *
 * Known simplification (flagged — there is no live Google app configured in this sandbox to test
 * end-to-end against anyway): this does not itself issue/validate the `state` value against a
 * stored authorization request the way Spring Security's default flow does via
 * AuthorizationRequestRepository — the frontend is expected to have generated and verified `state`
 * client-side before hitting this callback, or a follow-up pass should add a
 * Redis-backed pending-authorization-request store here, mirroring MagicLinkService's pattern.
 */
@Service
public class GoogleOAuthService {

    private static final String REGISTRATION_ID = "google";
    private static final String UNCONFIGURED_CLIENT_ID = "unconfigured-google-client-id";

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final AuthService authService;
    private final RestClient restClient;

    public GoogleOAuthService(ClientRegistrationRepository clientRegistrationRepository, AuthService authService) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.authService = authService;
        this.restClient = RestClient.create();
    }

    public SessionService.Session handleCallback(String code) {
        ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID);
        if (registration == null || UNCONFIGURED_CLIENT_ID.equals(registration.getClientId())) {
            throw new BadRequestException("Google OAuth2 is not configured (GOOGLE_CLIENT_ID/SECRET unset)");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", registration.getClientId());
        form.add("client_secret", registration.getClientSecret());
        form.add("redirect_uri", registration.getRedirectUri());
        form.add("grant_type", "authorization_code");

        Map<String, Object> tokenResponse = restClient.post()
                .uri(registration.getProviderDetails().getTokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);

        if (tokenResponse == null || tokenResponse.get("access_token") == null) {
            throw new BadRequestException("Google token exchange failed");
        }
        String accessToken = tokenResponse.get("access_token").toString();

        Map<String, Object> userInfo = restClient.get()
                .uri(registration.getProviderDetails().getUserInfoEndpoint().getUri())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);

        if (userInfo == null || userInfo.get("sub") == null || userInfo.get("email") == null) {
            throw new BadRequestException("Google userinfo lookup failed");
        }
        String sub = userInfo.get("sub").toString();
        String email = userInfo.get("email").toString();
        String name = userInfo.getOrDefault("name", email).toString();

        return authService.establishGoogleSession(sub, email, name);
    }
}
