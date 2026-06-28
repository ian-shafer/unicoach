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
hook, which runs `deno fmt --check` (Markdown) and `bin/test check` (Kotlin +
Postgres) — all dev-shell tools. Commit from inside the shell, or wrap it:

```sh
nix develop -c git commit ...
```

A bare `git commit` is refused early by the hook with a message telling you to
use the dev shell — the commit is blocked (your changes are not lost), so re-run
it via `nix develop -c git commit` rather than reaching for `--no-verify`.

**Carve-out — rfc-pipeline WIP checkpoints skip the hook.** The one place
`--no-verify` is sanctioned is the `/rfc-pipeline` orchestrator's throwaway
checkpoint commits, made via
`.claude/skills/rfc-pipeline/scripts/rfc-pipeline-checkpoint`. Those commits
exist only as restore points and are squashed away before anything lands, so
running the full gate on each is pure waste; the script encapsulates the
`--no-verify` (keeping the flag out of the orchestrator's Bash command). The
pipeline's two **final** commits — the ones the Architect actually lands — go
through `nix develop -c git commit` and the full hook like any other commit. Do
not reach for `--no-verify` anywhere else.

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

`bin/test` recreates the **local test database** on every run
(`db-reset (drop →
create → migrate)`) — expected, non-destructive to anything
but the test DB.

## RFCs

Feature work is designed in numbered RFCs under `rfc/` (`NN-title.md`). The
binding convention: **a committed/implemented RFC is immutable — NEVER edit
it.** The design still evolves, but a changed decision lands in a **new,
higher-numbered RFC** that carries the change into the code, `SPEC.md`, and (if
a durable guarantee changes) `INVARIANTS.md`; the earlier RFC's file is left
exactly as committed. **An RFC's prose references the `SPEC.md` and code — never
another RFC**, even when changing an earlier decision. Code + applied migrations
are the source of truth for current behaviour — when an RFC and the code
disagree, the code wins. See [`rfc/README.md`](rfc/README.md) for the full
convention.

## Specs and invariants

A documented directory carries up to **two sibling Markdown files** with
strictly separated mandates. They exist for LLM consumption — a context-window
substitute for reading the directory:

- **`SPEC.md` — descriptive.** States _what the code does_ (present tense), so
  an LLM gets context without reading every file. It carries **no**
  `MUST`/`NEVER` language and no "Invariants" section. It is **fully LLM-managed
  with no human gate**, and is expected to change whenever the code changes — a
  convenience layer, not a contract. Authored/synced by the `spec-writer` /
  `spec-sync-loop` skills.
- **`INVARIANTS.md` — prescriptive.** Records the **few** durable guarantees
  that must remain true as the code evolves (typically 0–5; **often none, in
  which case the file is simply absent**), each as a **Rule** plus a **Why**. A
  true invariant is prescriptive, not type-enforced, breaks something real if
  violated, and is specific to this directory — most "musts" do **not** qualify.
  It is **human-gated** (kept small and reviewed). Authored by the
  `invariants-writer` skill.

Treat both as point-in-time descriptions: the **code + applied migrations remain
the source of truth**. When a file and the code disagree, the code wins.

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

## Product

The canonical product positioning is in [`PRODUCT.md`](PRODUCT.md). All
public-facing and brand copy — the home page, taglines, and any other
user-visible surface — must reflect it.
