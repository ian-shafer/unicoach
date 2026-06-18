# SPEC: `db/src/main/kotlin/ed/unicoach/db/dao`

## I. Overview

`db/dao/` is the sole interface between the Kotlin application and the
PostgreSQL database. Stateless DAO singletons provide every read and write
operation:

| DAO                | Table(s)                                                             |
| ------------------ | -------------------------------------------------------------------- |
| `UsersDao`         | `users`, `users_versions`                                            |
| `SessionsDao`      | `sessions`                                                           |
| `StudentsDao`      | `students`                                                           |
| `ConvosDao`        | `convos`, `convo_requests`, `convo_responses`, `convo_responses_raw` |
| `SystemPromptsDao` | `system_prompts` (read-only catalog)                                 |

Every DAO method accepts a `SqlSession` as its first parameter. Connection
pooling, transaction boundaries, and commit/rollback are managed exclusively by
`Database.kt` in the parent package.

All DAO methods return standard Kotlin `Result<T>`. Expected domain states and
successful ("happy path") operations return `Result.success(T)`. Database
exceptions are classified into `TransientError` or `PermanentError` interfaces
at the DAO boundary and wrapped in `Result.failure(Exception)`.

---

## II. Invariants

### Entity Tables

Database tables fall into two categories: **entity tables** and **non-entity
tables**. Entity tables represent first-class domain objects with standardized
lifecycle columns. Non-entity tables (e.g., `job_attempts`) have no DAO-level
invariants beyond normal SQL correctness.

Entity table characteristics vary by flavor:

| Entity     | Soft Delete  | `*_versions` Table         | `version` Column | Row Timestamps |
| ---------- | ------------ | -------------------------- | ---------------- | -------------- |
| `users`    | ✓ (logical)  | ✓                          | ✓                | ✓              |
| `sessions` | ✗ (physical) | ✗                          | ✓                | ✓              |
| `students` | ✓ (logical)  | ✓ (read by `listVersions`) | ✓                | ✓              |
| `convos`   | ✓ (logical)  | ✗                          | ✗                | ✓              |

The "Row Timestamps" column records that `row_created_at`/`row_updated_at`
columns exist and are maintained by database triggers. These columns are **not**
projected by the row mappers into any domain model — although `SELECT *` still
fetches them, no mapper reads them into a Kotlin field. The `version` column is
mapped as a plain `Int` (`rs.getInt("version")`), not a value-class wrapper.

#### Mapping Notes

- **`users` table**: The `password_hash` and `sso_provider_id` columns are
  synthesized into a sealed `AuthMethod` (variants: `Password`, `SSO`, or
  `Both`). The DB constraints already guarantee at least one column is non-NULL,
  so a row where both are NULL indicates row corruption, not user input; mapping
  MUST throw `CorruptPersistedAuthMethodException` — a `PermanentError`
  `DaoException` carrying the offending row's `UserId` — never a user-facing
  validation failure.
- **`users.is_admin`**: A plain `Boolean` mapped by `mapUser`/`mapUserVersion`
  (`rs.getBoolean("is_admin")`). It MUST be bound in every `users` write that
  reconstructs the full row — `create`, `doUpdate` (shared by `update` /
  `updatePhysicalRecord`), and `revertToVersion` — so the admin flag survives
  edits and version reverts. `NewUser.isAdmin` defaults to `false`; dropping the
  bind from any one statement silently resets a user's admin state.
- Complex columns (e.g., `email`, `name`, `display_name`) are mapped into
  strongly-typed value classes (e.g., `EmailAddress`, `PersonName`) using their
  respective `.create()` factories, unwrapping `ValidationResult.Valid`.
- **`students` table**: The decomposed
  `expected_high_school_graduation_{year,month,day}` columns are reconstructed
  into a single `PartialDate` via `PartialDate.of`, with the month/day
  NULL-state preserved through `ResultSet.wasNull`. The DB constraints already
  guarantee the persisted columns form a valid partial date, so a
  `ValidationResult.Invalid` on this read path indicates row corruption, not
  user input; it MUST be surfaced as `CorruptPersistedValueException` — a
  `PermanentError` `DaoException` carrying the offending decomposed column
  values and the structured `ValidationError` — never as a user-facing
  validation failure. The raw offending value rides on the exception, not on
  `ValidationError`.
- **`convos` table**: The persisted `name` is reconstructed via
  `ConvoName.create` (`parseConvoName`). The DB CHECK constraints already
  guarantee a non-empty, trimmed, ≤ 255-char name, so a
  `ValidationResult.Invalid` on this read path indicates row corruption, not
  user input; it MUST be raised as a `SQLException` and surfaced as a
  `PermanentError` mapping failure, never as a user-facing validation failure.
  `mapConvo` projects the nullable `archived_at` timestamp into `Convo`
  alongside `deleted_at`; the two are independent lifecycle axes.
- **`system_prompts` table**: `SystemPromptsDao` maps every column verbatim
  (`name`, `version`, `body` as plain `String`); the table carries no
  soft-delete, version, or validated value-class columns, so its mapper has no
  corruption-throwing read path.

### Transaction Boundary

- Transaction management (begin, commit, rollback) is handled exclusively by
  `../Database.kt`. DAO methods MUST NOT manage transactions.
- `SqlSession` provides necessary access to the underlying connection (via
  `prepareStatement`). Direct access to the connection is not possible through
  this interface.

### Optimistic Concurrency Control (OCC)

