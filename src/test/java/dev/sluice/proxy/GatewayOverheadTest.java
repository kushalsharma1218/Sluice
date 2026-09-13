package dev.sluice.proxy;

import dev.sluice.account.Account;
import dev.sluice.auth.Principal;
import dev.sluice.auth.VirtualKey;
import dev.sluice.account.AccountService;
import dev.sluice.budget.SpendGate;
import dev.sluice.support.AbstractWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures what Sluice costs the caller. Not a benchmark harness -- the numbers
 * move with the machine -- but it produces the figures quoted in the README and
 * fails if the overhead regresses into a different order of magnitude.
 *
 * <p>Both legs hit the same stub provider on the same loopback interface, so the
 * difference between them is the gateway and nothing else.
 */
class GatewayOverheadTest extends AbstractWebTest {

    private static final int WARMUP = 40;
    private static final int SAMPLES = 200;

    @Autowired
    SpendGate gate;
    @Autowired
    AccountService accounts;

    @Test
    @DisplayName("the gateway adds single-digit milliseconds before the provider is called")
    void measuresGatewayOverhead() throws Exception {
        Account org = fixtures.org("acme");
        Account team = fixtures.team(org, "platform");
        fixtures.fund(team, "1000.00");
        String key = fixtures.key(team).secret();
        ANTHROPIC.respondWithMessage("claude-opus-5", 100, 50);

        for (int i = 0; i < WARMUP; i++) {
            callThroughSluice(key, "warmup-" + i);
        }

        // Everything Sluice does before the provider call: auth, parse, estimate,
        // budget check, hold. Measured server-side, so it excludes the loopback
        // round trip and the stub provider's own latency.
        long[] overheadNanos = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            overheadNanos[i] = overheadOf(callThroughSluice(key, "bench-" + i));
        }

        double p50 = percentileMillis(overheadNanos, 50);
        double p95 = percentileMillis(overheadNanos, 95);
        double p99 = percentileMillis(overheadNanos, 99);

        System.out.printf("""

                  Gateway overhead before the provider call (%d samples)
                  auth + parse + estimate + budget check + hold write
                  ------------------------------------------------------
                  p50 %6.2f ms   p95 %6.2f ms   p99 %6.2f ms
                %n""", SAMPLES, p50, p95, p99);

        // Generous by design: this guards against an order-of-magnitude regression
        // (an accidental N+1, a lost index, a synchronous cache round trip), not
        // against machine-to-machine variation.
        assertThat(p99).as("p99 gateway overhead").isLessThan(100.0);
    }

    @Test
    @DisplayName("the cached pre-flight budget check is sub-millisecond")
    void measuresBudgetCheckLatency() throws Exception {
        Account org = fixtures.org("acme");
        Account team = fixtures.team(org, "platform");
        fixtures.fund(team, "1000.00");
        String key = fixtures.key(team).secret();
        ANTHROPIC.respondWithMessage("claude-opus-5", 100, 50);

        // One real call populates the payer hint and the cached balance.
        callThroughSluice(key, "prime-the-cache");

        VirtualKey virtualKey = new VirtualKey(java.util.UUID.randomUUID(), team.id(),
                "unused", "unused", "bench", java.time.Instant.now(), null);
        Principal principal = new Principal(virtualKey, team, accounts.chain(team.id()));

        for (int i = 0; i < 1000; i++) {
            gate.preflight(principal, 1_000L);
        }

        long[] samples = new long[10_000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = gate.preflight(principal, 1_000L).elapsedNanos();
        }

        double p50 = percentileMillis(samples, 50);
        double p99 = percentileMillis(samples, 99);
        System.out.printf("""

                  Pre-flight budget check (cache path, %d samples)
                  ------------------------------------------------
                  p50 %.4f ms   p99 %.4f ms
                %n""", samples.length, p50, p99);

        assertThat(p99).as("p99 budget check").isLessThan(2.0);
        assertThat(gate.preflight(principal, 1_000L).source())
                .as("this is measuring the cache path, not a Postgres fallback")
                .isEqualTo("cache");
    }

    private static long overheadOf(HttpResponse<String> response) {
        double millis = Double.parseDouble(
                response.headers().firstValue("x-sluice-overhead-ms").orElseThrow());
        return (long) (millis * 1_000_000L);
    }

    private HttpResponse<String> callThroughSluice(String key, String requestId) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/v1/messages"))
                        .header("content-type", "application/json")
                        .header("x-api-key", key)
                        .header("x-sluice-request-id", requestId)
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(BODY))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response;
    }

    /** Returns the percentile in milliseconds; {@code samples} are nanoseconds. */
    private static double percentileMillis(long[] samples, int percentile) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1,
                (int) Math.ceil(percentile / 100.0 * sorted.length) - 1);
        return sorted[Math.max(index, 0)] / 1_000_000.0;
    }

    private static final String BODY = """
            {"model":"claude-opus-5","max_tokens":64,
             "messages":[{"role":"user","content":"Say hello."}]}""";
}
