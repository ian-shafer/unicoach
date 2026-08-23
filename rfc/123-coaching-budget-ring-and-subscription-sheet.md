# RFC 123: The coaching budget ring and the subscription sheet

## Summary

RFC 119 landed the subscription rail and RFC 121 landed the paywall gate, and
between them the coaching meter is reachable from exactly two places: Settings,
three taps deep, and the block screen that appears when it is already too late.
This RFC gives the budget an **ambient** presence — a small ring on the
composer's own control row, beside the send button, showing how much coaching is
left — and makes it the door to a **subscription sheet** that explains how the
subscription works and offers the two actions Apple permits: subscribe, and
manage (which is where cancelling lives).

It also closes the two open items RFC 119 and RFC 121 recorded, both of which
were deferred to exactly this surface:

- **RFC 119** — `grace` / `billingRetry` has a status line and no UX. A
  subscription that is failing to bill now gets a sentence that says what
  happened and a control that fixes it.
- **RFC 121** — "a subscriber who has spent the period is told a date and
  offered nothing." They are now offered the App Store's own management screen,
  which is a real action for a paying customer rather than a dead end.

And it closes RFC 121's first deferred item as a side effect: there is now
something ambient that reports 90%, so the hard block is no longer silent up to
the moment it lands.

**No server change.** Every value on this surface is already fetched:
`GET /api/v1/students/me/coaching-usage` and the bound `PublicSubscription`.

## The one hard constraint

**You cannot unsubscribe in-app.** Apple requires cancellation to go through the
App Store, and there is no API that cancels a subscription on the user's behalf.
The affordance is StoreKit 2's `AppStore.showManageSubscriptions(in:)`, which
presents Apple's own sheet over the app; the student cancels there, and the
change reaches us the way every other change does — as a transaction on
`Transaction.updates`, recorded by `TransactionRecorder`, verified server-side.

RFC 119 is explicit that StoreKit is a payment rail and the server is the source
of truth. So this RFC adds **no cancel endpoint and no cancel client method**.
"Unsubscribe" is a link out, and the copy under it says so plainly rather than
letting the student discover it by tapping.

## Detailed Design

### Where it lives: the composer's control row

The ring goes **in the composer, on the send button's row** (Ian's call). That
is the right place on the merits and not merely by instruction: the composer is
where the budget is _spent_, so the reading sits next to the act that consumes
it, and it is on screen at the exact moment the decision to send is made. It is
also the one piece of chrome that appears on **every** conversation screen — the
root and every pushed conversation alike — where the top bar exists only at the
root.

Today's composer is a single `TextField(axis: .vertical)` with the send button
`.overlay`-ed at `.bottomTrailing`, and the text inset out of its way by a
`SendButtonWidthKey` preference measured at runtime. That structure has no row
to put anything else on, so it is replaced by the shape it was already
imitating: **one outlined box containing a text field above a control row**.

```
┌────────────────────────────────────────────┐
│  Message                                   │   ← TextField, full width
│                                            │
│  ◕ 62% left                          ( ↑ ) │   ← control row
└────────────────────────────────────────────┘
```

The box keeps its `DSRadius.control` corners, its 1pt `dsFieldBorder` hairline
and its 20pt leading inset — it is still a `LabeledField` in everything but
name. The composer gets **taller**, which is the accepted cost of the row, and
it buys back the preference-key dance: with a real row, `SendButtonWidthKey`,
`sendButtonWidth`, the trailing-inset padding and the `onPreferenceChange` all
delete. A geometry hack existed only to fake the row this RFC actually builds.

### The budget control

`CoachingBudgetButton` is the control on that row: a `CoachingBudgetRing` and a
short label, tappable as one element, opening the subscription sheet.

**`CoachingBudgetRing`** is a new `DesignSystem/` primitive taking plain values,
never a wire model, exactly as `UsageMeter` does: a small circle with a 1pt
`dsFieldBorder` hairline track and an arc over it whose sweep is the
**remaining** fraction, starting at twelve o'clock and running clockwise. It
depletes as coaching is used, which is the direction the word "remaining" leads
a reader to expect. `@ScaledMetric` diameter, so it grows with Dynamic Type like
`OptionCard`'s radio.

