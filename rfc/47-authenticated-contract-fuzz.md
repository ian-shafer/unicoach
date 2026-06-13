# RFC 47: Authenticated Contract-Referee Fuzzing

## Executive Summary

`bin/test-fuzz` is rebuilt into a working end-to-end contract referee that
fuzzes the **authenticated** REST surface against the committed
`api-specs/openapi.yaml`. Today the script is inert: it targets an
`/api-spec/openapi.json` endpoint the server never serves, passes a Schemathesis
v3 `--validate-schema` flag absent from the v4 CLI, and shells out to a
`mise`-managed `schemathesis` that does not exist in the nix toolchain. Even if
it ran, it would hold no session, so every authenticated operation returns
`401`, leaving the bulk of the API's response schemas unvalidated — the same
blind spot that let an iOS `DECODE_ERROR` ship.

The rebuild does four things. (1) **Spec:** introduce an OpenAPI `apiKey`-in-
cookie `securityScheme` keyed `cookieAuth` whose cookie name is the
`UNICOACH_SESSION` the server actually reads, apply `security` to every
protected operation, and delete the ad-hoc lowercase `unicoach_session` cookie
parameter on `/api/v1/auth/me` (a real contract bug — cookie names are
case-sensitive). (2) **Harness:** auto-provision a pinned,
gitignored Schemathesis venv on the flake's `python3`; refuse to run if the
target port is already served; register a uniquely-named user at runtime,
capture its `UNICOACH_SESSION` cookie, and inject it into every request; and
exclude the two session-destructive operations so the fuzzer cannot revoke its
own credential. (3) **DB isolation:** run against a dedicated, reset-per-run
`unicoach-fuzz-<worktree>` database on a dedicated port, never the dev DB. (4)
**Triage:** the harness scopes the run to the 7 implemented operations and
classifies each contract-drift finding as a spec or server defect.

## Detailed Design

### Component 1 — Spec: model authentication as a security scheme

The committed `api-specs/openapi.yaml` is the contract iOS treats as truth and
the spec Schemathesis validates responses against; it must model reality.

**Security scheme.** Add one reusable scheme under `components.securitySchemes`:

```yaml
securitySchemes:
  cookieAuth:
    type: apiKey
    in: cookie
    name: UNICOACH_SESSION
```

The `name` is `UNICOACH_SESSION` — the value of `session.cookieName` in
`rest-server/src/main/resources/rest-server.conf`, which every route handler
reads via `call.request.cookies[sessionConfig.cookieName]`. The prior spec
modelled the credential as a per-operation cookie **parameter** named
`unicoach_session` (lowercase) on `/api/v1/auth/me` only. Cookie names are
case-sensitive, so `unicoach_session != UNICOACH_SESSION`: a client following
the spec literally would send a cookie the server ignores and receive `401`.
This is the one **confirmed spec defect**. Modelling auth as a parameter rather
than a scheme also made Schemathesis fuzz the credential with garbage instead of
treating it as a credential to supply.

**Apply `security`.** Add `security: [{ cookieAuth: [] }]` to exactly the
operations whose handler short-circuits to `401` when owner resolution fails:

| Operation (`operationId`) | Auth required |
| --- | --- |
| `getCurrentUser` (`GET /api/v1/auth/me`) | yes |
| `createStudent` (`POST /api/v1/students`) | yes |
| `getStudentMe` (`GET /api/v1/students/me`) | yes |
| `updateStudentMe` (`PATCH /api/v1/students/me`) | yes |
| `deleteStudentMe` (`DELETE /api/v1/students/me`) | yes |
| all nine `/api/v1/conversations*` operations | yes |
| `registerUser`, `loginUser` | no — establish the session |
| `logoutUser` | no — idempotent, returns `204` with or without a cookie |
| `getHello` | no — public health probe |

The nine `/api/v1/conversations*` operations carry `security` for spec
correctness — they are auth-gated by design and will short-circuit to `401` once
their routes are wired — even though the harness excludes them from every run
(their routes are unregistered today; see Triage).

`register` and `login` are the credential-minting entrypoints and `logout` is
idempotent (`AuthRoutes.handleLogout` clears the cookie and returns `204` even
when no cookie is present), so none carry `security`. No global top-level
`security:` is declared; applying it per-operation keeps the public endpoints
un-gated without a redundant `security: []` override on each.

