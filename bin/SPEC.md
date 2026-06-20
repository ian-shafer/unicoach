# SPEC: bin

## I. Overview

`bin/` contains the operational scripts for the Unicoach application. Scripts
fall into seven categories: build, daemon control, linting, CLIs, testing, iOS
deploy (system Xcode), and infrastructure & deploy. Every shell script sources
`bin/common` to establish `$PROJECT_ROOT` and a shared environment — **except**
the system-Xcode iOS scripts (`build-ios`, `install-ios`, `release-ios`,
`ios-scripts-tests`) and the dev-shell predicate (`is-nix`), which source only
`bin/functions` because `bin/common` requires the Nix dev shell they must run
outside of (see [`INVARIANTS.md`](./INVARIANTS.md)). The single non-shell
script, `bin/compile-skills.py`, is exempt from the shell-script invariants (see
[`INVARIANTS.md`](./INVARIANTS.md)).

---

## II. Behavioral Contracts

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

Individual daemons use the Daemon Lifecycle scripts (§II) and follow the naming
convention `<service>-{up,down,bounce,check}`. A daemon MAY additionally provide
`<service>-wait-for-health`, which blocks until the daemon is healthy. "Healthy"
is defined by the logic in each script.

Current daemons: `rest-server`, `queue-worker`, `admin-server`, `public-web`.

| Script                  | Behavior                                                                                                                                                          |
| ----------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `<svc>-up`              | Fatals if the `installDist` binary is absent. Never invokes Gradle. Delegates to `daemon-up`, then `<svc>-wait-for-health` if it exists.                          |
| `<svc>-down`            | Delegates to `daemon-down`.                                                                                                                                       |
| `<svc>-bounce`          | Runs `<svc>-down` then `<svc>-up` (e.g. `rest-server`, `queue-worker`, `admin-server`, `public-web`).                                                             |
| `<svc>-check`           | Runs the service's health check — an HTTP `GET /healthz` probe (`rest-server`, `admin-server`, `public-web`) or PID liveness via `daemon-check` (`queue-worker`). |
| `<svc>-wait-for-health` | Optional. Blocks until the service-specific health check passes.                                                                                                  |

`admin-server-check` and `admin-server-wait-for-health` probe `GET /healthz` on
`ADMIN_SERVER_PORT` (default `8081`) — a dedicated port variable, distinct from
the rest-server's `PORT`, because the admin server runs as a separate process
alongside the rest-server.

`public-web-check` and `public-web-wait-for-health` probe `GET /healthz` on
`PUBLIC_WEB_PORT` (default `8082`) via `curl -sf` — a dedicated port variable,
distinct from `PORT` and `ADMIN_SERVER_PORT`, because the public-web server runs
as its own separate process. `public-web-up` fatals if the `installDist` binary
at `public-web/build/install/public-web/bin/public-web` is absent (directing to
`bin/build-public-web`), starts the daemon via `daemon-up`, then blocks on
`public-web-wait-for-health` (a `wait-for` poll of `public-web-check`, default
timeout `4s`). `public-web-bounce` runs `public-web-down` then `public-web-up`.
`admin-server-bounce` likewise runs `admin-server-down` then `admin-server-up`.

---

### Build Scripts

`bin/build` builds all Kotlin source. It delegates to per-module
`bin/build-<module>` scripts in dependency order:
`common → db → service → queue → net → rest-server → queue-worker → admin-server → public-web`.
`public-web` depends only on `:common`, so its build position is unconstrained;
it is appended last. Fast-fails on the first module failure.

Each `bin/build-<module>` script runs exactly one `./gradlew` task via `exec`:
`:module:assemble` for libraries, `:module:installDist` for daemons.

A new Gradle module gets a `bin/build-<module>` script only when `bin/build`
must assemble its artifact directly; library modules consumed transitively by
the daemons (`email`, `chat`) are deliberately omitted. To add a directly-built
module:

1. Create `bin/build-<module>` following the existing template.
2. Insert it at the correct position in the ordered sequence inside `bin/build`.

---

### iOS Build, Install & Release (system Xcode)

`build-ios`, `install-ios`, `release-ios`, and the `is-nix` predicate run under
the **system** Xcode toolchain, never the Nix dev shell. Inside `nix develop`,
`xcrun` is shadowed by a stub and `DEVELOPER_DIR`/`SDKROOT` point into the Nix
store, so a build there silently targets the wrong toolchain. `build-ios`,
`install-ios`, and `release-ios` therefore call `is-nix --quiet` immediately
after option parsing and `fatal` before invoking any tool if it returns `0` —
placed before env resolution so the guard message wins over a missing-env error.

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
  `com.unicoachapp.UnicoachiOS`.
