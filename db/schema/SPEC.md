# SPEC: db/schema

## I. Overview

`db/schema/` is the append-only SQL migration layer for the Unicoach PostgreSQL
database. Each file defines DDL — tables, indexes, check constraints, unique
indexes, functions, and triggers — applied in lexicographical order by
`bin/db-migrate`, the sole consumer of this directory.

Migrations MAY insert application-level reference data (e.g., a table of valid
state values). They MUST NOT insert user data; user data originates exclusively
from application usage.

The schema is the primary enforcement layer for application invariants. Every
constraint that can be expressed in DDL — check constraints, unique indexes,
foreign keys, NOT NULL — MUST be defined in the database, not deferred to
application code. Where possible, the database MUST generate derived values at
the DDL level (e.g., primary keys via `uuidv7()`, timestamps via
`DEFAULT NOW()` or trigger) rather than accepting them from application code.

PostgreSQL's clock is the single source of truth for time in the system.
Application code MUST NOT supply its own point-in-time values (e.g.,
`created_at` or `updated_at`), but MAY perform time arithmetic on values
retrieved from PostgreSQL.

---

## II. Invariants

### Global Schema Invariants

- `bin/db-migrate` MUST apply schema files in strict lexicographical order.
  Files currently use a 4-digit numeric prefix convention
  (e.g., `0000.shared-functions.sql`, `0001.create-users.sql`) but any naming
  scheme that sorts correctly is acceptable.
- The schema MUST be append-only. Down migrations (rollbacks) are NEVER
  supported. Reversion requires a full `db-destroy` + `db-init` + `db-migrate`
  cycle and MUST only be performed in non-production environments.
- `bin/db-migrate` MUST execute each migration file inside its own isolated SQL
  transaction. The transaction MUST include both the DDL operations and the
  `schema_migrations` tracking-table update. A failure MUST roll back the
  entire transaction and MUST NOT apply subsequent files.
- `bin/db-migrate` MUST skip any migration whose `version_id` already exists in
  `schema_migrations`, making re-runs idempotent.

### Shared Functions (`0000.shared-functions.sql`)

- `update_timestamp()` sets `row_updated_at = NOW()` on every update. It also
  sets `updated_at = NOW()` unless the logical-timestamp bypass is active.
- **Logical-timestamp bypass**: Setting the session variable
  `unicoach.bypass_logical_timestamp` to `'true'` (via `SET LOCAL`) freezes
  `updated_at` while still advancing `row_updated_at`. The bypass is read via
  PostgreSQL's `current_setting(...)` two-argument form.
- `enforce_versioning()` raises `ERRCODE = '23514'` when an INSERT supplies a
  `version` other than `1`. It raises `ERRCODE = '40001'`
  (serialization_failure) when an UPDATE supplies a `version` that is not
  exactly `OLD.version + 1`.
- `prevent_physical_delete()` raises `ERRCODE = 'P0001'` on any DELETE trigger
  invocation.
- `prevent_immutable_updates()` raises `ERRCODE = 'P0001'` if an UPDATE
  attempts to change `id`, `created_at`, or `row_created_at`.

### Standard Entity Table Pattern

Every entity table starts from a base pattern:

- **Primary key**: `id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7()`.
- **Timestamps**: `created_at` and `updated_at`, both
  `TIMESTAMPTZ DEFAULT NOW() NOT NULL`.
- **Immutability guard**: `prevent_immutable_updates()` blocks changes to `id`
  and `created_at` (and `row_created_at` when present).
- **Trigger naming**: BEFORE triggers execute in alphabetical name order. The
  convention `trigger_00`, `trigger_00a`, `trigger_01`, ... MUST be preserved
  to guarantee correct execution sequence.

Entity tables enable additional capabilities via **mix-ins**:

- **Advanced timestamps**: Adds `row_created_at` and `row_updated_at` for
  distinguishing physical writes from logical mutations. Maintained by the
  `update_timestamp()` trigger (see §II, Logical-timestamp bypass).
- **OCC versioning**: Adds `version INTEGER NOT NULL DEFAULT 1`, enforced by
  `enforce_versioning()`. Provides optimistic concurrency control.
- **Version history**: Adds a sibling `{table}_versions` table with a
  `(id, version)` composite primary key and a `log_{table}_version()` AFTER
  trigger. Requires OCC versioning.
- **Logical deletes**: Adds `deleted_at TIMESTAMPTZ NULL`. Physical deletions
  are blocked by `prevent_physical_delete()`. Unique indexes MUST use partial
  predicates (`WHERE deleted_at IS NULL`).

#### Table Summary

| Table | Type | Adv. Timestamps | OCC Versioning | Version History | Logical Deletes |
|---|---|---|---|---|---|
| `users` | Entity | ✅ | ✅ | ✅ | ✅ |
| `users_versions` | Support | — | — | — | — |
| `sessions` | Entity | ✅ | ✅ | ❌ | ❌ |
| `jobs` | Non-entity | ❌ | ❌ | ❌ | ❌ |
| `job_attempts` | Non-entity | ❌ | ❌ | ❌ | ❌ |

### `users` — Extensions

- **Auth method**: Every row MUST have `password_hash IS NOT NULL` or
  `sso_provider_id IS NOT NULL` (`users_auth_method_check`).
- **String normalization**: `email` is lowercased and trimmed; `name` and
  `display_name` are trimmed — enforced by `trim_users_strings()` trigger and
  corresponding check constraints.
- **Email uniqueness**: Unique among active users via partial unique index
  (`users_email_unique_active_idx WHERE deleted_at IS NULL`).
- **Version history**: `users_versions` rows MUST NOT be physically deleted
  (`ON DELETE RESTRICT`).