**Remove the ad-hoc parameter.** Delete the
`parameters: [{ in: cookie, name: unicoach_session, ... }]` block from
`getCurrentUser`. It is the only ad-hoc cookie parameter in the spec; the
`securityScheme` replaces it.

**Documented responses.** The `401` responses required by this change already
exist on every protected operation in the current spec, so no `401` entries are
added; the `securityScheme` makes those existing `401`s semantically grounded
rather than orphaned. `405` is validated by Schemathesis's `unsupported_method`
check against the server's live `Allow`-header behaviour (`rejectUnsupportedMethods`
in `Routing.kt`) and does not require per-operation `405` response entries —
OpenAPI has no idiomatic way to attach a `405` to an operation that is itself
the wrong-method target. The Triage section records the actual
`unsupported_method` findings and their resolution.

### Component 2 — Harness: `bin/test-fuzz`

The script follows the daemon-lifecycle contract in
[`bin/SPEC.md`](../bin/SPEC.md) §III (own daemon teardown via an EXIT/INT/TERM
trap) and the Shared Cluster invariant in §II (never stop the cluster Postgres
shares across worktrees). It adds one behaviour absent from existing daemon
harnesses: it owns a dedicated database and resets it per run.

**Dedicated environment.** The script sets `export ENV_FILE=".env.fuzz"` before
sourcing `bin/common` — the single sanctioned pre-source statement. `.env.fuzz`
declares **only** the keys the harness's process tree actually consumes:
`PORT=8082` (dev `8080`, test `8081`) with `SERVER_PORT=$PORT` (the
`rest-server.conf` `${?SERVER_PORT}` override),
`POSTGRES_DB=unicoach-fuzz-$(basename "$PROJECT_ROOT")`, and the remaining
Postgres/`DATABASE_*` connection keys the db scripts and the JVM's `db.conf`
read — `${POSTGRES_DB}`/`${POSTGRES_PORT}` are required substitutions,
`${?DATABASE_USER}`/`${?DATABASE_PASSWORD}`/`${?DATABASE_MAXIMUM_POOL_SIZE}` are
overrides. It carries no `UNICOACH_NETWORK` (a docker-compose-era network label
that no script, `.conf`, Kotlin, or `flake.nix` reads — the harness runs
Postgres as a local nix cluster keyed by `POSTGRES_DATA_DIR`, so a network label
is meaningless) and no `JWT_*` keys (the REST server authenticates with opaque
session cookies via `SessionConfig`, and no `.conf` substitutes `${JWT_SECRET}`,
so the server boots without them — unlike the load-bearing `${POSTGRES_DB}`,
whose absence breaks `db.conf` resolution). Because `bin/common`
exports the sourced values (`set -a`) and the script invokes the daemon/db
scripts as child processes, every child (`postgres-up`, `db-reset`,
`build-rest-server`, `rest-server-up`, `rest-server-down`) inherits
`ENV_FILE=.env.fuzz` and operates on the fuzz DB and port. The booted
`rest-server` JVM inherits `POSTGRES_DB`/`SERVER_PORT` from the environment and
resolves `db.conf`'s `${POSTGRES_DB}` to the fuzz database — the same env-override
mechanism dev and test already rely on.

**Ordered run.** Under `set -e`:

1. **Provision Schemathesis** — ensure a pinned venv exists (Component 3).
2. **Port guard** — if anything is already listening on `localhost:$PORT`,
   `fatal`. A pre-existing listener is either a foreign server (which must never
   be fuzzed against this spec) or a leftover from a crashed run; either way the
   harness must boot its own freshly-built server, so it refuses rather than
   fuzz an unknown target.
3. `bin/postgres-up` — ensure the shared cluster is online.
4. `bin/db-reset` — drop → create → migrate the fuzz database, giving each run a
   clean schema and discarding the prior run's fuzz user and junk rows.
5. `bin/build-rest-server` — produce the `installDist` binary.
6. `bin/rest-server-up` — boot the daemon on `$PORT` and wait for `/hello`.
7. **Provision a session** — register a uniquely-named user and capture its
   `UNICOACH_SESSION` cookie (below).
