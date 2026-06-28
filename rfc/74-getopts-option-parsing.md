# RFC 74: Standardize `bin/` Option Parsing on `getopts` (Short-Only)

## Executive Summary

Every script under `bin/` parses options with a hand-rolled
`while … case "$1" in` (or `for arg`) loop whose sole reason to exist is
accepting GNU-style long spellings (`--help`, `--port`, `--force`, …) alongside
their short forms. This RFC replaces that idiom with `bash`'s built-in
`getopts`, drops all long-option support, and collapses every long spelling to
its short form (`--help`→`-h`, `--port`→`-p`, `--force`→`-f`, and so on).
`getopts` is currently used by no script in `bin/`; this RFC introduces it.

The motivation is uniformity: one parsing mechanism with option clustering
(`-fv`), `OPTARG`/`OPTIND` conventions, and a single error-handling shape across
all scripts, instead of ~60 bespoke loops that drift in message wording and
edge-case handling.

Each script keeps a leading-colon optstring (`:p:h`) so `getopts` suppresses its
own diagnostics; usage errors route through the script's existing `fatal`/`help`
sink, preserving the `bin/INVARIANTS.md` guarantees (source `bin/common`, all
diagnostics to stderr, a distinct non-zero exit code per failure reason) and
every script's documented exit-code contract. Parsing is the only thing that
changes: command grammars, validation, and exit codes are preserved verbatim —
with one deliberate exception, the destructive-confirmation sentinel
`--yes-i-really-want-to-do-this` (in `db-drop`/`q-truncate`) collapses to `-y`
per architect decision.

The port-collision scripts (`check-port`, `daemon-http-check`, `daemon-up`,
`daemon-check`, `daemon-status`, `find-free-port`) and their `--port` call sites
are ordinary in-scope members of this single migration; `bin/test-tcp-listener`
is a Python helper with positional-only arguments and no option flags, so
`getopts` does not apply and it is untouched.

## Detailed Design

### The migration idiom

Each script replaces its manual loop with an inline `getopts` loop using a
**leading-colon optstring**. The leading colon puts `getopts` in silent mode: on
an unknown option it sets `opt='?'` with `OPTARG` = the offending letter; on a
missing required option-argument it sets `opt=':'` with `OPTARG` = the letter.
The script never relies on `getopts`' own stderr messages — both error cases
route to the script's existing usage-error sink so the wording, the stderr
destination, and the **exit code are unchanged**:

```
while getopts ":<optstring>" opt; do
  case "$opt" in
  <letter>) … ;;
  \?) <SINK> "unknown option [-$OPTARG]" ;;
  :)  <SINK> "option -$OPTARG requires an argument" ;;
  esac
done
shift $((OPTIND-1))
# positional handling unchanged from here
```

`<SINK>` is whichever the script uses today: `fatal "…"` (exit 1) for the
`-*) fatal …` scripts, or `help "…"` for the `-*) help …` scripts (which exits
with that script's documented usage-error code — 1 for most, 2 for `check-port`
and `bin/test`, 3 for `daemon-http-check`). `shift $((OPTIND-1))` drops the
consumed options, leaving the positional arguments exactly where the prior loop
left them.

The two generic wordings above (`unknown option [-$OPTARG]`,
`option -$OPTARG requires an argument`) are **canonical** — every migrated `\?`
and `:` arm uses them, superseding each script's bespoke phrasing (e.g.
`daemon-up`'s current `--port requires a value` becomes the generic `:`-arm
wording). Only the sink and its exit code are per-script.

The loop is kept **inline per script** rather than extracted to a shared helper
in `bin/functions`: each optstring is script-specific, and a shared wrapper
could not express the per-script sink/exit-code or the value-option set without
becoming a parameterized re-implementation of `getopts`. The canonical wording
is the one cross-cutting string the inline loops duplicate; it is upheld by
copying the two strings above verbatim into every `\?`/`:` arm, not by a shared
helper. Because the message text is not exit-code-bearing, the behavioral
harnesses do not assert on it; a divergent wording would not fail a test, so the
canonicality is a review-enforced convention rather than a tested guarantee.

### Long→short collapse

Most long spellings already have a short alias; the migration simply deletes the
long alias. Five options are long-only and are assigned a short letter (no
collisions within their script); one long alias is dead and is deleted:

| Script                  | Long-only option                    | Collapses to |
| ----------------------- | ----------------------------------- | ------------ |
| `install-ios`           | `--launch`                          | `-l`         |
| `release-ios`           | `--no-upload`                       | `-n`         |
| `q-enqueue`             | `--max-attempts`                    | `-m`         |
| `q-enqueue`             | `--delay`                           | `-d`         |
| `db-drop`, `q-truncate` | `--yes-i-really-want-to-do-this`    | `-y`         |
| `functions` (`fatal`)   | `--status-code` (never called long) | `-s` only    |

Every migrated script's `help()` text is rewritten to the short-only spelling
with its exit-code documentation unchanged (e.g. `-h, --help: Help` →
`-h: Help`; `q-enqueue` documents `-m <n>` / `-d <duration>`; `daemon-up`
documents `-p <port>`).

### Per-script optstrings

**Help-only (`:h`), the bulk.** Optstring `:h`; `-h)` calls `help`. The `\?`/`:`
arms call the script's current sink, preserving its exit code:

- Sink = `fatal` (exit 1): `admin-grant`, `admin-server-check`,
  `admin-server-up`, `admin-server-wait-for-health`, `build`,
  `build-admin-server`, `build-common`, `build-db`, `build-net`,
  `build-public-web`, `build-queue`, `build-queue-worker`, `build-rest-server`,
  `build-service`, `check-pid`, `daemon-bounce`, `db-bootstrap`, `db-create`,
  `db-create-role`, `db-migrate`, `db-repl`, `db-reset`, `deploy`,
  `ingest-colleges`, `postgres-check`, `postgres-down`, `postgres-up`,
  `postgres-wait-for-health`, `public-web-check`, `public-web-up`,
  `public-web-wait-for-health`, `queue-worker-check`, `queue-worker-up`,
  `queue-worker-wait-for-health`, `rest-server-check`, `rest-server-up`,
  `rest-server-wait-for-health`, `q-delete-job`, `q-inspect`, `q-retry`,
  `q-status`.
- Sink = `help` (prints usage, exit 1): `dev-bootstrap`, `format`, `pre-commit`,
  `test-fuzz`, `build-ios`, `find-free-port`, `daemon-status`.
- Sink = `help` with a non-1 usage-error code: `check-port` (exit 2),
  `daemon-http-check` (exit 3). The optstring is still `:h`; only the sink's
  exit code differs, and it is unchanged from today.

`find-free-port` takes an optional positional `<base>`; `getopts` stops at it,
then the existing `[ "$#" -gt 1 ]` too-many-arguments check and `validate_port`
run unchanged. `check-port` (one positional `<port>`) and `daemon-http-check`
(three positionals) likewise keep their post-`shift` validation verbatim.

`q-delete-job`, `q-inspect`, `q-retry` currently short-circuit on `-h` with a
`[[ "${1:-}" == "-h" || … "--help" ]]` one-liner before a single positional
`<job-id>`; they migrate to the `:h` loop followed by their unchanged positional
handling. Today an unknown leading `-x` falls through to `<job-id>`; the `:h`
migration newly **rejects** it via the `\?` arm (`fatal`, exit 1) — an intended
tightening (see the `db-status` `*) break` note below), covered by the tests.

`daemon-bounce` is in this `:h` group but shares `daemon-up`'s
`[options] <service-name> -- <command…>` leading-only shape: it stops at the
first non-option (`*) break` today) and forwards a mandatory `--`/command to
`daemon-up`. `getopts` halts before `<service-name>` and never consumes the `--`
or the command; the existing `--`-separator check and passthrough are unchanged.

**Flag clusters (no option-arguments).** Optstring lists each flag letter;
clustering (`-qv`) now works:

| Script        | Optstring | Letters                | `\?` sink |
| ------------- | --------- | ---------------------- | --------- |
| `is-nix`      | `:hqv`    | `-h`, `-q`, `-v`       | `fatal`   |
| `install-ios` | `:hl`     | `-h`, `-l` (launch)    | `help`    |
| `release-ios` | `:hn`     | `-h`, `-n` (no-upload) | `help`    |

`is-nix` already carries a `-q` short alias (`-q|--quiet`), so `:hqv` is a pure
long-alias deletion. Four internal callers gate on its exit code via the
`--quiet` spelling — `bin/pre-commit`, `bin/build-ios`, `bin/install-ios`,
`bin/release-ios` each call `is-nix --quiet` as a dev-shell guard — so each
collapses to `is-nix -q` (exit-code semantics unchanged; the nix guard still
fires).

