# RFC 126: A simulator per checkout

## Problem

CoreSimulator devices are **machine-global**. Everything else about a unicoach
checkout is not: `var/run/` gives each checkout its own `rest-server` /
`queue-worker` identity (so `bin/scripts-tests` can stop "this checkout's"
daemons without touching a sibling), and `ios-app/build/DerivedData` is under
`PROJECT_ROOT`, so two worktrees never share a build tree.

The simulator is the exception. `ios-app/env/simulator.env` pins

    UNICOACH_DESTINATION="platform=iOS Simulator,name=iPhone 17 Pro"

and every worktree, every `.claude/worktrees/*` copy, and every `ship` run
resolves that same one device. There is no lock anywhere in `bin/` guarding it
(`bin/file-lock` sources `bin/common`, which requires the Nix dev shell — the
one thing the iOS scripts are forbidden to run inside, so it is not even
available to them).

Concurrent runs therefore collide on one device, and the failure modes are not
loud:

- `bin/screenshot-ios` **terminates the running app before launching**, on
  purpose (`simctl launch` does not relaunch, so without it you screenshot the
  old process). Run from two checkouts, that terminate kills the sibling's app
  mid-capture.
- `simctl install` overwrites the bundle the sibling just installed. The next
  capture is a real screenshot of _someone else's build_, reviewed as if it were
  this run's. This is the dangerous one: it produces a plausible image, not an
  error.
- `xcodebuild test -destination 'platform=iOS Simulator,name=iPhone 17 Pro'` —
  the command `ios-app/DEPLOY.md` documents and agents type by hand — boots and
  drives the same device concurrently.

This was observed, not theorised. While three `ship` runs were open, a
`xcodebuild test` from `unicoach-ship-paywall-empty-state` held `iPhone 17 Pro`;
a `killall Simulator` issued from the main checkout shut that device down
underneath it, and the run simply started again a minute later. Nothing on
either side noticed.

## Decision

Give each checkout its own simulator device, created on demand and named after
the checkout, exactly as `var/run/` gives each checkout its own daemon identity.

Isolation rather than a lock, for three reasons: the lock primitive this repo
already has cannot be used from the iOS scripts (dev-shell dependency); a lock
cannot cover a hand-typed `xcodebuild test`; and serialising multi-minute
simulator work across three live runs costs throughput that separate devices do
not.

## Detailed Design

### `bin/ios-sim` — resolve this checkout's device

A new script in the `-ios` family (system Xcode, no `bin/common`, `bin/is-nix`
guard, same target-argument shape as `bin/build-ios`):

    bin/ios-sim [target]     # prints the UDID of this checkout's device
    bin/ios-sim -D [target]  # deletes it

It reads `ios-app/env/<target>.env` (default `simulator`) for
`UNICOACH_DESTINATION`, extracts the `name=<model>` component, and computes

    DEVICE_NAME = "<model> (<basename of PROJECT_ROOT>)"

e.g. `iPhone 17 Pro (unicoach-rfc-126)`. If a device with that exact name
exists, its UDID is printed. If not, one is **created** —
`xcrun simctl create "<DEVICE_NAME>" <devicetype-id>` — and the new UDID
printed. `create`, not `clone`: a clone copies the source device's data
directory, and nothing on the shared device is worth inheriting.

The device type is resolved by matching `<model>` against
`xcrun simctl list devicetypes` (`iPhone 17 Pro` →
`com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro`), and that lookup `fatal`s
with the actual list when it misses, since "that model is not installed locally"
is the expected human error.

The runtime is **not** chosen here: `create` is called with the name and the
device type only, and `simctl help create` documents that simctl then picks "the
newest runtime compatible with the device type". Ranking runtimes from the text
listing could reproduce the version ordering, but not the compatibility half —
the newest installed iOS runtime is not necessarily one this device type can
run, and only CoreSimulator holds that table. A failing `create` `fatal`s with
simctl's own message plus `xcrun simctl list runtimes`, so the common "no iOS
runtime is installed" case still diagnoses itself.

The name is the identity and lookup key; the UDID is what callers use. Nothing
is cached on disk: `simctl list` is the source of truth, so deleting the device
by hand (or from Simulator.app) self-heals on the next call.

Checkout identity is the directory **basename**, not the full path — short
enough to read in Simulator's device list, and unique across the layouts that
exist here (`/Users/ian/Work/unicoach-rfc-126`, `.claude/worktrees/<name>`). Two
checkouts with the same basename under different parents would share a device;
that is the documented limit of the scheme, not a silent one — `bin/ios-sim`
prints the name it resolved to stderr.

### `bin/screenshot-ios` — one new step in the precedence chain

`resolve_device` becomes, most explicit first:

1. `-d <device>`
2. `UNICOACH_SIMULATOR`
3. the destination's `id=<UDID>` component — an explicit UDID is explicit intent
4. **this checkout's device**, created on demand from the destination's
   `name=<model>` component
5. `fatal` (unchanged text: this is still how `prod-simulator`, which carries
   neither component, tells you to pass `-d`)

Step 4 replaces today's step "use the destination's `name=` verbatim". The two
existing escape hatches keep working and are how you deliberately drive the
shared device (`UNICOACH_SIMULATOR="iPhone 17 Pro"`), so nothing is taken away.

Note the ordering change: `id=` is now consulted before `name=`. Today `name=`
wins and `id=` is only a fallback for destinations that lack one. No checked-in
target sets both, so no existing target changes meaning.

### `bin/test-ios` — the iOS unit suite, on this checkout's device

