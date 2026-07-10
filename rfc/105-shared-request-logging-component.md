# RFC 105: Shared request-logging component

## Executive Summary

RFC 103's configurable, self-diagnosing request log lives only in rest-server
(`configureRequestLogging`, `RequestLoggingConfig`, the pure `formatLogLine` and
the route-scoped `secretQueryParams` opt-in). admin-web and public-web log no
request line at all — a failure on either is undiagnosable from the server log,
and the secret-header-redaction guarantee that protects rest-server's log does
not exist for them.

This RFC extracts the RFC-103 component, unchanged, into a new `web-common`
Gradle module (`ed.unicoach.web.common.logging`) — not `common`, to keep a web
framework off `common`'s 13 dependents — and wires
`configureRequestLogging(config)` into all three Ktor services, each building
its `RequestLoggingConfig` from the config it already loads. One implementation
replaces the current single-service copy; rest-server switches to consume it and
deletes its originals.

Behavior is preserved for rest-server: its logged lines, its `appModule` test
call-sites, and the three RFC-103 tests are unchanged except for package/import
moves. admin-web and public-web gain the identical log. The shared careful
defaults (header allowlist, `detail = failure`, base
`secretHeaders = Cookie,Authorization`) plus their optional `${?REQUEST_LOG_*}`
overrides live once in a new `web-common/src/main/resources/reference.conf`,
which typesafe-config auto-merges onto every web-common-dependent service (all
three) as the lowest-priority fallback — so an environment that omits the
overrides inherits careful, never verbose, and `.env.dev` alone widens all three
locally. This DERIVE replaces the three copied `.conf` blocks the design review
flagged as duplication, so the shared defaults cannot drift. rest-server's
`.conf` keeps only its one override (adding `X-Unicoach-Client-Key` to the
secret set); admin-web and public-web carry no `requestLogging` block. One
web-common test asserts the shared defaults resolve to careful; a thin
rest-server guard asserts its override adds the client-key header.

RFC 103's secret-header invariant relocates from rest-server's `plugins`
directory to the shared component's `INVARIANTS.md`; it now governs all three
services through the one component.

## Detailed Design

### New module: `web-common`

A new Gradle module holding the RFC-103 component and nothing else. Depended on
by rest-server, admin-web, and public-web; depends on ktor-server-core (not on
`:common`).

The component is placed in a new module rather than the existing framework-
agnostic `common` module: `common` is depended on by 13 modules, including
background/library ones (db, auth, chat, cron, queue-worker), and must not gain
a web framework on its classpath. `web-common` depends on ktor-server-core and
is depended on only by the three web apps.

`settings.gradle.kts` gains `include("web-common")`.

`web-common/build.gradle.kts`:

```kotlin
plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-library`
}

dependencies {
  api(libs.typesafe.config)              // Config in RequestLoggingConfig.from()'s signature
  api(libs.ktor.server.core)             // Application/Route receivers in public signatures
  implementation(libs.ktor.server.call.logging)  // CallLogging, referenced only in-body

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.logback.classic)
  testImplementation(libs.ktor.server.content.negotiation)
  testImplementation(libs.ktor.serialization.jackson)
}

