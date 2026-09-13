package dev.sluice.ledger;

import java.time.Instant;
import java.util.UUID;

public record Hold(UUID id, UUID entryId, UUID accountId, String requestId,
                   long amountMicros, String currency, HoldStatus status,
                   Instant createdAt, Instant expiresAt, Instant resolvedAt) {
}
