# SPEC: `db/src/main/kotlin/ed/unicoach/db/dao`

## I. Overview

`db/dao/` is the sole interface between the Kotlin application and the
PostgreSQL database. Stateless DAO singletons provide every read and write
operation, composing à-la-carte **capability interfaces** ([Dao.kt](./Dao.kt))
and delegating all query execution to shared `SqlSession` **query scaffolding**
([SqlSessionQueries.kt](./SqlSessionQueries.kt)).

| DAO                     | Table(s)                                                              |
| ----------------------- | --------------------------------------------------------------------- |
| `UsersDao`              | `users`, `users_versions`                                             |
| `UserAuthIdentitiesDao` | `user_auth_identities` (append-only)                                  |
| `SessionsDao`           | `sessions`                                                            |
| `StudentsDao`           | `students`, `students_versions`                                       |
| `ConvosDao`             | `convos`, `convo_requests`, `convo_responses`, `convo_responses_raw`  |
| `SystemPromptsDao`      | `system_prompts` (immutable catalog: read + insert, no update/delete) |
| `VerificationTokensDao` | `verification_tokens`                                                 |
| `CollegesDao`           | `colleges`, `college_programs` (reference data: upsert + search)      |
| `ObservationsDao`       | `observations` (append-only log)                                      |
| `ClaimsDao`             | `claims` (mutable entity, no `version`/OCC)                           |
| `ClaimSupportDao`       | `claim_support` (append-only link log; reads `observations`)          |
| `ExtractionRunsDao`     | `extraction_runs` (append-only log)                                   |
| `AdvisoryLockDao`       | none — issues `pg_advisory_xact_lock` only (no table)                 |

The last five DAOs are the RFC 66 coaching-memory extraction surface: a model
silently mines each conversation turn into durable `observations` (verbatim
quotes), distills them into `claims` (deduplicated statements about the student)
backed by `claim_support` citations, records each pass in `extraction_runs`, and
serializes concurrent same-student passes through a per-student transaction
advisory lock (`AdvisoryLockDao`).

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
`updatePhysicalRecord`, `markEmailVerified`, `changeEmail`, the `*ForUpdate`
lock-reads, `expireZombieSessions`, `*WithActivity`, `listTurns`,
`findRawByResponseId`, `findByNameAndVersion`,
`consume`/`findByTokenHash`/`consumeAllForUser`, the RFC-66 reads
`listByConvoRange`/`listByStudent`/`listActiveByStudent`/
`listObservationsForClaim`/`watermark`, the `claims` lifecycle write `revise`,
the idempotent `link`, the `append` aliases, and `AdvisoryLockDao.lockStudent` —
remain concrete methods on the DAO; `SystemPromptsDao.findById`/`list`/`create`
are interface-backed via `Findable`/`Listable`/`Creatable`):

| DAO                     | Capability interfaces                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `UsersDao`              | `SoftDeleteFindable<User, UserId>`, `SoftDeleteListable<User>`, `Creatable<NewUser, User>`, `Updatable<UserEdit, User>`, `OccDeletable<User, UserId>`, `VersionHistory<UserId, UserVersion>`                                                                                                                                                                                                                                                                        |
| `UserAuthIdentitiesDao` | `Creatable<NewAuthIdentity, AuthIdentity>` (append-only; no update/delete surface; reads `findByProviderAndSubject`/`listByUser` stay concrete)                                                                                                                                                                                                                                                                                                                     |
| `StudentsDao`           | `SoftDeleteFindable<Student, StudentId>`, `Creatable<NewStudent, Student>`, `Updatable<StudentEdit, Student>`, `OccDeletable<Student, StudentId>`, `VersionHistory<StudentId, StudentVersion>`                                                                                                                                                                                                                                                                      |
| `ConvosDao`             | `SoftDeleteFindable<Convo, ConvoId>`, `Creatable<NewConvo, Convo>`, `Deletable<Convo, ConvoId>`                                                                                                                                                                                                                                                                                                                                                                     |
| `SessionsDao`           | `Findable<Session, SessionId>`, `Listable<Session>`, `Creatable<NewSession, Session>`, `Destroyable<SessionId>`                                                                                                                                                                                                                                                                                                                                                     |
| `SystemPromptsDao`      | `Findable<SystemPrompt, SystemPromptId>`, `Listable<SystemPrompt>`, `Creatable<NewSystemPrompt, SystemPrompt>` (plain, not the soft-delete variants — `system_prompts` has no `deleted_at`; `findByNameAndVersion` remains concrete)                                                                                                                                                                                                                                |
| `VerificationTokensDao` | `Creatable<NewVerificationToken, VerificationToken>` (remaining methods — `consume`, `findByTokenHash`, `consumeAllForUser` — are concrete)                                                                                                                                                                                                                                                                                                                         |
| `CollegesDao`           | NONE — declares no capability interface. Its operations (`upsert`/`upsertProgram` hand-rolled `ON CONFLICT`, the natural-key `findByUnitId`, the dynamic `search`) match no à-la-carte interface, so every method is concrete. The interfaces model student-entity CRUD over a surface id; the upsert key is the federal natural key (`unit_id`; `(college_id, cip_code, credential_level)`), not the surface UUID, and `Creatable.create` has no upsert semantics. |
| `ObservationsDao`       | `Creatable<NewObservation, Observation>` (append-only log; `append` is an alias of `create`; the range/student reads `listByConvoRange`/`listByStudent` stay concrete)                                                                                                                                                                                                                                                                                              |
| `ClaimsDao`             | `Findable<Claim, ClaimId>`, `Creatable<NewClaim, Claim>` (the lifecycle write `revise` and the hot read `listActiveByStudent` stay concrete; `claims` has no `version`, so no `Updatable`/`OccDeletable`)                                                                                                                                                                                                                                                           |
| `ClaimSupportDao`       | `Creatable<NewClaimSupport, ClaimSupport>` (append-only link log; `create` delegates to the idempotent `link`; the read `listObservationsForClaim` stays concrete)                                                                                                                                                                                                                                                                                                  |
| `ExtractionRunsDao`     | `Creatable<NewExtractionRun, ExtractionRun>` (append-only log; `append` is an alias of `create`; the read `watermark` stays concrete)                                                                                                                                                                                                                                                                                                                               |
| `AdvisoryLockDao`       | NONE — its sole method `lockStudent` is neither a row read nor a write, so no à-la-carte interface fits.                                                                                                                                                                                                                                                                                                                                                            |

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
  factories (unwrapping `ValidationResult.Valid`), and read `is_admin` as a
  plain `Boolean`. The credential is the single nullable `password_hash` column,
  read by `readPasswordHash`
  (`rs.getString("password_hash")?.let { PasswordHash.create(it) }`) into a
  nullable `PasswordHash` — a SQL NULL maps to a `null` credential (a
  federated-only user). There is no `AuthMethod` sealed type, no
  `sso_provider_id` column, and no corrupt-auth-method path on this mapper. Both
  mappers also project the nullable `email_verified_at` timestamp (NULL =
  unverified).