- `UPDATE` statements on tables with a `version` column MUST include
  `WHERE id = ? AND version = ?`. The DAO sets the new version in the `SET`
  clause; the database trigger validates it equals `old_version + 1`.
- When an UPDATE matches zero rows, the DAO MUST issue a secondary
  `SELECT version FROM <table> WHERE id = ?` to distinguish
  `TransientError.ConcurrentModification` (row exists, wrong version) from
  `PermanentError.NotFound` (row absent).
- "Force" methods (e.g., `revokeByTokenHash`) MAY skip version guards when the
  business logic does not require OCC.
- `ConvosDao` mutations (`create`, `rename`, `delete`, `undelete`, `archive`,
  `unarchive`) MUST NOT include a version guard: `convos` has no `version`
  column, so convo writes are last-write-wins. `rename`/`delete`/`undelete`/
  `archive`/`unarchive` gate solely on the soft-delete state in the `WHERE`
  clause (`deleted_at IS NULL` for `rename`/`delete`/`archive`/`unarchive`,
  `IS NOT NULL` for `undelete`) and return `NotFoundException` when zero rows
  match.

### Immutable Columns

- `id` and `{row_,}created_at` MUST NEVER appear in any `UPDATE ... SET` clause.
- `{row_,}updated_at` and `version` are managed by database triggers and MUST
  NOT be set by DAO code (except `version` in the `SET` clause for OCC, which
  the trigger validates).

### Soft Delete

- `UsersDao.findById` and `UsersDao.findByIdForUpdate` MUST return
  `Result.failure(NotFoundException())` for rows where `deleted_at IS NOT NULL`
  unless `includeDeleted = true` is explicitly passed.
- `UsersDao.findByEmail` MUST filter `deleted_at IS NULL` unconditionally.
- `StudentsDao.findById` and `StudentsDao.findByUserId` MUST return
  `Result.failure(NotFoundException())` for soft-deleted rows unless
  `includeDeleted = true`. `StudentsDao.findByUserIdForUpdate` filters
  `deleted_at IS NULL` unconditionally in SQL; `StudentsDao.findByIdForUpdate`
  locks by primary key without a soft-delete filter.
- `ConvosDao` reads take an explicit `scope: SoftDeleteScope = ACTIVE` (`ACTIVE`
  → `deleted_at IS NULL`, `DELETED` → `deleted_at IS NOT NULL`, `ALL` → no
  filter). `listByStudent` and `listTurns` apply the scope as a SQL predicate;
  `findById` fetches by primary key and applies the scope in application code
  (`scopeAdmits`), returning `NotFoundException()` when the row's `deleted_at`
  does not satisfy the scope.
- `ConvosDao.listTurns` MUST scope on the owning convo's `c.deleted_at` (the
  parent entity), NOT on the append-only log rows — turns of a soft-deleted
  convo are hidden under `ACTIVE`.
- The `*WithActivity` reads (`listByStudentWithActivity`,
  `findByIdWithActivity`) MUST exclude soft-deleted convos per their `scope`
  predicate on `c.deleted_at`. `listByStudentWithActivity` additionally filters
  on the orthogonal `archived_at` axis via an `ArchiveScope` predicate
  (`UNARCHIVED` → `archived_at IS NULL`, `ARCHIVED` → `IS NOT NULL`, `ALL` → no
  filter), defaulting to `UNARCHIVED`.

### Physical Delete (`SessionsDao`)

- `sessions` is the only entity table with no `deleted_at` column and no
  `prevent_physical_delete` trigger, so it has no soft-delete axis.
- `SessionsDao.deleteById` MUST issue a genuine physical
  `DELETE FROM sessions WHERE id = ?` and return `NotFoundException` when zero
  rows are removed. This is the sole row-level physical delete in the DAO layer
  — every other entity `delete` is a soft-delete `UPDATE ... SET deleted_at`. A
  refactor MUST NOT convert it to a soft delete: the table has no column to set.
- Because `sessions` has no soft-delete axis, `SessionsDao` read methods MUST
  NOT accept a `SoftDeleteScope`.

### Admin Read Surface

The admin website pages entire tables and inspects version history. These reads
exist only for that surface; the application path never needed them.

- **Paging.** `UsersDao.listAll`, `SessionsDao.listAll`, and
  `SessionsDao.listByUser` MUST order newest-first (`ORDER BY created_at DESC`,
  with `id` as a deterministic tiebreak) and page via `LIMIT ?`/`OFFSET ?` bound
  parameters. The caller supplies `limit`/`offset`; the DAO MUST NOT embed a
  page size.
- **Soft-delete scope asymmetry.** `UsersDao.listAll` MUST accept a
  `scope: SoftDeleteScope` defaulting to `ALL` (admin lists keep soft-deleted
  rows visible), applied as a fixed SQL predicate carrying no caller data. The
  `SessionsDao` list methods MUST NOT accept a scope — `sessions` has no
  soft-delete axis (see Physical Delete). This divergence is deliberate; the
  signatures MUST NOT be harmonized.
- **Version history ordering.** `UsersDao.listVersions` and
  `StudentsDao.listVersions` MUST return the full `*_versions` history for one
  id ordered **ascending by `version`** (replay order), and MUST return an empty
  list (never `NotFoundException`) for an id with no history.
- `StudentsDao.listVersions` reads `students_versions` into `StudentVersion` via
  `mapStudentVersion`, which shares `mapGraduationDate` and therefore the same
  `CorruptPersistedValueException` read-corruption path as `mapStudent`.

