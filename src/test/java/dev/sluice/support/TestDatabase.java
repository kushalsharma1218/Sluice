package dev.sluice.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * One real Postgres for the whole test run.
 *
 * <p>The ledger's central guarantees are enforced by Postgres itself -- a deferred
 * constraint trigger, a unique index, {@code SELECT ... FOR UPDATE}. Testing them
 * against an in-memory database in "PostgreSQL compatibility mode" would test the
 * emulation, not the thing that ships. This runs the real server, no Docker
 * required, so the suite works anywhere with a JVM.
 */
public final class TestDatabase {

    private static volatile EmbeddedPostgres instance;

    private TestDatabase() {
    }

    public static synchronized EmbeddedPostgres get() {
        if (instance == null) {
            try {
                instance = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException("could not start embedded Postgres", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    instance.close();
                } catch (IOException ignored) {
                    // Nothing useful to do while the JVM is exiting.
                }
            }));
        }
        return instance;
    }

    public static String jdbcUrl() {
        return get().getJdbcUrl("postgres", "postgres");
    }
}
