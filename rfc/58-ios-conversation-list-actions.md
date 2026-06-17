# RFC 58: iOS conversation-list archive and delete actions

## Executive Summary

The iOS conversation list (`ConversationListView`) is read-only: a student can
open a conversation but cannot remove one. This RFC adds per-row **Archive** and
**Delete** actions, consuming the in-place REST surface from three iOS layers: a
transport addition (`APIClient` gains `delete`/`patch`), two new
`ConversationClient` methods, and view-model intents that mutate the loaded list
optimistically with rollback on failure.

Each action removes its row immediately and reinserts it on failure; the failure
is surfaced through a separate `actionError` alert channel so the loaded list is
never torn down. The UI exposes trailing swipe actions and a context menu per
row; Delete is gated by a confirmation dialog, Archive fires immediately.

Scope is iOS-only. Unarchive UI and an archived-browsing surface are out of
scope.

## Detailed Design

The REST contract exists in `ConvoRoutes.kt`:
`DELETE /api/v1/conversations/{id}` → `204`; `PATCH /api/v1/conversations/{id}`
with `{archived:true}` → `200` and stamps `archivedAt`. The default list scope
is unarchived, so an archived conversation drops out of the active list on the
next load. Conversation routes emit **lowercase** error codes (`unauthorized`,
`not_found`, `validation_failed`, `student_profile_required`); no iOS code in
this RFC branches on a specific `code`, so casing is immaterial here.

### Transport layer — `APIClient`

`APIClient` currently exposes only `post`, `get`, and `stream` over the private
`perform`. Two thin verb wrappers are added, mirroring the existing
`post`/`get`:

```swift
func delete(_ path: String) async throws -> (data: Data, response: HTTPURLResponse)
func patch<B: Encodable>(_ path: String, body: B) async throws -> (data: Data, response: HTTPURLResponse)
```

Both delegate to the private `perform` (bodyless for `delete`), inheriting its
header, encoding, and transport-error mapping. The existing
`expect(data:response:expectedStatus:)` helper (status-only, no body decode) is
reused by callers for the `204`/`200` checks; `decodeError` (invoked by `expect`
on mismatch) yields the thrown `ErrorResponse`.

### Request model — `Models.swift`

```swift
struct UpdateConversationRequest: Codable, Sendable {
    let name: String?
    let archived: Bool?
}
```

Both fields are optional to mirror the server DTO
(`UpdateConversationRequest(name: String? = null, archived: Boolean? = null)`),
which permits a one-field PATCH. Swift's synthesized `Encodable` omits `nil`
optionals via `encodeIfPresent`, so a `nil` `name` is dropped from the wire,
leaving the server-side name untouched.

### Client layer — `ConversationClient`

`ConversationClientProtocol` gains two methods; `ConversationClient` implements
them against `APIClient`, mirroring `fetchMessages`'s single-entity-by-id error
handling (no `404` short-circuit — any non-success routes through `decodeError`
and throws the decoded `ErrorResponse`):

```swift
func deleteConversation(conversationId: UUID) async throws
func setArchived(conversationId: UUID, archived: Bool) async throws
```

- `deleteConversation` issues `DELETE /api/v1/conversations/{id}` and expects
  `204`. Returns `Void`.
- `setArchived` issues `PATCH /api/v1/conversations/{id}` with an
  `UpdateConversationRequest` carrying only `archived` and expects `200`.
  Returns `Void`; the `200` body (`ConversationResponse`) is discarded — the
  view model removes the row regardless, so no `ConversationResponse` DTO is
  added on iOS.

`setArchived(archived:)` is named generically rather than `archiveConversation`
because the PATCH is symmetric; the view model only ever passes `true`
(unarchive UI is out of scope), but the client carries no archive-only
assumption.

### View-model layer — `ConversationListViewModel`

The `ConversationListState` enum is unchanged. `.failed` stays reserved for
initial-load failure (it replaces the whole screen). A separate published
channel carries action failures so the loaded list is never torn down:

```swift
@Published var actionError: ErrorResponse?
```

`ErrorResponse` is already `Identifiable` (`id == code`), so it drives an
item-style alert directly. Two intents are added:

```swift
func archive(_ conversation: Conversation) async
func delete(_ conversation: Conversation) async
```

Both follow one optimistic-with-rollback shape:

1. Guard `state` is `.loaded(let convos)` and the conversation is present
   (matched by `id`); capture its index. If absent, no-op.
2. Remove the row immediately: set `state` to `.loaded(remaining)`, or `.empty`
   when `remaining` is empty.
3. Call the client (`setArchived(conversationId:archived:true)` /
   `deleteConversation(conversationId:)`).
4. On success: nothing further (the row is already gone).
5. On failure: **roll back** — reinsert the conversation into the list as it
   currently stands at `min(originalIndex, currentCount)` (restoring `.loaded`
   from `.empty` if the removed row was the last), then set `actionError` to the
   thrown `ErrorResponse`, or a synthesized `SERVER_ERROR` for any
   non-`ErrorResponse` error (mirroring `load()`). Reinserting into the current
   list rather than overwriting with the pre-removal snapshot composes correctly
   if the list shape changed between removal and failure.

