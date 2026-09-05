# RFC 168 — The iOS college list says what it means

Product brief 0007 (`product/0007-explicit-profile-view`), slice
`profile/03/list-screen-parity` (spec.md:132-162), gate 2 approved 2026-09-05.
Unblocked by RFC 164 landing on `main` (`8989cfd0` + `112eb660`).

Audit evidence, produced for this RFC and carried into the brief's ledger:
`.scratch/ship/rfc-168/findings/research-server-capabilities.md` and
`research-ios-list-screen.md`.

## Motivation

RFC 164 fixed the wire contract: on `PATCH .../college-list/{id}` an omitted
`livingPlan` now **keeps** the stored override, `livingPlanClear: true`
**clears** it, and both together are a 400. The shipped iOS client was the
victim of the old contract and needs no change to stop losing data.

Two things are still wrong, and they are the subject of this slice.

**1. The client is safe by accident.** `UpdateCollegeListEntryRequest`
(`ios-app/UnicoachiOS/CollegeListModels.swift:78-82`) declares three properties
— `version`, `status`, `reasons` — and Swift's synthesized `Codable` happens to
emit exactly the body RFC 164 made safe. Nothing in the Swift source says _why_
the key is absent. A later tidy-up that adds `let livingPlan: LivingPlan?` would
look like an improvement and would still be safe; one that hand-wrote
`encodeNil` for it would re-arm the original defect. The server test that exists
to imitate this client says so in its own comment: "if that struct gains a key,
this string stops imitating the client it exists to imitate"
(`CollegeListRoutingTest.kt:431-462`). Correctness that depends on a synthesized
encoder and a comment in another language is not stated, it is inherited.

**2. The client cannot say "clear".** There is no way to produce
`{"livingPlanClear": true}` from the current struct. A per-college living plan
the coach set by mistake can be undone only by talking to the coach again. The
server has supported the operation since RFC 164; the app is mute.

The app already models this exact wire correctly once, for the money profile:
`UpdateMoneyProfileRequest` (`ios-app/UnicoachiOS/Models.swift:182-246`) carries
a three-state `MoneyProfileFieldUpdate` (`set` / `declined` / `clear`) and a
hand-written `encode(to:)`. This RFC makes the college list the second.

### What this RFC deliberately does NOT do

- **No living-plan picker.** Brief 0007 D3 and spec.md:153-157 put per-college
  living-plan _editing UI_ outside this brief. The transport becomes
  expressible; the screen gains no control.
- **No change to `reasons`.** `reasons` has the same shape as the old
  `livingPlan` defect and the opposite meaning: an omitted `reasons` clears the
  note, and that omission **is** the shipped client's Clear button. RFC 164 D4
  decided this asymmetry deliberately and guards it with a test
  (`CollegeListRoutingTest.kt:464-490`). This RFC preserves it, and the risk
  that a hand-written encoder quietly breaks it is the reason a test for it
  belongs here too.
- **No server behaviour change.** The only server file touched is a test
  comment, kept in step with the Swift struct it imitates.

## Detailed Design

### `LivingPlanUpdate` — a three-state client value

A new Swift enum in `CollegeListModels.swift`, mirroring the server's three
states and the money profile's precedent:

```swift
/// The three states `PATCH .../college-list/{id}` accepts for the per-college
/// living-plan override (RFC 164). `keep` emits NEITHER key: on the wire,
/// silence means "leave the stored value alone", and that is the whole reason
/// this type exists rather than a bare `LivingPlan?`.
enum LivingPlanUpdate: Equatable, Sendable {
    case keep
    case set(LivingPlan)
    case clear
}
```

`LivingPlan` already exists (`Models.swift:341-347`) with the three wire strings
`on_campus` / `off_campus` / `with_family`, matching the server's
`LivingArrangement`.

### The request body encodes itself

`UpdateCollegeListEntryRequest` gains the field and a hand-written
`encode(to:)`:

| State        | `livingPlan` key | `livingPlanClear` key |
| ------------ | ---------------- | --------------------- |
| `.keep`      | absent           | absent                |
| `.set(plan)` | `plan.rawValue`  | absent                |
| `.clear`     | absent           | `true`                |

`version` and `status` are always encoded. **`reasons` uses `encodeIfPresent`**
— a `nil` reasons omits the key, which is how the detail screen clears a note
today (`CollegeEntryDetailView.swift:132-141`). Writing the encoder by hand is
what makes both rules explicit instead of emergent.

`.set` and `.clear` are never emitted together, so the server's 400 for that
pair is unreachable from this client by construction.

### The client call site

`updateEntry` gains a `livingPlan: LivingPlanUpdate` parameter on the
`CollegeListClientProtocol` requirement, on `CollegeListClient`, on
`MockCollegeListClient` (and its captured-call tuple), and on the SwiftUI
preview stub in `AuthenticatedRootView`.

**Swift forbids a default argument on a protocol requirement**, and
`CollegeListViewModel` holds its client as `CollegeListClientProtocol`. So the
default lives in a protocol extension instead of on the declaration:

```swift
extension CollegeListClientProtocol {
    func updateEntry(id: UUID, version: Int, status: CollegeListStatus,
                     reasons: String?) async throws -> CollegeListEntry {
        try await updateEntry(id: id, version: version, status: status,
                              reasons: reasons, livingPlan: .keep)
    }
}
```

The net effect is the one the design wants: every existing call site —
`CollegeListViewModel.update`, the detail screen's Save — is unchanged source
AND byte-identical on the wire, and "say nothing about a field you do not
manage" is what a caller gets for free. The concrete types deliberately do NOT
also declare a four-argument overload, which would be ambiguous against this
extension.

