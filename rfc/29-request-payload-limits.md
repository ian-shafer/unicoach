# RFC 29: Request Payload Size Limits

## Executive Summary

The REST server applies no upper bound on request body size. One
`AuthRoutingTest` assertion fails as a result: an oversized body posted to
`/api/v1/auth/register` is not rejected with `413`, because no size check exists
on the request-ingress path.

This RFC installs Ktor's `RequestBodyLimit` plugin **once at application scope**
with a path-aware `bodyLimit` callback to enforce a configurable maximum request
body size, returning `413 Payload Too Large` when exceeded. Enforcement happens
in the application call/receive pipeline that every routed call traverses, so
**every route — current or future — is covered automatically with zero per-route
wiring**: there is no per-route opt-in a developer could forget. The global
default `server.requestSize.maxSize` (`"8 KiB"`) governs any path with no
override; a `routeOverrides` entry only tightens or loosens that default for its
exact path. `/api/v1/auth/register` overrides to 1 KiB. The size check runs
before content negotiation, so an oversized body is rejected as `413` before
`JacksonConverter` can produce a `400`.

Request-size limits are carried by a `DataSize` value type in the `common`
module, replacing raw `Long` byte primitives. Its non-negative invariant is
enforced at construction, so a negative limit cannot be represented and a
negative configured value becomes a config-load failure rather than reaching
Ktor.

It also adds an explicit `StatusPages` handler mapping
`PayloadTooLargeException` to `413`, which would otherwise fall through the
`Throwable` catch-all and return `500`.

This RFC also enables the JUnit Platform test engine in the `common` module
(`useJUnitPlatform()`), without which `common`'s JUnit 5 tests — including the
new `DataSizeTest` — are never discovered or executed by Gradle. Enabling it
also makes the pre-existing `AppConfigTest` run for the first time, surfacing
two stale assertions that fail when executed (they couple a test of the generic
config-load mechanism to production values owned by other modules); this RFC
rewrites them to test the `AppConfig.load` mechanism against self-contained
fixtures.

Malformed-body mapping (`{"email": 123}` → `400`/`bad_request`) is already
handled by the existing `exception<BadRequestException>` handler and is out of
scope; the corresponding test is retained as a regression guard. No changes to
domain logic, the auth flow, or persistence. Scope is confined to the `413`
payload-limit ingress edge, the `DataSize` refactor, and the `common`
test-engine enablement (including the `AppConfigTest` repair it forces).

## Detailed Design

### Global-Default Invariant ("impossible to forget a payload limit")

**Guarantee.** Every route the server exposes is subject to a body-size limit
with no per-route wiring. The limit is enforced once, in the application
call/receive pipeline, by a single application-scope
`install(RequestBodyLimit)`. Because that pipeline is traversed by every routed
call, a route added in the future is covered the moment it exists — the author
writes a plain Ktor route and gets the global default for free. There is **no
per-route opt-in**, so there is nothing a developer can forget to add. A
`routeOverrides` entry for a path is the _only_ way to deviate (tighten or
loosen) from the default for that path; its absence means the global default
governs.

**Rejected alternative — per-route / custom-route-builder enforcement.** An
approach that enforces the limit via a route-scoped install (e.g. a
`Route.applyRouteBodyLimit(...)` helper called per route, or a custom
`securePost`/`limitedPost` route builder) is **bypassable**: any route declared
with a plain Ktor `post`/`get`/`route` function silently skips the limit, and
the omission is invisible at the call site. That is the "forgettable opt-in"
failure mode (`design-review-impossible-misuse`). The application-scope single
install is chosen precisely because it removes the opt-in: the limit cannot be
bypassed by choosing a different route function, because no route function
participates in enforcement at all.

The prior iteration of this RFC attempted route-scoped enforcement via
`Route.applyRouteBodyLimit` plus a
`route("/register") { install(...); post {...} }` restructure. That design was
unimplementable and incorrect for two compounding reasons, both removed by this
revision:

1. `RequestBodyLimit` is a `RouteScopedPlugin`. Installing it at **both**
   application scope (the global default) **and** route scope (the `/register`
   override) throws `DuplicatePluginException` at server boot.
