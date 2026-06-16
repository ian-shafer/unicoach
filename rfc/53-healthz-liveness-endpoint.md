# RFC 53: `/healthz` Liveness Endpoint

## Executive Summary

Replace the placeholder `GET /hello` route with a purpose-built liveness
endpoint, `GET /healthz`, ahead of an AWS deployment where an ALB/target-group
health check polls it.

`GET /healthz` returns `200 OK` with a fixed 15-byte JSON body
(`{"status":"ok"}`) whenever the process is up and serving HTTP. It is
**unauthenticated** and **unversioned** (NOT under `/api/v1`): it is
infrastructure for the load balancer, not a product API.

The endpoint is a **pure liveness** check: it MUST NOT touch the database,
queue, chat provider, or any backing dependency, so a transient dependency blip
cannot make AWS kill and replace an otherwise-serving task. The full rationale
and the never-returns-non-200 guarantee are specified in Detailed Design. A
readiness/dependency check is a separate future endpoint, out of scope here.

Removing `/hello` is not purely additive: `bin/rest-server-check` curls
`GET /hello` as the rest-server liveness probe, and the daemon lifecycle
(`rest-server-up`/`-bounce`) and `bin/test-fuzz` depend on it transitively. This
RFC therefore also repoints `bin/rest-server-check` to `/healthz` and refreshes
the stale references (`bin/` help text, `README.md`, the OpenAPI spec). The
local daemon probe thereby becomes a real liveness check instead of a
placeholder greeting.

## Detailed Design

### Data Models

None. The body is the constant literal `{"status":"ok"}` (15 bytes), emitted as
a string — NOT a serialized DTO. A Jackson DTO via `ContentNegotiation` is
avoided because that plugin enables `INDENT_OUTPUT`
([`plugins/Serialization.kt`](../rest-server/src/main/kotlin/ed/unicoach/rest/plugins/Serialization.kt)),
which would pretty-print the body and break byte-stability, and a serialization
misconfiguration could turn the probe itself into a `500`. The constant cannot
fail to serialize and is independent of any plugin.

### API Contracts

`GET /healthz`

- **Path**: `/healthz` — top-level, NOT under `/api/v1`. Registered in the same
  top-level `routing { }` block in
  [`Routing.kt`](../rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt)
  that previously held `/hello`, so it inherits no authentication (the
  per-route-group auth lives inside `AuthRouteHandler`/`StudentRouteHandler`/
  `ConvoRouteHandler`, none of which wrap this route).
- **Authentication**: none.
- **Response**: `200 OK`, `Content-Type: application/json`, body exactly
  `{"status":"ok"}`.
- **Unsupported methods**: the route block calls
  `rejectUnsupportedMethods(HttpMethod.Get)` — the existing helper in
  `Routing.kt` — so a non-GET request to `/healthz` returns
  `405 Method Not
  Allowed` with an `Allow: GET` header, matching the
  established top-level route convention the `/hello` block used. The `405` body
  is plain text emitted by that shared helper; only the `200` liveness body is
  JSON.
- **No dependency access**: the handler returns the constant unconditionally —
  see Error Handling for the no-dependency invariant.

