# RFC 121: The iOS paywall gate

## Summary

This RFC is the **gate** half of the `paywall-ios` node in
[`features/paid-subscriptions.md`](../features/paid-subscriptions.md): when the
server refuses a coaching turn with HTTP **402 `coaching_budget_exhausted`**,
the app stops looking like something broke and starts saying what happened and
what the student can do about it — a block state on the composer, a paywall
sheet carrying the coaching-used meter, and the "budget spent, resets on ⟨date⟩"
screen for a subscriber who has spent the period.

[RFC 119](119-ios-subscription-rail.md) landed the **rail** (StoreKit purchase,
Restore, the clients, the meter, Settings). This slice adds the enforcement
surface on top of it and reuses every piece rather than growing a second copy.
With it, the node is complete.

**No server change.** The 402 has been produced by the two streaming turn
endpoints since RFC 109.

> **On the number.** The brief and RFC 119's report call this slice "RFC 120".
> 120 was claimed by a concurrent Markdown run while 119 was landing, so this is
> 121. The brief's living index is corrected here.

## What exists, and what today does with it

Today **nothing** in `ios-app/` mentions `coaching_budget_exhausted`, 402, or a
paywall — zero references across 86 Swift files. A 402 currently falls into
`ConversationViewModel.handle`'s `default:` arm and renders a generic
`FormErrorBanner` reading "Coaching allowance exhausted", beside a **Retry
button whose only possible outcome is the same 402**. That is the defect this
RFC removes: a dead end presented as a transient failure.

Three facts from the landed code shape everything below.

**The refusal is action-scoped, not session-scoped.** The server keeps reads
open, and says so in a test of its own:

```kotlin
@Test
fun `read routes stay open for an exhausted student`() {
    …
    assertEquals(HttpStatusCode.OK, list.status, "reading history costs nothing")
}
```

**The 402 arrives clean and pre-stream.** `ConvoRoutes.respondBudgetExhausted`
answers plain JSON in pre-flight, never an SSE error frame; `APIClient.stream`
drains the body and throws an `ErrorResponse` with `status` stamped 402, which
`ConversationClient.runStream` re-throws as the stream's terminal failure. So
`handle` sees the refusal before a single event yields — no partial reply, no
half-rendered turn to reconcile.

**The refusal body carries nothing but `code` and `message`.** There is no
percentage and no reset date in it. Anything the block screen says beyond "you
are out" must come from `GET /api/v1/students/me/coaching-usage`.

### Why the 402 does not carry the meter, and should not

`respondBudgetExhausted` receives the whole `Entitlement` and spends it on a log
line, so enriching the refusal is tempting and nearly free server-side. It is
still the wrong move.

**An error response reports the error. It cannot know what the caller will do
next.** The server knows one thing at the refusal: this turn is not allowed, and
why. What the caller intends to _render_ — a percentage, a reset date, an
upsell, nothing at all — is the caller's business, and a second client will want
a different set. Bundling this client's current screen into the refusal freezes
a guess about a consumer the endpoint cannot see, and every future consumer pays
for it. If the information can be had from the endpoint that owns it, it should
be had there.

**One endpoint owns the meter.** RFC 119's landed principle is that the client's
only entitlement truth is `CoachingUsage`, straight from the server. Embedding a
second copy in an error envelope creates two sources that can disagree — and
since the client refreshes usage on 402 anyway (below), it would immediately
have both, with no rule for which wins.

**`ErrorResponse` is a shared envelope.** `{code, message, fieldErrors?}` is RFC
69's contract for every error in the system. `fieldErrors` is the one
code-specific extension, and it is emitted as literal `null` on every response
that is not a validation failure. A second such field, null on all but one code,
is a cost paid by every consumer of every error to save one client one fetch.

**And it would not save the fetch.** The proactive layer below needs usage
loaded while chat is on screen, not just in Settings — so by the time a 402
arrives, usage is already in hand and the post-402 refresh is a cheap
confirmation rather than a blocking round trip.

The one case that genuinely regresses is a 402 on the very first turn after a
cold launch, before the initial usage load lands: the sheet renders its copy
immediately and the meter a beat later. RFC 119 already handles a missing
reading (`usageUnavailable`, and a failed refresh keeps the last value), so this
degrades correctly rather than showing a wrong number.

This is not a cost/benefit call that could tip the other way with a faster
network. It is a boundary: the refusal is complete when it names the refusal.

## Detailed Design

### The gate is not an auth state

