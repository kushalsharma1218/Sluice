# High-level design

What the system is made of, how a request moves through it, and what happens when
each piece fails.

---

## 1. The problem shape

Three facts constrain every design decision here:

1. **You cannot know what a call costs until it finishes.** Output tokens are priced,
   and output does not exist yet when the allow/deny decision has to be made.
2. **The decision must happen before the call leaves.** Refusing after the fact is
   what a dashboard does. It does not stop spend.
3. **The numbers have to be good enough to invoice from.** A counter that is
   approximately right is fine for a chart and useless for a bill.

(1) and (2) together force a **reserve-then-reconcile** design: block a pessimistic
estimate up front, run the call, replace the estimate with the truth afterwards.
That is the hold/settle cycle, and it is the same mechanism a card network uses when
a fuel pump authorises $100 and settles $43.

(3) forces **double-entry bookkeeping** rather than a `spend_total` column. A counter
cannot tell you *why* a balance is what it is, cannot be audited, and quietly absorbs
every bug that ever touches it.

---

## 2. System context

```mermaid
flowchart LR
    subgraph clients[" "]
        SDK["Anthropic SDK<br/><i>base_url = sluice</i>"]
        OPS["Operator<br/><i>admin API / seed script</i>"]
    end

    SLUICE["<b>Sluice</b><br/>Spring Boot 3.5 · Java 21<br/>virtual threads"]

    PG[("<b>Postgres</b><br/>source of truth<br/><i>never wrong</i>")]
    RD[("<b>Redis</b><br/>balance cache<br/><i>may be stale or down</i>")]
    API["Anthropic<br/>Messages API"]

    SDK -->|"POST /v1/messages<br/>x-api-key: sk-sluice-…"| SLUICE
    OPS -->|"/admin/**<br/>Bearer token"| SLUICE
    SLUICE -->|"ledger reads + writes"| PG
    SLUICE <-->|"available balance"| RD
    SLUICE -->|"x-api-key: real key"| API

    style SLUICE fill:#1f6feb,stroke:#1f6feb,color:#fff
    style PG fill:#0d1117,stroke:#30363d,color:#fff
    style RD fill:#0d1117,stroke:#30363d,color:#fff
```

Integration cost for the caller is one line:

```python
client = Anthropic(base_url="https://sluice.internal", api_key="sk-sluice-...")
```

No SDK fork, no wrapper library, no code change at the call site. That constraint is
what makes the proxy shape the right answer instead of a client-side SDK.

---

## 3. Internal components

```mermaid
flowchart TB
    subgraph edge["Edge"]
        MC["MessagesController<br/><i>the only proxied route</i>"]
        AC["AdminController"]
        AF["AdminAuthFilter"]
        EH["GlobalExceptionHandler<br/><i>402 / 409 / 503 shaping</i>"]
    end

    subgraph decide["Decision"]
        PR["PrincipalResolver<br/><i>key → account chain</i>"]
        TE["TokenEstimator"]
        CC["CostCalculator"]
        SG["<b>SpendGate</b><br/><i>lock + authorize + hold</i>"]
        BS["BudgetService<br/><i>who pays, what caps</i>"]
        BC["BalanceCache<br/><i>Redis | in-memory</i>"]
    end

    subgraph money["Ledger"]
        MS["<b>MeteringService</b><br/><i>hold · settle · release</i>"]
        LS["LedgerService<br/><i>the only writer</i>"]
        HR["HoldRepository"]
        UR["UsageRepository"]
    end

    subgraph io["I/O"]
        AN["AnthropicClient"]
        UT["UsageTap<br/><i>SSE token counter</i>"]
        ST["Settler<br/><i>interrupt-proof close-out</i>"]
    end

    subgraph bg["Background"]
        HS["HoldSweeper<br/><i>expired holds</i>"]
        BR["BalanceReconciler<br/><i>cache drift</i>"]
    end

    MC --> PR & TE & CC & SG & AN & ST
    AC --> AF
    SG --> BS --> BC
    SG --> MS --> LS
    MS --> HR & UR
    AN --> UT --> ST --> MS
    HS --> MS
    BR --> BC

    style SG fill:#1f6feb,stroke:#1f6feb,color:#fff
    style MS fill:#1f6feb,stroke:#1f6feb,color:#fff
```

Two components carry the weight. **`SpendGate`** decides whether a call may proceed
and who pays for it. **`MeteringService`** is the only thing that moves money.
Everything else feeds one of those two.

---

## 4. Request lifecycle — the allowed path

