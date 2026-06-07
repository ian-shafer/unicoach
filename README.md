# unicoach

A Kotlin/Ktor backend with a native PostgreSQL database and an iOS Swift client.

## Prerequisites

Install [nix](https://nixos.org/download) — it provides every project tool (Java
21, PostgreSQL 18, Python 3, Deno, ktlint, git) as a reproducible package set.

```sh
bin/dev-bootstrap   # verifies nix is installed and prints activation instructions
```

## Dev Environment

Activate the project shell once per terminal session. All tools are placed on
`PATH` automatically.

```sh
nix develop --command zsh
```

The shell hook prints the active tool versions on entry:

```
🛠  unicoach dev environment active
   java:     openjdk version "21..."
   postgres: psql (PostgreSQL) 18.x
   python:   Python 3.x
   deno:     deno 2.x
   ktlint:   ktlint version 1.x
```

> All `bin/` commands below assume an active `nix develop` shell.

## Initial Setup

Copy the environment template and bootstrap the shared PostgreSQL cluster.
`bin/db-bootstrap` is a **one-time, per-machine** step: it initialises the
cluster (at `POSTGRES_DATA_DIR`, shared by every worktree) and creates the
application role. Database creation and migration are cheap, repeatable steps.

```sh
cp .env.template .env      # fill in any local overrides (safe defaults are provided)
bin/db-bootstrap           # ONCE per machine: initialise the cluster + application role
bin/db-create              # create the application database + schema_migrations table
bin/db-migrate             # apply all pending SQL migrations
```

## Building

The build system is a two-layer hierarchy. Per-module scripts invoke a single
Gradle task; `bin/build` orchestrates them in dependency order.

```sh
bin/build                  # full build: common → db → service → queue → net → rest-server → queue-worker
```

Individual modules:

```sh
bin/build-common
bin/build-db
bin/build-service
bin/build-queue
bin/build-net
bin/build-rest-server      # produces rest-server/build/install/rest-server/bin/rest-server
bin/build-queue-worker     # produces queue-worker/build/install/queue-worker/bin/queue-worker
```

`bin/build` exits immediately with the failing module's exit code if any step
fails.

## Running Services

Each service has a matching `up`, `down`, `bounce`, and `check` script.

### PostgreSQL

```sh
bin/postgres-up            # starts the postgres daemon (requires bin/db-bootstrap to have been run first)
bin/postgres-down          # gracefully stops postgres
bin/postgres-bounce        # restarts postgres
bin/postgres-check         # exits 0 if postgres is accepting connections, 1 otherwise
```

### REST Server

Requires postgres to be running and the app binary to have been built
(`bin/build-rest-server`).

```sh
bin/rest-server-up         # starts the REST API on PORT (default 8080)
bin/rest-server-down       # stops the REST server
bin/rest-server-bounce     # restarts the REST server
bin/rest-server-check      # exits 0 if /hello responds, 1 otherwise
```

### Queue Worker

Requires postgres to be running and the binary to have been built
(`bin/build-queue-worker`).

```sh
bin/queue-worker-up        # starts the background job processor
bin/queue-worker-down      # stops the queue worker
bin/queue-worker-bounce    # restarts the queue worker
bin/queue-worker-check     # exits 0 if the worker process is alive, 1 otherwise
```

### Status overview

```sh
bin/daemon-status          # prints running/stopped status for all known services
```

Logs are written to `var/log/<service>.log`. PIDs are tracked in
`var/run/<service>.pid`.

## Database

```sh
bin/db-bootstrap           # ONCE per machine: idempotent cluster init + application role
bin/db-create              # idempotent: create the database, grants, and schema_migrations
bin/db-migrate             # applies all pending SQL migrations in lexicographical order
bin/db-reset               # drop + create + migrate: a clean, fully-migrated database
bin/db-status              # shows applied / unapplied migrations
bin/db-repl                # opens a psql session against the application database
bin/db-drop                # drops the application database (requires --yes-i-really-want-to-do-this)
```

`bin/db-bootstrap` touches the shared cluster and is rare; `bin/db-drop`,
`bin/db-create`, and `bin/db-reset` operate on a single database and are cheap
to run often (e.g. `bin/test` runs `db-reset` every invocation).

Migration files live in `db/schema/` and must follow the naming convention
`NNNN.<slug>.sql` (e.g. `0001.create-users.sql`).

### Queue utilities

```sh
bin/q-status               # shows job queue counts by state
bin/q-inspect              # inspects a specific job by ID
bin/q-enqueue              # manually enqueues a job
bin/q-retry                # re-queues a failed job
bin/q-delete-job           # removes a specific job
bin/q-truncate             # clears all jobs (requires --yes-i-really-want-to-do-this)
```

## Testing

### JVM unit tests

