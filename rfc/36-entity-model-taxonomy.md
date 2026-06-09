# RFC 36: Entity Model Capability Taxonomy

## Executive Summary

This RFC replaces the three entity supertypes in
`db/src/main/kotlin/ed/unicoach/db/models/Entity.kt` with one interface per
capability — `Identifiable<ID : Id>`, `Created`, `Updated`, `Versioned`,
`SoftDeletable`, plus an `Id` marker — so each data class declares only the
capabilities it has.

The current `BaseEntity<ID, V>` welds four independent axes — identity, version,
created, updated — into one supertype. Versioning and mutability are orthogonal,
so the monolith cannot model a row with one but not the other. `Session` already
escapes it as a bare `data class` (versioned-but-immutable), and the planned RFC
32/35 convos rows (mutable + soft-deletable but unversioned) cannot be expressed
at all. RFC 35 (convos DAO) is blocked on a taxonomy that captures these shapes;
this RFC is its precursor.

Four decisions ride along, each specified and justified at its point in Detailed
Design: an `Id` marker giving identity types a backing-agnostic `asString` view;
a new typed `SessionId` replacing `Session`'s raw `UUID`; collapsing the per-row
version to a plain `Int` (the `UserVersionId`/`StudentVersionId` value classes
are deleted); and dropping the trigger-maintained
`row_created_at`/`row_updated_at` row timestamps from every model.

The interfaces dispatch nowhere, so the supertype edits are low-risk; risk
concentrates in the `version` collapse and the `SessionId` introduction. The
existing DB-backed suites are the regression net (see Tests).

## Detailed Design

### Data Models

#### Capability interfaces (`Entity.kt`, full rewrite)

Five capability interfaces plus an `Id` marker, each declaring one concern.

```kotlin
package ed.unicoach.db.models

import java.time.Instant

interface Id {
  val asString: String
}

interface Identifiable<ID : Id> {
  val id: ID
}

interface Created {
  val createdAt: Instant
}

interface Updated {
  val updatedAt: Instant
}

interface Versioned {
  val version: Int
}

interface SoftDeletable {
  val deletedAt: Instant?
}
```

Semantics, one line each:

- `Id` — marker for identity types. `asString` is a backing-agnostic view
  (`UUID.toString()`, the string itself, `Int.toString()`) for logging,
  serialization, and generic code; implementors retain their typed `value`. The
  view is derived, not stored, so the typed key and its DB binding are preserved
  (chosen over a `value: String` interface, which would break native `uuid`
  binding and discard compile-time validity).
- `Identifiable<ID : Id>` — every persisted row, including append-only logs, has
  a typed identity bound to `Id`.
- `Created` — domain creation instant; every row.
- `Updated` — last domain mutation instant; mutable rows only.
- `Versioned` — OCC version as a plain `Int`. Non-generic: a version is a
  per-row counter, not a domain-typed key, so it needs no wrapper.
- `SoftDeletable` — nullable `deletedAt`; logical-delete rows only. `Instant?`
  because a live row has no deletion instant.

There is no `RowTimestamped` capability — `row_created_at`/`row_updated_at` are
dropped from every model (the columns and triggers stay in Postgres).

#### Identity value classes

Each identity type implements `Id`, keeps its typed `value`, and derives
`asString`:

```kotlin
@JvmInline value class UserId(val value: UUID) : Id { override val asString get() = value.toString() }
@JvmInline value class StudentId(val value: UUID) : Id { override val asString get() = value.toString() }
@JvmInline value class SessionId(val value: UUID) : Id { override val asString get() = value.toString() }
```

`SessionId` is new (`SessionId.kt`). `UserId`/`StudentId` gain only the `: Id`
supertype and `asString`; their `value: UUID` is unchanged, so existing
`stmt.setObject(n, id.value)` binds continue to pass a `java.util.UUID` to the
`uuid` columns natively. A value class boxes only when handled through its `Id`
supertype; no current call site does, so the markers add no runtime cost.

The `UserVersionId` and `StudentVersionId` value classes are deleted — versions
are now `Int`.

#### Retrofitted implementors

`User` and `Student` — full mutable lifecycle:

```kotlin
data class User(
  override val id: UserId,
  val email: EmailAddress,
  val name: PersonName,
  val displayName: DisplayName?,
  val authMethod: AuthMethod,
  override val version: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
) : Identifiable<UserId>,
  Created,
  Updated,
  Versioned,
  SoftDeletable
```

`Student` carries the same capability set over its own field set (`userId`,
`expectedHighSchoolGraduationDate`; no email/name) and undergoes the same
retype. Both drop `rowCreatedAt`/`rowUpdatedAt`; `versionId` is renamed
`version` and changes from the value class to `Int`; `deletedAt` becomes
`override val` (it was a plain `val`, since `AdvancedEntity` did not declare
it).

