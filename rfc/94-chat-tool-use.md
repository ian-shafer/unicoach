# RFC 94: Chat Tool-Use Loop

## Executive Summary

This RFC lands the generic tool-use loop in the chat turn path: the plumbing
that lets the coach call a registered tool mid-conversation, read its result,
and continue. `AnthropicChatProvider` accumulates `tool_use` blocks into
`convo_responses.content` but nothing acts on them —
`CoachingService.collectTurn` drops every non-text event through `else -> {}`,
and one turn is always one provider call. RFC 66 (`rfc/66-extraction.md`, ~lines
102-105) records a `tool_use` `stop_reason` but explicitly does not act on it,
deferring dispatch to "a later RFC once tool-use exists in the chat path"; RFC
67 (`rfc/67-college-knowledge.md`, Scope section ~line 33 and note ~line 609)
ships `CollegeSearchTool` (definition + `execute`) but leaves the loop and the
chat turn path out of scope.

Three pieces of infrastructure, landed once so any tool registers without
touching the loop:

1. **Structured content on `ChatMessage`** — `content` becomes a content-block
   array (`JsonElement`) not `text: String`, carrying `tool_use`/`tool_result`
   blocks on the wire and replay.
2. **A typed `tools` field on `ChatRequest`** — verbatim Anthropic tool specs
   serialized into the body's `tools` array, not the `params` passthrough.
3. **The loop in `CoachingService`** — `stop_reason == "tool_use"` → registry
   dispatch → continuation call, bounded, on the request coroutine (RFC 43),
   streaming deltas throughout (RFCs 43/44); each call persists its own
   `convo_requests` + `convo_responses` pair, so no billed call goes unrecorded.

A tool excursion spans multiple `convo_requests` rows: the `kind='user'` opener
plus one `kind='tool_result'` row per continuation, closing when a response has
`stop_reason != 'tool_use'`. Those rows are bound into one **logical turn** by a
`turn_id` grouping key (a dedicated bigint sequence), minted once when the user
turn begins and stamped on every row of the excursion. `turn_id` is the explicit
turn boundary the visible-exchange projection and the extraction window group
on, so a positional row cap can never split an excursion — the silent per-turn
extraction loss this refinement closes.

The first consumer is the existing `CollegeSearchTool`: a thin `CollegeChatTool`
adapter registers it so the coach runs live college search in chat. No
retrieval, index, or SQL is added — that shipped in RFC 67/82.

## Detailed Design

### Ground truth (verified against code, not RFCs)

`chat/ChatMessage.kt` is `(role, text: String)`; `AnthropicChatProvider`'s
`requestBody` serializes each message as `content = message.text` (a JSON
string), so no `tool_result`/`tool_use` block can cross the wire. `ChatRequest`
has `params: JsonObject?` but no `tools`. `CoachingService.collectTurn` relays
`ContentDelta.Text`, records the terminal, and drops all else; `buildReplyFlow`
runs exactly one provider call and one `persistTerminal` per turn.
`convo_responses.request_id` is `NOT NULL UNIQUE` — strictly 1:1 with
`convo_requests`, and token usage lives on `convo_responses`, so **each billed
call must be its own request+response pair**. `CollegeSearchTool` (in
`:college`, deps `:common`/`:db`, no `:chat` dependency) exposes
`val definition: JsonObject` (`name = "search_colleges"`) and total
`suspend fun execute(input): JsonObject`. `:service` does not yet depend on
`:college`. `CoachingService` is constructed once, at
`rest-server/.../Application.kt`.

`convo_requests` is an append-only log:
`trigger_00_prevent_convo_requests_update` raises on every row `UPDATE`, so a
new column CANNOT be backfilled per-row via `UPDATE`. `id` is
`BIGINT GENERATED ALWAYS AS IDENTITY` (assigned at insert), so a turn cannot
self-reference its own PK before the first row exists.
`extraction_runs.through_request_id` is a `convo_requests.id`
(`REFERENCES convo_requests(id) ON DELETE CASCADE`), read back as the per-convo
extraction watermark (`MAX(through_request_id) WHERE outcome = 'applied'`); the
chat routes enqueue extraction with the turn's final-answer request id
(`ConvoResponse.requestId`). `extraction_runs` is itself append-only with the
same log guards and a denormalized `convo_id` FK (`ON DELETE CASCADE`).

### Data model — `chat` module

**`ChatMessage` carries content blocks, not text.** `content` is the Anthropic
message-`content` shape: an array of blocks.

```kotlin
data class ChatMessage(
  val role: ChatRole,
  val content: JsonElement,   // content-block array, e.g. [{"type":"text","text":...}]
) {
  companion object {
    // Convenience for the common single-text-block message.
    fun text(role: ChatRole, text: String): ChatMessage
  }
}
```

The block array is the canonical form because the loop must express three block
kinds a flat string cannot: an assistant turn containing a `tool_use` block, and
a user turn containing a `tool_result` block, alongside plain `text`.
`text(...)` preserves the ergonomics of the old constructor for every caller
that only sends prose (extraction, the visible-history projection, tests). This
is the same content-block array `ConvoContent` already stores and renders, so
persistence, replay, and REST share one representation.

**`ChatRequest` gains a typed `tools` field.**

```kotlin
data class ChatRequest(
  val model: String,
  val system: String?,
  val messages: List<ChatMessage>,
  val maxTokens: Int,
  val tools: List<JsonObject> = emptyList(),  // verbatim Anthropic tool specs
  val params: JsonObject? = null,
)
```

Each element is a verbatim Anthropic tool spec (`name`/`description`/
`input_schema`) — the exact shape `CollegeSearchTool.definition` already emits.
The element type is opaque `JsonObject`, not a typed `ToolDefinition`, matching
the port's stance that content blocks and vendor params are opaque `JsonElement`
(RFC 43); typing the JSON-Schema `input_schema` would over-model with no
consumer. The default `emptyList()` keeps every existing tool-less caller
(extraction, the stub tests) byte-identical on the wire — the `tools` key is
omitted when empty (below).

