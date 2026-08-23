# RFC 122 — A permanent snapshot gate for `ios-app`

Six consecutive runs (116, 117, 118, 119, 120, 121) named "build the visual
gate" their #1 open item, and two of them — RFC 119's subscription rail and RFC
121's paywall — landed their **primary UI without anyone ever looking at it**.
Meanwhile, on the two occasions a capture harness _was_ built as throwaway
scaffolding, it earned its keep immediately: it caught four defects no test saw
— truncated list items, a table column cut off with no affordance, a wrapped
cell bleeding into the row below, and an oversized grid proving to itself that
it fit.

The recipe is not in doubt. It has been written three times, deleted three
times, and is transcribed in prose in RFC 118 §Visual gate and RFC 120 §Visual
gate. This RFC makes it permanent, and closes the gap that kept it from being a
gate at all: **`bin/screenshot-ios` only ever reaches the first screen**, so
every authenticated surface — the conversation, the settings screen, the
subscription rail, the paywall — has never been photographed.

The way in is the one all three throwaway harnesses used and which this RFC
adopts as the design: **construct the view with seeded state and host it, rather
than driving the UI**. No XCUITest, no sign-in, no network, no StoreKit.

## Goals

1. One command captures every important screen of the app, light and dark, from
   a cold checkout: `bin/snapshot-ios`.
2. The authenticated and billing screens are in that corpus.
3. The harness is a committed, compiled part of `UnicoachiOSTests`, so a change
   that breaks a screen's _construction_ fails `xcodebuild test` mechanically,
   with no human in the loop.
4. Adding a scene when a new screen ships is a few lines in one list, so the
   next UI RFC has no excuse.

Explicit non-goal: bulletproof pixel-regression testing. Ian's framing in RFC
116 — "it does not have to be bulletproof" — is taken literally below.

## Decisions

### Hand-rolled, not `swift-snapshot-testing`

`swift-snapshot-testing` is the obvious dependency and it is declined. The
recipe this repo needs is ~40 lines of `UIWindow` + `UIGraphicsImageRenderer`
that already exist and are already proven here; the library's value is its
diffing, its strategy zoo, and its golden management, and the first is 30 lines
while the other two are exactly what this RFC does not want. This repo chose SF
Pro over bundling a font rather than take a resource dependency, and an SPM
package is resolved on every clean build. A dependency that saves 40 lines of
code we have already written and debugged is not worth its supply chain.

### No committed golden images

The gate does **not** commit reference PNGs and does not fail on pixel drift by
default.

The evidence says goldens would not have helped: all four defects the throwaway
harness caught were in **new** code, where no golden exists and the first
capture _becomes_ the golden — a golden gate would have accepted every one of
them. What it would have bought instead is churn: binaries in git, and a suite
that goes red whenever Xcode retunes a font metric or the pinned runtime moves.

What the gate _does_ have is teeth in three places, described below: it renders
(a compile-and-construct tripwire), it proves each render non-blank, and it
looks for overdraw outside the device width. Everything past that is a human
looking at the corpus — which is the review rule that already exists, now with a
corpus that actually contains the screen under review.

For the "did my change move anything I did not intend" question, `-b` compares
against a **previously captured corpus directory**, not a committed one (below).

## Detailed Design

### 1. The host — `UnicoachiOSTests/SnapshotHost.swift`

A `@MainActor` helper, hosting a SwiftUI view in a real `UIWindow`:

```swift
let window = UIWindow(windowScene: scene)          // a LIVE scene, from UIApplication
window.frame = CGRect(origin: .zero, size: canvas)
window.overrideUserInterfaceStyle = dark ? .dark : .light
window.rootViewController = UIHostingController(rootView: content)
window.makeKeyAndVisible()
window.layoutIfNeeded()
RunLoop.current.run(until: Date().addingTimeInterval(settle))
let image = UIGraphicsImageRenderer(bounds: window.bounds)
    .image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
```

Three of those lines are load-bearing and were each bought with a real defect
report. They are commented as such in the source, and this RFC records why:

- **`ImageRenderer` is banned.** It does not rasterize `ScrollView` content (it
  returns the scroll view's frame with nothing in it, which reads exactly like a
  product defect) and it ignores a SwiftUI `.colorScheme` override for
  asset-catalog colours (dark mode captured as white-on-white). Both traps were
  hit in the RFC 118 run and cost two phantom defect reports. The comment says
  so, so that the next person to "simplify" this finds the reason first.
