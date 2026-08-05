# RFC 108: LLM cost ledger

## Executive Summary

RFC 106 landed a provider-agnostic call log (`llm_requests` / `llm_responses` /
`llm_responses_raw`) recording every LLM call's token usage, plus a
`student_llm_token_usage` view attributing tokens to a student across the four
call owners. It records tokens, not dollars, and its view is lifetime-cumulative
with no time window. The cost-metered subscription feature
(`features/paid-subscriptions.md`) needs both: a per-student **dollar** total,
readable over an arbitrary `[period_start, period_end)`.

This RFC adds a per-call frozen cost and a windowable per-student cost read, as
a strict extension of the RFC-106 seam — no second ledger. A config-driven
`LlmPriceBook` (per-MTok USD rates, keyed on the **resolved** model id) turns a
call's four token counts into an integer nano-dollar cost, frozen at the
`LlmCallLog` write boundary from the price in effect then. The cost is two new
columns on `llm_responses` — `cost_nanodollars` and its `cost_is_estimated`
qualifier — whose append-only guards make the freeze DB-enforced.

The lifetime-aggregated `student_llm_token_usage` view — read by no production
code — is replaced by a per-call `student_llm_cost` spine: the same four-owner
union at per-call grain, carrying the cost columns, `created_at`, and the token
columns. A new `StudentLlmCostDao` sums it into a **lifetime** (free-tier meter)
and a **`[start, end)` windowed** (subscription meter) per-student total, each
reporting an `uncostedCalls` count that bounds the total's gap and an
`estimatedCalls` count that bounds how much of it is approximated. No budget
gate, subscriptions, StoreKit, or iOS — backend only.

## Detailed Design

### Verified starting state (code, not RFCs)

- `LlmCallLog` (`service/.../coaching/LlmCallLog.kt`) is the sole seam for every
  provider call and has **two** distinct `llm_responses` write sites.
  `writeResponse` (private) terminates every call whose provider stream was
  actually invoked, from six call sites in `record` / `recordStreaming`: the two
  terminal-mapping sites, plus four cancellation / defect catches that pass
  `outcome = LlmCallOutcome.Failed(…)` **and** `usage = null`.
  `writeTerminalIfAbsent` (private) is the shared body of the two idempotent
  repair methods (`writeCancelledIfAbsent` / `writeInternalErrorIfAbsent`); it
  builds its own `NewLlmResponse` and calls `LlmCallsDao.appendResponse`
  directly rather than routing through `writeResponse`, and fires only when an
  opener was interrupted before its cold flow ran — the provider call never
  happened.
- `usageOf` yields a `TokenUsage` only on the `Completed` terminal; `Rejected`
  and `TransientFailure` hard-code `null` (neither `ChatEvent` variant has a
  usage field).
- `mapOutcome` carries the resolved model into the outcome —
  `LlmCallOutcome.Completed(content, modelResolved, stopReason)`, read from
  `ChatEvent.Completed.response.modelResolved` — and `outcome` is already a
  `writeResponse` parameter.
- All five `ChatRequest` constructions (`CoachingService`, `ExtractionService`,
  `SynthesisService`, `FitLensService` ×2) set `model = config.model`, so
  `model_requested` and `model_resolved` are the same string today, with nothing
  guaranteeing they stay so. Each `*.model` is read by a typed
  `*Config.from(config)` reader and is env-overridable (`${?COACHING_MODEL}` and
  siblings).
- `llm_responses` (migration `0038`) carries `prevent_log_update` /
  `prevent_log_delete` triggers: a row is physically immutable once written. It
  records `model_requested` (request row) and `model_resolved` (completed
  response).
