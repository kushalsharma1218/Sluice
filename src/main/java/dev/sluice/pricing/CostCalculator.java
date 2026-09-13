package dev.sluice.pricing;

import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Turns tokens into money. Every multiplication is exact in {@code long} --
 * a million tokens at the priciest rate is ~5e13 micros, far inside the range --
 * and the single division at the end rounds half-up, deterministically.
 */
@Service
public class CostCalculator {

    private static final long TOKENS_PER_MTOK = 1_000_000L;

    private final ModelRateRepository rates;

    public CostCalculator(ModelRateRepository rates) {
        this.rates = rates;
    }

    public ModelRate rateFor(String model, Instant at) {
        return rates.rateAt(model, at).orElseThrow(() -> new UnknownModelException(model));
    }

    public long costMicros(String model, TokenUsage usage, Instant at) {
        return costMicros(rateFor(model, at), usage);
    }

    public long costMicros(ModelRate rate, TokenUsage usage) {
        long weighted = 0;
        weighted = Math.addExact(weighted,
                Math.multiplyExact(usage.inputTokens(), rate.inputPerMTokMicros()));
        weighted = Math.addExact(weighted,
                Math.multiplyExact(usage.outputTokens(), rate.outputPerMTokMicros()));
        weighted = Math.addExact(weighted,
                Math.multiplyExact(usage.cacheCreationInputTokens(), rate.cacheWritePerMTokMicros()));
        weighted = Math.addExact(weighted,
                Math.multiplyExact(usage.cacheReadInputTokens(), rate.cacheReadPerMTokMicros()));
        return roundHalfUp(weighted, TOKENS_PER_MTOK);
    }

    /**
     * Worst case for a call before it runs: every input token billed at the
     * uncached rate, plus {@code max_tokens} of output. Deliberately pessimistic --
     * a hold that is too small under-reserves and lets spend escape the budget.
     */
    public long estimateMicros(String model, long estimatedInputTokens, long maxOutputTokens, Instant at) {
        return costMicros(rateFor(model, at),
                TokenUsage.of(estimatedInputTokens, maxOutputTokens));
    }

    private static long roundHalfUp(long numerator, long denominator) {
        long quotient = numerator / denominator;
        long remainder = numerator % denominator;
        if (remainder * 2 >= denominator) {
            quotient++;
        }
        return quotient;
    }
}