8. **Run Schemathesis** — non-`exec`, capture the exit code, let the trap tear
   the server down, then exit with that code.

The trap is `trap '"$PROJECT_ROOT/bin/rest-server-down" >/dev/null 2>&1 || true'
EXIT INT TERM`. The current script's terminal `exec schemathesis` discards this
trap (a process replacement drops shell traps), so today the server is never
torn down; the rebuild runs Schemathesis as an ordinary child and exits with its
status so the trap fires.

**Session provisioning.** The script `POST`s to `/api/v1/auth/register` with a
unique email (`fuzz-$(date +%s)-$$@fuzz.invalid`), a ≥8-char password, and a
name, writing cookies to a jar via `curl -c`. It extracts the
`UNICOACH_SESSION` value from the jar and `fatal`s if it is empty (registration
must succeed against a freshly migrated DB). `register` returns `201` **with**
`Set-Cookie`, so one call both creates the user and starts the session.

**Credential injection.** The token is injected on **every** generated request
via `-H "Cookie: UNICOACH_SESSION=$TOKEN"`, combined with
`--generation-with-security-parameters false` so Schemathesis does not also
generate a random value for the `cookieAuth` parameter that would shadow the real
cookie. The injected header is static and independent of any `Set-Cookie` the
server returns mid-run, so operations that mint new sessions (`register`,
`login`) do not change the credential the harness presents.

**Self-session protection.** Two operations would revoke the injected
credential: `POST /api/v1/auth/logout` (revokes the session) and
`DELETE /api/v1/students/me` (deletes the account). Both are excluded by
`operationId` (`--exclude-operation-id logoutUser --exclude-operation-id
deleteStudentMe`). `login` and `register` are **not** destructive to the
injected header and remain in scope.

**Scope filters.** Schemathesis filters union (an operation matching any
`--exclude-*` is dropped). The run applies:

```
--exclude-path-regex conversations          # 9 unimplemented operations
--exclude-operation-id logoutUser           # session-destructive
--exclude-operation-id deleteStudentMe      # session-destructive
```

This selects exactly the 7 implemented, non-destructive operations: `GET
/hello`, `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `GET
/api/v1/auth/me`, `POST /api/v1/students`, `GET /api/v1/students/me`, `PATCH
/api/v1/students/me`. The harness `log-info`s the excluded surface so the
coverage gap is never silent.

**Invocation** (verified to parse and select 7/18 operations on Schemathesis
4.21.5):

```
schemathesis run "$PROJECT_ROOT/api-specs/openapi.yaml" \
  --url "http://localhost:$PORT" \
  --checks all --exclude-checks ignored_auth \
  --exclude-path-regex conversations \
  --exclude-operation-id logoutUser \
  --exclude-operation-id deleteStudentMe \
  --generation-with-security-parameters false \
  -H "Cookie: UNICOACH_SESSION=$TOKEN" \
  --report junit --report-junit-path "$PROJECT_ROOT/var/fuzz/report.xml"
