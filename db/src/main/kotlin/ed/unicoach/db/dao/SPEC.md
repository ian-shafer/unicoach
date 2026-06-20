# SPEC: `db/src/main/kotlin/ed/unicoach/db/dao`

## I. Overview

`db/dao/` is the sole interface between the Kotlin application and the
PostgreSQL database. Stateless DAO singletons provide every read and write
operation, composing à-la-carte **capability interfaces** ([Dao.kt](./Dao.kt))
and delegating all query execution to shared `SqlSession` **query scaffolding**
([SqlSessionQueries.kt](./SqlSessionQueries.kt)).

| DAO                | Table(s)                                                             |
| ------------------ | -------------------------------------------------------------------- |
| `UsersDao`         | `users`, `users_versions`                                            |
| `SessionsDao`      | `sessions`                                                           |
| `StudentsDao`      | `students`, `students_versions`                                      |
| `ConvosDao`        | `convos`, `convo_requests`, `convo_responses`, `convo_responses_raw` |
| `SystemPromptsDao` | `system_prompts` (read-only catalog)                                 |

Every DAO method accepts a `SqlSession` as its first parameter. Connection
pooling, transaction boundaries, and commit/rollback are managed exclusively by
`Database.kt` in the parent package; `SqlSession` exposes only
`prepareStatement`, so DAO and scaffolding code cannot begin/commit/rollback.

All DAO methods return standard Kotlin `Result<T>`. Expected domain states and
successful ("happy path") operations return `Result.success(T)`. Database
exceptions are classified into `TransientError` or `PermanentError` interfaces
at the DAO boundary and wrapped in `Result.failure(Exception)`.

---

## II. Capability Interfaces — [`Dao.kt`](./Dao.kt)

One interface per operation-capability, generic over the row (`ROW`) and id
(`ID`) types, composed à la carte per DAO with no welded supertype. They mirror
the model-layer taxonomy in [`../models/Entity.kt`](../models/Entity.kt)
(`Identifiable`, `Created`, `Versioned`, `SoftDeletable`). `ROW` is bound to
`Identifiable<ID>` only on interfaces that take an `id: ID` parameter; the
id-less interfaces (`Listable`, `Creatable`, `Updatable`) leave `ROW` unbound.

| Interface                     | Method(s)                                                                      |
| ----------------------------- | ------------------------------------------------------------------------------ |
| `Findable<ROW, ID>`           | `findById(session, id)`                                                        |
| `SoftDeleteFindable<ROW, ID>` | `findById(session, id, scope)`; `findById(session, id)` default → `ACTIVE`     |
| `Listable<ROW>`               | `list(session, limit, offset)`                                                 |
| `SoftDeleteListable<ROW>`     | `list(session, scope, limit, offset)`                                          |
| `Creatable<NEW, ROW>`         | `create(session, input)`                                                       |
| `Updatable<EDIT, ROW>`        | `update(session, edit)`                                                        |
| `OccDeletable<ROW, ID>`       | `delete(session, id, currentVersion)`; `undelete(session, id, currentVersion)` |
| `Deletable<ROW, ID>`          | `delete(session, id)`; `undelete(session, id)`                                 |
| `Destroyable<ID>`             | `destroy(session, id)`                                                         |
| `VersionHistory<ID, V>`       | `listVersions(session, id)`                                                    |

`SoftDeleteFindable` carries a second `findById(session, id)` default delegating
to the scope-taking method with `SoftDeleteScope.ACTIVE`; an implementing
`object` overrides only the scope-taking method (the override drops any default
value), inheriting the no-scope overload.

Declared capability sets per DAO (operations not covered by an interface — token
lookups, `rename`, `archive`/`unarchive`, parented `listBy*`, `appendRequest`,
`remintToken`, `findByEmail`, `findVersion`, `revertToVersion`,
`updatePhysicalRecord`, the `*ForUpdate` lock-reads, `expireZombieSessions`,
`*WithActivity`, `listTurns`, `findRawByResponseId`, `findByNameAndVersion` —
remain concrete methods on the DAO):

| DAO                | Capability interfaces                                                                                                                                                                          |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `UsersDao`         | `SoftDeleteFindable<User, UserId>`, `SoftDeleteListable<User>`, `Creatable<NewUser, User>`, `Updatable<UserEdit, User>`, `OccDeletable<User, UserId>`, `VersionHistory<UserId, UserVersion>`   |
| `StudentsDao`      | `SoftDeleteFindable<Student, StudentId>`, `Creatable<NewStudent, Student>`, `Updatable<StudentEdit, Student>`, `OccDeletable<Student, StudentId>`, `VersionHistory<StudentId, StudentVersion>` |
| `ConvosDao`        | `SoftDeleteFindable<Convo, ConvoId>`, `Creatable<NewConvo, Convo>`, `Deletable<Convo, ConvoId>`                                                                                                |
| `SessionsDao`      | `Findable<Session, SessionId>`, `Listable<Session>`, `Creatable<NewSession, Session>`, `Destroyable<SessionId>`                                                                                |
| `SystemPromptsDao` | none (sole read is `findByNameAndVersion`; adopts the scaffolding only)                                                                                                                        |

