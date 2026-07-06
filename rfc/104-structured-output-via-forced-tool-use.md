# RFC 104: Structured Output via Forced Tool Use

## Executive Summary

The four coaching LLM call sites that need structured JSON today prompt for a
free-text JSON document, render the assistant text, and parse it with
`json.parseToJsonElement(raw) as? JsonObject`. Two of RFC 101's three
`JsonParseFailure` categories exist only because that envelope can arrive as
prose that is not valid JSON, or valid JSON that is not an object.

This RFC converts all four calls to **forced tool use** (Tier A): each request
carries a single tool definition whose `input_schema` mirrors the fields the
call's `parseX` already expects, plus `tool_choice: {type:"tool", name:…}`
forcing that tool. The model emits a `tool_use` block whose `input` is the JSON
object — assembled by the transport from `input_json_delta` fragments, already
present in `Completed.response.content`. The response side pulls that `input`
JsonObject from the block instead of rendering text; existing per-field
extraction runs over it unchanged.

Because the payload arrives as a structured `tool_use.input` object rather than
prose we parse, `MALFORMED_JSON` and `NOT_A_JSON_OBJECT` become unrepresentable.
The residual surface is `INVALID_FIELD` (bad enum / UUID / blank / out-of-match-
set value — the schema is guidance, not a hard validator on this tier) plus a
new "no tool_use block" outcome. Both map onto RFC 101's persisted categories,
so admin diagnostics keep working with **no DB enum migration**.

Scope is exactly the four structured-output calls in
`service/.../coaching/{extraction,synthesis,fitlens}`. `CoachingService` (the
RFC 43 interactive chat path, already using tools for genuine function calling)
is untouched. The model stays `claude-sonnet-4-6`; a Tier B `strict: true`
upgrade (needing a model bump) is deferred.

## Detailed Design

### Verified starting state (code, not RFCs)

- `ChatRequest.tools: List<JsonObject>` already carries verbatim Anthropic tool
  specs, serialized into the body's `tools` array and omitted when empty.
- The Anthropic transport already accumulates `input_json_delta` fragments per
  content index (`MessageAccumulator.toolInputBuffers`) and parses them into
  `block["input"]` at `content_block_stop`, so `Completed.response.content`
  carries the tool_use block with its full `input` JsonObject.
  `AnthropicChatProviderTest` already covers this
  (`tool_use accumulates parsed
  input`,
  `…empty buffer accumulates an empty object`). **No transport change is in
  scope.**
- `ContentBlocks.renderText` deliberately renders `tool_use` blocks as empty, so
  the response side cannot use it for the tool payload.
- Extraction/Synthesis share `JsonParseFailure` → `JsonParseFailureCategory`
  (`NOT_A_JSON_OBJECT` / `MALFORMED_JSON` / `INVALID_FIELD`). FitLens has its
  own `FailureReason` keyed by `studentId` → `FitLensFailureCategory`
  (`MALFORMED_OUTPUT` / `INVALID_CONTENT`).
- All four calls resolve `model` to `claude-sonnet-4-6` via `service.conf`.
- Prompts are immutable `system_prompts` catalog rows (RFC 66), pinned by
  `config.promptVersion`; a change is a new version row + a config bump.

### 1. `ChatRequest.toolChoice` (new typed field)

`tool_choice` rides as a new typed field, not through `params`:

```kotlin
data class ChatRequest(
  …
  val tools: List<JsonObject> = emptyList(),
  // Verbatim Anthropic tool_choice object, e.g. {"type":"tool","name":…};
  // serialized as `tool_choice` when present, omitted when null so tool_choice-
  // less callers stay byte-identical on the wire.
  val toolChoice: JsonObject? = null,
  val params: JsonObject? = null,
)
```

Rationale (inline): symmetric with `tools`, which already holds an opaque
verbatim-Anthropic shape as a typed field. `tool_choice` is request-shaping, not
the persisted opaque vendor `params` that mirror
`convo_requests.request_params`. `AnthropicChatProvider.requestBody` gains one
line: `request.toolChoice?.let {
put("tool_choice", it) }`. `CoachingService`
leaves it null, so that path is byte-identical.

### 2. `ContentBlocks.toolUseInput` (new shared helper)

The single-forced-tool read step lives in the `chat` module beside `renderText`:

```kotlin
// The `input` object of the first tool_use block in [content], or null when no
// tool_use block is present (a forced tool call that produced none). A present
// block with absent/non-object input yields an empty object, mirroring
// ConvoContent.toolUses. Forced tool_choice yields exactly one tool_use block.
fun toolUseInput(content: JsonElement): JsonObject?
```

