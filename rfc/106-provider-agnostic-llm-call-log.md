# RFC 106: Provider-agnostic LLM call log

## Executive Summary

Today only the interactive chat path captures its LLM I/O. `CoachingService`
hand-writes a `convo_requests` / `convo_responses` / `convo_responses_raw`
triple per provider call. The four RFC-104 structured-output calls (extraction,
synthesis, fit-lens query, fit-lens reason) go through the **same**
`ChatProvider` yet persist nothing of the call — only an outcome+token row in
`extraction_runs` / `synthesis_runs` / `fit_lens_runs`. Their `tool_use`
request/response is unobservable, and token spend is scattered across four
tables.

This RFC introduces one provider-agnostic **LLM call log** — `llm_requests` /
`llm_responses` / `llm_responses_raw`, append-only, with **zero domain columns**
— and a concrete `LlmCallLog` wrapper (`:service`) that holds the pure
`ChatProvider`, owns the log writes, and returns a log-owned `BIGINT` call id.
Every caller switches from `chatProvider.chat/stream` to
`llmCallLog.record/recordStreaming` and stores the returned id in its own domain
row. Because each composition root injects **only** the wrapper, an unlogged
provider call is hard to write. Capture is faithful for every terminal —
`completed`, `rejected`, `transient_failure`, and `cancelled`.

The convo and run tables become thin domain extensions: `convo_requests` keeps
its coaching columns (`convo_id` / `turn_id` / `kind` / `system_prompt_id`) and
gains `llm_request_id`; `convo_responses` / `_raw` are dropped; the `*_runs`
shed provider/model/token columns and reference the call. A new
`LlmRequestsResource` in `admin-web` renders any logged call, and each run links
to its call — making the previously invisible structured requests observable.
Dev and prod hold no data worth keeping, so the migration clears and reshapes
the affected tables forward — no backfill, no reset.

## Detailed Design

### Verified starting state (code, not RFCs)

- `ChatProvider` (`:chat`) is the port: `val id: String` and
  `fun stream(request: ChatRequest): Flow<ChatEvent>`, with a
  `suspend fun ChatProvider.chat(request): ChatEvent.Terminal` accumulation
  extension. `:chat` depends only on `:common` — **no `:db`**.
- `ChatRequest` carries the full logical request (`model`, `system` body,
  `messages`, `maxTokens`, `tools`, `toolChoice`, `params`). `ChatResponse` is
  field-aligned with `NewConvoResponse` (content, `modelResolved`, `stopReason`,
  `usage: TokenUsage`, `providerRequestId`). `Completed` carries a verbatim
  `rawPayload: JsonElement`; `Rejected` / `TransientFailure` carry a
  `reason: String`, a nullable `providerRequestId`, and a nullable `rawPayload`.
- The four call sites all live in `:service`: `CoachingService.buildReplyFlow`
  uses `stream()` (SSE relay + the RFC-94 tool-use loop, N calls per turn);
  `ExtractionService` and `SynthesisService` use `chat()` (1 call);
  `FitLensService.runPass` uses `chat()` **twice** (query then reason) and
  records **one** `fit_lens_runs` row summing both.
- Composition roots that build a `ChatProvider`:
  `rest-server/.../Application.kt` (injected into `CoachingService`) and
  `queue-worker/.../Application.kt` (injected into `ExtractionService`,
  `SynthesisService`, `FitLensService`). Both use `ChatProviderFactory`.
- `convo_requests` (`id BIGINT` identity) is FK-referenced by `convo_responses`,
  `observations.source_request_id`, and `extraction_runs.through_request_id`
  (the extraction watermark). Nothing FK-references the three `*_runs` tables.
- All of `convo_requests` / `convo_responses` / `convo_responses_raw` /
  `extraction_runs` / `synthesis_runs` / `fit_lens_runs` are append-only logs
  carrying `prevent_log_update` + `prevent_log_delete` (see
  `db/schema/INVARIANTS.md`).
- `admin-web` renders resources via `AdminResource<ROW, ID>`;
  `ConvoRequestsResource` is a composite `ROW = ConvoTurn` (request+response)
  whose JSON fields (`content`, `responseContent`) flow through the RFC-102 JSON
  pretty-printer (`FieldType.JSON`). `admin-web` reads `:db` DAOs directly.
- No shared monotonic-clock seam exists; `convo_responses.latency_ms` is
  measured ad hoc by the caller today, so the wrapper takes an injectable
  `nanoTime` source for deterministic latency tests.

### The seam — `LlmCallLog` (`:service`)

`LlmCallLog` is a concrete class (not an interface, not a `ChatProvider`) that
holds the pure provider and owns every log write. It is the single seam through
which all provider calls flow.

```kotlin
class LlmCallLog(
  private val provider: ChatProvider,   // the pure AnthropicChatProvider (or "log" stub)
  private val database: Database,
  private val nanoTime: () -> Long = System::nanoTime,  // injectable for latency tests
) {
  // Accumulation: extraction / synthesis / fit-lens. Inserts the request,
  // invokes the provider, writes the terminal `llm_responses` row, and returns
  // the id + classified terminal. The caller writes its own domain/run row
  // afterward, in its own transaction.
  suspend fun record(request: ChatRequest): LoggedCall

  // Streaming: CoachingService. The id is available before the first event; the
  // caller stamps its `convo_requests` row before collecting.
  suspend fun recordStreaming(request: ChatRequest): StreamingCall
}

data class LoggedCall(val llmRequestId: LlmRequestId, val terminal: ChatEvent.Terminal)
data class StreamingCall(val llmRequestId: LlmRequestId, val events: Flow<ChatEvent>)
```

`record` inserts `llm_requests` from the request (returns the `BIGINT` id),
times `provider.chat(request)`, maps the terminal to `llm_responses` (+
`llm_responses_raw` when a payload exists) in one `withConnection` transaction,
and returns `(id, terminal)`. It writes only the log rows; the caller reads
`terminal` and writes its own domain/run row afterward (see the best-effort
linkage below). An exception escaping the flow (a defect: the `ChatProvider`
port contract is to surface provider trouble as a `Rejected` /
`TransientFailure` terminal, never a throw) is caught, recorded as outcome
`internal_error` — a discriminant distinct from a provider-reported
`transient_failure`, so a code defect and a legitimate transient provider
failure are never bit-identical rows — and rethrown so the caller's existing
`catch` runs.

`recordStreaming` inserts `llm_requests` eagerly (id known up front), then
returns a cold `events` flow that, on collection, passes every provider event
through to the collector (SSE relay) and, on the terminal, writes
`llm_responses` (+ raw). The id is returned so the caller can write its domain
row and stamp `turn_id` / `kind` before streaming, exactly as `convo_requests`
is written before the call today.