`Updatable<EDIT, ROW>` applies only to entities with a dedicated edit-input type
covering the full mutable field set (`User` via `UserEdit`, `Student` via
`StudentEdit`). `ConvosDao.rename` is a single-column last-write-wins write on a
non-versioned entity and stays a named concrete method — no `ConvoEdit` type and
no `Updatable` membership.

---

## III. Query Scaffolding — [`SqlSessionQueries.kt`](./SqlSessionQueries.kt)

`internal` `SqlSession` extension functions own the try/prepare/execute/map
envelope, the OCC existence-probe dance, SQL generation from ordered column
maps, and JDBC null/JSON binding. Concrete DAOs supply column maps, row-mappers,
and — for create/update — their specialized SQLSTATE mapper
(`mapCreateUpdateError`, `mapConvoError`); helpers default to
`mapDatabaseError`. Column names paired into the generators are fixed DAO
identifiers, never caller data; only bound values vary.

### Execution envelope

- **`queryOne(sql, bind, map, onNoRow = { NotFoundException() })`** — SELECT
  yielding one row → `success(map(row))`, else `failure(onNoRow())`. Any thrown
  exception → `failure(mapDatabaseError(e))`.
- **`queryList(sql, bind, map)`** — SELECT → `success(List<T>)` (empty list when
  no rows). Exceptions → `mapDatabaseError`.
- **`mutateReturning(sql, bind, map, mapError = ::mapDatabaseError, onNoRow = { NotFoundException() })`**
  — INSERT/UPDATE … RETURNING *. Returned row → `success(map(row))`; 0 rows →
  `failure(onNoRow())`. `SQLException` → `failure(mapError(e))`; other
  throwables → `mapDatabaseError`.
- **`execute(sql, bind = {})`** — a write returning its affected-row count as
  `Result<Int>`. Exceptions → `mapDatabaseError`.
- **`occUpdate(table, sql, bind, idValue, map, mapError = ::mapDatabaseError)`**
  — runs an OCC `UPDATE … WHERE id = ? AND version = ? RETURNING *`. Returned
  row → success; 0 rows → probes `SELECT 1 FROM <table> WHERE id = ?` and fails
  with `ConcurrentModificationException` when the row exists, else
  `NotFoundException` (the probed column is immaterial — existence-only).

### Generic column-map mutation helpers

- **`typealias Bind = (PreparedStatement, Int) -> Unit`** — a closure binding
  one parameter at a positional index. Callers use the JDBC helpers
  (`setStringOrNull`, `setIntOrNull`, `setJsonbOrNull`, `setObject`) inside the
  closure, so each value keeps its own type-specific binding semantics.
- **`insertReturning(table, columns, map, mapError = ::mapDatabaseError)`** —
  generates `INSERT INTO $table (<cols>) VALUES (?, …) RETURNING *` from the
  ordered `columns` map and delegates to `mutateReturning`.
- **`updateColumnsReturning(table, id, currentVersion, columns, map, mapError)`**
  — generates
  `UPDATE $table SET <col>=?, … WHERE id=? [AND version=?] RETURNING *`.
  `currentVersion == null` → delegates to `mutateReturning` (NotFound on 0
  rows); `currentVersion` non-null → prepends `version = currentVersion+1` to
  the SET clause, appends `AND version = ?`, and delegates to `occUpdate`
  (ConcurrentModification probe on 0 rows).
- **`softDeleteReturning(table, id, currentVersion, deleted, map, mapError)`** —
  generates a soft-delete toggle. `deleted = true` → `SET deleted_at = NOW()`;
  `false` → `SET deleted_at = NULL`. `currentVersion == null` → non-OCC: appends
  `AND deleted_at IS [NOT] NULL` and delegates to `mutateReturning` (NotFound on
  0 rows, whether the id is absent or the row is already in the target state);
  `currentVersion` non-null → OCC: appends `AND version = ?`, increments the
  version, and delegates to `occUpdate`.

