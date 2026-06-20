# RFC 62: DAO Capability Interfaces and Shared Query Scaffolding

## Executive Summary

This RFC adds two layers to the `db` module's `dao` package: a set of capability
interfaces that each DAO declares à la carte, and a set of `SqlSession`
extension helpers that all DAOs delegate to for query execution.

The DAOs today are concrete `object`s with no shared supertype: every method
re-implements the same
`try { prepareStatement.use { executeQuery.use { map } } }
catch { mapDatabaseError }`
envelope, and the optimistic-concurrency "0 rows → probe existence →
`ConcurrentModification` vs `NotFound`" block is copied across the OCC writers
of `UsersDao` (`update`/`delete`/`undelete`/`revertToVersion`) and `StudentsDao`
(`update`/`delete`). There is also no typed seam an admin tool (or any generic
caller) can program against — every call site hand-writes the concrete `object`
name.

The capability interfaces mirror the model-layer taxonomy in
`db/models/Entity.kt` (`Identifiable`, `Created`, `Versioned`, `SoftDeletable`):
one interface per operation-capability (`Findable`, `Creatable`, `Updatable`,
`OccDeletable`, …), composed per DAO, with no welded supertype. The scaffolding
extracts the repeated envelope and the OCC dance into `SqlSession` extension
functions that take SQL, a binder, a row-mapper, and an optional error-mapper.
Both DAO families — mutable entities and append-only logs — consume the same
helpers, which is the concrete code-sharing this RFC delivers.

Two model types are added (`UserEdit`, `StudentEdit`) so `update` accepts only
mutable fields plus the OCC version instead of a whole row, and `SessionsDao`'s
physical delete is renamed `deleteById` → `destroy`. The change is structural
and behaviour-preserving; the existing DB-backed DAO suites are the regression
net.

## Detailed Design

### Data Models

Two update-input records are added to `db/models`, siblings of the existing
`NewUser`/`NewStudent` creation inputs. Each carries the entity identity, the
expected OCC `version`, and only the mutable business fields — never an
immutable column (`createdAt`, `deletedAt`) or an out-of-band field
(`User.authMethod`, i.e. `password_hash`/`sso_provider_id`, which are mutated
through dedicated auth flows, never through `update`).

```kotlin
data class UserEdit(
  val id: UserId,
  val version: Int,
  val email: EmailAddress,
  val name: PersonName,
  val displayName: DisplayName?,
  val isAdmin: Boolean,
)

data class StudentEdit(
  val id: StudentId,
  val version: Int,
  val expectedHighSchoolGraduationDate: PartialDate,
)
```

`UsersDao.update` consequently stops writing `password_hash`/`sso_provider_id`:
its `SET` clause covers only `version, email, name, display_name, is_admin`, so
an update can no longer clobber the auth method. This narrowing applies **only**
to the public `update`. `UsersDao.revertToVersion` and the private
`doUpdate`/`updatePhysicalRecord` writers — which restore a full historical row,
including its auth columns — keep their existing full-row `SET` and are not
routed through the `UserEdit` path; `update` no longer delegates to `doUpdate`.

### API Contracts — capability interfaces (`db/dao/Dao.kt`, new)

One interface per operation-capability, generic over the row and id types. `ROW`
is bound to `Identifiable<ID>` only on interfaces that take an `id: ID`
parameter (coupling the row's id type to the interface's id type); the id-less
interfaces leave `ROW` unbound — no type parameter exists solely to carry a
bound.

