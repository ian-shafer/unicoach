# 43 — Provider-Agnostic LLM Chat Provider

## Executive Summary

This RFC introduces the `chat` module: a provider-agnostic port for LLM chat
completions, the streaming event model the port speaks, and a no-network stub
adapter — the first of three RFCs (port → Anthropic adapter → coaching service).
It mirrors RFC 34's shape for `email`: port interface, stub adapter, config, and
factory in one leaf module; the transmitting adapter follows in its own RFC, as
RFC 37 did for SES.

The port is consumed by the coaching service's request handlers (future RFC),
not a queue worker: the handler writes the `convo_requests` row, collects the
provider flow on the request coroutine — relaying text deltas as SSE frames on
RFC 39's streaming endpoints — and persists the terminal outcome. The response
is a cold `Flow<ChatEvent>` ending in exactly one terminal event that
distinguishes permanent from retriable failure; the taxonomy maps the Anthropic
Messages API streaming events losslessly while staying vendor-neutral.

The stub's identity `"log"` becomes insertable into `convo_requests.provider`
via migration `0009`. The module stays unwired: compiled and tested only.

## Detailed Design

### Module layout

`chat` is a new leaf Gradle library module, package `ed.unicoach.chat`,
registered in `settings.gradle.kts` and in `bin/test`'s `MODULES` array (both
the array and the help text enumerate modules). It mirrors `email`: no other
module depends on it, no `main()` constructs a provider, and there is no
production `AppConfig.load(...)` callsite to modify — only this module's own
test fixtures load `chat.conf`. Production wiring belongs to the
coaching-service RFC. `bin/build` is untouched (`email` sets the precedent:
library modules outside the daemon build order are not listed there).

### Data models — request

`ChatRequest` is the provider-agnostic input for one completion call.

```kotlin
data class ChatRequest(
  val model: String,                 // provider model id, e.g. "claude-opus-4-8"
  val system: String?,               // system prompt body (verbatim text, not an id)
  val messages: List<ChatMessage>,   // replayed history + the new turn, oldest first
  val maxTokens: Int,                // response token ceiling
  val params: JsonObject? = null,    // vendor passthrough, mirrors convo_requests.request_params
)

data class ChatMessage(
  val role: ChatRole,
  val text: String,
)

enum class ChatRole { USER, ASSISTANT }
```

The port does not validate: `model` arrives as the string the caller pins into
`convo_requests.model_requested`, and `system` is the resolved prompt body — the
`system_prompts` catalog and `system_prompt_id` FK are the caller's concern
(layer purity; the applied schema's FK rewire in migration `0007` is DB-level
provenance, not a port input). An empty `messages` list is passed through
unvalidated; the provider answers it however it answers (the stub echoes an
empty turn, a real provider rejects it as a provider outcome). `params` is an
opaque vendor object because `convo_requests.request_params` is deliberately
opaque JSONB interpreted via `provider`; typed fields are reserved for
parameters every vendor shares (`maxTokens` is required by the Anthropic
Messages API and universal enough to type).

### Data models — streaming events

`ChatEvent` is a sealed event taxonomy; one provider call yields a stream of
them. The non-terminal events carry the live view (deltas for future SSE relay);
the terminal events carry the outcome.

