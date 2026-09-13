package dev.sluice.sweeper;

import dev.sluice.budget.BalanceCache;
import dev.sluice.budget.SpendGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Re-derives cached balances from Postgres.
 *
 * <p>The cache is maintained incrementally in the request path, which is fast but
 * drifts: a delta lost to a Redis blip, or a second gateway instance posting
 * entries this one never saw. This puts it back. Between runs the cache can be
 * wrong; the authoritative check in the hold transaction is what keeps that from
 * mattering.
 */
@Component
public class BalanceReconciler {

    private static final Logger log = LoggerFactory.getLogger(BalanceReconciler.class);

    private final JdbcTemplate jdbc;
    private final SpendGate gate;
    private final BalanceCache cache;

    public BalanceReconciler(JdbcTemplate jdbc, SpendGate gate, BalanceCache cache) {
        this.jdbc = jdbc;
        this.gate = gate;
        this.cache = cache;
    }

    @Scheduled(fixedDelayString = "${sluice.cache.reconcile-interval:60s}")
    public void reconcile() {
        if (!cache.healthy()) {
            return;
        }
        try {
            List<FundedAccount> funded = fundedAccounts();
            for (FundedAccount account : funded) {
                gate.refresh(account.id(), account.currency());
            }
            if (!funded.isEmpty()) {
                log.debug("reconciled {} cached balance(s)", funded.size());
            }
        } catch (RuntimeException e) {
            log.warn("balance reconciliation failed, will retry: {}", e.toString());
        }
    }

    /** Accounts holding prepaid credit -- the only ones that can actually pay. */
    private List<FundedAccount> fundedAccounts() {
        return jdbc.query("""
                select distinct a.id, a.currency
                  from account a
                  join posting p
                    on p.account_id = a.id
                   and p.account_ref = 'credits:' || a.id
                """,
                (rs, rowNum) -> new FundedAccount(
                        rs.getObject("id", UUID.class), rs.getString("currency")));
    }

    private record FundedAccount(UUID id, String currency) {
    }
}