```mermaid
sequenceDiagram
    autonumber
    participant C as Client SDK
    participant S as Sluice
    participant R as Redis
    participant P as Postgres
    participant A as Anthropic

    C->>S: POST /v1/messages (sk-sluice-…)

    rect rgb(240, 246, 252)
    Note over S,P: Decide — no provider call yet
    S->>P: resolve key hash → account + ancestor chain
    S->>S: estimate = input_est × in_rate + max_tokens × out_rate
    S->>R: cached available balance?
    R-->>S: hit / miss (advisory only)

    Note over S,P: One transaction, authoritative
    S->>P: BEGIN
    S->>P: SELECT … FOR UPDATE over chain (sorted by id)
    S->>P: balance + budget check
    S->>P: INSERT journal_entry 'hold:<reqId>' + 2 postings
    S->>P: INSERT hold (OPEN, expires_at)
    S->>P: COMMIT
    end

    S->>A: POST /v1/messages (real key)

    rect rgb(240, 253, 244)
    Note over S,A: Relay — bytes out as they arrive
    A-->>S: message_start (input_tokens)
    S-->>C: message_start
    A-->>S: content_block_delta …
    S-->>C: content_block_delta …
    A-->>S: message_delta (output_tokens) ← authoritative
    S-->>C: message_delta
    end

    rect rgb(255, 247, 237)
    Note over S,P: Settle — after the client already has the answer
    S->>P: INSERT journal_entry 'settle:<reqId>' + 3 postings
    S->>P: UPDATE hold → SETTLED
    S->>P: INSERT usage_record
    S->>R: adjust cached balance
    end
```

Note where the transaction boundary sits: **the hold commits before the provider is
contacted.** If the process dies at any point after that, the money is already
reserved and the sweeper will reclaim it. The failure mode is a temporarily
over-reserved account, never an unmetered call.

---

## 5. Request lifecycle — the refused path

This is the path the product exists for.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client SDK
    participant S as Sluice
    participant R as Redis
    participant P as Postgres
    participant A as Anthropic

    C->>S: POST /v1/messages
    S->>R: cached available balance?
    R-->>S: 0.00 — insufficient

    Note over S,R: Cache says no, but a stale cache<br/>must never produce a false 402
    S->>P: BEGIN · lock chain · authorize
    P-->>S: BudgetExceededException
    S->>P: ROLLBACK

    S--xA: never called
    S-->>C: 402 Payment Required<br/>{ account_name, period, limit,<br/>  committed, requested }

    Note over C,A: No hold written. No tokens bought.<br/>No provider round trip.
```

The refusal names **which account in the chain** tripped and by how much, so a
developer can diagnose it from the client side without admin access. An org-level
budget refusing a project's call says `acme`, not the project's name.

---

## 6. Who pays, and what constrains a call

Two independent constraints. Either can refuse.

```mermaid
flowchart TB
    K["Virtual key on<br/><b>project: search</b>"] --> CH

    subgraph CH["Account chain, resolved nearest-first"]
        direction TB
        P1["project: search"]
        P2["team: platform"]
        P3["org: acme"]
        P1 --> P2 --> P3
    end

    CH --> BAL & BUD

    subgraph BAL["① Balance — exactly one payer"]
        B1["Walk up. First account<br/>ever funded pays."]
        B2["Its prepaid credit must<br/>cover the estimate."]
        B1 --> B2
    end

    subgraph BUD["② Budget — all ancestors"]
        U1["Every account in the chain<br/>carrying a budget row"]
        U2["caps spend across its<br/><b>entire subtree</b> for the period."]
        U1 --> U2
    end

    BAL --> D{Both pass?}
    BUD --> D
    D -->|yes| GO["hold → proxy"]
    D -->|no| NO["402, provider untouched"]

    style NO fill:#da3633,stroke:#da3633,color:#fff
    style GO fill:#238636,stroke:#238636,color:#fff
```

**Balance — one payer.** Walking up and stopping at the first funded account means
funding is a placement decision with real meaning:

- Fund the **org** → every project beneath it draws from one shared pot.
- Fund a **project** → it gets a ring-fenced pot nothing else can touch.

Only that account's `credits:` is debited, so a balance never has two competing
interpretations.

**Budget — all ancestors.** Budgets are additive and independent of funding. Spend is
summed across the whole subtree beneath each budgeted account, so a team budget
counts every project under it. This is what lets an unfunded team run on a pure
budget with no prepaid credit at all.

**Neither present?** Refused. An account with no credit and no budget anywhere in its
chain is one nobody can stop spending — the exact failure this gateway exists to
prevent. Override with `sluice.budget.allow-unmetered=true` if you genuinely want it.

---

## 7. Consistency model

> **Postgres is the source of truth and is never wrong. Redis is a cache and may be
> stale, empty, or entirely down.**

```mermaid
flowchart LR
    subgraph fast["Pre-flight · microseconds · advisory"]
        F1["Redis GET"]
        F2{"affordable?"}
        F1 --> F2
        F2 -->|"yes"| F3["proceed"]
        F2 -->|"no"| F4["still confirm<br/>against Postgres"]
        F2 -->|"miss / down"| F4
    end

    subgraph slow["Hold transaction · authoritative"]
        S1["row locks over chain"]
        S2["balance + budget from postings"]
        S3["write HOLD"]
        S1 --> S2 --> S3
    end

    F3 --> S1
    F4 --> S1

    style slow fill:#161b22,stroke:#30363d,color:#fff