```

`--checks all` is the goal; `ignored_auth` is the one check excluded by design:
it probes whether auth can be omitted by stripping the generated security
parameter, but the statically-injected `-H` cookie remains attached, so the
check would report every operation as "auth ignored" — a false positive the
harness design forces. A v4 CLI carries no `--validate-schema` flag; schema
validation against the loaded spec is intrinsic.

### Component 3 — Schemathesis provisioning

Schemathesis is not packaged in nixpkgs, so the harness provisions it into a
gitignored pip venv on the flake's `python3`. The harness `mkdir -p`s
`var/fuzz/` before provisioning or reporting, so both the venv and the JUnit
report have a guaranteed parent directory. The venv lives at
`var/fuzz/venv` (`var/` is already gitignored). The version is pinned to
`schemathesis==4.21.5` via a constant in the script. Provisioning is idempotent:
if `var/fuzz/venv/bin/schemathesis` is absent or reports a version other than
the pin, the script recreates the venv (`python3 -m venv`) and
`pip install`s the pin; otherwise it reuses the existing venv. All subsequent
calls use the venv's `schemathesis` binary by absolute path, never a PATH lookup
or a `mise`-managed binary.

### Component 4 — DB isolation decision

**Decision: dedicated, reset-per-run fuzz database, not the dev DB.** Fuzzing
issues thousands of mutating requests (`register` alone creates a user per valid
example); running against `unicoach` (the dev DB, as the current script does via
the default `.env`) accumulates junk users and rows in the database a developer
uses interactively. A dedicated `unicoach-fuzz-<worktree>` DB, dropped and
re-migrated by `bin/db-reset` at the start of every run, gives reproducibility
(each run starts from an identical migrated schema) and isolation (concurrent
runs across worktrees never collide, mirroring `.env.test`'s per-worktree
scheme). The fuzz DB shares the one cluster at `$HOME/var/unicoach/postgres`;
isolation is per-database, never per-cluster, consistent with `.env`/`.env.test`.

### Error Handling / Edge Cases

- **Port already served** → `fatal` before any build or boot (Component 2 step
  2). The message names the port and instructs the operator to stop the
  occupant.
- **Registration fails / empty cookie** → `fatal`; the run cannot validate the
  authenticated surface without a credential.
- **Server fails health check** → `bin/rest-server-wait-for-health` already
  `fatal`s after its timeout, pointing at `var/log/rest-server.log`.
- **Schemathesis finds contract drift** → non-zero exit propagates as the
  script's exit code (after teardown), failing the run; the JUnit report at
  `var/fuzz/report.xml` carries the per-operation detail.
- **Interrupted run** (`INT`/`TERM`) → trap tears down the fuzz server;
  Postgres and the shared cluster are untouched.

### Dependencies

- `flake.nix`'s `python3` (3.13) — venv host. No flake change: `schemathesis`
  stays a pip dependency, not a nix package.
- Existing `bin/` daemon and db scripts (`postgres-up`, `db-reset`,
  `build-rest-server`, `rest-server-up`, `rest-server-down`,
  `rest-server-wait-for-health`), reused unchanged.
- `curl` (system) for registration.

## Tests

The harness is itself the test artifact; its correctness is verified by
execution and by the existing `bin/scripts-tests` conventions. No new JUnit/Kotlin
or `bin/tests-common` assertion harness is added — `test-fuzz` is a test runner,
not a unit under test, matching `bin/test`'s own absence of a wrapping harness.

- **Spec validity** — `nix develop -c deno run ...` / Schemathesis spec load:
  `schemathesis run api-specs/openapi.yaml -u http://localhost:1 -n 0` must load
  the spec without schema errors after the `securityScheme` edits.
- **Operation selection** — the filter set must report `7 selected / 18 total`,
  and the 7 must be exactly the implemented, non-destructive operations.
- **Provisioning idempotency** — a second `bin/test-fuzz` invocation must reuse
  the existing `var/fuzz/venv` (no reinstall) when the pinned version matches.
- **Port guard** — with a process already bound to `8082`, `bin/test-fuzz` must
  `fatal` before building or booting.
- **Session injection** — the run must reach `2xx` on the authenticated
  read/write operations (`GET /api/v1/auth/me`, `GET /api/v1/students/me`,
  `POST /api/v1/students`), proving the cookie is accepted; a regression to
  `401` across all authenticated operations indicates the cookie name or
  injection broke.
- **Auth still enforced** — a manual probe compensating for the excluded
  `ignored_auth` check: `GET /api/v1/auth/me` with a deliberately wrong
  `Cookie: UNICOACH_SESSION=bogus` must return `401`, proving the `2xx`s above
  come from a valid credential and not a disabled guard.
- **End-to-end** — `nix develop -c bin/test-fuzz` EXITS NON-ZERO whenever the
  referee finds contract drift, and against today's server it always does:
  defects #1/#2/#4 in the Triage section are deterministic
  (`positive_data_acceptance` on `registerUser`'s boolean `name` and on
  `loginUser`'s `null` body, `content_type_conformance` on the `415`
  `text/plain` body) and fire on every run. The run reports a
  **seed-dependent subset** of the documented server defects: those three
  surface every run, while defect #3 (`not_a_server_error` on `registerUser`'s
  malformed email) surfaces only when Schemathesis generates a genuinely
  malformed email, so the reported set is **3–4** of the four. The invariant is
  not a fixed finding-count but that every reported finding is one of the
  documented, deferred server defects in the Triage table — never an
  undocumented finding and never a spec defect (those are fixed by this RFC).
  That non-zero exit is correct, intended behaviour for a contract referee
  facing a non-conformant server; the harness exits `0` only against a
  conformant server — i.e. once a future server-fix RFC lands. Regardless of
  exit code, the run leaves no `rest-server` daemon running (the EXIT/INT/TERM
  trap fired) and no rows in the dev `unicoach` DB.
