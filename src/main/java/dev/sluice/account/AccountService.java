package dev.sluice.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository repository;

    public AccountService(AccountRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Account create(UUID parentId, String name, AccountType type, String currency) {
        String resolvedCurrency = currency;
        if (parentId != null) {
            Account parent = require(parentId);
            if (resolvedCurrency == null) {
                resolvedCurrency = parent.currency();
            } else if (!resolvedCurrency.equals(parent.currency())) {
                // v1 keeps one currency per tree: a chain check that had to convert
                // between currencies mid-request would need an FX rate in the hot path.
                throw new IllegalArgumentException(
                        "child currency %s does not match parent currency %s"
                                .formatted(resolvedCurrency, parent.currency()));
            }
        }
        if (resolvedCurrency == null) {
            resolvedCurrency = "USD";
        }
        return repository.insert(UUID.randomUUID(), parentId, name, type, resolvedCurrency);
    }

    public Account require(UUID id) {
        return repository.find(id)
                .orElseThrow(() -> new AccountNotFoundException("no account " + id));
    }

    /** Nearest first: the account itself, then its parent, then the root. */
    public List<Account> chain(UUID id) {
        List<Account> chain = repository.chain(id);
        if (chain.isEmpty()) {
            throw new AccountNotFoundException("no account " + id);
        }
        return chain;
    }

    public List<UUID> subtreeIds(UUID id) {
        return repository.subtreeIds(id);
    }

    public List<Account> children(UUID id) {
        return repository.children(id);
    }
}