### Archive (`ConvosDao`)

- `archived_at` is a lifecycle axis **independent** of soft-delete: a convo MAY
  be archived, deleted, both, or neither. Archive does NOT hide a convo from the
  soft-delete scopes and vice versa.
- `archive` and `unarchive` MUST be idempotent toggles. `archive` sets
  `archived_at = COALESCE(archived_at, NOW())` (re-archiving preserves the
  original archive instant); `unarchive` sets `archived_at = NULL` (succeeds
  even on a never-archived row). Neither carries a `version` guard.
- Both MUST reject soft-deleted rows — the `WHERE id = ? AND deleted_at IS NULL`
  clause returns `NotFoundException` when the row is absent or soft-deleted.
- Both MUST first issue `SET LOCAL unicoach.bypass_logical_timestamp = 'true'`
  so the `update_timestamp` trigger does NOT advance `updated_at` — `updated_at`
  advances on `rename` only (precedent: `UsersDao.updatePhysicalRecord`).
- `SET LOCAL` persists for the remainder of the caller transaction. A caller
  combining `rename` and an archive toggle in one transaction MUST `rename`
  first, or the rename's `updated_at` bump is suppressed.
- `listByStudentWithActivity` and `findByIdWithActivity` MUST derive
  `lastActivityAt` from `MAX(convo_requests.created_at)` (NULL when the convo
  has no turns) via a single `LEFT JOIN` grouped by convo. The MAX includes
  failed and orphan-request turns — recency reflects any request row, not only
  successfully answered ones. `listByStudentWithActivity` MUST order by activity
  descending with `NULLS LAST`, then `created_at` descending, then `id` (a
  deterministic tiebreak).

### Coaching Turn Writes (`ConvosDao`)

- A coaching turn MUST be written as two separate calls — `appendRequest` then
  `appendResponse` — each within its own caller transaction. The DAO MUST NOT
  expose a combined "write whole turn" method: the request is durably recorded
  before the model is called, so a request with no reply survives as an orphan
  request (later joined to a `null` response by `listTurns`).
- `appendResponse` MUST insert the `convo_responses` row and, when `rawPayload`
  is non-null, the `convo_responses_raw` sibling within the single transaction
  the caller provides; the response and its raw row are atomic together. A null
  `rawPayload` is the transport-error turn (`stop_reason = "error"`,
  `content = null`) and writes only the response row.
- `appendResponse` MUST take `convoId` from the caller-supplied
  `NewConvoResponse` and MUST NOT issue a derivation `SELECT` to re-read it from
  the parent request; `convo_responses.convo_id` is denormalized.

### Postgres Error Codes

Specific error code mappings:

- `23505` containing index `users_email_unique_active_idx` →
  `Result.failure(DuplicateEmailException())`.
- `23505` containing index `students_user_id_unique_idx` →
  `Result.failure(StudentAlreadyExistsException())`. The index is total (no
  `WHERE deleted_at IS NULL` predicate), so a soft-deleted student still
  reserves its owner's slot.
- `23505` (other index) → `Result.failure(ConstraintViolationException())`.
- `22008` (datetime field overflow; `StudentsDao` only) and `23514` (check
  violation) → `Result.failure(ConstraintViolationException())`. On
  `StudentsDao` these surface the `students` grad-date constraints as a
  permanent, caller-correctable validation failure. `UsersDao` routes `22008`
  through the generic classification (→ `DatabaseException`).
- `23503` (foreign key violation) on `StudentsDao` create/update →
  `Result.failure(NotFoundException())` (owning user absent).
- `55P03` in any `*ForUpdate` method (issued via `SELECT ... FOR UPDATE NOWAIT`)
  → `Result.failure(LockAcquisitionFailureException())`.

All other `SQLException` are classified into `TransientError` or
`PermanentError` via `DaoException` implementations based on SQLSTATE class:

| SQLSTATE Class  | Category  | Examples                          |
| --------------- | --------- | --------------------------------- |
| `08*`           | Transient | Connection exception, broken pipe |
| `40001`         | Transient | Serialization failure             |
| `40P01`         | Transient | Deadlock detected                 |
| `53*`           | Transient | Insufficient resources            |
| `57P*`          | Transient | Operator intervention             |
| Everything else | Permanent | Syntax error, type mismatch       |

`mapDatabaseError` MUST return an already-domain-typed `DaoException` unchanged
— it NEVER re-wraps one in `DatabaseException` or `TransientDatabaseException`.
Corruption exceptions thrown inside row mappers
(`CorruptPersistedValueException`, `CorruptPersistedAuthMethodException`)
therefore cross the catch-all `catch` boundary intact.

Non-`DaoException` throwables that are also not `SQLException` (e.g.,
`ClassCastException`) default to `DatabaseException` (Permanent) — these
indicate application bugs.

#### `ConvosDao` (`mapConvoError`)

The write paths (`create`, `rename`, `archive`, `unarchive`, `appendRequest`,
`appendResponse`) route `SQLException` through `mapConvoError`; reads (including
the `*WithActivity` reads) and the soft-delete mutations (`delete`, `undelete`)
route through `mapDatabaseError`. `mapConvoError` introduces **no new
`DaoException` type** — it reuses `NotFoundException` and
`ConstraintViolationException` only.

