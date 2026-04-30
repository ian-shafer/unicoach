# SPEC: db/schema

## I. Overview

`db/schema/` is the append-only SQL migration layer for the Unicoach PostgreSQL
database. It owns the canonical DDL for every table, trigger, function, and
index. Files are consumed exclusively by the `db-migrate` shell script and are
never imported by JVM code. The directory is also the source of truth for
shared PL/pgSQL utility functions reused across all entity tables.

---

## II. Invariants

### Global Schema Invariants

- The system MUST apply schema files in strict lexicographical order (enforced
  by `LC_COLLATE=C sort`) using a 4-digit numeric prefix convention
  (e.g., `0000.shared-functions.sql`, `0001.create-users.sql`).
- The schema MUST be append-only. Down migrations (rollbacks) are NEVER
  supported. Reversion requires a full `db-destroy` + `db-init` + `db-migrate`
  cycle.
- Every migration file MUST be applied inside its own isolated SQL transaction.
  A failure MUST roll back the entire file and MUST NOT apply subsequent files.
- Every migration file MUST be idempotent with respect to the tracking table:
  once a `version_id` is recorded in `schema_migrations`, re-running `db-migrate`
  MUST skip it.

### Shared Functions (`0000.shared-functions.sql`)

- `update_timestamp()` MUST always set `row_updated_at = NOW()`.
- `update_timestamp()` MUST only update `updated_at` when the session variable
  `unicoach.bypass_logical_timestamp` is NOT set to `'true'`. This bypass MUST
  be controlled via PostgreSQL's `current_setting(...)` mechanism, never by
  application-layer conditional logic.
- `enforce_versioning()` MUST raise `ERRCODE = '23514'` when an INSERT supplies
  a `version` other than `1`.
- `enforce_versioning()` MUST raise `ERRCODE = '40001'` (serialization_failure)
  when an UPDATE supplies a `version` that is not exactly `OLD.version + 1`.
- `prevent_physical_delete()` MUST raise `ERRCODE = 'P0001'` unconditionally on
  any DELETE trigger invocation.
- `prevent_immutable_updates()` MUST raise `ERRCODE = 'P0001'` if an UPDATE
  attempts to change `id`, `created_at`, or `row_created_at`.

### `users` Table (`0001.create-users.sql`)

- Every `users` row MUST have either `password_hash IS NOT NULL` or
  `sso_provider_id IS NOT NULL` (enforced by `users_auth_method_check`).
- `email` MUST be stored lowercase and trimmed (enforced by
  `trim_users_strings` trigger and `users_email_lowercase_check` /
  `users_email_trim_check` constraints).
- `email` MUST match the pattern `'%@%'` and MUST NOT be blank after trimming.
- `name`, `display_name`, `password_hash`, and `sso_provider_id` MUST NOT be
  empty strings if non-null (enforced by `_not_empty_check` constraints).
- `name` and `display_name` MUST be stored pre-trimmed (enforced by
  `trim_users_strings` trigger and `_trim_check` constraints).
- An email address MUST be unique among active (non-deleted) users
  (`users_email_unique_active_idx` partial unique index where `deleted_at IS NULL`).
- Physical deletions of `users` rows are NEVER permitted (enforced by
  `trigger_00_prevent_physical_delete`).
- BEFORE triggers on a table execute in alphabetical name order. The naming
  convention `trigger_00`, `trigger_00a`, `trigger_01`, ... MUST be preserved to
  guarantee the correct execution sequence across all entity tables.
- Soft-deletion MUST be performed by setting `deleted_at` to a non-null
  timestamp.
- Every `users` INSERT or UPDATE MUST be logged to `users_versions` via the
  `trigger_04_log_user_version` AFTER trigger.
- `users_versions` rows MUST NOT be physically deleted (`ON DELETE RESTRICT` on
  the `users` foreign key).

### `sessions` Table (`0002.create-sessions.sql`)

- `token_hash` MUST be stored as `BYTEA` (SHA-256 of the plain-text token).
  The plain-text token is NEVER stored.
- `token_hash` MUST be unique across all sessions
  (`sessions_token_hash_idx` unique index).
