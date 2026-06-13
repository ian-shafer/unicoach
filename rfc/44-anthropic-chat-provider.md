# 44 — Anthropic Chat Provider

## Executive Summary

`AnthropicChatProvider` is the first transmitting `ChatProvider`: it streams the
Anthropic Messages API (`POST /v1/messages`, `stream: true`) and maps the SSE
events onto the `chat` module's `ChatEvent` taxonomy (`ChatEvent.kt`, with the
fidelity invariants in the module `SPEC.md`). The SES email provider
(`email/.../SesEmailProvider.kt` over its `SesSendOperation` seam, plus
`SesConfig` and `EmailProviderFactory`; `email/.../SPEC.md`) is the structural
precedent: adapter behind an existing port, a narrow transport seam for
no-network tests, config + factory extension, module still unwired.

The adapter is a thin Ktor-client transport on the repo's pinned Ktor stack. It
accumulates the raw final `Message` — identical in shape to the non-streaming
response body — and surfaces it as `Completed.rawPayload` for
`convo_responses_raw` persistence. Errors classify into the permanent/retriable
terminal split: by Anthropic `error.type` first, by HTTP status bucket second,
with the port's unrecognized-error → `TransientFailure` rule (chat `SPEC.md`,
Failure classification) as the floor. One port extension: `ChatEvent` gains a
`Raw` case, so SSE event types outside the documented set surface verbatim
instead of being dropped.

Config gains a `chat.anthropic` block (API key via env override, base URL,
timeouts); `ChatProviderFactory` gains the `"anthropic"` branch; no DB work. The
module stays unwired: compiled and tested only; production wiring belongs to the
coaching service. All tests run against fake or replayed event sources — no live
network in CI.

## Detailed Design

### Transport

The adapter speaks the Messages API through a thin Ktor-client transport built
on the catalog's pinned Ktor 3.4.2 (`ktor-client-core` + `ktor-client-cio`),
with all JSON as `kotlinx-serialization` `JsonElement` — the type the port
already speaks; the only wire work is SSE framing plus JSON. (The official
`com.anthropic:anthropic-java` SDK is declined because its blocking,
auto-retrying OkHttp/Jackson client fights the port's cold-`Flow`,
re-collection-as-retry, verbatim-`JsonElement` contract.) Wire drift is held off
by the pinned `anthropic-version` and absorbed by the taxonomy's verbatim fields
(`blockType`, `ContentDelta.Opaque`, free-text `stopReason`, `ChatEvent.Raw`).

The transport pins `anthropic-version: 2023-06-01` (the current stable wire
version) as a constant and sends `x-api-key`, `content-type: application/json`,
and `accept: text/event-stream`. SSE framing is parsed by a small reader over
the response byte channel (per the SSE spec: `event:`/`data:` lines accumulate
until a blank line; multiple `data:` lines join with `\n`; `:` comment lines are
dropped) rather than Ktor's SSE plugin — the plugin fails non-2xx responses
before their bodies can be read, and the error body is load-bearing here
(`Rejected.rawPayload`). Retries belong to the caller: the flow is cold, so
re-collection is the retry.

### Transport seam

`AnthropicStreamTransport` is the internal seam between HTTP and mapping,
playing the role `SesSendOperation` plays for SES: the real implementation is
Ktor; tests supply a replayed sequence.

```kotlin
// One streaming Messages API call per collection. Emits Opened once (response
// accepted), then one Frame per SSE frame, then completes when the stream
// closes. Throws AnthropicHttpException for non-2xx responses (body read
// first); connect/read/parse failures propagate as their underlying
// exceptions. The provider, not the seam, turns all of these into terminals.
fun interface AnthropicStreamTransport {
  fun stream(body: JsonObject): Flow<AnthropicTransportEvent>
}

sealed interface AnthropicTransportEvent {
  // 2xx response accepted; requestId = the `request-id` response header.
  data class Opened(val requestId: String?) : AnthropicTransportEvent

  // One SSE frame, verbatim: event name + raw data payload (unparsed).
  data class Frame(val event: String, val data: String) : AnthropicTransportEvent
}

// Non-2xx response: status, `request-id` header, verbatim body text.
class AnthropicHttpException(
  val status: Int,
  val requestId: String?,
  val body: String,
) : Exception(...)
```