tasks.withType<Test> {
  useJUnitPlatform()
}
```

`typesafe-config` and `ktor-server-core` are `api` because `Config`,
`Application`, and `Route` appear in the component's public signatures.
`ktor-server-call-logging` is `implementation` because `CallLogging` is named
only inside `configureRequestLogging`'s body; consumers reach the jar
transitively at runtime without declaring it. No dependency on `:common` is
declared: the component uses only `Config` and Ktor types, so pulling `:common`
(and its argon2/jwt/coroutines transitive graph) would be gratuitous.

### The moved component

Two files move from rest-server into
`web-common/src/main/kotlin/ed/unicoach/web/common/logging/`, package
`ed.unicoach.web.common.logging`. The Kotlin is byte-identical to the RFC-103
source; only the `package` line changes. rest-server's originals are deleted.

- `RequestLoggingConfig.kt` — `HeaderSelection` (sealed: `All` /
  `Allowlist(names)`), `Detail` (enum: `FAILURE` / `ALWAYS`), the
  `RequestLoggingConfig(secretHeaders, headers, detail)` data class, and
  `from(config: Config): Result<RequestLoggingConfig>`. Moves from
  `rest-server/src/main/kotlin/ed/unicoach/rest/config/`.
- `RequestLogging.kt` — `configureRequestLogging(config, nanoTime)` (the
  `StartNanosKey` Setup interceptor + `CallLogging` install), the pure
  `internal formatLogLine` / `resolveBodySize` / header-selection /
  `redactQuery` helpers, and the query-secret opt-in: `SecretQueryParamsKey`,
  `SecretQueryParamsConfig`, the route-scoped `SecretQueryParamsPlugin`, and
  `Route.secretQueryParams(vararg names)`. Moves from
  `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/`.

`formatLogLine` stays `internal`: it is the module-private diagnostic core, and
its guarantee tests live in the same module (below). The public API surface is
exactly `RequestLoggingConfig` (+ `HeaderSelection`, `Detail`),
`configureRequestLogging`, `SecretQueryParamsPlugin`, and
`Route.secretQueryParams` — the surface the three services and the tests use.

### Per-service wiring

Each service builds its own config and installs the plugin from its own module,
identically to rest-server today. The install is the first line of the module so
its Setup-phase interceptor wraps the whole pipeline.

- rest-server: unchanged. `appModule` keeps
  `configureRequestLogging(requestLoggingConfig)`; the `requestLoggingConfig`
  parameter and its `RequestLoggingConfig.from(config).getOrThrow()`
  construction in `startServer` are untouched. Only the `import`s move to
  `ed.unicoach.web.common.logging`.
- admin-web: `adminModule` gains a `requestLoggingConfig: RequestLoggingConfig`
  parameter and calls `configureRequestLogging(requestLoggingConfig)` as its
  first line (before `configureAdminStatusPages`). `startServer` builds it via
  `RequestLoggingConfig.from(config).getOrThrow()` from the already-loaded
  config and passes it into `adminModule`.
- public-web: `publicWebModule` gains a
  `requestLoggingConfig: RequestLoggingConfig` parameter and calls
  `configureRequestLogging(requestLoggingConfig)` as its first line (before
  `installPublicWebRouting`). `startServer` builds it the same way.

`adminModule` and `publicWebModule` take no `config: Config` parameter today, so
only the built `RequestLoggingConfig` is threaded into them (not the raw
`config`); `startServer` does the `RequestLoggingConfig.from(config)` resolution
against its already-loaded config. The parameter is threaded through the module
functions (rather than installed in the enclosing `startServer` boot lambda) so
all three services place the install uniformly inside their module function,
matching rest-server's existing shape.

### Shared configuration via `reference.conf`

The shared careful defaults live once in a new
`web-common/src/main/resources/reference.conf`, not copied into each service's
`.conf`. typesafe-config merges every classpath `reference.conf` as the
lowest-priority fallback, so all three services inherit these defaults through
their existing `AppConfig.load` path with no per-service block and no change to
any `load(...)` call. This is the DERIVE fix for the careful-default duplication
the design review flagged (no-remote-breakage FAIL, generalization Major): one
source of truth means the three services' shared defaults cannot silently drift.

`web-common/src/main/resources/reference.conf`:

```hocon
requestLogging {
  # Shared careful defaults for all three web services. Each key pairs a
  # careful literal with its optional REQUEST_LOG_* override, so an env that
  # sets no override inherits careful, and an env that sets one widens all
  # three uniformly.
  secretHeaders = "Cookie,Authorization"
  secretHeaders = ${?REQUEST_LOG_SECRET_HEADERS}
  headers = "Accept,Content-Type,User-Agent,Expect,Content-Length"
  headers = ${?REQUEST_LOG_HEADERS}
  detail = "failure"
  detail = ${?REQUEST_LOG_DETAIL}
}
```

`reference.conf` carries **only optional** substitutions (no required `${VAR}`).
This is load-bearing: `ConfigFactory.load` resolves the merged `reference.conf`
(`defaultReference()`) standalone against the environment before the app merge,
and a required unresolved sub there would throw in **every** service that
depends on web-common; the optional-only form keeps that resolution total.

**How the merge reaches the services.** `AppConfig.load` ends with
`ConfigFactory.load(overlay.withFallback(mergedConfig))`;
`ConfigFactory.load(Config)` appends `defaultReference()` — every
`reference.conf` on the classpath, resolved against the JVM environment — as the
lowest-priority layer. web-common is a compile dependency of all three services
(they call `configureRequestLogging`), so its `reference.conf` is always on each
service's runtime classpath and always merges. Services compose their other
config through `AppConfig.load`'s vararg list, not HOCON `include`s, so nothing
needs to name `reference.conf`. It reaches **only** the three: `cron` and
`queue-worker` do not depend on web-common, so their `AppConfig.load` sees no
such `reference.conf`.

**Per-service override — rest-server only.** rest-server's log must additionally
redact its mobile client-key header, so `rest-server.conf` keeps exactly its
`secretHeaders` override and drops the `headers`/`detail` lines (now inherited):

```hocon
requestLogging {
  # Override only: adds X-Unicoach-Client-Key to reference.conf's base secret
  # set. The second line is REQUIRED for env parity — see precedence below.
  secretHeaders = "Cookie,Authorization,X-Unicoach-Client-Key"
  secretHeaders = ${?REQUEST_LOG_SECRET_HEADERS}
}
```

`admin-web.conf` and `public-web.conf` carry **no** `requestLogging` block; they
inherit `reference.conf` verbatim.

**Precedence.** A service `.conf` assignment for a `requestLogging` key wins
over `reference.conf` for that key (the service `.conf` sits above
`defaultReference()` in the merge); a key the service omits falls through to
`reference.conf`. The replacement is **whole-value per key**: rest-server's
scalar `secretHeaders` shadows `reference.conf`'s `secretHeaders` entirely —
including the `${?REQUEST_LOG_SECRET_HEADERS}` override that lives there. That
is why `rest-server.conf` **restates** the
`secretHeaders = ${?REQUEST_LOG_SECRET_HEADERS}` line: without it, setting
`REQUEST_LOG_SECRET_HEADERS` would widen admin-web and public-web (via
`reference.conf`) but silently skip rest-server, breaking the "env widens all
three uniformly" property. rest-server therefore resolves to its three-header
secret set (env-overridable) plus the inherited `headers`/`detail`; admin-web
and public-web resolve to the full careful defaults.

The `REQUEST_LOG_*` keys are process-global environment variables read
identically by `reference.conf` (and rest-server's override) in every service,
so the existing dotenv layering reaches all three unchanged: `bin/common`
sources `.env -> .env.dev` for local runs (`REQUEST_LOG_HEADERS=*`,
`REQUEST_LOG_DETAIL=always` widen all three to verbose locally), and the
deploy/infra path never layers `.env.dev` (`bin/functions`), so no cloud env
sees the verbose values. `.env.dev` is unchanged. `.env.template`'s comment is
generalized from "rest-server.conf" to "web-common's `reference.conf` (shared by
all three web services)".

The defaults live in web-common's `reference.conf` rather than the shared
`common.conf` because `common.conf` is also loaded by the background modules
(`cron`, `queue-worker`); keeping `requestLogging` in web-common confines it to
the three web services, mirroring the module-placement choice made for the code.

### Error handling / edge cases

- No double-logging to reconcile: neither admin-web nor public-web installs Ktor
  `CallLogging` or any request logger today (verified repo-wide; `CallLogging`
  appears only in the moved component). Each service simply gains the log.
- Missing `requestLogging` section or invalid `detail`: `from` returns
  `Result.failure`; each service's `startServer` calls `.getOrThrow()`, failing
  the boot — the RFC-103 behavior, now on three boots. `reference.conf`
  guarantees the section is present for every web service, so a missing section
  can arise only if web-common is dropped as a dependency — which fails
  compilation first (`configureRequestLogging` unresolved), before any boot.
- Log-forgery safety carries: all three services' `logback.xml` use the logstash
  `LogstashEncoder`, which JSON-escapes newlines, so an attacker- supplied
  header or query value cannot forge a log line. The implementation MUST
  preserve the logstash encoder in each service's `logback.xml`; the RFC changes
  none of them.
- Client IP / `X-Forwarded-For` remains out of scope, carried forward from RFC
  103's non-goals. A service that wants the client IP can already add
  `X-Forwarded-For` to its `headers` allowlist and it logs as a plain header;
  parsing it into a first-class field (through the ALB's forwarded chain) is a
  separate future concern for all services at once.
- No `secretQueryParams` opt-ins are needed by admin-web or public-web today
  (their routes carry no secret query params); the mechanism comes along with
  the component, exercised only by web-common's routing test.

### Dependencies

One new Gradle module (`web-common`) and one new inter-module edge from each of
the three apps to it. No new external libraries: ktor-server-core,
ktor-server-call-logging, typesafe-config, ktor-server-test-host,
ktor-server-content-negotiation, ktor-serialization-jackson, and logback-classic
are all already in the version catalog and on the relevant classpaths.
rest-server's explicit `ktor-server-call-logging` dependency is removed (reached
transitively via `web-common`). web-common additionally ships
`src/main/resources/reference.conf`; being a classpath resource it needs no
build change and reaches each service over the existing
`implementation(project(":web-common"))` edge.

## Tests

The RFC-103 tests move into web-common's test source set unchanged except for
package and one substitution; the triplicated careful-default guard collapses to
one shared web-common test plus one thin rest-server override guard. No new
behavior is introduced, so the moved tests are the regression proof that the
extraction preserved the component.

### Moved to `web-common/src/test/kotlin/ed/unicoach/web/common/logging/`

- `RequestLoggingConfigTest` — the parser cases (lowercased secret set,
  whitespace trimming, `"*"` -> `All`, allowlist parse, `detail` mapping,
  `"bogus"` -> failure, missing section -> failure). Moves from
  `rest-server/.../config/`; only the package line changes.
- `RequestLoggingFormatTest` — the pure-formatter guarantees (careful success is
  bare; careful failure enriches with `Accept`/`Content-Type`/`(absent)`;
  always- mode and no-response enrich; secret subtraction under `All` and
  case-insensitive and allowlisted-secret; `body=`/`respBody=` rendering;
  latency from injected nanos; `Content-Length` as both header and `body=`;
  query redaction). Must move to the same module because it calls
  `internal formatLogLine`. Only the package line changes.
- `RequestLoggingRoutingTest` — the integration test (pre-handler `406` on an
  opted-in route redacts `token` and reports the real status; a `200` on a non-
  opted-in route logs the raw query). Moves with one change: rest-server's
  `configureSerialization()` is replaced by an inline
  `install(ContentNegotiation) { jackson() }`, since `configureSerialization`
  stays in rest-server. The `406`-via-`Accept` mechanism and every assertion are
  otherwise unchanged.

### Careful-default guards (one shared + one thin override)

The DERIVE collapses the triplicated guard to two. One web-common test asserts
the shared `reference.conf` defaults are careful; one thin rest-server guard
asserts its override. **admin-web and public-web add no guard** — they carry no
`requestLogging` block, so the web-common test already covers every value they
resolve. That the auto-merge actually reaches a service is exercised by
admin-web's harness `AdminTestSupport`, which loads config via `AppConfig.load`
and builds `RequestLoggingConfig.from(config).getOrThrow()`: every admin-web
module test fails loudly if `reference.conf` is absent. public-web's route tests
pass a literal `TEST_REQUEST_LOG_CONFIG` and never load config, so they do not
exercise the merge; public-web relies on the identical `AppConfig.load` path
(proven by admin-web) plus its compile-time `web-common` dependency, which keeps
`reference.conf` on the classpath. A dedicated public-web runtime guard would
add a third per-service test for no coverage the web-common and admin-web tests
do not already give.

Both guards resolve **offline** — JVM environment excluded via
`offlineOptions = ConfigResolveOptions.defaults().setUseSystemEnvironment(false)`
— because `bin/test` exports `ENV_FILES=".env.dev:.env.test"` and `.env.dev`
sets `REQUEST_LOG_HEADERS=*` / `REQUEST_LOG_DETAIL=always`; an env-aware resolve
would read those widened values and the guard would see `All`/`ALWAYS`. The
guards assert the shipped literal defaults, so they exclude the environment.

The `parseString(...)` dummy feed is mandatory for any guard whose `.conf`
fallback chain carries **required** `${VAR}` subs (no `.conf` default): an
offline `.resolve()` (`allowUnresolved = false`) throws
`ConfigException.UnresolvedSubstitution` on the first such sub, **before**
`RequestLoggingConfig.from` runs. The precedent is `AdminConfigTest`, whose
`ADMIN_COOKIE_SECURE resolves to the set value` prepends a `parseString(...)`
feed and whose sibling
`required ADMIN_COOKIE_SECURE fails to resolve when unset` is the counter-proof
that the bare no-feed resolve throws exactly that exception. The fed dummy
values are irrelevant: `from` reads only the `requestLogging` subtree, which
carries none of them.

- **web-common — `RequestLoggingReferenceDefaultTest`** (new,
  `web-common/src/test/kotlin/ed/unicoach/web/common/logging/`). Parses **only**
  `reference.conf` and resolves offline —
  `ConfigFactory.parseResourcesAnySyntax("reference.conf").resolve(offlineOptions)`
  — then asserts `RequestLoggingConfig.from(config).getOrThrow()` yields
  `detail == Detail.FAILURE`, `headers is HeaderSelection.Allowlist` holding
  `Accept,Content-Type,User-Agent,Expect,Content-Length`, and
  `secretHeaders == setOf("cookie", "authorization")`. **No `parseString`
  feed:** `reference.conf` has no required subs, and its optional
  `${?REQUEST_LOG_*}` subs vanish under the excluded environment, falling to the
  careful literals. This same bare resolve is the guard against a future edit
  adding a required sub to `reference.conf` (which would break every service's
  boot): it would throw `UnresolvedSubstitution` before reaching the assertions.
- **rest-server — `RequestLoggingCarefulDefaultTest`** (new,
  `rest-server/src/test/kotlin/ed/unicoach/rest/config/`). Asserts **only**
  rest-server's override:
  `secretHeaders == setOf("cookie", "authorization", "x-unicoach-client-key")`.
  Because `rest-server.conf` now carries `headers`/`detail` nowhere (they live
  in `reference.conf`), this guard **MUST add `reference.conf` to its offline
  fallback chain** — the offline path does not auto-merge it — or
  `RequestLoggingConfig.from` fails on the absent keys. It feeds the required
  subs (`SERVER_PORT`, `APP_DOMAIN`, `SESSION_COOKIE_SECURE`, `CHAT_PROVIDER`,
  `POSTGRES_PORT`, `POSTGRES_DB`, `PUBLIC_WEB_PORT`, `GOOGLE_AUTH_PROVIDER`) via
  `parseString`, then falls back over
  `rest-server.conf, service.conf, chat.conf, db.conf, common.conf, queue.conf,
  reference.conf`
  (`reference.conf` lowest, mirroring the runtime layering) and resolves
  offline. The resolved `x-unicoach-client-key` proves the restated override
  merged; `from` succeeding proves the inherited `headers`/`detail` resolved.
  This restores a rest-server-local request-log test after the parser test moves
  to web-common.

### Updated call-sites (compile-only, no assertion change)

- rest-server: `ConvoStreamErrorRoutingTest`, `ConvoExtractionEnqueueTest`,
  `ConvoToolLoopRoutingTest`, `EmailVerificationGateTest` — the
  `import ed.unicoach.rest.config.RequestLoggingConfig` becomes
  `ed.unicoach.web.common.logging.RequestLoggingConfig`.
- admin-web: `AdminTestSupport.installTestAdminModule` builds
  `RequestLoggingConfig.from(config).getOrThrow()` (it already has the loaded
  `config` in scope) and passes it to `adminModule`.
- public-web: a shared `val TEST_REQUEST_LOG_CONFIG` (a careful
  `RequestLoggingConfig`) is added next to `TEST_OPEN_IN_APP_URL` in
  `FakeEmailVerifier.kt`; the 18 `publicWebModule(...)` call-sites across
  `HomePageTest`, `StaticAssetsTest`, `HealthTest`, `VerifyEmailRoutingTest`,
  `ErrorPagesTest`, and `LegalPagesTest` pass it as the new argument.

## Invariants

### Secret request-header values are never logged, in any environment

**Rule:** The request log MUST NOT emit the value of any header whose name
matches an entry in `requestLogging.secretHeaders`, in any environment or
verbosity mode (including `headers="*"`); the secret set is subtracted last,
after header selection, by case-insensitive name match.

**Why:** Secret headers carry session and credential material — `Cookie` and
`Authorization` for every service, plus any service-specific credential header
(e.g. rest-server's `X-Unicoach-Client-Key`); logging them writes durable
credentials into the log store. Dev's `headers="*"` selects every header sent,
so this subtraction is the only barrier — applied before the wildcard expansion,
or matched case-sensitively (so a lowercase `authorization` slips through), it
would leak. The guarantee now protects all three services through the one shared
component.

**Target directory:**
`web-common/src/main/kotlin/ed/unicoach/web/common/logging/`

This invariant is not new; it relocates from
`rest-server/src/main/kotlin/ed/unicoach/rest/plugins/INVARIANTS.md` (where RFC
103 placed it) because the code it governs moves. The implementation removes it
from the rest-server `plugins` `INVARIANTS.md` and adds it to the new
directory's `INVARIANTS.md`.

## Implementation Plan

1. **Create the `web-common` module.** Add `include("web-common")` to
   `settings.gradle.kts`; create `web-common/build.gradle.kts` with the deps
   above. Create the package directory
   `web-common/src/main/kotlin/ed/unicoach/web/common/logging/`.
   - Verify: `nix develop -c ./gradlew :web-common:tasks` resolves the module.

2. **Move the component.** Move `RequestLoggingConfig.kt` and
   `RequestLogging.kt` into `web-common/.../logging/`, changing only the
   `package` line to `ed.unicoach.web.common.logging`. Delete the rest-server
   originals (`rest-server/.../config/RequestLoggingConfig.kt`,
   `rest-server/.../plugins/RequestLogging.kt`).
   - Verify: `nix develop -c ./gradlew :web-common:compileKotlin`.

3. **Add the shared `reference.conf` and its guard.** Create
   `web-common/src/main/resources/reference.conf` with the `requestLogging {}`
   block (careful literals + optional `${?REQUEST_LOG_*}` overrides; optional
   subs only). Add
   `web-common/src/test/kotlin/ed/unicoach/web/common/logging/RequestLoggingReferenceDefaultTest.kt`
   asserting the offline-resolved defaults are careful (`detail = FAILURE`,
   allowlist headers, `secretHeaders = {cookie, authorization}`).
   - Verify: `nix develop -c ./gradlew :web-common:processResources`.

4. **Move the component tests.** Move `RequestLoggingConfigTest`,
   `RequestLoggingFormatTest`, and `RequestLoggingRoutingTest` into
   `web-common/src/test/kotlin/ed/unicoach/web/common/logging/`, changing the
   `package` line; in `RequestLoggingRoutingTest` replace
   `configureSerialization()` with `install(ContentNegotiation) { jackson() }`
   (add the imports). Delete the rest-server test originals.
   - Verify: `nix develop -c bin/test web-common -f` (confirm `N executed`, all
     pass — including the new `RequestLoggingReferenceDefaultTest`).

5. **Consume from rest-server.** Add `implementation(project(":web-common"))`
   and remove `implementation(libs.ktor.server.call.logging)` from
   `rest-server/build.gradle.kts`. Update the imports in
   `rest-server/.../rest/Application.kt` and the four test call-sites
   (`ConvoStreamErrorRoutingTest`, `ConvoExtractionEnqueueTest`,
   `ConvoToolLoopRoutingTest`, `EmailVerificationGateTest`) to
   `ed.unicoach.web.common.logging`. In
   `rest-server/src/main/resources/rest-server.conf`, reduce the
   `requestLogging {}` block to only the two `secretHeaders` lines (the literal
   plus the `${?REQUEST_LOG_SECRET_HEADERS}` override); delete its `headers` and
   `detail` lines (now inherited from `reference.conf`). Add the thin guard
   `RequestLoggingCarefulDefaultTest` (asserts `x-unicoach-client-key` in the
   secret set; its offline fallback chain includes `reference.conf`).
   - Verify: `nix develop -c bin/test rest-server -f` (confirm `N executed`).

6. **Wire admin-web.** Add `implementation(project(":web-common"))` to
   `admin-web/build.gradle.kts`. In `admin-web/.../admin/Application.kt`: build
   `RequestLoggingConfig.from(config).getOrThrow()` in `startServer`, add the
   `requestLoggingConfig` parameter to `adminModule`, call
   `configureRequestLogging(requestLoggingConfig)` first. Ensure
   `admin-web/src/main/resources/admin-web.conf` carries **no** `requestLogging`
   block (it inherits `reference.conf`; remove the block if a prior pipeline
   iteration added one). Update `AdminTestSupport.installTestAdminModule` to
   build and pass the config. No careful-default guard — the shared defaults are
   covered by the web-common test.
   - Verify: `nix develop -c bin/test admin-web -f` (confirm `N executed`).

7. **Wire public-web.** Add `implementation(project(":web-common"))` to
   `public-web/build.gradle.kts`. In `public-web/.../web/Application.kt`: build
   the config in `startServer`, add the `requestLoggingConfig` parameter to
   `publicWebModule`, call `configureRequestLogging(requestLoggingConfig)`
   first. Ensure `public-web/src/main/resources/public-web.conf` carries **no**
   `requestLogging` block (remove it if a prior pipeline iteration added one).
   Add `val TEST_REQUEST_LOG_CONFIG` to `FakeEmailVerifier.kt` and pass it at
   the 18 `publicWebModule(...)` call-sites. No careful-default guard.
   - Verify: `nix develop -c bin/test public-web -f` (confirm `N executed`).

8. **Relocate the invariant.** Remove the "Secret request-header values are
   never logged" section and the RFC-103 History line from
   `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/INVARIANTS.md`. Create
   `web-common/src/main/kotlin/ed/unicoach/web/common/logging/INVARIANTS.md`
   with the invariant (Rule + Why, generalized) and an RFC-105 History entry.
   - Verify: `nix develop -c bin/format -c`.

9. **Generalize the env comment.** Update the `REQUEST_LOG_*` comment in
   `.env.template` from "rest-server.conf" to "web-common's `reference.conf`
   (shared by all three web services)".
   - Verify: `nix develop -c bin/format -c`.

10. **Full gate.** Run format and the whole suite.
    - Verify: `nix develop -c bin/format -c` and `nix develop -c bin/test -f`
      (confirm `N executed` across modules).

## Files Modified

New:

- `web-common/build.gradle.kts` — new module build.
- `web-common/src/main/kotlin/ed/unicoach/web/common/logging/RequestLoggingConfig.kt`
  — moved from rest-server (package change only).
- `web-common/src/main/kotlin/ed/unicoach/web/common/logging/RequestLogging.kt`
  — moved from rest-server (package change only).
- `web-common/src/main/resources/reference.conf` — new; the shared
  `requestLogging` careful defaults auto-merged onto all three services.
- `web-common/src/main/kotlin/ed/unicoach/web/common/logging/INVARIANTS.md` —
  new; carries the relocated secret-header invariant.
- `web-common/src/test/kotlin/ed/unicoach/web/common/logging/RequestLoggingReferenceDefaultTest.kt`
  — new; asserts `reference.conf`'s shared defaults resolve to careful.
- `web-common/src/test/kotlin/ed/unicoach/web/common/logging/RequestLoggingConfigTest.kt`
  — moved (package change only).
- `web-common/src/test/kotlin/ed/unicoach/web/common/logging/RequestLoggingFormatTest.kt`
  — moved (package change only).
- `web-common/src/test/kotlin/ed/unicoach/web/common/logging/RequestLoggingRoutingTest.kt`
  — moved; inline `ContentNegotiation`/`jackson` replaces
  `configureSerialization()`.
- `rest-server/src/test/kotlin/ed/unicoach/rest/config/RequestLoggingCarefulDefaultTest.kt`
  — new; thin rest-server override guard (asserts `x-unicoach-client-key`;
  offline chain includes `reference.conf`).

Modified:

- `settings.gradle.kts` — add `include("web-common")`.
- `rest-server/build.gradle.kts` — add `:web-common`, remove
  `ktor-server-call-logging`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — imports move
  to `ed.unicoach.web.common.logging`.
- `rest-server/src/main/resources/rest-server.conf` — reduce the
  `requestLogging` block to the two `secretHeaders` lines; delete `headers` and
  `detail` (now inherited from `reference.conf`).
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoStreamErrorRoutingTest.kt`
  — import move.
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoExtractionEnqueueTest.kt` —
  import move.
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoToolLoopRoutingTest.kt` —
  import move.
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/EmailVerificationGateTest.kt`
  — import move.
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/INVARIANTS.md` — remove
  the secret-header invariant section and the RFC-103 History line.
