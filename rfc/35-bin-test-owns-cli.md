# RFC 35: bin/test owns its CLI instead of forwarding args to gradlew

## Executive Summary

`bin/test` currently forwards all positional arguments straight to `gradlew`
(`GRADLE_ARGS=("${@:-test}")`, [bin/test:45](../bin/test)). `${@:-test}` injects
the `test` task only when arguments are _completely_ empty. A flags-only
invocation such as `bin/test --rerun-tasks --no-build-cache` therefore reaches
`gradlew` with no task: `gradlew` prints "To run a build, run gradlew <task>",
exits `0`, and emits a "BUILD SUCCESSFUL" that ran zero tests. Forwarding also
silently accepts unknown task names, so the broken
`./bin/test
ed.unicoach.queue.JobsDaoTest` shape cited across older RFCs has
always failed as "task not found" rather than running a filtered test.

This RFC replaces forwarding with a closed, validated CLI that `bin/test` owns.
`bin/test` parses its own options, maps friendly module names to explicit
`:module:test` Gradle tasks, and **always** emits at least one such task. With
no module arguments it runs every module. Unknown modules and unknown options
are rejected with usage and a non-zero exit; they are never forwarded. The
zero-task false-green becomes structurally impossible rather than a hazard
callers must remember to avoid.

The lifecycle (`postgres-up → db-reset → db-tests → exec gradlew`) is unchanged.
Only argument handling and the final `gradlew` argv construction change.
Argument validation runs _before_ any side effect, so an invalid invocation
fails without resetting the test database.

## Detailed Design

### CLI grammar

```
test [options] [module ...]
```

Options and module positionals may interleave in any order. Modules are friendly
bare names (`rest-server`, `db`), not Gradle task paths; `bin/test` owns the
mapping to `:module:test`.

| Token              | Maps to                         | Notes                                                                                                                                                                                   |
| ------------------ | ------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `<module>`         | `:<module>:test`                | Must be a member of the module set. Repeats are de-duplicated.                                                                                                                          |
| (no modules)       | every module's `:<module>:test` | Default. Each module emitted explicitly; never an empty task list.                                                                                                                      |
| `-t, --tests GLOB` | `--tests GLOB`                  | Gradle test filter. Requires **exactly one** module.                                                                                                                                    |
| `-f, --force`      | `--rerun-tasks`                 | Bypass the build cache; force re-execution.                                                                                                                                             |
| `-v, --verbose`    | `--info`                        | Verbose Gradle output.                                                                                                                                                                  |
| `-c, --continue`   | `--continue`                    | Run all selected test tasks and report every failure (default is fail-fast). Opt-in. Legal but inert with a single selected task (e.g. alongside `--tests`); not a validation conflict. |
| `-h, --help`       | —                               | Prints usage, exits `0`.                                                                                                                                                                |

There is no raw-argument passthrough (no `--`). The option set is deliberately
closed: adding power-user Gradle flags is a future, explicit decision, not an
open forwarding channel. `--force` is the only cache lever needed — cached
execution is already Gradle's default, so no `--cached` flag exists.

### Module set

The module set is a hardcoded array in `bin/test`, kept in sync with
`settings.gradle.kts` by hand:

```bash
# Add new Gradle modules here, in sync with settings.gradle.kts.
MODULES=(common db net queue queue-worker rest-server service email)
```

All eight modules apply the Kotlin convention plugin and therefore expose a
`:module:test` task. `queue-worker` currently has no `src/test`; its `test` task
runs NO-SOURCE and passes — a task is still emitted and executed, so this is not
the zero-task failure this RFC eliminates.

`bin/test` does not parse `settings.gradle.kts` at runtime (regex-scraping a
Kotlin DSL file is brittle) and does not ask Gradle to enumerate projects
(spinning up the JVM to list modules is slow and circular). The cost of the
hardcoded array is manual drift: a new module must be added in both places. This
trade-off is accepted; no automated guard test is added.

### Task-list construction and the core invariant

After parsing, `bin/test` builds `GRADLE_ARGS` as: one `:module:test` entry per
selected module (all modules if none were named), followed by the mapped flags
(`--rerun-tasks`, `--info`, `--continue`, `--tests GLOB`) in a fixed order.

The invariant: **`GRADLE_ARGS` always contains at least one `:module:test`
entry.** Because the selected-module set is non-empty by construction (empty
selection defaults to the full module set), a flags-only or zero-task argv can
never be produced. This is the structural property that kills the false-green.

### Execution order

1. Source `bin/common` (unchanged: establishes `PROJECT_ROOT`, loads
   `.env.test`, exports `PGPORT`).
