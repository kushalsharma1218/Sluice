package dev.sluice.budget;

import dev.sluice.account.Account;

/**
 * A cleared pre-flight check.
 *
 * @param payer            the account whose credits will be debited: the nearest
 *                         funded account in the chain, or the key's own account
 *                         when nothing in the chain has ever been funded
 * @param payerFunded      false when no account in the chain holds prepaid credit,
 *                         so this call is constrained by budgets alone
 * @param availableMicros  the payer's spendable balance at check time
 */
public record Authorization(Account payer, boolean payerFunded, long availableMicros) {
}
