# RFC 91: College List

## Executive Summary

The coaching-memory feature (RFC 66/67) has no place for a student to say "I'm
applying to Michigan" and have the coach remember it as a durable, correctable
fact about a specific college — only the generic `claims` table, which is not
student-editable and carries no per-college structure. This RFC adds
`college_list_entries`: a dedicated entity tying a student to a real college
(`colleges.id`, RFC 82) with a status (`considering` / `applying` / `admitted` /
`rejected`) and a free-text `reasons` note, both student-correctable.

Provenance mirrors `claim_support`: entries optionally cite supporting
`observations` through an append-only link table, `college_list_entry_support`.
Editing an entry's status or reasons is a plain `UPDATE` on
`college_list_entries` and never touches the link table, so a correction can
never sever the trail back to what the student actually said.

The entity ships as a vertical slice with its first (and only) consumer this RFC
builds: a student-facing REST CRUD surface. There is no queue-worker producer
yet — nothing infers list membership automatically — and no chat-tool-use
dependency: the schema accepts an optional observation citation on write so a
future inline writer (post `chat-tool-use`) can target the same table, but
conversational/inline edits are out of scope here. A read-only admin view ships
now, since RFC 77's descriptor-driven admin engine makes it a declarative
addition, not new infrastructure. "Key dates" (deadlines, interviews, etc.),
floated in the originating feature brief, is cut from this RFC: no consumer for
it exists yet and its shape (fixed columns vs. a labeled child table) is
undetermined — added later behind its own migration when a concrete use case
exists.

## Detailed Design

### Data Models

#### `college_list_entries` — versioned mutable entity

Mirrors `students` (`db/schema/0005.create-students.sql`): OCC `version`, the
four-timestamp split (`created_at`/`row_created_at`/`updated_at`/
`row_updated_at`), and soft-delete via `deleted_at`. Migration
`db/schema/0024.create-college-list.sql`:

```sql
CREATE TABLE college_list_entries (
  id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  version        INTEGER     NOT NULL DEFAULT 1,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  deleted_at     TIMESTAMPTZ NULL,

  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  college_id UUID NOT NULL REFERENCES colleges(id) ON DELETE RESTRICT, -- colleges are never deleted (prevent_delete, 0023); RESTRICT never fires

  status  TEXT NOT NULL DEFAULT 'considering',
  reasons TEXT NULL,

  CONSTRAINT college_list_entries_status_check CHECK (status IN ('considering','applying','admitted','rejected')),
  CONSTRAINT college_list_entries_reasons_length_check CHECK (reasons IS NULL OR length(reasons) <= 2048),
  CONSTRAINT college_list_entries_reasons_not_empty_check CHECK (reasons IS NULL OR length(trim(reasons)) > 0)
);

-- One active entry per (student, college); removing and re-adding later is a
-- new row, so a prior removal's history is never resurrected into it.
CREATE UNIQUE INDEX college_list_entries_student_college_active_idx
  ON college_list_entries (student_id, college_id) WHERE deleted_at IS NULL;

CREATE INDEX college_list_entries_student_id_idx ON college_list_entries (student_id) WHERE deleted_at IS NULL;
```

`reasons` mirrors `claims.statement`'s length CHECK (`<= 2048`) and, despite
being nullable (unlike `statement`/`observations.quote`, both `NOT NULL`), also
takes the `_not_empty_check` counterpart those columns carry: `reasons` models
"no reason given" as SQL `NULL`, so an empty string is a distinct, meaningless
state (a student submitting `""` rather than omitting the field) the CHECK
rejects rather than silently accepting.

Triggers mirror `students` exactly, entity-scoped to `college_list_entries` per
the naming convention every existing migration follows (`students`'s own set:
`trigger_00_prevent_students_physical_delete`,
`trigger_00a_prevent_students_immutable_updates`,
`trigger_01_enforce_students_versioning`,
`trigger_03_enforce_students_updated_at`, `trigger_04_log_student_version`):

- `trigger_00_prevent_college_list_entries_physical_delete` —
  `prevent_physical_delete()`
- `trigger_00a_prevent_college_list_entries_immutable_updates` —
  `prevent_immutable_updates()`
