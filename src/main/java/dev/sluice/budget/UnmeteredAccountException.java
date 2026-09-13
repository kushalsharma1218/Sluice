package dev.sluice.budget;

/**
 * Neither prepaid credit nor any budget constrains this account chain, so Sluice
 * has nothing to enforce. Refused by default: an account nobody can stop spending
 * is the exact failure this gateway exists to prevent.
 */
public class UnmeteredAccountException extends RuntimeException {
    public UnmeteredAccountException(String message) {
        super(message);
    }
}
