package app.classpool.api;

import app.classpool.api.dto.SessionResponse;
import app.classpool.api.service.MagicLinkService;
import app.classpool.api.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class MagicLinkAuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MagicLinkService magicLinkService;

    @Test
    void magicLinkToken_isSingleUse_secondVerifyAttemptFails() {
        // Issues a token the same way AuthService.requestMagicLink does, without going through
        // the LoggingEmailSender — this is the real Redis-backed token, just captured directly
        // instead of scraping a log line.
        String token = magicLinkService.issue("magiclink-user@example.com");

        ResponseEntity<SessionResponse> first = rest.getForEntity(
                baseUrl() + "/api/v1/auth/magic-link/verify?token=" + token, SessionResponse.class);
        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(first.getBody().userId()).isNotNull();

        ResponseEntity<String> second = rest.getForEntity(
                baseUrl() + "/api/v1/auth/magic-link/verify?token=" + token, String.class);
        assertThat(second.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void anUnknownOrExpiredToken_isRejected() {
        ResponseEntity<String> response = rest.getForEntity(
                baseUrl() + "/api/v1/auth/magic-link/verify?token=not-a-real-token", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void signingInTwice_withTwoFreshTokens_returnsTheSameUnderlyingUser() {
        String tokenA = magicLinkService.issue("repeat-user@example.com");
        ResponseEntity<SessionResponse> first = rest.getForEntity(
                baseUrl() + "/api/v1/auth/magic-link/verify?token=" + tokenA, SessionResponse.class);

        String tokenB = magicLinkService.issue("repeat-user@example.com");
        ResponseEntity<SessionResponse> second = rest.getForEntity(
                baseUrl() + "/api/v1/auth/magic-link/verify?token=" + tokenB, SessionResponse.class);

        assertThat(first.getBody().userId()).isEqualTo(second.getBody().userId());
    }
}