- `student_llm_token_usage` (migration `0040`) is a `GROUP BY student_id`
  aggregate over the four-owner union (chat via `convo_requests → convos`,
  `extraction_runs`, `synthesis_runs`, and `fit_lens_runs`'s two ids), joined to
  `llm_responses` by the `UNIQUE(request_id)`. Its chat join deliberately omits
  `convos.deleted_at IS NULL` so a soft-deleted first turn's billed spend still
  counts. No production Kotlin reads it — only `StudentLlmTokenUsageViewTest`
  (raw JDBC).
- `convos_student_id_idx` (`0006`) is the **only** index on `convos.student_id`
  and is **partial** (`WHERE deleted_at IS NULL`); the spine's other three
  branches already carry `(student_id, created_at)` indexes
  (`extraction_runs_student_idx`, `synthesis_runs_student_idx`,
  `fit_lens_runs_student_idx`).
- Both composition roots build `LlmCallLog(chatProvider, database)` and inject
  it: `rest-server/.../Application.kt` inside `startServer()` (whose `main()` is
  only `startServer(wait = true)`), into `CoachingService`; and
  `queue-worker/.../Application.kt` inside `main()`, into `ExtractionService` /
  `SynthesisService` / `FitLensService`, behind their enabled switches.
- All four callers request `claude-sonnet-4-6` today (`service.conf`:
  `coaching.model`, `extraction.model`, `synthesis.model`, `fitLens.model`).
- No `NUMERIC` column or `BigDecimal` exists in the codebase; this is the first
  monetary quantity.

### Cost unit — integer nano-dollars

Cost is stored as an integer count of **nano-dollars** (1 nano-dollar =
`1e-9 USD`), not a decimal dollar amount: a running meter is summed over many
rows, and integer arithmetic cannot drift.

Nano-dollars is the coarsest scale that keeps **per-token** cost an exact
integer for realistic pricing — per-token cost is `rate_$per_MTok / 1e6`, i.e.
`rate_$per_MTok × 1000` nano-dollars, so a rate quoted to a tenth of a cent per
MTok (finer than any current Anthropic rate) is still integral and a call's cost
is a pure integer dot-product, no division and no rounding:

```
cost_nanodollars =
    input_tokens       × input_nano_per_token
  + output_tokens      × output_nano_per_token
  + cache_read_tokens  × cache_read_nano_per_token
  + cache_write_tokens × cache_write_nano_per_token
```

Micro-dollars (`1e-6`) cannot represent a single cache-read token at
`$0.30/MTok` (`0.3 µ$`). `BIGINT`'s `≈9.2e18` range — matched by
`StudentLlmCost`'s `Nanodollars` total (itself a zero-cost `Long` wrapper), the
operative bound — leaves nine orders of headroom over a per-student lifetime
meter in the low `1e9`s.

### Price book — config (`:service`)

The `model → price` table is HOCON config, per design principle 5 of the brief.
`service.conf` gains an `llmPricing` block: per-model, per-MTok USD rates for
the four token classes. It carries no `${?VAR}` substitutions, unlike its
sibling tunables — pricing is JVM-only, so under `CONFIGURATION.md`'s prime
directive it has exactly one home, in a classpath resource baked into the jar. A
rate change is therefore a committed-file edit plus a redeploy, the cadence of a
model bump, not a live lever.

```hocon
llmPricing {
  # Per-MTok USD rates, human-readable dollars. Keyed on the RESOLVED model id
  # (llm_responses.model_resolved — the model the provider reports actually ran).
  # cacheWrite is the 5-minute-TTL rate.
  models {
    "claude-sonnet-4-6" { input = 3.00, output = 15.00, cacheRead = 0.30, cacheWrite = 3.75 }
  }

  # Applied to any resolved model absent from `models` (see costOf below); set at
  # the priciest tier we would plausibly be served. Rows priced this way carry
  # cost_is_estimated = true.
  default { input = 10.00, output = 50.00, cacheRead = 1.00, cacheWrite = 12.50 }
}
```

`LlmPriceBook` is the typed, pure domain object read from that block:

Nano-dollar amounts are not a bare `Long`: `Nanodollars` (`:common`,
`ed.unicoach.common.money`) is a `@JvmInline value class` wrapping one, guarding
non-negativity once (`init { require(value >= 0) }`) rather than at every call
site, and owning the nano-dollar → USD-string conversion (`toUsdString()`, used
at the `admin-web` render boundary below) beside the unit it formats. Zero
runtime cost (`value class`); `:db`, `:service`, and `admin-web` all already
depend on `:common`, so no new dependency edge.

```kotlin
// service/.../coaching/LlmPriceBook.kt
class ModelPrice(               // nano-dollars PER TOKEN, per class (converted once at load)
  val inputNanoPerToken: Nanodollars,
  val outputNanoPerToken: Nanodollars,
  val cacheReadNanoPerToken: Nanodollars,
  val cacheWriteNanoPerToken: Nanodollars,
)

class LlmPriceBook private constructor(
  private val prices: Map<String, ModelPrice>,
  private val default: ModelPrice?,   // null ONLY in EMPTY — an unpriced book
) {
  /** Cost for [usage] under [model]'s price, under [default] when [model] is absent, or null (below). */
  fun costOf(model: String, usage: TokenUsage): FrozenCost?

  /** Boot guard: fails when any [models] id has no entry of its own (the default does not count). */
  fun requireExplicitlyPriced(models: Collection<String>): Result<Unit>

  companion object {
    fun from(config: Config): Result<LlmPriceBook>   // reads `llmPricing.models` + `llmPricing.default`
    val EMPTY: LlmPriceBook                           // no prices, no default; every costOf → null
  }
}
```

`from` converts each `$per_MTok` rate to an integer nano-dollars-per-token
(`× 1000`) once, at load, via `BigDecimal`, wrapping the result in
`Nanodollars.of(...)`. A rate finer than a tenth of a cent per MTok has no exact
integer form: `from` returns `Result.failure` rather than round it silently, as
it does for a missing `default` block. It fails the same way on a **negative**
rate, which converts cleanly and would otherwise reach the DB: every completed
call prices below zero, `llm_responses_cost_nonneg_check` rejects the terminal
insert, and `writeResponse`'s `getOrThrow` throws from a site `record` /
`recordStreaming` reach only _after_ their catches — leaving a committed
`llm_requests` row with no response row, which RFC 106's
one-response-per-request guarantee forbids. `TokenUsage` is the existing `:chat`
type already mapped by `LlmCallLog`.

`costOf` returns `null` in exactly **three** cases: either base count
(`inputTokens`, `outputTokens`) is null **or negative** — a billed call whose
usage is unreported or corrupt, an `uncostedCalls` gap either way, and never
something that reaches `Nanodollars`'s own non-negative guard — or the book has
no entry for `model` **and** no `default` to fall back on — the last reachable
only through `EMPTY`, since `from` rejects a config with no `default` block.
Otherwise it is the integer dot-product above, reading a null **cache** count as
`0`; a **negative** cache count — a lesser-severity provider glitch than a
corrupt base count — coerces to that same `0` rather than voiding the whole
computation. The base/cache asymmetry tracks what a null (or negative) means per
class: a completed call provably spent input and output tokens, so a missing or
corrupt count there is unreported usage, not a measured zero (see the
frozen-cost invariant); a missing or corrupt cache class means no caching is
known to have occurred, so `0` is exact.

An **unknown model is not** a null case in a configured book (one from `from`,
which is every book a composition root wires): it prices at the `default` rate
with `estimated = true`, and `LlmCallLog` WARNs naming the unrecognized
`model_resolved`. Returning `null` there would let one provider-side rename
blank the meter for every call at once — the failure mode a spend gate cannot
tolerate, since it fails open, where an over-estimate fails closed; the WARN
makes the rename loud from its first call rather than surfacing later as a 3.3×
over-charge already frozen into every student's meter. `cost_is_estimated` keeps
the approximated share countable (`estimatedCalls`) for the budget gate that
consumes it.

`EMPTY` is `LlmCallLog`'s constructor default (below): the only book with a null
`default`, so a cost-agnostic construction prices nothing and freezes every cost
`NULL`. That null `default` is the price-nothing discriminator, never
`prices.isEmpty()` — a configured `models {}` with a valid `default` is a
legitimate all-default book.

**Pricing keys on `model_resolved`** — the model the provider reports actually
ran, and therefore the model actually billed; identical to `model_requested`
today, but stays correct if an alias repoint or a server-side fallback separates
them. That key space is not ours (`model_resolved` is `TEXT NULL` in 0038,
unvalidated, with no model registry to check it against), so the lookup must
have a total answer: `llmPricing.default`. Nothing is lost on the failure path —
0038's `(model_resolved IS NOT NULL) = (outcome = 'completed')` CHECK and
`usageOf`'s `Completed`-only usage put the model and the usage in exactly the
same rows.

**Boot check — configured models must be priced explicitly.** Each composition
root, after building the price book, calls `requireExplicitlyPriced(...)` with
the caller models it wires (`rest-server`: `coaching.model`; `queue-worker`: the
enabled subset of `extraction.model` / `synthesis.model` / `fitLens.model`) and
`getOrThrow`s, so an env override to an unpriced id fails startup rather than
silently metering at the default rate. It is explicitly _not_ a completeness
guarantee for the lookup — it validates the ids we **request**, while `costOf`
keys on the ids we are **served**, a set nothing can guarantee in advance.

### Freezing cost at the write boundary (`LlmCallLog`)

`LlmCallLog`'s constructor gains an `LlmPriceBook`:

```kotlin
class LlmCallLog(
  private val provider: ChatProvider,
  private val database: Database,
  private val priceBook: LlmPriceBook = LlmPriceBook.EMPTY,
  private val nanoTime: () -> Long = System::nanoTime,
)
```

Cost is **derived inside `writeResponse`**, which gains **no new parameter**. It
already holds both inputs: `usage`, and — inside the `outcome` parameter it
already takes — the resolved model. `writeResponse` calls two named steps in
sequence: `costOf(outcome, usage)` — a pure function,
`priceBook.costOf(outcome.modelResolved, usage)` on the `Completed` arm with a
non-null `usage`, `null` on every other arm — and
`warnIfEstimatedDefault(outcome, cost)`, which WARNs (see below) as its sole
concern; splitting the WARN out of `costOf` keeps the cost computation itself
pure and independently testable with no logger capture. None of the six call
sites changes: the four cancellation / defect catches pass
`LlmCallOutcome.Failed` and `usage = null` together, so they land on `NULL` by
the same expression that prices the two terminal sites — no site makes a cost
decision, so no site can get one wrong.

`writeTerminalIfAbsent` — the second write site — sets
`cost = FrozenCost(nanodollars = Nanodollars.of(0), estimated = false)` in its
own `NewLlmResponse`. Both repair methods fire only when an opener was
interrupted before any provider call, so nothing was billed and `0` is a true
statement, exactly measured; it also keeps routine mid-tool-loop disconnects out
of the `uncostedCalls` signal. The two write sites stay separate
(`writeTerminalIfAbsent` has a fixed `latencyMs = 0`, runs `NonCancellable`, and
swallows the 1:1 collision), so `NewLlmResponse.cost: FrozenCost?` (see below)
is declared **without a default**: the compiler forces both sites to state a
cost and a third write site can never omit it silently.

### Data models (`:db`, migration `0041`)

Two additive columns, an index correction, and a view swap. `ADD COLUMN … NULL`
needs no table rewrite and does not fire the append-only delete guard, so —
unlike RFC 106's `NOT NULL` reshapes — no `TRUNCATE` is required. Pre-existing
rows read `NULL` and stay permanently uncosted, inflating `uncostedCalls` by the
short pre-launch backlog logged since RFC 106; backfilling them at a price they
were not made under would be worse than leaving the gap countable. Undoing
`0041` is a new, higher-numbered compensating migration
(`db/schema/INVARIANTS.md` forbids reverse migrations) that re-creates
`student_llm_token_usage` and restores `0006`'s partial `convos_student_id_idx`;
the two cost columns are additive and nullable, so nothing needs to undo them.

```sql
-- 0041.add-llm-response-cost.sql
ALTER TABLE llm_responses
  ADD COLUMN cost_nanodollars BIGINT NULL;   -- frozen $ cost of THIS call, price-at-time-of-call
ALTER TABLE llm_responses
  ADD COLUMN cost_is_estimated BOOLEAN NULL; -- true when priced at llmPricing.default (model not in the book)
ALTER TABLE llm_responses
  ADD CONSTRAINT llm_responses_cost_nonneg_check
    CHECK (cost_nanodollars IS NULL OR cost_nanodollars >= 0);
-- The flag qualifies a cost, so it is present exactly when there is a cost to
-- qualify. Same idiom as 0038's llm_responses_completed_model_check.
ALTER TABLE llm_responses
  ADD CONSTRAINT llm_responses_cost_estimated_check
    CHECK ((cost_is_estimated IS NOT NULL) = (cost_nanodollars IS NOT NULL));

-- The spine's chat branch selects convos by student_id with NO deleted_at
-- predicate, so 0006's PARTIAL convos_student_id_idx cannot serve it and every
-- meter read would seq scan convos. A plain index serves the spine and the
-- existing deleted_at IS NULL reads both, so it replaces rather than doubles.
DROP INDEX convos_student_id_idx;
CREATE INDEX convos_student_id_idx ON convos (student_id);

DROP VIEW student_llm_token_usage;

-- Per-call attribution spine: one row per attributed call, the four-owner union
-- in exactly one place. Not pre-aggregated, so it windows by created_at. Carries
-- the token columns so a future token-totals reader is a GROUP BY away.
CREATE VIEW student_llm_cost AS
WITH per_call AS (
  SELECT c.student_id, cr.llm_request_id
  FROM convo_requests cr JOIN convos c ON c.id = cr.convo_id
  UNION ALL
  SELECT er.student_id, er.llm_request_id FROM extraction_runs er
  UNION ALL
  SELECT sr.student_id, sr.llm_request_id FROM synthesis_runs sr
  UNION ALL
  SELECT flr.student_id, flr.query_llm_request_id AS llm_request_id FROM fit_lens_runs flr
  UNION ALL
  SELECT flr.student_id, flr.reason_llm_request_id AS llm_request_id
  FROM fit_lens_runs flr WHERE flr.reason_llm_request_id IS NOT NULL
)
SELECT
  pc.student_id,
  resp.created_at,
  resp.cost_nanodollars,
  resp.cost_is_estimated,
  resp.input_tokens,
  resp.output_tokens,
  resp.cache_read_tokens,
  resp.cache_write_tokens
FROM per_call pc
JOIN llm_responses resp ON resp.request_id = pc.llm_request_id;
```

RFC-106's attribution semantics are unchanged (same union, same soft-deleted
inclusion, same orphan exclusion); the spine only lowers the grain to per-call
and adds `created_at` + the two cost columns. `llm_responses` needs no
`created_at` index: the read is driven by `student_id` through the owner tables,
and `created_at` filters the small post-join set.

