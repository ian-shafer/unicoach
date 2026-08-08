# RFC 109: Coaching budget gate

## Executive Summary

RFC 108 landed the metering substrate: every LLM call freezes a nano-dollar cost
at the `LlmCallLog` write boundary, and `StudentLlmCostDao` sums the per-call
`student_llm_cost` spine into a lifetime per-student total. Nothing reads it yet
— per-student spend is observable but unbounded.

This RFC adds enforcement, the `budget-gate` node of
`features/paid-subscriptions.md`: a config-driven lifetime free allowance
(`FREE_ALLOWANCE_USD`) and a hard pre-call block at every LLM call boundary. A
new `BudgetService.entitlement(studentId)` compares the ledger's lifetime total
against the allowance. Live chat checks it at turn admission — an exhausted
student's turn is refused with `coaching_budget_exhausted` (HTTP 402) before any
convo row or provider call exists. The three background passes (extraction,
synthesis, fit-lens) check it in their read phases and skip — a named, logged
outcome, not an error and not spend. A new
`GET /api/v1/students/me/coaching-usage` returns the abstract usage percentage
(never dollars, tokens, or provider names), the read the future iOS usage bar
consumes.

Free-tier only, by design: every student hard-blocks at the allowance with no
purchase path — that is this node's intended end state. Subscriptions, StoreKit
verification, the subscribed entitlement branch, and iOS UI are later nodes.
Backend only.

## Detailed Design

### Verified starting state (code, not RFCs)

- `StudentLlmCostDao` (`db/.../dao/StudentLlmCostDao.kt`) is a stateless
  `object` with **zero production callers** — the read API landed ahead of this
  consumer. `lifetimeCost(session, studentId): Result<StudentLlmCost>` returns
  `StudentLlmCost(costNanodollars: Nanodollars, uncostedCalls: Int, estimatedCalls: Int)`;
  its KDoc defers all policy to "a downstream budget gate". `windowedCost` (the
  subscription meter) stays unused until the subscribed branch lands.
- `LlmCallLog` is the single seam every provider call crosses, but it
  deliberately carries **no `StudentId`** (0038: attribution lives outside the
  log, reconstructed by the `student_llm_cost` view). A gate inside `LlmCallLog`
  would need a student threaded into both entry points; the gate therefore lives
  one layer up, at the four caller services, where the student is already in
  scope and each caller's refusal shape differs anyway.
- Chat turns are two-phase (`CoachingService.startConvo` / `postTurn`): a
  synchronous pre-flight builds `Prepared` inside `database.withConnection` —
  `startConvo` creates the convo row inside that block — then `openUserTurn`
  logs the opener call. The pre-flight is the last point where the outcome can
  still be a sealed variant with **nothing persisted**. The tool loop's
  continuation calls (`openContinuation`) run inside the cold reply flow,
  bounded by `coaching.maxToolRounds` plus one forced no-tools call.
- `StartConvoResult` has `Started` / `ValidationFailure`; `PostTurnResult` adds
  `NotFound`. Routes `when`-exhaust them in four handlers (`handleCreate`,
  `handlePostMessage`, `handleStreamCreate`, `handleStreamMessage`); the SSE
  handlers respond plain JSON for every pre-flight arm, so a new arm surfaces as
  a normal status response, never a mid-stream frame.
- Background passes gate per student inside each service's read phase, under the
  student advisory lock. Fit-lens has a first-class skip vocabulary
  (`FitLensResult.Skipped(SkipReason)`, seven variants, `toDisplay()`);
  extraction and synthesis collapse every pre-LLM gate into an anonymous
  `ReadPhase.NoOp → Success`. A pre-LLM skip **cannot** be persisted as a run
  row: all three `*_runs` tables `CHECK (outcome IN ('applied','failed'))` and
  require a non-null `llm_request_id` — a skip writes nothing, and this RFC does
  not relax that.
- Error codes: the wire strings are **lowercase snake_case** (RFC 69;
  `ErrorCodeTest` fails the build on anything else). The brief's
  "`COACHING_BUDGET_EXHAUSTED` (UPPERCASE)" is realizable only as the Kotlin
  enum constant; the wire string is `coaching_budget_exhausted`. The code wins;
  the brief is stale on this point. No 402 exists anywhere in the API today.
- Config: `CONFIGURATION.md`'s prime directive puts a JVM-only value in HOCON
  with at most a `${?VAR}` override and **no dotenv line** — the shape of every
  `coaching.*` tunable (`COACHING_MODEL` etc. appear in no `.env*` file).
- Wiring: `rest-server`'s `startServer()` parses configs and `appModule` builds
  services; `queue-worker`'s `main()` builds `ExtractionService` /
  `SynthesisService` / `FitLensService` behind enabled switches. `cron` loads no
  `service.conf` and makes no LLM calls — untouched.
