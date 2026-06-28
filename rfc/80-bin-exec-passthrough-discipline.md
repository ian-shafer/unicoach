# RFC 80: bin/ exec and argument-passthrough discipline

## Executive Summary

`bin/` scripts reach for `exec <command>` and opaque `"$@"`/`"$*"` forwarding as
casual defaults. Both surrender control: `exec` discards the script's process
identity and its ownership of the child's exit and signals, and a forwarded
`"$@"` lets a caller pass any flag straight to the underlying tool, so an
invocation may or may not do what the script's documented contract claims. A
wrapper that forwards `"$@"` to `psql` is not a `db-query` — it is `psql` with a
prefix.

This RFC removes both constructs from the operational scripts and adds a
prescriptive `bin/INVARIANTS.md` rule forbidding them, with a short, enumerated
exception list (stated canonically in that rule; see Detailed Design). Three
kinds of change: (1) every terminal `exec <command>` becomes an ordinary child
invocation, preserving the exit-code contract (a script exits with its last
command's status); (2) the `db-query` / `db-write` / `db-run` / `db-repl` family
loses its opaque `psql` passthrough and gains an explicit, bounded interface —
the only output modes the codebase actually uses, `-r` (raw, `psql -tA`) and
`-x` (expanded, `psql -x -P border=0`), become named options that reject
everything else — and the ~10 in-repo call sites that carry a passthrough flag
migrate to it (the plain `db-*` callers are unaffected); (3) the `*-server-down`
wrappers stop blind-forwarding `"$@"` to `daemon-down`.

The exceptions are the cases where a forwarded token is not a bounded delegation
and cannot be made one: the TCP-liveness `exec 3<>/dev/tcp` redirection
(standing), `bin/functions`' variadic message primitives, the scripts whose
`"$@"` is an irreducible caller-supplied command (`daemon-up`, `daemon-bounce`,
`wait-for`, `tests-common`, `ios-scripts-tests`), and the thin `tofu` fronts
(`infra-*`). The rule is review-enforced, like every rule in
`bin/INVARIANTS.md`.

This RFC also closes the inverse gap — scripts that silently _ignore_ a surplus
argument instead of forwarding it. Twenty-one operational scripts (the five
`*-bounce` orchestrators, the four `*-server-down` wrappers, the four `*-check`
probes, `db-repl`, the five `*-wait-for-health` waiters, `is-nix`, and
`postgres-check`) accept extra arguments without error. The new policy: every
operational script rejects unexpected arguments. So rejection stays
distinguishable from operational results — a `*-check` exits `1` for "down",
`is-nix` exits `1` for "not in the dev shell" — usage errors move to a reserved
exit-code band (10–29): unknown option `10`, option-missing-value `11`,
unexpected argument `20`, missing required argument `21`, invalid value `22`,
mutually-exclusive options `23`. The band is defined as overridable constants in
`bin/functions`, applied to every operational script's usage-error arms, and
codified as two new `bin/INVARIANTS.md` rules. This intentionally expands the
original "no command-grammar changes" scope, by architect direction.

Reserving 10–29 for usage errors requires one operational code to move first:
`file-lock` today exits `10` for "lock already held by a matching operation" (a
runtime "no", not a usage error; `bin/scripts-tests` pins it). That outcome is
recoded to the operational code `3` — a distinct runtime reason in `file-lock`'s
own 1–9 space (`1` is the active non-matching lock, `2` the lock-metadata read
failure) — so the 10–29 band starts genuinely free of any operational use.

## Detailed Design

### The rule (`bin/INVARIANTS.md`)

A new invariant replaces, in place, the existing rule headed _"`exec` and `"$@"`
passthrough are reserved for single-target wrappers"_ (the abandoned
single-target framing), keeping its position after the "Port-liveness probe via
pure-bash `/dev/tcp`" rule it cross-references:

> #### `exec <command>` and `"$@"`/`"$*"` child-forwarding are forbidden
>
> **Rule:** A script MUST NOT replace its process with `exec <command>`, and
> MUST NOT pass `"$@"`, `"$*"`, `"${@}"`, or unquoted `$@`/`$*` as the argument
> vector of an invoked command. A script delegates by invoking the target as an
> ordinary child with explicit arguments it chose: fixed literals, parsed
> `getopts` options, or a named array assembled from the script's OWN parsed
> options and literals — never a verbatim `("$@")` capture of the caller's argv
> handed onward as a child's argument vector. (Interpolating `"$*"` into a
> single diagnostic _string_ — e.g. `help "… : $*"` — is not forwarding; the
> command receives one argument it named. A script's own positional args used
> for its own logic — e.g. `q-status`'s `types=("$@")`, a filter list it
> consumes itself — are likewise not a child-forward.) The only exceptions,
> permitted by name:
>
> - **TCP-liveness redirection** — `exec 3<>/dev/tcp/127.0.0.1/$PORT` (no
>   command word; opens a probe descriptor, does not replace the process) in
>   `check-port` and `test-fuzz`, mandated by the port-liveness rule above.
>   _[standing]_
> - **Variadic diagnostic primitives** — `bin/functions`' `log-info` /
>   `log-warning` / `log-error` (`echo "$@"`) and `fatal` (`echo "[FATAL] $*"`),
>   which join the script's own message onto stderr, never to a delegated
>   program.
> - **Irreducible caller-command capture** — `daemon-up`, `daemon-bounce`,
>   `wait-for`, `tests-common`, `ios-scripts-tests`: the script executes, polls,
>   or asserts an arbitrary caller-supplied _command_ it cannot name, so its
>   `"$@"` is that command, not a delegated program's options.
>   `ios-scripts-tests`' `run_*` helpers forward an arbitrary caller-supplied
>   command/args to the iOS-build SUT (`build-ios`, `install-ios`,
>   `release-ios`, `is-nix`), the same harness-forwards-to-SUT shape as
>   `tests-common`. (Its `for a in "$@"; do echo "$a"; done` lines are bodies of
>   the generated `xcodebuild`/`xcrun` test stubs written via heredoc —
>   generated stub source whose `$@` resolves when the stub runs, not
>   `ios-scripts-tests`' own argv forward, and outside this RFC's scope.)
> - **Thin third-party-CLI fronts** — `infra-apply`, `infra-plan`, `infra-init`,
>   `infra-output`, `infra-bootstrap`: each `exec tofu -chdir=… "$@"`, a
>   single-target front contributing only `-chdir` and forwarding the caller's
>   `tofu` arguments opaquely. A wrapper over an in-repo sibling owns that
>   sibling's grammar and MUST name the arguments instead; this exception is for
>   a third-party CLI whose grammar is not ours to re-encode.
>
> **Why:** `exec` and an opaque `"$@"` make a script an unbounded conduit, so
> its real behaviour is whatever the caller and the underlying tool negotiate,
> not what the script documents. Naming every argument keeps behaviour bounded
> by the declared interface and rejects the rest, which is the only way a
> wrapper can carry a contract distinct from the tool it fronts. The exceptions
> are the cases where the forwarded tokens are not a bounded delegation at all
> (the redirection; the message primitives) or _are_ the script's irreducible
> purpose (running a caller's command; fronting a third-party CLI) — each
> enumerated so the boundary is explicit, not discovered by `grep`.

Two further invariants are added (full Rule/Why blocks in Detailed Design's
argument sections below). The first is placed immediately after the
`exec`/`"$@"` rule, continuing the argument-discipline run:

> #### Operational scripts reject unexpected arguments
>
> **Rule:** An operational `bin/` script MUST reject any argument outside its
> declared grammar — a positional beyond the count it consumes, or an unknown
> option — with a non-zero exit from the usage-error band (rule below), never
> silently ignore it. A script that takes no positional MUST error on the first
> one; a script that takes a fixed count MUST error on a surplus one. This binds
> the operational CLIs (lifecycle, db, queue, build, health), not the test
> harnesses (`*-tests`, `tests-common`, `ios-scripts-tests`) or the
> `bin/functions` / `bin/common` libraries. Scripts whose grammar is an
> open-ended caller command or list — `daemon-up`, `daemon-bounce`, `wait-for`,
> `db-run`'s trailing SQL, `q-status`'s filter list — have no "surplus" to
> reject and are exempt.
>
> **Why:** A silently-ignored argument means the script did something other than
> what the caller wrote, with no signal. Rejecting it turns a typo or a stale
> flag (e.g. a former `psql` passthrough) into an immediate, diagnosable failure
> instead of a wrong-but-green run.

The second specializes the existing "Distinct non-zero exit codes per failure
reason" rule and is placed immediately after it:

> #### Usage errors use the reserved 10–29 exit-code band
>
> **Rule:** A usage error (the caller invoked the script wrongly) MUST exit with
> the band code for its category, leaving 1–9 for operational/runtime outcomes:
> `10` unknown option, `11` option missing its value, `20` unexpected argument,
> `21` missing required argument, `22` invalid argument value, `23`
> mutually-exclusive options. The codes are the named constants
> `EXIT_UNKNOWN_OPTION`, `EXIT_OPTION_REQUIRES_VALUE`, `EXIT_UNEXPECTED_ARG`,
> `EXIT_MISSING_REQUIRED_ARG`, `EXIT_INVALID_ARG_VALUE`,
> `EXIT_EXCLUSIVE_OPTIONS`, defined in `bin/functions` and overridable from
> `.env` (for every script that sources `bin/common`; `is-nix`, functions-only,
> uses the defaults). An operational/runtime outcome MUST NOT use a 10–29 code.
> `bin/functions`' own internal `fatal -s` parse guard is exempt (a library
> primitive, not a CLI surface).
>
> **Why:** A caller distinguishes "you typed it wrong" from "the system said no"
> only by the code. Reusing `1` for a usage error collapses it into operational
> failures — a stray argument to `postgres-check` reads as "postgres down", to
> `is-nix` as "not in the dev shell" — silently, since both are consumed in `if`
> guards with stderr suppressed. A reserved band keeps the two classes disjoint;
> the converse — an operational outcome in 10–29 — is equally forbidden, which
> is why `file-lock`'s matching-op fast-fail moves out of `10` (below).

### `exec <command>` removal (process replacement → named child)

Every terminal `exec <command>` is replaced by the same invocation without
`exec`. The exit-code contract is preserved unconditionally: a shell script
exits with the status of the last command it runs, and each of these `exec`s is
the script's last command (all source `bin/common`'s `set -euo pipefail`; the
two system-Xcode scripts set their own `set -euo pipefail` and the `exec` is
their final statement). The single observable difference is process identity — a
`bash` parent persists above the child instead of being replaced — which no
script's documented contract depends on. Signals continue to reach the child via
the terminal's foreground process group; for the interactive `psql` paths
(`db-repl`, a TTY `db-run`) `psql` owns `SIGINT` itself (query-cancel) and exits
on `\q`/EOF as before.

Sites, by shape:

- **Fixed/named Gradle and Xcode terminals** (no `"$@"`): `build-common`,
  `build-db`, `build-net`, `build-service`, `build-queue`, `build-queue-worker`,
  `build-rest-server`, `build-admin-server`, `build-public-web`
  (`exec gradlew :mod:task`); `ingest-colleges`
  (`exec gradlew :college:run
  --args=…`); `test`
  (`exec gradlew "${GRADLE_ARGS[@]}"`, a named array); `build-ios`,
  `release-ios` (`exec xcodebuild …`). Drop `exec`.
- **Health-check fronts** (fixed args): `rest-server-check`,
  `admin-server-check`, `public-web-check`
  (`exec daemon-http-check <label>
  <port> <url>`). Drop `exec`; the 0/1/2
  operational tri-state is preserved as the last command's status (3 is
  `daemon-http-check`'s help/misuse code, never reached on the fixed-arg call).
- **Bounce tails**: `rest-server-bounce`, `admin-server-bounce`,
  `queue-worker-bounce`, `public-web-bounce`, `postgres-bounce` each run
  `<svc>-down` then `exec <svc>-up`. Drop `exec`; `<svc>-up`'s status becomes
  the bounce's. `postgres-bounce` belongs purely here: its `postgres-down` call
  is already named and argument-free, so only the `exec` on the `postgres-up`
  tail is dropped.