2. The override never functioned even before that exception surfaced: the
   route-override lookup compared an unquoted path against config map keys that
   the parser produced quoted, so `applyRouteBodyLimit` always early-returned
   and the route-scoped install never fired. The `413` test passed only because
   its ~10 KB body also exceeded the global 8 KiB default — never the intended 1
   KiB override.

**Mechanism (verified against `ktor-server-body-limit:3.4.2`).** A single
application-scope `install(RequestBodyLimit)` enforces limits on every route
through two complementary interceptors, both placed on pipelines that every
routed call traverses. Both interceptors obtain the applicable limit by invoking
the configured `bodyLimit` callback with the current `ApplicationCall`, so a
single install with a path-aware callback yields per-path limits without a
second install:

1. **Eager `Content-Length` rejection.** The plugin registers an `onCall`
   interceptor on the application call pipeline. It reads
   `request.contentLength()` and throws `PayloadTooLargeException` before the
   route handler runs when the declared length exceeds the callback's limit for
   that call — including on routes that never call `call.receive` (e.g. a GET
   handler). Every call traverses the application call pipeline, so this runs
   application-wide.
2. **Lazy streamed-body enforcement.** The plugin's `BeforeReceive` hook
   intercepts the `ApplicationReceivePipeline` (`Before` phase) and wraps the
   body channel to enforce the callback's limit as bytes are read (covering
   chunked bodies with no `Content-Length`). When routing resolves a call,
   `RoutingRoot.executeResult` builds the routed call's receive pipeline by
   merging the application receive pipeline into it, so the application-scope
   `BeforeReceive` interceptor is present in every routed call's receive
   pipeline.

The `RequestSizeLimitTest` probe-route regression tests in **Tests** guard this
invariant against future Ktor upgrades that could alter pipeline-merge behavior.

### Path-Aware Limit Selection

The `bodyLimit` callback selects the limit by the request's exact path:

```
bodyLimit { call -> config.routeOverrides[call.request.path()]?.bytes ?: config.defaultMax.bytes }
```

- `call.request.path()` returns the decoded request path without query string.
- Lookup is an **exact string match** against `routeOverrides` keys (the full
  request path). It is therefore slash- and case-sensitive:
  `/api/v1/auth/register` and `/api/v1/auth/register/` are distinct keys, and an
  override applies to every HTTP method on the matched path (a size limit on a
  bodyless verb such as `GET` is inert, since `contentLength()` is null).
- A path with no override entry resolves to `config.defaultMax.bytes` — the
  Global-Default Invariant.

### Dependencies

Add the Gradle dependency `io.ktor:ktor-server-body-limit`, version-aligned to
the existing `ktor = "3.4.2"` catalog reference. The artifact is published at
`3.4.2` (`ktor-server-body-limit-jvm:3.4.2` resolves, and `:rest-server`
compiles against it). The `RequestBodyLimit` plugin is in package
`io.ktor.server.plugins.bodylimit`; it throws
`io.ktor.server.plugins.PayloadTooLargeException` (package
`io.ktor.server.plugins`, **not** the `.bodylimit` subpackage) when the
configured limit is exceeded.

### Data Models

#### `DataSize` value type (`common`)

Value type `DataSize` in the `common` module, package `ed.unicoach.common.util`
(parallels the existing `ed.unicoach.common.config` / `ed.unicoach.common.json`
packages; `common` already houses cross-cutting validated value types such as
`SecretString`). It encapsulates a non-negative byte count, replacing the raw
`Long` byte primitives previously used for request-size limits.

```kotlin
package ed.unicoach.common.util

@JvmInline
value class DataSize private constructor(val bytes: Long) {
  init {
    require(bytes >= 0) { "DataSize must be non-negative, got $bytes bytes" }
  }

  companion object {
    fun ofBytes(bytes: Long): DataSize = DataSize(bytes)
  }
}
```

- `@JvmInline value class` over a single `Long`: a zero-allocation wrapper with
  structural `equals` / `hashCode` derived from `bytes`.
