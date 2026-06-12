# RFC 41: iOS — Start a Coaching Conversation (Streaming)

## Executive Summary

This RFC adds the iOS client and UI for an authenticated student to start a
coaching conversation and watch the coach's first reply stream in
token-by-token. It implements one operation of the RFC 39 contract —
`POST /api/v1/conversations/stream` (SSE) — and the single user journey: from
the post-login home screen, compose a first message, send it, and see the user
turn plus the streamed coach turn rendered in a thread. The journey ends there:
no follow-up turn, no conversation list, no archive/delete (each a later RFC).

The app's networking and onboarding architecture from RFC 42 is the baseline:
all HTTP traffic flows through the shared `APIClient`, and the root state
machine gates `.authenticated` (HomeView) behind a successful
`StudentClient.fetchProfile()`, routing profile-less users to `.onboarding`.
This RFC extends `APIClient` with a domain-agnostic streaming entrypoint
(`session.bytes`-based) and adds `ConversationClient` as a thin endpoint binding
over it — same shape as `AuthClient`/`StudentClient` — plus SSE frame assembly,
which is conversation-specific.

Because profile gating means a user cannot reach the "Start Coaching" entry
point without a student profile, the endpoint's `409 student_profile_required`
is an abnormal edge (profile deleted server-side mid-session), not a primary
journey. It is handled by routing the app back into the onboarding flow via the
root state machine rather than rendering a dead-end error.

The backend for this endpoint does not exist; the client is built and verified
against the published contract using the existing `MockURLProtocol` (transport)
and a `MockConversationClient` (view-model) test seams.

## Detailed Design

### Components and data flow

`NewConversationView` (View, `@StateObject`) → `NewConversationViewModel`
(`@MainActor`) → `ConversationClientProtocol` → `APIClient` (transport). The
view model never touches `URLSession`; the view never calls the network. Entry
point: a button on `HomeView`, pushing `NewConversationView` onto a
`NavigationStack`.

### Data Models (`Models.swift`)

All types are `Codable`, `Sendable`, value types. Date-time fields are modeled
as `Date` and decoded with `APIClient`'s fraction-tolerant ISO-8601 strategy.

```
enum MessageRole: String, Codable, Sendable { case user, coach }

struct Message: Codable, Sendable, Identifiable, Equatable {
    let id: String        // opaque; never parsed
    let role: MessageRole
    let content: String
    let createdAt: Date
}

struct Conversation: Codable, Sendable, Identifiable, Equatable {
    let id: UUID         // contract: uuid-format string; decoded as UUID
    let name: String
    let createdAt: Date
    let updatedAt: Date
    let lastActivityAt: Date?
    let archivedAt: Date?
}

struct CreateConversationRequest: Codable, Sendable {
    let message: String
    let name: String?     // always nil this iteration; server derives the name
}
```

Domain event surfaced to the view model (terminal failure is thrown, not an
event):

```
enum ConversationStreamEvent: Sendable {
    case conversation(Conversation, userMessage: Message)
    case delta(String)
    case completed(Message)
}
```

The terminal coach message has one identity across layers: contract
`type:
"message"` → wire `MessageCompletedFrame` → domain `.completed(Message)`
→ published `coachMessage`.

Wire DTOs used only to decode each SSE frame's `data:` JSON by its `type`
discriminator, then mapped to `ConversationStreamEvent` (or, for
`type == "error"`, thrown as the carried `ErrorResponse`):

```
struct ConversationCreatedFrame: Codable { let type: String; let conversation: Conversation; let userMessage: Message }
struct MessageDeltaFrame:        Codable { let type: String; let text: String }
struct MessageCompletedFrame:    Codable { let type: String; let message: Message }
struct StreamErrorFrame:         Codable { let type: String; let error: ErrorResponse }
```

### SSE parsing (`SSEParser.swift`)

A transport-agnostic frame assembler, separated from `ConversationClient` so it
is unit-testable without a network. It consumes SSE lines and emits one frame
per blank-line boundary.

