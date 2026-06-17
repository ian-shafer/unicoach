# RFC 59: Named iOS build targets

## Executive Summary

`bin/build-ios <target>` always derives the app's backend URL as
`http://$APP_DOMAIN:${SERVER_PORT:-8080}` from the repo `.env`. That derivation
exists so the app and the server read host/cookie-domain from one source and
cannot drift, but it can express only `http://host:port`. The AWS deployment is
`https://api.unicoachapp.com` — HTTPS, TLS terminated at the ALB, no explicit
port — a shape the derived form cannot produce, so hitting the deployment
required bypassing `build-ios` with a literal `xcodebuild` invocation.

This RFC promotes the existing two-tier env model (checked-in
`ios-app/env/<target>.env` overlaid by gitignored `signing.env`) to first-class
**build targets** with a three-bucket configuration boundary: public
environment-specific settings (backend URL, destination, configuration) are
checked in per target; per-developer machine settings
(`UNICOACH_DEVELOPMENT_TEAM`, `UNICOACH_DEVICE`) live in the local, gitignored
`signing.env`; and genuinely secret settings (`UNICOACH_CLIENT_KEY`) remain
local. The one new mechanism is an "honor-if-set-else-derive" rule for the
backend URL (see §"Backend URL"): it preserves the single-source / no-drift
invariant for local-dev targets while letting a deploy target carry a literal
externally-terminated HTTPS URL the derived `http://host:port` form cannot
express.

Two checked-in deploy targets ship: `prod.env` (device) and `prod-simulator.env`
(simulator), both pointing at `https://api.unicoachapp.com`. To keep a user
logged in against the deployment, this RFC also closes a server/infra gap that
dropped the session cookie by seeding `APP_DOMAIN` on the deployment (see
§"Deployment cookie domain"). A per-target local secret seam
(`<target>.local.env`) is documented but not implemented.

## Detailed Design

### Configuration model (data model)

A build target is a file `ios-app/env/<target>.env` sourced by `bin/build-ios`
and `bin/install-ios`. Settings split into three buckets by sensitivity and
ownership:

- **Public, environment-specific (checked in, in the target file):**
  `UNICOACH_DESTINATION` (required), `UNICOACH_CONFIGURATION` (optional, default
  `Debug`), and the optional `UNICOACH_BACKEND_URL`. The backend URL is not a
  secret, so a deploy target file is shareable.
- **Per-developer / machine-specific (local, gitignored, in `signing.env`):**
  `UNICOACH_DEVELOPMENT_TEAM` (required for device builds) and `UNICOACH_DEVICE`
  (optional). One `signing.env` is shared across all targets so device builds of
  any target inherit signing without restating it. This is unchanged from
  current behaviour.
- **Actually secret (local, gitignored):** `UNICOACH_CLIENT_KEY`, relevant only
  when the server's client-key gate is enabled. Sourced today from the repo
  `.env`; the per-target home for it is the future `<target>.local.env` seam
  (below). The prod targets omit it because no gated deployment exists yet
  (`infra/ssm.tf` seeds no client-key parameter); the seam is its future home.

A target is a **simulator** target iff its `UNICOACH_DESTINATION` contains the
substring `Simulator`; otherwise it is a **device** target. This discriminator
is unchanged and governs both signing (device only) and the install-ios
device-only guard.

### Backend URL: honor-if-set, else derive (build-ios)

`build-ios` resolves `UNICOACH_BACKEND_URL` by one of two paths, decided after
the target file and the repo `.env` are sourced:

- **Honor:** if `UNICOACH_BACKEND_URL` is set and non-empty, it is forwarded to
  `xcodebuild` verbatim — no derivation, no validation. A checked-in target's
  URL is reviewed at commit time, so no further parsing is performed (a bare-IP
  host inside an explicit URL is therefore not separately rejected).
- **Derive:** if `UNICOACH_BACKEND_URL` is empty, `build-ios` defaults
  `APP_DOMAIN` to `localhost` and `SERVER_PORT` to `8080`, applies the bare-IP
  rejection, and composes `http://$APP_DOMAIN:$SERVER_PORT` — exactly today's
  behaviour.

