# RFC 171 — Your details: an explicit screen for the money profile

**Slice:** `profile/02/your-details-screen` (brief 0007, gate 2 approved
2026-09-05). The slice the brief calls its aha: a family can see and change
their household income band and state of residence **without talking to the
coach**.

Substrate already landed: RFC 134 (the `GET`/`PUT` money-profile surface), RFC
163 (`MoneyProfileClient`, `APIClient.put`, the Swift wire enums), RFC 165
(`GET /api/v1/vocabularies`).

## Problem

unicoach stores two facts that change every number it shows a family — the
household income band and the state of residence — and today there is exactly
one door to both: the chat. A family that wants to correct one wrong field has
to talk the coach into it, and there is nowhere to look at what unicoach
believes about them.

The server side is complete and needs no change.
`GET
/api/v1/students/me/money-profile` returns a per-field tri-state
(`unanswered` / `answered` / `declined`) and `404` before the first write; `PUT`
takes an absolute per-field statement — value, `*Declined`, or `*Clear` — and
returns the whole post-write profile
(`rest-server/.../routing/MoneyProfileRoutes.kt:46-120`,
`.../models/UpdateMoneyProfileRequest.kt:9-19`). There is no optimistic
concurrency and no version to hold, so a single field can be written without a
prior read (`service/.../moneyprofile/MoneyProfileService.kt:78-82`).

What is missing is entirely in the client. RFC 163 gave the app a **write-only**
`MoneyProfileClient` — its KDoc says so explicitly: "There is no read binding …
the read verb lands with the first screen that needs it"
(`ios-app/UnicoachiOS/MoneyProfileClient.swift:18-24`). This is that screen.

### Why a decline is not an empty field

The tri-state is not decoration. `PrecisionOffer` keys every rule off
`AnswerStatus.UNANSWERED`, never off a missing value
(`service/.../costs/CollegeCostService.kt:582-590, 631, 656, 693`), precisely so
that a decline is permanent:

- **declined** → the coach never raises that question again;
- **unanswered** (which is what a _clear_ produces) → the coach may invite the
  answer inside a cost answer;
- and declining residency also retires the **living-plan** invitation at public
  colleges, because that rule is gated on the same profile.

A cleared field and a never-asked field are byte-identical in the row
(`db/.../dao/MoneyProfilesDao.kt:167-179`) — there is no "was declined once"
memory. So clearing is the only undo, and a screen that showed a decline as an
empty field would let a family clear a question to stop being asked and get
asked _more_. Brief 0007 D5 is the answer: three states, always legible, both
controls, and the consequence named on the control that carries it.

## Decisions this RFC makes

Everything below is the slice text's "left to /ship's design phase". The product
decisions (D2–D8) are inherited, not re-opened.

### 1. Where it lives — a fifth `Destination`, one menu row (D2)

`AuthenticatedRootView`'s `Destination` enum grows a `yourDetails` case
(`:40-45`), built in `destination(_:)` (`:319-349`), reached from one new
`SlideOverMenu` footer row above "My colleges" — the same `menuRow` shape as the
other three (`SlideOverMenu.swift:179-218`). No tab bar, no re-architecture.

Row copy: **"Your details"**, icon `person.text.rectangle`, identifier
`yourDetailsButton`.

### 2. The vocabulary is served, and the shipped Swift lists become its fallback (D6)

The pickers render `GET /api/v1/vocabularies` (RFC 165): `income_bands` gives
the five bands with their dollar ranges as the coach says them, and
`residency_states` gives **59** codes — literally the set
`MoneyProfileService.parseResidencyState` accepts — each with its name, in the
server's display order. The client sorts nothing and invents nothing.

Two consequences worth stating:

- **The details screen offers 59 jurisdictions, not the 51 onboarding offers.**
  RFC 163 shipped a deliberately narrower reading menu (`ResidencyStates.swift`)
  on the reasoning that offering more than the server accepts is a 400 with no
  recourse. The served set removes that hazard by construction, so the screen
  offers everything the server takes and its field is labelled **"State or
  territory of residence"** — a menu that lists Guam must not call Guam a state.
  Onboarding is **not** changed by this run: its 51-item menu is RFC 163's
  product decision, and re-deciding it here would be scope creep. That leaves
  the app with one served vocabulary and one narrower onboarding menu; this is
  recorded as an open item, not silently absorbed.
