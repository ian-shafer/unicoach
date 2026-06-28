# RFC 73: Daemon Port-Collision Preflight

## Executive Summary

`bin/daemon-up` decides "already running?" solely from the per-worktree
`var/run/<svc>.pid`, which is blind to processes from other checkouts. A stale
`admin-server` from a different worktree holding `127.0.0.1:8081` caused a fresh
JVM to be spawned, die with `BindException`, yet `daemon-up` wrote the dead PID
and logged success, and `admin-server-wait-for-health` got HTTP 200 from the
**foreign** server — a false-healthy boot serving stale code.

This RFC adds a pre-launch TCP port preflight for the `daemon-up`-managed
daemons (`rest-server`, `admin-server`, `public-web`; `queue-worker` is
portless). A new primitive `bin/check-port <port>`, a sibling to
`bin/check-pid`, reports whether a port is bound, built on the
`bin/INVARIANTS.md`-mandated `exec 3<>/dev/tcp/127.0.0.1/$PORT` probe —
occupant-agnostic, dependency-free, and cross-platform (no `lsof`). `daemon-up`
gains an optional `--port`; the port-bound `-up` wrappers pass it. Ownership is
decided **before** the port is consulted, from our own tracking file: if our
recorded process is alive, `-up` is idempotent (`exit 0`) and never reaches the
port check. Only once we conclude we are not running does the preflight run — a
free port spawns, a taken port **fatals naming `[$service]` / `[$port]`**, never
spawning a doomed process, never writing a dead PID, never reaching health-wait.
`daemon-check` and `daemon-status` gain a `conflict` tri-state (our PID
absent/dead while the port is held), `daemon-status` resolving ports through a
data-driven name→port map so a future port-bound service gains conflict
detection without a new branch. A new `bin/daemon-http-check` primitive owns the
curl→`check-port` health tri-state in one place; the HTTP `*-check` scripts
become thin wrappers reporting "port held by another process" distinctly from
"stopped."

## Detailed Design

### `bin/check-port <port>` (new primitive)

A passive probe of whether `127.0.0.1:<port>` has a listener. It performs a
single `exec 3<>/dev/tcp/127.0.0.1/<port>` connect and immediately closes the
descriptor, honoring the `bin/INVARIANTS.md` rule "Port-liveness probe via
pure-bash `/dev/tcp`" verbatim — no `nc`, no `curl`, no `lsof`, no added
dependency. It mirrors `bin/check-pid`'s shape: a positional argument, a
`help()` block, and result reported only through the exit code with diagnostics
on stderr.

Exit codes (documented in `help()`):

- `0` — port is in use (connect succeeded).
- `1` — port is free (the loopback connect failed; refused).
- `2` — invalid argument (missing, non-numeric, or out-of-range port).

A loopback connect accepts or refuses instantly, so no timeout wrapper is
required. The caller idiom is `if check-port "$PORT"; then …` (in-use branch).
`0`=in-use deliberately inverts `check-pid`'s `0`=alive: each primitive's
success exit answers its own question (`is the port taken?` vs
`is the PID alive?`), so the natural `if`-true branch reads correctly at every
call site.

### `bin/daemon-up` — optional `--port` and preflight

`daemon-up` gains an optional `-p` / `--port <port>` flag holding a `PORT`
variable. The existing options loop `break`s on the first non-option and does
not consume a value, so `--port` is added as an explicit two-token case
(`-p|--port) PORT="$2"; shift 2`), mirroring `bin/file-lock`'s `-o` / `-t`
handling; the value is validated as numeric before any spawn (a missing or
non-numeric value fatals via `help`). The `help()` synopsis line gains
`[--port <port>]` and an `Options` entry. When `--port` is absent (the portless
`queue-worker` path) the preflight is skipped and behavior is unchanged. The
preflight is inserted into the existing `file-lock` critical section and `trap`,
in this order:

1. PID-file liveness short-circuit (existing): if a PID file exists **and** our
   recorded PID is alive, `exit 0`. Our own running instance is therefore
   recognized as ours and never reaches the port check — idempotency is
   preserved. This branch is gated on the PID file existing (in `daemon-up` the
   liveness check lives inside `if [ -f "$PID_FILE" ]`); when the file is absent
   control falls straight through to the port check (step 3) — the same path
   that produces the orphaned-own-process refusal in Error handling, not a
   separate branch.
