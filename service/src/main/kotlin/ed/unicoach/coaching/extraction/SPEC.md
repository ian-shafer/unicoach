# SPEC: coaching/extraction

## I. Overview

This directory is the **per-conversation extraction pass**: the background step
that turns a window of finished coaching turns into durable memory —
**observations** (immutable verbatim records of what the student said) and
**claims** (the coach's revisable beliefs), linked through `claim_support`. It
runs only in the queue worker, never on the chat request path: a coaching turn
enqueues an `EXTRACT_CONVERSATION` job, and this package distills the transcript
via one LLM call and persists the resulting structure.

## II. Behavioral Contracts

### `ExtractionService.extract(convoId, throughRequestId): ExtractionResult`

The core pass. See [ExtractionService.kt](./ExtractionService.kt). Runs in three
phases — a **read transaction**, the **LLM call outside any transaction**, then
a **write transaction** — so the multi-second provider call holds neither a
pooled connection nor the student advisory lock.

**Behavior, phase by phase:**

- **Read phase** (one DB transaction): loads the conversation (all soft-delete
  scopes), acquires the per-**student** advisory lock, reads the conversation's
  watermark, assembles the turn window, loads the student's active claims, and
  resolves the extraction system prompt.
  - A **soft-deleted** conversation (`deletedAt != null`) returns a no-op
    success — no run row appended.
  - When `throughRequestId.value <= watermark`, returns a no-op success (a
    coalesced duplicate job).
  - The window is the turns whose `request.id` lies in
    `(watermark, throughRequestId]`, then truncated to the **oldest**
    `windowMaxTurns` (the safety cap). When the cap trims the range, the
    **effective through-request id** becomes the last kept turn's id, so the
    watermark later advances only over what was actually distilled — newer turns
    are left for a later pass.
  - An empty window (e.g. every turn in range soft-deleted out of `listTurns`)
    returns a no-op success.
- **LLM call** (no transaction): builds a `ChatRequest` (model, prompt body as
  system, the assembled transcript as a single user message, `maxTokens`) and
  calls the injected `ChatProvider`. The prompt is the seeded `extraction` /
  `v1` system prompt; the user message contains the active-claim list and the
  transcript window (each user turn tagged with its request id, paired with the
  coach reply).
- **Write phase** (one DB transaction): re-acquires the student lock, re-reads
  the watermark, re-loads the active-claim set, applies observation/claim
  writes, recomputes confidence, and appends the run row.

**Outcomes** (sealed [ExtractionResult](./ExtractionResult.kt)):

- `ExtractionResult.Success` — the pass applied writes, or was an idempotent /
  soft-deleted / lost-race no-op.
- `ExtractionResult.TransientFailure(message, cause?)` — a read-phase exception,
  a provider error, an unparseable `Completed` output, a stale supersede /
  reinforce target, or a write-phase exception.

**Provider-terminal branching** (on the `ChatEvent` returned by the accumulating
`chat()` call):

- `ChatEvent.Rejected` / `ChatEvent.TransientFailure` — no billed, usable call;
  **no run row** is written and the result is `TransientFailure`. Nothing to
  account.
- `ChatEvent.Completed` — the call is billed regardless of JSON content. Its
  `TokenUsage` and resolved model are captured. The text content is parsed:
  - **Unparseable / invalid** output writes a `failed` `extraction_runs` row
    carrying the call's token usage (see `writeFailedRun`), then returns
    `TransientFailure`. The watermark does not advance.
  - **Valid** output proceeds to the write phase.

**Side Effects:**

- **One LLM call** per non-no-op pass, via the injected `ChatProvider`.
- **DB writes** under the held student lock: inserts into `observations`,
  `claims` (for `new` and `supersede` replacements), `claim_support` links,
  `claims` lifecycle revisions (supersession + confidence), and exactly one
  `extraction_runs` row.
- **Advisory lock**: `pg_advisory_xact_lock` keyed on the student id, taken in
  the read transaction and again in each write transaction; released on each
  transaction's commit/rollback, and never held across the LLM call.
- **Token ledger**: every billed `Completed` call records its input/output/cache
  token usage on its `extraction_runs` row (`applied` or `failed`), so retried
  passes never spend unrecorded tokens.

**Watermark & idempotency:** the watermark is the highest `through_request_id`
over the conversation's `applied` runs (0 if none). It is read in the read phase
to window the pass, and **re-read under the held write lock** before any
persistence; a concurrent same-conversation pass that already advanced past the
target makes the write phase a lost-race no-op (returns `Success`, writes
nothing). This is what makes at-least-once job delivery idempotent for a
conversation's stream without unique constraints on derived rows.

**Per-student serialization:** the advisory lock keyed on the student id
serializes all passes for that student (across all of the student's
conversations) so concurrent passes cannot race on shared
supersede/reinforce/confidence writes. Distinct students hash to distinct keys
and run in parallel. The active-claim set is re-loaded under the write lock so
`supersede`/`reinforce` ops validate against the fresh set, not the read-phase
snapshot.

#### Write-phase application (`applyWrites`)

On valid output, under the held student lock:

1. **Observations**: each parsed observation is inserted. An observation citing
   a `sourceRequestId` outside the window's turn ids throws (→
   `TransientFailure`).
