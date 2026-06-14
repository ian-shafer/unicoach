# 51 — iOS Deploy to Physical Device

## Executive Summary

Add a documented, scripted path to build the `ios-app/UnicoachiOS` target and
install it on a registered physical iPhone for on-device testing, replacing the
current simulator-only, hardcoded-`localhost` workflow.

Three gaps block on-device use today. (1) The backend URL is fixed: `APIClient`
defaults `baseURL` to `http://localhost:8080`
(`ios-app/UnicoachiOS/APIClient.swift`), and on a device `localhost` is the
phone, not the developer's Mac. (2) Code signing is a placeholder
(`DEVELOPMENT_TEAM = "-"`, `ios-app/UnicoachiOS.xcodeproj/project.pbxproj`), so
no device build is signable. (3) The session cookie is issued with
`Domain=localhost` (`session.cookieDomain`,
`rest-server/src/main/resources/rest-server.conf`), which a device talking to
the Mac at a non-`localhost` host will not store or replay, breaking session
restore.

This RFC closes all three. A new build setting `UNICOACH_BACKEND_URL` is baked
into `Info.plist` and resolved at launch by a new pure function with a
`localhost` fallback; the app is otherwise unchanged. The deploy host is defined
**once** as a single `APP_DOMAIN` env var in `.env`: the server derives
`session.cookieDomain` from it (a one-line `rest-server.conf` substitution
change), and `bin/build-ios` derives
`UNICOACH_BACKEND_URL = http://$APP_DOMAIN:
${SERVER_PORT:-8080}` from the same
value — so the cookie `Domain` and the backend host cannot disagree, and a
bare-IP `APP_DOMAIN` (an invalid cookie `Domain` per RFC 6265) is rejected at
build. Two `bin/` scripts — `bin/build-ios` (build/sign) and `bin/install-ios`
(install via `devicectl`) — drive `xcodebuild` and `xcrun` from the system Xcode
toolchain (not the Nix dev shell). A new predicate `bin/is-nix` detects the dev
shell; both scripts call it and `fatal` before invoking any tool if run inside
it, because the dev shell shadows `xcrun` with a stub and redirects
`DEVELOPER_DIR`/`SDKROOT` into the Nix store — silently building against the
wrong toolchain. The iOS env files carry only the build destination;
per-developer signing secrets (Apple team, device UDID) live in gitignored
`signing.env` with a checked-in `.example` template. `ios-app/DEPLOY.md`
documents prerequisites and the recommended Tailscale transport. The single
deliberate server-side change is the one-line `cookieDomain` substitution; no
other server code or config changes.

## Detailed Design

### Backend URL resolution (app)

The installed app reads its backend URL from an `Info.plist` key baked at build
time, falling back to `localhost`. The value must be embedded in the bundle
because a device process launched standalone (no Xcode debugger attached) cannot
receive scheme environment variables.

- **Build setting.** A user-defined build setting `UNICOACH_BACKEND_URL` is
  added to the `UnicoachiOS` app target (Debug and Release), defaulting to the
  empty string. `xcodebuild` invocations override it on the command line.
- **`Info.plist` key.** A new key `UnicoachBackendURL` with value
  `$(UNICOACH_BACKEND_URL)`. Xcode expands build settings in `Info.plist` by
  default, so the literal string is substituted at build time. An undefined or
  empty setting yields an empty `UnicoachBackendURL`.
- **Resolver.** A new file `ios-app/UnicoachiOS/BackendURL.swift` exposes two
  functions:

  ```
  func resolveBackendURL(_ infoValue: String?) -> URL   // pure, testable
  func defaultBackendURL() -> URL                        // reads Bundle.main
  ```

  `resolveBackendURL` trims `infoValue`; if it is non-empty and parses to a
  valid absolute `URL`, that URL is returned, otherwise
  `URL(string: "http://localhost:8080")!`. `defaultBackendURL` calls it with
  `Bundle.main.object(forInfoDictionaryKey: "UnicoachBackendURL") as? String`.
- **Wiring.** `APIClient.init` changes its default parameter from
  `baseURL: URL = URL(string: "http://localhost:8080")!` to
  `baseURL: URL = defaultBackendURL()`. `AppViewModel` already constructs
  `APIClient()` with no arguments (`ios-app/UnicoachiOS/AppViewModel.swift`), so
  production picks up the resolved URL with no further change. Client unit tests
  inject an explicit `baseURL` and are unaffected; in the test bundle the
  `Info.plist` key is absent, so the fallback preserves existing behaviour.