- `trigger_00b_prevent_physical_timestamp_update` —
  `prevent_physical_timestamp_update()`; this one is the existing
  **non-suffixed** shared trigger name reused verbatim (already attached to
  `users`/`sessions`/`students`/`convos`/`claims`, the five tables with a
  `row_created_at` column — `college_list_entries` is the sixth, not a name
  variant)
- `trigger_01_enforce_college_list_entries_versioning` — `enforce_versioning()`
- `trigger_03_enforce_college_list_entries_updated_at` — `update_timestamp()`
- `trigger_04_log_college_list_entry_version` — a new
  `log_college_list_entry_version()` function analogous to
  `log_student_version()`

`college_list_entries_versions` mirrors `students_versions`: same columns minus
the surrogate PK, keyed `(id, version)`, FK `ON DELETE RESTRICT`.

#### `college_list_entry_support` — append-only link log

Mirrors `claim_support` exactly:

```sql
CREATE TABLE college_list_entry_support (
  entry_id       UUID   NOT NULL REFERENCES college_list_entries(id) ON DELETE CASCADE,
  observation_id BIGINT NOT NULL REFERENCES observations(id) ON DELETE CASCADE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (entry_id, observation_id)
);

CREATE INDEX college_list_entry_support_observation_idx ON college_list_entry_support (observation_id);
```

Carries `prevent_log_update` + `prevent_log_delete`. An entry with no support
rows is a student-asserted fact with no cited utterance — the majority case for
this RFC's REST-only consumer, exactly as most `claims` cite no observation.

`db/schema/INVARIANTS.md` requires two hand-edits, not a mechanical append:
`college_list_entry_support` joins the log-table-guard enumeration (a list
edit), and — inside the existing "DB-level trigger" invariant's **Why** clause —
the prose sentence "the `row_created_at` guarantee is carried separately by
`prevent_physical_timestamp_update()` on the five tables that have that column
(`users`, `sessions`, `students`, `convos`, `claims`)" is hand-edited to six,
adding `college_list_entries`. This is a prose rewrite of an existing sentence,
not a new bullet.

#### Kotlin models (`db/src/main/kotlin/ed/unicoach/db/models/`)

```kotlin
@JvmInline value class CollegeListEntryId(val value: UUID) : Id

enum class CollegeListEntryStatus(val value: String) {
  CONSIDERING("considering"), APPLYING("applying"), ADMITTED("admitted"), REJECTED("rejected");
  companion object { fun fromValue(v: String): CollegeListEntryStatus? }
}

data class CollegeListEntry(
  override val id: CollegeListEntryId,
  val studentId: StudentId,
  val collegeId: CollegeId,
  val status: CollegeListEntryStatus,
  val reasons: String?,
  override val version: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
) : Identifiable<CollegeListEntryId>,
  Created,
  Updated,
  Versioned,
  SoftDeletable

data class NewCollegeListEntry(val studentId: StudentId, val collegeId: CollegeId, val status: CollegeListEntryStatus, val reasons: String?)
data class CollegeListEntryEdit(val id: CollegeListEntryId, val version: Int, val status: CollegeListEntryStatus, val reasons: String?)

data class CollegeListEntrySupport(val entryId: CollegeListEntryId, val observationId: ObservationId, val createdAt: Instant)
data class NewCollegeListEntrySupport(val entryId: CollegeListEntryId, val observationId: ObservationId)
```

#### DAOs (`db/src/main/kotlin/ed/unicoach/db/dao/`)

`CollegeListEntriesDao` composes the existing capability interfaces
(`db/dao/Dao.kt`) — no new SQL primitive, only the existing
`insertReturning`/`updateColumnsReturning`/`softDeleteReturning` helpers.
`StudentsDao`'s shape is the precedent for the CRUD/OCC interfaces
(`SoftDeleteFindable`, `Creatable`, `Updatable`, `OccDeletable`,
`VersionHistory`); `StudentsDao` implements no listing interface at all, so the
`SoftDeleteListable` composition below instead follows `UsersDao`, the one DAO
in the codebase that implements it:

```kotlin
object CollegeListEntriesDao :
  SoftDeleteFindable<CollegeListEntry, CollegeListEntryId>,
  Creatable<NewCollegeListEntry, CollegeListEntry>,
  Updatable<CollegeListEntryEdit, CollegeListEntry>,
  OccDeletable<CollegeListEntry, CollegeListEntryId>,
  SoftDeleteListable<CollegeListEntry>,
  VersionHistory<CollegeListEntryId, Version<CollegeListEntry>> {
  // findById(session, id, scope) — SoftDeleteFindable
  // create / update / delete / undelete — as above
  // list(session, scope, limit, offset) — SoftDeleteListable; admin surface, see API Contracts for the pagination rationale
  // listVersions(session, id) — admin history read (RFC 77 posture)

  /** Ownership-scoped fetch: a wrong-owner id is NotFoundException, never a separate Forbidden. */
  fun findByIdAndStudent(session: SqlSession, id: CollegeListEntryId, studentId: StudentId, scope: SoftDeleteScope = SoftDeleteScope.ACTIVE): Result<CollegeListEntry>

  /** The student's active list, ordered created_at, id. The hot read — see API Contracts for why it is unpaginated. */
  fun listActiveByStudent(session: SqlSession, studentId: StudentId): Result<List<CollegeListEntry>>
}
```

`mapError` follows `ClaimsDao`'s pattern: `23503` on `college_id` FK →
`NotFoundException("College not found")`; on `student_id` FK →
`NotFoundException("Owning student not found")`; `23505` on the
active-uniqueness index → `ConstraintViolationException` (service maps this to
"already on the list").

`CollegeListEntrySupportDao` mirrors `ClaimSupportDao`: `link()` is an
idempotent
`INSERT ... ON CONFLICT (entry_id, observation_id) DO NOTHING RETURNING *`. When
the same `(entry_id, observation_id)` pair is linked again, the conflict means
`RETURNING` yields no row; `link()` then reads the existing row back by its
composite key and returns it, so a duplicate/conflicting link attempt is a no-op
success — never an error — identical to `ClaimSupportDao.link()`. Plus
`listObservationsForEntry(session, entryId)` and, for the admin reverse lookup,
`listEntriesForObservation(session, observationId)`.

### API Contracts

#### Service (`service/src/main/kotlin/ed/unicoach/coaching/collegelist/CollegeListService.kt`)

Same shape as `StudentService`: one `Database`-backed class, `Result<Outcome>`
per operation, OCC conflicts surfaced as a named outcome rather than an
exception.

```kotlin
class CollegeListService(private val database: Database) {
  suspend fun addToList(studentId: StudentId, collegeId: CollegeId, status: CollegeListEntryStatus, reasons: String?, observationIds: List<ObservationId>): Result<AddToListResult>
  suspend fun listForStudent(studentId: StudentId): Result<List<CollegeListEntry>>
  suspend fun getForStudent(studentId: StudentId, entryId: CollegeListEntryId): Result<GetEntryResult>
  suspend fun updateEntry(studentId: StudentId, entryId: CollegeListEntryId, expectedVersion: Int, status: CollegeListEntryStatus, reasons: String?, addObservationIds: List<ObservationId>): Result<UpdateEntryResult>
  suspend fun removeFromList(studentId: StudentId, entryId: CollegeListEntryId, expectedVersion: Int): Result<RemoveEntryResult>
}

sealed interface AddToListResult {
  data class Success(val entry: CollegeListEntry) : AddToListResult
  data object CollegeNotFound : AddToListResult
  data object AlreadyOnList : AddToListResult
  data class ObservationNotFound(val observationId: ObservationId) : AddToListResult // cited id absent, or not owned by this student
}
sealed interface GetEntryResult { data class Found(val entry: CollegeListEntry) : GetEntryResult; data object NotFound : GetEntryResult }
sealed interface UpdateEntryResult {
  data class Success(val entry: CollegeListEntry) : UpdateEntryResult
  data object NotFound : UpdateEntryResult
  data object VersionConflict : UpdateEntryResult
  data class ObservationNotFound(val observationId: ObservationId) : UpdateEntryResult
}
sealed interface RemoveEntryResult { data class Success(val entry: CollegeListEntry) : RemoveEntryResult; data object NotFound : RemoveEntryResult; data object VersionConflict : RemoveEntryResult }
```

Observation citation ownership: before linking, the service loads each cited
observation via `ObservationsDao.findById` and rejects (as `ObservationNotFound`
— identical wire treatment to "doesn't exist," never leaking whose observation
it actually is) any whose `studentId` does not match the caller. `addToList` and
`updateEntry` link citations inside the same transaction as the row
write/update.

#### REST (`rest-server/src/main/kotlin/ed/unicoach/rest/routing/CollegeListRoutes.kt`)

