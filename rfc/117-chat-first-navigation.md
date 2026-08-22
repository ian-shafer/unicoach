# RFC 117: Chat-first navigation with a slide-over menu

## Summary

`ios-app/DESIGN.md` §7 specifies a chat-first structure: the conversation view
becomes the root of the authenticated state, `HomeView`'s hub screen disappears,
and everything secondary moves behind a slide-over menu. RFC 116 landed the
visual motif but deliberately left navigation alone. This RFC carries out §7.

It also corrects §7 in two places where the spec has drifted from the code, and
defers one part of it that cannot be built yet.

## Where §7 is stale

`rfc/README.md` puts the code above the RFC as the source of truth, and §7 has
been overtaken twice:

**"The four independent `NavigationStack`s ... collapse into it."** There are
**two**, not four. `ConversationView` and `ConversationListView` already declare
none — they are pure destinations relying on an ambient stack, and their only
`NavigationStack` occurrences are inside `#Preview` blocks. The real ones are in
`HomeView` and `VerificationRequiredView`. The collapse is therefore much
smaller than the spec implies: delete `HomeView`'s along with the view, and
leave `VerificationRequiredView`'s alone (below).

**"One `NavigationStack` at the root" contradicts "the auth-state `switch` stays
above navigation."** Both cannot hold. `VerificationRequiredView` is a _sibling
auth state_ of `.authenticated`, not a screen within it; the two are never on
screen together, so they cannot share a stack unless the stack is hoisted above
the switch — which §7 explicitly forbids. Resolution: the **authenticated
state** gets exactly one `NavigationStack`. `VerificationRequiredView` keeps its
own, because it is a different state. `DESIGN.md` §7 is corrected to say so.

## Detailed Design

### Root

`UnicoachiOSApp`'s `case .authenticated(let user)` renders a new
`AuthenticatedRootView` rather than `HomeView`. That view owns:

- the single `NavigationStack` for the authenticated tree,
- `ConversationView` as its root content — the app opens straight into chat,
- the slide-over menu overlay and its scrim.

`HomeView.swift` is **deleted**. Its three affordances rehome: _Start Coaching_
becomes the root itself, _Your Conversations_ moves into the menu, _Log Out_
moves into Settings.

### The menu

A custom overlay, not `NavigationSplitView` — §7's reasoning holds, the
full-bleed gradient chrome does not survive stock split-view presentation on
iPhone.

- Opened by a **leading accessory on `BrandTopBar`**, which today renders the
  wordmark only and gains an optional leading button slot. The glyph gets its
  own type token (`Font.dsTopBarGlyph`): a line symbol at the wordmark's point
  size reads weedy beside it. The 44pt tap target is separate and unchanged.
- Working on that bar surfaced an **RFC 116 defect, corrected here**: the
  `uni.COACH` wordmark had no Dynamic Type ceiling, so at accessibility sizes it
  wrapped and hyphenated to "uni.-COACH" and roughly tripled the bar's height —
  on every branded screen, not only the new one. The wordmark is a logotype and
  therefore artwork, so its growth is now capped and it may never wrap; the
  bar's accessory **button** keeps scaling, because it is a control. This
  changes `OnboardingView` and `VerificationRequiredView` too, which is intended
  rather than incidental. `DESIGN.md` §5 states the artwork/control split.
- Slides from the leading edge over a **dimmed scrim**; tapping the scrim or
  swiping back closes it. Animated, and driven by one `@State` in
  `AuthenticatedRootView`.
- Contents, top to bottom: **New conversation**; the **recent conversations**
  (server MRU order, tap to push that conversation); **All conversations**,
  pushing the existing `ConversationListView`; and a footer entry into
  **Settings**.
- The drawer shows at most `DSMenu.recentLimit` (3) recents and **does not
  scroll**: a scrolling menu has an indeterminate height and hides its own
  footer behind a gesture. That promotes **All conversations** from a
  convenience to the only route to an older conversation, so it is pinned in the
  footer.
- The list's state is owned by `AuthenticatedRootView`, not by the drawer, and
  the drawer is hidden by an **offset** rather than by conditional insertion. A
  drawer constructed per open owns a fresh view model and a fresh fetch, so it
  slid in empty and the rows appeared whenever the network returned — sometimes
  during the animation, sometimes after it. `ConversationListViewModel` gains
  `refresh()`, which updates MRU order without passing through `.loading`, so
  re-opening never blinks the list out.

