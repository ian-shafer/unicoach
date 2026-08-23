# RFC 119: The iOS subscription rail and the coaching-usage surface

## Summary

This RFC builds the iOS client's **purchase rail** for cost-metered paid
subscriptions
([`features/paid-subscriptions.md`](../features/paid-subscriptions.md)):
StoreKit 2 product fetch and purchase, a transaction listener, Restore
Purchases, the two API clients for the landed backend endpoints, and a
"Subscription" section in Settings carrying an abstract **coaching-used** meter.

It is the first of the two slices the brief's `paywall-ios` node calls for. The
second (RFC 120) adds the **gate**: intercepting the `coaching_budget_exhausted`
402 from the four turn endpoints and presenting the block/paywall screen. This
split is the brief's own recommendation ("Prefer to split into a
StoreKit-purchase slice and the paywall UI, run in that order") and it holds up:
the rail is what a student uses to _buy_, the gate is what _forces_ them to.
Each is independently shippable and independently testable, and the rail must
exist before the gate has anywhere to send anyone.

**No server change.** Every backend surface this needs is landed and specified
in `api-specs/openapi.yaml`.

## Where RFC 117 and DESIGN.md §7 are stale

`SettingsView`'s doc comment and `ios-app/DESIGN.md` §7 both state that
subscription status and coaching usage are "not buildable today — the server
exposes no GET for subscription state and no usage endpoint of any kind."
[RFC 117](117-chat-first-navigation.md) records the same deferral.

**That is no longer true, and was already untrue when RFC 117 landed.** RFC 109
shipped `GET /api/v1/students/me/coaching-usage`
(`rest-server/.../routing/CoachingUsageRoutes.kt`, `openapi.yaml` line 830) and
RFC 110 shipped `POST /api/v1/subscriptions/verify`
(`rest-server/.../routing/SubscriptionRoutes.kt`); both landed before RFC 117.

Per `rfc/README.md` the committed RFC 117 file is left exactly as it is — the
correction lands here and in the code. This RFC updates the two **live**
documents (`DESIGN.md` §7 and the `SettingsView` doc comment) to describe what
is now built. The half of the deferral that remains true is narrower and stated
plainly: there is still **no GET for subscription state**, and that shapes the
design below.

## Detailed Design

### The server is the authority; the client renders and never derives

The brief's first design principle is that the server is the entitlement
authority. Concretely, in this client:

- **The client never computes entitlement.** It never uses
  `PublicSubscription.status`, never compares `currentPeriodEnd` to now, and
  never consults StoreKit's `Transaction.currentEntitlements` **to decide
  whether coaching is allowed**. The only entitlement truth it displays is
  `CoachingUsage.exhausted` and `usedPercent`, straight from the server, which
  reads the same `Entitlement` the four turn gates read.

  `status` _is_ read for two presentation questions — which status line to show,
  and whether to offer a purchase — because the Settings section below cannot be
  built without them. That is display, not entitlement: getting it wrong shows a
  student the wrong words or an extra button, never coaching they have not paid
  for. The ban is on the entitlement input, not on the field.
- **StoreKit is a payment rail, not a source of truth.** Its transactions exist
  to be handed to `/verify`. `Transaction.currentEntitlements` is used only to
  enumerate JWSs worth posting (Restore), never to unlock anything locally.
- **The abstraction is preserved.** The client displays a percentage and a reset
  date. It never sees, stores, or renders dollars, tokens, model names, or the
  budget ratio — the server does not send them, and this RFC adds no field that
  would.

### There is no GET for subscription state — `/verify` is the read

The server exposes no endpoint answering "what subscription does this student
have?". The only response carrying a `PublicSubscription` is
`POST
/api/v1/subscriptions/verify`, which is documented as idempotent and as
"the idempotent refresh path" (`openapi.yaml`).

So the client learns subscription status by **re-posting the current
entitlement's JWS to `/verify`**. This is not a workaround: `/verify` is the
binding call, and re-posting is exactly what the backend intends. It runs when
the subscription surface is opened and when StoreKit delivers a transaction
update — not on every foreground, which would be chatty for no gain now that
renewals arrive server-side via the RFC 112 webhook.