```kotlin
package ed.unicoach.db.dao

import ed.unicoach.db.models.Id
import ed.unicoach.db.models.Identifiable
import ed.unicoach.db.models.SoftDeleteScope

/* Reads */
interface Findable<ROW : Identifiable<ID>, ID : Id> {
  fun findById(session: SqlSession, id: ID): Result<ROW>
}

interface SoftDeleteFindable<ROW : Identifiable<ID>, ID : Id> {
  fun findById(session: SqlSession, id: ID, scope: SoftDeleteScope): Result<ROW>
  fun findById(session: SqlSession, id: ID): Result<ROW> = findById(session, id, SoftDeleteScope.ACTIVE)
}

interface Listable<ROW> {
  fun list(session: SqlSession, limit: Int, offset: Int): Result<List<ROW>>
}

interface SoftDeleteListable<ROW> {
  fun list(session: SqlSession, scope: SoftDeleteScope, limit: Int, offset: Int): Result<List<ROW>>
}

/* Writes */
interface Creatable<NEW, ROW> {
  fun create(session: SqlSession, input: NEW): Result<ROW>
}

interface Updatable<EDIT, ROW> {
  fun update(session: SqlSession, edit: EDIT): Result<ROW>
}

interface OccDeletable<ROW : Identifiable<ID>, ID : Id> {
  fun delete(session: SqlSession, id: ID, currentVersion: Int): Result<ROW>
  fun undelete(session: SqlSession, id: ID, currentVersion: Int): Result<ROW>
}

interface Deletable<ROW : Identifiable<ID>, ID : Id> {
  fun delete(session: SqlSession, id: ID): Result<ROW>
  fun undelete(session: SqlSession, id: ID): Result<ROW>
}

interface Destroyable<ID : Id> {
  fun destroy(session: SqlSession, id: ID): Result<Unit>
}

/* History */
interface VersionHistory<ID : Id, V> {
  fun listVersions(session: SqlSession, id: ID): Result<List<V>>
}
```

The second `findById` on `SoftDeleteFindable` is a default convenience that
preserves existing 2-argument call sites: an implementing `object` overrides
only the scope-taking method (an override may not restate a default value), and
the no-scope form is inherited from the interface. `ConvosDao.findById` today
declares `scope: SoftDeleteScope = SoftDeleteScope.ACTIVE` with a default; as an
interface override it must **drop** that default value and rely on the inherited
no-scope overload for its existing 2-argument callers.

Declared capability sets per DAO (operations not covered by an interface — token
lookups, `rename`, `archive`/`unarchive`, parented `listBy*`, `appendRequest`,
`remintToken`, … — remain concrete methods on the DAO):

| DAO                | Capability interfaces                                                                                                                                                                          |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `UsersDao`         | `SoftDeleteFindable<User, UserId>`, `SoftDeleteListable<User>`, `Creatable<NewUser, User>`, `Updatable<UserEdit, User>`, `OccDeletable<User, UserId>`, `VersionHistory<UserId, UserVersion>`   |
| `StudentsDao`      | `SoftDeleteFindable<Student, StudentId>`, `Creatable<NewStudent, Student>`, `Updatable<StudentEdit, Student>`, `OccDeletable<Student, StudentId>`, `VersionHistory<StudentId, StudentVersion>` |
| `ConvosDao`        | `SoftDeleteFindable<Convo, ConvoId>`, `Creatable<NewConvo, Convo>`, `Deletable<Convo, ConvoId>`                                                                                                |
| `SessionsDao`      | `Findable<Session, SessionId>`, `Listable<Session>`, `Creatable<NewSession, Session>`, `Destroyable<SessionId>`                                                                                |
| `SystemPromptsDao` | none (sole read is `findByNameAndVersion`; adopts scaffolding only)                                                                                                                            |

### API Contracts — query scaffolding (`db/dao/SqlSessionQueries.kt`, new)

`SqlSession` extension functions owning the execution envelope, the OCC dance,
SQL generation from column maps, and JDBC null/JSON binding. Concrete DAOs
supply column maps, row-mappers, and — for create/update — their specialized
SQLSTATE mapper (`mapCreateUpdateError`, `mapConvoError`); helpers default to
`mapDatabaseError`.

