package dev.sluice.ledger;

import java.util.UUID;

/**
 * @param created false when the idempotency key already existed, i.e. this call
 *                was a replay and nothing new was written.
 */
public record PostedEntry(UUID id, String idempotencyKey, EntryType type, boolean created) {
}