**`ChatTool` is the registration contract; `ToolRegistry` indexes it.**

```kotlin
interface ChatTool {
  val name: String                                  // == definition["name"]; dispatch key
  val definition: JsonObject                        // verbatim Anthropic tool spec
  suspend fun execute(input: JsonObject): JsonObject
}

class ToolRegistry(tools: List<ChatTool>) {
  // Construction indexes by name and throws on a duplicate name (fail fast).
  fun definitions(): List<JsonObject>   // → ChatRequest.tools, in registration order
  fun get(name: String): ChatTool?      // loop dispatch; null = unknown tool
}
```

Both live in `:chat` (the home of the `tools` field and the loop's wire
contract). `ChatTool.execute` is total by contract — a tool signals a domain
failure by returning an error-shaped object (as `CollegeSearchTool` does), not
by throwing; the loop still guards against a throwing tool (below). The registry
is constructed once at the composition root from a static list; there is no
runtime discovery and no per-turn tool subsetting (every turn advertises the
full registry). A per-student/per-turn selector is YAGNI now and would change
`CoachingService` to take a selector rather than a fixed registry — deferred
until a tool needs gating.

### Registration: implement `ChatTool`, append at the composition root

A new tool registers in two steps, neither of which touches the loop:

1. Provide a `ChatTool` — implement the interface directly (extraction's future
   inline-action writer will, in `:service`), or adapt an existing chat-free
   tool contract.
2. Append it to the `ToolRegistry` list where `CoachingService` is built.

`CollegeSearchTool` stays in `:college` with no `:chat` dependency (RFC 67's
deliberate separation). A thin adapter in `:service` bridges it:

```kotlin
// service module
class CollegeChatTool(private val tool: CollegeSearchTool) : ChatTool {
  override val name = CollegeSearchTool.TOOL_NAME          // "search_colleges"
  override val definition = tool.definition
  override suspend fun execute(input: JsonObject) = tool.execute(input)
}
```

`:service` gains an `implementation(project(":college"))` dependency so the
adapter can see the tool. The composition root wires the chain:

```kotlin
// rest-server/.../Application.kt
val tools = ToolRegistry(listOf(
  CollegeChatTool(CollegeSearchTool(CollegeSearchService(database))),
  // future tools append here; nothing else changes
))
val coachingService = CoachingService(database, chatProvider, coachingConfig, tools)
```

### The loop — `CoachingService`

`CoachingService` takes a `ToolRegistry` and never references a concrete tool.
`buildReplyFlow` replaces its single `collectTurn`/`persistTerminal` with a
bounded loop; the streaming, cancellation, and first-turn-cleanup structure of
the existing flow is retained.

Per iteration, on the request coroutine:

1. Build
   `ChatRequest(model, system, messages, maxTokens, tools =
   registry.definitions())`.
   `messages` is the running list: `visibleHistory` + the new user message on
   the first iteration, extended each round (below).
2. `collectTurn(request) { emit(ReplyEvent.Delta(it)) }` — unchanged; streams
   `ContentDelta.Text` as SSE the same way across every iteration, so the user
   sees one continuous reply.
3. Persist this call's response row (`persistTerminal`) — one `convo_responses`
   row, carrying its `TokenUsage`. This is the per-call recording point for
   every outcome, success or failure.
4. Branch on the terminal:
   - **`Completed`, `stop_reason != "tool_use"`** → final answer. Emit
     `ReplyEvent.Completed(finalResponse)`; exit.
   - **`Completed`, `stop_reason == "tool_use"`** → dispatch (below), append the
     continuation request row, extend `messages`, iterate.
   - **`Rejected` / `TransientFailure` / synthetic defect** → the exchange
     failed. Persist the error response row (already done in step 3), emit
     `ReplyEvent.Failed`; apply first-turn cleanup (below); exit.

**Dispatch.** Extract every `tool_use` block from the response content
(Anthropic may emit several in one message — parallel tool use; all must be
answered). For each, in order: `registry.get(name)` then `execute(input)`. Build
one user message carrying one `tool_result` block per `tool_use`, `tool_use_id`
matched:

- Tool returns an object → `tool_result.content` = that object serialized to a
  compact JSON string; `is_error` unset. A tool's own `{ "error": ... }` object
  is a normal result the model reads, **not** a transport error.
- `registry.get(name) == null` (model hallucinated a tool) or `execute` throws →
  `tool_result` with `is_error = true` and a short bracketed reason, so the
  model can recover. The loop never propagates a tool defect as a turn failure.

**Turn identity.** The chat loop mints one `turn_id` per user turn — a single
`nextval('convo_turn_id_seq')` read (`ConvosDao.nextTurnId`) taken when the user
opener is written — and stamps that same value on the opener and on every
`tool_result` continuation row of the excursion. The opener carries it directly;
the continuation reuses `preflight.userTurn.turnId` (the value the opener's
`RETURNING *` already surfaced), so it is threaded, never re-minted. The app
holds the value before the first row is written; this sidesteps the
chicken-and-egg of a self-referential `turn_id = id` (`id` is
`GENERATED ALWAYS AS IDENTITY`, unknown before insert and un-backfillable via
`UPDATE` on an append-only table). No self-reference, no trigger, no `UPDATE`.

**Continuation persistence.** The `tool_result` user message is the "new input"
for the next call, so it is persisted as a `convo_requests` row with
`kind = 'tool_result'`, `turn_id` = the opener's `turn_id`, and `content` = the
`tool_result` block array (identical to what is sent). Its unique
`convo_responses` row is the next call's response.
`provider`/`model_requested`/`system_prompt_version` mirror the user turn's
envelope; `request_params` is null (the tool set is static registry config, not
per-request vendor params).

**In-loop `messages` extension.** After a `tool_use` round, append to the
running list: `ChatMessage(ASSISTANT, response.content)` (the verbatim block
array, which carries the `tool_use` block Anthropic requires echoed back) then
`ChatMessage(USER, toolResultBlocks)`. These in-memory messages are built
directly, not via the DB projection.