- **`file-lock` timeout branch**: `exec wait-for "$TIMEOUT" "${ARGS[@]}"` is
  mid-script, inside `if [ -n "$TIMEOUT" ]`. Drop `exec` and follow the call
  with `exit "$?"`, so control does not fall through to the non-timeout locking
  block below. Under `set -e` a non-zero `wait-for` already aborts with its own
  status; the explicit `exit "$?"` carries the success path. The `wait-for` exit
  semantics are unchanged.
- **`dev-bootstrap` generated hook**: the
  `exec "$(git rev-parse
  --show-toplevel)/bin/pre-commit"` lives inside the
  `<< 'HOOK'` heredoc written to `.git/hooks/pre-commit`. Drop `exec` in the
  generated body; the hook then runs `pre-commit` as its last command and exits
  with `pre-commit`'s status, which git reads to allow or block the commit —
  preserved. The quoted heredoc keeps `$(…)` literal (resolved at hook-run time)
  as today.

The `infra-*` scripts keep `exec tofu … "$@"` verbatim — the third-party-front
exception enumerated in the rule above.

### `*-server-down`: drop the `"$@"` forward

`rest-server-down`, `admin-server-down`, `queue-worker-down`, `public-web-down`
are each `exec "$PROJECT_ROOT/bin/daemon-down" "$@" <svc>`, where `"$@"`
forwards `daemon-down`'s own `-t` lock-timeout option. No caller forwards
anything: every in-repo invocation (the four bounces, the `test-fuzz` and
`scripts-tests` teardown traps) calls them with zero arguments. The wrapper owns
`daemon-down`'s grammar, so it names the call instead:

```
# after
"$PROJECT_ROOT/bin/daemon-down" <svc>
```

Observable delta: `rest-server-down -t 30` (never used in-repo) previously tuned
`daemon-down`'s lock timeout; it now leaves `daemon-down` at its 16s default.
The `<svc>-down` exit status is unchanged (`daemon-down`'s, as the last
command).

`postgres-bounce` does not belong to this `"$@"`-removal narrative: its
`postgres-down` call is already named and argument-free, so its only change is
dropping the `exec` on the `postgres-up` tail (the exec-removal bucket above).

### The `db-query` / `db-write` / `db-run` / `db-repl` interface

These four lose the opaque `psql` passthrough. `db-run` is the sole `psql`
owner; `db-query` (ro) and `db-write` (rw) are bounded fronts over it. The
complete passthrough surface the codebase uses today is two `psql` output modes
— `-tA` (raw) and `-x -P border=0` (expanded) — which become named options.
Every other `psql` flag is no longer accepted; an unrecognised option is
rejected by `getopts` (`fatal`, exit 1) instead of reaching `psql`.

New grammars:

```
db-run [-d <db>] [-r | -x] <ro|rw> [sql]
  -d <db>   target database (default $POSTGRES_DB)
  -r        raw output      -> psql -t -A   (tuples-only, unaligned)
  -x        expanded output -> psql -x -P border=0
  -h        help
  <ro|rw>   required mode; ro sets default_transaction_read_only=on
  [sql]     optional statement; when omitted, psql reads stdin

db-query [-d <db>] [-r | -x] [sql]   ->  db-run [-d <db>] [-r|-x] ro [sql]
db-write [-d <db>] [sql]             ->  db-run [-d <db>] rw [sql]
db-repl  [-h]                        ->  psql -U $POSTGRES_USER -d $POSTGRES_DB
```

Design points:

- **Options lead, then mode, then one optional `sql`.** `db-run` runs a single
  leading `getopts ":d:rxh"` loop that consumes only leading options; after
  `shift $((OPTIND-1))` the first remaining positional is the required mode
  (`ro|rw`) and the second, if present, is the single optional `sql`. The
  grammar is closed by two reject rules, both in the usage-error band (below):
  any post-mode positional beginning with `-` (a residual `psql`-style flag such
  as the trailing `-tA`) is rejected as an unexpected argument
  (`EXIT_UNEXPECTED_ARG`, 20), and more than one post-mode positional is the
  same error. This — not merely the rename — is what makes today's tolerated
  `db-run rw "<sql>" -tA` reject rather than passthrough; the current `*) break`
  loop terminates at the mode positional and lets such trailing flags through.
  It is also why the direct `db-run rw "<sql>" -tA` call sites are reordered to
  `db-run -r rw "<sql>"`. The same reorder applies to the no-inline-sql
  stdin-pipe form `db-run rw -tA` (no `sql` positional; SQL piped on stdin),
  which becomes `db-run -r rw` — the trailing `-tA` moves ahead of the mode as
  `-r`, and the piped stdin SQL still reaches `psql` unchanged.
- **`db-query`/`db-write` forward named flags, never `"$@"`.** Each parses its
  own options into named variables, assembles a named array (`(-d "$db" -r)`
  etc.), and invokes `db-run` with it plus the mode and the optional single
  `sql` positional. More than one positional is `EXIT_UNEXPECTED_ARG` (20).
- **`-r` and `-x` are mutually exclusive** (tuples-only vs expanded are
  contradictory); supplying both is `EXIT_EXCLUSIVE_OPTIONS` (23). An invalid
  mode is `EXIT_INVALID_ARG_VALUE` (22); a missing mode is
  `EXIT_MISSING_REQUIRED_ARG` (21). Neither output flag is accepted by
  `db-write` (writes emit command tags, never a result grid — no in-repo use).
- **`db-run` keeps the offline guard** (`postgres-check` → exit 2) and the ro/rw
  read-only transaction semantics unchanged; only the argument surface and the
  terminal `exec` change. `psql` is invoked as the last command (per branch),
  preserving its exit status as `db-run`'s.
- **`db-repl`** accepts no passthrough; dropping its `"$@"` is a no-op because
  no caller passes `psql` flags to it. Interactive / `\q` / EOF / stdin use is
  unaffected (`psql` still reads stdin / the TTY); `echo "\q" | db-repl` works
  as before.

### Usage-error exit-code band (`bin/functions`)

