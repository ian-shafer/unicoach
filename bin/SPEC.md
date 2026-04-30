# bin/ — Shell Script Layer

## I. Overview

`bin/` is the **unified shell interface** for the entire project. It owns every
developer-facing and CI-facing operation: process lifecycle management, database
administration, build orchestration, code quality enforcement, and test
execution. All scripts are written in Bash and source `bin/common` to establish
`$PROJECT_ROOT` and a shared environment. No script in `bin/` contains
business logic; each delegates to the appropriate tool (`gradlew`, `pg_ctl`,
`psql`, `ktlint`, `deno`, `schemathesis`).

---

## II. Invariants

### Execution Environment

- Every script MUST begin with `source "$(dirname "$0")/common"` as its first
  non-comment, non-shebang line (or source `common` before any project path
  resolution is needed).
- `bin/common` MUST set `PROJECT_ROOT` to the repo root by resolving
  `"$(dirname "${BASH_SOURCE[0]}")/.."` and exporting it.
- `bin/common` MUST source `bin/functions` before loading `.env`.
- `bin/common` MUST fatal if the resolved `$ENV_FILE` does not exist.
- All scripts MUST set `set -euo pipefail` (inherited from `common`).

### Help Interface

- Every script MUST intercept `-h` / `--help` and print a multiline usage block
  via a `help()` heredoc, then exit `0`.
- A script invoking `help()` with a non-empty argument MUST print that argument
  to stderr and exit `1`.

### Exit Codes

- Exit `0`: success or idempotent no-op (service already up/down).
- Exit `1`: general error.
- Exit `2`: PostgreSQL is unreachable (used by `db-run`, `db-repl`, and queue
  CLI scripts).
- `file-lock` exits `10` when the lock is already held by a matching operation.

### Logging

- All human-readable status output MUST go to **stderr** via `log-info` or
  `log-warning`.
- `log-error` MUST be used for errors arising from programming logic mistakes or
  I/O failures (e.g. invalid user input, tool invocation failures).
- `fatal` MUST print `[FATAL] <message>` to stderr and exit with the given
  status code (default `1`).
- Scripts MUST NOT emit to stdout unless producing output is the core function
  of the script (e.g. `db-query` prints query rows, `db-status` prints
  migration state). Diagnostic and status messages MUST go to stderr.

### Dangerous Operations

- Scripts that destroy data (`db-destroy`, `q-truncate`) MUST gate execution
  behind `require_dangerous_confirmation` **unless** the caller passes
  `--yes-i-really-want-to-do-this`.

### Path Resolution

- All inter-script invocations MUST use absolute paths:
  `"$PROJECT_ROOT/bin/<script>"`. Relative invocations are forbidden.

---

## III. Behavioral Contracts

> Per-script argument details are intentionally omitted — run `<script> --help`
> or read the source. This section covers only non-obvious invariants and
> cross-script relationships.

### Shared Library

`bin/common` bootstraps every script: sets `PROJECT_ROOT`, sources
`bin/functions`, and exports all `.env` variables into the process environment.

`bin/functions` exports the following public API (all functions are available
in every script that sources `common`):

| Function | Signature | Output / Return | Notes |
|---|---|---|---|
| `log-info` | `log-info <msg…>` | stderr | No prefix. |
| `log-warning` | `log-warning <msg…>` | stderr | Prefixes `[WARNING]`. |
| `log-error` | `log-error <msg…>` | stderr | Prefixes `[ERROR]`. |
| `fatal` | `fatal [-s \|--status-code <n>] <msg…>` | exits | Prefixes `[FATAL]`; exits with `<n>` (default `1`). |
| `read-file-or-die` | `read-file-or-die <file> [<code>]` | stdout | Cats file or calls `fatal -s <code>`. |
| `parse-duration-to-seconds` | `parse-duration-to-seconds <dur>` | stdout | Converts `30s`/`5m`/`2h`/`1d` → integer seconds; bare integers treated as seconds. |
| `validate_duration` | `validate_duration <dur>` | exit 0/1 | Accepts only `[0-9]+[smhd]` (unit suffix required). |
| `transform_duration_to_postgres` | `transform_duration_to_postgres <dur>` | stdout | Converts `5m` → `"5 minutes"` for SQL `INTERVAL` literals. Calls `validate_duration` internally. |
| `require_dangerous_confirmation` | `require_dangerous_confirmation <desc>` | exit 0/1 | Interactive prompt; returns `0` on confirmation, `1` on EOF (empty line exits `0` via `exit 0`). Callers bypass this entirely by checking `--yes-i-really-want-to-do-this` before invoking. |

