# SPEC: bin

## I. Overview

`bin/` contains the operational scripts for the Unicoach application. Scripts
fall into seven categories: build, daemon control, linting, CLIs, testing, iOS
deploy (system Xcode), and infrastructure & deploy. Every shell script sources
`bin/common` to establish `$PROJECT_ROOT` and a shared environment — **except**
the system-Xcode iOS scripts (`build-ios`, `install-ios`, `release-ios`,
`ios-scripts-tests`) and the dev-shell predicate (`is-nix`), which source only
`bin/functions` because `bin/common` requires the Nix dev shell they must run
outside of (see [`INVARIANTS.md`](./INVARIANTS.md)). Two non-shell scripts —
`bin/compile-skills.py` and the test-only `bin/test-tcp-listener` (a Python
foreign-listener helper for the scripts harness, see Test Scripts) — are
`python3` files exempt from the shell-script invariants (see
[`INVARIANTS.md`](./INVARIANTS.md)).

---

## II. Behavioral Contracts

> Per-script argument details are intentionally omitted — run `<script> -h` or
> read the source. This section covers only non-obvious invariants and
> cross-script relationships.
>
> **Option parsing.** Every option-bearing script parses with bash `getopts`
> over a **leading-colon optstring** (e.g. `getopts ":hp:"`), so getopts is
> silent and the script owns its own diagnostics: a `\?` (unknown option) arm
> and a `:` (missing required argument) arm each route to `fatal -s` with the
> appropriate usage-error band code (10 or 11). Options are **short-only** —
> there are no GNU-style long spellings; every long form was collapsed to its
> short flag (`-h` help, `-p` port, `-q` quiet, `-l` launch, `-n` no-upload,
> `-f` format/force, `-m` max-attempts, `-d` delay, `-s` status-code, `-y`
> confirm-dangerous, `-t` test-filter/timeout, `-o` operation). Scripts that
> intermix options with positionals (`bin/test`, `q-truncate`) wrap getopts in
> an outer loop that resets `OPTIND=1` each round.

### Shared Library

`bin/functions` exports the following public API, available in every script that
sources `bin/common`:

| Function                         | Signature                               | Output / Return | Notes                                                                                                                                                                                    |
| -------------------------------- | --------------------------------------- | --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `log-info`                       | `log-info <msg…>`                       | stderr          | No prefix.                                                                                                                                                                               |
| `log-warning`                    | `log-warning <msg…>`                    | stderr          | Prefixes `[WARNING]`.                                                                                                                                                                    |
| `log-error`                      | `log-error <msg…>`                      | stderr          | Prefixes `[ERROR]`.                                                                                                                                                                      |
| `fatal`                          | `fatal [-s <n>] <msg…>`                 | stderr; exits   | Prefixes `[FATAL]`; exits with `<n>` (default `1`). Parses `-s` via `getopts`; an unknown option to `fatal` itself prints `[FATAL]` and exits `1`.                                       |
| `read-file-or-die`               | `read-file-or-die <file> [<code>]`      | stdout          | Cats file or calls `fatal -s <code>`.                                                                                                                                                    |
| `parse-duration-to-seconds`      | `parse-duration-to-seconds <dur>`       | stdout          | Converts `30s`/`5m`/`2h`/`1d` → integer seconds; bare integers treated as seconds.                                                                                                       |
| `validate_duration`              | `validate_duration <dur>`               | exit 0/1        | Accepts only `[0-9]+[smhd]` (unit suffix required).                                                                                                                                      |
| `validate_port`                  | `validate_port <port>`                  | exit 0/1        | Returns `0` iff `<port>` is an integer in `1..65535`. Single owner of the port-range rule; used by `check-port`, `daemon-up`, `daemon-check`, `daemon-http-check`, and `find-free-port`. |
| `transform_duration_to_postgres` | `transform_duration_to_postgres <dur>`  | stdout          | Converts `5m` → `"5 minutes"` for SQL `INTERVAL` literals. Calls `validate_duration` internally.                                                                                         |
| `require_dangerous_confirmation` | `require_dangerous_confirmation <desc>` | exit 0/1        | Interactive prompt; returns `0` on confirmation, `1` on EOF (empty line exits `0` via `exit 0`). Callers bypass this entirely by checking the `-y` flag before invoking.                 |

`bin/functions` also defines the **usage-error exit-code band** (10–29) as
assign-if-unset constants, available to every script that sources `bin/common`
(and to `is-nix`, which sources only `bin/functions`):

| Constant                     | Value | Category                                               |
| ---------------------------- | ----- | ------------------------------------------------------ |
| `EXIT_UNKNOWN_OPTION`        | `10`  | `getopts '?'` — option the script does not define      |
| `EXIT_OPTION_REQUIRES_VALUE` | `11`  | `getopts ':'` — defined option given without its value |
| `EXIT_UNEXPECTED_ARG`        | `20`  | positional beyond the declared grammar                 |
| `EXIT_MISSING_REQUIRED_ARG`  | `21`  | required positional absent                             |
| `EXIT_INVALID_ARG_VALUE`     | `22`  | present value that is malformed (bad port, bad mode)   |
| `EXIT_EXCLUSIVE_OPTIONS`     | `23`  | two options that cannot combine (e.g. `-r` with `-x`)  |

