# 45 — Coaching Service and Conversation REST Surface

## Executive Summary

This RFC implements the server side of the coaching-conversation API contract in
`api-specs/openapi.yaml` (currently contract-only: no convo routes or service
code exist). It ties the convos data layer — the
`convos`/`convo_requests`/`convo_responses`/`convo_responses_raw` schema
(`db/schema/0006`–`0009`), `ConvosDao`, and the `system_prompts` catalog — to
the `ChatProvider` port (`chat/src/main/kotlin/ed/unicoach/chat/`) behind a new
`CoachingService` in the `service` module and a `ConvoRouteHandler` in
`rest-server`, implementing all nine operations of that contract, including the
two SSE streaming endpoints.

Chat turns run on the request coroutine, not the queue worker, collecting the
port's cold `Flow<ChatEvent>` directly as its flow contract
(`chat/src/main/kotlin/ed/unicoach/chat/SPEC.md`) permits: the handler persists
the `convo_requests` row, collects the flow, relays text deltas as SSE frames,
and persists the terminal outcome into `convo_responses` (+ raw payload). The
queue-free failure model — every attempt durably recorded, zero automatic
retries, failed turns invisible to both the API and replay — is specified under
"Turn execution and failure semantics".

The RFC also closes the contract's two data-layer gaps: an additive
`archived_at` column on `convos` (archive and soft-delete are distinct states in
the `Conversation` schema and the `deleteConversation`/`updateConversation`
operations of `api-specs/openapi.yaml`) and the first reader + seed for the
`system_prompts` catalog. Production wiring lands here too: `main()` constructs
the provider via `ChatProviderFactory` — the first production callsite of the
`chat` module (default `"log"` stub until an Anthropic adapter is added behind
the factory's `anthropic` selector and pinned by config).

## Detailed Design

### Scope and decomposition

This RFC spans three modules — `db` (two migrations, archive + listing DAO
support, the `system_prompts` reader), `service` (the coaching domain layer),
and `rest-server` (the nine contract operations) — plus production wiring.

**This ships as a single RFC** (architect-approved): the `createConversation`
operation in `api-specs/openapi.yaml` couples conversation creation to turn
execution (a conversation is started _with_ its first reply), so a "lifecycle
CRUD only" split cannot implement the create operation and any split line cuts
through the turn path. The Implementation Plan's ordering (data layer + service:
steps 1–8; REST surface + wiring: steps 9–12) still permits landing the work as
two gated efforts if needed.

Facts below are verified against the living artifacts — applied migrations
(`db/schema/0006`, `0007`, `0009`), live DAO code, and `api-specs/openapi.yaml`
— not the originating RFCs. The convo routes read the session cookie name from
`SessionConfig` (`UNICOACH_SESSION` in `rest-server.conf`) and resolve the
caller via the same cookie → `AuthService.getCurrentUser` path the existing
route families use.

### Data models — migrations

**`db/schema/0010.add-convos-archived-at.sql`** adds the archive state the
`Conversation` schema in `api-specs/openapi.yaml` exposes as `archivedAt` and
which `db/schema/0006` does not yet provide: a nullable column, no constraint
changes, no trigger changes.

```sql
ALTER TABLE convos
  ADD COLUMN archived_at TIMESTAMPTZ NULL;
```

`archived_at IS NOT NULL` = archived (reversible); `deleted_at` keeps meaning
soft-deleted (terminal for the API). The two are independent axes; the partial
index `convos_student_id_idx ... WHERE deleted_at IS NULL` continues to serve
both listing filters. `prevent_immutable_updates` does not cover the new column,
so it is mutable as required.

**`db/schema/0011.seed-coach-system-prompt.sql`** inserts the first row of the
`system_prompts` catalog, which is currently empty and has no reader:

```sql
INSERT INTO system_prompts (name, version, body)
VALUES (
  'coach',
  'v1',
  'You are Uni, a warm, encouraging college-admissions coach for '
  || 'high-school students. Help the student explore college options, plan '
  || 'applications and deadlines, and build confidence in their choices. '
  || 'Be concise and concrete. Ask at most one focused question per reply. '
  || 'Never invent facts about the student, or about specific colleges, '
  || 'deadlines, or requirements — say plainly when you don''t know. Keep '
  || 'the conversation on college coaching; gently redirect anything else.'
);
```

The body is architect-approved copy, stored as a single-line string (the `||`
concatenation is layout only; `body` is verbatim and untrimmed, so no newlines
are introduced). A new version later is a new row (`coach`/`v2`) per the
immutable-entity design; the seed is never edited.

### Data models — db module

**`Convo` gains `archivedAt: Instant?`** (mapped from `archived_at`), keeping
the model 1:1 with the table.

**`ArchiveScope`** is the read-time filter for the archive axis, the sibling of
`SoftDeleteScope`:

```kotlin
enum class ArchiveScope { UNARCHIVED, ARCHIVED, ALL }
```

**`ConvoWithActivity`** is the listing projection carrying the derived recency
timestamp the `Conversation.lastActivityAt` field in `api-specs/openapi.yaml`
requires:

```kotlin
data class ConvoWithActivity(
  val convo: Convo,
  val lastActivityAt: Instant?,   // MAX(convo_requests.created_at); null with no turns
)
```

`lastActivityAt` derives from **all** request rows, including failed turns
(matching the contract's `MAX(convo_requests.created_at)` definition; a failed
attempt is still activity).

**`SystemPrompt`** is the catalog row model:

```kotlin
data class SystemPrompt(
  val id: SystemPromptId,
  val name: String,
  val version: String,
  val body: String,
  val createdAt: Instant,
)
```

**`SystemPromptsDao`** is the catalog's first reader — read-only, since rows are
authored by migration:

```kotlin
object SystemPromptsDao {
  // NotFoundException when no row matches.
  fun findByNameAndVersion(session: SqlSession, name: String, version: String): Result<SystemPrompt>
}
```

**`ConvosDao` additions** (existing methods unchanged):

