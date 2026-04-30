# SPEC: `db/src/main/kotlin/ed/unicoach/db/dao`

## I. Overview

This directory is the **data access layer** for the `db` Gradle module. It
exposes two stateless DAO singletons (`UsersDao`, `SessionsDao`) and the
shared infrastructure they depend on: the `SqlSession` transaction-scope
facade and the sealed-interface result types declared in `DaoModule.kt`. All
database reads and writes for `users` and `sessions` rows MUST pass through
this layer.

---

## II. Invariants

### Transaction Boundary

- The `SqlSession` interface MUST expose only `prepareStatement(sql: String):
  PreparedStatement`. It MUST NOT expose `commit()`, `rollback()`, or any other
  connection-lifecycle method, making it a compile-time guarantee that DAO
  methods cannot mutate transaction state.
- Every DAO method MUST accept a `SqlSession` as its first parameter. Direct
  use of `java.sql.Connection` inside DAO methods is NEVER permitted.

### Optimistic Concurrency Control (OCC)

- Every `UPDATE` on `users` MUST include `WHERE id = ? AND version = ?` and
  increment `version` by exactly 1 in the `SET` clause.
- Every `UPDATE` on `sessions` that is version-guarded MUST include
  `WHERE id = ? AND version = ?` and increment `version` by exactly 1.
- When an UPDATE matches zero rows, the DAO MUST issue a secondary
  `SELECT version FROM <table> WHERE id = ?` to distinguish
  `ConcurrentModification` (row exists, wrong version) from `NotFound` (row
  absent).

### Immutable Columns

- `id`, `created_at`, and `row_created_at` MUST NEVER appear in any `UPDATE
  ... SET` clause.
- `updated_at` and `row_updated_at` are managed exclusively by database
  triggers and MUST NOT be set by DAO code.

### Soft Delete

- `UsersDao.findById` and `UsersDao.findByIdForUpdate` MUST return
  `FindResult.NotFound` for rows where `deleted_at IS NOT NULL` unless
  `includeDeleted = true` is explicitly passed.
- `UsersDao.findByEmail` MUST filter `deleted_at IS NULL` unconditionally.

### Token Storage

- The `sessions.token_hash` column stores a SHA-256 hash as `BYTEA`. The
  plain-text token MUST NEVER be stored.
- `SessionsDao.findByTokenHash` MUST perform an additional in-process
  `contentEquals` check on the retrieved `BYTEA` value after the SQL
  `WHERE token_hash = ?` filter to guard against hash-equality collisions from
  the JVM's `ByteArray` reference equality semantics.

### Value-Class Mapping Assumption

- The DAO ASSUMES all persisted `users` rows satisfy their value-class
  validation constraints (non-blank `email`, `name`, etc.). The `mapUser` and
  `mapUserVersion` functions perform unsafe `as ValidationResult.Valid` casts;
  if a DB row fails validation, the resulting `ClassCastException` is caught by
  the outer `catch (e: Exception)` handler and surfaces as `DatabaseFailure`.
  Database-level constraints MUST enforce conformance so this path is never
  reached in normal operation.

### Session Validity Filter

- `SessionsDao.findByTokenHash` MUST filter `is_revoked = false AND expires_at > NOW()` in the SQL query. Expired or revoked sessions MUST return
  `SessionFindResult.NotFound`.

### Revocation Semantics

- `SessionsDao.revokeByTokenHash` MUST use a blind `UPDATE ... WHERE
  token_hash = ? AND is_revoked = false` without a version guard. It MUST set
  `is_revoked = true` and atomically bump `version = version + 1`.
- If the session is already revoked or absent, `revokeByTokenHash` returns
  `SessionUpdateResult.NotFound`.

### Expiry Window

- `SessionsDao.extendExpiry` MUST hard-code the renewal interval as `7 days`
  (`INTERVAL '7 days'`). This value is intentionally not configurable.

### Error Wrapping

- All `SQLException` and general `Exception` instances MUST be wrapped in
  `ExceptionWrapper` (for `SessionsDao`) or `ed.unicoach.error.AppError` /
  `ed.unicoach.error.ExceptionWrapper` (for `UsersDao`) before being placed in
  a `DatabaseFailure` result variant. Raw exceptions MUST NEVER propagate past
  a DAO method boundary.

### SQLSTATE Discrimination (`UsersDao`)

- `SQLException` with `sqlState = "23505"` containing the index name
  `users_email_unique_active_idx` MUST be mapped to `DuplicateEmail`.