**Round cap.** `coaching.maxToolRounds` (config, default 8) bounds tool-dispatch
rounds. On reaching it while the model still returns `tool_use`, the loop makes
one final continuation with `tools = emptyList()`, forcing a text answer
(Anthropic returns `end_turn`); that call is persisted and recorded like any
other. If it too fails, it is a normal failure terminal. This terminates a
runaway without discarding the work already done.

**First-turn cleanup.** The existing rule — a failed first turn deletes the
just-created convo so no orphan remains — applies at the **exchange** level: on
the first turn, if the exchange terminates without a successful final response
(whether the first call or a continuation failed), the convo is deleted. A
non-first-turn failure leaves the convo, with the exchange non-visible (below),
exactly as a failed `postTurn` does today.

### Data model — `convo_requests.kind` (migration `0025`)

Migration `0025.add-convo-requests-kind.sql` adds one column to
`convo_requests`:

```sql
ALTER TABLE convo_requests
  ADD COLUMN kind TEXT NOT NULL DEFAULT 'user';
ALTER TABLE convo_requests
  ADD CONSTRAINT convo_requests_kind_valid_check CHECK (kind IN ('user', 'tool_result'));
```

`kind` names why a request row exists: `'user'` is real student input, made
visible and extractable; `'tool_result'` is a synthetic loop continuation
carrying `tool_result` blocks, excluded from every projection. The
`DEFAULT
'user'` backfills all existing rows correctly (every current request is
a user turn). It is an explicit, self-describing marker rather than positional
inference from a neighbor's `stop_reason` — each row states its own kind, which
the projection, extraction, and any future reader rely on without reconstructing
turn order. The allowlist follows the `provider`-column TEXT+CHECK convention
and extends in a later migration if another synthetic kind appears. No index:
the readers already scan a convo's turns by `convo_id`; `kind` is a per-row
filter, not a lookup key.

`kind` distinguishes a row's role; `turn_id` (below) groups the rows of one
excursion. `kind` alone cannot group — two adjacent excursions are all
`user`/`tool_result` rows with no boundary between them — which is exactly why
the turn is modeled explicitly rather than reconstructed positionally.

### Data model — `convo_requests.turn_id` (migration `0026`)

Migration `0026.add-convo-requests-turn-id.sql` adds one column and a dedicated
sequence:

```sql
CREATE SEQUENCE convo_turn_id_seq;

-- Backfill every existing row as its own singleton turn (turn_id = id) via a
-- STORED generated column: this is a table rewrite, so the append-only update
-- guard never fires (no row-level UPDATE). Each historical row is a complete
-- turn, so turn_id = id reads correctly.
ALTER TABLE convo_requests
  ADD COLUMN turn_id BIGINT GENERATED ALWAYS AS (id) STORED;

-- Convert to a plain writable column, preserving the backfilled values, so the
-- app can stamp a shared turn_id on an excursion's continuation rows. Then
-- require it: every insert MUST carry an explicit turn_id (the loop mints one
-- per logical turn), so a forgotten value fails NOT NULL loudly instead of
-- silently minting a fresh id that would split an excursion.
ALTER TABLE convo_requests ALTER COLUMN turn_id DROP EXPRESSION;
ALTER TABLE convo_requests ALTER COLUMN turn_id SET NOT NULL;

-- Seed the sequence past every backfilled turn_id (= MAX(id)) so app-minted
-- turn_ids never collide with the legacy turn_id = id range.
SELECT setval('convo_turn_id_seq', (SELECT COALESCE(MAX(id), 0) FROM convo_requests));
ALTER SEQUENCE convo_turn_id_seq OWNED BY convo_requests.turn_id;
```

`turn_id` names the logical turn a row belongs to: all rows of one tool-use
excursion (the `user` opener plus its `tool_result` continuations) share one
value; a plain no-tool turn is a singleton. It lives in its own namespace (a
dedicated sequence), unrelated to `id` — it is a grouping key, never compared to
a `convo_requests.id`. It is added to `convo_requests` only, not
`convo_responses`: a response is 1:1 with its request
(`request_id NOT NULL
UNIQUE`), so a response's turn is a single join away;
denormalizing `turn_id` onto responses is speculative with no reader today. The
backfill mechanism is dictated by the append-only guard: a per-row `UPDATE` is
blocked, but `ADD COLUMN ... GENERATED ALWAYS AS
(id) STORED` computes the value
during the table rewrite (no row-level UPDATE fires the guard);
`DROP EXPRESSION` then makes the column writable while keeping the stored
values. Both behaviors were verified against PostgreSQL 18. No index: the
projection and the extraction window read a convo's turns via
`ConvosDao.listTurns` and group by `turn_id` in application code, so `turn_id`
is a per-row grouping field, not a SQL lookup key.

Rollback: additive and safe to revert with the code. Reverting the loop leaves
every row a singleton user turn whose `turn_id` still reads correctly (legacy
rows have `turn_id = id`; any excursion rows written pre-revert keep their
shared `turn_id`, harmless once the loop that reads it is gone).

Model/DAO carry it: `ConvoRequest` and `NewConvoRequest` gain
`turnId: ConvoTurnId` (a `@JvmInline value class` over `Long`, matching the
`ConvoRequestId` convention). `NewConvoRequest.turnId` is mandatory (no
default): each turn's `turn_id` is unique, so there is no sensible constant
default, and a non-null type makes "forgot to thread the turn_id" a compile
error rather than a silent split. `ConvosDao.nextTurnId(session)` mints a value
(`SELECT nextval('convo_turn_id_seq')`); `appendRequest` binds `turn_id` and
`mapRequest`/`mapTurn` read it (the shared `turnSelect` adds
`r.turn_id AS req_turn_id`). `appendUserTurn` mints and stamps the opener; the
loop's continuation append reuses the opener's `turnId`.