2. Stale-PID cleanup (existing).
3. **New:** if `PORT` is set and `check-port "$PORT"` reports in-use, `fatal`
   with a distinct non-zero exit code naming `[$SERVICE_NAME]` and
   `[127.0.0.1:$PORT]`, never spawning. Per the `bin/INVARIANTS.md` rule
   "Distinct non-zero exit codes per failure reason", the collision is given its
   own code `3` (`fatal -s 3`), separate from the existing lock-acquire-fail
   `fatal` (default exit `1`) and the concurrent-`up` graceful `exit 0`; the new
   code is documented in `help()`. The existing `EXIT`/`INT`/`TERM` trap removes
   the lock directory on this exit.
4. Spawn (existing).

Because a second concurrent `up` matches the operation at `file-lock` (which
exits `10`, translated by `daemon-up` to a graceful `exit 0`) _before_ reaching
step 3, the idempotent concurrent-up exit-0 contract and `bin/file-lock`
concurrency are unaffected.

### `bin/daemon-check` — optional `--port` and tri-state

`daemon-check` gains an optional `-p` / `--port <port>` flag, added to its
options loop as an explicit two-token case (`-p|--port) PORT="$2"; shift 2`),
the same shape as `daemon-up`. After its existing PID logic: our PID alive ⇒
`running` (`0`); else if `--port` is given and `check-port` reports in-use ⇒
`port held by another process` (`2`); else ⇒ `stopped` (`1`). Without `--port`
(e.g. the unchanged `queue-worker-check` path) the behavior is exactly as today
— only `0` / `1`, never `2` — so existing callers that treat any non-zero as
"not running" are unaffected. The new exit code `2` and the `--port` option are
documented in `help()`.

### `bin/daemon-status` — port-aware conflict state

`daemon-status` resolves each service's state through a data-driven name→port
structure rather than a per-service named branch, so a future port-bound service
added to the status list gains conflict detection by registering its port — no
new code branch. Two structures back the loop: the ordered
`SERVICES=(postgres rest-server queue-worker)` array fixes iteration/print
order, and an associative map `SERVICE_PORTS` (bash 5.x — the dev-shell
interpreter) carries the port of each port-bound service
(`SERVICE_PORTS[rest-server]="${PORT:-8080}"`). A service absent from the map is
portless; key presence/absence is the portless sentinel, with no delimiter
packed into the service token (no implicit string-parsing contract).

The loop keeps `postgres` as its own named branch — its `postmaster.pid` probe
cannot route through `daemon-check` — and routes every other service through one
generic path: resolve `port="${SERVICE_PORTS[$service]:-}"`, call
`daemon-check --port "$port" "$service"` when the port is non-empty else
`daemon-check "$service"`, and map the result through a single exit-code `case`
— `0`→`running`, `1`→`stopped`, `2`→`conflict`, anything else→`log-warning` +
`unknown`. This collapses the previously duplicated `rest-server` and portless
`case` blocks into one. A portless service never yields `2` (`daemon-check`
returns only `0`/`1` without `--port`), so the `conflict` arm is unreached for
`queue-worker`. The status line format gains `conflict` as a third state.

`rest-server` reads `$PORT` (default `8080`), the same variable its `*-check` /
`*-wait-for-health` scripts probe; `.env` defines `SERVER_PORT=$PORT` only as a
JVM bind alias, so `$PORT` is the value the port machinery uses everywhere.
`daemon-status` runs with no env override and sources `bin/common`, which loads
the top-level `.env` (`PORT=8080`) — so in normal operator use the `conflict`
line reflects the `8080` binding. (Under the `bin/test` harness's `.env.test`
the same machinery resolves `8081`; the foreign-listener tests bind whatever
`$PORT` resolves to.)