```
struct ServerSentEvent: Equatable { let event: String?; let data: String }

struct SSEFrameAssembler {
    // Feed lines one at a time; returns a completed frame on a blank-line
    // boundary, else nil. Concatenates multiple `data:` lines with "\n";
    // records the last `event:` value; ignores comment lines (leading ':');
    // normalizes a trailing CR so CRLF bodies parse identically to LF.
    mutating func consume(line: String) -> ServerSentEvent?

    // Returns the buffered frame when the body was cut off before its
    // terminating blank line (data present, no terminator); else nil.
    mutating func flushPending() -> ServerSentEvent?
}
```

Frame `data:` JSON is decoded into the wire DTOs above by `type`; the SSE
`event:` field is not load-bearing (the JSON carries its own `type`) and is used
only for assertion in tests.

The contract's `StreamEvent` discriminator also defines a `user_message` event,
but `streamConversation` (first turn) folds the persisted user message into the
`conversation` event (per the endpoint description) and does not emit
`user_message`; that event is reserved for the follow-up `messages/stream`
endpoint (a later RFC). This client therefore does not model `user_message`: a
`user_message` frame on this endpoint is off-contract and is treated as a
malformed/unknown frame (→ `SERVER_ERROR`, below).

### Streaming transport (`APIClient.swift`)

`APIClient` gains one streaming entrypoint, keeping all request construction,
transport-error mapping, and error-body decoding in the shared HTTP layer. It
stays domain-agnostic: path, body, accept, and expected status are parameters;
SSE semantics live in `ConversationClient`.

```
func stream<B: Encodable>(_ path: String, body: B, accept: String,
    expectedStatus: Int) async throws -> URLSession.AsyncBytes
```

Behavioral contract:

- Builds the request exactly as `perform` does (`POST`,
  `Content-Type:
  application/json`, encoded body) plus an `Accept: <accept>`
  header, and issues it via `streamSession.bytes(for:)`.
- Connection-phase transport failures map through the shared vocabulary
  (`TIMEOUT` / `NETWORK_ERROR`, below); a non-`HTTPURLResponse` throws
  `ErrorResponse(code: "UNKNOWN")` — identical to `perform`.
- Status == `expectedStatus`: returns the live byte stream after headers arrive.
  Status mismatch: drains the (buffered) body and throws the existing private
  `decodeError(data:)` result — the decoded `ErrorResponse`, or `SERVER_ERROR`
  when the body is unparseable. Per-status domain semantics remain the caller's
  job.

Supporting changes inside `APIClient`:

- `let streamSession: URLSession` — a second session whose
  `timeoutIntervalForRequest` is the named constant `streamIdleTimeout = 60`
  (the value is the inter-chunk idle timeout; the request session's `10` would
  abort a slow time-to-first-token). A dedicated session is used because a
  per-request `timeoutInterval` cannot reliably extend a session-level value.
  When `init` receives an injected session (tests), it is used as **both** the
  request and stream session; otherwise both are built with their respective
  timeouts.
- `func transportError(_ error: Error) -> ErrorResponse` (internal) — extracted
  from `perform`'s catch clauses: `NSURLErrorTimedOut` →
  `ErrorResponse(code:
  "TIMEOUT", message: <localized>)`, anything else →
  `ErrorResponse(code:
  "NETWORK_ERROR", message: error.localizedDescription)`.
  `perform` and `stream` route their transport catches through it;
  `ConversationClient` uses it for failures thrown **mid-iteration** by
  `AsyncBytes` (idle timeout, connection loss), which occur outside `stream()`.
  Lossless logging at the conversion point is retained per the observability
  invariant.
- `jsonDecoder` access changes from `private` to internal so
  `ConversationClient` decodes SSE frame payloads with the shared
  fraction-tolerant date strategy. The duplicate decoder previously planned for
  `ConversationClient` is eliminated.

### Networking (`ConversationClient.swift`)

A thin endpoint binding over the injected `APIClient`, owning only what is
SSE-specific: line splitting, frame assembly, and frame→event decoding. It does
not construct `URLRequest`s, own a `URLSession`, or configure transport.