```kotlin
/* Execution envelope */

fun <T> SqlSession.queryOne(
  sql: String, bind: (PreparedStatement) -> Unit, map: (ResultSet) -> T,
  onNoRow: () -> Exception = { NotFoundException() },
): Result<T>                                            // SELECT → row, or onNoRow() on no row

fun <T> SqlSession.queryList(
  sql: String, bind: (PreparedStatement) -> Unit, map: (ResultSet) -> T,
): Result<List<T>>                                      // SELECT → N rows

fun <T> SqlSession.mutateReturning(
  sql: String, bind: (PreparedStatement) -> Unit, map: (ResultSet) -> T,
  mapError: (SQLException) -> Exception = ::mapDatabaseError,
  onNoRow: () -> Exception = { NotFoundException() },
): Result<T>
// INSERT/UPDATE ... RETURNING *. On a returned row → success(map(row)). On 0 rows
// → failure(onNoRow()); callers whose WHERE can match nothing (extendExpiry,
// remintToken, revokeByTokenHash) pass their specific NotFoundException message.

fun SqlSession.execute(
  sql: String, bind: (PreparedStatement) -> Unit = {},
): Result<Int>                                          // write → affected-row count

fun <T> SqlSession.occUpdate(
  table: String, sql: String, bind: (PreparedStatement) -> Unit, idValue: Any,
  map: (ResultSet) -> T, mapError: (SQLException) -> Exception = ::mapDatabaseError,
): Result<T>
// Runs an OCC UPDATE ... WHERE id = ? AND version = ? RETURNING *. On a returned
// row → success. On 0 rows → probes SELECT 1 FROM <table> WHERE id = ? and fails
// with ConcurrentModificationException if the row exists, else NotFoundException.

/* Generic column-map mutation helpers */

typealias Bind = (PreparedStatement, Int) -> Unit
// A closure that binds one parameter at a positional index. Callers use the
// existing JDBC helpers (setStringOrNull, setIntOrNull, setJsonbOrNull, setObject)
// inside the closure, preserving all type-specific binding semantics.

fun <T> SqlSession.insertReturning(
  table: String, columns: Map<String, Bind>, map: (ResultSet) -> T,
  mapError: (SQLException) -> Exception = ::mapDatabaseError,
): Result<T>
// Generates INSERT INTO $table (<cols>) VALUES (?, …) RETURNING * from the ordered
// column map and delegates to mutateReturning. Column names are fixed DAO
// identifiers, never caller data.

fun <T> SqlSession.updateColumnsReturning(
  table: String, id: Any, currentVersion: Int?, columns: Map<String, Bind>,
  map: (ResultSet) -> T, mapError: (SQLException) -> Exception = ::mapDatabaseError,
): Result<T>
// Generates UPDATE $table SET <col>=?, … WHERE id=? [AND version=?] RETURNING *.
// currentVersion=null → delegates to mutateReturning (NotFound on 0 rows).
// currentVersion non-null → prepends version=currentVersion+1 to the SET clause
// and delegates to occUpdate (ConcurrentModification probe on 0 rows).

fun <T> SqlSession.softDeleteReturning(
  table: String, id: Any, currentVersion: Int?, deleted: Boolean,
  map: (ResultSet) -> T, mapError: (SQLException) -> Exception = ::mapDatabaseError,
): Result<T>
// Generates UPDATE $table SET [version=?,] deleted_at=[NOW()/NULL]
//   WHERE id=? [AND version=?] [AND deleted_at IS [NOT] NULL] RETURNING *.
// deleted=true → SET deleted_at=NOW(); deleted=false → SET deleted_at=NULL.
// currentVersion=null → non-OCC: appends AND deleted_at IS [NOT] NULL; delegates
//   to mutateReturning (NotFound on 0 rows).
// currentVersion non-null → OCC: appends AND version=? and increments version;
//   delegates to occUpdate (ConcurrentModification probe on 0 rows).
// SQL generation boundary: only single-table CUD where all column values are
// positional parameters. Remains hand-written: JOINs; FOR UPDATE NOWAIT lock-reads;
// non-id WHERE predicates (token lookups, findByEmail, parented listBy*); SQL-
// expression columns beyond deleted_at (expires_at = NOW() + interval,
// archived_at = COALESCE(…, NOW()), SET LOCAL GUC); bare DELETEs without RETURNING
// (expireZombieSessions, destroy); append-only log writes (embedded ::jsonb casts
// in VALUES, multi-column payloads with SQL expressions).

/* JDBC binding/reading helpers */
fun PreparedStatement.setStringOrNull(index: Int, value: String?)
fun PreparedStatement.setIntOrNull(index: Int, value: Int?)
fun PreparedStatement.setJsonbOrNull(index: Int, value: JsonElement?)
fun ResultSet.getInstant(column: String): Instant
fun ResultSet.getInstantOrNull(column: String): Instant?
fun ResultSet.getJsonbOrNull(column: String): JsonElement?

/* Read-time soft-delete predicate (fixed SQL fragment, no caller data) */
fun SoftDeleteScope.predicate(column: String = "deleted_at"): String
// ACTIVE → "<column> IS NULL"; DELETED → "<column> IS NOT NULL"; ALL → "TRUE"
```

