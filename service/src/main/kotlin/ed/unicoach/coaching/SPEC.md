# SPEC: `service/src/main/kotlin/ed/unicoach/coaching`

## I. Overview

This directory is the **coaching domain layer**. It owns the business logic for
owner-scoped coaching conversations: starting a conversation with its first
turn, posting subsequent turns, listing/reading conversations with derived
recency, renaming and archiving them, soft-deleting them, and listing their
visible turns. Each turn drives the injected `ChatProvider` port and records
every attempt durably in the convos append-only log. Operations accept a
resolved `StudentId` and return pure domain outcomes via sealed interfaces; the
layer carries no transport concern of its own.

---

## II. Invariants

### General

- `CoachingService` MUST NOT import or reference any Ktor, HTTP, or REST-layer
  types.
- Every `CoachingService` method MUST surface any exception raised during DB
  access or provider use as `Result.failure(e)`; no exception may escape a
  method as a thrown exception. Expected domain states (validation failure,
  not-found) MUST be modeled as result variants returned via `Result.success`,
  never as `Result.failure`.
- `CoachingService` MUST NOT own or hold a raw `java.sql.Connection` — all DB
  access MUST go through `Database.withConnection`.
- Student resolution is **not** this layer's concern: the service takes a
  `StudentId` and MUST NOT carry the no-student-profile case.

### Ownership and existence

- Every `convoId`-bearing operation MUST load the convo under
  `SoftDeleteScope.ACTIVE` and confirm `convo.studentId == studentId` before
  acting. A row that is missing, soft-deleted, or owned by another student MUST
  collapse to the operation's `NotFound` variant — existence MUST NEVER be
  leaked (the three cases are indistinguishable to the caller).
- **Archived** conversations MUST remain fetchable and writable; the archive
  axis affects listing buckets only, never reachability.

### Validation (pre-flight, before any DB write)

- A `FieldError`'s field name MUST be the name of the input it validates
  (`message`, `name`), so a caller can attribute each failure to its argument.
- A turn `message` MUST be rejected when blank after trimming, and when its raw
  length exceeds `MESSAGE_MAX_LENGTH` (100000). The message is persisted
  verbatim and untrimmed — trimming is applied to the blankness check only.
- An explicit `name` MUST be validated through `ConvoName.create`; an invalid
  name short-circuits to the result's validation-failure variant with no DB
  access.
- When `name` is omitted on `startConvo`, it MUST be derived from the first
  message by trimming, collapsing whitespace runs (including newlines) to single
  spaces, truncating to `NAME_DERIVATION_MAX` (80), and trimming again.
- An empty `ConvoUpdate` (both `name` and `archived` absent) MUST be rejected as
  a validation failure by `updateConvo`, not at construction.

### Turn execution

- A turn MUST be two transactions bracketing one **un-transacted** provider
  call: a DB connection MUST NEVER be held across the provider stream.
- Exactly **one** `convo_responses` row MUST be persisted for every
  `convo_requests` row, whatever the provider terminal (success, rejection,
  transient failure, provider defect, or client cancellation).
- The user turn MUST be durably committed (transaction 1) before the provider
  call begins, so it survives regardless of the turn's outcome.
- The reply MUST be exposed as a **cold, single-collection** `Flow<ReplyEvent>`;
  collecting it is what executes the provider call. The pre-flight outcome
  (validation, ownership, user-turn persistence) MUST be decided synchronously,
  before the flow is collected.
- The model MUST be config-pinned (`config.model`), recorded per turn as
  `model_requested`; there is no per-conversation model selection.
- The system prompt MUST be resolved per turn from
  `(config.systemPromptName, config.systemPromptVersion)` and pinned by id on
  the request row. A missing catalog row is a **deployment defect**: it MUST
  surface as `Result.failure(IllegalStateException)` (bracketed name/version in
  the message), never as a not-found domain outcome.
- `request_params` MUST be `null` in v1 (model lives in `model_requested`,
  `maxTokens` is reconstructable from config; mirroring them would duplicate
  provenance).
- Only text deltas MUST be relayed as `ReplyEvent.Delta`: a
  `ChatEvent.ContentBlockDelta` carrying a `ContentDelta.Text`. Thinking, tool,
  and opaque deltas MUST NOT be relayed.

### Visibility, failure, and durability

- A turn is **visible** — projected by `listTurns` and replayed to the provider
  — iff its response row has non-null content (`turn.response?.content != null`).
  Failed and abandoned turns MUST remain in the append-only log as audit but
  MUST NEVER reach the API projection or the replayed history.
