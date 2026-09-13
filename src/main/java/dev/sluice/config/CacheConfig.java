package dev.sluice.config;

import dev.sluice.budget.BalanceCache;
import dev.sluice.budget.InMemoryBalanceCache;
import dev.sluice.budget.RedisBalanceCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public BalanceCache balanceCache(SluiceProperties properties,
                                     ObjectProvider<StringRedisTemplate> redis) {
        StringRedisTemplate template = properties.cache().enabled() ? redis.getIfAvailable() : null;
        if (template == null) {
            log.info("balance cache: in-memory (single node). Set sluice.cache.enabled=true "
                    + "with a reachable Redis for the shared fast path.");
            return new InMemoryBalanceCache();
        }
        log.info("balance cache: redis, ttl {}", properties.cache().ttl());
        return new RedisBalanceCache(template, properties.cache().ttl());
    }
}