```
protocol ConversationClientProtocol: Sendable {
    func streamConversation(request: CreateConversationRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error>
}

final class ConversationClient: ConversationClientProtocol, @unchecked Sendable {
    init(apiClient: APIClient = APIClient())
}
```

Behavioral contract of `streamConversation`:

- Calls
  `apiClient.stream("/api/v1/conversations/stream", body: request,
  accept: "text/event-stream", expectedStatus: 200)`.
  Pre-stream failures (`400/401/409/413/500`, transport errors, non-HTTP
  responses) are thrown by `APIClient` and finish the stream throwing the
  `ErrorResponse` unchanged.
- On `200`, splits the byte stream into LF-delimited lines **preserving empty
  lines** (a private `AsyncBytes` line-splitting extension; `AsyncLineSequence`
  (`bytes.lines`) discards empty lines, erasing the SSE frame boundaries the
  assembler keys on; a trailing CR is left for the assembler to normalize). Each
  line feeds `SSEFrameAssembler`; each completed frame is decoded and yielded:
  `.conversation` → `.delta`* → terminates on `.completed` (finish) or a
  `type:"error"` frame (`finish(throwing: ErrorResponse)`). At end of bytes,
  `flushPending()` recovers a frame cut off before its terminating blank line; a
  stream ending without a terminal frame finishes without throwing.
- A frame whose `data:` fails to decode to a known `type` (including
  `user_message`, off-contract here) finishes the stream throwing
  `ErrorResponse(code: "SERVER_ERROR", message: <localized>)`.
- Mid-stream `AsyncBytes` failures map via `apiClient.transportError(_:)`;
  `CancellationError` finishes the stream cleanly (no error). All frame decoding
  uses `apiClient.jsonDecoder`.

`ConversationClient` is not `@MainActor`; the byte-reading loop runs off the
main actor and the `@MainActor` view model consumes the stream.

### View model (`NewConversationViewModel.swift`)

```
@MainActor final class NewConversationViewModel: ObservableObject {
    @Published var messageText: String
    @Published var isStreaming: Bool
    @Published private(set) var userMessage: Message?
    @Published private(set) var coachStreamingText: String   // live buffer
    @Published private(set) var coachMessage: Message?        // canonical, on completion
    @Published private(set) var conversation: Conversation?
    @Published var errorResponse: ErrorResponse?
    @Published var infrastructureError: InfrastructureError?

    init(conversationClient: ConversationClientProtocol,
         onProfileRequired: @escaping () -> Void)
    func send() async
}
```

`send()`:

- Returns immediately if `coachMessage != nil` (first-turn-only guard: a turn
  succeeded). A failed turn leaves `coachMessage == nil`, so retry is permitted
  even when a prior attempt got as far as the `.conversation` event. The
  composer is also disabled in the succeeded state.
