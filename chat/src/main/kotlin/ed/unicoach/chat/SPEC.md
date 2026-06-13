# SPEC: `chat/src/main/kotlin/ed/unicoach/chat`

## I. Overview

The `chat` package is the provider-agnostic port for LLM chat completions. It
owns the request value types ([ChatRequest](./ChatRequest.kt),
[ChatMessage](./ChatMessage.kt), [ChatRole](./ChatRole.kt)), the streaming
event taxonomy ([ChatEvent](./ChatEvent.kt), [ContentDelta](./ContentDelta.kt))
whose terminal events carry the outcome of one provider call, the parsed reply
types ([ChatResponse](./ChatResponse.kt), [TokenUsage](./TokenUsage.kt)), the
[ChatProvider](./ChatProvider.kt) port with its `chat` accumulation extension,
two adapters — the no-network stub [LogOnlyChatProvider](./LogOnlyChatProvider.kt)
(`id = "log"`) and the transmitting [AnthropicChatProvider](./AnthropicChatProvider.kt)
(`id = "anthropic"`) that streams the Anthropic Messages API over its replayable
[AnthropicStreamTransport](./AnthropicStreamTransport.kt) seam — the configuration
surface ([ChatConfig](./ChatConfig.kt), [AnthropicConfig](./AnthropicConfig.kt)),
and the [ChatProviderFactory](./ChatProviderFactory.kt) selector. The package
performs no persistence and no validation of caller input; it defines the
stream contract every adapter is bound by.

---

## II. Invariants

### Stream contract (binding on every adapter)

- A `stream` flow MUST emit zero or more non-terminal `ChatEvent`s, then
  **exactly one** `ChatEvent.Terminal`, then complete normally. Nothing follows
  a terminal event.
- Expected provider outcomes are NEVER thrown; they are returned as `Rejected`
  / `TransientFailure` terminal events. An exception escaping the flow (other
  than `CancellationException`) is a defect, not an API channel; the caller
  treats it as transient.
- The flow MUST be cold: each collection performs one fresh provider call.
  Retry semantics are therefore at-least-once with **no idempotency key**;
  deduplication is the caller's concern, not provided here.
- Collector cancellation MUST cancel the in-flight provider call (cooperative
  `Flow` cancellation); no partial response is surfaced at the port level.
- Adapters own their execution context: the flow MUST be safe to collect from
  any dispatcher. An adapter MUST guarantee this either by using a non-blocking
  engine or by shifting its blocking I/O off the collector's context — NEVER by
  delegating that obligation to the caller.
- Events MUST be emitted in the provider's order; the block `index` field is
  the sole correlator of `ContentBlockStart`/`ContentBlockDelta`/
  `ContentBlockStop` across interleaved blocks.

### Failure classification

- An error an adapter does not recognize MUST map to `TransientFailure`, NEVER
  `Rejected` — a bounded retry is safer than silently dropping a turn.
- `Rejected` and `TransientFailure` MUST carry the provider's request id and
  verbatim error body whenever the provider supplied them, and `null` only
  when the failure precedes any provider response (e.g. a connect timeout) —
  faithful error provenance, never a bare reason string when more exists.

### Taxonomy fidelity (deliberate openness)

- `ContentBlockStart.blockType` MUST remain a verbatim provider string, NEVER
  an enum — an enum would force a lossy "other" bucket every time a vendor
  adds a block type.
- `ContentDelta` is a closed hierarchy whose `Opaque` case MUST carry
  unrecognized provider delta payloads verbatim; adapters MUST use it rather
  than dropping deltas they do not model.
- An SSE frame whose `event` name is outside the modeled set MUST surface as
  `ChatEvent.Raw` (verbatim event name + parsed payload, falling back to a
  `JsonPrimitive` when the data is unparseable), MUST NOT be folded into
  `Completed.rawPayload`, and is distinct from `ContentDelta.Opaque`: `Raw` is an
  unrecognized event *envelope* with no `index` to correlate, `Opaque` an
  unrecognized delta *type* inside a modeled `content_block_delta`. A `ping`
  frame is a documented drop, NEVER surfaced as `Raw`.
