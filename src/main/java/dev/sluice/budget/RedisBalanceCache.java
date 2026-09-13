package dev.sluice.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed balance cache.
 *
 * <p>Every operation swallows connection failures and reports a miss. A miss
 * falls through to Postgres, which is slower and correct -- exactly the intended
 * degradation. Nothing here can cause an incorrect charge.
 */
public class RedisBalanceCache implements BalanceCache {

    private static final Logger log = LoggerFactory.getLogger(RedisBalanceCache.class);
    private static final String PREFIX = "sluice:avail:";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final AtomicBoolean healthy = new AtomicBoolean(true);

    public RedisBalanceCache(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public OptionalLong availableMicros(UUID accountId) {
        try {
            String raw = redis.opsForValue().get(key(accountId));
            markHealthy();
            return raw == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            // A corrupt value is worse than no value.
            invalidate(accountId);
            return OptionalLong.empty();
        } catch (RuntimeException e) {
            markUnhealthy(e);
            return OptionalLong.empty();
        }
    }

    @Override
    public void put(UUID accountId, long micros) {
        try {
            redis.opsForValue().set(key(accountId), Long.toString(micros), ttl);
            markHealthy();
        } catch (RuntimeException e) {
            markUnhealthy(e);
        }
    }

    @Override
    public void applyDelta(UUID accountId, long deltaMicros) {
        if (deltaMicros == 0) {
            return;
        }
        try {
            // Only adjust a key that already exists: INCRBY on a missing key would
            // invent a balance of `delta` out of nothing.
            String key = key(accountId);
            if (Boolean.TRUE.equals(redis.hasKey(key))) {
                redis.opsForValue().increment(key, deltaMicros);
                redis.expire(key, ttl);
            }
            markHealthy();
        } catch (RuntimeException e) {
            markUnhealthy(e);
        }
    }

    @Override
    public void invalidate(UUID accountId) {
        try {
            redis.delete(key(accountId));
            markHealthy();
        } catch (RuntimeException e) {
            markUnhealthy(e);
        }
    }

    @Override
    public boolean healthy() {
        return healthy.get();
    }

    @Override
    public String describe() {
        return "redis";
    }

    private void markHealthy() {
        if (healthy.compareAndSet(false, true)) {
            log.info("balance cache recovered; fast path re-enabled");
        }
    }

    private void markUnhealthy(RuntimeException e) {
        if (healthy.compareAndSet(true, false)) {
            log.warn("balance cache unavailable, degrading to Postgres reads: {}", e.toString());
        }
    }

    private static String key(UUID accountId) {
        return PREFIX + accountId;
    }
}