2. Parse and validate all arguments. **All validation errors abort here with
   exit `2`**, before any side effect.
3. `postgres-up` → `db-reset` → `db-tests` (unchanged lifecycle).
4. `exec "$PROJECT_ROOT/gradlew" "${GRADLE_ARGS[@]}"`.

Validation preceding step 3 means an invalid invocation (`bin/test bogus`) does
not drop and recreate the test database before failing.

### `help()` output

`help()` mirrors the `bin/SPEC.md` §II help template with one deliberate
deviation: its error-message branch sets the exit code to `2`, not the
template's `1`. An error message is emitted via `log-info "$1"` (stderr, per §II
Logging); the usage body prints to stdout; called with no error message it exits
`0` (the `--help` path). The deviation gives usage errors a code distinct from a
test-run failure (`1`, see Error handling) and is the only behavioral divergence
from the §II help template (the usage-line and Options body are the template
instantiated, not a divergence).

```
test [options] [module ...]

Runs Gradle tests on the nix-provided host JVM. Resets this worktree's test
database first (postgres-up -> db-reset -> db-tests), then runs the selected
modules' test tasks. With no modules, runs every module.

Modules:
  common db net queue queue-worker rest-server service email

Options:
  -t, --tests GLOB   Gradle test filter (requires exactly one module)
  -f, --force        Force re-run, bypass the build cache (--rerun-tasks)
  -v, --verbose      Verbose Gradle output (--info)
  -c, --continue     Run all selected test tasks, report every failure
  -h, --help         Help
```

### Error handling / edge cases

| Input                                            | Outcome                                                  |
| ------------------------------------------------ | -------------------------------------------------------- |
| Unknown option (e.g. `--no-build-cache`, `--`)   | `help "unknown option [<opt>]"` → exit `2`.              |
| Unknown module (e.g. `bin/test bogus`)           | `help "unknown module [<name>]"` → exit `2`.             |
| `--tests` with no following value                | `help "--tests requires a GLOB"` → exit `2`.             |
| `--tests` with no module or more than one module | `help "--tests requires exactly one module"` → exit `2`. |
| Duplicate modules (`bin/test db db`)             | De-duplicated to a single `:db:test`.                    |
| No arguments                                     | All modules; full suite.                                 |

