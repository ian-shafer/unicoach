# RFC 55: Cluster-Lifecycle-Agnostic DB Scripts

## Executive Summary

The AWS deploy path ships a release bundle to the EC2 host, which runs
`db-create-role → db-create → db-migrate` against RDS before swapping the
current-release symlink (`infra/files/deploy-on-instance.sh`). That path has
never completed end-to-end. Two coupled defects break it, both rooted in the DB
scripts conflating cluster lifecycle with schema work:

1. `bin/db-create` shells out to `bin/postgres-up` to boot a local `pg_ctl`
   cluster. Against RDS there is no local data directory or server, so the call
   fatals — and `postgres-up` is not even in the deploy bundle, so it fails
   regardless.
2. `bin/postgres-check` — invoked transitively by every `db-run` (and therefore
   every `db-create`/`db-migrate` query) — hardcodes `pg_isready -h localhost`,
   so on the instance it probes the wrong host. The deploy bundle also omits
   `bin/postgres-check` entirely, and `db-run` suppresses its stderr, so a
   missing file surfaces as the misleading "PostgreSQL is offline".

This RFC makes the DB DDL/migration scripts (`db-create`, `db-create-role`,
`db-migrate`) cluster-lifecycle-agnostic: they assume a cluster already running
and reachable at `PGHOST:PGPORT` and never start one. Starting/initialising the
cluster is exclusively the environment's responsibility — `bin/test` locally
(`postgres-up` before `db-reset`), managed RDS in prod. This unifies the local
and prod paths instead of forking them, extending the model `db-create-role`
already follows. The fix removes the `postgres-up` coupling from `db-create`,
makes `postgres-check` honor `PGHOST`, and completes the deploy bundle.

## Detailed Design

### Invariant: DB scripts assume an already-running cluster

`db-create`, `db-create-role`, and `db-migrate` connect to whatever cluster
`PGHOST:PGPORT` points at and MUST NOT start or initialise one. `db-create-role`
already holds (no `initdb`/`postgres-up`; connects as the master role to the
admin DB) as does `db-migrate` (reaches the DB only via `db-status`/`db-write`,
which gate on `postgres-check`; no `pg_ctl` call). This RFC brings `db-create`
into line and fixes the shared `postgres-check` probe.

Cluster lifecycle stays with the environment: locally `bin/test` runs
`postgres-up` before `db-reset`, and `bin/db-scripts-tests` stands up its own
private cluster; in prod RDS provides the running cluster and already creates
the `unicoach` database with master role `unicoach_admin` (`infra/rds.tf`).

### `bin/db-create` — drop the `postgres-up` coupling

Remove the `"$PROJECT_ROOT/bin/postgres-up" >/dev/null` invocation and its
preceding comment (`bin/db-create:43-44`). `db-create` then performs schema work
only: `CREATE DATABASE` if-not-exists, `GRANT CONNECT`, schema-access and
default-privilege grants, and `CREATE TABLE IF NOT EXISTS schema_migrations`.
The existing-database check (`SELECT 1 FROM pg_database …` via `db-query`) makes
`CREATE DATABASE` a no-op against RDS, where the database already exists; the
`GRANT CONNECT` to the application role (`DATABASE_USER`) is still required, so
`db-create` must still run on every deploy.

The `help()` text is updated: drop the "Ensures the cluster is running
(postgres-up)" numbered step (`bin/db-create:17`) and renumber the remaining
steps, and replace the "run db-bootstrap once first" clause (`bin/db-create:14`)
with a statement that `db-create` assumes a running cluster reachable at
`PGHOST:PGPORT`. Removing both the help step and the call (`:44`) is what clears
every `postgres-up` reference from the file. No behavioral change beyond
removing the `postgres-up` call.

### `bin/postgres-check` — honor `PGHOST`

Change the probe host from the hardcoded `localhost` to
`"${PGHOST:-localhost}"`. The probe (`bin/postgres-check:30`) becomes
`pg_isready -q -h "${PGHOST:-localhost}" -p "$POSTGRES_PORT" -U "$POSTGRES_USER"`,
and the help line (`bin/postgres-check:13`) changes from "accepting connections
on localhost at POSTGRES_PORT" to "accepting connections at PGHOST (default
localhost) on POSTGRES_PORT".