Runs all Gradle tests against this worktree's own test database (named
`unicoach-test-<worktree-dir>`, derived in `.env.test`). Assumes
`bin/db-bootstrap` has already been run once; it then `db-reset`s the test
database (drop + create + migrate) and executes the suite. Because each worktree
has its own test database on the shared cluster, `bin/test` can run concurrently
across worktrees without collision.

```sh
bin/test                   # equivalent to ./gradlew test
bin/test :rest-server:test # run tests for a specific module
```

### Script / integration tests

```sh
bin/scripts-tests          # daemon lifecycle tests (daemon-up/down/bounce/check, file-lock, SIGKILL escalation)
bin/db-scripts-tests       # database migration and schema scripts
bin/db-users-tests         # database role and privilege assertions
bin/q-scripts-tests        # queue operation scripts
```

### Fuzz testing

```sh
bin/test-fuzz              # runs schemathesis against the live REST API (requires rest-server-up)
```

## Code Quality

```sh
bin/format                 # runs ktlint --format (Kotlin) and deno fmt (Markdown) concurrently
bin/pre-commit             # lint check only (no modifications); used as the git pre-commit hook
```

Format configuration:

| Tool     | Config          | Scope                 |
| -------- | --------------- | --------------------- |
| ktlint   | `.editorconfig` | `**/*.kt`, `**/*.kts` |
| deno fmt | `deno.json`     | `**/*.md`             |

## Project Structure

```
bin/                  lifecycle and tooling scripts
common/               shared Kotlin library (DTOs, utilities)
db/                   SQL migrations (db/schema/) and database utilities
service/              core domain logic
queue/                job queue abstractions
net/                  HTTP client utilities
rest-server/          Ktor HTTP API server
queue-worker/         background job processor
ios-app/              Swift/SwiftUI iOS client
specs/                feature design documents
var/log/              service log files (gitignored)
var/run/              PID and lock files (gitignored)
```

The PostgreSQL data directory lives **outside** any checkout at
`$HOME/var/unicoach/postgres` (`POSTGRES_DATA_DIR`), so a single cluster is
shared by every worktree.

## Environment Variables

Copy `.env.template` to `.env`. Key variables:

| Variable            | Description                                            | Default                       |
| ------------------- | ------------------------------------------------------ | ----------------------------- |
| `PORT`              | REST server listen port                                | `8080`                        |
| `SERVER_PORT`       | Derived from `PORT` (used by rest-server.conf)         | `$PORT`                       |
| `POSTGRES_DATA_DIR` | PostgreSQL cluster directory (shared by all worktrees) | `$HOME/var/unicoach/postgres` |
| `POSTGRES_PORT`     | PostgreSQL listen port (required; no in-code default)  | `5432`                        |
| `POSTGRES_DB`       | Application database name                              | `unicoach`                    |
| `POSTGRES_USER`     | PostgreSQL superuser                                   | `postgres`                    |
| `PGHOST`            | libpq host (all psql/pg_isready calls)                 | `localhost`                   |
| `DATABASE_USER`     | Application role                                       | `unicoach`                    |
| `DATABASE_PASSWORD` | Application role password                              | `password`                    |
| `JWT_SECRET`        | Token signing secret                                   | —                             |
| `JWT_ISSUER`        | Token issuer URI                                       | —                             |

`POSTGRES_PORT` is **required** and must be set in the env file — scripts and
the JVM crash hard if it is missing rather than silently defaulting.

`.env.test` mirrors `.env` with test-specific overrides (`PORT=8081`). Its
`POSTGRES_DB` is derived **per worktree** (`unicoach-test-<worktree-dir>`) so
parallel test runs never collide. Dev and test (and every worktree) share the
same postgres cluster; isolation is at the database level.

## Parallel development with worktrees

Run several features — or several `/rfc-pipeline` instances — at once, each in
its own [git worktree](https://git-scm.com/docs/git-worktree). All worktrees
share one PostgreSQL cluster (`POSTGRES_DATA_DIR` is an absolute,
checkout-independent path), and isolation happens at the database level.

```sh
git worktree add -b my-feature ../unicoach-my-feature main
cd ../unicoach-my-feature
nix develop -c bin/test        # uses its own DB: unicoach-test-unicoach-my-feature
```

- **`bin/db-bootstrap` is run once per machine**, not per worktree — the cluster
  and role are shared.
- Each worktree's `bin/test` resets only its own per-worktree test database, so
  test runs are safe to execute concurrently across worktrees.
- **Caution:** `bin/postgres-down` stops the shared cluster for _every_
  worktree.
- To give a worktree a fully isolated cluster instead, override both
  `POSTGRES_DATA_DIR` and `POSTGRES_PORT` in that worktree's `.env`/`.env.test`.
