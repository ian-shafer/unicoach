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
bin/rest-server-check      # exits 0 if /healthz responds, 1 otherwise
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
bin/test                   # full suite (every module)
bin/test rest-server       # run tests for a specific module
bin/test rest-server --tests "ed.unicoach.rest.AuthRoutingTest" # filter within one module
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

## Deployment

The backend deploys as code (OpenTofu under `infra/`) to one or more **isolated
AWS environments**, each selected by an explicit `UNICOACH_ENV` argument
(`prod`, a future `staging`, …). Every environment is the same topology — an ALB
terminating TLS in front of one EC2 instance running both services under
`systemd`, against an RDS PostgreSQL database — but with disjoint identity: its
resources are named `unicoach-<env>`, its config/secrets live under
`/unicoach/<env>/*` in SSM, and its Terraform state is a per-env key + on-disk
`infra/.terraform-<env>` working dir (no Terraform workspaces). All resource
identity derives from two tokens — `ENVIRONMENT` and the base domain — so a new
environment is stood up by authoring one `.env.<env>` file (see
[Add an environment](#add-an-environment)). There is no CI; deployment is
operator-invoked. All commands below assume an active `nix develop` shell and a
configured AWS session (`aws configure` / SSO), and take the environment as the
first argument.

### 0. Domain gate (prerequisite)

Each environment serves its API at `api.<app_domain>` and writes records into a
Route53 zone identified by `hosted_zone_name`:

- **Apex env** (e.g. `prod`): `app_domain` _is_ its own zone (`uni.coach`), so
  `.env.prod` omits `HOSTED_ZONE_NAME` and infra coalesces it to `app_domain`.
- **Subdomain-per-env** (e.g. `staging`): `app_domain = staging.uni.coach` and
  `.env.staging` sets `HOSTED_ZONE_NAME=uni.coach` (the parent zone). All envs
  share that one zone, writing distinct `api.<env>.uni.coach` records — no
  collision, no new registration.

The zone must already be registered through Route53 Domains (its hosted zone is
auto-created). OpenTofu references it by data source; if registration is
incomplete, `bin/infra-apply <env>` fails closed at the TLS/DNS resources with
no partial state.

### 1. Provision infrastructure

```sh
bin/infra-bootstrap init                # ONCE: local-state config for the backend bucket
bin/infra-bootstrap apply               # creates the S3 remote-state bucket
bin/infra-init prod                     # wires the S3 backend with the per-env state key
bin/infra-plan prod                     # review the plan
bin/infra-apply prod                    # create the VPC, RDS, EC2, ALB, ACM cert, DNS, SSM params
```

`bin/infra-init`, `bin/infra-plan`, `bin/infra-apply`, and `bin/infra-output`
each take the environment as the first argument; they select `.env.<env>`,
export the `TF_VAR_*` inputs, set `TF_DATA_DIR=infra/.terraform-<env>`, and
(init, plan, apply) supply the partial backend's per-env state key. They are
thin wrappers over `tofu -chdir=infra …` carrying no resource logic
(`bin/infra-bootstrap` targets `infra/bootstrap` for the one-time state bucket).

### 2. Seed out-of-band secrets

Two SecureString parameters per environment are created with a placeholder
(`PLACEHOLDER_SEED_OUT_OF_BAND`) and `ignore_changes` on their value, so
OpenTofu never reverts an operator-seeded secret. `render-env.sh` refuses to
render the host env while a placeholder remains, so seed the real values once
with the AWS CLI before deploying:

```sh
aws ssm put-parameter --overwrite --type SecureString \
  --name /unicoach/prod/DATABASE_PASSWORD       --value '<app-role-password>'
aws ssm put-parameter --overwrite --type SecureString \
  --name /unicoach/prod/CHAT_ANTHROPIC_API_KEY  --value '<anthropic-api-key>'
```

The RDS master password (`PGPASSWORD`) is generated by OpenTofu and written
straight to SSM — no human handles it. `EMAIL_PROVIDER=ses` additionally
requires a **verified SES sender identity** (an out-of-band AWS prerequisite,
like the Route53 zone).

### 3. Deploy the application

```sh
bin/deploy prod
```

`bin/deploy <env>` builds both `installDist` distributions (`bin/build`),
bundles them with `db/schema/` and the migration scripts, uploads the bundle to
the env's artifacts bucket, and issues an SSM Run Command. On the instance the
deploy step refreshes `/etc/unicoach/env` from `/unicoach/<env>/*` SSM, runs
`db-create-role → db-create → db-migrate` against RDS, then repoints
`/opt/unicoach/current` and restarts both units. A failed migration leaves the
previous release serving (the symlink swaps only after migrations succeed); a
single-instance restart implies brief downtime per deploy, accepted at this
scale.

### Add an environment

A second cloud environment needs no code change — only a new `.env.<env>` file:

1. Author `.env.<env>` (`ENVIRONMENT=<env>`, `APP_DOMAIN`, `GOOGLE_CLIENT_IDS`,
   and — for a subdomain env — `HOSTED_ZONE_NAME` = the parent zone).
2. `bin/infra-apply <env>` — stands up the full env (`0 to destroy`).
3. Seed the operator secrets into `/unicoach/<env>/*`
   (`aws ssm put-parameter … --type SecureString`).
4. `bin/db-create-role`.
5. `bin/deploy <env>`.

The iOS app targets local and prod only; it does not consume the cloud-env
selector.

### Administration

There is no SSH; all access flows through SSM Session Manager and Run Command:

```sh
aws ssm start-session --target "$(bin/infra-output prod -raw instance_id)"
# on the instance: journalctl -u unicoach-rest-server -f
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

`POSTGRES_PORT` is **required** and must be set in the env file — scripts and
the JVM crash hard if it is missing rather than silently defaulting. The same is
now true of `SERVER_PORT` and `APP_DOMAIN` for the JVM: `rest-server.conf` pulls
both from the env with a **required** `${VAR}` (no HOCON default), so they live
in the dotenv layer alone.

`.env` is the committed **base**; `.env.test` and `.env.fuzz` are **slimmed
deltas layered _after_ it** — each restates only the keys whose values differ
from `.env` (e.g. `PORT`, the per-worktree `POSTGRES_DB`, `DATABASE_USER`,
`GOOGLE_AUTH_PROVIDER=stub`), inheriting everything else (the Postgres-cluster
block, `DATABASE_PASSWORD`, `APP_DOMAIN`) from the base. `bin/common` sources
`.env` first, then the selected delta. `POSTGRES_DB` is derived **per worktree**
(`unicoach-test-<worktree-dir>`) so parallel test runs never collide. Dev and
test (and every worktree) share the same postgres cluster; isolation is at the
database level.

## Configuration model & environments

There is one documented answer to "where does config X come from in environment
Y." Every value is declared **once**, in the lowest layer all its consumers
read.

**Read-channels** — who reads which layer:

| Consumer  | Reads config from                                                      |
| --------- | ---------------------------------------------------------------------- |
| JVM       | HOCON (`.conf` default → process-env `${?VAR}` → `local.conf` overlay) |
| Shell     | `.env*` (sourced)                                                      |
| iOS       | `.env*` (sourced at build → baked into `Info.plist`)                   |
| Terraform | `TF_VAR_*`, exported by `bin/infra-*` from `.env.<env>`                |

Only the JVM reads HOCON; Shell, iOS, and Terraform all read the dotenv layer.

**Placement rule** — a value lives in the lowest layer all its consumers read:

| Consumers                    | Home                                | HOCON treatment                               |
| ---------------------------- | ----------------------------------- | --------------------------------------------- |
| JVM only                     | HOCON `.conf`                       | `key = devDefault` then `key = ${?VAR}`       |
| Shell / iOS / Terraform only | `.env` (dev), `.env.<env>` (cloud)  | absent                                        |
| JVM + any dotenv consumer    | `.env` / `.env.<env>` (single home) | `key = ${VAR}` — **required, no dev default** |

So `APP_DOMAIN` and `SERVER_PORT` (JVM + dotenv consumers) live only in the
dotenv layer; HOCON pulls them with a required `${VAR}`.

**Invariants** (the durable guarantees this rests on — see `INVARIANTS.md` under
`common/.../config` and `infra/`):

1. **Committed runtime config is development-only** — the `.conf` defaults and
   `.env` hold working dev values; non-dev environments override solely through
   the process environment (`${?VAR}`), materialized from SSM on cloud hosts.
2. **Secrets are never committed** — they live only in `/unicoach/<env>` SSM
   SecureStrings (cloud) or `~/.config/unicoach/local.conf` (local).
3. **The JVM reads env vars only through HOCON** — application code never calls
   `System.getenv`; config enters via `${?VAR}`, `-D` properties, or `main()`
   args.
4. **Each value has exactly one home** — never both a HOCON default and a dotenv
   definition.
5. **The local overlay never reaches the test JVM** — every test JVM pins
   `unicoach.config.dir` to an overlay-free directory.
6. **Environments are isolated by disjoint identity** (infra) — per-env SSM
   prefix, state key, on-disk state dir, IAM scope, and resource names.

**Layers and the hand-off** — the process environment is the universal hand-off
point:

- **Local:** `bin/*` source `.env` → exported env → JVM `${?VAR}`; the
  `~/.config/unicoach/local.conf` overlay (the on-host home for local secrets)
  sits highest.
- **Cloud:** `.env.<env>` → `TF_VAR_*` → SSM `/unicoach/<env>/*` →
  `render-env.sh` → `/etc/unicoach/env` → systemd → JVM `${?VAR}`. No `.env`, no
  overlay on the host.

**Per-environment manifest:**

| Variable            | Where                        | Notes                                   |
| ------------------- | ---------------------------- | --------------------------------------- |
| `UNICOACH_ENV`      | invocation arg               | selects `.env.<env>`, state, SSM prefix |
| `ENVIRONMENT`       | `.env.<env>` → `TF_VAR_*`    | MUST equal `UNICOACH_ENV`; required     |
| `APP_DOMAIN`        | `.env.<env>` → `TF_VAR_*`    | the env's web host; required            |
| `HOSTED_ZONE_NAME`  | `.env.<env>` (omit for apex) | parent zone; coalesces to `APP_DOMAIN`  |
| `GOOGLE_CLIENT_IDS` | `.env.<env>` → `TF_VAR_*`    | accepted OAuth audiences; required      |
| `REGION`            | `.env.<env>` → `TF_VAR_*`    | AWS region; defaults to `us-east-1`     |

**Secret inventory** (cloud home → set by):

| Secret                    | Cloud home (`/unicoach/<env>/`) | Set by                        |
| ------------------------- | ------------------------------- | ----------------------------- |
| `PGPASSWORD` (RDS master) | `PGPASSWORD`                    | Terraform `random_password`   |
| `DATABASE_PASSWORD`       | `DATABASE_PASSWORD`             | operator-seeded (placeholder) |
| `CHAT_ANTHROPIC_API_KEY`  | `CHAT_ANTHROPIC_API_KEY`        | operator-seeded               |
| `UNICOACH_CLIENT_KEYS`    | `UNICOACH_CLIENT_KEYS`          | operator-seeded (gate off)    |
| `EMAIL_SES_*`             | `EMAIL_SES_*` (if static creds) | operator-seeded               |

A fresh checkout runs locally with **zero AWS**: localhost Postgres (`trust`,
dev password) and `.conf` dev defaults (`chat`/`email` = `log`, `auth.google` =
`stub`, dev URLs). Live providers and secrets are opted in via
`~/.config/unicoach/local.conf` only. To add a cloud environment, see
[Add an environment](#add-an-environment).

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