```kotlin
sealed interface ChatEvent {
  // Provider acknowledged the request and began a message.
  data class MessageStart(
    val providerRequestId: String?,  // provider's message/request id
    val model: String?,              // model actually serving the request
    val usage: TokenUsage?,          // e.g. input tokens, known at start
  ) : ChatEvent

  // A content block opened at `index`.
  data class ContentBlockStart(
    val index: Int,
    val blockType: String,           // provider's block type verbatim: "text", "thinking", "tool_use", ...
    val block: JsonElement?,         // verbatim initial block object when it carries data beyond its type
  ) : ChatEvent

  // Incremental content for the block at `index`.
  data class ContentBlockDelta(
    val index: Int,
    val delta: ContentDelta,
  ) : ChatEvent

  // The block at `index` is complete.
  data class ContentBlockStop(
    val index: Int,
  ) : ChatEvent

  // Message-level update (stop reason, cumulative usage).
  data class MessageDelta(
    val stopReason: String?,
    val usage: TokenUsage?,
  ) : ChatEvent

  // Exactly one Terminal ends every stream.
  sealed interface Terminal : ChatEvent

  // The provider produced a usable reply.
  data class Completed(
    val response: ChatResponse,
    val rawPayload: JsonElement,     // verbatim provider payload for convo_responses_raw
  ) : Terminal

  // Permanent failure; no retry helps.
  data class Rejected(
    val reason: String,              // human-readable classification, bracketed-value style
    val providerRequestId: String?,  // provider's request id when one was assigned
    val rawPayload: JsonElement?,    // verbatim provider error body when one exists
  ) : Terminal

  // Retriable failure.
  data class TransientFailure(
    val reason: String,
    val providerRequestId: String?,
    val rawPayload: JsonElement?,
  ) : Terminal
}

sealed interface ContentDelta {
  data class Text(val text: String) : ContentDelta
  data class Thinking(val thinking: String) : ContentDelta
  data class ToolInput(val partialJson: String) : ContentDelta
  data class Opaque(val raw: JsonElement) : ContentDelta  // unrecognized delta types, verbatim
}
```