Both entry points terminate identically. To avoid the classification drifting
between the two paths, a single private outcome-classifier maps a
`ChatEvent.Terminal`, a caught exception, or a cancellation to an
`LlmCallOutcome` — the sealed ADT that carries the outcome discriminant and (on
the failure arm) the `reason`. **The classifier is the sole producer of the
failure `reason`**, synthesized from a single documented source per arm:
`Rejected` / `TransientFailure` carry the terminal's own `reason: String`;
`internal_error` is `"${e::class.simpleName}: ${e.message}"` from the caught
`Throwable`; `cancelled` is one canonical documented literal
(`"client disconnected before terminal"`), since no `ChatEvent` cancelled
terminal exists to carry one. This is what satisfies the
`llm_responses_reason_presence_check` NOT-NULL-reason constraint on every
non-`completed` outcome. A single pure row-mapper then turns
`(LlmCallOutcome, latency)` into a `NewLlmResponse` (+ optional
`NewLlmResponseRaw`), consuming the already-classified outcome (reason and usage
in hand) rather than re-deriving from a terminal — the cancellation path has no
terminal to re-derive from. `record` and `recordStreaming` both call the
classifier then the mapper; `LlmCallLog` is a thin orchestrator (request insert,
timing, provider invocation, transaction bracketing) over the shared
classifier/mapper, so outcome semantics live in exactly one place.

