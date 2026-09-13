package dev.sluice.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Conversions between decimal currency amounts and integer micros. */
public final class Micros {

    public static final long PER_UNIT = 1_000_000L;

    private Micros() {
    }

    public static long fromDecimal(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(PER_UNIT))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    public static BigDecimal toDecimal(long micros) {
        return BigDecimal.valueOf(micros, 6);
    }

    /** Human-readable, e.g. {@code 1234567 -> "1.234567"}. */
    public static String format(long micros) {
        return toDecimal(micros).toPlainString();
    }
}
