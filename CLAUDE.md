# unicoach

## Development environment: Nix flakes

This project's entire toolchain is provided by a **Nix flake** (`flake.nix`) —
the JVM, Postgres, and all CLIs live in the dev shell, **not** on the bare
system PATH. There is **no `.envrc`/direnv**, so the shell is **not**
auto-activated.

**Always run toolchain commands inside the dev shell**, either by entering it
once:

```sh
nix develop
```

or by wrapping a single command (preferred for scripted/one-off runs):

```sh
nix develop -c ./gradlew test
nix develop -c ./gradlew :service:compileKotlin
nix develop -c ktlint --version
nix develop -c psql ...
```

Running `./gradlew` (or `java`, `psql`, `ktlint`, `deno`, `python3`) directly
will fail with errors like **"Unable to locate a Java Runtime"** — that means
the dev shell was not active, not that the tool is missing. Re-run via
`nix develop -c`.

**Committing also runs the toolchain.** `git commit` fires the `bin/pre-commit`
hook, which runs `bin/format -c` (the single ktlint + `deno fmt` lint gate) and
`bin/check` (Gradle tests + Postgres) concurrently — all dev-shell tools. Commit
from inside the shell, or wrap it:

```sh
nix develop -c git commit ...
```

A bare `git commit` is refused early by the hook with a message telling you to
use the dev shell — the commit is blocked (your changes are not lost), so re-run
it via `nix develop -c git commit`.

**The gate protects `main`, not every commit.** The full hook is **required on
whatever lands on `main`** — a direct commit to `main`, or the tip of a branch
about to be fast-forwarded in. Commits that are only ever intermediate — WIP on
a work branch, one step of an operator-driven fix loop — may skip it with
`--no-verify`. A Gradle run spent on a commit that will be squashed away, or
that nobody will ever ship from, buys nothing.

Note the phrasing: it is what **reaches** `main` that must be verified, not
literally a commit whose parent is `main`. A fast-forward makes branch commits
part of `main`'s history without any of them being a "commit to `main`", so the
branch tip is what has to have passed.

**One narrow exemption, at land time (RFC 193).** `ship-land` does not re-run
the gate when the base advanced ONLY by commits that touch nothing but
`product/**`; it replays the run onto them and lands. So the landed tree is this
run's hook-verified code plus those docs, and that combined tree never went
through the hook as a whole. The claim is deliberately narrower than "it passed
the hook": the landed tree differs from the hook-verified tree only by paths
under `product/`, which are not Gradle inputs and cannot change a test result,
and `bin/format -c` — the only Markdown gate — has already passed on both halves
independently. Every other advance still costs a rebase and a full re-run, and
`ship-land` records which of the two it did.

**Skipping the hook moves the responsibility; it does not remove it.** The agent
that changed the code is responsible for testing it — run
`nix develop -c bin/test <module> -f` and report the real executed counts — and
an orchestrator committing someone else's work records what that agent reported.
A branch arriving at `main` with the gate never having run is the failure this
rule exists to prevent.

**`rfc-pipeline` is an instance of this rule, not an exception to it.** Its
throwaway checkpoints commit via
`.claude/skills/rfc-pipeline/scripts/rfc-pipeline-checkpoint`, which
encapsulates the `--no-verify` (keeping the flag out of the orchestrator's Bash
command); they are squashed away before anything lands. Of its two **final**
commits, the **code** commit goes through `nix develop -c git commit` and the
full hook — that run is the branch's gate, and the same run's `deno fmt --check`
covers the whole working tree including the RFC markdown — so the **RFC doc**
commit that follows lands with `--no-verify` against an already-validated tree.

**A `product/**`-only commit skips the test half automatically** (RFC 194). When
**every staged path** is under `product/**` — the `/chart` layer's briefs,
specs, research reports and `STATUS.md` — the hook runs `bin/format -c` and
**not** `bin/check`. Anything else runs the full gate: one code path in the set,
a mixed set, an empty index, or a rename that crosses the `product/**` boundary.
The hook logs which scope it chose, by name, so the two kinds of commit are
distinguishable afterwards. You do not ask for this and cannot ask for it; it is
not a flag.