- **`ResidencyStates.offered` and `IncomeBand.bracket` stay, as the offline
  fallback.** If the vocabulary fetch fails — or the served document yields no
  menu this build can render whole (a vocabulary absent, served empty, or
  carrying one entry this build cannot type) — the screen falls back to those
  lists **as a unit** rather than refusing to render a picker or rendering a
  partial one. A picker missing a band is worse than the shipped list: it is
  short while still claiming to be the server's. They are not a fourth unchecked
  copy: `ResidencyStateTableTests` already reads the server's set out of
  `MoneyProfileService.kt` and asserts membership, and this RFC adds the
  matching assertion for the band labels. The served document is authoritative;
  the local list is a degradation, and the screen says so.

### 3. Each field saves on its own, immediately (the "individually or together" question)

The `PUT` is an absolute per-field statement that returns the whole profile, so
a per-field write is both the smallest honest request and a complete read. Every
control — pick a value, decline, remove — sends exactly one field's key and
replaces the screen's state with the response. There is no Save button and no
dirty state to lose.

This matches Apple's settings convention (a change applies when made) and it is
what makes D5's "always undoable, both ways" cheap: three buttons, three writes,
no state machine.

One guard, invisible to the family: while a write for a field is open, a second
action on **that** field is dropped. Nothing is greyed out and the other field
is never blocked. The repeated request would be harmless — each is an absolute
per-field statement — but two responses adopting into the screen's state in an
unchosen order would not be.

### 4. The three states, and the two controls, per field

    Not answered            — "Not answered yet"
    Answered (value)        — the label, e.g. "$48,001 to $75,000"
    You chose not to say    — "You chose not to say"

Under each field, always visible and never greyed out:

- **"Prefer not to say"** — sends `*Declined: true`. Its caption names the cost:
  _"The coach stops asking about this."_ Hidden only when the field is already
  declined.
- **"Remove my answer"** — sends `*Clear: true`. Caption: _"Returns it to not
  answered. The coach may invite it again."_ Hidden only when the field is
  already unanswered.

A declined field keeps its picker fully live, so answering it outright is one
tap (declined → answered), and "Remove my answer" is offered on it too (declined
→ unanswered). D5's amendment, exactly: never a one-way door.

### 5. `404` is "nothing answered yet" (D4)

`fetch()` maps `404` to `nil` and the screen renders all-unanswered. The mapping
lives in the read verb alone, because `404` is overloaded on this resource: on
`GET` it is the benign pre-first-write state, on `PUT` it is a real fault
("Owning student not found"). The `code` is `not_found` for both; only the verb
distinguishes them (`MoneyProfileRoutes.kt:64, 88`).

`APIClient.decodeError` treats `400/401/404/409` identically
(`APIClient.swift:283-294`), so the read verb branches on `ErrorResponse.status`
— the `StudentClient.swift:26-31` precedent — rather than on the code string.

`409 student_profile_required` is routed to the existing `onProfileRequired`
closure, the `CollegeListViewModel` precedent — it is a routing signal, not an
error to show.

### 6. Saving says so, on the screen — and only there (D7)

A terse per-field receipt, **"Saved"**, appears beside the field it belongs to
and clears on the next edit or after a short delay. It is posted to VoiceOver as
an announcement. Nothing is said in chat, ever: the coach recomposes the money
block from the database on every turn (`CoachingService.kt:830-836, 872-901`)
and already names the basis it used in a cost answer (RFC 145/157), so there is
nothing to build and nothing to add to the prompt.

### 7. Failure never eats the answer

A failed write leaves the picker showing what the family chose, surfaces the
message in an alert (the `actionError` channel, `CollegeListViewModel.swift:40`
precedent), and offers retry. Only a `200` moves the screen's state. A failed
initial load renders `ErrorView` with retry; a failed _vocabulary_ load alone
falls back (decision 2) and the screen still works.

### 8. Never a gate (D8)

Reachable from the menu, never interposed. No onboarding form, no blocking, no
completion meter. The screen's footer links to **"My colleges"**, which keeps
its own screen and is not duplicated here (D3).

## Files Modified

**New — `ios-app/UnicoachiOS/`**