- `SQLException` with `sqlState = "23505"` **not** matching that index MUST be
  mapped to `ConstraintViolation`.
- `SQLException` with `sqlState = "23514"` MUST be mapped to
  `ConstraintViolation`.
- `SQLException` with `sqlState = "55P03"` in `findByIdForUpdate` MUST be
  mapped to `FindResult.LockAcquisitionFailure`.

### Physical Record Bypass

- `UsersDao.updatePhysicalRecord` MUST prepend `SET LOCAL
  unicoach.bypass_logical_timestamp = 'true'` via `session.prepareStatement`
  before executing the standard `doUpdate` path. This flag is scoped to the
  current transaction and MUST NOT bleed into subsequent transactions.

### DRY Execution (`SessionsDao`)

- `SessionsDao` MUST abstract all JDBC `try/catch` blocks through a single
  private generic `executeSafely(onError, block)` function. Duplicate
  `try/catch` patterns across individual methods are NEVER permitted in this
  file.

---

## III. Behavioral Contracts

### `SqlSession` — [`SqlSession.kt`](./SqlSession.kt)

| Attribute | Detail |
|-----------|--------|
| **Side Effects** | None. Wraps an existing `java.sql.Connection`; does not open or close it. |
| **Error Handling** | Propagates `SQLException` from `PreparedStatement` creation. |
| **Idempotency** | Idempotent: yes — method is a pure delegation. |

---

### `UsersDao` — [`UsersDao.kt`](./UsersDao.kt)

All methods are `object`-level (static equivalent). All SQL is issued via
`PreparedStatement`; no string interpolation of user data is permitted.

#### `findById(session, id, includeDeleted = false): FindResult`

- **SQL**: `SELECT * FROM users WHERE id = ?`
- **Side Effects**: DB read only.
- **Error Handling**: `FindResult.NotFound` if row absent or soft-deleted (when
  `includeDeleted = false`). `FindResult.DatabaseFailure` on `SQLException` or
  general `Exception`.
- **Idempotency**: Idempotent: yes.

#### `findByIdForUpdate(session, id, includeDeleted = false): FindResult`

- **SQL**: `SELECT * FROM users WHERE id = ? FOR UPDATE NOWAIT`
- **Side Effects**: Acquires a row-level exclusive lock for the duration of the
  enclosing transaction.
- **Error Handling**: `FindResult.LockAcquisitionFailure` on `sqlState =
  "55P03"`. All other errors → `FindResult.DatabaseFailure`.
- **Idempotency**: Idempotent: no (lock acquisition is stateful within a
  transaction).

#### `findByEmail(session, email): FindResult`

- **SQL**: `SELECT * FROM users WHERE email = ? AND deleted_at IS NULL`
- **Side Effects**: DB read only.
- **Error Handling**: `FindResult.NotFound` if absent. `FindResult.DatabaseFailure` on error.
- **Idempotency**: Idempotent: yes.

#### `findVersion(session, id, targetVersion): FindVersionResult`

- **SQL**: `SELECT * FROM users_versions WHERE id = ? AND version = ?`
- **Side Effects**: DB read only (queries the `users_versions` audit table).
- **Error Handling**: `FindVersionResult.NotFound` if absent.
  `FindVersionResult.DatabaseFailure` on error.
- **Idempotency**: Idempotent: yes.

#### `create(session, user): CreateResult`

- **SQL**: `INSERT INTO users (...) VALUES (...) RETURNING *`
- **Side Effects**: Inserts one row into `users`. Database generates UUID via
  `uuidv7()` default; `id` is NOT supplied by the caller.
- **Error Handling**:
  - `sqlState = "23505"` + index `users_email_unique_active_idx` →
    `CreateResult.DuplicateEmail`
  - `sqlState = "23505"` (other index) → `CreateResult.ConstraintViolation`
  - `sqlState = "23514"` → `CreateResult.ConstraintViolation`
  - Any other error → `CreateResult.DatabaseFailure`
- **Idempotency**: Idempotent: no (each call produces a new row if the email
  is unique; duplicate emails surface as `DuplicateEmail` rather than
  silently succeeding).

#### `update(session, user): UpdateResult`

- **SQL**: `UPDATE users SET version = ?, ... WHERE id = ? AND version = ? RETURNING *`
- **Side Effects**: Mutates one `users` row. On zero-row match, issues a
  secondary `SELECT version` query to distinguish `ConcurrentModification` from
  `NotFound`.
