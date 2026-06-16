# Deploying UnicoachiOS to a Physical iPhone

This guide covers building, signing, and installing the `UnicoachiOS` app on a
registered physical iPhone for on-device testing. The simulator workflow needs
none of this — see [UnicoachiOSTests/TESTING.md](UnicoachiOSTests/TESTING.md).

The two scripts here run under **system Xcode**, not the Nix dev shell. Do not
wrap them in `nix develop`; just run `bin/build-ios` / `bin/install-ios`
directly. Both call `bin/is-nix` and refuse to run if launched inside the dev
shell, because there `xcrun` is shadowed by a stub and `DEVELOPER_DIR`/`SDKROOT`
point into the Nix store — silently targeting the wrong toolchain.

## How it fits together

The deploy host is defined **once**, as `APP_DOMAIN` in the repo `.env`. Both
sides derive from that single value, so they cannot disagree:

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
it reloads `cookieDomain`.

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
- `bin/build-ios <env>` builds (and, for device targets, signs) the app, baking
  the derived `UNICOACH_BACKEND_URL` into the bundle.
- `bin/install-ios <env>` installs the most recent device build to the iPhone
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
  across every environment so device builds inherit signing creds without
  restating them.
- `local.env` — only `UNICOACH_DESTINATION="generic/platform=iOS"` (and an
  optional `UNICOACH_CONFIGURATION`). It no longer carries a backend host; the
  host comes from `APP_DOMAIN` in `.env`. `local` is the default env, so both
  scripts use it when you pass no argument.

Environment files under `ios-app/env/` are gitignored except the shared
`simulator.env` and the `*.env.example` templates, so your personal files are
never committed.

## Build and install

```sh
bin/build-ios            # builds + signs the `local` env
bin/install-ios --launch # installs to the device and launches it
```

`bin/install-ios` selects the target device from `UNICOACH_DEVICE` when set;
otherwise it auto-detects the single connected device (and fails fast if zero or
more than one is connected — set `UNICOACH_DEVICE` to disambiguate). The
`--launch` flag additionally starts the app on the device (fire-and-forget).

On the first device build, `-allowProvisioningUpdates` lets `xcodebuild`
register the device and create or refresh the managed provisioning profile
against the team's portal.

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
  `bin/install-ios`.

## Manual on-device smoke test

Set `APP_DOMAIN` once in `.env` and bounce the server, then `bin/build-ios`
followed by `bin/install-ios --launch`: register or log in on the device against
the Tailscale backend, then force-quit and relaunch the app and confirm the
session survived. This validates that the single `APP_DOMAIN` drives both the
baked backend host and the issued cookie `Domain`.
