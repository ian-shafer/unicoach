# RFC 259: The gate runs what is independent at the same time

## Summary

Two changes to how the full commit gate and `bin/test` spend wall-clock time.
Neither removes a test, narrows a scope, or replaces Postgres.

1. **`bin/shell-tests` runs its harnesses in three lanes at once**, split by
   what each harness shares: a private cluster, nothing, or this checkout's test
   database and daemons. Inside a lane, the order and stop-at-first-failure are
   unchanged.
2. **`bin/test` gives each Gradle module its own test database**, copied from
   the freshly migrated one with `CREATE DATABASE … TEMPLATE`, and runs the
   module test tasks in parallel. The copies are dropped when `bin/test` exits.

Making 2 safe meant fixing one test that leaked a connection pool per test
method and held 60 of the cluster's 100 connections.

## Motivation

Measured on 2026-10-25 (10 cores, load 3–5 from other sessions). The full gate
on unmodified `main` took **1,278s** wall clock, every step one after another:

| Step                                    | Seconds |
| --------------------------------------- | ------: |
| `ship-scripts-tests`                    |     150 |
| 9 other cluster-free harnesses          |      69 |
| `q-scripts-tests`                       |      54 |
| `db-scripts-tests`                      |     311 |
| `scripts-tests`                         |     291 |
| `bin/test`'s `db-reset` + `db-tests`    |      47 |
| Gradle tests (re-run in a new worktree) |     354 |

The migration half of that was already cut by the change that applies the
pending set in one `psql` session (`e38989bb`: 923s → 737s for everything but
Gradle). What is left is sequencing, not work.

Of the last 80 commits on `main`, 28 of the 29 code commits classified as scope
`full`. So RFC 214's path map, valuable as it is, leaves the full gate as the
common case.

The `/work` skill runs `bin/test` unscoped in its verify step, so the Gradle
suite really does re-run once per change, on the critical path of every land.
Its parts are independent: summed class time is 300s, and the largest module
(`service`) is 83s.

### Answering RFC 214 D8

RFC 214 deferred harness concurrency for three reasons. Each is answered here:

1. _"It cannot be justified by reading."_ The collisions it named are real, and
   the lanes keep them apart rather than overlapping them. `q-scripts-tests` and
   `scripts-tests` share this checkout's test DB, ports and daemons, and they
   stay in one serial lane. `db-scripts-tests` provisions a private cluster on
   its own port. The ten cluster-free harnesses run no `db-*` script, touch no
   `var/` state and write nothing into the checkout (each was checked). The
   experiment is the before/after gate runs under **Measurements**.
2. _"After the rows above, the win shrinks."_ It did not: full scope is still 28
   of 29 code commits.
3. _"db-scripts-tests alone took load to 8."_ The test of that objection is the
   measured wall clock under real load. If the lanes had not beaten serial on
   this machine, that half of this RFC would have been withdrawn.

## Decisions

### D1 — three lanes, assigned by what a harness shares

- `own-cluster`: `db-scripts-tests`, which provisions and tears down its own
  cluster.
- `cluster-free`: the ten harnesses that need only a temp directory and git.
- `shared`: `q-scripts-tests`, then `scripts-tests`. They share this checkout's
  test DB, test ports and daemons.

The lane map (`harness_lane` in `bin/shell-tests`) names the first two lanes
explicitly. **Every other harness, including any added later, goes to
`shared`**, the conservative lane that runs serially beside the database users.
A new harness never gets concurrency it was not reviewed for.

### D2 — output is captured per harness and replayed in the declared order

Interleaving three harnesses' live output would make a failure unreadable. Each
harness writes to its own log. A one-line note goes to stderr as each harness
finishes. When the lanes are done, each harness's log is replayed to stderr in
the declared harness order.

When only one lane has work (`bin/shell-tests -n scripts-tests` while
iterating), it runs in the foreground with live output, as before.

### D3 — every failure is reported, and the first sets the exit code

A lane stops at its first failing harness, as `set -e` stopped the serial loop.
The other lanes run on, so one run reports every failing harness rather than
only the first. The exit status is the first failure's, in the declared order,
so it does not depend on which lane finished first. Harnesses a stopped lane
never reached are named as not run.

A harness that never ran is never a pass. If no harness failed and some were
still never run (a lane died between harnesses), the run fails by name.

### D4 — stdin is `/dev/null` for every harness

