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
the DDL level (e.g., primary keys via `uuidv7()`, timestamps via `DEFAULT NOW()`
or trigger) rather than accepting them from application code.

PostgreSQL's clock is the single source of truth for time in the system.
Application code MUST NOT supply its own point-in-time values (e.g.,
`created_at` or `updated_at`), but MAY perform time arithmetic on values
retrieved from PostgreSQL.

---

## II. Invariants

### Global Schema Invariants

- `bin/db-migrate` MUST apply schema files in strict lexicographical order.
  Files currently use a 4-digit numeric prefix convention (e.g.,
  `0000.shared-functions.sql`, `0001.create-users.sql`) but any naming scheme
  that sorts correctly is acceptable.
- The schema MUST be append-only. Down migrations (rollbacks) are NEVER
  supported. Reversion requires a full `db-destroy` + `db-init` + `db-migrate`
  cycle and MUST only be performed in non-production environments.
- `bin/db-migrate` MUST execute each migration file inside its own isolated SQL
  transaction. The transaction MUST include both the DDL operations and the
  `schema_migrations` tracking-table update. A failure MUST roll back the entire
  transaction and MUST NOT apply subsequent files.
- `bin/db-migrate` MUST skip any migration whose `version_id` already exists in
  `schema_migrations`, making re-runs idempotent.

### Shared Functions

Defined in `0000.shared-functions.sql` unless noted otherwise.

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
- `prevent_immutable_updates()` raises `ERRCODE = 'P0001'` if an UPDATE attempts
  to change `id`, `created_at`, or `row_created_at`.
- `prevent_log_update()` raises `ERRCODE = 'P0001'` on any UPDATE trigger
  invocation, enforcing the append-only contract on log tables. Defined in
  `0006` (not `0000`), via idempotent `CREATE OR REPLACE`. Distinct from
  `prevent_physical_delete()` — different message, append-only (not soft-delete)
  semantics.
- `prevent_log_delete()` raises `ERRCODE = 'P0001'` on any DELETE trigger
  invocation. Defined in `0006` (not `0000`).
- `prevent_immutable_entity_update()` raises `ERRCODE = 'P0001'` on any UPDATE
  trigger invocation, enforcing the insert-only contract on immutable-entity
  tables. Defined in `0007` (not `0000`), via idempotent `CREATE OR REPLACE`.
  Distinct from `prevent_immutable_updates()`: that guard blocks only `id`,
  `created_at`, and `row_created_at`, leaving domain columns mutable; this one
  blocks every update, including domain columns.
- `prevent_immutable_entity_delete()` raises `ERRCODE = 'P0001'` on any DELETE
  trigger invocation. Defined in `0007` (not `0000`).

### Standard Entity Table Pattern

Every entity table starts from a base pattern:

- **Primary key**: `id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7()`.
- **Timestamps**: `created_at` and `updated_at`, both
  `TIMESTAMPTZ DEFAULT NOW() NOT NULL`.
- **Immutability guard**: `prevent_immutable_updates()` blocks changes to `id`
  and `created_at` (and `row_created_at` when present).
- **Trigger naming**: BEFORE triggers execute in alphabetical name order. The
  convention `trigger_00`, `trigger_00a`, `trigger_01`, ... MUST be preserved to
  guarantee correct execution sequence.

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

An entity table MAY instead be an **immutable (insert-only) variant**: created
once, never updated or deleted. It carries creation-only timestamps —
`created_at` and `row_created_at`, no `updated_at`/`row_updated_at` — no
`version`, and no `deleted_at`. A `BEFORE UPDATE` trigger
(`prevent_immutable_entity_update()`) and a `BEFORE DELETE` trigger
(`prevent_immutable_entity_delete()`) reject every mutation, making all columns
— domain fields included — immutable. A "new version" is a new row with a new
`id`, never an in-place edit.

#### Table Summary

