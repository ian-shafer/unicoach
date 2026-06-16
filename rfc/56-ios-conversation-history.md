# RFC 56: iOS Conversation History

## Executive Summary

The iOS app can start a conversation and continue it while the screen stays
open, but the active `Conversation` lives only in a `ConversationViewModel`
instance; leaving the chat discards it. There is no way to see prior
conversations or return to one. This RFC adds a conversation-history surface to
the iOS client: a list of the student's conversations in most-recently-used
order, each row tapping into the existing chat screen seeded with that
conversation's history and immediately continuable.

The backend already serves everything required. `GET /api/v1/conversations`
returns the student's unarchived conversations ordered by activity
(`MAX(convo_requests.created_at) DESC NULLS LAST`), so the client renders the
response verbatim with no client-side sort.
`GET
/api/v1/conversations/{id}/messages` returns the visible turns as a flat,
replay-ordered `[Message]`, strictly paired user-then-coach (a turn is visible
only with a non-null coach response). Continuing a conversation already works
through `POST /api/v1/conversations/{id}/messages/stream`. This RFC makes no
server, schema, or REST-contract change.

The work is three additions to the iOS target: (1) two non-streaming reads on
`ConversationClientProtocol` — `listConversations` and `fetchMessages`; (2) a
`ConversationListView` / `ConversationListViewModel` reached from a new "Your
Conversations" link on `HomeView`, with a toolbar compose action and an
empty/failed state; (3) a seed path on `ConversationViewModel` that loads a
conversation's history on appear, rebuilds the `[ChatTurn]` thread, and routes
follow-ups to the existing `postMessage` flow. `HomeView`'s "Start Coaching"
button is unchanged.

## Detailed Design

### Data Models

Two response envelopes are added to `ios-app/UnicoachiOS/Models.swift`, matching
the server DTOs (`ConversationListResponse`, `MessageListResponse`). The element
types `Conversation` and `Message` already exist and are reused unchanged.

```swift
struct ConversationListResponse: Codable, Sendable {
    let conversations: [Conversation]
}

struct MessageListResponse: Codable, Sendable {
    let messages: [Message]
}
```

`ConversationViewModel` gains a history-load state enum, governing only the
initial fetch when a conversation is re-entered (distinct from the per-turn
`isStreaming` / `ChatTurn.failure` state):

```swift
enum HistoryLoad: Equatable {
    case loading        // seeded VM, history fetch in flight
    case ready          // fresh VM (no fetch), or seeded VM whose fetch succeeded
    case failed(ErrorResponse)
}
```

`ConversationListViewModel` exposes a list-load state enum:

```swift
enum ConversationListState: Equatable {
    case loading
    case loaded([Conversation])
    case empty
    case failed(ErrorResponse)
}
```

### API Contracts

**Client protocol** (`ios-app/UnicoachiOS/ConversationClient.swift`).
`ConversationClientProtocol` is widened with two non-streaming reads; the two
existing streaming methods are unchanged:

```swift
protocol ConversationClientProtocol: Sendable {
    func streamConversation(request: CreateConversationRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error>
    func postMessage(conversationId: UUID, request: PostMessageRequest)
        -> AsyncThrowingStream<ConversationStreamEvent, Error>
    func listConversations() async throws -> [Conversation]
    func fetchMessages(conversationId: UUID) async throws -> [Message]
}
```

`ConversationClient` implements both through the injected `APIClient`: a
`apiClient.get(path)` returning `(data, response)`, then
`apiClient.decode(data:response:expectedStatus: 200)`. This follows
`StudentClient.createStudent`, which relies on `decode` throwing `decodeError`
on any status mismatch — the error path both reads depend on. (`fetchProfile` is
not the precedent: it short-circuits its `404` before `decode`, which these
reads must not do.)

- `listConversations` issues `GET /api/v1/conversations` (no `status` query
  parameter; the server defaults to the unarchived scope), decodes
  `ConversationListResponse` at status `200`, and returns `.conversations` **in
  the order received**. The client never re-sorts.
- `fetchMessages` issues `GET /api/v1/conversations/{conversationId}/messages`,
  decodes `MessageListResponse` at status `200`, returns `.messages` in order.

**View-model seeding.** `ConversationViewModel` gains a seed initializer that
takes an established `Conversation`; the existing fresh initializer is retained
for the "start a new conversation" paths.

```swift
// existing — fresh conversation (Start Coaching / compose)
init(conversationClient: ConversationClientProtocol, onProfileRequired: @escaping () -> Void)

// new — re-enter an established conversation
init(conversation: Conversation,
     conversationClient: ConversationClientProtocol,
     onProfileRequired: @escaping () -> Void)
```

The seed initializer sets `conversation` (non-`nil`, so `stream()` routes every
turn to `postMessage` — unchanged dispatch logic) and sets
`historyLoad =
.loading`. The fresh initializer leaves `historyLoad = .ready`
and `conversation
= nil`.

