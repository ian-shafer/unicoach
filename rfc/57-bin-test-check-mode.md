# RFC 57: A `check` mode for `bin/test`, fixing the pre-commit Kotlin gate

## Executive Summary

The git `pre-commit` hook (`bin/pre-commit`) runs its Kotlin gate as
`"$PROJECT_ROOT/bin/test" check`, but `bin/test` has no `check` keyword: its CLI
validates every positional against the hardcoded module set
(`common db net queue queue-worker rest-server service email chat`) and rejects
anything else with `help "unknown module [$1]"`, which exits `2`. So
`bin/test check` always fails before resetting the database or starting Gradle,
the Kotlin half of every pre-commit run fails, and contributors are forced to
`git commit --no-verify` — silently disabling the Markdown-format and contract-
fuzz gates that share the same hook.

This RFC adds a `check` mode to `bin/test`: an optional leading `check` keyword
that swaps each selected module's Gradle task from `test` to `check`, giving a
full Kotlin verification (ktlint + tests) over the same Postgres lifecycle
`bin/test` already owns. The fix lands entirely in `bin/test`; `bin/pre-commit`
is left unchanged because its existing `bin/test check` invocation becomes
valid.

## Why the fix belongs in `bin/test`, not the hook

A "full Kotlin verification" must run **both** ktlint and the test suites. The
ktlint Gradle plugin (applied to `allprojects` in `build.gradle.kts`) wires
`ktlintCheck` into Gradle's `check` lifecycle task, and `check` also depends on
`test`. Those test tasks are DB-backed: every `Test` task needs the migrated
Postgres database that `bin/test` brings up via
`postgres-up → db-reset →
db-tests` before it execs Gradle. A bare
`./gradlew check` invoked directly from `bin/pre-commit` would therefore fail
for want of a database — the Postgres lifecycle, not just the task name, is the
thing the hook needs. `bin/test` already owns that lifecycle, so extending it to
run `check` is the cohesive fix and keeps the hook a thin delegator.

## Design

`bin/test`'s CLI, as described in `bin/SPEC.md` (§ "Test Suites" → `bin/test`),
maps each positional module to an explicit `:<module>:test` Gradle task and
holds a **core invariant**: it always emits at least one such task (naming no
module runs every module), so a flags-only argv can never make Gradle print a
false "BUILD SUCCESSFUL" over zero tests.

This RFC generalizes the per-module task from the fixed string `test` to a
selectable task that defaults to `test`:

- A new optional **leading keyword `check`** sets the per-module Gradle task to
  `check` instead of `test`. It is recognized in the argument-parsing loop as a
  distinct case arm — it is **not** a module and is **not** an option — so it
  composes with the existing grammar:
  - `bin/test check` → `:common:check :db:check … :chat:check` (every module's
    `check`; equivalent to `./gradlew check`, the pre-commit gate's form).
  - `bin/test check rest-server` → `:rest-server:check` (one module).
  - The mapped options (`--tests`, `--force`, `--verbose`, `--continue`) keep
    their existing meaning and ordering; `--tests` still requires exactly one
    module and filters the `Test` task that `check` depends on.
- The **core invariant is preserved and generalized**: `bin/test` still emits at
  least one `:<module>:<task>` task, where `<task>` is `test` (default) or
  `check`. Validation still completes before any side effect, so an invalid
  invocation still fails without resetting the test database, and the exit-code
  contract (usage errors `2`; an executed-but-failing run propagates Gradle's
  `1` through `exec`; success/`--help` `0`) is unchanged.

No new option spellings, no `--` passthrough, and no change to the lifecycle
order (`postgres-up → db-reset → db-tests → exec gradlew`).

## Implementation plan

All changes are in `bin/test`:

1. Add a `TASK="test"` default alongside the other parse-state defaults.
2. Add a `check)` arm to the argument `case` (before the `-*)` unknown-option
   arm) that sets `TASK="check"`.
3. Build the Gradle task list from `:$module:$TASK` instead of the literal
   `:$module:test`.
4. Update `help()` usage (`test [options] [check] [module ...]`) and the
   core-invariant comment to document the `check` keyword.

`bin/SPEC.md` is updated in the spec-sync phase to describe the `check` keyword
and restate the core invariant over `:<module>:<task>`, and to note that the
`bin/pre-commit` Kotlin gate runs `bin/test check`.

## Verification

Run `bin/pre-commit` inside `nix develop` on a clean tree and confirm it exits
`0` without `--no-verify`: the Kotlin gate (`bin/test check`) resets the test DB
and runs every module's `check` (ktlint + tests) to completion, and the Markdown
gate (`deno fmt --check`) passes. Confirm `bin/test check rest-server` scopes to
`:rest-server:check`, and that an unknown positional (e.g. `bin/test bogus`)
still exits `2` with `unknown module [bogus]`.