- **Error Handling**: Full SQLSTATE discrimination (see Invariants).
  `UpdateResult.NotFound`, `ConcurrentModification`, `DuplicateEmail`,
  `ConstraintViolation`, `DatabaseFailure`.
- **Idempotency**: Idempotent: no.

#### `updatePhysicalRecord(session, user): UpdateResult`

- **SQL**: Prepends `SET LOCAL unicoach.bypass_logical_timestamp = 'true'`
  then delegates to `doUpdate`.
- **Side Effects**: Mutates one `users` row. Sets a session-local PostgreSQL
  configuration variable scoped to the current transaction.
- **Error Handling**: `UpdateResult.DatabaseFailure` on any `SQLException`
  raised during the `SET LOCAL` step. Remaining error paths identical to
  `update`.
- **Idempotency**: Idempotent: no.

#### `delete(session, id, currentVersion): DeleteResult`

- **SQL**: `UPDATE users SET version = ?, deleted_at = NOW() WHERE id = ? AND version = ? RETURNING *`
- **Side Effects**: Soft-deletes one `users` row (sets `deleted_at`).
  Secondary `SELECT version` on zero-row match.
- **Error Handling**: `DeleteResult.NotFound`, `ConcurrentModification`,
  `DatabaseFailure`.
- **Idempotency**: Idempotent: no.

#### `undelete(session, id, currentVersion): UpdateResult`

- **SQL**: `UPDATE users SET version = ?, deleted_at = NULL WHERE id = ? AND version = ? RETURNING *`
- **Side Effects**: Restores a soft-deleted `users` row. Secondary `SELECT
  version` on zero-row match.
- **Error Handling**: Full SQLSTATE discrimination. May surface
  `UpdateResult.DuplicateEmail` if undeleting would violate the active-email
  unique index.
- **Idempotency**: Idempotent: no.

#### `revertToVersion(session, id, targetHistoricalVersion, currentVersion): UpdateResult`

- **Logic**: First calls `findVersion(session, id, targetHistoricalVersion)`.
  If not found → `UpdateResult.TargetVersionMissing`. If DB error →
  `UpdateResult.DatabaseFailure`. On success, re-applies the historical row's
  fields via a full `UPDATE ... SET` against `currentVersion`.
- **Side Effects**: Two DB round-trips (read historical version, then update
  current row).
- **Error Handling**: `UpdateResult.TargetVersionMissing`,
  `ConcurrentModification`, `DuplicateEmail`, `ConstraintViolation`,
  `DatabaseFailure`.
- **Idempotency**: Idempotent: no.

---

### `SessionsDao` — [`SessionsDao.kt`](./SessionsDao.kt)

All methods delegate through `executeSafely`. All session mutations target the
`sessions` table.

#### `findByTokenHash(session, tokenHash): SessionFindResult`

- **SQL**: `SELECT * FROM sessions WHERE token_hash = ? AND is_revoked = false AND expires_at > NOW()`
- **Side Effects**: DB read only.
- **Error Handling**: `SessionFindResult.NotFound` if absent, revoked, expired,
  or if the post-fetch `contentEquals` check fails (see §II Token Storage
  invariant). `SessionFindResult.DatabaseFailure` on exception.
- **Idempotency**: Idempotent: yes.

#### `create(session, newSession): SessionCreateResult`

- **SQL**: `INSERT INTO sessions (user_id, token_hash, user_agent, initial_ip, metadata, expires_at) VALUES (...) RETURNING *`
- **Side Effects**: Inserts one row. `expires_at` is computed as
  `NOW() + newSession.expiration.seconds * INTERVAL '1 second'`. The caller
  supplies `userId` (nullable for anonymous sessions), `tokenHash`,
  `userAgent` (nullable), `initialIp` (nullable), `metadata` (nullable),
  and `expiration` as a `java.time.Duration`.
- **Error Handling**: `SessionCreateResult.DatabaseFailure` on any exception,
  including when `RETURNING *` yields no rows after a successful insert.
- **Idempotency**: Idempotent: no.

#### `remintToken(session, id, currentVersion, newUserId, newTokenHash, newExpirationSeconds): SessionUpdateResult`

- **SQL**: `UPDATE sessions SET version = ?, user_id = ?, token_hash = ?, expires_at = NOW() + (? * INTERVAL '1 second') WHERE id = ? AND version = ? AND is_revoked = false RETURNING *`
- **Side Effects**: Rotates the token hash and binds the session to a
  `userId`. Used during login/registration to upgrade an anonymous session to
  an authenticated one (session fixation defense).