A student with no StoreKit entitlement has no JWS to post, so the client shows
the free-tier state from `coaching-usage` alone. That is correct and needs no
call.

### Models (`Models.swift`)

```swift
struct SubscriptionVerifyRequest: Codable { let signedTransaction: String }
struct SubscriptionVerifyResponse: Codable { let subscription: PublicSubscription }

struct PublicSubscription: Codable {
    /// Raw wire string, not a Swift enum — see below.
    let status: String
    let productId: String
    let currentPeriodEnd: Date
}

struct CoachingUsageResponse: Codable { let usage: CoachingUsage }

struct CoachingUsage: Codable {
    let usedPercent: Int      // 0...100, floored and capped server-side
    let exhausted: Bool       // usedPercent == 100 iff exhausted
    let resetsAt: Date?       // nil == free lifetime allowance; explicit null on the wire
}
```

`status` decodes as `String` with a companion optional accessor, mirroring the
existing `ErrorResponse.knownCode` precedent:

```swift
enum SubscriptionStatus: String { case active, expired, grace, revoked
                                  case billingRetry = "billing_retry" }
extension PublicSubscription { var knownStatus: SubscriptionStatus? { .init(rawValue: status) } }
```

A bare `enum: String, Codable` property would **throw on decode** the day the
server adds a status, turning a display concern into a hard failure of the whole
response. The server's enum is closed today; the client's tolerance costs one
line. This is display-only either way — it never gates anything.

`Date` decoding needs no work: `APIClient`'s decoder already handles ISO-8601
with and without fractional seconds, and `resetsAt` is a plain `Date?`.

New `ServerErrorCode` cases for codes this surface must distinguish:
`subscriptionNotFound = "subscription_not_found"`,
`subscriptionOwnedByOtherAccount = "subscription_owned_by_other_account"`,
`validationFailed = "validation_failed"`,
`payloadTooLarge = "payload_too_large"`, and `decodeError = "DECODE_ERROR"` —
client-synthesized, never sent by a server, for a body that will not decode
_after_ the expected status. (`serviceUnavailable`, `unauthorized`,
`emailNotVerified` already exist.)

### Two clients: the purchase rail and the meter

```swift
protocol SubscriptionClientProtocol: Sendable {
    func verify(signedTransaction: String) async throws -> PublicSubscription
}
protocol CoachingUsageClientProtocol: Sendable {
    func fetchUsage() async throws -> CoachingUsage
}
```

Both are thin `APIClient` wrappers in the established shape of `StudentClient`
(protocol + `@unchecked Sendable` class + `Logger` +
`apiClient.decode(...,
expectedStatus:)`), so both mock cleanly through
`MockURLProtocol`.

**Three concepts, not two endpoints.** The server models three distinct things,
and the client is right to mirror the concepts rather than the URLs:

| concept               | server owner                        | who has one       |
| --------------------- | ----------------------------------- | ----------------- |
| the coaching meter    | `coaching/budget/BudgetService`     | **every** student |
| the subscription bind | `subscriptions/SubscriptionService` | purchasers only   |
| the student profile   | `student/StudentService`            | every student     |

`CoachingUsageRouteHandler` takes `BudgetService` and uses `StudentService`
**only to resolve the caller**; `StudentRoutes.kt` contains no reference to
budget or usage at all. So the `/api/v1/students/me/` prefix is _caller
scoping_, not ownership — the same shape as `/students/me/college-list`, which
is likewise not a property of the profile.

That decides both alternatives on correctness, not convenience:

- **Not on `StudentClient`.** The URL prefix suggests it, but adopting it would
  import an ownership claim into the client that the server itself does not
  make: it would assert the meter is part of the student profile. It is not — it
  is a projection of `Entitlement`, whose basis is the free allowance or a
  subscription, and which changes on every LLM call while the profile sits
  still. Two things with different rates of change and different owners do not
  belong behind one type.