| File                         | What                                                                                                              |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `VocabularyClient.swift`     | `VocabularyClientProtocol` + `VocabularyClient`: `GET /api/v1/vocabularies` → `VocabulariesResponse`.             |
| `YourDetailsViewModel.swift` | `@MainActor ObservableObject`; load, three write actions per field, receipts, `actionError`, `onProfileRequired`. |
| `YourDetailsView.swift`      | The screen: two field sections, the controls, the receipt, the link to My colleges.                               |

**Modified — `ios-app/UnicoachiOS/`**

| File                                            | What                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `Models.swift`                                  | `VocabulariesResponse` / `PublicVocabulary` / `VocabularyEntry` (only `value` and `label` decoded; flattened extras such as `jurisdictionKind` tolerated and ignored), `VocabulariesResponse.Name` as a `String`-raw enum over a `String`-keyed map; `Sendable` on `MoneyProfileFieldValue` / `MoneyProfileFieldUpdate` / `UpdateMoneyProfileRequest`, because a `@MainActor` view model hands a locally built request to a nonisolated client and these are pure value types. |
| `MoneyProfileClient.swift`                      | Adds `fetch() async throws -> PublicMoneyProfile?` — the read verb, `404` → `nil` via `APIClient.getIfPresent`.                                                                                                                                                                                                                                                                                                                                                                |
| `AuthenticatedRootView.swift`                   | `Destination.yourDetails`, its `destination(_:)` arm, the `onYourDetails` closure, client wiring, preview double.                                                                                                                                                                                                                                                                                                                                                              |
| `SlideOverMenu.swift`                           | One `yourDetails` footer row + its property/init parameter.                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `ResidencyStates.swift`                         | `init?(served: VocabularyEntry)` — construction from a code the server itself published, in the two-letter USPS shape this type declares, so the screen offers all 59 served jurisdictions while free text stays unrepresentable.                                                                                                                                                                                                                                              |
| `OnboardingView.swift`                          | Preview double gains `fetch()` from the widened `MoneyProfileClientProtocol`; onboarding never reads. Its private `captioned` helper is replaced by the shared `DSCaptioned`.                                                                                                                                                                                                                                                                                                  |
| `APIClient.swift`                               | `getIfPresent(_:)` — the `GET` whose `404` is an answer, not a fault. Read-verb only and opt-in per call site, so a `PUT`'s `404` still throws.                                                                                                                                                                                                                                                                                                                                |
| `StudentClient.swift`                           | `fetchProfile()` routed through `getIfPresent`, deleting the second hand-written copy of the same rule.                                                                                                                                                                                                                                                                                                                                                                        |
| `DesignSystem/Components.swift`                 | `DSCaptioned` — the control-plus-caption shape `OnboardingView` and `YourDetailsView` both need, extracted rather than copied. The `sm` gap is the component's whole point.                                                                                                                                                                                                                                                                                                    |
| `UnicoachiOSApp.swift`, `AppViewModel.swift`    | `VocabularyClient` construction and injection, the existing client pattern.                                                                                                                                                                                                                                                                                                                                                                                                    |
| `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` | Four entries per new file (`PBXBuildFile`, `PBXFileReference`, group child, sources phase), IDs prefixed `AB71` per house convention.                                                                                                                                                                                                                                                                                                                                          |

**Modified — `ios-app/UnicoachiOSTests/`**

| File                                    | What                                                                                                                                                                                                                                                                                                                                       |
| --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `MockMoneyProfileClient.swift`          | Adds the `fetch` result + call count.                                                                                                                                                                                                                                                                                                      |
| `MockVocabularyClient.swift` (new)      | Per-method `Result` + capture, the house mock shape.                                                                                                                                                                                                                                                                                       |
| `YourDetailsViewModelTests.swift` (new) | See Tests.                                                                                                                                                                                                                                                                                                                                 |
| `IncomeBandLabelTests.swift` (new)      | The band-label fallback asserted against `IncomeBand.kt`, the `ResidencyStateTableTests` precedent.                                                                                                                                                                                                                                        |
| `VocabularyClientTests.swift` (new)     | The endpoint binding over a real `URLSession` round trip: the `/api/v1/vocabularies` path, the served order, flattened extras ignored. Plus `VocabularyNameTests`, which reads the two vocabulary names out of `VocabularyService.kt` so a server-side rename fails a test instead of silently degrading the screen to the fallback lists. |
| `MoneyProfileClientTests.swift`         | The read verb: `GET` path and decode, `404` → `nil`, and a `404` on the `PUT` still throwing.                                                                                                                                                                                                                                              |
| `SnapshotScenes.swift`                  | The screen's scenes, and the re-composed `SlideOverMenu` scene with its new row.                                                                                                                                                                                                                                                           |

