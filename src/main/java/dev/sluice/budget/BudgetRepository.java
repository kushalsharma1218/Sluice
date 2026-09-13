package dev.sluice.budget;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BudgetRepository {

    private static final RowMapper<Budget> MAPPER = (rs, rowNum) -> new Budget(
            rs.getObject("id", UUID.class),
            rs.getObject("account_id", UUID.class),
            BudgetPeriod.valueOf(rs.getString("period")),
            rs.getLong("limit_micros"),
            rs.getString("currency"),
            rs.getBoolean("hard_stop"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public BudgetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Budget upsert(UUID accountId, BudgetPeriod period, long limitMicros,
                         String currency, boolean hardStop) {
        jdbc.update("""
                insert into budget (id, account_id, period, limit_micros, currency, hard_stop)
                values (?, ?, ?, ?, ?, ?)
                on conflict (account_id, period) do update
                   set limit_micros = excluded.limit_micros,
                       currency     = excluded.currency,
                       hard_stop    = excluded.hard_stop,
                       updated_at   = now()
                """, UUID.randomUUID(), accountId, period.name(), limitMicros, currency, hardStop);
        return findFor(accountId, period).orElseThrow();
    }

    public Optional<Budget> findFor(UUID accountId, BudgetPeriod period) {
        return jdbc.query("select * from budget where account_id = ? and period = ?",
                MAPPER, accountId, period.name()).stream().findFirst();
    }

    public List<Budget> findForAccounts(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select * from budget where account_id = any (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", accountIds.toArray())),
                MAPPER);
    }

    public List<Budget> findByAccount(UUID accountId) {
        return jdbc.query("select * from budget where account_id = ? order by period",
                MAPPER, accountId);
    }

    public int delete(UUID accountId, BudgetPeriod period) {
        return jdbc.update("delete from budget where account_id = ? and period = ?",
                accountId, period.name());
    }

    /**
     * Money already committed against a budget in the current window: everything
     * settled, plus everything currently held. Counting open holds is what stops
     * a burst of concurrent calls from collectively blowing through the limit.
     */
    public long committedMicros(Collection<UUID> subtreeIds, Instant windowStart) {
        if (subtreeIds.isEmpty()) {
            return 0L;
        }
        Object[] ids = subtreeIds.toArray();
        Long settled = jdbc.query("""
                select coalesce(sum(cost_micros), 0)
                  from usage_record
                 where account_id = any (?) and created_at >= ?
                """,
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids));
                    ps.setTimestamp(2, Timestamp.from(windowStart));
                },
                rs -> rs.next() ? rs.getLong(1) : 0L);

        Long held = jdbc.query("""
                select coalesce(sum(amount_micros), 0)
                  from hold
                 where account_id = any (?) and status = 'OPEN'
                """,
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids)),
                rs -> rs.next() ? rs.getLong(1) : 0L);

        return (settled == null ? 0L : settled) + (held == null ? 0L : held);
    }
}