**Cancellation is recorded — including a never-collected continuation.** When
the collector cancels (client disconnect) _while a call's stream is being
collected_, the provider stream cancels cooperatively and no terminal arrives;
under `NonCancellable` the wrapper writes an `llm_responses` row with outcome
`cancelled`, the canonical cancellation `reason`, `latency_ms` = time to the
disconnect, and whatever usage / `provider_request_id` had already arrived, then
rethrows the `CancellationException`. But because `recordStreaming` returns a
**cold** flow, a continuation opened in the tool-use loop (its `llm_requests`
row already committed) whose flow is cancelled _before_ collection begins never
runs that in-flow write. That gap is real and reachable: both `openUserTurn` and
`openContinuation` commit the request row (via `recordStreaming`) and then do a
second suspending DB write (`appendRequestRow`) _before_ returning the
uncollected flow, so **any** interruption parked in that write — a client
disconnect _or_ a defect such as a transient DB error — strands a committed row
whose flow never runs. To close it, `LlmCallLog` exposes an idempotent,
`NonCancellable` write pair over one private helper: `writeCancelledIfAbsent`
(outcome `cancelled`) and `writeInternalErrorIfAbsent` (outcome
`internal_error`, carrying the defect's reason); a second write for a request
that already has a response is a no-op, arbitrated by the `llm_responses`
`UNIQUE(request_id)` constraint. **Each opener owns the guarantee for the row it
just committed**: `openUserTurn` / `openContinuation` wrap their
post-`recordStreaming` work in a two-clause guard —
`catch (CancellationException)` → `writeCancelledIfAbsent`, `catch (Exception)`
→ `writeInternalErrorIfAbsent` — on `call.llmRequestId` (the correct id, in
scope right there) before rethrowing. This mirrors `recordStreaming`'s own
in-flow handler, which already records `cancelled` / `internal_error` for the
same two interruption kinds once collection has begun; the same applies to
`record`. So every path that opens an `llm_requests` row — interrupted by a
disconnect, a defect, or neither — writes exactly one `llm_responses` row. The
guarantee holds: **every `llm_requests` row gets a matching `llm_responses`
row**; only a hard process crash can leave a request without a response.

**Call ↔ domain-row linkage is best-effort, not atomic — uniformly, for every
caller.** One logged call is a sequence of independently-committed writes
bracketing an un-transacted network call: `llm_requests` insert →
`provider.chat/stream` → `llm_responses` insert, with the caller's domain-row
insert (carrying `llm_request_id`) committed in a **separate** transaction. Only
the request→response pair carries a durable guarantee (above); the linkage from
the call to the domain row that references it is **not** transactional and is
not promised — a crash after `llm_requests`/`llm_responses` are written but
before the domain row commits leaves an **unlinked** call (fully logged,
attributable to no student; see Error Handling). This is deliberate and is the
pre-existing shape of the codebase: `CoachingService` already brackets one
un-transacted provider call with two independent transactions (`convo_requests`
before, `convo_responses` after), and the accumulation callers already write
their run row in a `withConnection` distinct from the network call. `record`
therefore returns the `LoggedCall` and leaves the domain-row write to the
caller, which writes it exactly as today — on the streaming path
(`recordStreaming`) the `convo_requests` row is stamped **before** the stream is
collected; on the accumulation path (`record`) each service reads
`loggedCall.terminal` and writes its run row afterward in its own transaction.
The crash window between the `llm_responses` write and the domain-row write is
accepted on both paths.

Unlinked calls are surfaced operationally through a **dedicated filtered list**
(not a column on the main call list): a single anti-join query returns calls
older than a configurable age threshold that no domain row (`convo_requests`,
`extraction_runs`, `synthesis_runs`, `fit_lens_runs`) references, giving an
operator a direct list of orphaned/unattributed spend rather than silent
undercount. Orphans are rare and permanent (only a hard crash between the
`llm_responses` write and the domain-row write produces one), so the list is
near-empty and the query runs only on demand.

**Bypass-hardening.** Each composition root constructs
`LlmCallLog(AnthropicChatProvider(...), database)` and injects the `LlmCallLog`
into the four callers. The raw `ChatProvider` is named in exactly one place per
process; no caller receives it. Writing an unlogged call requires visibly
reintroducing the raw provider into the DI graph.

### Data models — the generic log (`:db`, migration `0038`)

Three append-only tables carrying `prevent_log_update` + `prevent_log_delete`,
holding only provider-agnostic facts.

```sql
CREATE TABLE llm_requests (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  provider        TEXT  NOT NULL,   -- ChatProvider.id, verbatim
  model_requested TEXT  NOT NULL,   -- ChatRequest.model
  system          TEXT  NULL,       -- ChatRequest.system (verbatim body; NULL when none)
  content         JSONB NOT NULL,   -- ChatRequest.messages (the sent message array)
  max_tokens      INTEGER NOT NULL, -- ChatRequest.maxTokens
  tools           JSONB NULL,       -- ChatRequest.tools   (NULL when empty)
  tool_choice     JSONB NULL,       -- ChatRequest.toolChoice
  params          JSONB NULL,       -- ChatRequest.params

  CONSTRAINT llm_requests_provider_valid_check CHECK (provider IN ('anthropic','log')),
  CONSTRAINT llm_requests_model_requested_not_empty_check CHECK (length(trim(model_requested)) > 0),
  CONSTRAINT llm_requests_content_is_array_check CHECK (jsonb_typeof(content) = 'array'),
  CONSTRAINT llm_requests_tools_is_array_check CHECK (tools IS NULL OR jsonb_typeof(tools) = 'array'),
  CONSTRAINT llm_requests_tool_choice_is_object_check CHECK (tool_choice IS NULL OR jsonb_typeof(tool_choice) = 'object'),
  CONSTRAINT llm_requests_params_is_object_check CHECK (params IS NULL OR jsonb_typeof(params) = 'object'),
  CONSTRAINT llm_requests_max_tokens_positive_check CHECK (max_tokens > 0)
);

CREATE TABLE llm_responses (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  request_id BIGINT NOT NULL UNIQUE REFERENCES llm_requests(id) ON DELETE CASCADE,

  outcome TEXT NOT NULL,             -- completed | rejected | transient_failure | cancelled | internal_error

  content             JSONB NULL,    -- assistant blocks; non-null iff completed
  model_resolved      TEXT  NULL,    -- non-null iff completed
  stop_reason         TEXT  NULL,    -- verbatim; non-null iff completed
  provider_request_id TEXT  NULL,
  reason              TEXT  NULL,    -- failure classification; null iff completed

  input_tokens       INTEGER NULL,
  output_tokens      INTEGER NULL,
  cache_read_tokens  INTEGER NULL,
  cache_write_tokens INTEGER NULL,
  latency_ms         INTEGER NOT NULL,

  CONSTRAINT llm_responses_outcome_valid_check
    CHECK (outcome IN ('completed','rejected','transient_failure','cancelled','internal_error')),
  CONSTRAINT llm_responses_completed_content_check
    CHECK ((content IS NOT NULL) = (outcome = 'completed')),
  CONSTRAINT llm_responses_completed_model_check
    CHECK ((model_resolved IS NOT NULL) = (outcome = 'completed')),
  CONSTRAINT llm_responses_completed_stop_reason_check
    CHECK ((stop_reason IS NOT NULL) = (outcome = 'completed')),
  CONSTRAINT llm_responses_reason_presence_check
    CHECK ((reason IS NULL) = (outcome = 'completed')),
  CONSTRAINT llm_responses_tokens_nonneg_check CHECK (
    (input_tokens       IS NULL OR input_tokens       >= 0) AND
    (output_tokens      IS NULL OR output_tokens      >= 0) AND
    (cache_read_tokens  IS NULL OR cache_read_tokens  >= 0) AND
    (cache_write_tokens IS NULL OR cache_write_tokens >= 0)
  ),
  CONSTRAINT llm_responses_latency_nonneg_check CHECK (latency_ms >= 0)
);

CREATE TABLE llm_responses_raw (
  response_id BIGINT NOT NULL PRIMARY KEY REFERENCES llm_responses(id) ON DELETE CASCADE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  payload     JSONB NOT NULL
);
```

`content` holds the sent message array. `ChatRequest.messages` is
`List<ChatMessage>`, a `:chat` typed domain object whose `ChatMessage.content`
is already a content-block `JsonElement` (array); the list is serialized to a
single JSON array of `{"role","content"}` objects by
`ChatMessage.serializeChatMessages` — one named serializer in `:chat` that
**both** `AnthropicChatProvider` (building the request body's `messages` key)
and `LlmCallLog` (building `NewLlmRequest.content`) call, so the persisted log
can never drift from what was actually sent — and `NewLlmRequest.content` is
that `JsonArray`. This satisfies the `llm_requests_content_is_array_check` CHECK
and is the defined serialization the byte-equal round-trip test asserts.
`content` and `max_tokens` are `NOT NULL`: because there is no historical data
to backfill, every logged call is fully faithful and the hollow-parent problem
never arises. `llm_responses` is 1:1 with `llm_requests`; `llm_responses_raw` is
0..1 (absent when a failure terminal carried no body). `provider` reuses the
existing `('anthropic','log')` allowlist convention, widened by a later
migration as providers are added. The three tables carry no `student_id` /
`convo_id` / `system_prompt_id` — attribution and provenance live in the domain
rows that reference the call.

**Models + DAO (`:db`).** `LlmRequestId` / `LlmResponseId` (`@JvmInline` value
classes over `Long`); `NewLlmRequest`, `LlmRequest`, `NewLlmResponse`,
`LlmResponse`, `LlmResponseRaw`; `LlmCallOutcome`, a **two-arm sealed ADT** —
`Completed(content, modelResolved, stopReason)` vs
`Failed(kind: LlmFailureKind, reason)`, where `LlmFailureKind` is an enum over
`rejected` / `transient_failure` / `cancelled` / `internal_error` —
reconstructed by the DAO from the `outcome` text column plus its dependent
columns. This mirrors the existing `ExtractionOutcome` / `SynthesisOutcome` /
`FitLensOutcome` convention exactly (RFC 101): a binary `Completed`/`Failed`
split with a typed discriminant on the failure arm (their `category`; here
`kind`) plus a `reason` string. The four failure modes carry identical data and
nothing branches on them structurally — no admin resource, service, or
`ChatEvent` consumer distinguishes rejected from transient from cancelled at the
type level — so five separate arms would be dead structure. `LlmCall` (composite
read model: `request` + nullable `response` + nullable `raw`). `LlmCallsDao`
exposes `appendRequest`, `appendResponse` (+ optional raw), and the read paths
`findCallByRequestId`, `listCalls` (mirroring `ConvosDao.findTurnByRequestId` /
`listTurns`).

```kotlin
sealed interface LlmCallOutcome {
  val value: String
  data class Completed(
    val content: JsonElement, val modelResolved: String, val stopReason: String,
  ) : LlmCallOutcome { override val value get() = "completed" }
  data class Failed(val kind: LlmFailureKind, val reason: String) : LlmCallOutcome {
    override val value get() = kind.value
  }
}
enum class LlmFailureKind(val value: String) {
  REJECTED("rejected"), TRANSIENT_FAILURE("transient_failure"),
  CANCELLED("cancelled"), INTERNAL_ERROR("internal_error"),
}
```

The four token columns and `provider_request_id` are **orthogonal to the
outcome** — a failed or cancelled call can still carry partial usage or a
provider id — so they are plain sibling fields on `NewLlmResponse` /
`LlmResponse` (`Int?` / `String?`), never on the ADT. `LlmCallLog` (in
`:service`) maps `ChatResponse.usage` (`TokenUsage`, a `:chat` type) into the
four ints exactly as `CoachingService` maps it into `NewConvoResponse` today, so
`:chat`'s `TokenUsage` never enters `:db` (which does not depend on `:chat`).
The ADT keeps the `*Outcome` suffix, not `*Result`: `:db` reserves `*Outcome`
for the persisted `outcome`-column ADT (sibling to `ExtractionOutcome`), while
`*Result` denotes a service-layer return (`ExtractionResult`). The DAO's
reconstruction `when` is total over the five CHECK-constrained `outcome` strings
(`completed` → `Completed`; each failure string → `Failed(kind)`), so an
unrecognized value is a corrupt row and throws, mirroring the existing
`ExtractionRunsDao` mapping — no null-returning branch is reintroduced.

### Phase 2 — convo tables become coaching extensions (migration `0039`)

`convo_requests` keeps its coaching identity and columns and references the
call:

- **KEEP** `id` (the watermark + `observations` FK target), `created_at`,
  `convo_id`, `turn_id`, `kind`, `system_prompt_id`.
- **ADD** `llm_request_id BIGINT NOT NULL REFERENCES llm_requests(id)`, with a
  btree index `convo_requests_llm_request_id_idx` on it (the unlinked-call
  anti-join probes this owner by `llm_request_id`; matches the FK-lookup-index
  convention of `0019`/`0031`).
- **DROP** `provider`, `model_requested`, `request_params`, `content` (now in
  `llm_requests`).

`convo_responses` and `convo_responses_raw` are **dropped entirely**; a convo's
response is reached via `convo_requests.llm_request_id → llm_responses` (1:1).
`extraction_runs.through_request_id` and `observations.source_request_id` still
reference `convo_requests(id)`, unchanged.

**Model + service impact.** `ConvoRequest` / `NewConvoRequest` drop the four I/O
fields and add `llmRequestId`. `ConvoResponse`, `NewConvoResponse`,
`ConvoResponseId`, `ConvoResponseRaw` models are deleted. `ConvoTurn` becomes
`(request: ConvoRequest, call: LlmCall?)` — the response side is the joined
`LlmResponse`. `ConvosDao.appendRequest` writes only the coaching columns +
`llm_request_id`; `ConvosDao.appendResponse` / `insertRaw` are removed;
`listTurns` / `findTurnByRequestId` join
`convo_requests → llm_requests /
llm_responses`. `CoachingService` stops
mapping/writing `NewConvoResponse` entirely (the wrapper owns it): per provider
call it calls `recordStreaming`, appends the `convo_requests` extension row with
the returned `llmRequestId` (stamping `turn_id` / `kind` per RFC 94), collects
`events` for SSE, and reads the terminal from the passed-through events to drive
the tool loop, `ReplyEvent` emission, and first-turn cleanup.
`ConvoProjection.visibleExchanges` groups by `turn_id` as before but reads
response content/`stop_reason` from the joined `LlmResponse`.

### Phase 3 — run tables shrink to outcome/watermark records (migration `0040`)

Each `*_runs` table drops the columns now owned by the generic log and
references its call(s):

- **DROP** `provider`, `model_resolved`, `input_tokens`, `output_tokens`,
  `cache_read_tokens`, `cache_write_tokens` (all four token columns).
- **KEEP** `outcome`, the domain counts (`observations_written` /
  `claims_written` / `claims_superseded`; `commitments_written` /
  `commitments_dropped`; `suggestions_written` / `matches_considered`),
  `failure_category` / `failure_reason`, `system_prompt_id`(s), and
  `extraction_runs.through_request_id`.
- **ADD** `extraction_runs.llm_request_id` and `synthesis_runs.llm_request_id`
  (`NOT NULL`, `REFERENCES llm_requests(id)`);
  `fit_lens_runs.query_llm_request_id` (**`NOT NULL`** — every written fit-lens
  run made its query call first, so the id is always present) and
  `fit_lens_runs.reason_llm_request_id` (**`NULLABLE`** — a `Rejected` /
  `TransientFailure` query call, or a zero-match retrieve, bails before the
  reason call). Each of these five new id columns gets a btree index (both
  fit-lens id columns included), since the unlinked-call anti-join probes every
  owner by its `llm_request_id`(s); per the `0019`/`0031` FK-lookup-index
  convention.

A run's `outcome` (`applied` / `failed`) is the **domain** outcome (did we parse
and apply); `llm_responses.outcome` is the **transport** outcome (did the
provider respond). They are independent — a `completed` call can back a `failed`
run (bad enum). The runs keep their outcome/failure columns for that reason.

**Model + service impact.** `ExtractionRun` / `NewExtractionRun`, `SynthesisRun`
/ `NewSynthesisRun`, `FitLensRun` / `NewFitLensRun` drop the
provider/model/token fields and add the id reference(s). `ExtractionRunsDao`,
`SynthesisRunsDao`, `FitLensRunsDao` drop those columns from their insert/select
(each `append` still takes a `SqlSession`, unchanged). Each service calls
`llmCallLog.record(request)`, reads `loggedCall.terminal` exactly as it reads
the old terminal, and — on the `Completed` arm, exactly as today — writes its
run row in its **own** `withConnection` transaction (under the re-acquired
student lock), now carrying `loggedCall.llmRequestId`. The run-writing logic is
otherwise unchanged: `ExtractionService.writePhase` / `writeFailedRun`,
`SynthesisService`'s equivalents, and `FitLensService`'s five
`Completed`-reachable write paths (`writePhase`, `writeAppliedRun` for
zero-match and empty-reason, `writeFailedRun` for query-parse-failure and
reason-parse-failure) each keep their own `database.withConnection`, lock
re-acquire, novelty recheck, and `appendRun`. No run row is written on the
`Rejected` / `TransientFailure` arms (they return before any run write, as
today).

`llm_request_id` is threaded by the caller capturing each call's
`LoggedCall.llmRequestId` as a local. Extraction and synthesis carry one id.
Fit-lens captures the query call's `llmRequestId` after its `record` (always
present, so `queryLlmRequestId` is non-null), then (when the reason call is
reached) the reason call's, and passes both — the nullable `reasonLlmRequestId`
stays null on any path that bails before the reason call (query-parse-failure,
zero-match) — into whichever of the five write paths runs. Because the run write
is the caller's own transaction, every write path (including zero-match, which
no continuation model could host — retrieval has not run at the query call's
terminal) has an unambiguous home; the call ↔ run-row linkage is the uniform
best-effort contract (above).

