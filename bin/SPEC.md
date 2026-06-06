# SPEC: bin

## I. Overview

`bin/` contains all shell scripts for the Unicoach application. Scripts fall
into five categories: build, daemon control, linting, CLIs, and testing. Every
script sources `bin/common` to establish `$PROJECT_ROOT` and a shared
environment.

---

## II. Invariants

### Common Invariants

Every script MUST source `bin/common` as its first non-comment, non-shebang
line. `bin/common` establishes the following environment:

- Sets `set -euo pipefail`.
- Resolves and exports `PROJECT_ROOT` to the repository root.
- Sources `bin/functions` (shared function library).
- Loads `$ENV_FILE` (defaults to `$PROJECT_ROOT/.env`) into the process
  environment. Fatals if the file does not exist.

### Help Interface

Every operational script directly invoked by the architect MUST define a
`help()` function triggered by `-h` or `--help`. Test harnesses (`*-tests`,
`test*`) are exempt. The structure:

```bash
help() {
  local exit_code=0
  if [ "$#" -gt 0 ]; then
    log-info "$1"
    exit_code=1
  fi
  cat << 'EOF'
<script-name> [options] [arguments]

<description>

Options:
  -h, --help: Help
EOF
  exit "$exit_code"
}
```

- Called with no arguments: prints usage, exits `0`.
- Called with an error message: prints the message to stderr, prints usage,
  exits `1`.

### Exit Codes

- `0`: success.
- Non-zero: MUST be documented in the script's `help()` output. Scripts MUST
  define distinct non-zero codes when there are multiple failure reasons, so
  calling scripts can distinguish why a dependency failed.

### Logging

All log output MUST go to stderr. Each log function MUST prefix its output
with the log level in brackets. Five levels:

| Level | Function | Side Effects |
|---|---|---|
| `DEBUG` | `log-debug` | None. |
| `INFO` | `log-info` | None. |
| `WARN` | `log-warn` | None. |
| `ERROR` | `log-error` | None. |
| `FATAL` | `fatal` | Exits with the given status code (default `1`). |

### Path Resolution

All scripts MUST refer to other scripts and files using absolute paths,
typically via `$PROJECT_ROOT` (e.g., `"$PROJECT_ROOT/bin/<script>"`).

---

## III. Behavioral Contracts

> Per-script argument details are intentionally omitted — run `<script> --help`
> or read the source. This section covers only non-obvious invariants and
> cross-script relationships.

### Shared Library

`bin/functions` exports the following public API, available in every script
that sources `bin/common`:

| Function | Signature | Output / Return | Notes |
|---|---|---|---|
| `log-debug` | `log-debug <msg…>` | stderr | Prefixes `[DEBUG]`. |
| `log-info` | `log-info <msg…>` | stderr | Prefixes `[INFO]`. |
| `log-warn` | `log-warn <msg…>` | stderr | Prefixes `[WARN]`. |
| `log-error` | `log-error <msg…>` | stderr | Prefixes `[ERROR]`. |
| `fatal` | `fatal [-s \|--status-code <n>] <msg…>` | stderr; exits | Prefixes `[FATAL]`; exits with `<n>` (default `1`). |
| `read-file-or-die` | `read-file-or-die <file> [<code>]` | stdout | Cats file or calls `fatal -s <code>`. |
| `parse-duration-to-seconds` | `parse-duration-to-seconds <dur>` | stdout | Converts `30s`/`5m`/`2h`/`1d` → integer seconds; bare integers treated as seconds. |
| `validate_duration` | `validate_duration <dur>` | exit 0/1 | Accepts only `[0-9]+[smhd]` (unit suffix required). |
| `transform_duration_to_postgres` | `transform_duration_to_postgres <dur>` | stdout | Converts `5m` → `"5 minutes"` for SQL `INTERVAL` literals. Calls `validate_duration` internally. |
| `require_dangerous_confirmation` | `require_dangerous_confirmation <desc>` | exit 0/1 | Interactive prompt; returns `0` on confirmation, `1` on EOF (empty line exits `0` via `exit 0`). Callers bypass this entirely by checking `--yes-i-really-want-to-do-this` before invoking. |

---

### Dangerous Operations

Scripts that destroy data (`db-destroy`, `q-truncate`) MUST gate execution
behind `require_dangerous_confirmation` **unless** the caller passes
`--yes-i-really-want-to-do-this`.

