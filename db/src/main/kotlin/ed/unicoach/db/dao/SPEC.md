# SPEC: `db/src/main/kotlin/ed/unicoach/db/dao`

## I. Overview

`db/dao/` is the sole interface between the Kotlin application and the
PostgreSQL database. Stateless DAO singletons provide every read and write
operation:

| DAO | Table(s) |
|-----|----------|
| `UsersDao` | `users`, `users_versions` |
| `SessionsDao` | `sessions` |

Every DAO method accepts a `SqlSession` as its first parameter. Connection
pooling, transaction boundaries, and commit/rollback are managed exclusively
by `Database.kt` in the parent package.

All DAO methods return `DaoResult<T>`, a unified sealed interface declared in
`DaoResult.kt`. `DaoResult<T>` has three branches — `Success<T>`,
`TransientError`, `PermanentError` — enabling consumers to handle results at
any granularity from coarse category matching to specific variant
discrimination. Database exceptions are classified by SQLSTATE into transient
or permanent at the DAO boundary.

---

## II. Invariants

### Entity Tables

Database tables fall into two categories: **entity tables** and
**non-entity tables**. Entity tables represent first-class domain objects
with standardized lifecycle columns. Non-entity tables (e.g., `job_attempts`)
have no DAO-level invariants beyond normal SQL correctness.

Entity table characteristics vary by flavor:

| Entity | Soft Delete | `*_versions` Table | `version` Column | Row Timestamps |
|--------|-------------|---------------------|------------------|----------------|
| `users` | ✓ (logical) | ✓ | ✓ | ✓ |
| `sessions` | ✗ (physical) | ✗ | ✓ | ✓ |

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
- "Force" methods (e.g., `revokeByTokenHash`) MAY skip version guards when
  the business logic does not require OCC.

### Immutable Columns

- `id` and `{row_,}created_at` MUST NEVER appear in any `UPDATE ... SET`
  clause.
- `{row_,}updated_at` and `version` are managed by database triggers and
  SHOULD NOT be set by DAO code (except `version` in the `SET` clause for OCC,
  which the trigger validates).

### Soft Delete

- `UsersDao.findById` and `UsersDao.findByIdForUpdate` MUST return
  `DaoResult.PermanentError.NotFound` for rows where `deleted_at IS NOT NULL`
  unless `includeDeleted = true` is explicitly passed.
- `UsersDao.findByEmail` MUST filter `deleted_at IS NULL` unconditionally.

### Postgres Error Codes

Specific error code mappings:

- `23505` containing index `users_email_unique_active_idx` →
  `DaoResult.PermanentError.DuplicateEmail`.
- `23505` (other index) → `DaoResult.PermanentError.ConstraintViolation`.
- `23514` → `DaoResult.PermanentError.ConstraintViolation`.
- `55P03` in `findByIdForUpdate` →
  `DaoResult.TransientError.LockAcquisitionFailure`.

All other `SQLException` are classified into `TransientError.DatabaseError` or
`PermanentError.DatabaseError` based on SQLSTATE class:

| SQLSTATE Class | Category | Examples |
|---|---|---|
| `08*` | Transient | Connection exception, broken pipe |
| `40001` | Transient | Serialization failure |
| `40P01` | Transient | Deadlock detected |
| `53*` | Transient | Insufficient resources |
| `57P*` | Transient | Operator intervention |
| Everything else | Permanent | Syntax error, type mismatch |

Non-`SQLException` (e.g., `ClassCastException`) defaults to
`PermanentError.DatabaseError` — these indicate application bugs.

### Physical Record Bypass

- `UsersDao.updatePhysicalRecord` MUST prepend `SET LOCAL
  unicoach.bypass_logical_timestamp = 'true'` via `session.prepareStatement`
  before executing the standard `doUpdate` path. This flag is scoped to the
  current transaction and MUST NOT bleed into subsequent transactions.

---

## III. Behavioral Contracts

### `SqlSession` — [`SqlSession.kt`](./SqlSession.kt)