`Opened` exists so failures after the response is accepted (mid-stream I/O,
`error` events before `message_start`) can still carry the provider's request id
— the port requires faithful error provenance whenever the provider supplied an
id (chat `SPEC.md`, Failure classification).

`KtorAnthropicStreamTransport` is the real implementation:

```kotlin
class KtorAnthropicStreamTransport(
  private val client: HttpClient,        // closed by the owning provider, not here
  private val config: AnthropicConfig,
) : AnthropicStreamTransport
```

It POSTs `{baseUrl}/v1/messages` via a streaming request (`preparePost` +
`execute`), reads non-2xx bodies into `AnthropicHttpException`, and parses 2xx
byte channels into frames. The transport is non-blocking (CIO engine), so the
flow is safe to collect from any dispatcher with no `flowOn` shift — the
execution-context obligation of the stream contract is met by construction.

### Adapter — `AnthropicChatProvider`

`AnthropicChatProvider` implements `ChatProvider` over the seam and owns the
backing `HttpClient`'s lifecycle, mirroring `SesEmailProvider`'s
constructor-injected seam + `AutoCloseable` resource.

```kotlin
class AnthropicChatProvider(
  private val transport: AnthropicStreamTransport,
  // Backing HttpClient; closed by close().
  private val resource: AutoCloseable,
) : ChatProvider,
  AutoCloseable {
  override val id: String                // constant PROVIDER_ID = "anthropic"
  override fun stream(request: ChatRequest): Flow<ChatEvent>
  override fun close()
}
```

`id` is the wire identity written verbatim to `convo_requests.provider` —
already insertable: the applied CHECK (migration `0006`, widened by `0009`) is
`provider IN ('anthropic', 'log')`. No migration in this RFC.

Each collection performs exactly one transport call (cold flow). Everything the
transport emits or throws — except `CancellationException`, which is rethrown
for cooperative cancellation — is converted to `ChatEvent`s ending in exactly
one terminal; no exception escapes the flow. The flow completes at the first
terminal: transport events after `message_stop` (or a duplicate terminal frame)
are not collected.

### Request mapping — `ChatRequest` → Messages API body

The request body is a `JsonObject` assembled from `params` first, then the typed
fields written over it:

| Body field   | Source                                                                                                                 |
| ------------ | ---------------------------------------------------------------------------------------------------------------------- |
| (any)        | `request.params` entries, verbatim, when present                                                                       |
| `model`      | `request.model`                                                                                                        |
| `max_tokens` | `request.maxTokens`                                                                                                    |
| `stream`     | `true`, always                                                                                                         |
| `system`     | `request.system`; key omitted when null                                                                                |
| `messages`   | `request.messages` → `[{"role", "content"}]`, `USER`→`"user"`, `ASSISTANT`→`"assistant"`, `content` = the message text |

Typed fields overwrite colliding `params` keys: the model and ceiling the caller
pins into `convo_requests` must be what is transmitted, and `params` must not be
able to disable streaming. `params` is the vendor passthrough for everything
else (`thinking`, `output_config`, `stop_sequences`, ...). The adapter validates
nothing — an empty `messages` list goes to the wire and comes back as a provider
outcome (`Rejected`, `invalid_request_error`), per the port's no-validation
stance.

### Port extension — `ChatEvent.Raw`

`ChatEvent` gains one case: a frame whose SSE event type the adapter does not
model surfaces verbatim instead of being dropped.

