package app.classpool.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        sessionService = new SessionService(redis, 30);
    }

    @Test
    void create_storesTheUserIdUnderARandomTokenKey() {
        UUID userId = UUID.randomUUID();

        SessionService.Session session = sessionService.create(userId);

        assertThat(session.token()).isNotBlank();
        assertThat(session.userId()).isEqualTo(userId);
        verify(valueOps).set(eq("session:" + session.token()), eq(userId.toString()),
                eq(java.time.Duration.ofDays(30)));
    }

    @Test
    void resolve_returnsTheUserId_forAValidToken() {
        UUID userId = UUID.randomUUID();
        when(valueOps.get("session:tok")).thenReturn(userId.toString());

        assertThat(sessionService.resolve("tok")).contains(userId);
    }

    @Test
    void resolve_returnsEmpty_forAnUnknownToken() {
        when(valueOps.get("session:missing")).thenReturn(null);

        assertThat(sessionService.resolve("missing")).isEmpty();
    }

    @Test
    void resolve_returnsEmpty_forABlankToken_withoutTouchingRedis() {
        assertThat(sessionService.resolve(null)).isEmpty();
        assertThat(sessionService.resolve("")).isEmpty();
        verifyNoInteractions(valueOps);
    }

    @Test
    void invalidate_deletesTheSessionKey() {
        sessionService.invalidate("tok");

        verify(redis).delete("session:tok");
    }
}