Git already gives the hook `/dev/null`, so this changes nothing under a commit.
It makes direct runs behave the same. That matters: a test in `db-scripts-tests`
calls `bin/db-drop` without `-y`, and under an open stdin it waits on the
confirmation prompt forever. That test's own defect is left as a separate
finding; with this change it can no longer hang a lane.

### D5 — one database per Gradle module, copied from the migrated test DB

This checkout's test database is still the one that is migrated, and the one
`db-tests` runs against. After `db-tests`, `bin/test` copies it once per
selected module (about 0.2s each, in one `psql` session), so each copy starts
from exactly the state the serial suite started from.

Each module's test task gets its copy as the `POSTGRES_DB` **environment
variable**, so every reader sees it: `AppConfig`, a bare HOCON `resolve()`,
`System.getenv`, a child process. The variable is an ordinary task input. That
costs no cache sharing, because test tasks already carried a per-worktree
absolute path (`unicoach.config.dir`) as an input and were never shared across
worktrees. Within a worktree the names are stable, so a verify followed by the
hook still finds the tests up to date.

RFC 156 declined `TEMPLATE` for dev databases because a live daemon is usually
connected to them. That precondition does not hold here: nothing connects to
this checkout's test DB between `db-tests` finishing and the copies being made.
If something does, `CREATE DATABASE` refuses and `bin/test` stops with that
error. It never falls back to a shared database.

### D6 — the per-module name fits the existing budget

The name is `unicoach-tNN-<checkout>`, where `NN` is the module's position in
`bin/test`'s `MODULES`. The 13-byte prefix is shorter than the 14-byte
`unicoach-test-` that `CHECKOUT_NAME_MAX_BYTES` is computed from, so every
checkout name that is legal today stays legal. The name is made in one helper,
`test_module_database_name` in `bin/functions`, which also checks the 63-byte
limit itself.

### D7 — the copies are temporary

The shared cluster already carries 425 `unicoach-*` databases (6.2 GB), and
nothing reaps them. `bin/test`'s exit trap drops the module copies on every
exit, pass or fail, using `WITH (FORCE)` so that a killed test JVM's leftover
backend cannot keep one alive. Making a copy also drops any leftover first. Each
checkout's footprint stays one test database, as before.

### D8 — parallelism is opt-in through `bin/test`, and bounded by connections

`bin/test` is the only caller that passes `--parallel`, together with
`--max-workers=4`. A plain `./gradlew` stays serial against the shared test DB.
If a test task runs with a database map that does not name its module, it
refuses at execution time. The check cannot run at configuration time, because
several modules configure their test tasks eagerly, and a run of one module
would then fail over every other.

The cap comes from connections, not cores. `max_connections` is 100, shared by
every checkout on the machine. Measured per module database during a forced full
run: `college` **62**, `service` 14, `rest-server` 12, every other module 5 or
fewer.

### D9 — the one leak is fixed, not budgeted around

`college`'s 62 came from `IpedsLoaderTest`, which built a `Database` (a Hikari
pool that opens its connections at construction) as an instance field. JUnit
creates an instance per test method, so that was 30 pools, never closed, in a
suite whose comment says it runs no SQL. That was already a hazard under the
serial suite: one checkout's gate held 62 connections, so two gates at once
could exhaust the cluster. It is now one pool per class, closed in `@AfterAll`.
Measured after the fix: `college` peaks at **2**.

## Detailed Design

### `bin/shell-tests`

- `HARNESSES` is unchanged, entry for entry; the anti-orphan sweep in
  `bin/scripts-tests` greps it.
- `harness_lane NAME` prints `own-cluster`, `cluster-free` or `shared`, with
  `shared` as the default branch.
- `run_harness NAME` runs one harness with stdin `/dev/null`. It clears
  `ENV_FILES` for `db-scripts-tests`, as before.
- If only one lane has work, the harnesses run in the foreground, serially,
  under `set -e`, as before.
- Otherwise, `run_lane LANE` runs in the background for each active lane. Each
  harness's output goes to `$LOG_DIR/<name>.log` and its status to
  `<name>.status`, and the lane returns at the first failure. `LOG_DIR` is a
  `mktemp -d`, removed by the `EXIT` trap. `INT`/`TERM` signal the lanes'
  harnesses so their own traps tear down what they started.
- The verdict is read from the status files, never from the lane processes' exit
  statuses.

### `bin/functions`

- `test_module_database_name INDEX`: prints `unicoach-tNN-<checkout>`. It
  refuses a non-numeric or three-digit position, or a name over 63 bytes.
- `test_module_databases_create TEMPLATE NAME...`: in one session, for each
  name, `DROP DATABASE IF EXISTS` then `CREATE DATABASE … TEMPLATE`.
