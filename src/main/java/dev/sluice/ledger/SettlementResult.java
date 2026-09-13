package dev.sluice.ledger;

/**
 * @param chargedMicros  what the account was actually billed
 * @param releasedMicros the unused part of the hold returned to the pot
 *                       (negative when the actual cost overran the estimate)
 * @param created        false when this request id was already settled -- a
 *                       retried settle is a no-op, not a second charge
 */
public record SettlementResult(String requestId, long heldMicros, long chargedMicros,
                               long releasedMicros, boolean created) {
}