---

### Concurrency Primitive: `file-lock`

Directory-based file lock. Scripts use `file-lock <lock-dir> <max-duration>`
to acquire a lock that automatically expires after `<max-duration>`. An
optional `--operation <OP>` writes the operation name into the lock directory,
allowing other lock readers to identify the holder. Without `--timeout`, the
script fails immediately if the lock is held; with `--timeout <dur>`, it
polls via `wait-for` until the lock is acquired or the timeout expires.
Stale locks are broken automatically. Callers MUST release the lock via a
trap registered **after** successful acquisition.

Exit codes:

- **Exit 0**: lock acquired.
- **Exit 1**: lock held by a conflicting operation; timed out.
- **Exit 10**: lock already held by the **same** operation — caller MUST treat
  this as a success and exit `0` without retrying.

---

### Polling Primitive: `wait-for`

Waits for `<command>` to exit `0`. Sleeps `<period>` (default `1s`) between
retries. On timeout, exits with the status of the last `<command>` invocation.

---

### PID Liveness: `check-pid`

Exits `0` if the given PID is running, `1` otherwise.

---

### Daemon Lifecycle

- **`daemon-up`**: Idempotent. Starts the daemon and writes the PID file. If
  the daemon is already running, does nothing. Only checks PID for liveness —
  callers MUST invoke `<service>-wait-for-health` separately.
- **`daemon-down`**: Idempotent. Sends `SIGTERM` with a grace period;
  escalates to `SIGKILL` if the process does not exit. Removes the PID file
  on completion. No-op if already stopped.
- **`daemon-check`**: Exits `0` if the daemon is running according to the PID,
  `1` otherwise.
- **`daemon-bounce`**: Idempotent. Stops the daemon if running, then starts it.
- **`daemon-status`**: Prints the status of all known services.

---

### PostgreSQL Lifecycle

PostgreSQL is **not** managed via `daemon-*` system. It uses `pg_ctl` as the authoritative source of truth regarding its running status and manages its own lifecycle (and PID file).

`bin/db-init` is the first step for setting up the unicoach environment.

Then `bin/db-migrate` must be run to apply the database schema migrations and match it to the source code.

`bin/postgres-*` scripts can be used to manage the lifecycle of the PostgreSQL server.

| Script | Delegates to | Exit contract |
|---|---|---|
| `postgres-up` | `pg_ctl start` | Exit `0` if started or already running. |
| `postgres-down` | `pg_ctl stop`, polls `postmaster.pid` | Exit `0` if stopped or already stopped. |
| `postgres-bounce` | `postgres-down` then `postgres-up` | Exit `0` on success. |
| `postgres-check` | `pg_isready` | Exit `0` if accepting connections on `localhost:5432`, `1` otherwise. No side effects. |
| `postgres-wait-for-health` | `wait-for` + `postgres-check` | Polls until `postgres-check` succeeds or timeout. |

---

### Daemons

Individual daemons use the Daemon Lifecycle scripts (§III) and follow the
naming convention `<service>-{up,down,bounce,check}`. A daemon MAY
additionally provide `<service>-wait-for-health`, which blocks until the
daemon is healthy. "Healthy" is defined by the logic in each script.

Current daemons: `rest-server`, `queue-worker`.

| Script | Behavior |
|---|---|
| `<svc>-up` | Fatals if the `installDist` binary is absent. Never invokes Gradle. Delegates to `daemon-up`, then `<svc>-wait-for-health` if it exists. |
| `<svc>-down` | Delegates to `daemon-down`. |
| `<svc>-bounce` | Delegates to `daemon-bounce`. |
| `<svc>-check` | Runs service-specific health check (possibly none), then delegates to `daemon-check` for PID liveness check. |
| `<svc>-wait-for-health` | Optional. Blocks until the service-specific health check passes. |

---

### Build Scripts

`bin/build` builds all Kotlin source. It delegates to per-module
`bin/build-<module>` scripts in dependency order:
`common → db → service → queue → net → rest-server → queue-worker`.
Fast-fails on the first module failure.

Each `bin/build-<module>` script runs exactly one `./gradlew` task via `exec`:
`:module:assemble` for libraries, `:module:installDist` for daemons.

To add a new Gradle module:

1. Create `bin/build-<module>` following the existing template.
2. Insert it at the correct position in the ordered sequence inside
   `bin/build`.