**Colour: the arc is `brandAccent`.** On the composer's `dsSurface` this is
precisely the sanctioned use — a small indicator fill, the same one
`UsageMeter`'s bar, `OptionCard`'s radio and `SegmentedSelector`'s selection
already take (DESIGN.md §1, §6). Had the ring gone on the brand gradient it
could not have been the accent at all, because `#EE732F` on `#EE7330` is not a
contrast question but an invisibility one; in the composer that problem does not
arise. The gradient stays chrome-only: this is a solid accent, not
`DSGradient.brand`.

**Exhaustion does not repaint the ring.** `UsageMeter` set that rule and it
holds here: the bar reads the same in both states and only the words change
(DESIGN.md §6 makes error UI outlined, not a tinted wash). An empty ring _is_
nothing remaining, drawn as nothing, and the label beside it says so in
`dsError` type while the block notice above the composer explains it at length.

**No reading yet.** `CoachingBudget.unknown` — a load in flight or a refresh
that failed — draws the track alone, with no arc and no label. Not a spinner: a
control that spins on every cold launch reads as the app working rather than as
the budget being unknown, and an empty groove for a beat is the quieter lie-free
answer. The arc appears the moment the reading lands.

**The label**, `.dsCaption`, is what makes this first-class rather than a
decoration a student has to learn:

| state          | label             | colour            |
| -------------- | ----------------- | ----------------- |
| a reading      | `62% left`        | `dsTextSecondary` |
| exhausted      | `Out of coaching` | `dsError`         |
| no reading yet | _(nothing)_       | —                 |

It is `lineLimit(1)` and yields its width to the send button under large Dynamic
Type — the send button is the control that must never be squeezed.

The ring's sweep, this label and what VoiceOver says are **one value**,
`CoachingBudgetGlance`, published by the view model — not three views each
re-reading the meter. That is what puts all three user-visible strings inside
XCTest's reach, which the Tests section below requires, and it carries a rule
the sweep alone would not: an exhausted budget draws an **empty** ring whatever
the percentage rounded to, so the ring and the words beside it cannot contradict
each other. The percentage is clamped where the value is built, not only where
it is drawn, for the same reason.

**The free tier.** `resetsAt == nil` is a lifetime credit that never resets, and
neither ring nor label says anything about time — they are a quantity — so both
read correctly with no special case at all. The distinction is the _sheet's_ to
make, and it makes it in words.

**Accessibility.** One element, like `UsageMeter`: label "Coaching budget",
value "62 percent remaining", the `.isButton` trait, and the hint "Opens your
subscription". The percentage is spoken once, never twice. A 44pt `contentShape`
guarantees the tap target regardless of the glyph's drawn size.

**It is never disabled.** The composer is disabled while blocked or streaming;
the budget control is not, because a student who has just been blocked needs
exactly this door, and the sheet is a read-only explanation the rest of the
time.

### The sheet

`SubscriptionView` is a new sheet, presented from the budget control's tap,
owned by `AuthenticatedRootView` beside the paywall, and rendering the **same**
`SubscriptionViewModel`.

**Two screens, one flag.** `AuthenticatedRootView` holds a single
`SubscriptionSheet?` — `.paywall` or `.subscription` — behind one
`.sheet(item:)`, and `PaywallGate` is its sole mutator. Not two `Bool`s: the
budget control is deliberately never disabled, so a 402 can land while the
subscription sheet is open, and with two flags the block would set a `true`
SwiftUI cannot act on, leaving the paywall unreachable and every later "See
options" tap a no-op against a flag that is already `true`.
`SubscriptionViewModel` already makes this argument for `Notice` — "one field
rather than two optionals … a single value is how that stays true" — and this is
the same shape. A 402 **replaces** the explanation with the block, which is the
more urgent screen. It is a presentation of the one rail, exactly as
`PaywallView` is: nothing on it derives entitlement or keeps state of its own.

It reads, top to bottom — a heading, then five sections separated by the 1pt
`DSHairline`:

1. **The meter** — `CoachingUsageMeter`, unchanged and shared. The ring is a
   glance; the sheet is where the number and the reset date live, and they are
   already authored once.
2. **The explanation** — how the subscription works, in the student's current
   situation. See `SubscriptionExplanation` below.
3. **The offer** — `SubscriptionOffer`, unchanged and shared: Subscribe when a
   purchase is on the table, Restore always.
4. **Manage subscription** — `ManageSubscriptionLink`, new and shared with the
   paywall. Present whenever a subscription is bound in any state, and it draws
   **its own leading hairline**: the rule and the condition that governs it then
   have one owner, so the paywall cannot end up without the separation the sheet
   has. Without it "Restore Purchases" and "Manage subscription" render as
   identical centred twins, and for a paying subscriber the second is the one
   that matters.