**Single value-option, options-before-positionals.** These scripts already stop
at the first non-option (`*) break`, or a `while [[ "${1:-}" == -* ]]`
condition). `getopts` halts at the same point; `shift $((OPTIND-1))` then hands
the positionals to the unchanged downstream logic. The leading-colon `:` arm
replaces each script's bespoke "requires an argument" check.

| Script        | Optstring | Value option              | Positionals after      | `\?`/`:` sink |
| ------------- | --------- | ------------------------- | ---------------------- | ------------- |
| `db-status`   | `:f:h`    | `-f <format>`             | passthrough            | `help`        |
| `db-query`    | `:d:h`    | `-d <database>`           | SQL/`psql` args        | `help`        |
| `db-run`      | `:d:h`    | `-d <database>`           | `rw`/`ro` + args       | `help`        |
| `db-write`    | `:d:h`    | `-d <database>`           | `psql` args            | `help`        |
| `daemon-down` | `:ht:`    | `-t <lock-timeout>`       | `<service-name>`       | `fatal`       |
| `wait-for`    | `:hp:`    | `-p <period>`             | `<timeout> <command…>` | `help`        |
| `file-lock`   | `:ho:t:`  | `-o <op>`, `-t <timeout>` | `<lock-dir> <ttl>`     | `fatal`       |

`db-status`/`db-query`/`db-run`/`db-write` today fall through an unknown leading
option to `*) break`, silently treating `-x` as a positional. Under `getopts`
that unknown option is now **rejected** via the `\?` arm. This tightening is
intentional and matches this RFC's test requirement that unknown options are
rejected; the exit code (1, via `help`) is unchanged.

For `wait-for` and `daemon-up`, `getopts` stops at the first non-option — the
`<timeout>` value and the `<service-name>` respectively — so the trailing
`<command…>` (which carries its own flags) is never seen by `getopts`. The
verbatim passthrough is preserved.

**Permuted options and positionals.** `bin/test` and `q-truncate` accept options
and positionals in any order. `getopts` alone stops at the first positional, so
they wrap `getopts` in an outer loop that processes one non-option per round and
**resets `OPTIND=1` before each inner `getopts` run** (mandatory — `OPTIND` is
not auto-reset within a single shell):

```
while [ "$#" -gt 0 ]; do
  OPTIND=1
  while getopts ":<optstring>" opt; do … done
  shift $((OPTIND-1))
  [ "$#" -gt 0 ] && { <consume one non-option>; shift; }
done
```

- `bin/test`: optstring `:ht:fvc` (`-t <glob>` value; `-f`/`-v`/`-c` flags). The
  per-round non-option is the `check` keyword (`TASK=check`) or a module name
  (validated by `is-module`, de-duplicated; unknown →
  `help "unknown module …"`). The `:` arm for `-t` preserves the "requires a
  GLOB" semantics; `\?` → `help "unknown option [-$OPTARG]"`. `bin/test`'s
  `help` sink exits **2** on usage error (unchanged), so its parsing failures
  exit 2, not 1. Intermixed forms documented in CLAUDE.md
  (`bin/test rest-server --tests "…"`) keep working as
  `bin/test rest-server -t "…"`.
- `q-truncate`: optstring `:hy` (`-y` = skip the
  `require_dangerous_confirmation` prompt; `-h` exits 0). The per-round
  non-option is a job-type, collected into `types`. `\?` →
  `fatal "Unknown option [-$OPTARG]"`. Permutation preserves both
  `q-truncate type.a -y` and `q-truncate -y type.a`.

**Positionals-before-options.** `q-enqueue`'s grammar is
`<job-type> <payload> [options]` — options strictly trail two required
positionals. It keeps a leading `-h` short-circuit (collapsed from
`-h`/`--help`) so help works before the positional-count check, consumes the two
positionals (`shift 2`), then runs `getopts ":m:d:"` over the remainder
(`-m <n>`, `-d <duration>`). `\?`/`:` → `fatal`. After `shift $((OPTIND-1))`,
any leftover argument → `fatal "Unknown option"`.

