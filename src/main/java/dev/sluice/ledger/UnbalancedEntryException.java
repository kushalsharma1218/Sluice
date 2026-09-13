package dev.sluice.ledger;

/** Thrown before touching the database when an entry's debits != its credits. */
public class UnbalancedEntryException extends RuntimeException {
    public UnbalancedEntryException(String message) {
        super(message);
    }
}