`ConvoRequestKind` (the `kind` enum, `USER`/`TOOL_RESULT`) is unchanged.

### `AnthropicChatProvider` request serialization

`requestBody` changes at two points, both in the `messages`/body assembly:

- Each message serializes `content = message.content` **verbatim** (the block
  array) instead of `message.text`.
- When `request.tools` is non-empty, add
  `put("tools", JsonArray(request.tools))`; omit the key entirely when empty, so
  tool-less callers are unchanged.

Typed fields still overwrite colliding `params` keys (RFC 44).
`LogOnlyChatProvider` (the stub) reads `message.content` in place of
`message.text` for its echo and input-length accounting.

### Visibility: the shared visible-exchange projection

A tool excursion writes intermediate rows — a `tool_use` response and a
`tool_result` request — that must replay to the model **within** the turn but
stay out of every after-the-fact projection: the model-replay history, the REST
transcript, and the extraction transcript. All three today key on
`isVisible(turn) = turn.response?.content != null` and pair each request with
its response as one user/assistant exchange. That pairing breaks across an
excursion: the real user text is on the first request, but the final answer is
on a _later_ request's response.

A single projection, `ConvoProjection.visibleExchanges(turns)`, owns the
collapse. It **groups the turns by `turn_id`** (in `convo_requests.id` /
creation order) and emits one logical exchange per group:

```kotlin
data class VisibleExchange(
  val userRequest: ConvoRequest,     // the kind = 'user' opener of the turn_id group
  val finalResponse: ConvoResponse,  // the group's response with stop_reason != 'tool_use', content != null
)
fun visibleExchanges(turns: List<ConvoTurn>): List<VisibleExchange>
```

For each `turn_id` group: `userRequest` is the group's `kind = 'user'` row;
`finalResponse` is the group's response whose `stop_reason != 'tool_use'` (the
excursion's closing answer). The exchange is emitted only if that closing
response exists and succeeded (`content != null`); a group that is still open
(no non-`tool_use` response yet) or whose closing response failed is omitted —
preserving today's "failed turns are invisible" behavior. `kind = 'tool_result'`
requests and `tool_use` responses never surface on their own.

Grouping on the explicit `turn_id` replaces the shipped positional walk (open at
a `kind='user'` row, close at the first non-`tool_use` response). The positional
walk is correct only while the rows of a turn are strictly contiguous in `id`
order; the `turn_id` group is the boundary itself, so a future reader cannot
re-derive it wrong. The three readers each format from `VisibleExchange`:

- `CoachingService.visibleHistory` →
  `[ChatMessage.text(USER, render(userRequest.content)),
  ChatMessage.text(ASSISTANT, render(finalResponse.content))]`
  per exchange. Cross-turn replay stays text-only (thinking/tool plumbing not
  replayed), as today.
- `CoachingService.listTurns` (REST transcript) returns `List<VisibleExchange>`;
  `ConvoRoutes` maps `userRequest`/`finalResponse` to its
  `userMessage`/`coachMessage` DTOs.
- `ExtractionService.buildPromptMessages` renders `[userTurn id=…]` / `[coach]`
  lines from the exchanges instead of raw turns.

Known minor discrepancy: if a first call streams preamble text _before_ a
`tool_use`, the live SSE shows it but a later reload (canonical =
`finalResponse`) does not. Accepted; the final answer is the record of the turn,
and college search rarely emits a preamble. Concatenating all excursion text in
the projection is a possible later refinement, out of scope.

### Extraction window and watermark (turn-bounded)

The shipped read phase (`ExtractionService.readPhase`) caps the window with
`.take(config.windowMaxTurns)` over `convo_requests` **rows** and advances the
watermark to `windowTurns.last().request.id`. A tool excursion is many rows, so
a cap landing inside an open excursion drops the still-open exchange from the
projection (`visibleExchanges` omits it) **and** advances the watermark past the
excursion's opener; the next pass's `request.id > watermark` filter then
excludes the opener, permanently orphaning the excursion's turns from the
extraction/memory pipeline (silent per-turn data loss; the chat transcript is
unaffected). This is the failure `turn_id` closes.

The read phase is rebuilt to window on **whole logical turns**. It groups the
in-range turns by `turn_id` (creation order), caps on **distinct turns**
(`windowMaxTurns` whole turns, not rows), and sets the effective target to the
last kept turn's boundary. A logical turn is in-window only if all its rows fall
in `(watermark, throughRequestId]`, so the cap can never bisect an excursion.
`applyWrites`' observation-source validation is unchanged: it keys on the window
rows' `request.id`, and observations still cite the `kind='user'` opener's
request id (the only rows `visibleExchanges` surfaces to the prompt).

**Watermark representation — REQUIRES ARCHITECT DECISION.** Two ways to keep the
watermark on a turn boundary:

- **(A — recommended) Keep `extraction_runs.through_request_id` a request id;
  make the windowing turn-aware.** The effective target becomes the last kept
  whole turn's highest request id — its final-answer row. Because the enqueue
  already passes a final-answer request id and the cap now trims to whole turns,
  the watermark always lands on a complete turn's last row, and
  `request.id > watermark` excludes that whole turn. The FK
  `through_request_id → convo_requests(id)` and every `extraction_runs` / queue
  / admin surface are untouched; the change is confined to
  `ExtractionService.readPhase`.