Two distinct non-zero codes satisfy the `bin/SPEC.md` §II Exit Codes invariant
("scripts MUST define distinct non-zero codes when there are multiple failure
reasons") directly. Usage errors — caller-side input mistakes — exit `2` via the
`help()` error path's explicit code. A test run that executed but did not all
pass exits `1`, gradlew's natural code, propagated unchanged for free through
`exec` with no special handling. Success and `--help` exit `0`. Downstream
lifecycle failures (`postgres-up`, `db-reset`, `db-tests`) propagate their own
exit codes through `set -e`, unchanged.

### Dropped invocation shape

The bare-FQN positional `./bin/test ed.unicoach.queue.JobsDaoTest` is dropped.
It never worked (forwarded as a non-existent Gradle task name). Its replacement
is the module + filter form:

```
bin/test queue --tests "ed.unicoach.queue.JobsDaoTest"
bin/test queue --tests "*JobsDaoTest"
```

### Documentation

The living CLI reference is `bin/test --help`; the living behavioral contract is
`bin/SPEC.md` §III "Test Suites" (synchronized out-of-band by spec-sync-loop,
not by this RFC). This RFC updates only the human-facing usage docs that cite
the old forwarding shapes:

- **README.md**: the `bin/test` / `bin/test :rest-server:test` example block —
  rewritten to the friendly-name shapes (full-suite, single module, and a
  `--tests` filter example), dropping the stale `# equivalent to ./gradlew test`
  comment.
- **CLAUDE.md**: the three-line `nix develop -c bin/test ...` block, including
  the stale "pass-through args go to gradlew" comment and the stale
  `db-destroy → db-init → db-migrate` parenthetical.
- **GRADLE.md**: the two descriptions of `bin/test` delegating to
  `./gradlew test`.

Historical RFCs (11, 13, 16, 21, 22 with the bare-FQN shape; 29, 34 with
`:module:test --tests`) are immutable records and are **not** edited.

The agent-memory note `bin-test-flag-forwarding-false-green` is removed in full
once this RFC lands — both the note file and its `MEMORY.md` index entry. The
note's caution (flag-only invocations false-greening) is made structurally
impossible by this change and is wholly obsolete; its still-valid sub-point
(FROM-CACHE tasks report success without re-executing — read JUnit XML, not the
console summary) is already preserved by this RFC's manual smoke check and the
§III contract, so full deletion loses no live guidance. The note lives outside
this repository and outside any implementor's write-scope; its removal is
therefore NOT an implementation task and does NOT appear in Files Modified. Full
removal is a MANDATORY post-merge step, executed and verified by the pipeline
orchestrator.

### Dependencies

None. Pure Bash within the existing `bin/` toolchain. No change to `gradlew`,
build configuration, or the test lifecycle scripts.

## Tests

No automated parser tests are added. The parser is a small, fail-loud token loop
with explicit validation; a dedicated test seam plus an assertion suite is more
machinery than the change warrants. This trade-off is accepted; the parser is
covered by the manual smoke checks below instead, **not** wired into any
automated harness. These checks are the canonical verification set; the
Implementation Plan steps reference them rather than restating them.

- `nix develop -c bin/test common` runs only `:common:test`, green.
- `nix develop -c bin/test` runs the full suite, green — confirm real counts via
  `<module>/build/test-results/test/TEST-*.xml`, not the console summary.
- `nix develop -c bin/test bogus` exits `2` immediately, without resetting the
  test database (no `postgres-up`/`db-reset`/Gradle output).
- `nix develop -c bin/test db net --tests Foo` exits `2` (`--tests` requires
  exactly one module); `nix develop -c bin/test --no-build-cache` exits `2`
  (unknown option).
- `nix develop -c bin/test --help` prints usage and exits `0`.

## Implementation Plan

1. **Rewrite `bin/test` argument handling.** Replace the
   `while [[ "${1:-}" ==
   -* ]]` loop and `GRADLE_ARGS=("${@:-test}")` with:
   the `MODULES` array; a full parse loop classifying each token as option or
   module; validation per the Detailed Design error table, each usage error
   routed through `help "<msg>"` (which exits `2`); construction of
   `GRADLE_ARGS` as explicit `:module:test` entries plus mapped flags in fixed
   order; and the updated `help()` text with its error branch exiting `2`. Leave
   `postgres-up → db-reset → db-tests → exec
   gradlew "${GRADLE_ARGS[@]}"`
   intact so a test-run failure propagates gradlew's `1` through `exec`.
   - Verify: run the full Tests § smoke checks. For the exit-code checks among
     them, capture the code with
     `nix develop -c bash -c '<invocation>'; echo $?`: `bin/test bogus`,
     `bin/test db net --tests Foo`, and `bin/test
     --no-build-cache` each
     print `2`, and the `bogus` run emits no `postgres-up`/`db-reset`/Gradle
     output (validation precedes the lifecycle, so the test DB is not reset).

2. **Update live docs.** Rewrite the `bin/test` examples in README.md,
   CLAUDE.md, and GRADLE.md to the friendly-name CLI: full-suite `bin/test`,
   single module `bin/test rest-server`, and filter
   `bin/test rest-server --tests
   "ed.unicoach.rest.AuthRoutingTest"`. Remove
   CLAUDE.md's "pass-through args go to gradlew" comment and correct its
   `db-destroy → db-init → db-migrate` parenthetical to
   `db-reset (drop → create → migrate)`. Correct GRADLE.md's "delegates to
   `./gradlew test`" to "runs explicit per-module `:module:test` tasks."
   - Verify:
     `grep -rn ':rest-server:test\|:service:test\|pass-through args\|db-destroy\|equivalent to ./gradlew test\|delegates to'
     README.md CLAUDE.md GRADLE.md`
     returns nothing (the `delegates to` alternative guards the GRADLE.md edit).
   - Verify:
     `grep -n 'bin/test rest-server\|bin/test --tests\|bin/test
     rest-server --tests' README.md CLAUDE.md`
     shows the new shapes.

3. **Full-suite regression.** Confirm the real test runner is unaffected by the
   CLI rewrite.
   - Verify: `nix develop -c bin/test` runs the full suite; confirm real counts
     via `<module>/build/test-results/test/TEST-*.xml`, not the console summary.

## Files Modified

- `bin/test` [MODIFY] — replace argument forwarding with the owned, validated
  CLI; add `MODULES` array, parse/validate logic with usage errors exiting `2`,
  explicit `:module:test` construction, and updated `help()`. Lifecycle and
  `exec gradlew` (test-failure exit `1`) preserved.
- `README.md` [MODIFY] — rewrite the `bin/test` usage example block to the
  friendly-name CLI; drop the stale `# equivalent to ./gradlew test` comment.
- `CLAUDE.md` [MODIFY] — rewrite the `nix develop -c bin/test ...` block; remove
  the "pass-through args" comment; fix the `db-destroy → db-init → db-migrate`
  parenthetical.
- `GRADLE.md` [MODIFY] — correct the two `bin/test` descriptions that say it
  delegates to `./gradlew test`.