- `user_id` is NULLABLE (anonymous sessions). When the referenced `users` row
  is physically deleted, `ON DELETE CASCADE` deletes the `sessions` row entirely.
  Anonymous sessions (where `user_id IS NULL`) are NOT affected by user deletion.
  Transitioning an anonymous session to an authenticated one MUST be done via
  application UPDATE (setting `user_id` and rotating `token_hash`), not via
  database FK mechanics.
- Physical deletes of `sessions` rows ARE explicitly permitted (no
  `prevent_physical_delete` trigger on this table).
- The `sessions` table MUST NOT use a `deleted_at` column or soft-delete pattern.
  Session lifecycle is managed exclusively via `is_revoked` and `expires_at`.
  No version-history table (equivalent to `users_versions`) exists for sessions.
- `is_revoked` MUST default to `false`. Revocation MUST be performed by setting
  `is_revoked = true` via UPDATE; no other field mutation is required for revocation.
- `expires_at` MUST be `NOT NULL`. A plain index on `expires_at`
  (`sessions_expires_at_idx`) MUST exist to support efficient expiry-based queries
  and zombie-purge operations.
- `user_agent` length MUST NOT exceed 512 characters.
- `initial_ip` length MUST NOT exceed 64 characters.
- `metadata` byte size MUST NOT exceed 2048 bytes.

### `jobs` Table (`0003.create-queue.sql`)

- `status` MUST be one of: `'SCHEDULED'`, `'RUNNING'`, `'COMPLETED'`,
  `'DEAD_LETTERED'` (enforced by `jobs_status_valid_check`).
- `job_type` length MUST NOT exceed 128 characters.
- `payload` size MUST NOT exceed 65,536 bytes (`octet_length(payload::text) <= 65536`).
- `jobs` has no `version` column; `enforce_versioning` MUST NOT be applied.
- `updated_at` MUST be maintained automatically by the
  `trigger_03_enforce_jobs_updated_at` trigger via `update_jobs_timestamp()`.
  This is a local variant of `update_timestamp()` without the logical/physical
  split (jobs has no `row_updated_at`).

### `job_attempts` Table (`0003.create-queue.sql`)

- `status` MUST be one of: `'SUCCESS'`, `'RETRIABLE_FAILURE'`,
  `'PERMANENT_FAILURE'` (enforced by `job_attempts_status_valid_check`).
- `(job_id, attempt_number)` MUST be unique (enforced by `UNIQUE` constraint).
- `error_message` length MUST NOT exceed 4,096 characters.
- `finished_at` MUST be set by the database at insert time (`DEFAULT NOW()`);
  application code MUST NOT supply this value.
- Deleting a `jobs` row MUST cascade to all associated `job_attempts` rows
  (`ON DELETE CASCADE`).

### `notify_jobs` Trigger (`0004.add-jobs-notify-trigger.sql`)

- A NOTIFY on channel `'jobs_channel'` with payload `NEW.job_type` MUST be
  emitted after every INSERT or UPDATE to `jobs` where `NEW.status = 'SCHEDULED'`.
- The trigger MUST fire AFTER INSERT OR UPDATE (not BEFORE).

---

## III. Behavioral Contracts

### `update_timestamp()` — Shared Trigger Function

- **Trigger type**: `BEFORE UPDATE`
- **Side effects**: Mutates `NEW.row_updated_at` (always); mutates `NEW.updated_at`
  conditionally based on session variable.
- **Error handling**: No exceptions raised; returns `NEW`.
- **Idempotency**: Not idempotent (every invocation updates `row_updated_at`).
- **Bypass mechanism**: Callers set
  `SET LOCAL unicoach.bypass_logical_timestamp = 'true'` within the same
  transaction to freeze `updated_at` while still advancing `row_updated_at`.

### `enforce_versioning()` — Shared Trigger Function

- **Trigger type**: `BEFORE INSERT OR UPDATE`
- **Side effects**: None on success; raises exception on violation.
- **Error handling**:
  - INSERT with `version != 1` → `ERRCODE 23514`, message: `'Initial version must be 1'`
  - UPDATE with `version != OLD.version + 1` → `ERRCODE 40001`, message:
    `'Optimistic Concurrency Control conflict: ...'`
- **Idempotency**: N/A — stateless validation function.
- **OCC application requirement**: Application MUST supply the absolute next
  version number (e.g., `SET version = 2`). Relative SQL (`SET version = version + 1`)
  MUST NOT be used, as PostgreSQL evaluates it against the latest committed row
  in a race, bypassing the trigger check.

