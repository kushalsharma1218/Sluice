package dev.sluice.admin;

import dev.sluice.account.AccountType;
import dev.sluice.budget.BudgetPeriod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public final class AdminDtos {

    private AdminDtos() {
    }

    public record CreateAccountRequest(
            @NotBlank String name,
            @NotNull AccountType type,
            UUID parentId,
            String currency) {
    }

    public record CreateKeyRequest(@NotBlank String name) {
    }

    /** Amounts are decimal currency units -- "5.00" means five dollars. */
    public record SetBudgetRequest(
            @NotNull BudgetPeriod period,
            @NotNull @Positive BigDecimal limit,
            Boolean hardStop) {
    }

    public record CreditRequest(
            @NotNull @Positive BigDecimal amount,
            String reference,
            String note) {
    }

    public record AccountResponse(UUID id, UUID parentId, String name, AccountType type,
                                  String currency, String createdAt) {
    }

    public record IssuedKeyResponse(UUID id, UUID accountId, String name, String keyPrefix,
                                    String key, String warning) {
    }

    public record KeyResponse(UUID id, UUID accountId, String name, String keyPrefix,
                              String createdAt, String revokedAt) {
    }

    public record BudgetResponse(UUID accountId, BudgetPeriod period, String limit,
                                 String committed, String remaining, String currency,
                                 boolean hardStop) {
    }

    public record BalanceResponse(UUID accountId, String name, String currency,
                                  String available, String held, String lifetimeUsage,
                                  String payerAccount) {
    }

    public record UsageLine(String requestId, String model, long inputTokens, long outputTokens,
                            long cacheCreationInputTokens, long cacheReadInputTokens,
                            String cost, String currency, boolean streamed, boolean partial,
                            int latencyMs, String at) {
    }

    public record UsageRollupLine(String model, long calls, long inputTokens, long outputTokens,
                                  String cost, String currency) {
    }

    public record UsageResponse(UUID accountId, String from, String to, String totalCost,
                                String currency, java.util.List<UsageRollupLine> byModel,
                                java.util.List<UsageLine> records) {
    }

    public record LedgerLineResponse(String entryId, String idempotencyKey, String type, String at,
                                     String accountRef, String amount, String currency,
                                     String direction) {
    }

    public record EntryResponse(String entryId, String idempotencyKey, String type, boolean created) {
    }
}
