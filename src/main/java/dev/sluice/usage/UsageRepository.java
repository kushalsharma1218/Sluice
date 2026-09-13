package dev.sluice.usage;

import dev.sluice.pricing.TokenUsage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public class UsageRepository {

    private static final RowMapper<UsageRecord> MAPPER = (rs, rowNum) -> new UsageRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("entry_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getString("request_id"),
            rs.getString("model"),
            rs.getLong("input_tokens"),
            rs.getLong("output_tokens"),
            rs.getLong("cache_creation_input_tokens"),
            rs.getLong("cache_read_input_tokens"),
            rs.getLong("cost_micros"),
            rs.getString("currency"),
            rs.getBoolean("streamed"),
            rs.getBoolean("partial"),
            rs.getInt("latency_ms"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public UsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID entryId, UUID accountId, String requestId, String model,
                       TokenUsage usage, long costMicros, String currency,
                       boolean streamed, boolean partial, int latencyMs) {
        jdbc.update("""
                insert into usage_record (id, entry_id, account_id, request_id, model,
                                          input_tokens, output_tokens,
                                          cache_creation_input_tokens, cache_read_input_tokens,
                                          cost_micros, currency, streamed, partial, latency_ms)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (request_id) do nothing
                """,
                id, entryId, accountId, requestId, model,
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens(), usage.cacheReadInputTokens(),
                costMicros, currency, streamed, partial, latencyMs);
    }

    public List<UsageRecord> findForAccounts(Collection<UUID> accountIds, Instant from, Instant to,
                                             int limit, int offset) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        Object[] ids = accountIds.toArray();
        return jdbc.query("""
                select * from usage_record
                 where account_id = any (?) and created_at >= ? and created_at < ?
                 order by created_at desc
                 limit ? offset ?
                """,
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids));
                    ps.setTimestamp(2, Timestamp.from(from));
                    ps.setTimestamp(3, Timestamp.from(to));
                    ps.setInt(4, limit);
                    ps.setInt(5, offset);
                },
                MAPPER);
    }

    public List<ModelRollup> rollupByModel(Collection<UUID> accountIds, Instant from, Instant to) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        Object[] ids = accountIds.toArray();
        return jdbc.query("""
                select model,
                       count(*)                   as calls,
                       sum(input_tokens)          as input_tokens,
                       sum(output_tokens)         as output_tokens,
                       sum(cost_micros)           as cost_micros,
                       min(currency)              as currency
                  from usage_record
                 where account_id = any (?) and created_at >= ? and created_at < ?
                 group by model
                 order by sum(cost_micros) desc
                """,
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids));
                    ps.setTimestamp(2, Timestamp.from(from));
                    ps.setTimestamp(3, Timestamp.from(to));
                },
                (rs, rowNum) -> new ModelRollup(
                        rs.getString("model"),
                        rs.getLong("calls"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getLong("cost_micros"),
                        rs.getString("currency")));
    }

    public record ModelRollup(String model, long calls, long inputTokens, long outputTokens,
                              long costMicros, String currency) {
    }
}