- Private primary constructor + `init { require(...) }`: the only construction
  path is `DataSize.ofBytes`, which validates non-negativity. A negative
  `DataSize` cannot be represented (per `design-review-impossible-misuse` and
  `code-review-unit-encapsulation`).
- `bytes: Long` is the accessor the Ktor `bodyLimit { ... }` callback consumes
  (the callback's return type is `Long`).
- **The public surface is intentionally minimal.** No `ofKibibytes`, no
  `fromString`, no unit-conversion (`to*`) methods. Human-readable config sizes
  (`"8 KiB"`) are parsed by HOCON's spec-defined `Config.getBytes` in
  `RequestSizeConfig.from`, which returns the `Long` that `DataSize.ofBytes`
  wraps — so `DataSize` needs no parser of its own and is not the place that
  understands size strings. Such surface is deferred until a concrete consumer
  exists (`design-review-scale-restraints`, YAGNI).

#### `RequestSizeConfig` (`rest-server`)

Configuration value object `RequestSizeConfig` in package
`ed.unicoach.rest.config` (request-size limiting is an ingress concern distinct
from auth, so it does not live alongside `SessionConfig` in
`ed.unicoach.rest.auth`). It carries `DataSize` rather than raw `Long`:

```kotlin
data class RequestSizeConfig(
  val defaultMax: DataSize,
  val routeOverrides: Map<String, DataSize>,
) {
  companion object {
    fun from(config: com.typesafe.config.Config): Result<RequestSizeConfig>
  }
}
```

- `from` reads `server.requestSize.maxSize` via `Config.getBytes` (parsing a
  size string such as `"8 KiB"` — IEC binary, SI decimal, or a bare byte integer
  — into a `Long` byte count) and each entry of the
  `server.requestSize.routeOverrides` object (keyed by full route path) via
  per-key `getBytes`, wrapping each resulting `Long` via `DataSize.ofBytes`.
  Size-string parsing is delegated entirely to typesafe-config; `DataSize` owns
  only the non-negative-byte invariant.
- Follows the existing `SessionConfig.from` pattern: missing-section guard,
  `Result.success` / `Result.failure`, `catch (e: Exception)` envelope.
- **Missing/empty-section contract:**
  - Absent `server.requestSize` →
    `Result.failure(IllegalArgumentException(...))` (mirrors `SessionConfig`'s
    missing-`session` guard).
  - Present `server.requestSize` with `maxSize` set but `routeOverrides` absent
    or empty → `Result.success` with `routeOverrides = emptyMap()`; the global
    `defaultMax` then applies to every route.
  - Absent `maxSize` within a present `requestSize` → `Result.failure`.
- **Negative / unparseable value:** an unparseable `maxSize` string fails
  `getBytes`, and a negative configured value fails `DataSize.ofBytes`'s
  `require`; both are caught by `from`'s `catch (e: Exception)` envelope and
  surface as `Result.failure`. An invalid or negative `maxSize` or override is
  therefore a config-load failure rather than being passed to Ktor.

### Configuration Schema (`rest-server.conf`)

```hocon
server {
    # ... existing port / host ...
    requestSize {
        maxSize = "8 KiB"
        maxSize = ${?SERVER_MAX_REQUEST_SIZE}
        routeOverrides {
            "/api/v1/auth/register" = "1 KiB"
        }
    }
}
```

Route-override keys are the **full request path**. This is explicit and avoids a
hidden logical-name → path mapping. Values are HOCON size strings (`"8 KiB"`,
`"1 KiB"`) — or bare byte integers — parsed via `Config.getBytes`; `"8 KiB"` ≡
8192 and `"1 KiB"` ≡ 1024.

### API Contracts (behavioral)

| Condition                                                                | Status                                   | Body `code`              |
| ------------------------------------------------------------------------ | ---------------------------------------- | ------------------------ |
| Body size > applicable limit (Content-Length or streamed)                | `413 Payload Too Large`                  | `payload_too_large`      |
| JSON structural deserialization failure (malformed JSON / type mismatch) | `400 Bad Request`                        | `bad_request`            |
| Unsupported `Content-Type`                                               | `415 Unsupported Media Type` (unchanged) | `unsupported_media_type` |

Only the `413` row is new. The `400` and `415` rows document existing, unchanged
behavior (the `exception<BadRequestException>` and
`exception<UnsupportedMediaTypeException>` handlers in `StatusPages.kt`).
Response shape is the existing `ErrorResponse(code, message, fieldErrors?)`.
Note: service-layer registration validation (missing/invalid fields on a body
that _does_ deserialize) is a separate, out-of-scope path that returns
`400`/`validation_failed` via `RegisterOutcome.ValidationFailure` in
`AuthRoutes.kt`; it is distinct from the `bad_request` deserialization-failure
row above.

### Plugin Wiring

- `Application.configureRequestSizeLimit(config: RequestSizeConfig)` (package
  `ed.unicoach.rest.plugins`) performs the **single**
  `install(RequestBodyLimit)` at application scope, configuring the plugin's
  `bodyLimit` callback (an `(ApplicationCall) -> Long` on
  `RequestBodyLimitConfig`) to return
  `config.routeOverrides[call.request.path()]?.bytes ?: config.defaultMax.bytes`.
  This is the sole enforcement mechanism. There is no route-scoped install and
  no per-route helper.
- `RequestSizeConfig` is loaded in `startServer` and threaded
  `startServer → appModule → configureRequestSizeLimit`. The `appModule`
  signature is
  `Application.appModule(database: Database, sessionConfig: SessionConfig,
  requestSizeConfig: RequestSizeConfig)`;
  `configureRequestSizeLimit(requestSizeConfig)` is invoked inside `appModule`
  alongside `configureSerialization()` / `configureStatusPages()`.
- **Routing does not receive `RequestSizeConfig`.** Because enforcement is
  purely application-scope, nothing under `configureRouting`/`AuthRouteHandler`
  reads the config. `configureRouting(authService, sessionConfig)` takes no
  `requestSizeConfig` parameter, and
  `AuthRouteHandler(authService, sessionConfig)` has no `requestSizeConfig`
  constructor parameter. `/register` is a plain
  `post("/register") { handleRegister() }` (no `route { install(...); post }`
  restructure, no `applyRouteBodyLimit`, no `rejectUnsupportedMethods` change).

### StatusPages Handlers (added)

```kotlin
exception<PayloadTooLargeException> { call, _ ->
  call.respond(
    HttpStatusCode.PayloadTooLarge,
    ErrorResponse("payload_too_large", "Request body exceeds the maximum allowed size"),
  )
}
```

- `PayloadTooLargeException` resolves to
  `io.ktor.server.plugins.PayloadTooLargeException` (package
  `io.ktor.server.plugins`).

`StatusPages` dispatches to the handler registered for the most specific
superclass of the thrown exception. The `PayloadTooLargeException` handler takes
precedence over the existing `exception<Throwable>` catch-all. No other
`StatusPages` handler is added or modified; the existing
`exception<BadRequestException>` handler continues to cover the malformed-body
case unchanged.

### Error Handling / Edge Cases

- **Single install removes the stack-vs-replace question.** With only one
  application-scope install, there is no second (route-scoped) install to stack
  with or replace, and therefore no `DuplicatePluginException` risk. The limit
  for any call is whatever the path-aware callback returns — deterministic and
  independent of plugin-resolution semantics.
- **Ordering guarantee:** `RequestBodyLimit` enforces on `Content-Length` up
  front and on the streamed body during `call.receive`, before
  `JacksonConverter` runs. Because the size check precedes content negotiation,
  any body — well-formed or not — that exceeds the limit is rejected as `413`
  before `JacksonConverter` can produce a `400`.
- **Negative / unparseable configured size:** an unparseable `maxSize` string
  fails `Config.getBytes` and a negative value fails `DataSize.ofBytes`'s
  `require`; both are caught by `RequestSizeConfig.from` and returned as
  `Result.failure`, so `startServer`'s `getOrThrow()` fails fast at startup
  rather than installing an invalid limit.
- **Exact-path matching:** override selection uses `call.request.path()` exact
  string equality (see **Path-Aware Limit Selection**); a path with no matching
  key falls back to the default. This is the intended behavior — the default is
  always applied, so a key typo loosens that path to the default rather than
  removing the limit.
- **Existing handlers unchanged:** `BadRequestException` (400),
  `UnsupportedMediaTypeException` (415), `PermanentError` / `TransientError`,
  and the `Throwable` fallback (500) are retained.

### Dependencies (cross-module)

`RequestSizeConfig` (`rest-server`) references `DataSize` (`common`).
`rest-server` already declares `implementation(project(":common"))`, so **no new
Gradle module wiring is required** — only a new `common` source file
(`DataSize.kt`) consumed by `rest-server`, plus a `common` test file
(`DataSizeTest.kt`). `RequestSizeConfig.from` continues to consume the
`com.typesafe.config.Config` produced by `AppConfig.load`. No database, queue,
or service-layer changes.

**`common` test-engine enablement.** The `common` module's `build.gradle.kts`
lacks `tasks.withType<Test> { useJUnitPlatform() }`, which other modules (e.g.
`rest-server`) declare. Without it, Gradle's default test runner does not use
the JUnit Platform launcher and therefore never discovers `common`'s JUnit 5
(`kotlin-test-junit5`) tests — the new `DataSizeTest` would silently not
execute, and the suite would report success without running it. This RFC adds
that block so `DataSizeTest` (and the module's other already-present JUnit 5
tests) run.

Enabling the engine also makes the pre-existing `AppConfigTest` execute for the
first time, exposing two stale assertions that fail when run:

1. `load correctly prioritizes right-most module overrides` loaded only
   `common.conf` and asserted `database.connectionTimeout == 30000`, but that
   key is defined in `db.conf`, not `common.conf` — the assertion couples a test
   of the generic load/merge mechanism to a production value owned by another
   module.
2. `verify System getenv forcefully overrides all identically named fields`
   asserted `config.hasPath("PATH") || config.hasPath("USER")`, assuming HOCON
   exposes process environment variables as bare config paths, which it does
   not.

Because this RFC is what makes these tests run, repairing them is in scope. Both
are rewritten to exercise the `AppConfig.load` mechanism — right-to-left
resource merge and optional `${?VAR}` substitution — against self-contained test
fixtures (`merge-base.conf`, `merge-override.conf`, `env-substitution.conf`)
under `common/src/test/resources/`, with no dependency on production config
values or process environment. No other `common` test is modified.

## Tests

Assertions span three files: the existing
`rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt` (end-to-end,
against the production routing graph via `startServer`); a new
`rest-server/src/test/kotlin/ed/unicoach/rest/plugins/RequestSizeLimitTest.kt`
(plugin-in-isolation via `testApplication`, mirroring the existing
`SessionExpiryPluginTest`); and a new
`common/src/test/kotlin/ed/unicoach/common/util/DataSizeTest.kt` (value-type
unit test, mirroring `SecretStringTest`). The currently-failing `413` test must
become passing, the new tests must pass, and all existing tests must remain
green.

1. **`global default rejects oversized body on a route with no override` (new —
   `RequestSizeLimitTest`):** a `testApplication` installs
   `configureSerialization()`, `configureStatusPages()`, and
   `configureRequestSizeLimit(RequestSizeConfig(defaultMax = DataSize.ofBytes(8192),
   routeOverrides = emptyMap()))`,
   then defines a bare
   `post("/probe") { call.receive<String>(); call.respond(HttpStatusCode.OK) }`
   route with **no** body-limit wiring. The test posts a >8192-byte body with an
   explicit `Content-Length` header and asserts `413 Payload Too Large` and a
   body containing `payload_too_large`. The probe route stands in for an
   arbitrary future route; its rejection proves the application-scope install
   alone enforces the global default, guarding the **Global-Default Invariant**
   against regression. No database.

2. **`route override tightens the limit for its path only` (new —
   `RequestSizeLimitTest`):** the assertion the old design silently failed — it
   proves the override genuinely tightens **and** is path-specific (not a global
   change). A `testApplication` installs the same plugins with
   `RequestSizeConfig(defaultMax = DataSize.ofBytes(8192),
   routeOverrides = mapOf("/probe-override" to DataSize.ofBytes(1024)))`,
   and defines two bare routes that each `call.receive<String>()` and respond
   `200`: `post("/probe-override")` and `post("/probe-default")`. A ~2 KiB body
   (`> 1024` and `< 8192`, e.g. 2048 bytes) is posted to each with an explicit
   `Content-Length`:
   - `/probe-override` → asserts `413 Payload Too Large` and body contains
     `payload_too_large` (the 1 KiB override tightened below the body size).
   - `/probe-default` → asserts **not** `413` (e.g. `200 OK`) for the _same_
     body (the default 8 KiB still permits it), proving the override is scoped
     to its path and did not lower the global default. No database.

3. **`test large buffer mitigation rejection` (transitions to passing —
   `AuthRoutingTest`):** a ~10 027-byte body posted to `/api/v1/auth/register`
   with an explicit `Content-Length` header asserts `413 Payload Too Large`.
   Currently fails because no size check exists. **Note:** this body exceeds
   both the 1 KiB `/register` override and the 8 KiB global default, so it no
   longer _uniquely_ proves the override is wired — the global default alone
   would reject it. Test 2 above is what proves the override tightens. This test
   is retained as an end-to-end `413` smoke test through the production routing
   graph and the new `PayloadTooLargeException` handler.

4. **`test StatusPages deserialization boundaries` (regression guard — must stay
   green):** `{"email": 123}` posted to `/api/v1/auth/register` asserts
   `400 Bad Request` and that the body contains `bad_request`. This test
   **already passes on `main`** via the existing
   `exception<BadRequestException>` handler; this RFC adds no handler for it.
   The body is under 1 KiB, so the size check does not pre-empt it. It must
   remain green to confirm the `413` wiring does not regress the malformed-body
   path.

5. **Regression — must remain green:**
   - `test valid registration state simulation` — `201`, small body under the
     limit, `Set-Cookie` present.
   - `test header structure verification constraints` — `415` for `text/plain`,
     tiny body.
   - `test unique invariant and malicious vector rejection` — `409` on duplicate
     email.
   - `test CORS configuration validation hooks`,
     `test timing attack mitigation`, and all `/me`, `/login`, and `/logout`
     cases.

6. **`DataSizeTest` (new — `common`):** focused unit tests for the value type,
   mirroring `SecretStringTest`'s isolation pattern. Each needs no database:
   - `ofBytes stores the byte count` — `DataSize.ofBytes(8192).bytes == 8192`.
   - `ofBytes accepts a zero byte count` — boundary;
     `DataSize.ofBytes(0).bytes
     == 0`.
   - `ofBytes rejects a negative byte count` —
     `assertFailsWith<IllegalArgumentException> { DataSize.ofBytes(-1) }`.
   - `equal byte counts compare equal` —
     `DataSize.ofBytes(1024) ==
     DataSize.ofBytes(1024)` and
     `DataSize.ofBytes(1024) !=
     DataSize.ofBytes(2048)` (value-class
     structural equality).

   These tests execute only once `useJUnitPlatform()` is enabled for `common`
   (see **Dependencies (cross-module)**); verifying they run (and are not
   silently skipped) is part of the value of the build-script change.

7. **`AppConfigTest` (rewritten — `common`):** the two stale assertions un-gated
   by `useJUnitPlatform()` are replaced by mechanism tests against dedicated
   fixtures (see **Dependencies (cross-module)**), each needing no database:
   - `load merges resources right-to-left so the right-most resource overrides
     earlier keys`
     — loads `merge-base.conf` then `merge-override.conf`; asserts
     `app.name == "override"` (right-most wins) and
     `app.region == "base-region"` (a left-only key survives the merge).
   - `load resolves optional substitutions so external overrides win over in-file
     defaults`
     — sets system property `APP_CONFIG_TEST_OVERRIDE`, invalidates the
     `ConfigFactory` cache, loads `env-substitution.conf`, and asserts
     `app.value == "from-environment"` (the `${?APP_CONFIG_TEST_OVERRIDE}`
     substitution overrides the in-file default). An `@AfterTest` clears the
     property and re-invalidates the cache.

No unit test is specified for `RequestSizeConfig.from`; the integration
assertions cover its wired behavior end to end — including HOCON size-string
parsing, since `AuthRoutingTest` boots the production `rest-server.conf`
(`maxSize = "8 KiB"`, override `"1 KiB"`) through `startServer`. `DataSize`, by
contrast, is a reusable shared value type with construction-time invariants and
is unit-tested directly in `common` (`DataSizeTest`), following the
`SecretStringTest` precedent.

## Implementation Plan

1. **Add the dependency.** Add
   `ktor-server-body-limit = { module = "io.ktor:ktor-server-body-limit", version.ref = "ktor" }`
   to `gradle/libs.versions.toml`, and add
   `implementation(libs.ktor.server.body.limit)` to
   `rest-server/build.gradle.kts`.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`
2. **Enable the JUnit Platform engine in `common` and repair the tests it
   un-gates.** Add `tasks.withType<Test> { useJUnitPlatform() }` to
   `common/build.gradle.kts` so the module's JUnit 5 tests are discovered and
   executed. This makes the pre-existing `AppConfigTest` run and expose two
   stale assertions; rewrite
   `common/src/test/kotlin/ed/unicoach/common/config/AppConfigTest.kt` to test
   the `AppConfig.load` merge/substitution mechanism, and add the fixtures
   `common/src/test/resources/merge-base.conf`,
   `common/src/test/resources/merge-override.conf`, and
   `common/src/test/resources/env-substitution.conf`.
   - Verify: `nix develop -c bin/test :common:test`
3. **Add the configuration schema.** Add the
   `server.requestSize { maxSize = "8 KiB"; maxSize = ${?SERVER_MAX_REQUEST_SIZE}; routeOverrides { "/api/v1/auth/register" = "1 KiB" } }`
   block to `rest-server/src/main/resources/rest-server.conf` (HOCON size
   strings, parsed downstream via `Config.getBytes`).
   - Verify: `nix develop -c ./gradlew :rest-server:processResources`
4. **Create `DataSize` in `common`.** Add
   `common/src/main/kotlin/ed/unicoach/common/util/DataSize.kt` (the
   `@JvmInline value class` with private constructor,
   `init { require(bytes >= 0) }`, and the `DataSize.ofBytes` factory). Add the
   focused unit test
   `common/src/test/kotlin/ed/unicoach/common/util/DataSizeTest.kt`.
   - Verify: `nix develop -c ./gradlew :common:compileKotlin`
   - Verify:
     `nix develop -c bin/test :common:test --tests "ed.unicoach.common.util.DataSizeTest"`
5. **Create `RequestSizeConfig`.** Add
   `rest-server/src/main/kotlin/ed/unicoach/rest/config/RequestSizeConfig.kt`
   with `defaultMax: DataSize` / `routeOverrides: Map<String, DataSize>` and
   `from(Config): Result<RequestSizeConfig>`, parsing `maxSize` and each
   override via `Config.getBytes` and wrapping each resulting `Long` via
   `DataSize.ofBytes`, following the `SessionConfig` pattern.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`
6. **Create the body-limit plugin wiring (single install).** Add
   `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/RequestSizeLimit.kt`
   with `Application.configureRequestSizeLimit(RequestSizeConfig)` performing
   one application-scope `install(RequestBodyLimit)` whose
   `bodyLimit { call -> ... }` callback returns
   `config.routeOverrides[call.request.path()]?.bytes ?: config.defaultMax.bytes`.
   No route helper, no route-scoped install.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`
7. **Thread the config through and apply it.** In `Application.kt`: load
   `RequestSizeConfig.from(config).getOrThrow()` in `startServer`; keep the
   `appModule(database, sessionConfig, requestSizeConfig)` signature and call
   `configureRequestSizeLimit(requestSizeConfig)` within `appModule`. In
   `Routing.kt`: `configureRouting(authService, sessionConfig)` takes no
   `requestSizeConfig` parameter. In `AuthRoutes.kt`: `AuthRouteHandler` has no
   `requestSizeConfig` constructor parameter; `/register` is a plain
   `post("/register") { handleRegister() }` (remove the `route { ... }`
   restructure, the `applyRouteBodyLimit` call, and the now-unused imports of
   `RequestSizeConfig` and `applyRouteBodyLimit`).
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`
8. **Add the StatusPages handler.** In `StatusPages.kt`: add
   `exception<PayloadTooLargeException>` → `413`, importing
   `io.ktor.server.plugins.PayloadTooLargeException`. Do not modify the existing
   `exception<BadRequestException>` handler.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`
9. **Add the plugin-isolation tests.** Create
   `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/RequestSizeLimitTest.kt`
   using `testApplication` with both Test 1 (global-default probe) and Test 2
   (path-specific tightening: `/probe-override` 413, `/probe-default` not 413
   for the same ~2 KiB body).
   - Verify:
     `nix develop -c bin/test :rest-server:test --tests "ed.unicoach.rest.plugins.RequestSizeLimitTest"`
10. **Run the suite.** Confirm all `AuthRoutingTest`, `RequestSizeLimitTest`,
    and `DataSizeTest` assertions pass with no regressions.
    - Verify: `nix develop -c bin/test :common:test`
    - Verify:
      `nix develop -c bin/test :rest-server:test --tests "ed.unicoach.rest.AuthRoutingTest"`
    - Verify: `nix develop -c bin/test :rest-server:test`
11. **Lint.**
    - Verify:
      `nix develop -c ktlint "common/src/**/*.kt" "rest-server/src/**/*.kt"`

## Files Modified

- `gradle/libs.versions.toml` — add the `ktor-server-body-limit` library entry.
- `rest-server/build.gradle.kts` — add
  `implementation(libs.ktor.server.body.limit)`.
- `common/build.gradle.kts` — add `tasks.withType<Test> { useJUnitPlatform() }`
  so `common`'s JUnit 5 tests (including `DataSizeTest`) are discovered and run.
- `rest-server/src/main/resources/rest-server.conf` — add the
  `server.requestSize` section (HOCON size strings `maxSize`/overrides, parsed
  via `Config.getBytes`).
- `common/src/main/kotlin/ed/unicoach/common/util/DataSize.kt` — new `DataSize`
  value type (non-negative byte count).
- `common/src/test/kotlin/ed/unicoach/common/util/DataSizeTest.kt` — new focused
  unit test for `DataSize`.
- `common/src/test/kotlin/ed/unicoach/common/config/AppConfigTest.kt` — rewrite
  the two stale assertions (un-gated by `useJUnitPlatform()`) to test the
  `AppConfig.load` merge/substitution mechanism against self-contained fixtures.
- `common/src/test/resources/merge-base.conf`,
  `common/src/test/resources/merge-override.conf`,
  `common/src/test/resources/env-substitution.conf` — new test fixtures for the
  rewritten `AppConfigTest`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/config/RequestSizeConfig.kt` —
  new config value object carrying `DataSize` (`defaultMax`, `routeOverrides`).
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/RequestSizeLimit.kt` —
  new plugin wiring: single application-scope `install(RequestBodyLimit)` with a
  path-aware `bodyLimit` callback (no route helper).
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/StatusPages.kt` — add
  the `413`/`PayloadTooLargeException` handler.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — load and
  thread `RequestSizeConfig`; install the single application-scope limit.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt` — `configureRouting`
  drops the `RequestSizeConfig` parameter (routing no longer reads it).
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt` — remove
  the `requestSizeConfig` constructor parameter and the
  `applyRouteBodyLimit`/`route("/register")` restructure; revert to plain
  `post("/register")`; drop now-unused imports.
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/RequestSizeLimitTest.kt`
  — new plugin-isolation tests (global-default probe + path-specific
  tightening); construct `RequestSizeConfig` with `DataSize.ofBytes`.