```kotlin
// Idempotent: re-archiving keeps the original archived_at; both reject
// soft-deleted rows (NotFoundException when no row matches).
fun archive(session: SqlSession, id: ConvoId): Result<Convo>
fun unarchive(session: SqlSession, id: ConvoId): Result<Convo>

// Listing with derived activity; excludes soft-deleted rows by scope default.
fun listByStudentWithActivity(
  session: SqlSession,
  studentId: StudentId,
  archive: ArchiveScope = ArchiveScope.UNARCHIVED,
  scope: SoftDeleteScope = SoftDeleteScope.ACTIVE,
): Result<List<ConvoWithActivity>>

fun findByIdWithActivity(
  session: SqlSession,
  id: ConvoId,
  scope: SoftDeleteScope = SoftDeleteScope.ACTIVE,
): Result<ConvoWithActivity>
```

`archive`/`unarchive` use
`UPDATE convos SET archived_at = COALESCE(archived_at,
NOW())` /
`SET archived_at = NULL` with `WHERE id = ? AND deleted_at IS NULL
RETURNING *`
— idempotent toggles, unlike `delete`/`undelete`'s state-transition predicates,
because `PATCH archived` must be repeatable (second archive returns `200` with
the unchanged timestamp). Both first execute
`SET LOCAL unicoach.bypass_logical_timestamp = 'true'` (precedent: `UsersDao`)
so the `update_timestamp` trigger does not advance `updated_at` — the contract
pins "`updatedAt` advances on rename only". Because `SET LOCAL` persists for the
remainder of the transaction, a caller combining rename and archive in one
transaction MUST rename first.

`listByStudentWithActivity` is one query — `LEFT JOIN convo_requests` grouped by
convo, ordered `MAX(r.created_at) DESC NULLS LAST, c.created_at DESC, c.id`
(contract: most recent activity first; deterministic tiebreak).

### Configuration

**`service/src/main/resources/service.conf`** (currently empty) gains the
coaching block; the keys pin the model, the response ceiling, and which catalog
prompt every turn resolves:

```hocon
coaching {
  model = "claude-sonnet-4-6"
  model = ${?COACHING_MODEL}
  maxTokens = 4096
  maxTokens = ${?COACHING_MAX_TOKENS}
  systemPromptName = "coach"
  systemPromptName = ${?COACHING_SYSTEM_PROMPT_NAME}
  systemPromptVersion = "v1"
  systemPromptVersion = ${?COACHING_SYSTEM_PROMPT_VERSION}
}
```

**Model selection is config-pinned, not per-conversation.** The contract in
`api-specs/openapi.yaml` carries no model field on any operation, so per-convo
selection has no wire surface; config is the only contract-compatible source.
The model used is still recorded per turn (`convo_requests.model_requested`), so
a config change mid-conversation keeps provenance exact. The packaged default is
`claude-sonnet-4-6` — the deliberate coaching default (sonnet's cost/latency
profile suits the coach persona), distinct from the `claude-opus-4-8` example id
used elsewhere in the codebase; deployments override it via `COACHING_MODEL` or
their config. The value is inert until production config pins
`chat.provider = "anthropic"` once an Anthropic adapter exists; the packaged
`"log"` stub echoes regardless of model.

**System prompt binding is per-turn, not per-conversation.** The applied schema
has no prompt column on `convos`; provenance lives on each
`convo_requests.system_prompt_id` row (migration `0007`). Each turn resolves
`(coaching.systemPromptName, coaching.systemPromptVersion)` via
`SystemPromptsDao` inside the turn's first transaction and pins the resolved id;
a config change mid-conversation applies from the next turn onward with per-turn
provenance intact. A missing catalog row is a deployment defect: the service
maps it to `Result.failure(IllegalStateException)` (bracketed name/version in
the message), surfacing as `500 internal_error` — not a `404`.

**`CoachingConfig`** is the typed reader, mirroring `SessionConfig`/
`ChatConfig` (fails on absent keys, no value validation):

```kotlin
class CoachingConfig private constructor(
  val model: String,
  val maxTokens: Int,
  val systemPromptName: String,
  val systemPromptVersion: String,
) {
  companion object {
    fun from(config: Config): Result<CoachingConfig>
  }
}
```

**Production wiring.** The `coaching` block lives in `service.conf`, which
`startServer` already loads
(`AppConfig.load("common.conf", "db.conf",
"service.conf", "rest-server.conf", "queue.conf")`);
`CoachingConfig.from` reads it from the merged config. `startServer()` adds
**`"chat.conf"`** to that load list solely to surface `chat.provider` (the
resource arrives on the classpath via the new `:chat` dependency); `coaching`
keys do **not** depend on `chat.conf`. It then builds `ChatConfig.from(config)`
→ `ChatProviderFactory.fromConfig(...)` → `CoachingConfig.from(config)`, all
`getOrThrow()` (fail-fast at boot, matching the existing config style).
`appModule` gains two parameters and constructs the service:

```kotlin
fun Application.appModule(
  database: Database,
  sessionConfig: SessionConfig,
  requestSizeConfig: RequestSizeConfig,
  chatProvider: ChatProvider,
  coachingConfig: CoachingConfig,
)
// inside: val coachingService = CoachingService(database, chatProvider, coachingConfig)
```

`appModule` constructs `coachingService` and threads it into `configureRouting`,
whose signature gains the parameter
(`configureRouting(authService, studentService, coachingService,
sessionConfig)`);
`configureRouting` constructs `ConvoRouteHandler` alongside the auth and student
handlers and calls its `registerRoutes(this)`. No existing test calls
`appModule` directly (routing tests boot `startServer`), so the signature change
touches only `Application.kt` and `Routing.kt`. This is the first production
callsite of the `chat` module; `chat.provider` stays `"log"` until production
config pins `"anthropic"`.

### Service API — `CoachingService`

`CoachingService` is the coaching domain layer, sibling to
`AuthService`/`StudentService`: constructor DI, `suspend` methods returning
`Result<sealed outcome>`, all DB access through `database.withConnection`, no
HTTP/Ktor imports.

