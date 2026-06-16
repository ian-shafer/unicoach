# 54 — Client-Key Gate

## Executive Summary

The deployed REST API is reachable by anyone who knows its URL. This RFC adds a
client-key gate: a single application-level interceptor that runs before any
route handler and rejects, with `403`, every request that does not carry a valid
client key in a dedicated header. The goal is to raise the bar for an eventual
AWS deployment so that only clients the operator controls (the iOS app and the
operator's own tooling/CI) reach the API — not strong security. A key baked into
a publicly-distributed app binary is extractable, so the gate does not defend
against a determined attacker; App Attest (device attestation) would be the real
wall and is out of scope.

The gate is independent of the existing session cookie. The client key answers
"is this one of my clients?"; the session cookie answers "who is the user?".
Protected routes require both. Config holds a _set_ of valid keys (loaded from a
single env var, never committed) so keys can be rotated without downtime and
issued distinctly per client, each independently revocable. The gate applies to
all routes including the public `auth/register` and `auth/login` routes; only
the `/healthz` health-check route is exempt so AWS ALB/target-group probes pass.
The durable invariant the gate establishes: a central key check fronts every
route except an explicit health-check allowlist, and it is orthogonal to
user-session authentication. It is a deploy-time control, enabled in production
solely by setting the env var.

## Detailed Design

### Header

A dedicated request header carries the key:

```
X-Unicoach-Client-Key: <key>
```

The header name is a compile-time constant on both server and client, not a
config value, and is kept separate from the `UNICOACH_SESSION` cookie (the
compose-independently/both-required invariant is stated in the Executive
Summary).

### Configuration

A new `clientKeyGate` block is added to
`rest-server/src/main/resources/rest-server.conf`, following the existing
`${?ENV}` override pattern (`APP_DOMAIN`, `SESSION_COOKIE_SECURE`):

```hocon
clientKeyGate {
    keys = ""
    keys = ${?UNICOACH_CLIENT_KEYS}
    allowlistPaths = ["/healthz"]
}
```

`keys` is a **comma-separated string**, not a HOCON list. Environment variables
are scalars and AWS Secrets Manager injects a single value, so a delimited
string is the override-compatible representation; the set is reconstructed at
parse time. `allowlistPaths` is a committed HOCON list (not a secret), so the
native list form is used.

`keys` is never committed. Locally it stays blank (gate disabled). In production
it is supplied from the task environment / Secrets Manager. Distinct keys (one
for the iOS app, one for control tooling) are comma-joined in the same variable;
revoking one means removing it from the list and redeploying.

A config model mirrors the `RequestSizeConfig.from(config): Result<T>` pattern
in `rest-server/src/main/kotlin/ed/unicoach/rest/config/`:

```kotlin
data class ClientKeyGateConfig(
  val validKeys: Set<String>,
  val allowlistPaths: Set<String>,
) {
  companion object {
    fun from(config: Config): Result<ClientKeyGateConfig>
  }
}
```

`from` reads `config.getConfig("clientKeyGate")`, splits `keys` on `,`, trims
each element and drops blanks into `validKeys`, and reads `allowlistPaths` (a
string list) into `allowlistPaths`. It is **total**, mirroring
`RequestSizeConfig.from`: every failure — the `clientKeyGate` section being
absent, or `allowlistPaths` being mistyped (a scalar where `getStringList`
expects a list) — is returned as `Result.failure`, never thrown. An empty
`validKeys` set is valid and parses successfully — it denotes the disabled gate.

### Gate interceptor

The gate is an `Application` extension function in
`rest-server/src/main/kotlin/ed/unicoach/rest/plugins/`, mirroring the
`configureStatusPages` / `configureRequestSizeLimit` style rather than the
`SessionExpiryPlugin` object style, because the gate must short-circuit the
pipeline:

```kotlin
fun Application.configureClientKeyGate(config: ClientKeyGateConfig)
```

It installs an interceptor on the `ApplicationCallPipeline.Plugins` phase, which
runs before routing. Per call, in order:

1. If `config.validKeys` is empty, proceed (gate disabled — fail open).
2. If `call.request.path()` is an exact member of `config.allowlistPaths`,
   proceed. Exact match, not prefix: a prefix allowlist of `/healthz` would also
   admit `/healthzextra`. The request path (not `uri`) is used so a query string
   cannot affect matching.
3. Otherwise read the `X-Unicoach-Client-Key` header. If it is absent, or its
   value matches no configured key, respond `403` and call `finish()` to halt
   the pipeline so no route handler runs.

Key comparison is constant-time via `java.security.MessageDigest.isEqual`,
comparing the UTF-8 bytes of the provided value against each configured key. The
match folds over the entire key set without short-circuiting on the first hit
(boolean OR accumulation), so neither which key matched nor how many were
checked is observable through timing (`isEqual` returns early on a length
mismatch, leaking key length — acceptable for a raise-the-bar control).

Installation is wired in `Application.appModule` after `configureSerialization`
(so the `403` body serializes through the installed `ContentNegotiation`) and
before `configureRouting`. `startServer` loads the config via
`ClientKeyGateConfig.from(config).getOrThrow()` and passes it into `appModule`,
extending the existing parameter list
(`database, sessionConfig, requestSizeConfig, chatProvider, coachingConfig`).

### Error response and edge cases

A rejected request receives `403 Forbidden` with the existing
`ErrorResponse(code, message)` body shape used by `configureStatusPages`, using
a lowercase code consistent with that plugin's convention:

```json
{ "code": "forbidden", "message": "Valid client key required." }
```

A missing header and an invalid key return the **same** response; the gate does
not reveal which condition failed. The gate does not inspect or depend on the
session cookie and contributes no cookie or session side effects: it
`finish()`es the pipeline before any route handler runs. Session extension is
independently unaffected because `SessionExpiryPlugin` already excludes non-2xx
responses (`ResponseSent` on a status outside `200..299`).

### Dependencies

No new libraries. `MessageDigest` is JDK-standard; HOCON parsing, Ktor
interceptors, and `ErrorResponse` already exist in the module.

### iOS client

The client key is baked into the app bundle at build time using the exact
mechanism already used for `UNICOACH_BACKEND_URL` (see `ios-app/DEPLOY.md`): a
build setting → `Info.plist` substitution → `Bundle.main` read.

- `bin/build-ios` reads `UNICOACH_CLIENT_KEY` from the repo `.env` (blank by
  default) and passes it verbatim to `xcodebuild` as a build-setting argument on
  the existing `exec xcodebuild` invocation. Unlike the composed
  `UNICOACH_BACKEND_URL` (built from `APP_DOMAIN`/`SERVER_PORT`), the key is a
  raw secret passed through unchanged. The argument is written
  `"UNICOACH_CLIENT_KEY=${UNICOACH_CLIENT_KEY:-}"` so an unset variable bakes
  blank under the script's `set -euo pipefail`.
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` declares
  `UNICOACH_CLIENT_KEY = "";` as the default build setting in both
  configurations (matching the existing `UNICOACH_BACKEND_URL` default).
- `ios-app/UnicoachiOS/Info.plist` adds a key whose value is the build-setting
  reference:

  ```xml
  <key>UnicoachClientKey</key>
  <string>$(UNICOACH_CLIENT_KEY)</string>
  ```

- A new `ios-app/UnicoachiOS/ClientKey.swift` mirrors `BackendURL.swift`'s
  pure/impure split: a pure
  `func resolveClientKey(_ infoValue: String?) -> String?` that trims whitespace
  and returns `nil` for nil/blank input (unit-testable without a bundle), and
  `func defaultClientKey() -> String?` that reads the `UnicoachClientKey`
  Info.plist value via `Bundle.main.object(forInfoDictionaryKey:)` and delegates
  to `resolveClientKey`.
- `ios-app/UnicoachiOS/APIClient.swift` gains a stored client-key property,
  injectable via `init` with a default of `defaultClientKey()` (mirroring how
  `baseURL` defaults to `defaultBackendURL()`), and sets the
  `X-Unicoach-Client-Key` header on every request it builds — in both the
  non-streaming `perform` path and the `stream` path — when the key is non-nil.
  When the key is nil (local build, blank key) no header is sent, which the
  disabled local gate accepts.

The baked-in key is extractable from the distributed binary; this is the
documented limitation of the approach.

## Tests

Server tests use the module's two established harnesses: `testApplication { }`
for the gate/interceptor behavior and direct `from(config)` calls for config
parsing. Existing routing tests (`AuthRoutingTest`, `StudentRoutingTest`,
`ConvoRoutingTest`, `RoutingTest`, …) are unchanged because the default
`rest-server.conf` `keys` is blank, so the gate is disabled under
`startServer()`.

### `ClientKeyGateConfigTest` (config parsing)

- `keys` of `"k1,k2,k3"` parses to the set `{k1, k2, k3}`.
- Whitespace around delimited keys is trimmed: `" k1 , k2 "` → `{k1, k2}`.
- Blank `keys` (`""`) parses to an empty `validKeys` set, returning success.
- A `keys` value with empty segments (`"k1,,k2,"`) drops the blanks →
  `{k1, k2}`.
- `allowlistPaths` parses from a HOCON string list into the expected set.
- A `Config` lacking the `clientKeyGate` section returns `Result.failure`.
- A `Config` whose `allowlistPaths` is a scalar (not a list) returns
  `Result.failure` rather than throwing, proving `from` is total.

### `ClientKeyGateTest` (interceptor behavior, `testApplication`)

A minimal app installs `configureSerialization` and `configureClientKeyGate`
with a known config and registers a single test route (e.g. `GET /api/v1/ping`)
that responds with an explicit `200 OK`, plus the `/healthz` allowlisted route.
Because the gate intercepts before routing, the test route must exist and return
`200` so "reaches the route" is distinguishable from a gate-passed request
hitting an unregistered path (which would `404`); the passing cases assert
`200`.

- **Valid key accepted**: request with a configured key on
  `X-Unicoach-Client-Key` reaches the route and returns `200`.
- **Each key in the set accepted**: with `validKeys = {kA, kB}`, a request with
  `kA` and a separate request with `kB` each return `200`.
- **Invalid key rejected**: a request with an unconfigured key returns `403`
  with body `{"code":"forbidden", ...}` and does not reach the route.
- **Missing header rejected**: a request with no `X-Unicoach-Client-Key` returns
  `403` with the identical body, indistinguishable from the invalid-key case.
- **Health-check bypass**: a request to `/healthz` with no header and a
  non-empty key set returns `200` (allowlist exact-match bypass).
- **Non-allowlisted lookalike not bypassed**: a request to `/healthzextra` (no
  header) returns `403`, proving exact-match rather than prefix.
- **Disabled gate (fail open)**: with `validKeys = {}` (empty), a request with
  no header reaches the route and returns `200`.

### iOS

Header injection is verified by build and manual inspection rather than an
automated test: `bin/build-ios` with `UNICOACH_CLIENT_KEY` set produces a bundle
whose `APIClient` sends the header; the `APIClient` client-key `init` parameter
keeps the behavior unit-testable if a future Swift test is added. No automated
iOS test is added in this RFC.

## Implementation Plan

1. **Add `ClientKeyGateConfig` and the `clientKeyGate` config block.** Create
   `rest-server/src/main/kotlin/ed/unicoach/rest/config/ClientKeyGateConfig.kt`
   (data class + `from(config): Result<ClientKeyGateConfig>` parsing
   comma-split/trim/filter `keys` and the `allowlistPaths` string list; failure
   when the section is absent). Add the `clientKeyGate` block to
   `rest-server/src/main/resources/rest-server.conf`.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`
   - Verify:
     `nix develop -c ktlint rest-server/src/main/kotlin/ed/unicoach/rest/config/ClientKeyGateConfig.kt`

2. **Add the config parsing tests.** Create
   `rest-server/src/test/kotlin/ed/unicoach/rest/config/ClientKeyGateConfigTest.kt`
   with the `ClientKeyGateConfigTest` cases above.
   - Verify:
     `nix develop -c bin/test rest-server --force --tests "ed.unicoach.rest.config.ClientKeyGateConfigTest"`
   - Verify:
     `nix develop -c ktlint rest-server/src/test/kotlin/ed/unicoach/rest/config/ClientKeyGateConfigTest.kt`

3. **Add the gate interceptor.** Create
   `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/ClientKeyGate.kt` with
   the `X-Unicoach-Client-Key` header constant and
   `fun Application.configureClientKeyGate(config: ClientKeyGateConfig)`
   intercepting `ApplicationCallPipeline.Plugins`: disabled-when-empty,
   exact-path allowlist bypass, constant-time `MessageDigest.isEqual` match over
   all keys, `403` + `finish()` on failure with
   `ErrorResponse(code = "forbidden", ...)`.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`
   - Verify:
     `nix develop -c ktlint rest-server/src/main/kotlin/ed/unicoach/rest/plugins/ClientKeyGate.kt`

4. **Wire the gate into the application module.** In
   `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`: load
   `ClientKeyGateConfig.from(config).getOrThrow()` in `startServer`, thread it
   into `appModule`'s signature and call, and call
   `configureClientKeyGate(clientKeyGateConfig)` after `configureSerialization`
   and before `configureRouting`.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`
   - Verify:
     `nix develop -c ktlint rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`

5. **Add the interceptor behavior tests.** Create
   `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/ClientKeyGateTest.kt`
   with the `ClientKeyGateTest` `testApplication` cases above.
   - Verify:
     `nix develop -c bin/test rest-server --force --tests "ed.unicoach.rest.plugins.ClientKeyGateTest"`
   - Verify:
     `nix develop -c ktlint rest-server/src/test/kotlin/ed/unicoach/rest/plugins/ClientKeyGateTest.kt`

6. **Full rest-server regression (existing suite stays green, gate disabled by
   default).**
   - Verify: `nix develop -c bin/test rest-server --force`

7. **iOS build-time injection and header.** In `bin/build-ios`, read
   `UNICOACH_CLIENT_KEY` from the sourced `.env` and add
   `"UNICOACH_CLIENT_KEY=${UNICOACH_CLIENT_KEY:-}"` to the `exec xcodebuild`
   invocation (the line that already passes `"UNICOACH_BACKEND_URL=..."`); add
   the `UNICOACH_CLIENT_KEY = "";` default build setting to both configurations
   in `ios-app/UnicoachiOS.xcodeproj/project.pbxproj`; add the
   `UnicoachClientKey` key to `ios-app/UnicoachiOS/Info.plist`; create
   `ios-app/UnicoachiOS/ClientKey.swift`; add the injectable client-key property
   and the `X-Unicoach-Client-Key` header (both `perform` and `stream`) to
   `ios-app/UnicoachiOS/APIClient.swift`; document the variable in
   `ios-app/DEPLOY.md`.
   - Verify: `UNICOACH_CLIENT_KEY=testkey bin/build-ios` (outside the nix shell;
     uses system `xcodebuild`, scheme `UnicoachiOS`) builds successfully.

8. **Document the env vars.** Add `UNICOACH_CLIENT_KEYS` (server,
   comma-separated, blank locally) and `UNICOACH_CLIENT_KEY` (iOS build, blank
   locally) to `.env.template` with comments noting they must be supplied from
   the environment / Secrets Manager in deployment and never committed.
   - Verify: `nix develop -c bin/test rest-server --force` (config still loads
     with the documented-but-blank defaults)

## Files Modified

New:

- `rest-server/src/main/kotlin/ed/unicoach/rest/config/ClientKeyGateConfig.kt` —
  config model + `from(config)` parser.
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/ClientKeyGate.kt` —
  header constant + `configureClientKeyGate` interceptor.
- `rest-server/src/test/kotlin/ed/unicoach/rest/config/ClientKeyGateConfigTest.kt`
  — config parsing tests.
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/ClientKeyGateTest.kt` —
  interceptor behavior tests.
- `ios-app/UnicoachiOS/ClientKey.swift` — pure `resolveClientKey` +
  `defaultClientKey()` Info.plist reader, mirroring `BackendURL.swift`.

Updated:

- `rest-server/src/main/resources/rest-server.conf` — add the `clientKeyGate`
  block with the `${?UNICOACH_CLIENT_KEYS}` override and `allowlistPaths`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — load
  `ClientKeyGateConfig`, thread it through `appModule`, install the gate after
  serialization and before routing.
- `bin/build-ios` — read `UNICOACH_CLIENT_KEY` from `.env` and pass it verbatim
  to `xcodebuild` as a build setting on the existing `exec xcodebuild` line.
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` — add the
  `UNICOACH_CLIENT_KEY` default build setting to both configurations.
- `ios-app/UnicoachiOS/Info.plist` — add the `UnicoachClientKey` key.
- `ios-app/UnicoachiOS/APIClient.swift` — injectable client-key property; send
  the `X-Unicoach-Client-Key` header on the `perform` and `stream` paths.
- `ios-app/DEPLOY.md` — document the `UNICOACH_CLIENT_KEY` build-time injection.
- `.env.template` — document `UNICOACH_CLIENT_KEYS` and `UNICOACH_CLIENT_KEY`.
