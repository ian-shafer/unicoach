# RFC 131: The test harness's free port must survive into its children

## Problem

`bin/scripts-tests` boots this checkout's real `rest-server` to exercise the
daemon wrappers. It is careful not to hardcode a port — it picks a free one at
startup:

```sh
export PORT="$("$(dirname "$0")/find-free-port")"
```

and `.env.test` states the intent plainly:

```sh
# Tests must never depend on a hardcoded port being free. The harness
# (bin/scripts-tests) exports a free PORT before sourcing this file; 8081 is only
# a fallback for tooling that sources .env.test without first picking a port.
PORT="${PORT:-8081}"
```

**Neither works.** The mechanism is dead for every child process, and has been
since it was written. A developer with a `rest-server` running in any checkout
fails the pre-commit hook:

```
==== Testing Wrapper: rest-server ====
[FATAL] Cannot start [rest-server]: port [127.0.0.1:8080] is already held by
        another process.
Test suite failed (Status: 3)
Pre-commit checks failed.
```

Note the port: **8080**, the dev port — neither the free port `find-free-port`
chose (18000+) nor `.env.test`'s 8081 fallback.

### Why

`bin/common`'s `load_env_layers` sources the layers under `set -a`, base first:

```sh
source "$BASE_ENV_FILE"        # .env      -> PORT=8080   (unconditional)
source .env.dev
source .env.test               # PORT="${PORT:-8081}"     (PORT is now set)
```

`.env` assigns `PORT=8080` **unconditionally**. So in any process that sources
`bin/common`, the inherited `PORT` is destroyed by the base layer before
`.env.test` is reached — and `.env.test`'s `${PORT:-8081}` then sees `8080`,
which is set and non-empty, so the fallback never fires either. The parameter
default is unreachable code.

`bin/scripts-tests` gets away with it _in its own process_, because it exports
`PORT` after `functions` but the value is re-clobbered when it sources `common`
two lines later. Every child it drives — `bin/rest-server-up`, `bin/daemon-*`,
every `bin/` SUT — sources `common` and lands on 8080. That is the process that
actually binds the socket.

Demonstrated directly, no test harness involved:

```
$ PORT=18000 ENV_FILES=".env.dev:.env.test" bash -c 'source bin/common; echo $PORT'
8080
```

So the harness's isolation is exactly as strong as its weakest layer, and the
weakest layer is the one that cannot be made conditional.

### Why this matters beyond one annoyance

`bin/test check` is the pre-commit hook — the gate on everything reaching
`main`. Today that gate is only passable when no other checkout is serving on
8080. The failure is also maximally misleading: it arrives _after_ Gradle has
gone green, inside a shell harness, naming a port the developer never
configured, in a checkout that is not the one at fault. The RFC 129 run lost a
full hook cycle to it.

The repo already isolates the other shared resource this way: `.env.test` gives
each worktree its own database
(`POSTGRES_DB=unicoach-test-$(basename
"$PROJECT_ROOT")`). The port is the
remaining machine-global resource in an otherwise per-checkout test stack.

## Detailed Design

Give the harness a channel the base layer does not own.

The variable is named `REST_SERVER_TEST_PORT`, not a generic `TEST_PORT`. It
overrides exactly one service's port — `.env`'s `PORT`/`SERVER_PORT` pair is
rest-server's — and naming the service keeps the door open for a sibling
(`QUEUE_WORKER_TEST_PORT`) without either one having to be renamed or a generic
name having to be disambiguated by comment.

`.env` keeps `PORT=8080` unconditional. `.env.test` — the last layer, and the
file that already declares this intent — derives the test port from an exported
`REST_SERVER_TEST_PORT` when one is present:

```sh
PORT="${REST_SERVER_TEST_PORT:-8081}"
SERVER_PORT=$PORT
```

`bin/scripts-tests` exports `REST_SERVER_TEST_PORT` rather than `PORT`, and
derives it from the checkout rather than scanning from a fixed base (see
_Choosing the port_):

```sh
export REST_SERVER_TEST_PORT="$("$(dirname "$0")/checkout-port")"
```

`.env` does not mention `REST_SERVER_TEST_PORT`, so nothing clobbers it. It is
exported, so every child inherits it, and every child's `.env.test` re-derives
the same `PORT` — the whole point, since `bin/rest-server-up` must bind the port
that `bin/rest-server-check` later probes.

Verified before writing this RFC, against the real layering:

```
$ REST_SERVER_TEST_PORT=18000 ENV_FILES=".env.dev:.env.test" bash -c 'source bin/common; echo $PORT'
18000
$ ENV_FILES=".env.dev:.env.test" bash -c 'source bin/common; echo $PORT'
8081
```

The second line is the fallback finally becoming reachable: tooling that sources
`.env.test` without picking a port gets 8081, which is what the comment always
claimed and never delivered.

### Choosing the port: per-checkout, not a scan from a fixed base

`find-free-port` scans upward from a fixed base (18000) for the first free port,
so today's value is already dynamic — but a scan from a _fixed base_ is the
worst available choice for concurrent callers. Every racer starts at the same
number and deterministically picks the same first-free port, so the scan
maximally **correlates** choosers rather than spreading them. And the race
window is wide: `scripts-tests` picks the port at startup but does not bind it
until `test_daemon_wrapper` runs, minutes of assertions later. Two worktrees
running `bin/test check` together will both be handed 18000.

Randomising the base decorrelates them, and would be a real improvement. It is
still the second-best answer, for two reasons: it only makes collision
_unlikely_ rather than impossible, and a random port is **unattributable** — a
leaked daemon or a wedged socket on port 24713 cannot be traced back to the
checkout that created it, which is precisely the debugging position this RFC
exists to get us out of.