`ConversationListView` is **kept, not absorbed**. It carries swipe-to-delete,
archive, the confirmation dialog, and the error alert, all covered by
`ConversationListViewModelTests`. The menu's inline list is a fast switcher for
the common case; the full surface remains one tap further for management. Making
the drawer carry destructive affordances would duplicate that logic in a cramped
space for no gain.

### Opening a conversation

**Chrome is a function of depth.** The root is always a _fresh_ conversation —
brand chrome, menu button, no back button, because there is nothing behind it.
Every _existing_ conversation is a **pushed** destination, from the drawer and
from `ConversationListView` alike, carrying stock chrome, a title and a back
button.

An earlier revision of this RFC had the drawer swap the conversation shown at
the root, which gave the same content two chromes and two exits depending on
which door the student came through, and no back button from the drawer. It also
needed the delicate part: because `ConversationView` binds its
`ConversationViewModel` in `@StateObject` at init, swapping at the root required
`.id(selectedConversationID)` to force a rebuild, or an in-flight SSE task would
have gone on writing into the wrong conversation. Pushing gives every
conversation its own view instance, and therefore its own view model, by
construction. The subtle mechanic is **deleted, not retained**.

`Conversation` gains `Hashable` so it can be a navigation destination value.

### Settings

A new `SettingsView`, pushed from the menu footer, holding:

- the student's **name and email** (previously shown by `HomeView`),
- an **appearance preference** — System / Light / Dark,
- **Change Email**,
- **Log Out**.

The appearance preference is new behaviour rather than rehomed behaviour: dark
mode existed but only ever followed the device. `AppearancePreference` is a
`String`-raw-valued enum persisted with `@AppStorage` and applied with
`.preferredColorScheme` at the **root scene**, so it governs the login and
verification screens as well as the authenticated tree. `System` maps to `nil`,
SwiftUI's own "follow the device" — not a third palette that would freeze
whatever the device happened to be when the preference was first written. The
picker reuses `SegmentedSelector`; a stock segmented picker would put exactly
the grey capsule on screen that `DESIGN.md` §2 and §3 rule out.

`ChangeEmailView` currently lives _inside_ `VerificationRequiredView.swift` and
is reachable only from the pre-verification blocking screen — so a verified user
cannot change their email at all. It moves to its own `ChangeEmailView.swift`,
used by both call sites. This is a **new file and must be registered in
`project.pbxproj`** or it silently never compiles.

Making that path reachable is precisely what obliges this RFC to finish it. The
server clears verification when the address changes, so a verified student who
changes their email is unverified the moment the call returns; leaving the app
in `.authenticated` would show a verified session for an address that is not.
`AppViewModel` therefore gains `onEmailChanged(_:)` — the same shape as
`onLoginSuccess`, delegating to the existing `resolveProfileState`, which
already routes an unverified user to `.verificationRequired`. `SettingsView`
threads the changed user up to it. The gap was dormant before this RFC only
because the path could not be reached.

### The chat root's empty state

Promoting chat to the root makes a fresh conversation the first thing an
authenticated student sees, which is otherwise a blank screen on every cold
launch. `ConversationView` gains **one line** of token-driven copy, centred in
the thread area when there are no turns — the `dsDisplay` question `HomeView`
used to ask. A designed empty state (illustration, suggested prompts) is
`DESIGN.md` §8.2 work and stays there; §8.2 is amended to record that a minimal
placeholder now exists and the real design is still open.

### Deferred: subscription status and coaching usage

§7 also asks Settings to absorb subscription status and coaching usage. **Not
buildable today.** The iOS client has no subscription or usage surface at all,
and the server exposes only `POST /api/v1/subscriptions/verify` and the Apple
notifications webhook — there is **no GET for subscription state, and no usage
endpoint of any kind**. Delivering this means new server endpoints, an OpenAPI
change, a new client, and tests: a larger and differently-shaped change than a
navigation restructure.

`SettingsView` is built so these are additive later. `DESIGN.md` §7 is amended
to record the deferral rather than leaving a promise the code does not keep.

## Files Modified

**Added**

