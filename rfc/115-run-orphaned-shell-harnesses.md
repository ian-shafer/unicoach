# RFC 115: Run the orphaned shell harnesses from `bin/test check`

## Executive Summary

Three shell test harnesses are executed by nothing:

| Harness                | Tests | Runtime | Needs                                               |
| ---------------------- | ----- | ------- | --------------------------------------------------- |
| `bin/scripts-tests`    | 191   | 63s     | shared cluster + **built** rest-server/queue-worker |
| `bin/q-scripts-tests`  | 32    | 12s     | shared cluster                                      |
| `bin/db-scripts-tests` | 31    | 15s     | its own private, throwaway cluster                  |

`bin/test` runs Gradle plus the `bin/db-tests` aggregator; these three are in
neither. Nothing else runs them: every other mention of them in `bin/` is a
comment. That is not theoretical debt — RFC 114 and the `gen-deployed-env` fix
each landed a defect that `bin/scripts-tests` was already written to catch: a
fixture that had been failing on `main` since 2026-08-09, and a security-shaped
leak assertion passing **vacuously** on empty output. Both sat unread for eight
days because running the harness is a thing a human has to remember.

This RFC adds `bin/shell-tests`, an aggregator mirroring `bin/db-tests`, and
invokes it from `bin/test` **only under the `check` keyword** — the form
`bin/pre-commit` uses. Anything reaching `main` runs all 257 shell assertions
(the 254 above plus the three this RFC adds); the bare `bin/test` tight loop
stays as fast as it is today.

## Detailed Design

### Why `check` and not every `bin/test`

The measured cost is ~95-105s added to `check`: `installDist` for the two
daemons `scripts-tests` requires, then 63 + 12 + 15. Two end-to-end runs on a
warm tree put `bin/test check` at 1m49s and 3m28s against a bare `bin/test` of
15-17s; the spread between them is Gradle's, not the harnesses' — the shell
portion is stable, and it is the only part this RFC adds. Attaching this to
every invocation would make the command CLAUDE.md tells developers to run by
default roughly 7x slower, for assertions that a Kotlin edit cannot affect.

`check` already means "the gate" in this repo: `bin/pre-commit` runs
`bin/test check`, and CLAUDE.md's rule is that **the hook protects `main`, not
every commit**. Binding the shell harnesses to that keyword puts them exactly
where the existing invariant already lives, and costs nothing to the loop a
developer runs fifty times an hour.

### Why not skip-if-unchanged

The obvious alternative — hash the harnesses' inputs, skip when they have not
changed — was designed and rejected on evidence. `bin/scripts-tests` asserts
over `infra/files/unicoach-*.service`, runs `tofu validate` against `infra/`,
greps `bin/deploy` and `bin/functions` for deploy-managed paths, and reads
`.env.prod`. Its true input set is `bin/** + infra/** + .env*`, and a stamp
keyed on `bin/**` would silently skip a run that an `infra/` change had just
invalidated. A review that quietly runs fewer assertions looks exactly like a
clean one; that is the one failure mode worth engineering against, and a second
hand-maintained cache alongside Gradle's is a poor way to buy 95s.

Gradle's incrementality is trustworthy because Gradle derives the input set.
Nothing here derives it, so this RFC does not pretend to.

### `bin/shell-tests`

A thin aggregator, deliberately shaped like `bin/db-tests`:

```
bin/shell-tests
  bin/q-scripts-tests     # 12s — cheapest first: fastest signal on a broken tree
  bin/db-scripts-tests    # 15s — private cluster, independent of the other two
  bin/scripts-tests       # 63s — most expensive last
```

One wrinkle the aggregator absorbs: `db-scripts-tests` builds a private env file
and sets `ENV_FILE`, which `bin/common` rejects as mutually exclusive with the
`ENV_FILES` that `bin/test` exports. Run naively it fatals ~17s in, so the
aggregator invokes that one harness as `env -u ENV_FILES bin/db-scripts-tests`.
The fix belongs here, where the environment is composed, rather than in the
harness — which keeps this RFC's promise not to modify the three harnesses, and
is honest about the fact that its private cluster is deliberately unrelated to
the shared stack the rest of the run points at.

It **owns no Postgres lifecycle**, matching `db-tests`: the caller provides a
live shared cluster. `db-scripts-tests` provisions and tears down its own
private cluster regardless, and `q-scripts-tests`/`scripts-tests` self-provision
against the shared one.

Ordering is cheapest-first because all three abort on their first failure, so
the ordering only ever changes how long a developer waits to learn the tree is
broken.

### Where it goes in `bin/test`

`q-scripts-tests` and `scripts-tests` both run `bin/db-reset` against
`unicoach-test-<worktree>` — the **same** database the Gradle suite then uses —
and `scripts-tests` additionally kills this worktree's `rest-server` and
`queue-worker` daemons via its EXIT trap. They therefore cannot overlap the
Gradle run, and must not run after the reset that prepares its database:

```
postgres-up
shell-tests          # only when TASK=check; leaves the test DB dirty
db-reset             # existing line, now also the cleanup after shell-tests
db-tests
gradlew …
```

Placing it before the existing `db-reset` means the reset that already exists
absorbs the damage. No new cleanup step is introduced.

### Why the daemon teardown is safe

Making a harness that stops `rest-server` and `queue-worker` part of the commit
gate reads alarming, so state the scoping explicitly: daemon identity is
**per-checkout**, not global. `bin/common` derives `PROJECT_ROOT` from its own
location, and every daemon's identity hangs off it — `var/run/<name>.pid`,
`var/log/<name>.log`, `var/run/<name>.daemon.lock`. A harness running in one
worktree therefore cannot see, signal, or reap a daemon started in another. The
ports are already isolated independently: the harness picks its own via
`bin/find-free-port` before sourcing `common`.

So the daemons this suite tears down are, by construction, the ones this
checkout started. The case not covered by that argument is a developer
committing from a checkout where their own dev stack is running: the hook will
stop those two daemons. That is accepted rather than engineered around — the
alternative is a second identity scheme for the daemon scripts, carved around
the deliberately shared Postgres cluster, to protect a stack that `bin/*-up`
restores in seconds. CLAUDE.md notes it so it is not a surprise.

### The build dependency

`scripts-tests` fatals unless `rest-server` and `queue-worker` `installDist`
artifacts exist; it checks existence, never freshness, so a stale August binary
satisfies it silently. On a clean checkout it simply fails. `bin/test` therefore
runs `:rest-server:installDist :queue-worker:installDist` before `shell-tests`,
inside the `check` branch only. Gradle caches it: 28s cold, no-op warm.

Building rather than skipping also fixes the staleness: the harness exercises
the `bin/*-up` wrapper scripts against a real binary, and a binary from a month
ago is not the one being shipped.

### The accepted cost

Bare `bin/test` no longer runs everything. A developer editing `bin/` can see
green locally and then fail at the hook. That is the correct direction — the
failure still precedes `main` — but it is a surprise, so the non-`check` path
prints one line naming what it did not run and how to run it. Silence would make
`bin/test` quietly weaker than it looks.

## Files Modified

| File                | Change                                                                                                                                                                             |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `bin/shell-tests`   | **New.** Aggregator over the three harnesses, sequential, abort on first failure. Sources `bin/common`.                                                                            |
| `bin/test`          | In the `check` path only: `installDist` the two daemons, then run `bin/shell-tests`, before the existing `db-reset`. Non-`check` path prints a one-line notice. Help text updated. |
| `bin/scripts-tests` | One assertion: `bin/shell-tests` names every `bin/*-scripts-tests` harness that exists (the anti-orphan guard).                                                                    |
| `CLAUDE.md`         | The "Running tests" section states that `check` additionally runs the shell harnesses, what that costs, and that they stop this checkout's `rest-server`/`queue-worker`.           |

Not modified: `bin/q-scripts-tests`, `bin/db-scripts-tests`, `bin/db-tests`,
`bin/pre-commit` (it already calls `bin/test check`, so it inherits this with no
edit).

## Implementation Plan

1. Write `bin/shell-tests` in the shape of `bin/db-tests` (same `ENV_FILES`
   prelude, same "owns no Postgres lifecycle" contract, `-h` per the repo's
   getopts convention).
2. In `bin/test`, add the `check`-only branch: `installDist` both daemons, then
   `bin/shell-tests`, placed above the existing `postgres-up`/`db-reset` pair as
   described. Add the non-`check` notice and update `help()`.
3. Add the anti-orphan assertion to `bin/scripts-tests`.
4. Update CLAUDE.md's "Running tests" section.
5. Verify: `bin/test check` green end to end and `bin/test` unchanged in
   duration; time both and record real numbers.

## Tests

The anti-orphan guard is the only new assertion, and it is the one that keeps
this RFC from decaying the way it found the repo: glob `bin/*-scripts-tests`,
assert each name appears in `bin/shell-tests`, and fail naming any that does
not. `ios-scripts-tests` is the one deliberate exclusion — it runs under system
Xcode and refuses inside the dev shell (RFC 114) — so the guard carves it out by
name, with the reason, rather than by a pattern that would silently swallow a
future omission.

This mirrors the accounting invariant the review-rule corpus already uses: a
discovered harness that is not in the manifest halts loudly, because a suite
that quietly runs fewer harnesses looks exactly like a passing one.

Verification of the wiring itself is by execution, not by new assertions:
`bin/test check` must run all three harnesses and report 194 / 32 / 31, and bare
`bin/test` must not run any of them. Both are timed and the real numbers
recorded in the run report.

The 194 is 191 plus this RFC's own three guard assertions, one per harness the
glob discovers. Note the glob is `bin/*scripts-tests`, **not**
`bin/*-scripts-tests` as first drafted: the hyphenated form does not match
`bin/scripts-tests` itself, so the guard would have covered the two cheap
harnesses and silently exempted the 63s one it most exists to protect — the
precise failure this section argues against, reintroduced by a typo.
