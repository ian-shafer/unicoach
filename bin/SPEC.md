# SPEC: bin

## I. Overview

`bin/` contains the operational scripts for the Unicoach application. Scripts
fall into seven categories: build, daemon control, linting, CLIs, testing, iOS
deploy (system Xcode), and infrastructure & deploy. Every shell script sources
`bin/common` to establish `$PROJECT_ROOT` and a shared environment — **except**
the system-Xcode iOS scripts (`build-ios`, `install-ios`, `ios-scripts-tests`)
and the dev-shell predicate (`is-nix`), which source only `bin/functions`
because `bin/common` requires the Nix dev shell they must run outside of (see
§II). The single non-shell script, `bin/compile-skills.py`, is exempt from the
shell-script invariants in §II.

---

## II. Invariants

### Common Invariants

Every shell script MUST source `bin/common` as its first non-comment,
non-shebang line, optionally preceded by an `export ENV_FILE=…` assignment — the
only sanctioned pre-source statement. `bin/test` and most test harnesses use it
to select `.env.test`; `bin/test-fuzz` points it at `.env.fuzz` so the whole
child-process tree (postgres-up, db-reset, build-rest-server,
rest-server-up/down) binds to the dedicated fuzz DB and port 8082 (see §III
"Test Scripts"). `bin/common` establishes the following environment:

- Sets `set -euo pipefail`.
- Resolves and exports `PROJECT_ROOT` to the repository root.
- Sources `bin/functions` (shared function library).
- Loads `$ENV_FILE` (defaults to `$PROJECT_ROOT/.env`) into the process
  environment. Fatals if the file does not exist.
- Exports `PGPORT="$POSTGRES_PORT"`. `POSTGRES_PORT` is required; under `set -u`
  an unset value aborts here rather than defaulting.
- Exports `DB_SCHEMA_DIR` (defaults to `$PROJECT_ROOT/db/schema`), consumed by
  `db-migrate` and `db-status`.

**System-Xcode exception.** `is-nix`, `build-ios`, `install-ios`, and
`ios-scripts-tests` MUST NOT source `bin/common`; they source only
`bin/functions` (for `log-info`/`fatal`) and resolve `PROJECT_ROOT` themselves.
`bin/common` loads `.env` and assumes the Nix dev shell, but these scripts run
under the **system** Xcode toolchain — never `nix develop` — so sourcing it
would defeat their purpose. Bypassing `bin/common` does not mean they read no
environment: `build-ios` and `install-ios` perform their own loading of the
target file `ios-app/env/<target>.env`, the repo `.env`, and (for device work)
`ios-app/env/signing.env` (see §III). Unlike `bin/common`, which fatals on a
missing `$ENV_FILE`, `build-ios` tolerates a missing repo `.env` (it is sourced
only when present).

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

> **Sanctioned deviation:** `bin/test`'s `help()` error branch exits `2`, not
> the template's `1`, to keep caller-side usage errors distinct from a test-run
> failure's `1`. This is the Exit Codes multi-code rule below applied directly;
> see §III "Test Suites".

> **Sanctioned deviation:** the `infra-*` wrappers (`infra-init`, `infra-plan`,
> `infra-apply`, `infra-output`, `infra-bootstrap`) define no `help()`. Each is
> a one-line `exec tofu -chdir=… "$@"` passthrough, so `-h`/`--help` is
> forwarded to `tofu`, which owns the usage text. See §III "Infrastructure &
> Deploy Scripts".

### Exit Codes

- `0`: success.
- Non-zero: MUST be documented in the script's `help()` output. Scripts MUST
  define distinct non-zero codes when there are multiple failure reasons, so
  calling scripts can distinguish why a dependency failed. `bin/test` is the
  canonical instance: usage errors exit `2` (its `help()` error branch), a
  test-run failure propagates `gradlew`'s `1` through `exec`.

### Logging

All log output MUST go to stderr. Three functions:

| Function      | Prefix      | Side Effects                                    |
| ------------- | ----------- | ----------------------------------------------- |
| `log-info`    | None.       | None.                                           |
| `log-warning` | `[WARNING]` | None.                                           |
| `fatal`       | `[FATAL]`   | Exits with the given status code (default `1`). |

### Path Resolution

All scripts MUST refer to other scripts and files using absolute paths,
typically via `$PROJECT_ROOT` (e.g., `"$PROJECT_ROOT/bin/<script>"`).

### Shared Cluster

The PostgreSQL cluster is shared by every git worktree; isolation is
per-database only. Test harnesses that use the shared cluster MUST NOT stop or
wipe it. A harness that needs cluster-lifecycle control MUST stand up its own
private cluster (private `POSTGRES_DATA_DIR` + port).

### Cluster Lifecycle Ownership

The schema/DDL scripts — `db-create`, `db-create-role`, and `db-migrate` — MUST
connect to an already-running cluster reachable at `PGHOST:PGPORT` and MUST NOT
start or initialise one (no `initdb`, no `postgres-up`, no `pg_ctl`). Cluster
startup is owned by the environment: locally `bin/test` runs `postgres-up`
before `db-reset`, `bin/db-scripts-tests` stands up its own private cluster, and
in production managed RDS supplies the running cluster. The local and deploy
paths do not fork on cluster provisioning.