```kotlin
class CoachingService(
  private val database: Database,
  private val chatProvider: ChatProvider,
  private val config: CoachingConfig,
) {
  suspend fun startConvo(studentId: StudentId, message: String, name: String?): Result<StartConvoResult>
  suspend fun postTurn(studentId: StudentId, convoId: ConvoId, message: String): Result<PostTurnResult>
  suspend fun listConvos(studentId: StudentId, archive: ArchiveScope): Result<List<ConvoWithActivity>>
  suspend fun getConvo(studentId: StudentId, convoId: ConvoId): Result<GetConvoResult>
  suspend fun updateConvo(studentId: StudentId, convoId: ConvoId, update: ConvoUpdate): Result<UpdateConvoResult>
  suspend fun deleteConvo(studentId: StudentId, convoId: ConvoId): Result<DeleteConvoResult>
  suspend fun listTurns(studentId: StudentId, convoId: ConvoId): Result<ListTurnsResult>
}
```

`updateConvo` takes a `ConvoUpdate` carrying the independently-optional fields,
so a caller changing only one need not name the other:

```kotlin
// Field absent (null) = leave unchanged; present = set. name is never nulled
// (NOT NULL column), so null unambiguously means "untouched"; archived's three
// states are null (untouched) / true (archive) / false (unarchive).
data class ConvoUpdate(
  val name: String? = null,
  val archived: Boolean? = null,
)
```

