package dev.sluice.usage;

import java.time.Instant;
import java.util.UUID;

public record UsageRecord(UUID id, UUID entryId, UUID accountId, String requestId, String model,
                          long inputTokens, long outputTokens,
                          long cacheCreationInputTokens, long cacheReadInputTokens,
                          long costMicros, String currency,
                          boolean streamed, boolean partial, int latencyMs, Instant createdAt) {
}
