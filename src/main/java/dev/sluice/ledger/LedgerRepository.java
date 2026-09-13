package dev.sluice.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public LedgerRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Inserts the entry header, or returns empty when the idempotency key is
     * already present. The unique constraint -- not the application -- is what
     * makes this safe under concurrency.
     */
    public Optional<UUID> insertEntryIfAbsent(UUID id, JournalEntryRequest request) {
        List<UUID> inserted = jdbc.query(
                """
                insert into journal_entry (id, idempotency_key, type, fx_rate, metadata)
                values (?, ?, ?, ?, ?::jsonb)
                on conflict (idempotency_key) do nothing
                returning id
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                id, request.idempotencyKey(), request.type().name(),
                request.fxRate(), writeJson(request.metadata()));
        return inserted.stream().findFirst();
    }

    public Optional<JournalEntryHeader> findByIdempotencyKey(String key) {
        return jdbc.query(
                        "select id, idempotency_key, type from journal_entry where idempotency_key = ?",
                        (rs, rowNum) -> new JournalEntryHeader(
                                rs.getObject("id", UUID.class),
                                rs.getString("idempotency_key"),
                                EntryType.valueOf(rs.getString("type"))),
                        key)
                .stream().findFirst();
    }

    public void insertPostings(UUID entryId, List<PostingLine> postings) {
        jdbc.batchUpdate(
                """
                insert into posting (entry_id, account_ref, account_id, amount_micros, currency, direction)
                values (?, ?, ?, ?, ?, ?)
                """,
                postings,
                postings.size(),
                (ps, line) -> {
                    ps.setObject(1, entryId);
                    ps.setString(2, line.accountRef());
                    if (line.accountId() == null) {
                        ps.setNull(3, Types.OTHER);
                    } else {
                        ps.setObject(3, line.accountId());
                    }
                    ps.setLong(4, line.amountMicros());
                    ps.setString(5, line.currency());
                    ps.setString(6, line.direction().name());
                });
    }

    /** Balance under the DEBIT-positive convention. */
    public long balance(String accountRef, String currency) {
        Long value = jdbc.queryForObject(
                """
                select coalesce(sum(case when direction = 'DEBIT' then amount_micros
                                         else -amount_micros end), 0)
                  from posting
                 where account_ref = ? and currency = ?
                """,
                Long.class, accountRef, currency);
        return value == null ? 0L : value;
    }

    /**
     * Serialisation point for the check-then-hold sequence. Locks are taken in
     * ascending id order so concurrent requests over overlapping account chains
     * cannot deadlock.
     */
    public void lockAccountsInOrder(Collection<UUID> accountIds) {
        accountIds.stream().sorted().forEach(id ->
                jdbc.queryForList("select id from account where id = ? for update", UUID.class, id));
    }

    /**
     * Balance and posting count for several ledger accounts in one round trip.
     * The count matters: an account that was never funded is absent from the
     * result, which is a different thing from one that was funded and is now at
     * zero. The first is unconstrained; the second is broke.
     */
    public Map<String, RefBalance> balances(Collection<String> accountRefs, String currency) {
        if (accountRefs.isEmpty()) {
            return Map.of();
        }
        Object[] refs = accountRefs.toArray();
        Map<String, RefBalance> result = new HashMap<>();
        jdbc.query("""
                select account_ref,
                       count(*) as posting_count,
                       coalesce(sum(case when direction = 'DEBIT' then amount_micros
                                         else -amount_micros end), 0) as balance_micros
                  from posting
                 where account_ref = any (?) and currency = ?
                 group by account_ref
                """,
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("text", refs));
                    ps.setString(2, currency);
                },
                rs -> {
                    result.put(rs.getString("account_ref"), new RefBalance(
                            rs.getString("account_ref"),
                            rs.getLong("balance_micros"),
                            rs.getLong("posting_count")));
                });
        return result;
    }

    /** Sums every posting in the ledger. Must always be zero. */
    public long globalImbalance() {
        Long value = jdbc.queryForObject(
                """
                select coalesce(sum(case when direction = 'DEBIT' then amount_micros
                                         else -amount_micros end), 0)
                  from posting
                """,
                Long.class);
        return value == null ? 0L : value;
    }

    /** Number of journal entries whose own debits and credits disagree. */
    public int unbalancedEntryCount() {
        Integer value = jdbc.queryForObject(
                """
                select count(*) from (
                    select entry_id
                      from posting
                     group by entry_id, currency
                    having sum(case when direction = 'DEBIT' then amount_micros else 0 end)
                        <> sum(case when direction = 'CREDIT' then amount_micros else 0 end)
                ) bad
                """,
                Integer.class);
        return value == null ? 0 : value;
    }

    public List<LedgerLine> ledgerFor(UUID accountId, int limit, int offset) {
        return jdbc.query(
                """
                select e.id           as entry_id,
                       e.idempotency_key,
                       e.type,
                       e.created_at,
                       p.account_ref,
                       p.amount_micros,
                       p.currency,
                       p.direction
                  from posting p
                  join journal_entry e on e.id = p.entry_id
                 where p.account_id = ?
                 order by e.created_at desc, p.id desc
                 limit ? offset ?
                """,
                (rs, rowNum) -> new LedgerLine(
                        rs.getObject("entry_id", UUID.class),
                        rs.getString("idempotency_key"),
                        rs.getString("type"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("account_ref"),
                        rs.getLong("amount_micros"),
                        rs.getString("currency"),
                        rs.getString("direction")),
                accountId, limit, offset);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("entry metadata is not serialisable", e);
        }
    }

    public record RefBalance(String accountRef, long balanceMicros, long postingCount) {
    }

    public record JournalEntryHeader(UUID id, String idempotencyKey, EntryType type) {
    }

    public record LedgerLine(UUID entryId, String idempotencyKey, String type, java.time.Instant createdAt,
                             String accountRef, long amountMicros, String currency, String direction) {
    }
}