```kotlin
// An SSE frame outside the modeled event set: event name + payload, verbatim.
// Never folded into the accumulated message (no folding rule can exist for an
// unknown shape) — visible to stream collectors, absent from
// Completed.rawPayload.
data class Raw(
  val event: String,
  val data: JsonElement,
) : ChatEvent
```

`data` is the frame payload parsed as JSON, or a `JsonPrimitive` of the raw text
when it does not parse (the same fallback error bodies use). The case is
provider-agnostic — any adapter's unmodeled frames take this path — and extends
the closed `ChatEvent` taxonomy with a new case; `ChatEvent.kt` is the contract.
`ping` is unaffected: a documented no-op, dropped, never `Raw`. The warning
logged alongside each `Raw` event is the signal to amend the taxonomy with the
new event's real semantics once Anthropic documents one.

`Raw` does not subsume `ContentDelta.Opaque`: the two capture different
unknowns. `Opaque` is an unrecognized _delta type_ inside a modeled
`content_block_delta` frame — it keeps its typed `index` and block correlation
(and is surfaced, not dropped, per accumulation rule 6). `Raw` is an
unrecognized _event kind_ with no `index` to correlate. The rule: unknown delta
inside a modeled,
index-bearing envelope → `Opaque`; unknown envelope → `Raw`.

### Event mapping — SSE frames → `ChatEvent`

Frames map onto the `ChatEvent` cases (`ChatEvent.kt`), honoring the
taxonomy-fidelity invariants in the module `SPEC.md`; this section pins the
field extraction and the cases those leave open. Frame `data` is parsed with the
module's `Json` into a `JsonElement`; all extraction below is by JSON path on
that element.

| SSE event             | Extraction                                                                                                                                                                                   |
| --------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `message_start`       | `MessageStart(message.id, message.model, usage(message.usage))`                                                                                                                              |
| `content_block_start` | `ContentBlockStart(index, content_block.type, block)` — `block` per the empty-open rule below                                                                                                |
| `content_block_delta` | `ContentBlockDelta(index, <delta>)`, `<delta>` by `delta.type`: `text_delta`→`Text(delta.text)`; `thinking_delta`→`Thinking(delta.thinking)`; `input_json_delta`→`ToolInput(delta.partial_json)`; any other type → `Opaque(delta object verbatim)` |
| `content_block_stop`  | `ContentBlockStop(index)`                                                                                                                                                                    |
| `message_delta`       | `MessageDelta(delta.stop_reason, usage(usage))` — usage is event-level and cumulative                                                                                                        |
| `message_stop`        | `Completed(response, rawPayload)` from the accumulated message (below)                                                                                                                       |
| `ping`                | dropped                                                                                                                                                                                      |
| `error`               | terminal per the classification rule (below)                                                                                                                                                 |
| any other event type  | `Raw(event, data)` — payload parsed as JSON, `JsonPrimitive` of the raw text when unparseable; one warning logged with the event name bracketed                                              |

`usage(obj)` maps `input_tokens` / `output_tokens` / `cache_read_input_tokens` /
`cache_creation_input_tokens` onto `TokenUsage`, each field null when absent.

**Empty-open rule for `ContentBlockStart.block`:** `block` is null iff every
`content_block` field other than `type` is an empty string, empty object, or
empty array; otherwise it is the verbatim `content_block` object. A `text` block
opening `{"type":"text","text":""}` yields null; a `tool_use` block opening with
`id`/`name` yields the verbatim object — making the `ContentBlockStart.block`
contract in `ChatEvent.kt` ("carries data beyond its type") a checkable rule.

### Raw-payload accumulation

The adapter maintains the accumulated `Message` as a raw `JsonObject`, so
`Completed.rawPayload` is identical in shape to the non-streaming response body
(the `Completed.rawPayload` contract in the module `SPEC.md` — the canonical
provider response object for `convo_responses_raw.payload`). Accumulation rules:

1. `message_start` — start from the verbatim `message` object (its `content`
   arrives empty).