App Transport Security already permits cleartext
(`NSAllowsArbitraryLoads =
true`, `ios-app/UnicoachiOS/Info.plist`), so plain
HTTP to a LAN/Tailscale host needs no ATS change.

### Code signing / provisioning

Signing uses Xcode automatic signing against a paid Apple Developer Program
team, supplied at build time and never committed.

- `project.pbxproj` retains `DEVELOPMENT_TEAM = "-"`. The build script passes
  `DEVELOPMENT_TEAM=<team>`, `CODE_SIGN_STYLE=Automatic`, and
  `-allowProvisioningUpdates` as `xcodebuild` overrides. Nothing
  machine-specific is written into the tracked project; simulator and test
  builds, which do not sign, are unaffected.
- `-allowProvisioningUpdates` lets `xcodebuild` register the device and create
  or refresh the managed provisioning profile against the team's portal on first
  device build. The developer must have signed into the team in Xcode (Settings
  → Accounts) and enabled Developer Mode on the device (iOS 16+).
- The paid program yields ~1-year provisioning profiles and portal-managed
  device registration (free personal teams' 7-day profiles are out of scope).

### Dev-shell guard

`bin/build-ios` and `bin/install-ios` must run under the system Xcode toolchain,
never inside the Nix dev shell. Inside `nix develop` the shell shadows `xcrun`
with a Nix `xcbuild` stub (PATH-precedence over `/usr/bin/xcrun`, and the stub
has no `devicectl`) and exports `DEVELOPER_DIR`/`SDKROOT` pointing into a Nix
`apple-sdk` store path, so even the system `xcodebuild` is redirected to the
wrong SDK. Today nothing enforces the boundary; a `nix develop -c bin/build-ios`
would build against that toolchain or fail opaquely.

**`bin/is-nix`** — a standalone predicate reporting whether the caller is inside
the Nix dev shell. Mirrors the `*-check` script convention
(`bin/postgres-check`): exit code is the contract, a human status line goes to
stderr via `log-info`, and it sources only `bin/functions` (never `bin/common`,
which requires the dev shell it is meant to detect).

- **Detection signal: `IN_NIX_SHELL` non-empty.** `nix develop` exports
  `IN_NIX_SHELL` (`impure`, or `pure` for a pure shell); a check for non-empty
  covers both. This is the canonical, documented marker for "inside a Nix shell"
  and is exactly the boundary being enforced. `DEVELOPER_DIR` is rejected as the
  signal: it is a _consequence_ of the shell, and is also a legitimate system
  mechanism (a developer may set it to select among installed Xcodes), so keying
  on it risks false positives; matching `/nix/store` within it is a heuristic
  coupled to the flake's current package set. `IN_NIX_SHELL` has neither problem
  and is trivially simulable in tests by exporting one variable.
- **Contract.** Exit `0` inside the dev shell, `1` outside. Prints `nix enabled`
  / `nix NOT enabled` via `log-info` unless `-q`/`--quiet` (which suppresses all
  informational output, for callers that want only the exit code). With
  `-v`/`--verbose` it additionally lists the enabled packages, derived from the
  exported `nativeBuildInputs` (space-separated `/nix/store/<hash>-<name>`
  paths; each name is the basename with the leading `<hash>-` stripped, e.g.
  `temurin-bin-21.0.8`). `-q` takes precedence over `-v`. `-h`/`--help` prints
  usage; an unknown option is `fatal`.

**Guard in the iOS scripts.** Each of `bin/build-ios` and `bin/install-ios`
calls `bin/is-nix --quiet` immediately after `-h`/`--help` and option parsing,
before any positional/env/signing/tool logic, and `fatal`s if it returns `0`.
Placement after `--help` keeps usage readable from any shell; placement before
env resolution makes the guard the first real operation, so its message wins
over a missing-env error. The message names the script (`${0##*/}`), states that
it must run under system Xcode, and directs the developer to run it directly
(e.g. `bin/build-ios local`), not via `nix develop -c`. Detection lives solely
in `bin/is-nix`; the refusal policy and remediation text live in each script.

### Build & install mechanism

Two scripts, mirroring the existing `bin/build-*` family and
single-responsibility daemon scripts. Both run under **system Xcode**, not
`nix develop`: they MUST NOT `source bin/common` (which requires Postgres and
the Nix shell). Among the `bin/` libraries they source only `bin/functions` for
`log-info`/`fatal`, resolve `PROJECT_ROOT` themselves, and guard against the dev
shell via `bin/is-nix` (see Dev-shell guard). `bin/build-ios` additionally reads
the repo `.env` directly (a plain env file, not via `bin/common`) for the
single-source `APP_DOMAIN`/`SERVER_PORT`.

**`bin/build-ios [env]`** — builds and (for device targets) signs the app.

- `env` defaults to `local`. The script sources `ios-app/env/<env>.env` for
  `UNICOACH_DESTINATION` (and optional `UNICOACH_CONFIGURATION`), failing fast
  with a remediating message if absent.
- The backend URL is **derived, not configured per-env**: the script sources the
  repo `.env` (overridable by `UNICOACH_DOTENV`, for tests) and composes
  `UNICOACH_BACKEND_URL = http://$APP_DOMAIN:${SERVER_PORT:-8080}` from the same
  `APP_DOMAIN`/`SERVER_PORT` the server reads (see Backend host and cookie
  domain). `APP_DOMAIN` defaults to `localhost` when unset. A bare-IP
  `APP_DOMAIN` (IPv4/IPv6 literal) is `fatal` — it is an invalid cookie `Domain`
  per RFC 6265 and could never persist a session; the message directs the
  developer to a DNS hostname. `localhost` is permitted.
- An env is a simulator build iff `UNICOACH_DESTINATION` contains the substring
  `Simulator`; otherwise it is a device build. For a device build the script
  also sources `ios-app/env/signing.env` for `UNICOACH_DEVELOPMENT_TEAM`
  (required) and `UNICOACH_DEVICE` (optional), failing fast if `signing.env` or
  the team is missing. Simulator builds skip the signing requirement entirely,
  so a contributor without an Apple account can still run a simulator compile
  check.
- Invokes:
  `xcodebuild -project ios-app/UnicoachiOS.xcodeproj -scheme UnicoachiOS
  -configuration "$UNICOACH_CONFIGURATION" -destination "$UNICOACH_DESTINATION"
  -derivedDataPath ios-app/build/DerivedData
  UNICOACH_BACKEND_URL="$UNICOACH_BACKEND_URL" [DEVELOPMENT_TEAM=… CODE_SIGN_STYLE=Automatic -allowProvisioningUpdates] build`.
- `ios-app/build/` is already covered by the repo-wide `build/` gitignore rule.

**`bin/install-ios [env]`** — installs the most recent device build to the
iPhone. Physical device only.

- Sources `ios-app/env/<env>.env` (default `local`) to locate the built `.app`
  at
  `ios-app/build/DerivedData/Build/Products/<UNICOACH_CONFIGURATION>-iphoneos/UnicoachiOS.app`,
  failing fast if absent (directs the developer to run `bin/build-ios` first).
  An env whose `UNICOACH_DESTINATION` contains `Simulator` is rejected with a
  clear message (same discriminator as `bin/build-ios`).
- Sources `ios-app/env/signing.env`, failing fast if absent (it is the home of
  `UNICOACH_DEVICE`). Device selection: if `UNICOACH_DEVICE` is set, it is used;
  otherwise the script auto-detects via `xcrun devicectl list devices` and fails
  fast if zero or more than one device is connected (instructing the developer
  to set `UNICOACH_DEVICE`).
- Invokes `xcrun devicectl device install app --device "$UDID" "$APP"`, which
  replaces any prior install of the bundle id in place. An optional `--launch`
  flag additionally runs
  `xcrun devicectl device process launch --device "$UDID" com.unicoach.UnicoachiOS`
  (fire-and-forget; the script does not stream the process). The bundle id
  `com.unicoach.UnicoachiOS` is the app target's `PRODUCT_BUNDLE_IDENTIFIER`
  (`project.pbxproj`).

### Environment files

A new `ios-app/env/` directory holds per-environment shell fragments, each
defining `UNICOACH_DESTINATION` (the build target); `UNICOACH_CONFIGURATION` is
optional, defaulting to `Debug`. The backend host is **not** an env-file value —
it derives from `APP_DOMAIN` (see Backend host and cookie domain), so the env
files differ only in build destination. All `*.env` under `ios-app/env/` are
gitignored except `simulator.env` and the `*.env.example` templates, so any
per-developer file is ignored by default.

- **`ios-app/env/simulator.env`** (checked in):
  `UNICOACH_DESTINATION=platform=iOS Simulator,name=iPhone 17 Pro`. The one real
  shared environment today; the directory accepts additional files (e.g. a
  future `staging.env`) without code change. The pinned simulator name is a
  default a developer overrides via their own env file if that device is not
  installed locally.
- **`ios-app/env/signing.env`** (gitignored): `UNICOACH_DEVELOPMENT_TEAM`,
  optional `UNICOACH_DEVICE`. Per-developer, needed by any device build/install,
  kept separate from per-env files so a device build of any environment inherits
  signing creds without restating them.
- **`ios-app/env/local.env`** (gitignored): the default device env,
  `UNICOACH_DESTINATION=generic/platform=iOS`. Gitignored to match the
  `ios-app/env/*.env` rule, though it no longer carries personal data (the host
  moved to `APP_DOMAIN`).
- **`ios-app/env/signing.env.example`**, **`ios-app/env/local.env.example`**
  (checked in): templates documenting every variable.

### Backend host and cookie domain (single source: `APP_DOMAIN`)

The deploy host is defined exactly once, as the env var `APP_DOMAIN` in `.env`,
and both the cookie `Domain` and the iOS backend host derive from it. This makes
the prior two-settings hazard structurally impossible: there is no second value
to disagree.

The session cookie is issued with `Domain = sessionConfig.cookieDomain` on every
auth response
(`rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt`,
`StudentRoutes.kt`); for the device to store and replay it, that `Domain` MUST
match the host the app targets. Rather than configure the two independently and
reconcile them, the single source feeds both:

- **Server.** `rest-server.conf` changes its `cookieDomain` substitution from
  `${?SESSION_COOKIE_DOMAIN}` to `${?APP_DOMAIN}` (the base default
  `"localhost"` is unchanged, so an unset `APP_DOMAIN` preserves today's
  behaviour). `SESSION_COOKIE_DOMAIN` is referenced nowhere else in the tree, so
  this rename is self-contained. `SessionConfig` reads `cookieDomain` unchanged
  (`SessionConfig.kt`). This one-line substitution is the **only** server-side
  change in the RFC and is a deliberate, scoped relaxation of the original "no
  server change" goal — required to make `APP_DOMAIN` the single source.
- **iOS build.** `bin/build-ios` sources `.env` and composes
  `UNICOACH_BACKEND_URL = http://$APP_DOMAIN:${SERVER_PORT:-8080}` (the same
  `SERVER_PORT` the server's `port = ${?SERVER_PORT}` reads, default `8080`),
  then bakes it via the existing `UNICOACH_BACKEND_URL` build setting. The host
  is never restated in an iOS env file.
- **Shared source.** `.env` is `set -a; source`d into the server's process by
  `bin/common` (so `${?APP_DOMAIN}` resolves), and `bin/build-ios` sources the
  same file, so both processes compute an identical host from one definition.
  `APP_DOMAIN` defaults to `localhost`; a developer overrides it once for
  on-device use.

Because there is nothing to compare, no preflight, server probe, or HOCON
parsing is introduced. The only residual coupling is temporal — the server must
be bounced after `APP_DOMAIN` changes to reload `cookieDomain` — which is a
documented step, not a second source of truth. A bare-IP `APP_DOMAIN` is
rejected at build (invalid cookie `Domain` per RFC 6265); `localhost` and DNS
hostnames are accepted.

The recommended transport is **Tailscale**: with the Mac and iPhone on one
tailnet, the phone reaches the Mac at a stable MagicDNS name
(`<host>.<tailnet>.ts.net`) over any network, surviving DHCP changes — a natural
fit for `APP_DOMAIN`, which must be a DNS hostname. The server already binds
`0.0.0.0:8080`, so it listens on the Tailscale interface unchanged; a
same-network LAN hostname is the fallback. Tailscale is a documented
recommendation, not a code dependency.

### Documentation

`ios-app/DEPLOY.md` is the operator-facing guide: prerequisites (paid team
signed into Xcode, device paired + trusted + Developer Mode on, Tailscale on
both ends with MagicDNS, server running and bound `0.0.0.0:8080`, `APP_DOMAIN`
set once in `.env` to the Tailscale/LAN hostname + server bounced, macOS
firewall allowing inbound 8080 or Tailscale), first-time setup (copy `.example`
files, fill `signing.env`), the `bin/build-ios` → `bin/install-ios` cycle, and
troubleshooting (login not persisting → `APP_DOMAIN` changed but server not
bounced; cannot reach backend → firewall / wrong host; no device found →
`UNICOACH_DEVICE`; dev-shell guard error → the script was wrapped in
`nix develop -c`, run it directly). It states plainly that the deploy host is
set once via `APP_DOMAIN`, and that `bin/build-ios`/`bin/install-ios` run under
system Xcode and are enforced by `bin/is-nix` to refuse the dev shell.

### Error handling / edge cases

- Missing/empty `UNICOACH_BACKEND_URL` or an unparseable value → app falls back
  to `http://localhost:8080` (resolver contract); on a device this surfaces as a
  connection failure, not a crash. (Reached only by a raw Xcode build;
  `bin/build-ios` always bakes a value derived from `APP_DOMAIN`.)
- Unset `APP_DOMAIN` → `bin/build-ios` bakes `http://localhost:8080` and the
  server's `cookieDomain` stays `localhost` (both from the same default), so
  simulator use works with zero configuration.
- Bare-IP `APP_DOMAIN` (IPv4/IPv6 literal) → `bin/build-ios` `fatal`s before
  invoking `xcodebuild`, directing the developer to a DNS hostname (a bare IP is
  an invalid cookie `Domain` per RFC 6265 and can never persist a session).
- Missing env file, missing `signing.env`, or missing team → scripts `fatal`
  with a remediating message before invoking any tool. The default env `local`
  is gitignored, so on a fresh clone both scripts invoked with no argument
  `fatal` directing the developer to copy `local.env.example` to `local.env`.
- Install before build → `bin/install-ios` fails fast on the absent `.app`.
- Simulator env passed to `bin/install-ios` → rejected.
- Zero or multiple connected devices with no `UNICOACH_DEVICE` →
  `bin/install-ios` fails fast.
- Either script invoked inside the Nix dev shell (e.g.
  `nix develop -c
  bin/build-ios`) → `bin/is-nix --quiet` returns `0`, the
  script `fatal`s before invoking any tool, naming itself and directing the
  developer to run it directly. The guard precedes env resolution, so this
  message wins even when the env file is also missing. `--help` is unaffected
  (it precedes the guard).

### Dependencies

System Xcode (`xcodebuild`, `xcrun devicectl`); no new Swift package, no new Nix
input, no new Gradle/server dependency. Tailscale is an optional, documented
host-level tool.

## Tests

### Swift: `ios-app/UnicoachiOSTests/BackendURLTests.swift` (new)

Unit tests for `resolveBackendURL(_:)`, driving the pure function directly
(consistent with the target's DI-for-testability convention). Each test asserts
the returned `URL.absoluteString`.

- `resolveBackendURL(nil)` → `http://localhost:8080` (absent key).
- `resolveBackendURL("")` → `http://localhost:8080` (empty expansion).
- `resolveBackendURL("   ")` → `http://localhost:8080` (whitespace-only trims to
  empty).
- `resolveBackendURL("http://mymac.example.ts.net:8080")` → that exact URL
  (Tailscale host).
- `resolveBackendURL("http://192.168.1.42:8080")` → that exact URL (LAN IP).
- `resolveBackendURL("not a url")` (a value that fails absolute-URL parsing) →
  `http://localhost:8080` (fallback, not a crash).

The new test file MUST be registered in `project.pbxproj` (explicit file
references; no filesystem synchronization — see
`ios-app/UnicoachiOSTests/TESTING.md`) or it never compiles.

### Kotlin: `rest-server/src/test/kotlin/ed/unicoach/rest/auth/SessionConfigTest.kt`

Two cases added to the existing `SessionConfigTest`, locking the `APP_DOMAIN`
substitution in `rest-server.conf` (the one server-side change). Each loads the
real resource (`ConfigFactory.parseResources("rest-server.conf")`) with an
`APP_DOMAIN` override folded on top and resolves it.

- With `APP_DOMAIN=cookie.example.test` folded in (via
  `ConfigFactory.parseString(...).withFallback(...).resolve()`) →
  `config.getString("session.cookieDomain")` is `cookie.example.test`.
- With no `APP_DOMAIN` provided → `session.cookieDomain` is `localhost` (the
  unchanged base default; asserts the rename did not alter default behaviour).

### Shell: `bin/ios-scripts-tests` (new)

A standalone harness sourcing only `bin/functions` and `bin/tests-common` (no
`bin/common`, no Postgres, no Nix). `xcodebuild` and `xcrun` are shimmed onto
`PATH` via a temp directory so no hardware or real build runs; the shims record
their argv to a file the assertions inspect. The `ios-app/env/<env>.env` files
are pointed at a temp fixture dir via `UNICOACH_ENV_DIR`, and the repo `.env`
(the `APP_DOMAIN`/`SERVER_PORT` source) is pointed at a temp fixture file via
`UNICOACH_DOTENV`, so the assertions drive the derived `UNICOACH_BACKEND_URL`
without touching the real `.env`.

The harness `unset`s `IN_NIX_SHELL` at startup so happy-path cases are
deterministic even when the harness itself is launched via `nix develop -c`;
"inside Nix" is simulated per-invocation by prefixing the script call with
`IN_NIX_SHELL=impure` (an external-command assignment, so it scopes to that
child only). `bin/is-nix` is exercised directly.

- `bin/is-nix -q` with `IN_NIX_SHELL=impure` → exit `0`, no output; with
  `IN_NIX_SHELL` unset → exit `1`, no output.
- `bin/is-nix` (no `-q`) with `IN_NIX_SHELL=impure` → exit `0`, stderr contains
  `nix enabled`; unset → exit `1`, stderr contains `nix NOT enabled`.
- `bin/is-nix -v` with `IN_NIX_SHELL=impure` and a fixture
  `nativeBuildInputs="/nix/store/<hash>-foo-1.0 /nix/store/<hash>-bar-2.0"` →
  exit `0`, output lists `foo-1.0` and `bar-2.0` (asserts the `<hash>-` strip).
- `bin/is-nix --bogus` → non-zero, unknown-option message.
- `bin/build-ios simulator` with `IN_NIX_SHELL=impure` → non-zero exit, message
  names the script and directs to run directly; the recorded argv log contains
  **no** `xcodebuild` line (guard fires before any tool).
- `bin/build-ios nonexistent-env` with `IN_NIX_SHELL=impure` → non-zero exit,
  the message is the dev-shell guard's, **not** the missing-env message (asserts
  guard precedence over env resolution).
- `bin/install-ios device` with `IN_NIX_SHELL=impure` (built `.app` and
  `signing.env` present) → non-zero exit, dev-shell guard message; the argv log
  contains no `xcrun` line.

- `bin/build-ios nonexistent-env` → non-zero exit, message names the missing env
  file.
- `bin/build-ios` with a device env but absent `signing.env` → non-zero exit,
  message names `signing.env`.
- `bin/build-ios` with a device env and absent `UNICOACH_DEVELOPMENT_TEAM` →
  non-zero exit.
- `bin/build-ios simulator` (fixture, `Simulator` in destination; fixture `.env`
  with unset/`localhost` `APP_DOMAIN`) → succeeds; recorded `xcodebuild` argv
  contains the simulator `-destination`, the derived
  `UNICOACH_BACKEND_URL=http://localhost:8080`, and does **not** require/forward
  `DEVELOPMENT_TEAM` (asserts the `Simulator`-substring discriminator skips
  signing).
- `bin/build-ios` device env (fixture) with fixture `.env`
  `APP_DOMAIN=mymac.example.ts.net` → recorded argv contains
  `-destination generic/platform=iOS`, `DEVELOPMENT_TEAM=<fixture>`,
  `CODE_SIGN_STYLE=Automatic`, `-allowProvisioningUpdates`, and the **derived**
  `UNICOACH_BACKEND_URL=http://mymac.example.ts.net:8080`.
- `bin/build-ios` device env with fixture `.env`
  `APP_DOMAIN=mymac.example.ts.net` and `SERVER_PORT=9090` → recorded argv
  contains `UNICOACH_BACKEND_URL=http://mymac.example.ts.net:9090` (asserts the
  port derives from `SERVER_PORT`).
- `bin/build-ios` device env with fixture `.env` `APP_DOMAIN=192.168.1.42` (bare
  IP) → non-zero exit, message directs to a DNS hostname; the argv log contains
  **no** `xcodebuild` line (rejected before any build).
- `bin/install-ios` with no built `.app` present → non-zero exit, message
  directs to `bin/build-ios`.
- `bin/install-ios` with a simulator env → non-zero exit (device-only).
- `bin/install-ios` device env with a built `.app` but absent `signing.env` →
  non-zero exit, message names `signing.env`.
- `bin/install-ios` device env with `UNICOACH_DEVICE` set → recorded
  `devicectl … install app --device <udid>` argv includes the expected app path;
  with `--launch`, a `process launch … com.unicoach.UnicoachiOS` invocation is
  also recorded.
- `bin/install-ios` device env, no `UNICOACH_DEVICE`, shim reports two devices →
  non-zero exit (ambiguous device).

### Manual (documented in `ios-app/DEPLOY.md`, not automated)

End-to-end on-device smoke: set `APP_DOMAIN` once in `.env` and bounce the
server, `bin/build-ios` then `bin/install-ios --launch`, register/login on the
device against the Tailscale backend, confirm the session survives an app
relaunch (validates that the single `APP_DOMAIN` drives both the baked backend
host and the issued cookie `Domain`).

## Implementation Plan

1. **Add the backend-URL resolver (app).** Create
   `ios-app/UnicoachiOS/BackendURL.swift` with `resolveBackendURL(_:)` and
   `defaultBackendURL()` per Detailed Design. Change `APIClient.init`'s
   `baseURL` default to `defaultBackendURL()` in
   `ios-app/UnicoachiOS/APIClient.swift`. Register `BackendURL.swift` in
   `project.pbxproj` (app target).
   - Verify:
     `xcodebuild -project ios-app/UnicoachiOS.xcodeproj -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build`.

2. **Bake the build setting into `Info.plist`.** Add user-defined build setting
   `UNICOACH_BACKEND_URL` (default empty) to the app target's Debug and Release
   configs in `project.pbxproj`; add
   `UnicoachBackendURL = $(UNICOACH_BACKEND_URL)` to
   `ios-app/UnicoachiOS/Info.plist`.
   - Verify:
     `xcodebuild -project ios-app/UnicoachiOS.xcodeproj -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -showBuildSettings | grep UNICOACH_BACKEND_URL`.
   - Verify the override path: build with
     `UNICOACH_BACKEND_URL=http://example.test:8080` and confirm the built app's
     `Info.plist` carries that value (`plutil -p` on the product `Info.plist`).

3. **Add the resolver unit tests.** Create
   `ios-app/UnicoachiOSTests/BackendURLTests.swift` with the cases above;
   register it in `project.pbxproj` (test target).
   - Verify:
     `xcodebuild test -project ios-app/UnicoachiOS.xcodeproj -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 17 Pro'`.

4. **Make `APP_DOMAIN` the single source.** Change `rest-server.conf` line 22
   from `cookieDomain = ${?SESSION_COOKIE_DOMAIN}` to
   `cookieDomain = ${?APP_DOMAIN}` (base default `"localhost"` unchanged). Add
   `APP_DOMAIN=localhost` to `.env` and `.env.template`. Add the two
   `SessionConfigTest` cases above.
   - Verify:
     `nix develop -c bin/test rest-server --tests "ed.unicoach.rest.auth.SessionConfigTest"`
     passes;
     `grep -n APP_DOMAIN rest-server/src/main/resources/rest-server.conf .env .env.template`
     shows all three.

5. **Create the env directory and templates.** Add `ios-app/env/simulator.env`
   (`UNICOACH_DESTINATION` only), `ios-app/env/signing.env.example`,
   `ios-app/env/local.env.example`. Add to `.gitignore` the pattern
   `ios-app/env/*.env` with negations `!ios-app/env/*.env.example` and
   `!ios-app/env/simulator.env`, so any per-developer env (current
   `local.env`/`signing.env`, future ones) is ignored by default while
   checked-in files are tracked.
   - Verify: `git check-ignore ios-app/env/signing.env ios-app/env/local.env`
     prints both; `git status --porcelain ios-app/env/` shows only the
     checked-in files (`simulator.env`, `*.env.example`).

6. **Write `bin/is-nix`.** Implement the predicate per Dev-shell guard: source
   only `bin/functions`; exit `0` if `IN_NIX_SHELL` is non-empty else `1`; print
   `nix enabled`/`nix NOT enabled` via `log-info` unless `-q`/`--quiet`; with
   `-v`/`--verbose` list package names parsed from `nativeBuildInputs`;
   `-h`/`--help` and `fatal` on unknown option. Mark executable.
   - Verify (run directly, not via `nix develop -c`):
     `IN_NIX_SHELL=impure bin/is-nix -q` exits `0`;
     `env -u IN_NIX_SHELL bin/is-nix -q` exits `1`;
     `nix develop -c bin/is-nix -v` prints `nix enabled` and the flake packages
     (`temurin-bin-…`, `postgresql-…`, …).

7. **Write `bin/build-ios`.** Implement per Detailed Design: own preamble
   (`set -euo pipefail`, resolve `PROJECT_ROOT`, source `bin/functions`),
   `-h/--help` parsing, the `bin/is-nix --quiet` dev-shell guard (after option
   parsing, before env work), `ios-app/env/<env>.env` sourcing for
   `UNICOACH_DESTINATION`, `.env` sourcing (via `UNICOACH_DOTENV`) for
   `APP_DOMAIN`/`SERVER_PORT` and derivation of
   `UNICOACH_BACKEND_URL=http://$APP_DOMAIN:${SERVER_PORT:-8080}` with bare-IP
   rejection, conditional signing sourcing, `xcodebuild` invocation. Mark
   executable.
   - Verify (run directly): `bin/build-ios --help`; `bin/build-ios nonexistent`
     exits non-zero with a clear message;
     `nix develop -c bin/build-ios simulator` exits non-zero with the dev-shell
     guard message.

8. **Write `bin/install-ios`.** Implement per Detailed Design: own preamble, the
   `bin/is-nix --quiet` dev-shell guard (after option parsing, before env work),
   env sourcing, `.app` resolution, device resolution (`UNICOACH_DEVICE` or
   single-device auto-detect), `devicectl install`, optional `--launch`,
   `-h/--help`. Mark executable.
   - Verify (run directly): `bin/install-ios --help`;
     `bin/install-ios simulator` exits non-zero (device-only);
     `nix develop -c bin/install-ios` exits non-zero with the dev-shell guard
     message.

9. **Write `bin/ios-scripts-tests`.** Implement the shimmed harness and
   assertions above, including `unset IN_NIX_SHELL` at startup, the
   per-invocation `IN_NIX_SHELL=impure` guard cases, the `bin/is-nix` cases, the
   `UNICOACH_DOTENV` fixture `.env` driving the derived-`UNICOACH_BACKEND_URL`
   and `SERVER_PORT` cases, and the bare-IP `APP_DOMAIN` rejection case. Mark
   executable.
   - Verify: `bin/ios-scripts-tests` exits zero and prints the passed-test
     count.

10. **Write `ios-app/DEPLOY.md`.** Author the operator guide per Documentation.

- Verify: `nix develop -c deno fmt ios-app/DEPLOY.md` (or the repo's configured
  Markdown formatter) leaves it unchanged.

## Files Modified

- `ios-app/UnicoachiOS/BackendURL.swift` — NEW. `resolveBackendURL(_:)` +
  `defaultBackendURL()`.
- `ios-app/UnicoachiOS/APIClient.swift` — change `baseURL` default to
  `defaultBackendURL()`.
- `ios-app/UnicoachiOS/Info.plist` — add
  `UnicoachBackendURL =
  $(UNICOACH_BACKEND_URL)`.
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` — add user-defined build
  setting `UNICOACH_BACKEND_URL` (app Debug + Release); register
  `BackendURL.swift` (app target) and `BackendURLTests.swift` (test target).
- `ios-app/UnicoachiOSTests/BackendURLTests.swift` — NEW. Resolver unit tests.
- `rest-server/src/main/resources/rest-server.conf` — change `cookieDomain`
  substitution from `${?SESSION_COOKIE_DOMAIN}` to `${?APP_DOMAIN}` (base
  default unchanged). The only server-side change.
- `rest-server/src/test/kotlin/ed/unicoach/rest/auth/SessionConfigTest.kt` — add
  two cases locking `APP_DOMAIN` → `session.cookieDomain` (set + default).
- `.env` — add `APP_DOMAIN=localhost` (the single host source, read by the
  server and `bin/build-ios`).
- `.env.template` — add `APP_DOMAIN=localhost`.
- `ios-app/env/simulator.env` — NEW, checked in (`UNICOACH_DESTINATION` only; no
  backend URL).
- `ios-app/env/signing.env.example` — NEW, checked in.
- `ios-app/env/local.env.example` — NEW, checked in (`UNICOACH_DESTINATION`
  only).
- `bin/is-nix` — NEW, executable. Dev-shell predicate (exit 0 inside
  `nix
  develop`, 1 outside; `-q`/`--quiet`, `-v`/`--verbose`, `-h`/`--help`).
- `bin/build-ios` — NEW, executable. Includes the `bin/is-nix --quiet` dev-shell
  guard and derives `UNICOACH_BACKEND_URL` from `APP_DOMAIN`/`SERVER_PORT` in
  `.env` (bare-IP rejected).
- `bin/install-ios` — NEW, executable. Includes the `bin/is-nix --quiet`
  dev-shell guard.
- `bin/ios-scripts-tests` — NEW, executable. Includes the `bin/is-nix`,
  dev-shell-guard, derived-`UNICOACH_BACKEND_URL` (via `UNICOACH_DOTENV` fixture
  `.env`), and bare-IP-`APP_DOMAIN` cases.
- `ios-app/DEPLOY.md` — NEW. Operator guide.
- `.gitignore` — ignore `ios-app/env/*.env` with negations for `*.env.example`
  and `simulator.env`.
