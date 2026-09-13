package dev.sluice.auth;

import dev.sluice.account.Account;

import java.util.List;
import java.util.UUID;

/**
 * @param account the account the key belongs to
 * @param chain   that account and every ancestor, nearest first
 */
public record Principal(VirtualKey key, Account account, List<Account> chain) {

    public UUID accountId() {
        return account.id();
    }

    public String currency() {
        return account.currency();
    }

    public List<UUID> chainIds() {
        return chain.stream().map(Account::id).toList();
    }
}
