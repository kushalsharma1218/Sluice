package dev.sluice.admin;

import dev.sluice.account.Account;
import dev.sluice.account.AccountService;
import dev.sluice.admin.AdminDtos.*;
import dev.sluice.auth.IssuedKey;
import dev.sluice.auth.VirtualKey;
import dev.sluice.auth.VirtualKeyService;
import dev.sluice.budget.Budget;
import dev.sluice.budget.BudgetPeriod;
import dev.sluice.budget.BudgetService;
import dev.sluice.budget.SpendGate;
import dev.sluice.ledger.LedgerRepository;
import dev.sluice.ledger.LedgerService;
import dev.sluice.ledger.MeteringService;
import dev.sluice.ledger.Micros;
import dev.sluice.ledger.PostedEntry;
import dev.sluice.usage.UsageExportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AccountService accounts;
    private final VirtualKeyService keys;
    private final BudgetService budgets;
    private final LedgerService ledger;
    private final LedgerRepository ledgerRepository;
    private final MeteringService metering;
    private final UsageExportService usage;
    private final SpendGate gate;

    public AdminController(AccountService accounts, VirtualKeyService keys, BudgetService budgets,
                           LedgerService ledger, LedgerRepository ledgerRepository,
                           MeteringService metering, UsageExportService usage, SpendGate gate) {
        this.accounts = accounts;
        this.keys = keys;
        this.budgets = budgets;
        this.ledger = ledger;
        this.ledgerRepository = ledgerRepository;
        this.metering = metering;
        this.usage = usage;
        this.gate = gate;
    }

    // ---------------------------------------------------------------- accounts

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accounts.create(request.parentId(), request.name(),
                request.type(), request.currency());
        return toResponse(account);
    }

    @GetMapping("/accounts/{id}")
    public AccountResponse getAccount(@PathVariable UUID id) {
        return toResponse(accounts.require(id));
    }

    @GetMapping("/accounts/{id}/children")
    public List<AccountResponse> children(@PathVariable UUID id) {
        accounts.require(id);
        return accounts.children(id).stream().map(AdminController::toResponse).toList();
    }

    // -------------------------------------------------------------------- keys

    @PostMapping("/accounts/{id}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedKeyResponse issueKey(@PathVariable UUID id, @Valid @RequestBody CreateKeyRequest request) {
        accounts.require(id);
        IssuedKey issued = keys.issue(id, request.name());
        return new IssuedKeyResponse(issued.key().id(), id, issued.key().name(),
                issued.key().keyPrefix(), issued.secret(),
                "This is the only time the key is shown. Sluice stores a SHA-256 hash and "
                        + "cannot recover it.");
    }

    @GetMapping("/accounts/{id}/keys")
    public List<KeyResponse> listKeys(@PathVariable UUID id) {
        accounts.require(id);
        return keys.list(id).stream().map(AdminController::toResponse).toList();
    }

    @DeleteMapping("/keys/{keyId}")
    public ResponseEntity<Map<String, Object>> revokeKey(@PathVariable UUID keyId) {
        boolean revoked = keys.revoke(keyId);
        return ResponseEntity.status(revoked ? HttpStatus.OK : HttpStatus.NOT_FOUND)
                .body(Map.of("id", keyId, "revoked", revoked));
    }

    // ----------------------------------------------------------------- credits

    /** Prepaid top-up. Idempotent on {@code reference} when one is supplied. */
    @PostMapping("/accounts/{id}/credits")
    @ResponseStatus(HttpStatus.CREATED)
    public EntryResponse credit(@PathVariable UUID id, @Valid @RequestBody CreditRequest request) {
        Account account = accounts.require(id);
        String reference = request.reference() == null || request.reference().isBlank()
                ? "deposit:" + UUID.randomUUID()
                : "deposit:" + request.reference();

        PostedEntry entry = metering.deposit(id, Micros.fromDecimal(request.amount()),
                account.currency(), reference,
                Map.of("note", request.note() == null ? "" : request.note()));
        gate.refresh(id, account.currency());
        return new EntryResponse(entry.id().toString(), entry.idempotencyKey(),
                entry.type().name(), entry.created());
    }

    // ----------------------------------------------------------------- budgets

    @PutMapping("/accounts/{id}/budget")
    public BudgetResponse setBudget(@PathVariable UUID id, @Valid @RequestBody SetBudgetRequest request) {
        Account account = accounts.require(id);
        Budget budget = budgets.upsert(id, request.period(), Micros.fromDecimal(request.limit()),
                account.currency(), request.hardStop() == null || request.hardStop());
        return toResponse(budget, budgets.committedMicros(id, budget.period(), Instant.now()));
    }

    @GetMapping("/accounts/{id}/budget")
    public List<BudgetResponse> getBudgets(@PathVariable UUID id) {
        accounts.require(id);
        Instant now = Instant.now();
        return budgets.forAccount(id).stream()
                .map(b -> toResponse(b, budgets.committedMicros(id, b.period(), now)))
                .toList();
    }

    @DeleteMapping("/accounts/{id}/budget/{period}")
    public ResponseEntity<Map<String, Object>> deleteBudget(@PathVariable UUID id,
                                                            @PathVariable BudgetPeriod period) {
        boolean deleted = budgets.delete(id, period);
        return ResponseEntity.status(deleted ? HttpStatus.OK : HttpStatus.NOT_FOUND)
                .body(Map.of("accountId", id, "period", period, "deleted", deleted));
    }

    // ---------------------------------------------------------------- balances

    @GetMapping("/accounts/{id}/balance")
    public BalanceResponse balance(@PathVariable UUID id) {
        Account account = accounts.require(id);
        String currency = account.currency();

        // Which account actually pays for this one: nearest funded ancestor.
        Account payer = accounts.chain(id).stream()
                .filter(a -> ledgerRepository
                        .balances(List.of(dev.sluice.ledger.AccountRef.credits(a.id())), currency)
                        .values().stream().anyMatch(b -> b.postingCount() > 0))
                .findFirst()
                .orElse(null);

        return new BalanceResponse(id, account.name(), currency,
                Micros.format(ledger.availableMicros(id, currency)),
                Micros.format(ledger.heldMicros(id, currency)),
                Micros.format(ledger.usageMicros(id, currency)),
                payer == null ? null : payer.id().toString());
    }

    // ------------------------------------------------------------------- usage

    @GetMapping(path = "/accounts/{id}/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    public UsageResponse usage(@PathVariable UUID id,
                               @RequestParam(required = false) String from,
                               @RequestParam(required = false) String to,
                               @RequestParam(defaultValue = "500") int limit) {
        accounts.require(id);
        Instant fromInstant = parseInstant(from, Instant.now().minus(30, ChronoUnit.DAYS));
        Instant toInstant = parseInstant(to, Instant.now().plus(1, ChronoUnit.MINUTES));
        return usage.report(id, fromInstant, toInstant, Math.min(limit, 5000));
    }

    @GetMapping(path = "/accounts/{id}/usage.csv", produces = "text/csv")
    public ResponseEntity<String> usageCsv(@PathVariable UUID id,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(defaultValue = "5000") int limit) {
        accounts.require(id);
        Instant fromInstant = parseInstant(from, Instant.now().minus(30, ChronoUnit.DAYS));
        Instant toInstant = parseInstant(to, Instant.now().plus(1, ChronoUnit.MINUTES));
        String csv = usage.csv(id, fromInstant, toInstant, Math.min(limit, 50_000));
        return ResponseEntity.ok()
                .header("content-disposition",
                        "attachment; filename=\"sluice-usage-%s.csv\"".formatted(id))
                .body(csv);
    }

    // ------------------------------------------------------------------ ledger

    /** Raw journal, for audit. Every posting that touched this account. */
    @GetMapping("/accounts/{id}/ledger")
    public List<LedgerLineResponse> ledger(@PathVariable UUID id,
                                           @RequestParam(defaultValue = "200") int limit,
                                           @RequestParam(defaultValue = "0") int offset) {
        accounts.require(id);
        return ledgerRepository.ledgerFor(id, Math.min(limit, 5000), Math.max(offset, 0)).stream()
                .map(line -> new LedgerLineResponse(
                        line.entryId().toString(), line.idempotencyKey(), line.type(),
                        line.createdAt().toString(), line.accountRef(),
                        Micros.format(line.amountMicros()), line.currency(), line.direction()))
                .toList();
    }

    /** Ledger health: both numbers must be zero, always. */
    @GetMapping("/ledger/integrity")
    public Map<String, Object> integrity() {
        long imbalance = ledgerRepository.globalImbalance();
        int unbalanced = ledgerRepository.unbalancedEntryCount();
        return Map.of(
                "globalImbalanceMicros", imbalance,
                "unbalancedEntries", unbalanced,
                "healthy", imbalance == 0 && unbalanced == 0);
    }

    // ------------------------------------------------------------------ mapping

    private static AccountResponse toResponse(Account account) {
        return new AccountResponse(account.id(), account.parentId(), account.name(),
                account.type(), account.currency(), account.createdAt().toString());
    }

    private static KeyResponse toResponse(VirtualKey key) {
        return new KeyResponse(key.id(), key.accountId(), key.name(), key.keyPrefix(),
                key.createdAt().toString(),
                key.revokedAt() == null ? null : key.revokedAt().toString());
    }

    private static BudgetResponse toResponse(Budget budget, long committed) {
        return new BudgetResponse(budget.accountId(), budget.period(),
                Micros.format(budget.limitMicros()), Micros.format(committed),
                Micros.format(Math.max(0, budget.limitMicros() - committed)),
                budget.currency(), budget.hardStop());
    }

    private static Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return value.length() == 10
                    ? java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                    : Instant.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "could not parse '" + value + "'; use YYYY-MM-DD or an ISO-8601 instant");
        }
    }
}