`ConvoContent.toolUses` (the multi-block list used by `CoachingService`'s
tool-use loop) is unchanged; this is the distinct single-payload variant the
structured-output calls need.

### 3. Per-call tool definition + forced choice

Each service gains a private tool-spec `JsonObject` constant whose
`input_schema` is derived from the fields its `parseX` already reads, with every
enum's values enumerated to steer the model. Each request sets
`tools = listOf(TOOL)` and `toolChoice = {"type":"tool","name":TOOL_NAME}`.

- **Extraction — `record_extraction`**: `observations[]` (`sourceRequestId`:
  integer, `quote`: string); `claims[]` (`op`: enum new/reinforce/supersede,
  `statement`: string, `kind`: enum goal/preference/constraint/fact/concern,
  `subject`: enum student/family/college/application, `topic`: enum
  academics/activities/finances/location/career/timeline/wellbeing, `origin`:
  enum student_stated/coach_inferred, `visibility`: enum
  student_visible/internal (optional), `supports`: integer[], `targetClaimId`:
  string (optional)).
- **Synthesis — `record_synthesis`**: `commitments[]` (`lens`: enum,
  `disclosure`: enum, `statement`: string, `triggerAt`: string (optional),
  `supports`: integer[]) — enums enumerated from the corresponding domain enums.
- **FitLens query — `record_college_query`**: the `CollegeQuery` filter fields
  (`cipPrefix`, `states[]`, `region`, `locales[]`, `control[]`,
  `minUndergradEnrollment`, `maxUndergradEnrollment`, `minAdmissionRate`,
  `maxAdmissionRate`, `maxNetPrice`, `minGraduationRate`), all optional (absent
  = unconstrained). `limit` is not in the schema — the service sets it after
  parse.
- **FitLens reason — `record_fit_reason`**: `collegeId`: string (optional),
  `rationale`: string (optional). Absent/blank `collegeId` = no fit.

The schema on Tier A is guidance, not a hard validator, so `required` may be
omitted; the code-side field extraction remains the enforcement point.

### 4. Response read + `parseX(root: JsonObject)`

Each call site replaces `ConvoContent.renderText(response.content)` +
`parseX(raw)` with `ContentBlocks.toolUseInput(response.content)`:

- `null` → the "no tool_use block" failure (below), written as a `failed` run
  exactly as an unparseable output is today.
- `JsonObject root` → `parseX(root)`.

`parseX` loses its `raw: String` parameter and its
`json.parseToJsonElement(raw)
.trim() as? JsonObject` / try-catch prologue (and
the now-unused `json` field); it takes `root: JsonObject` and keeps every
existing per-field extraction and `JsonParseFailure.BadField` / `FailureReason`
mapping verbatim. The change is strictly _where the JsonObject comes from_.

### 5. Failure ADTs — the shrunk surface

Because forced tool input is always a valid JSON object, the parse-prologue
variants become unreachable and are removed; a single "no tool_use block"
variant replaces them, reusing existing persisted categories (no DB migration):

- **`JsonParseFailure`** (extraction/synthesis): remove `NotAnObject` and
  `MalformedJson`; add `NoToolUse` → category `NOT_A_JSON_OBJECT` (the payload
  the model was forced to produce as a structured object never arrived),
  `toDisplay()` = `"response carried no tool_use block"`. `BadField` and
  `INVALID_FIELD` unchanged.
- **`FailureReason`** (fitlens): remove `QueryNotJsonObject` /
  `QueryMalformedJson` / `ReasonNotJsonObject` / `ReasonMalformedJson`; add
  `QueryNoToolUse(studentId)` and `ReasonNoToolUse(studentId)` → category
  `MALFORMED_OUTPUT`, joining the surviving `QueryTypeInvalidField`, which the
  category map already routes to `MALFORMED_OUTPUT` (a type-invalid tool-input
  field is a shape defect, not a content defect). The remaining content variants
  (`ReasonInvalidCollegeId`, `…OutsideMatchSet`, `…RationaleMissing`,
  `…RationaleTooLong`) → `INVALID_CONTENT` unchanged.

Reusing `NOT_A_JSON_OBJECT` for the new extraction/synthesis `NoToolUse` variant
is a deliberate overload of an existing persisted category: a forced tool
payload that never arrived is the tier-A analogue of the old prose-not-an-object
failure, so it fits the category's semantics and needs no DB enum migration. The
`JsonParseFailureCategory` / `FitLensFailureCategory` enums and their DB CHECKs
are untouched; `malformed_json`/`malformed_output` remain valid historical
vocabulary with no new live producer.