**No-positional, confirmation flag.** `db-drop` accepts only `-h` and `-y`
(`--yes-i-really-want-to-do-this`) and rejects every positional. Optstring
`:hy`; `-y` sets `skip_prompt`; `\?` and any remaining positional after
`shift $((OPTIND-1))` → `fatal` (preserving "does not accept positional
arguments"). `bin/db-reset` invokes `db-drop --yes-i-really-want-to-do-this`, so
that call site collapses to `db-drop -y` (db-reset still drops
non-interactively).

**Library function `fatal()`** (`bin/functions`). `fatal` parses its own
`-s <status-code>` option. It migrates to `getopts ":s:"` and **must declare
`local OPTIND`** (and `local OPTARG`) so the global parser index is not mutated
in callers' shells; the `--status-code` long alias (never invoked) is dropped.
It keeps its self-error path (exit 1) — `fatal` cannot recurse into itself.
`fatal -s 3 "msg"` and `fatal "msg"` both continue to work (`getopts` consumes
`-s 3`, stops at the message; `shift`, then `echo "[FATAL] $*"`).

**Daemon scripts carrying `-p <port>`.** `daemon-up` and `daemon-check` take a
`-p <port>` value option (collapsed from `--port`) ahead of their positionals.

- `daemon-up`: optstring `:hp:`. `getopts` parses only the **leading** options
  and halts at the first non-option (`<service-name>`), which always precedes
  the mandatory `--`; `getopts` therefore **never consumes the `--` or the
  command**. After `shift $((OPTIND-1))`, the existing manual handling of
  `<service-name>`, the `--` separator check, and `COMMAND=("$@")` is unchanged.
  The `-p` numeric/range validation (1..65535) is unchanged; `:` (missing `-p`
  value) → `help` with the canonical missing-argument wording; `\?` → `fatal`
  (exit 1). Exit codes 0/1/3 unchanged.
- `daemon-check`: optstring `:hp:`; positional `<service-name>`. `-p` validation
  unchanged; `:` (missing `-p` value) → `help`; `\?` → `fatal` (exit 1). Exit
  codes 0/1/2 unchanged.

Note that `daemon-up` and `daemon-check` are the **only** two scripts whose two
error arms route to **different** sinks — `:` → `help`, `\?` → `fatal` — rather
than a single shared `<SINK>`. This split is deliberate: it preserves each
script's current behavior verbatim (the missing-`--port`-value path already
calls `help`, the unknown-option path already calls `fatal`). An implementer
must not unify the two arms onto one sink for these two scripts.

`daemon-status` (own parser `:h`, `\?` → `help`) and the three `-up` wrappers
relay `--port` to these scripts; those call sites collapse to `-p`:
`daemon-status` calls `daemon-check -p "$port"`, and `rest-server-up`,
`admin-server-up`, `public-web-up` each call `daemon-up -p … -- …`.
(`queue-worker-up` and `postgres-up` pass no port.)

### Error handling and edge cases

- **Exit-code preservation.** The `\?`/`:` arms invoke each script's existing
  sink, so unknown-option and missing-argument failures keep their current exit
  code (1 generally; 2 `check-port` and `bin/test`; 3 `daemon-http-check`; and
  the multi-failure scripts' documented codes are untouched because parsing
  failures already routed to the usage-error code).
- **`OPTIND` hygiene.** Standalone scripts start a fresh process (`OPTIND=1`
  implicitly). The two permute loops reset `OPTIND=1` per round; `fatal()`
  localizes `OPTIND`. These are the only places `getopts` runs more than once or
  inside a function.
- **Clustering** (`-fv`, `-qv`) is newly accepted wherever multiple flags exist;
  it is additive and breaks no current invocation.
- **The one intentional behavior change.** `-y` replaces
  `--yes-i-really-want-to-do-this` in `db-drop`/`q-truncate` (architect
  decision). This shortens the deliberately verbose destructive-confirmation
  bypass; the interactive prompt path (no flag) is unchanged.

### Dependencies

`bash` `getopts` (built-in; no new toolchain). No `bin/common`/`bin/functions`
structural change beyond the `fatal()` migration.

## Tests