2. **Claim ops**, in order:
   - `new` — creates a claim; links its cited supports.
   - `reinforce` — resolves the `targetClaimId` against the fresh active set and
     links additional supports to it.
   - `supersede` — resolves the `targetClaimId`, creates a replacement claim,
     links its supports, and revises the target to `superseded` pointing at the
     replacement.
   - A `reinforce` / `supersede` op whose `targetClaimId` is missing or not in
     the fresh active set raises a stale-target failure (→ `TransientFailure`),
     mutating no claim.
   - A `supports` index out of range of the inserted observations throws (→
     `TransientFailure`).
3. **Confidence recompute**: for every touched claim still `active`, confidence
   is recomputed from its full `claim_support` set (see below) and written via a
   `claims` revision. The support read and the confidence write happen under the
   same lock, so a concurrent same-student pass cannot interleave a
   `claim_support` insert between them.
4. **Run row**: appends one `applied` `extraction_runs` row with
   `through_request_id = throughRequestId` (advancing the watermark),
   prompt/provider/model provenance, write counts, and token usage.

A window that yields no observations or claims still appends an `applied` run
(zero counts) and advances the watermark.

**Idempotency:** re-running with `throughRequestId <= watermark` writes nothing
and returns `Success`. At-least-once delivery is safe because of the watermark
re-check; a `Completed` call writes exactly one `extraction_runs` row.

#### Confidence computation

`confidence = round(1000 * (1 - exp(-Σ wᵢ)))`, where each supporting observation
contributes `wᵢ = 0.5 ^ (age_days(uttered_at) / confidenceHalfLifeDays)`,
clamped to `[0, 1000]`. With no support the confidence is 0. The age in days is
`(now − uttered_at)` in seconds over 86 400, floored at 0. Recurrence raises
confidence with diminishing returns; each contribution halves every half-life.
The value is computed in code from the support set at pass time — the LLM never
assigns it.

#### Output parsing

`parseOutput` reads a **strict-JSON** document `{ observations, claims }` via
the kotlinx JSON element DSL (no `@Serializable` plugin in `service`). It
returns a structured failure naming the offending field/value on any structural,
type, or enum-membership error:

- `observations[]`: each has an integer `sourceRequestId` and a non-blank string
  `quote`.
- `claims[]`: each has `op` ∈ {`new`, `reinforce`, `supersede`}, a non-blank
  `statement`, the closed enums `kind` / `subject` / `topic` / `origin`, an
  optional `visibility` (defaulting to `student_visible`), an optional integer
  `supports` array (indices into the returned observations), and an optional
  `targetClaimId` (a UUID). A non-`new` op missing `targetClaimId` is a parse
  failure.