---

### Database Scripts

- **`db-init`**: Idempotent. Creates the application role, database, schema
  grants, and `schema_migrations` table.
- **`db-migrate`**: Applies pending migrations in lexicographical order, each
  wrapped in a transaction. Previously applied migrations are skipped by
  inspecting the `schema_migrations` table. Halts on failure.
- **`db-status`**: Reports the applied/pending state of all migration scripts.
  Auto-initializes the database via `db-init` if needed.
- **`db-run`**: Execution primitive. `ro` mode enforces read-only transactions;
  exits `2` if postgres is unreachable.
- **`db-query`**, **`db-update`**: Thin wrappers over `db-run ro` and
  `db-run rw`.
- **`db-repl`**: Opens a `psql` session connected to the application database.
- **`db-destroy`**: Deletes the application database from the PostgreSQL
  instance.

---

### Queue CLI Scripts

Scripts to inspect and mutate the application work queue. All delegate to
`db-query`/`db-update` and propagate exit `2` on database unreachability.

- **`q-status`**: Displays queue stats by job type.
- **`q-enqueue`**: Adds an item onto the queue.
- **`q-inspect`**: Full details for a single job by ID.
- **`q-retry`**: Resets a `DEAD_LETTERED` job back to `SCHEDULED`. Rejects
  jobs not in `DEAD_LETTERED` status.
- **`q-delete-job`**: Removes an item from the queue by ID.
- **`q-truncate`**: Removes all items from the queue.

---

### CI/CD

- **`bin/format`**: Runs `ktlint` (Kotlin) and `deno fmt` (Markdown)
  concurrently.
- **`bin/pre-commit`**: Runs tests and checks code format.

---

### Test Scripts

All test scripts set `export ENV_FILE=".../.env.test"` before sourcing
`common`; the aggregator `db-tests` defaults `ENV_FILE` to `.env.test` but
defers to a caller-supplied value. Harnesses that make assertions source
`bin/tests-common` for assertion helpers. Two lifecycle-ownership models exist:

- **Self-contained harnesses** (`scripts-tests`, `db-scripts-tests`,
  `db-users-tests`, `q-scripts-tests`) MUST register an EXIT/INT/TERM trap to
  tear down any services they started.
- **No-lifecycle harnesses** (`db-convos-tests` and its aggregator `db-tests`)
  MUST own no Postgres lifecycle — no `postgres-up`/`postgres-down`, no
  `db-init`/`db-migrate`, no `$POSTGRES_DATA_DIR` wipe — and MUST NOT register a
  teardown trap. They MUST assume a live, already-migrated database supplied by
  the caller.

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

- **`bin/test`**: Test orchestrator. Under `set -e`, runs in order:
  `postgres-up` → `db-destroy` → `db-init` → `db-migrate` → `db-tests` →
  `exec gradlew`. The `db-tests` step runs sequentially (never backgrounded),
  after migration and before the terminal `exec`; a non-zero shell-harness exit
  aborts the run before Gradle ever starts. Postgres is left up so the Gradle
  suite observes the same migrated database.
- **`bin/test-fuzz`**: Runs schemathesis fuzz tests against the REST API.
- **`bin/scripts-tests`**: Tests scripts in `bin/`.
- **`bin/db-scripts-tests`**: Tests `db-run`, `db-query`, `db-update`,
  `db-repl`, `db-init`, `db-migrate`, `db-status`, `db-destroy`.
- **`bin/db-users-tests`**: Tests the functionality of the `users` table.
- **`bin/q-scripts-tests`**: Tests `q-status`, `q-enqueue`, `q-inspect`,
  `q-retry`, `q-delete-job`, `q-truncate`.
- **`bin/db-tests`**: Aggregator that runs the shell DB schema harnesses
  sequentially against a caller-supplied, already-migrated database. Owns no
  Postgres lifecycle; aborts non-zero on the first harness failure.
- **`bin/db-convos-tests`**: Schema harness for the coaching-conversations
  tables (`convos`, `convo_requests`, `convo_responses`,
  `convo_responses_raw`). Owns no Postgres lifecycle.

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
  `export ENV_FILE=".../.env.test"` before sourcing `common`; `db-tests`
  defaults to `.env.test` but honors a caller-supplied `ENV_FILE`.
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
- [x] [RFC-32: Coaching Conversations](../rfc/32-coaching-conversations.md)