- **Dark mode is `window.overrideUserInterfaceStyle`**, never
  `.colorScheme(.dark)` — the modifier does not reach asset-catalog colour
  resolution, the `UIWindow` trait does.
- **`UIWindow(windowScene:)`, from `UIApplication.shared.connectedScenes`.** A
  detached window is not attached to a live scene;
  `drawHierarchy(afterScreenUpdates:)` then returns a blank image. The live
  scene is what makes the traits real _and_ what makes the rasterizing call
  above work at all.
- **`drawHierarchy(in:afterScreenUpdates:)`, not `layer.render(in:)`.** This
  reverses what the first draft of this RFC said, and the reversal was bought
  the same way as everything else here. `layer.render(in:)` walks the layer tree
  synchronously and never evaluates the CoreAnimation compositing filters that
  iOS 26's Liquid Glass navigation chrome is drawn through, so it drops that
  chrome _quietly_: a `.navigationTitle` renders as its raw white mask, which is
  the right answer on a dark capture and invisible white-on-white on the light
  capture of the same scene, and a toolbar button loses its glass capsule and
  the pencil half of `square.and.pencil`. A corpus whose two captures of one
  view disagree about whether the title exists is the phantom defect report this
  gate exists to prevent, and it makes `-b` drift noisy. `layer.render(in:)`
  survives as the fallback for the no-live-scene case, chosen by a blank-result
  check.

`RunLoop.current.run(until:)` is the settle: SwiftUI's async measurement passes
— the `MarkdownView` width probe's preference-key round trip is the known case —
have not converged at the end of `layoutIfNeeded()`. 0.4s is the value the
throwaway harnesses used; it is a per-scene parameter with that default.

The helper writes `<dir>/<scene>-<light|dark>.png` and returns the `UIImage` so
the test can assert on it.

**Output directory.** `UNICOACH_SNAPSHOT_DIR` when set (both the bare name and
the `TEST_RUNNER_`-prefixed name `xcodebuild` uses for a test-runner
environment, whichever arrives), else a path derived from `#filePath` —
`ios-app/build/snapshots/latest/` — so a run from Xcode's Test action with no
setup writes somewhere findable. `build/` is gitignored. The directory is
cleared at the start of a run so a deleted scene does not leave a stale PNG
behind pretending to be current.

### 2. The catalogue — `UnicoachiOSTests/SnapshotScenes.swift`

One array. A scene is a name, a canvas size, and an `async @MainActor` closure
returning a view:

```swift
struct SnapshotScene {
    let name: String
    var size: CGSize = .device            // 402 × 874, the pinned iPhone 17 Pro
    var settle: TimeInterval = 0.4
    let content: @MainActor () async -> AnyView
}
```

The closure is `async` because most seeding is `await viewModel.load()` — every
double answers synchronously, so this is deterministic and fast, but it must
happen **before** the render rather than being left to the view's own `.task`.

The scenes (each captured light **and** dark — 16 scenes, 32 PNGs):

| scene                               | what it proves                                                                                                                                                                                                                                                                                          |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `paywall-free-exhausted`            | The RFC 121 paywall. Never seen.                                                                                                                                                                                                                                                                        |
| `paywall-period-exhausted`          | Same, with a reset date instead of an upsell.                                                                                                                                                                                                                                                           |
| `paywall-offer-unavailable`         | `DisabledSubscriptionStore` → the honest "no purchase path" arm.                                                                                                                                                                                                                                        |
| `subscription-section-free`         | The RFC 119 settings rail. Never seen.                                                                                                                                                                                                                                                                  |
| `subscription-section-bound-active` | Status line + `Offer.bound` (Subscribe hidden).                                                                                                                                                                                                                                                         |
| `settings-populated`                | Whole authenticated Settings screen in one column.                                                                                                                                                                                                                                                      |
| `usage-meter-strip`                 | The one invented primitive, at 42 / 68 / 100 %.                                                                                                                                                                                                                                                         |
| `menu-recents`                      | The slide-over drawer, only ever seen mid-animation.                                                                                                                                                                                                                                                    |
| `menu-empty`                        | Its empty placeholder.                                                                                                                                                                                                                                                                                  |
| `conversation-thread`               | The primary screen, with real bubbles.                                                                                                                                                                                                                                                                  |
| `conversation-markdown-worstcase`   | `MarkdownFixture.worstCaseReply` — the RFC 118/120 defect ground.                                                                                                                                                                                                                                       |
| `conversation-blocked-composer`     | RFC 121's paused composer and "See options".                                                                                                                                                                                                                                                            |
| `conversation-list-populated`       | Card rows, hidden separators.                                                                                                                                                                                                                                                                           |
| `conversation-list-empty`           | Its empty state.                                                                                                                                                                                                                                                                                        |
| `login-idle`                        | Apple/Google button parity — a PNG question, not a test question.                                                                                                                                                                                                                                       |
| `design-system-catalogue`           | Every DS control in one tall scene: `LoadingButton` (primary, destructive, loading), `CircularIconButton`, `LabeledField`, `OptionCard`, `SegmentedSelector`, `StepIndicator`, `BrandTopBar`, `LogoMark`, `DSHairline`, `FormErrorBanner`, `FieldErrorText`, `GoogleSignInButton`, `AppleSignInButton`. |