`bin/functions` gains six exit-code constants — the usage-error band, kept clear
of the 1–9 range reserved for operational/runtime outcomes:

```
EXIT_UNKNOWN_OPTION=10          # getopts '?'  — an option the script does not define
EXIT_OPTION_REQUIRES_VALUE=11   # getopts ':'  — a defined option given without its value
EXIT_UNEXPECTED_ARG=20          # a positional beyond the declared grammar (surplus / stray)
EXIT_MISSING_REQUIRED_ARG=21    # a required positional absent
EXIT_INVALID_ARG_VALUE=22       # a present value that is malformed (bad port, bad mode)
EXIT_EXCLUSIVE_OPTIONS=23       # two options that cannot combine (e.g. -r with -x)
```

Each is assigned with `: "${VAR:=N}"` (assign-if-unset, not `readonly`), so an
`.env` entry sourced after `bin/functions` overrides it and the default stands
otherwise. (`bin/common` sources `bin/functions` first, then `.env` with
auto-export, so the later unconditional `.env` assignment wins; `readonly` would
instead make that assignment a `set -u` fatal.) The home is `bin/functions`, not
`bin/common`/`.env`, because `is-nix` sources only `bin/functions` — it must
report on the dev shell without entering it, so it never reaches `bin/common` or
`.env` — yet needs the band. Every other operational script reaches the band
transitively through `bin/common` (which sources `bin/functions`),
`postgres-check` included; `bin/functions` is the single point all of them
share. The one consequence of `is-nix`'s functions-only sourcing is that its
band codes are the `:=` defaults only — a `.env` override does not reach it —
which is immaterial, since `is-nix` needs only the defaults.

Usage errors are reported through `fatal -s "$EXIT_<category>" "<message>"`,
which writes the diagnostic to stderr and exits with the band code (`fatal`
already accepts `-s`). The `getopts` arms map uniformly across every operational
script — `\?) fatal -s "$EXIT_UNKNOWN_OPTION" "unknown option [-$OPTARG]"` and
`:) fatal -s "$EXIT_OPTION_REQUIRES_VALUE" "option -$OPTARG requires an
argument"`
— replacing today's mixed `fatal`/`help` arms that all exited `1`. Each script's
`help()` reverts to a single purpose: print the usage banner and exit `0`,
invoked only by `-h`; its former "message ⇒ exit 1" branch is removed, since
every error now routes through `fatal -s`. The one observable delta for the
help-routed scripts is that a usage error prints its one-line diagnostic rather
than the full banner; `-h` still prints the banner.

Operational and precondition failures are not usage errors and keep their
existing codes: a missing built binary, an unset `POSTGRES_DATA_DIR`, or a
dependency offline still `fatal` at status `1` (or exit `2` for the offline
guard). `bin/functions`' own `fatal` keeps its internal `\?`/`:` parse guard at
exit `1` — a programming error in a `fatal -s` call, not a CLI surface — exempt
from the band, consistent with its diagnostic-primitive status under the
`exec`/`"$@"` rule.

One existing operational code sits inside the new band and must move so the band
starts free: `file-lock` exits `10` when the lock is already held by a matching
operation (`if [ -n "$OP" ] && [ "$CURRENT_OP" = "$OP" ]; then exit 10`, in both
the timeout fast-fail and the polling branch). That is an operational/runtime
outcome — a "no" from the system, not a usage error — so it is recoded to `3`, a
distinct reason in `file-lock`'s own 1–9 space alongside `1` (active
non-matching lock) and `2` (lock-metadata read failure). The `file-lock`
`help()` already documents its codes; the `3` is added there. The
`bin/scripts-tests` assertion that pins this outcome is updated from `-ne 10` to
`-ne 3` in step 8 (it is the only caller that inspects the code). This is the
sole operational-code change in the RFC; every other operational `1`/`2`/`3` is
left exactly as it is.

### Unexpected arguments are rejected

Twenty-one operational scripts `shift` past their options (or take none) and
never inspect the remaining positionals, so a surplus argument is silently
dropped. Each gains a guard rejecting any leftover positional with
`EXIT_UNEXPECTED_ARG`, in one of two shapes:

- **No option parsing today** — the five `*-bounce` orchestrators
  (`postgres-bounce`, `rest-server-bounce`, `admin-server-bounce`,
  `queue-worker-bounce`, `public-web-bounce`) and the four `*-server-down`
  wrappers (`rest-server-down`, `admin-server-down`, `queue-worker-down`,
  `public-web-down`). These are 2–4 line scripts with no `getopts`; each gains
  `[ "$#" -gt 0 ] && fatal -s "$EXIT_UNEXPECTED_ARG" "<name> accepts no
  arguments: $*"`
  before its child invocations.
- **`getopts` then no positional read** — the four `*-check` probes
  (`rest-server-check`, `admin-server-check`, `public-web-check`,
  `queue-worker-check`), `db-repl`, the five `*-wait-for-health` waiters
  (`rest`/`admin`/`queue-worker`/`public-web`/`postgres`), `is-nix`, and
  `postgres-check`. Each already has the `getopts` / `shift $((OPTIND-1))`
  preamble; the same guard is added immediately after the shift.