2. `content_block_start` — `content[index]` = the verbatim `content_block`.
3. `text_delta` / `thinking_delta` — append to `content[index].text` /
   `.thinking`.
4. `input_json_delta` — buffer `partial_json` per index; at `content_block_stop`
   parse the concatenation (empty buffer → `{}`) into `content[index].input`,
   matching the non-streaming body where `input` is an object, not a string.
5. `signature_delta` — append to `content[index].signature`. Accumulation and
   the event taxonomy are independent fidelity channels: this delta surfaces as
   `ContentDelta.Opaque` on the stream _and_ folds into the final message.
6. Other unrecognized delta types — surfaced as `ContentDelta.Opaque` but not
   folded (no folding rule can exist for an unknown shape); one warning logged
   with the delta type bracketed. `Raw` frames are likewise surfaced, never
   folded.
7. `message_delta` — merge `delta` fields (`stop_reason`, `stop_sequence`) into
   the top level; merge `usage` fields over `message.usage` (cumulative values
   overwrite).
8. `message_stop` — the object is final.
9. A frame the accumulator cannot apply (a delta or stop for an index with no
   open block, a delta before `message_start`) ends the stream as
   `TransientFailure` (reason format pinned in Error classification) — the
   no-exception invariant made concrete for out-of-order streams.

`ChatResponse` is then a mechanical projection of the accumulated message:
`content` = `message.content` (verbatim array), `modelResolved` =
`message.model`, `stopReason` = `message.stop_reason`, `usage` =
`usage(message.usage)`, `providerRequestId` = `message.id`.

### Error classification

Every failure becomes the terminal event; the split is decided per signal, in
two levels:

**Level 1 — by Anthropic `error.type`** (present in both HTTP error bodies and
mid-stream `error` events):

| `error.type`                                                                                                | Terminal           |
| ----------------------------------------------------------------------------------------------------------- | ------------------ |
| `invalid_request_error`, `authentication_error`, `permission_error`, `not_found_error`, `request_too_large` | `Rejected`         |
| `rate_limit_error`, `api_error`, `overloaded_error`                                                         | `TransientFailure` |

**Level 2 — by HTTP status**, when the body carries no recognized type
(unparseable body, novel type):

| Status              | Terminal           |
| ------------------- | ------------------ |
| 408, 429, any ≥ 500 | `TransientFailure` |
| any other 4xx       | `Rejected`         |

**Floor — the port's rule** (chat `SPEC.md`, Failure classification): anything
the adapter cannot place with either level maps to `TransientFailure`, never
`Rejected`. Concretely:

- Mid-stream `error` event with an unrecognized type (no status exists) →
  `TransientFailure`.
- I/O failures — connect timeout, socket (idle) timeout, connection reset,
  malformed frame JSON → `TransientFailure`.
- 2xx stream that ends without a terminal frame (connection dropped mid-message)
  → `TransientFailure` (truncated stream).

Failure terminals carry faithful provenance: `providerRequestId` is `message.id`
once `message_start` was seen, else `Opened.requestId` /
`AnthropicHttpException.requestId`, else null (failure preceded any provider
response). `rawPayload` is the parsed error body when it is valid JSON, a
`JsonPrimitive` of the raw text when a non-empty body does not parse, and null
when there is no body. `reason` is bracketed-value style:
`"http [<status>] [<error.type>] <provider message>"` for HTTP failures,
`"stream error [<error.type>] <provider message>"` for `error` events,
`"transport [<exception class>] <message>"` for I/O,
`"truncated stream after [<n>] events"` for a dropped stream, and
`"protocol violation [<event>] <detail>"` for frames the accumulator cannot
apply (accumulation rule 9).

`CancellationException` is rethrown, never classified: collector cancellation
cancels the in-flight Ktor call cooperatively.

### Configuration

`chat.conf` gains an `anthropic` block; all values `${?ENV}`-overridable per the
module-prefixed env naming `email.conf` set:

```hocon
chat {
  provider = "log"                       # "log" | "anthropic"
  provider = ${?CHAT_PROVIDER}

  anthropic {
    # Required to construct the adapter; no packaged default.
    apiKey = ${?CHAT_ANTHROPIC_API_KEY}

    baseUrl = "https://api.anthropic.com"
    baseUrl = ${?CHAT_ANTHROPIC_BASE_URL}

    connectTimeoutMs = 10000
    connectTimeoutMs = ${?CHAT_ANTHROPIC_CONNECT_TIMEOUT_MS}

    # Idle bound between stream bytes, not a whole-call ceiling — periodic
    # `ping` events keep a healthy stream under it.
    socketTimeoutMs = 60000
    socketTimeoutMs = ${?CHAT_ANTHROPIC_SOCKET_TIMEOUT_MS}
  }
}
```

There is no whole-request timeout: a stream's duration is bounded by
`maxTokens`, and a stall trips the socket timeout.

`AnthropicConfig` is the typed reader, mirroring `SesConfig`:

```kotlin
class AnthropicConfig private constructor(
  val apiKey: String?,          // hasPath → nullable; absent when the env var is unset
  val baseUrl: String,          // fail-fast getString (packaged default)
  val connectTimeoutMs: Long,   // fail-fast getLong (packaged default)
  val socketTimeoutMs: Long,    // fail-fast getLong (packaged default)
) {
  companion object {
    fun from(config: Config): Result<AnthropicConfig>
  }
}
```

`ChatConfig` gains the nested reader, exactly as `EmailConfig` gained `ses`:

```kotlin
class ChatConfig private constructor(
  val provider: String,
  val anthropic: AnthropicConfig,
)
```

No value validation in either reader (URL shape, key shape): a bad base URL or
key surfaces at the first call as a classified terminal, consistent with the
`SesConfig` stance. The missing-key case is the factory's, not the reader's:
`apiKey` stays nullable so `"log"` deployments need no Anthropic secrets.

### Factory

`ChatProviderFactory.fromConfig` gains the `"anthropic"` branch:

- `config.anthropic.apiKey == null` → `Result.failure(IllegalArgumentException)`
  with the missing path and env var bracketed:
  `"chat.provider [anthropic] requires [chat.anthropic.apiKey] (CHAT_ANTHROPIC_API_KEY)"`.
  Failing at construction is the single misconfiguration gate — without it, a
  keyless adapter would emit `Rejected` on every turn at runtime.
- Otherwise: construct `HttpClient(CIO)` with `HttpTimeout` from the config
  (connect + socket, no request timeout), wrap it in
  `KtorAnthropicStreamTransport`, and return
  `AnthropicChatProvider(transport, client)`. The client's lifetime transfers to
  the provider (closed via its `close()`), mirroring `EmailProviderFactory`'s
  SES branch; a future closer checks `is AutoCloseable`, the port stays minimal.

The `"log"` branch and the unknown-selector failure are unchanged.

### Dependencies

`gradle/libs.versions.toml` gains three Ktor client libraries on the existing
`ktor` version ref (3.4.2; bumping the repo-wide pin shared with `rest-server`
is out of scope):

- `ktor-client-core` — `HttpClient`, streaming request plumbing, `HttpTimeout`
- `ktor-client-cio` — non-blocking engine, no new transitive HTTP stack
- `ktor-client-mock` — `MockEngine`, test scope only

`chat/build.gradle.kts` adds `implementation(libs.ktor.client.core)`,
`implementation(libs.ktor.client.cio)`, and
`testImplementation(libs.ktor.client.mock)`. Everything else is already on the
classpath (`kotlinx-serialization-json` via `common`'s `api`, coroutines,
slf4j). No serialization plugin: the adapter declares no `@Serializable` types;
it builds and parses `JsonElement` trees. No dependency on `db`; no migration;
`settings.gradle.kts` and `bin/test` already know the `chat` module.