Structurally, this wraps only the `APP_DOMAIN`/`SERVER_PORT` defaulting, the
bare-IP rejection, and the URL composition (`bin/build-ios:91`–`102`) in a guard
that runs only when `UNICOACH_BACKEND_URL` is empty. The `.env` sourcing
(`bin/build-ios:84`–`90`) stays unconditional, because `.env` still supplies
`UNICOACH_CLIENT_KEY` on both paths. The guard is evaluated after `.env` is
sourced, so an explicit value from the target file (or the environment) wins and
short-circuits derivation — including its bare-IP check — preventing a stale
bare-IP `APP_DOMAIN` in `.env` from rejecting a build that never uses
derivation.

### Composition order and the local-secret seam

`build-ios` sources, in order: (1) the target file `<target>.env`, (2) the repo
`.env` (when present), (3) `signing.env` (device builds only). Later sources win
(`set -a; source …`); in practice the three buckets hold disjoint keys, so no
key is actually overridden — the only path that would override a target's value
is the `<target>.local.env` seam below. `install-ios` sources (1) the target
file and (3) `signing.env`; it does not source `.env` and does not consume the
backend URL.

A fourth, **unimplemented** step is reserved: a per-target local file
`<target>.local.env` (gitignored) sourced **last**, after `signing.env`, so it
overrides everything for that one target. It is the intended home for a
per-target `UNICOACH_CLIENT_KEY` once a gated deployment exists. This RFC marks
the insertion point with a comment in `build-ios` and documents the rule in
`DEPLOY.md`, but adds no code, no staging target, and no per-target client-key
overlay. The `ios-app/env/*.env` gitignore rule already covers
`<target>.local.env`, so no gitignore change is needed for the seam.

### CLI / xcodebuild contract

`bin/build-ios [target]` (default `local`) and
`bin/install-ios [--launch] [target]` (default `local`) take an optional target
name. `build-ios` forwards `UNICOACH_BACKEND_URL`, `UNICOACH_CLIENT_KEY`, and
(device only) the signing args (`DEVELOPMENT_TEAM`, `CODE_SIGN_STYLE=Automatic`,
`-allowProvisioningUpdates`) to `xcodebuild`. `install-ios` resolves the built
`.app` under `$UNICOACH_CONFIGURATION-iphoneos` from the same target file, so
build and install always agree on configuration. The test override hooks
`UNICOACH_ENV_DIR`, `UNICOACH_DOTENV`, and `UNICOACH_PRODUCTS_DIR` are
unchanged; internal variable names (`ENV_DIR`, `ENV_FILE`, `ENV_NAME`) are
retained — only user-facing help/usage text adopts "target" terminology.

### Checked-in deploy targets

`ios-app/env/prod.env` (device) and `ios-app/env/prod-simulator.env` (simulator)
both set `UNICOACH_BACKEND_URL="https://api.unicoachapp.com"` and omit
`UNICOACH_CONFIGURATION` (inheriting `Debug`). `prod.env` sets
`UNICOACH_DESTINATION="generic/platform=iOS"` (device → requires `signing.env`);
`prod-simulator.env` sets
`UNICOACH_DESTINATION="generic/platform=iOS Simulator"` (no signing, so any
contributor can build a simulator app targeting the live deployment). Both are
made trackable by `.gitignore` allowlist lines despite the `ios-app/env/*.env`
ignore.

The deployed app reaches `https://api.unicoachapp.com` over HTTPS under the
existing `NSAllowsArbitraryLoads: true` ATS exception (which permits, not
requires, plain HTTP) — no transport-security change.

### Deployment cookie domain (server/infra)

For a deploy target to keep a session, the server's `Set-Cookie` `Domain` must
match the host the app calls. The server derives `session.cookieDomain` from
`${?APP_DOMAIN}` with base default `localhost` (`rest-server.conf`); the
deployment seeds no `APP_DOMAIN`, so `cookieDomain` resolves to `localhost`,
does not match `api.unicoachapp.com`, and the cookie is dropped — the session
does not survive a relaunch.

