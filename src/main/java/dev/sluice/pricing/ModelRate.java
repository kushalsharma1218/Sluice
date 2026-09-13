package dev.sluice.pricing;

import java.time.Instant;

/** Rates are per million tokens, in micros. */
public record ModelRate(
        String model,
        long inputPerMTokMicros,
        long outputPerMTokMicros,
        long cacheWritePerMTokMicros,
        long cacheReadPerMTokMicros,
        String currency,
        Instant effectiveFrom) {
}