Seeding uses what already exists: the app target's non-DEBUG
`PreviewCoachingUsageClient`, `PreviewSubscriptionStore`,
`PreviewTransactionRecorder`, and `DisabledSubscriptionStore`; the test target's
`MockAuthClient`, `MockConversationClient`, `MockStudentClient`,
`MockSsoSignInProvider`, `MockSubscriptionStore`, `MockTransactionRecorder`. The
only new fake is a `@FocusState`-owning host struct for `LabeledField` (the
app's equivalent is `private`) — about eight lines, in the catalogue file.

**Determinism.** Fixture timestamps are computed as _offsets from now_ (−2h,
−1d) rather than absolute dates, because `ConversationListView` renders
`.formatted(.relative(presentation: .named))`: an absolute fixture date makes
the rendered string drift every day, while an offset renders "2 hours ago"
forever. Absolute dates that are formatted absolutely (a renewal date) are
pinned. Locale is forced to `en_US_POSIX` and the time zone to UTC through the
environment so a machine's settings cannot move the pixels.

### 3. The test — `UnicoachiOSTests/SnapshotTests.swift`

One `@MainActor final class SnapshotTests: XCTestCase` that walks the catalogue
and, per scene per colour scheme, renders, writes, and asserts:

1. **It renders.** Constructing and hosting the view must not trap. This is the
   real mechanical authority and the reason the harness is committed rather than
   generated: a change that breaks a screen's construction now fails
   `xcodebuild test` with nobody looking.
2. **The image is not blank.** A uniform image is the exact symptom of the
   `ImageRenderer` trap and of a view that failed to lay out; sampling for more
   than one distinct pixel value costs nothing and would have caught it.
3. **Nothing draws outside the device width** — the _bleed canvas_, below.

Scenes are driven from the array, so adding a screen is one entry, and a scene
that is added but never rendered is impossible.

**The bleed canvas.** The view is given a container of exactly the device width,
but the _window_ is wider (device width + 120pt) with a distinctive backdrop
colour. `UIView.clipsToBounds` is `false` by default and SwiftUI does not clip
by default either, so a subview that lays out wider than its container **draws
into that margin** — and a scan of the margin for any non-backdrop pixel is a
mechanical detector for precisely the defect class that recurred twice: a table
drawing wider than the bubble it sits in, and a column running off the trailing
edge.

This is the one part of the design that is not already proven, and it is
declared as an experiment rather than smuggled in: if SwiftUI turns out to clip
these cases before they reach the margin, or the check proves noisy on
legitimate shadows and blurs, **it is dropped and the run reports why**, leaving
assertions 1 and 2 plus the human corpus. It is not worth contorting the harness
to save.

### 4. `bin/snapshot-ios`

A sibling of `bin/screenshot-ios`, same house shape: system Xcode only (the
`bin/is-nix` refusal block, verbatim in form, placed before any other logic),
sources only `bin/functions`, `set -euo pipefail`, silent-getopts, `help()`
heredoc, all logging to stderr and **the corpus directory as the only line on
stdout**.

```
snapshot-ios [-o <dir>] [-b <baseline-dir>] [target]
```

It runs

```sh
bin/test-ios "$ENV_NAME" -- \
  -only-testing:UnicoachiOSTests/SnapshotTests -testLanguage en -testRegion US
```

with the output directory exported into the test environment, and calls
`xcodebuild` nowhere itself.

This draft originally specified a direct
`xcodebuild test -project … -scheme … -destination "$UNICOACH_DESTINATION" …`
here, with a `-d <device>` flag overriding the device. **RFC 126 landed first
and that is now wrong.** A destination taken verbatim from the checked-in env
file names the one machine-global `iPhone 17 Pro`, so two checkouts capturing at
once boot and drive the same device — precisely the collision RFC 126 exists to
fix. `bin/test-ios` is the command that already owns the answer: it resolves
**this checkout's own** simulator device through `bin/ios-sim`, and with it the
project, the scheme, `bin/build-ios`'s `-derivedDataPath` and the simulator-only
guard. `bin/snapshot-ios` delegates and inherits all of it, rather than carrying
a second copy that can drift.

`-d` is **dropped** with the duplication: under RFC 126 the device is this
checkout's and the model comes from the target's `UNICOACH_DESTINATION` `name=`
component, so a device selector on this script would be a second, contradicting
answer. `target` still defaults to `simulator` and is still the only knob — it
is simply forwarded to `bin/test-ios`, which is what reads
`ios-app/env/<target>.env`.

What stays here is what `bin/test-ios` knows nothing about: the corpus
directory, the baseline, the `TEST_RUNNER_*` environment, the `en`/`US`/UTC
pinning passed after `--`, the dev-shell refusal in this script's own name, and
the stdout contract (all of `bin/test-ios`'s output is redirected to stderr so
the corpus directory remains the only line on stdout). It asserts the corpus is
non-empty afterwards, because a `xcodebuild test` that runs zero tests exits 0 —
the failure mode of a scene file that never got registered in the project.

Unlike `bin/screenshot-ios` it needs no running backend, no booted app, and no
sign-in. That is the whole point.

**`-b <dir>`: comparing against a previous corpus.** Not a committed golden — a
directory a previous `bin/snapshot-ios` wrote. When given, the harness loads the
same-named PNG from the baseline and reports the fraction of pixels differing by
more than a small per-channel epsilon, writing a red-overlay
`<scene>-<mode>.diff.png` for any scene over threshold and printing a summary of
moved scenes. It **reports**; it does not fail the test. The question it answers
is "which screens did my change move", which in review is worth more than a
pass/fail, and because the baseline is captured on demand from the base commit
it can never go stale in git.

### 5. Registration and docs

Three new `.swift` files must be registered in the classic `project.pbxproj`,
four entries each (`PBXBuildFile`, `PBXFileReference`, the `UnicoachiOSTests`
`PBXGroup` children, and the tests `PBXSourcesBuildPhase` files list) with
hand-minted 24-char uppercase-hex ids in the existing structured house style. A
prior run silently broke the project by reusing an id, so the implementation
runs a uniqueness check (`grep -oE '^\t\t[0-9A-F]{24} ' … | sort | uniq -d` must
be empty) before and after, and `xcodebuild -list` as the syntax check. An
unregistered test file **silently never compiles** — the exact way this gate
would ship dead.

No new test target: `UnicoachiOSTests` already has `TEST_HOST` set to the app,
which is what makes `UIWindow` hosting work at all, and a second target is ~12
new pbxproj objects plus a scheme edit for no benefit.

`ios-app/UnicoachiOSTests/TESTING.md` gains a section on adding a scene;
`ios-app/DEPLOY.md` gains `bin/snapshot-ios` directly after RFC 126's
`bin/ios-sim` / `bin/test-ios` sections — one family, read top to bottom: the
per-checkout device, the suite that runs on it, and the corpus that runs through
that suite — saying plainly which capture to reach for: `screenshot-ios`
photographs the **running app** (first screen only, needs a backend),
`snapshot-ios` photographs **any view** (no backend, no session, cannot show you
a navigation bug between screens).

## Files Modified

**New**

- `ios-app/UnicoachiOSTests/SnapshotHost.swift` — the `UIWindow` host, the
  renderer, PNG writing, the non-blank and bleed checks, baseline comparison.
- `ios-app/UnicoachiOSTests/SnapshotScenes.swift` — the scene catalogue, the
  seeding helpers, the fixture clock, the `LabeledField` focus host.
- `ios-app/UnicoachiOSTests/SnapshotTests.swift` — the XCTest that walks it.
- `bin/snapshot-ios` — the one command.

**Changed**

- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` — twelve entries, three files.
- `bin/ios-scripts-tests` — shim tests for `bin/snapshot-ios` (it is carved out
  of `bin/shell-tests` by name and must be run manually, outside the dev shell).
- `ios-app/UnicoachiOSTests/TESTING.md`, `ios-app/DEPLOY.md` — documentation.
- `rfc/122-ios-snapshot-gate.md` — this file.

Nothing under `ios-app/UnicoachiOS/` changes. If a scene turns out to need a
seam into a view (an `internal init` for a view model), that is a design
question surfaced at review rather than a silent widening: the scene is dropped
and recorded instead. The catalogue is deliberately built only from what the app
already exposes.

## Implementation Plan

1. `SnapshotHost.swift` with the proven recipe, plus one throwaway scene, and
   get a PNG out of `xcodebuild test` onto the host filesystem. Nothing else
   matters until an image lands in `ios-app/build/snapshots/`. Register all
   three files in `project.pbxproj` at this step (empty stubs for the other
   two), so the id minting and the uniqueness check are done once and verified
   by an actual compile.
2. The catalogue and the test walk, easy scenes first (`usage-meter-strip`,
   `design-system-catalogue`, `paywall-*`, `subscription-*`), then the ones
   needing a seeded client (`menu-*`, `conversation-*`, `settings-populated`,
   `login-idle`). Any scene that resists is dropped from the array with a
   comment saying why, not forced.
3. Assertions: non-blank, then the bleed canvas as an experiment — with an
   explicit A/B on a scene known to overflow to prove the detector detects
   anything at all before it is trusted. Drop it with a written reason if it
   does not.
4. `bin/snapshot-ios` + the `UNICOACH_SNAPSHOT_DIR` seam, then shim tests in
   `bin/ios-scripts-tests` in the existing style (dev-shell guard, guard
   precedence, missing env file, unknown option, option-requires-value, stray
   positional, `-h` exits 0, and the argv assertions for `-only-testing:` and
   the delegation — that the destination is this checkout's device by `id=`,
   which only `bin/test-ios`/`bin/ios-sim` can produce).
5. Baseline comparison (`-b`).
6. Docs.

Steps 5 and 6 are the droppable tail if the run runs long; 1–4 are the gate.

## Tests

**Mechanical** — `xcodebuild test` (the only authority that compiles `ios-app/`;
`nix develop -c bin/test` does not, and never will):

- Every scene renders in both colour schemes without trapping — 32 renders.
- Every render is non-blank.
- No render draws into the bleed margin (if the detector survives step 3).
- A negative control for the detector itself: a deliberately overflowing view in
  the test file is asserted to **fail** the bleed check, so a detector that can
  never fire cannot pass for a working one.
- The corpus files exist on disk at the end of the run.

**`bin/ios-scripts-tests`** — the shim tests above, run manually outside the dev
shell (`./bin/ios-scripts-tests`), since `bin/test check` deliberately does not
run that harness.

**`nix develop -c bin/test`** — unaffected and still green; it compiles none of
this. Stated so that the run's evidence is not mistaken for coverage of the iOS
change.

**The corpus itself** is the deliverable evidence: 32 PNGs, attached to the run
and actually looked at, light and dark. First sight of the RFC 119 rail and the
RFC 121 paywall.

## Open items

- **One device width, one Dynamic Type size.** 402pt at default. The
  `@ScaledMetric` bounds remain unverified at accessibility sizes; the catalogue
  makes adding an `.environment(\.dynamicTypeSize, .accessibility3)` variant of
  a scene a one-line change, and this RFC does not spend it.
- **States behind interaction are unreachable.** Streaming turns, per-turn
  failure copy, the login error banner, `.purchasing`/`.restoring` button
  spinners, presented sheets and alerts — all live behind `private` state
  mutated only by taps. Snapshotting them needs either XCUITest or an injection
  seam into the views; both are bigger decisions than this RFC, and the second
  would change production code to serve a test.
- **Presented content is captured directly, not in situ.** A `.sheet` renders in
  a separate presentation context that a window render of the parent does not
  see, so `ChangeEmailView` and `ErrorView` (when added) are scenes in their own
  right and the "does the sheet look right on top of Settings" question stays
  unanswered.
- **Nothing enforces that a new screen gets a scene.** The gate cannot tell that
  a view exists but is uncatalogued. This is a review habit backed by a cheap
  ritual, not a mechanism.
- **The `ship` skill's `references/visual-gate.md` should point at
  `bin/snapshot-ios`** as the way to reach authenticated screens. That file is
  out of bounds from inside a run and is updated separately.
