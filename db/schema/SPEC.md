# SPEC: db/schema

## I. Overview

`db/schema/` is the append-only SQL migration layer for the Unicoach PostgreSQL
database. Each file defines DDL — tables, indexes, check constraints, unique
indexes, functions, and triggers — applied in lexicographical order by
`bin/db-migrate`, the sole consumer of this directory.

The schema is the primary enforcement layer for application data integrity:
constraints that can be expressed in DDL (check constraints, unique indexes,
foreign keys, NOT NULL) are defined in the database rather than deferred to
application code, and derived values are generated at the DDL level (primary
keys via `uuidv7()`, timestamps via `DEFAULT NOW()` or trigger) rather than
accepted from callers. PostgreSQL's clock is the source of truth for time;
application code reads point-in-time values from the database (`created_at`,
`updated_at`) rather than supplying them, with one documented exception
(`system_prompts.created_at` backfill).

Migrations insert application-level reference data (e.g. the seed
`system_prompts` catalog) but not user data; user data originates exclusively
from application usage.

---

## II. Behavioral Contracts

### Global Migration Behavior (`bin/db-migrate`)

- **Ordering**: Schema files apply in strict lexicographical order. Filenames
  follow `NNNN.kebab-case-name.sql` — a 4-digit numeric prefix, a dot, a
  lowercase kebab-case name (e.g. `0000.shared-functions.sql`). A non-conforming
  filename is rejected with a fatal error.
- **Append-only**: There are no down migrations (rollbacks). Reversion is a full
  `bin/db-reset` cycle (`db-drop` → `db-create` → `db-migrate`), used only in
  non-production environments.
- **Transactionality**: Each migration file runs inside its own SQL transaction,
  which encloses both the DDL and the `schema_migrations` tracking-table insert.
  A failure rolls back the whole transaction and no subsequent file is applied.
- **Idempotent re-run**: A migration whose `version_id` already exists in
  `schema_migrations` is skipped, so re-running `bin/db-migrate` is a no-op.

### Shared Trigger Functions

Defined in [`0000.shared-functions.sql`](./0000.shared-functions.sql) unless
noted otherwise. Each function is a stateless guard or mutator invoked by
per-table triggers; behavior is summarized here once and referenced by the table
subsections.

#### `update_timestamp()`

- **Trigger type**: `BEFORE UPDATE`. Expects columns `updated_at`,
  `row_updated_at`.
- **Side effects**: Sets `NEW.row_updated_at = NOW()` on every invocation. Sets
  `NEW.updated_at = NOW()` unless the logical-timestamp bypass is active.
- **Logical-timestamp bypass**: Setting the session variable
  `unicoach.bypass_logical_timestamp` to `'true'` (via `SET LOCAL`) freezes
  `updated_at` while still advancing `row_updated_at`. The value is read via
  `current_setting(...)`'s two-argument (missing-OK) form.
- **Error handling**: None — returns `NEW`. **Idempotency**: Not idempotent (the
  timestamp advances on each call).

#### `enforce_versioning()`

- **Trigger type**: `BEFORE INSERT OR UPDATE`. Expects column `version`.
- **Error handling**: An INSERT with `version != 1` raises `ERRCODE 23514`
  (check_violation). An UPDATE with `version != OLD.version + 1` raises
  `ERRCODE 40001` (serialization_failure).
- **OCC application contract**: Callers supply the absolute next version
  (`SET version = 2`). Relative SQL (`SET version = version + 1`) evaluates
  against the latest committed row under a race and bypasses the check, so it is
  not used. **Idempotency**: N/A — stateless validation.

#### `prevent_physical_delete()`

- **Trigger type**: `BEFORE DELETE`. Always raises `ERRCODE P0001`, enforcing
  soft-delete semantics on entity tables. **Idempotency**: N/A.

#### `prevent_immutable_updates()`

- **Trigger type**: `BEFORE UPDATE`. Expects columns `id`, `created_at`,
  `row_created_at`.
- **Side effects**: Raises `ERRCODE P0001` if an UPDATE changes any of those
  columns; all other columns remain mutable. **Idempotency**: N/A.

#### `prevent_log_update()` / `prevent_log_delete()`

Defined in [`0006`](./0006.create-coaching-conversations.sql) (not `0000`) via
idempotent `CREATE OR REPLACE`.

- **Trigger types**: `BEFORE UPDATE` and `BEFORE DELETE` respectively, attached
  to the five append-only log tables (`convo_requests`, `convo_responses`,
  `convo_responses_raw`, `email_sends`, `user_auth_identities`).