Each is assigned with `: "${VAR:=N}"` so an `.env` entry sourced after
`bin/functions` can override it; operational/runtime outcomes use 1–9 and are
never reassigned to 10–29. Usage errors across every operational script route
through `fatal -s "$EXIT_<category>" "<message>"` rather than the `help()`
banner. `bin/functions`' own `fatal` internal parse guard exits `1`, not a band
code — it is a library-primitive error, not a CLI surface.

---

### Dangerous Operations

Scripts that destroy data (`db-drop`, `q-truncate`) gate execution behind
`require_dangerous_confirmation` unless the caller passes `-y`.

---

### Concurrency Primitive: `file-lock`

Directory-based file lock. Scripts use `file-lock <lock-dir> <max-duration>` to
acquire a lock that automatically expires after `<max-duration>`. An optional
`-o <OP>` writes the operation name into the lock directory, allowing other lock
readers to identify the holder. Without `-t`, the script fails immediately if
the lock is held; with `-t <dur>`, it polls via `wait-for` until the lock is
acquired or the timeout expires. Stale locks are broken automatically. Callers
register a trap after successful acquisition to release the lock on exit.

Exit codes:

- **Exit 0**: lock acquired.
- **Exit 1**: lock held by a conflicting operation; timed out.
- **Exit 2**: lock metadata could not be read.
- **Exit 3**: lock already held by the **same** operation — same-operation
  callers treat this as a success and exit `0` without retrying.

---

### Polling Primitive: `wait-for`

Waits for `<command>` to exit `0`. Sleeps `<period>` (default `1s`) between
retries. On timeout, exits with the status of the last `<command>` invocation.

---

### PID Liveness: `check-pid`

Exits `0` if the given PID is running, `1` otherwise.

---

### Port Liveness: `check-port`

`check-port <port>` passively probes whether `127.0.0.1:<port>` has a listener:
a single pure-bash `exec 3<>/dev/tcp/127.0.0.1/<port>` connect, immediately
closed — occupant-agnostic, with no `nc`/`curl`/`lsof` dependency. The loopback
connect accepts or refuses instantly, so no timeout wrapper is needed.

Exit codes:

- **Exit 0**: port is in use (connect succeeded).
- **Exit 1**: port is free (the loopback connect was refused).
- **Exit 21** (`EXIT_MISSING_REQUIRED_ARG`): port argument absent.
- **Exit 22** (`EXIT_INVALID_ARG_VALUE`): port is non-numeric or out of range.

`0`=in-use deliberately inverts `check-pid`'s `0`=alive, so each primitive's
success exit answers its own question (`is the port taken?` vs
`is the PID
alive?`) and the natural `if`-true branch reads correctly at every
call site.

---

### Daemon HTTP Health: `daemon-http-check`

`daemon-http-check <service-label> <port> <health-url>` is a daemon-aware HTTP
health probe that composes `curl -sf` (HTTP health) with `check-port` (TCP
liveness), owning the curl→`check-port` tri-state in one place; it delegates the
`/dev/tcp` probe to `check-port` so the port-liveness rule lives in one
location. Resolution order: `curl` succeeds ⇒ healthy; else `check-port` reports
the port in-use ⇒ conflict (the port is bound but not responding as
`<service-label>` — held by another process or an unhealthy instance); else ⇒
not running.

The `curl` probe is time-bounded (`--connect-timeout 2` / `--max-time 3`, well
under the ~4s health-wait ceiling), so a wedged accept-but-never-reply listener
cannot hang the probe — this is what makes the `*-wait-for-health` poll timeouts
real, since `wait-for` only re-checks its deadline between probes.

Exit codes:

- **Exit 0**: healthy (HTTP 200).
- **Exit 1**: not running (`curl` failed, port free).
- **Exit 2**: port held by another process or an unhealthy instance (`curl`
  failed, port in use).
- **Exit 3**: invalid arguments (wrong count, or a non-numeric/out-of-range
  port). Kept distinct from the `2` conflict state so a malformed invocation is
  not read as a conflict.

---

### Free-Port Discovery: `find-free-port` (test-only)

`find-free-port [base]` prints the first TCP port at or above `<base>` (default
`18000`) that `check-port` reports free, then exits `0`; it scans upward to
`65535`. A test-only helper for callers that need an arbitrary free port instead
of hardcoding one (the scripts harness's listener ports and its hermetic
`PORT`). The port is free only at the instant of printing — a caller that later
fails to bind treats the port as taken rather than retrying inside the helper.
Exits `1` if no port is free at or above `<base>`, or on an invalid `<base>`;
rejects more than one positional argument.

---

### Daemon Lifecycle