---

### Concurrency Primitive: `file-lock`

Exit codes are the contract callers depend on:

- **Exit 0**: lock acquired.
- **Exit 1**: lock held by a conflicting operation; timed out.
- **Exit 10**: lock already held by the **same** operation — caller MUST treat
  this as a success and exit `0` without retrying.

The lock MUST be released via a trap registered **after** successful
acquisition, never before. Stale locks (past their expiry) are broken
automatically.

---

### Polling Primitive: `wait-for`

**Side effects**: executes `<command>` one or more times (command MUST be
idempotent); may sleep `<period>` between retries. Exits with the last non-zero
status of `<command>` on timeout.

---

### PID Liveness: `check-pid`

No side effects. Exit `0` if alive, `1` if dead.

---

### Generic Daemon Engine

- **`daemon-up`**: acquires a lock, checks for a live process (idempotent if
  already running), launches `<command>` in the background, writes the PID.
  **Does not perform health checks** — callers must invoke
  `<service>-wait-for-health`.
- **`daemon-down`**: idempotent if already stopped; sends `SIGTERM` with a
  grace period, escalates to `SIGKILL`. Always removes the PID file on exit.
- **`daemon-check`**: exit `0` if PID file exists and process is alive.
- **`daemon-bounce`**: `daemon-down` then `daemon-up`.
- **`daemon-status`**: prints `running`/`stopped` for each known service.
  Always exits `0`.

---

### PostgreSQL Lifecycle

PostgreSQL is **not** managed via `daemon-up`/`daemon-down`. It uses `pg_ctl`
directly because postgres owns its own `postmaster.pid`. `postgres-down` polls
for `postmaster.pid` deletion — not `kill -0` — to avoid PID reuse races.

`bin/db-init` is the sole entry point for cluster initialisation (`initdb`);
`postgres-up` requires the cluster to already exist.

The full postgres lifecycle family:

| Script | Delegates to | Exit contract |
|---|---|---|
| `postgres-up` | `pg_ctl start` | Exit `0` if started or already running. |
| `postgres-down` | `pg_ctl stop`, polls `postmaster.pid` | Exit `0` if stopped or already stopped. |
| `postgres-bounce` | `postgres-down` then `postgres-up` | Exit `0` on success. |
| `postgres-check` | `pg_isready` | Exit `0` if accepting connections on `localhost:5432`, `1` otherwise. No side effects. |
| `postgres-wait-for-health` | `wait-for` + `postgres-check` | Polls until `postgres-check` succeeds or timeout. |

---

### Service Wrappers

`rest-server-*` and `queue-worker-*` follow the same pattern. The full family
for each `<svc>` (`rest-server`, `queue-worker`):

| Script | Delegates to | Notes |
|---|---|---|
| `<svc>-up` | `daemon-up`, then `<svc>-wait-for-health` | **Fatals if the `installDist` binary is absent.** Never invokes Gradle. |
| `<svc>-down` | `daemon-down` | Thin delegate. |
| `<svc>-bounce` | `daemon-bounce` | Thin delegate. |
| `<svc>-check` | HTTP/port probe | `rest-server-check`: `GET /hello` → HTTP 200. `queue-worker-check`: TCP port probe. Exit `0` if healthy, non-zero otherwise. |
| `<svc>-wait-for-health` | `wait-for` + `<svc>-check` | Polls until `<svc>-check` succeeds or timeout. |