| Table                 | Type             | Adv. Timestamps | OCC Versioning | Version History | Logical Deletes |
| --------------------- | ---------------- | --------------- | -------------- | --------------- | --------------- |
| `users`               | Entity           | ✅              | ✅             | ✅              | ✅              |
| `users_versions`      | Support          | —               | —              | —               | —               |
| `students`            | Entity           | ✅              | ✅             | ✅              | ✅              |
| `students_versions`   | Support          | —               | —              | —               | —               |
| `sessions`            | Entity           | ✅              | ✅             | ❌              | ❌              |
| `jobs`                | Non-entity       | ❌              | ❌             | ❌              | ❌              |
| `job_attempts`        | Non-entity       | ❌              | ❌             | ❌              | ❌              |
| `convos`              | Entity           | ✅              | ❌             | ❌              | ✅              |
| `convo_requests`      | Log              | —               | —              | —               | —               |
| `convo_responses`     | Log              | —               | —              | —               | —               |
| `convo_responses_raw` | Log              | —               | —              | —               | —               |
| `system_prompts`      | Immutable Entity | —               | —              | —               | —               |
| `email_sends`         | Log              | —               | —              | —               | —               |

**Log** tables are append-only (see the `convo_*` subsections below):
write-once, never updated or deleted, carrying none of the entity mix-ins.

**Immutable-entity** tables (`system_prompts`) are insert-only: created once,
never updated or deleted. They carry creation-only timestamps (`created_at` +
`row_created_at`) and none of the four mix-ins above — see the `system_prompts`
subsection.

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

### `students` — Extensions

- **1:1 ownership**: `user_id UUID NOT NULL REFERENCES users(id)`. A user owns
  at most one student profile.
- **Total uniqueness on owner**: `students_user_id_unique_idx` is a **total**
  (non-partial) unique index on `user_id` — a `user_id` MUST NOT appear twice
  even across soft-deletes. There is no legitimate re-creation path: account
  deletion soft-deletes the owning user alongside the student, never freeing the
  `user_id` for re-use.
- **Variable-precision graduation date**: Modeled as three columns —
  `expected_high_school_graduation_year` (NOT NULL), `..._month` and `..._day`
  (both NULL) — admitting exactly three precisions: year, year+month, or
  year+month+day. Enforced by three check constraints:
  - `grad_month_range`: month, when present, MUST be `BETWEEN 1 AND 12`.
  - `grad_day_requires_month`: a non-null day REQUIRES a non-null month (no
    day-without-month precision).
  - `grad_date_valid`: when a day is present, the (year, month, day) triple MUST
    form a real calendar date. Verified via `make_date(...)`, which is
    `IMMUTABLE` and therefore legal in a CHECK. An impossible date (e.g. Feb 31,
    Feb 29 in a non-leap year) raises `ERRCODE 22008` (datetime_field_overflow),
    distinct from the `23514` (check_violation) raised by the other two
    constraints.
- **Version history**: `students_versions` rows MUST NOT be physically deleted
  (`ON DELETE RESTRICT`).
- **No string normalization**: No free-text columns, so no trim trigger is
  attached (unlike `users`).

### `sessions` — Extensions

- **Physical deletes permitted**: No `prevent_physical_delete()` trigger.
- **Token storage**: `token_hash` is `BYTEA` (SHA-256). The plain-text token is
  NEVER stored. Unique via `sessions_token_hash_idx`.
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
- `notify_jobs()` trigger emits `pg_notify('jobs_channel', NEW.job_type)` AFTER
  INSERT OR UPDATE when `NEW.status = 'SCHEDULED'`.

### `job_attempts` — Non-Standard Table

- Append-only record of job execution attempts. No triggers.
- `(job_id, attempt_number)` MUST be unique.
- `finished_at` defaults to `NOW()` at insert time; application code MUST NOT
  supply this value.
- `ON DELETE CASCADE` from `jobs`.

### `convos` — Extensions

`convos` is the **entity-that-owns-logs**: a standard entity (advanced
timestamps + logical deletes) with OCC versioning and version history
**disabled**, owning three append-only log children (`convo_requests`,
`convo_responses`, `convo_responses_raw`).

- **Ownership**:
  `student_id UUID NOT NULL REFERENCES students(id) ON DELETE
  CASCADE`. A
  student owns many conversations (1:many). The CASCADE is inert while
  `prevent_physical_delete()` blocks physical deletes; it wires the future
  physical-erasure path.