- **`user_auth_identities`**: `mapIdentity` reconstructs an `AuthIdentity` from
  `id`/`user_id` (UUIDs), `provider` (via `AuthProvider.fromWire`), `subject`
  (via `ProviderSubject.create`), `email` (via `EmailAddress.create`), and the
  plain `email_verified` Boolean and `created_at` timestamp. Any failed
  reconstruction (an unknown provider wire value, or an `Invalid` subject/email)
  throws `CorruptPersistedValueException` (a `PermanentError` carrying the
  offending raw value and the structured `ValidationError`) — never a
  `ConstraintViolationException`, so a corrupt row is not mistaken for the
  in-transaction `(provider, subject)` retry signal.
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
  `initial_ip`, and `expires_at`. It reconstructs a nullable `login_method` via
  `LoginMethod.fromWire`: a SQL NULL maps to a `null` login method (an anonymous
  session), while a non-null value the enum cannot resolve throws
  `CorruptPersistedValueException` (so a corrupt row is never silently collapsed
  into an anonymous one).
- **`system_prompts`**: `mapPrompt` maps every column verbatim (`name`,
  `version`, `body` as plain `String`); no soft-delete, version, or validated
  columns, so no corruption path.
- **`verification_tokens`**: `mapToken` maps `id`/`user_id` as typed
  `VerificationTokenId`/`UserId`, reads `expires_at`/`created_at` as `Instant`
  and the nullable `consumed_at` (NULL = unconsumed). No validated value classes
  and no corruption path; the `token_hash` BYTEA column is never projected into
  the domain row.
- **`colleges`/`college_programs`**: `mapCollege`/`mapProgram` map every column
  verbatim into the plain reference models — no validated value classes and no
  corruption path. They use scoped `ResultSet.intOrNull`/`doubleOrNull`
  (`getInt`/`getDouble` + `wasNull()`, per `ConvosDao`) for the nullable numeric
  columns rather than the shared scaffolding. `mapMatch` (the `search`
  projection) additionally reads the SQL `text[]` `program_titles` column via
  JDBC `getArray` (a `text[]` cannot be read as a typed scalar), freeing the
  `java.sql.Array` handle afterward; a NULL array collapses to an empty list.
- **`observations`**: `mapObservation` reads `id` as a `Long` `ObservationId`
  (the log uses a `BIGINT` surrogate, not a UUID), `student_id`/`convo_id` as
  typed UUIDs, `source_request_id` as a `Long` `ConvoRequestId`, `uttered_at`/
  `created_at` as `Instant`, and `quote` as a plain `String`. No validated value
  classes and no corruption path.