5. **Done** — the dismiss, matching the paywall's "Not now".

Sections are separated by the 1pt `DSHairline` and nothing else: no cards, no
fills, no shadows (DESIGN.md §3, §8).

### `SubscriptionExplanation` — the situation, named

The copy is a pure value derived from the situation, in the shape `PaywallCopy`
already established, so the rules are decided and tested without rendering a
view. One type, one exhaustive switch, no view-level `if` re-deriving it.

It is an **enum over the situations**, not a struct holding a finished string.
The classifier decides which situation the student is in; `detail` computes the
sentence at the point of use. That keeps the matrix this section draws as a
table visible in the code rather than only in a doc comment, and it lets the
suite assert _which situation was recognised_ for every row while pinning the
exact English for only two or three — the sentences were re-worded twice during
this run's own review, and a test that breaks on every copy edit is testing the
wrong thing.

The budget is read by an **exhaustive switch**, never `budget == .spent`:
`CoachingBudget` has three cases, and a boolean comparison silently lends
`.unknown` the open budget's sentence with no compiler error if a fourth is ever
added. `.unknown` does read as _not spent_ — a reading that has not landed is no
basis for telling a student their coaching is gone — but it says so on purpose.

The situation is the pair (bound subscription's `knownStatus`, coaching budget),
and the five cases are genuinely different:

| Situation                   | What it says                                                                                                                            |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| nothing bound, budget open  | Free coaching is a one-time allowance; a subscription gives a fresh allowance every month, and renews until cancelled                   |
| nothing bound, budget spent | The free allowance is used up; the subscription is the way to keep going                                                                |
| `active`, budget open       | Monthly, renewing on ⟨date⟩; the allowance resets then; cancel any time in the App Store                                                |
| `active`, budget spent      | This period's coaching is used; it resets ⟨date⟩ — **RFC 121's open item**                                                              |
| `grace` / `billingRetry`    | The last payment did not go through and the App Store is retrying; update the payment method to keep coaching — **RFC 119's open item** |
| `expired` / `revoked`       | The subscription has ended; subscribing starts a new one                                                                                |
| bound, status unknown       | "Your subscription is managed by the App Store." — the one thing true of every bound subscription, whatever its state                   |

That last row is not padding. A status outside this client's vocabulary still
decodes — that is what the raw wire string is for — and the no-`default:` rule
means it must be given words of its own rather than borrowing another arm's:
saying "ended" about a status that might mean the opposite is a guess dressed as
a fact.

The value is built from the **whole `PublicSubscription?`**, not from a bare
`knownStatus`: "nothing bound" is then the outer `nil`, and both `active`
sentences can name a date with no fallback, where a bare status would have
needed a nullable date argument that has to agree with the other three.

The `active`, budget-open sentence says the subscription **runs to** its period
end rather than _renews_ on it. `PublicSubscription` carries no auto-renew flag
and a cancelled subscription stays `active` until the period closes, so "renews"
would promise a renewal on the exact date the subscription is going to end — the
one date the app cannot stand behind.

The billing-failure case is deliberately **not** an error banner. It is not the
student's mistake and nothing is broken yet — coaching keeps working through
grace — so it is stated in ordinary type, with the control that resolves it
directly beneath, which is the same reasoning `SubscriptionOffer` applies to its
informational notices.

Two things this copy never does: it never says a subscription can be cancelled
in the app, and it never states a price. StoreKit's localized `displayPrice` is
the only price anyone shows (RFC 119), and it is already on the Subscribe
button.

### `ManageSubscriptionLink` and the store method

`AppStore.showManageSubscriptions(in:)` needs a `UIWindowScene` and it can
throw, so it goes on `SubscriptionStoreProtocol` as
`func showManageSubscriptions() async -> ManageSubscriptionsResult` — the
concrete `StoreKitSubscriptionStore` resolves the scene and calls Apple,
`DisabledSubscriptionStore` answers `.unavailable`, and
`PreviewSubscriptionStore` answers `.shown`. Putting it behind the protocol is
what lets the view model's arm be tested at all, and it keeps the "the app never
touches StoreKit directly" rule RFC 119 set.

Scene resolution is by **explicit predicate at every step** —
`.foregroundActive` first, then `.foregroundInactive`, then `.unavailable`.
There is no "any connected scene" fallback: `UIApplication.connectedScenes` is a
`Set`, so such a fallback picks a window by hash order, and on a multi-window
device it can present Apple's sheet somewhere the student is not looking while
still answering `.shown` — no sheet and no notice, which is the outcome
`.unavailable` exists to prevent.

The result is an enum, not a `Bool` and not a thrown error: `.shown`,
`.unavailable` (no scene, or Apple refused — the two are the same to a student),
matching `RestoreResult`'s shape. `SubscriptionViewModel.manageSubscriptions()`
raises the failure notice on `.unavailable` and says nothing on `.shown`;
Apple's sheet is the feedback.

**It reloads the rail when Apple's sheet is dismissed** —
`case .shown: await
load()`, where the `await` returns at exactly that moment.
The draft of this RFC argued the opposite, that `Transaction.updates` and RFC
121's `scenePhase` refresh would carry the change and a third path would be a
third thing to keep in step. Review showed **both of those mechanisms are inert
for this case**: `AppStore.showManageSubscriptions(in:)` presents over the app's
_own_ scene, so `scenePhase` never leaves `.active`, and a change of renewal
state pushes no `Transaction.updates` entry. Left as drafted, the student who
had just fixed their card would come back to "Your last payment didn't go
through" — the exact RFC 119 wound this feature exists to close.

The link's own copy carries the constraint: **"Cancelling is handled by the App
Store."** Said once, next to the control, rather than left to be discovered.

### The paywall gets the same link

`PaywallView` grows the same `ManageSubscriptionLink` under its offer, on the
same condition (a subscription is bound). That is RFC 121's open item in one
line of composition: the subscriber who has spent the period is no longer shown
a date and nothing.

### What this does not do

- **No cancel API, no cancel endpoint, no local entitlement change.** Covered
  above; it is the constraint this RFC is built around.
- **No warning notice near the cap.** The ring _is_ the runway RFC 121 asked
  for. A separate "you are at 90%" inline notice on top of a visible depleting
  ring would be two mechanisms for one job; if the ring proves too quiet in use,
  that is the moment to add one, with evidence.
- **No ring in the top bar.** `BrandTopBar` exists only at the root of the
  authenticated tree, so a ring there would vanish on every pushed conversation
  — the composer is on all of them.

## Landed against a moved base

`main` advanced five commits while this run was open, two of which touch the
same code:

- **RFC 125** made coach and student messages copyable. It never overlapped —
  its changes are in the turn rendering, this RFC's are in the composer below it
  — and the merged `ConversationView` is byte-identical to main's outside the
  composer.
- **"Never open a paywall with nothing to say"** gave `PaywallGate.present()` an
  exhaustive budget switch so the block never opens over an _open_ budget, and
  made `PaywallCopy` non-optional. That rule and this RFC's compose rather than
  compete: main constrains **when** the block opens, this RFC constrains **what
  value says a sheet is open**. Both are kept — `present()` keeps main's switch
  and writes `.paywall` in each presenting arm, and main's five gate tests now
  run against this branch's `SubscriptionSheet?` binding rather than being
  dropped.

`presentExplanation()` deliberately inherits **no** budget guard. That guard
exists because a paywall over an open budget has no basis to name and no way
out; neither is true of a read-only explanation whose entire job is describing a
budget that is fine.

## Files Modified

**Added — sources (`ios-app/UnicoachiOS/`)**

- `DesignSystem/CoachingBudgetRing.swift` — the ring primitive, plain values
  only
- `CoachingBudgetButton.swift` — the ring, the label, and the tap
- `SubscriptionView.swift` — the sheet and `SubscriptionExplanation`
- `ManageSubscriptionLink.swift` — the manage control and its Apple sentence,
  shared by the sheet and the paywall

**Modified — sources**

- `SubscriptionStore.swift` — `showManageSubscriptions()` on the protocol,
  `ManageSubscriptionsResult`, and the `Disabled`/`Preview` arms
- `StoreKitSubscriptionStore.swift` — the real
  `AppStore.showManageSubscriptions(in:)`
- `SubscriptionViewModel.swift` — `showManagement()`, `explanation`,
  `offersManage`, and the composer control's reading (`remainingPercent` and
  `budgetGlance`)