**And the hook now runs only the harnesses a diff can break** (RFC 214).
`bin/gate-scope` classifies the staged set as a whole and `bin/pre-commit` obeys
it: a Markdown-only commit outside a module `src/` tree runs `bin/format -c`
alone; a `.prime/**` script commit runs the three harnesses that observe
`.prime/`; an `infra/`-only commit runs `bin/format -c` alone; a commit entirely
inside Gradle modules' `src/` trees runs Gradle plus `bin/scripts-tests`.
Anything unrecognised, anything mixed, an empty index, and a rename crossing a
scope boundary all run **everything**. A new `bin/*scripts-tests` the map does
not name **fails the gate by name** rather than silently never running.

That map is a correctness claim, not a config table, and it lives in ONE script
with its own harness — never a second copy, because a wrong map that something
downstream catches fails silently and a wrong map in the hook does not.

**Every passing commit records a `ship-verified` marker naming its scope.** A
narrow pass means "green if the parent was", so its marker carries the parent
tree, and `ship-land` accepts it only on a commit whose parent is that tree and
is already on `main`. That makes a `.prime/` script or `bin/` change land by the
ordinary path, through the gate that change actually needs.

**`infra/` is now ungated** (RFC 214). The `tofu fmt -check` / `validate` pair
left `bin/scripts-tests` on an explicit decision — a commit gate should not
reach the public internet — and lives in `bin/infra-check`. Nothing runs it for
you: `nix develop -c bin/infra-check` before you commit an `infra/` change.

Note that this is a **different claim** from the `--no-verify` RFC-doc commit
above, and the difference is the point. That one is safe because an earlier run
**already checked** that content. This one is safe because **`product/**` is not
a build input at all** — no Gradle source set, resource, migration or script
reads those files — so the test half cannot change its answer for such a commit,
now or ever. It is an exemption from running the check, not from having passed
it. `bin/format -c` still runs, because Markdown IS checked and that gate is the
only thing that checks it. Its marker records the narrow scope it ran, like any
other narrow commit.

**Nothing is developed on `main`, and nothing is committed in the root
checkout** (RFC 201). `bin/pre-commit` refuses both, before it starts any gate,
and the refusal prints the command that moves your work. `main` only ever
fast-forwards.

Two sessions writing in one shared checkout collide in two ways. The hook
validates the whole working TREE rather than the commit, so one session's
unformatted file fails another session's commit — after that commit may already
have spent minutes running tests. And `git add <path>` is silent about whose
path it is, so a commit can carry changes the committing run does not own. One
worktree per writer removes both: another session's uncommitted work is not in
your tree, so no gate of yours can trip on it and no `git add` of yours can name
it.

So every writer claims a worktree:

    .prime/agent/skills/ship/scripts/ship-claim -l rfc|quick     # code
    .prime/agent/skills/chart/scripts/docs-claim -s <slug>       # product/** docs

and each lands by fast-forward — `ship-land` for code, `docs-land` for docs.
`docs-land` asserts that the branch's net diff touches nothing outside
`product/`; a change to anything else, `.prime/agent/skills/**` included, goes
through /ship and the full gate.

Two honest limits. `git commit --no-verify` walks past the guard exactly as it
walks past every other check here — the old path is impossible by DEFAULT, not
impossible. And the guard stops you COMMITTING in the root checkout, not WRITING
there: a file left dirty in it now blocks `ship-land`'s `git merge --ff-only`
instead of being committed by mistake, which is a better failure but still one
somebody has to clear. `docs-claim` names any dirty `product/` path it finds
there and leaves it alone, because it may be another session's.

## Running tests

Do **not** run `./gradlew test` directly — the DB-backed tests will fail with
`Could not resolve substitution to a value: ${POSTGRES_DB}`. Use the project's
test harness, which sources `.env.test`, starts Postgres, and re-inits/migrates
the test DB before invoking Gradle:

```sh
nix develop -c bin/test                       # full suite
nix develop -c bin/test rest-server           # one module
nix develop -c bin/test rest-server -t "ed.unicoach.rest.AuthRoutingTest"
```

**Default to the full suite. Do not scope by module, and do not pass `-f`.**

Naming a module is a guess at the blast radius of a change, and the guess is
routinely wrong — a `:db` edit breaks a `:rest-server` test and the scoped run
reports green. Gradle already knows the real answer from the dependency graph
and its input hashes, so `bin/test` with no argument executes exactly the tasks
the change actually invalidated and no-ops the rest. **Let the cache do the
scoping.** The executed count is the evidence of what ran.

Passing `-f` forces everything to re-run and so throws that incrementality away.
Reach for it only when an input Gradle does not model may have changed — a
schema migration or `.env` value with no accompanying Kotlin change — or when
hunting a flake. Both are exceptions; neither is the default.

The narrower forms above stay available for tight iteration while debugging one
test. They are not what you verify a change with.

`bin/test` recreates the **local test database** on every run
(`db-reset (drop →
create → migrate)`) — expected, non-destructive to anything
but the test DB.

### `bin/check` runs the shell harnesses

`bin/check` — what the pre-commit hook runs, i.e. the gate on what reaches
`main` — builds the `rest-server`/`queue-worker` `installDist` artifacts, runs
`bin/shell-tests` (the aggregator over the shell script-test harnesses, RFC
115), and then runs `bin/test`. **Do not look for the list of harnesses here.**
`bin/shell-tests -h` is the one place that knows; this file used to name four of
them and "~280 shell assertions" and both went stale without anyone noticing,
which is the whole reason RFC 214's map derives the set from the filesystem
instead of from prose. They are a large share of the hook's cost.

`bin/test` runs none of them, so the tight loop stays fast; the cost is that a
`bin/` edit can look green locally and fail at the hook. Run
`nix develop -c bin/shell-tests` directly while iterating on those, and
`bin/shell-tests -n <name>` to run just one.

Be aware that `scripts-tests` **stops this checkout's `rest-server` and
`queue-worker` daemons** on exit. Daemon identity is per-checkout (`var/run/`
under `PROJECT_ROOT`), so no other worktree is affected — but if you commit from
a checkout where your own dev stack is running, the hook will stop those two
daemons. Bring them back with `bin/rest-server-up` / `bin/queue-worker-up`.

## RFCs

Feature work is designed in numbered RFCs under `rfc/` (`NN-title.md`). The
binding convention (codified in `rfc/INVARIANTS.md`): **a committed RFC is
immutable except for cosmetic updates (e.g. formatting).** The design still
evolves, but a changed decision lands in a **new, higher-numbered RFC** that
carries the change into the code and (if a durable guarantee changes)
`INVARIANTS.md`; the earlier RFC's file is left as committed. RFCs drive change
but are not the source of truth — the files in the codebase are; when an RFC and
the code disagree, the code wins. See [`rfc/README.md`](rfc/README.md) for the
full convention.

**Don't get anchored on RFC prose.** An RFC's wording never outranks sound
engineering; while it's still uncommitted, rewrite the prose to fit the right
implementation, not the reverse. Decide on the merits, then make the prose say
that.

## Invariants