- **`claims`**: `mapClaim` reads `id` as a UUID `ClaimId`, the six enum columns
  (`origin`, `status`, `kind`, `subject`, `topic`, `visibility`) via the private
  `parseEnum` (each model's `fromValue`), `statement` as a plain `String`,
  `confidence` as a plain `Int`, the nullable `superseded_by_id` as a
  `ClaimId?`, and the nullable `superseded_at`/`retracted_at` timestamps.
  `parseEnum` throws a `SQLException` (→ `DatabaseException`, a
  `PermanentError`) when a persisted enum string resolves to null — the DB CHECK
  already guarantees a valid member, so a null signals row corruption, not a
  user-facing failure. Unlike the validated-value mappers it does not raise
  `CorruptPersistedValueException`.
- **`claim_support`**: `mapSupport` reads the `(claim_id, observation_id)`
  composite key (UUID + `Long`) and `created_at`. The same file also carries a
  local `mapObservation` (identical shape to `ObservationsDao`'s) for the
  joined-observation read. No corruption path.
- **`extraction_runs`**: `mapRun` reads `id` as a `Long` `ExtractionRunId`,
  `convo_id`/`student_id`/`system_prompt_id` as typed UUIDs,
  `through_request_id` as a `Long` `ConvoRequestId`, `outcome` via the private
  `parseOutcome` (`ExtractionOutcome.fromValue`, throwing a `SQLException` on an
  unrecognized value, same corruption convention as `claims`), `provider` as a
  plain `String`, the nullable `model_resolved`, the three non-null count
  columns (`observations_written`/`claims_written`/`claims_superseded`) as
  `Int`, and the four nullable token-usage columns
  (`input_tokens`/`output_tokens`/ `cache_read_tokens`/`cache_write_tokens`)
  read via `getInt` + `wasNull()`.

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

`UserAuthIdentitiesDao.mapCreateError` (the `create` write path only; reads
route through `mapDatabaseError`):

- `23505` (the `(provider, subject)` unique index) / `23514` (a CHECK) →
  `ConstraintViolationException` — the in-transaction signal a Google-login flow
  retries on when a concurrent insert wins the race to link the same federated
  identity.
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

`SystemPromptsDao.mapPromptError` (the `create` insert path only; reads route
through `mapDatabaseError`):

- `23505` (duplicate `(name, version)`) / `23514` (CHECK violation) →
  `ConstraintViolationException`.
- everything else → `mapDatabaseError`. The table's immutability triggers raise
  `P0001` only on `UPDATE`/`DELETE`, unreachable from the insert-only path, so
  they would fall through here.

`CollegesDao.mapCollegeError` (the upsert write paths only; `findByUnitId`/
`search` route through `mapDatabaseError`):

- `23503` (FK — a program referencing an absent college) → `NotFoundException`
  ("Referenced college not found").
- `23505` (the natural-key UNIQUE) / `23514` (any CHECK — dirty source data such
  as `admission_rate > 1`, `control` outside {1,2,3}, malformed `cip_code`) →
  `ConstraintViolationException`. NOTE: `23514` here maps to the PERMANENT
  `ConstraintViolationException`, NOT the generic `DatabaseException` that the
  shared `mapDatabaseError` would assign — so a college loader skips a
  CHECK-violating row as a `PermanentError`.
- everything else → `mapDatabaseError`. Mirrors `ConvosDao.mapConvoError` /
  `SystemPromptsDao.mapPromptError`.

`ObservationsDao.mapObservationError` (the `create`/`append` write path;
`listByConvoRange`/`listByStudent` route through `mapDatabaseError`):

- `23503` (FK) → `NotFoundException` with a message resolved from the violated
  constraint name (`observations_student_id_fkey` → "Owning student not found";
  `observations_convo_id_fkey` → "Convo not found";
  `observations_source_request_id_fkey` → "Source request not found"; unmatched
  → bare `NotFoundException()`).
- `23505` / `23514` → `ConstraintViolationException`.
- everything else → `mapDatabaseError`.

`ClaimsDao.mapClaimError` (the `create` and `revise` write paths; `findById`/
`listActiveByStudent` route through `mapDatabaseError`):

- `23503` (FK) → `NotFoundException` (`claims_student_id_fkey` → "Owning student
  not found"; `claims_superseded_by_id_fkey` → "Superseding claim not found";
  unmatched → bare `NotFoundException()`).
- `23505` / `23514` → `ConstraintViolationException` (the latter covers the
  lifecycle-consistency CHECKs `claims_superseded_consistency_check`/
  `claims_retracted_consistency_check`).
- everything else → `mapDatabaseError`.

`ClaimSupportDao.mapSupportError` (the `link`/`create` write path;
`listObservationsForClaim`/`readExisting` route through `mapDatabaseError`):

- `23503` (FK) → `NotFoundException` (`claim_support_claim_id_fkey` → "Claim not
  found"; `claim_support_observation_id_fkey` → "Observation not found";
  unmatched → bare `NotFoundException()`). No `23505` branch — the composite PK
  collision is absorbed by `ON CONFLICT DO NOTHING`, never surfacing as an
  error.
- everything else → `mapDatabaseError`.

`ExtractionRunsDao.mapRunError` (the `create`/`append` write path; `watermark`
routes through `mapDatabaseError`):

- `23503` (FK) → `NotFoundException` (`extraction_runs_convo_id_fkey` → "Convo
  not found"; `extraction_runs_student_id_fkey` → "Owning student not found";
  `extraction_runs_through_request_id_fkey` → "Through request not found";
  `extraction_runs_system_prompt_id_fkey` → "System prompt not found"; unmatched
  → bare `NotFoundException()`).
- `23505` / `23514` → `ConstraintViolationException`.
- everything else → `mapDatabaseError`.

`AdvisoryLockDao.lockStudent` declares no SQLSTATE map — it wraps its own
try/catch and routes any failure straight through `mapDatabaseError`.

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

- **Side Effects**: Write — `insertReturning` over five columns (`email`,
  `name`, `display_name`, `password_hash`, `is_admin`). The DB generates `id`
  (`uuidv7()` default) and timestamps. `password_hash` is bound from the
  nullable `NewUser.passwordHash` via `setStringOrNull` (a NULL credential is a
  federated-only user).
- **Error Handling**: `DuplicateEmailException` on the active-email index;
  `ConstraintViolationException` otherwise (via `mapCreateUpdateError`).
- **Idempotency**: No.

#### `update(session, edit: UserEdit): Result<User>`

- **Side Effects**: Read/write — `updateColumnsReturning` with OCC over four
  columns only: `email`, `name`, `display_name`, `is_admin`. It does not write
  `password_hash` (the credential mutates only through dedicated auth flows) nor
  any immutable column. The OCC path prepends `version = edit.version + 1` and
  matches `WHERE id = ? AND version = ?`.
- **Error Handling**: `NotFoundException`/`ConcurrentModificationException` (via
  the `occUpdate` probe on 0 rows); `DuplicateEmailException`/
  `ConstraintViolationException` via `mapCreateUpdateError`.
- **Idempotency**: No (version bumps).

#### `markEmailVerified(session, id): Result<User>`

- **Side Effects**: Read/write — a versioned conditional `mutateReturning`
  (`SET version = version + 1, email_verified_at = NOW() WHERE id = ? AND email_verified_at IS NULL AND deleted_at IS NULL RETURNING *`),
  stamping the verification timestamp and bumping `version` only while the
  column is still NULL on an active row. On 0 rows it falls back to
  `findById(session, id, ACTIVE)`, returning the existing row unchanged. Written
  only by the dedicated verification path, never the `UserEdit`/`update` surface
  (the same isolation the auth columns have).
- **Error Handling**: `mapCreateUpdateError` on the conditional update
  (`ConstraintViolationException`, etc.); a non-`NotFoundException` failure
  propagates. When the conditional update matches no row, the fallback
  `findById` returns `NotFoundException()` only when the user is truly absent
  (or soft-deleted); an already-verified, active user yields `success(User)`.
- **Idempotency**: Yes — the first call stamps `email_verified_at` and bumps the
  version; a second call matches no row and returns the existing user unchanged,
  with no further version bump (the first-verification timestamp is preserved).

#### `changeEmail(session, id, newEmail): Result<User>`

- **Side Effects**: Read/write — a versioned conditional `mutateReturning`
  (`SET version = version + 1, email = ?, email_verified_at = NULL WHERE id = ? AND deleted_at IS NULL RETURNING *`),
  rewriting the email, resetting verification back to NULL, and bumping
  `version` on an active row. Written only by the dedicated change-email path,
  never the `UserEdit`/`update` surface (the same isolation `markEmailVerified`
  has). Uses `version = version + 1` rather than an OCC `WHERE version = ?`
  guard: the caller holds a freshly-read session user and concurrent
  double-submits are self-correcting, so an OCC lost-update rejection would add
  a failure mode with no correctness benefit.
- **Error Handling**: `mapCreateUpdateError` — a `23505` on
  `users_email_unique_active_idx` surfaces `DuplicateEmailException`; `onNoRow`
  yields `NotFoundException()` when the user is absent or soft-deleted.
- **Idempotency**: No (version bumps; re-arms verification on every call).

#### `updatePhysicalRecord(session, user): Result<User>`

- **Side Effects**: Read/write — two statements in the caller transaction: a
  `SET LOCAL unicoach.bypass_logical_timestamp = 'true'` bypass (suppressing the
  `update_timestamp` trigger so `updated_at` does not advance), then a full-row
  OCC update via the private `updateFullRow` (`occUpdate`) restoring every
  mutable column **including the `password_hash` credential and
  `email_verified_at`**. Not routed through the `UserEdit` path.
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
  OCC write via `updateFullRow` (the `password_hash` credential and
  `email_verified_at` restored to that version's value, since the marker is
  ordinary versioned state).
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

### `UserAuthIdentitiesDao` — [`UserAuthIdentitiesDao.kt`](./UserAuthIdentitiesDao.kt)

`object` implementing `Creatable<NewAuthIdentity, AuthIdentity>` over the
append-only `user_auth_identities` table — the link between a `users` row and a
federated provider identity. There is no update or delete surface; rows are only
inserted and read. Both reads stay concrete methods (their shapes are not
`findById`/`list`). Reads route through `mapDatabaseError`; the write path
discriminates SQLSTATE via `mapCreateError`.

#### `create(session, input: NewAuthIdentity): Result<AuthIdentity>`

- **Side Effects**: Write — `insertReturning` over `user_id`, `provider`,
  `subject`, `email`, `email_verified`. The DB generates `id` and `created_at`.
- **Error Handling**: `ConstraintViolationException` on the
  `(provider, subject)` unique index (`23505`) or a CHECK (`23514`) via
  `mapCreateError`; `CorruptPersistedValueException` if the RETURNING row fails
  reconstruction; otherwise `mapDatabaseError`.
- **Idempotency**: No — a second insert of the same `(provider, subject)` is
  rejected by the unique index.

#### `findByProviderAndSubject(session, provider, subject): Result<AuthIdentity>`

- **Side Effects**: Read only — resolves the stable `(provider, subject)` key.
  Unaffected by `users` soft-delete; the identity row persists regardless of the
  owning user's state (user-state checks are the caller's responsibility).
- **Error Handling**: `NotFoundException()` when no identity matches.
- **Idempotency**: Yes.

#### `listByUser(session, userId): Result<List<AuthIdentity>>`

- **Side Effects**: Read only — all identities linked to a user, oldest-first
  (`ORDER BY created_at, id`).
- **Error Handling**: `success(emptyList())` for an unknown user.
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
  bound as `Types.OTHER`; the nullable `login_method` is bound from
  `input.loginMethod?.wire` via `setStringOrNull` (NULL for an anonymous
  session).
- **Error Handling**: No contractual errors; `NotFoundException` (the default
  `onNoRow`) only if RETURNING yields no row.
- **Idempotency**: No.

#### `remintToken(session, id, currentVersion, newUserId, newTokenHash, newExpirationSeconds, newLoginMethod): Result<Session>`

- **Side Effects**: Read/write — `mutateReturning` rotating `token_hash`,
  rebinding `user_id` and `login_method`, and resetting `expires_at` (session
  fixation defense), guarded by
  `WHERE id = ? AND version = ? AND is_revoked = false`.
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

`object` implementing `Findable<SystemPrompt, SystemPromptId>`/
`Listable<SystemPrompt>`/`Creatable<NewSystemPrompt, SystemPrompt>` over the
immutable `system_prompts` catalog. The table's triggers forbid `UPDATE`/
`DELETE`, so a "new version" is a new immutable row; the DAO exposes read and
insert only (no update, no soft-delete — `system_prompts` has no `deleted_at`,
hence the plain capability variants). Rows are authored by migration or the
admin tool. Stateless `object`; the insert path discriminates SQLSTATE via
`mapPromptError`, reads route through `mapDatabaseError`.

#### `findById(session, id): Result<SystemPrompt>`

- **Side Effects**: Read only — `queryOne` by primary key.
- **Error Handling**: `NotFoundException` when no row matches.
- **Idempotency**: Yes.

#### `list(session, limit, offset): Result<List<SystemPrompt>>`

- **Side Effects**: Read only — `queryList` ordered
  `name ASC, version ASC, created_at DESC` (the `created_at` tie-breaker is
  redundant under the `(name, version)` UNIQUE key but keeps the page order
  total), paged via `LIMIT ?`/`OFFSET ?`.
- **Error Handling**: `success(emptyList())` when no rows match.
- **Idempotency**: Yes.

#### `create(session, input: NewSystemPrompt): Result<SystemPrompt>`

- **Side Effects**: Write — `insertReturning` over `name`, `version`, `body`. DB
  generates `id` (`uuidv7()` default) and `created_at`. A new immutable row; the
  catalog is append-only.
- **Error Handling**: `ConstraintViolationException` on a duplicate
  `(name, version)` (`23505`) or a CHECK violation (`23514`), via
  `mapPromptError`; `mapDatabaseError` otherwise.
- **Idempotency**: No — a second insert of the same `(name, version)` fails the
  UNIQUE constraint.

#### `findByNameAndVersion(session, name, version): Result<SystemPrompt>`

- **Side Effects**: Read only — `queryOne` resolving the `(name, version)`
  UNIQUE key. Not part of a capability interface.
- **Error Handling**: `NotFoundException` when no row matches.
- **Idempotency**: Yes.

---

### `VerificationTokensDao` — [`VerificationTokensDao.kt`](./VerificationTokensDao.kt)

`object` implementing `Creatable<NewVerificationToken, VerificationToken>`; the
remaining methods are concrete. Single-use email-verification credential store
over `verification_tokens`, which has no `version`/`deleted_at`/`_versions`
table. Modeled on `SessionsDao` (a hashed credential): only the SHA-256
`token_hash` is persisted; the raw token rides only in the email link. Failures
route through `mapDatabaseError` (no specialized SQLSTATE mapper).

#### `create(session, input: NewVerificationToken): Result<VerificationToken>`

- **Side Effects**: Write — hand-written `mutateReturning` inserting one row
  (`user_id`, `token_hash` as BYTEA, `expires_at`). DB generates `id`
  (`uuidv7()`) and `created_at`; `consumed_at` defaults NULL.
- **Error Handling**: No contractual errors; `NotFoundException` (the default
  `onNoRow`) only if RETURNING yields no row. `mapDatabaseError` otherwise.
- **Idempotency**: No.

#### `consume(session, tokenHash): Result<VerificationToken>`

- **Side Effects**: Read/write — a compare-and-swap `mutateReturning`
  (`SET consumed_at = NOW() WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > NOW() RETURNING *`)
  that atomically claims the single unconsumed, unexpired token by hash and
  returns the claimed row. Under concurrency exactly one caller claims the row;
  the rest match 0 rows.
- **Error Handling**: `NotFoundException()` on 0 rows — unknown hash, already
  consumed, or expired all collapse to this (the `SessionsDao` no-row
  convention). `mapDatabaseError` otherwise.
- **Idempotency**: Effectively yes — a second `consume` of the same hash matches
  no row and returns `NotFound`; the original `consumed_at` instant is
  unchanged.

#### `findByTokenHash(session, tokenHash): Result<VerificationToken>`

- **Side Effects**: Read only — `queryOne` reading a token in any state
  (consumed and expired included), used to classify a failed `consume`.
- **Error Handling**: `NotFoundException()` when no row has the hash.
- **Idempotency**: Yes.

#### `consumeAllForUser(session, userId): Result<Int>`

- **Side Effects**: Write — `execute`
  (`UPDATE … SET consumed_at = NOW() WHERE user_id = ? AND consumed_at IS NULL`),
  stamping every still-unconsumed token for the user; returns the affected
  count. Already-consumed rows are untouched.
- **Error Handling**: No contractual errors; `mapDatabaseError` on failure.
- **Idempotency**: Yes — a re-run on a user with no unconsumed tokens affects 0
  rows and returns `success(0)`.

---

### `CollegesDao` — [`CollegesDao.kt`](./CollegesDao.kt)

Stateless `object` over the two College Scorecard reference tables (RFC 67),
same object/`SqlSession`/caller-transaction shape as `ConvosDao`. Declares no
capability interface (§II): the upsert key is a federal natural key, not the
surface UUID, so no `Creatable`/`Findable` interface fits. The upserts are
hand-rolled `INSERT … ON CONFLICT … DO UPDATE` — the only `ON CONFLICT` upsert
in the codebase, written fresh because the scaffolding's typed
`Creatable`/`insertReturning` helpers have no upsert mode. The tables have no
`version`, so no upsert carries an OCC guard (last-write-wins). Write paths
discriminate SQLSTATE via `mapCollegeError`; reads route through
`mapDatabaseError`. NOTE: the loader's per-row `SAVEPOINT` discipline lives in
the `college` module's `CollegeScorecardLoader`, NOT here — the DAO issues plain
upserts and returns a `Result`; it does not bracket itself in a savepoint.

#### `upsert(session, input: NewCollege): Result<College>`

- **Side Effects**: Write — `mutateReturning` over a hand-rolled
  `INSERT … (21 curated columns) … ON CONFLICT (unit_id) DO UPDATE SET …
  RETURNING *`.
  On conflict every curated column is overwritten from `input`; `id` and
  `created_at` are preserved and the `_03` trigger advances `updated_at`. The DB
  generates `id` (`uuidv7()`) on a fresh insert.
- **Error Handling**: `ConstraintViolationException` on the `unit_id` UNIQUE or
  any CHECK (`23505`/`23514`); other SQLSTATEs via `mapDatabaseError` (transient
  classification).
- **Idempotency**: Yes at the row level — re-upserting the same `unit_id`
  overwrites in place with no duplicate row (`id` stable).

#### `upsertProgram(session, input: NewCollegeProgram): Result<CollegeProgram>`

- **Side Effects**: Write — hand-rolled
  `INSERT … ON CONFLICT (college_id, cip_code, credential_level) DO UPDATE SET
  cip_title = EXCLUDED.cip_title RETURNING *`.
  On conflict only `cip_title` is refreshed; `id`/`created_at` preserved,
  `updated_at` advanced by the trigger.
- **Error Handling**: `NotFoundException` ("Referenced college not found") on
  the `college_id` FK (`23503`); `ConstraintViolationException` on the
  natural-key UNIQUE or a CHECK (`23505`/`23514`); else `mapDatabaseError`.
- **Idempotency**: Yes at the row level.

#### `findByUnitId(session, unitId: Int): Result<College?>`

- **Side Effects**: Read only — `queryOne` on the `unit_id` natural key, folding
  the `queryOne` `NotFoundException` into `Result.success(null)` so an absent
  college is a domain `null`, not a failure (the loader uses this to decide
  whether a program's owning college exists). Other failures propagate.
- **Idempotency**: Yes.

#### `search(session, query: CollegeQuery): Result<List<CollegeMatch>>`

- **Side Effects**: Read only — builds a parameterized `SELECT` appending one
  `AND` clause per non-null filter; JOINs `college_programs` ONLY when
  `cipPrefix` is set (matching `cip_code LIKE ? || '%'` so a 2/4/6-digit prefix
  all resolve, and aggregating the matched titles into `program_titles` via
  `array_agg(DISTINCT …)`; without a program filter `program_titles` is a
  literal empty `text[]`). Always `GROUP BY c.id`, then the deterministic
  `ORDER BY c.undergrad_enrollment DESC NULLS LAST, c.unit_id ASC` and the
  caller-supplied `LIMIT ?`. The list-membership filters (`states`, `locales`,
  `control`) expand to `IN (?, …)` with one bound parameter per element.
- **SQL-injection safety**: Only fixed column identifiers and `?` placeholders
  are concatenated into the SQL text — every filter VALUE (including each list
  element and the `LIMIT`) is bound as a positional parameter, never
  interpolated. The `limit` value is trusted: the `college` module's service
  clamps it to `1..25` before the DAO is called.
- **Error Handling**: `success(emptyList())` when nothing matches;
  `mapDatabaseError` on failure.
- **Idempotency**: Yes.

---

### `ObservationsDao` — [`ObservationsDao.kt`](./ObservationsDao.kt)

`object` implementing `Creatable<NewObservation, Observation>` over the
append-only `observations` log (RFC 66) — verbatim student quotes mined from
conversation turns. The log is insert-only; DB immutability triggers reject any
`UPDATE`/`DELETE`, so there is no update/delete/soft-delete surface. The write
path discriminates SQLSTATE via `mapObservationError`; reads route through
`mapDatabaseError`.

#### `create(session, input: NewObservation): Result<Observation>` / `append(session, input): Result<Observation>`

- **Side Effects**: Write — `insertReturning` over `student_id`, `convo_id`,
  `source_request_id`, `uttered_at`, `quote`. The DB generates the `BIGINT` `id`
  and `created_at`. `append` is a log-flavoured alias that delegates verbatim to
  `create`.
- **Error Handling**: `NotFoundException` (student/convo/source-request FK) via
  `mapObservationError`; `ConstraintViolationException` on `23505`/`23514`.
- **Idempotency**: No — every append is a distinct row.

#### `listByConvoRange(session, convoId, afterRequestId, throughRequestId): Result<List<Observation>>`

- **Side Effects**: Read only — the window of one convo's observations whose
  `source_request_id` lies in `(afterRequestId, throughRequestId]`, ordered
  `created_at, id`. This is the incremental-extraction read: the half-open lower
  bound is the prior watermark, the inclusive upper bound the new target.
- **Error Handling**: `success(emptyList())` when none match.
- **Idempotency**: Yes.

#### `listByStudent(session, studentId): Result<List<Observation>>`

- **Side Effects**: Read only — every observation for a student, ordered
  `created_at, id`.
- **Error Handling**: `success(emptyList())` when none match.
- **Idempotency**: Yes.

---

### `ClaimsDao` — [`ClaimsDao.kt`](./ClaimsDao.kt)

`object` implementing `Findable<Claim, ClaimId>`/`Creatable<NewClaim, Claim>`
over the mutable `claims` entity (RFC 66) — deduplicated statements distilled
about a student. `claims` has **no `version` column** (RFC 66 disabled
versioning), so no mutation carries an OCC guard; concurrent same-student passes
serialize on the per-student advisory lock (`AdvisoryLockDao`), not on OCC. The
write paths (`create`, `revise`) discriminate SQLSTATE via `mapClaimError`;
reads route through `mapDatabaseError`.

#### `create(session, input: NewClaim): Result<Claim>`

- **Side Effects**: Write — `insertReturning` over `student_id`, `origin`,
  `kind`, `subject`, `topic`, `visibility`, `statement` (the six enums bound as
  their `.value` strings). DB generates `id` (UUID), `created_at`, `updated_at`,
  and defaults `status` (`active`) and `confidence`.
- **Error Handling**: `NotFoundException("Owning student not found")` on the
  `student_id` FK; `ConstraintViolationException` on `23505`/`23514`.
- **Idempotency**: No.

#### `findById(session, id: ClaimId): Result<Claim>`

- **Side Effects**: Read only — `queryOne` by primary key.
- **Error Handling**: `NotFoundException()` when absent.
- **Idempotency**: Yes.

#### `listActiveByStudent(session, studentId): Result<List<Claim>>`

- **Side Effects**: Read only — the student's `status = 'active'` claims,
  ordered `created_at, id`. The hot read, served by `claims_student_active_idx`.
- **Error Handling**: `success(emptyList())` when none match.
- **Idempotency**: Yes.

#### `revise(session, id, revision: ClaimRevision): Result<Claim>`

- **Side Effects**: Read/write — `mutateReturning` updating a claim's lifecycle
  (`status`, `confidence`, `superseded_by_id`) by id. The `superseded_at`/
  `retracted_at` timestamp columns are **derived in SQL from the target status**
  (`NOW()` when superseding/retracting, else `NULL`) so the row always satisfies
  the DB lifecycle-consistency CHECKs; the `update_timestamp` trigger bumps
  `updated_at`. The `superseded_by_id` is bound as the UUID or SQL NULL.
- **Error Handling**: `NotFoundException` on 0 rows (no claim with that id);
  `NotFoundException("Superseding claim not found")` on the `superseded_by_id`
  FK; `ConstraintViolationException` on a lifecycle CHECK; via `mapClaimError`.
- **Idempotency**: No — `updated_at` advances each call.

---

### `ClaimSupportDao` — [`ClaimSupportDao.kt`](./ClaimSupportDao.kt)

`object` implementing `Creatable<NewClaimSupport, ClaimSupport>` over the
append-only `claim_support` link log (RFC 66) — the citation edges joining each
`claims` row to the `observations` that back it (composite PK
`(claim_id, observation_id)`). The write path discriminates SQLSTATE via
`mapSupportError`; the reads route through `mapDatabaseError`.

#### `link(session, claimId, observationId): Result<ClaimSupport>` / `create(session, input: NewClaimSupport): Result<ClaimSupport>`

- **Side Effects**: Write — an **idempotent** insert:
  `INSERT … ON CONFLICT (claim_id, observation_id) DO NOTHING RETURNING *`. A
  first insert returns the new row; a repeat conflicts, RETURNING yields
  nothing, the helper's `onNoRow` raises a private `ConflictNoOp` sentinel, and
  `recoverCatching` reads the existing row back via `readExisting` — so
  re-citing the same observation for the same claim is a no-op success, never a
  duplicate-key error. `create` delegates verbatim to `link`.
- **Error Handling**: `NotFoundException` (`claim_support_claim_id_fkey` →
  "Claim not found"; `claim_support_observation_id_fkey` → "Observation not
  found") via `mapSupportError`. No duplicate-key error path (absorbed by
  `ON CONFLICT`).
- **Idempotency**: Yes — repeat links collapse to the existing row.

#### `listObservationsForClaim(session, claimId): Result<List<Observation>>`

- **Side Effects**: Read only — JOINs `claim_support` to `observations`, ordered
  `o.created_at, o.id`; the "what backs this claim" read.
- **Error Handling**: `success(emptyList())` when none match.
- **Idempotency**: Yes.

---

### `ExtractionRunsDao` — [`ExtractionRunsDao.kt`](./ExtractionRunsDao.kt)

`object` implementing `Creatable<NewExtractionRun, ExtractionRun>` over the
append-only `extraction_runs` log (RFC 66) — one row per extraction pass
(success or failure), recording the window applied, the resolved model/provider,
the write counts, and token usage. The log is insert-only. The write path
discriminates SQLSTATE via `mapRunError`; `watermark` routes through
`mapDatabaseError`.

#### `create(session, input: NewExtractionRun): Result<ExtractionRun>` / `append(session, input): Result<ExtractionRun>`

- **Side Effects**: Write — `insertReturning` over the convo/student/
  through-request/system-prompt ids, `outcome`, `provider`, the nullable
  `model_resolved`, the three non-null write counts, and the four nullable
  token-usage columns. DB generates the `BIGINT` `id` and `created_at`. `append`
  is an alias of `create`.
- **Error Handling**: `NotFoundException` (convo/student/through-request/
  system-prompt FK) via `mapRunError`; `ConstraintViolationException` on
  `23505`/`23514`.
- **Idempotency**: No — every pass is a distinct row.

#### `watermark(session, convoId): Result<Long>`

- **Side Effects**: Read only — `COALESCE(MAX(through_request_id), 0)` over the
  convo's `outcome = 'applied'` rows, or 0 when none. The idempotency anchor for
  incremental extraction; `failed` rows are ignored (they billed tokens but did
  not advance the window).
- **Error Handling**: `mapDatabaseError` on failure.
- **Idempotency**: Yes.

---

### `AdvisoryLockDao` — [`AdvisoryLockDao.kt`](./AdvisoryLockDao.kt)

`object` exposing transaction-scoped advisory locks (RFC 66) — a net-new pattern
in this codebase. Declares no capability interface and touches no table. The
lock SQL lives here because the raw `SqlSession.execute` helper is `internal` to
`:db` and not visible from `:service`, so this is the public surface callers
use.

#### `lockStudent(session, studentId): Result<Unit>`

- **Side Effects**: Read/write — acquires the per-student transaction-scoped
  advisory lock via `SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))`
  (the student UUID stringified as the lock key). Built on a raw
  `prepareStatement`/`executeQuery` — not the generic helpers, which expect an
  update count or a RETURNING row — and the result set is drained and discarded;
  only the lock side-effect matters. Blocks until the lock is free, serializing
  all passes for the same student against the shared `claims`/`claim_support`
  state; distinct students hash to distinct keys and never contend. The lock is
  held for the remainder of the caller's transaction and released on
  commit/rollback — callers keep the LLM call outside the transaction that holds
  it.
- **Error Handling**: any thrown exception →
  `Result.failure(mapDatabaseError(e))` (its own try/catch; no SQLSTATE
  discrimination).
- **Idempotency**: No — re-acquiring within the same transaction is a stateful
  lock operation (Postgres permits re-entrant `pg_advisory_xact_lock`).

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
      — Added `CorruptPersistedValueException` and the `DaoException`
      pass-through guard in `mapDatabaseError`; row mappers throw corruption
      exceptions on failed re-validation.
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
- [x] [RFC-63: Admin System Prompts](../../../../../../../../rfc/63-admin-system-prompts.md)
      — Promoted `SystemPromptsDao` from a read-only reader to an immutable
      catalog admin surface: adopted `Findable`/`Listable`/`Creatable`, added
      `findById`, `list` (`name ASC, version ASC, created_at DESC`), and
      `create` (append-only insert of a new `(name, version)` row via
      `insertReturning`), plus the `mapPromptError` SQLSTATE map
      (`23505`/`23514` → `ConstraintViolationException`).
- [x] [RFC-64: Google SSO Login](../../../../../../../../rfc/64-google-sso-login.md)
      — Added `UserAuthIdentitiesDao` (append-only over `user_auth_identities`:
      `create`, `findByProviderAndSubject`, `listByUser`, with `mapCreateError`
      mapping `23505`/`23514` to `ConstraintViolationException`). Reshaped the
      `users` credential from a synthesized `AuthMethod`
      (`password_hash`/`sso_provider_id`) to a single nullable `password_hash`
      column read by `readPasswordHash`, deleting `AuthMethod`,
      `readAuthMethod`/`bindAuthMethodColumn`, and
      `CorruptPersistedAuthMethodException`. Added `login_method`
      mapping/binding to `SessionsDao` (`mapSession`, `create`) and a
      `newLoginMethod` parameter to `remintToken`.
- [x] [RFC-65: Email Verification](../../../../../../../../rfc/65-email-verification.md)
      — Added `VerificationTokensDao` over the new single-use
      `verification_tokens` table (`create`, the compare-and-swap `consume`,
      `findByTokenHash`, `consumeAllForUser`); added
      `UsersDao.markEmailVerified` (versioned conditional update, idempotent
      first-verification stamp); the `mapUser`/`mapUserVersion` mappers and the
      shared `updateFullRow` now carry `email_verified_at`.
- [x] [RFC-66: Coaching Memory Extraction](../../../../../../../../rfc/66-extraction.md)
      — Added the coaching-memory extraction DAOs: `ObservationsDao`
      (append-only `observations` log: `create`/`append`, `listByConvoRange`,
      `listByStudent`, with the `mapObservationError` FK map); `ClaimsDao`
      (mutable `claims` entity with no `version`/OCC: `create`, `findById`,
      `listActiveByStudent`, the lifecycle write `revise` with SQL-derived
      supersede/retract timestamps, and the `mapClaimError` map);
      `ClaimSupportDao` (append-only `claim_support` link log: the idempotent
      `link`/`create` via `ON CONFLICT DO NOTHING` + read-back,
      `listObservationsForClaim`, and the `mapSupportError` map with no
      duplicate-key path); `ExtractionRunsDao` (append-only `extraction_runs`
      log: `create`/`append`, the `applied`-only `watermark`, and the
      `mapRunError` map); and `AdvisoryLockDao` (the codebase's first
      advisory-lock surface — `lockStudent` via
      `pg_advisory_xact_lock(hashtextextended(...))`, serializing same-student
      passes). None declares a new capability interface beyond
      `Creatable`/`Findable`, and none adds a new `DaoException`. — Added
      `CollegesDao` over the `colleges`/`college_programs` reference tables: the
      codebase's first hand-rolled `INSERT … ON CONFLICT … DO UPDATE` upserts
      (`upsert` on `unit_id`, `upsertProgram` on
      `(college_id, cip_code, credential_level)`), the natural-key
      `findByUnitId` (NotFound folded to `null`), and the dynamic parameterized
      `search` (one `AND` per non-null `CollegeQuery` filter, optional
      `college_programs` JOIN with `cip_code LIKE prefix || '%'` + `array_agg`,
      deterministic `ORDER BY … LIMIT`). Declares no capability interface (the
      upsert key is a federal natural key). Added the `mapCollegeError`
      write-path SQLSTATE map (`23503` → NotFound; `23505`/`23514` →
      `ConstraintViolationException`) and the
      `mapCollege`/`mapProgram`/`mapMatch` row mappers (including `text[]`
      `program_titles` via JDBC `getArray`).
- [x] [RFC-70: Change-email flow](../../../../../../../../rfc/70-change-email.md)
      — Added `UsersDao.changeEmail` (versioned conditional update rewriting
      `email`, resetting `email_verified_at` to NULL, and bumping `version` on
      an active row via `mutateReturning`; `mapCreateUpdateError` maps the
      active-email index collision to `DuplicateEmailException`), isolated from
      the `UserEdit`/`update` surface like `markEmailVerified`.