`ios-app/UnicoachiOSTests/TESTING.md` documents `xcodebuild test` as a command
to type (`DEPLOY.md` refers to it in prose). Typed, it always names the shared
device. A wrapper in the `-ios` family fixes that at the same time as it becomes
the documented command:

    bin/test-ios [target] [-- <xcodebuild args>...]

    xcodebuild test -project ios-app/UnicoachiOS.xcodeproj -scheme UnicoachiOS \
      -destination "platform=iOS Simulator,id=$(bin/ios-sim <target>)"

Same dev-shell guard, same simulator-only guard as `screenshot-ios` (a device
destination is refused), same `-derivedDataPath` as `bin/build-ios` so the two
share a build tree within the checkout.

### `bin/build-ios` is deliberately unchanged

A `xcodebuild build` against a simulator destination does not boot a device, and
the produced `.app` is not tied to one, so the shared destination is harmless
there. Leaving it alone keeps the diff to the scripts that actually touch a
running device.

## Files Modified

| File                                  | Change                                                                |
| ------------------------------------- | --------------------------------------------------------------------- |
| `bin/ios-sim`                         | **new** — resolve/create/delete this checkout's simulator device      |
| `bin/test-ios`                        | **new** — `xcodebuild test` pinned to this checkout's device          |
| `bin/screenshot-ios`                  | `resolve_device`: per-checkout device between `id=` and the fatal     |
| `bin/ios-scripts-tests`               | shim cases for both new scripts and the new resolution step           |
| `ios-app/DEPLOY.md`                   | document `bin/ios-sim` / `bin/test-ios`; per-checkout device section  |
| `ios-app/UnicoachiOSTests/TESTING.md` | its `xcodebuild test` recipe becomes `bin/test-ios`                   |
| `bin/README.md` (if present)          | list the two new scripts                                              |
| `ios-app/env/simulator.env`           | comments only — `name=` is now a _model_ selector; name its consumers |

Not modified: `bin/build-ios`, `bin/install-ios`, `bin/release-ios`. The
checked-in destination VALUE stays as it is — it is now a _model_ selector
rather than a device selector, which is what it always meant — but
`ios-app/env/simulator.env`'s comments do change: the definition site claimed
`bin/build-ios` was its only consumer, and a reader there should not have to
carry this RFC in their head to know what `name=` now selects.

## Implementation Plan

1. `bin/ios-sim`, modelled on `bin/screenshot-ios`'s preamble (dev-shell guard,
   `UNICOACH_ENV_DIR` override, target argument, `fatal` texts that name the
   command to run next).
2. `bin/screenshot-ios`: reorder `id=` ahead of `name=`, and route `name=`
   through `bin/ios-sim`. Update the block comment and the `--help` text, both
   of which state the precedence explicitly. Move the
   `DEVICE="$(resolve_device)"` call to after the built-app existence check,
   since resolution can now CREATE a device and a run that fatals for want of a
   build must not leave one behind.
3. `bin/test-ios`.
4. `bin/ios-scripts-tests` cases (below).
5. `ios-app/DEPLOY.md`: a short "one simulator per checkout" section, and the
   `xcodebuild test` references (here and in
   `ios-app/UnicoachiOSTests/TESTING.md`, which holds the literal recipe)
   pointed at `bin/test-ios`.

## Tests

All shim-based in `bin/ios-scripts-tests` (no real Xcode, no booted device); the
`xcrun` shim gains fixture-driven `simctl list devices` / `list devicetypes` /
`list runtimes` output and records `simctl create`.

`bin/ios-sim`:

- prints the existing UDID when a device named `<model> (<checkout>)` is in the
  `list devices` fixture, and does **not** call `simctl create`
- calls `simctl create` with the right devicetype and **no runtime argument**
  when it is not — the runtime is simctl's choice — and prints the created UDID
- the UDID is the only thing on stdout (the family's stdout contract), and
  create output that is not UDID-**shaped** is fatal rather than relayed
- fatal, listing what is available, when the model matches no devicetype
- fatal, carrying simctl's own message and the installed runtimes, when
  `simctl create` fails (the "no iOS runtime is installed" case)
- fatal, naming the listing and carrying xcrun's own text, when `simctl list`
  itself fails — otherwise the caller's command substitution dies wordlessly
  under `set -e`
- fatal when the target's destination has no `name=` component
- fatal when **two** devices carry this checkout's name (simctl does not enforce
  unique names, and two first-runs in one checkout can each list-then-create),
  naming `bin/ios-sim -D` as the way out
- refuses inside the Nix dev shell (`UNICOACH_DEV_SHELL=impure`)
- `-D` deletes by UDID and is a no-op when the device does not exist; it deletes
  **every** device carrying the name, unavailable ones included — resolution
  skips those because they cannot be booted, but leaving one behind is what
  makes the next resolve ambiguous

`bin/screenshot-ios` (extending the existing precedence cases):

- with no `-d` and no `UNICOACH_SIMULATOR`, the device passed to
  `simctl boot/install/launch/io` is the per-checkout UDID, not the
  destination's `name=`
- `-d` and `UNICOACH_SIMULATOR` still win, and neither creates a device
- a destination carrying `id=` is used verbatim, with no device created

`bin/test-ios`:

- `xcodebuild test` is invoked with
  `-destination platform=iOS Simulator,id=<per-checkout UDID>`
- refuses a device target and refuses inside the dev shell
- args after `--` are forwarded to `xcodebuild`

`nix develop -c bin/test` does not run `bin/ios-scripts-tests` (it needs system
Xcode), so the evidence for this RFC is a direct `bin/ios-scripts-tests` run
plus one real `bin/screenshot-ios simulator` capture showing the new device in
use.
