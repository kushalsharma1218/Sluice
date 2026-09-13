package dev.sluice.ledger;

import java.util.UUID;

/**
 * One side of a journal entry. {@code amountMicros} is always positive; the
 * sign lives in {@code direction}.
 */
public record PostingLine(
        String accountRef,
        UUID accountId,
        long amountMicros,
        String currency,
        Direction direction) {

    public PostingLine {
        if (accountRef == null || accountRef.isBlank()) {
            throw new IllegalArgumentException("accountRef is required");
        }
        if (amountMicros <= 0) {
            throw new IllegalArgumentException("posting amount must be positive, got " + amountMicros);
        }
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter code, got " + currency);
        }
    }

    public static PostingLine debit(String ref, UUID accountId, long micros, String currency) {
        return new PostingLine(ref, accountId, micros, currency, Direction.DEBIT);
    }

    public static PostingLine credit(String ref, UUID accountId, long micros, String currency) {
        return new PostingLine(ref, accountId, micros, currency, Direction.CREDIT);
    }

    /** Contribution to a running balance under the DEBIT-positive convention. */
    public long signedMicros() {
        return direction == Direction.DEBIT ? amountMicros : -amountMicros;
    }
}