The guard lands before any real work, so a stray argument fails fast instead of
after a partial bounce or a health probe. No grammar changes: each of these
takes zero positionals by contract, and the guard only enforces that. The code
is `20`, never the operational `1`/`2` these scripts return for
"down"/"offline"/"conflict" — the collision the band exists to prevent
(`postgres-check` and `is-nix` are consumed in `if` guards with stderr
suppressed, so a `1` for misuse would read silently as "down" / "not in the dev
shell").

Scripts that already reject a surplus positional (added in the `getopts`
standardization: the `*-up` wrappers, `postgres-down`, `db-create`, `db-status`,
`format`, `dev-bootstrap`, `pre-commit`, and the `q-inspect` / `q-retry` /
`q-delete-job` `[ $# != 1 ]` guards) keep the guard and migrate only its exit
code into the band. Where a guard conflated "too few" with "too many" (the `q-*`
`!= 1` form), it splits: `EXIT_MISSING_REQUIRED_ARG` (21) for an absent job id,
`EXIT_UNEXPECTED_ARG` (20) for a surplus one. Open-ended-grammar scripts —
`daemon-up`, `daemon-bounce`, `wait-for`, `db-run`'s trailing SQL, and
`q-status`'s filter list — are untouched on the surplus-rejection axis: every
positional they receive is part of their variadic grammar, so there is no
"surplus" to add a guard for. The iOS `build-ios` / `install-ios` /
`release-ios` scripts are a different case: each takes exactly one optional
`target` positional and already rejects a _second_ one (today via `help` at exit
`1`); that existing surplus guard is recoded to `EXIT_UNEXPECTED_ARG` (20) like
every other arity guard, but no new guard is added.

### Error handling and edge cases

- **Unknown option → bounded rejection.** `db-query -tA` (and any other former
  passthrough flag) now exits `EXIT_UNKNOWN_OPTION` (10) via `fatal -s` rather
  than silently reaching `psql`. This is the intended control surface; the
  migration (below) moves every in-repo caller onto `-r`/`-x` first, so no live
  call regresses.
- **Usage errors name the offending tokens and carry a band code.** The `db`
  arity guards interpolate `$*` so the message shows what was rejected: `db-run`
  → `"db-run accepts at most one SQL argument after the mode; received: $*"`;
  `db-query` / `db-write` →
  `"<name> accepts at most one SQL argument; received:
  $*"`; each exits
  `EXIT_UNEXPECTED_ARG` (20). A residual flag after the mode exits `20` as
  `"unexpected argument after mode [$1]"`. These `$*` / `$1` interpolations are
  diagnostic strings (the `exec`/`"$@"` rule's stated exception), not
  child-forwards.
- **The offline diagnostic carries the connection coordinates.** `db-run` and
  `db-repl` print
  `"PostgreSQL is offline [host=${PGHOST:-localhost}
  port=${PGPORT:-5432}]"`
  and exit `2` (operational, unchanged). `bin/common` exports `PGPORT` from the
  required `POSTGRES_PORT`, and `.env` sets `PGHOST`, so both vars resolve in
  any script sourcing `common`.
- **stdin SQL still flows through.** With no `sql` positional, neither front
  consumes stdin, so a piped `echo "…" | db-query -r` reaches `psql` exactly as
  before.
- **Exit codes preserved for non-usage paths.** Every `exec`-removal site keeps
  its status as the last-command status; `file-lock` adds an explicit
  `exit "$?"`; the bounce and `*-down` wrappers propagate the child's status
  under `set -e` on the success path, exiting `EXIT_UNEXPECTED_ARG` (20) only
  when a stray argument is supplied before any child runs.

### Dependencies

None. No code outside `bin/` changes; no build, schema, service, or
infrastructure contract is affected. The change is confined to operational shell
scripts and their in-repo callers.

## Tests

Verification is by the existing shell suites, run against real Postgres (which
they boot), plus a small set of new assertions for the bounded `db` interface.
No test parses script source; each drives runtime behaviour and asserts exit
codes / output, per the `bin/INVARIANTS.md` convention.

New assertions in `bin/db-scripts-tests` (alongside the existing `db-run`/
`db-query` lifecycle cases, against the real test DB):

1. **`db-query -r` is raw** — a single-value query (`SELECT 1`) returns the bare
   value with no header, footer, or alignment padding (the `-tA` shape callers
   depend on).
2. **`db-query -x` is expanded** — a single-row query returns the expanded
   record view reproducing `q-inspect`'s `-x -P border=0` output (column labels
   present, no border rules, default aligned header absent).
3. **`db-query -tA` is rejected with code 10** — the former opaque flag now
   exits `EXIT_UNKNOWN_OPTION` (10) and produces no query output. This is the
   control assertion: it proves the passthrough is gone and the interface is
   bounded. It would have passed (silently reached `psql`) before this RFC.
4. **`db-run rw "<sql>" --bogus` is rejected with code 20** — a residual
   `psql`-style flag is a post-mode positional beginning with `-`, rejected as
   `EXIT_UNEXPECTED_ARG` (20), not forwarded.
5. **`db-run -r rw` / `db-query -r` round-trip** — a write via `db-run rw` then
   a raw read returns the written value, confirming reordered options and stdin
   both work.
6. **`db-run` band codes** — `db-run` (no mode) exits
   `EXIT_MISSING_REQUIRED_ARG` (21); `db-run bogus` exits
   `EXIT_INVALID_ARG_VALUE` (22); `db-run -r -x ro` exits
   `EXIT_EXCLUSIVE_OPTIONS` (23); `db-run -z ro` exits `EXIT_UNKNOWN_OPTION`
   (10); `db-run -d` (option without its value) exits
   `EXIT_OPTION_REQUIRES_VALUE` (11); `db-query rw extra` exits
   `EXIT_UNEXPECTED_ARG` (20). Each asserts the exact code, pinning the
   category-to-code mapping — every band code (10, 11, 20, 21, 22, 23) has at
   least one assertion.

Argument-rejection and band-code assertions. `bin/tests-common` gains an
`assert_exit_code <code> <message> <command...>` helper (running the command
with its status captured, comparing against `<code>`), so the exact-code
assertions read uniformly rather than each open-coding `status=$?`:

7. **Inverted `postgres-bounce` stray-argument case** — the existing
   `bin/scripts-tests` assertion
   `postgres-bounce ignores a stray argument and
   exits 0 (down then up)` is
   replaced: the test function and its `assert_success` become
   `assert_exit_code 20 "postgres-bounce rejects an
   unexpected argument" bin/postgres-bounce stray`,
   and the preceding comment block (which justified the ignore-and-exit-0
   behaviour) is rewritten to state that the bounce now rejects a stray argument
   before running either child. This is the direct inversion of the old
   behaviour — the assertion that previously demanded exit 0 now demands
   exit 20.
8. **Representative rejection across the guard set** (`bin/scripts-tests`, with
   the cluster the daemon-wrapper tests already booted): `assert_exit_code 20`
   for a no-`getopts` wrapper (`rest-server-down stray`), a `*-check` probe
   (`rest-server-check stray`, asserting `20` distinct from its operational
   `1`/`2`), a `*-wait-for-health` waiter (`postgres-wait-for-health stray`),
   `postgres-check stray`, and `is-nix stray` (asserting `20`, distinct from
   `is-nix`'s operational `0`/`1`). An unknown option is also pinned:
   `assert_exit_code 10 "rest-server-check rejects an unknown option"
   bin/rest-server-check -z`.
   The `file-lock` matching-op assertion is also updated here, from the old
   `-ne 10` to `-ne 3`, tracking that outcome's recode out of the band.
9. **`db-repl` stray-argument case** (`bin/db-scripts-tests`):
   `assert_exit_code
   20 "db-repl rejects an unexpected argument" bin/db-repl stray`,
   alongside the existing interactive-shell case.

Existing suites are the regression test for the call-site migration — they parse
the output of the very calls being migrated, so they fail if `-r` does not
reproduce `-tA`:

- **`bin/db-scripts-tests`**, **`bin/db-convos-tests`**,
  **`bin/db-users-tests`**, **`bin/db-system-prompts-tests`**,
  **`bin/q-scripts-tests`** — pass after migration (the `-tA`→`-r` and flag
  reordering changes).
- **`bin/scripts-tests`** — the `rest-server`/`queue-worker` daemon
  lifecycle/bounce cases pass unchanged, exercising the `exec`-removed bounce
  and `*-down` wrappers.
- **`bin/q-status`**, **`bin/q-inspect`**, **`bin/q-retry`**,
  **`bin/q-delete-job`** — smoke-run to confirm the migrated `db-query`/`db-run`
  calls behave (covered transitively by `q-scripts-tests`).
- **`nix develop -c bin/test check`** — the ktlint + Kotlin + Postgres gate is
  unaffected and stays green (also exercises the `exec`-removed `bin/test`
  itself).
- **`deno fmt --check`** over the RFC, the edited `bin/INVARIANTS.md`.

## Implementation Plan

1. **Establish the usage-error band.** Add the six constants to `bin/functions`
   (`: "${EXIT_UNKNOWN_OPTION:=10}"`, `EXIT_OPTION_REQUIRES_VALUE:=11`,
   `EXIT_UNEXPECTED_ARG:=20`, `EXIT_MISSING_REQUIRED_ARG:=21`,
   `EXIT_INVALID_ARG_VALUE:=22`, `EXIT_EXCLUSIVE_OPTIONS:=23`), and add the
   `assert_exit_code <code> <message> <command...>` helper to
   `bin/tests-common`.
   - Verify:
     `nix develop -c bash -c 'source bin/functions; echo "$EXIT_UNEXPECTED_ARG"'`
     prints `20`; `grep -n 'assert_exit_code' bin/tests-common` matches.

2. **Rebuild the `db` interface.** Edit `bin/db-run`, `bin/db-query`,
   `bin/db-write`, `bin/db-repl` to the grammars in Detailed Design: add
   `-r`/`-x` to `db-run` and `db-query`; map `-r`→`psql -t -A`,
   `-x`→`psql -x -P border=0`; `db-query`/`db-write` forward a named flag
   array + mode + optional single `sql` to `db-run`; remove every `exec` and
   every `"$@"`; `db-repl` drops its `"$@"` and `exec`. Map the usage errors to
   the band: missing mode → `EXIT_MISSING_REQUIRED_ARG` (21), invalid mode →
   `EXIT_INVALID_ARG_VALUE` (22), surplus/residual-flag positional →
   `EXIT_UNEXPECTED_ARG` (20) with `received: $*`, `-r`/`-x` clash →
   `EXIT_EXCLUSIVE_OPTIONS` (23), `\?`→10, `:`→11; add the connection
   coordinates to the offline diagnostic in `db-run` and `db-repl`.
   - Verify:
     `grep -nE 'exec |\$[{(]?[@*]' bin/db-run bin/db-query bin/db-write bin/db-repl`
     shows only the diagnostic `$*` interpolations;
     `nix develop -c bash -c 'bin/db-run; echo $?'` prints `21`;
     `nix develop -c bash -c 'bin/db-run bogus; echo $?'` prints `22`.

3. **Migrate the db/queue call sites.** Across `bin/db-scripts-tests`,
   `bin/db-status`, `bin/q-inspect`, `bin/q-retry`, `bin/db-convos-tests`,
   `bin/db-system-prompts-tests`, `bin/db-users-tests`, `bin/q-scripts-tests`,
   `bin/db-create`, `bin/q-delete-job`: replace `db-query … -tA` with
   `db-query … -r`; replace `db-query … -x -P border=0` (sole site `q-inspect`)
   with `db-query … -x`; rewrite each direct `db-run <mode> [sql] -tA` to
   `db-run -r <mode> [sql]` (options before the mode) — including the
   no-inline-sql stdin-pipe form `db-run rw -tA` → `db-run -r rw` (SQL piped on
   stdin still reaches `psql` unchanged).
   - Verify: `grep -rnE 'db-(query|run|write)\b[^|]*\-(tA|x )' bin/` returns
     nothing (no surviving passthrough flag).

4. **Add unexpected-argument guards to the 21 unguarded scripts.** The five
   `*-bounce` and four `*-server-down` wrappers (no `getopts`) gain
   `[ "$#" -gt 0 ] && fatal -s "$EXIT_UNEXPECTED_ARG" "<name> accepts no
   arguments: $*"`
   before their child invocations; the four `*-check` probes, `db-repl`, the
   five `*-wait-for-health` waiters, `is-nix`, and `postgres-check` gain the
   same guard immediately after `shift $((OPTIND-1))`.
   - Verify: for each of the 21,
     `nix develop -c bash -c 'bin/<name> stray >/dev/null 2>&1; echo $?'` prints
     `20`.

5. **Recode every operational script's usage-error arms to the band.** Across
   all operational scripts with `getopts`, route `\?)` →
   `fatal -s
   "$EXIT_UNKNOWN_OPTION"` and `:)` →
   `fatal -s "$EXIT_OPTION_REQUIRES_VALUE"`; move existing surplus guards to
   `EXIT_UNEXPECTED_ARG` (20), missing-required to `EXIT_MISSING_REQUIRED_ARG`
   (21), invalid-value to `EXIT_INVALID_ARG_VALUE` (22), and mutually-exclusive
   to `EXIT_EXCLUSIVE_OPTIONS` (23); split the
   `q-inspect`/`q-retry`/`q-delete-job` `[ $# != 1 ]` guard into `21` (too few)
   / `20` (too many); simplify each `help()` to print the banner and exit `0`
   (remove the message⇒exit-1 branch). `bin/functions`' own `fatal` parse-guard
   stays at exit `1`.
   - Verify:
     `grep -rnE '\\\?\)|:\)' bin/ | grep -E 'fatal|help' | grep -v 'EXIT_' | grep -v 'bin/functions'`
     returns nothing (every option arm recoded);
     `nix develop -c bash -c 'bin/daemon-check; echo $?'` prints `21`;
     `nix develop -c bash -c 'bin/db-status x; echo $?'` prints `20`.

6. **Remove `exec` from the process-replacement sites.** Drop `exec` in the nine
   `build-*` Gradle wrappers, `ingest-colleges`, `test`, `build-ios`,
   `release-ios`, the three `*-check` wrappers, the four `*-bounce` wrappers and
   `postgres-bounce` (whose `postgres-down` call is already named/argument-free
   — only the `exec` on its `postgres-up` tail is dropped), and the
   `dev-bootstrap` generated heredoc. In `file-lock`, drop `exec` and add
   `exit "$?"` after the `wait-for` call, and recode its matching-op fast-fail
   from `exit 10` to `exit 3` in both branches (timeout fast-fail and polling),
   documenting `3` in its `help()` — freeing `10` for the usage-error band.
   - Verify: `grep -rnE '(^|[^[:alnum:]_])exec ' bin/ | grep -v '3<>/dev/tcp'`
     lists only the five `infra-*` scripts; `grep -n 'exit 10' bin/file-lock`
     returns nothing.

7. **Name the `*-down` wrappers.** Rewrite `rest-server-down`,
   `admin-server-down`, `queue-worker-down`, `public-web-down` to
   `"$PROJECT_ROOT/bin/daemon-down" <svc>` (no `exec`, no `"$@"`; the
   surplus-argument guard from step 4 sits above the call).
   - Verify:
     `grep -nE '\$[{(]?[@*]' bin/rest-server-down bin/admin-server-down bin/queue-worker-down bin/public-web-down`
     shows only the diagnostic `$*` in each guard.

8. **Invert and extend the argument tests.** In `bin/scripts-tests`, replace the
   `postgres-bounce ignores a stray argument and exits 0` case (function, the
   `assert_success`, and the preceding rationale comment) with the
   `assert_exit_code 20 "postgres-bounce rejects an unexpected argument"` form;
   add the representative `assert_exit_code 20`/`10` cases (Tests §8); and
   update the existing `file-lock` matching-op assertion from `-ne 10` to
   `-ne 3`, tracking the operational recode. In `bin/db-scripts-tests`, add the
   bounded-interface and band-code assertions (Tests §3–6), the `db-repl` stray
   case (Tests §9), and switch the existing `-tA` assertions to `-r`.
   - Verify: `nix develop -c bin/scripts-tests` and
     `nix develop -c bin/db-scripts-tests` pass.

9. **Run the migrated DB/queue suites.**
   - Verify: `nix develop -c bin/db-convos-tests`,
     `nix develop -c bin/db-users-tests`,
     `nix develop -c bin/db-system-prompts-tests`,
     `nix develop -c bin/q-scripts-tests` all pass.

10. **Update `bin/INVARIANTS.md`.** Replace, in place, the existing section
    headed _"`exec` and `"$@"` passthrough are reserved for single-target
    wrappers"_ with the "`exec <command>` and `"$@"`/`"$*"` child-forwarding are
    forbidden" Rule/Why block (keeping its position after the port-liveness
    rule), add the "Operational scripts reject unexpected arguments" rule
    immediately after it, and add the "Usage errors use the reserved 10–29
    exit-code band" rule immediately after the "Distinct non-zero exit codes per
    failure reason" rule. Confirm the `## History` RFC-80 line.
    - Verify: `grep -n 'child-forwarding are forbidden' bin/INVARIANTS.md`,
      `grep -n 'reject unexpected arguments' bin/INVARIANTS.md`, and
      `grep -n 'reserved 10–29 exit-code band' bin/INVARIANTS.md` all match;
      `grep -nF 'reserved for single-target wrappers' bin/INVARIANTS.md` returns
      nothing; `grep -n 'RFC-80' bin/INVARIANTS.md` matches.

11. **Full gate.**
    - Verify: `nix develop -c bin/test check` passes;
      `nix develop -c deno fmt
      --check rfc/80-bin-exec-passthrough-discipline.md bin/INVARIANTS.md`
      is clean.

## Files Modified

`db` interface (controlled grammar; `exec` + passthrough removed):

- `bin/db-run` — add `-r`/`-x`; remove `psql` passthrough and all four `exec`
  sites; named `psql` invocation.
- `bin/db-query` — add `-r`/`-x`; forward a named flag array to `db-run`; remove
  passthrough and `exec`.
- `bin/db-write` — `-d`/`[sql]` only; named `db-run` call; remove passthrough
  and `exec`.
- `bin/db-repl` — remove `"$@"` and `exec`.

Call-site migration (`-tA`→`-r`, `-x -P border=0`→`-x`, `db-run` flag reorder):

- `bin/db-scripts-tests` — migrate, and add the five bounded-interface
  assertions.
- `bin/db-status`
- `bin/db-convos-tests`
- `bin/db-system-prompts-tests`
- `bin/db-users-tests`
- `bin/q-scripts-tests`
- `bin/q-inspect`
- `bin/q-retry`
- `bin/q-delete-job`
- `bin/db-create`

`exec` removal (process replacement → named child):

- `bin/build-common`, `bin/build-db`, `bin/build-net`, `bin/build-service`,
  `bin/build-queue`, `bin/build-queue-worker`, `bin/build-rest-server`,
  `bin/build-admin-server`, `bin/build-public-web`
- `bin/ingest-colleges`
- `bin/test`
- `bin/build-ios`, `bin/release-ios`
- `bin/rest-server-check`, `bin/admin-server-check`, `bin/public-web-check`
- `bin/rest-server-bounce`, `bin/admin-server-bounce`,
  `bin/queue-worker-bounce`, `bin/public-web-bounce`, `bin/postgres-bounce`
  (only the `exec` on the `postgres-up` tail; its `postgres-down` call is
  already named/argument-free)
- `bin/file-lock` (drop `exec`; add `exit "$?"`; recode the matching-op
  fast-fail from operational `10` to operational `3` in both branches, freeing
  `10` for the band, and document `3` in its `help()`)
- `bin/dev-bootstrap` (drop `exec` in the generated `pre-commit` heredoc)

`exec` + `"$@"` removal (`*-down` wrappers):

- `bin/rest-server-down`, `bin/admin-server-down`, `bin/queue-worker-down`,
  `bin/public-web-down`

Usage-error band + test helper:

- `bin/functions` — add the six band constants (`EXIT_UNKNOWN_OPTION` …
  `EXIT_EXCLUSIVE_OPTIONS`), assign-if-unset for `.env` override.
- `bin/tests-common` — add the `assert_exit_code` helper.

Unexpected-argument guard (new — the 21 scripts that silently ignore surplus
arguments today):

- No-`getopts` wrappers: `bin/postgres-bounce`, `bin/rest-server-bounce`,
  `bin/admin-server-bounce`, `bin/queue-worker-bounce`, `bin/public-web-bounce`,
  `bin/rest-server-down`, `bin/admin-server-down`, `bin/queue-worker-down`,
  `bin/public-web-down`.
- `getopts`-then-no-positional-read: `bin/rest-server-check`,
  `bin/admin-server-check`, `bin/public-web-check`, `bin/queue-worker-check`,
  `bin/db-repl`, `bin/rest-server-wait-for-health`,
  `bin/admin-server-wait-for-health`, `bin/queue-worker-wait-for-health`,
  `bin/public-web-wait-for-health`, `bin/postgres-wait-for-health`,
  `bin/is-nix`, `bin/postgres-check`.

Usage-error band recode (every operational `getopts` script: `\?`→10, `:`→11;
existing arity/exclusivity guards → 20/21/22/23; `q-*` `!= 1` split into 21/20;
`help()` simplified to banner-and-exit-0). The four `db-*` scripts above are
already covered by the `db` interface group; the remainder:

- Daemon/service lifecycle: `bin/daemon-up`, `bin/daemon-down`,
  `bin/daemon-bounce`, `bin/daemon-check`, `bin/daemon-status`,
  `bin/daemon-http-check`, `bin/rest-server-up`, `bin/admin-server-up`,
  `bin/queue-worker-up`, `bin/public-web-up`, `bin/postgres-up`,
  `bin/postgres-down`, `bin/rest-server-check`, `bin/admin-server-check`,
  `bin/public-web-check`, `bin/queue-worker-check`,
  `bin/rest-server-wait-for-health`, `bin/admin-server-wait-for-health`,
  `bin/queue-worker-wait-for-health`, `bin/public-web-wait-for-health`,
  `bin/postgres-wait-for-health`, `bin/postgres-check`.
- Build/test/tooling: `bin/build`, `bin/build-common`, `bin/build-db`,
  `bin/build-net`, `bin/build-service`, `bin/build-queue`,
  `bin/build-queue-worker`, `bin/build-rest-server`, `bin/build-admin-server`,
  `bin/build-public-web`, `bin/build-ios`, `bin/install-ios`, `bin/release-ios`,
  `bin/ingest-colleges`, `bin/test`, `bin/format`, `bin/dev-bootstrap`,
  `bin/pre-commit`, `bin/file-lock`, `bin/find-free-port`, `bin/check-pid`,
  `bin/check-port`, `bin/is-nix`, `bin/deploy`, `bin/admin-grant`,
  `bin/wait-for`. (`wait-for` is exempt from the _unexpected-argument_ guard —
  its trailing `<command…>` is open-ended grammar — but its `getopts`
  usage-error arms are not: `\?`→10, `:`→11, its `help "missing parameters"` →
  `EXIT_MISSING_REQUIRED_ARG` (21) for an absent `<timeout>`/`<command…>`, and
  its `help "Invalid timeout format …"` → `EXIT_INVALID_ARG_VALUE` (22), with
  `help()` simplified to banner-and-exit-0 like every other operational script.)
- DB/queue management & CLIs: `bin/db-bootstrap`, `bin/db-create`,
  `bin/db-create-role`, `bin/db-drop`, `bin/db-migrate`, `bin/db-reset`,
  `bin/db-status`, `bin/q-enqueue`, `bin/q-status`, `bin/q-truncate`,
  `bin/q-inspect`, `bin/q-retry`, `bin/q-delete-job`.

(The test harnesses `bin/test-fuzz`, `bin/*-tests`, `bin/tests-common`,
`bin/ios-scripts-tests` and the `bin/infra-*` `tofu` fronts are out of scope for
the recode; `bin/functions`' own `fatal` parse-guard stays at exit 1.)

Tests (argument-rejection inversion + band-code assertions):

- `bin/scripts-tests` — invert the `postgres-bounce` stray-argument case
  (function, `assert_success`→`assert_exit_code 20`, rationale comment) and add
  the representative rejection / unknown-option assertions.
- `bin/db-scripts-tests` — also listed under call-site migration; adds the
  band-code and `db-repl` stray-argument assertions.

Rule:

- `bin/INVARIANTS.md` — replace, in place, the existing "single-target wrappers"
  rule with the prohibition rule + enumerated exceptions; add the "Operational
  scripts reject unexpected arguments" rule after it and the "Usage errors use
  the reserved 10–29 exit-code band" rule after "Distinct non-zero exit codes
  per failure reason"; the `## History` line for this RFC is already present
  (verify, do not duplicate).

RFC:

- `rfc/80-bin-exec-passthrough-discipline.md` — this file.