### Token ledger view

`llm_responses` is now the single home of token spend. A read-only view
`student_llm_token_usage` unions the per-call owners — `convo_requests` (→
`convos.student_id`), `extraction_runs`, `synthesis_runs`, and `fit_lens_runs`
(its two ids) — joined FROM each owner TO `llm_responses`, yielding per-student
token totals from one place. This is the consolidation the scattered token
columns prevented; it is not a new product surface (no REST/API exposure here).

Because the join originates at the domain owners, a call **no** owner references
(a crash-window orphan; see Error Handling) contributes to no student and is
absent from the view — the ledger is exact for attributed spend and undercounts
only by the orphans, which the unlinked-call list surfaces separately.

Neither a `cancelled` call nor a first-turn-abandoned one is an orphan — for a
reason worth stating precisely. A logged call's `convo_requests` owner row is
**append-only** (`prevent_log_delete`) and is never physically removed:
first-turn cleanup (RFC 94) _soft_-deletes the `convos` row (`ConvosDao.delete`
sets `deleted_at`), which does not cascade to `convo_requests`, and a hard
cascade would be blocked by the delete guard anyway. So the call stays linked
and is never surfaced by the unlinked-call list. The only real question is
attribution through a **soft-deleted** convo: because the tokens were genuinely
billed, `student_llm_token_usage` joins `convo_requests → convos` **without** a
`deleted_at IS NULL` predicate (unlike the codebase's user-facing convo reads),
so an abandoned first turn's partial usage (whatever `MessageStart` /
`MessageDelta` delivered before the disconnect) **is** summed into that
student's totals — counted, not silently dropped. Excluding soft-deleted convos
would push that spend into a blind spot invisible to _both_ the ledger and the
unlinked-call list (the call is linked, so nothing flags it); the design
deliberately avoids that.

The view is introduced now, with a `db`-level test asserting per-student
aggregation across all four owners, the exclusion of an unattributed call, and
the inclusion of a call owned by a soft-deleted convo; the RFC ships no reader
(no Kotlin model/DAO/consumer) — a future RFC that needs one adds it.

### Admin visibility (`admin-web`)

- **`LlmRequestsResource` (new) + `LlmCallsDao` read paths.** A top-level
  `AdminKind.LOG` resource at `/llm-request/{id}`, composite `ROW = LlmCall`,
  modeled on `ConvoRequestsResource`. It renders the request envelope (`system`,
  `content`, `tools`, `tool_choice`, `params` as `FieldType.JSON`) and the
  response (`outcome`, `content` as JSON, `stop_reason`, `model_resolved`, four
  token columns, `latency_ms`, `provider_request_id`, `reason`) and the raw
  payload — one rendering path for every logged call, chat and structured alike,
  reusing the RFC-102 JSON pretty-printer with no change to `CellRender`.