- **Advanced timestamps**: full 4-timestamp pattern (`created_at`,
  `row_created_at`, `updated_at`, `row_updated_at`), maintained by
  `update_timestamp()`.
- **Logical deletes**: `deleted_at TIMESTAMPTZ NULL`; physical deletes blocked
  by `prevent_physical_delete()`. The active-list index `convos_student_id_idx`
  is partial (`WHERE deleted_at IS NULL`).
- **Versioning disabled**: no `version` column, no `enforce_versioning()`, no
  `convos_versions` sibling — the transcript logs are the authoritative history.
- **`name` mandatory & canonical**: `name TEXT NOT NULL` with **no default** —
  every conversation MUST be created with an explicit name. Stored trimmed and
  non-empty: `convos_name_trimmed_check` (`name = trim(name)`),
  `convos_name_not_empty_check` (`length(trim(name)) >
  0`),
  `convos_name_length_check` (`length(name) <= 255`). There is no trim trigger;
  callers MUST supply already-trimmed values or the check rejects them.

### `convo_requests` — Append-Only Log

- **Append-only**: rows MUST NOT be updated or deleted. `prevent_log_update()`
  (`trigger_00`) and `prevent_log_delete()` (`trigger_01`) raise `P0001`.
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
  `(name, version, body)` sent. `RESTRICT` (not the child-ward `CASCADE` of the
  other `convo_*` FKs): the pin points at a shared parent, so a `system_prompts`
  row MUST NOT be deleted while any turn cites it.
- **Provider allowlist**: `provider` is TEXT + CHECK
  (`provider IN ('anthropic')`), pinned per-turn — NOT a native pg enum. Extend
  the allowlist in a later migration as providers are added.
- **Content opaque & bounded**: `content JSONB NOT NULL`, the new user input.
  The DB MUST NOT constrain its internal shape. Bounded to 1 MiB
  (`octet_length(content::text) <= 1048576`). Prior history is deliberately NOT
  re-stored per row (the stateless API resends it each turn).
- **`request_params`**: `JSONB NULL`; when present MUST be a JSON object
  (`jsonb_typeof = 'object'`).
- **Free-text**: `model_requested` is NOT NULL, ≤255, trimmed, and non-empty.

### `convo_responses` — Append-Only Log

- **Append-only**: `prevent_log_update()` / `prevent_log_delete()` guards, same
  as `convo_requests`.
- **1:1 with request**:
  `request_id BIGINT NOT NULL UNIQUE REFERENCES
  convo_requests(id) ON DELETE CASCADE`
  — exactly one response per request.
- **Errors are recorded, never dropped**: a failed turn is stored as a row with
  `stop_reason = 'error'`. `convo_responses_content_presence_check`
  (`content IS NOT NULL OR stop_reason = 'error'`) and
  `convo_responses_model_presence_check` (same for `model_resolved`) guarantee
  content and model are present on success and NULL only on error.
- **Denormalized `convo_id` (writer-side invariant)**: `convo_id` is duplicated
  from the parent request for replay. The DB guarantees both `request_id` and
  `convo_id` reference valid rows but NOT that they agree;
  `convo_responses.convo_id = (request_id → convo_requests.convo_id)` is a
  writer-side invariant (no cross-row CHECK).
- **Numeric sanity**: `input_tokens`, `output_tokens`, `cache_read_tokens`,
  `cache_write_tokens`, and `latency_ms` are nullable and, when present, MUST be
  `>= 0`.
- **Free-text**: `stop_reason` NOT NULL, ≤64, trimmed, non-empty;
  `model_resolved` and `provider_request_id` are nullable but, when present,
  ≤255, trimmed, non-empty (NULL means absent; `''` is illegal).

### `convo_responses_raw` — Append-Only Log

- **Append-only**: `prevent_log_update()` / `prevent_log_delete()` guards.
- **At-most-one per response**:
  `response_id BIGINT NOT NULL PRIMARY KEY
  REFERENCES convo_responses(id) ON DELETE CASCADE`
  — the FK is the PK, so each response has zero or one raw rows. A
  transport-failure response legitimately has zero.
- **Payload**: `payload JSONB NOT NULL` — the verbatim provider response body,
  isolated so it can be archived/dropped later without rewriting hot
  `convo_responses` rows.

