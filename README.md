# Sluice

**Usage-based billing and spend-control gateway for LLM APIs.**

Point your Anthropic SDK at Sluice instead of `api.anthropic.com`. Every call is
metered against a double-entry ledger, checked against a budget before it leaves,
and refused with `402 Payment Required` when the money is gone.

```python
client = Anthropic(base_url="http://localhost:8080", api_key="sk-sluice-...")
```

That is the entire integration.

---

## Why

Teams share one provider key. The bill is one number, nobody knows who caused it,
and a runaway agent loop can burn a month's budget overnight while the provider
dashboard cheerfully reports it the next morning.

Most tools in this space are **observability** — they show you what happened.
Sluice is **control**: it refuses the call when the budget is gone, and keeps books
that are correct enough to bill from.

**Sluice never stores prompts or completions.** It counts tokens and charges for
them. That is a deliberate non-goal, and it makes the security story trivial.

---

## Architecture

```
  Anthropic SDK
       │  base_url = sluice, api_key = virtual key
       ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │  POST /v1/messages                                              │
  │                                                                 │
  │  1. AUTH        virtual key ──▶ account ──▶ ancestor chain      │
  │  2. ESTIMATE    input tokens + max_tokens × model rate          │
  │  3. PREFLIGHT   Redis: cached available balance      ~1 µs      │
  │  4. RESERVE     Postgres, one transaction:                      │
  │                   SELECT FOR UPDATE over the chain              │
  │                   budget + balance check   ← the authority      │
  │                   HOLD journal entry                            │
  │                     └─ refused ──▶ 402, provider never called   │
  │  5. PROXY       stream bytes through, tapping the usage block   │
  │  6. SETTLE      SETTLE entry for the real cost,                 │
  │                 remainder of the hold released                  │
  └─────────────────────────────────────────────────────────────────┘
       │                    │                      │
       ▼                    ▼                      ▼
   Anthropic            Postgres                 Redis
                    (source of truth)      (cache of available
                                            balance; may be stale,
                                            may be down)
```

**Consistency.** Postgres is the source of truth and is never wrong. Redis caches
available balance so a broke account can be refused in microseconds without a
database round trip; if it is stale, the authoritative check inside the hold
transaction disagrees and Postgres wins. If Redis dies entirely, the gateway
degrades to slower and still correct.

**Availability posture: fail closed.** If the ledger cannot be written, the call is
refused with `503`. Not charging is a bug; letting spend run unmetered is a worse
bug.

---

## The ledger

The ledger is the product. Everything else is plumbing.

### One sign convention

Balance is **`SUM(debits) − SUM(credits)`** for *every* account, so the whole ledger
sums to exactly zero at all times. Four ledger accounts per customer account:

| Ledger account | Meaning |
| --- | --- |
| `credits:<id>` | Spendable prepaid pot |
| `holds:<id>` | Reserved for calls in flight |
| `usage:<id>` | Lifetime consumption |
| `equity:funding` | Contra account prepaid money enters through |

### The hold/settle cycle

You cannot know what a call costs until it finishes, but you must decide whether to
allow it before it starts. So:

```
DEPOSIT  $25    DEBIT  credits:P  25.00     CREDIT equity:funding 25.00

HOLD     $0.05  DEBIT  holds:P     0.05     CREDIT credits:P       0.05
                └─ pessimistic: every input token uncached, a full max_tokens of output

PROXY           ... the call runs, tokens are counted on the way through ...

SETTLE   $0.02  DEBIT  usage:P     0.02     CREDIT holds:P         0.05
                DEBIT  credits:P   0.03
                └─ the real cost is booked, the unused 0.03 goes back

RELEASE         DEBIT  credits:P   0.05     CREDIT holds:P         0.05
                └─ call failed, hold expired, or the provider refused
```

Every entry balances. Every step is idempotent on the client's request id — a
retried settle is a no-op, not a second charge.

### The invariant, enforced by the database

```sql
create constraint trigger posting_balanced
    after insert or update or delete on posting
    deferrable initially deferred
    for each row execute function assert_entry_balanced();
```

Deferred to commit time, so a multi-row insert is legal mid-transaction but an
unbalanced entry can never be committed — by any code path, including one that
bypasses the application entirely. `LedgerConcurrencyTest` fires 1,000 concurrent
transfers and asserts the invariant three ways; another test writes a one-sided
posting with raw SQL and asserts the database rejects it.

`journal_entry.idempotency_key` carries a `UNIQUE` constraint. The database, not
the application, prevents double-posting.

### Money is stored in micros

