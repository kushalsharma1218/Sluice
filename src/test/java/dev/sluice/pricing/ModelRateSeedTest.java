package dev.sluice.pricing;

import dev.sluice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRateSeedTest extends AbstractIntegrationTest {

    @Autowired
    private ModelRateRepository rates;

    @Test
    @DisplayName("the shipped rate table covers the current models")
    void seededRatesAreUsable() {
        var opus = rates.rateAt("claude-opus-5", Instant.now()).orElseThrow();
        assertThat(opus.inputPerMTokMicros()).isEqualTo(5_000_000L);
        assertThat(opus.outputPerMTokMicros()).isEqualTo(25_000_000L);

        assertThat(rates.currentRates(Instant.now()))
                .extracting(ModelRate::model)
                .contains("claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5", "claude-fable-5-1");
    }

    @Test
    @DisplayName("a rate change applies from its effective date and leaves history intact")
    void ratesAreVersionedInTime() {
        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);
        rates.upsert(new ModelRate("claude-opus-5", 6_000_000L, 30_000_000L,
                7_500_000L, 600_000L, "USD", tomorrow));

        assertThat(rates.rateAt("claude-opus-5", Instant.now()).orElseThrow().inputPerMTokMicros())
                .as("today's calls keep today's price")
                .isEqualTo(5_000_000L);
        assertThat(rates.rateAt("claude-opus-5", tomorrow.plusSeconds(60))
                .orElseThrow().inputPerMTokMicros())
                .isEqualTo(6_000_000L);
    }
}
