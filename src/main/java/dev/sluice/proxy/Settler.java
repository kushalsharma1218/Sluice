package dev.sluice.proxy;

import dev.sluice.ledger.MeteringService;
import dev.sluice.ledger.Micros;
import dev.sluice.pricing.CostCalculator;
import dev.sluice.pricing.TokenUsage;
import dev.sluice.pricing.UnknownModelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Closes out a call exactly once, whatever happened to it.
 *
 * <p>Settling must never propagate a failure into the client's response -- the
 * response has usually already been delivered by the time we get here. A settle
 * that throws is logged loudly with everything needed to replay it, and the hold
 * is left for the sweeper.
 */
@Component
public class Settler {

    private static final Logger log = LoggerFactory.getLogger(Settler.class);

    private final MeteringService metering;
    private final CostCalculator costs;

    public Settler(MeteringService metering, CostCalculator costs) {
        this.metering = metering;
        this.costs = costs;
    }

    /**
     * @param requestedModel the model the client asked for, used when the response
     *                       never named one (a stream that died early)
     */
    public void settle(String requestId, String requestedModel, UsageTap tap,
                       boolean streamed, long startedAtNanos) {
        // A client disconnect makes the servlet container interrupt this thread.
        // JDBC over NIO closes its socket the moment it touches an interrupted
        // thread, so the flag has to come down before any ledger work -- otherwise
        // the disconnect that should settle the call silently fails to bill it.
        boolean interrupted = Thread.interrupted();
        try {
            doSettle(requestId, requestedModel, tap, streamed, startedAtNanos);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void doSettle(String requestId, String requestedModel, UsageTap tap,
                          boolean streamed, long startedAtNanos) {
        int latencyMs = (int) Math.min(Integer.MAX_VALUE,
                (System.nanoTime() - startedAtNanos) / 1_000_000L);
        String model = tap.model() == null ? requestedModel : tap.model();

        if (!tap.sawAnyUsage()) {
            // Nothing was delivered and nothing was reported. Charge nothing.
            release(requestId, "no usage reported by provider");
            return;
        }

        TokenUsage usage = tap.tokenUsage();
        boolean partial = streamed && !tap.hasAuthoritativeOutput();
        long cost;
        try {
            cost = costs.costMicros(model, usage, Instant.now());
        } catch (UnknownModelException e) {
            // A model we have no rate for cannot be priced, and guessing a price is
            // worse than releasing: release, and let the unpriced call show up in logs.
            log.error("no rate for model '{}' on request {}; releasing hold and charging nothing. "
                    + "Add a model_rate row.", model, requestId);
            release(requestId, "no rate for model " + model);
            return;
        }

        try {
            var result = metering.settle(requestId, model, usage, cost, streamed, partial, latencyMs,
                    Map.of("stop_reason", String.valueOf(tap.stopReason())));
            if (result.created()) {
                log.debug("settled {} model={} in={} out={} cost={} partial={}",
                        requestId, model, usage.inputTokens(), usage.outputTokens(),
                        Micros.format(cost), partial);
            }
        } catch (RuntimeException e) {
            log.error("SETTLE FAILED for request {} (model={}, input={}, output={}, cost_micros={}). "
                            + "The hold stays open until the sweeper releases it; this call is unbilled.",
                    requestId, model, usage.inputTokens(), usage.outputTokens(), cost, e);
        }
    }

    public void release(String requestId, String reason) {
        boolean interrupted = Thread.interrupted();
        try {
            metering.release(requestId, reason);
        } catch (RuntimeException e) {
            log.error("RELEASE FAILED for request {} ({}); the sweeper will retry at expiry",
                    requestId, reason, e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
