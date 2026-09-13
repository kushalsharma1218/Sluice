package dev.sluice.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {

    private static final RowMapper<Account> MAPPER = (rs, rowNum) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getObject("parent_id", UUID.class),
            rs.getString("name"),
            AccountType.valueOf(rs.getString("type")),
            rs.getString("currency"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Account insert(UUID id, UUID parentId, String name, AccountType type, String currency) {
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "insert into account (id, parent_id, name, type, currency) values (?, ?, ?, ?, ?)");
            ps.setObject(1, id);
            if (parentId == null) {
                ps.setNull(2, Types.OTHER);
            } else {
                ps.setObject(2, parentId);
            }
            ps.setString(3, name);
            ps.setString(4, type.name());
            ps.setString(5, currency);
            return ps;
        });
        return find(id).orElseThrow();
    }

    public Optional<Account> find(UUID id) {
        return jdbc.query("select * from account where id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * The account and every ancestor up to the root, nearest first. A budget on
     * any of these constrains the call.
     */
    public List<Account> chain(UUID id) {
        return jdbc.query("""
                with recursive ancestry (id, parent_id, name, type, currency, created_at, depth) as (
                    select a.id, a.parent_id, a.name, a.type, a.currency, a.created_at, 0
                      from account a
                     where a.id = ?
                    union all
                    select p.id, p.parent_id, p.name, p.type, p.currency, p.created_at, ancestry.depth + 1
                      from account p
                      join ancestry on p.id = ancestry.parent_id
                )
                select id, parent_id, name, type, currency, created_at from ancestry order by depth
                """, MAPPER, id);
    }

    /** The account and everything beneath it. Usage rolls up over this set. */
    public List<UUID> subtreeIds(UUID id) {
        return jdbc.query("""
                with recursive descendants (id) as (
                    select a.id from account a where a.id = ?
                    union all
                    select c.id from account c join descendants d on c.parent_id = d.id
                )
                select id from descendants
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), id);
    }

    public List<Account> children(UUID id) {
        return jdbc.query("select * from account where parent_id = ? order by name", MAPPER, id);
    }
}