- `ChatResponse.stopReason` MUST be the provider's stop reason verbatim, never
  a normalized enum.
- `Completed.rawPayload` MUST be the canonical provider response object — for
  a streaming call, the accumulated final message, identical in shape to the
  provider's non-streaming response — NEVER the event/SSE array. Chunking
  boundaries have no replay value.
- `ChatResponse` MUST NOT carry latency: the caller measures wall-clock around
  the port call.

### Port input stance

- The port performs NO input validation: `model`, `system`, and `messages`
  pass through verbatim, and an empty `messages` list is passed through for
  the provider to answer as a provider outcome — never rejected at the port.
- `ChatRequest.params` MUST stay an opaque vendor JSON object; typed
  `ChatRequest` fields are reserved for parameters every vendor shares.
- `ChatRole` MUST remain `USER`/`ASSISTANT` only; the system prompt is
  `ChatRequest.system`, NEVER a message role.

### Accumulation extension

- `ChatProvider.chat` MUST return the stream's terminal event. A stream that
  violates the stream contract — empty, or ending on a non-terminal — MUST
  make `chat` throw rather than return a terminal (a contract violation is a
  defect, not an outcome).

### Stub adapter (`LogOnlyChatProvider`)

- `id` MUST be the constant `PROVIDER_ID` (`"log"`) — the wire identity
  recorded verbatim by callers, not the class name.
- `stream` MUST transmit nothing and log exactly one line (provider id, model,
  message count, all bracketed).
- `stream` MUST emit, in order: `MessageStart` → `ContentBlockStart` (index 0,
  block type `"text"`, no initial block object) → one or more
  `ContentBlockDelta`s of `ContentDelta.Text` at index 0 → `ContentBlockStop`
  (index 0) → `MessageDelta` (stop reason `"end_turn"`, full usage) →
  `Completed`. It NEVER emits `Rejected` or `TransientFailure`.
- The reply text MUST be `ECHO_PREFIX` + the text of the last message (the
  empty string when `messages` is empty), so downstream tests can assert on
  content.
- Delta chunks MUST each be at most `DELTA_CHUNK_SIZE` characters, so replies
  longer than one chunk exercise multi-delta accumulation; concatenating all
  delta texts MUST equal the text inside `Completed.response.content`.
- Every field of every event MUST be deterministic given the request, except
  `providerRequestId` — a fresh UUID per collection (one collection = one
  provider call = one id).
- `maxTokens` is ignored: the stub NEVER truncates the echo. Synthetic "token"
  counts are character counts — input = total characters across `system` and
  all message texts, output = reply length; cache fields are `null`.
- `rawPayload` MUST be the stub's own wire shape — a JSON object with
  `provider` (`"log"`), `id`, `model`, `content`, `stop_reason`, and `usage`
  fields — NOT an imitation of any vendor's format.

### Transmitting adapter (`AnthropicChatProvider`)

- `id` MUST be the constant `PROVIDER_ID` (`"anthropic"`) — the wire identity,
  never the class name.
- Each collection performs exactly one transport call (cold flow); the flow
  completes at the first terminal, so transport frames after `message_stop` are
  never collected.
- Request mapping MUST write `ChatRequest.params` first, then overwrite with the
  typed fields; `stream` MUST always be `true`, and `params` MUST NOT be able to
  disable streaming or repin `model`/`max_tokens`. `ChatRole.USER`/`ASSISTANT`
  map to `"user"`/`"assistant"`.
- Failure classification MUST decide the permanent/retriable split by recognized
  Anthropic `error.type` first; absent a recognized type it MUST fall back to the
  HTTP status bucket — `408`, `429`, and `>= 500` are retriable, other `4xx`
  permanent. A mid-stream `error` frame has no HTTP status, so an unrecognized
  type there MUST be retriable. Every unmodeled failure (I/O, a truncated stream,
  a protocol violation, an unrecognized `error.type`) MUST be `TransientFailure`,
  NEVER `Rejected`.