- **Not one `SubscriptionClient` holding both.** The meter is defined for a
  student who has never subscribed and never will — that is the
  `resetsAt ==
  nil` free-tier branch, and it is what the paywall reads at its
  most common moment. Filing it under "subscription" would name the majority
  case after the minority one.

The app's existing three clients track resource families rather than route files
(six route files, three clients; `CollegeListRoutes.kt` has no client), so no
convention forces the answer — the domain does. `SubscriptionViewModel`
consuming two protocols is the honest consequence of consuming two concepts, not
a cost to be minimised.

`X-Unicoach-Client-Key` and the session cookie are already applied centrally by
`APIClient`/`ClientKey`, so neither client does anything special for auth.

### The StoreKit seam

StoreKit 2 is wrapped behind a protocol so every view model is testable without
a StoreKit environment, in the same spirit as RFC 113's `SsoSignInProviding`:

```swift
struct StoreProduct: Sendable, Equatable { let id: String; let displayName: String; let displayPrice: String }
struct StoreTransaction: Sendable, Equatable { let id: UInt64; let productID: String; let jws: String }
enum PurchaseResult: Sendable {
    case purchased(StoreTransaction)
    case userCancelled
    case pending        // Ask to Buy
    case unverified     // StoreKit could not verify the signature
    case unavailable    // no such product in the store
    case unrecognized   // a StoreKit result this build does not know
}

protocol SubscriptionStoreProtocol: Sendable {
    func product(id: String) async throws -> StoreProduct?
    func purchase(productID: String) async throws -> PurchaseResult
    func currentEntitlements() async -> [StoreTransaction]
    func sync() async -> RestoreResult    // .synced | .userCancelled | .failed
    func transactionUpdates() -> AsyncStream<StoreTransaction>
}

/// Finishing is a SEPARATE protocol, and `TransactionRecorder` is the only type
/// ever handed one. The view model holds a `SubscriptionStoreProtocol`, which
/// has no `finish` member at all — so "only the recorder finishes" is enforced
/// by the type system rather than by a comment nobody re-reads.
protocol TransactionFinishing: Sendable {
    func finish(_ transaction: StoreTransaction) async
}
```

`StoreKitSubscriptionStore` is the concrete implementation. Two rules inside it:

- **Only `.verified` results escape.** `VerificationResult.unverified` is
  dropped with a log and never becomes a `StoreTransaction`. The server would
  reject it anyway; not sending it keeps a forged payload out of the request
  path entirely.

  This refusal is **not** left inside a StoreKit-typed method where no test can
  reach its refusal arm. The decision is a pure mapping —
  `enum StoreKitVerdict { case verified(id:jws:); case unverified(id:) }`, whose
  refused case **carries no JWS at all**, so an unverified payload has no
  representation that could reach `/verify` — stated once and unit-tested
  directly, with the StoreKit method doing nothing but translate a
  `VerificationResult` into it. A guard whose refusing branch no test executes
  can be inverted without turning the suite red, and this one gates forged
  payloads.

- **`jws` is `VerificationResult.jwsRepresentation`** — the exact signed blob
  the server re-verifies. The client parses none of it.

`displayPrice` is StoreKit's **localized** price string and is the only price
ever shown. The server's `priceUsd = 9.99` is a budget input, not display copy;
rendering it would show the wrong currency and violate App Review guidelines.
The product identifier is `coach.uni.UnicoachiOS.monthly10`, matching
`service.conf`'s single configured plan, declared once as a constant.

### `TransactionRecorder` — the finish policy is one type, used by all callers

An unfinished StoreKit transaction is redelivered on every launch. Finishing one
the server never recorded loses a paid purchase. That policy is the correctness
core of this feature, and it has **three** callers: a fresh purchase, Restore,
and the background transaction listener.

So it is not a step written into each of those flows. It is one type that owns
it end to end:

```swift
/// The cause, kept as it actually arrived. A transport failure is NOT dressed up
/// as a server error: inventing a wire code no server sent is a sentinel, and it
/// destroys the real cause the log needs.
enum RecordFailure: Sendable {
    case server(ErrorResponse)
    case transport(Error)
}

enum RecordOutcome: Sendable {
    case recorded(PublicSubscription)   // server holds it; transaction finished
    case rejected(RecordFailure)      // permanently un-recordable; finished
    case deferred(RecordFailure)      // transient; NOT finished, will redeliver
}

/// The seam the view model and the listener consume. The RFC's own test plan
/// mocks the recorder, and an `actor` cannot be mocked — hence a protocol.
protocol TransactionRecording: Sendable {
    func record(_ transaction: StoreTransaction) async -> RecordOutcome
}

actor TransactionRecorder: TransactionRecording {
    init(client: SubscriptionClientProtocol, store: SubscriptionStoreProtocol)
    func record(_ transaction: StoreTransaction) async -> RecordOutcome
}
```

The "nothing else calls `store.finish`" rule widens with the protocol to "no
conformer calls it": the production conformer is the actor, and the test
conformer is a recording mock that owns no store.

`record` posts the JWS, maps the answer to an outcome, calls `store.finish` on
exactly the `recorded` and `rejected` arms, and returns. Nothing else in the app
calls `store.finish` — a rule cheap to check by grep and cheap to keep.

Splitting the outcome three ways rather than returning the raw error is the
point: `deferred` vs `rejected` **is** the finish decision, named. A caller
cannot re-derive it wrongly because it never sees the status code.

An `actor` because renewals arrive on the listener while a purchase or Restore
is in flight; serialising `record` means the same transaction cannot be posted
and finished twice concurrently.

**Finish only when the server has reached a terminal answer:**

| Outcome of `/verify`                             | arm        | why                                                                                              |
| ------------------------------------------------ | ---------- | ------------------------------------------------------------------------------------------------ |
| 200                                              | `recorded` | recorded; the row is Apple truth                                                                 |
| 200 whose body will not decode                   | `rejected` | the server recorded it, so retrying forever cannot help — finish, and say nothing to the student |
| 400 `validation_failed`                          | `rejected` | malformed JWS — will never succeed                                                               |
| 409 `subscription_owned_by_other_account`        | `rejected` | permanent, first-writer-wins; retrying cannot change it                                          |
| 503 `service_unavailable`                        | `deferred` | Apple unreachable, or credentials unset — transient                                              |
| 404 `subscription_not_found`                     | `deferred` | environment mismatch (sandbox JWS vs production API) — config, not the purchase                  |
| 401 / 403 / 409 `student_profile_required` / 500 | `deferred` | session or server problem, unrelated to the purchase                                             |
| transport failure                                | `deferred` | never reached the server                                                                         |

Not finishing costs a re-post on the next launch, which is idempotent and cheap.
Finishing wrongly costs a customer's money. The asymmetry decides it.

The policy switch is **exhaustive over `ServerErrorCode` with no `default:`**,
so a new server code cannot inherit a finish decision by falling through — it
fails to compile until someone chooses. An unrecognized (`nil`) code still
defers, and logs the code it did not recognise.

Finishing is safe on the `rejected` arm specifically because StoreKit 2's
`Transaction.currentEntitlements` keeps returning an active entitlement after
`finish()` — so if the student later signs into the account that owns it,
Restore Purchases still finds it.

### `SubscriptionViewModel`

`@MainActor`, `ObservableObject`, injected with the usage client, the store, and
the recorder. It owns **presentation**: what to show, what to say, what is
loading. It does not own the finish policy and never touches `store.finish`.

```swift
@Published private(set) var usage: CoachingUsage?
@Published private(set) var subscription: PublicSubscription?
@Published private(set) var product: StoreProduct?
@Published private(set) var phase: Phase        // .idle .loading .purchasing .restoring
@Published private(set) var notice: Notice?      // .informational | .failure
@Published private(set) var usageUnavailable: Bool  // a finished load left no reading
```

- `load()` — fetch usage and the product concurrently; then, if
  `currentEntitlements()` holds one of **this app's** products, `record` the
  newest to refresh the bound subscription. A StoreKit or product-fetch failure
  degrades to "the meter without a Subscribe button", never an empty screen:
  usage is the part that always works. If usage cannot be read at all,
  `usageUnavailable` renders a plain "unavailable right now" line — the promise
  of "always works" is kept by saying so, not by rendering nothing. A failed
  _refresh_ keeps the last reading on screen.