- **No dev-DB contamination** — after a run, `unicoach` (dev) is unchanged; only
  `unicoach-fuzz-<worktree>` saw writes.

## Implementation Plan

1. **Edit `api-specs/openapi.yaml`.**
   - Add `components.securitySchemes.cookieAuth` (`apiKey`/`cookie`/
     `UNICOACH_SESSION`).
   - Delete the `parameters` cookie block on `getCurrentUser`.
   - Add `security: [{ cookieAuth: [] }]` to `getCurrentUser`, `createStudent`,
     `getStudentMe`, `updateStudentMe`, `deleteStudentMe`, and the nine
     `/api/v1/conversations*` operations.
   - Verify: `nix develop -c bash -c 'var/fuzz/venv/bin/schemathesis run
     api-specs/openapi.yaml -u http://localhost:1 -n 0'` loads the spec with no
     schema error (run after step 2 provisions the venv, or with any installed
     4.x).
   - Verify: `nix develop -c deno run --allow-read -e 'import {parse} from
     "jsr:@std/yaml"; parse(Deno.readTextFileSync("api-specs/openapi.yaml"))'`
     exits `0` (YAML well-formed).

2. **Create `.env.fuzz`.** Declare **only** the keys the fuzz process tree
   consumes (Component 2): `PORT=8082`, `SERVER_PORT=$PORT`,
   `POSTGRES_DB=unicoach-fuzz-$(basename "$PROJECT_ROOT")`, and the
   Postgres/`DATABASE_*` connection keys the db scripts and `db.conf` read —
   `POSTGRES_USER`, `POSTGRES_HOST_AUTH_METHOD=trust`, `PGHOST`,
   `POSTGRES_DATA_DIR` (the shared cluster path), `POSTGRES_ADMIN_DB`,
   `POSTGRES_PORT`, `DATABASE_USER`, `DATABASE_PASSWORD`,
   `DATABASE_MAXIMUM_POOL_SIZE`. Do **not** copy `UNICOACH_NETWORK` or any
   `JWT_*` key; nothing in the fuzz path reads them.
   - Verify: `nix develop -c bash -c 'ENV_FILE=.env.fuzz bin/db-reset'` drops,
     creates, and migrates `unicoach-fuzz-<worktree>` with exit `0`.
   - Verify: `grep -E 'UNICOACH_NETWORK|JWT_' .env.fuzz` matches nothing
     (exit `1`).

3. **Rewrite `bin/test-fuzz`.** Implement Components 2–3: `export
   ENV_FILE=".env.fuzz"` pre-source; pinned-venv provisioning; port guard;
   `postgres-up` → `db-reset` → `build-rest-server` → `rest-server-up`;
   registration + cookie capture; the verified Schemathesis invocation
   (non-`exec`, captured exit code); EXIT/INT/TERM teardown trap; `help()` per
   the `bin/SPEC.md` template with documented non-zero exit codes.
   - Verify: `nix develop -c bin/test-fuzz --help` prints usage, exits `0`.
   - Verify: `nix develop -c bin/scripts-tests` passes (no regression in the
     `bin/` script test harness).
   - Verify: `nix develop -c bin/test-fuzz` provisions, boots, authenticates,
     runs the 7 operations, tears the server down, and exits with the
     Schemathesis status; inspect `var/fuzz/report.xml`.

