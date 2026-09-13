package dev.sluice.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class VirtualKeyRepository {

    private static final RowMapper<VirtualKey> MAPPER = (rs, rowNum) -> new VirtualKey(
            rs.getObject("id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getString("key_hash"),
            rs.getString("key_prefix"),
            rs.getString("name"),
            rs.getTimestamp("created_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("revoked_at")).map(Timestamp::toInstant).orElse(null));

    private final JdbcTemplate jdbc;

    public VirtualKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public VirtualKey insert(UUID id, UUID accountId, String keyHash, String keyPrefix, String name) {
        jdbc.update("""
                insert into virtual_key (id, account_id, key_hash, key_prefix, name)
                values (?, ?, ?, ?, ?)
                """, id, accountId, keyHash, keyPrefix, name);
        return findById(id).orElseThrow();
    }

    public Optional<VirtualKey> findById(UUID id) {
        return jdbc.query("select * from virtual_key where id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<VirtualKey> findByHash(String keyHash) {
        return jdbc.query("select * from virtual_key where key_hash = ?", MAPPER, keyHash)
                .stream().findFirst();
    }

    public List<VirtualKey> findByAccount(UUID accountId) {
        return jdbc.query("select * from virtual_key where account_id = ? order by created_at",
                MAPPER, accountId);
    }

    public int revoke(UUID id) {
        return jdbc.update("update virtual_key set revoked_at = ? where id = ? and revoked_at is null",
                Timestamp.from(Instant.now()), id);
    }
}