### 6. Prompts — v2 catalog rows + config bump

One migration seeds four v2 rows — `extraction/v2`, `synthesis/v2`,
`fit_lens_query/v2`, `fit_lens_reason/v2` — with architect-approved copy that
(a) instructs the model to **call the tool** rather than "respond with a strict
JSON object and nothing else", and (b) drops the now-redundant JSON-shape/enum
enumeration (the tool `input_schema` carries it), keeping the semantic guidance
(what to extract, when to use each op, novelty steering, no-confidence rule).
`service.conf` bumps `extraction.promptVersion`, `synthesis.promptVersion`,
`fitLens.queryPromptVersion`, `fitLens.reasonPromptVersion` to `"v2"`.

Correctness note: forced `tool_choice` compels the tool_use block regardless of
prompt text, so the v2 copy is a token/clarity cleanup, not a correctness
dependency; v1 would also produce correct tool calls.

### 7. Model decision (explicit)

- **(a) Adopt Tier A forced tool use now**, on the current `claude-sonnet-4-6`.
  Kills `MALFORMED_JSON` and `NOT_A_JSON_OBJECT` (unrepresentable), zero model
  change. This RFC.
- **(b) Tier B (`strict: true`) is a named deferred follow-up.** `strict` would
  also foreclose most `INVALID_FIELD` cases, but it is gated to Opus 4.8 /
  Sonnet 5 / Haiku 4.5 — not `sonnet-4-6` — so it requires a model bump and
  re-tuning. Out of scope now; a future RFC.

The token-usage-recording invariant is unaffected: a `Completed` call is billed
and writes its run row (applied or failed) regardless of tier or model.

### Error Handling / Edge Cases

- **No tool_use block** (model declined, refusal, empty content): `toolUseInput`
  returns null → `NoToolUse` / `Query|ReasonNoToolUse` → billed `failed` run.
- **Tool input violates a field constraint** (bad enum, blank statement, bad
  UUID, collegeId outside match set, over-length rationale): unchanged
  `BadField` / content-`FailureReason` → `INVALID_FIELD` / `INVALID_CONTENT`.
- **Empty tool input `{}`**: extraction/synthesis treat absent arrays as empty →
  a legitimate zero-write `APPLIED` run (the "nothing to extract" outcome, as
  today with `{"observations":[],"claims":[]}`); fitlens reason treats
  absent/blank `collegeId` as `ReasonParse.Empty` (no fit).
- **Parallel tool use**: forced single-tool `tool_choice` yields exactly one
  block; `toolUseInput` takes the first defensively.

### Dependencies

None new. Uses the existing `chat` module transport, RFC 101 failure
persistence, and the `system_prompts` catalog.

## Tests

`chat` module:

- **`ContentBlocksTest` (new)** — `toolUseInput` returns the input object of a
  tool_use block; returns null for a text-only block array; returns null for a
  non-array; returns an empty object for a tool_use block whose input is
  absent/non-object; takes the first of multiple tool_use blocks. Block-body
  tests; assert executed count.
- Transport accumulation is already covered (`AnthropicChatProviderTest`); no
  new transport test.

