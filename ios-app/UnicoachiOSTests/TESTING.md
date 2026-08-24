# Testing Guide — UnicoachiOS

Practical conventions for writing tests in this target. This is **guidance, not
a module spec** — for the DI / `MockURLProtocol` / xcodebuild mechanics, read
the code (`UnicoachiOS/` and this target's fixtures); this file captures _how to
write a good test_ on top of them.

## Running the suite

iOS tests run through **system Xcode**, not the Nix dev shell:

```sh
bin/test-ios            # the whole suite, on THIS checkout's simulator
bin/test-ios simulator -- -only-testing:UnicoachiOSTests/PaywallViewModelTests
```

`bin/test-ios` is `xcodebuild test` with the destination pinned to **this
checkout's own simulator device** (`bin/ios-sim`, created on demand), sharing
`bin/build-ios`'s DerivedData tree; everything after `--` is forwarded to
`xcodebuild`. Type the raw command and you get
`-destination 'platform=iOS Simulator,name=iPhone 17 Pro'` — the one
machine-global device every other checkout also resolves, which a concurrent
`ship` run or screenshot capture is booting, installing over, and terminating
apps on. See
[DEPLOY.md — One simulator per checkout](../DEPLOY.md#one-simulator-per-checkout-binios-sim).

To _use_ the app rather than test it, `bin/ios-simulator` builds this checkout
and runs it on the same per-checkout device (`-B` skips the build); see
[DEPLOY.md — Running the app in the simulator](../DEPLOY.md#running-the-app-in-the-simulator-binios-simulator).

New test `.swift` files MUST be registered in
`ios-app/UnicoachiOS.xcodeproj/project.pbxproj` (explicit file references —
there is no file-system synchronization), or they silently never compile.

## Test doubles

- **Client tests** (`APIClient` / `AuthClient` / `StudentClient`): drive the
  real client through `MockURLProtocol` on an **ephemeral** `URLSession`
  injected via `APIClient(baseURL:session:)`. This exercises the real request
  building, status handling, and **decoding** — the boundary where bugs hide.
  See [StudentClientTests.swift](./StudentClientTests.swift).
- **View-model tests**: inject a **protocol mock** (`MockStudentClient`, or the
  `CapturingStudentClient` double inside the test file) — never the real client.
  View-model tests assert state transitions and the request captured, not the
  wire.

> A protocol mock bypasses JSON decoding entirely. A bug that only manifests
> when real bytes are decoded (see below) can ONLY be caught at the
> client/`MockURLProtocol` layer — never in a view-model test. Put
> decode-sensitive assertions there.

## Boundary fidelity (load-bearing)

**A fixture MUST match what the real peer actually emits — never Swift's
convenient default.** A fixture that diverges from the wire can stay green while
production breaks.

- The REST server serializes `Instant` via Jackson's `JavaTimeModule`, so
  `PublicStudent.createdAt` / `updatedAt` arrive as **ISO-8601 strings** with
  variable-precision fractional seconds and a trailing `Z`
  (`2025-01-07T22:16:27.092942Z`, or `2025-01-07T22:16:27Z` on a whole second).
- Therefore a mock `StudentResponse` body MUST encode those timestamps as
  ISO-8601 strings — either via a `JSONEncoder` with
  `.dateEncodingStrategy = .iso8601`, or via
  `RandomFixtures.studentResponseJSON`. **NEVER** round-trip a default-encoded
  Swift `StudentResponse`: the default `Date` strategy emits a _numeric_
  timestamp the default decoder happily reads back, so encoder and decoder agree
  on a format the real server never sends.
- Why this rule exists: a numeric round-tripped fixture let a real
  `DECODE_ERROR` ship green (the app routed re-login to `.serverError`). The
  fixture, not the code, was wrong. See
  [RandomFixtures.swift](./RandomFixtures.swift) and the
  `…DecodesRealServerTimestamps` tests in
  [StudentClientTests.swift](./StudentClientTests.swift).

## Randomized fixtures, deterministic coverage

`RandomFixtures` exercises the **whole valid range** so edge cases surface over
runs instead of one hand-picked value:

- **Seeded & reproducible.** A SplitMix64 generator drives every draw, and each
  builder `print`s its seed and produced values, so a failing random draw is
  replayable by pinning the logged seed.
- **Boundary-faithful generators.** Graduation dates at a random valid precision
  (`YYYY` | `YYYY-MM` | `YYYY-MM-DD`); server timestamps as
  microsecond-fractional ISO-8601 — the exact shapes the wire uses.

**Randomized coverage is ADDITIVE, never a replacement.** Known-important
discrete cases stay deterministically pinned in their own tests, regardless of
any random draw. Example: the three graduation-date precisions are asserted
explicitly through `submit()` (`testSubmitEmitsCanonicalStringForEachPrecision`)
**and** through `isoDate` (`testIsoDate*Precision`), not left to chance.

## Snapshot scenes (the visual gate)

`SnapshotTests` walks a catalogue of scenes, hosts each one in a real `UIWindow`
and writes `<scene>-light.png` / `<scene>-dark.png`. It is a **compile-and-
construct tripwire first** (a change that breaks a screen's construction fails
`xcodebuild test` with nobody looking) and a review corpus second (RFC 122).

Capture the whole corpus with one command, outside the Nix dev shell:

```sh
bin/snapshot-ios                       # -> prints the corpus directory
bin/snapshot-ios -b <previous-corpus>  # also reports which scenes moved
```

Unlike `bin/screenshot-ios` it needs no backend, no session and no booted app —
the reason the authenticated and billing screens are in the corpus at all.

`bin/snapshot-ios` runs the capture **through `bin/test-ios`**
(`-- -only-testing:UnicoachiOSTests/SnapshotTests -testLanguage en -testRegion
US`)
rather than calling `xcodebuild` itself, so it lands on **this checkout's own
simulator device** (RFC 126) exactly like the unit suite does, and there is no
device option to pass — the model comes from the target's env file. It adds the
corpus directory, the `-b` comparison, the `en`/`US`/UTC pinning and the check
that PNGs were actually written.

### Adding a scene

Append one entry to `SnapshotCatalogue.scenes` in
[SnapshotScenes.swift](./SnapshotScenes.swift):

```swift
SnapshotScene(name: "my-screen", size: CGSize(width: 402, height: 1000)) {
    let rail = await SnapshotSeed.rail(
        usage: CoachingUsage(usedPercent: 42, exhausted: false, resetsAt: nil)
    )
    return AnyView(NavigationStack { MyScreen(viewModel: rail) })
}
```

- `size` defaults to the pinned iPhone 17 Pro canvas (402x874); give a taller
  one when the content is taller, because the window does not scroll for you.
- The closure is `async` so seeding is `await viewModel.load()` **before** the
  render. Do not rely on the view's own `.task` for anything you can seed
  yourself — that path is captured only if the test is synchronous (below).
- Seed from the doubles that already exist: the app target's
  `PreviewCoachingUsageClient`, `PreviewSubscriptionStore`,
  `PreviewTransactionRecorder`, `DisabledSubscriptionStore`, and this target's
  `Mock*` clients. `SnapshotSeed` has helpers for the common rails.
- Timestamps rendered by a **relative** formatter must be offsets from now
  (`SnapshotClock.agoHours(2)`), or the PNG changes every day. Absolute dates
  are pinned (`SnapshotClock.pinned`).

That is the whole change: the test walks the array, so a scene that is added is
rendered, asserted non-blank, and bleed-checked automatically.

### Rules the harness is built on (do not "simplify" these)

They are commented in [SnapshotHost.swift](./SnapshotHost.swift) with the defect
each one cost:

- **`ImageRenderer` is banned.** It does not rasterize `ScrollView` content and
  it ignores a SwiftUI `.colorScheme` override for asset-catalog colours.
  `UIGraphicsImageRenderer` is the path.
- **Inside it, `drawHierarchy(in:afterScreenUpdates:)` is the rasterizing call**
  and `layer.render(in:)` is only the fallback for a window with no live scene.
  `layer.render(in:)` walks the layer tree synchronously and never evaluates the
  compositing filters iOS 26's Liquid Glass navigation chrome is drawn through,
  so it drops that chrome silently: a `.navigationTitle` comes out as its raw
  white mask — correct-looking on a dark capture, invisible on the light capture
  of the _same_ scene — and a toolbar button loses its glass capsule and half of
  a multi-layer symbol. Two captures of one view disagreeing about whether the
  title exists is the phantom defect report this harness exists to prevent.
- **Dark mode is `window.overrideUserInterfaceStyle`**, never
  `.colorScheme(.dark)`.
- **The window comes from a live `UIWindowScene`** out of
  `UIApplication.shared.connectedScenes`.
- **The test methods are synchronous.** In an `async` `@MainActor` test the body
  holds the main actor for its whole duration, so a view's own `.task` never
  runs while the harness spins the run loop — `ConversationView` captures its
  loading spinner forever. Async seeding goes through
  `SnapshotAsync.resolve { ... }`.
- **One `autoreleasepool` per capture**, or the walk is SIGKILLed part way
  through by jetsam.

The corpus is **not** committed and nothing fails on pixel drift: the assertions
are "it renders", "it is not blank", "nothing draws outside the device width",
and "the files exist". `-b` reports drift against a corpus you captured
yourself; the rest is a human looking at the PNGs.

### The corpus is reproducible to a tolerance, not to the byte (RFC 130)

**Compare two corpora with `bin/snapshot-ios -b` — never with `md5`, `cmp`,
`git diff` or any other pixel-exact rule.** The contract the harness makes, and
the only one the platform can keep, is that a pixel moves when one of R, G, B
differs by more than `SnapshotBaseline.epsilon`; the value is deliberately not
restated here — that constant is the authority and the `-b` report prints the
one it applied, exactly as `bin/snapshot-ios` states the rule without the
numeral. Byte equality is not claimed anywhere.

The reason is a finding, not a fudge: iOS 26's Liquid Glass toolbar backdrop is
a compositing filter the render server evaluates off-tree, and its resolved
colour quantises **bistably** — capture an unchanged tree twice and the compose
button's capsule lands ±2/255 apart over ~0.4% of the frame. The discriminator
is that **button**, not chrome at large. Twelve of the 46 captures render
navigation chrome — six scenes wrapped in a `NavigationStack` with an inline
`.navigationTitle` (`settings-populated`, the three conversation scenes, both
conversation-list scenes) — and ten of them are byte-identical run to run.
`ConversationListView` is the only view in the corpus with a visible toolbar
**item**, and in the two captures that do wobble the bar, the title and the
glyph strokes are all byte-stable: every differing pixel lies inside the compose
button's glass capsule. So "has chrome" predicts nothing; "has a glass toolbar
button" is what does. The two conversation-list **light** captures have the same
button and come out byte-identical anyway. Dark mode is where that rounding
boundary is unlucky against a near-black capsule; **light is lower amplitude,
not immune** — the same mechanism, so the same rule applies to both. Do not
"fix" it with a longer settle or an extra draw pass: the state is bistable
rather than converging, and a third draw pass measurably reshuffles it rather
than pinning it.

Because a tolerance necessarily hides what sits below it, `-b` also reports each
compared scene's **maximum per-channel delta** and **how many pixels differ at
all**. Max delta 2 on a conversation-list scene is the noise above; max delta 7
across a broad area is a real change hiding under the tolerance, and the moved
fraction alone would have said "nothing moved". Those figures are reported,
never failed (RFC 122's posture on **drift** is unchanged). What does fail a
`-b` run is a comparison that could not be performed — a baseline PNG that
exists and cannot be decoded — because that is a broken instrument rather than
drift, and dropping it would shrink the compared-scene count the report presents
as evidence. A _missing_ baseline is ordinary and is merely noted.

**Read those figures as measured on the analysis downscale, not on the PNG.**
`-b` decodes through `SnapshotRaster`, which caps the longest side at 1024 for
the memory reason RFC 122 paid for, so the divisor is `ceil(longest / 1024)`,
which across this corpus takes three values: **1** — no downscale, so those
figures _are_ full resolution — for `coaching-budget-strip` (402×400pt at
captureScale 2 = 804×800px) and `usage-meter-strip` (402×480pt = 804×960px);
**2** for the device-sized scenes (402×874pt = 804×1748px) and the other short
strips; and **3** for the two tall ones, `conversation-markdown-worstcase`
(402×1400pt = 804×2800px) and `design-system-catalogue` (402×1500pt =
804×3000px). A delta of 2 survives that averaging; a single stray subpixel can
vanish into it, how much averaging it faces depends on which scene printed the
row, and a **pixel count is not comparable between scenes of different
heights**. The ±2/255 over ~0.4% of the frame quoted above is a full-resolution
measurement, which is why the repeatability assertion below does not downscale.

`SnapshotRepeatabilityTests.testOneSceneCapturedTwiceIsEqualUnderTheEpsilonRule`
executes that contract: one scene captured twice in one process, both captures
round-tripped through PNG (the corpus is PNG files, and the codec moves the
numbers), equal under the epsilon rule, with the observed maximum delta asserted
**strictly below** epsilon so a future OS widening the wobble fails loudly while
there is still headroom. It lives in a class of its own, outside the class
`bin/snapshot-ios` runs, so that failing it reports a problem instead of killing
corpus capture.

## Fixing a bug: failing test first

When resolving a reported bug, write a test that **reproduces the real failure
and fails first**, confirm it fails for the right reason, then fix and watch it
go green. The failing test must reproduce the bug at the real boundary (see
Boundary fidelity), not a synthetic stand-in. (Repo-wide policy: the `test`
skill.)
