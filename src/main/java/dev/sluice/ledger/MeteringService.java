package dev.sluice.ledger;

import dev.sluice.budget.BalanceCache;
import dev.sluice.pricing.TokenUsage;
import dev.sluice.usage.UsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The hold/settle cycle.
 *
 * <p>The central problem this solves: the cost of a call is unknown until it
 * finishes, but the decision to allow it has to be made before it starts. So a
 * pessimistic estimate is reserved up front, the call runs, and the reservation
 * is replaced by the real number afterwards.
 *
 * <p>Ledger shape, under the DEBIT-positive convention where balance is
 * {@code SUM(DEBIT) - SUM(CREDIT)}:
 *
 * <pre>
 *   DEPOSIT  d   DEBIT  credits:P d      CREDIT equity:funding d
 *   HOLD     h   DEBIT  holds:P   h      CREDIT credits:P      h
 *   SETTLE   a   DEBIT  usage:P   a      CREDIT holds:P        h
 *                DEBIT  credits:P (h-a)                              [when a &lt; h]
 *                                        CREDIT credits:P      (a-h) [when a &gt; h]
 *   RELEASE  h   DEBIT  credits:P h      CREDIT holds:P        h
 * </pre>
 *
 * <p>Every one of these is idempotent on the client's request id. Replaying a
 * settle returns the original settlement and writes nothing.
 */
@Service
public class MeteringService {

    private static final Logger log = LoggerFactory.getLogger(MeteringService.class);

    private final LedgerService ledger;
    private final HoldRepository holds;
    private final UsageRepository usage;
    private final BalanceCache cache;

    public MeteringService(LedgerService ledger, HoldRepository holds,
                           UsageRepository usage, BalanceCache cache) {
        this.ledger = ledger;
        this.holds = holds;
        this.usage = usage;
        this.cache = cache;
    }

    /** Prepaid credit arriving from outside the system. */
    @Transactional
    public PostedEntry deposit(UUID accountId, long amountMicros, String currency,
                               String idempotencyKey, Map<String, Object> metadata) {
        if (amountMicros <= 0) {
            throw new IllegalArgumentException("deposit must be positive");
        }
        Map<String, Object> meta = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        meta.put("account_id", accountId.toString());

        PostedEntry entry = ledger.post(new JournalEntryRequest(
                idempotencyKey, EntryType.DEPOSIT, null, meta,
                List.of(
                        PostingLine.debit(AccountRef.credits(accountId), accountId, amountMicros, currency),
                        PostingLine.credit(AccountRef.EQUITY_FUNDING, null, amountMicros, currency))));

        if (entry.created()) {
            cache.applyDelta(accountId, amountMicros);
        }
        return entry;
    }

    /**
     * Reserves {@code amountMicros} against the payer. Callers must have taken the
     * account locks (see {@link LedgerService#lockAccounts}) and run the budget
     * check inside the same transaction, or two concurrent requests can both pass
     * a check only one of them should.
     */
    @Transactional
    public HoldTicket hold(UUID payerAccountId, String requestId, long amountMicros,
                           String currency, Duration ttl, Map<String, Object> metadata) {
        Optional<Hold> existing = holds.findByRequestId(requestId);
        if (existing.isPresent()) {
            Hold hold = existing.get();
            return new HoldTicket(hold.id(), hold.entryId(), hold.accountId(), requestId,
                    hold.amountMicros(), hold.currency(), false);
        }

        Map<String, Object> meta = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        meta.put("request_id", requestId);
        meta.put("account_id", payerAccountId.toString());

        PostedEntry entry = ledger.post(new JournalEntryRequest(
                holdKey(requestId), EntryType.HOLD, null, meta,
                List.of(
                        PostingLine.debit(AccountRef.holds(payerAccountId), payerAccountId, amountMicros, currency),
                        PostingLine.credit(AccountRef.credits(payerAccountId), payerAccountId, amountMicros, currency))));

        Hold hold = holds.insert(UUID.randomUUID(), entry.id(), payerAccountId, requestId,
                amountMicros, currency, Instant.now().plus(ttl));

        cache.applyDelta(payerAccountId, -amountMicros);
        return new HoldTicket(hold.id(), entry.id(), payerAccountId, requestId,
                amountMicros, currency, true);
    }

