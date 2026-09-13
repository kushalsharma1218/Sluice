package dev.sluice.budget;

/** Maps to 402 Payment Required. The provider call is never made. */
public class BudgetExceededException extends RuntimeException {

    private final transient SpendRefusal refusal;

    public BudgetExceededException(SpendRefusal refusal) {
        super(refusal.message());
        this.refusal = refusal;
    }

    public SpendRefusal refusal() {
        return refusal;
    }
}