`conflict` reaches only `rest-server` because it is the sole port-bound entry in
the status `SERVICES` list — `admin-server` and `public-web` are not listed
there, and this refinement changes only how the listed services are resolved,
not which services appear. The `postgres` branch (its `postmaster.pid` probe)
and the portless `queue-worker` path are unchanged; postgres is out of scope for
this RFC.

### `bin/daemon-http-check <service-label> <port> <health-url>` (new primitive)

A daemon-aware HTTP health probe that resolves a port-bound daemon to one of
three operational states, owning the curl→`check-port` tri-state previously
triplicated verbatim across the per-service `*-check` scripts. A sibling to
`bin/check-port` and `bin/check-pid`: a standalone executable that sources
`bin/common`, takes positional arguments, carries a `help()` block, and reports
its result only through the exit code with diagnostics on stderr. It is
daemon-aware where `check-port` is occupant-agnostic — it composes `curl` (HTTP
health) with `check-port` (TCP liveness), delegating the `/dev/tcp` probe to
`check-port` so the `bin/INVARIANTS.md` port-liveness rule lives in one place.
`check-port` alone cannot yield a `healthy` verdict — a TCP connect cannot
distinguish an HTTP-200 server from any other listener — so the composition, not
`check-port`, is what the wrappers duplicate.

Resolution order: `curl -sf <health-url>` succeeds ⇒ `0` (healthy); else
`check-port <port>` reports in-use ⇒ `2`, logging that port `[<port>]` is bound
but not responding as `<service-label>` (held by another process or an unhealthy
instance); else ⇒ `1` (not running).

The `curl` probe is time-bounded (`--connect-timeout` / `--max-time`, capped
well under 4s) so a single probe can never block. A listener that accepts the
TCP connection but never sends an HTTP response — a daemon wedged
mid-`/healthz`, or a non-HTTP occupant — would otherwise make an unbounded
`curl` hang forever, and the `wait-for` loop that drives the `*-wait-for-health`
scripts only re-checks its own deadline _between_ probes, so an unbounded probe
defeats that timeout entirely. Bounding the probe is what makes the
health-wait's timeout real. A regression test asserts the probe returns (exit
`2`) against an accept-but-never-reply listener instead of hanging.

Arguments:

- `service-label` — the identifier named in the diagnostic.
- `port` — the TCP port for the `check-port` fallback.
- `health-url` — the full URL `curl` probes; the caller owns the `/healthz`
  path, so the primitive stays ignorant of route conventions.

Exit codes (documented in `help()`):

- `0` — healthy (HTTP 200).
- `1` — not running (`curl` failed, port free).
- `2` — port held by another process or an unhealthy instance (`curl` failed,
  port in use).
- `3` — invalid arguments (wrong count, or a non-numeric/out-of-range port).
  Kept distinct from the `2` conflict state so a malformed invocation is never
  read as a conflict, per the `bin/INVARIANTS.md` "Distinct non-zero exit codes
  per failure reason" rule. (`check-port` reuses `2` for invalid args; that code
  is unavailable here.)

### HTTP `*-check` wrappers

`rest-server-check`, `admin-server-check`, and `public-web-check` become thin
wrappers: each sources `bin/common` and delegates to
`daemon-http-check <label> <port> <health-url>` with its own service label,
default-guarded port, and health URL (`http://localhost:<port>/healthz`). The
success path (HTTP 200 ⇒ `0`) and the failure tri-state (held ⇒ `2`, free ⇒ `1`)
live once in the primitive; each wrapper carries only its label, port default,
and URL. The default-guarded port form is mandatory: `rest-server-check` reads
`${PORT:-8080}`, `admin-server-check` `${ADMIN_SERVER_PORT:-8081}`,
`public-web-check` `${PUBLIC_WEB_PORT:-8082}` — `ADMIN_SERVER_PORT` and
`PUBLIC_WEB_PORT` are defined in neither `.env` nor `.env.test`, so under
`bin/common`'s `set -u` a bare reference aborts on unbound variable. Each
wrapper's exit-code contract (`0`/`1`/`2`) is unchanged from the caller's view —
the wrappers always pass three valid arguments, so the primitive's `3` is
unreachable through them; only the implementation is hoisted into the primitive.

### Error handling and edge cases

