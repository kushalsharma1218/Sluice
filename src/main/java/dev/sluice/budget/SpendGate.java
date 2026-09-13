package dev.sluice.budget;

import dev.sluice.account.Account;
import dev.sluice.auth.Principal;
import dev.sluice.ledger.HoldTicket;
import dev.sluice.ledger.LedgerService;
import dev.sluice.ledger.MeteringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The gate every proxied call passes through.
 *
 * <p>Two checks, deliberately different in cost and in authority:
 *
 * <ol>
 *   <li>{@link #preflight} reads the cached available balance. On a hit with
 *       headroom it returns in microseconds and nothing touches Postgres. This is
 *       the path that makes a runaway agent cheap to refuse -- the thousandth
 *       rejected call costs a Redis GET, not a database round trip.</li>
 *   <li>{@link #reserve} is the authority. It locks the account chain, re-runs the
 *       full check against Postgres, and writes the hold in one transaction. A
 *       stale cache can make this slower or make it disagree; it can never make it
 *       wrong.</li>
 * </ol>
 *
 * <p>If Postgres is unreachable, {@link #reserve} throws and the call is refused.
 * Fail closed: not charging is a bug, letting spend run unmetered is a worse one.
 */
@Service
public class SpendGate {

    private static final Logger log = LoggerFactory.getLogger(SpendGate.class);
    private static final int PAYER_HINT_CAPACITY = 10_000;

    private final LedgerService ledger;
    private final BudgetService budgets;
    private final MeteringService metering;
    private final BalanceCache cache;

    /**
     * Which account actually pays for a given key's account. Changes only when an
     * account is funded for the first time, so a short-lived hint is safe: getting
     * it wrong costs a cache miss, never a wrong decision.
     */
    private final ConcurrentHashMap<UUID, UUID> payerHints = new ConcurrentHashMap<>();

    public SpendGate(LedgerService ledger, BudgetService budgets,
                     MeteringService metering, BalanceCache cache) {
        this.ledger = ledger;
        this.budgets = budgets;
        this.metering = metering;
        this.cache = cache;
    }

    /**
     * Cheap advisory check. {@code false} means the cache is confident the account
     * cannot afford this call; the caller should confirm against Postgres before
     * refusing, because a stale cache must not produce a false 402.
     */
    public PreflightDecision preflight(Principal principal, long estimateMicros) {
        long started = System.nanoTime();
        UUID payerHint = payerHints.get(principal.accountId());
        if (payerHint == null || !cache.healthy()) {
            return new PreflightDecision(true, "postgres", System.nanoTime() - started);
        }
        OptionalLong available = cache.availableMicros(payerHint);
        if (available.isEmpty()) {
            return new PreflightDecision(true, "postgres", System.nanoTime() - started);
        }
        return new PreflightDecision(available.getAsLong() >= estimateMicros,
                "cache", System.nanoTime() - started);
    }

    /**
     * Authoritative check plus hold, in one transaction.
     *
     * @throws BudgetExceededException   the call is refused; no provider call is made
     * @throws UnmeteredAccountException nothing constrains this account chain
     */
    @Transactional
    public HoldTicket reserve(Principal principal, String requestId, long estimateMicros,
                              Duration ttl, Map<String, Object> metadata) {
        // Serialisation point. Without this, two concurrent requests can both read
        // a balance that only covers one of them and both pass.
        ledger.lockAccounts(principal.chainIds());

        Authorization authorization = budgets.authorize(
                principal.chain(), estimateMicros, principal.currency(), Instant.now());

        Account payer = authorization.payer();
        rememberPayer(principal.accountId(), payer.id());
        if (authorization.payerFunded()) {
            cache.put(payer.id(), authorization.availableMicros());
        }

        return metering.hold(payer.id(), requestId, estimateMicros,
                principal.currency(), ttl, metadata);
    }

    /** Recomputes a cached balance from Postgres. Used by the reconciler. */
    @Transactional(readOnly = true)
    public long refresh(UUID payerAccountId, String currency) {
        long available = ledger.availableMicros(payerAccountId, currency);
        cache.put(payerAccountId, available);
        return available;
    }

    public void forget(UUID payerAccountId) {
        cache.invalidate(payerAccountId);
    }

    private void rememberPayer(UUID keyAccountId, UUID payerAccountId) {
        if (payerHints.size() > PAYER_HINT_CAPACITY) {
            log.debug("payer hint table full, clearing");
            payerHints.clear();
        }
        payerHints.put(keyAccountId, payerAccountId);
    }
}