- `Completed.rawPayload` MUST be the final `Message` accumulated as a JSON tree
  identical in shape to the non-streaming response body. `text`/`thinking`/
  `signature` deltas fold into the block; `input_json_delta` buffers per block
  index and parses at `content_block_stop`. A `signature_delta` MUST both fold
  into the block AND surface verbatim as `ContentDelta.Opaque` — accumulation and
  the event taxonomy are independent fidelity channels. A frame the accumulator
  cannot apply (out-of-order, no open block, no `message_start`) MUST terminate
  the stream as `TransientFailure` with a `protocol violation [<event>]` reason,
  NEVER throw out of the flow.

### Transport seam (`AnthropicStreamTransport`)

- The seam isolates HTTP from `ChatEvent` mapping: one streaming Messages API
  call per collection, emitting `AnthropicTransportEvent.Opened` exactly once
  (response accepted) before any `Frame`, then one `Frame` per SSE frame.
- `Opened` MUST carry the `request-id` response header so a failure after
  acceptance still carries the provider's request id.
- A non-2xx response MUST raise `AnthropicHttpException` with the body read in
  full first — the error body is load-bearing for `Rejected.rawPayload` and the
  `error.type` classification. Connect/read/parse failures MUST propagate as
  their underlying exceptions. The provider, NEVER the seam, converts these into
  terminals.

### Configuration (`ChatConfig`)

- `ChatConfig` MUST be constructed only via `from(config)`, which reads
  `chat.provider` verbatim. `from` MUST NOT validate the value — the factory
  is the single place an unknown selector is rejected.
- `from` MUST fail when the key is absent or unreadable, returning
  `Result.failure` carrying the underlying typesafe `ConfigException`
  unmapped.
- `ChatConfig` MUST delegate the `chat.anthropic` block to `AnthropicConfig.from`,
  surfacing its failure unmapped.
- `AnthropicConfig.apiKey` MUST be read via `hasPath` → nullable (absent when the
  `CHAT_ANTHROPIC_API_KEY` override is unset), so `"log"` deployments need no
  Anthropic secret. `baseUrl`, `connectTimeoutMs`, and `socketTimeoutMs` MUST be
  fail-fast reads against their packaged defaults. `AnthropicConfig` MUST NOT
  validate values (URL or key shape) — a bad value surfaces at the first call as
  a classified terminal.

### Provider selection (`ChatProviderFactory`)

- `fromConfig` MUST map the selector to exactly one adapter: `"log"` →
  `LogOnlyChatProvider`, `"anthropic"` → `AnthropicChatProvider`. Any other value
  MUST yield `Result.failure(IllegalArgumentException)` with the offending value
  bracketed in the message — NEVER a silent fallback default.
- The `"anthropic"` branch MUST reject a null `chat.anthropic.apiKey` with
  `Result.failure(IllegalArgumentException)` — the factory's single
  misconfiguration gate, since a keyless adapter would emit `Rejected` on every
  turn. Otherwise it MUST construct the backing `HttpClient` (connect + socket
  timeouts, no whole-request timeout) and transfer its lifetime to the provider,
  closed via the provider's `close()`.

---

## III. Behavioral Contracts

### `ChatProvider.stream(request: ChatRequest): Flow<ChatEvent>`

See [ChatProvider.kt](./ChatProvider.kt), [ChatEvent.kt](./ChatEvent.kt).

- **Side Effects**: Implementation-defined — one provider call per collection.
  The port itself prescribes none beyond the event stream.
- **Error Handling**: Expected provider outcomes arrive as the terminal event
  (`Completed` / `Rejected` / `TransientFailure`), never as exceptions.
  `Rejected` is permanent (no retry helps); `TransientFailure` is retriable.
- **Idempotency**: No. The flow is cold; re-collection is a fresh
  transmission (at-least-once).
- **Execution Context**: Safe to collect from any dispatcher; the adapter owns
  this — via a non-blocking engine or by shifting its blocking I/O — never the
  caller.

### `ChatProvider.chat(request: ChatRequest): ChatEvent.Terminal`

See [ChatProvider.kt](./ChatProvider.kt).

- **Side Effects**: One full collection of `stream(request)`.
- **Behavior**: Returns the last event — the terminal, by the stream contract.
  Non-streaming callers use this; streaming callers collect `stream` directly.
