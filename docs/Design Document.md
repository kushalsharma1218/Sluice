# Sluice: Design Document

**Status:** Draft, implementation in progress
**Author:** Kushal Sharma
**Last updated:** September 2026

---

## 1. Summary

Sluice is a gateway that sits between applications and LLM provider APIs. It meters every call, charges it against a team's prepaid credits, and refuses calls once a budget is exhausted. All money movement is recorded in a double-entry ledger.

The hard problem is that **the cost of a call is unknown until the call finishes**, but the decision to allow it must be made before it starts. This document explains how Sluice handles that gap using a hold-and-settle model, what happens when a call fails partway through, and what the nightly reconciliation job exists to catch.

## 2. Context

Teams that share a provider API key have no per-team attribution, no enforcement, and no way to charge usage back to the teams that caused it. Most existing tools show spend after it happens. Sluice's position is different: it enforces the budget in the request path and keeps books accurate enough to bill from.

That second requirement is what drives most of this design. Showing approximate usage on a dashboard is easy. Producing numbers that a finance team will sign off on is not.

## 3. Goals and non-goals

**Goals**

- Never let a team spend past a hard budget by more than one in-flight call.
- Never charge a request twice, regardless of retries, crashes, or duplicate delivery.
- Never lose a charge. A completed call must always end up in the ledger.
- Keep the added latency on the request path under 15ms at p99.
- Make every balance explainable from the underlying ledger entries.

**Non-goals**

- Storing prompt or response content. Sluice counts and charges; it does not log what was said. This keeps the security story simple and makes Sluice usable by teams who cannot send prompts to a third party.
- Invoicing and payment collection. Sluice produces accurate usage numbers; turning them into invoices is out of scope.
- Supporting every provider in v1. The design is provider-agnostic; the first implementation targets one.

## 4. Core model

### 4.1 Accounts

Each team has two accounts:

- `credits:{team}`: the prepaid balance the team can spend from.
- `usage:{team}`: the accumulated cost of what the team has consumed.

Teams are arranged in a tree (`org → team → project`). A budget can be set at any level, and a call is checked against every ancestor.

### 4.2 Journal entries and postings

```
journal_entry  (id, idempotency_key UNIQUE, type, fx_rate, metadata, created_at)
posting        (id, entry_id, account_id, amount_minor, currency, direction)
```

Every journal entry contains two or more postings. The invariant, which is enforced by tests and by a database check:

> **For every journal entry, total debits equal total credits.**

Balances are derived by summing postings. They are never stored as a mutable column that code updates directly. This is deliberate: a mutable balance can drift from the transactions that supposedly produced it, and when it does there is no record of why. A derived balance cannot drift, because it *is* the transactions.

A balance snapshot table will be added once reads get expensive, but only as a cache that can be rebuilt from postings at any time.

### 4.3 Amounts

All amounts are stored as integers in the currency's minor unit (cents, paise). Floating point is never used for money. Rounding happens exactly once, at the point where a provider's per-token rate is converted to a charge, and the rounding rule is recorded in the entry's metadata.

## 5. The central problem: charging for a call you haven't finished

When a request arrives, Sluice must decide whether to allow it. But the cost depends on how many output tokens the model produces, and that isn't known until the response is complete. For a streaming response, that could be many seconds later.

### 5.1 Alternatives considered

**A. Charge after the call completes.**
Let every call through, then charge the actual cost when it finishes.
*Rejected.* This cannot enforce a budget. Twenty concurrent calls can all pass the check against the same remaining balance, then all charge afterwards, overshooting the budget by twenty calls' worth of spend. A runaway agent loop is exactly the case where this fails worst.

**B. Charge a fixed estimate upfront and never adjust.**
Estimate the maximum cost from the input size and `max_tokens`, charge it immediately, and treat it as final.
*Rejected.* The estimate uses the worst case, so it overcharges almost every call. Teams would see bills that don't match their provider usage, and the numbers would be useless for chargeback.

**C. Hold, then settle. (Chosen.)**
Reserve the worst-case estimate before the call, then settle to the actual cost afterwards and release the difference.

This is the same model card networks use for authorisation and capture. It enforces the budget at the moment of decision, and the final charge is exact.

### 5.2 How hold and settle work

```
1. ESTIMATE   worst case = input tokens × input rate + max_tokens × output rate
2. HOLD       write a journal entry reserving the estimate.
              Reject with 402 if (balance − open holds) < estimate.
3. PROXY      forward the call to the provider and stream the response back.
4. SETTLE     write an entry for the actual cost, and release the unused hold.
```

The available balance is always `balance − sum of open holds`, which is why concurrent calls cannot collectively overspend. Each one reserves its own worst case before starting.