The view model is `@MainActor`, so these steps are serialized.

Delete confirmation is **not** the view model's concern — the intent fires only
after the view's confirmation dialog resolves.

### View layer — `ConversationListView`

Each row in `conversationList(_:)` gains, while keeping the existing
`NavigationLink` row behavior intact:

- `.swipeActions(edge: .trailing)` with a destructive **Delete** button
  (`role: .destructive`) and an **Archive** button.
- `.contextMenu` with the same two actions.
- The Delete buttons (swipe and menu) set a
  `@State var pendingDeletion:
  Conversation?` rather than deleting directly; a
  `.confirmationDialog` presented on that state confirms before invoking
  `viewModel.delete(_:)` in a `Task`.
- Archive buttons invoke `viewModel.archive(_:)` in a `Task` directly.
- An `.alert` presenting `viewModel.actionError` with a single dismiss button.

Labels use the design system per existing convention (system images +
`.dsBody`/`.dsCaption` already applied to row content). Accessibility
identifiers follow the existing camelCase scheme: `archiveButton`,
`deleteButton`, `deleteConfirmButton`, `deleteCancelButton`. The existing
`conversationRow` identifier and row tap-through are unchanged.

`ConversationListPreviewClient` (same file) must implement the two new protocol
methods as no-ops to compile.

View wiring (swipe actions, context menu, `pendingDeletion` confirmation dialog,
`actionError` alert) has no automated test — the suite contains no SwiftUI view
tests — and is verified by the manual smoke check in Implementation Plan step 8.

### Error handling and edge cases

- **Action failure (401/404/5xx/transport):** list rolled back, `actionError`
  set, screen otherwise intact. A `404 not_found` (already deleted/foreign)
  rolls back identically — the conversation reappears and the error explains
  why; no special-casing, consistent with `fetchMessages`.
- **Archiving/deleting the only row:** list transitions to `.empty`; rollback
  restores `.loaded([conversation])`.
- **Action on a stale row not in the loaded list:** guard no-ops; no client
  call.
- **Concurrent appearance reload:** out of scope to coordinate; `@MainActor`
  serialization plus the "reinsert into current list" rollback keeps state
  well-formed.

### Dependencies

No new packages. Depends only on the existing `APIClient`, `ErrorResponse`,
`Conversation`, and the in-place REST endpoints. No backend, REST, or migration
change.

### Cross-cutting compile impact

Adding methods to `ConversationClientProtocol` breaks every conformer; each is
enumerated in `Files Modified`. A protocol default-implementation extension is
deliberately avoided: it would silently no-op the real client if an
implementation were ever dropped.

## Tests

iOS tests run via `xcodebuild` (scheme `UnicoachiOS`), not `bin/test`.

### `MockConversationClient` (test seam additions)

Add recording and scriptable outcomes mirroring the existing `listConversations`
pattern:

- `setArchivedRequests: [(conversationId: UUID, archived: Bool)]`,
  `setArchivedError: Error?`.
- `deleteConversationRequests: [UUID]`, `deleteConversationError: Error?`.
- Each method records its invocation, then throws the scripted error if set,
  else returns. Call counts derive from the recorded arrays.

### `ConversationListViewModelTests`

- `testArchiveRemovesRowOptimistically` — loaded `[A,B,C]`; `archive(B)`; state
  is `.loaded([A,C])`; `setArchivedRequests == [(B.id, true)]`; `actionError`
  nil.
- `testArchiveFailureRollsBackAtOriginalIndex` — loaded `[A,B,C]`;
  `setArchivedError` set to `ErrorResponse(code:"not_found",…)`; `archive(B)`;
  state restored to `.loaded([A,B,C])` (B back at index 1);
  `actionError.code ==
  "not_found"`.
- `testDeleteRemovesRowOptimistically` — loaded `[A,B,C]`; `delete(C)`; state
  `.loaded([A,B])`; `deleteConversationRequests == [C.id]`.
- `testDeleteFailureRollsBackAndSurfacesError` — `deleteConversationError` set;
  `delete(A)`; state restored; `actionError` carries the error.
- `testArchiveLastConversationTransitionsToEmpty` — loaded `[A]`; `archive(A)`;
  state `.empty`.
- `testActionFailureFromEmptyRestoresLoaded` — loaded `[A]`; `setArchivedError`
  set; after `archive(A)` the terminal state is `.loaded([A])` (rolled back from
  the transient `.empty`); `actionError` is set.
- `testNonErrorResponseActionFailureSynthesizesServerError` — client throws an
  opaque (non-`ErrorResponse`) error; `actionError.code == "SERVER_ERROR"`; list
  restored.
- `testActionOnUnknownConversationNoOps` — loaded `[A,B]`; call `delete(_:)`
  with a conversation whose id is absent; state unchanged;
  `deleteConversationRequests` empty.
