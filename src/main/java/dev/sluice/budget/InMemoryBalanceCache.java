package dev.sluice.budget;

import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default cache when Redis is not configured. Correct, but process-local: with
 * more than one gateway instance each keeps its own view, so entries drift until
 * the reconciler refreshes them. Fine for a single node and for tests.
 */
public class InMemoryBalanceCache implements BalanceCache {

    private final ConcurrentHashMap<UUID, AtomicLong> values = new ConcurrentHashMap<>();

    @Override
    public OptionalLong availableMicros(UUID accountId) {
        AtomicLong value = values.get(accountId);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value.get());
    }

    @Override
    public void put(UUID accountId, long micros) {
        values.computeIfAbsent(accountId, k -> new AtomicLong()).set(micros);
    }

    @Override
    public void applyDelta(UUID accountId, long deltaMicros) {
        AtomicLong value = values.get(accountId);
        if (value != null) {
            value.addAndGet(deltaMicros);
        }
    }

    @Override
    public void invalidate(UUID accountId) {
        values.remove(accountId);
    }

    @Override
    public boolean healthy() {
        return true;
    }

    @Override
    public String describe() {
        return "in-memory";
    }
}