**This is a deliberate deviation from the PRD's `amount_minor`.** Cents are too
coarse for LLM billing: a 300-token call on a $3/MTok model costs $0.0009, which
rounds to zero. Sluice stores **micros** — 1 currency unit = 1,000,000 micros —
giving six decimal places, with a signed `BIGINT` still spanning ~$9.2 trillion.
All arithmetic is exact integer math with a single half-up division at the end.

### Who pays

Exactly one account pays for a call: **the nearest account in the chain that has
ever been funded**, starting from the key's own account and walking up. Fund an org
and every project beneath it spends from one pot; fund a project and it gets a
ring-fenced pot of its own.

Budgets are separate and additive: **every** ancestor carrying a budget row caps
spend across its whole subtree. A team can therefore run on a budget alone with no
prepaid credit.

A chain with neither credit nor a budget is **refused** (`sluice.budget.allow-unmetered`
overrides this). An account nobody can stop spending is the exact failure this
gateway exists to prevent.

---

## Running it

```bash
export ANTHROPIC_API_KEY=sk-ant-...
docker compose up --build -d
./scripts/seed.sh
```

`seed.sh` creates an org and a team, credits it $25, sets a $5 monthly hard budget,
issues a virtual key, and prints a ready-to-run `curl`. Under five minutes from
clone to a metered call.

### Without Docker

Needs a Postgres and (optionally) a Redis:

```bash
SLUICE_DB_URL=jdbc:postgresql://localhost:5432/sluice \
SLUICE_ADMIN_TOKEN=dev-admin-token \
ANTHROPIC_API_KEY=sk-ant-... \
./mvnw spring-boot:run
```

Flyway builds the schema and seeds the rate table on first start. With
`sluice.cache.enabled=false` Sluice runs single-node with an in-process cache and
no Redis at all.

### Tests

```bash
./mvnw test
```

52 tests, no Docker required — they run against a real embedded Postgres, because
the guarantees under test (a deferred constraint trigger, a unique index,
`SELECT ... FOR UPDATE`) are enforced by Postgres itself. Testing them against an
in-memory database in "PostgreSQL compatibility mode" would test the emulation
rather than the thing that ships.

---

## API

### Proxy

```
POST /v1/messages          Anthropic-compatible, streaming and non-streaming
```

Request and response bodies are relayed **unmodified**. The virtual key is stripped
and the real provider key substituted; every other header the provider understands
is forwarded, so features Sluice has never heard of keep working.

Sluice adds a few response headers:

| Header | Meaning |
| --- | --- |
| `x-sluice-request-id` | The idempotency key for this call's hold/settle cycle |
| `x-sluice-overhead-ms` | Gateway time before the provider was called |
| `x-sluice-cost` | What this call was charged (non-streaming) |
| `x-sluice-input-tokens` / `x-sluice-output-tokens` | The provider's own counts |

Send your own `x-sluice-request-id` (or `idempotency-key`) to get exactly-once
billing across retries. Replaying an id already processed returns `409` — Sluice
does not store response bodies, so it cannot replay the original answer, and
running the call again would spend money you did not ask to spend twice.

### Admin

All under `Authorization: Bearer $SLUICE_ADMIN_TOKEN`. An empty token disables
`/admin/**` outright rather than leaving it unauthenticated.

```
POST   /admin/accounts                        create an org / team / project
GET    /admin/accounts/{id}
POST   /admin/accounts/{id}/keys              issue a virtual key
GET    /admin/accounts/{id}/keys
DELETE /admin/keys/{keyId}                    revoke
POST   /admin/accounts/{id}/credits           prepaid top-up (idempotent on reference)
PUT    /admin/accounts/{id}/budget            set a period limit
GET    /admin/accounts/{id}/budget
GET    /admin/accounts/{id}/balance
GET    /admin/accounts/{id}/usage?from=&to=   rolled up over the subtree
GET    /admin/accounts/{id}/usage.csv         flat export for reconciliation
GET    /admin/accounts/{id}/ledger            raw journal, for audit
GET    /admin/ledger/integrity                both numbers must be zero, always
```

### Refusals are debuggable from the client

```json
{
  "type": "error",
  "error": {
    "type": "payment_required",
    "message": "MONTHLY budget exceeded on account 'platform': limit 5.000000 USD, 5.000000 USD already committed, 0.030000 USD requested",
    "sluice": {
      "reason": "budget_exceeded",
      "account_id": "…", "account_name": "platform",
      "period": "MONTHLY",
      "limit": "5.000000", "committed": "5.000000", "requested": "0.030000",
      "currency": "USD"
    }
  }
}
```

Note `account_name`: the account that tripped is not necessarily the one your key
belongs to. An org-level budget refuses a project's call, and the response says so.

---

## Measured numbers

From `GatewayOverheadTest`, on a 4-core container against embedded Postgres —
modest hardware, so treat these as an upper bound:

| | p50 | p95 | p99 | PRD target |
| --- | --- | --- | --- | --- |
| Gateway overhead before the provider call | 3–5 ms | 6–7 ms | 8–11 ms | < 15 ms |
| Pre-flight budget check (cache path) | < 0.001 ms | — | < 0.001 ms | < 2 ms |

Ranges, not single figures, because they move by a millisecond or two between runs
on shared hardware. Run `./mvnw test -Dtest=GatewayOverheadTest` to get your own.

"Gateway overhead" is auth + parse + estimate + budget check + the hold write,
measured server-side and reported on `x-sluice-overhead-ms`.

**Being straight about the cache:** the budget-check figure above is the in-process
cache used in tests. Its value is on the *refusal* path — a runaway agent's
thousandth rejected call costs a cache read, not a database round trip. The
*accept* path still writes the hold to Postgres synchronously, so Redis does not
speed it up today. Moving settle behind an outbox is the documented next step, and
the PRD is right that it should wait until load testing shows it matters.

`loadtest/budget-check.k6.js` drives both paths against a running stack:

```bash
k6 run -e SLUICE_URL=http://localhost:8080 \
       -e REFUSED_KEY=sk-sluice-... -e FUNDED_KEY=sk-sluice-... \
       loadtest/budget-check.k6.js
```

---

## Design decisions worth arguing about

**Token counts come from the provider, not from us.** Counting streamed output
locally drifts from the provider's own number, and the provider's number is what we
get billed. So Sluice trusts the `usage` block: `message_start` for input (with the
cache read/write split), `message_delta` for the final output count. The cost is
that a single call can overrun its hold — the PRD accepts this, and settlement
charges the real number rather than capping at the reservation, driving the balance
negative if it must. Under-reporting spend would be the worse failure.

**A mid-stream disconnect cancels upstream.** When the client hangs up, Sluice stops
reading, which closes the provider connection and stops generation. Draining it
instead would keep the meter running on tokens nobody will ever see. The call is
settled on what was actually delivered and flagged `partial`.

**Settlement is interrupt-proof.** A client disconnect makes the servlet container
interrupt the request thread, and JDBC over NIO closes its socket the instant it
touches an interrupted thread. Without clearing that flag first, the disconnect that
should settle the call silently fails to bill it. This was a real bug, found by the
disconnect test.

**Holds expire.** A client that dies between hold and settle would otherwise reserve
money forever. A sweeper releases holds past `expires_at`; releasing is the safe
direction to be wrong in, so the TTL (15m) is generous.

**Estimates are pessimistic.** The pre-flight reservation assumes every input token
is uncached and the model emits a full `max_tokens` of output. An estimate that is
too small under-reserves and lets spend escape the budget.

**Rates are versioned in time.** `model_rate` is keyed on `(model, effective_from)`.
Never update a row — insert a new one — so historical usage stays reproducible.
FX rates are pinned on the journal entry at transaction time for the same reason.

---

## Status against the build plan

| Milestone | | Acceptance test |
| --- | --- | --- |
| M0 Passthrough | done | `ProxyIntegrationTest` — SDK-shaped call relayed verbatim, virtual key never forwarded |
| M1 Ledger core | done | `LedgerConcurrencyTest` — 1,000 concurrent transfers, invariant holds |
| M2 Hold and settle | done | `HoldSettleTest`, `ProxyIntegrationTest` — replaying a request id charges exactly once |
| M3 Streaming and failure | done | `ProxyIntegrationTest` — killing the client mid-stream leaves no orphaned hold and charges only delivered tokens; `HoldSweeperTest` |
| M4 Budgets | done | `BudgetEnforcementTest` — a team with a $5 budget is refused at $5.01 and the provider call is never made |
| M5 Redis fast path | done | Cache with Postgres fallback on miss, fail-closed on ledger loss; `GatewayOverheadTest` |
| M6 Ship | done | Dockerfile, compose, k6 script, seed script, this README |

### Not built, on purpose

Web UI, multiple providers, cost-aware model routing, prompt/response storage,
invoicing. All out of scope for v1 per the PRD. The ledger produces the numbers;
billing is someone else's job.

### Known gaps

- **Settle failure is logged, not retried.** If the ledger write fails after a
  response has already been delivered, the call goes unbilled and the sweeper
  releases the hold. An outbox would close this.
- **One currency per account tree.** Postings carry a currency and entries pin an FX
  rate, so the schema is ready for more; the chain check would need conversion in
  the hot path before it is useful.
- **The payer-resolution hint is per-process.** It is a cache of a rarely-changing
  fact, and being wrong costs a cache miss rather than a wrong decision.