The obvious-looking move — a `UserAuthState.budgetExhausted` case beside RFC
72's `verificationRequired` — is wrong, and it is worth writing down why,
because the shape is superficially identical and the mistake is expensive.

`UserAuthState` cases are **siblings of `.authenticated`**: `UnicoachiOSApp`'s
`WindowGroup` switch replaces the entire window when one is active. Applying
that to a 402 would:

- **break reading**, which the server deliberately keeps open — conversations,
  history, the list, archive and delete all still work for an exhausted student;
- **hide Settings**, which is the only place Subscribe and Restore live, so the
  gate would remove the exit from itself;
- **unmount `AuthenticatedRootView`**, and with it the session-long StoreKit
  transaction listener — so a renewal or an Ask-to-Buy approval arriving while
  the student is blocked would never be recorded, which is precisely the moment
  it matters most.

A 402 refuses **one action**. The gate therefore lives at the action, and the
rest of the app stays exactly as it was.

### Two layers, and the server wins both times

**Reactive (authoritative).** The 402 is the truth. `handle` gains an arm for
it, and that arm is what actually blocks.

**Proactive (courtesy).** `CoachingUsage.exhausted` — the server's own flag,
from the same `Entitlement` the turn gate reads — disables the composer and
shows the block state _before_ the student types into something that cannot
send. This is displaying the server's answer, never deriving it: the client
still never computes entitlement, exactly as RFC 119's principle requires.

This forces one change to what RFC 119 landed. Usage is currently loaded only by
`SubscriptionSection.task` — only when the student opens Settings — so a student
who never visits Settings has no reading, and the composer could not know to
block. **The initial load moves up to `AuthenticatedRootView`**, which already
owns the shared `SubscriptionViewModel`, and runs when the authenticated tree
appears — and it is a **usage-only** read. `load()` also fetches the StoreKit
product and re-posts the entitlement to `/verify`; RFC 119 scoped that to the
subscription surface deliberately, and hoisting the whole of it would put a
`/verify` POST on every launch. The root wants the meter, so the root asks for
the meter. Settings still calls the full `load()` — it is idempotent, and it is
a screen the student explicitly opened.

Usage is also refreshed when the app returns to the foreground, mirroring RFC
72's `scenePhase` recheck. Without it a `spent` reading has no expiry: the
composer would stay disabled with no in-app way back.

That relocation is what makes the second call unremarkable rather than grudging:
the meter is fetched once, from the endpoint that owns it, before it is needed —
so the 402 path refreshes something already on screen instead of standing a cold
fetch between the student and an explanation.

The proactive layer is a courtesy and never a promise. Usage can be stale, and a
background pass can spend the last of the budget between the read and the send —
so an enabled composer never guarantees a turn will be accepted, and the 402
path must work on its own. Both layers exist; only one is authoritative.

### The student's words are not thrown away

`handle`'s existing `student_profile_required` arm **removes** the optimistic
turn. The 402 arm must **not** copy it.

A student who has typed a paragraph and hit send has spent real effort, and the
turn never reached the model. Removing it deletes their writing to make room for
an error message. Instead the optimistic turn stays in the transcript carrying a
new failure kind, `.blocked`, whose action is **"See options"** rather than
**"Retry"** — because Retry, while blocked, can only reproduce the 402.

Once the block clears — the meter reporting `open` — the same turn's action
reverts to Retry and the student sends the words they already wrote. That is the
whole point of keeping it, and a `.blocked` turn that can never be retried keeps
them for nothing.

**The meter has three answers, not two.** A `Bool` conflates "no reading yet"
with "budget open", and that conflation is a trap: it forces `.blocked` to
suppress Retry permanently, so a student who pays can never send the words we
made a point of keeping. The words would be preserved and then stranded — worse
than dropping them, because the UI would promise a send it never delivers.

The meter is **derived** from one reading state, not from a value plus a flag.
`CoachingUsage?` alongside a `usageUnavailable: Bool` encodes three situations
in two fields, so "no reading yet" and "a read failed" become indistinguishable
from each other by accident — the same conflation as above, one layer down:

```swift
enum Reading<Value: Equatable>: Equatable { case loading, ready(Value), unavailable }
typealias UsageReading = Reading<CoachingUsage>

/// What the shared meter says about the budget, derived from the reading and
/// from nothing else. `unknown` is a reading that has not arrived or a refresh
/// that failed: deliberately neither `open` (the 402 stays the authority) nor
/// `spent` (a failed read must not disable a composer).
enum CoachingBudget { case unknown, open, spent }

enum TurnAction: Equatable {
    case retry
    case seeOptions
    init(failure: TurnFailure, budget: CoachingBudget) {
        switch budget {
        case .spent:   self = .seeOptions
        case .open:    self = .retry          // the block lifted; let them send
        case .unknown: self = failure == .blocked ? .seeOptions : .retry
        }
    }
}
```

