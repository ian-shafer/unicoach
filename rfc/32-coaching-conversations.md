# 32 — Coaching Conversations

## Executive Summary

This RFC introduces the persistence layer for student↔LLM coaching
conversations: the immutable source of truth that every later memory feature
(consolidation / "dreaming" into the student profile, retrieval,
personalization) will fold over. The Anthropic Messages API is stateless — each
turn resends the full prior history plus a separate system prompt — so the
database is the coach's only durable memory. The mandate of this RFC is to
**capture completely and verbatim at write time**; it reads nothing back and
builds no application layer.

The design is the
[entity-that-owns-logs](../.claude/skills/postgres-log-table-design/SKILL.md)
shape, applying two skills at once:

- `convos` — a **mutable entity** (`postgres-entity-table-design`) owned by a
  student (1:many). Its editable `name` and `deleted_at` soft-delete flag are
  mutable domain data — that mutability is exactly why it is an entity, not a
  log. `UUIDv7` PK.
- `convo_requests`, `convo_responses`, `convo_responses_raw` — **append-only
  logs** (`postgres-log-table-design`) hanging off the `convos` entity, with
  write-once integrity enforced by triggers. `BIGINT` identity PKs.

Capture decisions are fixed: the raw provider **response** is stored verbatim
(irreplaceable) in an isolated sibling table; the replayed **request** history
is **not** re-stored (reconstructable → quadratic). Every exchange pins
provenance (provider + exact requested/resolved model + system-prompt version),
recorded per turn. Message content is stored as opaque `JSONB` (not text) — the
DB keeps it provider-shape agnostic; the application layer owns its internal
structure. Replay order is `created_at` with the `BIGINT` id as tiebreaker.

Scope is **schema-only**: one migration file plus DB-level tests. No DAO,
service, REST, prompt assembly, LLM integration, retrieval, or consolidation —
those are later RFCs that will read this store.

## Detailed Design

### Entity Configuration — `convos`

| Setting        | Selection               | Implementation Requirement                                                                                                                                                   |
| :------------- | :---------------------- | :--------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **ID Type**    | `UUIDv7`                | `id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7()`                                                                                                                              |
| **Mutability** | Mutable                 | `updated_at` maintained by shared `update_timestamp()` trigger                                                                                                               |
| **Timestamps** | Advanced (4-timestamp)  | `created_at`, `row_created_at`, `updated_at`, `row_updated_at` — reuses shared functions                                                                                     |
| **Versioning** | **Disabled** (see D-3)  | No `version`, no `convos_versions`, no OCC — the transcript logs are the authoritative history                                                                               |
| **Deletions**  | Logical + cascade-wired | `deleted_at` for user-facing delete; physical delete blocked by `prevent_physical_delete()`; FK chain declares `ON DELETE CASCADE` for future erasure, inert today (see D-4) |

### Log Configuration — `convo_requests`, `convo_responses`, `convo_responses_raw`

| Setting         | Selection                        | Implementation Requirement                                                                                                                                          |
| :-------------- | :------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Identity**    | `BIGINT` identity                | Each row is referenced individually (`response`→`request`, `raw`→`response`); generated in one Postgres instance, never exposed externally → `BIGINT` over `UUIDv7` |
| **Stream Key**  | `convo_id`                       | NOT NULL FK to `convos(id)`; indexed `(convo_id, created_at)` for replay                                                                                            |
| **Ordering**    | `created_at` (+ `id` tiebreaker) | Canonical replay `ORDER BY created_at, id`; no explicit `seq` column                                                                                                |
| **Time Model**  | Ingest-only                      | `created_at` only; no `occurred_at` (write time == event time for live turns)                                                                                       |
| **Raw Payload** | Isolated 1:1 sibling             | `convo_responses_raw` keyed `response_id` PK→FK, so it can be archived/dropped without touching hot rows                                                            |
| **Retention**   | Indefinite (see D-5)             | Not time-partitioned in this RFC; simple `BIGINT` PKs preserved                                                                                                     |
| **Corrections** | Compensating-append              | A wrong fact is never edited; append a new row. UPDATE/DELETE rejected by trigger                                                                                   |

### Data Model

The migration lives at `db/schema/0006.create-coaching-conversations.sql` (next
free number; `0005` is the last existing file). It defines, in order: the two
log-guard functions, then the four tables with their constraints, indexes, and
triggers.

#### Log-guard functions (new shared functions)

