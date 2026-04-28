# unicoach

A Kotlin/Ktor backend with a native PostgreSQL database and an iOS Swift client.

## Prerequisites

Install [nix](https://nixos.org/download) — it provides every project tool (Java 21,
PostgreSQL 18, Python 3, Deno, ktlint, git) as a reproducible package set.

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

Copy the environment template and initialise the database cluster. This is a
one-time step per machine.

```sh
cp .env.template .env      # fill in any local overrides (safe defaults are provided)
bin/db-init                # initialises the postgres cluster, role, database, and schema_migrations table
bin/db-migrate             # applies all pending SQL migrations
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
bin/postgres-up            # starts the postgres daemon (requires bin/db-init to have been run first)
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
bin/db-init                # idempotent: cluster init + role + database + schema_migrations
bin/db-migrate             # applies all pending SQL migrations in lexicographical order
bin/db-status              # shows applied / unapplied migrations
bin/db-repl                # opens a psql session against the application database
bin/db-destroy             # drops the application database (requires --yes-i-really-want-to-do-this)
```

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

Runs all Gradle tests against the test database (`unicoach-test`). Starts
postgres, destroys and re-creates the test database, applies migrations, then
executes the test suite.

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

| Tool | Config | Scope |
|---|---|---|
| ktlint | `.editorconfig` | `**/*.kt`, `**/*.kts` |
| deno fmt | `deno.json` | `**/*.md` |

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
var/postgres/         PostgreSQL data directory (gitignored)
```

## Environment Variables

Copy `.env.template` to `.env`. Key variables:

| Variable | Description | Default |
|---|---|---|
| `PORT` | REST server listen port | `8080` |
| `SERVER_PORT` | Derived from `PORT` (used by rest-server.conf) | `$PORT` |
| `POSTGRES_DATA_DIR` | PostgreSQL cluster directory | `$PROJECT_ROOT/var/postgres` |
| `POSTGRES_DB` | Application database name | `unicoach` |
| `POSTGRES_USER` | PostgreSQL superuser | `postgres` |
| `PGHOST` | libpq host (all psql/pg_isready calls) | `localhost` |
| `DATABASE_USER` | Application role | `unicoach` |
| `DATABASE_PASSWORD` | Application role password | `password` |
| `JWT_SECRET` | Token signing secret | — |
| `JWT_ISSUER` | Token issuer URI | — |

`.env.test` mirrors `.env` with test-specific overrides (`PORT=8081`,
`POSTGRES_DB=unicoach-test`). Both environments share the same postgres cluster;
isolation is at the database level.
