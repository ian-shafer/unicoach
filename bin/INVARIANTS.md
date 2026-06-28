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

### Usage errors use the reserved 10–29 exit-code band

**Rule:** A usage error (the caller invoked the script wrongly) MUST exit with
the band code for its category, leaving 1–9 for operational/runtime outcomes:
`10` unknown option, `11` option missing its value, `20` unexpected argument,
`21` missing required argument, `22` invalid argument value, `23`
mutually-exclusive options. The codes are the named constants
`EXIT_UNKNOWN_OPTION`, `EXIT_OPTION_REQUIRES_VALUE`, `EXIT_UNEXPECTED_ARG`,
`EXIT_MISSING_REQUIRED_ARG`, `EXIT_INVALID_ARG_VALUE`, `EXIT_EXCLUSIVE_OPTIONS`,
defined in `bin/functions` and overridable from `.env` (for every script that
sources `bin/common`; `is-nix`, functions-only, uses the defaults). An
operational/runtime outcome MUST NOT use a 10–29 code. `bin/functions`' own
internal `fatal -s` parse guard is exempt (a library primitive, not a CLI
surface).

**Why:** A caller distinguishes "you typed it wrong" from "the system said no"
only by the code. Reusing `1` for a usage error collapses it into operational
failures — a stray argument to `postgres-check` reads as "postgres down", to
`is-nix` as "not in the dev shell" — silently, since both are consumed in `if`
guards with stderr suppressed. A reserved band keeps the two classes disjoint;
the converse — an operational outcome in 10–29 — is equally forbidden, which is
why `file-lock`'s matching-op fast-fail uses the operational code `3`.

### Port-liveness probe via pure-bash `/dev/tcp`

**Rule:** A script determining whether a TCP port is served MUST probe with
`exec 3<>/dev/tcp/127.0.0.1/$PORT`. It MUST NOT use `nc -z` or an HTTP `curl`
probe for this.

**Why:** `nc -z` reports bound ports as closed on BSD/macOS (false negative),
and a `curl` probe misses a non-HTTP listener. The `/dev/tcp` connect is
occupant-agnostic, dependency-free, and refuses instantly on a closed port.

### `exec <command>` and `"$@"`/`"$*"` child-forwarding are forbidden

**Rule:** A script MUST NOT replace its process with `exec <command>`, and MUST
NOT pass `"$@"`, `"$*"`, `"${@}"`, or unquoted `$@`/`$*` as the argument vector
of an invoked command. A script delegates by invoking the target as an ordinary
child with explicit arguments it chose: fixed literals, parsed `getopts`
options, or a named array assembled from the script's OWN parsed options and
literals — never a verbatim `("$@")` capture of the caller's argv handed onward
as a child's argument vector. (Interpolating `"$*"` into a single diagnostic
_string_ — e.g. `fatal -s "$EXIT_UNEXPECTED_ARG" "… : $*"` — is not forwarding;
the command receives one argument it named. A script's own positional args used
for its own logic — e.g. `q-status`'s `types=("$@")`, a filter list it consumes
itself — are likewise not a child-forward.) The only exceptions, permitted by
name:

- **TCP-liveness redirection** — `exec 3<>/dev/tcp/127.0.0.1/$PORT` (no command
  word; opens a probe descriptor, does not replace the process) in `check-port`
  and `test-fuzz`, mandated by the port-liveness rule above. _[standing]_
- **Variadic diagnostic primitives** — `bin/functions`' `log-info` /
  `log-warning` / `log-error` (`echo "$@"`) and `fatal` (`echo "[FATAL] $*"`),
  which join the script's own message onto stderr, never to a delegated program.
- **Irreducible caller-command capture** — `daemon-up`, `daemon-bounce`,
  `wait-for`, `tests-common`, `ios-scripts-tests`: the script executes, polls,
  or asserts an arbitrary caller-supplied _command_ it cannot name, so its
  `"$@"` is that command, not a delegated program's options.
  `ios-scripts-tests`' `run_*` helpers forward an arbitrary caller-supplied
  command/args to the iOS-build SUT (`build-ios`, `install-ios`, `release-ios`,
  `is-nix`), the same harness-forwards-to-SUT shape as `tests-common`. (Its
  `for a in "$@"; do echo "$a"; done` lines are bodies of the generated
  `xcodebuild`/`xcrun` test stubs written via heredoc — generated stub source
  whose `$@` resolves when the stub runs, not `ios-scripts-tests`' own argv
  forward.)
- **Thin third-party-CLI fronts** — `infra-apply`, `infra-plan`, `infra-init`,
  `infra-output`, `infra-bootstrap`: each `exec tofu -chdir=… "$@"`, a
  single-target front contributing only `-chdir` and forwarding the caller's
  `tofu` arguments opaquely. A wrapper over an in-repo sibling owns that
  sibling's grammar and MUST name the arguments instead; this exception is for a
  third-party CLI whose grammar is not ours to re-encode.

**Why:** `exec` and an opaque `"$@"` make a script an unbounded conduit, so its
real behaviour is whatever the caller and the underlying tool negotiate, not
what the script documents. Naming every argument keeps behaviour bounded by the
declared interface and rejects the rest, which is the only way a wrapper can
carry a contract distinct from the tool it fronts. The exceptions are the cases
where the forwarded tokens are not a bounded delegation at all (the redirection;
the message primitives) or _are_ the script's irreducible purpose (running a
caller's command; fronting a third-party CLI) — each enumerated so the boundary
is explicit, not discovered by `grep`.

### Operational scripts reject unexpected arguments

**Rule:** An operational `bin/` script MUST reject any argument outside its
declared grammar — a positional beyond the count it consumes, or an unknown
option — with a non-zero exit from the usage-error band (rule above), never
silently ignore it. A script that takes no positional MUST error on the first
one; a script that takes a fixed count MUST error on a surplus one. This binds
the operational CLIs (lifecycle, db, queue, build, health), not the test
harnesses (`*-tests`, `tests-common`, `ios-scripts-tests`) or the
`bin/functions` / `bin/common` libraries. Scripts whose grammar is an open-ended
caller command or list — `daemon-up`, `daemon-bounce`, `wait-for`, `db-run`'s
trailing SQL, `q-status`'s filter list — have no "surplus" to reject and are
exempt.

**Why:** A silently-ignored argument means the script did something other than
what the caller wrote, with no signal. Rejecting it turns a typo or a stale flag
(e.g. a former `psql` passthrough) into an immediate, diagnosable failure
instead of a wrong-but-green run.

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
- [x] [RFC-80: bin/ exec and argument-passthrough discipline](../rfc/80-bin-exec-passthrough-discipline.md)
