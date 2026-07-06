# RFC 103: Configurable, self-diagnosing request log

## Executive Summary

rest-server logs one line per HTTP request — `METHOD uri -> status`
(`configureRequestLogging`, Ktor `CallLogging`). A failed response carries no
request context, so failures cannot be diagnosed from the server log alone. A
`406` is caused entirely by the request's `Accept` header, which is not logged;
a content-type mismatch is worse, because `StatusPages` rewrites Ktor's `415`
into a `400` before it is sent, discarding the offending `Content-Type`.

This RFC makes the line self-diagnosing without per-status special-casing. On
any enriched line the formatter attaches a fixed, configurable set of request
headers (`Header=[value]`, or `Header=[(absent)]` when unsent), request and
response body sizes, and a monotonic processing-time duration. Enrichment is
status-agnostic: the same context is attached to every failure `>= 400`, so the
`406`/`Accept` and `415`-rewritten-to-`400`/`Content-Type` cases both fall out
for free.

Verbosity is chosen entirely by configuration, never an `if (env == PROD)`
branch. A new `requestLogging {}` block in `rest-server.conf` holds careful
defaults (header allowlist, enrich-on-failure only) parsed by
`RequestLoggingConfig`. `.env` sets none of the overrides, so every cloud env
inherits the careful defaults; `.env.dev` alone widens to every header on every
request. This is the existing dotenv-layering precedent
(`SESSION_COOKIE_SECURE`), and `bin/functions` never layers `.env.dev` onto a
deploy.

A durable guarantee — `secretHeaders` values (`Cookie`, `Authorization`,
`X-Unicoach-Client-Key`) are never logged in any environment, including dev's
`headers="*"` mode — is captured as an invariant for the `plugins` directory.

## Detailed Design

### Configuration block

A new `requestLogging {}` section in
`rest-server/src/main/resources/rest-server.conf`. Its literal values are the
careful defaults; each is overridable by an optional environment substitution.
`.env` sets none of the `REQUEST_LOG_*` keys, so a cloud env that omits them
inherits the careful defaults — safe by default, not verbose.

```hocon
requestLogging {
    # Header values NEVER logged, in any environment. Subtracted even under
    # headers="*", so widening verbosity cannot leak a secret.
    secretHeaders = "Cookie,Authorization,X-Unicoach-Client-Key"
    secretHeaders = ${?REQUEST_LOG_SECRET_HEADERS}
    # Request headers to attach on an enriched line: comma-separated allowlist,
    # or "*" for every header sent except secretHeaders.
    headers = "Accept,Content-Type,User-Agent,Expect,Content-Length"
    headers = ${?REQUEST_LOG_HEADERS}
    # When to attach header + body-size + latency context: "failure" (>=400,
    # or no response) or "always".
    detail = "failure"
    detail = ${?REQUEST_LOG_DETAIL}
}
```

These use HOCON literal defaults plus optional `${?VAR}` overrides, not the
required-substitution form (`${VAR}`, no default) used by
`SESSION_COOKIE_SECURE`. The forms differ deliberately: a forgotten
cookie-secure override must crash the boot because the safe state is not the
default; here the safe state (careful) _is_ the default, so a forgotten override
degrades to careful, never to a leak.

`.env.dev` adds the two verbose overrides:

```sh
REQUEST_LOG_HEADERS=*
REQUEST_LOG_DETAIL=always
```

`bin/common` sources `.env -> .env.dev` for local runs (`set -a`, so both keys
are exported into the JVM env for HOCON `${?...}`); the deploy/infra path pins
`.env.<env>` and never layers `.env.dev` (`bin/functions`), so no cloud env sees
the verbose values unless its own `.env.<env>` sets them.

### `RequestLoggingConfig`

A new
`rest-server/src/main/kotlin/ed/unicoach/rest/config/RequestLoggingConfig.kt`,
mirroring `ClientKeyGateConfig`. It parses the block once into typed values so
no sentinel string (`"*"`) or free-form status word survives to a use-site.

```kotlin
package ed.unicoach.rest.config

import com.typesafe.config.Config

sealed interface HeaderSelection {
  object All : HeaderSelection
  data class Allowlist(val names: Set<String>) : HeaderSelection
}

enum class Detail { FAILURE, ALWAYS }

data class RequestLoggingConfig(
  val secretHeaders: Set<String>,
  val headers: HeaderSelection,
  val detail: Detail,
) {
  companion object {
    fun from(config: Config): Result<RequestLoggingConfig>
  }
}
```