The helpers live next to the DAOs as `internal` extensions; transaction
boundaries stay owned by `Database.withConnection` (the helpers receive a
`SqlSession`, which exposes only `prepareStatement`). The `bindJsonOrNull` /
`readJsonOrNull` / `bindNullableInt` private helpers currently inside
`ConvosDao`, and the `executeSafely` wrapper inside `SessionsDao`, are removed
in favour of these shared functions.

Entity–log sharing: `ConvosDao.appendRequest`/`appendResponse` (append-only log
writes) call `mutateReturning` + `setJsonbOrNull`, and `listTurns` /
`findRawByResponseId` (log reads) call `queryList` / `queryOne` — the same
helpers the mutable-entity DAOs use for `create` and `findById`.

`ConvosDao.rename(session, id, name)` is a named concrete method, kept
hand-written via `mutateReturning` rather than routed through
`updateColumnsReturning`: its SQL carries an active-row guard
(`WHERE id = ? AND deleted_at IS NULL`, so a soft-deleted convo yields
`NotFoundException` and is not renamed), and that non-id `WHERE` predicate falls
under the SQL-generation boundary's "remains hand-written" clause — the generic
`updateColumnsReturning` emits an id-only `WHERE` and cannot express the guard.
It is not added to the `Updatable` interface and no `ConvoEdit` type is
introduced. Rule: `Updatable<EDIT, ROW>` applies only to entities with a
dedicated edit-input type covering the full mutable field set (User via
`UserEdit`, Student via `StudentEdit`). Single-column last-write-wins writes on
non-versioned entities remain named concrete methods.

### Signature normalizations (ripple into consumers)

Three existing inconsistencies are normalized so the interfaces are
implementable; each ripples into a bounded set of call sites (see Files
Modified).

- **Soft-delete reads take `scope: SoftDeleteScope`, not
  `includeDeleted: Boolean`.** Only the methods that carry `includeDeleted`
  today change: `UsersDao.findById`/`findByIdForUpdate` and
  `StudentsDao.findById`/`findByUserId`. Their `includeDeleted: Boolean = false`
  parameter becomes `scope: SoftDeleteScope`. The `*ForUpdate` lock-reads on
  `StudentsDao` (`findByIdForUpdate`, `findByUserIdForUpdate`) take **no**
  `includeDeleted` parameter today and are unchanged: `findByIdForUpdate`
  returns the row unconditionally and `findByUserIdForUpdate` hardcodes
  `deleted_at IS NULL`; neither is part of a capability interface. Call sites
  passing `includeDeleted = true` become `SoftDeleteScope.ALL`; `false` becomes
  `SoftDeleteScope.ACTIVE`. `ConvosDao.findById` already takes `scope` but with
  a default (`= SoftDeleteScope.ACTIVE`); satisfying `SoftDeleteFindable`
  requires dropping that default on the override (see below), so the method is
  touched.
- **`listAll` → `list`.** `UsersDao.listAll`(scope, limit, offset) and
  `SessionsDao.listAll`(limit, offset) are renamed `list` to satisfy
  `SoftDeleteListable` / `Listable`.
- **`SessionsDao.deleteById` → `destroy`** to satisfy `Destroyable`.

`StudentsDao.undelete(session, id, currentVersion)` is **added** (it does not
exist today) so `StudentsDao` can satisfy `OccDeletable`; it mirrors
`UsersDao.undelete`.

### Error Handling / Edge Cases

No new error types or outcomes. `queryOne`/`occUpdate` produce the same
`NotFoundException`/`ConcurrentModificationException`/`DuplicateEmailException`
classifications the inline code produces today, routed through the same
`mapDatabaseError`/`mapCreateUpdateError`/`mapConvoError` mappers. The OCC
existence-probe semantics in `occUpdate` are equivalent to the current copied
blocks — existence-only, so `occUpdate`'s `SELECT 1 FROM <table> WHERE id = ?`
yields the same `ConcurrentModification`-vs-`NotFound` split as today's
`SELECT version …` probe (the projected column is immaterial).
`SoftDeleteScope.DELETED` on a `findById` returns the row only when it is
soft-deleted, else `NotFoundException` — consistent with the list predicate.

**Soft-delete 0-row contract.** `softDeleteReturning` maps 0 rows from
`RETURNING *` to `NotFoundException` via `onNoRow`. The contract differs by OCC
mode:

- _Non-OCC (`currentVersion=null`, `Deletable`)_: the WHERE predicate is
  `id=? AND deleted_at IS [NOT] NULL`. A 0-row result means either the id is
  absent or the row is already in the target state (already deleted / already
  active). Both cases collapse to `NotFoundException`. Callers cannot and need
  not distinguish them — exposing the already-deleted case would leak
  deleted-row existence upward.
- _OCC (`currentVersion` non-null, `OccDeletable`)_: the WHERE predicate is
  `id=? AND version=?`. A 0-row result triggers the `occUpdate` probe: id absent
  → `NotFoundException`; id present (regardless of `deleted_at`) →
  `ConcurrentModificationException`. A row already soft-deleted at the matching
  version is logically impossible (the delete itself increments the version), so
  the probe correctly fires `ConcurrentModification` only for version mismatches
  caused by concurrent writes.

### Dependencies

None added. No new libraries, configuration, or migrations; no schema change.
`db/build.gradle.kts` is unchanged. `Identifiable`/`Id`/`SoftDeleteScope`
already exist in `db/models`.

## Tests

The existing DB-backed DAO suites are the behaviour-preserving regression net:
they are mechanically retargeted to the normalized signatures and must stay
green, proving the scaffolding and interface refactor changed no observable
behaviour. New tests cover only genuinely-new surface. All run under `bin/test`.

- `UsersDaoTest` — retarget `findById(..., includeDeleted = true/false)` to
  `SoftDeleteScope.ALL/ACTIVE`; `listAll` → `list`; every
  `UsersDao.update(session,
  row.copy(...))` to
  `UsersDao.update(session, UserEdit(id, version, email, name,
  displayName, isAdmin))`.
  Retained assertions prove `User` round-trip, OCC increment,
  `ConcurrentModification`-vs-`NotFound`, duplicate-email, soft-delete scoping,
  version history.
- New `UsersDaoTest` case —
  `update via UserEdit leaves authMethod and createdAt
  untouched`: create a
  password user, `update` via `UserEdit`, assert the reloaded row's `authMethod`
  and `createdAt` are unchanged and `version` incremented.
- `StudentsDaoTest` — retarget `findById`/`findByUserId` to `scope`; `update` to
  `StudentEdit`. Retained assertions prove `Student` round-trip and OCC.
- New `StudentsDaoTest` case — `undelete restores a soft-deleted student`:
  create, `delete`, `undelete(currentVersion)`, assert active and `version`
  incremented; and `undelete with stale version yields ConcurrentModification`.
- `SessionsDaoTest` — `listAll` → `list`; `deleteById` → `destroy`. Retained
  assertions prove `Session` round-trip, paging, and physical delete.
- `ConvosDaoTest` — no change (its calls already pass `scope`): the suite
  recompiles and runs unchanged, confirming the scaffolding refactor of entity
  reads/writes and log append/read is behaviour-preserving (round-trip,
  soft-delete scoping, archive axis, two-boundary append, raw payload).
- `service/.../student/StudentServiceTest` — no change: it drives
  `StudentService.updateStudent(...)` by string arguments and never references
  `StudentsDao.update` or `StudentEdit` directly (the service builds the
  `StudentEdit` internally). Green confirms `StudentService.updateStudent` OCC
  behaviour is preserved.
- `admin/.../resources/SessionsResourceTest` — no change: it exercises the admin
  Sessions list/delete **routes** over HTTP and only references the DAO via
  `findByTokenHash`. Green confirms the renamed `list`/`destroy` wired through
  `SessionsResource` preserve the routes.

The full suite (`bin/test`) must pass, confirming no transitive breakage across
`db`, `service`, `admin-server`, `net`, and `rest-server`.

## Implementation Plan

Each step is locally verifiable. Run all commands inside the Nix dev shell. The
tree will not fully compile until the DAO refactors and consumer edits are
mutually consistent, so cross-module compilation is gated at step 8.

1. **Add the capability interfaces.** Create
   `db/src/main/kotlin/ed/unicoach/db/dao/Dao.kt` with the ten interfaces as
   specified. No implementors yet.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin` succeeds.

2. **Add the query scaffolding.** Create
   `db/src/main/kotlin/ed/unicoach/db/dao/SqlSessionQueries.kt` with the
   `SqlSession` extension helpers (`queryOne`, `queryList`, `mutateReturning`,
   `execute`, `occUpdate`), the column-map generator helpers (`Bind` typealias,
   `insertReturning`, `updateColumnsReturning`, `softDeleteReturning`), the JDBC
   bind/read helpers, and `SoftDeleteScope.predicate`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin` succeeds.