Behavioral coverage lives in `bin/scripts-tests` (daemon/general scripts and
`bin/functions`), `bin/q-scripts-tests` (`q-*`), `bin/ios-scripts-tests`
(`install-ios`/`release-ios`/`is-nix` via shim wrappers), and
`bin/db-scripts-tests` (the db-lifecycle scripts against a private throwaway
cluster). All use the `assert_success`/`assert_failure` helpers in
`bin/tests-common`. Exit-code contracts are unchanged, so existing lifecycle
assertions must continue to pass; the additions below verify the parsing
surface.

`bin/db-scripts-tests` is a standalone private-cluster harness — it is **not**
invoked by `bin/test` or `bin/db-tests`, so the standard gate cannot catch its
breakage; it must be run explicitly (`nix develop -c bin/db-scripts-tests`). It
calls `bin/db-drop --yes-i-really-want-to-do-this` (four sites) and
`bin/db-status --format <type>` (two live sites), all of which spell options
this migration deletes; those call sites must be collapsed alongside the
`db-drop`/`db-status` migrations (see its update block below), or the harness
silently breaks.

### `bin/ios-scripts-tests` updates

`run_install`/`run_release` forward argv verbatim to
`install-ios`/`release-ios`, so collapse the existing `run_install --launch …`
call sites to `run_install -l …` and the `run_release --no-upload …` call sites
to `run_release -n …`. The existing assertions (launch records a process launch;
`--no-upload` archives Release) stay otherwise unchanged.

### `bin/db-scripts-tests` updates

Collapse the long options this migration deletes, so the harness keeps passing
against the migrated `db-drop`/`db-status`:

- The four `bin/db-drop --yes-i-really-want-to-do-this` call sites become
  `bin/db-drop -y`.
- The two live `bin/db-status --format <type>` call sites become
  `bin/db-status -f <type>` (`--format applied-only`→`-f applied-only`,
  `--format unapplied-only`→`-f unapplied-only`); the `--format` occurrences
  inside `fail_test` diagnostic strings are message text and may be left or
  reworded, but the executed invocations must use `-f`.

The lifecycle assertions (drop/recreate succeeds, the format filter emits the
expected single line, pending-migration detection) stay otherwise unchanged.

### `bin/scripts-tests` additions

A new `==== Testing option parsing ====` section asserting, against
representative scripts:

1. **Unknown option rejected, exit code preserved.**
   - `bin/check-pid -x` fails (exit 1) — `assert_failure`.
   - `bin/daemon-down -x svc` fails (exit 1).
   - `bin/db-status -x` fails (exit 1) — confirms the `*) break` tightening: an
     unknown leading option is now rejected, not treated as a positional.
2. **Missing option-argument rejected.**
   - `bin/daemon-down -t` (no value) fails (exit 1).
   - `bin/file-lock -o` (no value) fails.
   - `bin/wait-for -p` (no value) fails.
3. **Value option accepted.** `bin/db-status -f csv …` parses (existing format
   behavior unchanged).
4. **Clustering and the `-q` guard.** `bin/is-nix -qv` is accepted (exits per
   dev-shell predicate, not a parse error) — assert it does not fail with a
   parse error. Add `bin/is-nix -q` parses (no parse error) and, inside the dev
   shell, exits 0 — this is the exact form the `bin/pre-commit` nix-guard now
   uses, so a regression here would break commits.
5. **`-h` exits 0.** Retain the existing `$CHECK_CMD -h` and
   `bin/daemon-status -h` assertions; add `bin/file-lock -h`, `bin/wait-for -h`.
6. **`fatal` option parsing.** Add assertions exercising the real `fatal`
   contract (the harness already sources `bin/functions`): `fatal -s 7 "x"`
   exits 7; `fatal "x"` exits 1; `fatal -s 7 "a b c"` exits 7 and prints the
   full message (confirming `getopts` consumed only `-s 7` and the message
   survives `shift`).
7. **`daemon-up` `--` passthrough still boots a flag-carrying command.** Start a
   command that itself takes options after `--`, e.g.
   `bin/daemon-up passthru-test -- bash -c 'sleep 60'` (or a command with a
   literal `-` flag), assert the PID file is written and the process is alive,
   then `bin/daemon-down passthru-test`. Confirms `getopts` did not consume `--`
   or the command's own flags.
