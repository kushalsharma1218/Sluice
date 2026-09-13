package dev.sluice.budget;

import java.time.Instant;
import java.util.UUID;

/**
 * @param hardStop true refuses the call; false records the breach and lets it
 *                 through, for teams that want the alert without the outage.
 */
public record Budget(UUID id, UUID accountId, BudgetPeriod period, long limitMicros,
                     String currency, boolean hardStop, Instant createdAt) {
}