**Retired 2026-08-06 (Ian's call).** Directories used to also carry a
durable-guarantee **`INVARIANTS.md`** — 0–5 Rule + Why statements, declared in
an RFC's `## Invariants` section (human-reviewed at RFC approval) and landed by
`/rfc-impl` alongside the code. The mechanism added ceremony that mostly
confused the LLM rather than preventing real defects. No RFC declares one going
forward, and `/rfc-impl` no longer lands them. Existing `INVARIANTS.md` files
are left in place as historical documentation — unmaintained, unverified, at
most a hint — read the code instead. `rfc/INVARIANTS.md` is the one exception:
it isn't a code guarantee, it's the RFC-immutability rule itself, cited under
**RFCs** above.

Historical note: directories used to also carry a descriptive, LLM-managed
`SPEC.md`. Those were removed — read the code instead. Older RFCs may reference
`SPEC.md` files; that is no different from an RFC referencing a code file that
was subsequently deleted.

The repo-root [`ASYNC_WORK.md`](ASYNC_WORK.md) predates this retirement and
stands on its own: it records the few cross-cutting rules for async-capable work
(queue-by-default, and a required enqueue as part of the request transaction)
that govern every request-handling surface rather than one directory (RFC 96).

## Transient pipeline output

Transient output from the RFC pipeline — RFC backup snapshots and the
`implementation_diff.md` walkthrough artifact — is written under `.scratch/`,
which is **gitignored**. Never commit `.scratch/`; it is safe to delete at any
time.

The dev shell (`flake.nix`) provides:

- **Temurin JDK 21** (`temurin-bin-21`) — the JVM for Gradle/Kotlin builds
- **PostgreSQL 18** — `psql`, `pg_isready`, `initdb`, server binaries
- **Python 3** — for schemathesis
- **Deno** — Markdown formatting (replaces `npx prettier`)
- **ktlint** — Kotlin lint/format
- **git** — self-consistent git on PATH

## `bin/` conventions

Two rules govern every script in `bin/`. Both are invariants, not preferences —
they were reaffirmed after a run added the only violations in the tree.

**1. Option parsing is `getopts`, with short options only.**

No hand-rolled `while`/`case` argument loops, and **no long options**. `getopts`
is the arbiter of the grammar; `bin/functions` supplies the reserved usage-error
exit codes (`EXIT_UNKNOWN_OPTION` 10, `EXIT_OPTION_REQUIRES_VALUE` 11,
`EXIT_UNEXPECTED_ARG` 20, `EXIT_MISSING_REQUIRED_ARG` 21,
`EXIT_INVALID_ARG_VALUE` 22), so a caller that invoked a script wrongly is
distinguishable from a run that faulted.

A python script in `bin/` has no `getopts`, so it uses the stdlib **`getopt`**
module — the same grammar in the language actually in use. `getopt` gives you
the grammar only: value-shape checks (empty value, a value that begins with `-`,
a repeated flag, a path that must be a directory) still belong after it.

The rule exists because a hand-rolled parser silently accepts what `getopts`
would reject, and every such parser disagrees with the others in its own way.

**2. Logging ALWAYS goes to stderr.**

Every diagnostic — `log-info`, `log-warning`, `log-error`, `fatal`, progress
output, and the final human-readable summary — is written to **stderr**.
`bin/functions` already defines the shell half this way; a python script in
`bin/` mirrors it with its own `log_info`/`log_warning`/`log_error`/`fatal`.

stdout carries only what the caller **explicitly asked for**: machine-consumable
data, or the help text when `-h` was passed. Everything else — progress, status,
the closing summary — is a diagnostic and belongs on stderr. A script whose
product is files on disk therefore writes _nothing at all_ to stdout on a normal
run, and that emptiness is worth asserting in a test — it is exactly what
regresses when someone "helpfully" moves a summary line back.

## Schema conventions

- **Own enumerations** are `TEXT` + `CHECK IN (...)` in the schema plus exactly
  one Kotlin `enum class Foo(val value: String)` with a `fromValue` companion in
  `db/src/main/kotlin/ed/unicoach/db/models`
  (`CollegeListEntryStatus`/`IncomeBand` precedent) — never invented integer
  codes.
- **Source-defined codes** (federal/IPEDS/CDS values) are stored raw as the
  source publishes them; human-readable labels appear only at the tool/API
  boundary.
- **Derived figures** (shares, percentages, anything computable from stored
  columns) are computed at read time and labeled as derived — never stored.

## Product

The canonical product positioning is in [`PRODUCT.md`](PRODUCT.md). All
public-facing and brand copy — the home page, taglines, and any other
user-visible surface — must reflect it.