- **Side effects**: Always raise `ERRCODE P0001` ("Log rows are append-only and
  cannot be updated." / "Log rows cannot be deleted; prune by
  partition/retention."), enforcing the append-only contract. Distinct from
  `prevent_physical_delete()` — different message, append-only (not soft-delete)
  semantics. **Idempotency**: N/A.

#### `prevent_immutable_entity_update()` / `prevent_immutable_entity_delete()`

Defined in [`0007`](./0007.create-system-prompts.sql) (not `0000`) via
idempotent `CREATE OR REPLACE`.

- **Trigger types**: `BEFORE UPDATE` and `BEFORE DELETE` respectively, attached
  to `system_prompts`.
- **Side effects**: Always raise `ERRCODE P0001` ("Immutable entity rows cannot
  be updated." / "Immutable entity rows cannot be deleted."), enforcing the
  insert-only contract. The blanket UPDATE block covers every column (including
  domain columns), unlike `prevent_immutable_updates()`, which blocks only `id`,
  `created_at`, `row_created_at`. **Idempotency**: N/A.

#### `trim_users_strings()`

- **Trigger type**: `BEFORE INSERT OR UPDATE` on `users`.
- **Side effects**: Normalizes `email` (lowercased + trimmed), `name` (trimmed),
  and `display_name` (trimmed if non-null). **Idempotency**: Yes — applying
  twice yields the same result.

#### `log_user_version()`

- **Trigger type**: `AFTER INSERT OR UPDATE` on `users`.
- **Side effects**: Inserts one `users_versions` row per triggering statement,
  copying every domain column of `users` — including `is_admin` and
  `email_verified_at`. Adding a `users` domain column requires updating this
  function (and the `users_versions` column set) to copy it, or history silently
  drops it.
- **Error handling**: Failure raises a PostgreSQL exception; the parent
  transaction rolls back. **Idempotency**: No — a duplicate `(id, version)`
  violates the version table's primary key.

#### `log_student_version()`

- **Trigger type**: `AFTER INSERT OR UPDATE` on `students`.
- **Side effects**: Inserts one `students_versions` row per triggering
  statement, copying every `students` domain column.
- **Error handling**: Failure raises a PostgreSQL exception; the parent
  transaction rolls back. **Idempotency**: No — duplicate `(id, version)`
  violates the primary key.

#### `update_jobs_timestamp()`

- **Trigger type**: `BEFORE UPDATE` on `jobs`. Expects column `updated_at`.
- **Side effects**: Sets `NEW.updated_at = NOW()` (a simpler variant without the
  logical/physical split). **Idempotency**: Not idempotent.

#### `update_colleges_timestamp()`

- **Trigger type**: `BEFORE UPDATE` on `colleges` and `college_programs`
  (`trigger_03_enforce_{table}_updated_at`). Expects column `updated_at`.
- **Side effects**: Sets `NEW.updated_at = NOW()` — a plain `updated_at` advance
  with NO logical/physical split and no bypass support, mirroring
  `update_jobs_timestamp()`. Defined in [`0015`](./0015.create-colleges.sql)
  (not `0000`), shared by both `colleges` and `college_programs`. It is the
  reference-table sibling of `update_timestamp()`: those tables carry only
  logical timestamps, so no `row_updated_at` and no
  `unicoach.bypass_logical_timestamp` handling. **Idempotency**: Not idempotent
  (timestamp advances on each call).

#### `notify_jobs()`

- **Trigger type**: `AFTER INSERT OR UPDATE` on `jobs`.
- **Side effects**: Calls `pg_notify('jobs_channel', NEW.job_type)` when
  `NEW.status = 'SCHEDULED'`.
- **Error handling**: None — PostgreSQL propagates any internal error.
- **Idempotency**: NOTIFY delivery is best-effort and non-durable; consumers do
  not rely on exactly-once delivery.

### Table Patterns

Every entity table starts from a base pattern:

- **Primary key**: `id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7()`.
- **Timestamps**: `created_at` and `updated_at`, both
  `TIMESTAMPTZ DEFAULT NOW() NOT NULL`.
- **Immutability guard**: `prevent_immutable_updates()` blocks changes to `id`,
  `created_at` (and `row_created_at` when present).
- **Trigger naming**: BEFORE triggers execute in alphabetical name order; the
  convention `trigger_00`, `trigger_00a`, `trigger_01`, … fixes that order.

Entity tables compose additional capabilities via **mix-ins**:

- **Advanced timestamps**: Adds `row_created_at` and `row_updated_at` to
  distinguish physical writes from logical mutations, maintained by
  `update_timestamp()`.
- **OCC versioning**: Adds `version INTEGER NOT NULL DEFAULT 1`, enforced by
  `enforce_versioning()`.
- **Version history**: Adds a sibling `{table}_versions` table with an
  `(id, version)` composite primary key and a `log_{table}_version()` AFTER
  trigger. Requires OCC versioning. The versions table mirrors the entity's full
  domain column set, and the log function copies every such column into the
  history row.
- **Logical deletes**: Adds `deleted_at TIMESTAMPTZ NULL`; physical deletions
  are blocked by `prevent_physical_delete()`, and unique indexes use partial
  predicates (`WHERE deleted_at IS NULL`).

An entity table may instead be an **immutable (insert-only) variant**: created
once, never updated or deleted. It carries creation-only timestamps
(`created_at` + `row_created_at`, no `updated_at`/`row_updated_at`), no
`version`, and no `deleted_at`. `prevent_immutable_entity_update()` and
`prevent_immutable_entity_delete()` reject every mutation, making all columns
immutable; a "new version" is a new row with a new `id`.

**Log** tables are append-only: write-once, never updated or deleted, carrying
none of the entity mix-ins; their append-only contract is the
`prevent_log_update()` / `prevent_log_delete()` pair.

A **credential** table stores only the hash of a secret whose plaintext lives
outside the database (the cookie/email link). It carries neither the entity
mix-ins nor the log guards (`sessions`, `verification_tokens`).

A **reference** table (`colleges`, `college_programs`) holds externally-sourced
federal data (College Scorecard), mutated only by re-ingestion upsert on a
natural key, never by the application request flow. It carries only logical
`created_at`/`updated_at` (no physical/logical split, so no `row_created_at`/
`row_updated_at`) advanced by the plain `update_colleges_timestamp()` trigger,
and none of the four mix-ins: no OCC `version`/version history (the pinned
snapshot is the archive — history is not a guarantee), no soft-delete
(`deleted_at`), and no append-only log guards (rows are updated in place by
upsert). Physical deletes are permitted (no `prevent_physical_delete()`).

#### Table Summary

| Table                  | Type             | Adv. Timestamps | OCC Versioning | Version History | Logical Deletes |
| ---------------------- | ---------------- | --------------- | -------------- | --------------- | --------------- |
| `users`                | Entity           | ✅              | ✅             | ✅              | ✅              |
| `users_versions`       | Support          | —               | —              | —               | —               |
| `user_auth_identities` | Log              | —               | —              | —               | —               |
| `students`             | Entity           | ✅              | ✅             | ✅              | ✅              |
| `students_versions`    | Support          | —               | —              | —               | —               |
| `sessions`             | Credential       | ✅              | ✅             | ❌              | ❌              |
| `verification_tokens`  | Credential       | ❌              | ❌             | ❌              | ❌              |
| `jobs`                 | Non-entity       | ❌              | ❌             | ❌              | ❌              |
| `job_attempts`         | Non-entity       | ❌              | ❌             | ❌              | ❌              |
| `convos`               | Entity           | ✅              | ❌             | ❌              | ✅              |
| `convo_requests`       | Log              | —               | —              | —               | —               |
| `convo_responses`      | Log              | —               | —              | —               | —               |
| `convo_responses_raw`  | Log              | —               | —              | —               | —               |
| `system_prompts`       | Immutable Entity | —               | —              | —               | —               |
| `email_sends`          | Log              | —               | —              | —               | —               |
| `colleges`             | Reference        | ❌              | ❌             | ❌              | ❌              |
| `college_programs`     | Reference        | ❌              | ❌             | ❌              | ❌              |

### `users`

A standard entity (advanced timestamps + OCC versioning + version history +
logical deletes).

- **Auth credentials**: `password_hash` is nullable — a user authenticating only
  via a federated identity carries no password. There is no row-local CHECK
  requiring at least one credential; the federated credential lives in the
  separate `user_auth_identities` table, so "every user has at least one auth
  method" is enforced in application code (AuthService), not in DDL. The schema
  carries no `sso_provider_id` column (dropped in
  [`0017`](./0017.drop-users-sso-provider-id.sql), along with
  `users_auth_method_check` and the `sso_provider_id` length/non-empty checks
  and index); the federated credential is no longer a column on `users`.
- **String normalization**: `email` is lowercased and trimmed; `name` and
  `display_name` are trimmed — by the `trim_users_strings()` trigger plus
  matching check constraints.
- **Email uniqueness**: Unique among active users via the partial unique index
  `users_email_unique_active_idx WHERE deleted_at IS NULL`.
- **Email bounds/format**: `email` is ≤254 chars and contains `'@'`
  (`LIKE '%@%'`).
- **Admin privilege**: `is_admin BOOLEAN NOT NULL DEFAULT false` is the single
  source of truth for administrative authority. It is a normal mutable domain
  column — deliberately not covered by `prevent_immutable_updates()` — so
  granting or revoking admin is an ordinary in-place versioned UPDATE (a new
  `version`, not a new row), captured in `users_versions`.
- **Email verification**: `email_verified_at TIMESTAMPTZ NULL` records when a
  user's email was verified; `NULL` means unverified, and new users default to
  `NULL`. Like `is_admin`, it is a normal mutable domain column not covered by
  `prevent_immutable_updates()`, so marking a user verified is an ordinary
  versioned UPDATE captured in `users_versions`. `log_user_version()` copies it
  into history alongside the other domain columns.
- **Version history**: `users_versions` has the version mix-in. A `users` row is
  not physically deletable while `users_versions` rows cite it
  (`ON DELETE
  RESTRICT` on the version FK); the version rows carry no DB-level
  delete guard, so their preservation is a writer-side invariant.

### `user_auth_identities` — Append-Only Log

The log of federated (SSO) login identities. One immutable row records that a
federated `(provider, subject)` belongs to a user, established at a point in
time. Carries none of the entity mix-ins — no `version`, `updated_at`,
`deleted_at`, or versions table.

- **Append-only**: rows are inserted once, never updated or deleted.
  `prevent_log_update()` (`trigger_00_prevent_user_auth_identities_update`) and
  `prevent_log_delete()` (`trigger_01_prevent_user_auth_identities_delete`)
  raise `P0001`.
- **Identity**: `id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7()`.
- **Timestamps**: `created_at` (logical fact time) and `row_created_at`
  (physical insert time), both `TIMESTAMPTZ NOT NULL DEFAULT NOW()`. No
  `updated_at`/`row_updated_at`.
- **Ownership**:
  `user_id UUID NOT NULL REFERENCES users(id) ON DELETE
  CASCADE`, indexed by
  `user_auth_identities_user_id_idx`. The CASCADE is inert in practice — the
  parent `users` row is `prevent_physical_delete()`-protected and only ever
  soft-deleted — so it never fires.
- **One user per federated identity**:
  `user_auth_identities_provider_subject_idx` is a UNIQUE index on
  `(provider, subject)`. A federated identity maps to at most one user. It does
  NOT restrict a user to one identity per provider — distinct subjects may link
  to the same user.
- **Provider allowlist**: `provider TEXT NOT NULL` constrained to `'google'`
  (`user_auth_identities_provider_check`).
- **Subject bounds**: `subject TEXT NOT NULL`, ≤255 chars
  (`..._subject_length_check`) and non-empty after trim
  (`..._subject_not_empty_check`).
- **Email provenance only**: `email TEXT NOT NULL` and
  `email_verified BOOLEAN
  NOT NULL` capture the claims the provider asserted
  at row creation. They are never re-synced and never read on the login path —
  lookup is strictly by `(provider, subject)` — and carry no uniqueness or FK
  guarantee. `email` is bounded ≤254 (`..._email_length_check`), non-empty
  (`..._email_not_empty_check`), lowercase (`..._email_lowercase_check`,
  `email = LOWER(email)`), and contains `'@'` (`..._email_format_check`,
  `LIKE '%@%'`). There is no trim trigger; callers supply already-normalized
  values or the checks reject them.

### `students`

A standard entity (advanced timestamps + OCC versioning + version history +
logical deletes).

- **1:1 ownership**: `user_id UUID NOT NULL REFERENCES users(id)`; a user owns
  at most one student profile.
- **Total uniqueness on owner**: `students_user_id_unique_idx` is a total
  (non-partial) unique index on `user_id`, so a `user_id` cannot appear twice
  even across soft-deletes. There is no re-creation path: account deletion
  soft-deletes the owning user alongside the student, never freeing the
  `user_id`.
- **Variable-precision graduation date**: Three columns —
  `expected_high_school_graduation_year` (NOT NULL), `..._month`, `..._day`
  (both NULL) — admit exactly three precisions (year, year+month,
  year+month+day), enforced by three checks:
  - `grad_month_range`: month, when present, is `BETWEEN 1 AND 12`.
  - `grad_day_requires_month`: a non-null day requires a non-null month.
  - `grad_date_valid`: when a day is present, the (year, month, day) triple
    forms a real calendar date via the `IMMUTABLE` `make_date(...)`. An
    impossible date raises `ERRCODE 22008` (datetime_field_overflow), distinct
    from the `23514` raised by the other two checks.
- **Version history**: a `students` row is not physically deletable while
  `students_versions` rows cite it (`ON DELETE RESTRICT`); the version rows
  carry no DB-level delete guard (writer-side invariant).
- **No string normalization**: No free-text columns, so no trim trigger (unlike
  `users`).

### `sessions`

A credential table with advanced timestamps + OCC versioning, no version
history, no logical deletes.

- **Physical deletes permitted**: No `prevent_physical_delete()` trigger.
- **Token storage**: `token_hash BYTEA` holds the SHA-256 hash; the plaintext
  token is not stored. Unique via `sessions_token_hash_idx`.
- **Anonymous sessions**: `user_id` is nullable with `ON DELETE CASCADE` from
  `users`. `user_id` is updatable, so an anonymous session can transition to
  authenticated via UPDATE; the schema does not constrain that transition
  further.
- **Login method**: `login_method TEXT NULL` records how a user-bound session
  authenticated, constrained to `'password'` or `'google'`
  (`sessions_login_method_check`). It is paired to `user_id` by
  `sessions_login_method_presence_check`
  (`(user_id IS NULL) = (login_method IS
  NULL)`): NULL exactly for anonymous
  sessions, non-null for every user-bound session, so an authenticated session
  can never lack a method and an anonymous one can never carry one. When an
  anonymous session transitions to authenticated, the UPDATE that sets `user_id`
  also sets `login_method` to satisfy the pairing.
- **Lifecycle**: `is_revoked` (boolean, default `false`) and `expires_at` (NOT
  NULL, indexed via `sessions_expires_at_idx`).
- **Bounds**: `user_agent` ≤512 chars, `initial_ip` ≤64 chars, `metadata` ≤2048
  bytes (via `pg_column_size`) — all CHECK-enforced.

### `verification_tokens`

A single-use email-verification credential table (migration
[`0014`](./0014.create-verification-tokens.sql)). Modeled on `sessions` (a
hashed credential), not on the entity pattern: no `version` column, no
`_versions` history, no `updated_at`/`row_updated_at`, no logical-delete or
physical-delete guard, and no purpose/type column.

- **Identity**: `id UUID PRIMARY KEY DEFAULT uuidv7()`.
- **Creation timestamps**: `created_at` and `row_created_at`, both
  `TIMESTAMPTZ NOT NULL DEFAULT NOW()`. No mutation-tracking timestamps.
- **Ownership**: `user_id UUID NOT NULL REFERENCES users(id)` (plain FK, no
  `ON DELETE` clause, so the default `NO ACTION` applies — a `users` row with
  outstanding tokens is not physically deletable). Indexed via
  `verification_tokens_user_id_idx` for per-user lookups.
- **Token storage**: `token_hash BYTEA NOT NULL` holds only the SHA-256 hash of
  the raw token; the raw token exists solely in the email link and is not
  stored. Unique via `verification_tokens_token_hash_idx`.
- **Expiry**: `expires_at TIMESTAMPTZ NOT NULL` bounds the token's validity
  window. The schema stores the deadline; expiry comparison is the caller's.
- **Single-use consumption**: `consumed_at TIMESTAMPTZ NULL` is the single-use
  marker, `NULL` until verification. The schema does not itself enforce
  exactly-once consumption; the consuming code performs a compare-and-swap
  UPDATE (set `consumed_at` only when currently `NULL`) to make consumption
  single-use.

### `jobs`

Non-standard table: no `version`, no `row_created_at`, no `row_updated_at`, no
`prevent_immutable_updates()`.

- **Timestamps**: `updated_at` maintained by the local `update_jobs_timestamp()`
  trigger.
- **Status**: one of `'SCHEDULED'`, `'RUNNING'`, `'COMPLETED'`,
  `'DEAD_LETTERED'`.
- **Notification**: the `notify_jobs()` trigger emits
  `pg_notify('jobs_channel', NEW.job_type)` AFTER INSERT OR UPDATE when
  `NEW.status = 'SCHEDULED'`.
- **Bounds**: `job_type` ≤128 chars; `payload` ≤64 KiB — both CHECK-enforced.

### `job_attempts`

Append-only record of job execution attempts; no triggers.

- **Uniqueness**: `(job_id, attempt_number)` is unique.
- **Status**: one of `'SUCCESS'`, `'RETRIABLE_FAILURE'`, `'PERMANENT_FAILURE'`
  (`job_attempts_status_valid_check`).
- **Timestamps**: `finished_at` defaults to `NOW()` at insert; callers do not
  supply it.
- **Cascade**: `ON DELETE CASCADE` from `jobs`.
- **Bounds**: `error_message` ≤4096 chars (CHECK-enforced).

### `convos`

The entity-that-owns-logs: a standard entity (advanced timestamps + logical
deletes) with OCC versioning and version history disabled, owning three
append-only log children.

- **Ownership**:
  `student_id UUID NOT NULL REFERENCES students(id) ON DELETE
  CASCADE` — a
  student owns many conversations (1:many). The CASCADE is inert while
  `prevent_physical_delete()` blocks physical deletes; it wires the future
  physical-erasure path.
- **Advanced timestamps**: full 4-timestamp pattern, maintained by
  `update_timestamp()`.
- **Logical deletes**: `deleted_at TIMESTAMPTZ NULL`; physical deletes blocked
  by `prevent_physical_delete()`. The active-list index `convos_student_id_idx`
  is partial (`WHERE deleted_at IS NULL`).
- **Archive state**: `archived_at TIMESTAMPTZ NULL` (non-null = archived). It is
  mutable — `prevent_immutable_updates()` deliberately does not cover it — so
  archive/unarchive is an in-place UPDATE. The archive axis is independent of
  `deleted_at` (archiving reversible, soft-delete terminal; the two compose
  freely); no separate `archived_at` index exists.
- **Versioning disabled**: no `version` column, no `enforce_versioning()`, no
  `convos_versions` sibling — the transcript logs are the authoritative history.
- **`name` mandatory & canonical**: `name TEXT NOT NULL` with no default. Stored
  trimmed and non-empty: `convos_name_trimmed_check` (`name = trim(name)`),
  `convos_name_not_empty_check` (`length(trim(name)) > 0`),
  `convos_name_length_check` (`length(name) <= 255`). There is no trim trigger,
  so a non-trimmed value is rejected rather than normalized.

### `convo_requests` — Append-Only Log

- **Append-only**: `prevent_log_update()` (`trigger_00`) and
  `prevent_log_delete()` (`trigger_01`) raise `P0001`.
- **Identity / ordering**: `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`
  (internal, monotonic). Replayed via `convo_requests_convo_id_created_at_idx`
  on `(convo_id, created_at)`.
- **Ownership**:
  `convo_id UUID NOT NULL REFERENCES convos(id) ON DELETE
  CASCADE`. One row =
  one turn sent to the model.
- **System-prompt pin**:
  `system_prompt_id UUID NOT NULL REFERENCES
  system_prompts(id) ON DELETE RESTRICT`.
  Each turn pins the exact immutable prompt that produced it; because the
  referenced row is immutable, the `id` resolves forever to the precise
  `(name, version, body)` sent. `RESTRICT` (vs the child-ward `CASCADE` of the
  other `convo_*` FKs) protects the shared parent: a `system_prompts` row is not
  deletable while any turn cites it.
- **Provider allowlist**: `provider` is TEXT + CHECK
  (`provider IN ('anthropic', 'log')`), pinned per-turn — not a native pg enum.
  `'log'` is the chat module's no-network stub adapter, so dev/test turns are
  recorded with visibly synthetic provenance. Widening reuses the constraint
  name `convo_requests_provider_valid_check` (drop + re-add).
- **Content opaque & bounded**: `content JSONB NOT NULL`, the new user input;
  the DB does not constrain its internal shape. Bounded to 1 MiB
  (`octet_length(content::text) <= 1048576`). Prior history is not re-stored per
  row (the stateless API resends it each turn).
- **`request_params`**: `JSONB NULL`; when present, a JSON object
  (`jsonb_typeof = 'object'`).
- **Free-text**: `model_requested` is NOT NULL, ≤255, trimmed, non-empty.

### `convo_responses` — Append-Only Log

- **Append-only**: `prevent_log_update()` / `prevent_log_delete()` guards.
- **1:1 with request**:
  `request_id BIGINT NOT NULL UNIQUE REFERENCES
  convo_requests(id) ON DELETE CASCADE`
  — exactly one response per request.
- **Errors recorded, never dropped**: a failed turn is a row with
  `stop_reason = 'error'`. `convo_responses_content_presence_check`
  (`content IS NOT NULL OR stop_reason = 'error'`) and
  `convo_responses_model_presence_check` (same for `model_resolved`) make
  content and model present on success and NULL only on error.
- **Denormalized `convo_id` (writer-side invariant)**: `convo_id` is duplicated
  from the parent request for replay. The DB guarantees both `request_id` and
  `convo_id` reference valid rows but not that they agree; the equality is a
  writer-side invariant (no cross-row CHECK).
- **Numeric sanity**: `input_tokens`, `output_tokens`, `cache_read_tokens`,
  `cache_write_tokens`, `latency_ms` are nullable and, when present, `>= 0`.
- **Free-text**: `stop_reason` NOT NULL, ≤64, trimmed, non-empty;
  `model_resolved` and `provider_request_id` nullable but, when present, ≤255,
  trimmed, non-empty (NULL means absent; `''` is illegal).

### `convo_responses_raw` — Append-Only Log

- **Append-only**: `prevent_log_update()` / `prevent_log_delete()` guards.
- **At-most-one per response**:
  `response_id BIGINT NOT NULL PRIMARY KEY
  REFERENCES convo_responses(id) ON DELETE CASCADE`
  — the FK is the PK, so each response has zero or one raw rows (a
  transport-failure response has zero).
- **Payload**: `payload JSONB NOT NULL`, the verbatim provider response body,
  isolated so it can be archived/dropped later without rewriting hot
  `convo_responses` rows.

### `system_prompts` — Immutable Entity

The team-authored catalog of system prompts that shape every coaching turn,
FK'd-to by `convo_requests`. Insert-only: a row is created once and never
updated or deleted; a "new version" is a new row with a new `id`, so there is no
`version` OCC column, no `system_prompts_versions` table, and no log trigger.

- **Immutability**: every UPDATE is rejected by
  `prevent_immutable_entity_update()` (`trigger_00`), every DELETE by
  `prevent_immutable_entity_delete()` (`trigger_01`), both raising `P0001`. The
  blanket UPDATE block makes `id`, `created_at`, `row_created_at` immutable with
  no separate guard.
- **Creation-only timestamps**: `created_at` (logical authoring time) and
  `row_created_at` (physical insert time), both
  `TIMESTAMPTZ NOT NULL DEFAULT
  NOW()`; no `updated_at`/`row_updated_at`.
  `created_at` may be supplied explicitly to backfill a prompt's authoring date
  — the one documented exception to §I's clock-authority behavior — in which
  case it reads back earlier than the defaulted `row_created_at`.
- **`(name, version)` uniqueness**: `system_prompts_name_version_unique` — a
  `(name, version)` pair maps to exactly one immutable `body` forever. `name` is
  the logical family (e.g. `coach`); `version` is a plain immutable label (e.g.
  `v1`), not an OCC counter. The composite index's leading `name` column also
  serves "all versions of a family" lookups, so there is no separate secondary
  index.
- **`name` / `version` canonical**: both NOT NULL, ≤255, non-empty, trimmed (six
  named CHECKs).
- **`body` bounded, not trimmed**: `body TEXT NOT NULL`, non-empty
  (`system_prompts_body_not_empty_check`) and ≤1 MiB
  (`system_prompts_body_size_check`), deliberately not trimmed — it is the
  verbatim artifact sent to the model, so leading/trailing whitespace is
  preserved as authored.
- **Seeded catalog**: the table is seeded with the first prompt (`name='coach'`,
  `version='v1'`) carrying architect-approved body copy (migration
  [`0011`](./0011.seed-coach-system-prompt.sql)). The seed is application-level
  reference data (§I) and, being an immutable-entity row, is itself immutable: a
  revised coach prompt is a new `coach/v2` row, leaving `coach/v1` — and every
  `convo_requests` turn that pinned it — intact.

### `email_sends` — Append-Only Log

The append-only ledger of terminal transactional-email outcomes: one immutable
row per resolved send, recording terminal outcomes only — a successful send
(`SENT`) or a permanent rejection (`REJECTED`). Transient failures are not
logged here (retry is the queue's domain, `job_attempts`).

- **Append-only**: `prevent_log_update()` (`trigger_00`) and
  `prevent_log_delete()` (`trigger_01`) raise `P0001`. A corrected outcome is a
  new row, never an in-place edit.
- **Identity**: `id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7()` — time-ordered
  because the id is externally surfaced, unlike the internal-only `BIGINT`
  identity of the `convo_*` logs.
- **Ordered replay**: replay order is `ORDER BY created_at, id`; `created_at`
  carries the meaning and the time-ordered `id` is the deterministic tiebreaker.
  No stream FK and no per-stream `seq` — a single flat ledger.
- **Timestamps**: `created_at` (logical send time) and `row_created_at`
  (physical insert time), `TIMESTAMPTZ NOT NULL DEFAULT NOW()`; no
  `updated_at`/`row_updated_at` (write-once).
- **Status domain (terminal-only)**:
  `status TEXT NOT NULL CHECK (status IN
  ('SENT', 'REJECTED'))`.
- **Outcome-conditional fields (writer-side invariant)**: `provider_message_id`
  (set when `SENT`) and `error_message` (set when `REJECTED`) are both nullable;
  the DB does not cross-check either against `status`.
- **Captured envelope**: `recipient_email`, `sender_email`, `subject`, `body`
  are all NOT NULL — the message is captured verbatim so the record is
  self-contained for audit. `body` is the plain-text body.
- **Provider provenance**: `provider TEXT NOT NULL` pins the adapter identity
  (e.g. log-only); deliberately not constrained by CHECK or enum (open-ended
  adapter set), unlike the closed allowlist on `convo_requests`.

### `colleges` — Reference Table

Curated institution-level College Scorecard data (RFC 67), defined in
[`0015.create-colleges.sql`](./0015.create-colleges.sql). A reference table (see
the Reference type note above): bulk-upserted, no entity mix-ins.

- **Surface id + natural key**:
  `id UUID NOT NULL PRIMARY KEY DEFAULT
  uuidv7()` is the project-convention
  DB-generated surface id; `unit_id INTEGER NOT NULL` is the federal `UNITID`
  natural key and the upsert target, with a SEPARATE
  `colleges_unit_id_unique_idx` (UNIQUE) — the PK is not the upsert key.
- **Logical timestamps only**: `created_at` + `updated_at`
  (`TIMESTAMPTZ NOT NULL DEFAULT NOW()`); `updated_at` is advanced on upsert by
  the `trigger_03_enforce_colleges_updated_at` `BEFORE UPDATE` trigger
  (`update_colleges_timestamp()`). No `row_created_at`/`row_updated_at`.
- **Coded categoricals (range-checked, nullable where the source is)**:
  `control SMALLINT NOT NULL` ∈ {1 public, 2 private-nonprofit, 3 for-profit}
  (`colleges_control_valid_check`); `region` 0–9 and `locale` 11–43 when
  present.
- **Rate columns**: `admission_rate`, `graduation_rate`, `pct_pell` are
  `DOUBLE PRECISION` and, when present, `BETWEEN 0 AND 1`.
- **Coalesced net price**: `net_price` is a single average-net-price column —
  the loader writes `NPT4_PUB` for public (`control = 1`) else `NPT4_PRIV`.
- **Non-negative integer metrics**: `undergrad_enrollment`, `sat_avg`,
  `cost_attendance`, `net_price`, `tuition_in_state`, `tuition_out_state`,
  `median_earnings` are each `>= 0` when present.
- **TEXT bounds**: `name`/`city` NOT NULL, trimmed, non-empty, ≤255; `state` is
  exactly 2 chars (`colleges_state_length_check`, `length(state) = 2`);
  `website`/`opeid` ≤255 when present.
- **Filter-supporting indexes**: `colleges_state_idx`, `colleges_control_idx`,
  `colleges_undergrad_enrollment_idx`, `colleges_admission_rate_idx`,
  `colleges_net_price_idx`, `colleges_graduation_rate_idx` back the
  `CollegesDao.search` range/equality filters.
- **Physical deletes permitted**: no `prevent_physical_delete()` trigger;
  `college_programs` cascades on a parent delete.

### `college_programs` — Reference Table

CIP program offerings per institution (full 6-digit CIP granularity), defined in
the same migration. Enables "offers marine biology" filtering finer than the
institution-level 2-digit `PCIP` families.

- **Ownership**:
  `college_id UUID NOT NULL REFERENCES colleges(id) ON DELETE
  CASCADE` — a
  college owns many programs; deleting a college removes its programs.
- **CIP code**: `cip_code TEXT NOT NULL`, a 6-character digit string enforced by
  `college_programs_cip_code_format_check (cip_code ~ '^[0-9]{6}$')`. Prefix
  matching at query time (`cip_code LIKE prefix || '%'`) lets a 2/4/6-digit
  prefix all resolve.
- **`cip_title`**: NOT NULL, non-empty, ≤255.
- **`credential_level SMALLINT NOT NULL`**: the Scorecard `CREDLEV`, always
  present in the field-of-study source, `BETWEEN 1 AND 8`. Declared NOT NULL so
  the upsert key needs no `COALESCE` sentinel.
- **Upsert key**: `college_programs_unique_idx` UNIQUE
  `(college_id, cip_code, credential_level)` — the conflict target for
  `upsertProgram`.
- **Lookup indexes**: `college_programs_cip_code_idx` (program-prefix lookups)
  and `college_programs_college_id_idx` (the join from a matched college).
- **Timestamps/trigger**: same logical-only `created_at`/`updated_at` +
  `trigger_03_enforce_college_programs_updated_at` (reusing
  `update_colleges_timestamp()`), carried for uniformity with `colleges`; no
  reader consumes them — the table is bulk-upserted only.

---

## III. Infrastructure & Environment

- **`DB_SCHEMA_DIR`** environment variable: Consumed by the `db-migrate` shell
  script. Defaults to `$PROJECT_ROOT/db/schema`. Test suites override it to an
  isolated temp directory to avoid cross-contamination.
- **`schema_migrations` tracking table**: The source of truth for the applied
  state of the schema. `bin/db-migrate` decides which migrations to apply by
  querying this table — it does not inspect the schema itself — which is why
  each migration and its `schema_migrations` insert run in the same transaction
  (see §II, Global Migration Behavior). Created by `bin/db-create` (not defined
  in this directory).
- **PostgreSQL version**: The schema relies on `pg_notify`, `TIMESTAMPTZ`,
  `JSONB`, `BYTEA`, `BIGINT GENERATED ALWAYS AS IDENTITY`, and `uuidv7()`, which
  is native to PostgreSQL 18 (no extension required) and absent from earlier
  servers. PostgreSQL 18 is provided by `pkgs.postgresql_18` in `flake.nix`.
- **`unicoach.bypass_logical_timestamp`** session variable: See §II,
  `update_timestamp()` logical-timestamp bypass.

---

## IV. History

- [x] [RFC-05: Database Scripts](../../rfc/05-db-scripts.md)
- [x] [RFC-06: Users Table](../../rfc/06-users-table.md)
- [x] [RFC-11: Sessions](../../rfc/11-sessions.md)
- [x] [RFC-15: Queue Data Layer](../../rfc/15-queue-data-layer.md)
- [x] [RFC-16: Queue Worker Framework](../../rfc/16-queue-worker-framework.md)
- [x] [RFC-31: Student Profile](../../rfc/31-student-profile.md)
- [x] [RFC-32: Coaching Conversations](../../rfc/32-coaching-conversations.md)
- [x] [RFC-33: System Prompts](../../rfc/33-system-prompts.md)
- [x] [RFC-34: Transactional Email Service](../../rfc/34-transactional-email-service.md)
- [x] [RFC-43: Provider-Agnostic LLM Chat Provider](../../rfc/43-chat-provider.md)
- [x] [RFC-45: Coaching Service and Conversation REST Surface](../../rfc/45-coaching-service.md)
- [x] [RFC-60: Admin Website](../../rfc/60-admin-website.md)
- [x] [RFC-64: Google SSO Login](../../rfc/64-google-sso-login.md)
- [x] [RFC-65: Email Verification](../../rfc/65-email-verification.md)
- [x] [RFC-67: College Knowledge](../../rfc/67-college-knowledge.md) — Added the
      `colleges` and `college_programs` reference tables (curated College
      Scorecard data) in `0015.create-colleges.sql`, the first non-entity,
      non-log mutable reference tables. Introduced `update_colleges_timestamp()`
      (plain `updated_at` advance, no logical/physical split) and the
      `trigger_03_enforce_{table}_updated_at` triggers; the "Reference" table
      type (logical timestamps only, bulk-upserted on a natural key, no OCC/
      version-history/soft-delete/log guards, physical deletes permitted).