- `admin-web/build.gradle.kts` — add `:web-common`.
- `admin-web/src/main/kotlin/ed/unicoach/admin/Application.kt` — build config,
  add `adminModule` parameter, install the plugin first.
- `admin-web/src/main/resources/admin-web.conf` — carries **no**
  `requestLogging` block (inherits `reference.conf`); net-unchanged vs `main`,
  listed to remove any block a prior pipeline iteration added.
- `admin-web/src/test/kotlin/ed/unicoach/admin/AdminTestSupport.kt` — build and
  pass the config into `adminModule`.
- `admin-web/src/test/kotlin/ed/unicoach/admin/AdminConfigTest.kt` —
  net-unchanged vs `main`, listed to remove any careful-default guard a prior
  pipeline iteration added (now covered by the web-common test).
- `public-web/build.gradle.kts` — add `:web-common`.
- `public-web/src/main/kotlin/ed/unicoach/web/Application.kt` — build config,
  add `publicWebModule` parameter, install the plugin first.
- `public-web/src/main/resources/public-web.conf` — carries **no**
  `requestLogging` block (inherits `reference.conf`); net-unchanged vs `main`,
  listed to remove any block a prior pipeline iteration added.
- `public-web/src/test/kotlin/ed/unicoach/web/FakeEmailVerifier.kt` — add
  `TEST_REQUEST_LOG_CONFIG`.
- `public-web/src/test/kotlin/ed/unicoach/web/HomePageTest.kt` — pass the config
  at each `publicWebModule` call.
- `public-web/src/test/kotlin/ed/unicoach/web/StaticAssetsTest.kt` — same.
- `public-web/src/test/kotlin/ed/unicoach/web/HealthTest.kt` — same.
- `public-web/src/test/kotlin/ed/unicoach/web/VerifyEmailRoutingTest.kt` — same.
- `public-web/src/test/kotlin/ed/unicoach/web/ErrorPagesTest.kt` — same.
- `public-web/src/test/kotlin/ed/unicoach/web/LegalPagesTest.kt` — same.
- `public-web/src/test/kotlin/ed/unicoach/web/PublicWebConfigTest.kt` —
  net-unchanged vs `main`, listed to remove any careful-default guard a prior
  pipeline iteration added (now covered by the web-common test).
- `.env.template` — generalize the `REQUEST_LOG_*` comment.