`blockType` is a verbatim provider string, not an enum: the column it ultimately
informs (`convo_responses.content`) is opaque JSONB, and an enum would force a
lossy "other" bucket every time a vendor adds a block type.
`ContentDelta.Opaque` is the same escape hatch at the delta level (e.g.
Anthropic's `signature_delta`), preserving fidelity without freezing the
taxonomy. `ContentBlockStart.block` carries the verbatim block object because
some block types open with data (a `tool_use` block opens with its id and name);
it is `null` when the block opens empty.

The taxonomy maps the Anthropic Messages API streaming events 1:1:

| Anthropic SSE event                        | `ChatEvent`                                                  |
| ------------------------------------------ | ------------------------------------------------------------ |
| `message_start`                            | `MessageStart` (`message.id`, `message.model`, input usage)  |
| `content_block_start`                      | `ContentBlockStart` (`index`, `content_block.type`, block)   |
| `content_block_delta` + `text_delta`       | `ContentBlockDelta` + `ContentDelta.Text`                    |
| `content_block_delta` + `thinking_delta`   | `ContentBlockDelta` + `ContentDelta.Thinking`                |
| `content_block_delta` + `input_json_delta` | `ContentBlockDelta` + `ContentDelta.ToolInput`               |
| `content_block_delta` + other delta types  | `ContentBlockDelta` + `ContentDelta.Opaque` (verbatim)       |
| `content_block_stop`                       | `ContentBlockStop`                                           |
| `message_delta`                            | `MessageDelta` (`stop_reason`, cumulative usage)             |
| `message_stop`                             | `Completed` (accumulated response + raw payload)             |
| `ping`                                     | dropped — carries no information                             |
| `error`                                    | `Rejected` or `TransientFailure` per the classification rule |

The error classification rule is a port-level contract the Anthropic adapter RFC
will implement: an error the adapter does not recognize MUST map to
`TransientFailure`, never `Rejected` — a bounded retry is safer than silently
dropping a turn (the same rule RFC 37 pinned for SES). Both failure terminals
carry the provider's request id and verbatim error body when the provider
supplied them (`null` when the failure precedes any provider response, e.g. a
connect timeout), so the caller can persist faithful error provenance instead of
a bare reason string.

### Data models — response and usage

`ChatResponse` is the parsed reply, field-aligned with `NewConvoResponse` so the
coaching service's mapping is mechanical.

```kotlin
data class ChatResponse(
  val content: JsonElement,          // assistant content blocks, verbatim-parsed → convo_responses.content
  val modelResolved: String,         // exact model that ran → convo_responses.model_resolved
  val stopReason: String,            // provider stop reason verbatim → convo_responses.stop_reason
  val usage: TokenUsage,             // → convo_responses.*_tokens columns
  val providerRequestId: String?,    // → convo_responses.provider_request_id
)

data class TokenUsage(
  val inputTokens: Int?,
  val outputTokens: Int?,
  val cacheReadTokens: Int?,         // Anthropic: cache_read_input_tokens
  val cacheWriteTokens: Int?,        // Anthropic: cache_creation_input_tokens
)
```

`stopReason` stays a verbatim provider string (faithful capture; the DB column
is free TEXT with the same stance). `latency_ms` is excluded: the caller
measures wall-clock around the port call. The `stop_reason = 'error'` /
`content = NULL` row shape for failed turns is the caller's mapping of
`Rejected`/`TransientFailure`, not a `ChatResponse` state — `Completed` always
carries non-null content and a non-null raw payload, matching
`convo_responses_raw.payload NOT NULL` and the DAO's success-path contract.

`Completed.rawPayload` is the canonical provider response object — for a
streaming Anthropic call, the accumulated final `Message` (identical in shape to
the non-streaming response body), not the SSE event array. The accumulated
object contains everything the event sequence carried except chunking
boundaries, which have no replay value and would bloat the 1:1 raw log.

### API contract — the port

`ChatProvider` is the port; one method, streaming-first.

```kotlin
interface ChatProvider {
  // Provider identity; written verbatim to convo_requests.provider by the caller.
  val id: String

  // Cold flow: each collection performs one provider call.
  fun stream(request: ChatRequest): Flow<ChatEvent>
}

// Non-streaming accumulation: collects the stream, returns its terminal event.
suspend fun ChatProvider.chat(request: ChatRequest): ChatEvent.Terminal
```

Stream contract (binding on every adapter):

- The flow emits zero or more non-terminal `ChatEvent`s, then **exactly one**
  `ChatEvent.Terminal`, then completes normally. Nothing follows a terminal
  event.
- Expected provider outcomes are NEVER thrown; they are returned as `Rejected` /
  `TransientFailure` terminal events. An exception escaping the flow (other than
  `CancellationException`) is a defect, not an API channel; the caller treats it
  as transient.
- The flow is cold: re-collecting is a fresh transmission. Retry semantics are
  therefore at-least-once with no idempotency key — the same allocation RFCs
  34/37 made for email, closed by the coaching-service RFC, not here.
- Collector cancellation cancels the in-flight provider call (cooperative `Flow`
  cancellation); no partial response is surfaced or persisted at the port level.
- Implementations own their execution context: the flow must be safe to collect
  from any dispatcher, with any internal blocking I/O shifted off the
  collector's context by the adapter (`flowOn`), never delegated to the caller.
- Event order is the provider's order; block `index` correlates
  `ContentBlockStart`/`Delta`/`Stop` for interleaved blocks.

`chat(request)` is the trivial accumulation non-streaming callers (RFC 39's
buffered endpoints) use: collect, return the last event (the terminal, by
contract). A stream that violates the contract — empty, or ending on a
non-terminal — makes the extension throw `IllegalStateException`, which is the
correct channel: a contract violation is a defect, not an outcome.

### Stub adapter — `LogOnlyChatProvider`

`LogOnlyChatProvider` is a no-network stub adapter so the coaching-service RFC
can build and test against the port. `id == "log"` (constant `PROVIDER_ID`),
mirroring `LogOnlyEmailProvider`.

For a request it emits, in order:

1. `MessageStart(providerRequestId = random UUID string, model = request.model,
   usage = TokenUsage(inputChars, null, null, null))`
2. `ContentBlockStart(index = 0, blockType = "text", block = null)`
3. One or more `ContentBlockDelta(0, ContentDelta.Text(chunk))` events: the
   reply text split into chunks of at most `DELTA_CHUNK_SIZE` (16) characters,
   so accumulation is exercised with multiple deltas.
4. `ContentBlockStop(0)`
5. `MessageDelta(stopReason = "end_turn", usage = TokenUsage(inputChars,
   outputChars, null, null))`
6. `Completed(response, rawPayload)`

The reply text is `"[log] echo: "` + the text of the last message (empty string
when `messages` is empty) — so downstream tests can assert on content. Every
field of every event is deterministic given the request except
`providerRequestId`, which is a fresh UUID per collection (one provider call =
one id). `maxTokens` is ignored: the stub never truncates the echo (synthetic
counts are characters, not tokens, so a token ceiling has no meaningful
enforcement here). Synthetic "token" counts are character counts: `inputChars` =
total characters across `system` and all message texts, `outputChars` = reply
length; cache fields are null. The terminal response is
`ChatResponse(content = [{"type": "text", "text": reply}], modelResolved =
request.model, stopReason = "end_turn", usage, providerRequestId)`,
and `rawPayload` is the stub's own wire shape — a JSON object with `provider`
(`"log"`), `id`, `model`, `content`, `stop_reason`, and `usage` fields — not an
imitation of any vendor's format. It logs one line (provider id, model, message
count, all bracketed) and transmits nothing.