**Kotlin models + DAOs (`:db`).**

- `FrozenCost` (new, `db.models`) pairs the two cost facts as one value:
  `data class FrozenCost(val nanodollars: Nanodollars, val estimated: Boolean)`.
  The DB CHECK (`llm_responses_cost_estimated_check`, below) enforces that a
  cost and its estimated flag are present or absent **together** — never one
  without the other — and `FrozenCost` lifts that pairing into the Kotlin type
  rather than leaving it a convention two independently-nullable fields must be
  read against. It mirrors `LlmCallOutcome`'s existing precedent one field over
  (a `completed`-with-a-reason cannot be constructed either);
  `LlmPriceBook.costOf` (`:service`, above) returns this same `FrozenCost`
  directly rather than a service-layer duplicate, since `:service` already
  depends on `:db`.
- `NewLlmResponse` / `LlmResponse` gain `cost: FrozenCost?` after the four token
  fields — their billing sibling. It is undefaulted (see above), so its nine
  existing constructions each state it explicitly (`null` where the original
  plan called for two positional `null`s); `LlmCallsDaoTest` gains a private
  `newResponse(requestId, cost: FrozenCost?)` helper (no default on `cost`) that
  the four positional constructions route through, so the compiler still forces
  every one of them to state a cost.
- `LlmCallsDao` has **three** edit points, not two: `appendResponse` binds both
  columns (`input.cost?.nanodollars?.value`, `input.cost?.estimated`) in the
  same insert as the token counts, so cost and tokens are one atomic,
  DB-immutable row; `mapResponse` reads them back into one `FrozenCost?`
  (prefix-aware, so the direct and joined paths both get them); and
  `joinedCallColumns` — the aliased `SELECT` fragment `ConvosDao.turnSelect`
  splices — aliases them under `JOINED_RESPONSE_PREFIX`. Omitting the third
  leaves `mapResponse` reading columns the joined query never selected, failing
  `ConvosDao.listTurns` / `findTurnByRequestId` at runtime rather than at
  compile time.