### Module wiring

Unchanged, per the `chat` module `SPEC.md` (Infrastructure & Environment): no
production `main()` constructs a provider, no module depends on `chat`, and only
this module's tests load `chat.conf`. Production wiring — including where the
API key lives in deployed config — belongs to the coaching service.

## Tests

All tests are pure JVM, no network: the provider is tested against a fake
`AnthropicStreamTransport` replaying recorded event sequences, the transport
against Ktor `MockEngine` serving recorded SSE bytes. `AnthropicTestFixtures.kt`
holds the canonical recorded streams once — both the SSE text (transport tests)
and the equivalent `AnthropicTransportEvent` list (provider tests) derive from
it, so the two layers agree on the wire shape.

`chat/src/test/kotlin/ed/unicoach/chat/AnthropicConfigTest.kt`:

1. `from reads the packaged defaults` — `AppConfig.load("chat.conf")` →
   `apiKey == null`, `baseUrl == "https://api.anthropic.com"`,
   `connectTimeoutMs == 10000`, `socketTimeoutMs == 60000`.
2. `from reads values verbatim` — a parsed config with all four keys set
   surfaces each verbatim, `apiKey` non-null.
3. `from fails when the block is absent` — empty config → `Result.failure` whose
   cause is a `ConfigException`.

`chat/src/test/kotlin/ed/unicoach/chat/KtorAnthropicStreamTransportTest.kt`
(`MockEngine`):

1. `a 200 stream parses into Opened plus frames` — recorded SSE body with a
   `request-id` header → first element `Opened` carrying the header value, then
   one `Frame` per SSE frame with `event` and `data` verbatim; blank lines and
   `:` comment lines produce nothing; a frame with two `data:` lines yields one
   `Frame` with the lines joined by `\n`.
2. `the request carries the wire contract` — captured `MockEngine` request: POST
   to `{baseUrl}/v1/messages`; headers `x-api-key`,
   `anthropic-version: 2023-06-01`, `accept: text/event-stream`,
   `content-type: application/json`; body equal to the passed `JsonObject`.
3. `a non-2xx response throws AnthropicHttpException` — 429 with a JSON error
   body and `request-id` header → exception carries status 429, the header id,
   and the body verbatim; the flow emits nothing.
4. `the flow is cold` — collecting twice issues two requests.

`chat/src/test/kotlin/ed/unicoach/chat/AnthropicChatProviderTest.kt` (fake
transport):

1. `id is the wire identity anthropic`.
2. `the canonical text stream maps 1:1` — replayed sequence (`Opened`,
   `message_start`, `content_block_start` text, two `text_delta`s, `ping`,
   `content_block_stop`, `message_delta`, `message_stop`) → exactly the event
   list from the Event mapping table; `ping` dropped; exactly one terminal,
   last, `Completed`; the text block's `ContentBlockStart.block` is null
   (empty-open rule).
3. `rawPayload equals the non-streaming body` — `Completed.rawPayload` equals
   the hand-written expected `Message` object: `id`, `model`, single text block
   with the concatenated deltas, merged `stop_reason`/`stop_sequence`,
   cumulative `usage`.
4. `the response projects the accumulated message` — `content`, `modelResolved`,
   `stopReason`, `usage`, `providerRequestId` each match the accumulated
   object's fields.
5. `tool_use accumulates parsed input` — a stream with a `tool_use` block:
   `ContentBlockStart.block` is the verbatim opening object (id/name);
   `input_json_delta`s surface as `ToolInput`; the accumulated block's `input`
   is the parsed object; a `tool_use` block whose buffer is empty accumulates
   `input == {}`.
6. `thinking and signature deltas` — `thinking_delta` → `Thinking`;
   `signature_delta` → `ContentDelta.Opaque` on the stream and folded into the
   accumulated block's `signature`.