### Configuration

`chat/src/main/resources/chat.conf` defines the module's config surface (file
name = module name, per the HOCON naming mandate):

```hocon
chat {
  provider = "log"                   # "log" | (RFC 44) "anthropic"
  provider = ${?CHAT_PROVIDER}
}
```

`ChatConfig` is the typed reader, mirroring `EmailConfig`:

```kotlin
class ChatConfig private constructor(
  val provider: String,
) {
  companion object {
    // Reads chat.provider verbatim (packaged default "log").
    fun from(config: Config): Result<ChatConfig>
  }
}
```

`from` fails when the key is absent or unreadable — `Result.failure` carrying
the underlying typesafe `ConfigException` unmapped, per the `EmailConfig`
precedent. It does not validate the value — the factory is the single place an
unknown selector is rejected.

### Provider factory

`ChatProviderFactory` maps the config selector to exactly one adapter, mirroring
`EmailProviderFactory`.

```kotlin
object ChatProviderFactory {
  // "log" → LogOnlyChatProvider; anything else → Result.failure(IllegalArgumentException),
  // never a silent fallback. RFC 44 adds the "anthropic" branch.
  fun fromConfig(config: ChatConfig): Result<ChatProvider>
}
```

The failure message carries the offending value bracketed:
`"unknown chat.provider [${config.provider}]"`.

### Provider identity and the DB allowlist — migration `0009`

Migration `db/schema/0009.widen-convo-requests-provider.sql` adds `'log'` to the
`convo_requests.provider` allowlist, replacing the constraint with
`CHECK (provider IN ('anthropic', 'log'))`.

The applied schema (migration `0006`, unaltered by `0007`/`0008`) constrains the
column with `CHECK (provider IN ('anthropic'))`. The coaching service will write
`ChatProvider.id` verbatim into that column, and the stack must run end-to-end
in dev and test against the stub, so the stub's identity must be insertable.
Widening keeps provenance truthful (`'log'` rows are visibly synthetic) and
matches `0006`'s own comment: "Extend the list in a later migration as providers
are added." Production exposure is bounded by config: `chat.provider` defaults
to `"log"` only until the coaching service's production config pins
`"anthropic"`. The alternatives — the stub reporting `id = "anthropic"`, or
skipping persistence for stub turns — are rejected because the first falsifies
provenance in an append-only audit log and the second breaks the data layer's
every-turn-recorded invariant.

The migration:

```sql
ALTER TABLE convo_requests
  DROP CONSTRAINT convo_requests_provider_valid_check;
ALTER TABLE convo_requests
  ADD CONSTRAINT convo_requests_provider_valid_check
    CHECK (provider IN ('anthropic', 'log'));
```

DDL is unaffected by the table's append-only row triggers, and the constraint
name is reused so the `bin/db-convos-tests` assertions keyed on it keep
matching. The harness gains one assertion (provider `'log'` accepted); the
existing `'openai'`-rejected and `'anthropic'`-accepted assertions stand
unchanged as the regression.

### Dependencies

`chat/build.gradle.kts`: plugins `kotlin.jvm` + `ktlint` only — no serialization
plugin (the module declares no `@Serializable` types; it uses
`JsonElement`/`JsonObject` from `kotlinx-serialization-json`, which arrives as
an `api` dependency of `common`, the same pattern as `db`).

- `implementation(project(":common"))` — `AppConfig` (test config loading) and
  the `api`-exposed `typesafe-config` + `kotlinx-serialization-json`.
- `implementation(libs.kotlinx.coroutines.core)` — `Flow`.
- `implementation(libs.slf4j.api)` — stub logging.
- `testImplementation(libs.kotlin.test.junit5)`,
  `testImplementation(libs.kotlinx.coroutines.test)`.