`SqlSession` is the DAO's only interface to the database. It controls what DAO
methods can and cannot do — for example, it exposes `prepareStatement` but does
not allow `commit()` or `rollback()`.

---

### `UsersDao` — [`UsersDao.kt`](./UsersDao.kt)

All methods are `object`-level (static equivalent). All SQL is issued via
`PreparedStatement`; no string interpolation of user data is permitted.

#### `findById(session, id, includeDeleted = false): DaoResult<User>`

- **Side Effects**: Read only.
- **Error Handling**: `PermanentError.NotFound` if row absent or soft-deleted
  (when `includeDeleted = false`).
- **Idempotency**: Yes.

#### `findByIdForUpdate(session, id, includeDeleted = false): DaoResult<User>`

- **Side Effects**: Read/write — acquires a row-level exclusive lock.
- **Error Handling**: `PermanentError.NotFound` if row absent or soft-deleted.
  `TransientError.LockAcquisitionFailure` on lock contention.
- **Idempotency**: No (lock acquisition is stateful within a transaction).

#### `findByEmail(session, email): DaoResult<User>`

- **Side Effects**: Read only.
- **Error Handling**: `PermanentError.NotFound` if absent.
- **Idempotency**: Yes.

#### `findVersion(session, id, targetVersion): DaoResult<UserVersion>`

- **Side Effects**: Read only (queries the `users_versions` audit table).
- **Error Handling**: `PermanentError.NotFound` if absent.
- **Idempotency**: Yes.

#### `create(session, user): DaoResult<User>`

- **Side Effects**: Write — inserts one row. Database generates `id` via
  `uuidv7()` default.
- **Error Handling**: `PermanentError.DuplicateEmail` on active-email unique
  index violation. `PermanentError.ConstraintViolation` on other constraint
  violations.
- **Idempotency**: No.

#### `update(session, user): DaoResult<User>`

- **Side Effects**: Read/write — mutates one row with OCC.
- **Error Handling**: `PermanentError.NotFound`, `ConcurrentModification`,
  `DuplicateEmail`, `ConstraintViolation`. Full SQLSTATE discrimination
  (see §II).
- **Idempotency**: No.

#### `updatePhysicalRecord(session, user): DaoResult<User>`

- **Side Effects**: Read/write — sets `bypass_logical_timestamp` then
  delegates to `doUpdate`.
- **Error Handling**: Same as `update`.
- **Idempotency**: No.

#### `delete(session, id, currentVersion): DaoResult<User>`

- **Side Effects**: Read/write — soft-deletes (sets `deleted_at`).
- **Error Handling**: `PermanentError.NotFound`,
  `TransientError.ConcurrentModification`.
- **Idempotency**: No.

#### `undelete(session, id, currentVersion): DaoResult<User>`

- **Side Effects**: Read/write — restores a soft-deleted row.
- **Error Handling**: `PermanentError.NotFound`. May surface
  `PermanentError.DuplicateEmail` if undeleting would violate the
  active-email unique index.
- **Idempotency**: No.

#### `revertToVersion(session, id, targetHistoricalVersion, currentVersion): DaoResult<User>`

- **Side Effects**: Read/write — two round-trips (read historical version,
  then update current row).
- **Error Handling**: `PermanentError.TargetVersionMissing` if the historical
  version does not exist. Otherwise same as `update`.
- **Idempotency**: No.

---

### `SessionsDao` — [`SessionsDao.kt`](./SessionsDao.kt)

All methods delegate through `executeSafely`. All session mutations target the
`sessions` table.

#### `findByTokenHash(session, tokenHash): DaoResult<Session>`

- **Side Effects**: Read only. Filters revoked and expired sessions.
- **Error Handling**: `PermanentError.NotFound` if absent, revoked, expired,
  or if the post-fetch `contentEquals` check fails.
- **Idempotency**: Yes.

#### `create(session, newSession): DaoResult<Session>`

- **Side Effects**: Write — inserts one row. `expires_at` is computed from
  the caller-supplied `expiration` duration.