- `ios-app/UnicoachiOS/AuthenticatedRootView.swift` — stack, root, menu overlay
- `ios-app/UnicoachiOS/SlideOverMenu.swift` — the drawer and its scrim
- `ios-app/UnicoachiOS/SettingsView.swift`
- `ios-app/UnicoachiOS/ChangeEmailView.swift` — extracted

**Deleted**

- `ios-app/UnicoachiOS/HomeView.swift`

**Modified**

- `ios-app/UnicoachiOS/UnicoachiOSApp.swift` — `.authenticated` renders
  `AuthenticatedRootView`
- `ios-app/UnicoachiOS/DesignSystem/Components.swift` — `BrandTopBar` gains an
  optional leading accessory
- `ios-app/UnicoachiOS/VerificationRequiredView.swift` — `ChangeEmailView`
  extracted out
- `ios-app/UnicoachiOS/ConversationView.swift` — the empty-thread placeholder.
  No chrome change was needed: at the root `AuthenticatedRootView` hides the
  stock bar, so the existing `.navigationTitle` is inert, and when pushed from
  `ConversationListView` that title and its back button are still wanted.
- `ios-app/UnicoachiOS/AppViewModel.swift` — `onEmailChanged(_:)`
- `ios-app/UnicoachiOS/AppearancePreference.swift` — **new file**, registered
- `ios-app/UnicoachiOS/ConversationListViewModel.swift` — `refresh()`
- `ios-app/UnicoachiOS/Models.swift` — `Conversation: Hashable`
- `ios-app/UnicoachiOS/DesignSystem/Theme.swift` — scrim and drawer-width
  tokens, so no view carries a literal
- `ios-app/DESIGN.md` — §7 corrected: two stacks not four,
  `VerificationRequiredView` keeps its own, subscription/usage deferred; §8.2
  records the placeholder empty state
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` — **four new files**

## Implementation Plan

1. `BrandTopBar` leading accessory slot; previews in both schemes.
2. Extract `ChangeEmailView` to its own file, register it, no behaviour change.
   Tests still green — this step is pure motion.
3. `AuthenticatedRootView` + `SlideOverMenu`, with `ConversationView` as root
   and the menu opening/closing. `HomeView` still present but unreferenced.
4. Wire the menu: new conversation, recent list with `.id()`-based switching,
   All conversations, Settings footer.
5. `SettingsView` with name/email, Change Email, Log Out.
6. Delete `HomeView.swift`, deregister it, remove its previews.
7. `DESIGN.md` §7 correction and deferral note.

Step 3 is the risk. Steps 1, 2, 6 are mechanical.

## Tests

`nix develop -c bin/test` never compiles `ios-app/`, so as with RFC 116 the
mechanical authority here is thin and must not be overstated. Unlike RFC 116,
though, this change is **behavioural**, so the existing suite is genuinely
load-bearing rather than merely a regression net.

1. **`xcodebuild test -scheme UnicoachiOS`** — the 244 existing tests must stay
   green. `ConversationListViewModelTests`, `ConversationViewModelTests`, and
   `ChangeEmailViewModelTests` cover the logic being rehomed; if extraction and
   restructuring are truly view-level, none of them should need editing. **A
   test that needs changing is a signal that behaviour moved** — stop and say so
   rather than editing the assertion.
2. **New view-model coverage.** The drawer's open/closed state stays pure view
   state, so it has nothing to unit test. The re-route above does: two new
   `AppViewModelTests` assert that `onEmailChanged` with an unverified user
   lands in `.verificationRequired`, and that a still-verified one does not
   leave `.authenticated`.
3. **Visual gate** per `references/visual-gate.md`: screenshots of the root with
   the menu closed and open, and of Settings, in both colour schemes. Judged for
   what the code cannot show — scrim contrast, the drawer not clipping, the top
   bar's accessory not colliding with the wordmark. Token conformance is checked
   in the diff, not measured off the pixels.
4. **Navigation smoke, by hand and stated plainly in the report**: log in → land
   in chat → open menu → switch conversation → all conversations → back →
   Settings → change email → log out. No UI automation exists to assert this;
   whoever runs it says so explicitly.
5. `nix develop -c bin/test` for the repo gate, understood as covering the
   `DESIGN.md` edit and nothing else.
