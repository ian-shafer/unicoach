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

A bare launch only ever reaches the first screen. Deeper screens need XCUITest
launch arguments — anything after `--` is forwarded to `simctl launch`, which is
the seam — but `ios-app` still has **no UI tests**, so that harness remains
unbuilt.

## Judging

Compare against `ios-app/DESIGN.md`, which carries measured values (64pt control
height, 16pt radius, `#EE7330`→`#E94577` gradient, the dark palette) rather than
adjectives. A screenshot review that does not cite a number from that file is
not a review.
