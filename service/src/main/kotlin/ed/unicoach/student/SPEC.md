# SPEC: `service/src/main/kotlin/ed/unicoach/student`

## I. Overview

This directory is the **student domain layer**. It owns the business logic for the
owner-scoped student profile: creating a student for a user, reading the user's
student, updating the expected high-school graduation date under optimistic
concurrency control, and atomically deleting both the student and its owning user
account with current-session revocation. It bridges the HTTP boundary (handled by
`rest-server`) and the data layer (handled by `db`), exposing pure domain results
via sealed interfaces that contain no HTTP types.

---

## II. Invariants

### General

- `StudentService` MUST NOT import or reference any Ktor, HTTP, or REST-layer types.
- Every `StudentService` method MUST surface any exception raised during DB
  access as `Result.failure(e)`; no exception may escape a method as a thrown
  exception.
- `StudentService` MUST NOT own or hold a raw `java.sql.Connection` — all DB access
  MUST go through `Database.withConnection`.
- Wire date strings MUST be validated through `PartialDate.parse` **before** any
  database call; a parse failure MUST short-circuit to the result's
  `ValidationFailure` variant without opening a DB connection.
- All validation field errors MUST use the field name
  `expectedHighSchoolGraduationDate`.

### Create

- `createStudent()` MUST reject a malformed graduation date with
  `CreateStudentResult.ValidationFailure` before touching the DB.
- A `StudentAlreadyExistsException` from the DAO MUST map to
  `CreateStudentResult.AlreadyExists`, never to a database failure.

### Update

- `updateStudent()` MUST enforce optimistic concurrency: it MUST compare the
  caller's `expectedVersion` against the persisted student's version and return
  `UpdateStudentResult.VersionConflict` on mismatch, **without** issuing an
  `UPDATE`.
- A `ConcurrentModificationException` raised by the DAO `UPDATE` (lost OCC race)
  MUST also map to `UpdateStudentResult.VersionConflict`.
- When the authenticated user owns no student, `updateStudent()` MUST return
  `UpdateStudentResult.NotFound`, never a database failure.
- The update MUST preserve all student fields except the graduation date — only
  `expectedHighSchoolGraduationDate` is replaced.

### Delete-Cascade

- `deleteStudentAndAccount()` MUST be **all-or-nothing**: the student soft-delete,
  the user soft-delete, and the current-session revocation MUST execute inside a
  single `Database.withConnection` transaction. Any failure MUST roll back the
  transaction so that neither row is modified.
- The student and the owning user MUST be row-locked (via the DAO's
  `*ForUpdate` lookups) before either is soft-deleted, serializing concurrent
  delete/update attempts on the same account.
- When the user owns no student, `deleteStudentAndAccount()` MUST return
  `DeleteStudentResult.NotFound` and MUST NOT modify the user row.
- The method MUST revoke the caller's current session by token hash; it MUST NOT
  introduce a bulk revoke-by-user operation. Invalidation of the user's other
  sessions is left to the data layer's deleted-user filter.

### Result Sealed Interfaces

- No `CreateStudentResult`, `UpdateStudentResult`, or `DeleteStudentResult`
  variant MUST contain HTTP status codes or Ktor types.
- Expected domain states (already-exists, not-found, version conflict, validation
  failure) MUST be modeled as result variants returned via `Result.success`, never
  as `Result.failure`.

---

## III. Behavioral Contracts

### `StudentService.createStudent(userId: UserId, graduationDateIso: String): Result<CreateStudentResult>` — See [StudentService.kt](./StudentService.kt)

- **Side Effects**: On valid input, writes one row to `students` via
  `StudentsDao.create`. No writes when validation fails.
- **Validation**: `PartialDate.parse` runs first; a non-`Valid` result returns
  `CreateStudentResult.ValidationFailure` with no DB access.
- **Idempotency**: Not idempotent. A second create for the same user returns
  `CreateStudentResult.AlreadyExists`.
- **Error mapping**:
  - DAO success → `Result.success(CreateStudentResult.Success(student))`
  - `StudentAlreadyExistsException` → `Result.success(CreateStudentResult.AlreadyExists)`
  - Other DAO failure / uncaught exception → `Result.failure(e)`

### `StudentService.getStudentForUser(userId: UserId): Result<Student?>` — See [StudentService.kt](./StudentService.kt)

- **Side Effects**: One read-only query via `StudentsDao.findByUserId` (excludes
  soft-deleted rows). No writes.
- **Absence**: A `NotFoundException` maps to `Result.success(null)` — absence is a
  successful, expected outcome, not an error.