8. **`bin/test` option/positional permutation.** Real invocations only; each one
   is driven to a deterministic terminal outcome by the parser's own pre-Gradle
   validation, so no Gradle task runs and the suite never recursively re-invokes
   itself. The permutation case asserts on the real **error output**, not on any
   inspected internal state:
   - `bin/test bogus-module` fails — unknown module rejected.
   - `bin/test -z` fails — unknown option rejected.
   - **Permutation (trailing option parsed).**
     `bin/test rest-server -t 'ed.unicoach.X' queue-worker` fails **and** its
     stderr contains the `--tests requires exactly one module` message. That
     gate fires only when the trailing `-t` is parsed as the `-t` option (so two
     modules end up selected); if the post-module `-t` were instead rejected as
     an unknown option, the message would differ — asserting on the message text
     distinguishes the two.
     `bin/test -t 'ed.unicoach.X' rest-server queue-worker` (option first) must
     fail with the identical message, proving the outcome is
     position-independent.
9. **`-p` daemon scripts.** Add `bin/daemon-check -p` (missing value) fails;
   `bin/daemon-up -p` (missing value) fails; `bin/daemon-check -x svc` fails.
   The existing RFC-collision section's `daemon-up --port …` and
   `daemon-check --port …` invocations (and the `--port` intake-validation
   assertions for empty/non-numeric/out-of-range values) collapse to `-p`,
   verbatim otherwise.
10. **`find-free-port`.** `bin/find-free-port -h` exits 0;
    `bin/find-free-port -x` fails; `bin/find-free-port 18000 19000` (too many
    args) fails.
11. **Inline sentinel-bypass block.** `bin/scripts-tests` re-implements the
    caller-side flag-bypass pattern inline with a hard-coded
    `--yes-i-really-want-to-do-this` (the
    `require_dangerous_confirmation flag bypass` case). Collapse that literal
    and its `case` arm to `-y` and fix the explanatory comment to read `-y`.
12. **`db-reset` still drops.** `bin/db-reset` calls `db-drop -y` after the
    collapse; assert a `bin/db-reset` run succeeds (drops/recreates), proving
    the relayed `-y` bypasses the confirmation prompt non-interactively.

### `bin/q-scripts-tests` updates

1. **Sentinel collapse.** Replace `--yes-i-really-want-to-do-this` with `-y` at
   its three call sites (`q-truncate -y`, `q-truncate type.a -y`,
   `q-truncate -y` in the cascade test). Add an assertion that
   `q-truncate -y type.a` (flag before type) also skips the prompt — covers the
   permutation.
2. **`q-enqueue` short options.** Replace `--delay 10m` → `-d 10m` and
   `--max-attempts 10` → `-m 10`. Stored-value assertions
   (`scheduled_at`/`max_attempts`) unchanged.
3. **Rejections.** Add: `q-enqueue jt '{}' -x` fails (unknown trailing option);
   `q-enqueue jt '{}' -d` fails (missing value); `q-truncate -x` fails. Add
   `q-delete-job -x`, `q-inspect -x`, `q-retry -x` each fail (exit 1) — the `:h`
   migration newly rejects the unknown option that previously fell through to
   `<job-id>`.
4. **Help short form.** Add `q-delete-job -h`, `q-status -h`, `q-truncate -h`
   each exit 0; confirm `--help` is no longer accepted (`q-status --help`
   fails).

## Implementation Plan

Each step is verified inside the dev shell (`nix develop -c …`).
`bash -n
bin/<script>` is the fast syntax gate; the harnesses are the behavioral
gate. `install-ios`/`release-ios` are driven by `bin/ios-scripts-tests` (via its
`run_install`/`run_release` wrappers, which forward argv verbatim), so the
`--launch`→`-l` / `--no-upload`→`-n` collapse must update that harness too;
`is-nix` is driven there as well (`run_is_nix`).

1. **Migrate `fatal()` in `bin/functions`.** Convert to `getopts ":s:"` with
   `local OPTIND OPTARG`; drop `--status-code`; keep the self-error path.
   - Verify: `bash -n bin/functions`; in the dev shell,
     `source bin/functions; (fatal -s 7 x); echo $?` prints `7`;
     `(fatal x); echo $?` prints `1`.

2. **Migrate the `:h`-only scripts** (both `fatal`- and `help`-sink groups,
   including `check-port`, `daemon-http-check`, `daemon-status`,
   `find-free-port`, and the `q-delete-job`/`q-inspect`/`q-retry` one-liners).
   Rewrite each loop to the leading-colon idiom and rewrite each `help()` to
   short-only.
   - Verify: `for f in <list>; do bash -n bin/$f; done`; spot-check
     `bin/check-pid -h` exits 0 and `bin/check-pid -x` exits non-zero;
     `bin/check-port -x` exits 2; `bin/find-free-port 1 2` exits 1.