- **(B — architect's stated preference) Store the watermark in `turn_id` space
  (`through_turn_id`).** Rename
  `extraction_runs.through_request_id →
  through_turn_id`, drop its FK (a
  `turn_id` has no unique target to reference), have the enqueue pass the
  completed turn's `turn_id` (`userTurn.turnId`, already in hand — this also
  drops the current need to thread the final response's request id), and
  filter/advance in `turn_id` space. This makes "the watermark names a whole
  turn" true by column semantics rather than by the windowing computing a
  boundary. Because `turn_id = id` for every legacy row, the existing
  `through_request_id` values already equal the corresponding `turn_id`s, so no
  data reinterpretation is needed — but the rename spans `db`, `queue`,
  `service`, `rest-server`, and `admin-web`, and the migration must drop the FK
  and rename the column on the append-only `extraction_runs` (both are
  catalog-only DDL; the log guard is untouched).

Recommendation: **A.** Both make the reported window-cap split structurally
impossible — a whole-turn cap cannot split a turn. B's only additional coverage
is a same-turn row-interleave that requires concurrent same-convo turn
submission (the product sends one turn at a time per convo); and even B does not
make orphaning impossible in general — a scalar high-watermark still orphans a
slower turn that completes out of order, a pre-existing limitation of
incremental extraction, orthogonal to F1 and out of scope here. A keeps the
`through_request_id` FK (a real referential-integrity guarantee) and the
smallest blast radius. The invariant below (whole-turn watermark advance) holds
under either. The rest of this RFC specifies **A**; the `Files Modified` and
`Implementation Plan` note B's additional edits under this decision.

The enqueue intent is unchanged: distill up to and including the just-completed
turn. Under A the enqueue is untouched (it passes the final response's
`requestId`, already a turn boundary); under B it passes `userTurn.turnId`. This
does not touch extraction's other watermark invariants (the re-read-under-lock
rule is unaffected).

### API Contracts

No REST surface changes shape. The four convo endpoints
(`POST /api/v1/conversations[/…]`, buffered and streaming) keep their request
and response DTOs; the streaming SSE taxonomy is unchanged — tool rounds surface
only as additional `delta` frames, never as new event types. The only new
external contract is the `search_colleges` tool spec reaching the model (already
defined in RFC 67). Internal contract changes: `ChatMessage`, `ChatRequest`, the
new `ChatTool`/`ToolRegistry`, `CoachingService`'s constructor, `ConvoRequest`/
`NewConvoRequest` (now carrying `kind` and `turnId`), and
`ListTurnsResult.Found` (now `List<VisibleExchange>`). Under decision B:
`ExtractionRun`/ `NewExtractionRun`, `ExtractionRunsDao`, `ExtractionPayload`,
and the extraction enqueue rename `throughRequestId → throughTurnId`.

### Error Handling / Edge Cases

- **Unknown tool name** (model hallucination) → `is_error` tool_result; the loop
  continues so the model can correct.
- **Throwing tool** → caught, `is_error` tool_result; never a turn failure.
- **Tool domain error** (`{ "error": ... }` from `execute`) → normal
  `tool_result` content, `is_error` unset; the model reads and adapts.
- **Parallel tool_use** (multiple blocks in one response) → all dispatched, all
  answered in one `tool_result` user message; an unanswered `tool_use` would
  make the next call invalid.
- **Continuation call fails** (`Rejected`/`TransientFailure`) → error response
  row persisted (token/attempt recorded), `ReplyEvent.Failed` emitted, exchange
  non-visible; first-turn cleanup deletes the convo if no success occurred.
- **Round cap reached** → one forced `tools`-omitted call yields the answer.
- **Client disconnect mid-loop** → existing cancellation path: the in-flight
  provider call is cancelled cooperatively; the abandoned-turn error row is
  written under `NonCancellable` iff no response row for the in-flight request
  exists yet.
- **`stop_reason == "tool_use"` with no tool_use block** (malformed) → treated
  as a final response (nothing to dispatch); the loop exits rather than
  spinning.
- **Window cap falls inside an open excursion** → cannot split it: the window
  caps on whole `turn_id` groups, so the straddling excursion is either wholly
  in-window or wholly deferred to the next pass, and the watermark advances only
  to a whole-turn boundary. A partial excursion in `listTurns` (opener written,
  no closing answer) is an open group `visibleExchanges` omits; it is picked up
  once complete, never orphaned by a mid-turn watermark.

### Dependencies

`:service` adds `implementation(project(":college"))`. No new third-party
dependency. `:chat` gains no dependency (the tool contract is plain
`JsonObject`).

## Tests

### `chat` module

- **`requestBody` serializes content blocks** — a `ChatMessage` whose `content`
  is `[{"type":"text","text":"hi"}]` produces `content` as that array, not a
  string; a message carrying a `tool_use` block round-trips verbatim.
- **`requestBody` emits `tools` when present, omits when empty** — a request
  with two tool specs yields a `tools` array of those objects in order; a
  request with `tools = emptyList()` has no `tools` key (byte-identical to
  pre-RFC bodies).
- **`ChatMessage.text` builds a single text block** — `text(USER, "x")` equals
  `content = [{"type":"text","text":"x"}]`.
- **`ToolRegistry` indexes and rejects duplicates** — `get(name)` returns the
  registered tool; a null for an unknown name; constructing with two tools of
  the same `name` throws.
- **`LogOnlyChatProvider` reads `content`** — its echo and input-char count use
  `message.content`'s text, not the removed `text` field; existing stub tests
  updated to the block form.

### `db` module

- **`appendRequest` persists and reads `kind` and `turn_id`** — a `TOOL_RESULT`
  request with an explicit `turnId` round-trips both fields; a `USER` request
  round-trips; the `kind` CHECK rejects an out-of-allowlist value.
- **`kind` migration backfill** — after `0025`, pre-existing `convo_requests`
  rows read as `kind = 'user'`.
- **`turn_id` migration backfill** — after `0026`, every pre-existing
  `convo_requests` row reads `turn_id = id` (each a distinct singleton turn);
  the `convo_turn_id_seq` next value exceeds `MAX(id)`.
- **`turn_id` is mandatory** — an insert path that omits `turn_id` fails the
  `NOT NULL` constraint (guards against a forgotten stamp silently minting a
  fresh id).
- **`nextTurnId` mints monotonic distinct values** — successive calls return
  strictly increasing values, all greater than the backfilled `MAX(id)`.

### `service` module