3. **Add the edit-input models.** Create
   `db/src/main/kotlin/ed/unicoach/db/models/UserEdit.kt` and
   `db/src/main/kotlin/ed/unicoach/db/models/StudentEdit.kt` as specified.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin` succeeds.

4. **Refactor `UsersDao`.** Declare the capability interfaces; change
   `findById`/`findByIdForUpdate` to `scope`; rename `listAll` → `list`; change
   `update` to take `UserEdit` and call
   `updateColumnsReturning("users", edit.id,
   edit.version, mapOf("email" to …, "name" to …, "display_name" to …,
   "is_admin" to …), …)`
   (5-column SET: version, email, name, display_name, is_admin). `create` →
   `insertReturning`. `delete`/`undelete` → `softDeleteReturning` with OCC
   (`currentVersion` non-null). `revertToVersion` keeps its full-row OCC write
   via `occUpdate` (auth columns included — not expressible as a
   `Map<String, Bind>` without an `AuthMethod` helper, so stays hand-written).
   `updatePhysicalRecord` keeps the same full-row OCC write as two statements —
   `execute("SET LOCAL …")` + `occUpdate` — preserving bypass behaviour. Add
   `override` modifiers; delete now-dead inline envelope/probe code.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin` succeeds.

5. **Refactor `StudentsDao`.** As step 4 for `findById`/`findByUserId`/`update`
   (`StudentEdit` via `updateColumnsReturning`); `create` → `insertReturning`;
   `delete`/`undelete` → `softDeleteReturning` with OCC; add `undelete`; declare
   interfaces.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin` succeeds.

6. **Refactor `SessionsDao`.** Declare `Findable`/`Listable`/`Creatable`/
   `Destroyable`; rename `listAll` → `list` and `deleteById` → `destroy`; remove
   the private `executeSafely` wrapper. SELECT readers (`findById`, `list`,
   `listByUser`) → `queryOne`/`queryList`. `create` stays hand-written
   (`mutateReturning`) because `expires_at = NOW() + interval` is a SQL
   expression outside `insertReturning`'s `Bind` model. OCC RETURNING writes
   (`remintToken`, `extendExpiry`, `revokeByTokenHash`) → `mutateReturning` each
   with its specific `onNoRow` message. `destroy` → `execute` + affected-count
   `NotFoundException` check. `expireZombieSessions` → `execute`.
   `findByTokenHash` retains its post-fetch `contentEquals` re-check, so it
   adopts only the envelope.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin` succeeds.

