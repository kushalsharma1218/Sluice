package dev.sluice.budget;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

public enum BudgetPeriod {
    DAILY,
    MONTHLY,
    /** Since the beginning of time -- a lifetime cap rather than a recurring one. */
    TOTAL;

    /** Periods roll over at UTC midnight so a budget means the same thing everywhere. */
    public Instant windowStart(Instant now) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        return switch (this) {
            case DAILY -> utc.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
            case MONTHLY -> utc.toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            case TOTAL -> Instant.EPOCH;
        };
    }
}
