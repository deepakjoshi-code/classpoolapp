package app.classpool.api.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real ephemeral Postgres via Testcontainers (ARCHITECTURE.md §2: no H2/in-memory substitute —
 * the whole point of the PRD §14 tenant-isolation test is catching real SQL/constraint behavior).
 * Redis is also a real container: session/magic-link tokens are Redis-backed (see
 * apps/api/README.md), so an integration test needs the real thing, not a mock, to exercise the
 * full auth flow end to end.
 *
 * Containers are static (one pair for the whole test JVM) so every @SpringBootTest subclass
 * shares them instead of paying container-startup cost per class.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("classpool_test")
            .withUsername("classpool_test")
            .withPassword("classpool_test");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    protected int port;

    protected TestRestTemplate rest = new TestRestTemplate();

    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}
