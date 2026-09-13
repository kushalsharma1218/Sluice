package dev.sluice.ledger;

/**
 * Balance is uniformly {@code SUM(DEBIT) - SUM(CREDIT)} for every account in the
 * ledger, so the sum across all accounts is always exactly zero.
 */
public enum Direction {
    DEBIT,
    CREDIT
}