- `23503` (FK violation) → `NotFoundException`, with the message resolved from
  the violated constraint name matched against the exception message:
  - `convos_student_id_fkey` → "Owning student not found"
  - `convo_requests_convo_id_fkey` → "Convo not found"
  - `convo_requests_system_prompt_id_fkey` → "System prompt not found"
  - `convo_responses_request_id_fkey` → "Request not found"
  - `convo_responses_convo_id_fkey` → "Convo not found"
  - unmatched constraint name → bare `NotFoundException()`.
- `23505` (unique violation — the `convo_responses.request_id` 1:1 guard, or the
  `convo_responses_raw.response_id` primary key) and `23514` (any
  `convos`/`convo_requests`/`convo_responses` CHECK, e.g. the 1 MiB content
  bound, the `provider IN ('anthropic')` allowlist, the content/model presence
  checks, or the non-negative token/latency checks) →
  `ConstraintViolationException`.
- All other SQLSTATE → `mapDatabaseError` (the shared classification above).

### Physical Record Bypass

- `UsersDao.updatePhysicalRecord` and `ConvosDao.archive`/`unarchive` MUST
  prepend `SET LOCAL unicoach.bypass_logical_timestamp = 'true'` via
  `session.prepareStatement` before executing their `UPDATE`. The flag
  suppresses the `update_timestamp` trigger so `updated_at`/`row_updated_at`
  does not advance. It is scoped to the current transaction (`SET LOCAL`) and
  MUST NOT bleed into subsequent transactions.

---

## III. Behavioral Contracts

### `SqlSession` — [`SqlSession.kt`](./SqlSession.kt)

`SqlSession` is the DAO's only interface to the database. It controls what DAO
methods can and cannot do — for example, it exposes `prepareStatement` but does
not allow `commit()` or `rollback()`.

```kotlin
interface SqlSession {
  fun prepareStatement(sql: String): PreparedStatement
}
```

---

### `UsersDao` — [`UsersDao.kt`](./UsersDao.kt)

All methods are `object`-level (static equivalent). All SQL is issued via
`PreparedStatement`; no string interpolation of user data is permitted.

#### `findById(session, id, includeDeleted = false): Result<User>`

- **Side Effects**: Read only.
- **Error Handling**: `Result.failure(NotFoundException())` if row absent or
  soft-deleted (when `includeDeleted = false`).
- **Idempotency**: Yes.

#### `findByIdForUpdate(session, id, includeDeleted = false): Result<User>`

- **Side Effects**: Read/write — acquires a row-level exclusive lock.
- **Error Handling**: `Result.failure(NotFoundException())` if row absent or
  soft-deleted. `Result.failure(LockAcquisitionFailureException())` on lock
  contention.
- **Idempotency**: No (lock acquisition is stateful within a transaction).

#### `findByEmail(session, email): Result<User>`

- **Side Effects**: Read only.
- **Error Handling**: `Result.failure(NotFoundException())` if absent.
- **Idempotency**: Yes.

#### `findVersion(session, id, targetVersion): Result<UserVersion>`

- **Side Effects**: Read only (queries the `users_versions` audit table).
- **Error Handling**: `Result.failure(NotFoundException())` if absent.
- **Idempotency**: Yes.

#### `create(session, user): Result<User>`

- **Side Effects**: Write — inserts one row. Database generates `id` via
  `uuidv7()` default.
- **Error Handling**: `Result.failure(DuplicateEmailException())` on
  active-email unique index violation.
  `Result.failure(ConstraintViolationException())` on other constraint
  violations.
- **Idempotency**: No.

#### `update(session, user): Result<User>`

- **Side Effects**: Read/write — mutates one row with OCC.
- **Error Handling**: `Result.failure(Exception)` containing
  `NotFoundException`, `ConcurrentModificationException`,
  `DuplicateEmailException`, or `ConstraintViolationException`.
- **Idempotency**: No.

#### `updatePhysicalRecord(session, user): Result<User>`

- **Side Effects**: Read/write — sets `bypass_logical_timestamp` then delegates
  to `doUpdate` (a private method used to share execution logic between standard
  and physical updates).
- **Error Handling**: Same as `update`.
- **Idempotency**: No.

#### `delete(session, id, currentVersion): Result<User>`

- **Side Effects**: Read/write — soft-deletes (sets `deleted_at`).
- **Error Handling**: `Result.failure(Exception)` with `NotFoundException` or
  `ConcurrentModificationException`.
- **Idempotency**: No.

#### `undelete(session, id, currentVersion): Result<User>`

- **Side Effects**: Read/write — restores a soft-deleted row.
- **Error Handling**: `Result.failure(NotFoundException())`. May surface
  `DuplicateEmailException` if undeleting would violate the active-email unique
  index.
- **Idempotency**: No.

#### `revertToVersion(session, id, targetHistoricalVersion, currentVersion): Result<User>`

- **Side Effects**: Read/write — two round-trips (read historical version, then
  update current row).
- **Error Handling**: `Result.failure(TargetVersionMissingException())` if the
  historical version does not exist. Otherwise same as `update`.
- **Idempotency**: No.

#### `listAll(session, scope = SoftDeleteScope.ALL, limit, offset): Result<List<User>>`

- **Side Effects**: Read only. Pages the full `users` table newest-first;
  `scope` is a fixed SQL predicate (no caller data).
- **Error Handling**: Returns `Result.success(emptyList())` when no rows match
  the page; `mapDatabaseError` on failure.
- **Idempotency**: Yes.

#### `listVersions(session, id): Result<List<UserVersion>>`

