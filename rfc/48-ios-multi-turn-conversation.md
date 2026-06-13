# RFC 48: iOS multi-turn coaching conversation

## Executive Summary

The iOS app can start a coaching conversation and stream the coach's first
reply, but cannot continue it: `NewConversationViewModel` keeps exactly one user
and one coach message and only ever calls `streamConversation`. The backend
already supports turn-taking — `POST
/api/v1/conversations/{id}/messages/stream`
streams a follow-up turn, opening with a `user_message` event (no
`Conversation`), then `delta`*, then a terminal `message` or `error`, mirroring
the start-conversation stream. This RFC is therefore an iOS-client change only:
no backend, service, or migration work.

The conversation screen becomes a multi-turn thread. The student sends follow-up
messages and receives streamed replies indefinitely within one session. The
screen's source of truth is its in-memory thread; resuming a past conversation
from a list or rehydrating history from the server is out of scope.

The work: extend `ConversationClient` with a `postMessage` stream binding that
decodes the `user_message` opener; replace the single-turn view model with a
`[ChatTurn]` thread that dispatches the first turn to `streamConversation` and
subsequent turns to `postMessage`; render the thread with optimistic echo of the
student's message and per-turn failure with retry-in-place. The first turn's
failure soft-deletes the conversation server-side, so a conversation is treated
as established (and follow-ups routed to `postMessage`) only after its first
turn completes successfully. The screen and its view model are renamed from
`NewConversationView`/`NewConversationViewModel` to
`ConversationView`/`ConversationViewModel` to reflect the ongoing-conversation
responsibility.

## Detailed Design

### Dependencies

No new dependencies. The change reuses the existing endpoint-agnostic transport
(`APIClient.stream`, dual `URLSession`s, fraction-tolerant ISO-8601 decoder,
`transportError` mapping), SSE assembly (`SSEFrameAssembler`,
`URLSession.AsyncBytes.sseLines`), and design-system components. The backend
follow-up endpoint `POST /api/v1/conversations/{id}/messages/stream` and its
`user_message`/`delta`/`message`/`error` event set in
`rest-server/src/main/kotlin/ed/unicoach/rest/models/StreamEvent.kt` are
consumed as-is.

### Data models

New and changed types live in `ios-app/UnicoachiOS/Models.swift`.

`PostMessageRequest` is the follow-up request body, matching the backend
`PostMessageRequest` (single `message` field; no `name`):

```swift
struct PostMessageRequest: Codable, Sendable {
    let message: String
}
```

`ConversationStreamEvent` gains a `userMessage` case. The follow-up stream opens
with a server-authoritative user message carrying no conversation; the start
stream's existing `.conversation(_, userMessage:)` opener is unchanged:

```swift
enum ConversationStreamEvent: Sendable {
    case conversation(Conversation, userMessage: Message)
    case userMessage(Message)
    case delta(String)
    case completed(Message)
}
```

`UserMessageFrame` is the wire DTO for the `type:"user_message"` SSE frame,
matching the backend `UserMessageEvent`:

```swift
struct UserMessageFrame: Codable {
    let type: String
    let userMessage: Message
}
```

`ChatTurn` is the per-turn view state held by the view model (see View model).
It is local to the view-model file, not a wire type.

### Client API contract

`ConversationClientProtocol` (`ios-app/UnicoachiOS/ConversationClient.swift`)
gains a follow-up binding with the same stream type as the existing start
binding:

```swift
protocol ConversationClientProtocol: Sendable {
    func streamConversation(request: CreateConversationRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error>
    func postMessage(conversationId: UUID, request: PostMessageRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error>
}
```

`postMessage` POSTs `request` to
`/api/v1/conversations/{conversationId}/messages/stream` with
`Accept: text/event-stream` and `expectedStatus: 200`, then runs the same SSE
pump as `streamConversation`. `APIClient.stream(_:body:accept:expectedStatus:)`
is already generic over an arbitrary path and `Encodable` body, so no
`APIClient` change is needed; the new binding only supplies a different path and
body type. The pump (currently
`runStream(request:continuation:)`) is generalized over `(path, body)` so both
bindings share line-splitting, frame assembly, terminal detection, and the
task/cancellation wrapper; only the URL path, request body type, and the
accepted opener differ.