    /**
     * Books the real cost and returns whatever is left of the hold.
     *
     * <p>{@code actualMicros} may exceed the hold. Sluice trusts the provider's own
     * usage numbers over its own estimate, so an under-estimated call is charged in
     * full and the payer's balance goes correspondingly negative rather than the
     * ledger quietly under-reporting what was spent.
     */
    @Transactional
    public SettlementResult settle(String requestId, String model, TokenUsage tokens,
                                   long actualMicros, boolean streamed, boolean partial,
                                   int latencyMs, Map<String, Object> metadata) {
        if (actualMicros < 0) {
            throw new IllegalArgumentException("settlement amount cannot be negative");
        }
        Hold hold = holds.lockByRequestId(requestId)
                .orElseThrow(() -> new IllegalStateException("no hold for request " + requestId));

        if (hold.status() != HoldStatus.OPEN) {
            // Already settled or released. Replays are no-ops by design.
            return new SettlementResult(requestId, hold.amountMicros(), 0L, 0L, false);
        }
        if (!holds.resolve(hold.id(), HoldStatus.SETTLED)) {
            return new SettlementResult(requestId, hold.amountMicros(), 0L, 0L, false);
        }

        long held = hold.amountMicros();
        UUID payer = hold.accountId();
        String currency = hold.currency();

        var postings = new java.util.ArrayList<PostingLine>(3);
        // Release the reservation in full...
        postings.add(PostingLine.credit(AccountRef.holds(payer), payer, held, currency));
        // ...book what was actually consumed...
        if (actualMicros > 0) {
            postings.add(PostingLine.debit(AccountRef.usage(payer), payer, actualMicros, currency));
        }
        // ...and reconcile the difference against the spendable pot.
        long difference = held - actualMicros;
        if (difference > 0) {
            postings.add(PostingLine.debit(AccountRef.credits(payer), payer, difference, currency));
        } else if (difference < 0) {
            postings.add(PostingLine.credit(AccountRef.credits(payer), payer, -difference, currency));
        }
        if (actualMicros == 0 && held == 0) {
            throw new IllegalStateException("a hold of zero should never have been written");
        }

        Map<String, Object> meta = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        meta.put("request_id", requestId);
        meta.put("model", model);
        meta.put("held_micros", held);
        meta.put("charged_micros", actualMicros);
        meta.put("partial", partial);

        PostedEntry entry = ledger.post(new JournalEntryRequest(
                settleKey(requestId), EntryType.SETTLE, null, meta, postings));

        usage.insert(UUID.randomUUID(), entry.id(), payer, requestId, model, tokens,
                actualMicros, currency, streamed, partial, latencyMs);

        cache.applyDelta(payer, difference);

        if (actualMicros > held) {
            log.warn("request {} overran its estimate: held {} charged {} ({}); "
                            + "balance for account {} may now be negative",
                    requestId, Micros.format(held), Micros.format(actualMicros), currency, payer);
        }
        return new SettlementResult(requestId, held, actualMicros, difference, true);
    }

    /**
     * Returns the whole hold. Used when the call failed before delivering anything,
     * and by the sweeper for holds whose settle never arrived.
     */
    @Transactional
    public SettlementResult release(String requestId, String reason) {
        Optional<Hold> found = holds.lockByRequestId(requestId);
        if (found.isEmpty()) {
            return new SettlementResult(requestId, 0L, 0L, 0L, false);
        }
        Hold hold = found.get();
        if (hold.status() != HoldStatus.OPEN || !holds.resolve(hold.id(), HoldStatus.RELEASED)) {
            return new SettlementResult(requestId, hold.amountMicros(), 0L, 0L, false);
        }

        UUID payer = hold.accountId();
        long amount = hold.amountMicros();

        ledger.post(new JournalEntryRequest(
                releaseKey(requestId), EntryType.RELEASE, null,
                Map.of("request_id", requestId, "reason", reason),
                List.of(
                        PostingLine.debit(AccountRef.credits(payer), payer, amount, hold.currency()),
                        PostingLine.credit(AccountRef.holds(payer), payer, amount, hold.currency()))));

        cache.applyDelta(payer, amount);
        return new SettlementResult(requestId, amount, 0L, amount, true);
    }

    public Optional<Hold> findHold(String requestId) {
        return holds.findByRequestId(requestId);
    }

    public List<Hold> expiredHolds(Instant asOf, int limit) {
        return holds.findExpired(asOf, limit);
    }

    static String holdKey(String requestId) {
        return "hold:" + requestId;
    }

    static String settleKey(String requestId) {
        return "settle:" + requestId;
    }

    static String releaseKey(String requestId) {
        return "release:" + requestId;
    }
}