`UpdateCollegeListEntryRequest` keeps a defaulted `livingPlan: .keep` on its own
memberwise `init`, where a default IS allowed, so constructing the body without
mentioning the field is equally safe.

### Why no UI, and how the capability is reachable

The slice's door is **the existing list screen** — that door already works and
this RFC does not move it. The user-visible outcome of the slice is that a Save
from that screen is provably non-destructive and that the app can express a
clear when a control for it is added. The clear is exercised by tests, not by a
control, exactly as the brief scoped it. A future slice that adds the picker
inherits a transport that is already correct and already tested.

## Files Modified

| File                                                                     | Change                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| ------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ios-app/UnicoachiOS/CollegeListModels.swift`                            | Add `LivingPlanUpdate`; add `livingPlan` to `UpdateCollegeListEntryRequest` with a hand-written `encode(to:)` (`encodeIfPresent` for `reasons`); narrow the struct from `Codable` to `Encodable` (nothing decodes a request body, and `LivingPlanUpdate` is deliberately not `Decodable`); document why `.keep` emits nothing, why the enum is not unified with `MoneyProfileFieldUpdate`, and that `.set`/`.clear` have no production caller by design. |
| `ios-app/UnicoachiOS/CollegeListClient.swift`                            | `updateEntry` gains `livingPlan: LivingPlanUpdate` on the protocol requirement and the implementation; a protocol extension supplies the `.keep` default (Swift forbids one on a requirement); comment states the three states.                                                                                                                                                                                                                          |
| `ios-app/UnicoachiOS/AuthenticatedRootView.swift`                        | The SwiftUI preview stub is a third conformer of the protocol; its `updateEntry` signature widens. Body unchanged.                                                                                                                                                                                                                                                                                                                                       |
| `ios-app/UnicoachiOS/Models.swift`                                       | Doc only: `LivingPlan` names its second consumer, the per-college override. No code change.                                                                                                                                                                                                                                                                                                                                                              |
| `ios-app/UnicoachiOSTests/MockCollegeListClient.swift`                   | Mock signature + captured-call tuple gain the parameter.                                                                                                                                                                                                                                                                                                                                                                                                 |
| `ios-app/UnicoachiOSTests/CollegeListClientTests.swift`                  | New body-shape assertions (below), over one shared `respondAssertingBody` helper.                                                                                                                                                                                                                                                                                                                                                                        |
| `ios-app/UnicoachiOSTests/CollegeListViewModelTests.swift`               | The existing restatus-Save test gains an assertion that the view model passes `.keep` — the claim belongs on the test that already exercises that path, not in a second test of the same call.                                                                                                                                                                                                                                                           |
| `rest-server/src/test/kotlin/ed/unicoach/rest/CollegeListRoutingTest.kt` | Two comments corrected to name the hand-written encoder, plus ONE new test asserting the server accepts the `.set` and `.clear` bodies **as the Swift encoder emits them** — raw strings, not DTO-built bodies, which carry keys the client never sends and so cannot catch client drift.                                                                                                                                                                |

No new Swift file, so **no `project.pbxproj` edit** — all six files are already
registered (`TESTING.md:31-33`). No DDL, no migration, no OpenAPI change, no
server behaviour change.

## Implementation Plan

1. Add `LivingPlanUpdate` to `CollegeListModels.swift`.
2. Add the field and hand-written `encode(to:)` to
   `UpdateCollegeListEntryRequest`; keep `version`/`status` unconditional and
   `reasons` `encodeIfPresent`.
3. Thread the defaulted parameter through the `CollegeListClient` protocol, its
   implementation, and `MockCollegeListClient`.
4. Widen `MockCollegeListClient`'s captured-call tuple. Its elements are
   labelled, so existing assertions keep compiling unchanged.
5. Add the tests below.
6. Update the server test's comment so the two surfaces stay in step.
7. `bin/test-ios` (directly, NOT under `nix develop`) and
   `nix develop -c bin/test`.

## Tests

Client tests (`CollegeListClientTests.swift`, `MockURLProtocol` + real client,
asserting on the actual request bytes — the pattern at `:86-104`):

- **`.keep` sends the RFC 164-safe body**: the JSON has `version`, `status`,
  `reasons`, and **neither** `livingPlan` nor `livingPlanClear`. This is the
  spec's "an iOS-shaped Save preserves a chat-set living plan", asserted at the
  byte level on the client side and already asserted end-to-end on the server
  side (`CollegeListRoutingTest.kt:431-462`).
- **`.clear` sends `livingPlanClear: true`** and no `livingPlan` key — the
  spec's "the client can send an explicit clear".
- **`.set(.onCampus)` sends `livingPlan: "on_campus"`** and no
  `livingPlanClear`.
- **`reasons: nil` still omits the key** in every one of the three states — the
  regression guard for RFC 164 D4's deliberate asymmetry.

View-model test (`CollegeListViewModelTests.swift`): the existing restatus-Save
test additionally asserts that the captured call's living-plan argument is
`.keep`, so the screen cannot start sending the key by accident.

Server (`CollegeListRoutingTest.kt`): one new test sends the `.set` and `.clear`
bodies **verbatim as the Swift encoder emits them** and asserts the stored
override is written and then cleared. It uses raw strings deliberately: a body
built from the server DTO also carries `livingPlanClear: false` and
`addObservationIds: []`, keys this client never sends, so it cannot catch the
client shape drifting. The rest of the suite is unchanged in behaviour and
re-run as the gate.

Executed counts (`bin/test-ios` and `nix develop -c bin/test`) go in the run
report.
