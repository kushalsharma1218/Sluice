package dev.sluice.sweeper;

import dev.sluice.account.Account;
import dev.sluice.ledger.HoldStatus;
import dev.sluice.ledger.LedgerService;
import dev.sluice.ledger.MeteringService;
import dev.sluice.ledger.Micros;
import dev.sluice.pricing.TokenUsage;
import dev.sluice.support.AbstractIntegrationTest;
import dev.sluice.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client that vanishes between the hold and the settle would otherwise leave
 * money reserved forever. This is the backstop.
 */
class HoldSweeperTest extends AbstractIntegrationTest {

    @Autowired
    private HoldSweeper sweeper;
    @Autowired
    private MeteringService metering;
    @Autowired
    private LedgerService ledger;
    @Autowired
    private Fixtures fixtures;

    @Test
    @DisplayName("an expired hold is released and the money returns to the pot")
    void expiredHoldsAreReleased() {
        Account team = funded();
        metering.hold(team.id(), "req-abandoned", micros("2.00"), "USD",
                Duration.ofMinutes(15), Map.of());
        assertThat(available(team)).isEqualTo(micros("8.00"));

        expire("req-abandoned");
        int released = sweeper.sweepOnce(Instant.now());

        assertThat(released).isEqualTo(1);
        assertThat(available(team)).isEqualTo(micros("10.00"));
        assertThat(ledger.heldMicros(team.id(), "USD")).isZero();
        assertThat(metering.findHold("req-abandoned")).get()
                .extracting(dev.sluice.ledger.Hold::status).isEqualTo(HoldStatus.RELEASED);
        assertLedgerIsSound();
    }

    @Test
    @DisplayName("a hold that has not expired is left alone")
    void liveHoldsSurviveTheSweep() {
        Account team = funded();
        metering.hold(team.id(), "req-inflight", micros("2.00"), "USD",
                Duration.ofMinutes(15), Map.of());

        assertThat(sweeper.sweepOnce(Instant.now())).isZero();
        assertThat(available(team)).isEqualTo(micros("8.00"));
        assertThat(metering.findHold("req-inflight")).get()
                .extracting(dev.sluice.ledger.Hold::status).isEqualTo(HoldStatus.OPEN);
    }

    @Test
    @DisplayName("sweeping an already-settled hold changes nothing")
    void settledHoldsAreNotDoubleReleased() {
        Account team = funded();
        metering.hold(team.id(), "req-done", micros("2.00"), "USD",
                Duration.ofMinutes(15), Map.of());
        metering.settle("req-done", "claude-opus-5", TokenUsage.of(1000, 500),
                micros("0.25"), false, false, 100, Map.of());

        expire("req-done");

        assertThat(sweeper.sweepOnce(Instant.now()))
                .as("a settled hold is no longer OPEN, so the sweeper skips it")
                .isZero();
        assertThat(available(team)).isEqualTo(micros("9.75"));
        assertLedgerIsSound();
    }

    @Test
    @DisplayName("the sweep is idempotent")
    void repeatedSweepsReleaseOnce() {
        Account team = funded();
        metering.hold(team.id(), "req-twice", micros("2.00"), "USD",
                Duration.ofMinutes(15), Map.of());
        expire("req-twice");

        assertThat(sweeper.sweepOnce(Instant.now())).isEqualTo(1);
        assertThat(sweeper.sweepOnce(Instant.now())).isZero();
        assertThat(available(team)).isEqualTo(micros("10.00"));
        assertLedgerIsSound();
    }

    private Account funded() {
        Account org = fixtures.org("acme");
        Account team = fixtures.team(org, "platform");
        fixtures.fund(team, "10.00");
        return team;
    }

    /** Backdates a hold's expiry so the sweeper sees it without waiting. */
    private void expire(String requestId) {
        jdbc.update("update hold set expires_at = ? where request_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))), requestId);
    }

    private long available(Account account) {
        return ledger.availableMicros(account.id(), "USD");
    }

    private void assertLedgerIsSound() {
        Long imbalance = jdbc.queryForObject(
                "select coalesce(sum(case when direction = 'DEBIT' then amount_micros "
                        + "else -amount_micros end), 0) from posting", Long.class);
        assertThat(imbalance).isZero();
    }

    private static long micros(String decimal) {
        return Micros.fromDecimal(new BigDecimal(decimal));
    }
}