Nested under the existing student-facing convention
(`StudentRoutes.kt`/`ConvoRoutes.kt`), all routes requiring an authenticated
user with an existing `Student` (missing profile →
`409
STUDENT_PROFILE_REQUIRED`, the `ConvoRoutes` convention, not `404`, since
the resource is "your college list" not "a specific college list"). Cross-tenant
access on a path id 404s (never `403`), matching `ConvoRoutes`. No new
`ErrorCode` members — every failure reuses an existing code:

| Method   | Path                                                           | Success                          | Failure                                                                                                                                                                 |
| -------- | -------------------------------------------------------------- | -------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `POST`   | `/api/v1/students/me/college-list`                             | `201` `CollegeListEntryResponse` | `400 VALIDATION_FAILED`, `404 NOT_FOUND` ("No such college" / "No such observation"), `409 CONFLICT` ("College is already on the list"), `409 STUDENT_PROFILE_REQUIRED` |
| `GET`    | `/api/v1/students/me/college-list`                             | `200` `CollegeListResponse`      | `409 STUDENT_PROFILE_REQUIRED`                                                                                                                                          |
| `GET`    | `/api/v1/students/me/college-list/{entryId}`                   | `200` `CollegeListEntryResponse` | `404 NOT_FOUND`, `409 STUDENT_PROFILE_REQUIRED`                                                                                                                         |
| `PATCH`  | `/api/v1/students/me/college-list/{entryId}`                   | `200` `CollegeListEntryResponse` | `400 VALIDATION_FAILED`, `404 NOT_FOUND`, `409 VERSION_CONFLICT`, `409 STUDENT_PROFILE_REQUIRED`                                                                        |
| `DELETE` | `/api/v1/students/me/college-list/{entryId}?version={version}` | `204`                            | `400 VALIDATION_FAILED` (missing/non-integer `version`), `404 NOT_FOUND`, `409 VERSION_CONFLICT`, `409 STUDENT_PROFILE_REQUIRED`                                        |

The collection `GET` is unpaginated: it returns every active entry for the
caller's own student in one response. A college list is bounded by real-world
application volume (tens of entries per student, not hundreds), unlike
admin-facing `list` endpoints that page across every student's rows; adding
`limit`/`offset` here would be unused complexity for a per-student collection
this small. The read-only admin surface (below) pages instead, via the
`SoftDeleteListable<CollegeListEntry>` interface.

`PATCH` is a full replace of `status`/`reasons` plus `version` (mirrors
`UpdateStudentRequest`: no partial-field semantics, no null-vs-absent
ambiguity). `DELETE` takes `version` as a query parameter — this codebase has no
existing OCC-guarded `DELETE`, so a query parameter is the only fit without
introducing a request body on `DELETE`.

DTOs (`rest-server/src/main/kotlin/ed/unicoach/rest/models/`), one file per
class as the existing convention (`CreateStudentRequest.kt` etc.):

```kotlin
// CreateCollegeListEntryRequest.kt
data class CreateCollegeListEntryRequest(val collegeId: UUID, val status: String = "considering", val reasons: String? = null, val observationIds: List<Long> = emptyList())

// UpdateCollegeListEntryRequest.kt
data class UpdateCollegeListEntryRequest(val version: Int, val status: String, val reasons: String?, val addObservationIds: List<Long> = emptyList())

// CollegeListEntryResponse.kt
data class CollegeListEntryResponse(val entry: PublicCollegeListEntry)
data class PublicCollegeListEntry(
  val id: UUID, val collegeId: UUID, val status: String, val reasons: String?,
  val version: Int, val createdAt: Instant, val updatedAt: Instant,
  val supportingObservations: List<ObservationSummary>,
)
data class ObservationSummary(val id: Long, val quote: String, val utteredAt: Instant)

// CollegeListResponse.kt
data class CollegeListResponse(val entries: List<PublicCollegeListEntry>)
```

`status` is validated against `CollegeListEntryStatus.fromValue`; an unknown
wire value is `400 VALIDATION_FAILED` with a `FieldError("status", …)`, the
`StudentRoutes`/`FieldError` convention.

`api-specs/openapi.yaml` gains the five paths above and their schemas, alongside
the existing `/api/v1/students/*` block.

### Admin View