- Clears prior errors. Trims `messageText`; an empty-after-trim value or one
  exceeding `100000` characters sets
  `errorResponse = ErrorResponse(code:
  "VALIDATION", message: <localized>, fieldErrors: [FieldError(field: "message",
  message: <localized>)])`
  and issues no request (mirrors `RegistrationViewModel`; bounds match the
  contract's `minLength`/`maxLength`). Every synthesized `ErrorResponse`
  supplies `message:` — the existing `init(code:message:fieldErrors:)` has no
  defaulted parameters (contract requires `code` and `message`).
- Sets `isStreaming = true`, builds
  `CreateConversationRequest(message:, name: nil)`, and `for try await` over the
  stream: `.conversation` stores `conversation` + `userMessage` and clears
  `messageText`; `.delta` appends to `coachStreamingText`; `.completed` sets
  `coachMessage`.
- On a thrown `ErrorResponse`, mapped by `code` (exact, case-sensitive — the
  client-synthesized codes are UPPERCASE; this route family's server codes are
  lowercase per the RFC 39 contract): `"student_profile_required"` → invokes
  `onProfileRequired()` and publishes nothing (the root state machine replaces
  this screen; see _Dependency injection wiring_); `"TIMEOUT"` →
  `infrastructureError = .timeout`; `"NETWORK_ERROR"` → `.noConnectivity`;
  `"SERVER_ERROR"` → `.serverError`; otherwise → `errorResponse`. Unknown thrown
  errors → `.serverError`.
- `isStreaming` is reset to `false` on every terminal path via `defer` in
  `send()` (mirrors `RegistrationViewModel`'s `defer { isLoading = false }`).
- A turn is "successfully completed" only when `coachMessage != nil`. A failure
  that terminates the stream after the `.conversation` event (in-stream `error`,
  or transport `TIMEOUT`/`NETWORK_ERROR`/`SERVER_ERROR`) leaves
  `coachMessage ==
  nil`; the composer re-enables so the user can retry. A
  retry `send()` clears prior errors and starts a fresh stream.

### View (`NewConversationView.swift`)

- Owns the view model via `@StateObject`;
  `init(conversationClient:onProfileRequired:)` forwards both into the view
  model.
- A `ScrollView` thread: the user bubble (once `userMessage` is set), then the
  coach bubble bound to `coachStreamingText` while `isStreaming`, reconciled to
  `coachMessage.content` on completion. Bubbles use `Color.dsSurface` /
  `Color.brandAccent`, `Font.dsBody`, `DSSpacing`, `DSRadius`.
- A composer: a `TextField` bound to `messageText` and a `LoadingButton`
  (`isLoading: isStreaming`), both disabled while `isStreaming` or after the
  turn _successfully_ completes (`coachMessage != nil`). A turn that fails after
  the `.conversation` event re-enables the composer for retry (see view model).
- Errors: `FormErrorBanner(_:)` (takes a `String`) rendered when `errorResponse`
  is set, passed `errorResponse.message`; `infrastructureError` rendered inline
  via its `title`/`description`/`systemImage`. The view carries no
  `student_profile_required` copy — that code never reaches `errorResponse`
  (view model routes it to `onProfileRequired()`).
- A `streamingIndicator` shown while `isStreaming && coachMessage == nil` (a
  turn is in flight, before the canonical coach message is reconciled) and
  removed on completion.
- Accessibility identifiers + labels on every interactive element:
  `messageField`/"Message", `sendButton`/"Send", plus `userBubble`,
  `coachBubble`, `streamingIndicator`.

### Dependency injection wiring

- `AppViewModel.init` constructs the default
  `ConversationClient(apiClient: apiClient)` from the same shared `apiClient` as
  `authClient`/`studentClient`
  (`conversationClient ??
  ConversationClient(apiClient: apiClient)`); the
  `conversationClient: ConversationClientProtocol?` injection parameter is
  retained for tests.
- `AppViewModel` gains the re-onboarding entry point for the abnormal `409`
  edge. Profile gating (`resolveProfileState`) guarantees HomeView is reachable
  only with a profile, so a `student_profile_required` from the stream endpoint
  means the profile was deleted server-side mid-session. The recovery reuses the
  existing flow — the root state machine re-enters `.onboarding`, and a
  completed (or `STUDENT_ALREADY_EXISTS`-converged) onboarding returns to
  `.authenticated`:

  ```
  @MainActor func onStudentProfileRequired()
  // .authenticated(user) → .onboarding(user); any other state → no-op.
  ```

  The `409` itself proves no profile exists server-side, so no confirming
  `fetchProfile()` round-trip is made. Switching `authState` tears down
  `HomeView`'s `NavigationStack` (and the conversation screen in it).
- `UnicoachiOSApp` passes `viewModel.conversationClient` and
  `viewModel.onStudentProfileRequired` into `HomeView`.
- `HomeView` gains `conversationClient` and `onProfileRequired` properties and
  the entry-point button; its authenticated content is wrapped in a
  `NavigationStack` that pushes
  `NewConversationView(conversationClient:onProfileRequired:)`.

### API Contracts

Single endpoint consumed: `POST /api/v1/conversations/stream` (RFC 39). Request
`CreateConversationRequest`; success `200 text/event-stream` with the event
sequence `conversation` → `delta`* → (`message` | `error`); pre-stream failures
`400/401/409/413/500` as `application/json` `ErrorResponse`. No contract
changes; `api-specs/openapi.yaml` is not modified.

### Error Handling / Edge Cases

- Pre-stream HTTP error vs in-stream terminal `error` frame: both surface to the
  view model as a thrown `ErrorResponse`; partial `coachStreamingText`
  accumulated before an in-stream error is retained (not erased) and the error
  is shown.
- `409 student_profile_required`: abnormal edge (gating, above) → routes back
  into onboarding via `onProfileRequired()`; never rendered as an error.
- Validation (empty-after-trim, `> 100000` chars): local, no request issued.
- `413`: treated as a thrown `ErrorResponse` like any non-200; shown via the
  banner. The client sets no body-size cap of its own.
- Malformed/unknown SSE frame: a frame whose `data:` fails to decode to a known
  `type` finishes the stream throwing `ErrorResponse(code: "SERVER_ERROR")`.
- Cancellation: navigating away cancels the consuming `Task`; the
  `AsyncThrowingStream` terminates, the request is cancelled, and no error is
  published.

### Dependencies

- RFC 39 contract in `api-specs/openapi.yaml` (consumed, unchanged).
- RFC 42 architecture (already on `main`, consumed): shared `APIClient` (request
  construction, ISO-8601 fraction-tolerant decoding, `TIMEOUT`/
  `NETWORK_ERROR`/`SERVER_ERROR`/`UNKNOWN` vocabulary, lossless error logging),
  `StudentClient` + `OnboardingView`/`OnboardingViewModel` (the re-onboarding
  target; `STUDENT_ALREADY_EXISTS` converges to success), profile gating in
  `AppViewModel.resolveProfileState`, and `UserAuthState.onboarding`.
- Existing iOS design system (`DSSpacing`, `DSRadius`, colors, fonts,
  `LoadingButton`, `FormErrorBanner` — whose `init(_ message:)` takes a
  `String`), `InfrastructureError`, `ErrorResponse`/`FieldError`, and the
  `MockURLProtocol` test harness. `MockURLProtocol` is an internal top-level
  class in `ios-app/UnicoachiOSTests/AuthClientTests.swift`; it is visible
  across the whole `UnicoachiOSTests` module and MUST be reused — NOT redeclared
  (a second top-level `MockURLProtocol` is a duplicate-symbol compile error).
  `AuthClientTests.swift` is therefore not modified.
- No third-party dependencies; `SwiftUI`/`Foundation`/`os` only. iOS 17 /
  Swift 6.

## Tests

### `APIClientTests` (additions; `MockURLProtocol`, ephemeral injected session)

- `stream` request shape: path, `POST`, `Content-Type: application/json`,
  `Accept` equal to the passed value, body decodes to the sent payload.
- `stream` success: status == `expectedStatus` returns bytes that replay the
  response body verbatim.
- `stream` status mismatch with `ErrorResponse` body → throws it (`code`
  preserved).
- `stream` status mismatch with non-JSON body → throws
  `ErrorResponse(code: "SERVER_ERROR")`.
- `stream` connection timeout → throws `ErrorResponse(code: "TIMEOUT")`.
- `stream` connection failure (non-timeout `NSURLErrorDomain`) → throws
  `ErrorResponse(code: "NETWORK_ERROR")`.
- `transportError(_:)`: `NSURLErrorTimedOut` → `TIMEOUT`; any other error →
  `NETWORK_ERROR`.

### `SSEParserTests` (`SSEFrameAssembler`)

- Single frame: `event:` + `data:` + blank line → one `ServerSentEvent` with
  both.
- Multi-`data:` frame: two `data:` lines → `data` joined with `\n`.
- Data-only frame (no `event:`) → `event == nil`, `data` populated.
- Comment line (leading `:`) ignored; no frame emitted.
- CRLF line endings parsed identically to LF.
- Incomplete trailing frame (no blank line) → no frame emitted by `consume`.
- `flushPending`: buffered `data:` without a terminating blank line → returns
  the frame; nothing buffered → returns `nil`.

### `ConversationClientTests` (`MockURLProtocol` via injected `APIClient(baseURL:session:)`)

- Request shape: path `/api/v1/conversations/stream`, `POST`,
  `Content-Type:
  application/json`, `Accept: text/event-stream`, body decodes
  to the sent message.
- Happy path: a `200 text/event-stream` body of `conversation` + two `delta` +
  terminal `message` frames yields `.conversation`, `.delta`, `.delta`,
  `.completed` in order; `completed.message.content` equals the two deltas
  concatenated.
- Date decoding: a `conversation` frame with a fractional-seconds ISO-8601
  `createdAt` decodes to the expected `Date`; a second case with a
  non-fractional ISO-8601 timestamp also decodes (no failure).
- Terminal `error` frame → stream throws the carried `ErrorResponse`.
- Pre-stream `400` with `ErrorResponse` body → throws it (`code` preserved).
- Pre-stream `401` (`code: "unauthorized"`) → throws it.
- Pre-stream `409` (`code: "student_profile_required"`) → throws it.
- Pre-stream non-JSON body → throws `ErrorResponse(code: "SERVER_ERROR")`.
- In-stream malformed/unknown frame: a `200` stream whose `data:` JSON carries
  an unknown `type` (or a `user_message` frame, off-contract for this endpoint)
  finishes the stream throwing `ErrorResponse(code: "SERVER_ERROR")` — distinct
  code path from the pre-stream non-JSON case above.
- Transport-error mapping is covered by `APIClientTests` (above), not repeated
  here. `MockURLProtocol` delivers the body in one chunk, so chunk-boundary
  splitting is exercised by `SSEParserTests` at the line level.

### `NewConversationViewModelTests` (`MockConversationClient`)

- Happy path: scripted `[conversation, delta("Hi"), delta(" there"), completed]`
  → `userMessage` set, `coachStreamingText == "Hi there"`, `coachMessage` set,
  `conversation` set, `messageText == ""`, `isStreaming == false`.
- Empty/whitespace `messageText` → `errorResponse.code == "VALIDATION"` with a
  `message` field error; client not invoked.
- `messageText` length `> 100000` → `VALIDATION` with a `message` field error;
  client not invoked.
- Thrown `ErrorResponse(code: "student_profile_required")` → `onProfileRequired`
  invoked exactly once; `errorResponse` nil, `infrastructureError` nil,
  `isStreaming == false`.
- Thrown `ErrorResponse(code: "TIMEOUT")` → `infrastructureError == .timeout`.
- Thrown `ErrorResponse(code: "NETWORK_ERROR")` →
  `infrastructureError == .noConnectivity`.
- In-stream error after the `conversation` event and one delta →
  `coachStreamingText` retained, `errorResponse` set, `isStreaming == false`,
  `coachMessage == nil`.
- Retry after failure: a `send()` that fails (e.g. in-stream error after the
  `conversation` event) followed by a second `send()` scripted to succeed clears
  the error and yields a completed turn (`coachMessage` set) — proves the
  `coachMessage`-based guard permits retry when `conversation` is already set.
- First-turn guard: a second `send()` after a _successful_ turn (`coachMessage`
  set) issues no client call.
- Cancellation: cancelling the consuming task mid-stream stops further state
  mutation and leaves `isStreaming == false`.

### `AppViewModelTests` (additions; existing mocks)

- `onStudentProfileRequired()` from `.authenticated(user)` → `.onboarding(user)`
  (same `user.id`); no `fetchProfile` call is made.
- `onStudentProfileRequired()` from a non-authenticated state (e.g.
  `.unauthenticated`) → state unchanged.

## Implementation Plan

Each step builds and tests via Xcode (system toolchain, not the Nix dev shell —
the iOS toolchain is not in `flake.nix`). New `.swift` files MUST be registered
in `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` (the project uses explicit
file references, not synchronized groups); an unregistered file silently does
not compile into the target.

Build:
`xcodebuild -project ios-app/UnicoachiOS.xcodeproj -scheme UnicoachiOS build`
Test:
`xcodebuild test -project ios-app/UnicoachiOS.xcodeproj -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 17 Pro'`

1. Extend `APIClient.swift`: `streamSession` + `streamIdleTimeout`, the
   `stream(_:body:accept:expectedStatus:)` entrypoint, the extracted internal
   `transportError(_:)` (route `perform`'s catches through it), and
   `jsonDecoder` access change. Add the new cases to `APIClientTests.swift`.
   Verify: build; `xcodebuild test` runs `APIClientTests` green (existing cases
   prove `perform`'s mapping is unchanged by the extraction).
2. Add the model types and stream-event/wire DTOs to `Models.swift`. Verify:
   `xcodebuild … build` succeeds.
3. Add `SSEParser.swift` and `SSEParserTests.swift`; register both in
   `project.pbxproj` (app target / test target). Verify: build; `SSEParserTests`
   green.
4. Add `ConversationClient.swift` (+ protocol) and
   `ConversationClientTests.swift`; register in `project.pbxproj`. Reuse the
   existing `MockURLProtocol` from `AuthClientTests.swift` (do not redeclare
   it). Verify: build; `ConversationClientTests` green.
5. Add `MockConversationClient.swift`, `NewConversationViewModel.swift`, and
   `NewConversationViewModelTests.swift`; register in `project.pbxproj`. Verify:
   build; `NewConversationViewModelTests` green.
6. Add `NewConversationView.swift`; register in `project.pbxproj`. Verify: build
   succeeds.
7. Wire DI: in `AppViewModel`, default `conversationClient` to
   `ConversationClient(apiClient: apiClient)` and add
   `onStudentProfileRequired()`; extend `AppViewModelTests.swift`; thread
   `conversationClient` + `onStudentProfileRequired` through `UnicoachiOSApp`
   into `HomeView`; add the `NavigationStack` + entry-point button to
   `HomeView`. Verify: build; full `xcodebuild test` suite green.

## Files Modified

- `ios-app/UnicoachiOS/APIClient.swift` — add `stream` entrypoint,
  `streamSession`, `transportError(_:)`; widen `jsonDecoder` access.
- `ios-app/UnicoachiOS/Models.swift` — add conversation/message/event types.
- `ios-app/UnicoachiOS/SSEParser.swift` — new; `SSEFrameAssembler`,
  `ServerSentEvent`.
- `ios-app/UnicoachiOS/ConversationClient.swift` — new; protocol + thin binding
  over `APIClient`.
- `ios-app/UnicoachiOS/NewConversationViewModel.swift` — new.
- `ios-app/UnicoachiOS/NewConversationView.swift` — new.
- `ios-app/UnicoachiOS/HomeView.swift` — add NavigationStack + entry point +
  `conversationClient`/`onProfileRequired` properties.
- `ios-app/UnicoachiOS/AppViewModel.swift` — construct `conversationClient` from
  the shared `apiClient`; add `onStudentProfileRequired()`.
- `ios-app/UnicoachiOS/UnicoachiOSApp.swift` — pass `conversationClient` +
  `onStudentProfileRequired` to `HomeView`.
- `ios-app/UnicoachiOSTests/APIClientTests.swift` — add
  `stream`/`transportError` cases.
- `ios-app/UnicoachiOSTests/AppViewModelTests.swift` — add
  `onStudentProfileRequired` cases.
- `ios-app/UnicoachiOSTests/MockConversationClient.swift` — new.
- `ios-app/UnicoachiOSTests/NewConversationViewModelTests.swift` — new.
- `ios-app/UnicoachiOSTests/ConversationClientTests.swift` — new.
- `ios-app/UnicoachiOSTests/SSEParserTests.swift` — new.
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` — register all new source and
  test files.