Each of the three service tests drives its existing fake `ChatProvider` but with
the `Completed.response.content` built as a **tool_use block** whose `input` is
a given JsonObject (replacing today's single text block holding a JSON string):

- **ExtractionServiceTest** — valid `record_extraction` input → a `applied` run
  row (observations/claims written, watermark advances); input with a bad enum
  (e.g. `kind:"bogus"`) → a `failed` run row with
  `failure_category = invalid_field` (the returned `ExtractionResult` stays
  `TransientFailure`, as on any parse failure today); content with no tool_use
  block (text-only) → a `failed` run row mapped via `NoToolUse` (category
  `not_a_json_object`), token usage still recorded.
- **SynthesisServiceTest** — valid `record_synthesis` → a `applied` run row; bad
  `lens`/`disclosure` enum → a `failed` run row `invalid_field`; no tool_use
  block → a `failed` run row via `NoToolUse` (both keep the returned
  `SynthesisResult` as `TransientFailure`).
- **FitLensServiceTest** — valid `record_college_query` + `record_fit_reason`
  (chosen college) → `Applied` with a suggestion; a `collegeId` outside the
  match set → `Failed` `invalid_content`; a type-invalid query field → `Failed`
  `malformed_output` (via `QueryTypeInvalidField`, which the category map routes
  to `MALFORMED_OUTPUT`); no tool_use block on either call → `Failed` via
  `Query|ReasonNoToolUse` (category `malformed_output`); an empty reason object
  → `Skipped` (no fit).
- Assert each request carries `tools` (one spec) and `toolChoice`
  (`{"type":"tool","name":…}`) via a capturing provider.

All service tests use real `Database` + fake `ChatProvider`, block-body funcs,
executed-vs-declared count checked.

## Invariants

**Rule:** The four structured-output coaching passes (extraction, synthesis,
fit-lens query, fit-lens reason) MUST obtain their parsed payload from the
forced tool's `tool_use.input` object (`ContentBlocks.toolUseInput`), and MUST
send both the tool definition and `tool_choice: {type:"tool", name:…}` that
force it — never rendered assistant text.

**Why:** `ContentBlocks.renderText` renders `tool_use` blocks as empty. A
refactor that reads the payload with `renderText`, or drops `tool_choice` so the
model may answer in prose, silently produces a 100%-`NoToolUse` failure rate
that reads as a model problem, not a code regression — re-introducing the
unparseable-envelope failure class this RFC removes. The coupling is a
write-path discipline, not type-enforced.

**Target directory:** `service/src/main/kotlin/ed/unicoach/coaching`
(`INVARIANTS.md`).

## Implementation Plan

1. **`ChatRequest.toolChoice` + serialization.** Add the field to
   `ChatRequest.kt`; add `request.toolChoice?.let { put("tool_choice", it) }` to
   `AnthropicChatProvider.requestBody`. Verify:
   `nix develop -c ./gradlew :chat:compileKotlin`.
2. **`ContentBlocks.toolUseInput` + test.** Add the helper; add
   `ContentBlocksTest`. Verify: `nix develop -c bin/test chat -f` (assert new
   tests executed).
3. **Failure ADTs.** In `JsonParseFailure.kt` remove
   `NotAnObject`/`MalformedJson`, add `NoToolUse`, update
   `category`/`toDisplay`. In `FitLensResult.kt` remove the four
   `*NotJsonObject`/`*MalformedJson` variants, add `Query|ReasonNoToolUse`,
   update `category`/`toDisplay`. Verify:
   `nix develop -c ./gradlew :service:compileKotlin`.
4. **Extraction call site.** Add `record_extraction` tool spec; set
   `tools`/`toolChoice` on the request; read via `toolUseInput`; convert
   `parseOutput` to `(root: JsonObject)`; map null → `NoToolUse`.
5. **Synthesis call site.** Same shape with `record_synthesis`.
6. **FitLens call sites.** Add `record_college_query` / `record_fit_reason`
   specs; set `tools`/`toolChoice` on both requests; read via `toolUseInput`;
   convert `parseQuery`/`parseReason` to `(root: JsonObject)`; map null →
   `Query|ReasonNoToolUse`. Verify (4–6):
   `nix develop -c ./gradlew :service:compileKotlin`.
7. **v2 prompt seeds.** `db/schema/0037.seed-forced-tool-use-prompts.sql`
   inserts `extraction/v2`, `synthesis/v2`, `fit_lens_query/v2`,
   `fit_lens_reason/v2`. Verify: migration applies in the `bin/test` DB re-init.
8. **Config bump.** In `service.conf` set the four `*promptVersion` defaults to
   `"v2"`.
9. **Service + helper tests.** Update the three service tests' fake providers to
   emit tool_use blocks; add the valid/invalid-field/no-tool-use cases. Verify:
   `nix develop -c bin/test service -f` and `nix develop -c bin/test -f`
   (confirm "N executed", full suite green).
10. **Invariant.** Add the §Invariants rule to
    `service/src/main/kotlin/ed/unicoach/coaching/INVARIANTS.md`.
11. **Lint gate.** `nix develop -c bin/format -c`.

## Files Modified

- `chat/src/main/kotlin/ed/unicoach/chat/ChatRequest.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/AnthropicChatProvider.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/ContentBlocks.kt`
- `chat/src/test/kotlin/ed/unicoach/chat/ContentBlocksTest.kt` (new)
- `service/src/main/kotlin/ed/unicoach/coaching/JsonParseFailure.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionService.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisService.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensService.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensResult.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/INVARIANTS.md`
- `service/src/main/resources/service.conf`
- `db/schema/0037.seed-forced-tool-use-prompts.sql` (new)
- `service/src/test/kotlin/ed/unicoach/coaching/extraction/ExtractionServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/synthesis/SynthesisServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensServiceTest.kt`
