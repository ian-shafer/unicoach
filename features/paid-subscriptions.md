# Feature Brief — Cost-Metered Paid Subscriptions (iOS)

> **Status:** design brief. This document is the north-star vision that drives
> RFC generation (via `rfc-pipeline`); it is **not** an RFC and carries no
> implementation authority. The per-RFC files under `rfc/`, and ultimately the
> code + applied migrations, are the source of truth. The RFC index below is a
> **coordination aid** that will drift — trust the code, not this table.

## What this is

Coaching costs real money: every chat turn, extraction pass, synthesis pass, and
fit-lens pass calls the Anthropic API and bills Anthropic. Today that spend is
unbounded per student. This feature makes **provider cost the thing a student
pays for**: each student gets a fixed dollar allowance of free Anthropic usage,
and past it must hold an auto-renewable iOS subscription whose price funds a
recurring dollar budget of further usage.

The entitlement is **not** feature-gated (some screens locked, others open). It
is **cost-metered**: a student is entitled to coaching exactly as long as their
attributed Anthropic spend sits under their current limit —

- **Free tier:** lifetime Anthropic cost `< $N`. Exhaust it → blocked until they
  subscribe.
- **Subscribed:** current-period Anthropic cost `< y × price`, where `price` is
  the plan they bought through the App Store and `y` is a configured fraction
  (`0 < y < 1`). A `$10/mo` plan at `y = 0.5` funds `$5` of Anthropic cost per
  period. Exhaust it → blocked until the period renews (or they upgrade).

The margin lives in `1 − y` minus Apple's commission. `y` is a business lever,
never a constant.

## The model

Three parts: a **cost ledger** (what has this student spent), an **entitlement**
(what is this student allowed to spend), and a **gate** (refuse the next call
when spent ≥ allowed).

### Cost ledger — extends the RFC 106 call log

The metering substrate already exists. RFC 106 landed a provider-agnostic call
log — `llm_requests` (carries `model_requested`), `llm_responses` (carries
`model_resolved` + `input_tokens` / `output_tokens` / `cache_read_tokens` /
`cache_write_tokens` + `outcome`), both append-only — and a per-student
token-attribution view across all four call owners (`convo_requests → convos`,
`extraction_runs`, `synthesis_runs`, `fit_lens_runs`); RFC 108 has since lowered
that view to the per-call `student_llm_cost` spine read by `StudentLlmCostDao`.
A standing invariant already guarantees every token on a student is recorded at
the call boundary, including failed/retried calls.

What is missing is **dollars** and **a time window**:

- **Per-call cost.** A `model → price` table (per-MTok rates for input, output,
  cache-read, cache-write) turns the four token counts into a dollar cost. Cost
  is computed and frozen **at the LlmCallLog write boundary**, at the price in
  effect then, so historical cost is immutable even as prices change. It is a
  new column on the response row (or its sibling), not a second ledger — the log
  stays the single source of spend truth.
- **Period-windowed aggregation.** The original view was lifetime-cumulative.
  Budget enforcement needs both: **lifetime** cost (free-tier meter) and cost
  **within `[period_start, period_end)`** (subscription meter), summed from
  `llm_responses.created_at`.

This completes the `token-ledger` node long planned in
[`coaching-memory.md`](coaching-memory.md): the token log gains a cost dimension
and a windowed read.

### Entitlement — server-authoritative, Apple owns the money

Apple (StoreKit 2 auto-renewable subscriptions) is the **payment** authority;
the backend is the **entitlement** authority. The client is never trusted for
budget or subscription state.

- **Purchase (iOS).** StoreKit 2 `Product.purchase()` yields a signed
  transaction (JWS). The app posts the JWS to the backend, which verifies it
  against the **App Store Server API**, resolves `productId` → plan → budget,
  and records the subscription.
- **Live state (server).** **App Store Server Notifications V2** — Apple's
  signed webhook — keeps state current without polling: renewals, cancellations,
  refunds, grace period, billing retry, revocation. It is an Apple-signed,
  Apple-callable endpoint (no session cookie); its JWS signature is the
  authentication. Receipt enqueues onto the existing queue and the worker
  applies it, per `ASYNC_WORK.md` (enqueue inside the request transaction).