`/hello` is deleted: after this RFC, `GET /hello` returns `404 Not Found`
(Ktor's default for an unregistered path).

### Error Handling / Edge Cases

- **Session-expiry plugin interaction**: no change required.
  `SessionExpiryPlugin` already skips any request path matching a prefix in
  `ignorePathPrefixes`, which includes `"/health"`
  ([`rest-server.conf`](../rest-server/src/main/resources/rest-server.conf)).
  Because `"/healthz".startsWith("/health")` is true, `/healthz` is already
  exempt from expiry enqueueing even on the (not-expected) path where a request
  carries a session cookie. The implementation MUST NOT add a redundant
  `ignorePathPrefixes` entry.
- **Request-body-size limit**: applies application-wide but is inert for a
  bodyless `GET`; no per-route wiring needed.
- **The endpoint never returns non-200 while the process is up.** There is no
  failure branch — it does not inspect anything that could fail. A non-200 (or
  no response at all) means the process is down or unresponsive, which is
  exactly the liveness signal AWS consumes.

### Forward Reference (out of scope)

A separate future RFC introduces a client-key gate that requires a key on
incoming requests. `/healthz` MUST be exempt from that gate so the ALB — which
has no client key — can poll it. This RFC does not implement the gate or any
exemption mechanism; it only records the requirement so the gate RFC accounts
for it.

### Dependencies

None added. The endpoint uses only Ktor primitives already present (`get`,
`respondText`, `ContentType.Application.Json`, and the existing
`rejectUnsupportedMethods` helper).

## Tests

A new test class, `HealthzRoutingTest`
(`rest-server/src/test/kotlin/ed/unicoach/rest/HealthzRoutingTest.kt`), replaces
the deleted `RoutingTest`. It boots the server via `startServer(wait = false)`
and drives it over HTTP with a CIO `HttpClient`, mirroring the boot/teardown
harness `RoutingTest` used (`@BeforeAll` boots and captures the resolved port;
`@AfterAll` stops the server and closes the client).

All test methods MUST use **block bodies**
(`fun name() { runBlocking { ... } }`), NOT expression bodies
(`fun name() = runBlocking { ... }`). A Kotlin expression-body test returning a
non-`Unit` value is silently unregistered by JUnit 5 and never executes; the
deleted `RoutingTest` used the expression-body form and this RFC MUST NOT
reproduce it.

Tests:

1. **`healthzReturns200WithStatusOkBody`** — `GET /healthz` returns
   `HttpStatusCode.OK`, the `Content-Type` header is `application/json`, and the
   response body equals exactly `{"status":"ok"}`.
2. **`helloIsGone`** — `GET /hello` returns `HttpStatusCode.NotFound`, proving
   the placeholder route is fully removed.

## Implementation Plan

1. **Replace the `/hello` route with `/healthz` in `Routing.kt`.** In
   [`Routing.kt`](../rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt),
   delete the `route("/hello") { ... }` block and add a `route("/healthz") { }`
   block in the same top-level `routing { }` scope. Its `get { }` responds with
   the constant body `{"status":"ok"}` at `ContentType.Application.Json`; the
   block also calls `rejectUnsupportedMethods(HttpMethod.Get)`. Remove the
   now-unused `ContentType.Text.Plain` / `withCharset` imports if they become
   unreferenced.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`
   - Verify:
     `nix develop -c ktlint rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt`
   - Verify:
     `! grep -q '/hello' rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt`

2. **Delete `RoutingTest.kt` and add `HealthzRoutingTest.kt`.** Delete
   [`RoutingTest.kt`](../rest-server/src/test/kotlin/ed/unicoach/rest/RoutingTest.kt).
   Create `rest-server/src/test/kotlin/ed/unicoach/rest/HealthzRoutingTest.kt`
   with the boot/teardown harness and the two block-bodied tests specified under
   **Tests**.
   - Verify:
     `test -f rest-server/src/test/kotlin/ed/unicoach/rest/HealthzRoutingTest.kt`
   - Verify:
     `! test -e rest-server/src/test/kotlin/ed/unicoach/rest/RoutingTest.kt`
   - Verify:
     `nix develop -c ktlint rest-server/src/test/kotlin/ed/unicoach/rest/HealthzRoutingTest.kt`
   - Verify:
     `nix develop -c bin/test rest-server --tests "ed.unicoach.rest.HealthzRoutingTest"`
     (confirm both tests executed, not skipped/dropped, per the block-body
     requirement)

3. **Repoint `bin/rest-server-check` to `/healthz`.** In
   [`bin/rest-server-check`](../bin/rest-server-check), change the probe URL
   from `/hello` to `/healthz` and update the help text describing the probe
   target.
   - Verify:
     `grep -q '/healthz' bin/rest-server-check && ! grep -q '/hello' bin/rest-server-check`

4. **Refresh stale `/hello` references in tooling and docs.** Update the help
   text in
   [`bin/rest-server-wait-for-health`](../bin/rest-server-wait-for-health), the
   step-6 comment in [`bin/test-fuzz`](../bin/test-fuzz), and the
   `bin/rest-server-check` description line in [`README.md`](../README.md) to
   say `/healthz` instead of `/hello`.
   - Verify: `! grep -rn '/hello' bin/ README.md`

5. **Remove the `/hello` path from the OpenAPI spec.** In
   [`api-specs/openapi.yaml`](../api-specs/openapi.yaml), delete the `/hello`
   path item (the `getHello` operation) and rewrite the now-stale
   `info.description` (currently the "Foundation \"Hello World\" OpenAPI spec…"
   line) so it no longer frames the spec around the removed greeting. Do NOT add
   a `/healthz` entry — `/healthz` is infrastructure, not a versioned product
   API, and is intentionally absent from the product API spec.
   - Verify: `! grep -qi 'hello' api-specs/openapi.yaml`
   - Verify (well-formed YAML):
     `nix develop -c deno run --allow-read -e 'import { parse } from "jsr:@std/yaml"; parse(Deno.readTextFileSync("api-specs/openapi.yaml")); console.log("ok")'`

6. **Full repo grep gate and module test pass.** Confirm `/hello` survives
   nowhere in the implementation-owned tree and the rest-server suite is green.
   The grep excludes `rfc/` (immutable history) and every `SPEC.md`:
   `rest-server/src/main/kotlin/ed/unicoach/rest/SPEC.md` still carries `/hello`
   references (a "MUST NOT be removed" invariant and the route listing), but
   SPEC files are reconciled out-of-band by `spec-sync-loop`, not by this RFC,
   and are deliberately absent from Files Modified.
   - Verify:
     `! grep -rn '/hello' --include='*.kt' --include='*.sh' --include='*.yaml' --include='*.md' . | grep -v '^./rfc/' | grep -v 'SPEC.md'`
   - Verify: `nix develop -c bin/test rest-server`

## Files Modified

- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt` — replace the
  `/hello` route block with the `/healthz` liveness route.
- `rest-server/src/test/kotlin/ed/unicoach/rest/RoutingTest.kt` — **deleted**.
- `rest-server/src/test/kotlin/ed/unicoach/rest/HealthzRoutingTest.kt` —
  **created**; `/healthz` 200/body test and `/hello` 404 test.
- `bin/rest-server-check` — repoint liveness probe from `/hello` to `/healthz`;
  update help text.
- `bin/rest-server-wait-for-health` — update help text reference `/hello` →
  `/healthz`.
- `bin/test-fuzz` — update step-6 comment reference `/hello` → `/healthz`.
- `README.md` — update the `bin/rest-server-check` description line.
- `api-specs/openapi.yaml` — remove the `/hello` (`getHello`) path item.
