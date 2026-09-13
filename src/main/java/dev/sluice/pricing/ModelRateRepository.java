package dev.sluice.pricing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ModelRateRepository {

    private static final RowMapper<ModelRate> MAPPER = (rs, rowNum) -> new ModelRate(
            rs.getString("model"),
            rs.getLong("input_per_mtok_micros"),
            rs.getLong("output_per_mtok_micros"),
            rs.getLong("cache_write_per_mtok_micros"),
            rs.getLong("cache_read_per_mtok_micros"),
            rs.getString("currency"),
            rs.getTimestamp("effective_from").toInstant());

    private final JdbcTemplate jdbc;

    public ModelRateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The rate in force for {@code model} at {@code at}. */
    public Optional<ModelRate> rateAt(String model, Instant at) {
        return jdbc.query("""
                select * from model_rate
                 where model = ? and effective_from <= ?
                 order by effective_from desc
                 limit 1
                """, MAPPER, model, Timestamp.from(at)).stream().findFirst();
    }

    public List<ModelRate> currentRates(Instant at) {
        return jdbc.query("""
                select distinct on (model) *
                  from model_rate
                 where effective_from <= ?
                 order by model, effective_from desc
                """, MAPPER, Timestamp.from(at));
    }

    public void upsert(ModelRate rate) {
        jdbc.update("""
                insert into model_rate (model, input_per_mtok_micros, output_per_mtok_micros,
                                        cache_write_per_mtok_micros, cache_read_per_mtok_micros,
                                        currency, effective_from)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (model, effective_from) do update
                   set input_per_mtok_micros       = excluded.input_per_mtok_micros,
                       output_per_mtok_micros      = excluded.output_per_mtok_micros,
                       cache_write_per_mtok_micros = excluded.cache_write_per_mtok_micros,
                       cache_read_per_mtok_micros  = excluded.cache_read_per_mtok_micros,
                       currency                    = excluded.currency
                """,
                rate.model(), rate.inputPerMTokMicros(), rate.outputPerMTokMicros(),
                rate.cacheWritePerMTokMicros(), rate.cacheReadPerMTokMicros(),
                rate.currency(), Timestamp.from(rate.effectiveFrom()));
    }
}