Test tasks configure `useJUnitPlatform()` with `exceptionFormat = FULL` and
`events("failed")`, per the project's Gradle conventions. No dependency on `db`:
this module owns no persistence; `ConvosDao` is consumed by the future coaching
service, not by the port.

## Tests

All chat-module tests are pure JVM (no DB, no network). DB coverage for the
migration lives in the existing SQL harness.

`chat/src/test/kotlin/ed/unicoach/chat/LogOnlyChatProviderTest.kt`:

1. `id is the wire identity log` — `LogOnlyChatProvider().id == "log"`.
2. `stream emits a well-formed event sequence` — collect to a list: first event
   is `MessageStart` with `model == request.model` and
   `usage == TokenUsage(inputChars, null, null, null)`; exactly one `Terminal`
   exists, it is the last element, and it is `Completed`.
3. `event order correlates one text block` — a
   `ContentBlockStart(0, "text", block = null)` precedes all deltas; every delta
   carries index 0 and is `ContentDelta.Text`; `ContentBlockStop(0)` follows the
   last delta; `MessageDelta` reports `stopReason == "end_turn"` and
   `usage == TokenUsage(inputChars, outputChars, null, null)`.
4. `accumulated deltas equal the response content text` — concatenating all
   `ContentDelta.Text` chunks equals the text inside
   `Completed.response.content` (the accumulation invariant).
5. `reply is deterministic and echoes the last message` — two calls with the
   same request produce the same reply text, `"[log] echo: "` + last message
   text; additionally, collecting the **same** `Flow` instance twice yields two
   full sequences identical except the `providerRequestId` fields, which differ
   (cold flow: each collection is a fresh call with a fresh id).
6. `long replies chunk at the delta bound` — a last message longer than
   `DELTA_CHUNK_SIZE` characters yields two or more `ContentBlockDelta` events,
   each chunk at most `DELTA_CHUNK_SIZE` characters, and `maxTokens` does not
   truncate the echo.
7. `completed response carries the request envelope` —
   `modelResolved == request.model`, `providerRequestId` non-null,
   `usage.inputTokens` == total characters across `system` and all message
   texts, `usage.outputTokens` == reply length, cache fields null.
8. `raw payload is a structured stub object` — `rawPayload` is a `JsonObject`
   whose `provider` field is `"log"` and whose `content` matches
   `response.content`.
9. `empty messages list yields the empty echo` — no exception; reply text is
   exactly `"[log] echo: "`.
10. `chat extension returns the terminal event` — `provider.chat(request)` is a
    `Completed` whose `response` matches a fresh full collection's terminal on
    every field except `providerRequestId` (fresh per call).

`chat/src/test/kotlin/ed/unicoach/chat/ChatProviderTest.kt` (the `chat`
accumulation extension against inline fake providers, not the stub):

1. `chat throws on an empty stream` — a fake `ChatProvider` whose `stream`
   returns `emptyFlow()` makes `chat(request)` throw `IllegalStateException`.
2. `chat throws when the stream ends on a non-terminal` — a fake emitting only
   `MessageStart` makes `chat(request)` throw `IllegalStateException`.

`chat/src/test/kotlin/ed/unicoach/chat/ChatConfigTest.kt`:

1. `from reads the packaged default` — `AppConfig.load("chat.conf")` →
   `ChatConfig.from` → `provider == "log"`.
2. `from reads the provider verbatim` — config built via
   `ConfigFactory.parseString("chat.provider = \"anthropic\"")` yields
   `provider == "anthropic"` (no value validation in the reader).
3. `from fails when the key is absent` — an empty config yields `Result.failure`
   whose cause is a `ConfigException` (the pinned failure type).

`chat/src/test/kotlin/ed/unicoach/chat/ChatProviderFactoryTest.kt`:

1. `log selects LogOnlyChatProvider` — `fromConfig` on a `"log"` config returns
   success with a `LogOnlyChatProvider`.
2. `unknown provider yields failure, not a fallback` — `fromConfig` on a
   `"smtp"` config returns `Result.failure` of `IllegalArgumentException` whose
   message contains `[smtp]`.