**No server change. No migration. No new table** — brief 0007 G4 holds.

## Implementation Plan

1. **Read verb.** `MoneyProfileClient.fetch()` + its mock and client test. The
   `GET` + `404` → `nil` + decode shape lands once, as `APIClient.getIfPresent`,
   and is chosen **per call site** — `StudentClient` routes through it too,
   while the `PUT` keeps throwing on `404`. Each call site keeps its own comment
   on why a `404` is benign on that read.
2. **Vocabulary client and models.** `VocabularyEntry` decodes `value` and
   `label` only — the two keys the screen renders; every flattened extra,
   `jurisdictionKind` included, is ignored rather than fatal. The screen's
   answer to "do not call Guam a state" is its own field label ("State or
   territory of residence"), not a per-entry kind.
3. **View model.** State enum with a distinct `.empty`-equivalent (all
   unanswered is a legitimate loaded state, not empty); `load()` fetches the
   profile and the vocabulary concurrently and degrades on the vocabulary alone;
   `answer/decline/remove` per field.
4. **View.** Two sections built from `DSPickerRow` (optional init, colourless
   "Select"), `DSCaptioned` under each control — the shape extracted out of
   `OnboardingView` into `DesignSystem/Components.swift` and shared by both
   screens — `ErrorView` on load failure, `.alert(item:)` on action failure, the
   footer link row.
5. **Wiring.** `Destination` case, `destination(_:)` arm, menu row, app-level
   client construction, preview doubles.
6. **`project.pbxproj`** — four lines per new file. Nothing validates
   membership; an unregistered file silently never compiles, so this step is
   verified by a clean build, not by inspection.
7. **Snapshots.** Register the scenes; regenerate and eyeball.
8. **`bin/test-ios`** (system Xcode, NOT under `nix develop`), then
   `nix develop -c bin/test` for the JVM tree, which this run does not touch but
   the hook runs anyway.

## Tests

`ios-app/UnicoachiOSTests/YourDetailsViewModelTests.swift`,
`@MainActor
XCTestCase` + protocol mocks + `makeViewModel()`
(`CollegeListViewModelTests.swift:4-19` precedent):

1. `404` on load renders **both fields as not answered**, and is not an error.
2. A loaded profile renders answered / declined / unanswered correctly per
   field, and a declined field carries no value.
3. Picking a band sends exactly `incomeBand` — no other key — and adopts the
   response.
4. "Prefer not to say" sends `incomeBandDeclined: true` (and
   `residencyDeclined: true`, the key whose name drops `State`) and the field
   reads "You chose not to say".
5. **Undo, both directions, from declined**: declined → answered by picking a
   value; declined → unanswered by "Remove my answer", asserting
   `incomeBandClear: true` on the wire.
6. A failed save **keeps the user's selection**, surfaces the error, and leaves
   the stored state untouched; a retry succeeds.
7. `409 student_profile_required` calls `onProfileRequired` and does not render
   an error.
8. A failed vocabulary fetch still renders both pickers from the fallback lists,
   and the screen says the list may be incomplete. So does a served vocabulary
   that yields no complete menu — an empty entry list, an income band this build
   cannot type, or a residency code that is not the two-letter USPS shape — each
   of which falls back **whole**, never to a partial picker.
9. Writing one field never sends the other field's keys (the subset-write
   guarantee the server documents).

`IncomeBandLabelTests` asserts the fallback `IncomeBand.bracket` copy equals the
`bracket` strings in `db/.../models/IncomeBand.kt`, read out of the Kotlin
source — the `ResidencyStateTableTests` pattern, so the fallback cannot drift
past the server in silence.

Snapshot scenes: not answered, one answered one declined, and the load-failure
state.

**Executed gates:** `bin/test-ios` (system Xcode) and `nix develop -c bin/test`.