This is lossless against the wire's PATCH semantics: neither field's set-value
space includes null, so the nullable-field carrier encodes the full tri-state.
The "at least one field present" rule (the contract's `minProperties: 1`) is not
expressible on the type without a smart constructor; an empty `ConvoUpdate` is
rejected by `updateConvo` as a `ValidationFailure`, not at construction.
`ConvoUpdate` lives in the `service` module — the `rest-server`
`UpdateConversationRequest` DTO maps to it in the route handler, since the
service imports no HTTP types.

Student resolution stays in the routes (cookie → `AuthService.getCurrentUser` →
`StudentService.getStudentForUser`), as `StudentRoutes` does; the service takes
a `StudentId` and never sees the no-profile case. Ownership is enforced in the
service: every `convoId` operation loads the convo (`SoftDeleteScope.ACTIVE`)
and returns the not-found outcome when the row is missing, soft-deleted, or
owned by another student — existence is never leaked. Archived conversations
remain fetchable and writable (archive affects only listing).

Outcome types (one per file, project pattern):

```kotlin
sealed interface StartConvoResult {
  data class Started(val convo: Convo, val userTurn: ConvoRequest, val reply: Flow<ReplyEvent>) : StartConvoResult
  data class ValidationFailure(val fieldErrors: List<FieldError>) : StartConvoResult
}

sealed interface PostTurnResult {
  data class Started(val convo: Convo, val userTurn: ConvoRequest, val reply: Flow<ReplyEvent>) : PostTurnResult
  data class ValidationFailure(val fieldErrors: List<FieldError>) : PostTurnResult
  data object NotFound : PostTurnResult
}

sealed interface ReplyEvent {
  data class Delta(val text: String) : ReplyEvent
  sealed interface Terminal : ReplyEvent
  data class Completed(val response: ConvoResponse) : Terminal
  data class Failed(val retriable: Boolean, val reason: String) : Terminal
}

sealed interface GetConvoResult {
  data class Found(val listing: ConvoWithActivity) : GetConvoResult
  data object NotFound : GetConvoResult
}

sealed interface UpdateConvoResult {
  data class Success(val listing: ConvoWithActivity) : UpdateConvoResult
  data class ValidationFailure(val fieldErrors: List<FieldError>) : UpdateConvoResult
  data object NotFound : UpdateConvoResult
}

sealed interface DeleteConvoResult {
  data object Success : DeleteConvoResult
  data object NotFound : DeleteConvoResult
}

sealed interface ListTurnsResult {
  data class Found(val turns: List<ConvoTurn>) : ListTurnsResult
  data object NotFound : ListTurnsResult
}
```

The two-phase shape of `startConvo`/`postTurn` — synchronous pre-flight
returning an outcome, with the reply as a cold `Flow` inside `Started` — is
forced by the contract in `api-specs/openapi.yaml`: pre-stream failures
(`400`/`404`/`409`) must be ordinary HTTP statuses decided _before_ the SSE
response opens, so validation and persistence of the user turn cannot live
inside the stream. Both endpoint flavors share one code path: the buffered
handler drains the same flow the SSE handler relays.

**Validation** (service-level, before any DB write; field names match the wire):
`message` must be non-blank after trimming and ≤ 100000 characters (stored
verbatim, untrimmed — faithful capture; validation applies to the trimmed view
for blankness only); `name`, when supplied, must satisfy `ConvoName.create`
(trimmed, 1–255). An empty `ConvoUpdate` (both fields absent) is a
`ValidationFailure`. When `name` is omitted on `startConvo`, it is derived from
the first message: trim, collapse whitespace runs (including newlines) to single
spaces, truncate to `NAME_DERIVATION_MAX` (80) characters, trim again.

**`updateConvo`** runs in one transaction: ownership check, then rename (when
`update.name` present), then archive/unarchive (when `update.archived` present)
— rename strictly first because archive's `SET LOCAL` bypass suppresses the
`updated_at` trigger for the rest of the transaction — then
`findByIdWithActivity` for the response projection.

**`ConvoContent`** (service module) is the single owner of the content-block
representation shared by persistence, provider replay, and the REST projection:

```kotlin
object ConvoContent {
  // [{"type": "text", "text": text}] — the stored shape of every user turn.
  fun userContent(text: String): JsonElement

  // Concatenated text of blocks with type == "text"; "" for anything else.
  fun renderText(content: JsonElement): String
}
```

### Turn execution and failure semantics

A turn is two transactions bracketing one un-transacted provider call — a
connection is never held across the stream:

**Transaction 1 (pre-flight, inside `startConvo`/`postTurn`):** validate;
(`startConvo`) create the convo, or (`postTurn`) load it and check ownership;
resolve the system prompt row; read the replay history (`listTurns`); append the
`convo_requests` row (`provider = chatProvider.id`,
`model_requested =
config.model`, `system_prompt_id` = resolved id,
`request_params = null`, `content = ConvoContent.userContent(message)`). Commit.
`request_params` stays null in v1: the only per-turn params are `model` and
`maxTokens`, and `model` is already pinned in `model_requested` while `maxTokens`
is reconstructable from config, so mirroring them into the JSONB column would
duplicate provenance without adding any. The user turn is now durable regardless
of what the provider does.

**Provider call (collecting `Started.reply`):** the flow is cold and
single-collection — collecting it executes the turn. It builds
`ChatRequest(model, system = prompt.body, messages = history + new turn,
maxTokens = config.maxTokens)`
where `history` is the tx-1 snapshot of **visible** turns only (see the
visibility rule), each successful turn contributing
`ChatMessage(USER, renderText(request.content))` and
`ChatMessage(ASSISTANT, renderText(response.content))` in `created_at, id`
order. The service relays `ContentBlockDelta` + `ContentDelta.Text` as
`ReplyEvent.Delta`; `Thinking`/`ToolInput`/`Opaque` deltas are not relayed
(nothing renders them; the terminal raw payload still captures everything).
Wall-clock latency is measured around the collection for `latency_ms`.

**Transaction 2 (terminal persistence):** exactly one `convo_responses` row is
written for every request row, whatever the terminal:

| Provider terminal                                                              | Persisted row                                                                                                                                           | Emitted                           |
| :----------------------------------------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------ | :-------------------------------- |
| `Completed`                                                                    | full `NewConvoResponse` from `ChatResponse` (content, model_resolved, stop_reason, tokens, provider_request_id, latency_ms) + raw row from `rawPayload` | `ReplyEvent.Completed(persisted)` on a successful write; `Failed(retriable = true)` if the write itself fails (below) |
| `Rejected`                                                                     | `stop_reason = "error"`, `content = null`, `model_resolved = null`, tokens null, `provider_request_id` when supplied, raw row when an error body exists | `Failed(retriable = false)`       |
| `TransientFailure`                                                             | same error row shape                                                                                                                                    | `Failed(retriable = true)`        |
| exception escaping the flow (non-cancellation; a defect per the port contract) | same error row shape, no raw row                                                                                                                        | `Failed(retriable = true)`        |
| cancellation (collector cancelled — client disconnect)                         | same error row shape, written under `NonCancellable` in the flow's completion handler, only if no response row was persisted yet                        | nothing (collector is gone)       |

The error-row shape above (`stop_reason = "error"` with null content, model, and
tokens) is the only shape the `convo_responses` CHECK constraints permit when
`stop_reason = 'error'`. `provider_request_id` carries the terminal's value for
`Rejected`/`TransientFailure` and is `null` for the synthetic defect and
cancellation rows, which never reached a provider response. A raw row is written
only when the terminal carried a `rawPayload`.

If the transaction-2 write itself fails (DB unavailable), the service logs the
loss (bracketed values) and emits `Failed(retriable = true)`: a reply that is
not durable is not reported as success, because `listMessages` could never show
it.

**No automatic retries.** A mid-stream transient failure cannot be transparently
retried once deltas have reached the client (streamed text cannot be unsaid),
and re-collecting the cold flow is a fresh at-least-once transmission with no
idempotency key (the flow contract in
`chat/src/main/kotlin/ed/unicoach/chat/SPEC.md`). Rather than retry buffered
turns but not streamed ones, the rule is uniform: every provider attempt is
exactly one persisted request/response pair, and re-attempt is user-initiated.
`Failed.retriable` tells the client whether resending is worth suggesting.

**Visibility rule.** A turn is _visible_ — projected by
`listMessages` and replayed to the provider — iff its response row has
`content IS NOT NULL` (success). Failed and abandoned turns stay in the
append-only log as audit but never reach the API or the model: the user's failed
message is gone from history after a reload, so resending it is clean and the
model never sees half-turns or duplicates. Clients that want to prefill a retry
keep their local copy.

**Failed first turn deletes the conversation.** When `startConvo`'s reply
terminates without a success, transaction 2 also soft-deletes the just-created
convo (same transaction as the error row). The contract's invariant that every
observable conversation has at least one turn holds, the orphan never appears in
listings, and a retried `createConversation` starts clean; the prior convo id
(already delivered by the SSE `conversation` event) uniformly `404`s.

**Mid-stream client disconnect: cancel and record** (architect-approved).
Collector cancellation cancels the in-flight provider call (the port's
cooperative-cancellation contract in
`chat/src/main/kotlin/ed/unicoach/chat/SPEC.md`) and the `NonCancellable`
completion handler persists the error row only when no response row was written
yet — the interlock with transaction 2: a `Completed`/`Failed` that already
committed its row makes the finalizer a no-op, so exactly one response row exists
per request. The turn is abandoned, invisible, and re-attemptable; no work
outlives the request coroutine. Detaching the
collection into an application-scoped job (so the reply completes server-side
for a reconnecting client) was rejected as billing for replies nobody may read,
and can be layered on later without schema change since both options share the
two-transaction shape.

**Concurrent turns on one conversation** are not serialized: two simultaneous
`postTurn`s each snapshot history in their own transaction 1, so neither replays
the other; both pairs persist and order by `created_at`. Accepted — a single
user's own conversation, and the append-only log records both faithfully.

### REST surface — API contracts

`ConvoRouteHandler` implements the nine operations exactly as specified in
`api-specs/openapi.yaml` (paths, wrappers, status codes — not restated here).
Registration follows the established pattern:

```kotlin
class ConvoRouteHandler(
  private val authService: AuthService,
  private val studentService: StudentService,
  private val coachingService: CoachingService,
  private val sessionConfig: SessionConfig,
) {
  fun registerRoutes(route: Route)  // route group /api/v1/conversations
}
```

registered from `configureRouting` alongside the auth and student handlers, with
`rejectUnsupportedMethods(...)` on each route group.

**Error codes — casing stated explicitly.** Convo routes use **lowercase
snake_case** codes: the contract in `api-specs/openapi.yaml` pins
`student_profile_required`, and lowercase is the casing it therefore establishes
for this route family (the codebase's per-family convention, as written in
`AuthRoutes.kt` and `StudentRoutes.kt`: auth lowercase, student UPPERCASE, convo
lowercase). The full set:

| Condition                                             | Status | `code`                     |
| :---------------------------------------------------- | :----- | :------------------------- |
| no/invalid session cookie                             | 401    | `unauthorized`             |
| no student profile, create operations only            | 409    | `student_profile_required` |
| validation failure (message/name/status/empty PATCH)  | 400    | `validation_failed`        |
| convo missing, soft-deleted, foreign, or malformed id | 404    | `not_found`                |
| provider terminal `TransientFailure` (buffered)       | 500    | `coach_unavailable`        |
| provider terminal `Rejected` (buffered)               | 500    | `coach_failed`             |

The same `coach_unavailable`/`coach_failed` codes ride inside the in-stream
terminal `error` event; messages are fixed strings ("The coach is temporarily
unavailable — try again." / "The coach could not respond to this message.") that
never leak provider internals (the `reason` is logged, bracketed, server side).
The contract declares only `500` for server-side turn failure, so both terminals
map to `500` with the code carrying the retriable distinction. Cross-cutting
codes are unchanged and already lowercase: `bad_request` (JSON shape,
StatusPages), `payload_too_large` (413), `unsupported_media_type`,
`internal_error` (unexpected exceptions via StatusPages; note the pre-existing
`TransientError → 503` mapping applies to all route families and is not changed
here).

**Per-route outcome mapping** (auth → student → body validation → ownership,
matching the contract's pre-stream ordering):

- `POST /api/v1/conversations` — resolve user (`401`), resolve student
  (`409 student_profile_required`), `startConvo`: `ValidationFailure → 400`;
  `Started` → drain `reply`: `Completed → 201 CreateConversationResponse`,
  `Failed → 500`.
- `GET /api/v1/conversations` — `status` query param `active`(default)
  /`archived` → `ArchiveScope.UNARCHIVED`/`ARCHIVED`; any other value →
  `400 validation_failed`. No student profile → `200` empty list (no service
  call). Otherwise `listConvos → 200 ConversationListResponse`.
- `GET /api/v1/conversations/{id}` — `getConvo`:
  `Found → 200
  ConversationResponse`, `NotFound → 404`. A path id that does
  not parse as a UUID is `404 not_found` (the contract declares no `400` for
  these paths; a malformed id denotes a nonexistent resource).
- `PATCH /api/v1/conversations/{id}` — map `UpdateConversationRequest` to a
  `ConvoUpdate`; an empty update (neither field) → `400 validation_failed`;
  `updateConvo` outcomes → `200`/`400`/`404`.
- `DELETE /api/v1/conversations/{id}` — `deleteConvo`: `Success → 204` (no
  body), `NotFound → 404` (a second delete `404`s; deleted convos are uniformly
  invisible).
- `GET .../{id}/messages` — `listTurns`: `Found → 200 MessageListResponse`
  (visible turns only, chronological), `NotFound → 404`.
- `POST .../{id}/messages` — `postTurn`: `ValidationFailure → 400`,
  `NotFound → 404`; `Started` → drain: `Completed → 201 PostMessageResponse`,
  `Failed → 500`.
- `POST /api/v1/conversations/stream` and `POST .../{id}/messages/stream` —
  identical pre-flight to their buffered siblings (all errors are plain HTTP
  before the stream opens); on `Started` the SSE response begins (next section).

**DTOs** (`ed.unicoach.rest.models`, one type per file, names matching the
OpenAPI schemas): `Conversation`, `Message`, `CreateConversationRequest`,
`PostMessageRequest`, `UpdateConversationRequest`, `ConversationResponse`,
`ConversationListResponse`, `CreateConversationResponse`, `PostMessageResponse`,
`MessageListResponse`, and `StreamEvent.kt` holding the five event DTOs
(`ConversationCreatedEvent`, `UserMessageEvent`, `MessageDeltaEvent`,
`MessageCompletedEvent`, `StreamErrorEvent`), each with a fixed `type` field —
no Jackson polymorphic configuration needed for serialization.
`UpdateConversationRequest` declares `name: String? = null` and
`archived: Boolean? = null` (Jackson's `FAIL_ON_MISSING_CREATOR_PROPERTIES` must
not reject a one-field PATCH).

**Projections** (private functions in `ConvoRoutes.kt`):
`Conversation(id = uuid string, name, createdAt, updatedAt, lastActivityAt,
archivedAt)`
from `ConvoWithActivity` (for the create response,
`lastActivityAt = userTurn.createdAt` — valid without a re-query because the
just-created convo holds exactly one request row, so `MAX(created_at)` equals
that row); `Message` ids are role-prefixed —
`"u_" + request.id` / `"c_" + response.id` — to keep user and coach ids disjoint
in one opaque space (the contract pins `Message.id` as opaque and
non-parseable, so the prefix is an internal uniqueness device, not a
client-readable format) — with
`content = ConvoContent.renderText(...)`, `role` `user`/`coach`, and `createdAt`
from the projected row (`request.createdAt` for a user message,
`response.createdAt` for a coach message — the source of the chronological
ordering `listMessages` returns). The buffered `coachMessage` field of
`CreateConversationResponse`/ `PostMessageResponse` projects from the
`Completed.response` `ConvoResponse` (`"c_" + response.id`,
`renderText(response.content)`); the `userMessage` field projects from the
persisted user `ConvoRequest`.

**Request body limits.** The `message` ceiling (100000 chars, worst-case 400000
UTF-8 bytes) exceeds the global 8 KiB ingress limit, so the four body-bearing
convo paths need an override (the body-bearing convo paths in
`api-specs/openapi.yaml` declare a `413` response that assumes it). The existing
override mechanism is exact-path only (`routeOverrides[call.request.path()]`)
and cannot match `/{conversationId}/messages`, so `RequestSizeConfig` gains a
`routePrefixOverrides: Map<String, DataSize>` section and the `RequestBodyLimit`
lambda resolves exact match → longest matching prefix → default.
`rest-server.conf` adds the block nested under the existing
`server.requestSize` section that `RequestSizeConfig.from` reads (sibling of the
current `routeOverrides`):

```hocon
server {
    requestSize {
        routePrefixOverrides {
            "/api/v1/conversations" = "512 KiB"
        }
    }
}
```

One prefix covers all four POST paths; it also raises the (bodyless) GET/
PATCH/DELETE convo routes to 512 KiB, which only loosens a protection bound —
acceptable for one config line instead of four plus a matching rework.

### Streaming implementation (SSE)

SSE is hand-rolled over
`call.respondBytesWriter(contentType =
ContentType.Text.EventStream)` — Ktor's
SSE plugin binds GET routes and these are POST endpoints, and a frame writer is
~20 lines. A `writeSseEvent(event:
String, data: String)` helper in
`ConvoRoutes.kt` writes `event: {type}\ndata: {json}\n\n` and flushes per frame.
The response sets `Cache-Control: no-store`.

Frame payloads are serialized by a dedicated Jackson mapper configured like
`configureSerialization` (JavaTimeModule, ISO-8601 dates) **except**
`INDENT_OUTPUT`, which must be off: a multi-line `data:` payload breaks SSE
framing.

Event sequences implement the contract exactly: `streamConversation` opens with
one `conversation` event (projected `Conversation` + persisted `userMessage`),
`streamMessage` opens with one `user_message` event; then `delta` frames from
`ReplyEvent.Delta`; then exactly one terminal — `message` (from `Completed`,
carrying the persisted coach `Message`, whose `content` equals the delta
concatenation by the port's accumulation invariant) or `error` (from `Failed`).
The writer closes after the terminal frame. A client disconnect surfaces as a
cancelled write/collection; the service flow's completion handler owns
persistence (previous section), and the handler does nothing further.

**No keep-alive frames in v1.** The opening event is written immediately after
pre-flight, so the only silent window is provider time-to-first-token (seconds);
turns are bounded and there is no intermediary proxy in the current deployment.
If idle-timeout drops appear, a `: keep-alive\n\n` comment-frame ticker can be
added inside the relay loop without contract change.

### Error handling and edge cases

Covered where they arise above; the remainder:

- **Whitespace-only message** passes the OpenAPI `minLength` but fails the
  service's trimmed-blank check → `400 validation_failed` (contract's stated
  behavior).
- **Archived conversations** accept turns and fetches; only listing buckets
  change. **Soft-deleted** conversations `404` everywhere, including as SSE
  pre-flight.
- **`listConversations` with no student profile** returns `200` with an empty
  array (contract); `{id}` operations fold the no-profile case into the uniform
  `404`.
- **Empty `messages` history** (first turn) sends only the new user message —
  valid per the port (passed through unvalidated).
- **An SSE stream that dies before any event** (e.g. terminal write fails) is
  still safe: persistence is owned by the service flow, not the writer.
- **`bin/db-convos-tests` / `bin/db-system-prompts-tests`** harness updates
  assert the new column and seed row at the SQL level (regressions for the
  migrations independent of the JVM stack).

### Dependencies

- `service/build.gradle.kts` adds `implementation(project(":chat"))` —
  `ChatProvider`, `ChatRequest`, `ChatEvent`. First dependent of `chat`.
- `rest-server/build.gradle.kts` adds `implementation(project(":chat"))` —
  factory + provider types for wiring (`:service` and `:db` already present).
- No new third-party libraries: SSE is hand-rolled on `ktor-server-core`;
  Jackson, kotlinx-serialization-json (via `common`), and coroutines are already
  on the classpath of every touched module.
- Existing artifacts consumed as-is, all unchanged by this RFC: the conversation
  contract (`api-specs/openapi.yaml`); the chat port, stub, and factory
  (`chat/`, with the `'log'` allowlist already applied in `db/schema/0009`); the
  convos schema and DAO (`db/schema/0006`–`0007`, `ConvosDao`), including the
  existing `SystemPromptId` value class (`db/.../models/SystemPromptId.kt`) the
  new `SystemPrompt` model reuses; the `ErrorResponse` DTO
  (`rest-server/.../models/ErrorResponse.kt`) and the `FieldError` validation
  shape (`common/.../ed/unicoach/error/FieldError.kt`); the body-limit plugin
  (`RequestSizeLimit.kt`); the
  `Result<sealed outcome>` convention (`AuthService`/`StudentService`); and the
  `Database.withConnection` coroutine/transaction context. An Anthropic adapter
  is a separate, parallel effort behind `ChatProviderFactory`'s `anthropic`
  selector; nothing here depends on it.

## Tests

All suites run under `bin/test` (DB recreated + migrated, so `0010`/`0011` are
always exercised). Provider-failure paths use inline fake `ChatProvider`s; happy
paths use `LogOnlyChatProvider` (deterministic echo).

### SQL harness

`bin/db-convos-tests` (additions):

1. `convos.archived_at exists, nullable, defaults NULL` — insert without the
   column → reads back NULL.
2. `archive round-trip` — `UPDATE ... SET archived_at = NOW()` then `= NULL`
   both succeed (mutable column on a guarded entity).

`bin/db-system-prompts-tests` (additions):

3. `seed row present` — exactly one `system_prompts` row with
   `(name, version) = ('coach', 'v1')` and a non-empty body.

### `db/src/test/kotlin/ed/unicoach/db/dao/ConvosDaoTest.kt` (additions)

1. `mapConvo carries archivedAt` — `findById` on an archived row returns
   non-null `archivedAt`.
2. `archive sets archived_at once` — `archive` twice: second call succeeds and
   `archivedAt` is unchanged (idempotent toggle).
3. `unarchive clears archived_at` — including on a never-archived row
   (idempotent success).
4. `archive/unarchive reject deleted convos` — soft-deleted id →
   `NotFoundException`.
5. `archive does not advance updatedAt; rename does` — the bypass-GUC behavior,
   asserted via timestamps.
6. `listByStudentWithActivity filters by ArchiveScope` — UNARCHIVED excludes
   archived rows, ARCHIVED returns only them, ALL returns both; soft-deleted
   rows excluded in every case.
7. `listByStudentWithActivity derives lastActivityAt` — convo with two requests
   reports the later `created_at`; convo with none reports null.
8. `listByStudentWithActivity orders by activity desc` — most recent first;
   null-activity rows last.
9. `findByIdWithActivity` — returns the projection; `NotFoundException` for
   missing/deleted ids.

### `db/src/test/kotlin/ed/unicoach/db/dao/SystemPromptsDaoTest.kt` (new)

1. `findByNameAndVersion returns the seeded coach prompt` — id, name, version,
   non-empty body.
2. `findByNameAndVersion fails on unknown pair` — `NotFoundException`.

### `service/src/test/kotlin/ed/unicoach/coaching/ConvoContentTest.kt` (new)

1. `userContent shape` — `[{"type":"text","text":...}]`.
2. `renderText concatenates text blocks` and ignores non-text blocks.
3. `renderText yields "" for non-array content`.
4. `round-trip` — `renderText(userContent(s)) == s`.

### `service/src/test/kotlin/ed/unicoach/coaching/CoachingConfigTest.kt` (new)

1. `from reads the packaged defaults` — `AppConfig.load("service.conf")` →
   model/maxTokens/promptName/promptVersion as shipped.
2. `from fails when a key is absent` — empty config → `Result.failure`.

### `service/src/test/kotlin/ed/unicoach/coaching/CoachingServiceTest.kt` (new)

Real test DB; `LogOnlyChatProvider` unless stated. Fakes capture the
`ChatRequest` or emit scripted events.

1. `startConvo persists convo, request, response, and raw` — all four rows;
   request pins `provider = "log"`, `model_requested = config.model`,
   `system_prompt_id` = seeded coach/v1 id; reply deltas concatenate to the
   persisted coach content.
2. `startConvo derives the name` — long multi-line first message →
   whitespace-collapsed, ≤ 80 chars; explicit `name` used verbatim.
3. `startConvo validation` — blank/whitespace message, > 100000-char message,
   invalid explicit name → `ValidationFailure` with the right field; nothing
   persisted.
4. `postTurn replays only visible history` — capture fake: after one successful
   turn, the next `ChatRequest.messages` is `[USER, ASSISTANT, USER]` with
   rendered texts, `system` = seeded body, `maxTokens` = config value.
5. `failed turns are invisible` — fake emits `TransientFailure`: error row
   persisted (`stop_reason = "error"`, `content` null); `listTurns` omits the
   turn; the next captured replay omits it.
6. `terminal mapping` — `Rejected → Failed(retriable = false)`,
   `TransientFailure → Failed(retriable = true)`; both persist the error row,
   with raw row iff the terminal carried a payload.
7. `provider defect maps to retriable failure` — fake flow throws: error row
   persisted, `Failed(retriable = true)` emitted.
8. `cancellation persists the error row` — fake with delayed deltas; cancel the
   collecting job after the first delta: request row has an error response row
   (NonCancellable finalizer), no `Completed` observed.
9. `failed first turn soft-deletes the convo` — `startConvo` + failing fake:
   convo `deleted_at` set in the same outcome; `getConvo → NotFound`.
10. `postTurn ownership` — unknown id, other student's convo, soft-deleted convo
    → `NotFound`; archived convo → `Started` (writable).
11. `missing system prompt is a failure, not an outcome` — config pointing at an
    unseeded pair → `Result.failure` of `IllegalStateException`.
12. `lifecycle: listConvos / getConvo / updateConvo / deleteConvo` — archive
    filtering and ordering by activity; rename advances `updatedAt`, archive
    does not; rename+archive in one call applies both; PATCH with neither field
    → `ValidationFailure`; delete then any operation → `NotFound`; second delete
    → `NotFound`.
13. `latency recorded` — `Completed` path persists non-null `latency_ms ≥ 0`.
14. `terminal-persistence write failure is reported as failure` — the provider
    emits `Completed`, but the transaction-2 write fails (e.g. the convo is
    concurrently/forcibly removed so the response insert violates its FK, or an
    injected DB fault): the turn emits `Failed(retriable = true)` and no
    `Completed` reaches the collector, because a non-durable reply is never
    reported as success.

### `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoRoutingTest.kt` (new)

Real server via `startServer(wait = false)` (provider = `"log"` stub), per
`AuthRoutingTest`'s pattern; register + login + create-student helpers.

1. `full buffered lifecycle` — create (201, derived name, coach echo body,
   role-prefixed ids) → list (one convo, `lastActivityAt` non-null) → get →
   PATCH rename (200, `updatedAt` advanced) → PATCH archive (200, `archivedAt`
   set; default list empty; `status=archived` lists it) → post message (201) →
   get messages (chronological `user`/`coach` alternation; each
   `Message.createdAt` equals its source request/response `created_at`) → DELETE
   (204) → get → 404.
2. `401 unauthorized` on every operation without a cookie (lowercase code
   asserted).
3. `409 student_profile_required` on both create endpoints for a user with no
   student profile; list returns `200 []`; `{id}` ops return 404.
4. `400 validation_failed` — whitespace-only message; 256-char name; PATCH `{}`;
   `?status=bogus`; fieldErrors present where applicable.
5. `404 not_found` — random UUID, malformed (non-UUID) id, another user's convo;
   identical body for all three (no existence leak).
6. `413 override` — a ~100 KiB message body succeeds on the convo POST paths
   (prefix override active) and a > 512 KiB body yields
   `413
   payload_too_large`; an unrelated route still enforces 8 KiB.
7. `streamConversation SSE` — `200` with `text/event-stream`; frame order: one
   `conversation` (carrying convo + persisted user message), ≥ 1 `delta`, one
   terminal `message`; delta concatenation equals the terminal message
   `content`; subsequent `GET messages` matches.
8. `streamMessage SSE` — opens with `user_message`, no `conversation` event;
   terminal `message`.
9. `405 with Allow` on unsupported methods for each route group.

### `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoStreamErrorRoutingTest.kt` (new)

Boots its own `embeddedServer` calling `appModule(...)` directly with an
injected fake `ChatProvider` plus a `CoachingConfig` (the parameterized module
is the injection seam; `startServer` cannot fake the provider).

1. `in-stream transient failure` — fake emits one delta then `TransientFailure`:
   HTTP stays `200`; frames are `user_message`, `delta`, then terminal `error`
   with code `coach_unavailable`; stream closes.
2. `buffered permanent failure` — fake emits `Rejected`: `500` with code
   `coach_failed`; the user request row and error response row exist.
3. `buffered transient failure` — `500` with code `coach_unavailable`.
4. `in-stream permanent failure` — fake emits one delta then `Rejected`: HTTP
   stays `200`; frames are `user_message`, `delta`, then terminal `error` with
   code `coach_failed`; stream closes.

### `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/RequestSizeLimitTest.kt` (additions)

1. `prefix override applies to matching paths` — longest-prefix resolution;
   exact override still wins over a prefix.
2. `non-matching paths use the default`.

## Implementation Plan

All commands run inside the Nix dev shell (`nix develop -c ...`). `bin/test`
re-inits and migrates the test DB each run; use `--force` where a re-run must
not be a cache no-op. After each step, confirm the named test classes appear
green in the JUnit XML under `<module>/build/test-results/test/`.

1. **Migration `0010` + convos harness.** Add
   `db/schema/0010.add-convos-archived-at.sql`; add the two `archived_at`
   assertions to `bin/db-convos-tests`.
   - Verify: `nix develop -c bin/test db`
   - Verify: `nix develop -c bin/db-run ro '\d convos'` shows
     `archived_at
     timestamptz` nullable.
2. **Migration `0011` + prompts harness.** Add
   `db/schema/0011.seed-coach-system-prompt.sql`; add the seed assertion to
   `bin/db-system-prompts-tests`.
   - Verify: `nix develop -c bin/test db`
   - Verify:
     `nix develop -c bin/db-run ro "SELECT name, version FROM
     system_prompts"`
     lists `coach | v1`.
3. **`Convo.archivedAt` + `ArchiveScope` + `ConvoWithActivity` + `ConvosDao`
   methods** (`archive`, `unarchive`, `listByStudentWithActivity`,
   `findByIdWithActivity`; `mapConvo` reads the new column) + `ConvosDaoTest`
   additions.
   - Verify: `nix develop -c bin/test db` (new tests in the XML).
4. **`SystemPrompt` + `SystemPromptsDao` + `SystemPromptsDaoTest`.**
   - Verify: `nix develop -c bin/test db`.
5. **Service config surface.** `service/build.gradle.kts` gains `:chat`;
   `service.conf` gains the `coaching` block; add `CoachingConfig` +
   `CoachingConfigTest`.
   - Verify: `nix develop -c bin/test service`.
6. **Content, input, and outcome types.** `ConvoContent` + `ConvoContentTest`;
   `ConvoUpdate`; `ReplyEvent`, `StartConvoResult`, `PostTurnResult`,
   `GetConvoResult`, `UpdateConvoResult`, `DeleteConvoResult`,
   `ListTurnsResult`.
   - Verify:
     `nix develop -c ./gradlew :service:compileKotlin
     :service:ktlintCheck`
     and `nix develop -c bin/test service`.
7. **`CoachingService` lifecycle operations** (`listConvos`, `getConvo`,
   `updateConvo`, `deleteConvo`, `listTurns`) + their `CoachingServiceTest`
   cases (test 12).
   - Verify: `nix develop -c bin/test service`.
8. **`CoachingService` turn path** (`startConvo`, `postTurn`, the reply flow,
   failure semantics, cancellation finalizer, first-turn-failure soft-delete)
   - remaining `CoachingServiceTest` cases. May be landed incrementally — turn
   happy path first (tests 1–4, 13), then the failure/cancellation/soft-delete
   behaviors (tests 5–11, 14) — each sub-behavior gated by its named test.
   * Verify: `nix develop -c bin/test service`.
9. **Request-size prefix overrides.** `RequestSizeConfig.routePrefixOverrides`
   - `RequestSizeLimit` longest-prefix resolution + `rest-server.conf` block +
     `RequestSizeLimitTest` additions.
   * Verify: `nix develop -c bin/test rest-server`.
10. **Buffered REST surface + wiring.** DTOs; `ConvoRoutes.kt` (buffered
    handlers + projections); `Routing.kt` registration; `Application.kt`
    (`chat.conf` in the load list, factory + `CoachingConfig` construction, new
    `appModule` parameters); `rest-server/build.gradle.kts` gains `:chat`;
    `ConvoRoutingTest` buffered cases (1–6, 9).
    - Verify: `nix develop -c bin/test rest-server`.
11. **SSE endpoints.** `writeSseEvent` + the SSE mapper + the two stream
    handlers; `ConvoRoutingTest` stream cases (7–8);
    `ConvoStreamErrorRoutingTest`.
    - Verify: `nix develop -c bin/test rest-server`.
12. **Full regression.**
    - Verify: `nix develop -c bin/test --force`; confirm module totals in the
      JUnit XML (a bare `BUILD SUCCESSFUL` can mask a no-tests-run state).

## Files Modified

### New

- `db/schema/0010.add-convos-archived-at.sql`
- `db/schema/0011.seed-coach-system-prompt.sql`
- `db/src/main/kotlin/ed/unicoach/db/models/ArchiveScope.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoWithActivity.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/SystemPrompt.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/SystemPromptsDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/SystemPromptsDaoTest.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/CoachingConfig.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/ConvoContent.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/ConvoUpdate.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/ReplyEvent.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/StartConvoResult.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/PostTurnResult.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/GetConvoResult.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/UpdateConvoResult.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/DeleteConvoResult.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/ListTurnsResult.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/CoachingServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/CoachingConfigTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/ConvoContentTest.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/Conversation.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/Message.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/CreateConversationRequest.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/PostMessageRequest.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/UpdateConversationRequest.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/ConversationResponse.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/ConversationListResponse.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/CreateConversationResponse.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/PostMessageResponse.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/MessageListResponse.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/StreamEvent.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/ConvoRoutes.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoRoutingTest.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoStreamErrorRoutingTest.kt`

### Modified

- `db/src/main/kotlin/ed/unicoach/db/models/Convo.kt` — `archivedAt` field
- `db/src/main/kotlin/ed/unicoach/db/dao/ConvosDao.kt` — archive/unarchive,
  activity listings, `mapConvo`
- `db/src/test/kotlin/ed/unicoach/db/dao/ConvosDaoTest.kt` — new coverage
- `bin/db-convos-tests` — `archived_at` assertions
- `bin/db-system-prompts-tests` — seed assertion
- `service/build.gradle.kts` — `implementation(project(":chat"))`
- `service/src/main/resources/service.conf` — `coaching` block
- `rest-server/build.gradle.kts` — `implementation(project(":chat"))`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — `chat.conf`
  load, provider factory + `CoachingConfig` wiring, `appModule` parameters
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt` —
  `ConvoRouteHandler` construction + registration
- `rest-server/src/main/kotlin/ed/unicoach/rest/config/RequestSizeConfig.kt` —
  `routePrefixOverrides`
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/RequestSizeLimit.kt` —
  longest-prefix resolution
- `rest-server/src/main/resources/rest-server.conf` — prefix override block
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/RequestSizeLimitTest.kt`
  — prefix coverage
