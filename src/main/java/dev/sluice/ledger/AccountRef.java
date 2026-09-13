package dev.sluice.ledger;

import java.util.UUID;

/**
 * Namespaced ledger account identifiers. These are not the same thing as
 * {@code account} rows: one customer account owns several ledger accounts.
 */
public final class AccountRef {

    /** Contra account that prepaid money enters the system through. */
    public static final String EQUITY_FUNDING = "equity:funding";

    private AccountRef() {
    }

    /** Spendable prepaid pot. Deposits raise it, holds lower it. */
    public static String credits(UUID accountId) {
        return "credits:" + accountId;
    }

    /** Reserved but not yet settled. Non-zero only while calls are in flight. */
    public static String holds(UUID accountId) {
        return "holds:" + accountId;
    }

    /** Lifetime consumption. Only ever grows. */
    public static String usage(UUID accountId) {
        return "usage:" + accountId;
    }
}