7. `an unknown delta type is Opaque and not folded` — surfaced verbatim as
   `Opaque`; the accumulated block is unchanged by it; exactly one warning is
   logged carrying the bracketed delta type.
8. `an unknown SSE event type surfaces as Raw` — `Raw` carries the event name
   and parsed payload verbatim; unparseable frame data yields a `JsonPrimitive`
   of the raw text; the accumulated message is unchanged; the stream still ends
   in `Completed`; exactly one warning is logged carrying the bracketed event
   name.
9. `the request body maps ChatRequest` — captured transport body: `model`,
   `max_tokens`, `stream == true`, `system` present/omitted by nullability,
   `messages` with lowercase roles; `params` entries pass through; a `params`
   collision on `model`/`max_tokens`/`stream` loses to the typed fields.
10. `an error event after message_start classifies with the message id` —
    `overloaded_error` → `TransientFailure`, `providerRequestId` = `message.id`,
    `rawPayload` = the verbatim error body.
11. `an error event before message_start carries the Opened request id` —
    `Rejected` on `invalid_request_error`, `providerRequestId` =
    `Opened.requestId`.
12. `an unrecognized error type is transient` — novel `error.type` →
    `TransientFailure` (the unrecognized-error floor).
13. `permanent HTTP statuses reject` — `AnthropicHttpException` for 400, 401,
    403, 404, 413 with typed JSON bodies → `Rejected`; `reason` carries the
    bracketed status and type; `rawPayload` is the parsed body;
    `providerRequestId` is the exception's id.
14. `retriable HTTP statuses are transient` — 408, 429, 500, 529 →
    `TransientFailure`.
15. `an unparseable 4xx body falls back to the status bucket` — 422 with a
    non-JSON body → `Rejected`, `rawPayload` a `JsonPrimitive` of the raw text.
16. `transport I/O failures are transient with null provenance` — an
    `IOException` before any `Opened` → `TransientFailure`,
    `providerRequestId == null`, `rawPayload == null`.
17. `a truncated stream is transient` — replay ends after deltas with no
    terminal frame → `TransientFailure`; nothing follows the terminal.
18. `an out-of-order frame is a protocol violation` — a `content_block_delta`
    for an index with no open block → `TransientFailure` whose `reason` starts
    with `protocol violation`; no exception escapes.
19. `frames after message_stop are not collected` — replay continues past
    `message_stop` → the flow ends on `Completed`; nothing follows it.
20. `every failure path emits exactly one terminal` — cases 10–18 each end the
    flow on their terminal with no trailing events.
21. `collector cancellation cancels the transport` — cancelling collection
    mid-stream cancels the fake transport's flow (observed via `onCompletion`);
    no terminal is emitted.
22. `the flow is cold` — two collections invoke the transport twice.
23. `close closes the injected resource` — recording fake `AutoCloseable`.

`chat/src/test/kotlin/ed/unicoach/chat/ChatProviderFactoryTest.kt` (extended):

1. `anthropic with an api key selects AnthropicChatProvider` — success, instance
   `is AnthropicChatProvider`, `id == "anthropic"`; the test closes the returned
   provider (releasing the real `HttpClient`, constructed offline).
2. `anthropic without an api key fails at construction` — `Result.failure` of
   `IllegalArgumentException` whose message contains `[chat.anthropic.apiKey]`.
3. Existing cases (`log` selected, `smtp` rejected) re-run unchanged; the
   `chatConfig` helper's `parseString` gains the `chat.anthropic` keys so the
   extended `ChatConfig.from` resolves.

`chat/src/test/kotlin/ed/unicoach/chat/ChatConfigTest.kt` (updated):

