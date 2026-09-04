package app.classpool.api.support;

import app.classpool.api.domain.AppUser;
import app.classpool.api.domain.AuthProvider;
import app.classpool.api.repository.AppUserRepository;
import app.classpool.api.service.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Test helper: creates a real AppUser + a real Redis-backed session, so integration tests can
 * authenticate as a given user by attaching the returned cookie value — without going through the
 * magic-link/email round trip for every test that merely needs "a logged-in user".
 */
@Component
public class TestUsers {

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private SessionService sessionService;

    public AuthedUser create(String email, String displayName) {
        AppUser user = appUserRepository.save(new AppUser(email, displayName, AuthProvider.MAGIC_LINK, null));
        SessionService.Session session = sessionService.create(user.getId());
        return new AuthedUser(user.getId(), session.token());
    }

    public record AuthedUser(java.util.UUID userId, String sessionToken) {
    }
}