- `api-specs/openapi.yaml` is the hand-maintained contract (no codegen); staging
  it triggers `bin/test-fuzz` (schemathesis) in the pre-commit hook.
  Conversation paths are excluded from fuzzing
  (`--exclude-path-regex
  conversations`), so documenting 402 there needs no
  `schemathesis.toml` change; the new usage endpoint is fuzzed and returns only
  already-allowed statuses (200/401/403/409/5xx).

### Config — `budget.freeAllowanceUsd` (`:service`)

`service.conf` gains a `budget` block: the lifetime free allowance in
human-readable USD, env-overridable like its `coaching.*` siblings (JVM-only ⇒
no dotenv line, per the prime directive):

```hocon
budget {
  # Lifetime free Anthropic allowance per student, human-readable USD.
  # Spent once, cumulatively (no monthly reset); at or past it every coaching
  # surface hard-blocks until a purchase path exists (subscriptions-apple).
  freeAllowanceUsd = 5.00
  freeAllowanceUsd = ${?FREE_ALLOWANCE_USD}
}
```

The default is `5.00` (architect decision) — several full coaching sessions at
current per-turn cost, still bounded. The brief calls `$N` a business lever;
retuning it is the `${?FREE_ALLOWANCE_USD}` override or a one-line conf edit,
never a code change.

`BudgetConfig` is the typed reader, mirroring `CoachingConfig`'s shape and
`LlmPriceBook.from`'s money conversion:

```kotlin
// service/.../coaching/budget/BudgetConfig.kt
class BudgetConfig private constructor(
  val freeAllowance: Nanodollars,
) {
  companion object {
    fun from(config: Config): Result<BudgetConfig>  // reads budget.freeAllowanceUsd
  }
}
```

`from` converts USD → nano-dollars once, at load, via `BigDecimal`
(`movePointRight(9).toBigIntegerExact()`), returning `Result.failure` on a
negative value or one finer than a nano-dollar — the same
reject-rather-than-round posture as `LlmPriceBook.from`. A zero allowance is
**valid**: it is the kill switch that blocks every student at once.

`from` also rejects an allowance above `Long.MAX_VALUE / 100` nano-dollars
(≈`$92.2M`) — the ceiling `Entitlement.usedPercent`'s integer arithmetic can
carry (see below). Config load is the one boundary an operator's
`FREE_ALLOWANCE_USD` crosses, so a typo adding stray zeros fails boot with the
key named, the same posture as the sibling `llmPricing` block, rather than
silently wrapping a percentage on a later read.

### Entitlement — one read serving gate and endpoint

`Entitlement` is the pure verdict: the two meters and the two facts derived from
them. One type serves both consumers (the boolean for the gate, the percentage
for the endpoint), so they can never disagree.

```kotlin
// service/.../coaching/budget/Entitlement.kt
class Entitlement(
  val spent: Nanodollars,      // StudentLlmCost.costNanodollars, lifetime
  val allowance: Nanodollars,  // BudgetConfig.freeAllowance
) {
  val exhausted: Boolean  // spent >= allowance (a zero allowance exhausts at zero spend)
  val usedPercent: Int    // floor(spent/allowance × 100) capped at 100; 100 when allowance is zero
}
```

`usedPercent` is integer `Long` arithmetic over `Nanodollars.value`, no floating
point: `100` when `exhausted`, otherwise the floor of `spent × 100 / allowance`.
In the non-exhausted case `spent < allowance`, so `spent × 100` can only
overflow for an allowance above ≈`$92M` — which `BudgetConfig.from` refuses, so
that is where a bad operator value dies. `Entitlement` takes a bare
`Nanodollars` rather than a `BudgetConfig`, though, so it restates the guard at
the arithmetic: the multiplication is `Math.multiplyExact`, which throws on an
allowance arriving by any other path (failing the gate read closed) instead of
returning a wrapped, garbage percentage. Tests wanting an effectively unlimited
budget still use a large-but-safe allowance (e.g. `$1M`), never
`Long.MAX_VALUE`. Because the ratio is floored and capped,
`usedPercent == 100 ⟺ exhausted` — the endpoint carries both anyway so the
client never re-derives the block condition.

`BudgetService` computes it from the ledger:

```kotlin
// service/.../coaching/budget/BudgetService.kt
class BudgetService(
  private val database: Database,
  private val config: BudgetConfig,
) {
  /** The gate: for callers already holding a session (the four gates). */
  fun verdict(session: SqlSession, studentId: StudentId): Result<BudgetVerdict>

  /** In-transaction read of the meters themselves; the read `verdict` is built on. */
  fun entitlement(session: SqlSession, studentId: StudentId): Result<Entitlement>

  /** Standalone form: opens its own connection (the usage route). */
  suspend fun entitlement(studentId: StudentId): Result<Entitlement>
}
```