### Port Liveness

A script that must determine whether a TCP port is already served MUST probe it
with a pure-bash `/dev/tcp` connect (`exec 3<>/dev/tcp/127.0.0.1/$PORT` succeeds
iff something accepts the connection) — occupant-agnostic, dependency-free, and
reliable: it refuses instantly on a closed port with no hang and needs no
timeout. It MUST NOT use `nc -z` (reports bound ports as closed on BSD/macOS) or
an HTTP `curl` probe (misses a non-HTTP listener). `bin/test-fuzz` applies this
as a port guard: it fatals on an already-served target port before building or
booting its own rest-server.

---

## III. Behavioral Contracts

> Per-script argument details are intentionally omitted — run `<script> --help`
> or read the source. This section covers only non-obvious invariants and
> cross-script relationships.

### Shared Library

`bin/functions` exports the following public API, available in every script that
sources `bin/common`:

| Function                         | Signature                               | Output / Return | Notes                                                                                                                                                                                       |
| -------------------------------- | --------------------------------------- | --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `log-info`                       | `log-info <msg…>`                       | stderr          | No prefix.                                                                                                                                                                                  |
| `log-warning`                    | `log-warning <msg…>`                    | stderr          | Prefixes `[WARNING]`.                                                                                                                                                                       |
| `fatal`                          | `fatal [-s \|--status-code <n>] <msg…>` | stderr; exits   | Prefixes `[FATAL]`; exits with `<n>` (default `1`).                                                                                                                                         |
| `read-file-or-die`               | `read-file-or-die <file> [<code>]`      | stdout          | Cats file or calls `fatal -s <code>`.                                                                                                                                                       |
| `parse-duration-to-seconds`      | `parse-duration-to-seconds <dur>`       | stdout          | Converts `30s`/`5m`/`2h`/`1d` → integer seconds; bare integers treated as seconds.                                                                                                          |
| `validate_duration`              | `validate_duration <dur>`               | exit 0/1        | Accepts only `[0-9]+[smhd]` (unit suffix required).                                                                                                                                         |
| `transform_duration_to_postgres` | `transform_duration_to_postgres <dur>`  | stdout          | Converts `5m` → `"5 minutes"` for SQL `INTERVAL` literals. Calls `validate_duration` internally.                                                                                            |
| `require_dangerous_confirmation` | `require_dangerous_confirmation <desc>` | exit 0/1        | Interactive prompt; returns `0` on confirmation, `1` on EOF (empty line exits `0` via `exit 0`). Callers bypass this entirely by checking `--yes-i-really-want-to-do-this` before invoking. |

---

### Dangerous Operations

Scripts that destroy data (`db-drop`, `q-truncate`) MUST gate execution behind
`require_dangerous_confirmation` **unless** the caller passes
`--yes-i-really-want-to-do-this`.

---

### Concurrency Primitive: `file-lock`

Directory-based file lock. Scripts use `file-lock <lock-dir> <max-duration>` to
acquire a lock that automatically expires after `<max-duration>`. An optional
`--operation <OP>` writes the operation name into the lock directory, allowing
other lock readers to identify the holder. Without `--timeout`, the script fails
immediately if the lock is held; with `--timeout <dur>`, it polls via `wait-for`
until the lock is acquired or the timeout expires. Stale locks are broken
automatically. Callers MUST release the lock via a trap registered **after**
successful acquisition.

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

- **`daemon-up`**: Idempotent. Starts the daemon and writes the PID file. If the
  daemon is already running, does nothing. Only checks PID for liveness —
  callers MUST invoke `<service>-wait-for-health` separately.
- **`daemon-down`**: Idempotent. Sends `SIGTERM` with a grace period; escalates
  to `SIGKILL` if the process does not exit. Removes the PID file on completion.
  No-op if already stopped.
- **`daemon-check`**: Exits `0` if the daemon is running according to the PID,
  `1` otherwise.
- **`daemon-bounce`**: Idempotent. Stops the daemon if running, then starts it.
- **`daemon-status`**: Prints the status of all known services.

---

### PostgreSQL Lifecycle

PostgreSQL is **not** managed via `daemon-*` system. It uses `pg_ctl` as the
authoritative source of truth regarding its running status and manages its own
lifecycle (and PID file).

`bin/db-bootstrap` is the one-time, per-machine first step for setting up the
unicoach environment: it initialises the shared cluster and creates the
application role (delegating to `bin/db-create-role`). Thereafter `bin/db-reset`
(or `bin/db-create` then `bin/db-migrate`) provisions and migrates the
per-worktree database.

`bin/postgres-*` scripts can be used to manage the lifecycle of the PostgreSQL
server.