The SQL generators handle only single-table CUD where all column values are
positional parameters. Hand-written SQL remains for: JOINs; `FOR UPDATE NOWAIT`
lock-reads; non-id WHERE predicates (token lookups, `findByEmail`, parented
`listBy*`, `rename`'s active-row guard); SQL-expression columns beyond
`deleted_at` (`expires_at = NOW() + interval`,
`archived_at = COALESCE(…, NOW())`, the full-row `updateFullRow`, `SET LOCAL`
GUC); bare DELETEs without RETURNING (`expireZombieSessions`, `destroy`); and
append-only log writes (embedded `::jsonb` casts, multi-column payloads).

### JDBC binding/reading helpers

- **`setStringOrNull(index, value)`** — binds a nullable String, NULL as
  `Types.VARCHAR`.
- **`setIntOrNull(index, value)`** — binds a nullable Int, NULL as
  `Types.INTEGER`.
- **`setJsonbOrNull(index, value)`** — binds a nullable `JsonElement` into a
  `?::jsonb` slot, NULL as `Types.OTHER`.
- **`getInstant(column)` / `getInstantOrNull(column)`** — read a timestamp into
  `Instant` (the OrNull form returns null for a NULL column).
- **`getJsonbOrNull(column)`** — parses a nullable JSONB text column into
  `JsonElement?`.

These replaced the per-DAO private helpers `bindJsonOrNull`/`readJsonOrNull`/
`bindNullableInt` (previously inside `ConvosDao`) and the `executeSafely`
wrapper (previously inside `SessionsDao`).

### Read-time soft-delete predicate

- **`SoftDeleteScope.predicate(column = "deleted_at"): String`** — a fixed SQL
  fragment with no caller data: `ACTIVE` → `"<column> IS NULL"`, `DELETED` →
  `"<column> IS NOT NULL"`, `ALL` → `"TRUE"`. The `column` argument is a fixed
  DAO-supplied identifier (e.g. `"c.deleted_at"` for joined queries).

---

## IV. Mapping & Classification

### Row mappers

- **`users`**: `mapUser`/`mapUserVersion` read `version` as a plain `Int`, map
  `email`/`name`/`display_name` into value classes via their `.create()`
  factories (unwrapping `ValidationResult.Valid`), read `is_admin` as a plain
  `Boolean`, and synthesize `password_hash`/`sso_provider_id` into a sealed
  `AuthMethod` (`Password`, `SSO`, or `Both`) via `readAuthMethod`. A row with
  both auth columns NULL throws `CorruptPersistedAuthMethodException` (a
  `PermanentError` `DaoException` carrying the offending `UserId`), since the DB
  constraints guarantee at least one is non-NULL.
- **`students`**: `mapStudent`/`mapStudentVersion` reconstruct the decomposed
  `expected_high_school_graduation_{year,month,day}` columns into a
  `PartialDate` via `PartialDate.of` (`mapGraduationDate`), preserving month/day
  NULL-state via `ResultSet.wasNull`. A `ValidationResult.Invalid` on this read
  path throws `CorruptPersistedValueException` (a `PermanentError` carrying the
  offending decomposed columns and the structured `ValidationError`).
- **`convos`**: `mapConvo` reconstructs `name` via `ConvoName.create`
  (`parseConvoName`); an `Invalid` is raised as a `SQLException` (surfacing as a
  `PermanentError` mapping failure). It projects both `deleted_at` and the
  independent `archived_at` lifecycle timestamps.
- **`sessions`**: `mapSession` maps `id` as a typed `SessionId`, reads `version`
  as a plain `Int`, and projects a nullable `user_id`, `metadata`, `user_agent`,
  `initial_ip`, and `expires_at`. No validated value classes; no corruption
  path.
- **`system_prompts`**: `mapPrompt` maps every column verbatim (`name`,
  `version`, `body` as plain `String`); no soft-delete, version, or validated
  columns, so no corruption path.

The `row_created_at`/`row_updated_at` columns exist in every entity table and
are maintained by DB triggers, but no mapper projects them into a domain model.

### Postgres error codes

`mapDatabaseError` classifies any `SQLException` by SQLSTATE class into
`TransientError` (`08*`, `40001`, `40P01`, `53*`, `57P*`) or `PermanentError`
(everything else) `DaoException` implementations. It returns an
already-domain-typed `DaoException` unchanged — it does not re-wrap one in
`DatabaseException`/`TransientDatabaseException`, so corruption exceptions
thrown inside row mappers cross the catch-all boundary intact.
Non-`SQLException` throwables (e.g. `ClassCastException`) default to
`DatabaseException` (Permanent).

`UsersDao.mapCreateUpdateError`:

- `23505` containing `users_email_unique_active_idx` →
  `DuplicateEmailException`; other `23505` and any `23514` →
  `ConstraintViolationException`.
- everything else → `mapDatabaseError`.

`StudentsDao.mapCreateUpdateError`:

- `23505` containing `students_user_id_unique_idx` →
  `StudentAlreadyExistsException` (the index is total — no
  `WHERE deleted_at IS NULL` — so a soft-deleted student still reserves its
  owner's slot); other `23505` → `ConstraintViolationException`.
- `22008` (datetime field overflow) / `23514` (check violation) →
  `ConstraintViolationException` (the grad-date constraints surfaced as a
  permanent, caller-correctable validation failure).
- `23503` (FK) → `NotFoundException("Owning user not found")`.
- everything else → `mapDatabaseError`.

`ConvosDao.mapConvoError` (write paths only; reads and the soft-delete mutations
route through `mapDatabaseError`):

- `23503` → `NotFoundException` with a message resolved from the violated FK
  constraint name (`convos_student_id_fkey` → "Owning student not found";
  `convo_requests_convo_id_fkey`/`convo_responses_convo_id_fkey` → "Convo not
  found"; `convo_requests_system_prompt_id_fkey` → "System prompt not found";
  `convo_responses_request_id_fkey` → "Request not found"; unmatched → bare
  `NotFoundException()`).
- `23505` / `23514` → `ConstraintViolationException`.
- everything else → `mapDatabaseError`.

`55P03` in any `*ForUpdate` method (`SELECT … FOR UPDATE NOWAIT`) →
`LockAcquisitionFailureException` (mapped inline, before `mapDatabaseError`).

---

## V. Behavioral Contracts

### `SqlSession` — [`SqlSession.kt`](./SqlSession.kt)

The DAO's only interface to the database, exposing `prepareStatement` and
nothing else (no `commit()`/`rollback()`).

```kotlin
interface SqlSession {
  fun prepareStatement(sql: String): PreparedStatement
}
```

---

### `UsersDao` — [`UsersDao.kt`](./UsersDao.kt)

`object` implementing `SoftDeleteFindable`/`SoftDeleteListable`/`Creatable`/
`Updatable`/`OccDeletable`/`VersionHistory`. All SQL is issued via
`PreparedStatement`.

#### `findById(session, id, scope = ACTIVE): Result<User>`

- **Side Effects**: Read only.
- **Error Handling**: `NotFoundException()` if the row is absent, or its
  `deletedAt` does not satisfy `scope` (checked in application code via
  `admits`). `scope` defaults to `ACTIVE` through the interface's no-scope
  overload.
- **Idempotency**: Yes.

#### `findByIdForUpdate(session, id, scope = ACTIVE): Result<User>`

- **Side Effects**: Read/write — acquires a row-level exclusive lock
  (`FOR UPDATE NOWAIT`). Not part of a capability interface.
- **Error Handling**: `NotFoundException()` if absent or the scope rejects it;
  `LockAcquisitionFailureException()` on `55P03`.
- **Idempotency**: No (lock acquisition is stateful within a transaction).

#### `findByEmail(session, email): Result<User>`

- **Side Effects**: Read only. Filters `deleted_at IS NULL` unconditionally.
- **Error Handling**: `NotFoundException()` if absent.
- **Idempotency**: Yes.

#### `findVersion(session, id, targetVersion): Result<UserVersion>`

- **Side Effects**: Read only (queries `users_versions`).
- **Error Handling**: `NotFoundException()` if absent.
- **Idempotency**: Yes.

#### `create(session, input: NewUser): Result<User>`

- **Side Effects**: Write — `insertReturning` over six columns (`email`, `name`,
  `display_name`, `password_hash`, `sso_provider_id`, `is_admin`). The DB
  generates `id` (`uuidv7()` default) and timestamps. `bindAuthMethodColumn`
  binds each auth column independently from the `NewUser.authMethod`.
- **Error Handling**: `DuplicateEmailException` on the active-email index;
  `ConstraintViolationException` otherwise (via `mapCreateUpdateError`).
- **Idempotency**: No.

#### `update(session, edit: UserEdit): Result<User>`

- **Side Effects**: Read/write — `updateColumnsReturning` with OCC over four
  columns only: `email`, `name`, `display_name`, `is_admin`. It does **not**
  write `password_hash`/`sso_provider_id` (the auth method mutates only through
  dedicated auth flows) nor any immutable column. The OCC path prepends
  `version = edit.version + 1` and matches `WHERE id = ? AND version = ?`.
- **Error Handling**: `NotFoundException`/`ConcurrentModificationException` (via
  the `occUpdate` probe on 0 rows); `DuplicateEmailException`/
  `ConstraintViolationException` via `mapCreateUpdateError`.
- **Idempotency**: No (version bumps).

#### `updatePhysicalRecord(session, user): Result<User>`

- **Side Effects**: Read/write — two statements in the caller transaction: a
  `SET LOCAL unicoach.bypass_logical_timestamp = 'true'` bypass (suppressing the
  `update_timestamp` trigger so `updated_at` does not advance), then a full-row
  OCC update via the private `updateFullRow` (`occUpdate`) restoring every
  mutable column **including the auth columns**. Not routed through the
  `UserEdit` path.
- **Error Handling**: Same OCC classification as `update`.
- **Idempotency**: No.

#### `delete(session, id, currentVersion): Result<User>`

- **Side Effects**: Read/write — `softDeleteReturning(deleted = true)` with OCC
  (`SET version = currentVersion+1, deleted_at = NOW() WHERE id = ? AND version = ?`).
- **Error Handling**: `NotFoundException`/`ConcurrentModificationException` via
  the `occUpdate` probe.
- **Idempotency**: No.

#### `undelete(session, id, currentVersion): Result<User>`

- **Side Effects**: Read/write — `softDeleteReturning(deleted = false)` with OCC
  (clears `deleted_at`). Uses `mapCreateUpdateError` so a reactivation that
  collides on the active-email index surfaces `DuplicateEmailException`.
- **Error Handling**: `NotFoundException`/`ConcurrentModificationException` via
  the probe; `DuplicateEmailException` on the active-email index.
- **Idempotency**: No.

#### `revertToVersion(session, id, targetHistoricalVersion, currentVersion): Result<User>`

- **Side Effects**: Read/write — reads the historical version, then a full-row
  OCC write via `updateFullRow` (auth columns restored).
- **Error Handling**: `TargetVersionMissingException()` if the historical
  version is absent; otherwise same OCC classification as `update`.
- **Idempotency**: No.

#### `list(session, scope = ALL, limit, offset): Result<List<User>>`

- **Side Effects**: Read only. Pages the full `users` table newest-first
  (`ORDER BY created_at DESC, id`) via `LIMIT ?`/`OFFSET ?`; `scope` is a fixed
  SQL predicate (`scope.predicate()`) carrying no caller data. Admin callers
  pass `SoftDeleteScope.ALL` so soft-deleted rows stay visible.
- **Error Handling**: `success(emptyList())` when no rows match;
  `mapDatabaseError` on failure.
- **Idempotency**: Yes.

#### `listVersions(session, id): Result<List<UserVersion>>`

- **Side Effects**: Read only — `users_versions` ascending by `version` (replay
  order).
- **Error Handling**: `success(emptyList())` for an id with no history.
- **Idempotency**: Yes.

---

### `SessionsDao` — [`SessionsDao.kt`](./SessionsDao.kt)

`object` implementing `Findable`/`Listable`/`Creatable`/`Destroyable`. All
session mutations target the `sessions` table, which has no `deleted_at` column
and no `prevent_physical_delete` trigger — its delete is physical, and its read
methods take no `SoftDeleteScope`.

#### `findById(session, id): Result<Session>`

- **Side Effects**: Read only. Does NOT filter revoked or expired sessions
  (admin detail must see them), unlike `findByTokenHash`.
- **Error Handling**: `NotFoundException("Session not found")` if absent.
- **Idempotency**: Yes.

#### `findByTokenHash(session, tokenHash): Result<Session>`

- **Side Effects**: Read only. Filters
  `is_revoked = false AND expires_at > NOW()` and re-checks the fetched
  `token_hash` with `contentEquals`.
- **Error Handling**: `NotFoundException` if absent, revoked, expired, or the
  `contentEquals` re-check fails. Retains its hand-written envelope (only the
  envelope was adopted from the scaffolding).
- **Idempotency**: Yes.

#### `create(session, input: NewSession): Result<Session>`

- **Side Effects**: Write — hand-written `mutateReturning` because
  `expires_at = NOW() + (input.expiration.seconds * INTERVAL '1 second')` is a
  SQL expression outside `insertReturning`'s `Bind` model. A null `userId` is
  bound as `Types.OTHER`.
- **Error Handling**: No contractual errors; `NotFoundException` (the default
  `onNoRow`) only if RETURNING yields no row.
- **Idempotency**: No.

#### `remintToken(session, id, currentVersion, newUserId, newTokenHash, newExpirationSeconds): Result<Session>`

- **Side Effects**: Read/write — `mutateReturning` rotating `token_hash`,
  rebinding `user_id`, and resetting `expires_at` (session fixation defense),
  guarded by `WHERE id = ? AND version = ? AND is_revoked = false`.
- **Error Handling**: its specific `NotFoundException` message on 0 rows
  (version mismatch, absent, or revoked).
- **Idempotency**: No.

#### `extendExpiry(session, id, currentVersion): Result<Session>`

- **Side Effects**: Read/write — `mutateReturning` resetting `expires_at` to
  `NOW() + INTERVAL '7 days'` (absolute reset), guarded as `remintToken`.
- **Error Handling**: its specific `NotFoundException` message on 0 rows.
- **Idempotency**: No.

#### `revokeByTokenHash(session, tokenHash): Result<Session>`

- **Side Effects**: Read/write — `mutateReturning` setting `is_revoked = true`
  and `version = version + 1` where `token_hash = ? AND is_revoked = false`. No
  version guard.
- **Error Handling**:
  `NotFoundException("Session not found or already revoked")` on 0 rows.
- **Idempotency**: Effectively yes — a second call returns `NotFound`.

#### `expireZombieSessions(session): Result<Unit>`

- **Side Effects**: Write — `execute` physically deleting all expired or revoked
  rows.
- **Error Handling**: No contractual errors.
- **Idempotency**: Yes.

#### `listByUser(session, userId, limit, offset): Result<List<Session>>`

- **Side Effects**: Read only — pages one user's sessions newest-first
  (`created_at DESC, id`). No scope.
- **Error Handling**: `success(emptyList())` when none match.
- **Idempotency**: Yes.

#### `list(session, limit, offset): Result<List<Session>>`

- **Side Effects**: Read only — pages the full `sessions` table newest-first. No
  scope.
- **Error Handling**: `success(emptyList())` when none match.
- **Idempotency**: Yes.

#### `destroy(session, id): Result<Unit>`

- **Side Effects**: Write — `execute` a physical
  `DELETE FROM sessions WHERE id = ?`, then maps a 0-affected-row count to
  `NotFoundException`. This is the sole row-level physical delete in the DAO
  layer (every other entity `delete` is a soft-delete
  `UPDATE … SET deleted_at`).
- **Error Handling**: `NotFoundException("Session not found")` when zero rows
  are removed.
- **Idempotency**: No — a second call returns `NotFound`.

---

### `StudentsDao` — [`StudentsDao.kt`](./StudentsDao.kt)

`object` implementing `SoftDeleteFindable`/`Creatable`/`Updatable`/
`OccDeletable`/`VersionHistory`. Create/update/delete/undelete SQLSTATE
discrimination is centralized in `mapCreateUpdateError`; reads route through
`mapDatabaseError`.

#### `findById(session, id, scope = ACTIVE): Result<Student>`

- **Side Effects**: Read only.
- **Error Handling**: `NotFoundException()` if absent or the scope rejects the
  row's `deletedAt` (checked via `admits`).
- **Idempotency**: Yes.

#### `findByUserId(session, userId, scope = ACTIVE): Result<Student>`

- **Side Effects**: Read only.
- **Error Handling**: `NotFoundException()` if absent or the scope rejects it.
- **Idempotency**: Yes.

#### `findByIdForUpdate(session, id): Result<Student>`

- **Side Effects**: Read/write — `FOR UPDATE NOWAIT` lock by primary key, no
  soft-delete filter. Takes no `scope`; not part of a capability interface.
- **Error Handling**: `NotFoundException()` if absent;
  `LockAcquisitionFailureException()` on `55P03`.
- **Idempotency**: No.

#### `findByUserIdForUpdate(session, userId): Result<Student>`

- **Side Effects**: Read/write — `FOR UPDATE NOWAIT` lock filtering
  `deleted_at IS NULL`. Takes no `scope`.
- **Error Handling**: `NotFoundException()` if absent or soft-deleted;
  `LockAcquisitionFailureException()` on `55P03`.
- **Idempotency**: No.

#### `create(session, input: NewStudent): Result<Student>`

- **Side Effects**: Write — `insertReturning` over `user_id` plus the three
  decomposed grad-date columns (built by `gradDateColumns`). DB generates `id`
  and timestamps.
- **Error Handling**: `StudentAlreadyExistsException` on the total unique index
  (including against a soft-deleted row); `ConstraintViolationException` on
  grad-date constraints (`22008`/`23514`); `NotFoundException` on FK (`23503`).
- **Idempotency**: No.

#### `update(session, edit: StudentEdit): Result<Student>`

- **Side Effects**: Read/write — `updateColumnsReturning` with OCC over the
  three decomposed grad-date columns only.
- **Error Handling**: `NotFoundException`/`ConcurrentModificationException` via
  the probe; otherwise same constraint mapping as `create`.
- **Idempotency**: No.

#### `delete(session, id, currentVersion): Result<Student>`

- **Side Effects**: Read/write — `softDeleteReturning(deleted = true)` with OCC.
- **Error Handling**: `NotFoundException`/`ConcurrentModificationException` via
  the probe.
- **Idempotency**: No.

#### `undelete(session, id, currentVersion): Result<Student>`

- **Side Effects**: Read/write — `softDeleteReturning(deleted = false)` with
  OCC, restoring a soft-deleted student. Added by RFC 62 (mirrors
  `UsersDao.undelete`).
- **Error Handling**: `NotFoundException`/`ConcurrentModificationException` via
  the probe.
- **Idempotency**: No.

#### `listVersions(session, id): Result<List<StudentVersion>>`

- **Side Effects**: Read only — `students_versions` ascending by `version`, via
  `mapStudentVersion` (same grad-date corruption path as `mapStudent`).
- **Error Handling**: `success(emptyList())` for an id with no history.
- **Idempotency**: Yes.

---

### `ConvosDao` — [`ConvosDao.kt`](./ConvosDao.kt)

`object` implementing `SoftDeleteFindable`/`Creatable`/`Deletable` over the
coaching-conversation tables. `convos` has no `version` column, so its mutations
carry no OCC guard (last-write-wins). Write paths discriminate SQLSTATE via
`mapConvoError`; reads and the soft-delete mutations route through
`mapDatabaseError`. The same scaffolding (`insertReturning`,
`softDeleteReturning`, `mutateReturning`, `queryOne`, `queryList`) serves both
the mutable-entity reads/writes and the append-only log append/read.

#### `findById(session, id, scope = ACTIVE): Result<Convo>`

- **Side Effects**: Read only.
- **Error Handling**: `NotFoundException()` if absent or its `deletedAt` does
  not satisfy `scope` (checked via `scopeAdmits`).
- **Idempotency**: Yes.

#### `listByStudent(session, studentId, scope = ACTIVE): Result<List<Convo>>`

- **Side Effects**: Read only — `queryList` filtering `student_id = ?` and the
  scope predicate in SQL; ordered `created_at, id`.
- **Error Handling**: `success(emptyList())` when none match.
- **Idempotency**: Yes.

#### `create(session, input: NewConvo): Result<Convo>`

- **Side Effects**: Write — `insertReturning` over `student_id`, `name`. DB
  generates `id` (`uuidv7()`) and timestamps.
- **Error Handling**: `NotFoundException("Owning student not found")` on the
  `student_id` FK; `ConstraintViolationException` on a name CHECK (via
  `mapConvoError`).
- **Idempotency**: No.

#### `rename(session, id, name): Result<Convo>`

- **Side Effects**: Read/write — hand-written `mutateReturning` updating `name`
  where `deleted_at IS NULL` (the active-row guard is a non-id predicate outside
  the generic `updateColumnsReturning`). No version guard.
- **Error Handling**: `NotFoundException` on 0 rows (absent or soft-deleted);
  `ConstraintViolationException` on a name CHECK.
- **Idempotency**: No (the timestamp trigger bumps `updated_at` each call).

#### `delete(session, id): Result<Convo>`

- **Side Effects**: Read/write —
  `softDeleteReturning(currentVersion = null, deleted = true)`:
  `SET deleted_at = NOW() WHERE id = ? AND deleted_at IS NULL`.
- **Error Handling**: `NotFoundException` when already deleted or absent (the
  two collapse).
- **Idempotency**: No — a second call returns `NotFound`.

#### `undelete(session, id): Result<Convo>`

- **Side Effects**: Read/write —
  `softDeleteReturning(currentVersion = null, deleted = false)`:
  `SET deleted_at = NULL WHERE id = ? AND deleted_at IS NOT NULL`.
- **Error Handling**: `NotFoundException` when already active or absent.
- **Idempotency**: No — a second call returns `NotFound`.

#### `archive(session, id): Result<Convo>` / `unarchive(session, id): Result<Convo>`

- **Side Effects**: Read/write — both call `setArchivedAt`, which first
  `execute`s the `SET LOCAL unicoach.bypass_logical_timestamp = 'true'` bypass
  (so `updated_at` does not advance), then a hand-written `mutateReturning`.
  `archive` sets `archived_at = COALESCE(archived_at, NOW())` (re-archiving
  preserves the original instant); `unarchive` sets `archived_at = NULL`. Both
  filter `deleted_at IS NULL`. No version guard. Because `SET LOCAL` persists
  for the rest of the transaction, a caller combining `rename` and an archive
  toggle renames first.
- **Error Handling**: `NotFoundException` when the row is absent or
  soft-deleted; `mapConvoError` on other `SQLException`.
- **Idempotency**: Yes — re-archiving preserves the instant; unarchiving a
  never-archived row succeeds. (`archived_at` is a lifecycle axis independent of
  soft-delete.)

#### `listByStudentWithActivity(session, studentId, archive = UNARCHIVED, scope = ACTIVE): Result<List<ConvoWithActivity>>`

- **Side Effects**: Read only — single `LEFT JOIN convo_requests` grouped by
  convo, deriving `lastActivityAt = MAX(convo_requests.created_at)` (null with
  no turns, including failed/orphan turns). Filters `c.deleted_at` by `scope`
  and `c.archived_at` by `archive` (`archivePredicate`). Orders by activity
  `DESC NULLS LAST`, then `created_at DESC`, then `id`.
- **Error Handling**: `success(emptyList())` when none match.
- **Idempotency**: Yes.

#### `findByIdWithActivity(session, id, scope = ACTIVE): Result<ConvoWithActivity>`

- **Side Effects**: Read only — same `LEFT JOIN`/`MAX` derivation, scoped on
  `c.deleted_at`.
- **Error Handling**: `NotFoundException` when no row matches the scope.
- **Idempotency**: Yes.

#### `appendRequest(session, request: NewConvoRequest): Result<ConvoRequest>`

- **Side Effects**: Write — hand-written `mutateReturning` inserting one
  `convo_requests` row (embedded `?::jsonb` casts). First half of a coaching
  turn; committed before the model is called, so a request with no reply
  survives as an orphan (later joined to a `null` response by `listTurns`).
- **Error Handling**: `NotFoundException` on the `convo_id`/`system_prompt_id`
  FK; `ConstraintViolationException` on a CHECK (via `mapConvoError`).
- **Idempotency**: No.

#### `appendResponse(session, response: NewConvoResponse, rawPayload): Result<ConvoResponse>`

- **Side Effects**: Write — inserts the `convo_responses` row via
  `mutateReturning` and, when `rawPayload != null`, the `convo_responses_raw`
  sibling (`insertRaw`), both within the caller's single transaction (atomic
  together). `convoId` is taken from `NewConvoResponse`; no derivation SELECT. A
  null `rawPayload` is the transport-error turn (`stop_reason = "error"`,
  `content = null`) — only the response row is written.
- **Error Handling**: `NotFoundException` on the `request_id`/`convo_id` FK;
  `ConstraintViolationException` on the `request_id` UNIQUE (duplicate response)
  or a content/model/token CHECK (via `mapConvoError`).
- **Idempotency**: No — the `request_id` UNIQUE rejects a second response.

#### `listTurns(session, convoId, scope = ACTIVE): Result<List<ConvoTurn>>`

- **Side Effects**: Read only — `queryList` LEFT-JOINing `convo_requests` to
  `convo_responses`, scoping on the owning convo's `c.deleted_at` by `scope`
  (turns of a soft-deleted convo are hidden under `ACTIVE`); ordered
  `r.created_at, r.id`. `ConvoTurn.response` is `null` when the request has no
  response row yet.
- **Error Handling**: `mapDatabaseError` on failure. The verbatim raw payload is
  excluded — fetch on demand via `findRawByResponseId`.
- **Idempotency**: Yes.

#### `findRawByResponseId(session, responseId): Result<ConvoResponseRaw>`

- **Side Effects**: Read only — `queryOne`.
- **Error Handling**: `NotFoundException` when no raw row exists (including
  error turns, which write no raw sibling).
- **Idempotency**: Yes.

---

### `SystemPromptsDao` — [`SystemPromptsDao.kt`](./SystemPromptsDao.kt)

Read-only reader over the immutable `system_prompts` catalog. Rows are authored
by migration, never by the application, so this DAO exposes no write methods. It
declares no capability interface (its sole read is parented-by-name) but adopts
the scaffolding. Stateless `object`; failures route through `mapDatabaseError`.

#### `findByNameAndVersion(session, name, version): Result<SystemPrompt>`

- **Side Effects**: Read only — `queryOne` resolving the `(name, version)`
  UNIQUE key.
- **Error Handling**: `NotFoundException` when no row matches.
- **Idempotency**: Yes.

---

### Result Types — Kotlin `Result<T>`

All DAO methods return standard Kotlin `Result<T>`. Exceptions are wrapped in
`Result.failure()` and derive from `DaoException` — defined in
[DaoExceptions.kt](./DaoExceptions.kt) — which extends `RuntimeException` and
may implement `TransientError` or `PermanentError`. SQLSTATE classification
rules are in §IV.

---

## VI. Infrastructure & Environment

- **JDBC Driver**: `org.postgresql:postgresql` (version managed by root BOM). No
  ORM (Hibernate, Exposed, etc.).
- **Database**: PostgreSQL 18.
- **Session-local configuration variable**: `unicoach.bypass_logical_timestamp`
  is a PostgreSQL custom GUC used by `UsersDao.updatePhysicalRecord` and
  `ConvosDao.archive`/`unarchive`. It is set with `SET LOCAL` and is discarded
  at transaction commit/rollback.

---

## VII. History

- [x] [RFC-07: UsersDao](../../../../../../../../rfc/07-users-dao.md) — Defined
      `UsersDao`, `DaoModule.kt`, and `SqlSession` (in `rest-server/`).
- [x] [RFC-08: Auth Registration](../../../../../../../../rfc/08-auth-registration.md)
      — Relocated `UsersDao`/`SqlSession` to `service/`.
- [x] [RFC-10: Auth Login](../../../../../../../../rfc/10-auth-login.md) — Added
      `findByEmail` to `UsersDao`.
- [x] [RFC-11: Sessions](../../../../../../../../rfc/11-sessions.md) — Created
      `SessionsDao` with `create`, `findByTokenHash`, `expireZombieSessions`,
      and the `executeSafely` pattern.
- [x] [RFC-13: Auth Me](../../../../../../../../rfc/13-auth-me.md) — Changed
      `findByTokenHash` parameter from `ByteArray` to `TokenHash`; added the
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
      locking, and the `mapCreateUpdateError` SQLSTATE map; added
      `StudentAlreadyExistsException` to `DaoExceptions.kt`.
- [x] [RFC-34: Transactional Email Service](../../../../../../../../rfc/34-transactional-email-service.md)
      — Repointed `EmailAddress`/`ValidationResult` imports in `UsersDao` and
      `StudentsDao` from `ed.unicoach.db.models` to `ed.unicoach.common.models`.
- [x] [RFC-36: Entity Model Capability Taxonomy](../../../../../../../../rfc/36-entity-model-taxonomy.md)
      — Row mappers stopped projecting `row_created_at`/`row_updated_at` and
      read `version` as a plain `Int`; `SessionsDao.mapSession` maps `id` as a
      typed `SessionId`; `remintToken`/`extendExpiry` take `id: SessionId`.
- [x] [RFC-38: Convos DAO](../../../../../../../../rfc/38-convos-dao.md) — Added
      `ConvosDao` over the coaching-conversation tables with
      `SoftDeleteScope`-aware reads, the two-transaction coaching turn,
      on-demand `findRawByResponseId`, no OCC/`version` guard on `convos`, and
      the `mapConvoError` SQLSTATE map.
- [x] [RFC-40: Validation Error Reporting](../../../../../../../../rfc/40-validation-error-reporting.md)
      — Added
      `CorruptPersistedValueException`/`CorruptPersistedAuthMethodException` and
      the `DaoException` pass-through guard in `mapDatabaseError`; row mappers
      throw corruption exceptions on failed re-validation.
- [x] [RFC-45: Coaching Service and Conversation REST Surface](../../../../../../../../rfc/45-coaching-service.md)
      — Added `SystemPromptsDao`; added `ConvosDao.archive`/`unarchive` and the
      activity-derived reads `listByStudentWithActivity`/`findByIdWithActivity`;
      `mapConvo` now projects `archived_at`.
- [x] [RFC-60: Admin Website](../../../../../../../../rfc/60-admin-website.md) —
      Threaded `is_admin` through the `users` read/write path; added the admin
      read surface (`UsersDao.listAll`/`listVersions`,
      `StudentsDao.listVersions`, `SessionsDao.findById`/`listByUser`/`listAll`/
      `deleteById`).
- [x] [RFC-62: DAO Capability Interfaces and Shared Query Scaffolding](../../../../../../../../rfc/62-dao-interfaces.md)
      — Added the capability interfaces ([Dao.kt](./Dao.kt)) and the shared
      `SqlSession` query scaffolding
      ([SqlSessionQueries.kt](./SqlSessionQueries.kt)) — the execution envelope
      (`queryOne`, `queryList`, `mutateReturning`, `execute`, `occUpdate`), the
      `Bind` typealias and column-map generators (`insertReturning`,
      `updateColumnsReturning`, `softDeleteReturning`), the JDBC bind/read
      helpers, and `SoftDeleteScope.predicate`. Every DAO now declares its
      capability interfaces and routes through the scaffolding, removing the
      per-DAO `executeSafely`/`bindJsonOrNull`/`readJsonOrNull`/
      `bindNullableInt` helpers. Renamed
      `UsersDao.listAll`/`SessionsDao.listAll` → `list`,
      `SessionsDao.deleteById` → `destroy`; narrowed `UsersDao.update` to a
      `UserEdit` (4-column SET, no auth columns) and `StudentsDao.update` to a
      `StudentEdit`; changed the soft-delete reads from
      `includeDeleted: Boolean` to `scope: SoftDeleteScope`; added
      `StudentsDao.undelete`.
