package dev.sluice.pricing;

public class UnknownModelException extends RuntimeException {
    public UnknownModelException(String model) {
        super("no rate on file for model '" + model + "'");
    }
}