### `sessions` — Extensions

- **Physical deletes permitted**: No `prevent_physical_delete()` trigger.
- **Token storage**: `token_hash` is `BYTEA` (SHA-256). The plain-text token
  is NEVER stored. Unique via `sessions_token_hash_idx`.
- **Anonymous sessions**: `user_id` is NULLABLE. `ON DELETE CASCADE` from
  `users` deletes associated sessions. Transitioning an anonymous session to
  authenticated MUST be done via application UPDATE (setting `user_id` and
  rotating `token_hash`).
- **Lifecycle**: Managed via `is_revoked` (boolean, default `false`) and
  `expires_at` (NOT NULL, indexed).

### `jobs` — Non-Standard Table

- Does NOT follow the standard entity pattern: no `version` column, no
  `row_created_at`, no `row_updated_at`, no `prevent_immutable_updates()`.
- `updated_at` is maintained by a local `update_jobs_timestamp()` trigger
  (simpler variant without the logical/physical split).
- `status` MUST be one of: `'SCHEDULED'`, `'RUNNING'`, `'COMPLETED'`,
  `'DEAD_LETTERED'`.
- `notify_jobs()` trigger emits `pg_notify('jobs_channel', NEW.job_type)`
  AFTER INSERT OR UPDATE when `NEW.status = 'SCHEDULED'`.

### `job_attempts` — Non-Standard Table

- Append-only record of job execution attempts. No triggers.
- `(job_id, attempt_number)` MUST be unique.
- `finished_at` defaults to `NOW()` at insert time; application code MUST NOT
  supply this value.
- `ON DELETE CASCADE` from `jobs`.

---

## III. Behavioral Contracts

### Trigger Functions

#### `update_timestamp()`

- **Trigger type**: `BEFORE UPDATE`
- **Expects columns**: `updated_at`, `row_updated_at`
- **Side effects**: Sets `NEW.row_updated_at = NOW()` on every invocation.
  Sets `NEW.updated_at = NOW()` unless the logical-timestamp bypass is active
  (see §II, Logical-timestamp bypass).
- **Error handling**: None — returns `NEW`.
- **Idempotency**: Not idempotent (timestamp advances on each call).

#### `enforce_versioning()`

- **Trigger type**: `BEFORE INSERT OR UPDATE`
- **Expects columns**: `version`
- **Side effects**: None on success; raises exception on violation.
- **Error handling**:
  - INSERT with `version != 1` → `ERRCODE 23514`
  - UPDATE with `version != OLD.version + 1` → `ERRCODE 40001`
    (serialization_failure)
- **Idempotency**: N/A — stateless validation.
- **OCC application requirement**: Application MUST supply the absolute next
  version number (e.g., `SET version = 2`). Relative SQL
  (`SET version = version + 1`) MUST NOT be used, as PostgreSQL evaluates it
  against the latest committed row in a race, bypassing the trigger check.

#### `prevent_physical_delete()`

- **Trigger type**: `BEFORE DELETE`
- **Side effects**: Always raises `ERRCODE P0001`.
- **Idempotency**: N/A.

#### `prevent_immutable_updates()`

- **Trigger type**: `BEFORE UPDATE`
- **Expects columns**: `id`, `created_at`, `row_created_at`
- **Side effects**: Raises `ERRCODE P0001` if any of the expected columns are
  changed.
- **Idempotency**: N/A.

#### `trim_users_strings()`

- **Trigger type**: `BEFORE INSERT OR UPDATE` on `users`
- **Side effects**: Normalizes `email` (lowercased + trimmed), `name`
  (trimmed), and `display_name` (trimmed if non-null).
- **Idempotency**: Yes — applying twice produces the same result.

#### `log_user_version()`

- **Trigger type**: `AFTER INSERT OR UPDATE` on `users`
- **Side effects**: Inserts one row into `users_versions` per triggering
  statement.
- **Error handling**: Failure raises a PostgreSQL exception; the parent
  transaction is rolled back.
- **Idempotency**: No — duplicate `(id, version)` violates the primary key.

#### `update_jobs_timestamp()`

- **Trigger type**: `BEFORE UPDATE` on `jobs`
- **Expects columns**: `updated_at`
- **Side effects**: Sets `NEW.updated_at = NOW()`.
- **Error handling**: None — returns `NEW`.
- **Idempotency**: Not idempotent (timestamp advances on each call).

#### `notify_jobs()`

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
- **`schema_migrations` tracking table**: The source of truth for the current
  state of the PostgreSQL schema. `bin/db-migrate` determines which migrations
  to apply by querying this table — it does not inspect the schema itself. This
  is why each migration and its `schema_migrations` update MUST execute in the
  same transaction (see §II, Global Schema Invariants). Created by `db-init`
  (not defined in this directory).
- **PostgreSQL version**: Schema relies on `pg_notify`, `TIMESTAMPTZ`,
  `JSONB`, `BYTEA`, and `uuidv7()`. Requires PostgreSQL 18 (provided by
  `pkgs.postgresql_18` in `flake.nix`).
- **`unicoach.bypass_logical_timestamp`**: See §II, Logical-timestamp bypass.

---

## V. History

- [x] [RFC-05: Database Scripts](../../rfc/05-db-scripts.md)
- [x] [RFC-06: Users Table](../../rfc/06-users-table.md)
- [x] [RFC-11: Sessions](../../rfc/11-sessions.md)
- [x] [RFC-14: Extract Database Module](../../rfc/14-db-module.md)
- [x] [RFC-15: Queue Data Layer](../../rfc/15-queue-data-layer.md)
- [x] [RFC-16: Queue Worker Framework](../../rfc/16-queue-worker-framework.md)