- `ConversationView.swift` — the composer restructured into a field plus a
  control row; `SendButtonWidthKey` and the preference plumbing deleted
- `PaywallGate.swift` — `SubscriptionSheet`, the one presentation binding, and
  `presentSubscription()`
- `Models.swift` — `CoachingUsage.percentRange` and its clamp: the server's
  0...100 contract named once, where the model that carries it lives, rather
  than re-typed as a bare literal in each body that depends on it
- `AuthenticatedRootView.swift` — the second sheet and its flag
- `PaywallView.swift` — the manage link for a bound subscription, and the shared
  sheet scaffold
- `SubscriptionOffer.swift` — adopts the shared `DSTextButton`
- `DesignSystem/Components.swift` — `DSTextButton` and `DSSheetScroll`, the two
  primitives this RFC's second surface turned from a copy into a duplicate
- `DesignSystem/UsageMeter.swift` — adopts the one clamped-fraction rule
- `ConversationListView.swift` — one preview call site of the widened
  `PaywallGate` initializer
- `DesignSystem/Theme.swift` — `DSControl.tapTarget`, `budgetRingDiameter` and
  `budgetRingWidth`, plus the shared clamped-fraction helper; the token-only
  rule leaves the ring's measurements nowhere else to live, and the tap target
  is a second token rather than a borrowed `topBarHeight` because an
  accessibility floor and a chrome height are different facts that happen to
  share a number
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` — register the four new files
- `ios-app/DESIGN.md` — the composer's new two-part shape and the ring

**Modified — tests (`ios-app/UnicoachiOSTests/`)**

- `SubscriptionViewModelTests.swift` — the explanation matrix, the remaining
  reading, and the manage arm
- `SubscriptionStoreMocks.swift` — the mock store's new method

## Implementation Plan

1. `ManageSubscriptionsResult` and `showManageSubscriptions()` on
   `SubscriptionStoreProtocol`; the `StoreKitSubscriptionStore`,
   `DisabledSubscriptionStore`, `PreviewSubscriptionStore` and mock arms.
   Nothing renders yet; the suite compiles.
2. `SubscriptionViewModel`: `remainingPercent`, `explanation`, and
   `showManagement()`. Tests first — these are the rules.
3. `CoachingBudgetRing` in `DesignSystem/`, with light and dark previews at a
   healthy, a low, an exhausted and an unknown budget.
4. The composer restructured into field-over-row, deleting `SendButtonWidthKey`;
   then `CoachingBudgetButton` on that row, opening the sheet through a
   `presentSubscription()` added to `PaywallGate`.
5. `ManageSubscriptionLink`, then `SubscriptionView` composed from the meter,
   the explanation, the offer and the link.
6. `PaywallView` adopts the link.
7. `project.pbxproj` registration, then a simulator build and screenshots.

## Tests

`bin/test` does not compile `ios-app/`; XCTest under `ios-app/UnicoachiOSTests/`
is the only mechanical authority here, and it has no view harness — so every
rule this RFC adds lives in a value type or the view model, where the suite can
reach it.

- **The explanation matrix.** One assertion per row of the table above, driven
  off `(knownStatus, CoachingUsage)`, asserting the exact string. The switch is
  exhaustive over `SubscriptionStatus?` with no `default:`, so a new status is a
  build failure rather than a silent fall-through to the free-tier sentence.
- **The reading is clamped where it is built.** A percentage outside 0...100
  cannot produce a label the ring contradicts — the ring's own clamp is not
  enough, because it would leave "-5% left" beside an empty circle.
- **The remaining reading.** `remainingPercent` is `100 - usedPercent` for a
  ready reading and `nil` for `loading`/`unavailable` — the last being the case
  that must never draw a full ring for a student with nothing left.
- **The manage arm.** `.shown` clears any standing notice and re-reads the rail;
  `.unavailable` raises a failure notice. Asserted through the mock store,
  including that the method was called exactly once. The `.shown` case starts
  from a view model that _has_ a notice, so the clearing is actually exercised —
  asserting `nil` in a state that was already `nil` would pass with the clearing
  deleted.
- **No cancel path exists.** `SubscriptionClient` gains no method; the existing
  client tests are unchanged, which is the point.
- **Visual.** Simulator screenshots of the composer at a healthy, a low, an
  exhausted and an unknown budget — including one at an accessibility Dynamic
  Type size, where the label must yield and the send button must not — and of
  the sheet in the free, active, spent-period and billing-retry states, light
  and dark.
