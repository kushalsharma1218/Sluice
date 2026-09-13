package dev.sluice.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only way postings enter the database.
 *
 * <p>Two guarantees, in order of importance:
 * <ol>
 *   <li>Every entry balances. Checked here for a readable error, and again by a
 *       deferred constraint trigger so no other code path can bypass it.</li>
 *   <li>Every entry is idempotent on {@code idempotencyKey}. A replay returns
 *       the original entry and writes nothing.</li>
 * </ol>
 */
@Service
public class LedgerService {

    private final LedgerRepository repository;

    public LedgerService(LedgerRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PostedEntry post(JournalEntryRequest request) {
        assertBalanced(request);

        UUID id = UUID.randomUUID();
        Optional<UUID> inserted = repository.insertEntryIfAbsent(id, request);
        if (inserted.isEmpty()) {
            // Replay. The original entry already carries the postings.
            LedgerRepository.JournalEntryHeader existing = repository
                    .findByIdempotencyKey(request.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "idempotency key " + request.idempotencyKey() + " conflicted but is not readable"));
            return new PostedEntry(existing.id(), existing.idempotencyKey(), existing.type(), false);
        }

        repository.insertPostings(id, request.postings());
        return new PostedEntry(id, request.idempotencyKey(), request.type(), true);
    }

    /**
     * Spendable balance: deposits minus everything held or spent. Open holds are
     * already deducted because a hold debits {@code holds:} out of {@code credits:}.
     */
    @Transactional(readOnly = true)
    public long availableMicros(UUID accountId, String currency) {
        return repository.balance(AccountRef.credits(accountId), currency);
    }

    @Transactional(readOnly = true)
    public long heldMicros(UUID accountId, String currency) {
        return repository.balance(AccountRef.holds(accountId), currency);
    }

    @Transactional(readOnly = true)
    public long usageMicros(UUID accountId, String currency) {
        return repository.balance(AccountRef.usage(accountId), currency);
    }

    /**
     * Takes row locks on the given accounts for the remainder of the current
     * transaction. Callers that read a balance and then post against it must call
     * this first, or two concurrent requests can both pass a check that only one
     * of them should.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAccounts(Collection<UUID> accountIds) {
        repository.lockAccountsInOrder(accountIds);
    }

    static void assertBalanced(JournalEntryRequest request) {
        Map<String, Long> byCurrency = new HashMap<>();
        for (PostingLine line : request.postings()) {
            byCurrency.merge(line.currency(), line.signedMicros(), Long::sum);
        }
        for (Map.Entry<String, Long> e : byCurrency.entrySet()) {
            if (e.getValue() != 0L) {
                throw new UnbalancedEntryException(
                        "entry %s is unbalanced in %s by %d micros"
                                .formatted(request.idempotencyKey(), e.getKey(), e.getValue()));
            }
        }
    }
}