`infra/ssm.tf` is changed to seed `APP_DOMAIN = var.api_domain` as a non-secret
`String` parameter and to delete the dead
`SESSION_COOKIE_DOMAIN = var.api_domain` entry. The server stopped reading
`SESSION_COOKIE_DOMAIN` when `cookieDomain` was repointed to `${?APP_DOMAIN}`;
the parameter has had no reader since, and removing it from the OpenTofu-owned
`String` set causes the next `tofu apply` to destroy it. `render-env` flattens
the whole `/unicoach/prod/` prefix into `/etc/unicoach/env`, the systemd
`EnvironmentFile` for the server unit, so `APP_DOMAIN` enters the JVM
environment and `cookieDomain` resolves to `api.unicoachapp.com`.

`APP_DOMAIN` is read server-side at exactly one site, the `cookieDomain`
substitution. Host binding uses `SERVER_HOST` (`0.0.0.0`) and the JWT issuer is
seeded independently as `JWT_ISSUER = "https://${var.api_domain}/"`, both
unchanged; seeding `APP_DOMAIN` therefore has the single effect of setting the
cookie domain. The session cookie is already marked `Secure`
(`SESSION_COOKIE_SECURE = "true"`), correct for the HTTPS deployment — no
change.

`var.api_domain` (default `api.unicoachapp.com`) is the single deployment-side
declaration of the host. The prod app targets independently declare the same
host in their `UNICOACH_BACKEND_URL`. The two are coupled by value, not by a
shared file (one is bash, one is HCL); a mismatch surfaces immediately as a
session that does not persist on the prod target, so no automated cross-file
drift guard is added (see Tests).

### Error handling / edge cases

- **Bare-IP host.** The RFC 6265 bare-IP rejection (an invalid cookie `Domain`)
  is preserved on the **derive** path only. An explicit `UNICOACH_BACKEND_URL`
  is not parsed or validated; the prod URL is a DNS host, and the only
  unreviewed path that could introduce a bad explicit URL is the unimplemented
  `<target>.local.env` seam.
- **Honor short-circuit vs stale `.env`.** A bare-IP `APP_DOMAIN` left in a
  developer's `.env` does not break an explicit-URL build — the honor path skips
  derivation and its bare-IP check (see §"Backend URL").
- **Device build of a deploy target without `signing.env`.** `prod.env` is a
  device target, so `build-ios` still fatals if `signing.env` /
  `UNICOACH_DEVELOPMENT_TEAM` is absent — unchanged.
- **`install-ios` against `prod-simulator`.** Rejected by the existing
  device-only guard (destination contains `Simulator`).
- **Dev-shell guard.** Both scripts continue to refuse to run inside the Nix dev
  shell (`bin/is-nix`); this RFC does not touch that path.
- **Configuration default.** Prod targets omit `UNICOACH_CONFIGURATION`, so both
  build and install resolve `Debug-iphoneos`; installability to a device is
  governed by signing, not configuration.

### Dependencies

None new. System Xcode toolchain (`xcodebuild`, `xcrun devicectl`) and the
existing `bin/functions` / `bin/is-nix` helpers, as today. `ios-scripts-tests`
runs without Nix and without real Xcode (shimmed `xcodebuild`/`xcrun`).

## Tests

All script behaviour is exercised in `bin/ios-scripts-tests` (shims record
`xcodebuild`/`xcrun` argv; `UNICOACH_ENV_DIR`/`UNICOACH_DOTENV` point at temp
fixtures). New and changed tests:

- **`test_build_honors_explicit_backend_url`** — a simulator target fixture sets
  `UNICOACH_BACKEND_URL=https://api.unicoachapp.com`; the `.env` fixture sets a
  _different_ `APP_DOMAIN` (e.g. `mymac.example.ts.net`). Assert `xcodebuild`
  receives `UNICOACH_BACKEND_URL=https://api.unicoachapp.com` verbatim (HTTPS,
  no port) and **not** the derived `http://mymac.example.ts.net:8080`. Proves
  honor beats derive and forwards the URL unchanged.
- **`test_build_honors_explicit_url_passthrough_fidelity`** — a target fixture
  sets an explicit URL with a trailing-slash path and no port
  (`https://api.unicoachapp.com/`). Assert `xcodebuild` receives that exact byte
  string. Proves the honor path forwards an arbitrary URL shape byte-for-byte,
  not just the canonical no-path form.
