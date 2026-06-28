# RFC 84: Entity version composition

## Executive Summary

The three versioned entities each carry a hand-written `*Version` data class
(`UserVersion`, `StudentVersion`, `CollegeVersion`) that structurally duplicates
its entity's fields and is populated by a `mapXVersion` DAO mapper that
duplicates the entity's `mapX` mapper line-for-line. Each `*_versions` row is a
complete snapshot of its entity — its columns are exactly the columns the entity
mapper already reads (verified for `users_versions`, `students_versions`,
`colleges_versions`) — so the duplicated type and mapper carry no information
the entity type does not.

This RFC replaces the three `*Version` classes with one generic composed type,
`Version<E : Versioned>`, that holds the entity snapshot itself. Each
`mapXVersion` is removed; the version readers wrap the existing `mapX` result.
`VersionHistory<ID, V>` binds `V` to `Version<E>`, and its element's version is
read from the wrapped entity (every entity is `Versioned`), so no version-row
metadata is stored twice. The admin history panels read snapshot fields through
the wrapped entity.

The change is a structural/DRY refactor. There is no schema, migration, trigger,
or write-path change; version history rows, admin columns, and ordering are
unchanged. It applies to all three versioned entities at once so none diverges.

## Detailed Design

### Composed snapshot type

A single generic type replaces the three per-entity version classes:

```kotlin
package ed.unicoach.db.models

data class Version<out E : Versioned>(
  val entity: E,
) : Versioned {
  override val version: Int
    get() = entity.version
}
```

`Version<E>` holds only the entity snapshot. It carries no `version` field of
its own: the version is a column of every `*_versions` row, `mapX` reads it into
`entity.version`, and `Version` re-exposes it through `Versioned` so callers
read `v.version` and the type itself satisfies the `VersionHistory` bound. `E`
is declared `out` because a snapshot is read-only. No other version-row metadata
exists for any of the three entities (`updatedAt`, `deletedAt`, timestamps, and
all domain fields already live on the entity), so the wrapper holds nothing
else.

The three deleted classes — `UserVersion`, `StudentVersion`, `CollegeVersion` —
each implemented `Identifiable<ID>`, `Created`, `Versioned` and re-declared
every entity field. `Version<E>` reaches those through `v.entity` (e.g.
`v.entity.id`, `v.entity.createdAt`, `v.entity.email`), and the entity's own
field types (`EmailAddress`, `PersonName`, `PasswordHash` on `User`;
`PartialDate` on `Student`; raw scalars on `College`) carry through unchanged
because the wrapper holds the real entity.

### Generic history capability

`VersionHistory` tightens its element bound to `Versioned`:

```kotlin
interface VersionHistory<ID : Id, V : Versioned> {
  fun listVersions(session: SqlSession, id: ID): Result<List<V>>
}
```

Tightening the bound makes `Dao.kt` reference `Versioned`, so it gains an
`import` of the marker from the `db/models` package (the only new intra-package
dependency the change introduces). Implementors bind `V` to the composed type:
`UsersDao :
VersionHistory<UserId, Version<User>>`,
`StudentsDao :
VersionHistory<StudentId, Version<Student>>`,
`CollegesDao :
VersionHistory<CollegeId, Version<College>>`. The bound documents
that every history element is a versioned snapshot and is satisfied by
`Version<E>`'s forwarded `version`.

### DAO mappers and readers

Each DAO drops its private `mapXVersion` function. The version readers compose
the surviving entity mapper: `listVersions` (all three DAOs),
`UsersDao.findVersion`, and the historical lookup inside
`UsersDao.revertToVersion` map each `*_versions` row with `mapX` and wrap the
result in `Version`. The SQL
(`SELECT * FROM x_versions WHERE id = ? ORDER BY
version`, and the
`id`+`version` lookup in `findVersion`) is unchanged; only the `map` argument
and declared result type change:

- `UsersDao.listVersions`: `Result<List<Version<User>>>`
- `UsersDao.findVersion`: `Result<Version<User>>`
- `StudentsDao.listVersions`: `Result<List<Version<Student>>>`
- `CollegesDao.listVersions`: `Result<List<Version<College>>>`

`UsersDao.revertToVersion` reads the historical column values through the
wrapped entity (`target.entity.email`, `target.entity.name`,
`target.entity.displayName`, `target.entity.passwordHash`,
`target.entity.isAdmin`, `target.entity.emailVerifiedAt`) before passing them to
the existing `updateFullRow` writer. Its own result type (`Result<User>`) and
the OCC write path are unchanged.

`mapCollege` reads the `version` column (RFC 82 gave `colleges` a `version`), so
wrapping it requires no entity-mapper change; the same holds for `mapUser` and
`mapStudent`.

### Admin history panels

The three admin resources render `EdgePanel.Table` history rows from the version
list. Each data cell reads through the wrapped entity; the produced column set,
cell strings, and row order are unchanged:

- `UsersResource`: `v.entity.version`, `v.entity.email.value`,
  `v.entity.name.value`, `v.entity.isAdmin`, `v.entity.emailVerifiedAt`,
  `v.entity.updatedAt`, `v.entity.deletedAt`.
- `StudentsResource`: `v.entity.version`,
  `v.entity.expectedHighSchoolGraduationDate.toIso()`, `v.entity.updatedAt`,
  `v.entity.deletedAt`.
- `CollegesResource`: `v.entity.version`, `v.entity.name`, `v.entity.city`,
  `v.entity.state`, `v.entity.control`, `v.entity.admissionRate`,
  `v.entity.netPrice`, `v.entity.updatedAt`.

### Error handling / edge cases

