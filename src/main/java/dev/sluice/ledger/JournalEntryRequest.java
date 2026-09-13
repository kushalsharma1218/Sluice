package dev.sluice.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A request to post one balanced journal entry.
 *
 * <p>{@code idempotencyKey} is backed by a unique constraint: posting the same
 * key twice is a no-op, not a double charge. {@code fxRate} is pinned here at
 * transaction time so historical balances never drift when rates move.
 */
public record JournalEntryRequest(
        String idempotencyKey,
        EntryType type,
        BigDecimal fxRate,
        Map<String, Object> metadata,
        List<PostingLine> postings) {

    public JournalEntryRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        if (postings == null || postings.isEmpty()) {
            throw new IllegalArgumentException("an entry needs at least one posting");
        }
        fxRate = fxRate == null ? BigDecimal.ONE : fxRate;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        postings = List.copyOf(postings);
    }

    public static JournalEntryRequest of(String idempotencyKey, EntryType type,
                                         Map<String, Object> metadata, PostingLine... postings) {
        return new JournalEntryRequest(idempotencyKey, type, BigDecimal.ONE, metadata, List.of(postings));
    }
}