| Script                     | Delegates to                          | Exit contract                                                                                                                                |
| -------------------------- | ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `postgres-up`              | `pg_ctl start`                        | Exit `0` if started or already running. Fatals if `POSTGRES_DATA_DIR` is unset; fails the health wait if the cluster was never bootstrapped. |
| `postgres-down`            | `pg_ctl stop`, polls `postmaster.pid` | Exit `0` if stopped or already stopped.                                                                                                      |
| `postgres-bounce`          | `postgres-down` then `postgres-up`    | Exit `0` on success.                                                                                                                         |
| `postgres-check`           | `pg_isready`                          | Exit `0` if accepting connections at `PGHOST` (default `localhost`) on `$POSTGRES_PORT`, `1` otherwise. No side effects.                     |
| `postgres-wait-for-health` | `wait-for` + `postgres-check`         | Polls until `postgres-check` succeeds or timeout.                                                                                            |

---

### Daemons

Individual daemons use the Daemon Lifecycle scripts (§III) and follow the naming
convention `<service>-{up,down,bounce,check}`. A daemon MAY additionally provide
`<service>-wait-for-health`, which blocks until the daemon is healthy. "Healthy"
is defined by the logic in each script.

Current daemons: `rest-server`, `queue-worker`.

| Script                  | Behavior                                                                                                                                 |
| ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `<svc>-up`              | Fatals if the `installDist` binary is absent. Never invokes Gradle. Delegates to `daemon-up`, then `<svc>-wait-for-health` if it exists. |
| `<svc>-down`            | Delegates to `daemon-down`.                                                                                                              |
| `<svc>-bounce`          | Delegates to `daemon-bounce`.                                                                                                            |
| `<svc>-check`           | Runs the service's health check — an HTTP probe (`rest-server`) or PID liveness via `daemon-check` (`queue-worker`).                     |
| `<svc>-wait-for-health` | Optional. Blocks until the service-specific health check passes.                                                                         |

---

### Build Scripts

`bin/build` builds all Kotlin source. It delegates to per-module
`bin/build-<module>` scripts in dependency order:
`common → db → service → queue → net → rest-server → queue-worker`. Fast-fails
on the first module failure.

Each `bin/build-<module>` script runs exactly one `./gradlew` task via `exec`:
`:module:assemble` for libraries, `:module:installDist` for daemons.

A new Gradle module gets a `bin/build-<module>` script only when `bin/build`
must assemble its artifact directly; library modules consumed transitively by
the daemons (`email`, `chat`) are deliberately omitted. To add a directly-built
module:

1. Create `bin/build-<module>` following the existing template.
2. Insert it at the correct position in the ordered sequence inside `bin/build`.

---

### iOS Build & Install (system Xcode)

`build-ios`, `install-ios`, and the `is-nix` predicate run under the **system**
Xcode toolchain, never the Nix dev shell. Inside `nix develop`, `xcrun` is
shadowed by a stub and `DEVELOPER_DIR`/`SDKROOT` point into the Nix store, so a
build there silently targets the wrong toolchain. `build-ios` and `install-ios`
therefore call `is-nix --quiet` immediately after option parsing and `fatal`
before invoking any tool if it returns `0` — placed before env resolution so the
guard message wins over a missing-env error.

- **`is-nix`**: Predicate. Exit `0` if inside the dev shell (`IN_NIX_SHELL`
  non-empty), `1` otherwise. Prints `nix enabled` / `nix NOT enabled` via
  `log-info` unless `-q`/`--quiet`. `-v`/`--verbose` additionally lists package
  names parsed from the exported `nativeBuildInputs` (suppressed by `-q`). No
  side effects.