`UserVersion` and `StudentVersion` — immutable snapshot rows:

```kotlin
data class UserVersion(
  override val id: UserId,
  override val version: Int,
  val email: EmailAddress,
  val name: PersonName,
  val displayName: DisplayName?,
  val authMethod: AuthMethod,
  override val createdAt: Instant,
  val updatedAt: Instant,
  val deletedAt: Instant?,
) : Identifiable<UserId>,
  Created,
  Versioned
```

`updatedAt` and `deletedAt` stay plain `val`s — snapshot data captured when the
version was written, not live capabilities — so a version row does not implement
`Updated` or `SoftDeletable`. This is the successor of the current "version
classes do NOT implement `AdvancedEntity`" distinction. Row timestamps removed;
`versionId` is renamed `version` and collapses to `Int`.

`StudentVersion` mirrors `UserVersion` but has **zero producers** — no DAO
constructs it and nothing reads it (only the `data class` declaration exists).
It is retrofitted (not deleted) solely so the version-row model set stays
uniform with `UserVersion`; the edit is a no-op retype with no runtime effect.

`Session` — versioned, immutable:

```kotlin
data class Session(
  override val id: SessionId,
  override val version: Int,
  override val createdAt: Instant,
  val userId: UserId?,
  val metadata: String?,
  val userAgent: String?,
  val initialIp: String?,
  val expiresAt: Instant,
) : Identifiable<SessionId>,
  Created,
  Versioned
```

`id` becomes `SessionId` (was raw `UUID`); `version` keeps its name, now an
`override val` of `Versioned`. `Session` carries a taxonomy supertype for the
first time.

### DAO and call-site changes

No schema change. The `version`, `row_created_at`, `row_updated_at`, and `id`
columns are untouched; queries keep `SELECT *` / `RETURNING *`.

- `UsersDao` — `mapUser`/`mapUserVersion`: drop the two `row_*_at` reads and
  constructor args; `version = rs.getInt("version")` (no wrapper). Version
  parameters `targetVersion`/`currentVersion`/`targetHistoricalVersion` become
  `Int`; `currentVersion.value` becomes the bare `Int`, and
  `user.versionId.value` reads become `user.version`. Remove the `UserVersionId`
  import.
- `StudentsDao` — `mapStudent`: drop the two row args;
  `version =
  rs.getInt("version")`. `currentVersion` parameter → `Int`;
  `student.versionId.value` reads and binds become `student.version`. Remove the
  `StudentVersionId` import.
- `SessionsDao` — `mapSession`:
  `id = SessionId(UUID.fromString(rs.getString("id")))` and map
  `version = rs.getInt("version")` (field name `version`, unchanged from the
  column). `remintToken`/`extendExpiry` take `id: SessionId` (was `UUID`) and
  bind `id.value`; add the `SessionId` import. (Version parameters are already
  `Int`.)
- `StudentService` — `expectedVersion: StudentVersionId` → `Int`; remove the
  import. The `existing.version != expectedVersion` comparison is
  `Int`-to-`Int`.
- `StudentRoutes` — `StudentVersionId(request.version)` → `request.version`;
  `student.versionId.value` → `student.version`; remove the import. The REST
  DTOs `UpdateStudentRequest.version` and `StudentResponse.version` are already
  `Int` and unchanged.
- `AuthService` and `SessionExpiryHandler` — no edit: both already read
  `session.version`, which this rename leaves untouched. `Session.id` becomes
  `SessionId` but passes straight through, so neither file changes.

### Error Handling / Edge Cases

None introduced; the change is structural. The compiler is the enforcement
mechanism: a data class that does not satisfy its declared capabilities fails to
compile, and every missed `.versionId` → `version` rename, leftover `.value` on
a now-`Int` version, `UserVersionId`, or `StudentVersionId` reference fails to
compile. `asString` is total — pure formatting, no parsing, no failure mode.

### Dependencies

- Downstream: RFC 35 (convos DAO) consumes this taxonomy — the convos row as
  `Identifiable + Created + Updated + SoftDeletable` (unversioned), the convos
  message log as `Identifiable + Created`. RFC 35 is blocked on this RFC.
- Upstream: none. No new libraries, configuration, or migrations.

## Tests

No new test files. The edits below are mechanical retypes/renames that keep the
existing suites compiling and green; per-test notes record what each retained
suite continues to prove.

- `UsersDaoTest` (`db/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt`) —
  replace `.versionId.value` with `.version` (line 387, both sides of the
  comparison). Version args passed to `delete`/`undelete`/`restore` (291, 310,
  379, 380) are now `Int`, no syntactic change. Green proves
  `User`/`UserVersion` round-trip and the OCC restore math.