---

### Build Scripts

`bin/build` runs per-module scripts in dependency order:
`common → db → service → queue → net → rest-server → queue-worker`. Exits
immediately on any module failure. Library modules use `:module:assemble`;
JVM executables use `:module:installDist`.

Each `bin/build-<module>` script follows this template:

1. `source "$(dirname "$0")/common"`
2. Declare a `help()` heredoc.
3. Parse `-h|--help` and reject unknown flags/positional args via `help`.
4. `exec "$PROJECT_ROOT/gradlew" :<module>:<task>` — `assemble` for library
   modules (`common`, `db`, `service`, `queue`, `net`); `installDist` for JVM
   executables (`rest-server`, `queue-worker`).

Adding a new Gradle module requires: (1) creating `bin/build-<module>` using
this template, and (2) inserting it at the correct position in the ordered
sequence inside `bin/build`.

---

### Database Scripts

- **`db-init`**: idempotent cluster bootstrap (role, database, schema grants,
  `schema_migrations` table). Safe to run multiple times.
- **`db-migrate`**: applies pending migrations in lexicographical order, each
  wrapped in a transaction. Halts on failure; previously applied migrations are
  not rolled back.
- **`db-status`**: calls `db-init` internally before reporting migration state.
- **`db-run`**: execution primitive. `ro` mode enforces read-only transactions;
  exits `2` if postgres is unreachable. `db-query` and `db-update` are thin
  wrappers over `db-run ro` and `db-run rw`.

---

### Queue CLI Scripts

`q-*` scripts delegate to `db-query`/`db-update` and propagate exit `2` on
database unreachability. `q-retry` only accepts `DEAD_LETTERED` jobs.
`q-truncate` requires `--yes-i-really-want-to-do-this` or interactive
confirmation.

---

### Code Quality

- **`bin/format`**: runs `ktlint` (Kotlin) and `deno fmt` (Markdown)
  concurrently; exits `2` if either fails.
- **`bin/pre-commit`**: runs `bin/test check` and `deno fmt --check`
  concurrently; exits `2` if either fails.

---

### Test Scripts

All test scripts set `export ENV_FILE=".../.env.test"` before sourcing
`common`, then source `bin/tests-common` for assertion helpers. Each registers
an EXIT trap to tear down any services it started.

#### `bin/tests-common` — Assertion API

| Function | Signature | Behavior |
|---|---|---|
| `pass_test` | `pass_test <msg>` | Increments `TOTAL_TESTS`; prints green `✅ <msg>` to stdout. |
| `fail_test` | `fail_test <msg>` | Prints red `❌ Fail: <msg>` to **stderr**; **exits `1` immediately**. No further assertions run. |
| `assert_success` | `assert_success <msg> <cmd…>` | Runs `<cmd>`; calls `fail_test` if it exits non-zero, otherwise calls `pass_test`. |
| `assert_failure` | `assert_failure <msg> <cmd…>` | Runs `<cmd>`; calls `fail_test` if it exits **zero**, otherwise calls `pass_test`. |
| `end_tests` | `end_tests` | Prints total count summary. **Does not exit.** Must be the final statement in every test script. |

> `fail_test` exits immediately — subsequent assertions in the same script are
> skipped. Test functions that need early returns before calling `fail_test`
> must `return 1` and let the `assert_*` wrapper call `fail_test`.

#### Test Suites

- **`bin/test`**: freshly destroys and re-initializes the test database, then
  delegates to `./gradlew`. Entry point for JVM unit/integration tests.
- **`bin/test-fuzz`**: boots postgres and rest-server, runs `schemathesis`
  against the live API.
- **`bin/scripts-tests`**: exercises the daemon engine (`rest-server`,
  `queue-worker`), `file-lock`, `check-pid`, `daemon-status`, and bash
  utilities (`validate_duration`, `transform_duration_to_postgres`,
  `require_dangerous_confirmation`). Postgres is excluded from
  `test_daemon_wrapper` — it is managed via `pg_ctl`, not the daemon engine.
