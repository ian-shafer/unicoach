# RFC 128: A failing card is not a missing subscription

## Summary

`SubscriptionViewModel.offersSubscribe` shows the Subscribe button to any bound
subscription that is not `active` — including `grace` and `billingRetry`, the
two states that mean **the student already pays us and their card failed**. So
the loudest element on the screen at that moment is a full-width filled
`Subscribe $9.99/month`, above the sentence explaining that the fix is to update
a payment method.

This RFC suppresses the offer for those two states. It is a **one-predicate
change**: `offersSubscribe` becomes an exhaustive switch, and the three surfaces
that read it — Settings, the paywall, the subscription sheet — each drop the
button without any layout branch of their own.

## Why RFC 119 decided otherwise, and why that no longer holds

[RFC 119](119-ios-subscription-rail.md) chose this deliberately and recorded the
reason:

> shown unless the bound subscription is `active`. Only `active` suppresses it:
> a subscription in `grace` or `billingRetry` is failing to bill, and hiding the
> purchase path at exactly that moment **strands the student with no in-app way
> forward**

That was correct **when it was written**. RFC 119's subscription surface offered
exactly two things: Subscribe and Restore. Restore re-binds an existing purchase
and does nothing for a card that expired. So suppressing Subscribe in `grace`
would have left the screen with no control that could help — the button was odd,
but it was the only door.

[RFC 123](123-coaching-budget-ring-and-subscription-sheet.md) built the door.
`ManageSubscriptionLink` opens `AppStore.showManageSubscriptions(in:)`, which is
**exactly where a payment method is updated** — it is the remedy for this state
and not a workaround for it. It renders whenever a subscription is bound, which
includes both of these states.

So the premise the exception rested on is gone. The exception is not.

This is the ordinary RFC mechanism, not a correction of a mistake: RFC 119's
rule was right for RFC 119's screen, the screen changed underneath it in RFC
123, and a changed decision lands in a new, higher-numbered RFC
(`rfc/INVARIANTS.md`).

## What the student sees today

`ios-app/rfc/artifacts/123/subscription-grace-light.png` (archived at
`.scratch/ship-archive/rfc-123/artifacts/`) shows it: the copy reads "You can
update your payment method there — the Manage subscription button opens it", and
directly beneath that sentence sits the filled black `Subscribe $9.99/month`.
The named control is third, past Restore and a hairline, in plain unfilled text.

The words are accurate — RFC 123's review already removed a positional "below"
that pointed straight at Subscribe — but **typography outranks prose**. The
screen says _do this quiet thing_ while shouting _do this other thing_.

**The harm is confusion, not money.** Apple prevents duplicate subscriptions
within a group, so a tap yields an App Store dialog rather than a second charge.
That is the whole reason this is a design defect rather than a bug: it costs an
existing paying customer, at the one moment they are already annoyed with us, a
wrong turn and a confusing system dialog — on the surface whose entire job is
the subscription.

## Detailed Design

`offersSubscribe` becomes exhaustive over this client's `SubscriptionStatus`,
with no `default:`, so a case added to **that enum** must be given a decision
here before this compiles. A status added to the **server's** vocabulary is
deliberately not a build failure — it decodes to `knownStatus == nil`, which is
what the raw wire string is for, and takes the unrecognized arm below:

| Bound subscription      | Offer Subscribe? | Why                                                                                        |
| ----------------------- | ---------------- | ------------------------------------------------------------------------------------------ |
| nothing bound           | **yes**          | The button's whole purpose.                                                                |
| `active`                | no               | One plan is configured; a second purchase is one StoreKit would refuse. Unchanged.         |
| `grace`, `billingRetry` | **no** — changed | They already subscribe. What is broken is the card, and Manage subscription is its remedy. |
| `expired`, `revoked`    | **yes**          | There is no live subscription; buying really is the way back.                              |
| unrecognized status     | **yes**          | See below.                                                                                 |