No new failure modes. `CorruptPersistedValueException` from
`StudentsDao.mapGraduationDate` and `findVersion`'s `NotFoundException` →
`TargetVersionMissingException` mapping in `revertToVersion` are reached through
the unchanged entity mapper and control flow. A historical row whose `version`
differs from the live entity's `version` is represented correctly because `mapX`
reads that row's `version` column into `entity.version`.

### Dependencies

None added. `Version<E>` lives in the existing `db/models` package alongside the
`Versioned` marker it bounds on.

## Tests

The existing DAO and admin-resource suites assert version-history behaviour and
remain the verification surface; this refactor must leave their observable
results identical. No new behaviour is introduced, so no new test files are
created. Only reads of **entity fields** move to `.entity.*`; a bare
`versions[i].version` read needs no edit because `Version` forwards `version`
through `Versioned`. The following existing tests are updated to read snapshot
fields through `.entity`, and continue to assert the same values:

- **`UsersDaoTest`**
  - `listVersions` suites (around lines 535, 579, 651, 708): assert the wrapped
    entities' fields and ascending `version` order across the soft-delete and
    multi-write histories already exercised; reads become `versions[i].entity.*`
    / `versions[i].version`.
  - `findVersion` (around line 451): the bound result is `Version<User>`;
    assertions read `v1.entity.*`.
  - `revertToVersion` suites (around lines 315, 386): assert on the returned
    `User` and the `TargetVersionMissingException` path; unchanged, confirming
    the internal `.entity` reads restore the same column values.
- **`StudentsDaoTest`**
  - `listVersions returns the students historical rows ascending` (line 409):
    reads become `versions[i].entity.*`, including the `PartialDate`
    `expectedHighSchoolGraduationDate` carried through the entity.
- **`CollegesDaoTest`**
  - `listVersions` suites (around lines 401, 416, 431, 468): reads become
    `history[i].entity.*`; ascending `version` order and content-change history
    assertions unchanged.
- **`UsersResourceTest`, `StudentsResourceTest`, `CollegesResourceTest`**:
  assert the rendered history-panel rows. Output is identical, so these are
  expected to pass without edit; they are in scope only if an assertion
  references a removed type.

Verification is the full DB-backed suite for the two affected modules:
`nix develop -c bin/test db -f` and `nix develop -c bin/test admin-server -f`
(force re-run; confirm a non-zero executed count, not an all-cache no-op).

## Implementation Plan

1. **Add `Version<E>`; delete the three `*Version` classes.**
   - Create `db/src/main/kotlin/ed/unicoach/db/models/Version.kt` with the
     `Version<out E : Versioned>` type above.
   - Delete `db/src/main/kotlin/ed/unicoach/db/models/UserVersion.kt`,
     `StudentVersion.kt`, `CollegeVersion.kt`.
   - Scan for any surviving KDoc doc-links to the deleted types
     (`grep -rn '\[UserVersion\]\|\[StudentVersion\]\|\[CollegeVersion\]'`); a
     dangling `[...]` link does not fail compilation but should be repointed to
     `[Version]` or the entity. (None are expected — the types have no external
     consumers — but the scan confirms it.)
   - Verify: `nix develop -c ./gradlew :db:compileKotlin` fails only at the DAO
     references to the deleted types (expected, fixed next).
2. **Tighten `VersionHistory` and re-point all three DAOs.**
   - In `db/src/main/kotlin/ed/unicoach/db/dao/Dao.kt`, change the interface to
     `VersionHistory<ID : Id, V : Versioned>` and import `Versioned`.
   - In `UsersDao.kt`: replace the `UserVersion` import with `Version`; bind
     `VersionHistory<UserId, Version<User>>`; delete `mapUserVersion`; change
     `listVersions` and `findVersion` to wrap `mapUser` in `Version` and update
     their result types; change `revertToVersion` to read `target.entity.*`.
   - In `StudentsDao.kt`: replace import; bind
     `VersionHistory<StudentId,
     Version<Student>>`; delete
     `mapStudentVersion`; wrap `mapStudent` in `listVersions`.
   - In `CollegesDao.kt`: replace import; bind
     `VersionHistory<CollegeId,
     Version<College>>`; delete
     `mapCollegeVersion`; wrap `mapCollege` in `listVersions`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`.
3. **Update the admin history panels.**
   - In `UsersResource.kt`, `StudentsResource.kt`, `CollegesResource.kt`, change
     the history-panel cell expressions to read `v.entity.*` (and
     `v.entity.version`) as listed in Detailed Design.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`.
4. **Update the DAO tests.**
   - In `UsersDaoTest.kt`, `StudentsDaoTest.kt`, `CollegesDaoTest.kt`, change
     version-result field reads to `.entity.*`.
   - Verify:
     `nix develop -c ./gradlew :db:compileTestKotlin
     :admin-server:compileTestKotlin`.
5. **Run the affected suites.**
   - Verify: `nix develop -c bin/test db -f` and
     `nix develop -c bin/test admin-server -f`; confirm both report executed
     tests (not all-cache) and pass.
6. **Lint gate.**
   - Verify: `nix develop -c bin/test check` (ktlint + tests).

## Files Modified

- `db/src/main/kotlin/ed/unicoach/db/models/Version.kt` (new)
- `db/src/main/kotlin/ed/unicoach/db/models/UserVersion.kt` (deleted)
- `db/src/main/kotlin/ed/unicoach/db/models/StudentVersion.kt` (deleted)
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeVersion.kt` (deleted)
- `db/src/main/kotlin/ed/unicoach/db/dao/Dao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/StudentsDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/UsersResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/StudentsResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/CollegesResource.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/StudentsDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/CollegesDaoTest.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/UsersResourceTest.kt`
  (defensive; expected no change)
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/StudentsResourceTest.kt`
  (defensive; expected no change)
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/CollegesResourceTest.kt`
  (defensive; expected no change)