- **`bin/db-scripts-tests`**: exercises `db-run`, `db-query`, `db-update`,
  `db-repl`, `db-init`, `db-migrate`, `db-status`, `db-destroy`. Spins up a
  fresh postgres cluster at test start; tears it down on EXIT.
- **`bin/db-users-tests`**: exercises the `users` and `users_versions` table
  constraints, triggers, and OCC semantics against a live migrated database.
  Runs `db-init` + `db-migrate` at startup; tears down postgres on EXIT.
- **`bin/q-scripts-tests`**: exercises `q-status`, `q-enqueue`, `q-inspect`,
  `q-retry`, `q-delete-job`, and `q-truncate` against a live migrated
  database. Spins up a fresh postgres cluster at test start; tears it down on EXIT.

---

### Developer Tools

**`bin/dev-bootstrap`**: verifies `nix` is installed and prints activation
instructions. Does not install anything.

---

## IV. Infrastructure & Environment

- **Shell**: All scripts target `bash` via `#!/usr/bin/env bash`.
- **Toolchain**: Provided by `flake.nix` (`temurin-bin-21`, `postgresql_18`,
  `python3`, `deno`, `ktlint`, `git`).
- **Runtime directories**: `var/run/` (PID files `<service>.pid` and lock dirs
  `<service>.daemon.lock`) and `var/log/` (service logs) are created on demand
  by `daemon-up` and `postgres-up`.
- **Environment files**: `.env` (dev), `.env.test` (test). Test scripts set
  `export ENV_FILE=".../.env.test"` before sourcing `common`.
- **`POSTGRES_DATA_DIR`**: absolute path (e.g. `$PROJECT_ROOT/var/postgres`).
  Declared in `.env` using `$PROJECT_ROOT` so it resolves correctly from any
  working directory.
- **`PGHOST=localhost`**: set in `.env` and `.env.test` so all libpq clients
  use TCP by default (postgres socket lives under `$POSTGRES_DATA_DIR`).
- **`PORT` / `SERVER_PORT`**: `PORT` is the single source of truth for the
  REST server bind port; `SERVER_PORT=$PORT` in env files bridges to
  `rest-server.conf`.
- **Dev/test cluster sharing**: both environments share one PostgreSQL cluster
  (`$PROJECT_ROOT/var/postgres/`); isolation is at the database level
  (`POSTGRES_DB=unicoach` vs `POSTGRES_DB=unicoach-test`).

---

## V. History

- [x] [RFC-03: Daemon Scripts](../rfc/03-daemon-scripts.md)
- [x] [RFC-04: Postgres Daemon Scripts](../rfc/04-postgres-daemon-scripts.md)
- [x] [RFC-05: DB Scripts](../rfc/05-db-scripts.md)
- [x] [RFC-06: Users Table](../rfc/06-users-table.md)
- [x] [RFC-08: Auth Registration](../rfc/08-auth-registration.md)
- [x] [RFC-13: Auth Me](../rfc/13-auth-me.md)
- [x] [RFC-14: DB Module](../rfc/14-db-module.md)
- [x] [RFC-15: Queue Data Layer](../rfc/15-queue-data-layer.md)
- [x] [RFC-17: Queue Worker Daemon](../rfc/17-queue-worker-daemon.md)
- [x] [RFC-18: Docker Infrastructure Hardening](../rfc/18-docker-infrastructure-hardening.md)
- [x] [RFC-19: Daemon Health Marker](../rfc/19-daemon-health-marker.md)
- [x] [RFC-20: Test Environment Isolation](../rfc/20-test-environment-isolation.md)
- [x] [RFC-21: Session Expiry Queue](../rfc/21-session-expiry-queue.md)
- [x] [RFC-22: Auth Logout](../rfc/22-auth-logout.md)
- [x] [RFC-23: Native Daemon Scripts](../rfc/23-native-daemon-scripts.md)