- **`release-ios [--no-upload] [target]`**: Archives the `UnicoachiOS` app for
  App Store distribution and, by default, uploads it to App Store Connect for
  TestFlight via `exec xcodebuild -exportArchive`. Architect-invoked,
  side-effecting, and NOT idempotent — each upload consumes a unique build
  number. `target` defaults to `prod` (not `local`). Like `build-ios` it runs
  under system Xcode (shares the `is-nix --quiet` guard), sources only
  `bin/functions`, resolves the backend URL **honor-if-set, else derive**
  (identical rules), and reads `UNICOACH_CLIENT_KEY` from the repo `.env`. It
  differs deliberately:
  - **Release-only, signed.** The configuration is forced to `Release` (the
    target's `UNICOACH_CONFIGURATION` is ignored) and a simulator target is
    rejected. `ios-app/env/signing.env` and `UNICOACH_DEVELOPMENT_TEAM` are
    mandatory; the archive is signed for distribution with
    `CODE_SIGN_STYLE=Automatic -allowProvisioningUpdates`.
  - **Unique build number.** `CURRENT_PROJECT_VERSION` is
    `UNICOACH_BUILD_NUMBER` if set, else `git rev-list --count HEAD`; an
    optional `UNICOACH_MARKETING_VERSION` overrides `MARKETING_VERSION`. Both
    are forwarded as build settings and resolved into `Info.plist`
    (`CFBundleVersion` / `CFBundleShortVersionString`).
  - **Upload vs export.** The default path uploads to App Store Connect using an
    App Store Connect API key forwarded as `-authenticationKey*` flags, read
    from `ios-app/env/appstore.env` (gitignored secret bucket:
    `UNICOACH_ASC_KEY_ID`, `UNICOACH_ASC_ISSUER_ID`, `UNICOACH_ASC_KEY_PATH`).
    `--no-upload` stops after exporting a signed `.ipa` under
    `ios-app/build/export/` and requires no credentials.

  Resolves upload credentials **before** the expensive archive (fail-fast).
  Fatals before invoking `xcodebuild` on: dev shell, missing target file,
  simulator target, missing `signing.env`/team, (derive path only) a bare-IP
  `APP_DOMAIN`, (upload path) missing `appstore.env` or a missing key file at
  `UNICOACH_ASC_KEY_PATH`, or an indeterminable build number.

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

### Admin Bootstrap

- **`admin-grant <email>`**: Bootstraps the first admin. In one `psql`
  transaction, sets `is_admin = true` and bumps `version` for the single
  **active** (`deleted_at IS NULL`) user matching `<email>`, normalizing the
  argument to the stored lowercased/trimmed form before lookup. Side-effecting
  (one versioned `users` row write, captured in `users_versions` via the DB
  triggers); **observably repeatable** — re-running bumps `version` again, it is
  not idempotent. Fatals if PostgreSQL is offline, on a `psql` failure, or when
  no active user matches the email. Connects as `POSTGRES_USER` to
  `POSTGRES_DB`. See the Admin-Grant Bootstrap Exception invariant in
  [`INVARIANTS.md`](./INVARIANTS.md).

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
  `UNICOACH_PRODUCTS_DIR`/`UNICOACH_BUILD_DIR`, with the `xcrun` shim emitting
  the per-test `DEVICECTL_LIST_OUTPUT` fixture to drive `install-ios` device
  auto-detect, so no real build or hardware runs. They `unset IN_NIX_SHELL` at
  startup and simulate the dev shell per-invocation with `IN_NIX_SHELL=impure`.

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
  (`common db net queue queue-worker rest-server service email chat admin-server
  public-web`
  — a hardcoded set kept in sync with `settings.gradle.kts` by hand), each
  mapped to an explicit `:<module>:<task>` task; repeats are de-duplicated. The
  per-module `<task>` is `test` by default, or `check` when the optional leading
  `check` keyword is given (`bin/test check [module ...]`) — Gradle's `check`
  runs ktlint plus tests for a full Kotlin verification over the same Postgres
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
  `help()` error branch, a sanctioned deviation per
  [`INVARIANTS.md`](./INVARIANTS.md)); a test run that executed but did not all
  pass propagates `gradlew`'s `1` unchanged through `exec`; success and `--help`
  exit `0`; downstream lifecycle failures propagate their own codes via
  `set -e`. After validation, under `set -e`, runs in order: `postgres-up` →
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
- **`bin/ios-scripts-tests`**: Tests `is-nix`, `build-ios`, `install-ios`, and
  `release-ios` with shimmed `xcodebuild`/`xcrun` and fixture env files. Runs no
  real build and needs no hardware, Postgres, or Nix dev shell.

---

### Developer Tools

**`bin/dev-bootstrap`**: verifies `nix` is installed, installs the git
`pre-commit` hook (a thin shim that delegates to `bin/pre-commit`, since
`.git/hooks` is not version-controlled), and prints activation instructions.