`admin-web/src/main/kotlin/ed/unicoach/admin/resources/CollegeListEntriesResource.kt`,
`AdminKind.ENTITY`, one `HasMany` edge to supporting observations via
`CollegeListEntrySupportDao.listObservationsForEntry`. Registered in
`admin-web/.../Application.kt`'s resource list alongside `ClaimsResource`/
`ObservationsResource`.

Unlike `claims` (no `deleted_at`, so its all-four-null posture is a non-choice),
`college_list_entries` carries `deleted_at` and OCC `version`, making it
structurally comparable to `convos` (RFC 32/81) — the closest precedent for a
top-level `AdminKind.ENTITY` that is soft-deletable, user-writable through its
own domain surface, and admin-read-only: `ConvosResource` is `topLevel = true`
with all four write handlers (`create`/`update`/`delete`/`undelete`) `null`, and
its `list`/`get` forward a `SoftDeleteScope` (`ConvosResource.list()` calls
`ConvosDao.listWithActivity(session, scope, limit, offset)`) rather than
ignoring it the way `StudentsResource` — an `EMBEDDED_ENTITY` with a permanently
stubbed `list()` — does. `CollegeListEntriesResource` follows the
`ConvosResource` precedent exactly: `create`/`update`/`delete`/`undelete` are
all `null` because the entity is user-writable through its own domain surface
(REST for `college_list_entries`, same as `convos`) and admin is
read-only-plus-history, not a parallel write path; `list`/`get` call
`CollegeListEntriesDao.list(session, scope, limit, offset)` /
`findById(session, id, scope)`. Per `AdminResource`'s documented engine-wide
invariant, `AdminRouting` always calls `list` with `scope = SoftDeleteScope.ALL`
and `get` with `includeDeleted = true` — there is no HTTP-reachable toggle for
any resource, `college_list_entries` included — so soft-deleted entries always
remain visible for audit via `scope` rather than being excluded or
admin-restorable.

### Error Handling / Edge Cases

- **Re-adding a removed college.** The partial unique index only constrains
  active rows, so removing (`DELETE`) then re-adding a college creates a new
  `college_list_entries` row with a fresh `id`; the old row's version history
  stays intact under its own soft-deleted id. This is a deliberate simplicity
  choice — no "undelete and resume" flow — matching how `students` offers
  `undelete` as a distinct DAO op the REST layer here does not expose (YAGNI: no
  consumer needs it).
- **Concurrent edits.** Two devices `PATCH`ing the same entry race on `version`
  exactly like `students`: the loser gets `409 VERSION_CONFLICT` and must
  re-`GET` and retry.
- **Cited observation belongs to another student.** Treated identically to a
  nonexistent observation id (`ObservationNotFound` → `404 NOT_FOUND`) so the
  endpoint never confirms or denies another student's observation exists.
- **`college_id` references a college that is later re-ingested (RFC 82
  versioning).** `colleges.id` is stable across version bumps (only `version`
  and content columns change on re-ingest), so the FK never dangles and no
  action is needed on ingest.

### Dependencies

Depends on `students` (0005), `colleges` (0015, versioned by 0023),
`observations` (0019). No dependency on `chat-tool-use` (not yet built) or on
`extraction`'s claim/confidence machinery — this entity is written directly by
the REST surface, not derived from claims.

## Tests

**`CollegeListEntriesDaoTest`** (`db/src/test/kotlin/ed/unicoach/db/dao/`):

- `create` persists all columns; default `status = 'considering'`.
- `create` with an unknown `college_id` →
  `NotFoundException("College not found")`.
- `create` with an unknown `student_id` →
  `NotFoundException("Owning student not found")`.
- `create` duplicate active `(student_id, college_id)` →
  `ConstraintViolationException`.
- `create` for the same `(student_id, college_id)` after soft-delete succeeds
  (new row).
- `findById` respects `SoftDeleteScope` (`ACTIVE`/`DELETED`/`ALL`).
- `findByIdAndStudent` returns `NotFoundException` for a wrong-owner id (not a
  distinct "forbidden" error).
- `update` bumps `version`, rejects a stale `currentVersion` with
  `ConcurrentModificationException`.
- `listActiveByStudent` excludes soft-deleted rows, orders `created_at, id`.
- `delete`/`undelete` round-trip via `OccDeletable`.
- `listVersions` returns ascending version history after multiple updates.
- Reasons length: 2049 chars rejected by the DB CHECK
  (`ConstraintViolationException`).