- **Error Handling**: `SessionUpdateResult.NotFound` if version mismatch,
  row absent, or already revoked. `SessionUpdateResult.DatabaseFailure` on
  exception.
- **Idempotency**: Idempotent: no.

#### `extendExpiry(session, id, currentVersion): SessionUpdateResult`

- **SQL**: `UPDATE sessions SET version = ?, expires_at = NOW() + INTERVAL '7 days' WHERE id = ? AND version = ? AND is_revoked = false RETURNING *`
- **Side Effects**: Extends a non-revoked session's expiry by exactly 7 days
  from the current server timestamp.
- **Error Handling**: `SessionUpdateResult.NotFound` on version mismatch, row
  absent, or already revoked. `SessionUpdateResult.DatabaseFailure` on
  exception.
- **Idempotency**: Idempotent: no (each call advances `expires_at` and bumps
  `version`).

#### `revokeByTokenHash(session, tokenHash): SessionUpdateResult`

- **SQL**: `UPDATE sessions SET version = version + 1, is_revoked = true WHERE token_hash = ? AND is_revoked = false RETURNING *`
- **Side Effects**: Marks one session as revoked; no version guard beyond
  `is_revoked = false`.
- **Error Handling**: `SessionUpdateResult.NotFound` if already revoked or
  absent. `SessionUpdateResult.DatabaseFailure` on exception.
- **Idempotency**: Idempotent: effectively idempotent — a second call on an
  already-revoked session returns `NotFound` without error.

#### `expireZombieSessions(session): SessionDeleteResult`

- **SQL**: `DELETE FROM sessions WHERE expires_at < NOW() OR is_revoked = true`
- **Side Effects**: Physically deletes all expired or revoked session rows.
  Intended to be called by `SessionCleanupJob` on a scheduled basis.
- **Error Handling**: `SessionDeleteResult.DatabaseFailure` on exception.
  `SessionDeleteResult.Success` carries no payload (row count is not exposed).
- **Idempotency**: Idempotent: yes — repeated calls delete any newly expired
  rows without error.

---

### Result Types — [`DaoModule.kt`](./DaoModule.kt)

Sealed interfaces scoped to `UsersDao` operations:

| Type | Variants |
|------|----------|
| `FindResult` | `Success(user)`, `NotFound`, `LockAcquisitionFailure`, `DatabaseFailure(error)` |
| `CreateResult` | `Success(user)`, `DuplicateEmail`, `ConstraintViolation(error)`, `DatabaseFailure(error)` |
| `UpdateResult` | `Success(user)`, `NotFound`, `DuplicateEmail`, `ConcurrentModification`, `TargetVersionMissing`, `ConstraintViolation(error)`, `DatabaseFailure(error)` |
| `DeleteResult` | `Success(user)`, `NotFound`, `ConcurrentModification`, `DatabaseFailure(error)` |
| `FindVersionResult` | `Success(version)`, `NotFound`, `DatabaseFailure(error)` |

Sealed interfaces scoped to `SessionsDao` operations are declared inline in
[`SessionsDao.kt`](./SessionsDao.kt):

| Type | Variants |
|------|----------|
| `SessionFindResult` | `Success(session)`, `NotFound(message)`, `DatabaseFailure(error)` |
| `SessionCreateResult` | `Success(session)`, `DatabaseFailure(error)` |
| `SessionUpdateResult` | `Success(session)`, `NotFound(message)`, `DatabaseFailure(error)` |
| `SessionDeleteResult` | `Success`, `DatabaseFailure(error)` |

---

## IV. Infrastructure & Environment

- **Module**: `db` Gradle sub-project.
- **JDBC Driver**: `org.postgresql:postgresql` (version managed by root BOM).
  No ORM (Hibernate, Exposed, etc.) is used.
- **Database**: PostgreSQL. All SQL targets the `users` and `sessions` tables.
  The `users_versions` audit table is read by `UsersDao.findVersion`.
- **Session-local configuration variable**: `unicoach.bypass_logical_timestamp`
  is a PostgreSQL custom GUC used by `updatePhysicalRecord`. It is set with
  `SET LOCAL` and is automatically discarded at transaction commit/rollback.
- **`ExceptionWrapper`**: Provided by the `ed.unicoach.error` package (sibling
  module dependency). Both DAOs use `ExceptionWrapper.from(e)` to wrap
  exceptions.
- **No environment variables** are read directly by this package. Connection
  parameters are injected via the `SqlSession` abstraction from the calling
  layer.

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
