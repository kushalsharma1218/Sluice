package dev.sluice.pricing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure arithmetic, no database. The rounding rule is the whole point. */
class CostCalculatorTest {

    private static final ModelRate OPUS = new ModelRate(
            "claude-opus-5", 5_000_000L, 25_000_000L, 6_250_000L, 500_000L,
            "USD", Instant.EPOCH);

    private final CostCalculator calculator = new CostCalculator(new StubRates(OPUS));

    @Test
    @DisplayName("input and output are priced at their own rates")
    void basicCost() {
        // 1000 input @ $5/MTok = 5000 micros; 500 output @ $25/MTok = 12500 micros.
        assertThat(calculator.costMicros(OPUS, TokenUsage.of(1000, 500))).isEqualTo(17_500L);
    }

    @Test
    @DisplayName("cache reads and writes are priced separately from fresh input")
    void cacheAwareCost() {
        var usage = new TokenUsage(1000, 500, 2000, 8000);
        // 5000 + 12500 + (2000 * 6.25) + (8000 * 0.5)
        assertThat(calculator.costMicros(OPUS, usage)).isEqualTo(17_500L + 12_500L + 4_000L);
    }

    @Test
    @DisplayName("a single token costs a fraction of a cent and is not rounded to zero")
    void subCentPrecisionSurvives() {
        // One output token at $25/MTok is $0.000025 -- 25 micros. In cents this
        // would round to zero, which is why the ledger is denominated in micros.
        assertThat(calculator.costMicros(OPUS, TokenUsage.of(0, 1))).isEqualTo(25L);
        assertThat(calculator.costMicros(OPUS, TokenUsage.of(1, 0))).isEqualTo(5L);
    }

    @Test
    @DisplayName("rounding is half-up and deterministic")
    void roundingIsHalfUp() {
        ModelRate odd = new ModelRate("odd", 3L, 0L, 0L, 0L, "USD", Instant.EPOCH);
        // 500_000 tokens * 3 = 1_500_000; / 1_000_000 = 1.5 -> 2
        assertThat(calculator.costMicros(odd, TokenUsage.of(500_000, 0))).isEqualTo(2L);
        // 499_999 * 3 = 1_499_997 -> 1.499997 -> 1
        assertThat(calculator.costMicros(odd, TokenUsage.of(499_999, 0))).isEqualTo(1L);
    }

    @Test
    @DisplayName("the pre-flight estimate assumes a full max_tokens of output")
    void estimateIsWorstCase() {
        long estimate = calculator.estimateMicros("claude-opus-5", 1000, 4096, Instant.now());
        long actualIfShort = calculator.costMicros(OPUS, TokenUsage.of(1000, 10));

        assertThat(estimate).isEqualTo(5_000L + 4096L * 25L);
        assertThat(estimate)
                .as("the reservation is deliberately larger than a typical settlement")
                .isGreaterThan(actualIfShort);
    }

    @Test
    @DisplayName("a model with no rate on file is an error, not a free call")
    void unknownModelIsRejected() {
        assertThatThrownBy(() -> calculator.rateFor("claude-imaginary", Instant.now()))
                .isInstanceOf(UnknownModelException.class)
                .hasMessageContaining("claude-imaginary");
    }

    /** Minimal stand-in so the arithmetic can be tested without a database. */
    private static final class StubRates extends ModelRateRepository {
        private final ModelRate rate;

        StubRates(ModelRate rate) {
            super(null);
            this.rate = rate;
        }

        @Override
        public Optional<ModelRate> rateAt(String model, Instant at) {
            return rate.model().equals(model) ? Optional.of(rate) : Optional.empty();
        }
    }
}