The table's first and last rows are **separate domain states**, and the code
keeps them separate. Swift would flatten `subscription?.knownStatus` — an
optional property on an optional value — into one `SubscriptionStatus?`, putting
"nothing bound" and "bound with a status this client cannot resolve" on a single
`case .none`. They happen to share an answer, but not a reason, so the predicate
is a `guard let subscription else { return true }` followed by a switch with an
explicit `case nil`, and each half states its own.

**The unrecognized arm stays `true`, and the asymmetry is the argument.** Unlike
`grace` / `billingRetry` — where we know a subscription exists and only the card
failed — an unrecognized status tells us nothing about whether the student is
covered. When we cannot tell, offering a purchase that turns out to be
unnecessary costs a dismissible App Store dialog, while withholding it from
someone who has nothing costs them the purchase path. The recoverable error is
the one to make. It is decided on that uncertainty and **not** on being the only
door: `offersManage` is true for any bound subscription, so this student is not
literally stranded either way. (The unbound row _is_ the only-door argument —
there the button is the only purchase path in the app.)

**The sheet needs no layout change.** `offer` already answers `.bound` when
`offersSubscribe` is false, and `SubscriptionOffer` already renders nothing for
`.bound`. So the grace sheet becomes: the sentence, Restore, the hairline,
Manage subscription — and the control the sentence names is the last and most
prominent action on the screen, with no situation-dependent ordering anywhere in
the view code.

**Settings, however, does.** This RFC's justification is that
`ManageSubscriptionLink` renders whenever a subscription is bound — and review
found that it does not: RFC 123 composed it into `PaywallView` and
`SubscriptionView` and **not** into `SubscriptionSection`, the Settings surface
RFC 119 was actually about. Removing Subscribe there without adding the link
would leave a student whose card is failing looking at a status line, a Restore
button and nothing that can fix a card — re-creating, on RFC 119's own screen,
the exact stranding its rule existed to prevent.

So `SubscriptionSection` composes the link too. That is a gap this RFC exposed
rather than created: a bound subscriber in Settings has never had a management
door, in any state. The link owns its own `offersManage` condition and draws its
own leading hairline, so it drops in with no rule and no conditional at the call
site, and a snapshot scene pins the composition so it cannot silently regress.

That is the test of whether the predicate is the right layer to fix the
_emphasis_ problem in — it is, and it passes. It is not a licence to assume the
button's absence is safe on every surface that drew it — which is the assumption
this RFC's first draft made, and it was wrong.

**`offersManage` is untouched.** It is `subscription != nil` and was already
correct for these states; RFC 123 deliberately made it not the inverse of
`offersSubscribe`, and this change is exactly the divergence that anticipated —
`grace` now answers `false` to one and `true` to the other.

**No server change, no StoreKit change, no wire change.**

## What this does not do

- **It does not reorder the sheet for one situation.** The emphasis problem is
  solved by removing the wrong control, not by moving the right one; a
  situation-dependent layout would be more machinery than the defect deserves.
- **It does not touch `statusLine`.** "Monthly · payment issue · retrying"
  (RFC 119) is still exactly right, and is now the only thing on the Settings
  row that mentions the problem.
- **It does not add a "fix your payment" call to action of its own.** RFC 123's
  sentence and link already are one.

## Files Modified

**Modified — sources (`ios-app/UnicoachiOS/`)**

- `SubscriptionViewModel.swift` — `offersSubscribe` becomes the exhaustive
  switch, with the RFC 119 reversal explained where the rule lives. Two doc
  comments in the same file go with it: `Offer.bound`'s, which named `active` as
  "the only" suppressed case and is now two cases out of date; and
  `offersManage`'s, which predicted that the two flags "would silently diverge
  the first time a status is added to either rule" — this RFC **is** that
  divergence, so the sentence stops being a prediction and becomes a
  description. `offersManage`'s behaviour is unchanged; RFC 123 already refused
  to define it as `!offersSubscribe`, and this is why
- `SubscriptionSection.swift` — composes `ManageSubscriptionLink`, so Settings
  stops being the one bound surface with no management door
