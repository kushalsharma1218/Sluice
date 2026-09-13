package dev.sluice.proxy;

/**
 * The same client request id has been seen before. Sluice deliberately does not
 * store response bodies, so it cannot replay the original answer -- and running
 * the call again would spend money the caller did not ask to spend twice.
 */
public class DuplicateRequestException extends RuntimeException {
    public DuplicateRequestException(String message) {
        super(message);
    }
}