- `StudentsDaoTest` (`db/src/test/kotlin/ed/unicoach/db/dao/StudentsDaoTest.kt`)
  — (a) delete two pieces of model-level row-timestamp coverage: the
  `assertTrue(updated.rowUpdatedAt >= created.rowUpdatedAt)` line (207, inside
  the kept `update bumps updated_at` test) and the entire
  `timestamp bypass bumps row_updated_at but not updated_at` test (298–323), per
  the architect decision to drop model-level 4-timestamp coverage; keep
  `expectImmutableFailure("row_created_at", ...)` (258), which uses raw SQL and
  still verifies DB-level immutability. (b) replace `.versionId.value` with
  `.version` (85, 204, 291). Green proves `Student` round-trip and OCC.
- `SessionsDaoTest` (`db/src/test/kotlin/ed/unicoach/db/dao/SessionsDaoTest.kt`)
  — unchanged: `Session.version` keeps its name, and `created.id` (now
  `SessionId`) passes straight to `extendExpiry`. Green proves `Session`
  round-trip, `SessionId` mapping, and OCC increment.
- `SessionExpiryHandlerTest`
  (`net/src/test/kotlin/ed/unicoach/net/handlers/SessionExpiryHandlerTest.kt`) —
  unchanged: `found.getOrNull()!!.version` is already correct under the kept
  name.
- `StudentServiceTest`
  (`service/src/test/kotlin/ed/unicoach/student/StudentServiceTest.kt`) —
  `StudentVersionId(1)` → `1` (168); `created.versionId` → `created.version`
  (still `Int`, passed to `updateStudent`); remove the `StudentVersionId`
  import.
- `StudentRoutingTest` — unchanged (HTTP/JSON; `version` is already `Int`).
  Green confirms the `StudentRoutes` mapping edits preserve end-to-end behavior.

The full suite (`bin/test`) must pass, confirming no transitive breakage across
`db`, `service`, `net`, and `rest-server`.

## Implementation Plan

Order: establish the `Id`/identity layer, narrow the models, fix the DAOs, then
the version-type collapse across consumers, then tests. The tree will not
compile until the value-class and consumer edits are mutually consistent, so
compilation is gated at step 8. Run all commands inside the Nix dev shell.

1. **Rewrite `Entity.kt`.** Replace its entire contents with `Id`,
   `Identifiable`, `Created`, `Updated`, `Versioned`, `SoftDeletable` as
   specified. Delete `BaseEntity`, `AdvancedEntity`, `BaseVersionEntity`; create
   no `RowTimestamped`.
   - Verify:
     `nix develop -c grep -nE "BaseEntity|AdvancedEntity|BaseVersionEntity|RowTimestamped" db/src/main/kotlin/ed/unicoach/db/models/Entity.kt`
     returns nothing.

2. **Add the `Id` supertype to identity value classes.** Add `: Id` and the
   `asString` getter to `UserId`
   (`db/src/main/kotlin/ed/unicoach/db/models/UserId.kt`) and `StudentId`
   (`db/src/main/kotlin/ed/unicoach/db/models/StudentId.kt`). Create
   `db/src/main/kotlin/ed/unicoach/db/models/SessionId.kt`.
   - Verify (deferred to step 8): compilation.

3. **Delete the version value classes.** Delete
   `db/src/main/kotlin/ed/unicoach/db/models/UserVersionId.kt`, and delete the
   `StudentVersionId` value class from
   `db/src/main/kotlin/ed/unicoach/db/models/StudentId.kt`.
   - Verify:
     `nix develop -c grep -rn "UserVersionId|StudentVersionId" --include=*.kt db service rest-server`
     lists only the consumer files edited in steps 7–9.

4. **Retrofit `User.kt`/`Student.kt`.** Remove `rowCreatedAt`/`rowUpdatedAt`;
   rename `versionId` → `version` (type `Int`); supertypes
   `Identifiable + Created + Updated + Versioned +
   SoftDeletable`;
   `deletedAt` → `override val`.
   - Verify (deferred to step 8): compilation.

5. **Retrofit `UserVersion.kt`/`StudentVersion.kt`.** Remove
   `rowCreatedAt`/`rowUpdatedAt`; rename `versionId` → `version` (type `Int`);
   supertypes `Identifiable +
   Created + Versioned`; `updatedAt` → plain
   `val`.
   - Verify (deferred to step 8): compilation.

6. **Retrofit `Session.kt`.** `id: SessionId`; keep `version` (now an
   `override val`); supertypes `Identifiable<SessionId> + Created + Versioned`;
   `override` modifiers on `id`/`version`/`createdAt`.
   - Verify (deferred to step 8): compilation.