4. **Triage the run output.** Execute `bin/test-fuzz`, classify every reported
   finding per the Triage table below, and apply each resolution: a spec edit in
   `api-specs/openapi.yaml`, a documented check/operation exclusion in
   `bin/test-fuzz` with an inline rationale comment, or — for the four known
   server defects — an inline comment in `bin/test-fuzz` documenting each as a
   known server defect left as a live failure (not excluded) and deferred to a
   future server-fix RFC. Re-run until every residual non-zero finding is one
   of those three sanctioned dispositions; against today's server the residual
   set is a seed-dependent subset of the documented server defects — defects
   #1/#2/#4 are deterministic and fire on every run, while defect #3
   (malformed email → `500`) surfaces only when a malformed-email input is
   generated, so the reported set is 3–4 of the four — and the harness always
   EXITS NON-ZERO by design.
   - Verify: `nix develop -c bin/test-fuzz` exits non-zero, and every reported
     finding is one of the documented, deferred server defects (defects
     #1/#2/#4 always present; #3 input-dependent) — never an undocumented
     finding and never a spec defect; it would exit `0` only against a
     conformant server.
   - Verify: no `rest-server` PID file remains (`test ! -f
     var/run/rest-server.pid`), and the dev `unicoach` DB shows no fuzz users
     (`bin/db-query` against `.env`) — both hold regardless of exit code.

### Triage: contract-drift classification

| Finding | Class | Resolution |
| --- | --- | --- |
| `/api/v1/auth/me` cookie-param casing mismatch | **Spec defect** | Per Component 1 |
| Authenticated operations carry no `security` | **Spec defect** | Per Component 1 |
| `/api/v1/conversations*` documented but return `404` (routes unregistered in `Routing.kt`) | **Server defect** (not yet wired) | Out of scope here; excluded via `--exclude-path-regex conversations` and `log`-ged. Resolved when a future RFC registers the conversation routes |
| `ignored_auth` reports every operation as auth-ignored | **Harness artifact** | Excluded via `--exclude-checks ignored_auth` (rationale in Component 2) |
| `registerUser` accepts `name: false` → `201` with `name:"false"` (`positive_data_acceptance`; Jackson coerces a JSON boolean into a `String`) | **Server defect** | Documented in `bin/test-fuzz` as a known server defect; left as a live failure — **not** excluded. Deferred to a future server-fix RFC |
| `loginUser` accepts a `null` body → `415` rather than a clean reject (`positive_data_acceptance`; Jackson lenient null handling) | **Server defect** | Documented in `bin/test-fuzz` as a known server defect; left as a live failure — **not** excluded. Deferred to a future server-fix RFC |
| `registerUser` with a malformed email (e.g. `useratexample.com`) → `500` (`not_a_server_error`; email-format validation throws instead of returning `400`/`422`) | **Server defect** | Documented in `bin/test-fuzz` as a known server defect; left as a live failure — **not** excluded. Deferred to a future server-fix RFC. Its appearance is input-dependent: it surfaces only when Schemathesis generates a genuinely malformed (no-`@`) email, so it is the one defect of these four that does not fire on every run |
| The `415` response body is `text/plain`, but the spec declares `application/json` (`content_type_conformance`; Ktor's `UnsupportedMediaTypeException` default emits a text body, bypassing the JSON `ErrorResponse`) | **Server defect** | Documented in `bin/test-fuzz` as a known server defect; left as a live failure — **not** excluded. Deferred to a future server-fix RFC |
| `unsupported_method` / other residual `--checks all` findings on the 7 live operations | **Determined at run time** | Step 4: each is classified and given a sanctioned disposition — a spec edit, a documented check/operation exclusion, or recorded as a known, deferred server defect left as a live failure (the disposition applied to the four rows above). The live `405`+`Allow` behaviour is expected to satisfy `unsupported_method`; any divergence (e.g. `POST /register`'s leaf-`post` method rejection) is recorded and resolved there |

## Files Modified

- `api-specs/openapi.yaml` — add `cookieAuth` securityScheme; remove the
  ad-hoc `unicoach_session` cookie parameter on `getCurrentUser`; add
  `security` to the protected operations.
- `.env.fuzz` — new dedicated fuzz environment (`PORT=8082`,
  `POSTGRES_DB=unicoach-fuzz-<worktree>`).
- `bin/test-fuzz` — full rewrite per Components 2–3.

No `.gitignore` change is required: `var/` (line 4) already covers `var/fuzz/`.