A malformed root, a non-object root, a missing/typed-wrong field, or an
out-of-set enum value yields a parse failure, which the caller logs (with the
raw output truncated to 2 000 chars) and records as a `failed` run.

### `ExtractionHandler`

The queue `JobHandler` adapter for `JobType.EXTRACT_CONVERSATION`. See
[ExtractionHandler.kt](./ExtractionHandler.kt).

- **Behavior:** deserializes the `ExtractionPayload`
  (`{ convoId, throughRequestId }`) and delegates to
  `ExtractionService.extract`.
- **Config:**
  `JobTypeConfig(concurrency = 4, maxAttempts = 5,
  lockDuration = 10m, executionTimeout = 5m)`.
  The execution timeout is below the queue lock duration so a slow pass cannot
  outlive its lock. Same-student claim-write correctness rests on the student
  advisory lock, not `concurrency = 1`, so distinct students extract in
  parallel.
- **Side Effects:** none of its own; all DB / LLM effects are
  `ExtractionService`'s.
- **Error Handling:** a malformed payload → `JobResult.PermanentFailure` (no
  retry helps). `ExtractionResult.Success` → `JobResult.Success`;
  `ExtractionResult.TransientFailure` →
  `JobResult.RetriableFailure(message,
  cause)`, retried up to `maxAttempts`
  then dead-lettered.
- **Idempotency:** retries are safe — the service's watermark makes a duplicate
  or already-applied target a no-op success.

### `ExtractionConfig`

Typed reader for the `extraction` config block. See
[ExtractionConfig.kt](./ExtractionConfig.kt).

- **Behavior:** `from(config)` returns `Result<ExtractionConfig>`; it **fails**
  (`Result.failure` carrying the underlying `ConfigException`) when a key is
  absent or unreadable, and performs no value validation.
- **Side Effects:** none.

## III. Infrastructure & Environment

**Config keys** (the `extraction` block of `service.conf`; defaults shown, each
overridable by the matching `EXTRACTION_*` env var):

| Key                                 | Type     | Default             | Role                                                       |
| :---------------------------------- | :------- | :------------------ | :--------------------------------------------------------- |
| `extraction.enabled`                | Boolean  | `true`              | Master switch for the chat-path enqueue + handler register |
| `extraction.debounce`               | Duration | `5m`                | Job delay coalescing rapid turns                           |
| `extraction.promptName`             | String   | `extraction`        | `system_prompts.name` to resolve                           |
| `extraction.promptVersion`          | String   | `v1`                | `system_prompts.version` pin                               |
| `extraction.model`                  | String   | `claude-sonnet-4-6` | Model id for the distillation call                         |
| `extraction.maxTokens`              | Int      | `4096`              | Output bound for the call                                  |
| `extraction.windowMaxTurns`         | Int      | `50`                | Safety cap on turns per pass                               |
| `extraction.confidenceHalfLifeDays` | Double   | `30.0`              | Decay half-life for the confidence formula                 |

**Collaborators:**

- `ChatProvider` (`:chat`) — the injected non-streaming distillation call,
  accumulated to a terminal `ChatEvent` via the `chat()` extension.
- `Database` (`:db`) — `withConnection` runs each phase's block in a single
  committed transaction (rollback on exception).
- DAOs (`:db`): `ConvosDao` (convo + turns), `AdvisoryLockDao` (student lock),
  `ExtractionRunsDao` (watermark + run append), `ClaimsDao`, `ClaimSupportDao`,
  `ObservationsDao`, `SystemPromptsDao`.
- `:queue` — `JobHandler` / `JobType` / `JobTypeConfig` / `ExtractionPayload`.

**Persistence:** the pass writes to `observations`, `claims`, `claim_support`,
and `extraction_runs` (migration `0019`), resolving the seeded `extraction`
prompt (migration `0020`). `observations`, `claim_support`, and
`extraction_runs` are append-only; `claims` is a mutable, status-tracked entity.

## IV. History

- [x] [RFC-66: Extraction](../../../../../../../../rfc/66-extraction.md)