`from` behaviour:

- Missing `requestLogging` section -> `Result.failure` (matches
  `ClientKeyGateConfig`).
- `secretHeaders`: comma-split, trim, drop empties, lowercased into a set.
  Stored lowercase because header-name matching is case-insensitive (below).
- `headers`: trimmed; `"*"` -> `HeaderSelection.All`; otherwise
  `HeaderSelection.Allowlist` of the comma-split, trimmed, non-empty names
  (original case retained for display).
- `detail`: `"failure"` -> `Detail.FAILURE`, `"always"` -> `Detail.ALWAYS`; any
  other value -> `Result.failure`. A typo'd `REQUEST_LOG_DETAIL` fails the boot
  loudly rather than silently mis-gating.

### Line formatter

A pure function is the whole of the diagnostic logic, so the guarantees are
unit-testable without a running server:

```kotlin
internal fun RequestLoggingConfig.formatLogLine(
  method: String,
  uri: String,
  status: Int?,
  requestHeaders: Headers,
  responseHeaders: Headers,
  latencyMillis: Long,
  secretQueryParams: Set<String>,
): String
```

Behaviour:

1. `safeUri` = `uri` with each secret query param value redacted (below).
2. `head` = `"$method $safeUri -> ${status?.toString() ?: "no-response"}"`.
3. `enrich` = `detail == Detail.ALWAYS || status == null || status >= 400`. If
   false, return `head` unchanged — a success under `detail=failure` stays one
   clean line.
4. Otherwise append, space-joined after `head`, in this order:
   - `body=[${bodySize(requestHeaders)}]`
   - one `Name=[value]` per selected request header (below)
   - `respBody=[${bodySize(responseHeaders)}]`
   - `latency=[${latencyMillis}ms]`

`bodySize(headers)` is shared by request and response: `Content-Length` present
-> `"${n}b"`; else `Transfer-Encoding: chunked` present -> `"chunked"`; else
`"(none)"`.

Header selection, with secret subtraction applied **last** in **both** modes:

- `HeaderSelection.All`: every name in `requestHeaders.names()`, rendered
  `Name=[values-joined-by-comma]`, dropping any name whose lowercase form is in
  `secretHeaders`.
- `HeaderSelection.Allowlist(names)`: each configured name, rendered
  `Name=[value]` or `Name=[(absent)]` when unsent, dropping any whose lowercase
  form is in `secretHeaders` (so a secret listed in the allowlist is still never
  emitted).

Query redaction: if `secretQueryParams` is empty or `uri` has no `?`, `safeUri`
== `uri`. Otherwise split `uri` once on `?` into path and query, split the raw
query on `&`, and for each `name=value` segment whose `name` (the substring
before the first `=`) is in `secretQueryParams`, rewrite the segment to
`name=(redacted)`; all other segments are preserved verbatim, order intact. The
raw query substring is edited in place (no decode/re-encode round-trip), so no
other character is altered.

Example lines:

```
# careful (headers=allowlist, detail=failure)
GET  /api/v1/conversations              -> 200
POST /api/v1/conversations/stream       -> 406 body=[16b] Accept=[text/event-stream] Content-Type=[application/json] User-Agent=[curl/8.1] Expect=[(absent)] Content-Length=[16] respBody=[chunked] latency=[3ms]
POST /api/v1/auth/reset?token=(redacted) -> 400 body=[24b] Accept=[application/json] Content-Type=[application/json] User-Agent=[curl/8.1] Expect=[(absent)] Content-Length=[24] respBody=[41b] latency=[8ms]
# verbose/dev (headers=*, detail=always)
GET  /api/v1/conversations?status=archived -> 200 body=[(none)] Accept=[*/*] User-Agent=[curl/8.1] Host=[api.uni.coach] respBody=[128b] latency=[1ms]
```

`Content-Length` intentionally appears both as an allowlist header and as the
`body=` field: `body=` is the interpreted size (`(none)`/`chunked`), the header
shows the raw declared value.

### Plugin wiring and latency measurement

`configureRequestLogging` gains the config and an injected monotonic time
source:

```kotlin
fun Application.configureRequestLogging(
  config: RequestLoggingConfig,
  nanoTime: () -> Long = System::nanoTime,
)
```

It installs two things, still first in `appModule` so it wraps the whole
pipeline:

- A `ApplicationCallPipeline.Setup` interceptor that stamps
  `call.attributes[StartNanosKey] = nanoTime()` on every call. Setup runs before
  content negotiation and any handler, so a call that later `406`s or `415`s is
  still stamped.
- `CallLogging`, whose `format { call -> ... }` runs on call completion (after
  the final status, including a `415`-rewritten-to-`400`). It reads
  `StartNanosKey`, samples `nanoTime()` again, computes
  `latencyMillis = (nanoTime() - start) / 1_000_000`, reads
  `call.attributes.getOrNull(SecretQueryParamsKey) ?: emptySet()`, and delegates
  to `config.formatLogLine(...)`.

Latency is a monotonic duration by construction. Ktor's own
`call.processingTimeMillis` is not used: it computes `clock() - CallStartTime`
where `CallStartTime` is stamped by Ktor with wall-clock `getTimeMillis()`, so
its duration is subject to clock jumps and its clock parameter cannot be made
consistent with a monotonic source. Owning the start stamp avoids that.

```kotlin
val StartNanosKey = AttributeKey<Long>("RequestLogStartNanos")
```

### Per-route query-secret opt-in

Query redaction is opt-in per route and additive; a route that needs it declares
its secret params, others are untouched.

```kotlin
val SecretQueryParamsKey = AttributeKey<Set<String>>("RequestLogSecretQueryParams")

class SecretQueryParamsConfig { var names: Set<String> = emptySet() }

val SecretQueryParamsPlugin: RouteScopedPlugin<SecretQueryParamsConfig>

fun Route.secretQueryParams(vararg names: String)
```

`SecretQueryParamsPlugin` is a route-scoped plugin (`createRouteScopedPlugin`)
whose `CallSetup` hook puts its configured `names` under `SecretQueryParamsKey`.
`secretQueryParams(...)` installs it through the public
`Route.install(plugin){}` seam with `names = names.toSet()`. `CallSetup`
intercepts the `ApplicationCallPipeline.Setup` phase and the plugin is
route-scoped, so the attribute is stamped when the route matches — before the
handler — so a pre-handler failure (`406`, `415`) on that route still redacts.

The public `Route.install` seam is used rather than casting the receiver to
`ApplicationCallPipeline` to reach `intercept`. In Ktor 3.4.2 `Route` is an
interface that does not extend `ApplicationCallPipeline`; its sole
implementation `RoutingNode` does, so `(this as ApplicationCallPipeline)`
compiles but is an unchecked downcast a future Ktor `Route` implementation could
turn into a runtime `ClassCastException` with no compile-time signal.
`Route.install` and the `CallSetup` hook are public members of the
routing/plugin API and yield the identical Setup-phase stamp through a
type-checked seam (per `design-review-no-remote-breakage`: derive/enforce over
documenting a coupling Ktor owns). Usage:

```kotlin
route("/api/v1/auth/reset") {
  secretQueryParams("token")
  post { /* ... */ }
}
```

rest-server has no secret query params today (routes read only `version` and
`status`), so this ships as an unused-but-ready mechanism, exercised only by
tests. It is added now because the query string is logged in full via `uri` and
a future token-bearing route would otherwise leak silently.

### Error handling / edge cases

- Invalid `detail` value or missing `requestLogging` section: `from` returns
  `Result.failure`; `startServer` / `appModule` call `.getOrThrow()`, failing
  the boot — consistent with every other `*Config.from`.