The cost of this approach is that a team near its limit may be refused a call that would actually have fit, because the worst-case estimate didn't. That is the right trade. Refusing a call that would have fit is a minor inconvenience; allowing spend past a hard budget breaks the one promise the product makes.

## 6. Failure handling

This is the section that matters most. Every failure below has the same two requirements: the team must never be charged for something they didn't receive, and a charge for something they did receive must never be lost.

| Failure | What Sluice does |
| --- | --- |
| **Provider returns an error before any output** | Release the hold in full. No charge. |
| **Stream fails partway through** | Settle for the tokens actually delivered, using the usage data received so far. Release the rest. |
| **Client disconnects mid-stream** | The provider may keep generating. Sluice settles for what the provider reports as consumed, since that cost is real. |
| **Gateway crashes between hold and settle** | The hold stays open. A sweeper releases holds past their expiry and flags them for reconciliation. The team is briefly under-credited, never over-charged. |
| **Settle is retried after a timeout** | The settle entry uses the same idempotency key, so the second write is rejected by the database's unique constraint. Charged exactly once. |
| **Client retries the whole request** | The request ID is the idempotency key. A retry with the same ID returns the original result without a second provider call or a second charge. |

### 6.1 Why idempotency lives in the database

It would be simpler to check "have I seen this request before?" in application code. But two instances of the gateway could each check, each see nothing, and each write. The check and the write have to be atomic, and the only place they reliably are is a unique constraint on `idempotency_key`. The database refuses the duplicate, so the application doesn't have to get the race right.

### 6.2 The fail-closed decision

If the ledger is unavailable, Sluice refuses calls rather than letting them through unmetered. Refusing calls is an outage; allowing unmetered spend is a financial error that is much harder to unwind. The fast-path cache (section 7) makes this rare in practice.

## 7. Latency: Redis in front, Postgres as truth

Writing a hold to Postgres on every request adds a few milliseconds. That is acceptable in v1 and keeps the system simple. As load grows, the budget check moves to Redis:

- Redis holds each team's available balance and is decremented atomically on hold.
- Postgres remains the source of truth. Redis is refreshed from it.
- If Redis is unavailable, the check falls back to Postgres. Slower, still correct.

Redis is allowed to be briefly stale. Postgres is never allowed to be wrong.

This ordering is intentional: build the correct version first, measure it, then add the cache. Adding Redis before there is a correct system to compare it against would make every bug harder to find.

## 8. Reconciliation

Sluice counts tokens from the provider's responses. The provider bills from its own internal records. These should always agree, and the reconciliation job exists because sometimes they won't.

Every night, a batch job pulls the provider's usage export and compares it, per team and per day, against the ledger.

**What it catches**

- **Orphaned holds** from gateway crashes that the sweeper released but never settled.
- **Missing settlements**, where a call completed at the provider but no settle entry exists.
- **Rate table errors**, where Sluice charged using a stale price after the provider changed its rates.
- **Token count drift**, where Sluice's count and the provider's count disagree for the same call.
- **Double charges**, which idempotency should prevent, and which reconciliation confirms it did.

**What it does not do**

It does not silently fix anything. Every discrepancy is written as an explicit adjustment entry with a reason, so the ledger shows both the original charge and the correction. An auditor can always see what happened and when.

**What it can't catch**

If Sluice and the provider make the *same* mistake, the numbers agree and nothing is flagged. Reconciliation catches disagreement, not shared error.

## 9. Multi-currency

Provider rates are in USD. Teams may be billed in other currencies. Each journal entry records the exchange rate used at the moment it was written, and historical amounts are never recalculated with today's rate. A balance from March must say the same thing in September.

## 10. Testing strategy

The most important test asserts the ledger invariant rather than checking specific outcomes:

1. Fire thousands of concurrent holds, settles, and reversals at random.
2. Randomly crash and restart the gateway during the run.
3. Assert that every journal entry balances, and that the sum of all postings is zero.

Example-based tests check that the code does what I expected. This test checks that no sequence of operations can break the books, including sequences I didn't think of.

## 11. Open questions

- **Token counting during streaming.** Counting live allows cutting off a call at the budget limit mid-stream; trusting the provider's final usage figure is more accurate but only available at the end. v1 trusts the final figure and accepts that one call may overshoot.
- **Hold expiry length.** Too short and long streaming calls lose their hold before finishing. Too long and a crash ties up budget for hours. Currently set per model based on its maximum response time.
- **Estimate quality.** Using `max_tokens` is safe but conservative. A tighter estimate based on historical output lengths would refuse fewer valid calls, at the cost of occasionally under-reserving.