- **Unlinked-call list (operator reporting for the best-effort linkage).**
  `LlmCallsDao` gains `listUnlinkedCalls(olderThan: Duration)` — calls whose
  `created_at` predates a threshold and which no domain row (`convo_requests`,
  `extraction_runs.llm_request_id`, `synthesis_runs.llm_request_id`,
  `fit_lens_runs.query_llm_request_id` / `reason_llm_request_id`) references. It
  backs a **dedicated filtered resource** (e.g. `/llm-request?unlinked`) whose
  single backing query _is_ the anti-join, with a `LIMIT` and the threshold
  parameterized (not a magic literal) — matching the admin engine's
  one-query-per-list-page shape. It is deliberately **not** a per-row `Unlinked`
  column on the main call list: that would force a correlated four-table
  `NOT EXISTS` for every row on every page (the overwhelming majority linked)
  against the append-only log's unbounded growth, and the engine has no
  precedent for a list column computed by an extra per-row query. The anti-join
  probes each owner _by_ `llm_request_id`, so those columns are indexed (see
  Phase 2/3); the `student_llm_token_usage` view needs no such index (it drives
  owner → `llm_responses` via the `UNIQUE request_id`).
- **Run resources link to their call.** `ExtractionRunsResource` and
  `SynthesisRunsResource` add an `llmRequestId` field with
  `refSlug = "llm-request"`; `FitLensRunsResource` adds two
  (`queryLlmRequestId`, `reasonLlmRequestId`). This makes the previously
  invisible structured requests/responses reachable in one click, exactly as
  `throughRequestId` links to `convo-request` today. These resources also drop
  the now-removed `provider` / `modelResolved` / token fields; that data renders
  on the linked call.
- **`ConvoRequestsResource` / `ConvosResource` rework.** `ConvoRequestsResource`
  drops the moved I/O fields and shows its coaching columns plus an
  `llmRequestId` link; `ConvosResource`'s turns panel drops the `Model` /
  `Stop
  Reason` / token columns (now one click away on the call) and keeps the
  request link + timestamp.

### API Contracts

No REST/HTTP surface changes shape. The convo endpoints and their streaming SSE
taxonomy are unchanged — tool rounds and logging are invisible to the client.
All changed contracts are internal: `LlmCallLog` and its `LoggedCall` /
`StreamingCall` types; the `LlmCallsDao` + `llm_*` models (including
`listUnlinkedCalls`); the constructor signatures of `CoachingService`,
`ExtractionService`, `SynthesisService`, `FitLensService` (each takes
`LlmCallLog` in place of `ChatProvider`); the reshaped `ConvoRequest` /
`ConvoTurn` and the three run models; and the new/updated admin resources.

### Error Handling / Edge Cases

- **`Completed`** → `llm_responses` outcome `completed`, content /
  `model_resolved` / `stop_reason` / usage / `provider_request_id` set, raw row
  written.
- **`Rejected` / `TransientFailure`** → outcome `rejected` /
  `transient_failure`, `reason` set, usage / `provider_request_id` when present,
  raw row only if a body was carried. The call is fully logged; the caller maps
  the terminal to its domain failure and returns **without writing a run row** —
  exactly as `ExtractionService` / `SynthesisService` / `FitLensService` do
  today (a run row is written only on the `Completed` arm).
- **Exception escaping the flow** (defect: the port contract forbids throwing) →
  caught, recorded as outcome `internal_error` (distinct from a
  provider-reported `transient_failure`, so the defect path is never a
  bit-identical row), rethrown so existing caller `catch` runs; no run row.
- **Cancellation mid-call / mid-stream** → `cancelled` row under
  `NonCancellable`; `CancellationException` rethrown. No response row is lost.
- **Interruption of an opener/continuation before its cold flow is collected**
  (a disconnect _or_ a defect parked in `appendRequestRow`, after the request
  row committed) → the opener (`openUserTurn` / `openContinuation`) that
  committed the row calls `LlmCallLog.writeCancelledIfAbsent` (on cancellation)
  or `writeInternalErrorIfAbsent` (on a defect) for `call.llmRequestId` in its
  two-clause guard (idempotent, `UNIQUE(request_id)`-arbitrated), so the
  eagerly-committed request still gets its `cancelled` / `internal_error`
  response row.
- **Fit-lens query bails** → no reason call; `reason_llm_request_id` stays NULL;
  the run records `failed` as today.
- **Hard process crash between calls** → the one case an `llm_requests` row can
  lack an `llm_responses` row (the request→response guarantee below is a
  write-path discipline, not a DB constraint), and — separately — the one case a
  completed call can lack its referencing domain row (see the best-effort
  linkage below). Such an orphaned call is **not** attributed to any student:
  the `student_llm_token_usage` ledger joins FROM the domain owners TO
  `llm_responses`, so a call no domain row references is invisible to it, and
  the per-student ledger undercounts by exactly the crash-window orphans.
  Unavoidable; strictly better than today, where the same crash loses the entire
  record. The orphan is not lost — it is a fully-logged call reachable directly
  in `LlmRequestsResource` (see below), just unattributed.
- **Migration over partial rows** → the append-only delete guard blocks row
  `DELETE` and `ADD COLUMN … NOT NULL` fails on a non-empty table, so the
  migration uses `TRUNCATE convo_requests CASCADE` (does not fire the row-level
  delete guard; cascades through `convo_responses` / `observations` /
  `extraction_runs` and the memory graph) before reshaping. Discards not-useful
  data by design; the rest of the database is untouched; no `db-reset`.

### Dependencies

No new third-party dependency. `:service` already depends on `:chat` + `:db`
(where `LlmCallLog` lives). `admin-web` already depends on `:db` + `:service`.
`:chat` gains no new dependency (the soft option to let `:chat` depend on `:db`
is explicitly **not** taken); its only change is the extracted
`ChatMessage.serializeChatMessages` — a pure `:chat`-internal serializer that
`LlmCallLog` reuses so the two sides can't diverge, keeping the wrapper reading
everything it needs from `ChatRequest` / `ChatEvent`. Latency uses an injected
`nanoTime: () -> Long = System::nanoTime` default parameter — the codebase's
existing seam idiom — so no new type or cross-module dependency is introduced.

## Tests

### `db` module — `LlmCallsDaoTest`

- **`appendRequest` round-trips the envelope** — a `NewLlmRequest` with system,
  messages, tools, tool_choice, params persists and reads back byte-equal; the
  `provider` CHECK rejects `'openai'`; `content` must be a JSON array;
  `max_tokens > 0` enforced.
- **`appendResponse` for each outcome** — `completed` requires non-null
  content/model_resolved/stop_reason and null reason; `rejected` /
  `transient_failure` / `cancelled` / `internal_error` require null content and
  non-null reason; the presence CHECKs reject the mismatched combinations; the
  `outcome` CHECK rejects an unknown value; `latency_ms` is `NOT NULL` and
  non-negative; tokens non-negative.