- **Error Handling**: Throws `IllegalStateException` on a contract-violating
  stream (empty, or ended on a non-terminal). Provider failures are returned
  as `Rejected`/`TransientFailure` values, not thrown.
- **Idempotency**: Same as `stream` — each call is a fresh provider call.

### `LogOnlyChatProvider`

See [LogOnlyChatProvider.kt](./LogOnlyChatProvider.kt).

- **Side Effects**: Emits one log line; transmits nothing.
- **Behavior**: `id == "log"`. Echoes the last message as a well-formed
  single-text-block event sequence ending in `Completed` (stop reason
  `"end_turn"`), chunked at `DELTA_CHUNK_SIZE`, with character-count usage.
- **Error Handling**: Never emits a failure terminal; never throws for any
  request shape (an empty `messages` list yields the bare-prefix echo).
- **Idempotency**: Each collection yields a fresh `providerRequestId`; all
  other event fields repeat deterministically. No external effect to repeat.

### `AnthropicChatProvider`

See [AnthropicChatProvider.kt](./AnthropicChatProvider.kt),
[AnthropicStreamTransport.kt](./AnthropicStreamTransport.kt).

- **Side Effects**: One streaming `POST {baseUrl}/v1/messages` per collection,
  via the injected transport. `close()` closes the owned backing `HttpClient`.
- **Behavior**: `id == "anthropic"`. Maps the request to the Messages API body,
  collects the transport's `Opened`/`Frame` events onto the `ChatEvent` taxonomy,
  and accumulates the final `Message` for `Completed.rawPayload`. Completes at
  the first terminal.
- **Error Handling**: Every transport outcome and every thrown exception except
  `CancellationException` becomes exactly one terminal
  (`Completed`/`Rejected`/`TransientFailure`); no exception escapes the flow.
  Permanent vs. retriable follows the Anthropic failure-classification invariant
  (§II). A transport ending with no `message_stop` yields `TransientFailure`
  (`truncated stream`).
- **Idempotency**: No. The flow is cold; each collection is a fresh transmission
  (at-least-once).

### `AnthropicStreamTransport.stream(body: JsonObject): Flow<AnthropicTransportEvent>`

See [AnthropicStreamTransport.kt](./AnthropicStreamTransport.kt),
[KtorAnthropicStreamTransport.kt](./KtorAnthropicStreamTransport.kt).

- **Side Effects**: One streaming Messages API call per collection. The backing
  `HttpClient` is owned and closed by the provider, not the seam.
- **Behavior**: Emits `Opened(requestId)` once on a 2xx response, then one
  `Frame(event, data)` per SSE frame (verbatim event name + unparsed data),
  completing when the stream closes.
- **Error Handling**: Non-2xx raises `AnthropicHttpException(status, requestId,
  body)` with the body read in full first. Connect/read/parse failures propagate
  as their underlying exceptions. The seam never produces a `ChatEvent`.
- **Idempotency**: No. Each collection is a fresh call.

### `AnthropicConfig.from(config: Config): Result<AnthropicConfig>`

See [AnthropicConfig.kt](./AnthropicConfig.kt).

- **Side Effects**: None.
- **Behavior**: Reads `chat.anthropic.apiKey` via `hasPath` → nullable; `baseUrl`,
  `connectTimeoutMs`, `socketTimeoutMs` fail-fast. No value validation.
- **Error Handling**: `Result.failure` carrying the typesafe `ConfigException`
  when a fail-fast key is absent or unreadable.
- **Idempotency**: Yes.

### `ChatConfig.from(config: Config): Result<ChatConfig>`

See [ChatConfig.kt](./ChatConfig.kt).

- **Side Effects**: None.
- **Behavior**: Reads `chat.provider` verbatim into `ChatConfig.provider`. No
  value validation.
- **Error Handling**: `Result.failure` carrying the typesafe `ConfigException`
  when the key is absent or unreadable.
- **Idempotency**: Yes.

### `ChatProviderFactory.fromConfig(config: ChatConfig): Result<ChatProvider>`

See [ChatProviderFactory.kt](./ChatProviderFactory.kt).

