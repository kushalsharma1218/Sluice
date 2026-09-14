package dev.sluice.ledger;

import dev.sluice.account.Account;
import dev.sluice.pricing.TokenUsage;
import dev.sluice.support.AbstractIntegrationTest;
import dev.sluice.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hold/settle cycle, and its central promise -- replaying a request id
 * charges exactly once.
 */
class HoldSettleTest extends AbstractIntegrationTest {

    @Autowired
    private MeteringService metering;
    @Autowired
    private LedgerService ledger;
    @Autowired
    private LedgerRepository repository;
    @Autowired
    private Fixtures fixtures;

    private static final Duration TTL = Duration.ofMinutes(15);

    @Test
    @DisplayName("a hold reserves from the spendable pot, and settling returns the remainder")
    void holdThenSettleReturnsTheUnusedReservation() {
        Account team = funded("10.00");

        metering.hold(team.id(), "req-1", micros("2.00"), "USD", TTL, Map.of());

        assertThat(available(team)).as("held money is not spendable").isEqualTo(micros("8.00"));
        assertThat(held(team)).isEqualTo(micros("2.00"));

        metering.settle("req-1", "claude-opus-5", TokenUsage.of(1000, 500),
                micros("0.25"), false, false, 120, Map.of());

        assertThat(available(team)).as("the unused 1.75 comes back").isEqualTo(micros("9.75"));
        assertThat(held(team)).as("the hold is fully unwound").isZero();
        assertThat(usage(team)).as("consumption is recorded as an expense").isEqualTo(micros("0.25"));
        assertLedgerIsSound();
    }

    @Test
    @DisplayName("replaying the same request id charges exactly once")
    void settlingTwiceChargesOnce() {
        Account team = funded("10.00");
        metering.hold(team.id(), "req-replay", micros("2.00"), "USD", TTL, Map.of());

        var first = metering.settle("req-replay", "claude-opus-5", TokenUsage.of(1000, 500),
                micros("0.25"), false, false, 100, Map.of());
        var second = metering.settle("req-replay", "claude-opus-5", TokenUsage.of(1000, 500),
                micros("0.25"), false, false, 100, Map.of());

        assertThat(first.created()).isTrue();
        assertThat(second.created()).as("the replay is a no-op, not a second charge").isFalse();

        assertThat(available(team)).isEqualTo(micros("9.75"));
        assertThat(usage(team)).isEqualTo(micros("0.25"));
        assertThat(countUsageRecords()).isEqualTo(1);
        assertLedgerIsSound();
    }

    @Test
    @DisplayName("replaying a hold does not reserve twice")
    void holdingTwiceReservesOnce() {
        Account team = funded("10.00");

        var first = metering.hold(team.id(), "req-dup", micros("2.00"), "USD", TTL, Map.of());
        var second = metering.hold(team.id(), "req-dup", micros("2.00"), "USD", TTL, Map.of());

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.holdId()).isEqualTo(first.holdId());
        assertThat(available(team)).isEqualTo(micros("8.00"));
        assertLedgerIsSound();
    }

    @Test
    @DisplayName("concurrent settles of one request produce exactly one charge")
    void concurrentSettlesChargeOnce() throws Exception {
        Account team = funded("10.00");
        metering.hold(team.id(), "req-race", micros("2.00"), "USD", TTL, Map.of());

        int racers = 16;
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<SettlementResult>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < racers; i++) {
                results.add(pool.submit(() -> {
                    startLine.await();
                    return metering.settle("req-race", "claude-opus-5", TokenUsage.of(1000, 500),
                            micros("0.25"), false, false, 100, Map.of());
                }));
            }
            startLine.countDown();
            long created = 0;
            for (Future<SettlementResult> result : results) {
                if (result.get().created()) {
                    created++;
                }
            }
            assertThat(created).as("exactly one settle wins the race").isEqualTo(1);
        }

        assertThat(usage(team)).isEqualTo(micros("0.25"));
        assertThat(countUsageRecords()).isEqualTo(1);
        assertLedgerIsSound();
    }

    @Test
    @DisplayName("releasing returns the whole hold and charges nothing")
    void releaseReturnsEverything() {
        Account team = funded("10.00");
        metering.hold(team.id(), "req-fail", micros("2.00"), "USD", TTL, Map.of());

        metering.release("req-fail", "provider returned 500");

        assertThat(available(team)).isEqualTo(micros("10.00"));
        assertThat(held(team)).isZero();
        assertThat(usage(team)).isZero();
        assertThat(metering.findHold("req-fail")).get()
                .extracting(Hold::status).isEqualTo(HoldStatus.RELEASED);
        assertLedgerIsSound();
    }

    @Test
    @DisplayName("a settled hold cannot then be released")
    void releaseAfterSettleIsANoOp() {
        Account team = funded("10.00");
        metering.hold(team.id(), "req-both", micros("2.00"), "USD", TTL, Map.of());
        metering.settle("req-both", "claude-opus-5", TokenUsage.of(1000, 500),
                micros("0.25"), false, false, 100, Map.of());

        var release = metering.release("req-both", "late release");

        assertThat(release.created()).isFalse();
        assertThat(available(team)).isEqualTo(micros("9.75"));
        assertLedgerIsSound();
    }

    @Test
    @DisplayName("a call that overruns its estimate is charged in full, not capped at the hold")
    void overrunIsChargedInFull() {
        Account team = funded("10.00");
        metering.hold(team.id(), "req-overrun", micros("1.00"), "USD", TTL, Map.of());

        // Sluice trusts the provider's own usage numbers over its own estimate.
        metering.settle("req-overrun", "claude-opus-5", TokenUsage.of(100_000, 50_000),
                micros("1.75"), false, false, 900, Map.of());

        assertThat(usage(team)).as("the real cost is booked, not the reservation")
                .isEqualTo(micros("1.75"));
        assertThat(available(team)).as("the overage comes out of the pot")
                .isEqualTo(micros("8.25"));
        assertThat(held(team)).isZero();
        assertLedgerIsSound();
    }

    @Test
    @DisplayName("an overrun past the balance drives it negative rather than under-reporting")
    void overrunCanDriveTheBalanceNegative() {
        Account team = funded("1.00");
        metering.hold(team.id(), "req-huge", micros("1.00"), "USD", TTL, Map.of());
        metering.settle("req-huge", "claude-opus-5", TokenUsage.of(1_000_000, 500_000),
                micros("3.00"), false, false, 5000, Map.of());

        assertThat(available(team)).isEqualTo(micros("-2.00"));
        assertThat(usage(team)).isEqualTo(micros("3.00"));
        assertLedgerIsSound();
    }

    private Account funded(String amount) {
        Account org = fixtures.org("acme");
        Account team = fixtures.team(org, "platform");
        fixtures.fund(team, amount);
        return team;
    }

    private long available(Account account) {
        return ledger.availableMicros(account.id(), "USD");
    }

    private long held(Account account) {
        return ledger.heldMicros(account.id(), "USD");
    }

    private long usage(Account account) {
        return ledger.usageMicros(account.id(), "USD");
    }

    private int countUsageRecords() {
        Integer count = jdbc.queryForObject("select count(*) from usage_record", Integer.class);
        return count == null ? 0 : count;
    }

    private void assertLedgerIsSound() {
        assertThat(repository.unbalancedEntryCount()).isZero();
        assertThat(repository.globalImbalance()).isZero();
    }

    private static long micros(String decimal) {
        return Micros.fromDecimal(new BigDecimal(decimal));
    }
}