`bin/db-convos-tests` (SQL harness, runs in `bin/test`'s DB phase):

1. `convo_requests: provider 'log' accepted` — new assertion, mirroring the
   existing `'anthropic'`-accepted test.
2. Existing assertions (`'openai'` rejected via
   `convo_requests_provider_valid_check`, `'anthropic'` accepted, NULL provider
   rejected) re-run unchanged as the migration regression.

## Implementation Plan

All commands run inside the Nix dev shell (`nix develop -c ...`). `bin/test`
recreates and migrates the test database before invoking Gradle, so it always
exercises the new migration.

1. **Migration `0009` + harness assertion.** Add
   `db/schema/0009.widen-convo-requests-provider.sql` (drop + re-add the
   `convo_requests_provider_valid_check` constraint with `'log'` added) and the
   `provider 'log' accepted` assertion in `bin/db-convos-tests`.
   - Verify: `nix develop -c bin/test db` (db-reset applies `0009`; the convos
     harness and `ConvosDaoTest` are green).
   - Verify: `nix develop -c bin/db-run ro '\d convo_requests'` shows the check
     listing both `'anthropic'` and `'log'`.
2. **Register the `chat` module.** Add `include("chat")` to
   `settings.gradle.kts`; add `chat` to `bin/test`'s `MODULES` array and its
   help-text module list; create `chat/build.gradle.kts` per §Dependencies and
   `chat/src/main/resources/chat.conf` per §Configuration.
   - Verify: `nix develop -c ./gradlew :chat:compileKotlin` succeeds (empty
     source set).
3. **Request/response types.** `ChatRole`, `ChatMessage`, `ChatRequest`,
   `TokenUsage`, `ChatResponse` (one type per file).
   - Verify: `nix develop -c ./gradlew :chat:compileKotlin :chat:ktlintCheck`.
4. **Event model and port.** `ContentDelta`, `ChatEvent` (with the `Terminal`
   hierarchy), `ChatProvider` (interface + `chat` accumulation extension in
   `ChatProvider.kt`) + `ChatProviderTest` (extension defect cases against
   inline fakes).
   - Verify: `nix develop -c bin/test chat`; confirm `ChatProviderTest` is
     present and green in the JUnit XML under `chat/build/test-results/test/` (a
     bare `BUILD SUCCESSFUL` can mask a no-tests-run state).
5. **Stub adapter.** `LogOnlyChatProvider` + `LogOnlyChatProviderTest`.
   - Verify: `nix develop -c bin/test chat`; confirm `LogOnlyChatProviderTest`
     is present and green in the JUnit XML.
6. **Config reader.** `ChatConfig` + `ChatConfigTest`.
   - Verify: `nix develop -c bin/test chat`; confirm `ChatConfigTest` in the
     JUnit XML.
7. **Factory.** `ChatProviderFactory` + `ChatProviderFactoryTest`.
   - Verify: `nix develop -c bin/test chat`; confirm `ChatProviderFactoryTest`
     in the JUnit XML.
8. **Full regression.**
   - Verify: `nix develop -c bin/test` (every module green, including the `db`
     phase that re-runs the convos SQL harness).

## Files Modified

New:

- `db/schema/0009.widen-convo-requests-provider.sql`
- `chat/build.gradle.kts`
- `chat/src/main/resources/chat.conf`
- `chat/src/main/kotlin/ed/unicoach/chat/ChatProvider.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/ChatEvent.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/ContentDelta.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/ChatRequest.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/ChatMessage.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/ChatRole.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/ChatResponse.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/TokenUsage.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/LogOnlyChatProvider.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/ChatConfig.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/ChatProviderFactory.kt`
- `chat/src/test/kotlin/ed/unicoach/chat/ChatProviderTest.kt`
- `chat/src/test/kotlin/ed/unicoach/chat/LogOnlyChatProviderTest.kt`
- `chat/src/test/kotlin/ed/unicoach/chat/ChatConfigTest.kt`
- `chat/src/test/kotlin/ed/unicoach/chat/ChatProviderFactoryTest.kt`

Updated:

- `settings.gradle.kts` — `include("chat")`
- `bin/test` — `MODULES` array and help-text module list gain `chat`
- `bin/db-convos-tests` — new `provider 'log' accepted` assertion
