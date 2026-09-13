package dev.sluice.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class HoldRepository {

    private static final RowMapper<Hold> MAPPER = (rs, rowNum) -> new Hold(
            rs.getObject("id", UUID.class),
            rs.getObject("entry_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getString("request_id"),
            rs.getLong("amount_micros"),
            rs.getString("currency"),
            HoldStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("resolved_at")).map(Timestamp::toInstant).orElse(null));

    private final JdbcTemplate jdbc;

    public HoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Hold insert(UUID id, UUID entryId, UUID accountId, String requestId,
                       long amountMicros, String currency, Instant expiresAt) {
        jdbc.update("""
                insert into hold (id, entry_id, account_id, request_id, amount_micros,
                                  currency, status, expires_at)
                values (?, ?, ?, ?, ?, ?, 'OPEN', ?)
                """, id, entryId, accountId, requestId, amountMicros, currency,
                Timestamp.from(expiresAt));
        return findByRequestId(requestId).orElseThrow();
    }

    public Optional<Hold> findByRequestId(String requestId) {
        return jdbc.query("select * from hold where request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /** Locks the hold row so settle and sweep cannot both resolve it. */
    public Optional<Hold> lockByRequestId(String requestId) {
        return jdbc.query("select * from hold where request_id = ? for update", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * @return true when this call is the one that moved the hold out of OPEN.
     *         A second caller gets false and must not post anything.
     */
    public boolean resolve(UUID holdId, HoldStatus status) {
        return jdbc.update("""
                update hold set status = ?, resolved_at = now()
                 where id = ? and status = 'OPEN'
                """, status.name(), holdId) > 0;
    }

    public List<Hold> findExpired(Instant asOf, int limit) {
        return jdbc.query("""
                select * from hold
                 where status = 'OPEN' and expires_at < ?
                 order by expires_at
                 limit ?
                """, MAPPER, Timestamp.from(asOf), limit);
    }

    public long openMicros(UUID accountId, String currency) {
        Long value = jdbc.queryForObject("""
                select coalesce(sum(amount_micros), 0) from hold
                 where account_id = ? and currency = ? and status = 'OPEN'
                """, Long.class, accountId, currency);
        return value == null ? 0L : value;
    }
}