- `testActionFailureDoesNotEnterFailedState` — after an action failure, `state`
  remains `.loaded` (never `.failed`), proving the list survives.

### `ConversationClientTests`

Uses the existing `MockURLProtocol`. Add a private `resolvedBody` `URLRequest`
extension (as in `APIClientTests`/`StudentClientTests`) because `URLSession`
moves the body to `httpBodyStream` before a custom `URLProtocol` sees it.

- `testDeleteConversationSendsDeleteAndSucceedsOn204` — handler asserts
  `httpMethod == "DELETE"` and path `/api/v1/conversations/<id>`; returns `204`
  empty body; call does not throw.
- `testDeleteConversationThrowsDecodedErrorOnNon204` — returns
  `404
  {"code":"not_found",…}`; call throws `ErrorResponse` with
  `code == "not_found"`.
- `testSetArchivedSendsPatchWithArchivedOnlyBody` — handler asserts
  `httpMethod
  == "PATCH"`, path, and that `resolvedBody` decodes to
  `{"archived":true}` with **no** `name` key; returns `200` with a conversation
  body; call does not throw.
- `testSetArchivedThrowsDecodedErrorOnNon200` — returns
  `404
  {"code":"not_found",…}`; call throws the decoded `ErrorResponse`.

Transport-failure mapping (`TIMEOUT`/`NETWORK_ERROR`) is not re-tested here: the
new verbs delegate to the same `perform` already covered by `APIClientTests`.
The view-model's `SERVER_ERROR` synthesis for any non-`ErrorResponse` is covered
by `testNonErrorResponseActionFailureSynthesizesServerError`.

## Implementation Plan

Each step is locally compilable/testable. iOS verification uses `xcodebuild`
with scheme `UnicoachiOS` against an installed simulator.

1. **Transport verbs.** Add `delete(_:)` and `patch(_:body:)` to
   `ios-app/UnicoachiOS/APIClient.swift`, delegating to `perform`. _Verify:_
   `xcodebuild build -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 15'`.

2. **Request model.** Add `UpdateConversationRequest` to
   `ios-app/UnicoachiOS/Models.swift`. _Verify:_ build as in step 1.

3. **Protocol + client methods.** Add `deleteConversation`/`setArchived` to
   `ConversationClientProtocol` and implement them in `ConversationClient`
   (`ios-app/UnicoachiOS/ConversationClient.swift`). The build now fails on the
   four other conformers — expected; fixed next. _Verify:_ compile error set is
   limited to missing-conformance on the four conformers.

4. **Conformer fixes.** Add no-op implementations of the two methods to
   `ConversationListPreviewClient` (`ConversationListView.swift`),
   `ConversationPreviewClient` (`ConversationView.swift`),
   `HomePreviewConversationClient` (`HomeView.swift`), and recording
   implementations to `MockConversationClient`
   (`ios-app/UnicoachiOSTests/MockConversationClient.swift`). _Verify:_
   `xcodebuild build` and `build-for-testing` both succeed.

5. **Client tests.** Add the four `ConversationClientTests` cases plus the
   `resolvedBody` helper. _Verify:_
   `xcodebuild test -scheme UnicoachiOS -only-testing:UnicoachiOSTests/ConversationClientTests`.

6. **View-model intents.** Add `actionError`, `archive(_:)`, `delete(_:)`, and
   the rollback logic to `ConversationListViewModel.swift`. _Verify:_ build
   succeeds.

7. **View-model tests.** Add the `ConversationListViewModelTests` cases.
   _Verify:_
   `xcodebuild test -scheme UnicoachiOS -only-testing:UnicoachiOSTests/ConversationListViewModelTests`.

8. **View wiring.** Add swipe actions, context menu, `pendingDeletion`
   confirmation dialog, and the `actionError` alert to
   `ConversationListView.swift`. _Verify:_ full suite
   `xcodebuild test -scheme UnicoachiOS` passes; manual smoke: swipe → Archive
   removes the row; swipe → Delete confirms then removes; a forced failure keeps
   the list and shows the alert.

## Files Modified

Production (`ios-app/UnicoachiOS/`):

- `APIClient.swift` — add `delete(_:)` and `patch(_:body:)`.
- `Models.swift` — add `UpdateConversationRequest`.
- `ConversationClient.swift` — extend `ConversationClientProtocol`; implement
  `deleteConversation` and `setArchived`.
- `ConversationListViewModel.swift` — add `actionError`, `archive(_:)`,
  `delete(_:)`, rollback logic.
- `ConversationListView.swift` — swipe actions, context menu, confirmation
  dialog, action-error alert; update `ConversationListPreviewClient`.
- `ConversationView.swift` — update `ConversationPreviewClient` conformance
  only.
- `HomeView.swift` — update `HomePreviewConversationClient` conformance only.

Tests (`ios-app/UnicoachiOSTests/`):

- `MockConversationClient.swift` — recording/scripting for the two new methods.
- `ConversationClientTests.swift` — DELETE/PATCH client tests + `resolvedBody`
  helper.
- `ConversationListViewModelTests.swift` — archive/delete intent tests.