- No response (`status == null`): treated as a failure, so it is enriched.
- Secret query param declared but never present on a request: no-op.
- Documented coupling (accepted risk, per `design-review-no-remote-breakage`):
  query redaction requires the route to opt in and applies only once a route is
  matched. A route that has a secret query param but forgets to call
  `secretQueryParams(...)` leaks it; a request rejected before routing resolves
  (the client-key gate's `403`, a `404` with no matched route) is never stamped
  and logs its raw query. Both are acceptable given zero secret query params
  exist today.

### Dependencies

None new. `Headers`, `AttributeKey`, `CallLogging`, and the route plugin API the
query opt-in uses (`createRouteScopedPlugin`, the `CallSetup` hook,
`Route.install`) are already on the rest-server classpath
(`io.ktor:ktor-server-call-logging` / `ktor-server-core` 3.4.2). The Logback
`LogstashEncoder` (`rest-server/src/main/resources/logback.xml`) JSON-escapes
newlines, so an attacker-supplied header or query value cannot forge a log line;
no additional sanitisation is required.

## Tests

All diagnostic behaviour lives in the pure `formatLogLine`, `bodySize`, header
selection, and query redaction; these are unit-tested exhaustively. A thin
integration test proves the plugin wiring delivers the pre-handler attribute and
the real status to the formatter. Manual verification covers the log side-effect
end to end.

### `RequestLoggingConfigTest` (new, mirrors `ClientKeyGateConfigTest`)

- `secretHeaders` comma list parses to a lowercased set.
- Whitespace around `secretHeaders` / `headers` entries is trimmed; empty
  segments dropped.
- `headers = "*"` parses to `HeaderSelection.All`.
- `headers = "Accept,Content-Type"` parses to `HeaderSelection.Allowlist`.
- `detail = "failure"` -> `Detail.FAILURE`; `detail = "always"` ->
  `Detail.ALWAYS`.
- `detail = "bogus"` -> `Result.failure`.
- Missing `requestLogging` section -> `Result.failure`.

### `RequestLoggingFormatTest` (new — the guarantees)

- Careful success: `detail=FAILURE`, `status=200` -> bare `GET /x -> 200`, no
  enrichment segment.
- Careful failure: `detail=FAILURE`, `status=406`, allowlist -> line contains
  `Accept=[...]`, `Content-Type=[...]`, and `Expect=[(absent)]` for an unsent
  header.
- Always mode: `detail=ALWAYS`, `status=200` -> enriched.
- No response: `status=null` -> enriched.
- Secret subtraction under `All`: request carries `Cookie`, `Authorization`,
  `X-Unicoach-Client-Key` -> none appear in the line. **Invariant test.**
- Case-insensitive secret subtraction: request carries lowercase `authorization`
  -> absent from the line.
- Secret listed in an allowlist is still never emitted.
- `body=` / `respBody=` render `Nb` from `Content-Length`, `chunked` from
  `Transfer-Encoding: chunked`, `(none)` from neither.
- `latency=[Nms]` computed from injected start/end nanos (e.g. `0` then
  `3_000_000` -> `latency=[3ms]`).
- `Content-Length` appears both as an allowlist header and as `body=`.
- Query redaction: a secret param value -> `(redacted)`; a non-secret param
  untouched; a param absent from the query untouched; a `uri` with no query
  untouched; order and other params preserved.

### `RequestLoggingRoutingTest` (new — integration)

Boots a minimal `testApplication` with
`configureRequestLogging(config, nanoTime)` and a route that calls
`secretQueryParams("token")`, capturing the emitted line via a Logback
`ListAppender` on the logger `CallLogging` writes to.

- A request to that route with `?token=abc&status=open` that is forced to `406`
  (before the handler) logs a line whose `uri` shows `token=(redacted)` and
  whose segments include `Accept=[...]` — proving the Setup-phase stamp and the
  real status both reach the formatter on a pre-handler failure.
- A `200` on a route that does not opt in logs the raw (non-secret) query
  unchanged.

## Invariants

### Secret request-header values are never logged, in any environment

**Rule:** The request log MUST NOT emit the value of any header whose name
matches an entry in `requestLogging.secretHeaders`, in any environment or
verbosity mode (including `headers="*"`); the secret set is subtracted last,
after header selection, by case-insensitive name match.

**Why:** `Cookie`, `Authorization`, and `X-Unicoach-Client-Key` carry session
and credential material; logging them writes durable credentials into the log
store. Dev's `headers="*"` selects every header sent, so this subtraction is the
only barrier — applied before the wildcard expansion, or matched
case-sensitively (so a lowercase `authorization` slips through), it would leak.

**Target directory:** `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/`

## Implementation Plan

1. **Add `RequestLoggingConfig`.** Create
   `rest-server/src/main/kotlin/ed/unicoach/rest/config/RequestLoggingConfig.kt`
   with `HeaderSelection`, `Detail`, the data class, and `from`. Add
   `RequestLoggingConfigTest` covering the parse cases above.
   - Verify:
     `nix develop -c bin/test rest-server --tests "ed.unicoach.rest.config.RequestLoggingConfigTest"`
     (confirm `N executed`, not all-cached — add `-f` if needed).

2. **Rewrite `RequestLogging.kt`.** Replace the no-arg `configureRequestLogging`
   with `configureRequestLogging(config, nanoTime)`: the `StartNanosKey` Setup
   interceptor, `CallLogging` install delegating to `formatLogLine`, the pure
   `formatLogLine` / `bodySize` / header-selection / query-redaction helpers,
   and the `SecretQueryParamsKey` opt-in — a route-scoped
   `SecretQueryParamsPlugin` (`createRouteScopedPlugin` + `CallSetup`) that
   `Route.secretQueryParams` installs via `Route.install`, no
   `ApplicationCallPipeline` cast. Add `RequestLoggingFormatTest`.
   - Verify:
     `nix develop -c bin/test rest-server --tests "ed.unicoach.rest.plugins.RequestLoggingFormatTest"`.

3. **Add the config block and env overrides.** Add `requestLogging {}` to
   `rest-server/src/main/resources/rest-server.conf`; add
   `REQUEST_LOG_HEADERS=*` and `REQUEST_LOG_DETAIL=always` to `.env.dev`;
   document the `REQUEST_LOG_*` keys and the careful-default note in
   `.env.template`.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin` and confirm
     `RequestLoggingConfig.from` succeeds against the real config at boot
     (covered by the routing tests in step 5).

4. **Wire it in `Application.kt`.** Build
   `RequestLoggingConfig.from(config).getOrThrow()` in `startServer`; add the
   `requestLoggingConfig: RequestLoggingConfig` parameter to `appModule`;
   replace `configureRequestLogging()` with
   `configureRequestLogging(requestLoggingConfig)`.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`.

5. **Update the four `appModule` test call sites.** In
   `ConvoStreamErrorRoutingTest`, `ConvoExtractionEnqueueTest` (two calls),
   `ConvoToolLoopRoutingTest`, and `EmailVerificationGateTest`, build
   `RequestLoggingConfig.from(config).getOrThrow()` and pass it to `appModule`.
   Add `RequestLoggingRoutingTest`.
   - Verify: `nix develop -c bin/test rest-server` (confirm `N executed`).

6. **Record the invariant.** Add the secret-header invariant (Rule + Why) to
   `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/INVARIANTS.md`, and add
   an RFC-103 entry to its History list.
   - Verify: `nix develop -c bin/format -c`.

7. **Full gate.** Run the whole suite and the formatter.
   - Verify: `nix develop -c bin/format -c` and
     `nix develop -c bin/test rest-server -f`.

## Files Modified

- `rest-server/src/main/kotlin/ed/unicoach/rest/config/RequestLoggingConfig.kt`
  — new; the config data class + `from`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/RequestLogging.kt` —
  rewrite; parameterised plugin, start-nanos interceptor, pure formatter, query
  redaction, and the `Route.secretQueryParams` opt-in via a route-scoped
  `SecretQueryParamsPlugin` (`Route.install` + `CallSetup`, no cast).
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — build the
  config in `startServer`, add the `appModule` parameter, pass it to
  `configureRequestLogging`.
- `rest-server/src/main/resources/rest-server.conf` — add the
  `requestLogging {}` block.
- `.env.dev` — add `REQUEST_LOG_HEADERS=*` and `REQUEST_LOG_DETAIL=always`.
- `.env.template` — document the `REQUEST_LOG_*` keys and the careful defaults.
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/INVARIANTS.md` — add the
  secret-header invariant and an RFC-103 History entry.
- `rest-server/src/test/kotlin/ed/unicoach/rest/config/RequestLoggingConfigTest.kt`
  — new; parse-case tests.
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/RequestLoggingFormatTest.kt`
  — new; pure formatter + guarantee tests.
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/RequestLoggingRoutingTest.kt`
  — new; integration test for pre-handler attribute delivery and redaction.
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoStreamErrorRoutingTest.kt`
  — update the `appModule` call.
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoExtractionEnqueueTest.kt` —
  update both `appModule` calls.
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoToolLoopRoutingTest.kt` —
  update the `appModule` call.
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/EmailVerificationGateTest.kt`
  — update the `appModule` call.