3. **Migrate the flag-cluster scripts** (`is-nix`, `install-ios`, `release-ios`)
   with `--launch`→`-l`, `--no-upload`→`-n`. Collapse the `is-nix --quiet` guard
   call sites to `is-nix -q` in `bin/pre-commit`, `bin/build-ios`,
   `bin/install-ios`, `bin/release-ios`, and collapse the
   `run_install --launch …` / `run_release --no-upload …` call sites in
   `bin/ios-scripts-tests` to `-l` / `-n`.
   - Verify: `bash -n`; `bin/is-nix -qv`; `bin/install-ios -h`;
     `bin/release-ios -h`; `nix develop -c bin/ios-scripts-tests`.

4. **Migrate the single-value-option scripts** (`db-status`, `db-query`,
   `db-run`, `db-write`, `daemon-down`, `wait-for`, `file-lock`) with the
   stop-at-first-non-option + `shift $((OPTIND-1))` shape; route `\?`/`:` to the
   existing sink. Collapse the two live `bin/db-status --format <type>` call
   sites in `bin/db-scripts-tests` to `bin/db-status -f <type>`.
   - Verify: `bash -n`; `bin/wait-for -p` fails; `bin/file-lock -o` fails;
     `bin/db-status -x` fails.

5. **Migrate the permute scripts** (`bin/test`, `q-truncate`) with the
   `OPTIND=1`-per-round outer loop; `--yes-i-really-want-to-do-this`→`-y`.
   - Verify: `bash -n`; `bin/test bogus-module` fails; `bin/test -z` fails;
     `bin/q-truncate -h` exits 0.

6. **Migrate `q-enqueue`** (leading `-h` guard, `shift 2`, trailing
   `getopts ":m:d:"`; `--max-attempts`→`-m`, `--delay`→`-d`).
   - Verify: `bash -n bin/q-enqueue`; `bin/q-enqueue -h` exits 0.

7. **Migrate `db-drop`** (`:hy`, `-y`, no positionals); collapse the
   `db-drop --yes-i-really-want-to-do-this` call site in `bin/db-reset` to
   `db-drop -y`, and the four `db-drop --yes-i-really-want-to-do-this` call
   sites in `bin/db-scripts-tests` to `db-drop -y`.
   - Verify: `bash -n bin/db-drop bin/db-reset`; `bin/db-drop extra` fails;
     `nix develop -c bin/db-reset` still drops/recreates the test DB.

8. **Migrate the `-p` daemon scripts.** `daemon-up` (`:hp:`, leading-only,
   `--`/command passthrough preserved) and `daemon-check` (`:hp:`); collapse the
   relay call sites `--port`→`-p` in `daemon-status`, `rest-server-up`,
   `admin-server-up`, `public-web-up`.
   - Verify: `bash -n` on each; `bin/daemon-up -p` (missing value) fails;
     `bin/daemon-up -p 8080 svc -- sleep 1` parses the leading `-p` and reaches
     the `--`/command handling.

9. **Update `bin/scripts-tests`** with the option-parsing section, the
   `fatal`/`daemon-up`-passthrough/`find-free-port` assertions, and the
   `--port`→`-p` collapse across the collision section.
   - Verify: `nix develop -c bin/scripts-tests`.

10. **Update `bin/q-scripts-tests`** with the `-y`/`-m`/`-d` collapses and the
    new rejection/help assertions.
    - Verify: `nix develop -c bin/q-scripts-tests`.

11. **Full gate.** `nix develop -c bin/scripts-tests`,
    `nix develop -c bin/q-scripts-tests`,
    `nix develop -c bin/ios-scripts-tests`, and
    `nix develop -c bin/db-scripts-tests` (the standalone private-cluster
    harness, not reached by `bin/test`) all green; `bin/pre-commit` passes
    (`deno fmt --check` for this RFC markdown, `bin/test check`) — confirming
    the `is-nix -q` dev-shell guard still fires from inside the hook.

## Files Modified

`bin/functions` — `fatal()` → `getopts ":s:"`, `local OPTIND`, drop
`--status-code`.

