# INVARIANTS — bin

The operational scripts for the Unicoach application: build, daemon control,
linting, CLIs, testing, iOS deploy, and infrastructure.

## Invariants

### Source `bin/common` first (system-Xcode scripts excepted)

**Rule:** Every shell script MUST source `bin/common` as its first non-comment,
non-shebang line — optionally preceded only by an `export ENV_FILE=…`. The sole
exception is the system-Xcode scripts (`build-ios`, `install-ios`,
`release-ios`, `ios-scripts-tests`) and the dev-shell predicate `is-nix`, which
MUST source only `bin/functions` and MUST NOT source `bin/common`.

**Why:** `bin/common` sets `set -euo pipefail`, resolves `PROJECT_ROOT`, loads
`$ENV_FILE`, and exports `PGPORT` from the required `POSTGRES_PORT`. A script
that skips it runs without strict-mode, without the env, and against an unset
port — silently targeting the wrong database or aborting on an unbound variable.
Conversely, `bin/common` assumes the Nix dev shell and fatals on a missing
`.env`; the system-Xcode scripts run under the system toolchain, never
`nix develop`, so sourcing it would defeat their purpose.

### Absolute paths only

**Rule:** Scripts MUST reference other scripts and files by absolute path,
typically `"$PROJECT_ROOT/bin/<script>"`.

**Why:** The harness resets the working directory between calls and `cd` is
avoided, so a relative path resolves against an unpredictable CWD and breaks
across worktrees.

### All log output to stderr

**Rule:** All diagnostic output MUST go to stderr, via `log-info` /
`log-warning` / `fatal`. stdout is reserved for a script's actual data output.

**Why:** CLIs whose stdout is captured by a caller (`db-query`, `infra-output`,
and pipelines) are corrupted if a log line lands on stdout.

### Distinct non-zero exit codes per failure reason

**Rule:** A script with more than one failure reason MUST assign a distinct
non-zero exit code per reason, documented in its `help()`.

**Why:** A calling script distinguishes _why_ a dependency failed only by the
code. Collapsing all failures to `1` makes that branch impossible.

### Port-liveness probe via pure-bash `/dev/tcp`

**Rule:** A script determining whether a TCP port is served MUST probe with
`exec 3<>/dev/tcp/127.0.0.1/$PORT`. It MUST NOT use `nc -z` or an HTTP `curl`
probe for this.

**Why:** `nc -z` reports bound ports as closed on BSD/macOS (false negative),
and a `curl` probe misses a non-HTTP listener. The `/dev/tcp` connect is
occupant-agnostic, dependency-free, and refuses instantly on a closed port.

### Daemon `-up` boots a pre-built binary or fatals

**Rule:** A daemon `-up` script MUST boot from a pre-built `installDist` binary
and MUST fatal immediately if it is absent — it MUST NOT invoke Gradle to build
on demand.

**Why:** Coupling boot to a build makes daemon startup slow and
non-deterministic and hides build failures inside a "start" operation; the build
step (`bin/build-<svc>`) is deliberately a separate, explicit phase.

### Test harnesses never stop or wipe the shared cluster

**Rule:** The PostgreSQL cluster is shared by every git worktree; isolation is
per-database only. A harness using the shared cluster MUST NOT stop or wipe it.
A harness needing cluster-lifecycle control MUST stand up its own private
cluster (private `POSTGRES_DATA_DIR` + port).

**Why:** Stopping or wiping the shared cluster destroys the databases of every
other worktree running against it.

### DDL scripts attach to a running cluster; never provision one

**Rule:** `db-create`, `db-create-role`, and `db-migrate` MUST connect to an
already-running cluster at `PGHOST:PGPORT` and MUST NOT start or initialise one
(no `initdb`, `postgres-up`, or `pg_ctl`).

**Why:** Cluster startup is owned by the environment (local `bin/test`, the
private-cluster harnesses, or managed RDS in production). If the DDL scripts
also provisioned, the local and deploy paths would fork on cluster provisioning
— production RDS must never be `initdb`-ed by a migration run.

### `admin-grant` is the sole sanctioned raw-SQL entity mutation

**Rule:** `bin/admin-grant` is the ONLY `bin/` script that may issue raw
entity-mutating SQL, and only to mint the **first** admin. It MUST set
`is_admin` in a single `psql` transaction that reads-and-bumps the row `version`
so the versioning/timestamp/history triggers fire. All later grants/revocations
MUST route through the in-tool DAO path.

**Why:** Bypassing the typed DAOs skips the in-app versioning and history
capture, so a raw `UPDATE` would mutate state without an audit row in
`users_versions`. The exception exists only because the in-tool grant path
cannot run until one admin exists to bootstrap it.

## History

- [x] [RFC-03: Daemon Scripts](../rfc/03-daemon-scripts.md)
- [x] [RFC-23: Native Daemon Scripts](../rfc/23-native-daemon-scripts.md)
- [x] [RFC-52: Make the REST Surface Fuzz-Clean](../rfc/52-make-rest-surface-fuzz-clean.md)
- [x] [RFC-55: Cluster-Lifecycle-Agnostic DB Scripts](../rfc/55-cluster-lifecycle-agnostic-db-scripts.md)
- [x] [RFC-60: Admin Website (Framework + Users Spine)](../rfc/60-admin-website.md)
- [x] [RFC-61: Public Web Module (Dynamic HTML via Shared Layout)](../rfc/61-static-marketing-site.md)