`TurnAction` is a value computed beside `TurnFailure` rather than inline in a
`body`, because this suite has no view-test harness: the same expression in
`body` is assertable nowhere, and this is the rule the whole RFC turns on.

The `unknown` arm is what keeps the 402 authoritative when the post-402 refresh
fails, and the `open` arm is what lets the block lift. Both are required; either
alone is a defect. A refused turn carries the 402's own verdict, which remains
true even if the meter is absent or stale — and it _will_ be absent when the
post-402 refresh cannot reach the server. Keying the affordance only on usage
would put a Retry button, whose sole possible outcome is another 402, next to
paywall copy. The meter is the second half of the condition so that a turn
attempted while the budget is known-spent is also spared a pointless Retry.

**The gate travels as one type, not as loose parameters.** The escalation
closure, the paywall presenter and the shared view model are correlated — only
ever meaningful together — so they are bundled into a single value handed down
once, rather than three parameters threaded through every chat surface.
`ConversationListView` in particular sits on the path without using them, and
drilling three correlated arguments through it makes their correlation a
convention rather than a type. There is one paywall entry point, opened by the
method that owns the flag rather than by assigning it at each call site.

### One blocked truth, shared

Each pushed `ConversationView` builds its **own** `ConversationViewModel`, so a
per-view-model blocked flag would leave one conversation blocked and the next
one cheerfully offering a composer.

The shared truth is the single `SubscriptionViewModel` that
`AuthenticatedRootView` already owns as a `@StateObject` and already hands to
`SettingsView` as an `@ObservedObject`. `ConversationView` receives it the same
way — the handoff exists, and needs no new plumbing.

So the 402 arm reports **upward** rather than storing state locally, mirroring
`onProfileRequired`'s established shape:

```swift
let onBudgetExhausted: () async -> Void
```

`AuthenticatedRootView` implements it as "refresh usage on the shared
`SubscriptionViewModel`, then present the paywall". Refreshing from the server
rather than setting a local boolean means the blocked state and the meter can
never disagree, and every conversation in the stack observes the same value.

### `PaywallView` — a presentation of the rail, not a second one

A sheet presented from `AuthenticatedRootView`, driven by the shared
`SubscriptionViewModel`:

- the `UsageMeter` (RFC 119, already takes plain values);
- an explanation whose words depend on the basis, below;
- Subscribe at `product.displayPrice`, and Restore Purchases — both already
  implemented as `subscribe()` and `restore()`;
- the same `notice` rendering Settings uses. The meter block is **extracted and
  shared** with Settings, not copied — a second copy drifts, and this one
  already did;
- **"Not now"** — an explicit dismissal. Reads stay open by design, so the sheet
  must be leaveable, and an explicit control is the one a VoiceOver user can
  find; a drag gesture is not discoverable.

The offer block is **extracted** from `SubscriptionSection` into a shared
`SubscriptionOffer` view used by both surfaces, so the Subscribe/Restore pair
and its notice exist once. Settings keeps its section heading and status line;
the paywall keeps its explanation. Neither grows a private copy of the buttons.

### What the block screen says depends on why

The two exhausted states are genuinely different situations and must not share
one sentence:

| basis          | `resetsAt` | copy                                                    | offer     |
| -------------- | ---------- | ------------------------------------------------------- | --------- |
| free allowance | `nil`      | "You've used your free coaching."                       | Subscribe |
| subscription   | non-`nil`  | "You've used this period's coaching. It resets ⟨date⟩." | **none**  |

| _not yet known_ | no reading | "You've used your coaching allowance." |
Subscribe |

The third row is the cold-launch 402 named above — a refusal arriving before the
first usage read lands. It must not fall back to the free-tier row: telling a
paying subscriber they have used their _free_ coaching is worse than saying
nothing specific. The neutral sentence is replaced the moment a reading arrives.
The second row is the brief's "budget spent, resets on ⟨date⟩" screen. A
subscriber who has spent the period **cannot buy their way out** — one plan is
configured — so offering Subscribe would be an invitation to a duplicate
purchase StoreKit would refuse. `SubscriptionViewModel.offersSubscribe` already
answers this correctly (only an `active` subscription suppresses the offer), and
the paywall reads it rather than re-deriving.