- **State of record.** A `subscriptions` table keyed to the student (bound via
  Apple's `originalTransactionId`) holds `product_id`, `status` (active / grace
  / expired / revoked), and the current period window. This row, plus the cost
  ledger, is the entire entitlement input.

### Gate — one check, two behaviors

A single `BudgetService.entitlement(studentId)` answers "may this student incur
another Anthropic call?" from the two meters:

- **not subscribed** → entitled iff `lifetime_cost < FREE_ALLOWANCE_USD`
- **subscribed** → entitled iff `period_cost < y × plan_price`

It is checked at **every** LLM call boundary. The behavior when blocked differs
by caller:

- **Live chat** (request coroutine, per RFC 43) → the turn is refused with an
  UPPERCASE limit error (e.g. `COACHING_BUDGET_EXHAUSTED`, HTTP 402), surfaced
  by iOS as the paywall / "budget spent, resets on ⟨date⟩" screen.
- **Background passes** (extraction / synthesis / fit-lens, in worker/cron) →
  the pass is **skipped**, not errored: no budget, no spend.

## Design principles

1. **The server is the entitlement authority; the client is never trusted.**
   Budget and subscription state are computed server-side from the ledger and
   the verified Apple record. A jailbroken or patched app cannot grant itself
   coaching.

2. **Meter real dollars internally; present an abstraction externally.** The
   backend meters Anthropic cost to the cent. The student sees an abstract
   "coaching used" bar (percentage of allowance), never provider dollars,
   tokens, or margin. This decouples the UX from Anthropic's cost structure —
   `y` and the price table can be retuned without changing what a student sees.

3. **Check at the call boundary; tolerate bounded single-call overshoot.** Cost
   is unknown until a call returns, so the gate is a **pre-call** check: block
   if already at/over limit. A student at 99% can start one more turn that
   overshoots — overshoot is bounded by one call's cost and is the accepted
   price of a hard block on a streaming, pay-per-completion API.

4. **Apple owns the money; we own the budget.** Apple processes the payment and
   sets the localized price; the backend maps the purchased `productId` to a
   monthly USD budget via `y × price`. Plan-to-budget is a server-side mapping,
   not a client claim.

5. **Every threshold is configuration, not a constant.** `FREE_ALLOWANCE_USD`
   (`$N`), `BUDGET_RATIO` (`y`), and the `model → price` table are config. For
   positive margin, `y < 1 − Apple's commission` (15% under the Small Business
   Program, else 30%) minus fixed overhead.

6. **One ledger, not two.** Cost is a derived extension of the RFC 106 call log,
   read through the same student attribution. There is no parallel token
   counter; the existing capture-at-boundary invariant already guarantees no
   call bills a student without a recorded row.

## Key decisions

- **Cost-metered, not feature-gated.** The paywall meters provider spend, not
  screens. This aligns the price a student pays with the cost they impose and
  keeps the end-to-end product intact (PRODUCT.md: no single stage singled out)
  — nothing in the journey is locked; usage is.

- **Hard block at the cap.** At the limit the coaching call is refused, not
  degraded. Simplest and most predictable margin. Model-downgrade-near-cap (drop
  to a cheaper model instead of blocking) is deliberately deferred — see Open
  forks.

- **All attributed spend counts, first-come.** Chat, extraction, synthesis, and
  fit-lens all draw from the same budget, in the order they run, with no
  prioritization. A background pass can consume budget before the student opens
  the app. Chosen for the simplest, most honest accounting; the starvation risk
  is an accepted tradeoff to revisit (Open forks).

- **Free allowance is a lifetime credit, not a monthly free tier.** `$N` is
  spent once, cumulatively, then the student must subscribe. There is no
  recurring free budget.

- **StoreKit 2, not Stripe or a card form.** Apple mandates In-App Purchase for
  digital services consumed in the app; a non-IAP payment rail would be
  rejected. StoreKit 2 (signed transactions, `Transaction.currentEntitlements`,
  async `Product.purchase()`) is the client rail; the App Store Server API +
  Notifications V2 are the server rails.

- **Subscription state rides the queue.** The Notifications V2 webhook enqueues
  and the worker applies, reusing the queue's retry/dead-letter guarantees
  rather than mutating entitlement inline on Apple's request.