- **Loop happy path** — a stub provider scripted to return `tool_use`
  (`search_colleges`) then, on the continuation, a text answer: the coach emits
  the final text; two `convo_requests` rows exist (`kind` `user` then
  `tool_result`) **sharing one `turn_id`**, and two `convo_responses` rows
  (`stop_reason` `tool_use` then `end_turn`).
- **Excursion rows share one `turn_id`** — after a two-round excursion (user +
  two `tool_result` continuations), all three request rows carry the same
  `turn_id`, distinct from a subsequent turn's `turn_id`.
- **Per-call token recording** — both response rows carry their scripted
  `TokenUsage`; the continuation call's usage is present (not silently
  unbilled).
- **Parallel tool_use** — a response with two `tool_use` blocks dispatches both
  and sends one `tool_result` user message with two blocks; a follow-up asserts
  ordering and `tool_use_id` matching.
- **Unknown / throwing tool** — a `tool_use` for an unregistered name, and a
  registered tool whose `execute` throws, each yield an `is_error` tool_result
  and the loop still reaches a final answer.
- **Tool domain error is not `is_error`** — `execute` returning `{ "error": … }`
  produces a normal `tool_result` (no `is_error`), and the model's continuation
  proceeds.
- **Round cap forces a final no-tools call** — a provider that always returns
  `tool_use`, with `maxToolRounds = 2`, makes the forced `tools`-empty call and
  produces a text answer; the request count matches the cap + 1, all sharing one
  `turn_id`.
- **Continuation failure + first-turn cleanup** — first turn, call 1 =
  `tool_use`, continuation = `TransientFailure`: an error response row is
  persisted, `ReplyEvent.Failed` is emitted, and the convo is deleted (no
  orphan); a non-first-turn variant leaves the convo with the exchange
  invisible.
- **`visibleExchanges` groups by `turn_id`** — turns
  `[user→tool_use, tool_result→end_turn]` (one `turn_id`) project to exactly one
  exchange (`userRequest` = the user row, `finalResponse` = the `end_turn` row);
  a failed final response yields zero exchanges; a plain no-tool turn yields one
  exchange; two back-to-back excursions yield exactly two exchanges keyed by
  their distinct `turn_id`s. A continuation row stamped with a _wrong_ fresh
  `turn_id` would fragment into a phantom open group — asserted to NOT happen
  for loop-written rows (regression guard for the shared-`turn_id` invariant).
- **Replay omits tool plumbing** — `visibleHistory` after an excursion replays
  `USER(text), ASSISTANT(final text)` only — no empty user message, no
  `tool_use` content (regression guard against invalid replay).
- **`CoachingService.listTurns` returns exchanges** — the REST projection over
  an excursion returns one exchange; existing transcript tests updated.
- **`CollegeChatTool` delegates** — `name`/`definition` match
  `CollegeSearchTool`; `execute` returns the wrapped tool's object.
- **Extraction ignores synthetic rows** — `buildPromptMessages` over a window
  containing a `tool_result` request and a `tool_use` response emits only the
  user/final-coach lines; the watermark advances over the excursion.
- **Straddling excursion at the window boundary (F1 regression)** — a convo of
  `windowMaxTurns` plain turns followed by an excursion of three rows (user +
  two `tool_result`), with `windowMaxTurns` set so a naive per-row cap would cut
  _inside_ the excursion: assert the first pass distills exactly the whole turns
  that fit (the excursion is wholly deferred, never half-included), the
  watermark lands on a whole-turn boundary (never on a `tool_result`
  continuation), and a second pass distills the excursion with its opener still
  visible (no orphaned turn, no duplicated observation). A companion case places
  the excursion inside the cap and asserts it is kept whole.
- **Live college-search round-trip** — with a real `CollegeChatTool` over a
  seeded fixture and a stub provider scripted to call `search_colleges` with a
  `cipPrefix`, the loop dispatches, the continuation sees the result JSON, and
  the final answer is produced end-to-end.

### `rest-server` module

- **Extraction enqueued at the turn boundary** — after a streamed turn involving
  a tool round, extraction is enqueued for the completed turn (under A: the
  final response's `requestId`, not the user turn's; under B: the turn's
  `turn_id`).
- **Transcript endpoint over an excursion** — `GET` conversation messages after
  a tool turn returns the user message and the single final coach message, no
  tool plumbing.

## Invariants

### Every provider call in the tool-use loop records its own usage