7. **Fix the DAOs.** Apply the "DAO and call-site changes" edits to
   `db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt`,
   `db/src/main/kotlin/ed/unicoach/db/dao/StudentsDao.kt`, and
   `db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt` (row reads dropped,
   version `Int`, `SessionId` mapping and parameters).
   - Verify: `nix develop -c ./gradlew :db:compileKotlin` succeeds.

8. **Fix non-DAO consumers.** Apply the same section's edits to
   `service/src/main/kotlin/ed/unicoach/student/StudentService.kt` and
   `rest-server/src/main/kotlin/ed/unicoach/rest/routing/StudentRoutes.kt`.
   `AuthService` and `SessionExpiryHandler` need no edit (`session.version` is
   unrenamed).
   - Verify: `nix develop -c ./gradlew compileKotlin` succeeds across all
     modules.

9. **Update the tests.** Apply the edits in the Tests section to `UsersDaoTest`,
   `StudentsDaoTest`, and `StudentServiceTest` (`SessionsDaoTest` and
   `SessionExpiryHandlerTest` need no edit).
   - Verify: `nix develop -c ./gradlew compileTestKotlin` succeeds across all
     modules.

10. **Run the scope-guard suites.**
    - Verify:
      `nix develop -c bin/test :db:test :service:test :rest-server:test :net:test`
      passes. Read the JUnit XML under each module's `build/test-results/` to
      confirm non-zero test counts (guard against the false-green
      flag-forwarding gotcha).

11. **Run the full suite.**
    - Verify: `nix develop -c bin/test` passes.

## Files Modified

- `db/src/main/kotlin/ed/unicoach/db/models/Entity.kt` — full rewrite: delete
  the three old interfaces; add `Id` + five capability interfaces (no
  `RowTimestamped`).
- `db/src/main/kotlin/ed/unicoach/db/models/UserId.kt` — implement `Id`; add
  `asString`.
- `db/src/main/kotlin/ed/unicoach/db/models/StudentId.kt` — `StudentId`
  implements `Id` and adds `asString`; delete the `StudentVersionId` value
  class.
- `db/src/main/kotlin/ed/unicoach/db/models/SessionId.kt` — new: `SessionId`
  value class implementing `Id`.
- `db/src/main/kotlin/ed/unicoach/db/models/UserVersionId.kt` — delete.
- `db/src/main/kotlin/ed/unicoach/db/models/User.kt` — remove row fields; rename
  `versionId` → `version` (`Int`); new supertypes; `deletedAt` → `override val`.
- `db/src/main/kotlin/ed/unicoach/db/models/Student.kt` — remove row fields;
  rename `versionId` → `version` (`Int`); new supertypes; `deletedAt` →
  `override val`.
- `db/src/main/kotlin/ed/unicoach/db/models/UserVersion.kt` — remove row fields;
  rename `versionId` → `version` (`Int`); new supertypes; `updatedAt` → plain
  `val`.
- `db/src/main/kotlin/ed/unicoach/db/models/StudentVersion.kt` — remove row
  fields; rename `versionId` → `version` (`Int`); new supertypes; `updatedAt` →
  plain `val`.
- `db/src/main/kotlin/ed/unicoach/db/models/Session.kt` — `id: SessionId`;
  `version` kept (now `override val`); add
  `Identifiable<SessionId>, Created, Versioned`.
- `db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt` — drop row reads/args;
  `version` field and version params to `Int`; rename `.versionId` reads →
  `.version`; drop `.value`; remove `UserVersionId` import.
- `db/src/main/kotlin/ed/unicoach/db/dao/StudentsDao.kt` — drop row args;
  `version` field and version param to `Int`; rename `.versionId` reads →
  `.version`; drop `.value`; remove `StudentVersionId` import.
- `db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt` — `mapSession` maps
  `SessionId` and the `version` field; `remintToken`/`extendExpiry` take
  `id: SessionId` and bind `id.value`; add `SessionId` import.
- `service/src/main/kotlin/ed/unicoach/student/StudentService.kt` —
  `expectedVersion: StudentVersionId` → `Int`; `existing.versionId` →
  `existing.version`; remove import.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/StudentRoutes.kt` —
  `StudentVersionId(request.version)` → `request.version`;
  `student.versionId.value` → `student.version`; remove import.
- `db/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt` — `.versionId.value` →
  `.version` (line 387).
- `db/src/test/kotlin/ed/unicoach/db/dao/StudentsDaoTest.kt` — delete the
  `rowUpdatedAt` assertion inside `update bumps updated_at` (207) and the entire
  `timestamp bypass bumps row_updated_at but not updated_at` test (298–323);
  `.versionId.value` → `.version` (85, 204, 291).
- `service/src/test/kotlin/ed/unicoach/student/StudentServiceTest.kt` —
  `StudentVersionId(1)` → `1` (168); `created.versionId` → `created.version`;
  remove import.
