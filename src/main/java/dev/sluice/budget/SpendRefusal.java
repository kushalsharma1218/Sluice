package dev.sluice.budget;

import java.util.UUID;

/**
 * Why a call was refused, in enough detail for the caller to debug it from the
 * client side without access to the admin API.
 *
 * @param kind        which constraint tripped
 * @param accountId   the account in the chain that tripped it -- not necessarily
 *                    the one the key belongs to
 * @param limitMicros the limit, or the balance that was available
 */
public record SpendRefusal(Kind kind, UUID accountId, String accountName, BudgetPeriod period,
                           long limitMicros, long committedMicros, long requestedMicros,
                           String currency) {

    public enum Kind {
        INSUFFICIENT_BALANCE,
        BUDGET_EXCEEDED
    }

    public String message() {
        return switch (kind) {
            case INSUFFICIENT_BALANCE -> "insufficient credit on account '%s': %s %s available, %s %s required"
                    .formatted(accountName,
                            dev.sluice.ledger.Micros.format(limitMicros), currency,
                            dev.sluice.ledger.Micros.format(requestedMicros), currency);
            case BUDGET_EXCEEDED -> "%s budget exceeded on account '%s': limit %s %s, %s %s already committed, %s %s requested"
                    .formatted(period, accountName,
                            dev.sluice.ledger.Micros.format(limitMicros), currency,
                            dev.sluice.ledger.Micros.format(committedMicros), currency,
                            dev.sluice.ledger.Micros.format(requestedMicros), currency);
        };
    }
}
