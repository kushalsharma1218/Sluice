package dev.sluice.budget;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * A cache of available balance for the sub-millisecond pre-flight check.
 *
 * <p>This is a cache and nothing more. It may be stale, it may be empty, it may
 * be entirely down -- every one of those degrades latency, never correctness,
 * because the authoritative check happens again inside the hold transaction.
 */
public interface BalanceCache {

    OptionalLong availableMicros(UUID accountId);

    void put(UUID accountId, long micros);

    /** Adjust in place after a posting, so the cache tracks without a re-read. */
    void applyDelta(UUID accountId, long deltaMicros);

    void invalidate(UUID accountId);

    boolean healthy();

    String describe();
}