The view-model exposes a derived `isReady: Bool` (`historyLoad == .ready`). The
view's composer-disabled gate consumes it (`isStreaming || !isReady`); the
existing `canSend` is unchanged. `isReady` is the testable surface for the
composer gate.

`loadHistory()` is called from `ConversationView`'s `.task`. It is a no-op on
the fresh path and idempotent on re-appearance:

- Guard: return immediately unless `conversation != nil` and `turns.isEmpty`
  (fresh path has no conversation; an already-loaded thread is non-empty).
- Set `historyLoad = .loading`, call `fetchMessages(conversationId:)`, rebuild
  `turns`, set `historyLoad = .ready`.
- On a thrown `ErrorResponse`, set `historyLoad = .failed(error)`; any other
  thrown error maps to a synthesized `SERVER_ERROR` `ErrorResponse`. A retry
  action re-invokes `loadHistory()` (the guard still holds — `turns` is empty
  after a failure).

**Flat history → `[ChatTurn]`.** The server emits each visible turn as a `user`
then `coach` pair. A turn is visible iff its response content is non-null
(`service/src/main/kotlin/ed/unicoach/coaching/SPEC.md`), so the wire output is
strictly paired: no user-only, coach-only, or interleaved shapes occur. The
rebuild walks the list in order: a `.user` message opens a new `ChatTurn` (fresh
`UUID` key, `coachStreamingText = ""`, `failure = nil`); the next `.coach`
message attaches as that turn's `coachMessage`. A `.user` with no following
`.coach` yields a turn with `coachMessage = nil`, and a leading `.coach` with no
open turn is dropped — pure crash-guards against a contract violation, not
expected shapes. `Message.id` values are the server's real ids, so no
reconciliation is needed.

### Error Handling / Edge Cases

- **Empty list.** `listConversations` returns `[]` →
  `ConversationListState.empty`. The list screen shows a "No conversations yet"
  state that includes the same "Start a conversation" action as the toolbar
  compose button.
- **List load failure.** Transport failures surface as `ErrorResponse` with the
  client codes `TIMEOUT` / `NETWORK_ERROR` (via `APIClient.transportError`); a
  status mismatch or unparseable body surfaces `SERVER_ERROR` / a server code
  (e.g. `unauthorized`) via `decodeError`. All render a `.failed` state with a
  generic message and a Retry button that re-runs the load.
- **Reload on appearance.** `ConversationListViewModel` re-fetches on every
  appearance of the list screen, so MRU order reflects a conversation that was
  just continued and popped back from. The conversation screen does **not**
  reload its history on re-appearance (the `turns.isEmpty` guard makes
  `loadHistory` load-once per instance).
- **Re-entering a since-deleted conversation.** `fetchMessages` on a
  soft-deleted or foreign conversation returns `404` with body
  `{"code":"not_found"}`. Because `decode(data:response:expectedStatus: 200)`
  routes any non-`200` through `decodeError`, this surfaces as the `not_found`
  `ErrorResponse` → `historyLoad =
  .failed`, with the Retry affordance. No
  special-casing is added.
- **Profile deleted mid-session.** A re-entered conversation is continuable;
  posting a turn can still yield the first-turn-only pre-stream
  `409
  student_profile_required` path already handled in
  `ConversationViewModel.handle`, which invokes `onProfileRequired`.
  `ConversationListView` therefore threads `onProfileRequired` into every
  `ConversationView` it pushes. The list fetch itself cannot trigger this (a
  student-less caller gets a `200` empty list).
- **`lastActivityAt` display.** Rows render `name` and a relative timestamp from
  `lastActivityAt`, falling back to `updatedAt` to unwrap the `Date?` (listed
  conversations always have a visible turn, so `lastActivityAt` is set in
  practice).
- **In-flight continue then leave.** Unchanged from current behavior; leaving
  the chat cancels the stream via `AsyncThrowingStream` termination.

### Navigation & Views

- `HomeView` adds a `NavigationLink` labeled "Your Conversations" →
  `ConversationListView(conversationClient:onProfileRequired:)`, inside the
  existing `NavigationStack`. "Start Coaching" and "Log Out" are unchanged.
- `ConversationListView` renders a SwiftUI `List` over `ConversationListState`.
  Each conversation is a `NavigationLink` whose label is the row (name as
  primary text, relative timestamp as secondary) — the entire row is the tap
  target, per the standard list-detail convention — pushing
  `ConversationView(conversation:conversationClient:onProfileRequired:)`. A
  toolbar compose button (`+`) pushes a fresh
  `ConversationView(conversationClient:onProfileRequired:)`. `loading` shows a
  progress indicator; `empty` shows the no-conversations state with the start
  action; `failed` shows a message and Retry.
