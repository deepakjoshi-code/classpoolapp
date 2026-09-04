package app.classpool.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Session mechanism (documented in apps/api/README.md): an opaque, high-entropy, single-use-per-login
 * random token, stored server-side in Redis as {@code session:<token> -> userId} with a TTL, and
 * handed to the browser as the value of an HttpOnly cookie (name matches the OpenAPI
 * `sessionCookie` security scheme: CLASSPOOL_SESSION). Nothing about the user is encoded in the
 * cookie itself, so a stolen/guessed cookie is useless without the matching Redis entry, and
 * revocation (logout) is a single key delete.
 */
@Service
public class SessionService {

    private static final String KEY_PREFIX = "session:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public SessionService(StringRedisTemplate redis, @Value("${classpool.session.ttl-days:30}") long ttlDays) {
        this.redis = redis;
        this.ttl = Duration.ofDays(ttlDays);
    }

    public Session create(UUID userId) {
        String token = randomToken();
        redis.opsForValue().set(KEY_PREFIX + token, userId.toString(), ttl);
        return new Session(token, userId, Instant.now().plus(ttl));
    }

    public Optional<UUID> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String value = redis.opsForValue().get(KEY_PREFIX + token);
        return Optional.ofNullable(value).map(UUID::fromString);
    }

    public void invalidate(String token) {
        if (token != null && !token.isBlank()) {
            redis.delete(KEY_PREFIX + token);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record Session(String token, UUID userId, Instant expiresAt) {
    }
}
