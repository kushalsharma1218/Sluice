package dev.sluice.ledger;

import java.util.UUID;

/**
 * A reservation the proxy carries through the call and hands back at settle time.
 *
 * @param payerAccountId the account whose credits were debited
 * @param created        false when a hold already existed for this request id
 */
public record HoldTicket(UUID holdId, UUID entryId, UUID payerAccountId, String requestId,
                         long amountMicros, String currency, boolean created) {
}
