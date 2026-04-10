# 05 Database Scripts

## Executive Summary

This specification defines database shell scripts designed to manage the
lifecycle of the Postgres database schema, migrations, and interactive querying.
It formalizes operations such as database initialization (`db-init`), structural
destruction (`db-destroy`), schema migrations (`db-migrate`), and ad hoc
interactions (`db-repl`, `db-query`, `db-update`, `db-run`). These scripts
provide a standardized, idempotent, and testable interface for all local and CI
database interactions.

## Detailed Design

### Connection Architecture

To eliminate host-machine dependencies, all `db-*` scripts will interface with
PostgreSQL by proxying commands through the running container using Docker
Compose. Operational scripts (e.g., `db-run`, `db-migrate`) will query the
application database natively defined by the environment variable `$POSTGRES_DB`
via
`docker compose -f docker/postgres-compose.yaml exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"`.
Administrative lifecycle scripts (`db-init`, `db-destroy`) will explicitly
connect to the system `postgres` database (`-d postgres`) to permit creating and
dropping the application database safely. This utilizes the `psql` binary
natively shipped inside the `postgres:18` image, guaranteeing version parity and
zero local setup.

### Script Interfaces and Behaviours

- **`db-init`**: Idempotent. Connects to the system `postgres` database to
  create the application database (`CREATE DATABASE "$POSTGRES_DB";`) if it does
  not already exist, gracefully handling the fact that the initial Docker boot
  may have already created it. Afterwards, it provisions the schema migration
  tracking table inside the application database.
- **`db-destroy`**: Idempotent. Connects to the system `postgres` database to
  drop the application database. It explicitly uses
  `DROP DATABASE IF EXISTS "$POSTGRES_DB" WITH (FORCE);` to ensure any hanging
  connections are forcefully terminated. If `--destroy-all-db-data` is passed,
  the database is deleted immediately. Otherwise, it prompts the user to type
  `DESTROY`. If the user hits enter (empty input), it exits immediately with
  status `0`. If the user enters anything other than `DESTROY`, it prints the
  input to stderr and prompts again. The user can exit the loop via `ctrl-c` or
  `ctrl-d`.
- **`db-migrate`**: Idempotent. Uses `file-lock var/locks/db-migrate 60s` to
  prevent concurrent execution. Identifies unapplied schema files in the target
  `$DB_SCHEMA_DIR` (prefix: `XXXX.schema-name.sql`). Applies each file within
  its own isolated transaction and immediately registers it in the tracking
  table so partial progress is safely checkpointed.
- **`db-status`**: Emits the current database schema version timestamp/ID and
  lists any pending unapplied schema migrations. Accepts an optional
  `--format <type>` (`-f`) flag with valid values `applied-only` and
  `unapplied-only`, which outputs a simple list containing only the filenames of
  the respective files.
- **`db-repl`**: Drops the user into an interactive `psql` session by executing
  an interactive `docker compose exec` shell instead of `-T`.
- **`db-query`**: Runs read-only queries directly from the CLI. Accepts the SQL
  statement either as a positional argument or piped via `stdin` (e.g.,
  `echo "SELECT *" | db-query`). Supports `psql` output formatting options.
- **`db-update`**: Runs mutating SQL statements (Create, Update, Delete) from
  the CLI. Accepts the SQL statement via positional argument or `stdin`.
  Supports output options similar to `db-query`.
- **`db-run`**: The underlying engine for `db-query` and `db-update`. Accepts a
  generic access mode (`rw` or `ro`) and a SQL statement via argument or
  `stdin`, dispatching to `docker compose exec` with structured options. When
  the `ro` mode is specified, immutability is strictly enforced by injecting
  `-e PGOPTIONS='-c default_transaction_read_only=on'` into the database
  connection environment.

### Schema Tracking Table

The database schema will be tracked in a `schema_migrations` table to provide an
immutable history of applied updates. It will have the following structure:

- `version_id`: `VARCHAR(4)` (Primary Key). The unique 4-digit prefix extracted
  from the schema filename.
- `filename`: `VARCHAR(255)`. The full name of the applied schema file.
- `applied_at`: `TIMESTAMP WITH TIME ZONE DEFAULT NOW()`. The exact time the
  migration was successfully completed.

### Schema Files Format

Stored in the directory specified by the `DB_SCHEMA_DIR` environment variable
(which defaults to `$PROJECT_ROOT/db/schema` if unset). This allows test suites
to safely inject temporary isolation directories via `export DB_SCHEMA_DIR=...`.
**Note**: Permitting this environment variable override is an explicit exception
to our global "no magic exports" rule, implemented specifically to afford test
safety. Files are loaded in strict lexicographical order (enforced via
`LC_COLLATE=C sort`) using a 4-digit prefix convention (e.g.,
`0000.initial-schema.sql`, `0001.add-users.sql`).

### Error Contracts

To ensure fault tolerance, all `db-*` scripts adhere to the following error
handling principles:

- **Standard Exit Codes**: All scripts must return an exit status of `0` on
  absolute success, and `1` on general failures.
- **Container Unavailability**: If the PostgreSQL Docker container is not
  running or unreachable, the scripts must fail fast and return a unique exit
  code (e.g., `2`) so calling scripts can differentiate this from SQL or general
  errors.
- **`psql` Pass-Through**: For `db-run`, `db-query`, and `db-update`, any error
  thrown by the underlying `psql` execution (e.g., invalid SQL syntax,
  constraint violations) must propagate its exact exit code transparently to the
  caller and print the raw `psql` stderr output.
- **Migration Aborts**: During `db-migrate`, if an individual schema file fails,
  the script must ensure the transaction rolls back, print the failing filename
  and error, and exit `1` immediately without attempting subsequent schema
  files. This MUST be enforced by passing the `-v ON_ERROR_STOP=1` flag to
  `psql` to override its default behavior of continuing execution after an
  error.