- **Side Effects**: Read only — queries the `users_versions` audit table.
- **Error Handling**: Returns `Result.success(emptyList())` for an id with no
  history; `mapDatabaseError` on failure.
- **Idempotency**: Yes.

---

### `SessionsDao` — [`SessionsDao.kt`](./SessionsDao.kt)

All methods delegate through `executeSafely`. All session mutations target the
`sessions` table.

#### `findByTokenHash(session, tokenHash): Result<Session>`

- **Side Effects**: Read only. Filters revoked and expired sessions.
- **Error Handling**: `Result.failure(NotFoundException())` if absent, revoked,
  expired, or if the post-fetch `contentEquals` check fails.
- **Idempotency**: Yes.

#### `create(session, newSession): Result<Session>`

- **Side Effects**: Write — inserts one row. `expires_at` is computed from the
  caller-supplied `expiration` duration.
- **Error Handling**: No contractual errors. `Result.failure(DatabaseException)`
  if `RETURNING` yields no row; all other failures classified per §II.
- **Idempotency**: No.

#### `remintToken(session, id, currentVersion, newUserId, newTokenHash, newExpirationSeconds): Result<Session>`

- **Side Effects**: Read/write — rotates token hash and binds session to a user
  (session fixation defense).
- **Error Handling**: `Result.failure(NotFoundException())` if version mismatch,
  row absent, or already revoked.
- **Idempotency**: No.

#### `extendExpiry(session, id, currentVersion): Result<Session>`

- **Side Effects**: Read/write — resets `expires_at` to 7 days from now
  (absolute reset, not additive).
- **Error Handling**: `Result.failure(NotFoundException())` on version mismatch,
  row absent, or already revoked.
- **Idempotency**: No.

#### `revokeByTokenHash(session, tokenHash): Result<Session>`

- **Side Effects**: Read/write — marks session as revoked. No version guard.
- **Error Handling**: `Result.failure(NotFoundException())` if already revoked
  or absent.
- **Idempotency**: Effectively yes — a second call returns `NotFound`.

#### `expireZombieSessions(session): Result<Unit>`

- **Side Effects**: Write — physically deletes all expired or revoked rows.
- **Error Handling**: No contractual errors; failures classified per §II.
- **Idempotency**: Yes.

#### `findById(session, id): Result<Session>`

- **Side Effects**: Read only. Does NOT filter revoked or expired sessions
  (admin detail must see them), unlike `findByTokenHash`.
- **Error Handling**: `Result.failure(NotFoundException())` if absent.
- **Idempotency**: Yes.

#### `listByUser(session, userId, limit, offset): Result<List<Session>>`

- **Side Effects**: Read only. Pages one user's sessions newest-first
  (`created_at DESC, id`). No `SoftDeleteScope` — `sessions` has no soft-delete.
- **Error Handling**: Returns `Result.success(emptyList())` when none match;
  failures classified per §II.
- **Idempotency**: Yes.

#### `listAll(session, limit, offset): Result<List<Session>>`

- **Side Effects**: Read only. Pages the full `sessions` table newest-first. No
  scope filter.
- **Error Handling**: Returns `Result.success(emptyList())` when none match;
  failures classified per §II.
- **Idempotency**: Yes.

#### `deleteById(session, id): Result<Unit>`

- **Side Effects**: Write — physical `DELETE FROM sessions` (no soft-delete
  axis; see §II Physical Delete).
- **Error Handling**: `Result.failure(NotFoundException())` when zero rows are
  removed; failures classified per §II.
- **Idempotency**: No — a second call returns `NotFound`.

---

### `StudentsDao` — [`StudentsDao.kt`](./StudentsDao.kt)

All methods are `object`-level. Create/update/delete SQLSTATE discrimination is
centralized in the private `mapCreateUpdateError`; all other failures route
through `mapDatabaseError`. Each grad-date column trio is reconstructed into a
`PartialDate` per §II Mapping Notes.

#### `findById(session, id, includeDeleted = false): Result<Student>`

- **Side Effects**: Read only.
- **Error Handling**: `Result.failure(NotFoundException())` if row absent or
  soft-deleted (when `includeDeleted = false`).
- **Idempotency**: Yes.

#### `findByUserId(session, userId, includeDeleted = false): Result<Student>`

- **Side Effects**: Read only.
- **Error Handling**: `Result.failure(NotFoundException())` if row absent or
  soft-deleted (when `includeDeleted = false`).
- **Idempotency**: Yes.

#### `findByIdForUpdate(session, id): Result<Student>`

- **Side Effects**: Read/write — acquires a row-level exclusive lock via
  `FOR UPDATE NOWAIT`. No soft-delete filter.
- **Error Handling**: `Result.failure(NotFoundException())` if row absent.
  `Result.failure(LockAcquisitionFailureException())` on lock contention.
- **Idempotency**: No (lock acquisition is stateful within a transaction).

#### `findByUserIdForUpdate(session, userId): Result<Student>`

- **Side Effects**: Read/write — acquires a row-level exclusive lock via
  `FOR UPDATE NOWAIT`, filtering `deleted_at IS NULL`.
- **Error Handling**: `Result.failure(NotFoundException())` if row absent or
  soft-deleted. `Result.failure(LockAcquisitionFailureException())` on lock
  contention.
- **Idempotency**: No.

#### `create(session, student): Result<Student>`

- **Side Effects**: Write — inserts one row (`RETURNING *`). Database generates
  `id` and timestamps.