- Our PID file lost while our own daemon is orphaned-alive on the port:
  `daemon-up` fatals "port held" (exit `3`) rather than re-adopting it. This is
  an accepted safe refusal (no double-spawn) and the deliberate trade for not
  identifying occupants. It is exercised by the cross-process collision
  regression test, whose foreign listener is indistinguishable from an orphaned
  own-process from `daemon-up`'s vantage (the PID file is absent in both cases).
- Generic `bin/daemon-bounce` is intentionally **not** given `--port`; the
  per-service bounce wrappers do not route through it. `bin/rest-server-bounce`
  calls `bin/rest-server-down` then `bin/rest-server-up`, and `*-up` is where
  the port is supplied — so a bounce carries the port without `daemon-bounce`
  changing. `daemon-bounce` is not modified.
- `--port` validation: a missing or non-numeric value fatals via `help` before
  any spawn.

### Dependencies

None added. `check-port` relies on bash `/dev/tcp` only, already required by the
existing port-liveness invariant.

## Tests

Test additions live in `bin/scripts-tests`, plus a small `bin/test-tcp-listener`
helper (a real `python3` file — bash cannot `listen()` and the dev shell ships
no `nc`/`socat`) that binds a port on `127.0.0.1` as a _foreign_ listener whose
PID is not recorded in any of our PID files. It takes a mode: `close` (accept
and immediately close — a fast empty reply, the occupant case) or `hold` (accept
and never reply — the wedged-server case used to prove the bounded probe does
not hang); the test traps clean it up. The three distinct ports these tests need
(one general, one for the idempotency/concurrency cases, one for
`daemon-http-check`) are obtained at run time from `bin/find-free-port` — a
small helper that scans upward from a base (default `18000`, well above the
harness's `.env.test` `PORT=8081`) and prints the first port `check-port`
reports free — rather than hardcoded constants guarded by a "pre-check free or
fail" dance. If no port is free the helper fails and the suite aborts. (The
specific port numbers in the per-test descriptions below are illustrative; the
actual values are whatever `find-free-port` returns.)

The same rule applies to the real service the suite boots: `bin/scripts-tests`
exports a free `PORT` (from `find-free-port`) **before** sourcing the env, and
`.env.test` is changed from a hardcoded `PORT=8081` to `PORT="${PORT:-8081}"` so
that exported value wins. The booted `rest-server` therefore binds a free port
rather than a fixed `8081`, so the suite cannot collide with a developer's
`admin-server` or other daemon already on `8081` across worktrees — the suite is
hermetic with respect to ports. Hardcoded ports remain only in `.env`
(prod/dev); `8081` survives in `.env.test` solely as a fallback for tooling that
sources it without first picking a port (e.g. the Kotlin `bin/test` harness,
whose behavior is unchanged).

Any assertion on a **specific** exit code (the collision `3`, the tri-state `2`,
the HTTP-check `2`/`1`, the `daemon-http-check` usage `3`) must capture `$?` and
compare the exact value — e.g. `bin/daemon-up --port … || status=$?` then
`[ "${status:-0}" -ne 3 ]` — the same pattern the existing file-lock exit-`10`
test uses. The boolean `assert_success` / `assert_failure` helpers in
`bin/tests-common` only distinguish zero from non-zero and cannot witness which
non-zero code was returned, so they are insufficient for these cases.

- **check-port: free** — `bin/check-port 18099` on an unbound port exits `1`.
- **check-port: in use** — with a python listener bound to `18099`,
  `bin/check-port 18099` exits `0`; after killing it, `bin/check-port 18099`
  exits `1`.
- **check-port: invalid args** — `bin/check-port` (no argument) and
  `bin/check-port notaport` each exit `2`.