- The provider call MUST replay only the transaction-1 snapshot of **visible**
  prior turns, each contributing a `USER` message (rendered request content)
  then an `ASSISTANT` message (rendered response content), in `created_at, id`
  order, followed by the new user message.
- There MUST be **no automatic retries**: every provider attempt is exactly one
  persisted request/response pair; re-attempt is user-initiated.
  `ReplyEvent.Failed.retriable` carries the resend-worthiness distinction
  (`true` for transient/defect, `false` for rejection).
- A **failed first turn** (the first turn of a freshly created convo terminating
  without success) MUST soft-delete the just-created convo in the **same
  transaction** as the error row, so no observable conversation ever lacks a
  visible turn.
- On **client cancellation** (collector cancelled), the in-flight provider call
  MUST be cancelled cooperatively, and the abandoned error row MUST be persisted
  under `NonCancellable` **iff** no response row was committed yet — so exactly
  one response row exists per request and a finalizer never double-writes.
- If the terminal-persistence transaction itself fails, the loss MUST be logged
  (bracketed values) and the turn MUST emit `Failed(retriable = true)`: a reply
  that is not durable MUST NEVER be reported as `Completed`.

### Content representation

- `ConvoContent` MUST be the single owner of the content-block representation
  used for persistence and provider replay. A user turn is stored as a
  one-text-block array; `renderText` concatenates the `text` of every
  `type == "text"` block and yields `""` for any non-block-array content.

---

## III. Behavioral Contracts

### `CoachingService.startConvo(studentId, message, name?): Result<StartConvoResult>` — See [CoachingService.kt](./CoachingService.kt)

- **Side Effects (pre-flight, tx-1)**: resolves the system prompt, creates the
  convo, appends the user request row, snapshots visible history. The reply is a
  cold flow; collecting it runs the provider and writes the response row (tx-2).
- **Validation**: message and (when present) name validated before any DB
  access; `ValidationFailure` carries the offending `FieldError`.
- **First-turn failure**: a non-success terminal soft-deletes the new convo in
  tx-2.
- **Outcomes**: `Started(convo, userTurn, reply)` or `ValidationFailure`.

### `CoachingService.postTurn(studentId, convoId, message): Result<PostTurnResult>` — See [CoachingService.kt](./CoachingService.kt)

- **Side Effects**: same two-transaction shape as `startConvo`, minus convo
  creation; tx-1 loads + ownership-checks the convo, appends the user turn,
  snapshots visible history.
- **Outcomes**: `Started`, `ValidationFailure`, or `NotFound` (missing,
  soft-deleted, or foreign convo). An **archived** convo yields `Started`.

### `CoachingService.listConvos(studentId, archive): Result<List<ConvoWithActivity>>` — See [CoachingService.kt](./CoachingService.kt)

- **Side Effects**: one read via `ConvosDao.listByStudentWithActivity` under the
  given `ArchiveScope` (soft-deleted rows always excluded). No writes.
- **Idempotency**: fully idempotent (read-only).

### `CoachingService.getConvo(studentId, convoId): Result<GetConvoResult>` — See [CoachingService.kt](./CoachingService.kt)

