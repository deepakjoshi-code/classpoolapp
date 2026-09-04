package app.classpool.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * Single-use, 15-minute-expiry magic-link tokens, stored in Redis as
 * {@code magic-link:<token> -> email} with a TTL (see apps/api/README.md for why Redis over a
 * DB table: it gives expiry and single-use-by-deletion for free, and matches ARCHITECTURE.md §1's
 * "Redis, used for session/rate-limit state").
 */
@Service
public class MagicLinkService {

    private static final String KEY_PREFIX = "magic-link:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public MagicLinkService(StringRedisTemplate redis,
                             @Value("${classpool.magic-link.ttl-minutes:15}") long ttlMinutes) {
        this.redis = redis;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public String issue(String email) {
        String token = randomToken();
        redis.opsForValue().set(KEY_PREFIX + token, email.toLowerCase(), ttl);
        return token;
    }

    /**
     * Atomically reads and deletes the token (Redis GETDEL) so a second call for the same token
     * always returns empty — this is what makes the token single-use, not just time-limited.
     */
    public Optional<String> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String email = redis.opsForValue().getAndDelete(KEY_PREFIX + token);
        return Optional.ofNullable(email);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
