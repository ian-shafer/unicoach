# The visual gate

`nix develop -c bin/test` cannot express "does it look right", so for UI work
the chain has **zero** mechanical authority without this.

## Rule

Any change touching `ios-app/**` must attach before/after screenshots to the run
as artifacts under `<run-scratch>/artifacts/`, and the reviewing step must
actually **look at them** — `attach_image` in Prime Agent — not merely note that
they exist.

## Capture

iOS scripts run under **system Xcode**, not the Nix dev shell (`bin/is-nix`
guards this in both directions). The family: `bin/build-ios`, `bin/install-ios`,
`bin/release-ios`, `bin/screenshot-ios`, all shim-tested by
`bin/ios-scripts-tests` — which `bin/test` does NOT run, since it needs system
Xcode. The pre-commit gate is blind to these scripts; a real run is the only
evidence they work.

`bin/screenshot-ios` is the capture tool (landed by the `ship/screenshot-ios`
run; `ios-app/DEPLOY.md` documents it):

    bin/build-ios simulator             # after any code change
    bin/rest-server-up                  # else you capture the offline screen
    OUT=$(bin/screenshot-ios simulator) # the PNG path is the ONLY stdout line

It boots the target's simulator, installs the build, **terminates any running
instance**, launches, waits for the first screen, and writes
`ios-app/build/screenshots/<target>-<UTC>.png`. `-o` sets an exact path, `-w`
the settle wait, `-d` a simulator by name or UDID.

Two of its behaviours are load-bearing here and were bought with real defects,
so do not "simplify" them away:

- It drives an **explicitly resolved device**, never the literal `booted`, so a
  second simulator booted elsewhere cannot be captured by mistake.
- It terminates before launching, because `simctl launch` does not relaunch an
  already-running app: without it a second run silently screenshots the OLD
  process — stale code that looks perfectly plausible.

The app talks to the backend baked in at build time, so with nothing serving
locally the capture is the app's "No Connection" state, not a real screen.

A bare launch only ever reaches the first screen. **For anything deeper, use
`bin/snapshot-ios`** (RFC 122), which is the answer to six runs' worth of
"nobody looked at the billing screens": it hosts each view in a `UIWindow`
inside the test process, so it needs no backend, no session and no booted app,
and it reaches every authenticated surface.

    OUT=$(bin/snapshot-ios)     # 32 PNGs, 16 scenes x light/dark; the dir is the only stdout line
    bin/snapshot-ios -b "$PREV" # report which scenes MOVED against an earlier corpus

It runs through `bin/test-ios`, so it uses this checkout's own simulator device
and cannot fight a sibling run. A new screen must be added to the catalogue in
`ios-app/UnicoachiOSTests/SnapshotScenes.swift` — the gate cannot see a view
that nobody listed. What it cannot show is a navigation bug _between_ screens:
each scene is constructed, not arrived at. `bin/screenshot-ios` remains the tool
for the running app.

Two rasterizing traps, both paid for with phantom defect reports, are recorded
in `SnapshotHost.swift` and must not be re-derived: **`ImageRenderer` is banned**
(it does not rasterize `ScrollView` content and ignores a SwiftUI `colorScheme`
override for asset colours), and **`layer.render(in:)` silently drops iOS 26's
Liquid Glass navigation chrome** (a title renders as its raw white mask —
correct on a dark capture, invisible on the light one). Use
`drawHierarchy(in:afterScreenUpdates:)` on a window attached to a live scene.

## Judging

Two different questions, judged in two different places. Confusing them is the
main failure mode.

**Token conformance is reviewed in the diff, not in the pixels.** The question
is whether the view reads `DSControl.height` or hardcodes `64`. Measuring
64.00pt in a PNG when the code already says `DSControl.height` verifies nothing
— the number is true by construction and the measurement can only ever agree.
Grep the diff for magic numbers, inline `.font(.system(`, and literal colours.
That is where exactness belongs, and where real drift becomes visible.

**The screenshot is for what the code cannot tell you.** Stock chrome leaking
through (a `.pickerStyle(.segmented)` grey capsule, a system-blue `.tint`), text
clipped or overflowing, a contrast failure, a layout that collapses in dark mode
or at large Dynamic Type, an element that is simply absent. In the RFC 116 run
every real defect came from looking at the image or reading the diff; the pixel
measurements confirmed three numbers the tokens already guaranteed and found
nothing.

**Tolerance: the mockup is a reference, not a contract.** It is one render at
one width. If a control reads as the motif, it passes. Do not chase pixel parity
— a dimension expressed as a fraction of its container is _better_ than one
transcribed from a mockup measurement, because it survives the device sizes the
mockup never showed. Prefer proportional and semantic layout over copied
constants, and one token over a repeated literal. Clean, legible view code beats
a closer match to the render; if the two conflict, the code wins and `DESIGN.md`
gets updated to say so.

Cite specifics rather than adjectives — but a specific is "the segmented control
is still stock grey, violating `DESIGN.md` §2" every bit as much as a number is.
What is banned is a review that could have been written without opening the
image.