- `StudentLlmCost` — the read result:
  `class StudentLlmCost(val costNanodollars: Nanodollars, val uncostedCalls: Int, val estimatedCalls: Int)`.
  The two counters bound the total in opposite directions and must not be
  collapsed: `uncostedCalls` (no cost, usage unreported) makes the total an
  **under**-count by an unknown amount; `estimatedCalls` (priced at the default
  rate) makes that portion a deliberate **over**-estimate.
- `StudentLlmCostDao` (new) reads the spine:

```kotlin
object StudentLlmCostDao {
  fun lifetimeCost(session: SqlSession, studentId: StudentId): Result<StudentLlmCost>
  fun windowedCost(
    session: SqlSession, studentId: StudentId,
    periodStart: Instant, periodEnd: Instant,   // [start, end)
  ): Result<StudentLlmCost>
}
```

Both run
`SELECT COALESCE(SUM(cost_nanodollars), 0) AS cost, COUNT(*) FILTER
(WHERE cost_nanodollars IS NULL) AS uncosted, COUNT(*) FILTER
(WHERE cost_is_estimated) AS estimated FROM student_llm_cost WHERE
student_id = ?`;
`windowedCost` adds `AND created_at >= ? AND created_at < ?` and `require`s
`periodStart < periodEnd` (`LlmCallsDao`'s precedent for a nonsensical
argument), since reading an inverted window as a silent `(0, 0, 0)` is
indistinguishable from a student who spent nothing; `COALESCE` gives that
genuine no-spend read. PostgreSQL returns `SUM(bigint)` as `numeric` and
`COUNT(*)` as `bigint`, so the mapper narrows via `getLong("cost")` /
`getInt("uncosted")` / `getInt("estimated")`. A downstream budget gate reads all
three and sets its own policy; this RFC sets none.

### API Contracts

No REST/HTTP surface changes. All new contracts are internal: `Nanodollars`
(`:common`); `LlmPriceBook` / `ModelPrice` (`costOf` returns `FrozenCost?`
directly); `LlmCallLog`'s constructor (`+ priceBook` — `writeResponse`'s
signature is unchanged); `FrozenCost` and `NewLlmResponse` / `LlmResponse`
(`+ cost: FrozenCost?`); `StudentLlmCost` and `StudentLlmCostDao`; and the
`LlmRequestsResource` render fields (below), including
`FieldType.CURRENCY_NANO_USD` on the shared `admin-web` render layer.

### Admin visibility (`admin-web`)

`LlmRequestsResource` gains two detail-only fields, not three. Rather than a
resource-local formatter and a second, redundant field carrying a pre-formatted
duplicate of the first, cost reuses the codebase's existing, general
`FieldType`-dispatch rendering convention (`admin-web/.../render/CellRender.kt`
— the single place the date and boolean display conventions already live):

```kotlin
// admin-web/.../engine/AdminField.kt — new FieldType case
CURRENCY_NANO_USD  // a stored nano-dollar integer; rendered as USD at render time (below)

// admin-web/.../resources/LlmRequestsResource.kt
AdminField("costNanodollars", "Cost (USD)", FieldType.CURRENCY_NANO_USD, editable = false, sensitive = false, inList = false)
AdminField("costIsEstimated", "Cost Estimated", FieldType.BOOL, editable = false, sensitive = false, inList = false)
```

`FieldType.CURRENCY_NANO_USD` dispatches to a new `renderCurrencyValue` in
`CellRender.kt`, mirroring `FieldType.TIMESTAMP`'s existing shape exactly: the
raw stored value renders formatted (nano-dollars / 1e9 at 6 dp, via
`BigDecimal`, no binary-float rounding) with the exact raw integer carried in a
hover `title`, and a value that fails to parse falls back to raw text at WARN
rather than throwing — one raw field, one render-time conversion, no second
field to keep in lockstep by hand. Both read fields are already on `LlmResponse`
(`cost?.nanodollars`, `cost?.estimated`), beside the `modelResolved` the
resource already renders — no new page, no new DAO. They stay off the list
(already at its column budget); the WARN, not a column, is the detection path
for an unrecognized model.

### Error Handling / Edge Cases

- **Unknown resolved model at write time** (a new snapshot, an alias repoint, a
  server-side fallback) → priced at `llmPricing.default`, `cost_is_estimated`
  freezes `true`, and the write boundary WARNs with the id (see `costOf`).
- **Unmodeled price dimensions.** `inference_geo` (a 1.1× multiplier on every
  token class), 1h-TTL cache writes (2× input, vs `cacheWrite`'s 5-minute 1.25×
  rate, on the same undifferentiated `cache_write_tokens` scalar), and
  long-context tiers (`claude-sonnet-4-6` bills its full 1M window at standard
  rates) are all unused today, so the flat `model → 4 rates` shape is exact.
  Each would need a new price-book dimension; enabling one without adding it
  undercounts **silently** — the cost stays non-null and wrong, so neither
  counter flags it.
- **Null usage** — every non-`Completed` terminal and every cancellation/defect
  catch reaches `writeResponse` with `usage = null`. The provider call ran, so
  spend is possible but unquantifiable: the cost freezes `NULL` and the row is
  counted in `uncostedCalls`. Closing this gap means carrying usage on the
  failure terminals, a `:chat` change out of this RFC's scope.