Help-only `:h` migrations and `help()` short-only rewrite: `bin/admin-grant`,
`bin/admin-server-check`, `bin/admin-server-up`,
`bin/admin-server-wait-for-health`, `bin/build`, `bin/build-admin-server`,
`bin/build-common`, `bin/build-db`, `bin/build-net`, `bin/build-public-web`,
`bin/build-queue`, `bin/build-queue-worker`, `bin/build-rest-server`,
`bin/build-service`, `bin/build-ios`, `bin/check-pid`, `bin/check-port` (exit-2
sink), `bin/daemon-bounce`, `bin/daemon-http-check` (exit-3 sink),
`bin/daemon-status`, `bin/db-bootstrap`, `bin/db-create`, `bin/db-create-role`,
`bin/db-migrate`, `bin/db-repl`, `bin/db-reset`, `bin/deploy`,
`bin/dev-bootstrap`, `bin/find-free-port`, `bin/format`, `bin/ingest-colleges`,
`bin/postgres-check`, `bin/postgres-down`, `bin/postgres-up`,
`bin/postgres-wait-for-health`, `bin/pre-commit`, `bin/public-web-check`,
`bin/public-web-up`, `bin/public-web-wait-for-health`, `bin/queue-worker-check`,
`bin/queue-worker-up`, `bin/queue-worker-wait-for-health`,
`bin/rest-server-check`, `bin/rest-server-up`,
`bin/rest-server-wait-for-health`, `bin/test-fuzz`, `bin/q-delete-job`,
`bin/q-inspect`, `bin/q-retry`, `bin/q-status`.

Flag clusters: `bin/is-nix` (`:hqv`), `bin/install-ios` (`:hl`,
`--launch`→`-l`), `bin/release-ios` (`:hn`, `--no-upload`→`-n`).

`is-nix --quiet`→`-q` guard call sites: `bin/pre-commit`, `bin/build-ios`,
`bin/install-ios`, `bin/release-ios` (also migrated above; this is the
additional call-site edit).

Single value-option: `bin/db-status` (`:f:h`), `bin/db-query` (`:d:h`),
`bin/db-run` (`:d:h`), `bin/db-write` (`:d:h`), `bin/daemon-down` (`:ht:`),
`bin/wait-for` (`:hp:`), `bin/file-lock` (`:ho:t:`).

Daemon `-p` value-option: `bin/daemon-up` (`:hp:`, leading-only, `--`/command
passthrough), `bin/daemon-check` (`:hp:`).

`--port`→`-p` relay call sites (parser otherwise covered above):
`bin/daemon-status` (calls `daemon-check -p`), `bin/rest-server-up`,
`bin/admin-server-up`, `bin/public-web-up` (each calls `daemon-up -p`).

Permute: `bin/test` (`:ht:fvc`), `bin/q-truncate` (`:hy`, sentinel→`-y`).

Positionals-first: `bin/q-enqueue` (`:m:d:`, `--max-attempts`→`-m`,
`--delay`→`-d`).

No-positional confirmation: `bin/db-drop` (`:hy`, sentinel→`-y`).

Sentinel→`-y` call site: `bin/db-reset` (already migrated above for its own
`:h`; this additionally collapses its `db-drop --yes-i-really-want-to-do-this`
call to `db-drop -y`).

Tests: `bin/scripts-tests` (option-parsing/`fatal`/passthrough/`find-free-port`
assertions + `--port`→`-p` across the collision section + `is-nix -q` guard
coverage + the inline sentinel-bypass block collapsed to `-y` + a `db-reset`
drop assertion), `bin/q-scripts-tests` (`-y`/`-m`/`-d` collapse + rejection/help
assertions, incl. `q-delete-job`/`q-inspect`/`q-retry -x`),
`bin/ios-scripts-tests` (`run_install --launch`→`-l`,
`run_release --no-upload`→`-n` call sites), `bin/db-scripts-tests` (four
`db-drop --yes-i-really-want-to-do-this`→`db-drop -y` call sites + two live
`db-status --format <type>`→`db-status -f <type>` call sites; this standalone
private-cluster harness is not reached by `bin/test`, so it is run explicitly).

Not modified: `bin/test-tcp-listener` is Python with positional-only arguments
(`<port> [close|hold]`) and no option flags, so `getopts` does not apply.
