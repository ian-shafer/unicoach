# INVARIANTS — bin

The operational scripts for the Unicoach application: build, daemon control,
linting, CLIs, testing, iOS deploy, and infrastructure.

An **ops-tool** is a sanctioned `bin/` script run on a deployed instance via
`bin/remote` (SSM Run Command) to perform an operational task against the
running deployment — currently only `ingest-colleges`, gated by `bin/remote`'s
allowlist. It is distinct from the deploy path, which owns the release swap and
restart.

## Invariants

### Source `bin/common` first (system-Xcode scripts excepted)

**Rule:** Every shell script MUST `source bin/common` as its first non-shebang,
non-comment line, optionally preceded only by an `export ENV_FILE=…` /
`export ENV_FILES=…` selector. Exempt: the system-Xcode scripts (`build-ios`,
`install-ios`, `release-ios`, `ios-scripts-tests`) and `is-nix`, which run
outside the Nix dev shell and source only `bin/functions`.

**Why:** `bin/common` is where the shared setup lives — `set -euo pipefail`,
`PROJECT_ROOT`, the loaded `.env` plus deltas, and required vars like `PGPORT`.
A script that skips it runs without strict-mode, without the env, and against
unset variables.

### Absolute paths only

**Rule:** Scripts MUST reference other scripts and files by absolute path,
typically `"$PROJECT_ROOT/bin/<script>"`.

**Why:** An absolute path can only be interpreted one way, so it is
deterministic. A relative path resolves against whatever the current directory
happens to be — which the harness resets between calls — so it breaks
unpredictably across worktrees.

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

### Don't forward `"$@"`/`"$*"` as a child's argument vector

**Rule:** A script MUST NOT pass `"$@"` or `"$*"` (including the unquoted
`$@`/`$*`) as the argument vector of a command it invokes; it must explicitly
name the arguments it chooses. Indiscriminate forwarding is allowed only in
exceptional cases — e.g. `wait-for`, which cannot know the command it waits for,
or the `log-*` functions, which log an arbitrary list of strings.

**Why:** Explicit over implicit, and minimal surface area — both central to
controlling complexity. An opaque `"$@"` makes the script's interface implicitly
whatever the caller passed, so it can't be reasoned about, tested against its
help text, or trusted to reject bad input. Naming every argument makes the input
contract explicit and finite.

### `exec` only when ceding control is intended

**Rule:** A script MUST NOT replace its process with `exec <command>` unless it
is genuinely fine to cede all control to that command; normally it runs the
target as an ordinary child and stays alive to handle the result. The sanctioned
case is a thin front over a third-party CLI whose exit status is meant to pass
straight through (the `infra-*` wrappers `exec tofu -chdir=… …`), where ceding
control is the point. (An `exec 3<>/dev/tcp/…` fd redirect has no command word —
it opens a descriptor, not a new process — so it is not this kind of `exec`.)

**Why:** `exec` hands the process to the target and returns nothing — no code
runs after it, so the script cedes all control of the outcome: it can't map the
exit code, clean up, or enforce a postcondition. Only cede that control when
passing the target's result straight through is the whole point.

### Operational scripts reject unexpected arguments

**Rule:** An operational `bin/` script with a fixed argument grammar MUST reject
an argument outside it — a surplus positional or an unknown option — with a
non-zero usage error, never silently ignore it. A script whose grammar is
intentionally an open-ended list (a command to run like `wait-for`, trailing SQL
in `db-run`, a filter list in `q-status`) has no surplus, so the rule doesn't
bind it.

**Why:** A silently-ignored argument means the script did something other than
what the caller wrote, with no signal. Rejecting it turns a typo or a stale flag
into an immediate, diagnosable failure instead of a wrong-but-green run.

### Build and run are separate steps; run never builds

**Rule:** A `bin/` script that runs a compiled program MUST invoke the pre-built
artifact directly and MUST NOT build it on demand. Build is its own step
(`bin/build-<module>`); run only executes the artifact. Run MAY verify the
artifact exists and fail with a clear message (as it does today), but is not
required to — a missing artifact may simply crash.

**Why:** Building on demand couples run to build and silently forks behavior
between a dev machine (which can build) and the production instance (which
cannot). Keeping them separate means a skipped build fails loudly at run instead
of being papered over.

### Scripts parse their own options with `getopts`

**Rule:** A `bin/` script MUST parse its own options with `getopts`, and MUST
NOT hand-roll long-form (`--foo`) options (e.g. via the `getopts -:` trick).
Exception: the `infra-*` `tofu` fronts, whose argument grammar isn't ours to
constrain.

**Why:** One option parser across all of `bin/`. A second, long-form syntax
would fork the parsing style script by script for no benefit.

### Secrets reach a script only through the environment

**Rule:** A `bin/` script MUST obtain any secret from the loaded environment
(`/etc/unicoach/env` on hosts, the layered dotenv / `local.conf` in dev, via
`bin/common`), never as a command-line argument or committed value.

**Why:** Command arguments are visible in process listings, shell history, and
command logs, and a committed secret is effectively unrevocable; the environment
file is the one access-controlled channel.

### Ops tools never route secrets through `bin/remote`

**Rule:** An ops-tool script run via `bin/remote` MUST NOT receive a secret
through `bin/remote`'s arguments or the SSM command payload; it reads what it
needs from `/etc/unicoach/env` already on the instance.

**Why:** The SSM `send-command` path is logged in plaintext to CloudTrail, so a
secret in its arguments leaks.

### An ops-tool script never mutates `current` or restarts a service

**Rule:** An ops-tool script invoked via `bin/remote` MUST NOT modify
`/opt/unicoach/current` or restart a systemd unit.

**Why:** `deploy-on-instance.sh` is the only tool allowed to do this: it swaps
the release and restarts atomically, gated on a successful migration, so a
second, unsynchronized path can't serve a half-deployed release or restart a
service mid-request.

### Test harnesses never stop or wipe the shared cluster

**Rule:** The PostgreSQL cluster is shared by every git worktree; isolation is
per-database only. A harness using the shared cluster MUST NOT stop or wipe it.
A harness needing cluster-lifecycle control MUST stand up its own private
cluster (private `POSTGRES_DATA_DIR` + port).

**Why:** Stopping or wiping the shared cluster destroys the databases of every
other worktree running against it.

### DDL scripts attach to a running cluster; never provision one

**Rule:** `db-create`, `db-create-role`, and `db-migrate` MUST assume an
already-running cluster at `PGHOST:PGPORT`; they MUST NOT start or initialise
one (no `initdb`, `postgres-up`, or `pg_ctl`).

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
- [x] [RFC-92: Ops Tool Runner](../rfc/92-ops-tool-runner.md)