- **Error Handling**: `Result.failure(StudentAlreadyExistsException())` on the
  total `students_user_id_unique_idx` violation (including against a
  soft-deleted row); `Result.failure(ConstraintViolationException())` on
  grad-date constraint violations (`22008`/`23514`);
  `Result.failure(NotFoundException())` on FK violation (`23503`).
- **Idempotency**: No.

#### `update(session, student): Result<Student>`

- **Side Effects**: Read/write — mutates one row with OCC
  (`WHERE id = ? AND version = ?`, `RETURNING *`).
- **Error Handling**: On zero rows matched, issues the secondary
  `SELECT version` to return `ConcurrentModificationException` (row exists) or
  `NotFoundException` (row absent). Otherwise same constraint mapping as
  `create`.
- **Idempotency**: No.

#### `delete(session, id, currentVersion): Result<Student>`

- **Side Effects**: Read/write — soft-deletes (sets `deleted_at = NOW()`) with
  OCC, `RETURNING *`.
- **Error Handling**: On zero rows matched, the secondary `SELECT version`
  distinguishes `ConcurrentModificationException` from `NotFoundException`.
- **Idempotency**: No.

#### `listVersions(session, id): Result<List<StudentVersion>>`

- **Side Effects**: Read only — queries the `students_versions` audit table,
  mapping each row to `StudentVersion` (same grad-date corruption path as
  `mapStudent`).
- **Error Handling**: Returns `Result.success(emptyList())` for an id with no
  history; `mapDatabaseError` on failure.
- **Idempotency**: Yes.

---

### `ConvosDao` — [`ConvosDao.kt`](./ConvosDao.kt)

All methods are `object`-level over the coaching-conversation tables. Write
paths discriminate SQLSTATE via the private `mapConvoError`; all other failures
route through `mapDatabaseError`. The `SoftDeleteScope` predicates are fixed SQL
fragments carrying no caller data; no user data is string-interpolated into SQL.

#### `findById(session, id, scope = ACTIVE): Result<Convo>`

- **Side Effects**: Read only.
- **Error Handling**: `Result.failure(NotFoundException())` if the row is absent
  or its `deleted_at` does not satisfy `scope` (filtered in application code via
  `scopeAdmits`).
- **Idempotency**: Yes.

#### `listByStudent(session, studentId, scope = ACTIVE): Result<List<Convo>>`

- **Side Effects**: Read only. Filters `deleted_at` by `scope` in SQL; orders by
  `created_at, id`.
- **Error Handling**: Returns `Result.success(emptyList())` when no convos
  match; `mapDatabaseError` on failure.
- **Idempotency**: Yes.

#### `create(session, convo: NewConvo): Result<Convo>`

- **Side Effects**: Write — inserts one `convos` row (`RETURNING *`). Database
  generates `id` (`uuidv7()`) and timestamps.
- **Error Handling**: `mapConvoError` —
  `NotFoundException("Owning student not
  found")` on the `student_id` FK
  violation; `ConstraintViolationException` on a name CHECK violation.
- **Idempotency**: No.

#### `rename(session, id, name): Result<Convo>`

- **Side Effects**: Read/write — updates `name` where `deleted_at IS NULL`
  (`RETURNING *`). No version guard.
- **Error Handling**: `Result.failure(NotFoundException())` when zero rows match
  (absent or soft-deleted); `ConstraintViolationException` on a name CHECK.
- **Idempotency**: No (the timestamp trigger bumps `updated_at` each call).

#### `delete(session, id): Result<Convo>`

- **Side Effects**: Read/write — soft-deletes (`deleted_at = NOW()`) where
  `deleted_at IS NULL` (`RETURNING *`).
- **Error Handling**: `Result.failure(NotFoundException())` when already deleted
  or absent.
- **Idempotency**: No — a second call returns `NotFound`.

#### `undelete(session, id): Result<Convo>`

- **Side Effects**: Read/write — clears `deleted_at` where
  `deleted_at IS NOT
  NULL` (`RETURNING *`).
- **Error Handling**: `Result.failure(NotFoundException())` when already active
  or absent.
- **Idempotency**: No — a second call returns `NotFound`.

#### `archive(session, id): Result<Convo>`

- **Side Effects**: Read/write — sets `bypass_logical_timestamp`, then sets
  `archived_at = COALESCE(archived_at, NOW())` where `deleted_at IS NULL`
  (`RETURNING *`). No version guard. Does NOT advance `updated_at`.
- **Error Handling**: `Result.failure(NotFoundException())` when the row is
  absent or soft-deleted. `mapConvoError` on other `SQLException`.
- **Idempotency**: Yes — re-archiving an already-archived row preserves the
  original `archived_at` and succeeds.

#### `unarchive(session, id): Result<Convo>`

- **Side Effects**: Read/write — sets `bypass_logical_timestamp`, then sets
  `archived_at = NULL` where `deleted_at IS NULL` (`RETURNING *`). No version
  guard. Does NOT advance `updated_at`.
- **Error Handling**: `Result.failure(NotFoundException())` when the row is
  absent or soft-deleted. `mapConvoError` on other `SQLException`.
- **Idempotency**: Yes — succeeds on an already-active (never-archived) row.

#### `listByStudentWithActivity(session, studentId, archive = UNARCHIVED, scope = ACTIVE): Result<List<ConvoWithActivity>>`

