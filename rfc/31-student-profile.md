# 31 — Student Profile

## Executive Summary

This RFC introduces the `students` entity — the first domain entity beyond
authentication and the spine of the college-preparation product. A student is
the primary user; a row in `students` is what distinguishes a student account
from a future `partners` account, with role determined by table membership
rather than a column on `users`. Each student is owned 1:1 by a user (`user_id`,
total unique index) and stores one datum in this RFC: the expected high-school
graduation date, modeled as a variable-precision value (year, year+month, or
year+month+day). That date is the stable anchor from which the roadmap (RFC #32)
will derive grade level and milestone deadlines; grade derivation itself is
deferred to #32.

The entity follows the established 4-timestamp / `version`-OCC / soft-delete /
`*_versions` pattern (RFC #06). All access is scoped to the owning user via an
owner-resolved singleton API (`/api/v1/students`, `/api/v1/students/me`) with no
path identifier, so the observer/share layer (RFC #33) can add id-addressed
reads additively. Account deletion is initiated through the student resource:
deleting a student soft-deletes the student row and the owning user row
atomically in one transaction (identical `deleted_at`), which auto-invalidates
all sessions via the existing deleted-user filter.

Scope is backend-only (DB → DAO → service → REST → OpenAPI), consistent with the
project convention of dedicated iOS RFCs (#12, #27, #30). The iOS onboarding
flow and the variable-precision date control are a separate follow-up RFC.

## Detailed Design

### Dependencies

- Shared trigger functions from `db/schema/0000.shared-functions.sql`
  (`update_timestamp`, `enforce_versioning`, `prevent_physical_delete`,
  `prevent_immutable_updates`).
- `users` table (RFC #06) and `sessions` table (RFC #11).
- `Result`-type error conventions (RFC #24).
- Auth session resolution (`AuthService.getCurrentUser`, RFC #13) for mapping
  the session cookie to the current user.
- `java.time` (JVM built-in) for the variable-precision date type. No new
  third-party dependency; `kotlinx-datetime` is intentionally not introduced.

### Prerequisite — generalize the entity base interfaces

`BaseEntity.versionId` is currently hardcoded to `UserVersionId`, which would
force `Student` to carry a `UserVersionId` (a cross-entity type leak). The base
interfaces are generalized over the version type:

```kotlin
interface BaseEntity<ID : Any, V : Any> {
    val id: ID
    val versionId: V
    val createdAt: Instant
    val updatedAt: Instant
}
interface AdvancedEntity {
    val rowCreatedAt: Instant
    val rowUpdatedAt: Instant
}
interface BaseVersionEntity<ID : Any, V : Any> : BaseEntity<ID, V>
```

`User : BaseEntity<UserId, UserVersionId>` and
`UserVersion : BaseVersionEntity<UserId, UserVersionId>` are updated
accordingly. Existing `.versionId` callers are source-compatible (they operate
on `User`, whose version type is unchanged).

### Data Model — `students` table

```sql
CREATE TABLE students (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  version INTEGER NOT NULL DEFAULT 1,

  -- Timestamps (4-timestamp advanced pattern)
  created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  deleted_at TIMESTAMPTZ NULL,

  -- Ownership
  user_id UUID NOT NULL REFERENCES users(id),

  -- Variable-precision expected high-school graduation date
  expected_high_school_graduation_year  SMALLINT NOT NULL,
  expected_high_school_graduation_month SMALLINT NULL,
  expected_high_school_graduation_day   SMALLINT NULL,

  CONSTRAINT grad_month_range CHECK (
    expected_high_school_graduation_month BETWEEN 1 AND 12),
  CONSTRAINT grad_day_requires_month CHECK (
    expected_high_school_graduation_day IS NULL
    OR expected_high_school_graduation_month IS NOT NULL),
  CONSTRAINT grad_date_valid CHECK (
    expected_high_school_graduation_day IS NULL
    OR make_date(
         expected_high_school_graduation_year::int,
         expected_high_school_graduation_month::int,
         expected_high_school_graduation_day::int) IS NOT NULL)
);

-- One student per user, total (NOT partial): a user_id can never appear twice,
-- even across soft-deletes. Account deletion cascades to the user, so there is no
-- legitimate re-creation path.
CREATE UNIQUE INDEX students_user_id_unique_idx ON students (user_id);

CREATE TABLE students_versions (
  id UUID NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
  version INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  row_created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  row_updated_at TIMESTAMPTZ NOT NULL,
  deleted_at TIMESTAMPTZ NULL,
  user_id UUID NOT NULL,
  expected_high_school_graduation_year  SMALLINT NOT NULL,
  expected_high_school_graduation_month SMALLINT NULL,
  expected_high_school_graduation_day   SMALLINT NULL,
  PRIMARY KEY (id, version)
);
```

Triggers attached to `students` (in the documented BEFORE/AFTER order): physical
delete prevention, immutable-update prevention (`id`, `created_at`,
`row_created_at`), versioning enforcement, timestamp maintenance, and an
entity-specific `log_student_version` AFTER trigger writing to
`students_versions`. No string-trim trigger is required (no free-text columns in
this RFC).

`grad_date_valid` rejects impossible calendar dates (e.g. Feb 31, Feb 29 in
non-leap years) via `make_date`, which is `IMMUTABLE` and therefore legal in a
CHECK. Rejection raises SQLSTATE `22008` (datetime_field_overflow), distinct
from the `23514` (check_violation) raised by the other constraints; the DAO maps
both to validation failures (see Error Handling).

### Domain Model

```kotlin
// db/.../models/PartialDate.kt — domain-agnostic, java.time-backed, reusable
// (birthdate, test dates, deadlines) by future RFCs.
sealed interface PartialDate {
    val year: Year
    val month: Month?            // null for YearOnly
    val day: Int?                // non-null only for FullDate
    fun toIso(): String          // "2028" | "2028-06" | "2028-06-15"

    data class YearOnly(override val year: Year) : PartialDate
    data class YearAndMonth(override val year: Year, val monthOf: Month) : PartialDate
    data class FullDate(val date: LocalDate) : PartialDate

    companion object {
        fun parse(iso: String): ValidationResult<PartialDate>          // wire → domain
        fun of(year: Int, month: Int?, day: Int?): ValidationResult<PartialDate> // columns → domain
    }
}

@JvmInline value class StudentId(val value: UUID)
@JvmInline value class StudentVersionId(val value: Int)

data class Student(
    override val id: StudentId,
    val userId: UserId,
    val expectedHighSchoolGraduationDate: PartialDate,
    override val versionId: StudentVersionId,
    override val createdAt: Instant,
    override val rowCreatedAt: Instant,
    override val updatedAt: Instant,
    override val rowUpdatedAt: Instant,
    val deletedAt: Instant?,
) : BaseEntity<StudentId, StudentVersionId>, AdvancedEntity

data class NewStudent(
    val userId: UserId,
    val expectedHighSchoolGraduationDate: PartialDate,
)

data class StudentVersion(
    override val id: StudentId,
    val userId: UserId,
    val expectedHighSchoolGraduationDate: PartialDate,
    override val versionId: StudentVersionId,
    override val createdAt: Instant,
    override val rowCreatedAt: Instant,
    override val updatedAt: Instant,
    override val rowUpdatedAt: Instant,
    val deletedAt: Instant?,
) : BaseVersionEntity<StudentId, StudentVersionId>
```

**Canonical parse format (decided): zero-padded ISO only.** `PartialDate.parse`
validates in two ordered stages. The authoritative gate is the regex
`^\d{4}(-\d{2}(-\d{2})?)?$`, applied **first**: it admits exactly three
fixed-width forms — `YYYY`, `YYYY-MM`, `YYYY-MM-DD` — with month and day
components required to be two digits, the year exactly four digits, and no
leading sign. Inputs failing the regex (unpadded components such as `"2028-6"`
and `"2028-6-5"`, signed or overlong years such as `"+2028"` / `"20281"`) are
**rejected** and return `ValidationResult.Invalid`. Strings that pass the regex
are then constructed via `java.time` (`Year` / `YearMonth.parse` /
`LocalDate.parse`), which rejects out-of-range months and impossible calendar
days — the in-application mirror of the `grad_date_valid` constraint. This makes
the accepted wire form identical to `toIso()` output, so `parse`/`toIso` are
symmetric, and the OpenAPI schema for the date string carries this same
`pattern`. All parse rejections — failing regex, out-of-range month, impossible
calendar day — map to `ValidationResult.Invalid(ValidationError.InvalidFormat)`;
no new `ValidationError` variant is introduced.

### DAO

```kotlin
// db/.../dao/StudentsDao.kt
object StudentsDao {
    fun findById(session: SqlSession, id: StudentId, includeDeleted: Boolean = false): Result<Student>
    fun findByUserId(session: SqlSession, userId: UserId, includeDeleted: Boolean = false): Result<Student>
    fun findByIdForUpdate(session: SqlSession, id: StudentId): Result<Student>      // SELECT ... FOR UPDATE
    fun create(session: SqlSession, student: NewStudent): Result<Student>           // RETURNING *
    fun update(session: SqlSession, student: Student): Result<Student>             // WHERE id=? AND version=?
    fun delete(session: SqlSession, id: StudentId, currentVersion: StudentVersionId): Result<Student> // soft
}
```

Mapping helpers reconstruct `PartialDate` from the decomposed columns via
`PartialDate.of`. Because the DB constraints (`grad_month_range`,
`grad_day_requires_month`, `grad_date_valid`) already guarantee the persisted
columns form a valid partial date, an `Invalid` result from `of` on a read path
indicates row corruption, not user input — `of` carries
`ValidationError.InvalidFormat` (same variant as `parse`), but the DAO treats it
as an unrecoverable mapping error (surfaced as a `PermanentError`), never as a
user-facing validation failure. Error mapping for INSERT/UPDATE:

- `23505` on `students_user_id_unique_idx` → `StudentAlreadyExistsException`
  (new `DaoException`, `PermanentError`).
- `22008` / `23514` on the grad-date constraints → existing validation-failure
  pathway (treated as a permanent, caller-correctable error).
- `23503` (FK) → `NotFoundException` (owning user absent; not reachable through
  the authenticated API but mapped for completeness).
- OCC mismatch → existing `ConcurrentModificationException` (`40001`).

### Service

```kotlin
// service/.../student/StudentService.kt
class StudentService(private val database: Database) {
    suspend fun createStudent(userId: UserId, graduationDateIso: String): Result<CreateStudentOutcome>
    suspend fun getStudentForUser(userId: UserId): Result<Student?>
    suspend fun updateStudent(userId: UserId, expectedVersion: StudentVersionId, graduationDateIso: String): Result<UpdateStudentOutcome>
    suspend fun deleteStudentAndAccount(userId: UserId, currentTokenHash: TokenHash): Result<DeleteStudentOutcome>
}

sealed interface CreateStudentOutcome {
    data class Success(val student: Student) : CreateStudentOutcome
    data class ValidationFailure(val fieldErrors: List<FieldError>) : CreateStudentOutcome
    data object AlreadyExists : CreateStudentOutcome
}
sealed interface UpdateStudentOutcome {
    data class Success(val student: Student) : UpdateStudentOutcome
    data class ValidationFailure(val fieldErrors: List<FieldError>) : UpdateStudentOutcome
    data object NotFound : UpdateStudentOutcome
    data object VersionConflict : UpdateStudentOutcome
}
sealed interface DeleteStudentOutcome {
    data object Success : DeleteStudentOutcome
    data object NotFound : DeleteStudentOutcome
}
```

`deleteStudentAndAccount` runs inside a single `database.withConnection { }`
transaction (all-or-nothing): lock the student (`findByUserId` for update) and
the user (`findByIdForUpdate`), soft-delete the student row, soft-delete the
user row, then revoke the current session via the existing
`SessionsDao.revokeByTokenHash`. Both soft-deletes obtain an identical
`deleted_at` because Postgres `NOW()` is fixed at transaction start. All
sessions are auto-invalidated by the existing deleted-user filter in
`getCurrentUser`; no `revokeByUserId` is introduced.

### API Contracts

All endpoints require an authenticated session cookie; the current user is
resolved via `AuthService.getCurrentUser`. There is no path identifier — the
resource is owner-resolved.

| Method | Path                  | Success                       | Errors                                                                   |
| :----- | :-------------------- | :---------------------------- | :----------------------------------------------------------------------- |
| POST   | `/api/v1/students`    | `201` `StudentResponse`       | `400` validation, `409` already exists, `401` unauth                     |
| GET    | `/api/v1/students/me` | `200` `StudentResponse`       | `404` no profile, `401` unauth                                           |
| PATCH  | `/api/v1/students/me` | `200` `StudentResponse`       | `400` validation, `404` no profile, `409` version conflict, `401` unauth |
| DELETE | `/api/v1/students/me` | `204` (clears session cookie) | `404` no profile, `401` unauth                                           |

```kotlin
// rest-server/.../rest/models/
data class CreateStudentRequest(val expectedHighSchoolGraduationDate: String)      // ISO var precision
data class UpdateStudentRequest(val expectedHighSchoolGraduationDate: String, val version: Int)
data class StudentResponse(val student: PublicStudent)
data class PublicStudent(
    val id: UUID,
    val expectedHighSchoolGraduationDate: String,   // zero-padded ISO at the stored precision (YYYY | YYYY-MM | YYYY-MM-DD)
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)
```

Error bodies reuse the existing `ErrorResponse(code, message, fieldErrors)`.
Codes: `VALIDATION_ERROR`, `STUDENT_ALREADY_EXISTS`, `STUDENT_NOT_FOUND`,
`VERSION_CONFLICT`, `UNAUTHORIZED`. Validation field errors use
`field = "expectedHighSchoolGraduationDate"`.

### Error Handling / Edge Cases

- Unauthenticated request to any endpoint → `401 UNAUTHORIZED`.
- Malformed or impossible graduation date (bad format, month 13, Feb 31) →
  `400 VALIDATION_ERROR` (rejected by `PartialDate.parse`; DB constraints are
  the backstop).
- `POST` when the user already has an active student →
  `409 STUDENT_ALREADY_EXISTS`.
- `GET`/`PATCH`/`DELETE` `/me` when the authenticated user has no student → all
  three return `404 STUDENT_NOT_FOUND`. (`401` is returned by any of these
  endpoints only when the request is unauthenticated.)
- `PATCH` with a stale `version` → `409 VERSION_CONFLICT` (OCC).
- `DELETE` cascade is all-or-nothing; on any failure the transaction rolls back
  and neither row is modified.
- Concurrent `PATCH`/`DELETE` are serialized by `SELECT ... FOR UPDATE` row
  locks.

## Tests

### `db/src/test/kotlin/ed/unicoach/db/models/PartialDateTest.kt`

- `parse` accepts the three zero-padded canonical forms: `"2028"` → `YearOnly`;
  `"2028-06"` → `YearAndMonth`; `"2028-06-15"` → `FullDate`.
- `parse` rejects **unpadded** components — `"2028-6"` and `"2028-6-5"` each
  return `ValidationResult.Invalid` — confirming the zero-padded-only canonical
  policy (the accepted form `"2028-06"` parses; the rejected form `"2028-6"`
  does not).
- `parse` rejects empty string, non-numeric, `"2028-13"` (month 13),
  `"2028-02-31"` (impossible day), and `"2028-00"` (month 0).
- `toIso` round-trips each precision.
- `of(year, month, day)` reconstructs the correct variant and rejects impossible
  day/month combinations and an orphan day (`of(2028, null, 15)`).

### `db/src/test/kotlin/ed/unicoach/db/dao/StudentsDaoTest.kt`

- `create` persists with `version = 1` and round-trips all three precisions
  (year-only → month/day NULL; year+month → day NULL; full date).
- `create` rejects a second active student for the same user with
  `StudentAlreadyExistsException`, **and rejects it even after the first row is
  soft-deleted** (total unique index).
- `create` rejects a `user_id` not present in `users` (FK).
- Constraint backstops: month out of range, orphan day, and impossible calendar
  date (Feb 31) are all rejected at the DB layer.
- `findById` and `findByUserId` return the active row and exclude soft-deleted
  rows unless `includeDeleted = true`.
- `update` with the correct version succeeds and bumps `updated_at` and
  `row_updated_at`; `students_versions` gains a row.
- `update` with a stale version raises `ConcurrentModificationException`
  (`40001`).
- Immutable-field updates (`id`, `created_at`, `row_created_at`) are rejected.
- Physical `DELETE FROM students` is rejected by the trigger.
- `delete` soft-deletes: sets `deleted_at`, bumps `version`, and logs to
  `students_versions`.
- Timestamp bypass: with `unicoach.bypass_logical_timestamp = 'true'`, an update
  bumps `row_updated_at` but not `updated_at`.

### `service/src/test/kotlin/ed/unicoach/student/StudentServiceTest.kt`

- `createStudent` returns `Success` for each precision.
- `createStudent` returns `ValidationFailure` for a malformed/impossible ISO
  date.
- `createStudent` returns `AlreadyExists` when the user already has an active
  student.
- `getStudentForUser` returns the student, and `null` when none exists.
- `updateStudent` returns `Success`; returns `VersionConflict` on a stale
  version; returns `NotFound` when the user has no student; `ValidationFailure`
  on a bad date.
- `deleteStudentAndAccount` soft-deletes the student **and** the user
  atomically; asserts both rows carry a non-null, **identical** `deleted_at`;
  asserts `getCurrentUser` for the prior session token now returns `null`;
  asserts both `students_versions` and `users_versions` logged the deletion.
- `deleteStudentAndAccount` is all-or-nothing: when the user soft-delete is
  forced to fail (stale user version), the student row is **not** soft-deleted
  (rollback).
- `deleteStudentAndAccount` returns `NotFound` when the user has no student.

### `rest-server/src/test/kotlin/ed/unicoach/rest/StudentRoutingTest.kt`

- `POST /students`: `401` unauthenticated; `201` for each precision with the
  date echoed in `StudentResponse`; `400` on a malformed date with a field
  error; `409` when a student already exists.
- `GET /students/me`: `200` with profile; `404` without; `401` unauthenticated.
- `PATCH /students/me`: `200` with correct version; `409` on stale version;
  `400` on malformed date; `401` unauthenticated.
- `DELETE /students/me`: `204` with a cleared `Set-Cookie`; a subsequent
  `GET /api/v1/auth/me` returns `401` (account gone); `404` when the
  authenticated user has no student; `401` unauthenticated.

## Implementation Plan

1. **Generalize entity base interfaces.** Update `Entity.kt`
   (`BaseEntity<ID, V>`, `BaseVersionEntity<ID, V>`), `User.kt`
   (`BaseEntity<UserId, UserVersionId>`), `UserVersion.kt`
   (`BaseVersionEntity<UserId, UserVersionId>`). Verify:
   `nix develop -c ./gradlew :db:compileKotlin` and
   `nix develop -c bin/test :db:test`.
2. **Migration.** Create `db/schema/0005.create-students.sql` (`students`,
   `students_versions`, constraints, total unique index, FK,
   `log_student_version`, trigger attachments). Verify:
   `nix develop -c bin/db-migrate && nix develop -c bin/db-status`;
   `nix develop -c psql ... -c '\d students'`.
3. **Domain models.** Add `PartialDate.kt`, `StudentId.kt` (both value classes),
   `Student.kt`, `NewStudent.kt`, `StudentVersion.kt`. Verify:
   `nix develop -c ./gradlew :db:compileKotlin`.
4. **DAO.** Add `StudentsDao.kt` and `StudentAlreadyExistsException` to
   `DaoExceptions.kt`; implement SQLSTATE mapping (`23505`, `22008`/`23514`,
   `23503`, `40001`). Verify: `nix develop -c ./gradlew :db:compileKotlin`.
5. **DB tests.** Add `PartialDateTest.kt` and `StudentsDaoTest.kt`. Verify:
   `nix develop -c bin/test :db:test`.
6. **Service.** Add `StudentService.kt` and the three outcome files; implement
   the atomic delete-cascade with session revocation. Verify:
   `nix develop -c ./gradlew :service:compileKotlin`.
7. **Service tests.** Add `StudentServiceTest.kt`. Verify:
   `nix develop -c bin/test :service:test`.
8. **REST + wiring.** Add request/response DTOs and `StudentRoutes.kt` (route
   functions plus their handler logic live in this single file — no separate
   handler class). Instantiate `StudentService` in `Application.kt` and register
   the student routes in `Routing.kt`. Verify:
   `nix develop -c ./gradlew :rest-server:compileKotlin`.
9. **Route tests.** Add `StudentRoutingTest.kt`. Verify:
   `nix develop -c bin/test :rest-server:test`.
10. **OpenAPI.** Add the four endpoints and schemas to `api-specs/openapi.yaml`.
    The graduation-date string field carries
    `pattern: '^\d{4}(-\d{2}(-\d{2})?)?$'` (zero-padded canonical form). Verify:
    `nix develop -c bin/rest-server-up && nix develop -c bin/test-fuzz`.
11. **Full suite.** Verify: `nix develop -c bin/test`.

## Files Modified

### New

- `db/schema/0005.create-students.sql`
- `db/src/main/kotlin/ed/unicoach/db/models/PartialDate.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/StudentId.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/Student.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewStudent.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/StudentVersion.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/StudentsDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/models/PartialDateTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/StudentsDaoTest.kt`
- `service/src/main/kotlin/ed/unicoach/student/StudentService.kt`
- `service/src/main/kotlin/ed/unicoach/student/CreateStudentOutcome.kt`
- `service/src/main/kotlin/ed/unicoach/student/UpdateStudentOutcome.kt`
- `service/src/main/kotlin/ed/unicoach/student/DeleteStudentOutcome.kt`
- `service/src/test/kotlin/ed/unicoach/student/StudentServiceTest.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/CreateStudentRequest.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/UpdateStudentRequest.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/StudentResponse.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/StudentRoutes.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/StudentRoutingTest.kt`

### Modified

- `db/src/main/kotlin/ed/unicoach/db/models/Entity.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/User.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/UserVersion.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/DaoExceptions.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt`
- `api-specs/openapi.yaml`