Restore stays available in both, because a student whose purchase never bound to
this account is exactly who needs it.

### `ServerErrorCode` gains the code

`coachingBudgetExhausted = "coaching_budget_exhausted"` — **and
`studentProfileRequired = "student_profile_required"`**, which the `knownCode`
conversion below forces: that 409 is currently matched as a raw string, and
converting the switch without giving it a case would route it into the generic
Retry arm and break the onboarding hand-off. It is a consequence of this RFC's
own instruction, not extra scope.

Two switches are deliberately `default:`-free after RFC 119 —
`TransactionRecorder.isPermanent` and `SubscriptionViewModel.message(for:)` — so
both fail to compile until the new case is decided. That is the exhaustiveness
working as intended:

- `isPermanent` → **defer**. A 402 cannot arise from `/verify`; if one ever did,
  it says nothing about whether Apple's transaction was recorded, and the
  asymmetry that governs finishing says do not finish on an answer you do not
  understand.
- `message(for:)` → the blocked copy, so a 402 surfacing anywhere in the
  subscription surface reads correctly rather than falling to a generic string.

`handle` currently switches on the **raw** `error.code` string while ignoring
`knownCode`. This RFC converts that switch to `knownCode`, so the new arm is
checked by the compiler rather than by a string literal, and a typo becomes a
build failure instead of a silently unhandled refusal.

## Deferred, deliberately

