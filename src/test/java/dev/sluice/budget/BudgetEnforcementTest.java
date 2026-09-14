package dev.sluice.budget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sluice.account.Account;
import dev.sluice.ledger.LedgerService;
import dev.sluice.ledger.Micros;
import dev.sluice.support.AbstractWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hard budget enforcement: a team with a $5 budget is refused at $5.01, and the
 * provider call is never made.
 */
class BudgetEnforcementTest extends AbstractWebTest {

    @Autowired
    BudgetService budgets;
    @Autowired
    LedgerService ledger;
    @Autowired
    ObjectMapper mapper;

    @Test
    @DisplayName("a team with a $5 budget is refused at $5.01 and the provider is never called")
    void hardBudgetRefusesTheCallBeforeItLeaves() throws Exception {
        Account org = fixtures.org("acme");
        Account team = fixtures.team(org, "platform");
        fixtures.fund(team, "1000.00");
        budgets.upsert(team.id(), BudgetPeriod.MONTHLY, micros("5.00"), "USD", true);
        String key = fixtures.key(team).secret();

        // Spend the budget down to exactly $5.00 with a direct ledger entry, the
        // same shape the proxy writes.
        spend(team, "req-preload", micros("5.00"));
        assertThat(budgets.committedMicros(team.id(), BudgetPeriod.MONTHLY, Instant.now()))
                .isEqualTo(micros("5.00"));

        ANTHROPIC.respondWithMessage("claude-opus-5", 1000, 500);
        int providerCallsBefore = ANTHROPIC.requests().size();

        HttpResponse<String> response = call(key, "req-over-budget");

        assertThat(response.statusCode())
                .as("budget exhausted is 402 Payment Required")
                .isEqualTo(402);
        assertThat(ANTHROPIC.requests())
                .as("the provider call is never made")
                .hasSize(providerCallsBefore);

        JsonNode error = mapper.readTree(response.body());
        assertThat(error.path("error").path("type").asText()).isEqualTo("payment_required");
        JsonNode detail = error.path("error").path("sluice");
        assertThat(detail.path("reason").asText()).isEqualTo("budget_exceeded");
        assertThat(detail.path("account_name").asText())
                .as("the response names the account that tripped, so it is debuggable client-side")
                .isEqualTo("platform");
        assertThat(detail.path("period").asText()).isEqualTo("MONTHLY");
        assertThat(detail.path("limit").asText()).isEqualTo("5.000000");
        assertThat(detail.path("committed").asText()).isEqualTo("5.000000");
        assertLedgerIsSound();
    }

    @Test
    @DisplayName("a budget on an ancestor constrains every project beneath it")
    void ancestorBudgetsApplyDownTheChain() throws Exception {
        Account org = fixtures.org("acme");
        Account team = fixtures.team(org, "platform");
        Account project = fixtures.project(team, "search");
        fixtures.fund(org, "1000.00");
        budgets.upsert(org.id(), BudgetPeriod.MONTHLY, micros("2.00"), "USD", true);
        String key = fixtures.key(project).secret();

        // Spend against a sibling project; the org-level budget still sees it.
        Account sibling = fixtures.project(team, "ranking");
        spend(sibling, "req-sibling", micros("2.00"));

        ANTHROPIC.respondWithMessage("claude-opus-5", 1000, 500);
        int before = ANTHROPIC.requests().size();

        HttpResponse<String> response = call(key, "req-child");

        assertThat(response.statusCode()).isEqualTo(402);
        assertThat(ANTHROPIC.requests()).hasSize(before);
        JsonNode detail = mapper.readTree(response.body()).path("error").path("sluice");
        assertThat(detail.path("account_name").asText())
                .as("the refusal names the ancestor that tripped, not the key's own account")
                .isEqualTo("acme");
    }

    @Test
    @DisplayName("a soft budget records the breach and lets the call through")
    void softBudgetDoesNotBlock() throws Exception {
        Account org = fixtures.org("acme");
        Account team = fixtures.team(org, "platform");
        fixtures.fund(team, "1000.00");
        budgets.upsert(team.id(), BudgetPeriod.MONTHLY, micros("0.001"), "USD", false);
        String key = fixtures.key(team).secret();

        ANTHROPIC.respondWithMessage("claude-opus-5", 1000, 500);

        assertThat(call(key, "req-soft").statusCode())
                .as("hard_stop=false is the alert without the outage")
                .isEqualTo(200);
        assertLedgerIsSound();
    }