Derive it from the checkout instead. That is already this repo's idiom for
per-checkout test resources — `.env.test` namespaces the database
(`POSTGRES_DB=unicoach-test-$(basename "$PROJECT_ROOT")`) and `bin/ios-sim` tags
simulator devices (`CHECKOUT="$(basename "$PROJECT_ROOT")"`, RFC 126). The port
is the last shared resource in an otherwise per-checkout test stack, and it
should be namespaced the same way, by the same key:

```sh
port=$(( 18000 + $(printf '%s' "$(basename "$PROJECT_ROOT")" | cksum | cut -d' ' -f1) % 10000 ))
```

Two concurrent worktree runs then **provably** never collide — different
checkout names, different ports, regardless of timing. The port is reproducible,
so the same checkout always uses the same one, and `lsof` on it names the
culprit. Measured against the nine checkouts live on this machine today: nine
distinct ports.

Two residual risks remain, and both are handled by falling back to the existing
scan rather than by widening the hash:

- **Hash collision between two live checkouts.** 10,000 slots; three collisions
  across 200 plausible checkout names, and only _simultaneously running_
  checkouts matter.
- **An unrelated foreign process** already on the derived port.

So the derived value is a _preferred base_, not a guarantee: check it with
`bin/check-port` and, if taken, hand it to `find-free-port` as the scan base.
Determinism in the overwhelmingly common case, liveness always. This also keeps
`find-free-port` unchanged and still useful for callers that genuinely want any
free port.

### Rejected: make `.env`'s `PORT` conditional

`PORT="${PORT:-8080}"` in the base is the smaller diff and the wrong change.
`.env` is the base for **every** environment including cloud deploys, and
CONFIGURATION.md's model is that a key omitted from a cloud `.env.<env>` must be
a laptop-time fatal rather than silently inheriting a default. Making the base
permeable to whatever `PORT` happens to be exported in a deploy shell trades a
local test annoyance for an ambient-environment leak on the deploy path. The
base layer should stay the one thing that is not negotiable.

### Rejected: make `load_env_layers` respect already-set variables

Standard dotenv "don't override" semantics would fix this and break the layering
contract wholesale: the entire point of `.env -> .env.dev -> .env.test` is that
later layers _do_ override earlier ones. Inverting that for ambient environment
only, while keeping it for files, is a second set of rules to hold in your head
at every call site.

### Rejected: have `.env.test` call `find-free-port` itself

`.env.test` is sourced by every child independently, so each would pick its own
free port: `rest-server-up` binds one, `rest-server-check` probes another. The
port must be chosen **once** and inherited, which is what an exported variable
is for.

### The fallback stops being a trap

`8081` is `ADMIN_WEB_PORT`. As an unreachable default that was harmless; once
reachable it is a real (if unlikely) collision with a running `admin-web`. Move
the fallback to `18080`, outside every daemon port in `.env`, and say why in the
comment. Nothing depends on the literal — it was dead code until now.

## Files Modified

- `.env.test` — derive `PORT` from `REST_SERVER_TEST_PORT`; move the fallback
  off 8081; rewrite the comment to describe the mechanism that now exists.
- `bin/checkout-port` (new) — derive the per-checkout preferred port, verify it
  with `check-port`, fall back to `find-free-port` from that base.
- `bin/scripts-tests` — export `REST_SERVER_TEST_PORT` instead of `PORT`; update
  the bootstrap-ordering comment, which currently explains an ordering
  requirement that is no longer the reason this works.
- `bin/scripts-tests` — a regression case (see Tests).
- `CONFIGURATION.md` — document `REST_SERVER_TEST_PORT` in the five-role model
  as a harness-only override that the base layer deliberately does not own.

No JVM code. The Kotlin e2e tests already bind `port = 0` (an ephemeral port
read back from Netty) and were never affected.

## Implementation Plan

1. `.env.test`: `PORT="${REST_SERVER_TEST_PORT:-18080}"`, comment rewritten.
2. `bin/scripts-tests`: export `REST_SERVER_TEST_PORT`; keep the explicit
   empty-value guard (it must now name `REST_SERVER_TEST_PORT`), and update the
   comment above it.
3. Add the regression case to `bin/scripts-tests`.
4. `CONFIGURATION.md`: one paragraph.
5. Prove it end to end: start a `rest-server` on 8080 in the original checkout,
   then run the full hook in the worktree and watch it pass.

## Tests

The bug is that a value does not survive a process boundary, so the test must
cross one. A unit assertion inside `scripts-tests` would pass today.

- **New case in `bin/scripts-tests`**: assert that a child which sources
  `bin/common` resolves `PORT` to the harness's `REST_SERVER_TEST_PORT` —
  `[ "$(bash -c 'source bin/common; echo $PORT')" = "$REST_SERVER_TEST_PORT" ]`.
  This is the exact invariant that was silently false, and it fails against
  `main`.
- **The acceptance criterion, executed**: hold 8080 with a real `rest-server` in
  a second checkout and run `nix develop -c bin/test check` to completion in
  this one. That is the scenario that failed the RFC 129 hook; it must now pass,
  and the held daemon must still be running afterwards.
- **Determinism and isolation**: `bin/checkout-port` returns the same value on
  repeated calls in one checkout, and different values in two different
  checkouts. Cover the fallback by holding the derived port and asserting the
  next call returns a different, free one.
- **No regression for dev**: `bin/rest-server-up` outside the harness must still
  bind 8080 — confirm `PORT` is unchanged when `REST_SERVER_TEST_PORT` is
  absent.
- **Full forced suite**: `nix develop -c bin/test -f`, executed count reported.
