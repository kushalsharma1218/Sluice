package dev.sluice.ledger;

public enum EntryType {
    /** Prepaid credit arriving from outside the system. */
    DEPOSIT,
    /** Pre-flight reservation against the estimated worst-case cost of a call. */
    HOLD,
    /** Actual cost booked, remainder of the hold returned. */
    SETTLE,
    /** Hold released in full: the call failed, or the hold expired. */
    RELEASE,
    /** Manual correction. */
    ADJUSTMENT
}