`BudgetService` owns the decision as well as the read, so no gate re-derives
`exhausted`. Both outcomes are named — an absent value never stands in for
"blocked":

```kotlin
// service/.../coaching/budget/BudgetVerdict.kt
sealed interface BudgetVerdict {
  data object Entitled : BudgetVerdict
  data class Exhausted(val entitlement: Entitlement) : BudgetVerdict
}
```

`Exhausted` carries the deciding `Entitlement` because every refusal downstream
states the spend and allowance it refused at; `Entitled` carries nothing. The
gate is shared, the refusal shapes are not: each caller wraps `Exhausted` in its
own arm (below).

Both call `StudentLlmCostDao.lifetimeCost` and wrap the total with the
configured allowance. Policy on the two counters, deliberately simple: the gate
compares the summed `costNanodollars` as stored. `uncostedCalls` (usage
unreported) contribute `0` — the gate fails **open** by a gap already bounded
and countable at the ledger; `estimatedCalls` are default-priced over-estimates
— the gate fails **closed** on those. Both biases are RFC 108's, inherited, not
re-decided per caller. The meter read is one indexed aggregate per gated
operation (a turn or a pass, not per provider call); the brief's "materialize a
running balance" fork stays YAGNI until measured.

The `entitlement` name and semantics are the brief's; when `subscriptions-apple`
lands, the subscribed branch (`windowedCost < y × price`) extends
`BudgetService` in that RFC — nothing here pre-builds for it.

### Chat gate — turn admission in `CoachingService`

The unit of admission is the **turn**, not the individual provider call.
`CoachingService` gains a `budgetService` constructor parameter (undefaulted —
every construction states its budget policy; a root cannot ship ungated by
omission) and asks for a `verdict` in the existing pre-flight transaction:

- `startConvo`: first thing inside the `withConnection` block, **before**
  `ConvosDao.create` — a refused turn leaves no convo row, no `convo_requests`
  row, no `llm_requests` row.
- `postTurn`: after `loadOwned` resolves — a foreign or deleted convo stays
  `NotFound` regardless of budget; only an owned convo can learn budget state.

Each sealed result gains one arm, and the compiler walks it through the four
route handlers:

```kotlin
sealed interface StartConvoResult { /* existing */ data class BudgetExhausted(val entitlement: Entitlement) : StartConvoResult }
sealed interface PostTurnResult  { /* existing */ data class BudgetExhausted(val entitlement: Entitlement) : PostTurnResult }
```

The arm carries the deciding `Entitlement`, exactly as the three background
passes below do, for the same reason: a refusal writes no run row, so the log
line is the operator's only trace of it — and chat is the pass that produces the
most refusals. The entitlement stays out of the wire body (see the REST surface
below).

The tool loop's continuation calls are **not** re-gated mid-turn (architect
decision): an admitted turn runs to its terminal, overshoot bounded by one turn
(`maxToolRounds + 1` calls plus the forced no-tools close) — refusing mid-turn
would strand a half-answered turn for spend already made.

### Background-pass gates — named skips, no spend, no row

Each pass asks for a `verdict` in its read phase, immediately after the student
advisory lock (the earliest point where the student is resolved and locked), and
before any further reads. All three services gain an undefaulted `budgetService`
constructor parameter. The skip is **named** — fit-lens's existing vocabulary,
extended to the other two — never folded into the anonymous `NoOp`, so "skipped
for budget" is distinguishable from "nothing to do" in results and logs. No run
row is written (schema forbids it; see starting state): the skip is visible in
worker logs and, indirectly, in the frozen meter itself.

- **Fit-lens** — one new `SkipReason` variant, flowing through the existing
  `ReadPhase.Skip → FitLensResult.Skipped → JobResult.Success` path
  (`FitLensHandler` is untouched; `toDisplay()` gains the arm):

  ```kotlin
  data class BudgetExhausted(
    override val studentId: StudentId,
    val entitlement: Entitlement,
  ) : SkipReason
  ```

- **Extraction** — `ReadPhase` gains a `BudgetExhausted(studentId, entitlement)`
  variant (the read phase resolves the student from the convo), surfaced as a
  new result arm and mapped by the handler to `JobResult.Success` with an INFO
  log:

  ```kotlin
  data class SkippedBudgetExhausted(
    val studentId: StudentId,
    val entitlement: Entitlement,
  ) : ExtractionResult
  ```

- **Synthesis** — identical shape: `ReadPhase.BudgetExhausted`,
  `SynthesisResult.SkippedBudgetExhausted(studentId, entitlement)`, handler →
  `JobResult.Success` + INFO log.

Each arm carries the `Entitlement` so the log line states spent vs allowance
(`Nanodollars.toUsdString()`) — the operator's answer to "why did this student's
synthesis stop running". The check sits after the advisory lock rather than
before it so the read is consistent with the pass it guards; lock contention is
per-student and the passes are infrequent.