- **Side Effects**: Read only. Single `LEFT JOIN convo_requests` grouped by
  convo; derives `lastActivityAt = MAX(convo_requests.created_at)` (null with no
  turns). Filters `c.deleted_at` by `scope` and `c.archived_at` by `archive`.
  Orders by activity `DESC NULLS LAST`, then `created_at DESC`, then `id`.
- **Error Handling**: Returns `Result.success(emptyList())` when none match;
  `mapDatabaseError` on failure.
- **Idempotency**: Yes.

#### `findByIdWithActivity(session, id, scope = ACTIVE): Result<ConvoWithActivity>`

- **Side Effects**: Read only. Same `LEFT JOIN`/`MAX` derivation as
  `listByStudentWithActivity`, scoped on `c.deleted_at`.
- **Error Handling**: `Result.failure(NotFoundException())` when no row matches
  the scope; `mapDatabaseError` on failure.
- **Idempotency**: Yes.

#### `appendRequest(session, request: NewConvoRequest): Result<ConvoRequest>`

- **Side Effects**: Write — inserts one `convo_requests` row (`RETURNING *`).
  First half of a coaching turn; committed before the model is called.
- **Error Handling**: `mapConvoError` — `NotFoundException` on the `convo_id` or
  `system_prompt_id` FK violation; `ConstraintViolationException` on a CHECK
  (e.g. the 1 MiB content bound, the `provider` allowlist).
- **Idempotency**: No.

#### `appendResponse(session, response: NewConvoResponse, rawPayload): Result<ConvoResponse>`

- **Side Effects**: Write — inserts one `convo_responses` row and, when
  `rawPayload != null`, the `convo_responses_raw` sibling, both within the
  caller's single transaction (atomic together). `convoId` is taken from
  `NewConvoResponse`; no derivation SELECT.
- **Error Handling**: `mapConvoError` — `NotFoundException` on the `request_id`
  or `convo_id` FK violation; `ConstraintViolationException` on the `request_id`
  UNIQUE (duplicate response) or a content/model/token CHECK.
- **Idempotency**: No — the `request_id` UNIQUE constraint rejects a second
  response for the same request.

#### `listTurns(session, convoId, scope = ACTIVE): Result<List<ConvoTurn>>`

- **Side Effects**: Read only. LEFT JOINs `convo_requests` to `convo_responses`,
  scoping on the owning convo's `c.deleted_at` by `scope`; orders by
  `r.created_at, r.id`.
- **Error Handling**: `ConvoTurn.response` is `null` when the request has no
  response row yet (request committed before the response was written). The
  verbatim raw payload is deliberately excluded from turns — fetch it on demand
  via `findRawByResponseId`. `mapDatabaseError` on failure.
- **Idempotency**: Yes.

#### `findRawByResponseId(session, responseId): Result<ConvoResponseRaw>`

- **Side Effects**: Read only.
- **Error Handling**: `Result.failure(NotFoundException())` when no raw row
  exists (including error turns, which write no raw sibling).
- **Idempotency**: Yes.

---

### `SystemPromptsDao` — [`SystemPromptsDao.kt`](./SystemPromptsDao.kt)

Read-only reader over the immutable `system_prompts` catalog. Rows are authored
by migration, never by the application, so this DAO exposes **no write
methods**. Stateless `object`; failures route through `mapDatabaseError`.

#### `findByNameAndVersion(session, name, version): Result<SystemPrompt>`

- **Side Effects**: Read only. Resolves the catalog row for the
  `(name, version)` UNIQUE key.
- **Error Handling**: `Result.failure(NotFoundException())` when no row matches;
  `mapDatabaseError` on failure.
- **Idempotency**: Yes.

---

### Result Types — Kotlin `Result<T>`

All DAO methods return standard Kotlin `Result<T>`. Exceptions are wrapped in
`Result.failure()` and are derived from `DaoException` — defined in
[DaoExceptions.kt](./DaoExceptions.kt) — which extends `RuntimeException` and
may implement `TransientError` or `PermanentError`.

SQLSTATE classification rules are documented in §II Postgres Error Codes.

---

## IV. Infrastructure & Environment

- **JDBC Driver**: `org.postgresql:postgresql` (version managed by root BOM). No
  ORM (Hibernate, Exposed, etc.) is used.
- **Database**: PostgreSQL 18.
- **Session-local configuration variable**: `unicoach.bypass_logical_timestamp`
  is a PostgreSQL custom GUC used by `updatePhysicalRecord`. It is set with
  `SET LOCAL` and is automatically discarded at transaction commit/rollback.

---

## V. History

- [x] [RFC-07: UsersDao](../../../../../../../../rfc/07-users-dao.md) — Defined
      `UsersDao`, `DaoModule.kt`, and `SqlSession` (in `rest-server/`).
- [x] [RFC-08: Auth Registration](../../../../../../../../rfc/08-auth-registration.md)
      — Relocated `UsersDao`/`SqlSession` to `service/`.
- [x] [RFC-10: Auth Login](../../../../../../../../rfc/10-auth-login.md) — Added
      `findByEmail` to `UsersDao`.
- [x] [RFC-11: Sessions](../../../../../../../../rfc/11-sessions.md) — Created
      `SessionsDao` with `create`, `findByTokenHash`, `expireZombieSessions`,
      and `executeSafely` pattern.
- [x] [RFC-13: Auth Me](../../../../../../../../rfc/13-auth-me.md) — Changed
      `findByTokenHash` parameter from `ByteArray` to `TokenHash`; added
      `contentEquals` guard.