**`bin/compile-skills.py`**: Python; exempt from the shell-script invariants in
[`INVARIANTS.md`](./INVARIANTS.md) (sources nothing, defines no `help()`).
Regenerates the aggregated `.agents/skills/*-review-chain/SKILL.md` files from
the `*-review-*` micro-skills. Idempotent; overwrites the generated files in
place.

---

## III. Infrastructure & Environment

- **Shell**: All scripts target `bash` via `#!/usr/bin/env bash`.
- **Toolchain**: Provided by `flake.nix` (`temurin-bin-21`, `postgresql_18`,
  `python3`, `deno`, `ktlint`, `git`).
- **System Xcode (iOS scripts)**: `build-ios`, `install-ios`, `release-ios`, and
  `is-nix` require the **system** Xcode toolchain (`xcodebuild`,
  `xcrun devicectl`), NOT the flake, and MUST run outside `nix develop`.
  `build-ios` reads `UNICOACH_CLIENT_KEY` (and, on the derive path,
  `APP_DOMAIN`/`SERVER_PORT`) from the repo `.env` and
  `UNICOACH_DESTINATION`/`UNICOACH_CONFIGURATION` (optionally an explicit
  `UNICOACH_BACKEND_URL`) from the target file `ios-app/env/<target>.env`;
  device builds also read `UNICOACH_DEVELOPMENT_TEAM`/`UNICOACH_DEVICE` from
  `ios-app/env/signing.env`. `release-ios` additionally reads the App Store
  Connect API key
  (`UNICOACH_ASC_KEY_ID`/`UNICOACH_ASC_ISSUER_ID`/`UNICOACH_ASC_KEY_PATH`) from
  the gitignored `ios-app/env/appstore.env` on the upload path, and honors
  `UNICOACH_BUILD_NUMBER`/`UNICOACH_MARKETING_VERSION` for versioning. Help text
  and usage use "target" terminology while the internal `ENV_*` variable names
  are retained. `is-nix` keys off `IN_NIX_SHELL` (and `nativeBuildInputs` under
  `-v`). Test overrides `UNICOACH_ENV_DIR`, `UNICOACH_DOTENV`,
  `UNICOACH_PRODUCTS_DIR`, `UNICOACH_BUILD_DIR`, and `DEVICECTL_LIST_OUTPUT`
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
  port guard, boot, registration, and fuzz target. `ADMIN_SERVER_PORT` (default
  `8081`) is the admin daemon's bind port, read by
  `admin-server-check`/`admin-server-wait-for-health`, and is independent of
  `PORT`. `PUBLIC_WEB_PORT` (default `8082`) is the public-web daemon's bind
  port, read by `public-web-check`/`public-web-wait-for-health`, and is likewise
  independent of `PORT` and `ADMIN_SERVER_PORT`. `db-create-role` (and
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

## IV. History

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
- [x] [RFC-45: Coaching Service and Conversation REST Surface](../rfc/45-coaching-service.md)
- [x] [RFC-47: Authenticated Contract-Referee Fuzzing](../rfc/47-authenticated-contract-fuzz.md)
- [x] [RFC-50: Deploy the Backend REST API to AWS](../rfc/50-deploy-rest-api-aws.md)
- [x] [RFC-51: iOS Deploy to Physical Device](../rfc/51-ios-deploy-to-device.md)
- [x] [RFC-52: Make the REST Surface Fuzz-Clean](../rfc/52-make-rest-surface-fuzz-clean.md)
      — switched the port guard to a pure-bash `/dev/tcp` probe; dropped the
      `unsupported_method` exclusion and the non-zero-by-design framing so the
      referee runs `--checks all` and exits `0` against the conformant server;
      accepts a `413` as a valid `negative_data_rejection` via
      `schemathesis.toml`.
- [x] [RFC-53: `/healthz` Liveness Endpoint](../rfc/53-healthz-liveness-endpoint.md)
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
- [x] [RFC-60: Admin Website (Framework + Users Spine)](../rfc/60-admin-website.md)
      — added `build-admin-server` and the
      `admin-server-{up,down,check,wait-for-health}` daemon scripts, plus
      `admin-grant` (the sole sanctioned raw-SQL entity mutation, minting the
      first admin); wired `admin-server` into `bin/test` and
      `build-admin-server` into `bin/build`.
- [x] [RFC-61: Public Web Module (Dynamic HTML via Shared Layout)](../rfc/61-static-marketing-site.md)
      — added `build-public-web` and the
      `public-web-{up,down,bounce,check,wait-for-health}` daemon scripts (HTTP
      `GET /healthz` health probe on `PUBLIC_WEB_PORT`, default `8082`);
      backfilled `admin-server-bounce` (`admin-server-down` then
      `admin-server-up`); wired `public-web` into `bin/test` `MODULES` and
      `build-public-web` last into `bin/build`.
