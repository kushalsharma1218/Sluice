package dev.sluice.web;

import dev.sluice.account.AccountNotFoundException;
import dev.sluice.auth.UnauthorizedException;
import dev.sluice.budget.BudgetExceededException;
import dev.sluice.budget.SpendRefusal;
import dev.sluice.budget.UnmeteredAccountException;
import dev.sluice.ledger.Micros;
import dev.sluice.ledger.UnbalancedEntryException;
import dev.sluice.pricing.UnknownModelException;
import dev.sluice.proxy.DuplicateRequestException;
import dev.sluice.proxy.InvalidRequestException;
import dev.sluice.proxy.ProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * The one that matters. 402 with the account and limit that tripped, so the
     * failure is diagnosable from the client without access to the admin API.
     */
    @ExceptionHandler(BudgetExceededException.class)
    public ResponseEntity<ApiError> budgetExceeded(BudgetExceededException e) {
        SpendRefusal refusal = e.refusal();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", refusal.kind().name().toLowerCase());
        detail.put("account_id", refusal.accountId().toString());
        detail.put("account_name", refusal.accountName());
        if (refusal.period() != null) {
            detail.put("period", refusal.period().name());
        }
        detail.put("limit", Micros.format(refusal.limitMicros()));
        detail.put("committed", Micros.format(refusal.committedMicros()));
        detail.put("requested", Micros.format(refusal.requestedMicros()));
        detail.put("currency", refusal.currency());

        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(ApiError.of("payment_required", e.getMessage(), detail));
    }

    @ExceptionHandler(UnmeteredAccountException.class)
    public ResponseEntity<ApiError> unmetered(UnmeteredAccountException e) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(ApiError.of("payment_required", e.getMessage(),
                        Map.of("reason", "unmetered_account")));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("authentication_error", e.getMessage()));
    }

    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<ApiError> duplicate(DuplicateRequestException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("invalid_request_error", e.getMessage(),
                        Map.of("reason", "duplicate_request_id")));
    }

    @ExceptionHandler({InvalidRequestException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiError> invalid(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("invalid_request_error", e.getMessage()));
    }

    @ExceptionHandler(UnknownModelException.class)
    public ResponseEntity<ApiError> unknownModel(UnknownModelException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("invalid_request_error", e.getMessage(),
                        Map.of("reason", "no_rate_for_model")));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiError> notFound(AccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("not_found_error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("request is not valid");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("invalid_request_error", message));
    }

    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ApiError> provider(ProviderException e) {
        log.warn("upstream provider call failed: {}", e.toString());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of("api_error", e.getMessage()));
    }

    /**
     * Fail closed. If the ledger cannot be written, the call is refused -- not
     * charging is a bug, letting spend run unmetered is a worse one.
     */
    @ExceptionHandler({DataAccessException.class, UnbalancedEntryException.class})
    public ResponseEntity<ApiError> ledgerUnavailable(RuntimeException e) {
        log.error("ledger unavailable, refusing the call", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("overloaded_error",
                        "the ledger is unavailable; Sluice fails closed rather than "
                                + "allow unmetered spend",
                        Map.of("reason", "ledger_unavailable")));
    }
}