- **Idempotency**: Fully idempotent (read-only).
- **Error mapping**: Any other DAO failure / uncaught exception → `Result.failure(e)`.

### `StudentService.updateStudent(userId: UserId, expectedVersion: StudentVersionId, graduationDateIso: String): Result<UpdateStudentResult>` — See [StudentService.kt](./StudentService.kt)

- **Side Effects**: On a version match, one `UPDATE` on `students` via
  `StudentsDao.update`. No write when validation fails, the student is absent, or
  the version is stale.
- **Validation**: `PartialDate.parse` runs first; failure →
  `UpdateStudentResult.ValidationFailure` with no DB access.
- **Concurrency**: Reads the current student, compares `versionId` to
  `expectedVersion`; mismatch → `VersionConflict` before any `UPDATE`.
- **Idempotency**: Not idempotent — a successful update bumps the persisted version.
- **Error mapping**:
  - DAO success → `Result.success(UpdateStudentResult.Success(student))`
  - Absent student (`NotFoundException`) → `Result.success(UpdateStudentResult.NotFound)`
  - Stale version (pre-check or `ConcurrentModificationException`) →
    `Result.success(UpdateStudentResult.VersionConflict)`
  - Other DAO failure / uncaught exception → `Result.failure(e)`

### `StudentService.deleteStudentAndAccount(userId: UserId, currentTokenHash: TokenHash): Result<DeleteStudentResult>` — See [StudentService.kt](./StudentService.kt)

- **Side Effects**: Within one transaction — locks the student and the user
  (`findByUserIdForUpdate`, `findByIdForUpdate`), soft-deletes the student
  (`StudentsDao.delete`), soft-deletes the user (`UsersDao.delete`), and revokes
  the current session (`SessionsDao.revokeByTokenHash`).
- **Atomicity**: All-or-nothing. Any step failing rolls back the entire
  transaction; partial deletion is impossible.
- **Absence**: When the user owns no student, returns
  `Result.success(DeleteStudentResult.NotFound)` with no mutation.
- **Idempotency**: Not idempotent. A successful call soft-deletes the only
  student the user can own and cascades to the account, so a second call finds no
  student and returns `DeleteStudentResult.NotFound`.
- **Error mapping**:
  - All steps succeed → `Result.success(DeleteStudentResult.Success)`
  - Absent student (`NotFoundException`) → `Result.success(DeleteStudentResult.NotFound)`
  - Any other failure / uncaught exception → `Result.failure(e)` (transaction
    rolled back)

### `CreateStudentResult` (sealed interface) — See [CreateStudentResult.kt](./CreateStudentResult.kt)

| Variant | Carries | When returned |
|---|---|---|
| `Success` | `student: Student` | Student persisted |
| `ValidationFailure` | `fieldErrors: List<FieldError>` | Graduation date rejected by `PartialDate.parse` |
| `AlreadyExists` | None | User already owns a student (total unique constraint) |

### `UpdateStudentResult` (sealed interface) — See [UpdateStudentResult.kt](./UpdateStudentResult.kt)

| Variant | Carries | When returned |
|---|---|---|
| `Success` | `student: Student` | Update persisted |
| `ValidationFailure` | `fieldErrors: List<FieldError>` | Graduation date rejected by `PartialDate.parse` |
| `NotFound` | None | Authenticated user owns no student |
| `VersionConflict` | None | `expectedVersion` is stale (OCC) |

### `DeleteStudentResult` (sealed interface) — See [DeleteStudentResult.kt](./DeleteStudentResult.kt)

| Variant | Carries | When returned |
|---|---|---|
| `Success` | None | Student and owning user soft-deleted, session revoked |
| `NotFound` | None | Authenticated user owns no student |

---

## IV. Infrastructure & Environment

- **Module**: `service` (Gradle). `StudentService` depends only on `Database` and
  the `db` data-access layer (`StudentsDao`, `UsersDao`, `SessionsDao`, domain
  models) plus `FieldError` from `common`. It is constructed with an injected
  `Database` instance.
- **Database**: Requires a live PostgreSQL connection pool via `Database`
  (HikariCP). The cascade relies on `Database.withConnection` providing a single
  transaction so that the student soft-delete, user soft-delete, and session
  revocation commit or roll back together.
- **Coroutine context**: The `suspend` methods must be called from a coroutine
  scope. The module performs no dispatcher switching and preserves the caller's
  context.
- **No HOCON keys** are read directly by this package; configuration is injected
  via the `database` constructor parameter.

---

## V. History

- [x] [RFC-31: Student Profile](../../../../../../../rfc/31-student-profile.md)
