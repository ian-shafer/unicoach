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
`bin/test check` (Gradle tests + Postgres) concurrently — all dev-shell tools.
Commit from inside the shell, or wrap it:

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

## Running tests

Do **not** run `./gradlew test` directly — the DB-backed tests will fail with
`Could not resolve substitution to a value: ${POSTGRES_DB}`. Use the project's
test harness, which sources `.env.test`, starts Postgres, and re-inits/migrates
the test DB before invoking Gradle:

```sh
nix develop -c bin/test                       # full suite
nix develop -c bin/test rest-server           # one module
nix develop -c bin/test rest-server --tests "ed.unicoach.rest.AuthRoutingTest"
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

### `check` also runs the shell harnesses

`bin/test check` — the form the pre-commit hook runs, i.e. the gate on what
reaches `main` — additionally builds the `rest-server`/`queue-worker`
`installDist` artifacts and runs `bin/shell-tests`, the aggregator over
`bin/q-scripts-tests`, `bin/db-scripts-tests`, and `bin/scripts-tests` (RFC
115). That is ~254 shell assertions and roughly two extra minutes cold, most of
it `scripts-tests`. Bare `bin/test` runs none of them and prints one line saying
so, so the tight loop stays as fast as it was; the cost is that a `bin/` or
`infra/` edit can look green locally and fail at the hook. Run
`nix develop -c
bin/shell-tests` directly while iterating on those.

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