- **Unreported usage on a `Completed`** — non-null usage is not known usage:
  `AnthropicChatProvider` projects a `Completed` carrying no parseable `usage`
  object as `TokenUsage(null, null, null, null)`, and `TokenUsage`'s four `Int?`
  fields go null _individually_. Either base count null → `costOf` returns
  `null`, the cost freezes `NULL`, and the row joins `uncostedCalls` — the only
  `costOf` null reachable in production, since the other needs a `default`-less
  book (`EMPTY`) and no root wires one.
- **Repair writes** (`writeCancelledIfAbsent` / `writeInternalErrorIfAbsent`) →
  `cost_nanodollars = 0` (see the write-boundary section).
- **Rate that is negative, or finer than a tenth of a cent per MTok** →
  `LlmPriceBook.from` returns `Result.failure`; the root's `getOrThrow` fails
  startup rather than freeze a silently-rounded or negative cost.
- **Crash-window orphan** (RFC 106) → a call no owner references is absent from
  `student_llm_cost`, so its cost is unattributed exactly as its tokens are; the
  meter undercounts by orphans, never misattributes.
- **Retroactive re-pricing is out of scope**: every row carries `created_at`, so
  a time-versioned price schedule stays an additive RFC.

### Dependencies

No new third-party dependency; `:service`'s **test** source set adds a reference
to the catalog's existing `logback-classic` alias (`web-common`'s precedent) for
the WARN assertion. The pinned version is untouched: it is the runtime logging
backend of every deployed root, so a bump is its own change, not this RFC's.
`:service` already depends on `:chat` (for `TokenUsage`) and `:db`;
`LlmPriceBook` is pure `:service`; `admin-web` already depends on `:db`. The
nano-dollar conversion reuses `java.math.BigDecimal` (JDK) at two points — once
at config-load (`LlmPriceBook.from`'s `$per_MTok → nano-dollars-per-token`),
once at admin render time (`Nanodollars.toUsdString`, called from
`CellRender.renderCurrencyValue`) — no `BigDecimal` reaches the hot path, the
DB, or any stored model (the column is `BIGINT`, the Kotlin type is
`Nanodollars`, itself a zero-cost `Long` wrapper). `Nanodollars` is a new,
first-party type in `:common`; `:db`, `:service`, and `admin-web` all already
declare `implementation(project(":common"))`, so it introduces no new module
dependency edge.

## Tests

### `db` — `LlmCallsDaoTest` (extend)

- **cost round-trips** — a `NewLlmResponse` with
  `cost = FrozenCost(nanodollars = Nanodollars.of(4200L), estimated = false)`
  persists and reads both columns back as one `FrozenCost` on `LlmResponse`; one
  with `cost = null` reads back `null`; one with `estimated = true` round-trips
  the flag.
- **cost non-negative CHECK** — a raw `llm_responses` INSERT with
  `cost_nanodollars = -1` raises the `llm_responses_cost_nonneg_check`
  violation. Raw SQL, not `NewLlmResponse` — `FrozenCost` makes the negative
  value unconstructable in Kotlin (`Nanodollars`'s own `init` guard rejects it
  first), so this CHECK is now reachable only by bypassing the Kotlin layer
  entirely, which is itself evidence the type-level guard works.
- **estimated-flag pairing CHECK** — a raw INSERT with a non-null
  `cost_nanodollars` and a null `cost_is_estimated`, and one with the reverse,
  each raise `llm_responses_cost_estimated_check`. Also raw SQL for the same
  reason: `FrozenCost` makes the pair disagree only by dropping to the column
  level directly.
- **cost is immutable** — no separate test: `prevent_log_update` is a row-level
  trigger, not column-scoped, so the existing append-only `UPDATE`/`DELETE`
  guard assertions already cover the new columns.

### `db` — `StudentLlmCostDaoTest` (new, replaces `StudentLlmTokenUsageViewTest`)

Seeds, for two students, calls owned by all four owners (a `convo_requests`
call, an `extraction_runs` call, a `synthesis_runs` call, a `fit_lens_runs`
query+reason pair), plus one **unattributed** call (no owner), one call owned by
a **soft-deleted** convo, one **uncosted** (`cost_nanodollars = NULL`) call, and
one **estimated** (`cost_is_estimated = true`) call.

- **lifetime sum** — `lifetimeCost` returns each student's summed
  `cost_nanodollars` across all four owners; the fit-lens pair's two calls both
  count to the right student.
- **unattributed excluded** — the orphan contributes to no student's total.
- **soft-deleted convo included** — its call's cost is summed into its student's
  total (the join ignores `convos.deleted_at`).
- **uncosted surfaced** — `uncostedCalls` counts the `NULL`-cost call and it
  contributes `0` to the summed cost (`COALESCE`).
- **estimated surfaced** — `estimatedCalls` counts the default-priced call, and
  its cost **is** in the sum (unlike an uncosted call); the counters being
  independent, a student with one of each reads `(uncosted = 1, estimated = 1)`.
- **windowed bounds** — `windowedCost(student, start, end)` sums only calls with
  `created_at ∈ [start, end)`: a call at exactly `start` is included, one at
  exactly `end` excluded, one before `start` excluded.
- **inverted window rejected** — `windowedCost` throws
  `IllegalArgumentException` for `periodStart == periodEnd` and for
  `periodStart > periodEnd`, rather than reading `(0, 0, 0)`.
- **no-spend student** — a student with no attributed call reads `(0, 0, 0)`.

### `service` — `LlmPriceBookTest` (new)

- **`from` parses rates** — `costOf("claude-sonnet-4-6", usage)` for known token
  counts equals the hand-computed integer dot-product (e.g. `1000` input @
  `$3/MTok` = `3_000_000` nano-dollars), with `estimated = false`.
- **unknown model → default rate, flagged** — `costOf("some-new-model", usage)`
  returns a `FrozenCost` whose `nanodollars` equals the dot-product under
  `llmPricing.default` and whose `estimated` is `true`. Asserting the amount,
  not just non-nullity, pins the default to its configured rates.
- **unreported usage → null** —
  `costOf(priced, TokenUsage(null,null,null,null))` returns `null`, not `0L`; so
  does `TokenUsage(null,null,0,0)`, whose cache-only counts would otherwise
  price to a false `0`; so does an **unknown** model with unreported usage.
- **null cache classes price as zero** —
  `costOf(priced, TokenUsage(1000,10,null,null))` equals the input+output
  dot-product; `TokenUsage(0,0,0,0)` returns `0L`.
- **negative base token count prices to null instead of throwing** —
  `costOf(priced, TokenUsage(-1,10,null,null))` and the output-negative mirror
  both return `null`, not a negative or thrown cost — a corrupt base count is
  treated the same as a missing one.
- **negative cache token count coerces to zero rather than voiding the whole
  cost** — `costOf(priced, TokenUsage(1000,10,-1,null))` equals the plain
  input+output dot-product, the same result a `null` cache count would give.
- **rate guards** — `from` returns `Result.failure` on a config with
  `input = 0.0005` (finer than a tenth of a cent) and on one with
  `input = -3.00`.
- **missing default → failure** — `from` on a config with `llmPricing.models`
  but no `llmPricing.default` returns `Result.failure`.
- **`requireExplicitlyPriced`** — succeeds when every named model has its own
  entry; fails naming the missing model otherwise, **including** a model the
  book would price at the default rate (priceability is universal in a
  configured book, so only the explicit-entry assertion pins the guard's
  purpose).
- **`EMPTY` prices nothing** — `costOf(anyModel, fullUsage)` on
  `LlmPriceBook.EMPTY` returns `null`, including for a model a configured book
  would reach the default for. This is the no-entry-and-no-default null case:
  `EMPTY` carries no `default`.

### `service` — `LlmCallLogTest` (extend)

Both new cases reuse the file's existing `completed(text, modelResolved)`
terminal factory (widened with a `modelResolved` parameter — its default
reproduces every pre-existing call site's behavior unchanged) rather than a
second, near-duplicate factory, keeping one definition of the terminal's shape.

- **completed freezes cost** — a fake `Completed` with known usage, whose
  `modelResolved` is priced, writes a `cost` whose `nanodollars.value` equals
  the price book's computation and `estimated = false`; the row's tokens and
  cost agree.
- **resolved model is the pricing key** — a `Completed` whose `modelResolved`
  differs from the request's `model`, with **only the resolved one priced**,
  writes the resolved model's cost and `estimated = false`; the mirror case
  (only the _requested_ one priced) writes the **default** cost and
  `estimated = true`. Both directions are needed — either alone passes under
  both keyings — and the mirror case doubles as the unknown-resolved-model →
  default assertion.
- **estimated model WARNs** — that mirror case also asserts the WARN naming the
  unrecognized `model_resolved`, captured by a `ListAppender` on the root logger
  (`RequestLoggingRoutingTest`'s idiom; `service/build.gradle.kts` gains
  `testImplementation(libs.logback.classic)`). It is the sole detection path for
  a provider-side rename, so it is asserted, not assumed.
- **failure terminal → NULL** — a `Rejected` terminal (usage is always null, and
  `LlmCallOutcome.Failed` carries no resolved model) writes `cost = null`, not a
  zero cost.
- **repair writes cost 0** — `writeCancelledIfAbsent` writes
  `cost = FrozenCost(nanodollars = Nanodollars.of(0), estimated = false)`.

### `rest-server` / `queue-worker` — boot check

No new root test files. The boot-check _behavior_ is unit-tested on
`LlmPriceBook.requireExplicitlyPriced` (above). Its _invocation_ in
`rest-server` is already exercised at runtime by eight existing integration
tests that call `startServer(wait = false, port = 0)` in setup
(`HealthzRoutingTest`, `AuthRoutingTest`, `CollegeListRoutingTest`,
`ConvoRoutingTest`, `GoogleAuthRoutingTest`, `StudentRoutingTest`,
`EmailVerificationRoutingTest`, `plugins/EmailVerificationGateTest`);
`startServer` loads the real `service.conf`, so after step 5 a malformed rate, a
missing `default`, or a `coaching.model` absent from `models` fails all eight at
`@BeforeAll`. That coupling is the intended detection path and is why step 5
runs one of them as its gate. `queue-worker` has no test source, so its
invocation is compile-verified only.

### `admin-web` — `LlmRequestsResourceTest` (extend)

- **cost renders** — a seeded `LlmCall` with
  `cost = FrozenCost(nanodollars = Nanodollars.of(3_000_000), estimated = false)`
  renders the `CURRENCY_NANO_USD` value as `"0.003000"` with the raw integer
  `"3000000"` in the hover title, and the `costIsEstimated` field label. A
  `NULL`-cost call renders without error with the cost field label present (a
  binary presence check — a human operator will notice a genuinely missing
  field; the stronger per-value assertion was considered and deliberately not
  taken, since a bare `body.contains("bool-false")`-style check is satisfied by
  the page's own stylesheet regardless of the actual value, and a row-anchored
  regex is brittle to unrelated rendering changes).

## Invariants

### Per-call cost is frozen at the write boundary

**Rule:** A call's `cost_nanodollars` MUST be computed at an `LlmCallLog` write
site from the price book in effect then, and MUST NOT be recomputed or
backfilled from the current price book afterward. A cost that cannot be computed
— the call's token usage is unknown — MUST freeze `NULL`; `0` MUST be written
only for a call that provably spent nothing.

**Why:** The meter gates a live session — spend-to-date is read on every request
to decide whether coaching may continue — so if a past call's cost can change, a
rate rise silently inflates every prior call and locks students out of coaching
they already earned, with no change in their behavior and no way for support to
explain it. Freezing makes what a student has "spent" depend only on what they
did, not on what a provider later charged. The same logic forbids the softer
failure: writing `0` where spend is merely unknown reads a billed call as free,
where `NULL` keeps that gap visible and countable (`uncostedCalls`). The
append-only guard blocks a same-row rewrite, but a re-derivation writing a _new_
aggregate elsewhere is not DB-preventable — this is a write-path discipline.

**Target directory:** `service/src/main/kotlin/ed/unicoach/coaching`
(`INVARIANTS.md`).

## Implementation Plan

1. **`db` (a): migration `0041` + cost on the call log, stated at every call
   site.** Add `db/schema/0041.add-llm-response-cost.sql` exactly as specified
   above. Add `FrozenCost` (new, `db.models`) pairing `nanodollars`/`estimated`
   as one value, and give `NewLlmResponse` / `LlmResponse` a single
   `cost: FrozenCost?` (undefaulted) in place of two independently-nullable
   fields, and wire all three `LlmCallsDao` edit points (`appendResponse` bind,
   `mapResponse` read, `joinedCallColumns` alias). Because the field is
   undefaulted, **every** construction must state it in this step or `:service`
   stops compiling — the two production ones in `LlmCallLog.kt` included:
   `writeTerminalIfAbsent` passes its final
   `FrozenCost(nanodollars = Nanodollars.of(0), estimated = false)`,
   `writeResponse` passes `null` as a placeholder step 4 replaces. Pass `null`
   at the nine existing test `NewLlmResponse` constructions in
   `LlmCallsDaoTest`, `ConvosDaoTest`, and `AdminTestSupport` and at
   `ConvoProjectionTest`'s `LlmResponse` construction; give `LlmCallsDaoTest` a
   private `newResponse(requestId, cost: FrozenCost?)` helper (no default on
   `cost`) so its four repeated constructions share one definition while every
   call site still states a cost explicitly; give
   `AdminTestSupport.seedLlmResponse` its own defaulted `cost: FrozenCost?`
   parameter, which step 6 seeds through. Extend `LlmCallsDaoTest` (cost
   round-trip + both CHECKs, the latter via raw SQL — see `Tests`).
   - Verify: `nix develop -c bin/test db -f --tests "*LlmCallsDaoTest*"`;
     `nix develop -c bin/test db -f --tests "*ConvosDaoTest*"`;
     `nix develop -c ./gradlew :service:compileTestKotlin
     :admin-web:compileTestKotlin`.
2. **`db` (b): per-student cost read.** Add the `StudentLlmCost` model and
   `StudentLlmCostDao` (`lifetimeCost`, `windowedCost`) over the spine view.
   Replace `StudentLlmTokenUsageViewTest` with `StudentLlmCostDaoTest`; fix the
   two stale test comments referencing the old view.
   - Verify: `nix develop -c bin/test db -f`.
3. **`service`: price book.** Add `LlmPriceBook` / `ModelPrice` (`from`,
   `costOf`, `requireExplicitlyPriced`) and the `llmPricing` block — `models`
   plus the required `default` — in `service.conf`. `costOf` returns `:db`'s
   `FrozenCost?` directly — no service-layer duplicate type, since `:service`
   already depends on `:db`. Add `LlmPriceBookTest`.
   - Verify: `nix develop -c bin/test service -f --tests "*LlmPriceBook*"`.
4. **`service`: freeze cost in `LlmCallLog`.** Add the `priceBook` constructor
   param and replace `writeResponse`'s step-1 `null` placeholder with the
   price-book derivation (signature and all six call sites unchanged), as two
   named steps — a pure `costOf(outcome, usage): FrozenCost?` and a
   `warnIfEstimatedDefault(outcome, cost)` that owns the WARN on an `estimated`
   result — rather than one function computing and logging together.
   `writeTerminalIfAbsent` is already final from step 1 and is not touched. Add
   `testImplementation(libs.logback.classic)` to `service/build.gradle.kts` and
   extend `LlmCallLogTest` (cost + the WARN capture), switching its `log`
   helper's positional `nanoTime` argument to a named one.
   - Verify: `nix develop -c bin/test service -f --tests "*LlmCallLog*"`.
5. **Composition roots.** Build `LlmPriceBook.from(config).getOrThrow()` beside
   the sibling `*Config.from(config).getOrThrow()` calls in each root's
   config-loading function — `startServer()` in `rest-server`, `main()` in
   `queue-worker` — and **outside** `queue-worker`'s
   `if (extraction || synthesis || fitLens)` gate, so a malformed `llmPricing`
   fails boot even when every pass is disabled. Only
   `requireExplicitlyPriced(...)` + `getOrThrow` sits inside the gate, where the
   enabled subset is known; `rest-server` passes `coaching.model`. Pass the book
   into `LlmCallLog(...)`. No new root test (see `Tests`).
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`;
     `nix develop -c ./gradlew :queue-worker:compileKotlin`;
     `nix develop -c bin/test rest-server -f --tests "*HealthzRoutingTest*"`
     (boots `startServer`, so it exercises the new `from` + boot check against
     the real `service.conf` — a compile gate cannot).
6. **`admin-web`: per-call cost fields.** Add `FieldType.CURRENCY_NANO_USD` to
   the shared `admin-web` render layer (`AdminField.kt`'s enum,
   `CellRender.kt`'s dispatch + a new `renderCurrencyValue`, mirroring
   `FieldType.TIMESTAMP`'s shape). `renderCurrencyValue` shares a common
   `renderParsedOrRaw` helper with `renderJsonValue` and `renderTimestampValue`
   — both pre-existing renderers rewritten (behavior-preserving) to route
   through it rather than each carrying its own copy of the same
   parse-or-fallback skeleton. Add the `costNanodollars` (typed
   `CURRENCY_NANO_USD`) / `costIsEstimated` `AdminField` rows and their `cells`
   entries to `LlmRequestsResource` — two fields, not three; no separate
   pre-formatted `costUsd` field, since formatting happens at render time.
   Extend `LlmRequestsResourceTest`.
   - Verify: `nix develop -c bin/test admin-web -f`.
7. **Invariant.** Add the "Per-call cost is frozen at the write boundary" rule
   to `service/src/main/kotlin/ed/unicoach/coaching/INVARIANTS.md`.
   - Verify:
     `grep -q 'frozen at the write boundary'
     service/src/main/kotlin/ed/unicoach/coaching/INVARIANTS.md`;
     `nix develop -c bin/format -c`.
8. **Brief.** In `features/paid-subscriptions.md`, mark the `llm-cost-ledger`
   node `implemented` with rfc `108` in the living index, and reconcile
   **every** reference to an artifact this RFC renamed
   (`student_llm_token_usage` → `student_llm_cost` / `StudentLlmCostDao`) —
   narrative prose and still-pending kickoff prompts alike.
   - Verify:
     `! grep -q 'student_llm_token_usage\|StudentLlmTokenUsageViewTest'
     features/paid-subscriptions.md`;
     `grep -E '\|.*llm-cost-ledger.*\|.*implemented.*\|.*108'
     features/paid-subscriptions.md`;
     `nix develop -c bin/format -c`.
9. **Full gate.** Whole suite + formatter.
   - Verify: `nix develop -c bin/test -f`; `nix develop -c bin/format -c`.

## Files Modified

**`common` — money value type**

- `common/src/main/kotlin/ed/unicoach/common/money/Nanodollars.kt` — new.
  `@JvmInline value class Nanodollars` wrapping the nano-dollar `Long`, guarding
  non-negativity once and owning `toUsdString()` beside the unit it formats.
  `:db`, `:service`, and `admin-web` already depend on `:common`, so this
  introduces no new module dependency edge.

**`db` — migration**

- `db/schema/0041.add-llm-response-cost.sql` — new.

**`db` — models + DAO**

- `db/src/main/kotlin/ed/unicoach/db/models/FrozenCost.kt` — new. Pairs
  `nanodollars: Nanodollars` and `estimated: Boolean` as one value, mirroring
  the DB CHECK's pairing; `LlmPriceBook.costOf` (`:service`, one layer up)
  returns this same type directly rather than a service-layer duplicate, so the
  two facts can never be constructed disagreeing.
- `db/src/main/kotlin/ed/unicoach/db/models/NewLlmResponse.kt` — replace
  `costNanodollars: Long?` / `costIsEstimated: Boolean?` with a single
  `cost: FrozenCost?`, undefaulted.
- `db/src/main/kotlin/ed/unicoach/db/models/LlmResponse.kt` — same.
- `db/src/main/kotlin/ed/unicoach/db/dao/LlmCallsDao.kt` — `appendResponse` bind
  (`input.cost?.nanodollars?.value`, `input.cost?.estimated`), `mapResponse`
  read (reconstructs one `FrozenCost?`), `joinedCallColumns` alias.
- `db/src/main/kotlin/ed/unicoach/db/dao/SqlSessionQueries.kt` — add
  `setBooleanOrNull`, the nullable-Boolean JDBC binder `appendResponse` uses for
  `cost_is_estimated` (mirrors the existing `setLongOrNull`).
- `db/src/main/kotlin/ed/unicoach/db/models/StudentLlmCost.kt` — new;
  `costNanodollars: Nanodollars`.
- `db/src/main/kotlin/ed/unicoach/db/dao/StudentLlmCostDao.kt` — new; reads wrap
  the summed `Long` in `Nanodollars.of(...)`.
- `db/src/test/kotlin/ed/unicoach/db/dao/LlmCallsDaoTest.kt` — cost round-trip
  (via a private `newResponse(requestId, cost: FrozenCost?)` helper with no
  default on `cost`, so every construction still states one explicitly) + both
  CHECKs (now raw SQL — `FrozenCost` makes the invalid combinations
  unconstructable in Kotlin).
- `db/src/test/kotlin/ed/unicoach/db/dao/ConvosDaoTest.kt` — state `cost` at its
  `NewLlmResponse` construction.
- `db/src/test/kotlin/ed/unicoach/db/dao/StudentLlmTokenUsageViewTest.kt` —
  delete.
- `db/src/test/kotlin/ed/unicoach/db/dao/StudentLlmCostDaoTest.kt` — new.
- `db/src/test/kotlin/ed/unicoach/db/dao/ExtractionRunsDaoTest.kt` — fix stale
  `student_llm_token_usage` / `StudentLlmTokenUsageViewTest` comment.
- `db/src/test/kotlin/ed/unicoach/db/dao/SynthesisRunsDaoTest.kt` — same comment
  fix.

**`service`**

- `service/src/main/resources/service.conf` — add the `llmPricing` block.
- `service/src/main/kotlin/ed/unicoach/coaching/LlmPriceBook.kt` — new
  (`LlmPriceBook` + `ModelPrice`, all nano-dollar fields typed `Nanodollars`;
  `costOf` returns `:db`'s `FrozenCost?` directly).
- `service/src/main/kotlin/ed/unicoach/coaching/LlmCallLog.kt` — `priceBook`
  ctor param; `writeResponse` calls two named steps — a pure `costOf` (cost
  derivation) and `warnIfEstimatedDefault` (the estimated-model WARN, split out
  so `costOf` stays a pure, independently-testable function);
  `writeTerminalIfAbsent` passes
  `FrozenCost(nanodollars = Nanodollars.of(0), estimated = false)`.
- `service/src/main/kotlin/ed/unicoach/coaching/INVARIANTS.md` — add the
  frozen-cost rule.
- `service/build.gradle.kts` — add `testImplementation(libs.logback.classic)`.
- `service/src/test/kotlin/ed/unicoach/coaching/LlmPriceBookTest.kt` — new.
- `service/src/test/kotlin/ed/unicoach/coaching/LlmCallLogTest.kt` — cost
  (`.cost?.nanodollars?.value`, `.cost?.estimated`) and WARN assertions; named
  `nanoTime` argument in its `log` helper; the file's existing `completed`
  terminal factory widened with a `modelResolved` parameter rather than adding a
  second, near-duplicate factory.
- `service/src/test/kotlin/ed/unicoach/coaching/ConvoProjectionTest.kt` — state
  `cost` at its `LlmResponse` construction.

**`rest-server`**

- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — build the
  price book at the top of `startServer()`,
  `requireExplicitlyPriced(coaching.model)`, pass into `LlmCallLog`.

**`queue-worker`**

- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` — build the
  price book at the top of `main()` (outside the enabled-gate),
  `requireExplicitlyPriced` on the enabled models inside it, pass into
  `LlmCallLog`.

**`admin-web`**

- `admin-web/src/main/kotlin/ed/unicoach/admin/engine/AdminField.kt` — add
  `FieldType.CURRENCY_NANO_USD`.
- `admin-web/src/main/kotlin/ed/unicoach/admin/render/CellRender.kt` — dispatch
  `CURRENCY_NANO_USD` to a new `renderCurrencyValue`, mirroring
  `renderTimestampValue`'s raw-in/formatted-display-out/hover-title shape;
  `renderCurrencyValue` shares a new `renderParsedOrRaw` helper with
  `renderJsonValue` and `renderTimestampValue`, both rewritten
  (behavior-preserving) to route through it.
- `admin-web/src/test/kotlin/ed/unicoach/admin/render/CellRenderTest.kt` —
  extend with `CURRENCY_NANO_USD` render cases: blank, valid, non-numeric
  fallback, negative-value fallback.
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/LlmRequestsResource.kt`
  — add the `costNanodollars` (`CURRENCY_NANO_USD`) / `costIsEstimated` fields
  and cells — two fields, not three; no separate pre-formatted `costUsd` field.
- `admin-web/src/test/kotlin/ed/unicoach/admin/resources/LlmRequestsResourceTest.kt`
  — assert cost renders via `CURRENCY_NANO_USD` (formatted value + raw integer
  in the hover title); NULL-cost case asserts the field label renders, not a
  per-value glyph check (see `Tests`).
- `admin-web/src/test/kotlin/ed/unicoach/admin/AdminTestSupport.kt` —
  `seedLlmResponse` gains a defaulted `cost: FrozenCost?` parameter and binds
  it.

**Feature brief**

- `features/paid-subscriptions.md` — mark `llm-cost-ledger` implemented (rfc
  108); reconcile every reference to a renamed artifact.