Frame decoding stays strict per endpoint. The decoder is parameterized by the
opener the endpoint may legally emit:

- `streamConversation` accepts `conversation`, `delta`, `message`, `error`; a
  `user_message` frame is off-contract → `SERVER_ERROR` (unchanged behavior).
- `postMessage` accepts `user_message`, `delta`, `message`, `error`; a
  `conversation` frame is off-contract → `SERVER_ERROR`.

`delta`, `message`, and `error` decode identically for both, reusing
`MessageDeltaFrame`, `MessageCompletedFrame`, and `StreamErrorFrame`; on the
follow-up endpoint `user_message` decodes via `UserMessageFrame` into
`.userMessage(_)`. The terminal `message` frame finishes the continuation; an
`error` frame throws its carried `ErrorResponse`; an
unknown/off-contract/undecodable frame throws the shared `SERVER_ERROR`.

### View model

`ConversationViewModel` (renamed from `NewConversationViewModel`, `@MainActor`,
`ObservableObject`) replaces the single user/coach message pair with an ordered
thread of turns.

```swift
struct ChatTurn: Identifiable {
    let id: UUID                 // sole ForEach key; never derived from Message.id
    var userMessage: Message     // synthetic on append; fully replaced by the server copy on the opener
    var coachStreamingText: String
    var coachMessage: Message?   // canonical on .completed
    var failure: TurnFailure?    // set on a streamed/transport failure
}

enum TurnFailure: Equatable {
    case server(ErrorResponse)        // a coach failure frame (coach_unavailable / coach_failed)
    case infrastructure(InfrastructureError)  // a transport-class failure (timeout / connectivity / server)
}
```

`TurnFailure` preserves the two existing display vocabularies rather than
flattening them: a coach `error` frame keeps its server `ErrorResponse.message`,
and a transport failure keeps the existing `InfrastructureError` (its `title`,
`description`, and `systemImage`). The per-turn failure view (see View) renders
each case with the same copy the screen renders today; `InfrastructureError` is
no longer surfaced at screen level. `TurnFailure: Equatable` (so tests assert on
it) requires `Equatable` conformance on both associated types:
`InfrastructureError: Equatable` (new, in `InfrastructureError.swift`) and
`ErrorResponse: Equatable` (new, in `Models.swift`; its stored fields are all
`Equatable`).

Published state:

- `messageText: String` — composer text (unchanged).
- `turns: [ChatTurn]` — the thread, replacing
  `userMessage`/`coachStreamingText`/`coachMessage`.
- `conversation: Conversation?` — the established conversation, `nil` until the
  first turn completes successfully; drives endpoint selection.
- `isStreaming: Bool` — true while a turn is in flight; gates the composer.
- `validationError: ErrorResponse?` — pre-send validation banner, replacing the
  prior screen-level `errorResponse`/`infrastructureError`; turn-scoped failures
  live on `ChatTurn.failure` instead.

`send()`:

1. Returns if `isStreaming` (one turn in flight at a time). The first-turn guard
   (`coachMessage != nil`) is removed.
2. Clears `validationError`. Trims `messageText`; if empty or `> 100_000` chars,
   sets `validationError` (field `message`) and returns without appending a
   turn.
3. Appends a `ChatTurn` with a synthetic `userMessage` (local UUID string id,
   the trimmed text, current timestamp), clears `messageText`, sets
   `isStreaming = true`.
4. Dispatches by establishment: `conversation == nil` → `streamConversation`;
   otherwise → `postMessage(conversationId: conversation.id, …)`.
