# Deploying UnicoachiOS to a Physical iPhone

This guide covers building, signing, and installing the `UnicoachiOS` app on a
registered physical iPhone for on-device testing. The simulator workflow needs
none of this — see [UnicoachiOSTests/TESTING.md](UnicoachiOSTests/TESTING.md).

The scripts here run under **system Xcode**, not the Nix dev shell. Do not wrap
them in `nix develop`; just run `bin/build-ios` / `bin/install-ios` (and
`bin/release-ios` / `bin/ios-sim` / `bin/test-ios` / `bin/screenshot-ios`,
below) directly. They all call `bin/is-nix` and refuse to run if launched inside
the dev shell, because there `xcrun` is shadowed by a stub and
`DEVELOPER_DIR`/`SDKROOT` point into the Nix store — silently targeting the
wrong toolchain.

## Named build targets

A **build target** is a file `ios-app/env/<target>.env` sourced by
`bin/build-ios <target>` and `bin/install-ios <target>` (both default to
`local`). Its settings split into three buckets by sensitivity and ownership:

1. **Public, environment-specific** — checked in, in the target file:
   `UNICOACH_DESTINATION` (required), `UNICOACH_CONFIGURATION` (optional,
   default `Debug`), and the optional `UNICOACH_BACKEND_URL`. The backend URL is
   not a secret, so a deploy target file is shareable and checked in.
2. **Per-developer / machine-specific** — local, gitignored, in `signing.env`:
   `UNICOACH_DEVELOPMENT_TEAM` (required for device builds) and
   `UNICOACH_DEVICE` (optional). One `signing.env` is shared across all targets,
   so a device build of any target inherits signing without restating it.
3. **Actually secret** — local, gitignored: `UNICOACH_CLIENT_KEY`, relevant only
   when the server's client-key gate is enabled. Its per-target home is the
   `<target>.local.env` seam (below); today it is sourced from the repo `.env`.

A target is a **simulator** target iff its `UNICOACH_DESTINATION` contains the
substring `Simulator`; otherwise it is a **device** target. The discriminator
governs both signing (device only) and the `install-ios` device-only guard.