- [x] [RFC-14: DB Module](../../../../../../../../rfc/14-db-module.md) — Moved
      all DAO files from `service/` to the `db` Gradle module at their current
      path.
- [x] [RFC-21: Session Expiry Queue](../../../../../../../../rfc/21-session-expiry-queue.md)
      — Added `extendExpiry` and `remintToken` to `SessionsDao`; added
      `expires_at` mapping in `mapSession`.
- [x] [RFC-22: Auth Logout](../../../../../../../../rfc/22-auth-logout.md) —
      Added `revokeByTokenHash` to `SessionsDao`.
- [x] [RFC-24: Result Types Refactoring](../../../../../../../../rfc/24-result-types.md)
      — Unified all per-entity and per-session result types into standard Kotlin
      `Result<T>` with `TransientError`/`PermanentError` categories (deleted
      `DaoResult.kt`); introduced `DaoExceptions.kt` and SQLSTATE-based
      exception classification.
- [x] [RFC-31: Student Profile](../../../../../../../../rfc/31-student-profile.md)
      — Added `StudentsDao` (`findById`, `findByUserId`, `findByIdForUpdate`,
      `findByUserIdForUpdate`, `create`, `update`, `delete`) over the `students`
      table with `PartialDate` column reconstruction, `FOR UPDATE NOWAIT`
      locking, and the `mapCreateUpdateError` SQLSTATE map
      (`23505`→`StudentAlreadyExistsException`,
      `22008`/`23514`→`ConstraintViolationException`,
      `23503`→`NotFoundException`); added `StudentAlreadyExistsException` to
      `DaoExceptions.kt`.
- [x] [RFC-34: Transactional Email Service](../../../../../../../../rfc/34-transactional-email-service.md)
      — Repointed `EmailAddress`/`ValidationResult` imports in `UsersDao` and
      `StudentsDao` from `ed.unicoach.db.models` to `ed.unicoach.common.models`.
      No behavioral change to any DAO contract.
- [x] [RFC-36: Entity Model Capability Taxonomy](../../../../../../../../rfc/36-entity-model-taxonomy.md)
      — Row mappers stopped projecting `row_created_at`/`row_updated_at` into
      the models and read `version` as a plain `Int` (`rs.getInt("version")`);
      version parameters/binds across `UsersDao` and `StudentsDao` became bare
      `Int`; `SessionsDao.mapSession` maps `id` as a typed `SessionId`, and
      `remintToken`/`extendExpiry` take `id: SessionId`. No schema change —
      `SELECT *`/`RETURNING *` still fetch the untouched
      `version`/`row_*_at`/`id` columns.
- [x] [RFC-38: Convos DAO](../../../../../../../../rfc/38-convos-dao.md) — Added
      `ConvosDao` over the coaching-conversation tables (`convos`,
      `convo_requests`, `convo_responses`, `convo_responses_raw`) with
      `SoftDeleteScope`-aware reads (`findById`, `listByStudent`, `listTurns`),
      the two-transaction coaching turn (`appendRequest`, then
      `appendResponse` + optional raw sibling atomic within the caller
      transaction), on-demand `findRawByResponseId`, no OCC/`version` guard on
      `convos`, and the `mapConvoError` SQLSTATE map (`23503`→per-FK
      `NotFoundException`, `23505`/`23514`→`ConstraintViolationException`); no
      new `DaoException` type.
- [x] [RFC-40: Validation Error Reporting](../../../../../../../../rfc/40-validation-error-reporting.md)
      — Added `CorruptPersistedValueException` and
      `CorruptPersistedAuthMethodException` to `DaoExceptions.kt`; added the
      `DaoException` pass-through guard in `mapDatabaseError`; row mappers now
      throw corruption exceptions when persisted state fails domain
      re-validation.
- [x] [RFC-45: Coaching Service and Conversation REST Surface](../../../../../../../../rfc/45-coaching-service.md)
      — Added `SystemPromptsDao` (read-only `system_prompts` catalog reader;
      `findByNameAndVersion`). Added `ConvosDao.archive`/`unarchive` (idempotent
      `archived_at` toggles via `COALESCE(…, NOW())` / `NULL`, gated on
      `deleted_at IS NULL`, suppressing the `update_timestamp` trigger with the
      `bypass_logical_timestamp` GUC so `updated_at` does not advance) and the
      activity-derived reads `listByStudentWithActivity` (with `ArchiveScope`
      filtering) / `findByIdWithActivity` (single `LEFT JOIN` deriving
      `lastActivityAt = MAX(convo_requests.created_at)`); `mapConvo` now
      projects `archived_at`.
- [x] [RFC-60: Admin Website](../../../../../../../../rfc/60-admin-website.md) —
      Threaded `is_admin` through the `users` read/write path
      (`mapUser`/`mapUserVersion` read it; `create`, `doUpdate`, and
      `revertToVersion` bind it). Added the admin read surface:
      `UsersDao.listAll` (with `SoftDeleteScope`) and `UsersDao.listVersions`;
      `StudentsDao.listVersions` (reading `students_versions` →
      `StudentVersion`); and `SessionsDao.findById`, `listByUser`, `listAll` (no
      `SoftDeleteScope` — `sessions` has no soft-delete) and `deleteById`
      (physical `DELETE`, permitted by the absence of `prevent_physical_delete`
      on `sessions`). List reads order `created_at DESC` with `limit`/`offset`;
      `listVersions` orders ascending by `version`.