- `subscribe()` — `purchase` → on `.purchased`, hand the transaction to `record`
  → refresh usage. `.userCancelled` returns to `.idle` **silently** (a cancel is
  not an error and must not raise a banner). `.pending` (Ask to Buy) shows a
  neutral "waiting for approval" message; the listener picks it up when it
  resolves.
- `restore()` — `sync()`, then `record` every entitlement, then refresh usage.
  Reports "nothing to restore" when the set is empty, so the button is never a
  silent no-op.

`Notice` is one field with two arms rather than two optionals, so "a notice is
either informational or a failure, never both" holds by construction. Ask to Buy
`.pending` and "nothing to restore" are **informational** — neither is an error,
and rendering them in the error banner would tell a student something went wrong
when nothing did. Only `.failure` reaches `FormErrorBanner`.

Error copy is derived from the `ErrorResponse` carried by `rejected`/`deferred`,
defaulting to a generic message for an unrecognized code. The three with their
own words are `service_unavailable` ("your purchase is safe, we'll finish
setting it up"), `subscription_owned_by_other_account` ("already linked to
another Unicoach account"), and `subscription_not_found` ("we couldn't confirm
this with the App Store").

### The transaction listener runs for the whole authenticated session

`AuthenticatedRootView` starts one `Task` on appear that drains
`transactionUpdates()` and hands each transaction to the **same**
`TransactionRecorder`; it is cancelled on logout. It lives at the authenticated
root, not in `SubscriptionViewModel`, because a renewal must be recorded whether
or not Settings is on screen — but it does not run unauthenticated, when
`/verify` could only 401.

The recorder is therefore created once at the authenticated root and injected
into both the listener and the view model, so one instance serialises every
path.

**The rail is built by the composition root, not by the view.** `AppViewModel` —
a `@StateObject` created once for the app's lifetime, already the owner of
`authClient`, `conversationClient` and `studentClient` — owns the usage client,
the store and the recorder. `AuthenticatedRootView` receives all three and
constructs none of them.

This is not a stylistic preference. A SwiftUI `View` is a struct whose `init`
re-runs on every publish of the state it observes, while its `@StateObject`
survives. Building the rail in the view's `init` therefore rebuilds the store
and recorder on every render while the view model keeps the _first_ pair: the
listener and the view model drift onto different rails, and the surviving
store's `pending` registry is empty, so `finish()` silently no-ops and a paid
purchase is never finished. The single-instance rule the recorder depends on can
only be kept somewhere that is itself created once.

### `UsageMeter` — the one piece of new visual language

There is no meter or progress component in the design system today. DESIGN.md
§8's rule is "extrapolate from tokens; invent no new visual language", so:

- A track: `RoundedRectangle(cornerRadius: DSRadius.control)` with a 1pt
  `dsFieldBorder` stroke — the same hairline that separates every other surface.
  `UsageMeter` takes **plain values** — `usedPercent: Int`, `exhausted: Bool`,
  `resetsAt: Date?` — and never references `CoachingUsage`. A design-system
  component that imports a wire DTO couples the app's visual vocabulary to the
  shape of one endpoint's JSON; the caller unpacks.

- A fill: `brandAccent`, inset in the track, width proportional to `usedPercent`
  via `GeometryReader`. `brandAccent` as a selection/indicator fill is already
  sanctioned by `OptionCard`'s radio and `SegmentedSelector`.
- Flat. No shadow, no gradient — `DSGradient.brand` is chrome-only, and a meter
  is not chrome.
- **The exhausted state does not repaint the bar red.** DESIGN.md §6 makes error
  UI _outlined_, not a tinted wash. Exhaustion is carried by the caption
  (`dsError`, outlined treatment) and the label, so the meter reads the same in
  both states and only the words change.

The meter carries a **label row**: "Coaching used" leading, `NN%` trailing, both
`dsCaption`. The percentage belongs here and **only** here — a bar draws a
proportion but never states it, and a student reading "you are out" deserves the
number.

Caption text, below the track: `resetsAt != nil` → "Resets \(formatted date)";
`resetsAt == nil` → "One-time free allowance". Exactly those strings, with no
percentage repeated into them. This is the brief's "budget spent, resets on
⟨date⟩" copy, and the null branch is why `resetsAt` had to stay optional rather
than being defaulted client-side.

Accessibility: the meter is **one** element, not four —
`accessibilityElement(children: .ignore)` with
`accessibilityLabel("Coaching used")`,
`accessibilityValue("\(usedPercent) percent used")`, and the caption appended as
the hint. Left as separate elements, VoiceOver reads the percentage twice (once
from the label row, once from the value) and then the caption, which is why the
percentage appears in exactly one visible place.

`usedPercent` is used as the server sends it — the RFC states the 0...100 cap as
a server guarantee and the client does not re-derive it. The only bound is in
the geometry, where a fraction is clamped to `0...1` so no arithmetic can draw a
fill outside its track.

A new `DSControl.meterHeight` token is added rather than a literal, so the diff
review for magic numbers stays mechanical.

### Settings gains a "Subscription" section

`SettingsView` already composes as a stack of `dsOverlineStyle()` sections
awaiting exactly this, so the section drops between `appearanceSection` and the
button stack with no restructuring:

- the `UsageMeter` and its caption;
- a status line when a subscription is bound. A status line is only well-defined
  if **every** member of the status vocabulary has words, so the matrix is
  specified here rather than discovered in the implementation:

  | `knownStatus`           | line                                 |
  | ----------------------- | ------------------------------------ |
  | `active`                | `Monthly · renews <date>`            |
  | `grace`, `billingRetry` | `Monthly · payment issue · retrying` |
  | `expired`               | `Monthly · ended <date>`             |
  | `revoked`               | `Monthly · refunded`                 |
  | `nil` (unrecognized)    | `Monthly · <date>`                   |

  `<date>` is `currentPeriodEnd`, abbreviated and date-only. It is
  **displayed**, never compared to now.

- `LoadingButton` primary — "Subscribe \(product.displayPrice)/month" — shown
  unless the bound subscription is `active`. Only `active` suppresses it: a
  subscription in `grace` or `billingRetry` is failing to bill, and hiding the
  purchase path at exactly that moment strands the student with no in-app way
  forward;
- "Restore Purchases", secondary.

**The slide-over menu gets no new row.** `SlideOverMenu` must not scroll and
already degrades its recents 3→2→1→0 under large Dynamic Type (`ViewThatFits`);
a fourth footer row spends that budget for a screen reachable in one more tap.
Settings is the home.

### Xcode project

- New `.swift` files must be added to `project.pbxproj` by hand (no synchronized
  folders in this project).
- **No entitlements change.** In-App Purchase requires no key in
  `UnicoachiOS.entitlements`; it is enabled on the App ID.
- A `UnicoachiOS.storekit` **StoreKit configuration file** is added and wired to
  the scheme, so purchase flows run in the simulator without App Store Connect —
  which is what makes the visual gate and manual testing possible at all before
  the product is live.
- **Prerequisite to real revenue, not to landing:** the product
  `coach.uni.UnicoachiOS.monthly10` must exist in App Store Connect and the Paid
  Apps agreement must be active. Neither blocks this RFC, which is testable
  against the local StoreKit configuration.

## Files Modified

**Added — sources (`ios-app/UnicoachiOS/`)**

- `SubscriptionClient.swift` — `SubscriptionClientProtocol` +
  `SubscriptionClient`
- `CoachingUsageClient.swift` — `CoachingUsageClientProtocol` +
  `CoachingUsageClient`
- `SubscriptionStore.swift` — the seam: `StoreProduct`, `StoreTransaction`,
  `PurchaseResult`, `SubscriptionStoreProtocol`
- `StoreKitSubscriptionStore.swift` — the StoreKit 2 implementation
- `TransactionRecorder.swift` — `RecordOutcome` + the finish policy
- `SubscriptionViewModel.swift`
- `SubscriptionSection.swift` — the Settings section view
- `DesignSystem/UsageMeter.swift`
- `Locked.swift` — one small `Locked<Value>` box, replacing five hand-rolled
  `NSLock` + `withLock` copies across the store and the mocks

**Added — tests (`ios-app/UnicoachiOSTests/`)**

- `SubscriptionClientTests.swift`, `CoachingUsageClientTests.swift`,
  `TransactionRecorderTests.swift`, `SubscriptionViewModelTests.swift`,
  `StoreKitVerdictTests.swift`, `SubscriptionStoreMocks.swift`
- `StoreKitConfigurationTests.swift` — pins the `.storekit` catalogue's product
  id to `SubscriptionProduct.monthlyIdentifier`. The id is otherwise a silent
  third copy, and a mismatch breaks every purchase with no failing test

**Added — project**

- `ios-app/UnicoachiOS.storekit`

**Modified**

- `Models.swift` — the DTOs above; four new `ServerErrorCode` cases
- `AppViewModel.swift` — the composition root gains the usage client, the store
  and the recorder
- `UnicoachiOSApp.swift` — passes the rail to `AuthenticatedRootView`
- `SettingsView.swift` — the Subscription section; stale doc comment corrected
- `AuthenticatedRootView.swift` — the transaction-listener task
- `DesignSystem/Theme.swift` — `DSControl.meterHeight`
- `LoginViewModel.swift` — its `switch error.knownCode` is deliberately
  exhaustive with no `default`, so the four new `ServerErrorCode` cases must
  join its existing log-and-replace arm. No behaviour change; the compiler
  forces it
- `ios-app/DESIGN.md` — §7 rewritten: usage and subscription are built; the
  remaining gap is the absent GET for subscription state
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` — new files, StoreKit config
- `features/paid-subscriptions.md` — living index: this slice recorded

**Not modified:** no Kotlin, no `api-specs/openapi.yaml`, no migrations.

## Implementation Plan

1. **Models + error codes.** DTOs and `ServerErrorCode` cases in `Models.swift`.
2. **Clients.** `SubscriptionClient`, `CoachingUsageClient`, and their
   `MockURLProtocol` tests. Green before any UI exists.
3. **The seam.** `SubscriptionStore.swift` types and protocol; the mock store in
   tests. No StoreKit import yet.
4. **`TransactionRecorder`** against the mock store and mock client, with the
   finish-policy table as its test matrix. The correctness core, tested alone
   and before anything can be tempted to duplicate it.
5. **`SubscriptionViewModel`** against a mock recorder, the mock store and the
   mock usage client — presentation only, so its tests assert copy, phases and
   refreshes, never finishing.
6. **`StoreKitSubscriptionStore`.** The real StoreKit 2 implementation; add the
   `.storekit` configuration file and scheme wiring.
7. **`UsageMeter`** and `SubscriptionSection`; wire into `SettingsView`.
8. **Transaction listener** in `AuthenticatedRootView`, sharing the recorder
   instance with the view model.
9. **Docs.** `DESIGN.md` §7, the `SettingsView` comment, the brief's index.
10. **Gates.** `xcodebuild test`, then `bin/build-ios simulator` +
    `bin/screenshot-ios` for the visual gate, then `nix develop -c bin/test`.

Steps 1–5 are pure logic and carry the test weight; 6–8 are integration that
`xcodebuild` and the screenshot judge.

## Tests

### iOS — `SubscriptionClientTests` (`MockURLProtocol`)

- POSTs `/api/v1/subscriptions/verify` with body `{"signedTransaction":"..."}`.
- 200 decodes `status`/`productId`/`currentPeriodEnd`; an **unknown** status
  string decodes successfully and yields `knownStatus == nil` (the tolerance
  above, pinned).
- Each error status surfaces `ErrorResponse` with the right `code`: 400
  `validation_failed`, 401 `unauthorized`, 403 `email_not_verified`, 404
  `subscription_not_found`, 409 `subscription_owned_by_other_account`, 409
  `student_profile_required`, 413 `payload_too_large`, 503
  `service_unavailable`, 500 `internal_error`.
- A response whose `fieldErrors` is literal `null` decodes (the server emits
  it).

### iOS — `CoachingUsageClientTests` (`MockURLProtocol`)

- GETs `/api/v1/students/me/coaching-usage`.
- 200 with `"resetsAt": null` decodes to `nil` — the free-tier branch.
- 200 with an ISO-8601 `resetsAt` decodes to a `Date`.
- `usedPercent: 100, exhausted: true` round-trips.
- 401 / 409 `student_profile_required` / 500 surface their codes.

### iOS — `TransactionRecorderTests` (mock store + mock subscription client)

The finish policy's own test matrix. **One test per row of the table**, each
asserting the arm returned _and_ whether `store.finish` was called:

- 200 → `.recorded(subscription)`, finished, and the decoded
  `PublicSubscription` carried on the arm.
- 400 `validation_failed` → `.rejected`, finished.
- 409 `subscription_owned_by_other_account` → `.rejected`, finished.
- 503 `service_unavailable` → `.deferred`, **not** finished.
- 404 `subscription_not_found` → `.deferred`, **not** finished.
- 401, 403 `email_not_verified`, 409 `student_profile_required`, 500 →
  `.deferred`, **not** finished.
- A transport failure → `.deferred`, **not** finished.
- The JWS posted is exactly `StoreTransaction.jws`, unmodified.
- Two concurrent `record` calls for the same transaction serialise (the actor
  guarantee) and finish it at most once.

### iOS — `StoreKitVerdictTests`

The refusal arm, executed directly — the point of lifting it out of StoreKit's
types:

- A verified verdict maps to a `StoreTransaction` carrying the id and the JWS
  unmodified.
- An **unverified** verdict maps to `nil`. Inverting the two arms must turn this
  test red; that is the whole reason it exists.

### iOS — `SubscriptionViewModelTests` (mock recorder + mock store + mock usage client)

Presentation only. These tests never assert on `finish` — that is the recorder's
contract, asserted above, and duplicating it here would be the duplication this
design removes.

- `load()` populates usage and product; with an entitlement present it calls
  `record` once and publishes the returned subscription.
- `load()` with a StoreKit product failure still publishes usage (degrade, not
  fail).
- `subscribe()` success → `record` called with the purchased transaction → usage
  refetched → `phase == .idle`.
- `.userCancelled` → `record` **not** called, `errorMessage == nil`.
- `.pending` → neutral message, `record` not called.
- A `.deferred(service_unavailable)` outcome → the "purchase is safe" copy;
  `.rejected(subscription_owned_by_other_account)` → the "another account" copy;
  `.deferred(subscription_not_found)` → the "couldn't confirm" copy; an
  unrecognized code → the generic message.
- `restore()` → `sync()` then one `record` per entitlement; empty set → the
  "nothing to restore" message.

### What unit tests cannot reach

Real StoreKit purchase, Apple's JWS, sandbox-vs-production environment
resolution, and Ask to Buy. `/verify` answers **503 in dev** (App Store
credentials are unset by default), so the local loop exercises the do-not-finish
path, not the success path. The success path is verified against the `.storekit`
configuration file in the simulator, and the true end-to-end against a sandbox
account.

### Manual end-to-end (required to land)

1. `bin/build-ios simulator`, `bin/rest-server-up`, sign in.
2. Settings shows the meter at the free-tier percentage with "One-time free
   allowance" and no reset date.
3. Subscribe with the local StoreKit configuration → purchase sheet → (dev
   backend answers 503) → the "purchase is safe" message, transaction **not**
   finished; relaunch redelivers it.
4. Restore Purchases with no entitlement → "nothing to restore".
5. Screenshots for the visual gate: the Subscription section on the free tier
   and at 100% exhausted, light and dark.

### Server

None. No Kotlin changes; `nix develop -c bin/test` is run as the standing gate
and is expected to be a no-op for this diff — which is precisely why it is
**not** evidence here, and the `xcodebuild` run and screenshots are.