- **The gate can ship before the store.** `budget-gate` is a testable vertical
  slice on its own: it blocks every student at `$N` with no way to subscribe
  yet. StoreKit + subscriptions unblock the block.

## Open forks

- **Free-allowance abuse (Sybil).** A cost-real free tier means every fresh
  verified account is `$N` of real money, and email verification alone is weak —
  one user can mint many emails. **Accepted, addressed reactively:** ship
  without anti-abuse and act only if abuse appears in practice. Mitigations then
  available without redesign — bind the free grant to a device via Apple
  **DeviceCheck / App Attest**, require the App Store account token, lower `$N`,
  or require a payment method up front — because the grant is metered per
  student server-side, so a binding is additive.

- **First-come starvation.** Because background passes draw first-come from the
  same budget, a student's monthly synthesis/fit-lens can exhaust the budget
  before any chat. Accepted for now; revisit with a priority policy (protect
  live chat, skip background first) if it bites in practice.

- **Meter read cost.** The budget check sums the ledger (a join across four call
  owners) on every LLM call. Fine at current scale. If it becomes hot,
  materialize a per-student running balance updated at the call boundary. YAGNI
  until measured.

- **Degrade-instead-of-block.** Near-cap model downgrade (Opus/Sonnet → Haiku)
  preserves the coaching relationship past the limit at lower cost. Deferred
  behind hard-block; the north-star may be staged (degrade, then block).

- **Tiers and the upsell ladder.** MVP can ship one plan. Multiple price tiers
  (bigger price → bigger budget) turn "budget spent" into an upgrade prompt, and
  require handling StoreKit plan upgrade/downgrade and mid-period proration.

- **Cross-platform entitlement.** Purchase is iOS-only (StoreKit). Entitlement
  is server-side, so a future web client inherits it — but Apple's anti-steering
  rules constrain how other purchase paths may be surfaced in-app.

- **Usage-bar semantics.** The abstract bar needs a defined denominator (percent
  of `$N`, or of `y × price`), a reset-date display tied to the Apple period,
  and a rule for what it shows a never-subscribed vs. lapsed student.

## RFCs (living index)

Status axis:
`planned → drafting → designed (RFC committed) → implemented (code merged)`.
Slugs are the stable handles; `rfc/NN-*.md` numbers are assigned at design time
and backfilled here.

| slug                  | description                                                                                                                                                                                    | status      | rfc |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------- | --- |
| `llm-cost-ledger`     | extend the RFC 106 call log with per-call dollar cost (`model → price` table, cost frozen at the write boundary) and a period-windowed per-student cost read; completes `token-ledger`         | implemented | 108 |
| `budget-gate`         | `BudgetService.entitlement(studentId)` at every LLM call boundary; free-allowance (`$N` lifetime) logic; hard block — chat → `coaching_budget_exhausted` (402), background passes → named skip | implemented | 109 |
| `subscriptions-apple` | StoreKit 2 purchase + App Store Server API verification + `subscriptions` table + Notifications V2 webhook (queue-processed) + `productId → y × price` budget mapping                          | planned     | —   |
| `paywall-ios`         | iOS paywall + block screen + subscribe flow + Restore Purchases + `UserAuthState` gate; abstract "coaching used" usage bar (percentage, never dollars)                                         | planned     | —   |

## Dependency tree

```
llm-cost-ledger ──► budget-gate ──► subscriptions-apple ──► paywall-ios
  (cost + window)    (free tier,      (StoreKit + webhook     (purchase UI,
                      hard block)      + state of record)      usage bar)
```

- `llm-cost-ledger` is the root: pure backend, no Apple, no iOS. Independently
  valuable (per-student `$` observability) and testable against the existing
  log.
- `budget-gate` enforces on top of the ledger. Ships free-tier-only — every
  student blocks at `$N` with no purchase path — which is a complete, testable
  slice.
- `subscriptions-apple` unblocks the block: it introduces the store, the
  verified subscription record, the live webhook, and the plan → budget mapping
  the gate reads. The heaviest node; may split into a server track
  (verification + webhook
  - state) and the iOS StoreKit purchase call.
