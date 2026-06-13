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
higher-numbered RFC that supersedes the earlier one**, carried by the code and
`SPEC.md`; the earlier RFC's file is left exactly as committed. Code + applied
migrations are the source of truth for current behaviour — when an RFC and the
code disagree, the code wins. See [`rfc/README.md`](rfc/README.md) for the full
convention.

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