```

The cache can only ever make things **slower**, never wrong:

| Cache state | Effect |
| --- | --- |
| Correct | Cheap early rejection; Postgres agrees. |
| Stale **high** | Pre-flight says yes, the hold transaction refuses. Correct answer, one extra round trip. |
| Stale **low** | Pre-flight says no — so Sluice confirms against Postgres before refusing. No false `402`. |
| Missing | Falls through to Postgres and repopulates. |
| Redis down | Every operation swallows the error, marks the cache unhealthy, and falls through. Correctness unchanged. |

`BalanceReconciler` re-derives cached balances from `posting` on a timer, bounding
drift from lost increments or a second gateway instance.

**Honest note on what the cache buys.** It makes the *refusal* path cheap — a runaway
agent's thousandth rejected call costs a Redis `GET`, not a database round trip. It
does **not** speed up the accept path, which still writes the hold to Postgres
synchronously. Moving settlement behind an outbox is the next step, and is worth
doing when load testing shows it matters, not before.

---

## 8. Failure modes

Availability posture: **fail closed.** Not charging for a call is a bug. Letting
spend run unmetered is a worse bug.

| Failure | Behaviour | Money impact |
| --- | --- | --- |
| **Postgres unreachable** | `503`, provider never called | None. Nothing spent. |
| **Redis unreachable** | Cache marked unhealthy, all checks fall through to Postgres | None. Slower only. |
| **Provider returns 4xx/5xx** | Hold released in full, provider's error relayed verbatim | Nothing charged. |
| **Provider times out** | Hold released, `502` | Nothing charged. |
| **Client disconnects mid-stream** | Upstream cancelled → generation stops. Settled on delivered tokens, `partial=true` | Charged for what was delivered. |
| **Sluice killed mid-call** | Hold is already committed; sweeper releases it after `expires_at` | Reserved briefly, then returned. The call itself goes unbilled. |
| **Settle write fails** | Logged at `ERROR` with full replay detail; sweeper releases the hold | **Call goes unbilled.** The known gap — an outbox would close it. |
| **Actual cost > estimate** | Charged in full; balance may go negative | Correct. Under-reporting spend is the worse failure. |
| **Unknown model** | Hold released, nothing charged, loud log | Unbilled rather than guessed. Add a `model_rate` row. |

---

## 9. Deployment

```mermaid
flowchart TB
    subgraph compose["docker compose up"]
        direction LR
        APP["<b>sluice</b><br/>:8080<br/><i>stateless</i>"]
        PG[("postgres:17<br/>:5432<br/><i>volume</i>")]
        RD[("redis:7<br/>:6379<br/><i>no persistence</i>")]
        APP --> PG
        APP --> RD
    end

    FW["Flyway<br/><i>migrations on boot</i>"] -.-> PG
    APP -.->|"/actuator/health"| HC["healthcheck"]

    style APP fill:#1f6feb,stroke:#1f6feb,color:#fff
```

**Sluice holds no state.** Every durable fact lives in Postgres; the only in-process
state is the balance cache and a payer-resolution hint, both of which are caches that
rebuild themselves. Horizontal scaling is therefore just more containers behind a
load balancer — correctness comes from Postgres row locks, not from there being one
instance.

Redis runs with persistence disabled on purpose. It is a cache; losing it costs
latency, never correctness.

### Scaling characteristics

| Dimension | Behaviour |
| --- | --- |
| Gateway instances | Horizontal. Stateless. Row locks serialise across instances. |
| Concurrent calls per instance | Virtual threads — one blocking relay per call costs almost nothing. |
| Lock contention | Scoped to an account chain. Unrelated accounts never contend; heavy traffic on **one** account serialises at its hold write. |
| `posting` growth | Grows forever by design (it is an audit log). Balances are derived; add periodic snapshots when the derivation gets slow, not before. |
| Redis | Optional. Absent, Sluice runs single-node with an in-process cache. |

---

## 10. What is deliberately not here

| Not built | Why |
| --- | --- |
| **Prompt / completion storage** | Sluice counts and charges. Never seeing message bodies keeps the security story trivial and is a selling point, not a limitation. |
| **Web UI** | The admin API plus `scripts/seed.sh` covers v1. A UI is presentation over an API that already exists. |
| **Multiple providers** | Designed for — `AnthropicClient` is the only provider-aware class, and `model_rate` is keyed by model string. Ships with Anthropic only. |
| **Cost-aware model routing** | The interesting follow-up: with per-model rates and a live ledger, routing on price becomes a policy layer on top of what exists. |
| **Invoicing and payment collection** | The ledger produces numbers correct enough to bill from. Sending the bill is a different product. |

---

Next: **[LLD.md](LLD.md)** for the algorithms and class-level design ·
**[SCHEMA.md](SCHEMA.md)** for the data model.