- Reasons empty string (`""` or all-whitespace) rejected by the DB CHECK
  (`ConstraintViolationException`); `NULL` reasons still succeeds.

**`CollegeListEntrySupportDaoTest`**:

- `link` is idempotent (repeat link is a no-op, not a duplicate-key error).
- `link` with an unknown `entry_id`/`observation_id` → `NotFoundException`.
- `listObservationsForEntry` / `listEntriesForObservation` are exact inverses.
- Attempting `UPDATE`/`DELETE` on `college_list_entry_support` raises (log-table
  guard).

**`CollegeListServiceTest`**
(`service/src/test/kotlin/ed/unicoach/coaching/collegelist/`):

- `addToList` happy path returns `Success` with the created entry and links
  every valid citation.
- `addToList` with a citation owned by a different student →
  `ObservationNotFound`.
- `addToList` for a college already on the (active) list → `AlreadyOnList`.
- `updateEntry` with a stale version → `VersionConflict`; with a wrong-owner
  entry id → `NotFound`.
- `updateEntry` adding a new citation appends to `college_list_entry_support`
  without touching prior citations.
- `removeFromList` soft-deletes; a subsequent `getForStudent` on the same id →
  `NotFound` (scoped to `ACTIVE`).

**`CollegeListRoutingTest`** (`rest-server/src/test/kotlin/ed/unicoach/rest/`),
following `AuthRoutingTest`'s harness:

- Full CRUD happy path through HTTP: `POST` → `GET` list → `GET` one → `PATCH` →
  `DELETE`.
- `POST` without a student profile → `409 STUDENT_PROFILE_REQUIRED`.
- `POST` with an invalid `status` string → `400 VALIDATION_FAILED` with a
  `FieldError` naming `status`.
- `POST` with an unknown `collegeId` → `404 NOT_FOUND`.
- `GET`/`PATCH`/`DELETE` on another student's entry id → `404 NOT_FOUND`.
- `PATCH` with a stale `version` → `409 VERSION_CONFLICT`.
- `DELETE` missing the `version` query parameter → `400 VALIDATION_FAILED`.
- Unauthenticated request to every route → `401 UNAUTHORIZED`.
- Unsupported method on each route registered with the production
  `rejectUnsupportedMethods` handler (`Routing.kt`) returns `405` with an
  `Allow` header — one per-route named test case per `AuthRoutingTest`'s
  convention (e.g. `` `PUT college-list returns 405 with Allow` ``), not a
  shared test helper (no such helper exists in `rest-server/src/test/`).

**`CollegeListEntriesResourceTest`**
(`admin-web/src/test/kotlin/ed/unicoach/admin/resources/`), mirroring
`ConvosResourceTest`'s soft-delete-aware read posture:

- List/detail render the persisted fields; no create/edit/delete/undelete
  affordance is registered (all four handlers null).
- A soft-deleted entry remains visible and marked deleted at its admin detail
  route, mirroring `ConvosResourceTest`'s
  `` `GET convo id marks a deleted
  conversation` `` — `AdminRouting` always
  reads with `SoftDeleteScope.ALL` / `includeDeleted = true`, so there is no
  exclusion path to test.
- The supporting-observations edge panel renders linked observations and is
  empty for an uncited entry.

**`openapi.yaml`**: the project's Deno-based OpenAPI validation confirms the new
paths parse.

## Invariants

None. `college_list_entry_support`'s append-only guard is already covered by
`db/schema/INVARIANTS.md`'s existing generalized rule ("a new log or immutable
table MUST attach the same guards"); `college_list_entries`'s OCC/soft-delete
behavior is the standard versioned-entity pattern already governing `students`,
enforced by the same DB triggers, not a new discipline specific to this
directory.

## Implementation Plan

1. **Migration.** Add `db/schema/0024.create-college-list.sql` (both tables, all
   entity-scoped triggers per the Data Models section, indexes, and the new
   `log_college_list_entry_version()` function, defined in-place as 0023 did for
   its own new trigger functions). Hand-edit `db/schema/INVARIANTS.md`: append
   `college_list_entry_support` to the log-table-guard enumeration, rewrite the
   "five tables" sentence in the DB-level-trigger invariant's **Why** clause to
   six, adding `college_list_entries` (see Data Models for the exact
   before/after wording), and append
   `- [x] [RFC-91: College List](../../rfc/91-college-list.md)` to
   `##
   History`, per the established per-RFC entry pattern (RFC-05/66/82).
   _Verify:_ `nix develop -c bin/db-migrate`;
   `nix develop -c psql $POSTGRES_DB -c '\d college_list_entries'`.