- **daemon-up cross-process collision regression** (the reported bug; also
  covers the "PID file lost / orphaned-alive on the port" edge case, which from
  `daemon-up`'s vantage is the same PID-absent-while-port-held state) — start a
  foreign python listener on `18099`, then run
  `bin/daemon-up --port 18099 collision-test -- sleep 60`. Assert: exit code is
  the distinct collision code `3`, **no** `var/run/collision-test.pid` written,
  **no** `sleep` child spawned, and the lock directory cleaned. Kill the
  listener.
- **daemon-up idempotency preserved with `--port`** —
  `bin/daemon-up --port 18098 idem-test -- sleep 60` on a free port succeeds and
  writes a live PID; a second identical call exits `0` with the **same** PID (no
  duplicate spawn). Then `bin/daemon-down idem-test`.
- **daemon-up concurrent-up exit-0 contract with `--port`** — two racing
  `bin/daemon-up --port 18098 idem-test -- sleep 60` invocations both exit `0`
  with exactly one process running. Cleanup.
- **daemon-check tri-state** — for `idem-test`: stopped + port free ⇒
  `bin/daemon-check --port 18098 idem-test` exits `1`; running (our PID) ⇒ `0`;
  stopped + a foreign listener on `18098` ⇒ `2`. Cleanup.
- **daemon-check `--port`-less backward compatibility** — for a stopped service
  with a foreign listener on its port, `bin/daemon-check idem-test` (no
  `--port`) still exits `1`, never `2`; running ⇒ `0`. Confirms the new exit `2`
  is gated on `--port` and the `queue-worker-check` path is unchanged.
- **daemon-http-check: not running** — with nothing bound on `18097`,
  `bin/daemon-http-check svc 18097 http://localhost:18097/healthz` exits `1`.
- **daemon-http-check: port held / unhealthy** — with the python foreign
  listener bound on `18097` (a raw socket, so `curl -sf` fails), the same
  invocation exits `2` and logs "bound but not responding as `svc`". Kill the
  listener.
- **daemon-http-check: invalid args** — `bin/daemon-http-check svc` (too few
  arguments) and `bin/daemon-http-check svc notaport http://localhost/healthz`
  (non-numeric port) each exit `3`, distinct from the `2` conflict state. The
  `0` (healthy) path is exercised through the `rest-server-check` wrapper in the
  existing `test_daemon_wrapper` lifecycle assertion, which boots a real server.
- **daemon-http-check: bounded probe (no hang)** — a listener that accepts the
  connection but never replies is bound on `18097`; the call, wrapped in
  `timeout`, must return exit `2` (bounded by `curl --max-time`) rather than be
  killed by the guard (exit `124`). This is the regression test for the
  unbounded-`curl` hang.
- **HTTP `*-check` wrapper failure path** — with `rest-server` **down** (PID
  file absent — asserted as the precondition) and a foreign listener bound on
  its `$PORT`, `bin/rest-server-check` (now a thin wrapper delegating to
  `daemon-http-check`) exits `2` and logs "held by another process"; with
  nothing on the port it exits `1`. The foreign listener is bound on the
  resolved `$PORT` (`8081` under `.env.test`) for the duration of the assertion
  and torn down after.

The existing `test_daemon_wrapper` lifecycle assertions for `rest-server` and
`queue-worker` must continue to pass. The existing `daemon-status` `stopped`
assertion for `rest-server` still holds because the harness tears `rest-server`
down just before it, leaving `8081` free at that point, so step 4's
`daemon-check --port "${PORT:-8080}"` rewiring resolves `$PORT`=`8081` to a
`stopped` (not `conflict`) line; the test should defensively confirm `8081` is
free as its precondition — mirroring the foreign-listener teardown discipline
described above. Run via `nix develop -c bin/scripts-tests` and
`nix develop -c bin/test check` for the ktlint/Kotlin gate.

## Implementation Plan

1. **Create `bin/check-port`.** Implement the `/dev/tcp` probe, argument
   validation, `help()`, and exit codes `0` / `1` / `2`. Verify:
   `nix develop -c bin/check-port 18099` exits 1; with a `python3` listener
   bound to 18099 it exits 0; `nix develop -c bin/check-port xyz` exits 2.
2. **Add `--port` preflight to `bin/daemon-up`.** Add the explicit two-token
   `-p` / `--port` case (`shift 2`) with numeric validation, update `help()`
   (synopsis + `Options` + the new exit code `3`), and insert the `check-port`
   `fatal -s 3` between stale-PID cleanup and spawn. Verify: with a foreign
   listener on a port,
   `nix develop -c bin/daemon-up --port <held> t -- sleep 60` exits `3` with no
   `var/run/t.pid`; `--port <free>` boots; omitting `--port` still boots.
3. **Add `--port` tri-state to `bin/daemon-check`.** Add the two-token `--port`
   case, the exit-`2` branch, and the `help()` updates. Verify: stopped+free ⇒
   1, running ⇒ 0, stopped+held ⇒ 2; and without `--port`, stopped+held ⇒ 1
   (backward-compatible).
4. **Make `bin/daemon-status` data-driven.** Replace the hard-coded
   `rest-server` branch with an ordered `SERVICES` array plus an associative
   `SERVICE_PORTS` map (`SERVICE_PORTS[rest-server]="${PORT:-8080}"`); keep
   `postgres` as its named `postmaster.pid` branch and route every other service
   through one generic path that passes `--port` only when the service has a map
   entry and maps `0`/`1`/`2`/other to `running`/`stopped`/`conflict`/`unknown`.
   Verify: `nix develop -c bin/daemon-status` prints `conflict` for
   `rest-server` when its port (`$PORT`) is foreign-held while its PID file is
   absent, `stopped` when the port is free, and `running`/`stopped` only for
   `queue-worker`.
5. **Pass `--port` from the port-bound `-up` wrappers** — `rest-server-up`
   (`--port "${PORT:-8080}"`), `admin-server-up`
   (`--port "${ADMIN_SERVER_PORT:-8081}"`), `public-web-up`
   (`--port "${PUBLIC_WEB_PORT:-8082}"`). The default-guarded form is required:
   `ADMIN_SERVER_PORT` / `PUBLIC_WEB_PORT` are absent from `.env` and
   `.env.test`, so a bare `$ADMIN_SERVER_PORT` / `$PUBLIC_WEB_PORT` aborts under
   `bin/common`'s `set -u` before `daemon-up` runs. `queue-worker-up` is
   unchanged. Verify: each `*-up` still boots a healthy service when its port is
   free.
6. **Create `bin/daemon-http-check`.** Implement the curl→`check-port` tri-state
   for `<service-label> <port> <health-url>`, `help()`, and exit codes
   `0`/`1`/`2`/`3` (`3` = invalid args). Verify: against a free port
   `nix develop -c bin/daemon-http-check svc <free> http://localhost:<free>/healthz`
   exits `1`; with a `python3` listener on that port it exits `2`; a non-numeric
   port exits `3`.
7. **Make the HTTP `*-check` scripts thin wrappers** — `rest-server-check`,
   `admin-server-check`, `public-web-check` source `bin/common` and delegate to
   `daemon-http-check` with their label, default-guarded port (`${PORT:-8080}` /
   `${ADMIN_SERVER_PORT:-8081}` / `${PUBLIC_WEB_PORT:-8082}`), and
   `http://localhost:<port>/healthz`. Verify: each exits `2` with the "held by
   another process" message when its port is foreign-held and the daemon is
   down, `1` when the port is free, and `0` when the daemon is healthy.
8. **Add tests to `bin/scripts-tests`** per the Tests section, including the
   cross-process collision regression, the direct `daemon-http-check`
   `1`/`2`/`3` assertions, and a `python3` foreign-listener helper with cleanup
   traps. Verify: `nix develop -c bin/scripts-tests` passes;
   `nix develop -c bin/test check` passes.

## Files Modified

- `bin/check-port` (new)
- `bin/daemon-http-check` (new)
- `bin/find-free-port` (new, test-only)
- `bin/test-tcp-listener` (new, test-only)
- `bin/daemon-up`
- `bin/daemon-check`
- `bin/daemon-status`
- `bin/rest-server-up`
- `bin/admin-server-up`
- `bin/public-web-up`
- `bin/rest-server-check`
- `bin/admin-server-check`
- `bin/public-web-check`
- `bin/functions` (adds the shared `validate_port` helper used by all
  port-validating scripts)
- `bin/scripts-tests`
- `.env.test` (test `PORT` made overridable so the harness binds a free port)