The sweep dispatchers (`SynthesisSweepHandler` / `FitLensSweepHandler`) still
fan out to every active student; the per-job skip is one cheap aggregate read
and keeps the per-student reason.

### REST surface — 402 on the four turn endpoints

`ErrorCode` gains `COACHING_BUDGET_EXHAUSTED("coaching_budget_exhausted")`. The
four turn handlers in `ConvoRoutes` map the new arms through a shared helper:

```kotlin
// Logs budgetSkipMessage("chat", studentId, entitlement), then responds 402 Payment
// Required with ErrorResponse(COACHING_BUDGET_EXHAUSTED, "Coaching allowance exhausted").
private suspend fun RoutingContext.respondBudgetExhausted(studentId: StudentId, entitlement: Entitlement)
```

The helper is where the chat pass logs its skip, through the same
`budgetSkipMessage` the extraction, synthesis, and fit-lens handlers use — one
site rather than four, and one format across all four passes. The spend and
allowance go to the log only: the 402 body names no dollars, tokens, or
provider.

The SSE variants hit the arm in pre-flight, before `call.respondBytesWriter`
opens, so they return the same plain 402 JSON — no error frame, no stream.
Read-only conversation routes (list, get, messages, update, delete) are **not**
gated: reading history costs nothing and the iOS block screen must still render
past conversations.

### `GET /api/v1/students/me/coaching-usage`

A student-authenticated read of the abstract meter — percentage only, per design
principle 2 (no dollars, tokens, or provider names on the wire).

```kotlin
// rest-server/.../models/CoachingUsageResponse.kt
data class CoachingUsageResponse(val usage: CoachingUsage)
data class CoachingUsage(
  val usedPercent: Int,     // 0–100, floor, capped
  val exhausted: Boolean,   // usedPercent == 100, carried so the client never re-derives it
)
```

`CoachingUsageRouteHandler(authService, studentService, budgetService,
sessionConfig)`
— new file, `CollegeListRoutes`' shape: resolve caller (401 `unauthorized`),
resolve student (409 `student_profile_required`), then
`budgetService.entitlement(studentId)` → 200. Registered in `configureRouting`
with `rejectUnsupportedMethods(HttpMethod.Get)`; the email verification gate
covers it automatically (403 `email_not_verified`). The free-tier denominator is
the allowance; the reset-date and lapsed-subscriber semantics from the brief's
"usage-bar semantics" fork arrive with the subscription window, not here.

### API Contracts

New/changed surfaces, all specified above: `budget` config block +
`BudgetConfig`; `Entitlement`; `BudgetVerdict`; `BudgetService` (`verdict` plus
two `entitlement` overloads); `StartConvoResult.BudgetExhausted` /
`PostTurnResult.BudgetExhausted`; `ExtractionResult.SkippedBudgetExhausted` /
`SynthesisResult.SkippedBudgetExhausted` / `SkipReason.BudgetExhausted`;
`ErrorCode.COACHING_BUDGET_EXHAUSTED`; `GET /api/v1/students/me/coaching-usage`
→ `CoachingUsageResponse`; 402 documented on the four turn operations in
`api-specs/openapi.yaml` plus the new path (`ErrorResponse`'s `code` is an
untyped string in the spec, so no schema change for the new code). Constructor
signatures widen on `CoachingService`, `ExtractionService`, `SynthesisService`,
`FitLensService` (each `+ budgetService: BudgetService`, undefaulted) and
`configureRouting` / `appModule` (`+ budgetConfig` / handler threading).

### Error Handling / Edge Cases

- **Gate read fails** (DB error from `lifetimeCost`) → the gate fails
  **closed**: chat surfaces it through the existing
  `runCatching → getOrThrow →
  StatusPages` 500 path; background read phases
  already catch and return `TransientFailure` (retry). Budget unavailability is
  coaching unavailability, never a free pass.
- **Concurrent turns racing the gate** → both pre-flights can pass at 99% and
  both spend. Accepted: overshoot is bounded by the per-turn bound times the
  handful of concurrent sessions one student can drive (principle 3). No lock is
  taken; the meter re-reads fresh on the next turn.