- **raw is 0..1** — a completed call writes `llm_responses_raw`; a bodiless
  failure writes none; `findCallByRequestId` returns `raw = null` in the latter.
- **`findCallByRequestId` / `listCalls`** — compose request + response (+ raw)
  into `LlmCall`; a request with no response yet reads `response = null`.
- **append-only guards** — `UPDATE` / `DELETE` on each `llm_*` table raises
  `P0001` (harness assertion alongside the existing convo-log assertions).
- **`listUnlinkedCalls`** — a call referenced by a `convo_requests` /
  `extraction_runs` / `synthesis_runs` / `fit_lens_runs` row is excluded; an
  orphan call older than the threshold is returned; an orphan younger than the
  threshold is excluded (age-gated, threshold parameterized).

### `db` module — reshaped tables

- **`convo_requests` reshape** — after `0039`, insert reads back
  `convo_id`/`turn_id`/`kind`/`system_prompt_id`/`llm_request_id`; the dropped
  columns are absent; `llm_request_id` is `NOT NULL`; `observations` and
  `extraction_runs` FKs to `convo_requests(id)` still hold.
- **`convo_responses` / `_raw` dropped** — the tables no longer exist.
- **run reshape** — each `*_runs` insert reads back the kept columns +
  `llm_request_id`(s); dropped provider/model/token columns are absent; fit-lens
  `reason_llm_request_id` accepts NULL.
- **`student_llm_token_usage` view** — seed calls owned by all four owners for
  two students (a `convo_requests` call, an `extraction_runs` call, a
  `synthesis_runs` call, and a `fit_lens_runs` pair) plus one **unattributed**
  call referenced by no owner and one call owned by a **soft-deleted** convo
  (first-turn cleanup); the view sums per-student token totals across all four
  owners, attributes the fit-lens pair's tokens to the right student,
  **excludes** the unattributed call from every student's total (confirming the
  ledger undercounts orphans rather than misattributing them), and **includes**
  the soft-deleted-convo call's spend in its student's total (the join ignores
  `convos.deleted_at`).

### `service` module — `LlmCallLogTest` (real `Database`, fake `ChatProvider`)

- **`record` — completed** — a fake terminal `Completed` yields one
  `llm_requests` (mapped from the request) + one `llm_responses` (`completed`,
  tokens, latency > 0) + one raw row; `LoggedCall.llmRequestId` matches the
  written row; `terminal` is returned unchanged.
- **`record` — rejected / transient** — outcome recorded, `reason` set, raw only
  when the terminal carried a body; the terminal is returned so the caller maps
  it.
- **`record` — exception** — a fake whose `stream` throws → an `internal_error`
  row is written (distinct from `transient_failure`) whose `reason` equals the
  classifier's `"${e::class.simpleName}: ${e.message}"` for the thrown
  exception, and the exception rethrows.
- **`recordStreaming` — id up front, events relayed** — `llmRequestId` is
  available before collection; every provider event is re-emitted in order; on
  the terminal one `llm_responses` row is written; the accumulated response
  equals the delta text.
- **cancellation** — collecting `events` and cancelling mid-stream writes a
  `cancelled` `llm_responses` row (under `NonCancellable`) with the classifier's
  canonical cancellation `reason` and `latency_ms` set, and rethrows
  `CancellationException`; `record` cancellation behaves the same.
- **latency uses the injected clock** — a fake `nanoTime` returning fixed deltas
  yields a deterministic `latency_ms`.

### `service` module — callers

- **CoachingService** — a scripted stub drives a one-call turn and a tool-loop
  turn: each provider call writes one `llm_requests` + `llm_responses` pair and
  one `convo_requests` extension row carrying the returned `llm_request_id` and
  the shared `turn_id`; no `convo_responses` row exists; SSE deltas still relay;
  first-turn failure cleanup still deletes the convo; `visibleExchanges` reads
  the coach text from `llm_responses`.
- **ExtractionService / SynthesisService** — valid forced-tool input → `applied`
  run carrying `llm_request_id`; the linked `llm_responses` holds the tokens
  (run has none); a bad enum → `failed` run + a `completed` call (transport vs
  domain outcome divergence asserted); a no-tool-use terminal → `failed` run.
- **FitLensService** — full pass records two calls; the applied run carries both
  `query_`/`reason_llm_request_id`; a zero-match pass records **one** call and
  writes an applied run carrying `query_llm_request_id` with
  `reason_llm_request_id` NULL (the write path a continuation model could not
  host); a `Rejected` query call logs **only** the query call's
  `llm_requests`/`llm_responses` rows and writes **no** run row (the caller
  bails on the `Rejected` arm, as today).

### `rest-server` / `queue-worker` wiring

- **only `LlmCallLog` is injected** — a wiring test (or compile-level assertion)
  confirms the callers receive `LlmCallLog`, and the raw provider is constructed
  only inside the `LlmCallLog` at each root.

### `admin-web` — `LlmRequestsResourceTest`

- **call detail renders** — a seeded `LlmCall` renders request `content` /
  `tools` and response `content` as pretty JSON, plus tokens / `stop_reason` /
  `latency`; a call with no raw omits the raw block without error.
- **unlinked-call list** — the dedicated filtered list returns an old orphan
  call and excludes both a domain-referenced call and an orphan younger than the
  threshold, driven by `listUnlinkedCalls`.
- **run → call link** — extraction/synthesis/fit-lens run detail exposes the
  `llm-request` ref link(s); fit-lens shows two; the convo-request detail shows
  its `llm-request` link and no longer renders moved I/O columns.

## Invariants

### Every `ChatProvider` call goes through `LlmCallLog`

**Rule:** All LLM provider calls MUST be made through `LlmCallLog` (`record` /
`recordStreaming`); no code outside a process composition root may hold or call
a raw `ChatProvider`. Each composition root MUST construct the raw provider only
to wrap it in `LlmCallLog`, and inject only the `LlmCallLog`.

**Why:** The generic log is the sole record of every request/response/raw and
the single token ledger. A direct `chatProvider.chat/stream` call bypasses it —
silent, unrecorded spend and an unobservable call, exactly the gap this design
closes. The coupling is a wiring discipline, not type-enforced (the port stays
callable), so a refactor can reintroduce a bypass.

**Target directory:** `service/src/main/kotlin/ed/unicoach/coaching`
(`INVARIANTS.md`).

### Every logged request gets a terminal response row

**Rule:** Every `llm_requests` row `LlmCallLog` opens MUST get exactly one
`llm_responses` row before the opening call returns or propagates — a joint
discipline across the seam and its callers. (a) For a call whose stream is being
collected, `LlmCallLog`'s own flow writes the terminal row (`completed` /
`rejected` / `transient_failure`, or `cancelled` / `internal_error` on an
interruption, under `NonCancellable`). (b) Because `recordStreaming` commits the
request row eagerly and returns a **cold** flow, a caller that opens a streaming
call (`CoachingService.openUserTurn` / `openContinuation`) MUST, if interrupted
(cancellation _or_ a defect) before that flow is collected, write the missing
row itself via `LlmCallLog.writeCancelledIfAbsent` /
`writeInternalErrorIfAbsent`.