- `SubscriptionOffer.swift` — the `.bound` arm's comment justified the absent
  button by a duplicate purchase StoreKit would refuse, which is the wrong
  reason for a failing card
- `SubscriptionView.swift` — `SubscriptionExplanation`'s doc makes the same
  false claim about the server's vocabulary being a build failure, one commit
  old and the same untruth, so it is corrected with it
- `Models.swift` — `SubscriptionStatus` gains `CaseIterable`, so the "some door
  is always open" test drives off the vocabulary itself rather than a
  hand-listed copy a new case would fall out of
- `PaywallView.swift` — its doc comment paraphrased the old rule; it now names
  the condition and enumerates nothing, because the enumeration is exactly what
  went stale

**Modified — tests (`ios-app/UnicoachiOSTests/`)**

- `SubscriptionViewModelTests.swift` — `testABillingProblemStillOffersSubscribe`
  asserts the behaviour being reversed and is rewritten, and the vocabulary-wide
  "some door is always open" invariant is added
- `SnapshotScenes.swift` — the new `subscription-section-billing-retry` scene

`PaywallViewModelTests.swift` needed no edit: its only bound fixtures are
`active` and `expired`, whose answers this RFC does not change.

## Implementation Plan

1. Rewrite `offersSubscribe` as the exhaustive switch, with the table above as
   its doc comment and the RFC 119 reversal stated in it.
2. Rewrite the tests that assert the old rule; add the arms that had none.
3. Correct `PaywallView`'s doc comment.
4. Compose the link into `SubscriptionSection` and add a Settings snapshot scene
   in `billing_retry`, following `SnapshotScenes.swift`'s own convention.
5. Run `bin/test-ios`, then `bin/snapshot-ios` against a baseline captured from
   the base commit, and read the scene diff: the sheet's `billing_retry` scene
   and the two Settings scenes must move, and **nothing else may**. RFC 122's
   gate already carries `subscription-sheet-billing-retry` from RFC 123, so that
   half of the evidence costs nothing to collect.

## Tests

`bin/test` does not compile `ios-app/`; XCTest is the only mechanical authority,
and `offersSubscribe` is a view-model property, so every row above is reachable.

- **One assertion per row** of the table, driven off a bound
  `PublicSubscription` with each status plus the unbound case. The switch is
  exhaustive with no `default:`, so a status added to **this client's**
  `SubscriptionStatus` vocabulary is a build failure rather than a silent
  inheritance of some other row's answer.

  A new status on the **server** is a different matter and is deliberately not a
  build failure: it decodes to `knownStatus == nil` — that is what the raw wire
  string is for — and takes the unrecognized arm, which is the answer that arm
  exists to give. The compiler guards the vocabulary this client knows; the
  `nil` arm guards the one it does not.
- **`grace` and `billingRetry` suppress the offer.** One assertion each, on the
  predicate the change reverses.
- **Some door is always open**, over the whole `SubscriptionStatus` vocabulary
  plus an unrecognized status and the unbound case:
  `offersSubscribe ||
offersManage`. This, and not "`offersManage` is still true
  for `grace`", is the assertion that can fail — `offersManage` is
  `subscription != nil`, so it is true of every bound subscription and was true
  before this RFC. What must never become representable is `(false, false)`, the
  stranding itself.
- **`offer` answers `.bound` for `grace`**, so the suppression is proven at the
  value the view actually renders, not only at the predicate behind it.
- **`statusLine` is unchanged for `grace`**, pinned so this change cannot
  quietly take the words with it.
- **Visual, on both surfaces.** The existing `subscription-sheet-billing-retry`
  scene re-captured — the sheet must show the sentence, Restore, the rule and
  Manage subscription, with no Subscribe button — and a new
  `subscription-section-billing-retry` scene, which is what pins the Settings
  composition and would have caught the stranding this RFC nearly shipped.
- **The blast radius is evidence, not assumption.** A differential capture
  against the base commit must move the sheet's `billing_retry` scenes and the
  Settings scenes and no others; every paywall and conversation scene stays
  byte-identical.