Both were surfaced while walking the user through this flow, and both are
accepted as-is for this slice (Ian's call) rather than discovered later:

- **No runway before the stop.** A student goes from working to blocked with no
  warning: the meter lives only in Settings and the paywall, so nothing ambient
  reports 90%. The hard block is the brief's stated design ("Hard block at the
  cap"), but the _silence before it_ is a UX wound, not a design decision.
  Candidates when it is addressed: the meter in the slide-over menu, or a
  one-time inline notice near the cap.
- **A subscriber who spends the period has no action.** The sheet names a reset
  date and offers nothing, which is honest — one plan is configured, so there is
  nothing to sell them — but it is a dead end for a paying customer. This is
  also where RFC 119's deferred `grace` / `billingRetry` surface belongs, so the
  two should be designed together.

## Files Modified

**Added — sources (`ios-app/UnicoachiOS/`)**

- `PaywallView.swift` — the sheet
- `SubscriptionOffer.swift` — the Subscribe/Restore/notice block shared by the
  paywall and Settings
- `CoachingUsageArea.swift` — the meter block shared by the paywall and
  Settings, extracted rather than copied
- `PaywallGate.swift` — the one type the correlated gate parameters travel in

**Added — tests (`ios-app/UnicoachiOSTests/`)**

- `PaywallViewModelTests.swift` — the basis-dependent copy and offer rules

**Modified**

- `Models.swift` — `ServerErrorCode.coachingBudgetExhausted`
- `ConversationViewModel.swift` — the 402 arm, `.blocked` failure kind,
  `onBudgetExhausted`, and the `knownCode` conversion
- `ConversationView.swift` — blocked composer, the `.blocked` action, the shared
  `SubscriptionViewModel`
- `AuthenticatedRootView.swift` — `onBudgetExhausted` wiring, paywall
  presentation, passes the view model to `ConversationView`
- `SubscriptionSection.swift` — adopts `SubscriptionOffer`
- `SubscriptionViewModel.swift` — the `message(for:)` arm; `refreshUsage()`
  becomes non-private so the gate can force a refresh
- `SubscriptionSection.swift` / `AuthenticatedRootView.swift` — the initial
  usage load moves up from the Settings section to the authenticated root, so
  the composer can block for a student who never opens Settings
- `TransactionRecorder.swift` — the `isPermanent` arm
- `ConversationViewModelTests.swift`, `SubscriptionViewModelTests.swift`
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj`
- `features/paid-subscriptions.md` — node complete; the number corrected to 121

**Not modified:** no Kotlin, no `api-specs/openapi.yaml`, no migrations, and no
committed RFC.

## Implementation Plan

1. **`ServerErrorCode.coachingBudgetExhausted`**, and the two forced arms
   (`isPermanent` → defer, `message(for:)` → blocked copy). The build tells you
   when you are done.
2. **`handle` → `knownCode`**, no behaviour change, tests unchanged. Landing the
   conversion separately from the new arm keeps the two reviewable apart.
3. **The 402 arm**: `.blocked` failure kind, optimistic turn preserved,
   `onBudgetExhausted` reported upward. Tests first — this is the slice's core.
4. **`SubscriptionOffer`** extracted from `SubscriptionSection`; Settings adopts
   it and stays green.
5. **`PaywallView`** on top of the shared view model, with the basis-dependent
   copy table.
6. **Wiring** in `AuthenticatedRootView`: refresh-then-present, and the view
   model handed to `ConversationView`.
7. **Composer block** from `usage.exhausted`, with the `.blocked` action, and
   the initial usage load hoisted to the authenticated root.
8. **Docs** — the brief's living index; the node is complete.
9. **Gates** — `xcodebuild test`, then the visual gate, then
   `nix develop -c bin/test`.

## Tests

### iOS — `ConversationViewModelTests` (via `MockConversationClient.Script.terminalError`)

`ErrorResponse`'s `status` is settable in its initializer, so a 402 needs no
mock change. `testStudentProfileRequiredInvokesCallbackAndRemovesTurn` is the
template — and the contrast with it is the point:

- A 402 **keeps** the optimistic turn (the inverse of the profile-required arm,
  asserted explicitly) and marks it `.blocked`.
- A 402 invokes `onBudgetExhausted` exactly once.
- A `.blocked` turn offers **See options**, not Retry — **including when `usage`
  is `nil`**, i.e. when the post-402 refresh failed. This is the case that
  keying the affordance on the shared meter alone would get wrong, so it is
  asserted explicitly.
- A turn that failed for a non-budget reason still offers Retry while the meter
  says exhausted is **not** required; the blanket rule
  (`failure == .blocked ||
  isBlocked`) deliberately spares it a Retry that
  would 402.
- Once unblocked, the same turn offers Retry again and re-sends the original
  text — the words survived.
- A 402 on the message endpoint behaves identically to one on the conversation
  endpoint (both streaming call sites).
- A non-402 server error still takes the existing keep-and-Retry path
  (regression).
- `student_profile_required` still removes the turn (regression).

### iOS — `PaywallViewModelTests`

- `resetsAt == nil` + exhausted → free-tier copy, Subscribe offered.
- `resetsAt != nil` + exhausted + `active` → reset-date copy naming the date,
  Subscribe **not** offered, **Restore still offered** (asserted, not implied).
- No usage reading yet → the neutral third-row copy, never the free-tier
  sentence.
- A successful purchase refreshes usage and clears the blocked state, **and
  dismisses the sheet** — a student who has just paid must not be left reading
  "Coaching is paused".
- A `.blocked` turn whose meter later reports `open` offers **Retry**, and
  retrying re-sends the original text. This is the assertion that a refused turn
  is not stranded; asserting it on an `.infrastructure` failure instead would be
  trivially true and prove nothing.
- `usageUnavailable` renders the RFC 119 unavailable line rather than an empty
  sheet.
- A 402 arriving **before** the initial usage load lands still renders its copy,
  and fills the meter when the reading arrives — the cold-launch case.

### iOS — `SubscriptionViewModelTests` (regression)

- `message(for:)` answers the blocked copy for `coaching_budget_exhausted`.
- Settings renders identically after adopting `SubscriptionOffer` — the
  extraction is behaviour-preserving.

### iOS — `TransactionRecorderTests` (regression)

- A 402 from `/verify` **defers** and does not finish, per the new arm.

### What unit tests cannot reach

**This suite is view-model only — there is no view-test harness in `ios-app`.**
So three things the design specifies are _not_ mechanically assertable here and
are named rather than quietly dropped: that the composer renders disabled, that
the sheet actually presents, and that Settings renders identically after
adopting `SubscriptionOffer`. The first two are covered by the manual pass and
the screenshots below; the third is a behaviour-preserving extraction whose view
model is unchanged and whose tests are untouched.

A real exhausted budget end to end. The dev backend cannot easily be driven to
`exhausted` without spending real Anthropic budget, so the composer block and
the paywall are exercised against injected usage in tests, and end to end only
by pointing the app at a seeded account.

### Manual end-to-end (required to land)

1. Seed an exhausted student against the local backend.
2. Send a turn → the turn stays in the transcript, marked blocked, with **See
   options**; the paywall appears; the composer is disabled.
3. Confirm reading still works: open another conversation, the list, and
   Settings.
4. Screenshots for the visual gate: the blocked composer, the free-tier paywall,
   and the subscriber "resets on ⟨date⟩" state.

### Server

None. `nix develop -c bin/test` is run as the standing gate and is expected to
be a no-op for this diff — which is why the `xcodebuild` run and the screenshots
are the evidence, not it.