**Why:** A dangling `llm_requests` with no `llm_responses` is indistinguishable
from an in-flight call, a crash, and a silent drop, and understates token spend.
The subtlety: `LlmCallLog` alone cannot guarantee this — its response-writing
code lives inside a cold flow, so a request whose flow is never collected (an
opener/continuation interrupted in the gap after the eager request-row commit)
would be orphaned. The opener-side guards are therefore load-bearing, not
redundant. With both halves, the only cause of a dangling request is a hard
process crash. The guarantee is a write-path discipline (it deliberately races
structured concurrency), not a DB constraint, so a refactor that removes either
half can silently break it.

**Target directory:** `service/src/main/kotlin/ed/unicoach/coaching`
(`INVARIANTS.md`).

### The generic log tables keep their append-only guards

This edits the table enumeration in the existing `db/schema/INVARIANTS.md` rule
("Append-only log and immutable-entity tables keep their write guards"), which
names its guarded log tables explicitly: **remove** `convo_responses` and
`convo_responses_raw` (both DROPped in `0039`) and **add** `llm_requests`,
`llm_responses`, and `llm_responses_raw`. The rule's prose is otherwise
unchanged, and the History gains an RFC-106 entry.

**Target directory:** `db/schema` (`INVARIANTS.md`).

## Implementation Plan

1. **`db`: generic log tables + DAO (migration `0038`).** Add
   `db/schema/0038.create-llm-call-log.sql` (the three tables + both append-only
   triggers each). Add the `LlmRequestId` / `LlmResponseId` value classes, the
   `NewLlmRequest` / `LlmRequest` / `NewLlmResponse` / `LlmResponse` /
   `LlmResponseRaw` / `LlmCall` models, the two-arm sealed `LlmCallOutcome` ADT
   (+ `LlmFailureKind` enum), and `LlmCallsDao` (`appendRequest`,
   `appendResponse` + raw, `findCallByRequestId`, `listCalls`;
   `listUnlinkedCalls` is deferred to step 8, after the run tables'
   `llm_request_id` columns exist). Add `LlmCallsDaoTest` and the append-only
   harness assertions.
   - Verify: `nix develop -c bin/test db -f` (assert "N executed").
2. **`service`: `LlmCallLog`.** Add `LlmCallLog` (`record` / `recordStreaming`,
   an injected `nanoTime: () -> Long = System::nanoTime` for latency,
   cancellation under `NonCancellable`, exception → internal_error). Add
   `LlmCallLogTest`.
   - Verify: `nix develop -c bin/test service -f --tests "*LlmCallLog*"`.
3. **`db`: convo reshape (migration `0039`).** Add
   `db/schema/0039.convo-requests-onto-llm-call-log.sql`
   (`TRUNCATE convo_requests CASCADE`; `ALTER convo_requests` drop the four I/O
   columns + add `llm_request_id NOT NULL` FK + its btree index;
   `DROP TABLE convo_responses_raw`, `convo_responses`). Update `ConvoRequest` /
   `NewConvoRequest`; delete `ConvoResponse` / `NewConvoResponse` /
   `ConvoResponseId` / `ConvoResponseRaw`; reshape `ConvoTurn`; rework
   `ConvosDao` (`appendRequest`, `listTurns`, `findTurnByRequestId`; remove
   `appendResponse` / `insertRaw`). Update `ConvosDaoTest`.
   - Verify: `nix develop -c bin/test db -f`.
4. **`service`: CoachingService onto the wrapper.** Constructor takes
   `LlmCallLog` (drop `ChatProvider`). `buildReplyFlow` calls `recordStreaming`,
   appends the `convo_requests` extension row with the returned id + `turn_id` /
   `kind`, relays `events`, and drives the loop / cleanup from the
   passed-through terminal; remove all `NewConvoResponse` mapping and
   `appendResponse` calls. Repoint `ConvoProjection.visibleExchanges` /
   `listTurns` at the joined `LlmResponse`. Update `CoachingServiceTest`,
   `ConvoProjectionTest`.
   - Verify: `nix develop -c bin/test service -f`.
5. **`db`: run reshape + token-ledger view (migration `0040`).** Add
   `db/schema/0040.runs-onto-llm-call-log.sql` (`TRUNCATE` each run table;
   `ALTER` drop provider/model/token columns; add `llm_request_id` — fit-lens
   two: `query_llm_request_id` `NOT NULL`, `reason_llm_request_id` nullable —
   with a btree index on each new id column; and the read-only
   `student_llm_token_usage` view unioning the four per-call owners joined to
   `llm_responses`). Update the three run models + `New*` + DAOs. Update the run
   DAO tests and add the `student_llm_token_usage` view DB test (per-student
   aggregation across all four owners; unattributed call excluded).
   - Verify: `nix develop -c bin/test db -f`.
6. **`service`: structured callers onto the wrapper.** `ExtractionService`,
   `SynthesisService`, `FitLensService` constructors take `LlmCallLog`; each
   calls `record(request)`, reads `loggedCall.terminal` as before, and writes
   its run row (with the captured `loggedCall.llmRequestId`) in its own
   `withConnection` transaction exactly as today — no change to the run-writing
   logic, `writePhase` / `writeAppliedRun` / `writeFailedRun` keep their own
   `database.withConnection`, lock re-acquire, novelty recheck, and `appendRun`.
   Fit-lens captures the query call's id, then the reason call's id when
   reached, and threads both (nullable `reasonLlmRequestId`) into whichever
   write path runs. Drop the `provider` / `modelResolved` / token args from
   `NewExtractionRun` / `NewSynthesisRun` / `NewFitLensRun`. Update
   `ExtractionServiceTest`, `SynthesisServiceTest`, `FitLensServiceTest`.
   - Verify: `nix develop -c bin/test service -f`.
7. **Composition roots.** In `rest-server/.../Application.kt` and
   `queue-worker/.../Application.kt`, build one
   `LlmCallLog(chatProvider, database)` per root (latency `nanoTime` defaults to
   `System::nanoTime`) and pass it into the callers; the raw `chatProvider` is
   referenced only there. Update wiring tests.
   - Verify: `nix develop -c bin/test rest-server -f`;
     `nix develop -c ./gradlew :queue-worker:compileKotlin`.
8. **`admin-web`: LLM-call surface.** Add `LlmCallsDao.listUnlinkedCalls`
   (references the run tables' `llm_request_id` columns from step 5, so it lands
   here). Add `LlmRequestsResource` (register in `Application.kt`) reading via
   `LlmCallsDao`, including the dedicated parameterized-threshold unlinked-call
   filtered list (its single query is the anti-join; not a per-row column). Add
   the `llm-request` ref link(s) to the three run resources and drop their
   removed provider/model/token fields. Rework `ConvoRequestsResource` (coaching
   columns + link) and `ConvosResource` turns panel. Add
   `LlmRequestsResourceTest` (call detail + unlinked flag); update the run/convo
   resource tests.
   - Verify: `nix develop -c bin/test admin-web -f`.