- **Idempotency**: No.

#### `remintToken(session, id, currentVersion, newUserId, newTokenHash, newExpirationSeconds): DaoResult<Session>`

- **Side Effects**: Read/write — rotates token hash and binds session to a
  user (session fixation defense).
- **Error Handling**: `PermanentError.NotFound` if version mismatch, row
  absent, or already revoked.
- **Idempotency**: No.

#### `extendExpiry(session, id, currentVersion): DaoResult<Session>`

- **Side Effects**: Read/write — extends expiry by 7 days.
- **Error Handling**: `PermanentError.NotFound` on version mismatch, row
  absent, or already revoked.
- **Idempotency**: No.

#### `revokeByTokenHash(session, tokenHash): DaoResult<Session>`

- **Side Effects**: Read/write — marks session as revoked. No version guard.
- **Error Handling**: `PermanentError.NotFound` if already revoked or absent.
- **Idempotency**: Effectively yes — a second call returns `NotFound`.

#### `expireZombieSessions(session): DaoResult<Unit>`

- **Side Effects**: Write — physically deletes all expired or revoked rows.
- **Idempotency**: Yes.

---

### Result Types — [`DaoResult.kt`](./DaoResult.kt)

All DAO methods return `DaoResult<T>`, a single generic sealed interface with
three top-level branches:

| Branch | Variants |
|--------|----------|
| `Success<T>` | `Success(value: T)` |
| `TransientError` | `ConcurrentModification`, `LockAcquisitionFailure`, `DatabaseError(error)` |
| `PermanentError` | `NotFound(message)`, `DuplicateEmail`, `TargetVersionMissing`, `ConstraintViolation(error)`, `DatabaseError(error)` |

SQLSTATE classification rules are documented in §II Postgres Error Codes.

---

## IV. Infrastructure & Environment

- **JDBC Driver**: `org.postgresql:postgresql` (version managed by root BOM).
  No ORM (Hibernate, Exposed, etc.) is used.
- **Database**: PostgreSQL 18.
- **Session-local configuration variable**: `unicoach.bypass_logical_timestamp`
  is a PostgreSQL custom GUC used by `updatePhysicalRecord`. It is set with
  `SET LOCAL` and is automatically discarded at transaction commit/rollback.

---

## V. History

- [x] [RFC-07: UsersDao](../../../../../../../rfc/07-users-dao.md) — Defined `UsersDao`, `DaoModule.kt`, and `SqlSession` (in `rest-server/`).
- [x] [RFC-08: Auth Registration](../../../../../../../rfc/08-auth-registration.md) — Relocated `UsersDao`/`SqlSession` to `service/`.
- [x] [RFC-10: Auth Login](../../../../../../../rfc/10-auth-login.md) — Added `findByEmail` to `UsersDao`.
- [x] [RFC-11: Sessions](../../../../../../../rfc/11-sessions.md) — Created `SessionsDao` with `create`, `findByTokenHash`, `expireZombieSessions`, and `executeSafely` pattern.
- [x] [RFC-13: Auth Me](../../../../../../../rfc/13-auth-me.md) — Changed `findByTokenHash` parameter from `ByteArray` to `TokenHash`; added `contentEquals` guard.
- [x] [RFC-14: DB Module](../../../../../../../rfc/14-db-module.md) — Moved all DAO files from `service/` to the `db` Gradle module at their current path.
- [x] [RFC-21: Session Expiry Queue](../../../../../../../rfc/21-session-expiry-queue.md) — Added `extendExpiry` and `remintToken` to `SessionsDao`; added `expires_at` mapping in `mapSession`.
- [x] [RFC-22: Auth Logout](../../../../../../../rfc/22-auth-logout.md) — Added `revokeByTokenHash` to `SessionsDao`.
- [x] Unified all per-entity and per-session result types into `DaoResult<T>` with `TransientError`/`PermanentError` categories. Deleted `DaoModule.kt`. SQLSTATE-based exception classification.
