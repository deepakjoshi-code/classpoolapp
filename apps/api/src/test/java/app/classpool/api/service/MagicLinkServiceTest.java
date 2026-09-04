package app.classpool.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MagicLinkServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private MagicLinkService magicLinkService;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        magicLinkService = new MagicLinkService(redis, 15);
    }

    @Test
    void issue_storesTheEmailUnderARandomTokenKey_withA15MinuteTtl() {
        String token = magicLinkService.issue("Parent@Example.com");

        assertThat(token).isNotBlank();
        verify(valueOps).set(eq("magic-link:" + token), eq("parent@example.com"), eq(java.time.Duration.ofMinutes(15)));
    }

    @Test
    void consume_deletesOnRead_soASecondConsumeOfTheSameTokenFindsNothing() {
        when(valueOps.getAndDelete("magic-link:tok-1")).thenReturn("user@example.com").thenReturn(null);

        Optional<String> firstRead = magicLinkService.consume("tok-1");
        Optional<String> secondRead = magicLinkService.consume("tok-1");

        assertThat(firstRead).contains("user@example.com");
        assertThat(secondRead).isEmpty();
        verify(valueOps, times(2)).getAndDelete("magic-link:tok-1");
    }

    @Test
    void consume_returnsEmpty_forABlankToken() {
        assertThat(magicLinkService.consume(null)).isEmpty();
        assertThat(magicLinkService.consume("")).isEmpty();
        verifyNoInteractions(valueOps);
    }
}