### `system_prompts` — Immutable Entity

The team-authored catalog of system prompts that shape every coaching turn,
FK'd-to by `convo_requests`. Insert-only (see §II, immutable variant): a row is
created once and never updated or deleted. A "new version" of a prompt is a new
row with a new `id`, so there is no `version` OCC column, no `system_prompts`
sibling-versions table, and no log trigger.

- **Immutability**: every `UPDATE` is rejected by
  `prevent_immutable_entity_update()` (`trigger_00`), every `DELETE` by
  `prevent_immutable_entity_delete()` (`trigger_01`), both raising `P0001`. The
  blanket UPDATE block makes `id`, `created_at`, and `row_created_at` immutable
  with no separate guard.
- **Creation-only timestamps**: `created_at` (logical authoring time) and
  `row_created_at` (physical insert time), both
  `TIMESTAMPTZ NOT NULL DEFAULT
  NOW()`; no `updated_at`/`row_updated_at`.
  `created_at` MAY be supplied explicitly to backfill a prompt's original
  authoring date — the one documented exception to §I's clock-authority rule —
  in which case it reads back earlier than the defaulted `row_created_at`.
- **`(name, version)` uniqueness**: `system_prompts_name_version_unique` — a
  `(name, version)` pair maps to exactly one immutable `body` forever. `name` is
  the logical family (e.g. `coach`); `version` is a plain immutable label (e.g.
  `v1`), NOT an OCC counter. The composite index's leading `name` column also
  serves "all versions of a family" lookups, so there is no separate secondary
  index.
- **`name` / `version` canonical**: both NOT NULL, ≤255, non-empty, and trimmed
  (six named CHECKs, the project TEXT-column convention).
- **`body` bounded, NOT trimmed**: `body TEXT NOT NULL`, non-empty
  (`system_prompts_body_not_empty_check`) and ≤1 MiB
  (`system_prompts_body_size_check`), but deliberately NOT trimmed — it is the
  verbatim artifact sent to the model with no raw-payload backup behind it, so
  leading/trailing whitespace MUST be preserved as authored.

### `email_sends` — Append-Only Log

The append-only ledger of **terminal** transactional-email outcomes: one
immutable row per resolved send. It records terminal outcomes ONLY — a
successful send (`SENT`) or a permanent rejection (`REJECTED`). Transient
failures are NEVER logged here; retry is the queue's domain (`job_attempts`),
and dual-logging would create two sources of truth for one logical message.

- **Append-only**: rows MUST NOT be updated or deleted. `prevent_log_update()`
  (`trigger_00`) and `prevent_log_delete()` (`trigger_01`) raise `P0001`. A
  corrected or superseded outcome is a new row, never an in-place edit.
- **Identity**: `id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7()` — time-ordered
  (`UUIDv7`, not `BIGINT` identity) because the id is externally surfaced, not
  an internal-only cross-reference like the `convo_*` logs.
- **Ordered replay**: replay order is `ORDER BY created_at, id`; `created_at`
  carries the ordering meaning and the time-ordered `id` is the deterministic
  tiebreaker for rows sharing a transaction timestamp. No stream FK and no
  per-stream `seq`: this is a single flat ledger, not a partitioned stream.
- **Ingest/event timestamps**: both `created_at` (logical send time) and
  `row_created_at` (physical insert time), `TIMESTAMPTZ NOT NULL DEFAULT NOW()`;
  no `updated_at`/`row_updated_at` (write-once). PostgreSQL's clock is the
  source of truth (§I).
- **Status domain (terminal-only)**:
  `status TEXT NOT NULL CHECK (status IN
  ('SENT', 'REJECTED'))`. The CHECK is
  the durable contract that only terminal outcomes enter the ledger.
- **Outcome-conditional fields (writer-side invariant)**: `provider_message_id`
  (provider's id, set when `SENT`) and `error_message` (rejection reason, set
  when `REJECTED`) are both nullable. The DB does NOT cross-check either against
  `status`; their presence is a writer-side invariant, not a CHECK.
- **Captured envelope**: `recipient_email`, `sender_email`, `subject`, and
  `body` are all `NOT NULL` — the message is captured verbatim at write time so
  the record is self-contained for later audit. `body` is the plain-text body.
