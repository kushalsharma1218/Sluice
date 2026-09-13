package dev.sluice.account;

import java.time.Instant;
import java.util.UUID;

public record Account(UUID id, UUID parentId, String name, AccountType type,
                      String currency, Instant createdAt) {
}