`PGHOST` reaches the process via the environment file: `bin/common` does
`set -a; source "$ENV_FILE"`, so the `PGHOST` line in the SSM-rendered
`/etc/unicoach/env` is exported and consumed by both `pg_isready` here and
`psql` in `db-run` (libpq reads `PGHOST` natively, so `db-run` needs no `-h`
flag). `PGHOST` originates in the `/unicoach/prod` SSM prefix as
`aws_db_instance.main.address` (`infra/ssm.tf:16`); `render-env.sh` writes the
whole prefix verbatim to `/etc/unicoach/env`. When `PGHOST` is unset — the local
default, where `.env`/`.env.test` set no host — the probe falls back to
`localhost`, preserving current local behavior.

`bin/common` is not modified: it exports `PGPORT` (derived from the required
`POSTGRES_PORT`) but `PGHOST` flows directly from the env file via `set -a`, so
no explicit export is needed.

### `bin/deploy` — complete the bundle

Add `bin/postgres-check` to `BUNDLE_PATHS` (`bin/deploy:63-76`). The on-instance
migration path — `db-create-role → db-create → db-migrate` — transitively
sources/execs exactly this set of `bin/` scripts: `common`, `functions`,
`db-create-role`, `db-create`, `db-migrate`, `db-status`, `db-run`, `db-write`,
`db-query`, and `postgres-check` (reached from every `db-run`). The bundle
currently lists all but `postgres-check`. After the `db-create` change above,
`postgres-up` and `postgres-wait-for-health` are NOT reachable from the
migration path and MUST NOT be added — the bundle stays minimal and complete.
The bundle-rationale comment (`bin/deploy:56-58`) is updated to name
`postgres-check` and the `db-run` gating it.

### Error Handling / Edge Cases

- With the cluster unreachable, `db-create` now fails through `db-query`/
  `db-write` → `db-run` → `postgres-check`. `db-query`/`db-write` `exec` into
  `db-run`, which exits 2 when `postgres-check` fails (`bin/db-run:41-43`); that
  2 surfaces directly under `set -euo pipefail` instead of `db-create`
  attempting to start a server. On the instance, `deploy-on-instance.sh` runs
  the migration steps in a subshell before the symlink swap, so a failure leaves
  the previous release serving.
- A `postgres-check` file missing from the bundle previously read as "PostgreSQL
  is offline" because `db-run` suppresses its stderr (`db-run:41`). Including it
  in the bundle removes that failure mode; the misleading message is not
  otherwise changed (out of scope).

### Dependencies

No new dependencies. `infra/files/deploy-on-instance.sh` and
`infra/files/render-env.sh` already invoke the correct sequence and write
`PGHOST`/`PGPORT` into `/etc/unicoach/env`; neither requires modification.

## Tests

Two hermetic shell-harness additions; both run inside the Nix dev shell.

### `bin/db-scripts-tests` — `db-create` does not start the cluster

A new test asserting the core invariant against the harness's private cluster.
Inserted immediately after `test_container_unreachable_exits_2` (which leaves
the private cluster stopped via `postgres-down`), before the lifecycle test that
restarts it:

- **`db-create does not start a stopped cluster`**: with the private cluster
  down, assert `$POSTGRES_DATA_DIR/postmaster.pid` is absent; run
  `bin/db-create` and assert it exits non-zero (it cannot reach the cluster);
  re-assert `postmaster.pid` is still absent (the cluster was not started). The
  subsequent `test_db_lifecycle_wipes_data` already runs `postgres-up` to
  restore the cluster for later tests, so no teardown change is needed.

This locks in that `db-create` performs schema work only and never owns cluster
lifecycle. The existing `bin/db-bootstrap`/`bin/db-create` ordering in the
harness (`db-scripts-tests:49-50`, `:131-133`) already supplies a running
cluster before each `db-create`, so removing the `postgres-up` call does not
regress any existing assertion.

### `bin/scripts-tests` — deploy bundle completeness

A new static assertion (no AWS, no daemons) verifying the deploy bundle is
self-contained for the on-instance migration path:

- **`deploy bundle includes the full migration-path script set`**: parse the
  `BUNDLE_PATHS` array out of `bin/deploy` and assert it contains each of
  `bin/common`, `bin/functions`, `bin/db-create-role`, `bin/db-create`,
  `bin/db-migrate`, `bin/db-status`, `bin/db-run`, `bin/db-write`,
  `bin/db-query`, and `bin/postgres-check`. Guards against a future regression
  re-dropping `postgres-check` (or any migration-path script) from the bundle.

### Full deploy (operator-run acceptance)

Not a CI test — exercised by the operator per the acceptance criteria: a fresh
`bin/deploy` reaches SSM command `Status=Success` through the migration steps,
and `curl -fsS https://api.unicoachapp.com/healthz` returns `200`.

## Implementation Plan

1. **`bin/postgres-check`: honor `PGHOST`.** Change `-h localhost` to
   `-h "${PGHOST:-localhost}"` at `bin/postgres-check:30` and update the
   `help()` description (`:13`) to state "accepting connections at PGHOST
   (default localhost) on POSTGRES_PORT".
   - Verify: `nix develop -c bash -n bin/postgres-check`
   - Verify:
     `grep -q 'pg_isready -q -h "${PGHOST:-localhost}"' bin/postgres-check`
   - Verify: `! grep -q '\-h localhost' bin/postgres-check`

2. **`bin/db-create`: remove the `postgres-up` coupling.** Delete the comment
   and `postgres-up` invocation at `bin/db-create:43-44`. Update `help()`:
   remove the "Ensures the cluster is running (postgres-up)" numbered step
   (`:17`), renumber the remaining steps, and replace "Assumes the cluster and
   the application role already exist (run db-bootstrap once first)" (`:14`)
   with a statement that `db-create` assumes a running cluster reachable at
   `PGHOST:PGPORT`. The `! grep` verify below passes only if both the help step
   (`:17`) and the call (`:44`) are removed.
   - Verify: `nix develop -c bash -n bin/db-create`
   - Verify: `! grep -q 'postgres-up' bin/db-create`

3. **`bin/deploy`: add `bin/postgres-check` to the bundle.** Add the
   `"bin/postgres-check"` entry to `BUNDLE_PATHS` (`:63-76`) and update the
   bundle-rationale comment (`:56-58`) to name `postgres-check` and the `db-run`
   gating it. Do not add `postgres-up` or `postgres-wait-for-health`.
   - Verify: `nix develop -c bash -n bin/deploy`
   - Verify: `grep -q '"bin/postgres-check"' bin/deploy`
   - Verify: `! grep -q 'postgres-up\|postgres-wait-for-health' bin/deploy`

4. **`bin/db-scripts-tests`: add the
   `db-create does not start a stopped cluster` regression test.** Insert after
   `test_container_unreachable_exits_2` (`:117-126`), asserting `postmaster.pid`
   absence before and after a non-zero `db-create` against the stopped private
   cluster.
   - Verify: `nix develop -c bin/db-scripts-tests`

5. **`bin/scripts-tests`: add the
   `deploy bundle includes the full
   migration-path script set` assertion.**
   Parse `BUNDLE_PATHS` from `bin/deploy` and assert membership of the ten
   migration-path scripts.
   - Verify: `nix develop -c bin/scripts-tests`

6. **Confirm `bin/db-migrate` is unchanged.** It already has no
   `postgres-up`/`pg_ctl` call and reaches the DB only via `db-status`/
   `db-write`; the shared `postgres-check` fix (step 1) is the only change it
   depends on. No edit.
   - Verify: `! grep -q 'postgres-up\|pg_ctl' bin/db-migrate`

7. **Full local shell-suite regression.**
   - Verify: `nix develop -c bin/scripts-tests`
   - Verify: `nix develop -c bin/db-scripts-tests`

## Files Modified

- `bin/db-create` — remove the `bin/postgres-up` invocation and its comment;
  update `help()` text and step list to drop the cluster-start step and assume a
  running cluster at `PGHOST:PGPORT`.
- `bin/postgres-check` — `pg_isready -h "${PGHOST:-localhost}"` and updated
  `help()` text.
- `bin/deploy` — add `"bin/postgres-check"` to `BUNDLE_PATHS`; update the
  bundle-rationale comment.
- `bin/db-scripts-tests` — add the `db-create does not start a stopped cluster`
  regression test.
- `bin/scripts-tests` — add the deploy-bundle-completeness assertion.
