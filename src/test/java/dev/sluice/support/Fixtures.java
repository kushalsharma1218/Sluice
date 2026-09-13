package dev.sluice.support;

import dev.sluice.account.Account;
import dev.sluice.account.AccountService;
import dev.sluice.account.AccountType;
import dev.sluice.auth.IssuedKey;
import dev.sluice.auth.VirtualKeyService;
import dev.sluice.ledger.MeteringService;
import dev.sluice.ledger.Micros;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Small helpers so tests read as scenarios rather than as setup. */
@Component
public class Fixtures {

    @Autowired
    private AccountService accounts;
    @Autowired
    private VirtualKeyService keys;
    @Autowired
    private MeteringService metering;

    public Account org(String name) {
        return accounts.create(null, name, AccountType.ORG, "USD");
    }

    public Account team(Account parent, String name) {
        return accounts.create(parent.id(), name, AccountType.TEAM, null);
    }

    public Account project(Account parent, String name) {
        return accounts.create(parent.id(), name, AccountType.PROJECT, null);
    }

    /** Credits the account with a decimal amount, e.g. {@code fund(team, "100.00")}. */
    public void fund(Account account, String amount) {
        metering.deposit(account.id(), Micros.fromDecimal(new BigDecimal(amount)),
                account.currency(), "deposit:" + UUID.randomUUID(), Map.of());
    }

    public IssuedKey key(Account account) {
        return keys.issue(account.id(), "test key");
    }
}