`db/schema/0000.shared-functions.sql` is an already-applied, append-only
migration; `bin/db-migrate` skips files whose `version_id` is already recorded
in `schema_migrations`, so editing `0000` would **not** re-run on any existing
database. The two new append-only guards are therefore defined at the top of the
**new** `0006` migration (idempotent `CREATE OR REPLACE`), mirroring the
`postgres-log-table-design` skill. They are distinct from the existing
`prevent_physical_delete()`, whose message ("use soft deletes by setting
deleted_at") is wrong for an append-only log.

```sql
CREATE OR REPLACE FUNCTION prevent_log_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Log rows are append-only and cannot be updated.'
    USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_log_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Log rows cannot be deleted; prune by partition/retention.'
    USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;
```

#### `convos` — mutable entity

```sql
CREATE TABLE convos (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),

  -- Timestamps (4-timestamp advanced pattern; reuses shared functions)
  created_at     TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  updated_at     TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  deleted_at     TIMESTAMPTZ NULL,

  -- Ownership. CASCADE wires the future physical-erasure path; it stays inert
  -- while prevent_physical_delete blocks all physical deletes (see Deletion &
  -- cascade).
  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

  -- Mutable domain field (the reason this is an entity, not a log). Set at
  -- creation — typically derived from the first user turn — and editable after.
  name TEXT NOT NULL,

  CONSTRAINT convos_name_length_check    CHECK (length(name) <= 255),
  CONSTRAINT convos_name_not_empty_check CHECK (length(trim(name)) > 0),
  CONSTRAINT convos_name_trimmed_check   CHECK (name = trim(name))
);

-- Replay/list a student's active conversations.
CREATE INDEX convos_student_id_idx ON convos (student_id) WHERE deleted_at IS NULL;
```

Triggers (BEFORE triggers fire in alphabetical name order — the established
`trigger_00`/`00a`/`03` convention is preserved):

- `trigger_00_prevent_convos_physical_delete` — `BEFORE DELETE` →
  `prevent_physical_delete()`.
- `trigger_00a_prevent_convos_immutable_updates` — `BEFORE UPDATE` →
  `prevent_immutable_updates()` (guards `id`, `created_at`, `row_created_at`).
- `trigger_03_enforce_convos_updated_at` — `BEFORE UPDATE` →
  `update_timestamp()` (advances `row_updated_at` always; `updated_at` unless
  the `unicoach.bypass_logical_timestamp` bypass is active).

No `enforce_versioning`, no trim trigger, no `*_versions` log trigger
(versioning disabled — see D-3).

#### `convo_requests` — append-only log

One row = one turn **sent** to the model: the new user input plus the request
envelope. It deliberately does **not** store the replayed prior history (the
stateless API resends it each turn; re-storing it per row would be O(n²) bloat).

```sql
CREATE TABLE convo_requests (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- internal, monotonic
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  convo_id   UUID NOT NULL REFERENCES convos(id) ON DELETE CASCADE,

  -- Provenance pinned on every exchange.
  provider              TEXT  NOT NULL,   -- LLM vendor; allowlisted below (per-turn, see D-7)
  model_requested       TEXT  NOT NULL,   -- model id requested within the provider (e.g. claude-opus-4-8)
  system_prompt_version TEXT  NOT NULL,   -- verbatim version pin (see D-2; no FK yet)
  request_params        JSONB NULL,       -- vendor params (temperature, max_tokens, ...); interpreted via provider

  -- The new user input for this turn. Opaque structured JSONB; the DB does not
  -- constrain its internal shape (see Content representation).
  content JSONB NOT NULL,

  -- Vendor allowlist (TEXT + CHECK, project convention; NOT a native pg enum).
  -- Extend the list in a later migration as providers are added.
  CONSTRAINT convo_requests_provider_valid_check
    CHECK (provider IN ('anthropic')),
  CONSTRAINT convo_requests_model_requested_length_check
    CHECK (length(model_requested) <= 255),
  CONSTRAINT convo_requests_model_requested_not_empty_check
    CHECK (length(trim(model_requested)) > 0),
  CONSTRAINT convo_requests_model_requested_trimmed_check
    CHECK (model_requested = trim(model_requested)),
  CONSTRAINT convo_requests_system_prompt_version_length_check
    CHECK (length(system_prompt_version) <= 255),
  CONSTRAINT convo_requests_system_prompt_version_not_empty_check
    CHECK (length(trim(system_prompt_version)) > 0),
  CONSTRAINT convo_requests_system_prompt_version_trimmed_check
    CHECK (system_prompt_version = trim(system_prompt_version)),
  CONSTRAINT convo_requests_request_params_is_object_check
    CHECK (request_params IS NULL OR jsonb_typeof(request_params) = 'object'),
  -- Bounded user input. 1 MiB; revisit if larger inputs become legitimate.
  CONSTRAINT convo_requests_content_size_check
    CHECK (octet_length(content::text) <= 1048576)
);

CREATE INDEX convo_requests_convo_id_created_at_idx
  ON convo_requests (convo_id, created_at);
```

#### `convo_responses` — append-only log, 1:1 with request

The model's reply plus the response envelope. `request_id` is `UNIQUE`,
enforcing the 1:1 request↔response relationship at the DB level. A failed
request with no usable reply is recorded as a row with `stop_reason = 'error'`
and `content = NULL` — the attempt is never silently dropped.

```sql
CREATE TABLE convo_responses (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  request_id BIGINT NOT NULL UNIQUE REFERENCES convo_requests(id) ON DELETE CASCADE,
  convo_id   UUID   NOT NULL REFERENCES convos(id) ON DELETE CASCADE,  -- denormalized; see note

  content        JSONB NULL,    -- assistant output; opaque JSONB; NULL only on error
  model_resolved TEXT  NULL,    -- exact model that actually ran; NULL only on error
  stop_reason    TEXT  NOT NULL, -- end_turn | max_tokens | stop_sequence | tool_use | error | ...

  input_tokens       INTEGER NULL,
  output_tokens      INTEGER NULL,
  cache_read_tokens  INTEGER NULL,
  cache_write_tokens INTEGER NULL,

  provider_request_id TEXT    NULL,  -- provider's request/response id for cross-ref
  latency_ms          INTEGER NULL,

  CONSTRAINT convo_responses_stop_reason_length_check
    CHECK (length(stop_reason) <= 64),
  CONSTRAINT convo_responses_stop_reason_not_empty_check
    CHECK (length(trim(stop_reason)) > 0),
  CONSTRAINT convo_responses_stop_reason_trimmed_check
    CHECK (stop_reason = trim(stop_reason)),
  CONSTRAINT convo_responses_model_resolved_length_check
    CHECK (model_resolved IS NULL OR length(model_resolved) <= 255),
  CONSTRAINT convo_responses_model_resolved_not_empty_check
    CHECK (model_resolved IS NULL OR length(trim(model_resolved)) > 0),
  CONSTRAINT convo_responses_model_resolved_trimmed_check
    CHECK (model_resolved IS NULL OR model_resolved = trim(model_resolved)),
  -- Content/model are present on success, NULL only when the turn errored.
  CONSTRAINT convo_responses_content_presence_check
    CHECK (content IS NOT NULL OR stop_reason = 'error'),
  CONSTRAINT convo_responses_model_presence_check
    CHECK (model_resolved IS NOT NULL OR stop_reason = 'error'),
  CONSTRAINT convo_responses_provider_request_id_length_check
    CHECK (provider_request_id IS NULL OR length(provider_request_id) <= 255),
  CONSTRAINT convo_responses_provider_request_id_not_empty_check
    CHECK (provider_request_id IS NULL OR length(trim(provider_request_id)) > 0),
  CONSTRAINT convo_responses_provider_request_id_trimmed_check
    CHECK (provider_request_id IS NULL OR provider_request_id = trim(provider_request_id)),
  CONSTRAINT convo_responses_tokens_nonneg_check CHECK (
    (input_tokens       IS NULL OR input_tokens       >= 0) AND
    (output_tokens      IS NULL OR output_tokens      >= 0) AND
    (cache_read_tokens  IS NULL OR cache_read_tokens  >= 0) AND
    (cache_write_tokens IS NULL OR cache_write_tokens >= 0)
  ),
  CONSTRAINT convo_responses_latency_nonneg_check
    CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

CREATE INDEX convo_responses_convo_id_created_at_idx
  ON convo_responses (convo_id, created_at);
```

`convo_id` is denormalized onto the response (it is reachable via
`request_id → convo_requests.convo_id`). This is a **constant-size** copy per
row, not the quadratic history trap; it buys a direct `(convo_id, created_at)`
index so assistant outputs for a conversation are an index scan with no join.
The "never store the reconstructable" rule targets values that grow with stream
length, not a single fixed FK. Because the value is denormalized, keeping
`convo_responses.convo_id` equal to `request_id → convo_requests.convo_id` is a
**writer-side invariant**: the DB enforces only that both FKs reference valid
rows, not that they agree (a cross-row equality check is out of scope for this
schema-only RFC, mirroring the at-most-one-raw-row note). The writing
application sets `convo_id` from the request it is answering.

#### `convo_responses_raw` — append-only log, isolated verbatim payload

The verbatim provider response, kept because it is irreplaceable and so the
fidelity backup can be archived or dropped later without rewriting the hot
`convo_responses` rows. Keyed 1:1 to its response by making the FK the PK.

```sql
CREATE TABLE convo_responses_raw (
  response_id BIGINT NOT NULL PRIMARY KEY REFERENCES convo_responses(id) ON DELETE CASCADE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  payload     JSONB NOT NULL  -- exact provider response body, verbatim
);
```

The PK-as-FK guarantees **at most one** raw row per response. A response that
provider-replied (success, `max_tokens`, a provider error body, etc.) has
exactly one raw row; a pure transport failure with no provider payload at all
may have **none** (there is nothing verbatim to store — the failure is still
recorded by the `convo_responses` row with `stop_reason = 'error'`). Enforcing
"every non-transport-error response has a raw row" is a cross-table invariant
left to the writing application; the DB guarantees at-most-one, FK integrity,
and immutability.

#### Content representation

`convo_requests.content` and `convo_responses.content` are **opaque `JSONB`**:
the DB guarantees only that they are valid JSON and (for requests) present and
size-bounded. It deliberately does **not** constrain the internal shape — no
"must be an array of blocks" check — because that shape is an LLM-provider wire
convention (Anthropic Messages content blocks), and encoding it in a DDL
constraint would leak provider details into the persistence layer and force a
migration whenever the representation evolves or the provider changes. Verbatim
provider fidelity is already preserved in `convo_responses_raw.payload`; the
typed `content` columns are a working projection whose structure is owned and
interpreted by the application/DAO layer (a future RFC). `JSONB` (over `TEXT`)
keeps that structure queryable and additively extensible without committing the
schema to any one format. See Decision D-6.

#### Log immutability triggers

Applied to each of the three log tables (`convo_requests`, `convo_responses`,
`convo_responses_raw`):

```sql
CREATE TRIGGER trigger_00_prevent_<table>_update
BEFORE UPDATE ON <table>
FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();

CREATE TRIGGER trigger_01_prevent_<table>_delete
BEFORE DELETE ON <table>
FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
```

No `updated_at`, no `version`, no OCC, no versions-mirror — roughly half the
entity mechanics are absent by design for logs.

### Deletion & cascade

Two layers, deliberately separate:

- **Soft delete** (`convos.deleted_at`): the user-facing per-conversation delete
  and the RFC #31 account-deletion cascade both set it. Soft-delete is an
  `UPDATE`, so it does not propagate via FK — the application sets `deleted_at`
  on the student's `convos` inside the #31 transaction. The transcript is
  preserved as the memory source of truth.
- **Physical erasure** (declared, inert today): every FK in the chain declares
  `ON DELETE CASCADE` — `convos.student_id → students`,
  `convo_requests.convo_id → convos`,
  `convo_responses.request_id → convo_requests`,
  `convo_responses.convo_id → convos`, and
  `convo_responses_raw.response_id → convo_responses`.

The cascade is **declared but cannot fire**: the unconditional
`prevent_physical_delete` (on `convos`) and `prevent_log_delete` (on the logs)
`BEFORE DELETE` triggers RAISE, and a raising `BEFORE DELETE` trigger aborts the
entire cascade — so no row is physically deleted today. Declaring the cascade at
table creation is free and avoids a later `DROP`/`ADD CONSTRAINT` migration on
large log tables; a future retention/erasure RFC turns on physical purge by
replacing the guard functions with **role-aware** variants (block the
application role, allow a privileged purge role) via `CREATE OR REPLACE`, at
which point the already-declared cascades fire and a single
`DELETE FROM students WHERE id = ?` (as that role) tears down the whole
transcript subtree. See Decision D-4.

### Extensibility — snapshotting injected dynamic context (design, do not build)

Later milestones will inject dynamic context (retrieved memories, a profile
snapshot) into each turn. That injected context is **point-in-time and
irreplaceable** (it reflects what retrieval returned at that moment), so it
belongs captured on the request envelope — unlike the replayed history, it is
not reconstructable. The schema is shaped to absorb it **additively** later, by
either:

- adding a nullable `injected_context JSONB` column to `convo_requests`; or
- adding a sibling log table `convo_request_context` keyed `request_id` PK→FK
  (the raw-payload isolation pattern, if the snapshot is large).

This RFC builds neither; it only commits to the additive path so no rewrite is
needed.

### Replay semantics

- A conversation's request stream:
  `SELECT ... FROM convo_requests WHERE
  convo_id = ? ORDER BY created_at, id`
  (index `convo_requests_convo_id_created_at_idx`). `created_at` carries the
  ordering meaning; `id` is the deterministic tiebreaker for rows sharing a
  `created_at` (e.g. written in one transaction).
- Each turn's reply: join `convo_responses` on `request_id` (1:1), or scan
  `convo_responses` directly by `(convo_id, created_at)`.
- The verbatim payload for any reply: `convo_responses_raw` by `response_id`.

### Dependencies

- Shared trigger functions in `db/schema/0000.shared-functions.sql`
  (`update_timestamp`, `prevent_physical_delete`, `prevent_immutable_updates`).
- `students` table (RFC #31) for the owner FK.
- `users` table (RFC #06) — transitively, via `students.user_id`; required by
  the test fixture to create a student.
- `bin/db-migrate` (RFC #05) applies the new migration in lexicographical order;
  `pg_uuidv7` extension and PostgreSQL 18 (`JSONB`,
  `BIGINT GENERATED ALWAYS AS
  IDENTITY`, `TIMESTAMPTZ`) per existing schema
  infrastructure. No new external libraries or database extensions are
  introduced; every dependency version is inherited from the existing
  `flake.nix`/schema infrastructure (PostgreSQL 18, `pg_uuidv7`).
- DB tests are a **shell harness** (`bin/db-convos-tests`) built on
  `bin/common`, `bin/tests-common` (`assert_success`/`assert_failure`), and
  `bin/db-run` / `bin/db-query`, modeled on `bin/db-users-tests` (RFC #06). A
  new `bin/db-tests` aggregator runs it, and `bin/test` is extended to invoke
  `bin/db-tests`. **Lifecycle contract:** `bin/db-convos-tests` assumes a live,
  already-migrated database and **owns no Postgres lifecycle** — it sets **no
  `EXIT`/`INT`/`TERM` trap** and runs no
  `postgres-up`/`postgres-down`/`rm -rf
  $POSTGRES_DATA_DIR`/`db-init`/`db-migrate`
  (this is the one deliberate divergence from `bin/db-users-tests`, whose
  self-contained lifecycle would tear Postgres down out from under the Gradle
  phase — see Implementation Plan step 3). The aggregator `bin/db-tests`
  likewise owns no lifecycle; the caller (`bin/test`, or a developer who has
  already run `db-init`/`db-migrate`) guarantees the migrated DB. No DAO,
  `Result` types, or domain models are introduced (out of scope).

### Error Handling / Edge Cases

- Insert into any log table with a non-existent `convo_id` / `request_id` /
  `response_id` → FK violation `23503`.
- Second `convo_responses` row for the same `request_id` → unique violation
  `23505` (1:1 enforced).
- Second `convo_responses_raw` row for the same `response_id` → PK violation
  `23505` (1:1 enforced).
- `UPDATE` on any log table → `P0001` from `prevent_log_update()`.
- `DELETE` on any log table → `P0001` from `prevent_log_delete()`.
- `convos`: `UPDATE` changing `id` / `created_at` / `row_created_at` → `P0001`;
  physical `DELETE` → `P0001` (the declared `ON DELETE CASCADE` cannot fire
  while the guard blocks the delete — see D-4).
- NULL `convos.name` → NOT NULL violation `23502` (name is mandatory, D-8).
- CHECK violations (`content`/`model_resolved` NULL on a non-error response,
  disallowed `provider`, a text field too long / blank / not trimmed (D-9),
  negative tokens, oversized request content) → `23514`.
- Transport failure with no provider reply → one `convo_responses` row
  (`stop_reason='error'`, `content=NULL`, `model_resolved=NULL`) and no
  `convo_responses_raw` row.

### Privacy & Retention (concern, not implemented here)

Coaching transcripts are sensitive personal data and grow without bound. This
RFC does not implement retention, purge, encryption-at-rest beyond the cluster
default, or right-to-be-forgotten. Two forward concerns are noted for a future
RFC: (1) the isolated `convo_responses_raw` table is the designed shed-point for
archival/drop; (2) per-conversation delete and the #31 account cascade are soft
(`convos.deleted_at`), so true physical erasure of the transcript is the
cascade-driven, privileged purge enabled by a future retention RFC (Decision
D-4), never an ad-hoc application `DELETE`.

## Tests

Schema-only RFC → tests are a **shell harness**, `bin/db-convos-tests`, modeled
on `bin/db-users-tests` (RFC #06) but with one deliberate divergence: it **does
not manage the Postgres lifecycle** (no `postgres-up`/`postgres-down`, no
`rm
-rf $POSTGRES_DATA_DIR`, no `EXIT`/`INT`/`TERM` trap, no
`db-init`/`db-migrate`). It assumes a live, already-migrated database — the
caller (`bin/test` or the developer) provides it — and asserts schema behavior
purely through SQL via `bin/db-run rw` / `bin/db-query` and the `assert_success`
/ `assert_failure` helpers from `bin/tests-common`. This divergence is required
because the harness runs inside `bin/test`'s already-migrated DB; copying
`db-users-tests`'s self-contained lifecycle (with its EXIT-trap `postgres-down`)
would tear Postgres down before the Gradle phase. No JVM, DAO, or domain models
are involved (out of scope).

`bin/db-run` executes psql with `ON_ERROR_STOP=1`, so a statement rejected by a
constraint or trigger exits non-zero: `assert_failure` confirms rejection,
`assert_success` confirms acceptance. Where two rejection mechanisms must be
distinguished (an immutability trigger vs. a CHECK), the harness greps the error
for the **named constraint or trigger message** — part of the schema contract.
Exact SQLSTATE→domain-error mapping is deferred to the future DAO RFC's JUnit
tests; this harness asserts the schema-level accept/reject behavior. A fixture
inserts a `users` row (email/name/password_hash, per `bin/db-users-tests`), then
a `students` row referencing that `user_id` and supplying the NOT NULL
`expected_high_school_graduation_year` (a `students` insert without a valid
`user_id` fails `23503`, and without the grad year fails `23502`), capturing the
returned `students.id` as the `student_id` used by every `convos` case. The
harness starts from a freshly migrated database (no per-assertion reset), so
cases insert distinct rows and any count-style assertion scopes its predicate to
the row(s) it just inserted (by `id`/`student_id`), never a global `COUNT(*)`,
so assertions are order-independent.

Assertions (each an `assert_success` / `assert_failure` case):

### `convos` (entity)

- Insert with only `student_id` and no `name` is rejected (NOT NULL, `23502`) —
  `name` is mandatory (D-8).
- A minimal insert supplying `student_id` and a non-empty `name` is accepted;
  `id`, the four timestamps, and `deleted_at` (NULL) take their defaults.
- Insert with a non-existent `student_id` is rejected (FK).
- A NULL `name` is rejected (NOT NULL, `23502`); a 256-char `name` is rejected
  (`convos_name_length_check`); a whitespace-only `name` is rejected
  (`convos_name_not_empty_check`); a `name` with leading or trailing whitespace
  (e.g. `' Calc '`) is rejected (`convos_name_trimmed_check`); a trimmed,
  non-empty `name` (e.g. `'Calc help'`) is accepted.
- `UPDATE convos SET name = ...` is accepted and advances `updated_at` and
  `row_updated_at`.
- `UPDATE` of `id` / `created_at` / `row_created_at` is rejected
  (`prevent_immutable_updates`).
- Physical `DELETE FROM convos` is rejected (`prevent_physical_delete`).
- `UPDATE convos SET deleted_at = NOW()` (scoped to the just-inserted `id`) is
  accepted; a `WHERE id = <that id> AND deleted_at IS NULL` count then returns 0
  — the soft-deleted row is excluded by the partial index. The predicate is
  scoped to the row under test so it is independent of other live `convos` rows.
- With the `unicoach.bypass_logical_timestamp` session bypass set, an `UPDATE`
  advances `row_updated_at` but leaves `updated_at` unchanged.

### `convo_requests` (log)

- Two inserts produce monotonically increasing `BIGINT` ids; `created_at`
  defaults to `NOW()`.
- NULL `provider`, `model_requested`, `system_prompt_version`, or `content` is
  rejected (NOT NULL).
- `provider = 'anthropic'` is accepted; `provider = 'openai'` is rejected
  (`convo_requests_provider_valid_check`).
- `content` accepts any valid JSON shape (object, array, scalar) — no provider
  block structure is asserted (see Content representation).
- `request_params` as a JSON object is accepted, as NULL is accepted, as a JSON
  array is rejected (`convo_requests_request_params_is_object_check`).
- A 256-char `model_requested` is rejected; a blank `system_prompt_version` is
  rejected; a `model_requested` or `system_prompt_version` with leading or
  trailing whitespace is rejected
  (`convo_requests_model_requested_trimmed_check` /
  `convo_requests_system_prompt_version_trimmed_check`).
- A `content` payload over 1 MiB is rejected
  (`convo_requests_content_size_check`).
- Insert with a non-existent `convo_id` is rejected (FK).
- `UPDATE` of any column is rejected, and the error matches the
  `prevent_log_update()` message
  (`Log rows are append-only and cannot be
  updated.`); `DELETE` is rejected,
  matching the `prevent_log_delete()` message
  (`Log rows cannot be deleted; prune by partition/retention.`) — distinguishing
  the new log guards from the entity `prevent_physical_delete()` message.

### `convo_responses` (log)

- A first response for a `request_id` is accepted; a second for the same
  `request_id` is rejected (UNIQUE — 1:1 enforced).
- A success row (`stop_reason='end_turn'`, non-null `content`, `model_resolved`,
  token counts, `provider_request_id`, `latency_ms`) is accepted.
- An error row (`stop_reason='error'`, `content=NULL`, `model_resolved=NULL`) is
  accepted.
- `content=NULL` with `stop_reason='end_turn'` is rejected
  (`convo_responses_content_presence_check`); `model_resolved=NULL` with
  `stop_reason='end_turn'` is rejected (`convo_responses_model_presence_check`).
- A negative `output_tokens` or `latency_ms` is rejected (non-negative checks).
- A 65-char `stop_reason` is rejected
  (`convo_responses_stop_reason_length_check`); a 256-char `model_resolved` is
  rejected (`convo_responses_model_resolved_length_check`); a `stop_reason`,
  `model_resolved`, or `provider_request_id` with leading or trailing whitespace
  is rejected (`convo_responses_stop_reason_trimmed_check` /
  `convo_responses_model_resolved_trimmed_check` /
  `convo_responses_provider_request_id_trimmed_check`); an empty-string
  `stop_reason`, `model_resolved`, or `provider_request_id` is rejected
  (`convo_responses_stop_reason_not_empty_check` /
  `convo_responses_model_resolved_not_empty_check` /
  `convo_responses_provider_request_id_not_empty_check`). A NULL
  `model_resolved` or `provider_request_id` remains accepted (absence is `NULL`,
  not `''`).
- Insert with a non-existent `request_id` or `convo_id` is rejected (FK).
- `UPDATE` and `DELETE` are rejected (`prevent_log_update` /
  `prevent_log_delete`).

### `convo_responses_raw` (log)

- A first raw row for a `response_id` is accepted; a second for the same
  `response_id` is rejected (PK — 1:1 enforced).
- NULL `payload` is rejected (NOT NULL).
- Insert with a non-existent `response_id` is rejected (FK).
- An error `convo_responses` row with no raw row is a valid terminal state (the
  response exists; a raw lookup returns zero rows).
- `UPDATE` and `DELETE` are rejected (`prevent_log_update` /
  `prevent_log_delete`).

### Ordered replay

- Three requests inserted in separate statements: a
  `WHERE convo_id = ? ORDER BY created_at, id` read (via `db-query -tA`) returns
  them in insertion order.
- Two requests inserted in a **single transaction** (identical transaction-time
  `created_at`): the `id` tiebreaker yields a stable, total order matching
  insertion order.
- A request, its 1:1 response, and the response's raw row: a joined read
  (`convo_requests` ⨝ `convo_responses` on `request_id` ⨝ `convo_responses_raw`
  on `response_id`) reconstructs the full turn.

### Function existence

- `prevent_log_update()` and `prevent_log_delete()` exist after migration
  (queried from `pg_proc`), confirming the new shared guards were created by
  `0006`.

### Cascade topology (declared but inert)

- `information_schema.referential_constraints` reports `delete_rule = 'CASCADE'`
  for the five FKs: `convos.student_id`, `convo_requests.convo_id`,
  `convo_responses.request_id`, `convo_responses.convo_id`, and
  `convo_responses_raw.response_id` — confirming the erasure path is wired
  (D-4).
- The cascade is inert today: a physical `DELETE FROM convos` over a full
  request/response/raw subtree is rejected (`prevent_physical_delete`) and
  leaves every row intact (the `BEFORE DELETE` guard aborts the cascade before
  any child is removed).

## Implementation Plan

1. **Migration.** Create `db/schema/0006.create-coaching-conversations.sql`
   containing, in order: `prevent_log_update()` and `prevent_log_delete()`
   (`CREATE OR REPLACE`); the `convos` table with constraints, partial index,
   and its three triggers; the `convo_requests` table with constraints, index,
   and its two log triggers; the `convo_responses` table with constraints,
   index, and its two log triggers; the `convo_responses_raw` table with its two
   log triggers. All five FKs declare `ON DELETE CASCADE` (see Deletion &
   cascade). Verify:
   - `nix develop -c bin/db-migrate && nix develop -c bin/db-status`
   - `nix develop -c psql "$DATABASE_URL" -c '\d convos' -c '\d convo_requests' -c '\d convo_responses' -c '\d convo_responses_raw'`
   - `nix develop -c psql "$DATABASE_URL" -c '\df prevent_log_update' -c '\df prevent_log_delete'`

2. **Shell schema harness.** Add `bin/db-convos-tests` (executable; `chmod +x`),
   modeled on `bin/db-users-tests` **except it owns no Postgres lifecycle**:
   source `bin/common` + `bin/tests-common`, set **no** `EXIT`/`INT`/`TERM` trap
   and run **no** `postgres-up`/`postgres-down`/`rm -rf $POSTGRES_DATA_DIR`/
   `db-init`/`db-migrate`; assume the DB is already live and migrated. Then
   implement every assertion in the Tests section with `assert_success` /
   `assert_failure` over `bin/db-run rw` / `bin/db-query`, ending with
   `end_tests`. Verify (against an already-migrated DB):
   - `nix develop -c bin/postgres-up && nix develop -c bin/db-init && nix develop -c bin/db-migrate`
   - `nix develop -c bin/db-convos-tests` — all assertions pass; the script
     exits `0` and prints the `N tests passed` summary (a non-zero exit is the
     failure signal — no false-green path, unlike a Gradle flag pass-through).

3. **Aggregate and wire into `bin/test`.** Add `bin/db-tests` (executable) that
   runs `bin/db-convos-tests` (the home for shell DB schema harnesses);
   `bin/db-tests` also owns no Postgres lifecycle (it assumes the migrated DB
   the caller provides) and aborts non-zero if any harness fails. Modify
   `bin/test` to invoke `bin/db-tests` **after** its existing
   `postgres-up`/`db-destroy`/`db-init`/`db-migrate` block and **before** the
   terminal `exec "$PROJECT_ROOT/gradlew" …` line (nothing after `exec` ever
   runs). The invocation must check exit status (`bin/test` runs under the
   shell's error handling) so a shell-test failure aborts the run before `exec`,
   and it must **not** be backgrounded — run sequentially against the DB
   `bin/test` already migrated, never concurrently with the Gradle DB setup.
   Verify:
   - `nix develop -c bin/db-tests` — against an already-migrated DB, green.
   - `nix develop -c bin/test` — runs the shell DB tests **and** the Gradle
     suite; both green, and Postgres is still up when Gradle runs. A failure in
     either aborts the run.

## Files Modified

### New

- `db/schema/0006.create-coaching-conversations.sql` — the migration (discovered
  automatically by `bin/db-migrate`'s `DB_SCHEMA_DIR` scan; no registration
  needed).
- `bin/db-convos-tests` — shell schema-test harness (executable).
- `bin/db-tests` — aggregator that runs the shell DB schema harnesses
  (`bin/db-convos-tests`).

### Modified

- `bin/test` — invoke `bin/db-tests` as part of the run, inserted **after** the
  existing `postgres-up`/`db-destroy`/`db-init`/`db-migrate` block and
  **before** the terminal `exec "$PROJECT_ROOT/gradlew" …` line (an exit-status
  check aborts the run on shell-test failure before `exec`), so `bin/test` runs
  all tests — shell DB schema tests and the Gradle suite — against the same live
  migrated database without tearing it down between phases.

## Decisions

Resolved with the architect; recorded here as design rationale.

### D-1 — No conversation "end"; the boundary is a new `convos` row

`ended_at` is removed. There is no explicit close state: a new conversation is a
new `convos` row, and a conversation's last activity is derivable from
`MAX(convo_requests.created_at)`. `convos` remains an entity because `name` and
`deleted_at` are mutable. Boundary policy (explicit new-conversation, idle
rollover, per-day) is application logic, unconstrained by the schema. If an
explicit close/archive state is ever needed, a nullable column can be added
additively.

### D-2 — System-prompt version is a verbatim text pin; registry is a sibling RFC

`convo_requests.system_prompt_version` stores the version verbatim (`TEXT`, no
FK). The versioned prompt registry is a separate RFC; an FK can be added
additively later. A verbatim pin is the more log-faithful capture — provenance
survives even if the registry is later changed or dropped.

### D-3 — `convos` is unversioned (no OCC, no `convos_versions`)

Just `updated_at`. The authoritative, audit-grade history is the append-only
transcript itself; the header's only mutable fields (`name`, `deleted_at`) are
low-contention (distinct columns; last-write-wins on `name` is acceptable). A
`*_versions` mirror would add hardcoded-column trigger maintenance for
negligible value.

### D-4 — Soft per-conversation delete; physical erasure is cascade-wired but inert

Two layers (full detail in **Deletion & cascade**):

- User-facing per-conversation delete and the RFC #31 account-deletion cascade
  are **soft** (`convos.deleted_at`), preserving the transcript as the memory
  source of truth. Soft-delete is an `UPDATE` and does not propagate via FK, so
  the application sets `deleted_at` on the student's `convos` inside the #31
  transaction.
- True physical erasure is **cascade-wired but inert**: the full FK chain
  declares `ON DELETE CASCADE`, but the unconditional `prevent_physical_delete`
  / `prevent_log_delete` `BEFORE DELETE` triggers abort any cascade (a raising
  `BEFORE DELETE` trigger rolls back the parent delete and all cascading child
  deletes). Declaring the cascade now is free and avoids an expensive
  `DROP`/`ADD CONSTRAINT` migration later; a future retention/erasure RFC
  enables purge by replacing the guards with **role-aware** variants (block the
  app role, allow a privileged purge role) via `CREATE OR REPLACE`, at which
  point the declared cascades fire. End-to-end account purge also requires RFC
  #31's `students`/`users` guards to gain the same role bypass — that is the
  future RFC's responsibility, not this one's.

### D-5 — Logs are not time-partitioned (deferred)

Simple `BIGINT GENERATED ALWAYS AS IDENTITY` PKs and clean 1:1 FKs are kept.
Partitioning by `created_at` would force the partition key into every PK —
composite `(id, created_at)` PKs and composite 1:1 FKs — for volume this product
does not yet have. Adopting it later is a known migration that reworks the
PKs/FKs (and would redefine the D-4 cascade declarations). Deferred.

### D-6 — `content` is opaque `JSONB`; provider shape is not encoded in the schema

The `content` columns are stored as `JSONB` with no internal-shape constraint
(an earlier draft required `jsonb_typeof(content) = 'array'`, mirroring the
Anthropic Messages content-block format — removed). Asserting a provider wire
shape in a DDL `CHECK` leaks LLM-API details into the persistence layer and
would force a migration whenever the representation evolves or the provider
changes. Verbatim provider fidelity already lives in
`convo_responses_raw.payload`; the typed `content` columns are a working
projection interpreted by the application/DAO layer. The DB's remaining content
guarantees are provider-neutral: NOT NULL on the request, the content/model
presence invariants on the response, valid JSON, and the size bound. See Content
representation.

### D-7 — Vendor is per-turn provenance on the log, not a vendor-specific table

Recording which LLM vendor served an exchange is provenance, not a leak (the
leak in D-6 was asserting one vendor's _wire shape_ as a universal `CHECK`). Two
rejected alternatives and the choice:

- **Vendor-specific tables** (per-provider request/response tables) — rejected
  as premature: one provider today, `convo_responses_raw.payload` already holds
  the vendor-exact structure verbatim, and forking the core tables turns ordered
  replay into a cross-table `UNION`. If typed, queryable per-vendor fields are
  ever needed, add an isolated 1:1 sidecar to `convo_responses` (the raw-table
  pattern) additively — do not fork the core tables.
- **Vendor on `convos`** — rejected: it bakes a whole-conversation invariant,
  yet provider fallback / mid-stream model escalation is a routine scenario; and
  `model_requested` is already per-turn, so provider (the same provenance axis)
  belongs on the same row, not split onto the entity.

**Choice:** a `provider TEXT NOT NULL` column on `convo_requests` (paired with
`model_requested`), constrained by a `CHECK` allowlist — `TEXT` + `CHECK`, the
project convention (`jobs.status`), **not** a native pg `enum` (rigid to
evolve). `provider` is **not** denormalized onto `convo_responses` (kept
normalized; interpret a response/raw row via its 1:1 request) and **not** stored
on `convos`. Envelope columns (`stop_reason`, the token counts) hold each
vendor's **own** values, interpreted via `provider`; they are not normalized
into a universal vocabulary at the DB layer (that translation, if ever needed,
is an application concern).

### D-8 — `convos.name` is mandatory (NOT NULL), trimmed, no DB default

`name` is `NOT NULL` with no column default. A conversation is not created until
its first request exists, so a name is always derivable from the first user turn
at insert time; there is no "unnamed" window to represent. The DB enforces the
invariant: present, non-empty after trim, ≤255 chars, and stored in canonical
form with no leading/trailing whitespace (`name = trim(name)`,
`convos_name_trimmed_check`). The naming policy — derive from first user content
or a caller-supplied label — is application-owned. No DB default is used: a
default would bake a presentation string (e.g. "New conversation") into the
schema, leaking a UI/localization concern into the persistence layer (contra
D-6) and masking writers that fail to set a name. The earlier nullable design
("unset at start") is superseded. The no-surrounding-whitespace rule is a
general text-column policy (D-9), applied here and across every free-text column
in this RFC.

### D-9 — Free-text columns store canonical (trimmed) form

Every author- or projection-owned `TEXT` column carries a
`*_trimmed_check CHECK (col = trim(col))` (nullable columns guard
`col IS NULL OR …`), rejecting leading/trailing whitespace so stored values are
canonical. Applied to `convos.name`, `convo_requests.model_requested`,
`convo_requests.system_prompt_version`, `convo_responses.stop_reason`,
`convo_responses.model_resolved`, and `convo_responses.provider_request_id`.

Excluded: `convo_requests.provider` — its allowlist
`CHECK (provider IN ('anthropic'))` already pins the exact canonical value, so a
trim check would be dead. JSONB columns (`content`, `request_params`, `payload`)
are out of scope — not text, and opaque per D-6.

Each of these columns also carries a `*_not_empty_check`: the empty string is
rejected, so absence is represented by `NULL`, never `''` (no-sentinel rule).
The nullable columns (`model_resolved`, `provider_request_id`) guard
`col IS NULL OR …`, so `NULL` still legitimately means "absent" while `''` is
illegal; `stop_reason` is `NOT NULL` and non-empty unconditionally.

Consideration: `stop_reason`, `model_resolved`, and `provider_request_id` are
provider-supplied. The verbatim provider response is preserved independently in
`convo_responses_raw.payload`; these columns are the typed working projection
(D-6), so normalizing them to canonical, non-empty form does not risk the
verbatim-capture mandate — fidelity lives in the raw row. The writer maps an
absent provider value to `NULL`, not `''`.