2. **Models.** Add the Kotlin model files listed under Data Models. _Verify:_
   `nix develop -c ./gradlew :db:compileKotlin`.
3. **DAOs + tests.** Add `CollegeListEntriesDao`, `CollegeListEntrySupportDao`,
   and their test files. _Verify:_ `nix develop -c bin/test db -f`.
4. **Service + tests.** Add `CollegeListService` and result types under
   `service/.../coaching/collegelist/`. _Verify:_
   `nix develop -c bin/test service -f`.
5. **REST routes, DTOs, wiring.** Add `CollegeListRoutes.kt`, the DTO files,
   wire `CollegeListRouteHandler` and `CollegeListService` construction into
   `rest-server/.../Routing.kt` and `Application.kt`. _Verify:_
   `nix develop -c ./gradlew :rest-server:compileKotlin`.
6. **REST tests.** Add `CollegeListRoutingTest`. _Verify:_
   `nix develop -c bin/test rest-server -f`.
7. **OpenAPI spec.** Add the five paths/schemas to `api-specs/openapi.yaml`.
   _Verify:_ the project's Deno-based OpenAPI validation command.
8. **Admin resource + wiring + tests.** Add `CollegeListEntriesResource.kt`,
   register it in `admin-web/.../Application.kt`, add
   `CollegeListEntriesResourceTest`. _Verify:_
   `nix develop -c bin/test admin-web -f`.
9. **Full suite + format gate.** Run the complete hook-equivalent locally before
   the final commit — the full-tree gate, distinct from and not redundant with
   the per-module `bin/test <module> -f` runs above. _Verify:_
   `nix develop -c bin/format -c && nix develop -c bin/test check -f`.

## Files Modified

- `db/schema/0024.create-college-list.sql` (new)
- `db/schema/INVARIANTS.md`
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeListEntryId.kt` (new)
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeListEntryStatus.kt` (new)
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeListEntry.kt` (new)
- `db/src/main/kotlin/ed/unicoach/db/models/NewCollegeListEntry.kt` (new)
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeListEntryEdit.kt` (new)
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeListEntrySupport.kt` (new)
- `db/src/main/kotlin/ed/unicoach/db/models/NewCollegeListEntrySupport.kt` (new)
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegeListEntriesDao.kt` (new)
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegeListEntrySupportDao.kt` (new)
- `db/src/test/kotlin/ed/unicoach/db/dao/CollegeListEntriesDaoTest.kt` (new)
- `db/src/test/kotlin/ed/unicoach/db/dao/CollegeListEntrySupportDaoTest.kt`
  (new)
- `service/src/main/kotlin/ed/unicoach/coaching/collegelist/CollegeListService.kt`
  (new)
- `service/src/main/kotlin/ed/unicoach/coaching/collegelist/AddToListResult.kt`
  (new)
- `service/src/main/kotlin/ed/unicoach/coaching/collegelist/GetEntryResult.kt`
  (new)
- `service/src/main/kotlin/ed/unicoach/coaching/collegelist/UpdateEntryResult.kt`
  (new)
- `service/src/main/kotlin/ed/unicoach/coaching/collegelist/RemoveEntryResult.kt`
  (new)
- `service/src/test/kotlin/ed/unicoach/coaching/collegelist/CollegeListServiceTest.kt`
  (new)
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/CollegeListRoutes.kt`
  (new)
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/CreateCollegeListEntryRequest.kt`
  (new)
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/UpdateCollegeListEntryRequest.kt`
  (new)
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/CollegeListEntryResponse.kt`
  (new)
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/CollegeListResponse.kt`
  (new)
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/CollegeListRoutingTest.kt` (new)
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/CollegeListEntriesResource.kt`
  (new)
- `admin-web/src/main/kotlin/ed/unicoach/admin/Application.kt`
- `admin-web/src/test/kotlin/ed/unicoach/admin/resources/CollegeListEntriesResourceTest.kt`
  (new)
- `api-specs/openapi.yaml`
