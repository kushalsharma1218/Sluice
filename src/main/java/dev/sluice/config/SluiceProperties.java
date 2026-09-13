package dev.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "sluice")
public record SluiceProperties(
        @DefaultValue Provider provider,
        @DefaultValue Hold hold,
        @DefaultValue Budget budget,
        @DefaultValue Cache cache,
        @DefaultValue Admin admin) {

    /** Upstream provider. Anthropic only in v1; the shape is provider-agnostic. */
    public record Provider(
            @DefaultValue("https://api.anthropic.com") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("2023-06-01") String anthropicVersion,
            @DefaultValue("10s") Duration connectTimeout,
            @DefaultValue("10m") Duration requestTimeout) {
    }

    public record Hold(
            /* How long a hold survives without a settle before the sweeper releases it. */
            @DefaultValue("15m") Duration ttl,
            @DefaultValue("30s") Duration sweepInterval,
            @DefaultValue("200") int sweepBatchSize,
            /* Assumed max_tokens when a request omits it -- deliberately generous. */
            @DefaultValue("8192") long defaultMaxTokens) {
    }

    public record Budget(
            /* When false, a chain with no credit and no budget is refused outright. */
            @DefaultValue("false") boolean allowUnmetered) {
    }

    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5m") Duration ttl,
            @DefaultValue("60s") Duration reconcileInterval) {
    }

    public record Admin(
            /* Bearer token for /admin/**. Empty disables the admin API entirely. */
            @DefaultValue("") String token) {
    }
}
