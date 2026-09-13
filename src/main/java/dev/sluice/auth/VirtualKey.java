package dev.sluice.auth;

import java.time.Instant;
import java.util.UUID;

public record VirtualKey(UUID id, UUID accountId, String keyHash, String keyPrefix,
                         String name, Instant createdAt, Instant revokedAt) {

    public boolean isActive() {
        return revokedAt == null;
    }
}