5. Consumes the stream into the target turn — the just-appended turn for
   `send()`, the retried turn for `retry` — located in `turns` by its `id` (a
   value type; all mutation is `turns[idx].…` via `firstIndex(where:)` on the
   target `id`). This consume routine is shared by `send()` and `retry`:
   - `.conversation(convo, userMessage)` (first turn only) — reconciles the
     turn's `userMessage` with the server copy and stashes `convo` in a private
     `pendingConversation: Conversation?` (not published) until `.completed`.
   - `.userMessage(message)` (follow-up turns) — reconciles the turn's
     `userMessage`.
   - `.delta(text)` — appends to the turn's `coachStreamingText`.
   - `.completed(message)` — sets the turn's `coachMessage`; on the first turn,
     commits `conversation = pendingConversation` (establishment).
6. On a thrown `ErrorResponse`, maps it: `student_profile_required` (a pre-stream
   `409` on the start path only — see Error handling) → invokes
   `onProfileRequired()` and removes the optimistic turn (the screen is being
   replaced by onboarding); any other server code → sets the target turn's
   `failure = .server(error)`. On a thrown transport error, maps it through the
   shared `TIMEOUT`/`NETWORK_ERROR`/`SERVER_ERROR` vocabulary to the
   corresponding `InfrastructureError` and sets `failure = .infrastructure(_)`.
   `isStreaming` is reset via `defer`.

`retry(_ id: ChatTurn.ID)` resolves the turn by `id` in `turns` (a value type;
all mutation is `turns[idx].…`), clears its `failure` and partial
`coachStreamingText`, re-sets `isStreaming`, and re-dispatches that turn's
`userMessage.content` into the same element. Because the first turn's failure
soft-deletes
the conversation server-side, retry is routed by the same establishment rule: an
unestablished first turn re-creates via `streamConversation`; an established
follow-up retries via `postMessage` against the same conversation id.

### View

`ConversationView` (renamed from `NewConversationView`) renders the thread:

