package dev.sluice.sweeper;

import dev.sluice.config.SluiceProperties;
import dev.sluice.ledger.Hold;
import dev.sluice.ledger.MeteringService;
import dev.sluice.ledger.Micros;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Releases holds whose settle never arrived.
 *
 * <p>Without this, a client that dies between the hold and the settle leaves money
 * reserved forever, and the account slowly starves. Every hold carries an expiry;
 * this sweeps the ones past it.
 *
 * <p>Releasing is the safe direction to be wrong in. A hold swept early would
 * under-reserve an in-flight call, so the TTL is generous relative to the longest
 * plausible completion.
 */
@Component
public class HoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);

    private final MeteringService metering;
    private final SluiceProperties properties;

    public HoldSweeper(MeteringService metering, SluiceProperties properties) {
        this.metering = metering;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${sluice.hold.sweep-interval:30s}")
    public void sweep() {
        try {
            int released = sweepOnce(Instant.now());
            if (released > 0) {
                log.info("released {} expired hold(s)", released);
            }
        } catch (RuntimeException e) {
            log.warn("hold sweep failed, will retry next interval: {}", e.toString());
        }
    }

    /** Visible for testing: sweeps holds expired as of {@code asOf}. */
    public int sweepOnce(Instant asOf) {
        List<Hold> expired = metering.expiredHolds(asOf, properties.hold().sweepBatchSize());
        int released = 0;
        for (Hold hold : expired) {
            var result = metering.release(hold.requestId(), "hold expired at " + hold.expiresAt());
            if (result.created()) {
                released++;
                log.debug("released orphaned hold {} for {} {} on account {}",
                        hold.requestId(), Micros.format(hold.amountMicros()),
                        hold.currency(), hold.accountId());
            }
        }
        return released;
    }
}
