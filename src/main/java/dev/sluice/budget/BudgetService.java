package dev.sluice.budget;

import dev.sluice.account.Account;
import dev.sluice.account.AccountService;
import dev.sluice.ledger.AccountRef;
import dev.sluice.ledger.LedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Decides whether a call may proceed, and who pays for it.
 *
 * <p>Two independent constraints:
 *
 * <ul>
 *   <li><b>Balance.</b> Exactly one account pays: the nearest account in the
 *       chain that has ever been funded, starting from the key's own account and
 *       walking up. Its prepaid credit must cover the estimate. Funding an org
 *       centrally therefore lets every project beneath it spend from one pot,
 *       and funding a project gives it a ring-fenced pot of its own.</li>
 *   <li><b>Budget.</b> Every ancestor carrying a budget row caps spend across its
 *       whole subtree for the period. Budgets are checked whether or not anyone
 *       is funded, so a team can run on a pure budget with no prepaid credit.</li>
 * </ul>
 *
 * <p>A chain with neither is refused (see {@link UnmeteredAccountException}) unless
 * {@code sluice.budget.allow-unmetered} is set.
 */
@Service
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    private final BudgetRepository budgets;
    private final LedgerRepository ledger;
    private final AccountService accounts;
    private final boolean allowUnmetered;

    public BudgetService(BudgetRepository budgets, LedgerRepository ledger, AccountService accounts,
                         dev.sluice.config.SluiceProperties properties) {
        this.budgets = budgets;
        this.ledger = ledger;
        this.accounts = accounts;
        this.allowUnmetered = properties.budget().allowUnmetered();
    }

    /**
     * @throws BudgetExceededException  when a hard constraint refuses the call
     * @throws UnmeteredAccountException when nothing constrains the call at all
     */
    @Transactional(readOnly = true)
    public Authorization authorize(List<Account> chain, long requestedMicros,
                                   String currency, Instant now) {
        Account keyAccount = chain.get(0);

        Map<String, LedgerRepository.RefBalance> balances = ledger.balances(
                chain.stream().map(a -> AccountRef.credits(a.id())).toList(), currency);

        Account payer = null;
        long available = 0L;
        for (Account account : chain) {
            LedgerRepository.RefBalance balance = balances.get(AccountRef.credits(account.id()));
            if (balance != null && balance.postingCount() > 0) {
                payer = account;
                available = balance.balanceMicros();
                break;
            }
        }

        List<Budget> applicable = budgets.findForAccounts(chain.stream().map(Account::id).toList());

        if (payer == null && applicable.isEmpty()) {
            if (!allowUnmetered) {
                throw new UnmeteredAccountException(
                        ("account '%s' has no prepaid credit and no budget anywhere in its chain; "
                                + "credit it or set a budget before issuing traffic")
                                .formatted(keyAccount.name()));
            }
            log.warn("account '{}' is unmetered: no credit, no budget, spend is uncapped",
                    keyAccount.name());
            return new Authorization(keyAccount, false, 0L);
        }

        if (payer != null && available < requestedMicros) {
            throw new BudgetExceededException(new SpendRefusal(
                    SpendRefusal.Kind.INSUFFICIENT_BALANCE, payer.id(), payer.name(),
                    null, available, 0L, requestedMicros, currency));
        }

        enforceBudgets(applicable, chain, requestedMicros, currency, now);

        return payer == null
                ? new Authorization(keyAccount, false, 0L)
                : new Authorization(payer, true, available);
    }

    private void enforceBudgets(List<Budget> applicable, List<Account> chain,
                                long requestedMicros, String currency, Instant now) {
        if (applicable.isEmpty()) {
            return;
        }
        Map<UUID, Account> byId = chain.stream().collect(Collectors.toMap(Account::id, a -> a));
        List<SpendRefusal> softBreaches = new ArrayList<>();

        for (Budget budget : applicable) {
            long committed = budgets.committedMicros(
                    accounts.subtreeIds(budget.accountId()), budget.period().windowStart(now));
            if (committed + requestedMicros <= budget.limitMicros()) {
                continue;
            }
            Account account = byId.get(budget.accountId());
            SpendRefusal refusal = new SpendRefusal(
                    SpendRefusal.Kind.BUDGET_EXCEEDED, budget.accountId(),
                    account == null ? budget.accountId().toString() : account.name(),
                    budget.period(), budget.limitMicros(), committed, requestedMicros, currency);
            if (budget.hardStop()) {
                throw new BudgetExceededException(refusal);
            }
            softBreaches.add(refusal);
        }
        // Soft budgets are observed, not enforced -- the alert without the outage.
        softBreaches.forEach(b -> log.warn("soft budget breached, call allowed: {}", b.message()));
    }

    @Transactional
    public Budget upsert(UUID accountId, BudgetPeriod period, long limitMicros,
                         String currency, boolean hardStop) {
        return budgets.upsert(accountId, period, limitMicros, currency, hardStop);
    }

    public List<Budget> forAccount(UUID accountId) {
        return budgets.findByAccount(accountId);
    }

    @Transactional
    public boolean delete(UUID accountId, BudgetPeriod period) {
        return budgets.delete(accountId, period) > 0;
    }

    public long committedMicros(UUID accountId, BudgetPeriod period, Instant now) {
        return budgets.committedMicros(accounts.subtreeIds(accountId), period.windowStart(now));
    }
}