    @Test
    @DisplayName("an account out of prepaid credit is refused with the balance that tripped")
    void insufficientBalanceIsRefused() throws Exception {
        Account org = fixtures.org("acme");
        Account team = fixtures.team(org, "platform");
        fixtures.fund(team, "0.001");
        String key = fixtures.key(team).secret();

        ANTHROPIC.respondWithMessage("claude-opus-5", 1000, 500);
        int before = ANTHROPIC.requests().size();

        HttpResponse<String> response = call(key, "req-broke");

        assertThat(response.statusCode()).isEqualTo(402);
        assertThat(ANTHROPIC.requests()).hasSize(before);
        JsonNode detail = mapper.readTree(response.body()).path("error").path("sluice");
        assertThat(detail.path("reason").asText()).isEqualTo("insufficient_balance");
        assertThat(detail.path("limit").asText()).isEqualTo("0.001000");
    }

    @Test
    @DisplayName("an account with neither credit nor a budget is refused, not silently unmetered")
    void unmeteredAccountIsRefused() throws Exception {
        Account org = fixtures.org("acme");
        Account team = fixtures.team(org, "unfunded");
        String key = fixtures.key(team).secret();

        ANTHROPIC.respondWithMessage("claude-opus-5", 1000, 500);
        int before = ANTHROPIC.requests().size();

        HttpResponse<String> response = call(key, "req-unmetered");

        assertThat(response.statusCode()).isEqualTo(402);
        assertThat(ANTHROPIC.requests()).hasSize(before);
        assertThat(mapper.readTree(response.body()).path("error").path("sluice").path("reason").asText())
                .isEqualTo("unmetered_account");
    }

    @Test
    @DisplayName("an unfunded team can still run on a budget alone")
    void budgetWithoutPrepaidCreditIsEnforceable() throws Exception {
        Account org = fixtures.org("acme");
        Account team = fixtures.team(org, "budget-only");
        budgets.upsert(team.id(), BudgetPeriod.DAILY, micros("1.00"), "USD", true);
        String key = fixtures.key(team).secret();

        ANTHROPIC.respondWithMessage("claude-opus-5", 1000, 500);

        assertThat(call(key, "req-budget-only").statusCode()).isEqualTo(200);
        assertThat(ledger.usageMicros(team.id(), "USD")).isEqualTo(micros("0.0175"));
        assertLedgerIsSound();
    }

    @Test
    @DisplayName("concurrent calls cannot collectively overrun a budget")
    void concurrentCallsCannotOverrunTheBudget() throws Exception {
        Account org = fixtures.org("acme");
        Account team = fixtures.team(org, "platform");
        fixtures.fund(team, "1000.00");
        // Each call reserves 1000 max_tokens of output ($0.025) plus input.
        // A $0.10 budget must admit a handful and refuse the rest.
        budgets.upsert(team.id(), BudgetPeriod.DAILY, micros("0.10"), "USD", true);
        String key = fixtures.key(team).secret();
        ANTHROPIC.respondWithMessage("claude-opus-5", 10, 10);

        int attempts = 24;
        var results = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var startLine = new java.util.concurrent.CountDownLatch(1);

        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < attempts; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    startLine.await();
                    results.add(call(key, "req-race-" + index).statusCode());
                    return null;
                }));
            }
            startLine.countDown();
            for (var future : futures) {
                future.get();
            }
        }

        long allowed = results.stream().filter(status -> status == 200).count();
        long refused = results.stream().filter(status -> status == 402).count();

        assertThat(allowed + refused).isEqualTo(attempts);
        assertThat(refused).as("the budget did refuse some of them").isPositive();
        assertThat(ledger.heldMicros(team.id(), "USD")).isZero();
        assertThat(budgets.committedMicros(team.id(), BudgetPeriod.DAILY, Instant.now()))
                .as("settled spend never exceeds the limit")
                .isLessThanOrEqualTo(micros("0.10"));
        assertLedgerIsSound();
    }

    // ------------------------------------------------------------------ helpers

    /** Writes a completed hold/settle cycle without going through the proxy. */
    private void spend(Account account, String requestId, long amountMicros) {
        var metering = applicationMetering();
        metering.hold(account.id(), requestId, amountMicros, "USD", Duration.ofMinutes(5),
                java.util.Map.of());
        metering.settle(requestId, "claude-opus-5",
                dev.sluice.pricing.TokenUsage.of(1000, 500), amountMicros,
                false, false, 100, java.util.Map.of());
    }

    @Autowired
    private dev.sluice.ledger.MeteringService metering;

    private dev.sluice.ledger.MeteringService applicationMetering() {
        return metering;
    }

    private HttpResponse<String> call(String key, String requestId) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(base() + "/v1/messages"))
                        .header("content-type", "application/json")
                        .header("x-api-key", key)
                        .header("x-sluice-request-id", requestId)
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"model":"claude-opus-5","max_tokens":1000,
                                 "messages":[{"role":"user","content":"hi"}]}"""))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static long micros(String decimal) {
        return Micros.fromDecimal(new BigDecimal(decimal));
    }
}
