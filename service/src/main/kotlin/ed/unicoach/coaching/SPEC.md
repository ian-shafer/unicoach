# SPEC: `service/src/main/kotlin/ed/unicoach/coaching`

## I. Overview

This directory is the **coaching domain layer**. It owns the business logic for
owner-scoped coaching conversations: starting a conversation with its first
turn, posting subsequent turns, listing/reading conversations with derived
recency, renaming and archiving them, soft-deleting them, and listing their
visible turns. Each turn drives the injected `ChatProvider` port and records
every attempt durably in the convos append-only log. Operations take a resolved
`StudentId` and return pure domain outcomes via sealed interfaces; the layer
carries no transport concern of its own. The `extraction/` subdirectory is a
separate package (its own SPEC) for background memory distillation; it is not
invoked from this directory's code.

---

## II. Behavioral Contracts

### Cross-cutting behavior

- **No transport types**: `CoachingService` imports no Ktor, HTTP, or REST-layer
  type; it consumes only `db`, `chat`, and `common`.
- **Error surfacing**: every method wraps DB/provider work in `runCatching`, so
  an exception from DB access or provider use surfaces as `Result.failure(e)`
  rather than a thrown exception. Expected domain states (validation failure,
  not-found) are modeled as result variants returned via `Result.success`.
- **Connection ownership**: the service holds no raw `java.sql.Connection`; all
  DB access goes through `Database.withConnection`, each block being one
  transaction.
- **Ownership/existence collapse**: every `convoId`-bearing operation loads the
  convo under `SoftDeleteScope.ACTIVE` (`loadOwned` / `findByIdWithActivity`)
  and checks `convo.studentId == studentId` before acting. A row that is
  missing, soft-deleted, or owned by another student collapses to the
  operation's `NotFound` variant — the three cases are indistinguishable to the
  caller. **Archived** convos remain fetchable and writable; the archive axis
  affects listing buckets only, not reachability.
- **Validation altitude**: a `FieldError`'s field name is the name of the input
  it validates (`message`, `name`), so a caller attributes each failure to its
  argument. `ValidationFailure` variants carry these `FieldError`s and short-
  circuit before any DB access.

### `CoachingService.startConvo(studentId, message, name?): Result<StartConvoResult>` — See [CoachingService.kt](./CoachingService.kt)

- **Behavior**: validates `message` (and `name` when present); creates the
  convo; appends the user request row; snapshots visible history; returns a cold
  `reply` flow. When `name` is omitted it is derived from the first message by
  trimming, collapsing whitespace runs (including newlines) to single spaces,
  truncating to `NAME_DERIVATION_MAX` (80), and trimming again, then validated
  through `ConvoName.create` (a non-blank message always yields a valid name;
  `"Conversation"` is the defensive fallback).
- **Side Effects (pre-flight, tx-1)**: resolves the system prompt, creates the
  convo, appends the user request, snapshots visible history. Collecting the
  reply flow runs the provider and writes the response row (tx-2).
- **First-turn failure**: a non-success terminal soft-deletes the just-created
  convo in tx-2 (same transaction as the error row), so no observable
  conversation lacks a visible turn.
- **Error Handling**: `Started(convo, userTurn, reply)` on success;
  `ValidationFailure(fieldErrors)` when `message` is blank-after-trim, when its
  raw length exceeds `MESSAGE_MAX_LENGTH` (100000), or when an explicit `name`
  fails `ConvoName.create`.
- **Idempotency/Safety**: not idempotent — each call creates a convo and a turn.

### `CoachingService.postTurn(studentId, convoId, message): Result<PostTurnResult>` — See [CoachingService.kt](./CoachingService.kt)

- **Behavior**: same two-transaction shape as `startConvo` minus convo creation;
  tx-1 loads + ownership-checks the convo, appends the user turn, snapshots
  visible history, and returns the cold `reply` flow.
- **Side Effects**: tx-1 appends the user request; collecting the reply runs the
  provider and writes the response row (tx-2).
- **Error Handling**: `Started`, `ValidationFailure` (same `message` rules as
  `startConvo`), or `NotFound` (missing, soft-deleted, or foreign convo). An
  **archived** convo yields `Started`.
- **Idempotency/Safety**: not idempotent — each call appends a turn.

### `CoachingService.listConvos(studentId, archive): Result<List<ConvoWithActivity>>` — See [CoachingService.kt](./CoachingService.kt)

- **Behavior/Side Effects**: one read via `ConvosDao.listByStudentWithActivity`
  under the given `ArchiveScope` (soft-deleted rows always excluded). No writes.
- **Idempotency/Safety**: fully idempotent (read-only).

### `CoachingService.getConvo(studentId, convoId): Result<GetConvoResult>` — See [CoachingService.kt](./CoachingService.kt)

- **Behavior/Side Effects**: one read via `ConvosDao.findByIdWithActivity`,
  ownership checked against the loaded row's `studentId`. No writes.