- **`daemon-up`**: Idempotent. Starts the daemon and writes the PID file. If the
  daemon is already running, does nothing. Only checks PID for liveness; callers
  invoke `<service>-wait-for-health` separately for health confirmation. Takes
  an optional `-p` port (validated via `validate_port`). When `-p` is set, after
  the PID-liveness idempotency short-circuit and stale-PID cleanup, a pre-launch
  preflight refuses to spawn if `127.0.0.1:<port>` is already bound — by any
  process, including a daemon from another worktree or an orphaned own-process
  whose PID file was lost — exiting with a distinct code `3` (`fatal -s 3`)
  without writing a PID or reaching health-wait. The idempotency short-circuit
  runs before the port check, so a live own-instance is still recognized as
  ours. When `-p` is absent (the portless queue-worker) the preflight is skipped
  and behavior is unchanged.
- **`daemon-down`**: Idempotent. Sends `SIGTERM` with a grace period; escalates
  to `SIGKILL` if the process does not exit. Removes the PID file on completion.
  No-op if already stopped.
- **`daemon-check`**: Tri-state. Exits `0` if the daemon is running according to
  the PID. Takes an optional `-p` port: when given and the PID is absent/dead,
  exits `2` if the port is in-use (conflict — our PID is gone but the port is
  held by another process), else `1` (stopped). Without `-p` the result is only
  `0`/`1`, exactly as before, so existing callers (e.g. `queue-worker-check`)
  that treat any non-zero as "not running" are unaffected.
- **`daemon-bounce`**: Idempotent. Stops the daemon if running, then starts it.
- **`daemon-status`**: Prints the status of all known services, data-driven. An
  ordered `SERVICES` list (`postgres`, `rest-server`, `queue-worker`) fixes
  print order; an associative name→port map carries the port of each port-bound
  service (only `rest-server`, at `${PORT:-8080}`), with a service's absence
  from the map as the portless sentinel. `postgres` keeps its own
  `postmaster.pid` branch (it cannot route through `daemon-check`); every other
  service routes through one generic path that calls `daemon-check` (with `-p`
  when mapped) and maps the exit code to
  `running`/`stopped`/`conflict`/`unknown`. The status line now carries
  `conflict` (and `unknown`) as additional states. A future port-bound service
  gains conflict detection by registering its port — no new branch.
  `queue-worker`, being portless, never yields `conflict`.

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
convention `<service>-{up,down,bounce,check}`. Some daemons additionally provide
`<service>-wait-for-health`, which blocks until the daemon is healthy. "Healthy"
is defined by the logic in each script.

Current daemons: `rest-server`, `queue-worker`, `admin-server`, `public-web`.

| Script                  | Behavior                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `<svc>-up`              | Fatals if the `installDist` binary is absent. Never invokes Gradle. Delegates to `daemon-up`, then `<svc>-wait-for-health` if it exists. The port-bound wrappers pass `-p`: `rest-server-up` `${PORT:-8080}`, `admin-server-up` `${ADMIN_SERVER_PORT:-8081}`, `public-web-up` `${PUBLIC_WEB_PORT:-8082}`; `queue-worker-up` is portless and unchanged. The default-guarded form is load-bearing under `bin/common`'s `set -u` (`ADMIN_SERVER_PORT`/`PUBLIC_WEB_PORT` are absent from `.env`/`.env.test`). |
| `<svc>-down`            | Delegates to `daemon-down`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `<svc>-bounce`          | Runs `<svc>-down` then `<svc>-up` (e.g. `rest-server`, `queue-worker`, `admin-server`, `public-web`).                                                                                                                                                                                                                                                                                                                                                                                                     |
| `<svc>-check`           | `rest-server-check`, `admin-server-check`, `public-web-check` are thin wrappers that invoke `daemon-http-check <label> <port> http://localhost:<port>/healthz` as an ordinary child, inheriting its `0`/`1`/`2` tri-state (`2` = port held by another process not responding as `<service>`). `queue-worker-check` stays PID liveness via `daemon-check`.                                                                                                                                                 |
| `<svc>-wait-for-health` | Optional. Blocks until the service-specific health check passes.                                                                                                                                                                                                                                                                                                                                                                                                                                          |

`admin-server-check` and `admin-server-wait-for-health` probe `GET /healthz` on
`ADMIN_SERVER_PORT` (default `8081`) — a dedicated port variable, distinct from
the rest-server's `PORT`, because the admin server runs as a separate process
alongside the rest-server.