- **Provider provenance (intentionally unconstrained)**:
  `provider TEXT NOT
  NULL` pins the adapter identity that produced the outcome
  (e.g. log-only). It is deliberately NOT constrained by a CHECK or enum — the
  adapter set is open-ended, unlike the closed `provider` allowlist on
  `convo_requests`.

---

## III. Behavioral Contracts

### Trigger Functions

#### `update_timestamp()`

- **Trigger type**: `BEFORE UPDATE`
- **Expects columns**: `updated_at`, `row_updated_at`
- **Side effects**: Sets `NEW.row_updated_at = NOW()` on every invocation. Sets
  `NEW.updated_at = NOW()` unless the logical-timestamp bypass is active (see
  §II, Logical-timestamp bypass).
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

#### `prevent_log_update()`

- **Trigger type**: `BEFORE UPDATE` (on `convo_requests`, `convo_responses`,
  `convo_responses_raw`, `email_sends`)
- **Side effects**: Always raises `ERRCODE P0001` ("Log rows are append-only and
  cannot be updated.").
- **Idempotency**: N/A.

#### `prevent_log_delete()`

- **Trigger type**: `BEFORE DELETE` (on the four log tables: the three `convo_*`
  logs and `email_sends`)
- **Side effects**: Always raises `ERRCODE P0001` ("Log rows cannot be deleted;
  prune by partition/retention.").
- **Idempotency**: N/A.

#### `prevent_immutable_entity_update()`

- **Trigger type**: `BEFORE UPDATE` (on `system_prompts`)
- **Side effects**: Always raises `ERRCODE P0001` ("Immutable entity rows cannot
  be updated.").
- **Idempotency**: N/A.

#### `prevent_immutable_entity_delete()`

- **Trigger type**: `BEFORE DELETE` (on `system_prompts`)
- **Side effects**: Always raises `ERRCODE P0001` ("Immutable entity rows cannot
  be deleted.").
- **Idempotency**: N/A.

#### `trim_users_strings()`

- **Trigger type**: `BEFORE INSERT OR UPDATE` on `users`
- **Side effects**: Normalizes `email` (lowercased + trimmed), `name` (trimmed),
  and `display_name` (trimmed if non-null).
- **Idempotency**: Yes — applying twice produces the same result.

#### `log_user_version()`

- **Trigger type**: `AFTER INSERT OR UPDATE` on `users`
- **Side effects**: Inserts one row into `users_versions` per triggering
  statement.
- **Error handling**: Failure raises a PostgreSQL exception; the parent
  transaction is rolled back.
- **Idempotency**: No — duplicate `(id, version)` violates the primary key.

#### `log_student_version()`

- **Trigger type**: `AFTER INSERT OR UPDATE` on `students`
- **Side effects**: Inserts one row into `students_versions` per triggering
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
- **PostgreSQL version**: Schema relies on `pg_notify`, `TIMESTAMPTZ`, `JSONB`,
  `BYTEA`, `uuidv7()`, and `BIGINT GENERATED ALWAYS AS IDENTITY`. Requires
  PostgreSQL 18 (provided by `pkgs.postgresql_18` in `flake.nix`).
- **`unicoach.bypass_logical_timestamp`**: See §II, Logical-timestamp bypass.

---

## V. History

- [x] [RFC-05: Database Scripts](../../rfc/05-db-scripts.md)
- [x] [RFC-06: Users Table](../../rfc/06-users-table.md)
- [x] [RFC-11: Sessions](../../rfc/11-sessions.md)
- [x] [RFC-14: Extract Database Module](../../rfc/14-db-module.md)
- [x] [RFC-15: Queue Data Layer](../../rfc/15-queue-data-layer.md)
- [x] [RFC-16: Queue Worker Framework](../../rfc/16-queue-worker-framework.md)
- [x] [RFC-31: Student Profile](../../rfc/31-student-profile.md)
- [x] [RFC-32: Coaching Conversations](../../rfc/32-coaching-conversations.md)
- [x] [RFC-33: System Prompts](../../rfc/33-system-prompts.md)
- [x] [RFC-34: Transactional Email Service](../../rfc/34-transactional-email-service.md)
