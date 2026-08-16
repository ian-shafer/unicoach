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
guards this in both directions). Existing siblings: `bin/build-ios`,
`bin/install-ios`, `bin/release-ios`, `bin/ios-scripts-tests`.

`bin/screenshot-ios` **does not exist yet** — it is the first planned `ship`
run. Intended shape, next to `install-ios` but driving a simulator:

    xcrun simctl boot "<device>"
    xcrun simctl install booted "<app-path-under-ios-app/build/DerivedData>"
    xcrun simctl launch booted coach.uni.UnicoachiOS
    xcrun simctl io booted screenshot <out.png>

A bare launch only ever reaches the login screen. Deeper screens need XCUITest
launch arguments; `ios-app` has **no UI tests** today, so that harness is part
of the same work.

## Judging

Compare against `ios-app/DESIGN.md`, which carries measured values (64pt control
height, 16pt radius, `#EE7330`→`#E94577` gradient, the dark palette) rather than
adjectives. A screenshot review that does not cite a number from that file is
not a review.
