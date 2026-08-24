# RFC 132: One per-checkout block of test ports, for every service

## Problem

RFC 131 gave each checkout a deterministic test port and stopped `.env`
clobbering it. It fixed one service, on one harness, and the parts it did not
reach are now visibly wrong in three ways.

### 1. `rest-server`'s port became the origin of the address space

The test harness needs **five** ports, not one: `rest-server`'s, plus the four
scratch ports `scripts-tests` uses to exercise `check-port`, `daemon-http-check`
and the kill paths. RFC 131 derived only `REST_SERVER_TEST_PORT` and scanned
upward from it for the rest:

```sh
PORT_FREE="$(bin/find-free-port $((REST_SERVER_TEST_PORT + 1)))"
PORT_IDEM="$(bin/find-free-port $((PORT_FREE + 1)))"
...
```

There is no answer to "why is the rest-server port special?", because it isn't.

**And the scan bleeds into the neighbours.** Checkout X occupies X+1..X+4. If
another checkout's derived base falls in that window, `find-free-port` hands X a
port that checkout Y has already derived and is about to bind — and
`find-free-port` only ever claims the port is free _at the instant of printing_,
as its own help text says. Y's window between deriving and binding is minutes
wide.

Measured across the nine checkouts live on this machine: closest pair 64 apart.
That is luck, not design:

| live checkouts | P(some pair within 5 ports) |
| -------------- | --------------------------- |
| 9              | 3.5%                        |
| 20             | 17.3%                       |

RFC 131 replaced a _deterministic_ collision with a _probabilistic_ one. An
improvement, not a fix.

### 2. Only one of the three port-binding services is overridden at all

`.env` defines three service ports: `PORT` (rest-server), `ADMIN_WEB_PORT`
(8081), `PUBLIC_WEB_PORT` (8082). `.env.test` and `.env.fuzz` override **only
`PORT`**. The other two are inherited from `.env` unchanged, so any test or fuzz
run that boots `admin-web` or `public-web` binds the developer's **dev** ports.

That is the literal answer to "why is rest-server special?": at the config layer
it is special only because it is the one anybody bothered to override.
`bin/scripts-tests` already drives `admin-web-up` and `public-web-up` (today
only for argument parsing, which does not bind) — nothing but that accident
stands between this and a repeat of RFC 129.

### 3. RFC 131 introduced a collision on the fuzz path

`bin/test-fuzz` claims its port with a bare `find-free-port`, whose default scan
base is **18000** — now exactly the base of `checkout-port`'s derived range. So
a fuzz run can be handed a port sitting inside some checkout's test block, and
the fuzz harness has no idea the block exists.

`.env.fuzz` is otherwise the one place that already had this right: its
`PORT=${FUZZ_PORT:-8082}` indirection is the same mechanism RFC 131 introduced
for `.env.test`, and its comment describes the clobbering correctly. It was the
precedent. But its fallback, 8082, is `PUBLIC_WEB_PORT` — and unlike
`.env.test`'s old 8081, `FUZZ_PORT` can genuinely be unset, so that fallback is
**reachable**.

## Detailed Design

Derive a **block**, not a port, and let every service and every harness take a
named offset inside it.

```sh
BASE_TEST_PORT = 18000 + (cksum(basename $PROJECT_ROOT) % 1900) * 16
```

Aligned blocks cannot partially overlap: two checkouts get either entirely
disjoint ranges or the identical range. The bleed is gone by construction — no
allocation ever scans into territory another checkout has derived but not yet
bound.

### The offset registry

One table, in `bin/checkout-port`, is the authority. Ports are _assigned_, never
scanned for and never defined relative to each other:

| offset   | consumer                                      |
| -------- | --------------------------------------------- |
| +0       | `rest-server` — test (`PORT` / `SERVER_PORT`) |
| +1       | `admin-web` — test (`ADMIN_WEB_PORT`)         |
| +2       | `public-web` — test (`PUBLIC_WEB_PORT`)       |
| +3..+6   | `scripts-tests` scratch ports                 |
| +7       | `rest-server` — fuzz (`PORT` / `SERVER_PORT`) |
| +8       | `admin-web` — fuzz (`ADMIN_WEB_PORT`)         |
| +9       | `public-web` — fuzz (`PUBLIC_WEB_PORT`)       |
| +10..+15 | reserved                                      |

`rest-server` takes +0 because it was allocated first. Nothing about it is
privileged: the registry assigns ports, and a new consumer takes the next free
offset rather than scanning or hanging itself off a neighbour.

**Test and fuzz get disjoint halves of the same block** rather than separate
blocks. They use different databases (`unicoach-test-<checkout>` vs
`unicoach-fuzz-<checkout>`), so they may legitimately run at the same time in
one checkout; disjoint offsets make that safe. One derivation keeps a checkout's
whole footprint inspectable — `lsof -i :25904-25919` shows everything it owns.

`queue-worker` gets no offset: it binds no port.

### Both harnesses export the same variable

`REST_SERVER_TEST_PORT` and `FUZZ_PORT` both retire. `bin/scripts-tests` and
`bin/test-fuzz` each export `BASE_TEST_PORT` from `bin/checkout-port`, and the
delta files derive every service port from it:

```sh
# .env.test
PORT="${BASE_TEST_PORT:-18080}"
ADMIN_WEB_PORT=$(( ${BASE_TEST_PORT:-18080} + 1 ))
PUBLIC_WEB_PORT=$(( ${BASE_TEST_PORT:-18080} + 2 ))
```

That is RFC 131's mechanism unchanged — a key `.env` does not own, re-derived by
the last layer in every child. It now covers every service rather than one.

`bin/test-fuzz`'s `find-free-port` call goes away with it, taking defect 3 with
it. Its TOCTOU guard (probe the port; fatal if taken between selection and use)
stays: `checkout-port` has the same instant-of-printing caveat.

### Range

18000 is the floor, clear of every daemon port in `.env`. The ceiling is 48400:
macOS begins its ephemeral range at 49152 (`net.inet.ip.portrange.first`), and a
test port drawn from there can be stolen by any outbound socket on the machine.
1900 blocks of 16 fit between them.

### Fallback: advance by block, never by port

The derived block is a preference, not a guarantee — a hash collision between
two simultaneously-running checkouts, or a foreign process squatting inside the
range. `bin/checkout-port` verifies **every port in the block** with
`bin/check-port` and, if any is held, advances to the next block and re-checks.

Advancing by block rather than by port is the whole point: a port-wise fallback
would reintroduce the straddling this RFC exists to remove.

### Rejected: a separate derived block for fuzz

Two independent derivations per checkout double the collision surface and make a
checkout's footprint two disjoint ranges to look up instead of one. Halving one
block costs nothing — six offsets are still reserved.

### Rejected: per-consumer derived ports

Deriving each port independently (`cksum(checkout + consumer)`) removes the
privilege too, but scatters a checkout's ports across the range, so a collision
becomes per-port rather than per-checkout and there is nothing to reason about
as a unit.

## Files Modified

- `bin/checkout-port` — print the block base; verify the whole block; advance by
  block; carry the offset registry in its help text.
- `bin/scripts-tests` — export `BASE_TEST_PORT`; scratch ports become `+3..+6`
  instead of four `find-free-port` scans.
- `bin/test-fuzz` — export `BASE_TEST_PORT` from `checkout-port`; delete the
  `find-free-port` claim and `FUZZ_PORT`; keep the TOCTOU guard.
- `.env.test` — all three service ports from `BASE_TEST_PORT` (+0, +1, +2).
- `.env.fuzz` — all three from `BASE_TEST_PORT` (+7, +8, +9); the reachable 8082
  fallback dies with `FUZZ_PORT`.
- `CONFIGURATION.md` — `BASE_TEST_PORT` replaces `REST_SERVER_TEST_PORT` and
  `FUZZ_PORT`.

No JVM code. The Kotlin e2e tests bind `port = 0` and are unaffected.

Watch the `set -a` sourcing in the delta files: every assignment is exported, so
a scratch variable holding the base would be exported too. Repeat
`${BASE_TEST_PORT:-18080}` or unset the helper explicitly.

## Implementation Plan

1. `bin/checkout-port`: block derivation, whole-block verification, block-wise
   advance, offset registry in help.
2. `.env.test` and `.env.fuzz`: derive all three service ports.
3. `bin/scripts-tests`: export `BASE_TEST_PORT`; scratch ports to `+3..+6`.
4. `bin/test-fuzz`: export `BASE_TEST_PORT`; drop `find-free-port`/`FUZZ_PORT`.
5. `CONFIGURATION.md`.
6. Confirm no `REST_SERVER_TEST_PORT` or `FUZZ_PORT` reference survives.

## Tests

- **Block disjointness, the property this RFC adds**: for two different checkout
  names, assert the derived blocks do not overlap — not merely that the bases
  differ. This assertion fails against RFC 131's design.
- **Every service port lands in the block, and test and fuzz never share one**:
  resolve all three ports under both layerings and assert six distinct values,
  all within `BASE_TEST_PORT..BASE_TEST_PORT+15`. This is the assertion that
  fails today for `ADMIN_WEB_PORT`/`PUBLIC_WEB_PORT`, which currently resolve to
  the dev 8081/8082 under both.
- **Determinism**: repeated calls in one checkout return the same base.
- **Block-wise fallback**: hold a port in the _middle_ of the derived block
  (offset +12, which no consumer uses) and assert `checkout-port` returns a
  different block whose every port is free — not merely a different port. Under
  RFC 131's port-wise fallback this case cannot arise; it is the regression that
  proves the fallback advances by block.
- **Ceiling**: the highest derivable block plus 15 stays below 49152.
- **The RFC 131 invariant still holds**: a child sourcing `bin/common` resolves
  `PORT` to `BASE_TEST_PORT` under test layering.
- **The fuzz harness no longer scans**: assert `bin/test-fuzz` contains no
  `find-free-port` call and that fuzz layering resolves `PORT` to
  `BASE_TEST_PORT + 7`.
- **Acceptance, executed**: with a `rest-server` holding 8080 in another
  checkout, `nix develop -c bin/test check` runs to completion and that daemon
  is still running afterwards.
- **Full forced suite**: `nix develop -c bin/test -f`, executed count reported.
