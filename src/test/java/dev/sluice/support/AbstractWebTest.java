package dev.sluice.support;

import dev.sluice.ledger.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full application on a real port, talking to a stand-in provider on another real
 * port. One provider instance for the whole run, so the Spring context is shared
 * between the web tests instead of rebuilt per class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractWebTest {

    protected static final FakeAnthropic ANTHROPIC;

    static {
        try {
            ANTHROPIC = new FakeAnthropic();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the stand-in provider", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(ANTHROPIC::close));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
        registry.add("sluice.cache.enabled", () -> "false");
        registry.add("sluice.admin.token", () -> "test-admin-token");
        // Background jobs are driven explicitly by the tests that care about them.
        registry.add("sluice.hold.sweep-interval", () -> "3600s");
        registry.add("sluice.cache.reconcile-interval", () -> "3600s");
        registry.add("sluice.provider.base-url", ANTHROPIC::baseUrl);
        registry.add("sluice.provider.api-key", () -> "sk-ant-provider-key");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected Fixtures fixtures;
    @Autowired
    protected LedgerRepository ledgerRepository;

    protected final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("""
                truncate table posting, hold, usage_record, journal_entry,
                               budget, virtual_key, account
                restart identity cascade
                """);
    }

    protected String base() {
        return "http://127.0.0.1:" + port;
    }

    protected void assertLedgerIsSound() {
        assertThat(ledgerRepository.unbalancedEntryCount()).isZero();
        assertThat(ledgerRepository.globalImbalance()).isZero();
    }
}