9. **Invariants.** Add the two service rules to
   `service/src/main/kotlin/ed/unicoach/coaching/INVARIANTS.md`; edit
   `db/schema/INVARIANTS.md`'s guarded-tables enumeration — remove
   `convo_responses` / `convo_responses_raw`, add `llm_requests` /
   `llm_responses` / `llm_responses_raw` — and add an RFC-106 History entry.
   - Verify: files present; `nix develop -c bin/format -c`.
10. **Full gate.** Whole suite + formatter.
    - Verify: `nix develop -c bin/test -f`; `nix develop -c bin/format -c`.

## Files Modified

**`db` — migrations**

- `db/schema/0038.create-llm-call-log.sql` — new (three generic tables +
  guards).
- `db/schema/0039.convo-requests-onto-llm-call-log.sql` — new (truncate cascade;
  reshape `convo_requests` + `llm_request_id` index; drop `convo_responses` /
  `_raw`).
- `db/schema/0040.runs-onto-llm-call-log.sql` — new (reshape the three runs +
  `llm_request_id` indexes; `student_llm_token_usage` view).

**`db` — models + DAO**

- `db/src/main/kotlin/ed/unicoach/db/models/LlmRequestId.kt` — new.
- `db/src/main/kotlin/ed/unicoach/db/models/LlmResponseId.kt` — new.
- `db/src/main/kotlin/ed/unicoach/db/models/NewLlmRequest.kt` — new.
- `db/src/main/kotlin/ed/unicoach/db/models/LlmRequest.kt` — new.
- `db/src/main/kotlin/ed/unicoach/db/models/NewLlmResponse.kt` — new.
- `db/src/main/kotlin/ed/unicoach/db/models/LlmResponse.kt` — new.
- `db/src/main/kotlin/ed/unicoach/db/models/LlmResponseRaw.kt` — new.
- `db/src/main/kotlin/ed/unicoach/db/models/LlmCallOutcome.kt` — new (two-arm
  ADT + `LlmFailureKind`).
- `db/src/main/kotlin/ed/unicoach/db/models/LlmCall.kt` — new.
- `db/src/main/kotlin/ed/unicoach/db/dao/LlmCallsDao.kt` — new.
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoRequest.kt` — drop I/O fields,
  add `llmRequestId`.
- `db/src/main/kotlin/ed/unicoach/db/models/NewConvoRequest.kt` — same.
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoResponse.kt` — delete.
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoResponseId.kt` — delete.
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoResponseRaw.kt` — delete.
- `db/src/main/kotlin/ed/unicoach/db/models/NewConvoResponse.kt` — delete (write
  model for the dropped `convo_responses` table).
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoTurn.kt` —
  `(request, call: LlmCall?)`.
- `db/src/main/kotlin/ed/unicoach/db/models/ExtractionRun.kt` — drop
  provider/model/token, add `llmRequestId`.
- `db/src/main/kotlin/ed/unicoach/db/models/NewExtractionRun.kt` — same.
- `db/src/main/kotlin/ed/unicoach/db/models/SynthesisRun.kt` — same.
- `db/src/main/kotlin/ed/unicoach/db/models/NewSynthesisRun.kt` — same.
- `db/src/main/kotlin/ed/unicoach/db/models/FitLensRun.kt` — drop
  provider/model/token, add `queryLlmRequestId` + `reasonLlmRequestId`.
- `db/src/main/kotlin/ed/unicoach/db/models/NewFitLensRun.kt` — same.
- `db/src/main/kotlin/ed/unicoach/db/dao/ConvosDao.kt` — reshape (drop
  `appendResponse` / `insertRaw`; join reads).
- `db/src/main/kotlin/ed/unicoach/db/dao/ExtractionRunsDao.kt` — column changes.
- `db/src/main/kotlin/ed/unicoach/db/dao/SynthesisRunsDao.kt` — column changes.
- `db/src/main/kotlin/ed/unicoach/db/dao/FitLensRunsDao.kt` — column changes.
- `db/schema/INVARIANTS.md` — add the three `llm_*` tables + History entry.
- `db/src/test/kotlin/ed/unicoach/db/dao/LlmCallsDaoTest.kt` — new.
- `db/src/test/kotlin/ed/unicoach/db/dao/ConvosDaoTest.kt` — reshape.
- `db/src/test/kotlin/ed/unicoach/db/dao/ExtractionRunsDaoTest.kt` — reshape.
- `db/src/test/kotlin/ed/unicoach/db/dao/SynthesisRunsDaoTest.kt` — reshape.
- `db/src/test/kotlin/ed/unicoach/db/dao/FitLensRunsDaoTest.kt` — reshape.
- `bin/db-convos-tests` — `llm_*` append-only + provider-allowlist assertions.

**`service`**

- `service/src/main/kotlin/ed/unicoach/coaching/LlmCallLog.kt` — new (wrapper +
  `LoggedCall` / `StreamingCall`).
- `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt` —
  `LlmCallLog` ctor param; `recordStreaming`; drop response persistence;
  projection via `LlmResponse`.
- `service/src/main/kotlin/ed/unicoach/coaching/ConvoProjection.kt` — read
  response from the joined `LlmResponse`.
- `service/src/main/kotlin/ed/unicoach/coaching/ListTurnsResult.kt` — carry the
  reshaped `ConvoTurn` / exchange.
- `service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionService.kt`
  — `LlmCallLog` ctor param; `record`; write `llm_request_id`.
- `service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisService.kt` —
  same.
- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensService.kt` —
  `LlmCallLog` ctor param; two `record` calls; write both ids.
- `service/src/main/kotlin/ed/unicoach/coaching/INVARIANTS.md` — the two new
  rules.
- `service/src/test/kotlin/ed/unicoach/coaching/LlmCallLogTest.kt` — new.
- `service/src/test/kotlin/ed/unicoach/coaching/CoachingServiceTest.kt` —
  update.
- `service/src/test/kotlin/ed/unicoach/coaching/ConvoProjectionTest.kt` —
  update.
- `service/src/test/kotlin/ed/unicoach/coaching/extraction/ExtractionServiceTest.kt`
  — update.
- `service/src/test/kotlin/ed/unicoach/coaching/synthesis/SynthesisServiceTest.kt`
  — update.
- `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensServiceTest.kt` —
  update.

**`rest-server`**

- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — build
  `LlmCallLog`, inject into `CoachingService`.
- `rest-server/src/test/kotlin/ed/unicoach/rest/` — convo/streaming tests
  touching the projection / wiring.

**`queue-worker`**

- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` — build one
  `LlmCallLog`, inject into the three services.

**`admin-web`**

- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/LlmRequestsResource.kt`
  — new.
- `admin-web/src/main/kotlin/ed/unicoach/admin/Application.kt` — register it.
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/ExtractionRunsResource.kt`
  — add `llm-request` link; drop moved fields.
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/SynthesisRunsResource.kt`
  — same.
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/FitLensRunsResource.kt`
  — two links; drop moved fields.
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/ConvoRequestsResource.kt`
  — coaching columns + link; drop moved I/O fields.
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/ConvosResource.kt` —
  turns-panel column changes.
- `admin-web/src/test/kotlin/ed/unicoach/admin/resources/LlmRequestsResourceTest.kt`
  — new.
- `admin-web/src/test/kotlin/ed/unicoach/admin/` — update run/convo resource
  tests.

```
```
