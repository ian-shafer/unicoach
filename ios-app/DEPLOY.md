# Deploying UnicoachiOS to a Physical iPhone

This guide covers building, signing, and installing the `UnicoachiOS` app on a
registered physical iPhone for on-device testing. The simulator workflow needs
none of this — see [UnicoachiOSTests/TESTING.md](UnicoachiOSTests/TESTING.md).

The two scripts here run under **system Xcode**, not the Nix dev shell. Do not
wrap them in `nix develop`; just run `bin/build-ios` / `bin/install-ios`
directly. Both call `bin/is-nix` and refuse to run if launched inside the dev
shell, because there `xcrun` is shadowed by a stub and `DEVELOPER_DIR`/`SDKROOT`
point into the Nix store — silently targeting the wrong toolchain.

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
  `bin/install-ios` / `bin/release-ios`.
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