- **Side Effects**: one read via `ConvosDao.findByIdWithActivity`. No writes.
- **Outcomes**: `Found(listing)` or `NotFound` (missing, soft-deleted, or
  foreign — ownership checked against the loaded row's `studentId`).

### `CoachingService.updateConvo(studentId, convoId, update): Result<UpdateConvoResult>` — See [CoachingService.kt](./CoachingService.kt)

- **Side Effects**: one transaction — ownership check, then **rename strictly
  before archive/unarchive** (archive's bypass suppresses the `updated_at`
  trigger for the rest of the transaction), then `findByIdWithActivity` for the
  projection.
- **Validation**: empty update → `ValidationFailure`; invalid `name` →
  `ValidationFailure`, both with no mutation.
- **Outcomes**: `Success(listing)`, `ValidationFailure`, or `NotFound`.

### `CoachingService.deleteConvo(studentId, convoId): Result<DeleteConvoResult>` — See [CoachingService.kt](./CoachingService.kt)

- **Side Effects**: ownership check then `ConvosDao.delete` (soft-delete).
- **Idempotency**: a second delete returns `NotFound` (soft-deleted rows are
  uniformly invisible).
- **Outcomes**: `Success` or `NotFound`.

### `CoachingService.listTurns(studentId, convoId): Result<ListTurnsResult>` — See [CoachingService.kt](./CoachingService.kt)

- **Side Effects**: ownership check then `ConvosDao.listTurns`, filtered to
  **visible** turns (non-null response content), chronological.
- **Outcomes**: `Found(turns)` or `NotFound`.

### Reply flow — `Flow<ReplyEvent>` — See [ReplyEvent.kt](./ReplyEvent.kt)

- **Emission sequence**: zero or more `Delta(text)`, then exactly one `Terminal`
  — `Completed(response)` (persisted coach response) or
  `Failed(retriable, reason)`.
- **`reason` discipline**: `Failed.reason` is a fixed server-side token
  (`"transient"` / `"permanent"`) — never a verbatim provider message. Provider
  reasons are logged (bracketed), not relayed.
- **Provider-terminal mapping** (one response row per request, always):

  | Provider terminal      | Response row                                                                  | Emitted                            |
  | ---------------------- | ----------------------------------------------------------------------------- | ---------------------------------- |
  | `Completed`            | full row from `ChatResponse` + raw row from payload                           | `Completed` (or `Failed(true)` if the tx-2 write fails) |
  | `Rejected`             | error row (`stop_reason = "error"`, null content/model/tokens) + raw row if a body exists | `Failed(retriable = false)`        |
  | `TransientFailure`     | same error-row shape, raw row if a body exists                                | `Failed(retriable = true)`         |
  | exception in the flow  | same error-row shape, no raw row                                              | `Failed(retriable = true)`         |
  | client cancellation    | same error-row shape (under `NonCancellable`), iff no row yet written          | nothing (collector is gone)        |

- **Idempotency / Safety**: the flow is **not** retryable transparently —
  re-collection is a fresh at-least-once provider transmission with no
  idempotency key; re-attempt is a user decision.

### `ConvoContent` — See [ConvoContent.kt](./ConvoContent.kt)

- `userContent(text)` → `[{"type":"text","text":text}]`.
- `renderText(content)` → concatenation of `text`-block text; `""` for non-array
  content. `renderText(userContent(s)) == s`.

### `ConvoUpdate` — See [ConvoUpdate.kt](./ConvoUpdate.kt)

- Tri-state partial-update carrier: `name` null = untouched / present = set
  (never nulled — the column is NOT NULL, so null unambiguously means
  untouched); `archived` null = untouched / `true` = archive / `false` =
  unarchive. The "at least one field present" rule is enforced by `updateConvo`,
  not by the type's construction.

### `CoachingConfig` — See [CoachingConfig.kt](./CoachingConfig.kt)

- `from(config)` reads the `coaching` block and MUST fail (`Result.failure`
  carrying the `ConfigException`) when any key is absent. It performs **no value
  validation**.

### Result sealed interfaces

- No outcome variant MUST contain HTTP status codes or Ktor types.

| Type                | Variants                                          | See |
| ------------------- | ------------------------------------------------- | --- |
| `StartConvoResult`  | `Started` · `ValidationFailure`                   | [StartConvoResult.kt](./StartConvoResult.kt) |
| `PostTurnResult`    | `Started` · `ValidationFailure` · `NotFound`      | [PostTurnResult.kt](./PostTurnResult.kt) |
| `GetConvoResult`    | `Found` · `NotFound`                              | [GetConvoResult.kt](./GetConvoResult.kt) |
| `UpdateConvoResult` | `Success` · `ValidationFailure` · `NotFound`      | [UpdateConvoResult.kt](./UpdateConvoResult.kt) |
| `DeleteConvoResult` | `Success` · `NotFound`                            | [DeleteConvoResult.kt](./DeleteConvoResult.kt) |
| `ListTurnsResult`   | `Found` · `NotFound`                              | [ListTurnsResult.kt](./ListTurnsResult.kt) |
| `ReplyEvent`        | `Delta` · `Terminal`(`Completed` · `Failed`)      | [ReplyEvent.kt](./ReplyEvent.kt) |

---

## IV. Infrastructure & Environment

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
  two-transaction turn model relies on each `Database.withConnection` block being
  a single transaction; first-turn-failure soft-delete shares the error-row
  transaction.
- **Coroutine context**: `suspend` methods must be called from a coroutine
  scope. The module performs no dispatcher switching of its own, except the
  `NonCancellable` block used to persist an abandoned turn during cancellation.

---

## V. History

- [x] [RFC-45: Coaching Service and Conversation REST Surface](../../../../../../../rfc/45-coaching-service.md)