- **Strict Shell execution**: Scripts will source `bin/common` to globally
  enforce strict shell execution (`set -euo pipefail`).

### Rollbacks

The database migration architecture is strictly append-only. Down migrations
(rollbacks) are deliberately unsupported. If a migration is flawed or needs to
be reverted during local development, the intended workflow is to completely
destroy the database schema using `db-destroy` and re-evaluate from a clean
state via `db-init` and `db-migrate`.

## Tests

A test suite will be housed in `bin/db-scripts-tests` to independently verify
every `db-*` command behavior, edge case, and error state.

To enforce DRY standards among shell test files, validation logging and general
execution assertions (e.g., `assert_success`, `assert_failure`, and explicit
output formatting for pass/fail states) will be extracted into a shared library
at `bin/tests-common`. This shared library will strictly exclude domain-specific
operations or lifecycle hooks like `teardown`, keeping it generic and reusable
across `bin/scripts-tests` and `bin/db-scripts-tests`.

### Core Test Matrix

#### `db-init`

- `test_db_is_created`: Verifies the database is successfully created.
- `test_db_user_has_full_access_to_db`: Verifies the configured application user
  has full CRUD permissions on the database.
- `test_db_init_creates_schema_table`: Verifies initial execution provisions the
  `schema_migrations` tracking table.
- `test_db_init_idempotent`: Verifies subsequent executions exit `0` without
  side effects.

#### `db-destroy`

- `test_db_destroy_succeeds_with_flag`: Verifies database is dropped when
  `--destroy-all-db-data` flag is provided.
- `test_db_destroy_interactive_success`: Verifies database is dropped when user
  interactively types `DESTROY`.
- `test_db_destroy_interactive_empty_exit`: Verifies script exits `0` safely if
  user provides empty input.
- `test_db_destroy_interactive_retry`: Verifies script prompts again on invalid
  input and prints to stderr.
- `test_db_destroy_interactive_ctrl_c`: Verifies script exits cleanly on
  `ctrl-c` or `ctrl-d` interruptions.

#### `db-query` / `db-update` / `db-run`

- `test_db_run_ro_blocks_mutation`: Verifies `db-run ro` blocks
  `INSERT`/`UPDATE`/`DELETE`.
- `test_db_run_rw_allows_mutation`: Verifies `db-run rw` permits state changes.
- `test_db_query_via_stdin`: Verifies piping SQL to `db-query` executes
  correctly.
- `test_db_query_via_positional`: Verifies passing SQL as a positional argument
  to `db-query` executes correctly.
- `test_db_update_via_stdin`: Verifies piping SQL to `db-update` executes
  mutating SQL successfully.
- `test_db_update_via_positional`: Verifies passing SQL as a positional argument
  to `db-update` executes mutating SQL successfully.

#### `db-migrate`

- `test_db_migrate_alphabetical_application`: Verifies schema files are applied
  in strict lexicographical order.
- `test_db_migrate_transaction_rollback`: Verifies a failing `.sql` file aborts
  its transaction and does not commit partial state to `schema_migrations`.
- `test_db_migrate_skips_applied`: Verifies previously applied files stored in
  `schema_migrations` are safely skipped.
- `test_db_migrate_handles_concurrency`: Verifies simultaneous executions are
  properly serialized via `file-lock var/locks/db-migrate`.

#### `db-status`

- `test_db_status_reports_version`: Verifies the current schema version is
  printed.
- `test_db_status_lists_pending`: Verifies pending schema files are accurately
  identified.

#### `db-repl`

- `test_db_repl_interactive_shell`: Verifies it initiates the interactive
  session correctly.

#### Error Handling Validation

- `test_container_unreachable_exits_2`: Verifies scripts fail fast and return
  exit code `2` if the `postgres` container is down.
- `test_psql_error_propagation`: Verifies `db-run` (and its alias wrappers)
  accurately bubbles up `psql` exit codes (e.g., invalid SQL syntax).
- `test_psql_stderr_output`: Verifies `db-run` accurately pipes raw `psql`
  standard error upon general query failure.

## Implementation Plan

For each implementation step below, you MUST simultaneously write the
corresponding test case in `bin/db-scripts-tests` and verify it succeeds locally
before proceeding to the next step.

1. **Test Infrastructure**: Refactor `bin/scripts-tests` to extract generic
   assertion routines and output formatting into `bin/tests-common`. Verify
   existing daemon tests still succeed.
2. **Core Execution & Queries**: Implement `db-run`, `db-query`, `db-update`,
   and `db-repl`. Write their tests in `bin/db-scripts-tests` and verify
   execution.
3. **Database Lifecycle**: Implement `db-init` (instantiating
   `schema_migrations`) and `db-destroy`. Write their tests in
   `bin/db-scripts-tests`, verifying idempotency and failure modes.
4. **Migration Engine**: Implement `db-status` and `db-migrate`, strictly
   enforcing the sequential loop and per-file transaction requirements. Write
   their tests in `bin/db-scripts-tests`, specifically verifying alphabetical
   application and transaction boundaries.

## Files Modified

- `bin/db-init` [NEW]
- `bin/db-destroy` [NEW]
- `bin/db-migrate` [NEW]
- `bin/db-status` [NEW]
- `bin/db-repl` [NEW]
- `bin/db-query` [NEW]
- `bin/db-update` [NEW]
- `bin/db-run` [NEW]
- `db/schema/` (Directory) [NEW]
- `bin/db-scripts-tests` [NEW]
- `bin/tests-common` [NEW]
- `bin/scripts-tests` [MODIFY]