- `paywall-ios` is the student-facing surface: the block/paywall screen, the
  subscribe and restore flows, and the abstract usage bar.

## Sequencing

1. **`llm-cost-ledger`** — cost the log, window the read. No user-visible
   change; unlocks everything.
2. **`budget-gate`** — free allowance + hard block at every call boundary. The
   product now enforces a limit, with subscribing as the only (not-yet-built)
   way past it.
3. **`subscriptions-apple`** — StoreKit purchase, server verification, the
   Notifications V2 webhook, and the subscription state that feeds the gate's
   subscribed branch.
4. **`paywall-ios`** — the paywall, subscribe/restore UX, and usage bar.

Ship without anti-abuse (see Open forks); a device-binding or payment-up-front
mitigation is added reactively only if free-allowance abuse appears.

## Process — running the RFCs

Run **one `/rfc-pipeline` per node, in Sequencing order, each started only after
the previous one has landed** on `main` — this is a strict dependency chain, so
every node is designed and implemented against its predecessor's landed code. A
node may split into more than one RFC during design (e.g. `subscriptions-apple`
into verify-path + Notifications-V2 webhook, or `paywall-ios` into a
StoreKit-purchase slice + the paywall UI); run any such split in dependency
order.

**Each run keeps this section current.** Every kickoff prompt below ends with a
standing instruction: when the node lands, the run returns to this file, marks
the node implemented in the living index (with its RFC number), and revises the
still-pending prompts to match what actually landed — renamed tables / columns /
endpoints, a node that split into multiple RFCs, or any scope shift. The prompts
below are the **starting** text; treat a later prompt as authoritative only
after the runs before it have refreshed it.

### Kickoff prompts

**1 — `llm-cost-ledger`** (backend):

```
/rfc-pipeline

Feature: cost-metered paid subscriptions.
North-star brief: features/paid-subscriptions.md — read it first; it is the
source of intent (model, design principles, node breakdown).

Design and implement one node: llm-cost-ledger.
Scope: extend the RFC 106 provider-agnostic LLM call log with per-call dollar
cost and a period-windowed per-student cost read — a model→price table (per-MTok
input/output/cache-read/cache-write); per-call cost computed and frozen at the
LlmCallLog write boundary (price-at-time-of-call); a per-student cost read
exposing both a lifetime total (free-tier meter) and a [period_start,
period_end) windowed total (subscription meter). As landed: the lifetime token
view was replaced by the per-call `student_llm_cost` spine, read by the new
`StudentLlmCostDao` (lifetime + windowed).
Already landed (build on these): none — first node. RFC 106 is in place.
Out of scope: the budget gate/enforcement, subscriptions, StoreKit, iOS. Backend
only.
When done: return to features/paid-subscriptions.md — mark this node implemented
in the living index (with its RFC number) and update the still-pending kickoff
prompts to reflect what actually landed (renamed artifacts, node splits, scope
shifts).
```

**2 — `budget-gate`** (backend):

```
/rfc-pipeline

Feature: cost-metered paid subscriptions.
North-star brief: features/paid-subscriptions.md — read it first; it is the
source of intent (model, design principles, node breakdown).

Design and implement one node: budget-gate.
Scope: BudgetService.entitlement(studentId) computed from the cost ledger;
free-allowance ($N lifetime) HARD BLOCK enforced at every LLM call boundary —
live chat refused with COACHING_BUDGET_EXHAUSTED (HTTP 402), background passes
(extraction / synthesis / fit-lens) skipped; plus GET /api/v1/coaching-usage/me
returning the abstract usage percentage. FREE_ALLOWANCE_USD is config. Free-tier
only: every student blocks at $N with no purchase path yet — that is the
intended end state of this node.
As landed: the wire error code is lowercase `coaching_budget_exhausted` (RFC 69
forbids UPPERCASE wire codes; the UPPERCASE spelling survives only as the Kotlin
`ErrorCode` constant). The usage endpoint is
`GET /api/v1/students/me/coaching-usage`, following the student-scoped resource
convention. `BudgetService.entitlement` has two overloads — an in-transaction
form for the four gates and a suspend form for the route — both returning the
shared `Entitlement` (spent, allowance, exhausted, usedPercent). The background
skips are NAMED result arms, not anonymous no-ops:
`ExtractionResult.SkippedBudgetExhausted`,
`SynthesisResult.SkippedBudgetExhausted`, and
`FitLensResult.Skipped(SkipReason.BudgetExhausted)`.
Already landed (build on these): llm-cost-ledger (per-call cost + lifetime and
windowed per-student cost reads).
Out of scope: subscriptions, StoreKit verify/webhook, the subscribed entitlement
branch, iOS UI. Backend only.
When done: return to features/paid-subscriptions.md — mark this node implemented
in the living index (with its RFC number) and update the still-pending kickoff
prompts to reflect what actually landed (renamed artifacts, node splits, scope
shifts).
```

