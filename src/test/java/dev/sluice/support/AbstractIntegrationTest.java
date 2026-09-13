package dev.sluice.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@IntegrationTest
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        // Redis is not part of the test environment; the in-memory cache stands in
        // and Redis auto-configuration is switched off entirely.
        registry.add("sluice.cache.enabled", () -> "false");
        registry.add("spring.autoconfigure.exclude", () -> String.join(",",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"));
        registry.add("sluice.admin.token", () -> "test-admin-token");
        // The sweeper and reconciler are driven explicitly by the tests that need them.
        registry.add("sluice.hold.sweep-interval", () -> "3600s");
        registry.add("sluice.cache.reconcile-interval", () -> "3600s");
    }

    /**
     * Truncates every table Flyway created, leaving the seeded rates in place.
     * Cheaper and more honest than rebuilding the schema between tests: the
     * constraints under test stay exactly as they ship.
     */
    @BeforeEach
    void resetDatabase() {
        jdbc.execute("""
                truncate table posting, hold, usage_record, journal_entry,
                               budget, virtual_key, account
                restart identity cascade
                """);
    }
}