- **`test_build_explicit_url_skips_bare_ip_check`** — a target fixture sets an
  explicit HTTPS URL while the `.env` fixture sets a bare-IP `APP_DOMAIN`
  (`192.168.1.42`) that the derive path would reject. Assert the build
  **succeeds** and forwards the explicit URL. Proves the short-circuit skips the
  bare-IP check on the honor path.
- **`test_build_explicit_url_device_forwards_signing`** — a device target
  fixture (`generic/platform=iOS`) sets an explicit URL with `signing.env`
  present. Assert the explicit URL is honored **and** the signing args
  (`DEVELOPMENT_TEAM`, `CODE_SIGN_STYLE=Automatic`, `-allowProvisioningUpdates`)
  are forwarded. Proves honor works on the device path.
- **`test_build_derives_when_url_unset`** (regression) — a target fixture with
  no `UNICOACH_BACKEND_URL` still derives `http://$APP_DOMAIN:$SERVER_PORT` from
  `.env`. (Existing `test_build_simulator_no_signing`,
  `test_build_device_forwards_signing`, and
  `test_build_derives_port_from_server_port` already cover this; add one
  explicit assertion that an empty/unset `UNICOACH_BACKEND_URL` takes the derive
  path.)
- **`test_prod_targets_committed_and_wellformed`** — sources the **real**
  `ios-app/env/prod.env` and `ios-app/env/prod-simulator.env` (not the fixture
  dir). Assert each sets `UNICOACH_BACKEND_URL=https://api.unicoachapp.com`;
  that `prod.env`'s `UNICOACH_DESTINATION` is a device destination (no
  `Simulator` substring) and `prod-simulator.env`'s contains `Simulator`; and
  that neither sets `UNICOACH_CLIENT_KEY`. Guards the deliverable files against
  drift or deletion.
- **`test_prod_targets_not_gitignored`** — assert `git check-ignore` reports
  `ios-app/env/prod.env` and `ios-app/env/prod-simulator.env` as **not** ignored
  (exit 1), while a control path `ios-app/env/throwaway.env` **is** ignored
  (exit 0). Guards the gitignore allowlist.

Each new test is registered in the `assert_success` block. Existing tests
(`test_build_bare_ip_rejected`, simulator/device derive tests, install-ios
tests, dev-shell guard tests) remain unchanged and must still pass.

### Deployment cookie domain

No new automated test covers the `infra/ssm.tf` change. The `APP_DOMAIN` →
`session.cookieDomain` substitution is already locked by `SessionConfigTest`
(`derives cookieDomain from APP_DOMAIN substitution in rest-server conf`,
`cookieDomain defaults to localhost when APP_DOMAIN is unset`), proving a seeded
`APP_DOMAIN` resolves through to the cookie domain. The `ssm.tf` edit itself is
verified at apply time, not by an automated test: Plan step 6 asserts
`APP_DOMAIN` is present and `SESSION_COOKIE_DOMAIN` is absent via `grep`. A
cross-file guard between `prod.env` and `var.api_domain` is deliberately omitted
as brittle for a near-static value (see §"Deployment cookie domain" in Detailed
Design).

## Implementation Plan

1. **Add the checked-in deploy targets and gitignore allowlist.** Create
   `ios-app/env/prod.env` (device destination + explicit HTTPS URL, no
   configuration, no client key) and `ios-app/env/prod-simulator.env` (simulator
   destination + same URL). Add `!ios-app/env/prod.env` and
   `!ios-app/env/prod-simulator.env` after the `ios-app/env/*.env` line in
   `.gitignore`.
   - Verify: `git check-ignore -v ios-app/env/prod.env` exits 1 (not ignored);
     `git check-ignore -v ios-app/env/throwaway.env` exits 0 (ignored).
   - Verify:
     `bash -c 'set -a; source ios-app/env/prod.env; set +a; [ "$UNICOACH_BACKEND_URL" = https://api.unicoachapp.com ]'`
     succeeds; same for `prod-simulator.env`.

