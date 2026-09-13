package dev.sluice.budget;

/**
 * Outcome of the sub-millisecond pre-flight check.
 *
 * @param source       "cache" when Redis answered, "postgres" when it did not
 * @param elapsedNanos how long the check took, for the latency budget in the NFRs
 */
public record PreflightDecision(boolean allowed, String source, long elapsedNanos) {

    public double elapsedMillis() {
        return elapsedNanos / 1_000_000.0;
    }
}