**Rule:** Each provider call the chat tool-use loop makes MUST persist its own
`convo_responses` row (carrying that call's `TokenUsage`) before the loop makes
the next call or returns — including tool-continuation calls, the forced
no-tools cap call, and calls that fail. No billed call may be made without
recording a response row.

**Why:** The per-student token ledger and per-turn audit derive from
`convo_responses`. A continuation billed without a row is spend that happened
but was never recorded, silently understating student usage and losing the
provenance of a turn that made multiple model calls.

**Target directory:** `service/src/main/kotlin/ed/unicoach/coaching`
(`INVARIANTS.md`).

### All rows of one tool-use excursion share one `turn_id`

**Rule:** Every `convo_requests` row of one logical user turn — the
`kind = 'user'` opener and each `kind = 'tool_result'` continuation of its
tool-use excursion — MUST carry the same `turn_id`, minted once per user turn
and never re-minted mid-excursion. A continuation MUST reuse its opener's
`turn_id`.

**Why:** `turn_id` is the sole boundary the visible-exchange projection and the
extraction window group on. A continuation stamped with a fresh `turn_id`
fragments one excursion into phantom turns: the projection drops the
never-closed fragments and the extraction window can split mid-excursion —
re-introducing exactly the silent per-turn data loss the column exists to
prevent. The guarantee is a write-path discipline, not a DB constraint (no
trigger can check that a continuation reused the opener's value), so a refactor
can silently break it.

**Target directory:** `service/src/main/kotlin/ed/unicoach/coaching`
(`INVARIANTS.md`).

### The extraction watermark advances only on a whole-turn boundary

**Rule:** The extraction window MUST be bounded on whole logical turns
(`turn_id` groups): it caps on distinct turns and never includes a partial
excursion, and the applied watermark MUST name a whole-turn boundary — it MUST
NOT advance into an open excursion (past a turn's opener while that turn's rows
remain undistilled).

**Why:** The watermark is a monotonic high-water mark; the next pass reads only
rows beyond it. Advancing it into an open excursion strands that excursion's
opener below the watermark forever, so its turns are never distilled — silent,
unrecoverable per-turn memory loss. Bounding on whole turns makes the split the
shipped per-row cap allowed impossible.

**Target directory:** `service/src/main/kotlin/ed/unicoach/coaching/extraction`
(`INVARIANTS.md`).

## Implementation Plan

1. **`chat`: `ChatMessage` content blocks + `ChatRequest.tools`.** Change
   `ChatMessage` to `content: JsonElement` with a `text(role, s)` companion
   helper; add `tools: List<JsonObject> = emptyList()` to `ChatRequest`. Update
   `LogOnlyChatProvider` to read `content`. Update `chat` tests
   (`ChatProviderTest`, `LogOnlyChatProviderTest`, `AnthropicChatProviderTest`)
   to the block form and add the tools-serialization + content-block cases.
   Update `AnthropicChatProvider.requestBody`: verbatim `content`, `tools` when
   non-empty.
   - Verify: `nix develop -c bin/test chat -f` (assert "N executed").
2. **`chat`: `ChatTool` + `ToolRegistry`.** Add the interface and the registry
   (name-indexed, duplicate-name throw). Add `ToolRegistry` tests.
   - Verify: `nix develop -c bin/test chat -f`.
3. **`db`: `kind` + `turn_id` columns.** Add migration
   `0025.add-convo-requests-kind.sql` (column + CHECK) and
   `0026.add-convo-requests-turn-id.sql` (sequence; generated-column backfill →
   `DROP EXPRESSION` → `NOT NULL`; `setval` past `MAX(id)`). Add
   `ConvoRequestKind` enum and `ConvoTurnId` value class; add `kind` and
   `turnId` to `ConvoRequest`/`NewConvoRequest`; add `ConvosDao.nextTurnId`;
   wire `ConvosDao.appendRequest`/`mapRequest`/`mapTurn` and the shared
   `turnSelect` for both columns. Add DAO tests: `kind`
   persistence/default/CHECK; `turn_id` backfill (`turn_id = id`), mandatory
   `NOT NULL`, and `nextTurnId` monotonicity past `MAX(id)`.
   - Verify: `nix develop -c bin/test db -f`.
4. **`service`: `ConvoProjection.visibleExchanges`.** Add the projection and
   `VisibleExchange`, grouping by `turn_id`. Add `ConvoProjectionTest` (single
   excursion, failed final, plain turn, two back-to-back excursions, phantom-
   fragment guard).
   - Verify: `nix develop -c bin/test service -f --tests "*ConvoProjection*"`.
5. **`service`: `CollegeChatTool` + `:college` dependency.** Add
   `implementation(project(":college"))` to `service/build.gradle.kts`; add the
   adapter and `CollegeChatToolTest`.
   - Verify: `nix develop -c bin/test service -f --tests "*CollegeChatTool*"`.
6. **`service`: the loop.** Add `maxToolRounds` to `CoachingConfig` and
   `coaching.maxToolRounds` (default 8) to `service.conf`. Add a
   `tools: ToolRegistry` constructor param to `CoachingService`. Rewrite
   `buildReplyFlow` as the bounded loop (dispatch, `tool_result` continuation
   append with `kind = TOOL_RESULT` and the opener's `turnId`, in-memory message
   extension, cap forcing, exchange-level first-turn cleanup). `appendUserTurn`
   mints the `turn_id` via `nextTurnId`. Add `tool_result`-block and
   `tool_use`-extraction helpers to `ConvoContent`. Repoint `visibleHistory`,
   `CoachingService.listTurns`, and `ExtractionService.buildPromptMessages` at
   `visibleExchanges`; change `ListTurnsResult.Found` to
   `List<VisibleExchange>`. Update `CoachingServiceTest` and
   `ExtractionServiceTest`; add the loop cases and the shared-`turn_id` cases.
   - Verify: `nix develop -c bin/test service -f`.
7. **`service`: turn-bounded extraction window.** Rewrite
   `ExtractionService.readPhase` to group in-range turns by `turn_id`, cap on
   distinct turns (`windowMaxTurns` whole turns), and set the effective target
   to the last kept whole turn's boundary (its final-answer request id). Add the
   straddling-excursion-at-the-window-boundary regression tests and the
   kept-whole companion to `ExtractionServiceTest`.
   - Verify: `nix develop -c bin/test service -f --tests "*Extraction*"`.
   - **Decision B only:** additionally rename `throughRequestId → throughTurnId`
     across `ExtractionService`/`ReadPhase.Window`, filter/advance in `turn_id`
     space, and see step 9.
8. **`rest-server`: wiring + enqueue + transcript.** Build the `ToolRegistry` in
   `Application.kt` and pass it to `CoachingService`. In `ConvoRoutes`, map
   `VisibleExchange` to the message DTOs. Extraction enqueue: under A, unchanged
   (final response's `requestId`); **under B**, pass `userTurn.turnId`. Update
   `rest-server` convo/streaming tests.
   - Verify: `nix develop -c bin/test rest-server -f`.
9. **Decision B only: `extraction_runs` turn-id watermark.** Add the
   `extraction_runs` changes to migration `0026` (drop
   `extraction_runs_through_request_id_fkey`, rename column
   `through_request_id → through_turn_id`; the watermark index follows the
   rename; legacy values already equal `turn_id`s). Rename
   `ExtractionRun.throughRequestId → throughTurnId` (`ConvoTurnId`),
   `NewExtractionRun`, `ExtractionRunsDao` (`mapRun`, bind, `watermark` query,
   remove the dropped-FK error branch), `ExtractionPayload`,
   `ExtractionHandler`, and `admin-web`'s `ExtractionRunsResource`. Update the
   affected `db`/`service`/ `rest-server`/`admin-web` tests.
   - Verify: `nix develop -c bin/test db -f`;
     `nix develop -c bin/test service -f`;
     `nix develop -c bin/test rest-server -f`.
10. **Invariants.** Add the three invariants to their target `INVARIANTS.md`
    files: the per-call usage rule and the shared-`turn_id` rule to
    `service/src/main/kotlin/ed/unicoach/coaching/INVARIANTS.md`; the whole-turn
    watermark rule to
    `service/src/main/kotlin/ed/unicoach/coaching/extraction/INVARIANTS.md`
    (append to its History checklist an RFC-94 entry).
    - Verify: files present; `nix develop -c bin/format -c`.
11. **Full gate.** Run the whole suite and the formatter.
    - Verify: `nix develop -c bin/test -f`; `nix develop -c bin/format -c`.

## Files Modified

**`chat`**

- `chat/src/main/kotlin/ed/unicoach/chat/ChatMessage.kt` —
  `content: JsonElement`
  - `text(...)` companion.
- `chat/src/main/kotlin/ed/unicoach/chat/ChatRequest.kt` — add `tools`.
- `chat/src/main/kotlin/ed/unicoach/chat/ChatTool.kt` — new interface.
- `chat/src/main/kotlin/ed/unicoach/chat/ToolRegistry.kt` — new.
- `chat/src/main/kotlin/ed/unicoach/chat/AnthropicChatProvider.kt` — verbatim
  `content`, `tools` serialization.
- `chat/src/main/kotlin/ed/unicoach/chat/LogOnlyChatProvider.kt` — read
  `content`.
- `chat/src/test/kotlin/ed/unicoach/chat/ChatProviderTest.kt`
- `chat/src/test/kotlin/ed/unicoach/chat/LogOnlyChatProviderTest.kt`
- `chat/src/test/kotlin/ed/unicoach/chat/AnthropicChatProviderTest.kt`
- `chat/src/test/kotlin/ed/unicoach/chat/ToolRegistryTest.kt` — new.

**`db`**

- `db/schema/0025.add-convo-requests-kind.sql` — new migration.
- `db/schema/0026.add-convo-requests-turn-id.sql` — new migration (sequence +
  `turn_id`; **decision B** adds the `extraction_runs` FK-drop + column rename).
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoRequest.kt` — add `kind`,
  `turnId`.
- `db/src/main/kotlin/ed/unicoach/db/models/NewConvoRequest.kt` — add `kind`,
  `turnId`.
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoRequestKind.kt` — new enum.
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoTurnId.kt` — new value class.
- `db/src/main/kotlin/ed/unicoach/db/dao/ConvosDao.kt` — `nextTurnId`;
  write/read `kind` + `turn_id`.
- `db/src/test/kotlin/ed/unicoach/db/dao/ConvosDaoTest.kt`

**`service`**

- `service/build.gradle.kts` — add `:college` dependency.
- `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt` — loop,
  `tools` param, `turn_id` mint/stamp, projection use.
- `service/src/main/kotlin/ed/unicoach/coaching/CoachingConfig.kt` —
  `maxToolRounds`.
- `service/src/main/kotlin/ed/unicoach/coaching/ConvoContent.kt` — `tool_result`
  block + `tool_use` extraction helpers.
- `service/src/main/kotlin/ed/unicoach/coaching/ConvoProjection.kt` — new
  (`VisibleExchange`, `visibleExchanges` grouping by `turn_id`).
- `service/src/main/kotlin/ed/unicoach/coaching/CollegeChatTool.kt` — new
  adapter.
- `service/src/main/kotlin/ed/unicoach/coaching/ListTurnsResult.kt` — `Found`
  carries `List<VisibleExchange>` instead of `List<ConvoTurn>`.
- `service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionService.kt`
  — turn-bounded window; route transcript through `visibleExchanges`.
- `service/src/main/resources/service.conf` — `coaching.maxToolRounds`.
- `service/src/main/kotlin/ed/unicoach/coaching/INVARIANTS.md` — per-call usage
  rule + shared-`turn_id` rule.
- `service/src/main/kotlin/ed/unicoach/coaching/extraction/INVARIANTS.md` —
  whole-turn watermark rule (+ RFC-94 History entry).
- `service/src/test/kotlin/ed/unicoach/coaching/CoachingServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/ConvoProjectionTest.kt` — new.
- `service/src/test/kotlin/ed/unicoach/coaching/CollegeChatToolTest.kt` — new.
- `service/src/test/kotlin/ed/unicoach/coaching/extraction/ExtractionServiceTest.kt`

**`rest-server`**

- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — build/inject
  the `ToolRegistry`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/ConvoRoutes.kt` — map
  `VisibleExchange` to DTOs (**decision B:** enqueue with `userTurn.turnId`).
- `rest-server/src/test/kotlin/ed/unicoach/rest/` — convo/streaming route tests
  touching the transcript projection and extraction enqueue.

**Decision B only (turn-id watermark):**

- `db/src/main/kotlin/ed/unicoach/db/models/ExtractionRun.kt` — `throughTurnId`.
- `db/src/main/kotlin/ed/unicoach/db/models/NewExtractionRun.kt` —
  `throughTurnId`.
- `db/src/main/kotlin/ed/unicoach/db/dao/ExtractionRunsDao.kt` — column rename,
  watermark query, drop the removed-FK error branch.
- `queue/src/main/kotlin/ed/unicoach/queue/ExtractionPayload.kt` —
  `throughTurnId`.
- `service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionHandler.kt`
  — payload → `ConvoTurnId`.
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/ExtractionRunsResource.kt`
  — field/label rename.

Note: `ListTurnsResult.Found` (its own file `ListTurnsResult.kt`, listed above)
changes from `List<ConvoTurn>` to `List<VisibleExchange>` with step 6.