- **`build-ios [target]`**: Builds — and for device targets signs — the
  `UnicoachiOS` app via `exec xcodebuild`. `target` defaults to `local`; sources
  the target file `ios-app/env/<target>.env` for `UNICOACH_DESTINATION`
  (`UNICOACH_CONFIGURATION` optional, default `Debug`). (Help text and usage say
  "target"; the internal `ENV_*` variable names are retained.) The repo `.env`
  is sourced **unconditionally** on every path — it is the single source of
  `UNICOACH_CLIENT_KEY`, forwarded to `xcodebuild` verbatim as a build setting
  (a raw secret passed unchanged; an unset value bakes blank, so the app sends
  no client-key header) regardless of how the backend URL is resolved. The
  backend URL is **honor-if-set, else derive**:
  - **HONOR**: if the target file (or environment) set a non-empty
    `UNICOACH_BACKEND_URL`, it is forwarded to `xcodebuild` verbatim — no
    derivation and no bare-IP check. This carries externally-terminated HTTPS
    deployment URLs (e.g. `https://api.unicoachapp.com`) that the derived
    `http://host:port` form cannot express.
  - **DERIVE** (fallback when unset): bakes
    `UNICOACH_BACKEND_URL=http://$APP_DOMAIN:${SERVER_PORT:-8080}` from the repo
    `.env` (the single host source the server also reads); `APP_DOMAIN` defaults
    to `localhost`. A bare-IP `APP_DOMAIN` is rejected here (invalid cookie
    `Domain`, RFC 6265) — this check runs **only** on the derive path, so a
    stale bare-IP `.env` cannot break a build that honors an explicit URL.

  A simulator build (destination contains `Simulator`) skips signing; a device
  build additionally sources `ios-app/env/signing.env` for
  `UNICOACH_DEVELOPMENT_TEAM` (required) and forwards
  `CODE_SIGN_STYLE=Automatic
  -allowProvisioningUpdates`. Fatals before
  invoking `xcodebuild` on: dev shell, missing target file, missing
  `signing.env`/team, or (derive path only) a bare-IP `APP_DOMAIN`.

  **Checked-in deploy targets.** `prod` (device) and `prod-simulator`
  (simulator) both set `UNICOACH_BACKEND_URL=https://api.unicoachapp.com`
  explicitly, exercising the honor path. They carry no client key and are not
  secret, so they are shareable and committed. `.gitignore` ignores
  `ios-app/env/*.env` with explicit allowlist exceptions for `*.env.example`,
  `simulator.env`, `prod.env`, and `prod-simulator.env`; any other
  `<target>.env` (e.g. a contributor's `local.env`) stays untracked.

  **Reserved secret seam.** `ios-app/env/<target>.local.env` is a documented
  per-target secret-overlay seam — gitignored and intended to override after the
  target file/`.env`/`signing.env` — but it is **not implemented**: no code
  sources it today.
- **`install-ios [--launch] [target]`**: Installs the most recent device build
  to a physical iPhone via `xcrun devicectl device install app` (replaces any
  prior install in place — idempotent). `target` defaults to `local`;
  device-only, so a simulator target is rejected (use `prod` over
  `prod-simulator`). Resolves the `.app` under
  `ios-app/build/DerivedData/Build/Products/<config>-iphoneos/`, failing fast if
  absent (directs to `build-ios`). Always requires `ios-app/env/signing.env`
  (the home of `UNICOACH_DEVICE`) and fatals if it is absent, even when
  auto-detecting. Device id comes from `UNICOACH_DEVICE` when set, otherwise
  single-device auto-detect via `xcrun devicectl list devices` — fatals on zero
  or multiple devices. `--launch` additionally runs
  `xcrun devicectl device process launch` for bundle id
  `com.unicoach.UnicoachiOS`.

---

### Database Scripts

- **`db-bootstrap`**: One-time, per-machine setup. Idempotent and race-tolerant
  (concurrent worktrees cannot collide). `initdb`s the shared cluster if absent,
  ensures it is running (`postgres-up`), and creates the application role by
  delegating to `db-create-role`. Creates no database.
- **`db-create-role`**: Single source of truth for application-role creation.
  Creates the `DATABASE_USER` LOGIN role (password `DATABASE_PASSWORD`) via a
  `DO` block that swallows `duplicate_object`/`unique_violation`, so it is
  idempotent and race-tolerant. Connects as the master role (`POSTGRES_USER`) to
  the admin database (`POSTGRES_ADMIN_DB`, default `postgres`). Runs no
  `initdb`, so unlike `db-bootstrap` it is safe against a managed cluster (e.g.
  RDS). Accepts no positional arguments.
- **`db-create`**: Idempotent. Performs schema work only against an
  already-running cluster reachable at `PGHOST:PGPORT` — never starts or
  initialises one. Assumes the application role exists (crashes hard otherwise).
  Creates `POSTGRES_DB` if absent, grants `CONNECT` + schema access, sets
  `ALTER DEFAULT PRIVILEGES`, and creates `schema_migrations`. Applies no
  migrations.
- **`db-reset`**: `db-drop` → `db-create` → `db-migrate`. Yields a clean,
  fully-migrated database. The frequent per-run operation (e.g. `bin/test`);
  does no cluster or role work, so assumes `db-bootstrap` ran once.
- **`db-migrate`**: Applies pending migrations in lexicographical order, each
  wrapped in a transaction (written via `db-write`). Previously applied
  migrations are skipped by inspecting the `schema_migrations` table. Halts on
  failure.
- **`db-status`**: Reports the applied/pending state of all migration scripts.
  Auto-provisions the database via `db-create` if needed (assumes `db-bootstrap`
  already created the cluster and role).
- **`db-run`**: Execution primitive. `ro` mode enforces read-only transactions;
  exits `2` if postgres is unreachable.
- **`db-query`**, **`db-write`**: Thin wrappers over `db-run ro` and
  `db-run rw`.
- **`db-repl`**: Opens a `psql` session connected to the application database.
- **`db-drop`**: Drops a single database
  (`DROP DATABASE IF EXISTS … WITH
  FORCE`). Idempotent. Leaves the cluster and
  data directory intact. Gated by `require_dangerous_confirmation` unless
  `--yes-i-really-want-to-do-this`.

---

### Infrastructure & Deploy Scripts

OpenTofu is the sole source of AWS resource definitions; these scripts carry no
resource logic. All source `bin/common`, so a valid `$ENV_FILE` with
`POSTGRES_PORT` set is required even to run a pure-`tofu` action.

- **`infra-init`**, **`infra-plan`**, **`infra-apply`**, **`infra-output`**:
  thin `exec tofu -chdir="$PROJECT_ROOT/infra" <verb> "$@"` wrappers. Extra args
  (and `-h`/`--help`) pass through to `tofu`.
- **`infra-bootstrap`**: `exec tofu -chdir="$PROJECT_ROOT/infra/bootstrap" "$@"`
  — fronts the one-time state-backend setup. Run once as `infra-bootstrap init`
  then `infra-bootstrap apply`. The subcommand and extra args pass through.
- **`deploy`**: single operator entry point for shipping a release. Sequence:
  `bin/build` both `installDist` distributions → assemble a repo-relative
  tarball (`rest-server`/`queue-worker` dists, `db/schema`, and every `bin/`
  script the on-instance migration path transitively needs — the `db-*` family
  plus `postgres-check`, which gates every `db-run`; `postgres-up` and
  `postgres-wait-for-health` are deliberately excluded) → `aws s3 cp` to the
  `artifacts_bucket` → `aws ssm send-command` running `deploy-on-instance` on
  `instance_id`. Bucket and instance id are read from `tofu output -raw`, so a
  completed `infra-apply` and an active AWS session are prerequisites. Region
  resolves from `AWS_REGION` → `AWS_DEFAULT_REGION` → `us-east-1`. Rejects
  positional args and unknown options. Side-effecting and not idempotent — each
  run ships a new, timestamp-keyed bundle.

---

### Queue CLI Scripts

Scripts to inspect and mutate the application work queue. All delegate to
`db-query`/`db-write` and propagate exit `2` on database unreachability.

- **`q-status`**: Displays queue stats by job type.
- **`q-enqueue`**: Adds an item onto the queue.
- **`q-inspect`**: Full details for a single job by ID.
- **`q-retry`**: Resets a `DEAD_LETTERED` job back to `SCHEDULED`. Rejects jobs
  not in `DEAD_LETTERED` status.
- **`q-delete-job`**: Removes an item from the queue by ID.
- **`q-truncate`**: Removes all items from the queue.

---

### CI/CD

- **`bin/format`**: Runs `ktlint` (Kotlin) and `deno fmt` (Markdown)
  concurrently.
- **`bin/pre-commit`**: Runs the Kotlin gate (`bin/test check` — ktlint plus
  every module's tests) and the Markdown format check (`deno fmt --check`)
  concurrently. When this commit stages `api-specs/openapi.yaml`, it
  additionally runs `bin/test-fuzz` against the REST contract — sequentially,
  after the concurrent checks pass — and fails the commit on contract drift.
  `bin/dev-bootstrap` installs the git `pre-commit` hook that invokes this
  script.

---

### Test Scripts

Most test scripts set `export ENV_FILE=".../.env.test"` before sourcing
`common`; the aggregator `db-tests` defaults `ENV_FILE` to `.env.test` but
defers to a caller-supplied value, and `db-scripts-tests` instead points
`ENV_FILE` at a generated private env file (its own throwaway cluster).
Harnesses that make assertions source `bin/tests-common` for assertion helpers.
`bin/test-fuzz` instead points `ENV_FILE` at `.env.fuzz` (its own dedicated fuzz
DB and port). Five lifecycle-ownership models exist:

- **Private-cluster harnesses** (`db-scripts-tests`) MUST provision their OWN
  throwaway cluster (private `POSTGRES_DATA_DIR` + PID-derived `POSTGRES_PORT`),
  exercise cluster lifecycle freely, and tear it down via an EXIT/INT/TERM trap.
  They MUST NOT touch the shared cluster.
- **Self-contained daemon harnesses** (`scripts-tests`) MUST register an
  EXIT/INT/TERM trap to tear down the daemons they started (`rest-server`,
  `queue-worker`). They use the shared cluster (`postgres-up`) but MUST NOT stop
  it.
- **Shared-cluster reset harnesses** (`db-users-tests`, `q-scripts-tests`) MUST
  reach a clean state via `db-bootstrap` + `db-reset` against the per-worktree
  database, MUST NOT stop or wipe the shared cluster, and MUST NOT register a
  cluster teardown trap.
- **No-lifecycle harnesses** (`db-convos-tests`, `db-system-prompts-tests`, and
  their aggregator `db-tests`) MUST own no Postgres lifecycle — no
  `postgres-up`/`postgres-down`, no `db-reset`/`db-migrate`, no cluster wipe —
  and MUST NOT register a teardown trap. They MUST assume a live,
  already-migrated database supplied by the caller.
- **Dedicated-DB daemon harness** (`test-fuzz`) MUST bind `ENV_FILE` to
  `.env.fuzz` (its own per-worktree fuzz DB + port 8082), reach a clean state
  via `postgres-up` + `db-reset` against that dedicated DB, build and boot a
  fresh rest-server, and register an EXIT/INT/TERM trap that tears down ONLY the
  daemon it started. It uses the shared cluster but MUST NOT stop or wipe it.
- **No-Postgres harnesses** (`ios-scripts-tests`) own no Postgres lifecycle and
  source neither `bin/common` nor any cluster script. They shim
  `xcodebuild`/`xcrun` onto `PATH` (recording argv to a temp file) and redirect
  the scripts at fixtures via `UNICOACH_ENV_DIR`/`UNICOACH_DOTENV`/
  `UNICOACH_PRODUCTS_DIR`, with the `xcrun` shim emitting the per-test
  `DEVICECTL_LIST_OUTPUT` fixture to drive `install-ios` device auto-detect, so
  no real build or hardware runs. They `unset IN_NIX_SHELL` at startup and
  simulate the dev shell per-invocation with `IN_NIX_SHELL=impure`.

#### `bin/tests-common` — Assertion API

| Function         | Signature                     | Behavior                                                                                         |
| ---------------- | ----------------------------- | ------------------------------------------------------------------------------------------------ |
| `pass_test`      | `pass_test <msg>`             | Increments `TOTAL_TESTS`; prints green `✅ <msg>` to stdout.                                     |
| `fail_test`      | `fail_test <msg>`             | Prints red `❌ Fail: <msg>` to **stderr**; **exits `1` immediately**. No further assertions run. |
| `assert_success` | `assert_success <msg> <cmd…>` | Runs `<cmd>`; calls `fail_test` if it exits non-zero, otherwise calls `pass_test`.               |
| `assert_failure` | `assert_failure <msg> <cmd…>` | Runs `<cmd>`; calls `fail_test` if it exits **zero**, otherwise calls `pass_test`.               |
| `end_tests`      | `end_tests`                   | Prints total count summary. **Does not exit.** Must be the final statement in every test script. |

> `fail_test` exits immediately — subsequent assertions in the same script are
> skipped. Test functions that need early returns before calling `fail_test`
> must `return 1` and let the `assert_*` wrapper call `fail_test`.

#### Test Suites

- **`bin/test`**: Test orchestrator owning a closed, validated CLI. It parses
  its own options and never forwards raw arguments to `gradlew` (no `--`
  passthrough). Positional arguments are friendly module names
  (`common db net queue queue-worker rest-server service email chat` — a
  hardcoded set kept in sync with `settings.gradle.kts` by hand), each mapped to
  an explicit `:<module>:<task>` task; repeats are de-duplicated. The per-module
  `<task>` is `test` by default, or `check` when the optional leading `check`
  keyword is given (`bin/test check [module ...]`) — Gradle's `check` runs
  ktlint plus tests for a full Kotlin verification over the same Postgres
  lifecycle, and is the form the `bin/pre-commit` Kotlin gate invokes. `check`
  is a keyword, not a module and not an option. Unknown modules and unknown
  options are rejected, never forwarded. **Core invariant:** `bin/test` always
  emits at least one `:<module>:<task>` task — naming no module runs every
  module — so the flags-only/zero-task argv that made `gradlew` print a false
  "BUILD SUCCESSFUL" over zero tests is structurally impossible. Mapped options
  (`--tests GLOB` — requires exactly one module — `--force`, `--verbose`,
  `--continue`; see `--help` for spellings) follow the task list in fixed order.
  Argument validation completes **before** any side effect, so an invalid
  invocation (unknown module/option, `--tests` without exactly one module) fails
  without resetting the test database. Exit codes: usage errors exit `2` (the
  `help()` error branch, §II sanctioned deviation); a test run that executed but
  did not all pass propagates `gradlew`'s `1` unchanged through `exec`; success
  and `--help` exit `0`; downstream lifecycle failures propagate their own codes
  via `set -e`. After validation, under `set -e`, runs in order: `postgres-up` →
  `db-reset` → `db-tests` → `exec gradlew` with the constructed task list. The
  `db-tests` step runs sequentially (never backgrounded), after migration and
  before the terminal `exec`; a non-zero shell-harness exit aborts the run
  before Gradle ever starts. Postgres is left up so the Gradle suite observes
  the same migrated database. To filter to a single test, use the module +
  filter form `bin/test <module> --tests "<glob>"` (e.g.
  `bin/test queue --tests "*JobsDaoTest"`); the old bare-FQN positional shape
  (`bin/test ed.unicoach.queue.JobsDaoTest`) is dropped.
- **`bin/test-fuzz`**: Authenticated contract-referee fuzzer. Boots a
  freshly-built rest-server on its own fuzz DB/port (`.env.fuzz`) and runs
  Schemathesis against the committed `api-specs/openapi.yaml`. Ordered lifecycle
  under `set -e`: provision a pinned `schemathesis==4.21.5` venv
  (`var/fuzz/venv`, gitignored, on the flake python3; idempotent when the
  version already matches) → port-guard (fatal if `$PORT` is already served,
  before any build/boot) → `postgres-up` → `db-reset` the dedicated fuzz DB →
  `build-rest-server` → `rest-server-up` → register a uniquely-named user and
  capture its `UNICOACH_SESSION` cookie → run Schemathesis as a child (not
  `exec`) so the EXIT/INT/TERM trap tears down the rest-server. The captured
  cookie is injected via `-H "Cookie: …"` on every request, exercising the
  authenticated surface. Excludes, by category: unimplemented routes
  (`/api/v1/conversations*`), session-destructive operations (`logoutUser`,
  `deleteStudentMe`), and the single non-applicable check `ignored_auth` (a
  false positive under static cookie injection); the full `--checks all` set —
  including `unsupported_method` and every data-conformance check — otherwise
  runs. As a referee it surfaces — never masks — non-conformance: it runs the
  full check set and EXITS `0` against a conformant server, reporting any
  contract drift as a non-zero exit. A `413` (an oversized body rejected by the
  application-scope `RequestBodyLimit`) is accepted as a valid
  `negative_data_rejection` via the committed root `schemathesis.toml`, passed
  to Schemathesis with `--config-file`.
- **`bin/scripts-tests`**: Tests scripts in `bin/`.
- **`bin/db-scripts-tests`**: Tests `db-run`, `db-query`, `db-write`, `db-repl`,
  `db-bootstrap`, `db-create`, `db-migrate`, `db-status`, `db-drop` against its
  OWN private throwaway cluster (private data dir + PID-derived port), never the
  shared cluster.
- **`bin/db-users-tests`**: Tests `db-create-role` (LOGIN-role creation and
  idempotence, exercised against a sentinel role via a temp `ENV_FILE` so the
  shared `DATABASE_USER` is never disturbed) and the `users` table.
- **`bin/q-scripts-tests`**: Tests `q-status`, `q-enqueue`, `q-inspect`,
  `q-retry`, `q-delete-job`, `q-truncate`.
- **`bin/db-tests`**: Aggregator that runs the shell DB schema harnesses
  sequentially against a caller-supplied, already-migrated database. Owns no
  Postgres lifecycle; aborts non-zero on the first harness failure.
- **`bin/db-convos-tests`**: Schema harness for the coaching-conversations
  tables (`convos`, `convo_requests`, `convo_responses`, `convo_responses_raw`).
  Owns no Postgres lifecycle.
- **`bin/db-system-prompts-tests`**: Schema harness for the immutable
  `system_prompts` catalog and the `convo_requests.system_prompt_id` FK rewire.
  Owns no Postgres lifecycle.
- **`bin/ios-scripts-tests`**: Tests `is-nix`, `build-ios`, and `install-ios`
  with shimmed `xcodebuild`/`xcrun` and fixture env files. Runs no real build
  and needs no hardware, Postgres, or Nix dev shell.

---

### Developer Tools

**`bin/dev-bootstrap`**: verifies `nix` is installed, installs the git
`pre-commit` hook (a thin shim that delegates to `bin/pre-commit`, since
`.git/hooks` is not version-controlled), and prints activation instructions.

**`bin/compile-skills.py`**: Python; exempt from the shell-script invariants in
§II (sources nothing, defines no `help()`). Regenerates the aggregated
`.agents/skills/*-review-chain/SKILL.md` files from the `*-review-*`
micro-skills. Idempotent; overwrites the generated files in place.

---

## IV. Infrastructure & Environment

- **Shell**: All scripts target `bash` via `#!/usr/bin/env bash`.
- **Toolchain**: Provided by `flake.nix` (`temurin-bin-21`, `postgresql_18`,
  `python3`, `deno`, `ktlint`, `git`).
- **System Xcode (iOS scripts)**: `build-ios`, `install-ios`, and `is-nix`
  require the **system** Xcode toolchain (`xcodebuild`, `xcrun devicectl`), NOT
  the flake, and MUST run outside `nix develop`. `build-ios` reads
  `UNICOACH_CLIENT_KEY` (and, on the derive path, `APP_DOMAIN`/`SERVER_PORT`)
  from the repo `.env` and `UNICOACH_DESTINATION`/`UNICOACH_CONFIGURATION`
  (optionally an explicit `UNICOACH_BACKEND_URL`) from the target file
  `ios-app/env/<target>.env`; device builds also read
  `UNICOACH_DEVELOPMENT_TEAM`/`UNICOACH_DEVICE` from `ios-app/env/signing.env`.
  Help text and usage use "target" terminology while the internal `ENV_*`
  variable names are retained. `is-nix` keys off `IN_NIX_SHELL` (and
  `nativeBuildInputs` under `-v`). Test overrides `UNICOACH_ENV_DIR`,
  `UNICOACH_DOTENV`, `UNICOACH_PRODUCTS_DIR`, and `DEVICECTL_LIST_OUTPUT`
  redirect these inputs to fixtures.
- **Runtime directories**: `var/run/` (PID files `<service>.pid` and lock dirs
  `<service>.daemon.lock`) and `var/log/` (service logs) are created on demand
  by `daemon-up` and `postgres-up`. `bin/test-fuzz` creates `var/fuzz/` on
  demand (gitignored Schemathesis venv, JUnit report, cookie jar).
- **Environment files**: `.env` (dev), `.env.test` (test), `.env.fuzz` (fuzz;
  `PORT=8082`, `POSTGRES_DB=unicoach-fuzz-<worktree>`). Test scripts set
  `export ENV_FILE=".../.env.test"` before sourcing `common`; `db-tests`
  defaults to `.env.test` but honors a caller-supplied `ENV_FILE`; `test-fuzz`
  sets `export ENV_FILE=".../.env.fuzz"`.
- **Required env**: `POSTGRES_PORT` MUST be set (no in-code default);
  `bin/common` exports it as `PGPORT` for all libpq clients. `DB_SCHEMA_DIR` is
  optional; `bin/common` exports it, defaulting to `$PROJECT_ROOT/db/schema`.
  `PGHOST` is optional and is NOT exported by `bin/common`; it flows from
  `$ENV_FILE` via `set -a` and defaults to `localhost`. On the deploy instance
  it carries the RDS address, selecting the cluster host for `postgres-check`
  and, via libpq, `db-run`/`psql`. `PORT` sets the per-env service port
  (`.env`=8080, `.env.test`=8081, `.env.fuzz`=8082); each env file derives the
  daemon bind variable `SERVER_PORT=$PORT`. `test-fuzz` reads `$PORT` for its
  port guard, boot, registration, and fuzz target. `db-create-role` (and
  `db-bootstrap` via it) additionally require `DATABASE_USER`,
  `DATABASE_PASSWORD`, and `POSTGRES_USER`, and honor `POSTGRES_ADMIN_DB`
  (default `postgres`). `deploy` additionally honors
  `AWS_REGION`/`AWS_DEFAULT_REGION` (default `us-east-1`).
- **Shared cluster, per-database isolation**: every git worktree shares one
  PostgreSQL cluster at the checkout-independent absolute path
  `$HOME/var/unicoach/postgres` (`POSTGRES_DATA_DIR`). Isolation is at the
  database level; `.env.test` and `.env.fuzz` each derive a per-worktree DB
  (`unicoach-test-<worktree-dir>`, `unicoach-fuzz-<worktree-dir>`).

---

## V. History

- [x] [RFC-02: Hello World OpenAPI Spec](../rfc/02-hello-world-open-api-spec.md)
- [x] [RFC-03: Daemon Scripts](../rfc/03-daemon-scripts.md)
- [x] [RFC-04: Postgres Daemon Scripts](../rfc/04-postgres-daemon-scripts.md)
- [x] [RFC-05: DB Scripts](../rfc/05-db-scripts.md)
- [x] [RFC-17: Queue Worker Daemon](../rfc/17-queue-worker-daemon.md)
- [x] [RFC-18: Docker Infrastructure Hardening](../rfc/18-docker-infrastructure-hardening.md)
- [x] [RFC-19: Daemon Health Marker](../rfc/19-daemon-health-marker.md)
- [x] [RFC-20: Test Environment Isolation](../rfc/20-test-environment-isolation.md)
- [x] [RFC-23: Native Daemon Scripts](../rfc/23-native-daemon-scripts.md)
- [x] [RFC-32: Coaching Conversations](../rfc/32-coaching-conversations.md)
- [x] [RFC-33: System Prompts](../rfc/33-system-prompts.md)
- [x] [RFC-35: bin/test owns its CLI](../rfc/35-bin-test-owns-cli.md)
- [x] [RFC-43: Chat Provider](../rfc/43-chat-provider.md)
- [x] [RFC-47: Authenticated Contract-Referee Fuzzing](../rfc/47-authenticated-contract-fuzz.md)
- [x] [RFC-50: Deploy the Backend REST API to AWS](../rfc/50-deploy-rest-api-aws.md)
- [x] [RFC-51: iOS Deploy to Physical Device](../rfc/51-ios-deploy-to-device.md)
- [x] [RFC-52: Make the REST Surface Fuzz-Clean](../rfc/52-make-rest-surface-fuzz-clean.md)
      — switched the port guard to a pure-bash `/dev/tcp` probe; dropped the
      `unsupported_method` exclusion and the non-zero-by-design framing so the
      referee runs `--checks all` and exits `0` against the conformant server;
      accepts a `413` as a valid `negative_data_rejection` via
      `schemathesis.toml`.
- [x] [RFC-54: Client-Key Gate](../rfc/54-client-key-gate.md)
- [x] [RFC-55: Cluster-Lifecycle-Agnostic DB Scripts](../rfc/55-cluster-lifecycle-agnostic-db-scripts.md)
      — made `db-create` schema-only, dropping the `postgres-up` coupling so the
      DDL/migration scripts never start a cluster; `postgres-check` honors
      `PGHOST`; added `postgres-check` to the deploy bundle.
- [x] [RFC-59: iOS Build Targets](../rfc/59-ios-build-targets.md) — made
      `build-ios`'s backend URL honor-if-set-else-derive (an explicit
      `UNICOACH_BACKEND_URL` is forwarded verbatim, skipping derivation and the
      bare-IP check), sourced the repo `.env` unconditionally as the single
      source of `UNICOACH_CLIENT_KEY`, added the checked-in
      `prod`/`prod-simulator` targets, adopted "target" help terminology, and
      documented (but did not implement) the `<target>.local.env` secret seam.