1. `from reads the packaged default` — unchanged (classpath merge already
   resolves the new block's defaults).
2. `from reads the provider verbatim` — the bare `parseString` gains the
   `chat.anthropic` defaults so the nested fail-fast reads succeed.
3. `from fails when the key is absent` — unchanged.

Existing `LogOnlyChatProviderTest` and `ChatProviderTest` run unchanged as the
regression on the untouched stub and extension.

## Implementation Plan

All commands run inside the Nix dev shell (`nix develop -c ...`). After each
test step, confirm the named test classes are present and green in the JUnit XML
under `chat/build/test-results/test/` — a bare `BUILD SUCCESSFUL` can mask a
no-tests-run state.

1. **Dependencies.** Add `ktor-client-core`, `ktor-client-cio`, and
   `ktor-client-mock` to `gradle/libs.versions.toml` (version ref `ktor`); add
   the two `implementation` and one `testImplementation` entries to
   `chat/build.gradle.kts`.
   - Verify:
     `nix develop -c ./gradlew :chat:dependencies --configuration runtimeClasspath`
     lists `ktor-client-core` and `ktor-client-cio`;
     `nix develop -c ./gradlew :chat:compileKotlin`.
2. **Configuration.** Extend `chat/src/main/resources/chat.conf` with the
   `anthropic` block; add `AnthropicConfig.kt`; extend `ChatConfig.kt`; add
   `AnthropicConfigTest`; update `ChatConfigTest` and
   `ChatProviderFactoryTest`'s `chatConfig` helper.
   - Verify: `nix develop -c bin/test chat`; `AnthropicConfigTest`,
     `ChatConfigTest`, and `ChatProviderFactoryTest` green in the JUnit XML.
3. **Transport.** Add `AnthropicTransportEvent.kt`, `AnthropicHttpException.kt`,
   `AnthropicStreamTransport.kt`, `KtorAnthropicStreamTransport.kt`, the shared
   `AnthropicTestFixtures.kt`, and `KtorAnthropicStreamTransportTest`.
   - Verify: `nix develop -c bin/test chat`; `KtorAnthropicStreamTransportTest`
     green in the JUnit XML.
4. **Adapter.** Add the `Raw` case to `ChatEvent.kt`; add
   `AnthropicChatProvider.kt` (request mapping, event mapping, accumulation,
   error classification, lifecycle) and `AnthropicChatProviderTest`.
   - Verify: `nix develop -c bin/test chat`; `AnthropicChatProviderTest` green
     in the JUnit XML.
5. **Factory.** Add the `"anthropic"` branch to `ChatProviderFactory.kt`; add
   the two new `ChatProviderFactoryTest` cases.
   - Verify: `nix develop -c bin/test chat`; `ChatProviderFactoryTest` green in
     the JUnit XML.
6. **Full regression.**
   - Verify: `nix develop -c bin/test --force` (every module green; `--force`
     defeats the all-cache no-op so the suite demonstrably ran).

## Files Modified

New:

- `chat/src/main/kotlin/ed/unicoach/chat/AnthropicConfig.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/AnthropicStreamTransport.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/AnthropicTransportEvent.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/AnthropicHttpException.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/KtorAnthropicStreamTransport.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/AnthropicChatProvider.kt`
- `chat/src/test/kotlin/ed/unicoach/chat/AnthropicConfigTest.kt`
- `chat/src/test/kotlin/ed/unicoach/chat/AnthropicTestFixtures.kt`
- `chat/src/test/kotlin/ed/unicoach/chat/KtorAnthropicStreamTransportTest.kt`
- `chat/src/test/kotlin/ed/unicoach/chat/AnthropicChatProviderTest.kt`

Updated:

- `gradle/libs.versions.toml`
- `chat/build.gradle.kts`
- `chat/src/main/resources/chat.conf`
- `chat/src/main/kotlin/ed/unicoach/chat/ChatConfig.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/ChatEvent.kt`
- `chat/src/main/kotlin/ed/unicoach/chat/ChatProviderFactory.kt`
- `chat/src/test/kotlin/ed/unicoach/chat/ChatConfigTest.kt`
- `chat/src/test/kotlin/ed/unicoach/chat/ChatProviderFactoryTest.kt`