- **Error Handling**: `Found(listing)` or `NotFound` (missing, soft-deleted, or
  foreign).
- **Idempotency/Safety**: fully idempotent (read-only).

### `CoachingService.updateConvo(studentId, convoId, update): Result<UpdateConvoResult>` — See [CoachingService.kt](./CoachingService.kt)

- **Behavior**: one transaction — ownership check, then **rename strictly before
  archive/unarchive** (archive's `SET LOCAL` bypass suppresses the `updated_at`
  trigger for the rest of the transaction), then `findByIdWithActivity` for the
  projection.
- **Side Effects**: rename and/or archive/unarchive writes, then a read for the
  projection; no mutation on a validation failure or `NotFound`.
- **Error Handling**: an empty update (both `name` and `archived` absent) →
  `ValidationFailure`; an invalid `name` (via `ConvoName.create`) →
  `ValidationFailure`; otherwise `Success(listing)` or `NotFound`. The "at least
  one field present" rule is enforced here, not at `ConvoUpdate` construction.
- **Idempotency/Safety**: re-applying the same `name`/`archived` is effectively
  a no-op on observable state.

### `CoachingService.deleteConvo(studentId, convoId): Result<DeleteConvoResult>` — See [CoachingService.kt](./CoachingService.kt)

- **Behavior/Side Effects**: ownership check then `ConvosDao.delete`
  (soft-delete).
- **Error Handling**: `Success` or `NotFound`.
- **Idempotency/Safety**: a second delete returns `NotFound` (soft-deleted rows
  are uniformly invisible).

### `CoachingService.listTurns(studentId, convoId): Result<ListTurnsResult>` — See [CoachingService.kt](./CoachingService.kt)

- **Behavior/Side Effects**: ownership check then `ConvosDao.listTurns`,
  filtered to **visible** turns (`turn.response?.content != null`),
  chronological. No writes.
- **Error Handling**: `Found(turns)` or `NotFound`.
- **Idempotency/Safety**: fully idempotent (read-only).

### Turn execution model

The turn path (`startConvo` / `postTurn`) is **two transactions bracketing one
un-transacted provider call** — the DB connection is never held across the
provider stream:

- **tx-1 (pre-flight, synchronous)**: resolves the system prompt, persists the
  user request, and snapshots the visible-history replay set. This is decided
  before the reply flow is collected, so the user turn is durably committed
  before the provider call begins and survives regardless of outcome.
- **provider call**: runs when the cold `reply` flow is collected.
- **tx-2 (terminal)**: persists exactly **one** `convo_responses` row for the
  request, whatever the provider terminal (success, rejection, transient
  failure, provider defect, or client cancellation). There are **no automatic
  retries** — every attempt is one persisted request/response pair; re-attempt
  is user-initiated.

Per-turn pinning:

- The model is config-pinned (`config.model`), recorded as `model_requested`;
  there is no per-conversation model selection.
- The system prompt is resolved per turn from
  `(config.systemPromptName, config.systemPromptVersion)` and pinned by id on
  the request row. A missing catalog row surfaces as
  `Result.failure(IllegalStateException)` with bracketed name/version, not a
  not-found domain outcome.
- `request_params` is `null` (the model lives in `model_requested`; `maxTokens`
  is reconstructable from config).

Replay set (provider context): the transaction-1 snapshot of **visible** prior
turns, each contributing a `USER` message (rendered request content) then an
`ASSISTANT` message (rendered response content) in `created_at, id` order,
followed by the new user message. A turn is **visible** iff its response row has
non-null content; failed and abandoned turns stay in the append-only log as
audit but do not reach the API projection or the replayed history.

### Reply flow — `Flow<ReplyEvent>` — See [ReplyEvent.kt](./ReplyEvent.kt)

- **Behavior**: a **cold, single-collection** flow; collecting it executes the
  provider call. It emits zero or more `Delta(text)`, then exactly one
  `Terminal` — `Completed(response)` (persisted coach response) or
  `Failed(retriable, reason)`. Only text deltas are relayed as
  `ReplyEvent.Delta` (a `ChatEvent.ContentBlockDelta` carrying a
  `ContentDelta.Text`); thinking, tool, and opaque deltas are dropped.
- **`reason` discipline**: `Failed.reason` is a fixed server-side token
  (`"transient"` / `"permanent"`), never a verbatim provider message. Provider
  reasons are logged (bracketed), not relayed. `Failed.retriable` carries the
  resend-worthiness distinction (`true` for transient/defect, `false` for
  rejection).
- **Side Effects**: collecting writes the tx-2 response row. On **client
  cancellation** (collector cancelled) the in-flight provider call is cancelled
  cooperatively, and the abandoned error row is persisted under `NonCancellable`
  iff no response row was committed yet — so exactly one response row exists per
  request. If the terminal-persistence transaction itself fails, the loss is
  logged (bracketed) and the turn emits `Failed(retriable = true)`: a reply that
  is not durable is not reported as `Completed`.
