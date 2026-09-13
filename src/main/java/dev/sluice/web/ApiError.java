package dev.sluice.web;

import java.util.Map;

/**
 * Errors mirror the provider's own error envelope so a client's existing error
 * handling keeps working, with a {@code sluice} block added for the detail that
 * makes a refusal debuggable from the client side.
 */
public record ApiError(String type, ErrorBody error) {

    public record ErrorBody(String type, String message, Map<String, Object> sluice) {
    }

    public static ApiError of(String errorType, String message) {
        return new ApiError("error", new ErrorBody(errorType, message, null));
    }

    public static ApiError of(String errorType, String message, Map<String, Object> detail) {
        return new ApiError("error", new ErrorBody(errorType, message, detail));
    }
}