2. **Implement honor-if-set in `bin/build-ios`.** Wrap the
   `APP_DOMAIN`/`SERVER_PORT` defaulting, bare-IP rejection, and URL composition
   in a guard that runs only when `UNICOACH_BACKEND_URL` is empty after the
   target file and `.env` are sourced; leave `.env` sourcing unconditional.
   Update the help text to describe honor-if-set and rename the positional arg
   to `target` in the usage banner (`build-ios [env]` → `build-ios [target]`),
   mentioning the `prod` target. Add a comment marking the `<target>.local.env`
   seam insertion point (sourced last; not implemented).
   - Verify: `bash -n bin/build-ios`.

3. **Update `bin/install-ios` wording.** Adopt "target" terminology in
   help/usage and reference the `prod` target; no functional change.
   - Verify: `bash -n bin/install-ios`.

4. **Update the env templates.** In `ios-app/env/signing.env.example`, document
   the three-bucket model (this file = per-developer/machine bucket) and the
   `<target>.local.env` secret seam. In `ios-app/env/local.env.example` and
   `ios-app/env/simulator.env`, add a one-line note that a target may set
   `UNICOACH_BACKEND_URL` to skip derivation (pointing at `prod.env` as the
   example).
   - Verify:
     `bash -c 'set -a; source ios-app/env/simulator.env; set +a; [ -n "$UNICOACH_DESTINATION" ]'`.

5. **Add tests to `bin/ios-scripts-tests`.** Add the new fixtures and the tests
   enumerated above, and register each in the `assert_success` block.
   - Verify: `bin/ios-scripts-tests` (runs under system Xcode shims, not Nix) —
     all assertions pass.

6. **Seed `APP_DOMAIN` on the deployment and retire `SESSION_COOKIE_DOMAIN`.**
   In `infra/ssm.tf`, add `APP_DOMAIN = var.api_domain` to
   `local.ssm_string_params` and delete the
   `SESSION_COOKIE_DOMAIN = var.api_domain` entry.
   - Verify: `nix develop -c tofu -chdir=infra fmt -check ssm.tf`.
   - Verify:
     `grep -Eq 'APP_DOMAIN[[:space:]]*=[[:space:]]*var\.api_domain' infra/ssm.tf`.
   - Verify: `! grep -q 'SESSION_COOKIE_DOMAIN' infra/ssm.tf`.

7. **Update `ios-app/DEPLOY.md`.** Document named targets, the
   honor-if-set-else-derive rule, the three-bucket configuration boundary, the
   `prod` / `prod-simulator` deploy targets and how to build/install them, the
   `<target>.local.env` seam, and that the deployment seeds
   `APP_DOMAIN = var.api_domain` so the prod targets persist a session (the
   app-host / `var.api_domain` coupling).
   - Verify: `nix develop -c deno fmt --check ios-app/DEPLOY.md`.

## Files Modified

- `bin/build-ios` — honor-if-set backend-URL resolution (guard the derive
  block); help/usage "target" terminology; `<target>.local.env` seam comment.
- `bin/install-ios` — "target" terminology in help/usage; reference `prod`. No
  functional change.
- `bin/ios-scripts-tests` — new fixtures and tests for honor-if-set, the bare-IP
  short-circuit, device honor + signing, the committed prod targets, and the
  gitignore allowlist; register them.
- `ios-app/env/prod.env` — NEW (checked in): device deploy target, explicit
  `https://api.unicoachapp.com`.
- `ios-app/env/prod-simulator.env` — NEW (checked in): simulator deploy target,
  same explicit URL.
- `ios-app/env/signing.env.example` — document the three-bucket model and the
  `<target>.local.env` secret seam.
- `ios-app/env/local.env.example` — note the honor-if-set option.
- `ios-app/env/simulator.env` — note the honor-if-set option.
- `.gitignore` — allowlist `ios-app/env/prod.env` and
  `ios-app/env/prod-simulator.env`.
- `infra/ssm.tf` — seed `APP_DOMAIN = var.api_domain` (drives
  `session.cookieDomain` on the deployment); delete the dead
  `SESSION_COOKIE_DOMAIN` parameter the server no longer reads.
- `ios-app/DEPLOY.md` — document named targets, honor-if-set, the three-bucket
  boundary, the deploy targets, the local-secret seam, and that the deployment
  seeds `APP_DOMAIN = var.api_domain` so the prod targets persist a session.