### `prevent_physical_delete()` — Shared Trigger Function

- **Trigger type**: `BEFORE DELETE`
- **Side effects**: Always raises `ERRCODE P0001`.
- **Error handling**: Raises unconditionally. No return path.
- **Idempotency**: N/A.

### `prevent_immutable_updates()` — Shared Trigger Function

- **Trigger type**: `BEFORE UPDATE`
- **Side effects**: Raises exception if `id`, `created_at`, or `row_created_at`
  are changed.
- **Error handling**: `ERRCODE P0001` per field.
- **Idempotency**: N/A.

### `trim_users_strings()` — Users-Specific Trigger Function

- **Trigger type**: `BEFORE INSERT OR UPDATE` on `users`
- **Side effects**: Normalizes `email` (lowercased + trimmed), `name` (trimmed),
  and `display_name` (trimmed if non-null).
- **Idempotency**: Yes — applying twice produces the same result.

### `log_user_version()` — Users-Specific Trigger Function

- **Trigger type**: `AFTER INSERT OR UPDATE` on `users`
- **Side effects**: Writes one row to `users_versions` for every INSERT or UPDATE
  on `users`.
- **Error handling**: Failure raises a `PostgreSQL` exception; the parent
  transaction is rolled back.
- **Idempotency**: No — repeated inserts with the same `(id, version)` would
  violate the primary key.

### `update_jobs_timestamp()` — Jobs-Specific Trigger Function

- **Trigger type**: `BEFORE UPDATE` on `jobs`
- **Side effects**: Sets `NEW.updated_at = NOW()`.
- **Error handling**: None — returns `NEW`.
- **Idempotency**: Not idempotent (timestamp advances on each call).

### `notify_jobs()` — Jobs Notification Trigger Function

- **Trigger type**: `AFTER INSERT OR UPDATE` on `jobs`
- **Side effects**: Calls `pg_notify('jobs_channel', NEW.job_type)` when
  `NEW.status = 'SCHEDULED'`.
- **Error handling**: None — PostgreSQL propagates any internal error.
- **Idempotency**: NOTIFY delivery is best-effort and non-durable. Consumers
  MUST NOT rely on exactly-once delivery.

---

## IV. Infrastructure & Environment

- **`DB_SCHEMA_DIR`** environment variable: Consumed by the `db-migrate` shell
  script. Defaults to `$PROJECT_ROOT/db/schema`. Test suites MUST override this
  to an isolated temp directory to prevent cross-contamination.
- **`pg_uuidv7` PostgreSQL extension**: The `uuidv7()` function is provided by
  the `pg_uuidv7` extension (or equivalent). This extension MUST be installed in
  the database before any schema migration runs. It is not defined in this
  directory.
- **`schema_migrations` tracking table**: Created by `db-init` (not defined in
  this directory). `db-migrate` writes one row per applied file. The `version_id`
  column holds the 4-digit file prefix; the `filename` column holds the full
  filename.
- **PostgreSQL version**: Schema relies on `pg_notify`, `TIMESTAMPTZ`,
  `JSONB`, `BYTEA`, and `uuidv7()`. Requires PostgreSQL 18 (shipped in the
  `postgres:18` Docker image defined in `docker/postgres-compose.yaml`).
- **`unicoach.bypass_logical_timestamp`**: A custom PostgreSQL session
  configuration parameter. Must be set at the session level (`SET LOCAL ...`)
  by application code that requires decoupled logical/physical timestamp
  advancement. No default is defined at the server level; `current_setting`
  uses the safe two-argument form to return `NULL` rather than erroring.

---

## V. History

- [x] [RFC-05: Database Scripts](../../rfc/05-db-scripts.md)
- [x] [RFC-06: Users Table](../../rfc/06-users-table.md)
- [x] [RFC-11: Sessions](../../rfc/11-sessions.md)
- [x] [RFC-14: Extract Database Module](../../rfc/14-db-module.md)
- [x] [RFC-15: Queue Data Layer](../../rfc/15-queue-data-layer.md)
- [x] [RFC-16: Queue Worker Framework](../../rfc/16-queue-worker-framework.md)