`public-web-check` and `public-web-wait-for-health` probe `GET /healthz` on
`PUBLIC_WEB_PORT` (default `8082`) — a dedicated port variable, distinct from
`PORT` and `ADMIN_SERVER_PORT`, because the public-web server runs as its own
separate process. `public-web-check` routes through `daemon-http-check` (not a
direct `curl -sf`), inheriting its `0`/`1`/`2` tri-state. `public-web-up` fatals
if the `installDist` binary at
`public-web/build/install/public-web/bin/public-web` is absent (directing to
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

Each `bin/build-<module>` script runs exactly one `./gradlew` task:
`:module:assemble` for libraries, `:module:installDist` for daemons.

A new Gradle module gets a `bin/build-<module>` script only when `bin/build`
must assemble its artifact directly; library modules consumed transitively by
the daemons (`email`, `chat`) are deliberately omitted. To add a directly-built
module:

1. Create `bin/build-<module>` following the existing template.
2. Insert it at the correct position in the ordered sequence inside `bin/build`.

**`bin/ingest-colleges <institution.csv> <fields.csv>`**: Loads a version-pinned
College Scorecard CSV pair into the `colleges` and `college_programs` reference
tables via the `:college` Gradle ingester. Upserts on the natural keys, so
re-running re-applies the same snapshot without duplicates; malformed rows are
logged and skipped. Absolutizes both CSV paths via `realpath` (the
`:college:run` task executes from the module directory). Side-effecting and
idempotent for any given snapshot pair. Runs in the Nix dev shell (the JVM lives
there).

---

### iOS Build, Install & Release (system Xcode)

`build-ios`, `install-ios`, `release-ios`, and the `is-nix` predicate run under
the **system** Xcode toolchain, never the Nix dev shell. Inside `nix develop`,
`xcrun` is shadowed by a stub and `DEVELOPER_DIR`/`SDKROOT` point into the Nix
store, so a build there silently targets the wrong toolchain. `build-ios`,
`install-ios`, and `release-ios` therefore call `is-nix -q` immediately after
option parsing and `fatal` before invoking any tool if it returns `0` — placed
before env resolution so the guard message wins over a missing-env error.

- **`is-nix`**: Predicate. Exit `0` if inside the dev shell (`IN_NIX_SHELL`
  non-empty), `1` otherwise. Prints `nix enabled` / `nix NOT enabled` via
  `log-info` unless `-q`. `-v` additionally lists package names parsed from the
  exported `nativeBuildInputs` (suppressed by `-q`). No side effects.
- **`build-ios [target]`**: Builds — and for device targets signs — the
  `UnicoachiOS` app via `xcodebuild`. `target` defaults to `local`; sources the
  target file `ios-app/env/<target>.env` for `UNICOACH_DESTINATION`
  (`UNICOACH_CONFIGURATION` optional, default `Debug`). (Help text and usage say
  "target"; the internal `ENV_*` variable names are retained.) The repo `.env`
  is sourced **unconditionally** on every path — it is the single source of
  `UNICOACH_CLIENT_KEY`, forwarded to `xcodebuild` verbatim as a build setting
  (a raw secret passed unchanged; an unset value bakes blank, so the app sends
  no client-key header) regardless of how the backend URL is resolved. The
  backend URL is **honor-if-set, else derive**:
  - **HONOR**: if the target file (or environment) set a non-empty
    `UNICOACH_BACKEND_URL`, it is forwarded to `xcodebuild` verbatim — no
    derivation and no bare-IP check. This carries an externally-terminated HTTPS
    deployment URL that the derived `http://host:port` form cannot express.
  - **DERIVE (deploy)**: a target that sets `UNICOACH_DEPLOY` sources
    `.env.prod` (the single source of the prod domain) and bakes
    `UNICOACH_BACKEND_URL=https://api.$APP_DOMAIN` — HTTPS, TLS terminated at
    the ALB, `api.` subdomain, no port.
  - **DERIVE (local)**: otherwise the repo `.env` supplies `APP_DOMAIN` and
    bakes `UNICOACH_BACKEND_URL=http://$APP_DOMAIN:${SERVER_PORT:-8080}` (the
    single host source the server also reads); `APP_DOMAIN` defaults to
    `localhost`. A bare-IP `APP_DOMAIN` is rejected on either derive path
    (invalid cookie `Domain`, RFC 6265) — this check runs **only** on the derive
    paths, so a stale bare-IP `.env` cannot break a build that honors an
    explicit URL.

  A simulator build (destination contains `Simulator`) skips signing; a device
  build additionally sources `ios-app/env/signing.env` for
  `UNICOACH_DEVELOPMENT_TEAM` (required) and forwards
  `CODE_SIGN_STYLE=Automatic
  -allowProvisioningUpdates`. Fatals before
  invoking `xcodebuild` on: dev shell, missing target file, missing
  `signing.env`/team, or (derive path only) a bare-IP `APP_DOMAIN`.

  **Checked-in deploy targets.** `prod` (device) and `prod-simulator`
  (simulator) both set `UNICOACH_DEPLOY=1` (not an explicit
  `UNICOACH_BACKEND_URL`), so `bin/build-ios` sources `.env.prod` and derives
  `UNICOACH_BACKEND_URL=https://api.$APP_DOMAIN` (today `https://api.uni.coach`)
  — the prod domain lives once in `.env.prod` and is never restated in the
  target file. They carry no client key and are not secret, so they are
  shareable and committed. `.gitignore` ignores `ios-app/env/*.env` with
  explicit allowlist exceptions for `*.env.example`, `simulator.env`,
  `prod.env`, and `prod-simulator.env`; any other `<target>.env` (e.g. a
  contributor's `local.env`) stays untracked.

  **Reserved secret seam.** `ios-app/env/<target>.local.env` is a documented
  per-target secret-overlay seam — gitignored and intended to override after the
  target file/`.env`/`signing.env` — but it is **not implemented**: no code
  sources it today.
- **`install-ios [-l] [target]`**: Installs the most recent device build to a
  physical iPhone via `xcrun devicectl device install app` (replaces any prior
  install in place — idempotent). `target` defaults to `local`; device-only, so
  a simulator target is rejected (use `prod` over `prod-simulator`). Resolves
  the `.app` under
  `ios-app/build/DerivedData/Build/Products/<config>-iphoneos/`, failing fast if
  absent (directs to `build-ios`). Always requires `ios-app/env/signing.env`
  (the home of `UNICOACH_DEVICE`) and fatals if it is absent, even when
  auto-detecting. Device id comes from `UNICOACH_DEVICE` when set, otherwise
  single-device auto-detect via `xcrun devicectl list devices` — fatals on zero
  or multiple devices. `-l` additionally runs
  `xcrun devicectl device process launch` for bundle id `coach.uni.UnicoachiOS`.
- **`release-ios [-n] [target]`**: Archives the `UnicoachiOS` app for App Store
  distribution and, by default, uploads it to App Store Connect for TestFlight
  via `xcodebuild -exportArchive`. Architect-invoked, side-effecting, and NOT
  idempotent — each upload consumes a unique build number. `target` defaults to
  `prod` (not `local`). Like `build-ios` it runs under system Xcode (shares the
  `is-nix -q` guard), sources only `bin/functions`, resolves the backend URL
  **honor-if-set, else derive** (identical rules), and reads
  `UNICOACH_CLIENT_KEY` from the repo `.env`. It differs deliberately:
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
    `-n` stops after exporting a signed `.ipa` under `ios-app/build/export/` and
    requires no credentials.

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
- **`db-run [-d <db>] [-r | -x] <ro|rw> [sql]`**: Execution primitive. `ro` mode
  enforces `default_transaction_read_only=on`; `rw` issues unguarded writes.
  Named output options: `-r` → `psql -t -A` (tuples-only, unaligned), `-x` →
  `psql -x -P border=0` (expanded). `-r` and `-x` are mutually exclusive (exit
  `EXIT_EXCLUSIVE_OPTIONS=23`). All other `psql` flags are rejected — the
  passthrough surface no longer exists. A residual flag after the mode
  positional (e.g. `-tA` placed after the mode) exits `EXIT_UNEXPECTED_ARG=20`.
  Exit codes: `2` if Postgres is unreachable (prints coordinates
  `[host=${PGHOST:-localhost} port=${PGPORT:-5432}]`); usage errors in the 10–29
  band; psql's own status on the success path.
- **`db-query [-d <db>] [-r | -x] [sql]`**: Thin front over `db-run ro`. Accepts
  `-r`/`-x` (forwarded as a named flag array), optional `-d` database override,
  and at most one `sql` positional; does not accept `-r`/`-x` together. Exits
  `EXIT_UNEXPECTED_ARG=20` on a surplus positional.
- **`db-write [-d <db>] [sql]`**: Thin front over `db-run rw`. Does not accept
  `-r`/`-x` (writes produce command tags, not result grids). At most one `sql`
  positional.
- **`db-repl [-h]`**: Opens an interactive `psql` session connected to
  `POSTGRES_DB` as `POSTGRES_USER`. Accepts no positional arguments; exits
  `EXIT_UNEXPECTED_ARG=20` on surplus. Exits `2` if Postgres is offline (same
  coordinate diagnostic as `db-run`).
- **`db-drop`**: Drops a single database
  (`DROP DATABASE IF EXISTS … WITH
  FORCE`). Idempotent. Leaves the cluster and
  data directory intact. Gated by `require_dangerous_confirmation` unless `-y`.

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
  thin `exec tofu -chdir="$PROJECT_ROOT/infra" <verb> "$@"` wrappers. They parse
  no options of their own; all args (including any help flag `tofu` understands)
  pass straight through to `tofu`.
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
  script. It refuses to run outside the Nix dev shell (`is-nix -q`), failing
  fast with an actionable message instead of deep in the gate on a missing
  `deno`/JVM/Postgres — the inverse of the iOS scripts' `is-nix` guard.

---

### Test Scripts

Most test scripts set `export ENV_FILE=".../.env.test"` before sourcing
`common`; the aggregator `db-tests` defaults `ENV_FILE` to `.env.test` but
defers to a caller-supplied value, and `db-scripts-tests` instead points
`ENV_FILE` at a generated private env file (its own throwaway cluster).
Harnesses that make assertions source `bin/tests-common` for assertion helpers.
`bin/test-fuzz` instead points `ENV_FILE` at `.env.fuzz` (its own dedicated fuzz
DB and port). Five lifecycle-ownership models exist:

- **Private-cluster harnesses** (`db-scripts-tests`) provision their own
  throwaway cluster (private `POSTGRES_DATA_DIR` + PID-derived `POSTGRES_PORT`),
  exercise cluster lifecycle freely, and tear it down via an EXIT/INT/TERM trap.
  They do not touch the shared cluster.
- **Self-contained daemon harnesses** (`scripts-tests`) register an
  EXIT/INT/TERM trap to tear down the daemons they started (`rest-server`,
  `queue-worker`) and the foreign listeners they spawn. They use the shared
  cluster (`postgres-up`) but do not stop it. `scripts-tests` picks a hermetic
  `PORT` from `find-free-port` before sourcing the env, so the `rest-server` it
  boots binds a free port rather than a fixed one (see the `bin/scripts-tests`
  suite entry).
- **Shared-cluster reset harnesses** (`db-users-tests`, `q-scripts-tests`) reach
  a clean state via `db-bootstrap` + `db-reset` against the per-worktree
  database; they do not stop or wipe the shared cluster, and do not register a
  cluster teardown trap.
- **No-lifecycle harnesses** (`db-convos-tests`, `db-system-prompts-tests`, and
  their aggregator `db-tests`) own no Postgres lifecycle — no
  `postgres-up`/`postgres-down`, no `db-reset`/`db-migrate`, no cluster wipe —
  and register no teardown trap. They assume a live, already-migrated database
  supplied by the caller.
- **Dedicated-DB daemon harness** (`test-fuzz`) binds `ENV_FILE` to `.env.fuzz`
  (its own per-worktree fuzz DB + port 8082), reaches a clean state via
  `postgres-up` + `db-reset` against that dedicated DB, builds and boots a fresh
  rest-server, registers a user, marks it email-verified via a direct `psql`
  write (so the email-verification gate does not `403` the authenticated fuzz
  traffic), and registers an EXIT/INT/TERM trap that tears down only the daemon
  it started. It uses the shared cluster but does not stop or wipe it.
- **No-Postgres harnesses** (`ios-scripts-tests`) own no Postgres lifecycle and
  source neither `bin/common` nor any cluster script. They shim
  `xcodebuild`/`xcrun` onto `PATH` (recording argv to a temp file) and redirect
  the scripts at fixtures via `UNICOACH_ENV_DIR`/`UNICOACH_DOTENV`/
  `UNICOACH_PRODUCTS_DIR`/`UNICOACH_BUILD_DIR`, with the `xcrun` shim emitting
  the per-test `DEVICECTL_LIST_OUTPUT` fixture to drive `install-ios` device
  auto-detect, so no real build or hardware runs. They `unset IN_NIX_SHELL` at
  startup and simulate the dev shell per-invocation with `IN_NIX_SHELL=impure`.

#### `bin/tests-common` — Assertion API

| Function           | Signature                              | Behavior                                                                                                                             |
| ------------------ | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `pass_test`        | `pass_test <msg>`                      | Increments `TOTAL_TESTS`; prints green `✅ <msg>` to stdout.                                                                         |
| `fail_test`        | `fail_test <msg>`                      | Prints red `❌ Fail: <msg>` to **stderr**; **exits `1` immediately**. No further assertions run.                                     |
| `assert_success`   | `assert_success <msg> <cmd…>`          | Runs `<cmd>`; calls `fail_test` if it exits non-zero, otherwise calls `pass_test`.                                                   |
| `assert_failure`   | `assert_failure <msg> <cmd…>`          | Runs `<cmd>`; calls `fail_test` if it exits **zero**, otherwise calls `pass_test`.                                                   |
| `assert_exit_code` | `assert_exit_code <code> <msg> <cmd…>` | Runs `<cmd>` (suppressing stdout/stderr), captures its exit code, calls `fail_test` if it does not equal `<code>`, else `pass_test`. |
| `end_tests`        | `end_tests`                            | Prints total count summary. **Does not exit.** Must be the final statement in every test script.                                     |

> `fail_test` exits immediately — subsequent assertions in the same script are
> skipped. Test functions that need early returns before calling `fail_test`
> must `return 1` and let the `assert_*` wrapper call `fail_test`.

#### Test Suites

- **`bin/test`**: Test orchestrator owning a closed, validated CLI. It parses
  its own options and never forwards raw arguments to `gradlew` (no `--`
  passthrough). Positional arguments are friendly module names
  (`common db net auth queue queue-worker rest-server service email chat
  admin-server public-web college`
  — a hardcoded set kept in sync with `settings.gradle.kts` by hand), each
  mapped to an explicit `:<module>:<task>` task; repeats are de-duplicated. The
  per-module `<task>` is `test` by default, or `check` when the optional leading
  `check` keyword is given (`bin/test check [module ...]`) — Gradle's `check`
  runs ktlint plus tests for a full Kotlin verification over the same Postgres
  lifecycle, and is the form the `bin/pre-commit` Kotlin gate invokes. `check`
  is a keyword, not a module and not an option. Unknown modules and unknown
  options are rejected, never forwarded. `bin/test` always emits at least one
  `:<module>:<task>` task — naming no module runs every module — so the
  flags-only/zero-task argv that made `gradlew` print a false "BUILD SUCCESSFUL"
  over zero tests is structurally impossible. Mapped options (`-t GLOB` —
  requires exactly one module — `-f` force, `-v` verbose, `-c` continue) follow
  the task list in fixed order. Options and positionals may be intermixed in any
  order: getopts is wrapped in an outer loop that consumes one non-option per
  round (resetting `OPTIND=1`). Argument validation completes **before** any
  side effect, so an invalid invocation (unknown module/option, `-t` without
  exactly one module) fails without resetting the test database. Exit codes:
  usage errors exit `2` (the `help()` error branch); a test run that executed
  but did not all pass propagates `gradlew`'s `1` unchanged; success and `-h`
  exit `0`; downstream lifecycle failures propagate their own codes via
  `set -e`. After validation, under `set -e`, runs in order: `postgres-up` →
  `db-reset` → `db-tests` → `gradlew` with the constructed task list. The
  `db-tests` step runs sequentially (never backgrounded), after migration and
  before the terminal gradlew call; a non-zero shell-harness exit aborts the run
  before Gradle ever starts. Postgres is left up so the Gradle suite observes
  the same migrated database. To filter to a single test, use the module +
  filter form `bin/test <module> -t "<glob>"` (e.g.
  `bin/test queue -t "*JobsDaoTest"`); the old bare-FQN positional shape
  (`bin/test ed.unicoach.queue.JobsDaoTest`) is dropped.
- **`bin/test-fuzz`**: Authenticated contract-referee fuzzer. Boots a
  freshly-built rest-server on its own fuzz DB/port (`.env.fuzz`) and runs
  Schemathesis against the committed `api-specs/openapi.yaml`. Ordered lifecycle
  under `set -e`: provision a pinned `schemathesis==4.21.5` venv
  (`var/fuzz/venv`, gitignored, on the flake python3; idempotent when the
  version already matches) → port-guard (fatal if `$PORT` is already served,
  before any build/boot) → `postgres-up` → `db-reset` the dedicated fuzz DB →
  `build-rest-server` → `rest-server-up` → register a uniquely-named user and
  capture its `UNICOACH_SESSION` cookie → mark that user email-verified via a
  direct `psql` write → run Schemathesis as a child (not `exec`) so the
  EXIT/INT/TERM trap tears down the rest-server. The captured cookie is injected
  via `-H "Cookie: …"` on every request, exercising the authenticated surface.
  **Verified-user mutation (DB side effect):** registration leaves
  `email_verified_at` NULL, and the email-verification gate returns
  `403 email_not_verified` on every gated route (`students/*`) for an unverified
  caller — which would make Schemathesis report drift against the documented
  statuses. After capturing the cookie the harness issues one `psql` statement
  against the freshly-migrated fuzz DB —
  `UPDATE users SET version = version + 1, email_verified_at = NOW() WHERE email
  = <fuzz-email> AND email_verified_at IS NULL AND deleted_at IS NULL`
  (run with `ON_ERROR_STOP=1`, as `POSTGRES_USER` against `POSTGRES_DB`; `fatal`
  on failure) — marking the fuzz user verified so gated routes return their
  documented statuses, and bumping `version` alongside the write so the `users`
  versioning trigger does not reject it. Excludes, by category: unimplemented
  routes (`/api/v1/conversations*`), session-destructive operations
  (`logoutUser`, `deleteStudentMe`), and the single non-applicable check
  `ignored_auth` (a false positive under static cookie injection); the full
  `--checks all` set — including `unsupported_method` and every data-conformance
  check — otherwise runs. As a referee it surfaces — never masks —
  non-conformance: it runs the full check set and EXITS `0` against a conformant
  server, reporting any contract drift as a non-zero exit. A `413` (an oversized
  body rejected by the application-scope `RequestBodyLimit`) is accepted as a
  valid `negative_data_rejection` via the committed root `schemathesis.toml`,
  passed to Schemathesis with `--config-file`.
- **`bin/scripts-tests`**: Tests scripts in `bin/`. It sources `bin/functions`
  early (so `fatal` is available) and exports a free `PORT` from
  `find-free-port` **before** sourcing `.env.test`/`common`; a `find-free-port`
  failure routes through `fatal` rather than falling back to a hardcoded port.
  The booted `rest-server` therefore binds a free port, making the suite
  hermetic with respect to ports across worktrees. It also exercises the RFC 73
  port machinery (`check-port`, `daemon-up -p`, the
  `daemon-check`/`daemon-status` conflict tri-state, the thin `*-check`
  wrappers, `daemon-http-check`'s bounded probe), binding `test-tcp-listener`
  foreign listeners on `find-free-port` ports and reaping them in its teardown
  trap.
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
- **`bin/test-tcp-listener`** (test-only `python3` helper): binds
  `127.0.0.1:<port>` as a foreign listener whose PID is recorded in none of the
  PID files, simulating a cross-worktree port occupant for `scripts-tests`.
  Takes a mode: `close` (accept and immediately close) or `hold` (accept and
  never reply — the wedged-server case that proves `daemon-http-check`'s bounded
  probe does not hang).

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
  `xcrun devicectl`), not the flake; these scripts run outside `nix develop`.
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
- **Required env**: `POSTGRES_PORT` is required (no in-code default);
  `bin/common` exports it as `PGPORT` for all libpq clients. `DB_SCHEMA_DIR` is
  optional; `bin/common` exports it, defaulting to `$PROJECT_ROOT/db/schema`.
  `PGHOST` is optional and is NOT exported by `bin/common`; it flows from
  `$ENV_FILE` via `set -a` and defaults to `localhost`. On the deploy instance
  it carries the RDS address, selecting the cluster host for `postgres-check`
  and, via libpq, `db-run`/`psql`. `PORT` sets the per-env service port
  (`.env`=8080, `.env.test` default/fallback 8081 but overridable,
  `.env.fuzz`=8082); each env file derives the daemon bind variable
  `SERVER_PORT=$PORT`. `.env.test` defines `PORT="${PORT:-8081}"`: a
  harness-supplied `PORT` wins, and `8081` survives only as a fallback for
  tooling that sources `.env.test` without first picking a port (e.g. the Kotlin
  `bin/test` harness). `bin/scripts-tests` supplies a free `PORT` from
  `find-free-port`, so its booted `rest-server` binds a free port. `test-fuzz`
  reads `$PORT` for its port guard, boot, registration, and fuzz target.
  `ADMIN_SERVER_PORT` (default `8081`) is the admin daemon's bind port, read by
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
- [x] [RFC-67: College Knowledge](../rfc/67-college-knowledge.md) — added
      `bin/ingest-colleges`, a CLI that loads College Scorecard CSV pairs into
      the `colleges`/`college_programs` tables via the `:college` Gradle
      ingester; added `college` to `bin/test`'s `MODULES`.
- [x] [RFC-69: Email Verification Gate](../rfc/69-email-verification-gate.md) —
      `bin/test-fuzz` marks its provisioned fuzz user email-verified via a
      direct `psql`
      `UPDATE users SET version = version + 1, email_verified_at = NOW()` after
      capturing the session cookie, so the new email-verification gate returns
      the documented statuses (not `403 email_not_verified`) on gated
      `students/*` routes; the `version` bump satisfies the `users` versioning
      trigger.
- [x] [RFC-73: Daemon Port-Collision Preflight](../rfc/73-daemon-port-collision-preflight.md)
      — added the `check-port`/`daemon-http-check`/`find-free-port` primitives
      and the shared `validate_port` helper; gave `daemon-up` an optional `-p`
      with a pre-launch `fatal -s 3` preflight that refuses to spawn onto a
      bound port; added a `conflict` tri-state to `daemon-check` (`-p`) and a
      data-driven, port-aware `daemon-status`; turned the HTTP `*-check` scripts
      into thin `daemon-http-check` wrappers; and made `bin/scripts-tests` bind
      a hermetic `PORT` from `find-free-port` (with `.env.test`
      `PORT="${PORT:-8081}"` keeping `8081` only as a fallback). (Option
      spelling: `-p`, post-RFC-74.)
- [x] [RFC-74: Standardize `bin/` Option Parsing on `getopts` (Short-Only)](../rfc/74-getopts-option-parsing.md)
      — migrated every hand-rolled `while`/`case` option parser to bash
      `getopts` over a leading-colon optstring (silent getopts; each script owns
      its `\?`/`:` diagnostics) and dropped all GNU-style long options,
      collapsing every long spelling to its short flag (`--help`→`-h`,
      `--port`→`-p`, `--quiet`→`-q`, `--launch`→`-l`, `--no-upload`→`-n`,
      `--format`→`-f`, `--status-code`→`-s`,
      `--yes-i-really-want-to-do-this`→`-y`; per-script forms such as
      `bin/test`'s filter `--tests`→`-t`, `q-enqueue`'s `--max-attempts`→`-m`
      and `--delay`→`-d`, the `db-run`/`db-query`/`db-write` database override
      `--database`→`-d`, and `file-lock`'s `--operation`/`--timeout`→`-o`/`-t`).
      Exit-code contracts, command grammars, and validation are otherwise
      unchanged.
- [x] [RFC-80: bin/ exec and argument-passthrough discipline](../rfc/80-bin-exec-passthrough-discipline.md)
      — removed terminal `exec <command>` from all operational scripts (build
      wrappers, `test`, iOS scripts, `*-check` fronts, `*-bounce` tails,
      `file-lock`, `dev-bootstrap`'s generated hook); the `infra-*`
      `exec tofu
      … "$@"` fronts remain as the only enumerated `exec`
      exception; replaced the opaque psql passthrough in
      `db-run`/`db-query`/`db-write`/`db-repl` with a bounded grammar (`-r`/`-x`
      named output modes; all other flags rejected); dropped `"$@"` forwarding
      from the `*-server-down` wrappers; added the usage-error exit-code band
      (10–29) as six constants in `bin/functions`; made every operational script
      reject unexpected arguments (exit `EXIT_UNEXPECTED_ARG=20`); recoded
      `file-lock`'s matching-op fast-fail from `10` to `3`; added
      `assert_exit_code` to `bin/tests-common`.