The checked-in deploy targets are `prod` (device) and `prod-simulator`
(simulator) — see [Deploy targets](#deploy-targets-prod--prod-simulator).

### The `<target>.local.env` secret seam (reserved, not implemented)

A per-target local file `ios-app/env/<target>.local.env` (gitignored) is
reserved as the future home of a per-target `UNICOACH_CLIENT_KEY`. When
implemented, `bin/build-ios` will source it **last** — after the target file,
the repo `.env`, and `signing.env` — so it overrides everything for that one
target. The insertion point is marked by a comment in `bin/build-ios`; no code,
staging target, or per-target client-key overlay ships yet. The existing
`ios-app/env/*.env` gitignore rule already covers `<target>.local.env`.

## How the backend URL is resolved: honor-if-set, else derive

`bin/build-ios` resolves `UNICOACH_BACKEND_URL` by one of two paths, decided
after the target file and the repo `.env` are sourced:

- **Honor:** if `UNICOACH_BACKEND_URL` is set and non-empty (in the target file
  or the environment), it is forwarded to `xcodebuild` **verbatim** — no
  derivation, no validation. A checked-in target's URL is reviewed at commit
  time, so it is not re-parsed (a bare-IP host inside an explicit URL is not
  separately rejected). The honor path exists for an externally-terminated HTTPS
  deployment URL — a shape the derived `http://host:port` form cannot express —
  though the checked-in `prod` targets reach the deployment by the deploy-derive
  path below, not by setting an explicit URL.
- **Derive (deploy):** if `UNICOACH_BACKEND_URL` is empty and the target sets
  `UNICOACH_DEPLOY`, the build sources `.env.prod` (the single source of the
  prod domain) and composes `UNICOACH_BACKEND_URL = https://api.$APP_DOMAIN` —
  HTTPS, TLS terminated at the ALB, `api.` subdomain, no explicit port (today
  `https://api.uni.coach`).
- **Derive (local):** if `UNICOACH_BACKEND_URL` is empty and `UNICOACH_DEPLOY`
  is unset, the build composes
  `UNICOACH_BACKEND_URL = http://$APP_DOMAIN:${SERVER_PORT:-8080}` from the repo
  `.env` (the same single source the server reads). `APP_DOMAIN` defaults to
  `localhost`; a bare-IP literal is rejected (an invalid cookie `Domain`). This
  is the local-dev path.

For the **derive** path, the deploy host is defined **once**, as `APP_DOMAIN` in
the repo `.env`. Both sides derive from that single value, so they cannot
disagree:

- **The server** derives `session.cookieDomain` from `APP_DOMAIN`
  (`rest-server.conf`). The session cookie is issued with
  `Domain =
  session.cookieDomain`; for the device to store and replay it, that
  `Domain` must match the host the app targets.
- **The build** derives
  `UNICOACH_BACKEND_URL = http://$APP_DOMAIN:${SERVER_PORT:-8080}` in
  `bin/build-ios` and bakes it into the app bundle.

Because there is one source, there is no second value to reconcile. The only
residual step is temporal: **bounce the server after changing `APP_DOMAIN`** so
it reloads `cookieDomain`. A stale bare-IP `APP_DOMAIN` in `.env` does **not**
break an explicit-URL (honor) build: the honor path skips derivation and its
bare-IP check entirely.

However the URL is resolved, the build and install mechanics are the same:

- The app reads its backend URL from an `Info.plist` key (`UnicoachBackendURL`)
  baked at build time from the `UNICOACH_BACKEND_URL` build setting. A device
  process launched standalone cannot receive scheme environment variables, so
  the value must live in the bundle. An empty or unparseable value falls back to
  `http://localhost:8080` — which on a device is the phone itself, not your Mac.
- The app reads its client key from an `Info.plist` key (`UnicoachClientKey`)
  baked at build time from the `UNICOACH_CLIENT_KEY` build setting. Unlike the
  derived `UNICOACH_BACKEND_URL`, this is a raw secret read straight from
  `UNICOACH_CLIENT_KEY` in the repo `.env` and passed verbatim to `xcodebuild`
  by `bin/build-ios` (blank by default — an unset variable bakes blank). When
  non-blank the app sends it on every request as the `X-Unicoach-Client-Key`
  header, which the server's client-key gate checks; a blank key sends no
  header, which the disabled local gate accepts. The key must never be committed
  — it is supplied from the environment / Secrets Manager only for builds
  destined for a gated deployment. The baked-in key is extractable from the
  distributed binary; this is a deliberate raise-the-bar control, not strong
  security.
- `bin/build-ios <target>` builds (and, for device targets, signs) the app,
  baking the resolved `UNICOACH_BACKEND_URL` into the bundle.
- `bin/install-ios <target>` installs the most recent device build to the iPhone
  via `xcrun devicectl`.

## Prerequisites

1. **Paid Apple Developer Program team, signed into Xcode.** Xcode → Settings →
   Accounts → add your Apple ID and select the team. A paid membership yields
   ~1-year provisioning profiles; free personal teams (7-day profiles) are not
   supported here.
2. **Device paired, trusted, and in Developer Mode.** Connect the iPhone, tap
   _Trust_ on the device, and enable Settings → Privacy & Security → Developer
   Mode (iOS 16+), then reboot when prompted.
3. **Tailscale on both ends (recommended transport).** Install Tailscale on the
   Mac and the iPhone, sign both into the same tailnet, and enable MagicDNS. The
   phone then reaches the Mac at a stable name `<host>.<tailnet>.ts.net` over
   any network, surviving DHCP changes — a natural fit for `APP_DOMAIN`, which
   must be a DNS hostname. A same-network LAN hostname is the fallback.
4. **Server running and reachable.** `rest-server` already binds `0.0.0.0:8080`,
   so it listens on the Tailscale interface with no change. Start it the usual
   way (`bin/daemon-up` etc.).
5. **`APP_DOMAIN` set once + server bounced** (see below) — required for the
   session to persist on-device.
6. **Inbound 8080 allowed.** Allow inbound connections to the server: either via
   the Tailscale interface, or by permitting inbound 8080 in the macOS firewall
   (System Settings → Network → Firewall).
7. **Sign in with Apple enabled on the App ID, for a real Apple authorization.**
   The `coach.uni.UnicoachiOS` App ID in the Apple Developer portal must have
   the **Sign in with Apple** capability turned on. A simulator build does not
   need this — `UnicoachiOS.entitlements` is ad-hoc signed and its
   `com.apple.developer.applesignin` key is simulator-embedded without
   contacting the portal, so compilation and the unit suite never depend on it —
   but a device or TestFlight build fails provisioning without it, and only a
   real, portal-backed App ID can complete a genuine Apple authorization.

**Cross-artifact coupling (cannot be derived or enforced automatically):**
`.env.prod`'s `APPLE_CLIENT_IDS` must equal this project's
`PRODUCT_BUNDLE_IDENTIFIER` (`coach.uni.UnicoachiOS`) — the backend's Apple
verifier checks the token's `aud` against `APPLE_CLIENT_IDS`, and a native Apple
authorization's `aud` is always the app's bundle identifier. See `.env.prod`'s
own comment on `APPLE_CLIENT_IDS` for the reciprocal note (RFC 111 for Google's
identical `GOOGLE_CLIENT_IDS` coupling; RFC 113 added the Apple side). Renaming
the bundle identifier without updating `.env.prod` breaks every Apple sign-in
silently (a `401` at the route, not a build failure) — there is no automated
check across this boundary.

## Set the deploy host once: `APP_DOMAIN`

Set `APP_DOMAIN` in the repo `.env` to the host the phone reaches your Mac at —
a Tailscale MagicDNS name or a same-network LAN hostname:

```sh
# .env
APP_DOMAIN=your-mac.your-tailnet.ts.net
```

Then **bounce the server** so it reloads `session.cookieDomain` from the new
value, and run `bin/build-ios` so the new host is baked into the bundle. That is
the whole configuration: the server's cookie `Domain` and the app's backend host
both come from this one line, so they cannot drift apart.

**Caveat:** a **bare IP** host is an invalid cookie `Domain` per RFC 6265 and
will not yield a persisted session. `bin/build-ios` rejects a bare-IP
`APP_DOMAIN` before building and directs you to a DNS hostname. Use a MagicDNS
name (or any DNS hostname) — that is the path that retains login on-device.
`localhost` (the default) is accepted and is what the simulator uses.

## First-time setup

Copy the templates and fill them in (both are gitignored):

```sh
cp ios-app/env/signing.env.example ios-app/env/signing.env
cp ios-app/env/local.env.example   ios-app/env/local.env
```

- `signing.env` — `UNICOACH_DEVELOPMENT_TEAM` (your Apple team id; required for
  any device build) and optional `UNICOACH_DEVICE` (a device UDID). It is shared
  across every target so device builds inherit signing creds without restating
  them.
- `local.env` — only `UNICOACH_DESTINATION="generic/platform=iOS"` (and an
  optional `UNICOACH_CONFIGURATION`). It no longer carries a backend host; the
  host comes from `APP_DOMAIN` in `.env`. `local` is the default target, so both
  scripts use it when you pass no argument. A target may set
  `UNICOACH_BACKEND_URL` to skip derivation (see
  [the backend-URL resolution rule](#how-the-backend-url-is-resolved-honor-if-set-else-derive)).

Target files under `ios-app/env/` are gitignored except the shared
`simulator.env`, the checked-in `prod.env` / `prod-simulator.env` deploy
targets, and the `*.env.example` templates, so your personal files are never
committed.

## Build and install

```sh
bin/build-ios            # builds + signs the `local` target
bin/install-ios --launch # installs to the device and launches it
```

`bin/install-ios` selects the target device from `UNICOACH_DEVICE` when set;
otherwise it auto-detects the single connected device (and fails fast if zero or
more than one is connected — set `UNICOACH_DEVICE` to disambiguate). The
`--launch` flag additionally starts the app on the device (fire-and-forget).

On the first device build, `-allowProvisioningUpdates` lets `xcodebuild`
register the device and create or refresh the managed provisioning profile
against the team's portal.

## Deploy targets: `prod` / `prod-simulator`

Two checked-in deploy targets build against the live AWS deployment at
`https://api.uni.coach`. Neither restates the backend URL: both set
`UNICOACH_DEPLOY=1`, so `bin/build-ios` sources `.env.prod` and derives
`UNICOACH_BACKEND_URL=https://api.$APP_DOMAIN` (HTTPS, TLS terminated at the
ALB, no port). The prod domain is set once in `.env.prod`. Both omit
`UNICOACH_CONFIGURATION` (inheriting `Debug`):

- **`prod`** — device target (`UNICOACH_DESTINATION="generic/platform=iOS"`).
  Like any device build it requires `signing.env` / `UNICOACH_DEVELOPMENT_TEAM`.

  ```sh
  bin/build-ios prod              # builds + signs against the deployment
  bin/install-ios --launch prod   # installs to the device and launches it
  ```

- **`prod-simulator`** — simulator target
  (`UNICOACH_DESTINATION="generic/platform=iOS Simulator"`). A simulator build
  does not sign, so any contributor can build an app targeting the live
  deployment without an Apple account.

  ```sh
  bin/build-ios prod-simulator    # builds against the deployment, no signing
  ```

  `bin/install-ios prod-simulator` is rejected by the device-only guard
  (`UNICOACH_DESTINATION` contains `Simulator`); installs are device-only.

The deployed app reaches `https://api.uni.coach` over HTTPS under the existing
`NSAllowsArbitraryLoads: true` ATS exception (which permits, not requires, plain
HTTP) — no transport-security change.

### The deployment seeds `APP_DOMAIN` so the session persists

For a `prod`/`prod-simulator` build to keep a session across a relaunch, the
server's `Set-Cookie` `Domain` must cover the host the app calls. The server
derives `session.cookieDomain` from `APP_DOMAIN` (`rest-server.conf`), so the
deployment seeds it: `infra/ssm.tf` sets `APP_DOMAIN = var.app_domain` (the
apex) as a non-secret SSM `String` parameter, which `render-env` flattens into
`/etc/unicoach/env` (the server unit's `EnvironmentFile`), resolving
`cookieDomain` to the apex `uni.coach`. Because the cookie `Domain` is the apex,
it spans the `api.uni.coach` host the app calls. The session cookie is already
marked `Secure` (`SESSION_COOKIE_SECURE = "true"`), correct for HTTPS.

There is a single domain knob — `APP_DOMAIN` in `.env.prod` (the Route53 apex).
Infra reads it as `TF_VAR_app_domain` (so `var.app_domain` feeds both the SSM
`APP_DOMAIN` and the derived `api.$app_domain`), and `bin/build-ios` sources the
same `.env.prod` to derive the app's
`UNICOACH_BACKEND_URL=https://api.$APP_DOMAIN`. Both the server's cookie
`Domain` and the app's backend host therefore come from that one value and
cannot drift; change the domain once in `.env.prod`.

## Distributing via TestFlight: `bin/release-ios`

`bin/install-ios` puts a build on a cabled iPhone over USB. To distribute
over-the-air to testers, use **TestFlight**, which means uploading a signed
App-Store-distribution build to App Store Connect. `bin/release-ios` does that —
it is the archive/upload sibling of `bin/build-ios`, and runs under **system
Xcode**, not the Nix dev shell (same dev-shell guard).

```sh
bin/release-ios                  # archive + upload `prod` to App Store Connect
bin/release-ios --no-upload      # archive + export a signed .ipa, then stop
bin/release-ios <target>         # release a different device target
```

It reuses the named-target model: it sources `ios-app/env/<target>.env` for the
destination and the honor-if-set `UNICOACH_BACKEND_URL` exactly as
`bin/build-ios` does, but it **defaults to the `prod` target** (a release goes
against the live backend) and differs in three deliberate ways:

- **Always Release, always signed.** The target's `UNICOACH_CONFIGURATION` is
  ignored (a release is built `Release`), and a **simulator target is rejected**
  — distribution requires a signed device build. Signing is mandatory:
  `signing.env` / `UNICOACH_DEVELOPMENT_TEAM` must be present, and Xcode
  automatic signing creates/refreshes an **App Store distribution** profile
  under `-allowProvisioningUpdates`.
- **A unique build number.** App Store Connect rejects a re-upload that reuses a
  `CFBundleVersion`, so the build number is taken from `UNICOACH_BUILD_NUMBER`
  if set, else **derived from the HEAD commit count**
  (`git rev-list --count
  HEAD`) — unique and monotonic on a linear history,
  with no project-file edit. It is injected as `CURRENT_PROJECT_VERSION`, which
  `Info.plist` resolves into `CFBundleVersion` (the same variable-substitution
  seam as `UnicoachBackendURL`). `UNICOACH_MARKETING_VERSION`, when set,
  overrides `CFBundleShortVersionString`; otherwise the project default (`1.0`)
  applies.
- **API-key upload.** The default path uploads straight from `xcodebuild` using
  an **App Store Connect API key** — no Apple-ID password, no keychain prompt,
  CI-friendly. Credentials live in the gitignored `ios-app/env/appstore.env`
  (the secret bucket, alongside `UNICOACH_CLIENT_KEY`). `--no-upload` skips this
  entirely and just leaves a signed `.ipa` under `ios-app/build/export/`.

### First-time TestFlight setup

1. **Create the app record.** In
   [App Store Connect](https://appstoreconnect.apple.com) → My Apps → **+** →
   New App, for bundle id `coach.uni.UnicoachiOS` (it must exist as an
   Identifier in the Developer portal; the first signed archive creates it via
   `-allowProvisioningUpdates` if absent).
2. **Mint an App Store Connect API key.** Users and Access → Integrations → App
   Store Connect API → generate a key with the **App Manager** role. Note the
   **Issuer ID** (a UUID, shared by all keys) and the **Key ID**, and download
   `AuthKey_<KeyID>.p8` — it is downloadable **only once**. Store it outside the
   repo (e.g. `~/.appstoreconnect/private_keys/`).
3. **Fill in `appstore.env`.** Copy the template and set the three values:

   ```sh
   cp ios-app/env/appstore.env.example ios-app/env/appstore.env
   # UNICOACH_ASC_KEY_ID, UNICOACH_ASC_ISSUER_ID, UNICOACH_ASC_KEY_PATH
   ```

4. **Upload, then add testers.** Run `bin/release-ios`. After a few minutes of
   App Store Connect processing, the build appears under the app's
   **TestFlight** tab. Add it to **Internal Testing** (up to 100 team members,
   no review, available immediately) or **External Testing** (up to 10,000 via
   email/link, first build needs a short Beta App Review). The per-build
   export-compliance prompt is pre-answered by
   `ITSAppUsesNonExemptEncryption = false` in `Info.plist` (the app uses only
   standard HTTPS).

The build targets the live `https://api.uni.coach` deployment under the existing
`NSAllowsArbitraryLoads` ATS exception — no transport-security change, same as
the `prod` device build.

## One simulator per checkout: `bin/ios-sim`

CoreSimulator devices are **machine-global**; everything else about a checkout
is not (`var/run/` gives each checkout its own daemon identity,
`ios-app/build/DerivedData` its own build tree). `ios-app/env/simulator.env`
pins `name=iPhone 17 Pro`, so every worktree and every `ship` run used to
resolve that one device — and the collisions were quiet: a `screenshot-ios`
capture terminates the running app (deliberately, so it does not shoot the old
process) and kills the sibling's; a `simctl install` overwrites the bundle the
sibling just installed, so the next capture is a real screenshot of **someone
else's build**. RFC 126 gives each checkout its own device instead:

```sh
bin/ios-sim              # prints this checkout's device UDID, creating it if needed
bin/ios-sim -D           # deletes it
bin/ios-sim -G           # lists this repo's ORPHANED devices (see below)
bin/ios-sim -G -f        # ...and deletes them
```

The device is named `<model> (<repo>/<checkout>)` — e.g.
`iPhone 17 Pro (unicoach/unicoach-rfc-126)` — where `<repo>` is the basename of
the **main checkout** (the first entry of `git worktree list --porcelain`),
`<checkout>` is this checkout's directory basename, and `<model>` is the `name=`
component of the target's `UNICOACH_DESTINATION`. That **name is the identity
and the lookup key**; the UDID is what callers use, and it is the only thing on
stdout, so `-destination "platform=iOS Simulator,id=$(bin/ios-sim)"` works.
Nothing is cached on disk: `xcrun simctl list devices` is the source of truth,
so deleting the device by hand or from Simulator.app self-heals on the next
call. A missing device is **created** (`simctl create`, never `clone` — nothing
on the shared device is worth inheriting) against the exact device type for the
model, with no runtime named — `simctl` itself picks the newest runtime
compatible with that device type. An uninstalled model fatals with the list of
the ones you do have, and a failing `create` fatals with simctl's own message
and the installed runtime list (which is what "no iOS runtime installed" looks
like).

The checked-in `UNICOACH_DESTINATION` is unchanged and is now read as a **model
selector**, not a device selector. `bin/screenshot-ios` and `bin/test-ios` both
route through `bin/ios-sim`; `bin/build-ios` deliberately does not, because a
simulator `xcodebuild build` boots no device and the produced `.app` is not tied
to one.

### Orphans: `bin/ios-sim -G`

A device outlives the directory it was made for. Delete a worktree without
running `bin/ios-sim -D` first — which is what always happens — and its device
stays on the machine forever, with nothing left to say what it was for.

`bin/ios-sim -G` collects them: every device named `<model> (<repo>/<checkout>)`
for **this** repo whose `<checkout>` is not the basename of a live
`git worktree list --porcelain` entry. Git is the authority on which checkouts
exist, so an orphan is derived, not guessed — and a git that cannot answer is
fatal rather than an empty list, which would read as "everything is an orphan".

That is what the `<repo>` half of the name buys. A `.claude/worktrees/` checkout
is called something like `sad-clarke-6b73f4`, and a device named
`iPhone 17 Pro (sad-clarke-6b73f4)` is indistinguishable from one a human made
by hand; tagged with the repo it is provably ours, which is what lets `-G` run
unattended.

`-G` **lists only**; `-G -f` deletes (shutdown, then delete). Its stdout is the
orphan list — one `<UDID>  <name>` line each, nothing else, empty when there are
none — and everything else goes to stderr. It takes no target and reads no env
file: it is about devices, not about a build. Devices carrying another repo's
tag, no tag at all (including the pre-repo-tag `<model> (<checkout>)` names), or
a live checkout's are never touched.

**Devices from before the `<repo>` tag** are migrated by being used: the first
`bin/ios-sim` in a checkout that still has a single available
`<model> (<checkout>)` device **adopts** it — `simctl rename` in place, so the
installed app and its data survive — instead of creating a second one. That only
fires for checkouts that resolve again, so a pre-tag device whose checkout is
already gone is reachable by nothing here (`-G` will not claim it: nothing
proves it is this repo's); delete those once by hand with
`xcrun simctl list devices` and `xcrun simctl delete <UDID>`.

`simctl` does not enforce unique device names, so a lost race (two first-runs in
one checkout) or a runtime removal can leave **two** devices carrying the name.
That is refused rather than resolved to an arbitrary one; `bin/ios-sim -D`
clears the lot — every device with the name, unavailable ones included — and the
next call creates one fresh device.

Two checkouts of the same repo whose directories share a basename share a device
— the documented limit of the scheme, and not a silent one: `bin/ios-sim` prints
the name it resolved on stderr every time. To deliberately drive the shared
device, pass `-d` or set `UNICOACH_SIMULATOR="iPhone 17 Pro"`; both beat the
per-checkout device.

## Running the unit suite: `bin/test-ios`

```sh
bin/test-ios                                    # the whole suite
bin/test-ios simulator -- -only-testing:UnicoachiOSTests/PaywallViewModelTests
```

`xcodebuild test` pinned to this checkout's device, with the same dev-shell
guard as its siblings, the same simulator-only guard as `bin/screenshot-ios`,
and the same `-derivedDataPath` as `bin/build-ios` (so the two share a build
tree within the checkout). Everything after `--` is forwarded to `xcodebuild`.
Prefer it to a hand-typed `xcodebuild test`, which names the shared device — see
[UnicoachiOSTests/TESTING.md](UnicoachiOSTests/TESTING.md).

Note that `nix develop -c bin/test` does **not** run the iOS suite (it needs
system Xcode, which the dev shell shadows); a green repo gate says nothing about
an iOS change.

## Snapshot corpus: `bin/snapshot-ios`

`bin/screenshot-ios` photographs the **running app**, which means it only ever
reaches the **first screen** and needs a backend behind it. `bin/snapshot-ios`
photographs **any view**: it hosts each screen in a `UIWindow` inside the test
process, so it needs no backend, no session, no StoreKit and no booted app — and
it therefore reaches the authenticated and billing screens (the conversation,
Settings, the subscription rail, the paywall) that the running-app capture never
could. What it cannot do is show you a navigation bug _between_ screens: every
scene is constructed directly, not arrived at.

```sh
bin/snapshot-ios                          # prints the corpus directory
bin/snapshot-ios -b <a-previous-corpus>   # also reports which scenes moved
```

It is a front end over `bin/test-ios` above — it runs
`bin/test-ios <target> -- -only-testing:UnicoachiOSTests/SnapshotTests
-testLanguage en -testRegion US`,
and calls `xcodebuild` nowhere itself. So it inherits, rather than repeats,
everything that section describes: the project, the scheme, `bin/build-ios`'s
`-derivedDataPath`, the simulator-only guard — and, the reason the delegation
exists, **this checkout's own simulator device** (`bin/ios-sim`, RFC 126). Two
checkouts capturing at once therefore cannot collide; a duplicated
`-destination ...,name=iPhone 17 Pro` here would put them straight back on the
one machine-global device.

What it adds is the corpus: it writes `<scene>-light.png` / `<scene>-dark.png`
for every scene in the catalogue, into `ios-app/build/snapshots/latest` by
default (under the gitignored `build/` tree) or wherever `-o` says. The
directory is CLEARED at the start of a run, so a deleted scene leaves no stale
PNG pretending to be current. The corpus directory is the only line on stdout —
`DIR=$(bin/snapshot-ios)` — and all progress, `bin/test-ios`'s included, goes to
stderr.

Like its siblings it runs under **system Xcode** and refuses inside the Nix dev
shell with its own name in the message. There is **no `-d`**: the device is this
checkout's and the model comes from the target's env file, so a second device
selector would be a contradiction — `bin/ios-sim` is where that resolution
lives. The run is pinned to `en`/`US`/UTC so a machine's locale cannot move the
pixels. Afterwards it proves the corpus is non-empty, because a
`xcodebuild test` that ran ZERO tests exits 0 — the signature of a scene file
that was never registered in `project.pbxproj`.

Nothing here is a committed golden and `-b` never fails the run: it compares
against a corpus a previous run wrote (typically one captured from the base
commit), reports the fraction of pixels that moved per scene, and writes a
red-overlay `<scene>.diff.png` for the ones that did. Adding a scene is one
entry in `SnapshotCatalogue.scenes` — see
[UnicoachiOSTests/TESTING.md](./UnicoachiOSTests/TESTING.md).

## Simulator screenshots: `bin/screenshot-ios`

Everything above targets a physical iPhone. For a **simulator** screenshot — the
artifact a UI review is judged from — use `bin/screenshot-ios`, the simulator
sibling of `bin/install-ios`:

```sh
bin/build-ios simulator          # once, or after any code change
bin/rest-server-up               # else you capture the app's offline screen
bin/screenshot-ios simulator     # prints the PNG path on stdout
```

It boots the target's simulator, installs the built `.app`, terminates any stale
instance, launches, waits for the first screen to render, and writes
`ios-app/build/screenshots/<target>-<UTC timestamp>.png` (under the gitignored
`build/` tree). The path is the only thing on stdout, so a caller can capture it
with `OUT=$(bin/screenshot-ios simulator)`; all progress goes to stderr.

Like its siblings it runs under **system Xcode** and refuses inside the dev
shell. It is simulator-only — the inverse of `install-ios`'s device-only guard.
`-o` writes an exact path, `-w` tunes the settle wait, `-d` picks a simulator by
name or UDID (needed for a target such as `prod-simulator`, whose destination
names no device), and anything after `--` is forwarded to `xcrun simctl launch`
— the seam for driving the app to a particular screen before the capture.

The simulator it drives is **this checkout's own device**
([above](#one-simulator-per-checkout-binios-sim)), resolved most-explicit-first:
`-d`, else `UNICOACH_SIMULATOR`, else the destination's `id=<UDID>` component,
else `bin/ios-sim` on the destination's `name=<model>`. A target carrying
neither component (`prod-simulator`) still needs `-d` or `UNICOACH_SIMULATOR`.

### The StoreKit trap: a configuration is bound to the launch, not the artifact

`ios-app/UnicoachiOS.storekit` is the local StoreKit catalogue the app is
supposed to be exercised against in the simulator. It is **not** part of the
built app: it is referenced by `UnicoachiOS.xcscheme` and **injected by the
scheme's action at launch time**. Nothing in the `.app` bundle mentions it.

The consequence is easy to miss and unpleasant when hit: **any simulator launch
that is not driven by the scheme has no StoreKit configuration at all** and
would fall through to the real App Store. `xcrun simctl launch` — which is
exactly what `bin/screenshot-ios` does — is such a launch. On a simulator that
is not signed in to an Apple Account, StoreKit answers by demanding one,
repeatedly: `Product.products(for:)` and `Transaction.currentEntitlements` are
issued concurrently from `SubscriptionViewModel.load()` (driven by
`SubscriptionSection`'s `.task`, so it re-runs on every appearance), the
session-long `Transaction.updates` listener runs for as long as the process
does, and StoreKit retries on its own. The visible result was a stream of "Sign
in to your Apple Account" system alerts drawn over the UI — including over the
screenshot being captured.

**The rule, and note which way round it is: on a simulator, a Debug build talks
to real StoreKit only when the launch explicitly opts in.** Anything else gets
an inert store.

```sh
# Opts in — real StoreKit. Only do this where a catalogue or a Sandbox Apple
# Account actually exists.
xcrun simctl launch <device> coach.uni.UnicoachiOS -UnicoachEnableStoreKit

# Says nothing — inert store, cannot reach the App Store, cannot raise an alert.
xcrun simctl launch <device> coach.uni.UnicoachiOS
```

The default is inverted **because a simulator process can only ever get a
StoreKit configuration from a scheme action, and only a scheme action can pass
this argument** — so "no scheme" and "no configuration" are the same condition.
Defaulting to disabled means no launcher can reach the real App Store by
forgetting something: safety is not a flag that every future call site has to
remember. (The previous shape — a `-UnicoachDisableStoreKit` opt-out that
`bin/screenshot-ios` passed — fixed one script and left the next one to
rediscover the trap.)

`AppViewModel.defaultSubscriptionStore()` — the app's composition root — is
where this is decided, and it is the only place. Without the argument it injects
`DisabledSubscriptionStore`, which offers no product and no entitlements, so
StoreKit is never called at all. A captured Settings screen then shows the usage
meter with **no** Subscribe button, the honest rendering of "no purchase path is
on offer" — the inert store deliberately does not fabricate a price.

Two guards on that block, both load-bearing:

- **`#if DEBUG`** — a Release build does not contain the switch at all. A
  shipping binary that can be told on the command line what to do about StoreKit
  is a binary that can be told to skip paying.
- **`targetEnvironment(simulator)`** — a Debug build **on a real device** keeps
  real StoreKit with no argument. A device has a real Apple Account and is where
  purchases are actually tested; only the simulator, which cannot obtain the
  configuration outside a scheme, is defaulted off.

Xcode's own **Run** action is unaffected: the scheme's `LaunchAction` carries
`-UnicoachEnableStoreKit` as a `CommandLineArguments` entry **in the same node**
as its `StoreKitConfigurationFileReference`, so the thing that turns StoreKit on
and the catalogue that makes it safe live together and cannot drift apart. Press
Run and you get the local `.storekit` products exactly as before.

`bin/screenshot-ios` passes nothing at all, and needs no flag for this. To
deliberately capture the live subscription surface, put
`-UnicoachEnableStoreKit` after `--` (it is forwarded verbatim to
`simctl launch`) on a simulator signed in to a **Sandbox** Apple Account, or use
Xcode's Run action.

**If you add a UI test**, it needs both halves, because a test launch is not a
Run: add a `StoreKitConfigurationFileReference` to the scheme's `TestAction`
(pointing at `../../../UnicoachiOS.storekit`, as the `LaunchAction` does)
**and** arrange for the test host to receive `-UnicoachEnableStoreKit` — either
as a `CommandLineArguments` entry on the `TestAction`, or by setting
`shouldUseLaunchSchemeArgsEnv = "YES"` so it inherits the LaunchAction's. Today
that attribute is `"NO"` and the `TestAction` carries no configuration, on
purpose: the unit suite uses protocol mocks, so its host app must get the inert
store rather than a launch argument for a catalogue nobody injected.

## Troubleshooting

- **Login does not persist across an app relaunch.** You changed `APP_DOMAIN`
  but did not bounce the server, so the issued cookie `Domain` still names the
  old host. Bounce the server, then rebuild and reinstall. (`APP_DOMAIN` must be
  a DNS name, not a bare IP.)
- **Cannot reach the backend (connection failures).** Wrong host, server not
  running/bound, or the firewall is blocking inbound 8080. Confirm the phone can
  reach the Mac (e.g. open `http://<host>:8080` in mobile Safari), check the
  server is up on `0.0.0.0:8080`, and allow inbound 8080 / use Tailscale.
- **No device found, or the wrong device is targeted.** Set `UNICOACH_DEVICE` in
  `signing.env` to the intended UDID (`xcrun devicectl list devices`).
- **Dev-shell guard error (`must run under system Xcode`).** The script was
  wrapped in `nix develop -c`. Run it directly: `bin/build-ios` /
  `bin/install-ios` / `bin/release-ios` / `bin/ios-sim` / `bin/test-ios` /
  `bin/screenshot-ios`.
- **A capture or test run shows another checkout's build.** You bypassed the
  per-checkout device — `UNICOACH_SIMULATOR` is set in your environment, or the
  target's destination carries an explicit `id=<UDID>`. Unset it, or check what
  `bin/ios-sim` prints.
- **TestFlight upload rejected: duplicate build number.** App Store Connect
  already has a build with that `CFBundleVersion`. `bin/release-ios` derives the
  build number from the HEAD commit count, so commit first (or pass a higher
  `UNICOACH_BUILD_NUMBER`) and re-run.
- **TestFlight upload fails to authenticate.** Check `appstore.env`: the Key ID,
  Issuer ID, and the `AuthKey_<id>.p8` at `UNICOACH_ASC_KEY_PATH` must match a
  current App Store Connect API key with the App Manager role. Use `--no-upload`
  to confirm the archive/export succeeds independently of the upload.

## Manual on-device smoke test

Set `APP_DOMAIN` once in `.env` and bounce the server, then `bin/build-ios`
followed by `bin/install-ios --launch`: register or log in on the device against
the Tailscale backend, then force-quit and relaunch the app and confirm the
session survived. This validates that the single `APP_DOMAIN` drives both the
baked backend host and the issued cookie `Domain`.

## Manual Sign in with Apple pass

No automated gate covers Apple sign-in: `xcodebuild test` cannot construct an
`ASAuthorizationAppleIDCredential`, and the default `simulator` target's local
backend picks up `APPLE_AUTH_PROVIDER=stub` from `.env.dev`, whose verifier
rejects every real Apple token — the pass would fail spuriously there. Build
with `bin/build-ios prod-simulator`, which targets the live deployment whose
`APPLE_CLIENT_IDS` carries the bundle identifier, and run all six cases:

1. **First authorization.** Sign in with an Apple ID that has never authorized
   this app, disclosing name and email. The app reaches `HomeView`/onboarding —
   **not** `VerificationRequiredView` — and greets the user by the disclosed
   name.
2. **Subsequent authorization.** Log out and sign in again with the same Apple
   ID. Apple discloses no name; the session is established and the greeting is
   unchanged.
3. **Hide My Email.** Revoke, then re-authorize choosing "Hide My Email". The
   account provisions on the `@privaterelay.appleid.com` address and lands
   authenticated.
4. **Cancel.** Dismiss the Apple sheet; the login screen returns with no banner
   and no state change.
5. **Placement.** The Apple button sits above the Google button, the two are the
   same width and height, and both track Dynamic Type at the largest
   accessibility size.
6. **Disabled during loading.** On a deliberately slow network, tap Apple and
   then tap both buttons again while the spinner shows: neither responds and no
   second Apple sheet appears.

Case 1 is only reachable once per Apple ID. Re-testing it requires revoking the
app under _Settings → Apple ID → Sign-In & Security → Apps Using Apple ID → Stop
Using_ — a careless first run burns the case.

Device and TestFlight verification are out of scope.