- `test_module_databases_drop NAME...`: in one session,
  `DROP DATABASE IF EXISTS … WITH (FORCE)` for each name.

### `bin/test`

After `db-tests`, for each selected module, in order:

1. Derive its copy's name.
2. Set an `EXIT` trap that drops all the copies.
3. Create the copies from `$POSTGRES_DB`.
4. Export `UNICOACH_TEST_DATABASES` (`module=database,…`).
5. Run `gradlew --parallel --max-workers=4`. Gradle is no longer `exec`'d, so
   the trap can run.

### `build.gradle.kts`

In `subprojects { tasks.withType<Test>().configureEach { … } }`: when
`UNICOACH_TEST_DATABASES` is set and names this module, set
`environment("POSTGRES_DB", it)`. When it is set and does not name this module,
add a `doFirst` that throws. A malformed entry is refused while the map is
parsed.

## Files Modified

- `bin/shell-tests`: lanes, capture and replay, failure reporting
- `bin/functions`: the name, create and drop helpers
- `bin/test`: per-module copies, exit trap, `--parallel`, help text
- `build.gradle.kts`: per-module `POSTGRES_DB`
- `college/src/test/kotlin/ed/unicoach/college/IpedsLoaderTest.kt`: one closed
  pool per class
- `bin/scripts-tests`: the lane runner's tests and the name helper's tests
- `bin/db-scripts-tests`: the copy and drop helpers' tests
- `CLAUDE.md`: how `bin/test` isolates modules, and that a pool must be closed

## Tests

In `bin/scripts-tests`, against a fixture copy of `bin/shell-tests` with a fake
harness under every real name:

- all harnesses run, across three lanes, and their output is replayed;
- the lanes overlap, but `scripts-tests` starts only after `q-scripts-tests`
  ends (the `shared` default branch is serial);
- a failure stops only its own lane, names the harness it skipped, replays its
  log, and sets the exit status;
- with two failures, the exit status is the first in declared order, not the
  first to finish;
- a single-lane run streams live, with no replay;
- no harness reads the caller's stdin, in either path.

Run against the old serial `bin/shell-tests`, five of these six fail. The
single-lane test passes on both, which is its point.

Also in `bin/scripts-tests`: the module database name fits the longest legal
checkout name, differs per checkout, and refuses a bad position.

In `bin/db-scripts-tests`, on its private cluster: a copy holds the template's
rows; making a copy again discards a previous run's rows; dropping removes the
copies; and a copy is refused while the template has a connection.

## Measurements

Real hook runs on 2026-10-25, on the same 10-core machine, load average 2–4 at
the start of each run, with other sessions active.

### The full gate

| Run                                                   | Wall clock |
| ----------------------------------------------------- | ---------: |
| `main` before any of this, Gradle re-running          |     1,278s |
| `main` before any of this, Gradle up to date (equiv.) |      ~929s |
| after `e38989bb` (one-session migrate)                |       743s |
| after this RFC                                        |   **341s** |

The "up to date" baseline is the measured 1,278s run minus its Gradle step
(354s) plus a cached Gradle step (~6s). That is the shape a `/work` land has,
because its verify step has already run the suite on the same tree.

After this RFC the harness phase is the slowest lane:

| Lane           | Harnesses                          | Seconds |
| -------------- | ---------------------------------- | ------: |
| `own-cluster`  | `db-scripts-tests`                 |     305 |
| `shared`       | `q-scripts-tests`, `scripts-tests` |     216 |
| `cluster-free` | the other ten                      |     206 |

The same thirteen harness durations sum to 727s, which is what they cost run one
after another. `db-scripts-tests` is now the floor. Its cost is about 125 small
steps of `bin/` process-chain overhead, not one slow step.

### `bin/test` when the suite really re-runs (the verify step)

| Run                                                    | Wall clock |
| ------------------------------------------------------ | ---------: |
| serial, before (`db-reset` + `db-tests` + Gradle)      |      ~401s |
| parallel, 4 workers (57 tasks executed, 41 from cache) |   **208s** |
| parallel, 4 workers, `-f` (all 98 tasks executed)      |       240s |

### Connections

Peak connections to this checkout's module databases during a forced parallel
run, sampled every 0.5s: **76** before the `IpedsLoaderTest` fix (62 of them in
`college`'s database). After the fix, `college` alone peaks at 2.

### Not measured

- Two checkouts running the gate at the same moment.
- A worker cap other than 4.