**3 — `subscriptions-apple`** (backend; may split into verify-path + webhook):

```
/rfc-pipeline

Feature: cost-metered paid subscriptions.
North-star brief: features/paid-subscriptions.md — read it first; it is the
source of intent (model, design principles, node breakdown).

Design and implement one node: subscriptions-apple.
Scope: a subscriptions table + DAO; POST /api/v1/subscriptions/verify validating
the StoreKit 2 signed transaction (JWS) via the App Store Server API and mapping
productId → (y × price) budget; an App Store Server Notifications V2 webhook
(Apple-signed, no session cookie) enqueued onto the queue and applied by a
worker handler for renew / cancel / refund / grace / revoke; wire the SUBSCRIBED
entitlement branch (period_cost < y × plan_price) into budget-gate. Prefer to
split into a verify-path RFC and a Notifications-V2 webhook RFC, run in that
order.
Already landed (build on these): llm-cost-ledger (`StudentLlmCostDao.lifetimeCost`
+ the still-unused `windowedCost`, the subscription meter this node consumes);
budget-gate — `BudgetService` (two `entitlement` overloads) over `BudgetConfig`'s
`budget.freeAllowanceUsd`, the shared `Entitlement` verdict, the four call-site
gates, and `GET /api/v1/students/me/coaching-usage`. The subscribed branch
extends `BudgetService`/`Entitlement` here; budget-gate pre-built nothing for it.
Out of scope: the iOS StoreKit purchase UI and paywall. Backend only.
Prerequisite: the App Store Connect sandbox artifacts must already exist — the
auto-renewable subscription products (each productId mapped to a budget), the
App Store Server API key, and the Notifications V2 endpoint URL.
When done: return to features/paid-subscriptions.md — mark this node implemented
in the living index (with its RFC number) and update the still-pending kickoff
prompts to reflect what actually landed (renamed artifacts, node splits, scope
shifts).
```

**4 — `paywall-ios`** (iOS; may split into a StoreKit-purchase slice + paywall
UI):

```
/rfc-pipeline

Feature: cost-metered paid subscriptions.
North-star brief: features/paid-subscriptions.md — read it first; it is the
source of intent (model, design principles, node breakdown).

Design and implement one node: paywall-ios.
Scope: iOS StoreKit 2 purchase (product fetch, Product.purchase(), transaction
listener, POST the signed transaction to /api/v1/subscriptions/verify, Restore
Purchases); the paywall / block screen surfaced from the `coaching_budget_exhausted`
402 (a modal on the chat action, or a UserAuthState gate state modeled on RFC
72's verificationRequired) — the 402 is returned by all four turn endpoints,
including the two SSE ones, as plain JSON before any stream opens; subscribe
entry points; and the abstract "coaching used" usage bar consuming
GET /api/v1/students/me/coaching-usage, which answers
`{"usage": {"usedPercent": 0-100, "exhausted": bool}}` (percentage, never
dollars). Prefer to split into a StoreKit-purchase slice and the paywall UI, run
in that order.
Already landed (build on these): llm-cost-ledger; budget-gate; subscriptions-apple
(verify endpoint, webhook, subscribed entitlement branch).
Out of scope: nothing further — this is the last node.
iOS/Swift: bin/test check does NOT compile Swift, so the pipeline gate is a false
green here — verify with xcodebuild (scheme UnicoachiOS) before landing.
When done: return to features/paid-subscriptions.md — mark this node implemented
in the living index (with its RFC number); this is the final node, so record the
feature as complete.
```