7. **Refactor `ConvosDao` and `SystemPromptsDao`.** `ConvosDao`: declare
   `SoftDeleteFindable`/`Creatable`/`Deletable`; `create` → `insertReturning`;
   `delete`/`undelete` → `softDeleteReturning` (non-OCC, `currentVersion=null`);
   `rename` stays hand-written via `mutateReturning` (its
   `WHERE id = ? AND deleted_at IS NULL` active-row guard is a non-id predicate
   outside the generator boundary); log append/read stays as
   `mutateReturning`/`queryList`/`queryOne` (complex SQL expressions and
   `::jsonb` casts remain hand-written). Remove private
   `bindJsonOrNull`/`readJsonOrNull`/`bindNullableInt`, switching all call sites
   — including row mappers `mapRequest`/`mapResponse`/`mapTurn` — to
   `setJsonbOrNull`/`setIntOrNull` and `getJsonbOrNull`. `SystemPromptsDao`:
   route `findByNameAndVersion` through `queryOne`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin` succeeds; then
     `nix develop -c grep -n "executeSafely\|bindJsonOrNull\|readJsonOrNull" db/src/main/kotlin/ed/unicoach/db/dao/*.kt`
     returns nothing.

8. **Fix the consumers.** Update `admin/resources/UsersResource.kt`,
   `StudentsResource.kt`, `SessionsResource.kt` (map the admin
   `includeDeleted: Boolean` to `SoftDeleteScope.ALL/ACTIVE` at the DAO call
   boundary; `listAll` → `list`; `deleteById` → `destroy`; build
   `UserEdit`/`StudentEdit`) and `service/student/StudentService.kt` (build
   `StudentEdit`) and `service/auth/AuthService.kt` (two `SessionsDao.create`
   named-arg sites: `newSession=` → `input=`).
   - Verify: `nix develop -c ./gradlew compileKotlin` succeeds across all
     modules.

9. **Update and add tests.** Apply the retargets in the Tests section to the
   three edited DAO suites (`UsersDaoTest`, `StudentsDaoTest`,
   `SessionsDaoTest`); add the new `UserEdit`-immutability and
   `StudentsDao.undelete` cases. `ConvosDaoTest`, `StudentServiceTest`, and
   `SessionsResourceTest` are not edited — they recompile and run unchanged as
   the behaviour-preservation net.
   - Verify: `nix develop -c ./gradlew compileTestKotlin` succeeds across all
     modules.

10. **Run the scope-guard suites.**
    - Verify:
      `nix develop -c bin/test --force :db:test :service:test :admin-server:test`
      passes; read the JUnit XML under each module's `build/test-results/` to
      confirm non-zero executed counts.

11. **Run the full suite.**
    - Verify: `nix develop -c bin/test --force` passes.

## Files Modified

- `db/src/main/kotlin/ed/unicoach/db/dao/Dao.kt` — new: the ten capability
  interfaces.
- `db/src/main/kotlin/ed/unicoach/db/dao/SqlSessionQueries.kt` — new:
  `SqlSession` query/mutate helpers (`queryOne`, `queryList`, `mutateReturning`,
  `execute`, `occUpdate`); `Bind` typealias and column-map generator helpers
  (`insertReturning`, `updateColumnsReturning`, `softDeleteReturning`); JDBC
  bind/read helpers; `SoftDeleteScope.predicate`.
- `db/src/main/kotlin/ed/unicoach/db/models/UserEdit.kt` — new: update-input
  record.
- `db/src/main/kotlin/ed/unicoach/db/models/StudentEdit.kt` — new: update-input
  record.
- `db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt` — implement interfaces;
  adopt scaffolding; `findById`/`findByIdForUpdate` take `scope`; `listAll` →
  `list`; `update(UserEdit)` dropping `password_hash`/`sso_provider_id` from
  `SET`.
- `db/src/main/kotlin/ed/unicoach/db/dao/StudentsDao.kt` — implement interfaces;
  adopt scaffolding; `findById`/`findByUserId` take `scope` (the `*ForUpdate`
  lock-reads are unchanged); `update(StudentEdit)`; add `undelete`.
- `db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt` — implement interfaces;
  adopt scaffolding; `listAll` → `list`; `deleteById` → `destroy`; remove
  `executeSafely`.
- `db/src/main/kotlin/ed/unicoach/db/dao/ConvosDao.kt` — implement
  `SoftDeleteFindable`/`Creatable`/`Deletable`; adopt scaffolding for entity and
  log methods; remove private JSON/null bind helpers.
- `db/src/main/kotlin/ed/unicoach/db/dao/SystemPromptsDao.kt` — route
  `findByNameAndVersion` through `queryOne`.
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/UsersResource.kt` —
  `includeDeleted` → `scope` at every DAO call (the `get`/`delete`/`undelete`/
  `updateUser` `UsersDao.findById` calls and the nested-route
  `StudentsDao.findByUserId` calls); `listAll` → `list`; build `UserEdit` in
  `updateUser`; build `StudentEdit` in the nested `/student/update` route.
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/StudentsResource.kt`
  — `includeDeleted` → `scope` at DAO calls.
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/SessionsResource.kt`
  — `listAll` → `list`; `deleteById` → `destroy`.
- `service/src/main/kotlin/ed/unicoach/student/StudentService.kt` — build a
  `StudentEdit` for the update path.
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt` — two
  `SessionsDao.create` named-arg call sites: `newSession=` → `input=`.
- `db/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt` — retarget signatures;
  add `UserEdit`-immutability case.
- `db/src/test/kotlin/ed/unicoach/db/dao/StudentsDaoTest.kt` — retarget
  signatures; add `undelete` cases.
- `db/src/test/kotlin/ed/unicoach/db/dao/SessionsDaoTest.kt` — `listAll` →
  `list`; `deleteById` → `destroy`.