- **Uncosted / estimated calls** → per the counter policy above (uncosted fail
  open, estimated fail closed — RFC 108's biases, not compensated here); the
  write-boundary WARN remains the detection path for estimates.
- **Soft-deleted convos' spend counts** (spine semantics): deleting
  conversations does not refund allowance.
- **Exhausted student's queued background jobs** (enqueued before exhaustion) →
  skip on execution, `JobResult.Success`, no retry churn, no dead letter.
- **Student with no attributed calls** → `(0, 0, 0)` read, `usedPercent = 0`,
  entitled. Fresh accounts and the fuzz user always pass the gate, which is why
  schemathesis never observes a 402.
- **Free-allowance Sybil abuse** → accepted, addressed reactively (brief's open
  fork); nothing here binds the grant to a device.

### Dependencies

No new third-party or module dependency. `:service` already depends on `:db`
(`StudentLlmCostDao`, `SqlSession`) and `:common` (`Nanodollars`); `rest-server`
and `queue-worker` already depend on `:service`. `cron`, `admin-web`, `db`, and
the dotenv files are untouched — the one migration-free RFC in this feature:
enforcement is pure read-side policy over RFC 108's schema.

## Tests

### `service` — `BudgetConfigTest` (new)

- **parses USD to nano-dollars** — `budget { freeAllowanceUsd = 5.00 }` →
  `freeAllowance == Nanodollars.of(5_000_000_000)`.
- **zero allowance is valid** — `0.00` parses (the kill switch is
  representable).
- **negative → failure**; **finer than a nano-dollar → failure**
  (`0.0000000001`); **missing key → failure**.
- **above the usage-percentage ceiling → failure** — `92233721.00` is rejected
  and the failure names `budget.freeAllowanceUsd`; **just under it still
  parses** — `92233720.00` loads and `Entitlement` computes a percentage from
  it, pinning the ceiling to the arithmetic it protects.
- **not a number → failure naming the key AND the raw text** — `"5..00"` never
  reaches `Nanodollars`, so only the reader's own wrapper can name what broke;
  the failure carries both `budget.freeAllowanceUsd` and `5..00`.

### `service` — `LlmPriceBookTest` (extend)

- **a rate that is not a number → failure naming the key AND the raw text** —
  `input = "3..00"` fails the load with both `input` and `3..00` in the message,
  the same shape as `BudgetConfigTest`'s case above; the two readers share the
  money policy, so they share this failure mode.

### `service` — `EntitlementTest` (new, pure — no DB)

- **entitled below allowance** — `spent < allowance` → `exhausted == false`.
- **exhausted at exactly the allowance** — `spent == allowance` → `true` (the
  boundary is `>=`, pre-call check semantics).
- **exhausted above** — `true`, `usedPercent == 100` (capped).
- **percent floors** — e.g. spent `$2.50` of `$5.00` → `50`; spent `$4.999...` →
  `99`, never rounding up to a false block signal.
- **percent 0 at zero spend**; **`usedPercent == 100` only when exhausted**
  (sub-allowance ratios cap at 99 by flooring).
- **zero allowance** — `exhausted == true`, `usedPercent == 100`, no division
  error.
- **allowance past the percentage ceiling** — an `Entitlement` built directly
  (bypassing `BudgetConfig`'s guard) with an allowance of `Long.MAX_VALUE`
  throws `ArithmeticException` rather than reporting a wrapped percentage.

### `service` — `BudgetServiceTest` (new, DB-backed)

Seeds attributed, costed calls via the same raw-SQL pattern as
`StudentLlmCostDaoTest` (an owner row + `llm_requests` + `llm_responses` with
known `cost_nanodollars`).

- **fresh student** — no attributed calls → entitled, `spent == 0`.
- **summed spend** — calls across two owners sum into `spent`; entitled while
  under a configured allowance, exhausted once seeded past it.
- **uncosted call contributes zero** — a NULL-cost call flips nothing.
- **both overloads agree** — the suspend form returns the same entitlement as
  the session form for the same student.
- **the gate names both outcomes** — `verdict` returns `Entitled` under the
  allowance and `Exhausted` at it, the latter carrying the spend and allowance
  it was decided on.
- **read failure surfaces as failure** — `entitlement` (suspend form) against a
  closed `Database` returns `Result.failure`, never an entitled verdict — the
  callers' existing throw → 500 and catch → `TransientFailure` paths do the rest
  (fails closed).

### `service` — `CoachingServiceTest` (extend)

Existing constructions gain a generous-allowance `BudgetService`.

- **startConvo refused when exhausted** — a `BudgetService` with a tiny
  allowance and seeded spend (or zero allowance): returns
  `StartConvoResult.BudgetExhausted` carrying the deciding, exhausted
  `Entitlement`; **no convo row and no `llm_requests` row exist afterward** —
  the refusal precedes all persistence.
- **postTurn refused when exhausted** — `PostTurnResult.BudgetExhausted`, also
  carrying the deciding entitlement; no new `convo_requests` / `llm_requests`
  rows.
- **ownership outranks budget** — postTurn on a foreign convo with an exhausted
  budget → `NotFound`, not `BudgetExhausted`.
- **admitted turn is not re-gated** — existing tool-loop tests keep passing with
  the generous default; no new mid-turn refusal path exists to test.

### `service` — extraction / synthesis / fit-lens (extend)

- **`ExtractionServiceTest`** — exhausted student:
  `ExtractionResult.SkippedBudgetExhausted` with the right `studentId`; no
  `llm_requests` row, no run row, watermark unchanged.
- **`ExtractionHandlerTest`** — the new arm maps to `JobResult.Success`.
- **`SynthesisServiceTest`** — exhausted student: `SkippedBudgetExhausted`; no
  `llm_requests` row, no run row, freshness marker untouched.
- **`SynthesisHandlerTest`** — the new arm maps to `JobResult.Success`.
- **`FitLensServiceTest`** — exhausted student:
  `FitLensResult.Skipped(SkipReason.BudgetExhausted(...))`; no `llm_requests`
  row; `toDisplay()` renders spent/allowance via `toUsdString()`.
- (`FitLensHandlerTest` — no new mapping: `Skipped → Success` already covered.)

### `rest-server` — `CoachingBudgetRoutingTest` (new)

One server boot covers the 402s and the usage endpoint. Seeds exhaustion by raw
SQL: one `extraction_runs`-attributed `llm_requests`/`llm_responses` pair with
`cost_nanodollars` far above any sane allowance (e.g. `10^15`), mirroring
`StudentLlmCostDaoTest`'s helper — no config override needed against the real
`service.conf`.

- **`POST /api/v1/conversations` exhausted → 402** with
  `coaching_budget_exhausted`; a follow-up list shows **no conversation was
  created**.
- **`POST .../messages` exhausted → 402** (convo created while entitled, then
  exhaustion seeded).
- **stream variants → 402 plain JSON** — `Content-Type` is JSON, not an SSE
  stream.
- **read routes stay open** — list and messages GET return 200 for the exhausted
  student.
- **`GET /api/v1/students/me/coaching-usage`**: 401 unauthenticated; 409 without
  a student profile; 200 `{usage: {usedPercent: 0, exhausted: false}}` for a
  fresh student; 200 with a mid-range percent for seeded partial spend (the
  expected value computed from `BudgetConfig.from(AppConfig.load(...))`, so the
  test tracks the configured allowance rather than hardcoding it); 200
  `{usedPercent: 100, exhausted: true}` for the exhausted student.

### `rest-server` — existing suites

`ErrorCodeTest` covers the new code's casing automatically.
`OfflineCoachingE2eTest` constructions gain the generous-allowance
`BudgetService` and stay green — fresh e2e students spend far below any real
allowance. Staging `openapi.yaml` runs `bin/test-fuzz` at commit; the new
endpoint's documented statuses are all in schemathesis' accepted set.

## Implementation Plan

1. **`service`: budget domain.** Add the `budget` block to
   `service/src/main/resources/service.conf`; add `BudgetConfig`, `Entitlement`,
   `BudgetVerdict`, `BudgetService` under
   `service/src/main/kotlin/ed/unicoach/coaching/budget/`; add
   `BudgetConfigTest`, `EntitlementTest`, `BudgetServiceTest`.
   - Verify: `nix develop -c bin/test service -f --tests "*Budget*"`;
     `nix develop -c bin/test service -f --tests "*Entitlement*"`.
2. **`service`: chat gate.** Add the undefaulted `budgetService` parameter to
   `CoachingService`; add the `BudgetExhausted` arms to `StartConvoResult` /
   `PostTurnResult`; check the budget verdict in `startConvo`'s pre-flight
   (before `ConvosDao.create`) and `postTurn`'s (after `loadOwned`). Update
   every `CoachingServiceTest` construction (generous allowance) and add the
   refusal/ordering cases. `:rest-server` stops compiling here (non-exhaustive
   `when` + constructor) — restored in step 6; no verify command before then
   compiles it.
   - Verify: `nix develop -c bin/test service -f --tests "*CoachingService*"`.
3. **`service`: extraction gate.** `budgetService` parameter on
   `ExtractionService`; budget-verdict check in `readPhase` immediately after
   the advisory lock; `ReadPhase.BudgetExhausted(studentId, entitlement)`
   variant; `ExtractionResult.SkippedBudgetExhausted`; handler arm → INFO log +
   `JobResult.Success`. Extend `ExtractionServiceTest` / `ExtractionHandlerTest`
   (constructions + new cases).
   - Verify: `nix develop -c bin/test service -f --tests "*Extraction*"`.
4. **`service`: synthesis gate.** Same shape on `SynthesisService` /
   `SynthesisResult` / `SynthesisHandler`; extend `SynthesisServiceTest` /
   `SynthesisHandlerTest`.
   - Verify: `nix develop -c bin/test service -f --tests "*Synthesis*"`.
5. **`service`: fit-lens gate.** `budgetService` parameter on `FitLensService`;
   check after the advisory lock, before the min-claims floor;
   `SkipReason.BudgetExhausted(studentId, entitlement)` + `toDisplay()` arm;
   extend `FitLensServiceTest` / `FitLensHandlerTest` (constructions + new
   cases; `FitLensHandler` itself unchanged).
   - Verify: `nix develop -c bin/test service -f --tests "*FitLens*"`.
6. **`rest-server`: wiring + 402 + usage endpoint.** Add
   `ErrorCode.COACHING_BUDGET_EXHAUSTED`; `respondBudgetExhausted` + the four
   new `when` arms in `ConvoRoutes`; new `CoachingUsageRoutes.kt` +
   `CoachingUsageResponse.kt`; register in `Routing.kt` (new `budgetService`
   parameter); parse `BudgetConfig` in `startServer`, build `BudgetService` in
   `appModule`, pass into `CoachingService` and `configureRouting`. Fix
   `OfflineCoachingE2eTest`'s three service constructions. Add
   `CoachingBudgetRoutingTest`.
   - Verify:
     `nix develop -c bin/test rest-server -f --tests
     "*CoachingBudgetRoutingTest*"`;
     `nix develop -c bin/test rest-server -f --tests "*ErrorCodeTest*"`.
7. **`queue-worker`: wiring.** Parse `BudgetConfig` beside the sibling configs
   in `main()`, build one `BudgetService`, pass into the three services inside
   the enabled gate.
   - Verify: `nix develop -c ./gradlew :queue-worker:compileKotlin`.
8. **`api-specs/openapi.yaml`.** Add the `/api/v1/students/me/coaching-usage`
   GET (200 → `CoachingUsageResponse` schema, 401/409/500 → `ErrorResponse`) and
   a `'402'` `ErrorResponse` response on the four turn operations.
   - Verify: `nix develop -c bin/test-fuzz` (also runs from the pre-commit hook
     because the spec is staged).
9. **Brief.** In `features/paid-subscriptions.md`: mark `budget-gate`
   implemented with rfc `109` in the living index; reconcile the pending kickoff
   prompts with what landed (lowercase wire code `coaching_budget_exhausted`,
   `BudgetService` overloads, `Entitlement`, `CoachingUsageResponse` shape,
   named skip arms).
   - Verify:
     `grep -E '\|.*budget-gate.*\|.*implemented.*\|.*109'
     features/paid-subscriptions.md`;
     `nix develop -c bin/format -c`.
10. **Full gate.** Whole suite + formatter.
    - Verify: `nix develop -c bin/test`; `nix develop -c bin/format -c`.

## Files Modified

**`service` — budget domain**

- `service/src/main/resources/service.conf` — add the `budget` block.
- `service/src/main/kotlin/ed/unicoach/coaching/budget/BudgetConfig.kt` — new.
- `service/src/main/kotlin/ed/unicoach/coaching/budget/Entitlement.kt` — new.
- `service/src/main/kotlin/ed/unicoach/coaching/budget/BudgetVerdict.kt` — new.
- `service/src/main/kotlin/ed/unicoach/coaching/budget/BudgetService.kt` — new.
- `service/src/main/kotlin/ed/unicoach/coaching/budget/BudgetSkipLog.kt` — new
  (`budgetSkipMessage`), the one skip-log format the chat, extraction, and
  synthesis refusals share. A free function rather than a member of
  `Entitlement`: a domain entity models the two meters, it does not render log
  strings.
- `service/src/main/kotlin/ed/unicoach/coaching/LlmPriceBook.kt` —
  `parseNanoPerToken` reads its rate through `Nanodollars.fromExactDecimal`, so
  the money policy it shares with `BudgetConfig` is stated once; its
  `BigDecimal` parse sits inside the same context-adding catch, so malformed
  rate text fails with the key and the raw value named rather than as a bare
  `NumberFormatException`.
- `service/src/test/kotlin/ed/unicoach/coaching/LlmPriceBookTest.kt` — the
  malformed-rate case for that shared failure mode.
- `service/src/test/kotlin/ed/unicoach/coaching/budget/BudgetConfigTest.kt` —
  new.
- `service/src/test/kotlin/ed/unicoach/coaching/budget/EntitlementTest.kt` —
  new.
- `service/src/test/kotlin/ed/unicoach/coaching/budget/BudgetServiceTest.kt` —
  new.
- `service/src/test/kotlin/ed/unicoach/coaching/budget/TestBudgetServices.kt` —
  new; the single definition of the budget-gate test fixture
  (`testBudgetService` plus the `generousBudgetService` /
  `exhaustedBudgetService` conveniences), shared by every suite below that wires
  a `BudgetService` with a chosen allowance.

**`service` — gates**

- `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt` —
  `budgetService` parameter; pre-flight checks in `startConvo` / `postTurn`.
- `service/src/main/kotlin/ed/unicoach/coaching/StartConvoResult.kt` —
  `BudgetExhausted` arm.
- `service/src/main/kotlin/ed/unicoach/coaching/PostTurnResult.kt` —
  `BudgetExhausted` arm.
- `service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionService.kt`
  — `budgetService` parameter; read-phase check; `ReadPhase.BudgetExhausted`.
- `service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionResult.kt`
  — `SkippedBudgetExhausted` arm.
- `service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionHandler.kt`
  — map the new arm (INFO log + `JobResult.Success`).
- `service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisService.kt` —
  same shape as extraction.
- `service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisResult.kt` —
  `SkippedBudgetExhausted` arm.
- `service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisHandler.kt` —
  map the new arm.
- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensService.kt` —
  `budgetService` parameter; read-phase check.
- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensResult.kt` —
  `SkipReason.BudgetExhausted` + `toDisplay()` arm.
- `service/src/test/kotlin/ed/unicoach/coaching/CoachingServiceTest.kt` —
  constructions + refusal/ordering cases.
- `service/src/test/kotlin/ed/unicoach/coaching/extraction/ExtractionServiceTest.kt`
  — constructions + skip cases.
- `service/src/test/kotlin/ed/unicoach/coaching/extraction/ExtractionHandlerTest.kt`
  — subclass construction + mapping case.
- `service/src/test/kotlin/ed/unicoach/coaching/synthesis/SynthesisServiceTest.kt`
  — constructions + skip cases.
- `service/src/test/kotlin/ed/unicoach/coaching/synthesis/SynthesisHandlerTest.kt`
  — construction + mapping case.
- `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensServiceTest.kt` —
  constructions + skip cases.
- `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensHandlerTest.kt` —
  construction site.

**`rest-server`**

- `rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorCode.kt` —
  `COACHING_BUDGET_EXHAUSTED("coaching_budget_exhausted")`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/ConvoRoutes.kt` —
  `respondBudgetExhausted` (INFO `budgetSkipMessage("chat", …)` + 402) + four
  `when` arms; caller resolution via `CallerResolution`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/CoachingUsageRoutes.kt`
  — new (`CoachingUsageRouteHandler`).
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/CallerResolution.kt` —
  new (`CallerResolution`, `SessionCallerResolution`). The new route handler
  would have been the third hand-copy of the `resolveUser` / `resolveStudent` /
  `respondUnauthorized` / `respondStudentProfileRequired` preamble, so the four
  are extracted here and the existing handlers delegate to them. Pure extraction
  — no status, error code, or message text changes.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/CollegeListRoutes.kt` —
  drop the four local copies; delegate to `CallerResolution`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/StudentRoutes.kt` — drop
  the local `resolveUser` and file-level `respondUnauthorized`; delegate to
  `CallerResolution`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/CoachingUsageResponse.kt`
  — new (`CoachingUsageResponse`, `CoachingUsage`).
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt` — `budgetService`
  parameter; construct + register the handler.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — parse
  `BudgetConfig` in `startServer`; build `BudgetService` in `appModule`; thread
  into `CoachingService` and `configureRouting`.
- `rest-server/src/test/kotlin/ed/unicoach/rest/OfflineCoachingE2eTest.kt` —
  three service constructions.
- `rest-server/src/test/kotlin/ed/unicoach/rest/CoachingBudgetRoutingTest.kt` —
  new.

The remaining `rest-server` tests boot the server through `appModule`, so each
gains the new `BudgetConfig` argument at its call site — a mechanical
consequence of the `Application.kt` signature change, with no behavioural
change:

- `rest-server/src/test/kotlin/ed/unicoach/rest/ChatToDbIntegrationTest.kt` —
  one `appModule` call site.
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoExtractionEnqueueTest.kt` —
  two `appModule` call sites.
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoStreamErrorRoutingTest.kt`
  — one `appModule` call site.
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoToolLoopRoutingTest.kt` —
  one `appModule` call site.
- `rest-server/src/test/kotlin/ed/unicoach/rest/EmailSendE2eTest.kt` — one
  `appModule` call site.
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/EmailVerificationGateTest.kt`
  — the `moduleWith` helper's `appModule` call site.

**`common`**

- `common/src/main/kotlin/ed/unicoach/common/money/Nanodollars.kt` —
  `fromExactDecimal(amount, scaleDigits)`, the one home of the
  reject-rather-than-round money policy `BudgetConfig` and `LlmPriceBook` both
  read decimal amounts through.

**`queue-worker`**

- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` — parse
  `BudgetConfig`, build `BudgetService`, pass into the three services.

**`api-specs`**

- `api-specs/openapi.yaml` — new `/api/v1/students/me/coaching-usage` path +
  `CoachingUsage`/`CoachingUsageResponse` schemas; `'402'` on the four turn
  operations.

**Feature brief**

- `features/paid-subscriptions.md` — mark `budget-gate` implemented (rfc 109);
  reconcile pending kickoff prompts with landed names.