- **Provider-terminal mapping** (one response row per request, always):

  | Provider terminal     | Response row                                                                              | Emitted                                                 |
  | --------------------- | ----------------------------------------------------------------------------------------- | ------------------------------------------------------- |
  | `Completed`           | full row from `ChatResponse` + raw row from payload                                       | `Completed` (or `Failed(true)` if the tx-2 write fails) |
  | `Rejected`            | error row (`stop_reason = "error"`, null content/model/tokens) + raw row if a body exists | `Failed(retriable = false)`                             |
  | `TransientFailure`    | same error-row shape, raw row if a body exists                                            | `Failed(retriable = true)`                              |
  | exception in the flow | same error-row shape, no raw row                                                          | `Failed(retriable = true)`                              |
  | client cancellation   | same error-row shape (under `NonCancellable`), iff no row yet written                     | nothing (collector is gone)                             |

- **Idempotency/Safety**: not transparently retryable — re-collection is a fresh
  at-least-once provider transmission with no idempotency key; re-attempt is a
  user decision.

### `ConvoContent` — See [ConvoContent.kt](./ConvoContent.kt)

- Single owner of the content-block representation used for persistence and
  provider replay.
- `userContent(text)` → `[{"type":"text","text":text}]` (the stored shape of a
  user turn).
- `renderText(content)` → concatenation of the `text` of every `type == "text"`
  block; `""` for any non-block-array content.
  `renderText(userContent(s)) == s`.

### `ConvoUpdate` — See [ConvoUpdate.kt](./ConvoUpdate.kt)

- Tri-state partial-update carrier: `name` null = untouched / present = set
  (never nulled — the column is `NOT NULL`, so null unambiguously means
  untouched); `archived` null = untouched / `true` = archive / `false` =
  unarchive. The "at least one field present" rule is enforced by `updateConvo`,
  not by construction.

### `CoachingConfig` — See [CoachingConfig.kt](./CoachingConfig.kt)

- `from(config)` reads the `coaching` block and returns
  `Result.failure(ConfigException)` when any key is absent or unreadable. It
  performs no value validation.

### Result sealed interfaces

These carry no HTTP status codes or Ktor types.

| Type                | Variants                                     | See                                            |
| ------------------- | -------------------------------------------- | ---------------------------------------------- |
| `StartConvoResult`  | `Started` · `ValidationFailure`              | [StartConvoResult.kt](./StartConvoResult.kt)   |
| `PostTurnResult`    | `Started` · `ValidationFailure` · `NotFound` | [PostTurnResult.kt](./PostTurnResult.kt)       |
| `GetConvoResult`    | `Found` · `NotFound`                         | [GetConvoResult.kt](./GetConvoResult.kt)       |
| `UpdateConvoResult` | `Success` · `ValidationFailure` · `NotFound` | [UpdateConvoResult.kt](./UpdateConvoResult.kt) |
| `DeleteConvoResult` | `Success` · `NotFound`                       | [DeleteConvoResult.kt](./DeleteConvoResult.kt) |
| `ListTurnsResult`   | `Found` · `NotFound`                         | [ListTurnsResult.kt](./ListTurnsResult.kt)     |
| `ReplyEvent`        | `Delta` · `Terminal`(`Completed` · `Failed`) | [ReplyEvent.kt](./ReplyEvent.kt)               |

---

## III. Infrastructure & Environment

- **Module**: `service` (Gradle). Depends on `db` (`Database`, `ConvosDao`,
  `SystemPromptsDao`, convo/prompt models), `chat` (`ChatProvider`,
  `ChatRequest`, `ChatEvent`, `ChatMessage`), and `common` (`FieldError`,
  `ValidationResult`). Constructed with constructor DI:
  `CoachingService(database, chatProvider, config)`.
- **Configuration**: reads the HOCON `coaching` block of `service.conf` via
  `CoachingConfig.from` — keys `coaching.model`, `coaching.maxTokens`,
  `coaching.systemPromptName`, `coaching.systemPromptVersion`. Production wiring
  fails fast at boot on absent keys.
- **Database**: requires a live PostgreSQL pool via `Database`. The
  two-transaction turn model relies on each `Database.withConnection` block
  being a single transaction; the first-turn-failure soft-delete shares the
  error-row transaction.
- **Coroutine context**: `suspend` methods are called from a coroutine scope.
  The layer performs no dispatcher switching of its own, except the
  `NonCancellable` block used to persist an abandoned turn during cancellation.
- **`extraction/` subdirectory**: background coaching-memory distillation
  (observations/claims) lives in the sibling `extraction/` package with its own
  SPEC. This directory's code does not reference or invoke it; its enqueue
  trigger is in the REST layer, and the work runs in the queue worker.

---

## IV. History

- [x] [RFC-45: Coaching Service and Conversation REST Surface](../../../../../../../rfc/45-coaching-service.md)