- `thread` becomes a `ScrollViewReader` wrapping a `ScrollView` with a `ForEach`
  over `viewModel.turns`. Each turn renders the user bubble (reusing
  `messageBubble`, `identifier: "userBubble"`), then the coach bubble
  (`coachMessage?.content ?? coachStreamingText`, `identifier: "coachBubble"`)
  when present, then the streaming indicator on the active turn while
  `isStreaming` and no `coachMessage`, then an inline failure view when
  `turn.failure != nil`. The failure view is new: it shows the failure copy plus
  a new inline retry button (`identifier: "retryButton"`) calling
  `viewModel.retry(turn.id)`. The failure view renders by case:
  `.server(error)` shows `error.message` (matching today's `FormErrorBanner`
  copy); `.infrastructure(infra)` shows `infra.systemImage`, `infra.title`, and
  `infra.description` (matching today's `errorArea` infrastructure block).
  `InfrastructureError` no longer appears at screen level — the per-turn failure
  view is its only renderer. New content scrolls to the bottom.
- `composer` is unchanged except gating:
  `isComposerDisabled = viewModel.isStreaming` (the `coachMessage != nil` term
  is removed).
- `validationError` renders the pre-send banner directly above the composer
  (`FormErrorBanner`), replacing the prior screen-level `errorArea`.
- Navigation title is a static string; it does not depend on conversation state.

`HomeView`'s `NavigationLink` targets `ConversationView`. The two existing
SwiftUI preview clients gain the `postMessage` method:
`HomePreviewConversationClient` in `HomeView.swift` (extended in place) and
`NewConversationPreviewClient` in `NewConversationView.swift`, which is renamed
to `ConversationPreviewClient` alongside the view file rename.

### Error handling and edge cases

- **First-turn failure soft-delete.** `CoachingService.startConvo` soft-deletes
  the just-created conversation when its first reply fails. The client therefore
  commits `conversation` only on the first `.completed`. A failed-then-retried
  first turn re-creates via `streamConversation`; it never posts to a
  soft-deleted conversation id (which would return `404 not_found`).
- **Follow-up failure persistence.** `CoachingService.postTurn` does not delete
  on failure; the failed turn is invisible in server history (response content
  null) but the conversation persists. The client keeps the failed turn visible
  in-session with retry-in-place; a retry creates a fresh backend turn.
- **Off-contract opener.** A `user_message` frame on the start endpoint, or a
  `conversation` frame on the follow-up endpoint, is rejected as `SERVER_ERROR`.
- **Single in-flight turn.** `send()` is a no-op while `isStreaming`; the
  composer and send button are disabled, preventing concurrent turns.
- **Cancellation.** Leaving the screen cancels the active stream via the
  continuation's `onTermination` (unchanged); `isStreaming` resets through the
  `defer`. Partial turn state is discarded with the view model.
- **Optimistic reconcile gap.** If a transport error arrives before any opener
  event, the synthetic user bubble remains and the turn shows `failure` with
  retry; no server message is lost because none was received.
- **Validation.** Empty/oversized input is rejected before a turn is appended;
  no backend call is made.
- **`student_profile_required` is first-turn-only and pre-stream.** On the start
  path `handleStreamCreate` returns a `409 student_profile_required` *before*
  opening the SSE body, so the client receives it as a non-200 thrown by
  `APIClient.stream`, never as a terminal `error` frame. The follow-up path
  `handleStreamMessage` cannot emit it: a missing profile there returns a
  pre-stream `404 not_found`. The `student_profile_required` branch of the error
  mapping is therefore reachable only on the first turn.
- **Error-code casing.** Server codes for these endpoints are lowercase: the
  start path emits `coach_unavailable`/`coach_failed` (terminal `error` frame)
  and `student_profile_required` (pre-stream `409`); the follow-up path emits
  `coach_unavailable`/`coach_failed` (terminal `error` frame) and `not_found`
  (pre-stream `404`). Client-synthesized codes (`TIMEOUT`, `NETWORK_ERROR`,
  `SERVER_ERROR`, `VALIDATION`) are uppercase. Matching stays case-sensitive.

## Tests

iOS unit tests (XCTest) under `ios-app/UnicoachiOSTests`. No backend tests
change. Run via `xcodebuild` (scheme `UnicoachiOS`), outside the nix dev shell.

### ConversationClientTests (`ConversationClientTests.swift`)

Existing `streamConversation` tests are retained unchanged, including the
assertion that a `user_message` frame on the start endpoint yields
`SERVER_ERROR` (strictness preserved). New `postMessage` tests:

- **Request shape** — asserts method `POST`, path
  `/api/v1/conversations/{id}/messages/stream` with the conversation UUID,
  `Content-Type: application/json`, `Accept: text/event-stream`, and a body
  encoding `{ "message": … }`.
- **Happy path** — a scripted SSE body of `user_message`, two `delta` frames,
  then `message` yields `.userMessage`, `.delta`, `.delta`, `.completed` in
  order and finishes.
- **Date decoding** — `createdAt` parses with and without fractional seconds.
- **Terminal error frame** — a `type:"error"` frame throws the carried
  `ErrorResponse` (`coach_unavailable`).
- **Pre-stream non-200** — `400`/`401`/`404`/`500` with an `ErrorResponse` body
  throws that error; a non-JSON non-200 body throws `SERVER_ERROR`.
- **Off-contract opener** — a `conversation` frame on the follow-up endpoint
  throws `SERVER_ERROR`.
- **Unknown frame type** — an unrecognized `type` throws `SERVER_ERROR`.

### ConversationViewModelTests (renamed from `NewConversationViewModelTests.swift`)

- **First turn happy path** — `streamConversation` script (`conversation`,
  deltas, `message`) produces one turn with reconciled user message, accumulated
  then canonical coach text, and establishes `conversation`.
- **Follow-up dispatch routing** — after a successful first turn, a second
  `send()` calls `postMessage` with the established conversation id (not
  `streamConversation`), appending a second turn.
- **Optimistic echo + reconcile** — the user bubble appears on `send()` before
  any event; the opener event replaces the synthetic message with the server
  copy without duplicating the bubble.
- **Multi-turn accumulation** — three sequential turns yield three turns in
  order with correct user/coach content.
- **Pre-send validation** — empty/whitespace and `> 100_000`-char input set
  `validationError`, append no turn, and issue no client call.
- **Follow-up failure + retry-in-place** — a follow-up turn whose stream throws
  `coach_unavailable` sets `turn.failure = .server(_)`, keeps the user bubble,
  and re-enables the composer; `retry` clears the failure and re-calls
  `postMessage` with the same conversation id; on success the turn completes.
- **First-turn failure retry re-creates** — a first turn that fails leaves
  `conversation == nil`; `retry` calls `streamConversation` again (never
  `postMessage`).
- **student_profile_required** — a first turn throwing
  `student_profile_required` invokes `onProfileRequired`, publishes no
  `validationError`, and removes the optimistic turn.
- **Transport error mapping** — a thrown `TIMEOUT`/`NETWORK_ERROR` sets the
  active turn's `failure = .infrastructure(_)` with the matching
  `InfrastructureError` case (not a screen-level banner).
- **Gating** — `send()` is a no-op while `isStreaming`; the composer-disabled
  flag tracks `isStreaming` only.
- **Cancellation** — cancelling mid-stream stops thread mutation and resets
  `isStreaming`.

### MockConversationClient (`MockConversationClient.swift`)

- Records `streamConversation` requests and `postMessage`
  `(conversationId, PostMessageRequest)` pairs in separate per-method logs, so a
  test can assert which endpoint a turn dispatched to; the prior single
  `requests`/`invocationCount` recording is replaced by these per-method logs.
- Scripts distinct event sequences across successive calls (a per-call script
  queue spanning both methods) so multi-turn and retry tests can drive different
  outcomes per turn, while retaining the per-event delay used for cancellation
  tests.

## Implementation Plan

Steps 4 and 5 land as a pair — the view and view-model renames are mutually
required to compile, so step 4 is verified only by step 5's build, which also
updates `project.pbxproj` so the renamed sources resolve; all other steps are
independently buildable. iOS verification uses `xcodebuild` with
the `UnicoachiOS` scheme against an installed simulator (substitute an available
destination for `<sim>`), run outside the nix dev shell.

1. **Add data models.** In `Models.swift`, add `PostMessageRequest`, the
   `.userMessage(Message)` case on `ConversationStreamEvent`, `UserMessageFrame`,
   and `Equatable` on `ErrorResponse`; in `InfrastructureError.swift`, add
   `Equatable`.
   - Verify:
     `cd ios-app && xcodebuild build -scheme UnicoachiOS -destination '<sim>'`
     fails only at the not-yet-updated client switch (expected), or compiles if
     the new case is not yet matched exhaustively elsewhere; confirm the models
     compile by building the `UnicoachiOS` target.

2. **Extend the client.** In `ConversationClient.swift`, add `postMessage` to
   the protocol and `ConversationClient`; generalize the SSE pump over
   `(path, body)`; parameterize frame decoding by the legal opener so
   `streamConversation` rejects `user_message` and `postMessage` rejects
   `conversation`; decode `user_message` into `.userMessage`. The two existing
   preview clients gain `postMessage` in their own later steps
   (`HomePreviewConversationClient` in step 4; the renamed
   `ConversationPreviewClient` in step 5).
   - Verify:
     `cd ios-app && xcodebuild build -scheme UnicoachiOS -destination '<sim>'`.

3. **Add client tests.** In `ConversationClientTests.swift`, add the
   `postMessage` cases listed under Tests; keep the existing
   `streamConversation` strictness assertion.
   - Verify:
     `cd ios-app && xcodebuild test -scheme UnicoachiOS -destination '<sim>' -only-testing:UnicoachiOSTests/ConversationClientTests`.

4. **Rewrite the view model.** Rename `NewConversationViewModel.swift` →
   `ConversationViewModel.swift`; rename the type to `ConversationViewModel`;
   add `ChatTurn`/`TurnFailure`; implement the `[ChatTurn]` thread, optimistic
   echo, establishment-gated dispatch, per-turn failure, and `retry`. Update the
   reference and `HomePreviewConversationClient` in `HomeView.swift` and point
   the `NavigationLink` at `ConversationView`.
   - Verify: builds after step 5 (the view rename is required to compile).

5. **Rewrite the view and update the project file.** Rename
   `NewConversationView.swift` → `ConversationView.swift`; rename the type to
   `ConversationView`; render the thread via `ScrollViewReader`/`ForEach`,
   per-turn failure + the new inline retry button, `validationError` banner, and
   `isStreaming`-only composer gating; rename the in-file
   `NewConversationPreviewClient` to `ConversationPreviewClient` and add its
   `postMessage`. In the same step, in `UnicoachiOS.xcodeproj/project.pbxproj`,
   rename the file references and build-phase entries for the two production
   renames (`NewConversationView.swift`, `NewConversationViewModel.swift`) so the
   renamed sources resolve.
   - Verify:
     `cd ios-app && xcodebuild build -scheme UnicoachiOS -destination '<sim>'`
     resolves all sources with no missing-file references.

6. **Update the mock and view-model tests.** Rewrite
   `MockConversationClient.swift` to record `streamConversation` requests and
   `postMessage` `(conversationId, PostMessageRequest)` pairs separately and to
   script per-call event sequences; rename `NewConversationViewModelTests.swift`
   → `ConversationViewModelTests.swift` (updating the corresponding
   `project.pbxproj` test reference) and implement the cases listed under Tests.
   - Verify:
     `cd ios-app && xcodebuild test -scheme UnicoachiOS -destination '<sim>' -only-testing:UnicoachiOSTests/ConversationViewModelTests`.

7. **Full suite.** Run the whole iOS test target.
   - Verify:
     `cd ios-app && xcodebuild test -scheme UnicoachiOS -destination '<sim>'`.

## Files Modified

- `ios-app/UnicoachiOS/Models.swift` — add `PostMessageRequest`, the
  `.userMessage` case on `ConversationStreamEvent`, and `UserMessageFrame`; add
  `Equatable` to `ErrorResponse`.
- `ios-app/UnicoachiOS/InfrastructureError.swift` — add `Equatable` conformance
  (so `TurnFailure` is `Equatable`).
- `ios-app/UnicoachiOS/ConversationClient.swift` — add `postMessage` to the
  protocol and implementation; generalize the SSE pump; per-endpoint opener
  strictness; `user_message` decoding.
- `ios-app/UnicoachiOS/NewConversationViewModel.swift` → **renamed**
  `ios-app/UnicoachiOS/ConversationViewModel.swift` — multi-turn `[ChatTurn]`
  thread, optimistic echo, establishment-gated dispatch, per-turn failure,
  retry.
- `ios-app/UnicoachiOS/NewConversationView.swift` → **renamed**
  `ios-app/UnicoachiOS/ConversationView.swift` — thread rendering, auto-scroll,
  per-turn error + retry, composer gating, preview client.
- `ios-app/UnicoachiOS/HomeView.swift` — `NavigationLink` → `ConversationView`;
  `HomePreviewConversationClient` implements `postMessage`.
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` — rename file references and
  build-phase entries for the two production renames and the test rename.
- `ios-app/UnicoachiOSTests/ConversationClientTests.swift` — add `postMessage`
  cases; retain the `streamConversation` strictness assertion.
- `ios-app/UnicoachiOSTests/NewConversationViewModelTests.swift` → **renamed**
  `ios-app/UnicoachiOSTests/ConversationViewModelTests.swift` — multi-turn,
  routing, optimistic echo, failure/retry, gating, cancellation tests.
- `ios-app/UnicoachiOSTests/MockConversationClient.swift` — add `postMessage`;
  replace single-request recording with per-method logs and a per-call script
  queue.