- **Side Effects**: Constructs the selected adapter; the `"anthropic"` branch
  also constructs the backing `HttpClient(CIO)`. Production wiring is the
  consumer's responsibility; nothing in this module calls the factory outside
  tests.
- **Behavior**: `"log"` → `LogOnlyChatProvider`; `"anthropic"` →
  `AnthropicChatProvider` over a `KtorAnthropicStreamTransport`.
- **Error Handling**: `Result.failure(IllegalArgumentException)` for an unknown
  selector (offending value bracketed) and for `"anthropic"` with a null
  `chat.anthropic.apiKey`.
- **Idempotency**: Each call yields a new provider instance.

---

## IV. Infrastructure & Environment

- **Module**: `chat` Gradle leaf module (`chat/build.gradle.kts`). Plugins:
  `kotlin.jvm`, `ktlint` only — no serialization plugin: the module declares
  no `@Serializable` types and consumes `JsonElement`/`JsonObject` via
  `common`'s `api`-exposed `kotlinx-serialization-json`.
- **Dependencies**: `common` (test config loading + the `api`-exposed
  typesafe-config and kotlinx-serialization-json), `kotlinx.coroutines.core`
  (`Flow`), `slf4j.api` (stub logging), `ktor-client-core` + `ktor-client-cio`
  (the Anthropic transport's non-blocking HTTP engine). NO dependency on `db`:
  this module owns no persistence. Test scope: `kotlin-test-junit5`,
  `kotlinx-coroutines-test`, `ktor-client-mock` (replayed transport).
- **Package**: `ed.unicoach.chat`.
- **Configuration**: `chat/src/main/resources/chat.conf` defines `chat.provider`
  (packaged default `"log"`, override `CHAT_PROVIDER`) and the `chat.anthropic`
  block: `apiKey` (no packaged default, env-only via `CHAT_ANTHROPIC_API_KEY`),
  `baseUrl` (`https://api.anthropic.com`, override `CHAT_ANTHROPIC_BASE_URL`),
  `connectTimeoutMs` (`10000`, override `CHAT_ANTHROPIC_CONNECT_TIMEOUT_MS`), and
  `socketTimeoutMs` (`60000`, override `CHAT_ANTHROPIC_SOCKET_TIMEOUT_MS`). No
  module-internal `main()` calls the factory; the `"anthropic"` branch does
  construct an `HttpClient`, but production wiring is the consumer's
  responsibility.
- **Tests**: pure JVM — no DB, no network. Run with
  `nix develop -c bin/test chat`.

---

## V. History

- [x] [RFC-43: Provider-Agnostic LLM Chat Provider](../../../../../../../rfc/43-chat-provider.md)
      — Introduced the `chat` module: the `ChatProvider` port and its binding
      stream contract (cold flow, exactly-one-terminal, outcomes-not-thrown,
      unrecognized-error → `TransientFailure`), the `ChatEvent`/`ContentDelta`
      streaming taxonomy (verbatim `blockType`, `Opaque` escape hatch) mapping
      the Anthropic Messages API streaming events losslessly while staying
      vendor-neutral, the request/response value types, the `chat`
      accumulation extension, the `LogOnlyChatProvider` stub (`id = "log"`),
      `ChatConfig`, and `ChatProviderFactory`. Companion migration `0009`
      (outside this directory) widened the DB provider allowlist to admit
      `'log'`. Production wiring to the coaching-service RFC.
- [x] [RFC-44: Anthropic Chat Provider](../../../../../../../rfc/44-anthropic-chat-provider.md)
      — Added the first transmitting adapter, `AnthropicChatProvider`, streaming
      the Anthropic Messages API over a replayable `AnthropicStreamTransport`
      seam (`KtorAnthropicStreamTransport` + `AnthropicTransportEvent` +
      `AnthropicHttpException`). Classifies failures into the permanent/retriable
      split (by `error.type`, then HTTP status), accumulates the final `Message`
      for `Completed.rawPayload`, adds the `chat.anthropic` config block
      (`AnthropicConfig`) plus the factory `"anthropic"` branch, and extends the
      port taxonomy with `ChatEvent.Raw` for unmodeled SSE event types.
