package dev.sluice.usage;

import dev.sluice.account.AccountService;
import dev.sluice.admin.AdminDtos.UsageLine;
import dev.sluice.admin.AdminDtos.UsageResponse;
import dev.sluice.admin.AdminDtos.UsageRollupLine;
import dev.sluice.ledger.Micros;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Per-cost-centre usage, rolled up over the account subtree so a spend figure for
 * an org includes every team and project beneath it.
 */
@Service
public class UsageExportService {

    private final UsageRepository usage;
    private final AccountService accounts;

    public UsageExportService(UsageRepository usage, AccountService accounts) {
        this.usage = usage;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public UsageResponse report(UUID accountId, Instant from, Instant to, int limit) {
        List<UUID> subtree = accounts.subtreeIds(accountId);
        List<UsageRepository.ModelRollup> rollups = usage.rollupByModel(subtree, from, to);
        List<UsageRecord> records = usage.findForAccounts(subtree, from, to, limit, 0);

        long total = rollups.stream().mapToLong(UsageRepository.ModelRollup::costMicros).sum();
        String currency = rollups.isEmpty()
                ? accounts.require(accountId).currency()
                : rollups.get(0).currency();

        return new UsageResponse(accountId, from.toString(), to.toString(),
                Micros.format(total), currency,
                rollups.stream().map(r -> new UsageRollupLine(
                        r.model(), r.calls(), r.inputTokens(), r.outputTokens(),
                        Micros.format(r.costMicros()), r.currency())).toList(),
                records.stream().map(UsageExportService::toLine).toList());
    }

    /** Flat CSV for whoever has to reconcile this against the provider invoice. */
    @Transactional(readOnly = true)
    public String csv(UUID accountId, Instant from, Instant to, int limit) {
        List<UUID> subtree = accounts.subtreeIds(accountId);
        List<UsageRecord> records = usage.findForAccounts(subtree, from, to, limit, 0);

        StringBuilder out = new StringBuilder(records.size() * 128 + 256);
        out.append("timestamp,account_id,request_id,model,input_tokens,output_tokens,")
                .append("cache_creation_input_tokens,cache_read_input_tokens,cost,currency,")
                .append("streamed,partial,latency_ms\n");
        for (UsageRecord r : records) {
            out.append(r.createdAt()).append(',')
                    .append(r.accountId()).append(',')
                    .append(escape(r.requestId())).append(',')
                    .append(escape(r.model())).append(',')
                    .append(r.inputTokens()).append(',')
                    .append(r.outputTokens()).append(',')
                    .append(r.cacheCreationInputTokens()).append(',')
                    .append(r.cacheReadInputTokens()).append(',')
                    .append(Micros.format(r.costMicros())).append(',')
                    .append(r.currency()).append(',')
                    .append(r.streamed()).append(',')
                    .append(r.partial()).append(',')
                    .append(r.latencyMs()).append('\n');
        }
        return out.toString();
    }

    private static UsageLine toLine(UsageRecord r) {
        return new UsageLine(r.requestId(), r.model(), r.inputTokens(), r.outputTokens(),
                r.cacheCreationInputTokens(), r.cacheReadInputTokens(),
                Micros.format(r.costMicros()), r.currency(),
                r.streamed(), r.partial(), r.latencyMs(), r.createdAt().toString());
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
