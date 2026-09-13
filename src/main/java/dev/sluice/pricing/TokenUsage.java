package dev.sluice.pricing;

/** Mirrors the Anthropic {@code usage} block. */
public record TokenUsage(long inputTokens, long outputTokens,
                         long cacheCreationInputTokens, long cacheReadInputTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);

    public static TokenUsage of(long inputTokens, long outputTokens) {
        return new TokenUsage(inputTokens, outputTokens, 0, 0);
    }

    public TokenUsage withOutputTokens(long tokens) {
        return new TokenUsage(inputTokens, tokens, cacheCreationInputTokens, cacheReadInputTokens);
    }

    public long totalTokens() {
        return inputTokens + outputTokens + cacheCreationInputTokens + cacheReadInputTokens;
    }
}