- `ConversationView` gains the seed initializer and renders `historyLoad`: a
  thread-area progress indicator while `.loading`, an inline error with Retry on
  `.failed`, and the existing thread + composer when `.ready`. The composer-
  disabled gate becomes `isStreaming || !isReady`.

### Dependencies

No new packages. No backend, schema, or REST-contract change. The new screen
depends only on the existing `APIClient` (cookie session +
`X-Unicoach-Client-Key` header), `ConversationClientProtocol`, and the
design-system tokens/components in `ios-app/UnicoachiOS/DesignSystem`.
`AppViewModel` already constructs and exposes `conversationClient`, and
`HomeView` already receives `conversationClient` and `onProfileRequired`, so no
wiring changes above `HomeView` are required. New `.swift` files must be
registered in `project.pbxproj` (no file-system sync).

## Tests

All tests run through system Xcode (`xcodebuild test … -scheme UnicoachiOS`),
not the Nix dev shell.

**`ConversationClientTests.swift`** (real client through `MockURLProtocol`,
exercising request building + decoding):

- `listConversations` decodes a multi-conversation `ConversationListResponse`
  body and returns the elements **in the order received** (assert ids match the
  wire order exactly — guards against any accidental client sort).
- `listConversations` decodes an empty `{"conversations":[]}` body to `[]`.
- `listConversations` issues `GET /api/v1/conversations` with no `status` query
  parameter (assert the recorded request path/method).
- `listConversations` on a non-`200` error body throws the decoded
  `ErrorResponse` (e.g. `unauthorized`).
- `fetchMessages` decodes a `MessageListResponse` of N user/coach pairs and
  preserves order.
- `fetchMessages` issues `GET /api/v1/conversations/{id}/messages` with the
  conversation id in the path.
- `fetchMessages` on a `404 {"code":"not_found"}` body throws an `ErrorResponse`
  with code `not_found`.

**`ConversationListViewModelTests.swift`** (new; protocol mock, asserts state
transitions and captured calls):

- Load success transitions `loading` → `loaded` and preserves the mock's element
  order.
- Load of an empty list transitions to `empty`.
- Load failure (mock throws `ErrorResponse`) transitions to `failed` carrying
  the error.
- A second appearance re-invokes `listConversations` (assert the mock's call
  count increments) and replaces the state — verifying reload-on-appearance for
  MRU freshness.

**`ConversationViewModelTests.swift`** (additions; `MockConversationClient`):

- The seed initializer sets `conversation`, so the first `send()` dispatches to
  `postMessage` (not `streamConversation`) — assert against the mock's
  per-method request logs.
- `loadHistory` builds one `ChatTurn` per user/coach pair from a flat N-turn
  `[Message]`, mapping `userMessage`/`coachMessage` correctly and ordering turns
  as received; `historyLoad` ends `.ready`.
- `loadHistory` on a contract-violating trailing `.user` message yields a final
  turn with `coachMessage == nil` (crash-guard; the server never emits this).
- `loadHistory` failure sets `historyLoad = .failed`; a subsequent retry
  re-invokes `fetchMessages` and, on success, populates `turns` and sets
  `.ready`.
- After a successful history load, `send()` continues the conversation via
  `postMessage` against the seeded id, and the new turn appends after the loaded
  turns.
- The fresh initializer leaves `historyLoad = .ready` and `conversation == nil`;
  `loadHistory` is a no-op (no `fetchMessages` call) and the first `send()`
  still dispatches to `streamConversation`.
- `isReady` is `false` while `historyLoad == .loading` (seeded VM) and `true` on
  the fresh VM and after a successful load — driving the composer-disabled gate.

**`MockConversationClient`** is extended to script `listConversations` /
`fetchMessages` return values (and throwable errors) and to record their
invocation counts/arguments, alongside the existing streaming scripting.

## Implementation Plan

Each step builds the iOS target via system Xcode. Build/test command base:

```sh
xcodebuild build -project ios-app/UnicoachiOS.xcodeproj -scheme UnicoachiOS \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

1. **Add response envelopes.** Add `ConversationListResponse` and
   `MessageListResponse` to `ios-app/UnicoachiOS/Models.swift`.
   - Verify: `xcodebuild build … -scheme UnicoachiOS` succeeds.

2. **Widen the client protocol and implementation.** Add `listConversations` and
   `fetchMessages` to `ConversationClientProtocol` and implement them in
   `ConversationClient` (`apiClient.get` then
   `apiClient.decode(data:response:expectedStatus: 200)`, returning the
   envelope's array in order). Update the in-file preview/mocks that conform to
   the protocol: `ConversationPreviewClient` in `ConversationView.swift` and
   `HomePreviewConversationClient` in `HomeView.swift`.
   - Verify: `xcodebuild build …` succeeds (all conformers implement the new
     members).

3. **Extend `MockConversationClient`.** Add scripted returns + call recording
   for `listConversations` / `fetchMessages` in
   `ios-app/UnicoachiOSTests/MockConversationClient.swift`.
   - Verify: `xcodebuild build … -scheme UnicoachiOS` (test target compiles).

4. **Client tests.** Add the `listConversations` / `fetchMessages` cases to
   `ios-app/UnicoachiOSTests/ConversationClientTests.swift`.
   - Verify:
     `xcodebuild test … -only-testing:UnicoachiOSTests/ConversationClientTests`
     passes.

5. **Seed `ConversationViewModel`.** Add `HistoryLoad`, the seed initializer,
   the flat-history→`[ChatTurn]` rebuild, and `loadHistory()` (with the
   `conversation
   != nil && turns.isEmpty` guard and failure→retry handling)
   to `ios-app/UnicoachiOS/ConversationViewModel.swift`.
   - Verify: `xcodebuild build …` succeeds.

6. **Render history state in `ConversationView`.** Add the seed initializer, the
   `.task { await viewModel.loadHistory() }` hook, the thread-area loading
   indicator, the inline `.failed` Retry, and the
   composer-disabled-until-`.ready` gate in
   `ios-app/UnicoachiOS/ConversationView.swift`.
   - Verify: `xcodebuild build …` succeeds.

7. **Conversation view-model tests.** Add the seed/history cases to
   `ios-app/UnicoachiOSTests/ConversationViewModelTests.swift`.
   - Verify:
     `xcodebuild test … -only-testing:UnicoachiOSTests/ConversationViewModelTests`
     passes.

8. **Build the list view-model.** Add `ConversationListState` and
   `ConversationListViewModel` (load-on-appear via `listConversations`, mapping
   `[]`→`empty`, errors→`failed`) in a new
   `ios-app/UnicoachiOS/ConversationListViewModel.swift`. Register the file in
   `ios-app/UnicoachiOS.xcodeproj/project.pbxproj`.
   - Verify: `xcodebuild build …` succeeds.

9. **Build the list view.** Add `ConversationListView` (the `List` of
   `NavigationLink` rows pushing the seeded `ConversationView`, the toolbar
   compose button pushing a fresh `ConversationView`, and the
   loading/empty/failed states) in a new
   `ios-app/UnicoachiOS/ConversationListView.swift`. Register the file in
   `project.pbxproj`.
   - Verify: `xcodebuild build …` succeeds.

10. **Wire into `HomeView`.** Add the "Your Conversations" `NavigationLink` →
    `ConversationListView(conversationClient:onProfileRequired:)` in
    `ios-app/UnicoachiOS/HomeView.swift`.
    - Verify: `xcodebuild build …` succeeds.

11. **List view-model tests.** Add
    `ios-app/UnicoachiOSTests/ConversationListViewModelTests.swift` and register
    it in `project.pbxproj`.
    - Verify:
      `xcodebuild test … -only-testing:UnicoachiOSTests/ConversationListViewModelTests`
      passes.

12. **Full suite.** Run the whole target.
    - Verify:
      `xcodebuild test -project ios-app/UnicoachiOS.xcodeproj -scheme
      UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 17 Pro'`
      passes.

## Files Modified

Created:

- `ios-app/UnicoachiOS/ConversationListView.swift` — the history list screen.
- `ios-app/UnicoachiOS/ConversationListViewModel.swift` — list load state
  machine.
- `ios-app/UnicoachiOSTests/ConversationListViewModelTests.swift` — list
  view-model tests.

Modified:

- `ios-app/UnicoachiOS/Models.swift` — add `ConversationListResponse`,
  `MessageListResponse`.
- `ios-app/UnicoachiOS/ConversationClient.swift` — widen
  `ConversationClientProtocol`; implement `listConversations`, `fetchMessages`.
- `ios-app/UnicoachiOS/ConversationViewModel.swift` — `HistoryLoad`, seed
  initializer, `loadHistory()`, flat-history→`[ChatTurn]` rebuild.
- `ios-app/UnicoachiOS/ConversationView.swift` — seed initializer, history-load
  rendering (loading/failed), composer gate, update `ConversationPreviewClient`.
- `ios-app/UnicoachiOS/HomeView.swift` — "Your Conversations" `NavigationLink`,
  update `HomePreviewConversationClient`.
- `ios-app/UnicoachiOSTests/MockConversationClient.swift` — script + record
  `listConversations` / `fetchMessages`.
- `ios-app/UnicoachiOSTests/ConversationClientTests.swift` — list/messages
  client tests.
- `ios-app/UnicoachiOSTests/ConversationViewModelTests.swift` — seed/history
  view-model tests.
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` — register the three new
  `.swift` files (build + test target membership).
