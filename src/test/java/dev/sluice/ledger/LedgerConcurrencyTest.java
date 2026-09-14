package dev.sluice.ledger;

import dev.sluice.account.Account;
import dev.sluice.support.AbstractIntegrationTest;
import dev.sluice.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fires a thousand concurrent transfers and proves the debits-equal-credits
 * invariant survives -- the guarantee everything else in the system rests on.
 */
class LedgerConcurrencyTest extends AbstractIntegrationTest {

    private static final int TRANSFERS = 1_000;
    private static final int ACCOUNTS = 8;

    @Autowired
    private LedgerService ledger;
    @Autowired
    private LedgerRepository repository;
    @Autowired
    private Fixtures fixtures;

    @Test
    @DisplayName("1,000 concurrent transfers leave the ledger perfectly balanced")
    void concurrentTransfersPreserveTheInvariant() throws Exception {
        Account org = fixtures.org("acme");
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            Account team = fixtures.team(org, "team-" + i);
            accounts.add(team);
            fixtures.fund(team, "1000.00");
        }

        long openingTotal = accounts.stream()
                .mapToLong(a -> ledger.availableMicros(a.id(), "USD")).sum();

        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        List<Callable<Void>> work = new ArrayList<>(TRANSFERS);

        for (int i = 0; i < TRANSFERS; i++) {
            final int index = i;
            work.add(() -> {
                startLine.await();
                var random = ThreadLocalRandom.current();
                int from = random.nextInt(ACCOUNTS);
                int to = (from + 1 + random.nextInt(ACCOUNTS - 1)) % ACCOUNTS;
                long amount = 1 + random.nextLong(50_000);
                transfer(accounts.get(from), accounts.get(to), amount, "transfer-" + index);
                applied.incrementAndGet();
                return null;
            });
        }

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = work.stream().map(pool::submit).toList();
            startLine.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        }

        assertThat(applied.get()).isEqualTo(TRANSFERS);

        // The invariant, three ways.
        assertThat(repository.unbalancedEntryCount())
                .as("every journal entry balances on its own")
                .isZero();
        assertThat(repository.globalImbalance())
                .as("the ledger as a whole sums to zero")
                .isZero();

        long closingTotal = accounts.stream()
                .mapToLong(a -> ledger.availableMicros(a.id(), "USD")).sum();
        assertThat(closingTotal)
                .as("transfers move money between accounts, they never create or destroy it")
                .isEqualTo(openingTotal);
    }

    @Test
    @DisplayName("the database rejects an unbalanced entry even when the application does not")
    void databaseRefusesUnbalancedPostings() {
        Account org = fixtures.org("acme");
        UUID entryId = UUID.randomUUID();

        // Write the header and a single one-sided posting directly, bypassing
        // LedgerService entirely. The deferred trigger fires at commit.
        assertThatThrownBy(() -> jdbc.execute("""
                begin;
                insert into journal_entry (id, idempotency_key, type)
                values ('%s', 'bypass-test', 'ADJUSTMENT');
                insert into posting (entry_id, account_ref, account_id, amount_micros, currency, direction)
                values ('%s', 'credits:%s', '%s', 5000, 'USD', 'DEBIT');
                commit;
                """.formatted(entryId, entryId, org.id(), org.id())))
                .hasMessageContaining("unbalanced");

        assertThat(repository.unbalancedEntryCount()).isZero();
        assertThat(repository.globalImbalance()).isZero();
    }

    @Test
    @DisplayName("an unbalanced entry is refused before it reaches the database")
    void serviceRefusesUnbalancedEntry() {
        Account org = fixtures.org("acme");
        assertThatThrownBy(() -> ledger.post(new JournalEntryRequest(
                "lopsided", EntryType.ADJUSTMENT, null, Map.of(),
                List.of(PostingLine.debit(AccountRef.credits(org.id()), org.id(), 100, "USD"),
                        PostingLine.credit(AccountRef.EQUITY_FUNDING, null, 99, "USD")))))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("unbalanced in USD by 1 micros");
    }

    private void transfer(Account from, Account to, long micros, String key) {
        ledger.post(new JournalEntryRequest(
                key, EntryType.ADJUSTMENT, null, Map.of(),
                List.of(
                        PostingLine.credit(AccountRef.credits(from.id()), from.id(), micros, "USD"),
                        PostingLine.debit(AccountRef.credits(to.id()), to.id(), micros, "USD"))));
    }
}
