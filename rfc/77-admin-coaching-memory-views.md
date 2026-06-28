# RFC 77: Read-only admin views for coaching memory

## Executive Summary

The coaching-memory tables (`observations`, `claims`, `claim_support`,
`extraction_runs`) have no admin surface: a row written by extraction can only
be inspected by hand in `psql`. This RFC makes them browsable through the
existing descriptor-driven admin engine, strictly read-only.

It adds three new `AdminResource` descriptors — `claim`, `observation`,
`extraction-run` — each declaring list/detail only, with all four write handlers
(`create`/`update`/`delete`/`undelete`) null. No new `AdminKind` is introduced:
the engine gates operations on handler nullability, not on kind (the kind is a
semantic label with no engine-observable effect beyond the `EMBEDDED_ENTITY`
route exemption), so read-only needs no new kind. `observations` and
`extraction_runs` are append-only logs (`AdminKind.LOG`); `claims` is mutable in
the domain but exposes no admin writes (`AdminKind.ENTITY`).

`claim_support` gets no descriptor — its composite primary key
`(claim_id, observation_id)` has no surrogate id and fits the engine's
single-typed-id contract poorly. It is surfaced only through edges: claim detail
lists its supporting observations, observation detail lists the claims it
supports (reverse lookup).

The embedded `students` profile panel gains three nested tables — that student's
claims, observations, and extraction runs — each row linking to the new
canonical detail URLs, so per-student memory and LLM spend are reviewable from
the user page.

No schema migration and no engine change. The work is three descriptors,
registry wiring, and read-only DAO methods (`findById`, paged `list`,
per-student list, observation-reverse-link) added to the existing typed DAOs per
the `db/dao/Dao.kt` capability interfaces.

## Detailed Design

### Data Models

No schema change. Source of truth is the applied migrations `0019` (coaching
memory) and the model/DAO code; the descriptors and DAO methods below read those
columns exactly as the row mappers in `ClaimsDao`/`ObservationsDao`/
`ExtractionRunsDao` already produce them.

#### DAO additions (read-only)

New methods compose the existing à-la-carte capability interfaces in
`db/src/main/kotlin/ed/unicoach/db/dao/Dao.kt` (`Findable`, `Listable`) plus
per-parent list methods. Every method returns `Result<…>` and delegates to the
shared `SqlSessionQueries` scaffolding, mirroring the existing methods on each
DAO. No DAO builds new table-spanning SQL beyond the existing `claim_support`
join shape.

`ClaimsDao` (already `Findable<Claim, ClaimId>`):

```kotlin
// Listable<Claim>
fun list(session: SqlSession, limit: Int, offset: Int): Result<List<Claim>>
// all statuses for a student (distinct from the existing active-only
// listActiveByStudent); ordered created_at, id
fun listByStudent(session: SqlSession, studentId: StudentId, limit: Int, offset: Int): Result<List<Claim>>
```

`ObservationsDao` (currently `Creatable` only):

```kotlin
// Findable<Observation, ObservationId>
fun findById(session: SqlSession, id: ObservationId): Result<Observation>
// Listable<Observation>
fun list(session: SqlSession, limit: Int, offset: Int): Result<List<Observation>>
// bounded per-student overload (the existing unbounded listByStudent is retained)
fun listByStudent(session: SqlSession, studentId: StudentId, limit: Int, offset: Int): Result<List<Observation>>
```

`ExtractionRunsDao` (currently `Creatable` only):

```kotlin
// Findable<ExtractionRun, ExtractionRunId>
fun findById(session: SqlSession, id: ExtractionRunId): Result<ExtractionRun>
// Listable<ExtractionRun>
fun list(session: SqlSession, limit: Int, offset: Int): Result<List<ExtractionRun>>
// ordered created_at, id; serves extraction_runs_student_idx
fun listByStudent(session: SqlSession, studentId: StudentId, limit: Int, offset: Int): Result<List<ExtractionRun>>
```

`ClaimSupportDao` (currently has `listObservationsForClaim`):

```kotlin
// reverse of listObservationsForClaim: the claims an observation supports;
// joins claims on claim_support.claim_id, served by claim_support_observation_idx
fun listClaimsForObservation(session: SqlSession, observationId: ObservationId): Result<List<Claim>>
```

`list` ordering for the top-level paged lists is `id ASC` for the `BIGINT`-keyed
logs (`observations`, `extraction_runs`, monotonic with insertion) and
`row_created_at, id` for `claims`, so paging is deterministic. `findById`
returns the DAO's `NotFoundException` for an unknown id (already mapped by each
DAO).

#### Descriptors

Three new `AdminResource` implementations in
`admin-server/src/main/kotlin/ed/unicoach/admin/resources/`. Each is a stateless
`object`. Common contract: `topLevel = true`; `create`/`update`/`delete`/
`undelete` all null; `isDeleted` always false; `list`/`get` ignore the engine's
`scope`/`includeDeleted` (no `deleted_at` on any table — same posture as
`SessionsResource`/`SystemPromptsResource`); `list`/`get` delegate to the DAO
methods above inside `db.withConnection`.

`ClaimsResource` — `slug = "claim"`, `title = "Claim"`,
`kind = AdminKind.ENTITY` (mutable in the domain; admin exposes no writes).
`parseId` over `ClaimId(UUID)`.

| field          | type      | inList |
| -------------- | --------- | ------ |
| id             | TEXT      | yes    |
| studentId      | TEXT      | yes    |
| status         | TEXT      | yes    |
| kind           | TEXT      | yes    |
| topic          | TEXT      | yes    |
| confidence     | INT       | yes    |
| createdAt      | TIMESTAMP | yes    |
| origin         | TEXT      | no     |
| subject        | TEXT      | no     |
| visibility     | TEXT      | no     |
| statement      | MULTILINE | no     |
| supersededById | TEXT      | no     |
| supersededAt   | TIMESTAMP | no     |
| retractedAt    | TIMESTAMP | no     |
| updatedAt      | TIMESTAMP | no     |

Edge: one panel, "Supporting observations", resolved via
`ClaimSupportDao.listObservationsForClaim`; rows link to `/observation/{id}`.

`ObservationsResource` — `slug = "observation"`, `title = "Observation"`,
`kind = AdminKind.LOG`. `parseId` over `ObservationId(Long)`
(`raw.toLongOrNull()`).

| field           | type      | inList |
| --------------- | --------- | ------ |
| id              | TEXT      | yes    |
| studentId       | TEXT      | yes    |
| convoId         | TEXT      | yes    |
| utteredAt       | TIMESTAMP | yes    |
| createdAt       | TIMESTAMP | yes    |
| sourceRequestId | TEXT      | no     |
| quote           | MULTILINE | no     |

Edge: one panel, "Supported claims", resolved via
`ClaimSupportDao.listClaimsForObservation`; rows link to `/claim/{id}`.

`ExtractionRunsResource` — `slug = "extraction-run"`,
`title = "Extraction Run"`, `kind = AdminKind.LOG`. `parseId` over
`ExtractionRunId(Long)`.

| field               | type      | inList |
| ------------------- | --------- | ------ |
| id                  | TEXT      | yes    |
| studentId           | TEXT      | yes    |
| outcome             | TEXT      | yes    |
| modelResolved       | TEXT      | yes    |
| claimsWritten       | INT       | yes    |
| inputTokens         | INT       | yes    |
| outputTokens        | INT       | yes    |
| createdAt           | TIMESTAMP | yes    |
| convoId             | TEXT      | no     |
| throughRequestId    | TEXT      | no     |
| systemPromptId      | TEXT      | no     |
| provider            | TEXT      | no     |
| observationsWritten | INT       | no     |
| claimsSuperseded    | INT       | no     |
| cacheReadTokens     | INT       | no     |
| cacheWriteTokens    | INT       | no     |

No edges (`resolveEdges` default-empty). The token and write-count columns make
per-student LLM spend eyeballable on the list, on detail, and on the student
panel.

No column on any of the three is marked `sensitive`: the existing convention
reserves redaction for credential material (`password_hash`, `token_hash`), not
content. `quote` and `statement` are the values the view exists to inspect; they
are kept out of the list table via `inList = false` (oversized, up to 4096/2048
chars) but shown in full on detail, the same treatment `system_prompts.body`
receives.

#### Student-panel nested tables

`StudentsResource.buildPanel` appends three `EdgePanel.Table` panels to the
embedded panel's `nested` list, after the existing `students_versions` history
table. The student is an `EMBEDDED_ENTITY` with no route, so the engine never
calls its `resolveEdges`; these tables are built in `buildPanel` exactly as the
version-history table already is. Each reads the bounded per-student DAO list
(cap `STUDENT_PANEL_LIMIT = 50`, mirroring the sessions panel's `limit = 50`)
and links rows to the canonical detail URLs:

| panel           | source                                       | row link               |
| --------------- | -------------------------------------------- | ---------------------- |
| Claims          | `ClaimsDao.listByStudent(id, 50, 0)`         | `/claim/{id}`          |
| Observations    | `ObservationsDao.listByStudent(id, 50, 0)`   | `/observation/{id}`    |
| Extraction runs | `ExtractionRunsDao.listByStudent(id, 50, 0)` | `/extraction-run/{id}` |

A transient DAO fault on any of the three propagates as a failed `Result` (the
panel build already short-circuits on
`getOrElse { return Result.failure(it) }`), so the user detail page renders the
DAO-error page rather than a panel masking the fault — consistent with the
existing sessions/history edge contract.

### API Contracts

Routes are generated by the engine from the registry; no route is hand-written.
Registering the three descriptors in `AdminRegistry` yields, per descriptor
(`{slug}` ∈ `claim`, `observation`, `extraction-run`):

| route                                   | behavior                                                 |
| --------------------------------------- | -------------------------------------------------------- |
| `GET /{slug}`                           | paged list (50/page, count-free), nav entry on dashboard |
| `GET /{slug}/{id}`                      | detail: field table + resolved edge panels               |
| `GET /{slug}/new`                       | not registered (`create == null`) → 404                  |
| `GET /{slug}/{id}/edit`                 | `update == null` → not-found page                        |
| `POST /{slug}`, `POST /{slug}/{id}`     | not registered / not-found (no create/update)            |
| `POST /{slug}/{id}/delete`, `/undelete` | not-found (`delete`/`undelete` null)                     |

The three slugs join `user`, `session`, `system-prompt` on the dashboard nav.
Detail links from the student panel and from the claim↔observation edges all
target the single canonical `/{slug}/{id}` path; no nested detail URLs are
introduced.

### Error Handling / Edge Cases

- Malformed path id (`parseId` returns null — non-UUID for `claim`, non-numeric
  for `observation`/`extraction-run`) → engine renders the not-found page.
- `findById` on an unknown id → DAO `NotFoundException` → 404 page.
- Transient DB fault on list/get/edge → failed `Result` → DAO-error page.
- Empty edge result (a claim with no support, an observation citing no claim) →
  an empty `EdgePanel.Table`, rendered as a panel with no rows (not an error).
- A claim's `claim_support` reverse/forward join returning observations/claims
  across students is impossible by FK shape but not special-cased; the panel
  renders whatever the join yields.
- Student-panel tables are capped at 50 rows; a student with more memory shows
  the first page only. Full enumeration is via the global `/{slug}` lists. This
  cap is the known limitation — there is no student-filtered paged route (the
  engine's list route takes no filter parameter), matching the existing
  sessions-panel cap.

### Dependencies

- The admin engine (`admin-server/.../engine`): `AdminResource`, `AdminKind`,
  `AdminField`/`FieldType`, `AdminEdge`, `EdgePanel`, `AdminRegistry`,
  `registerAdminRoutes`. Unchanged.
- The capability interfaces in `db/dao/Dao.kt` (`Findable`, `Listable`) and the
  `SqlSessionQueries` scaffolding. Unchanged; the new DAO methods compose them.
- The coaching-memory tables from migration `0019` and their models/Id types.
  Unchanged.
- No new library, no migration, no config key.

## Tests

### DAO tests (extend existing files)

`ClaimsDaoTest`:

- `list` returns a page ordered by `row_created_at, id`; respects `limit`/
  `offset`; `limit + 1` probe shape works (insert 3, `list(2, 0)` → 2,
  `list(2, 2)` → 1).
- `listByStudent` returns all statuses for the student (seed an `active`, a
  `retracted`, and a `superseded` claim → all three returned), excludes another
  student's claims, ordered `created_at, id`, bounded by `limit`/`offset`.

`ObservationsDaoTest`:

- `findById` returns the row for a known id; `NotFoundException` for an unknown
  id.
- `list` paging and ordering by `id`.
- bounded `listByStudent(id, limit, offset)` returns only that student's rows,
  bounded and ordered; the existing unbounded `listByStudent` still passes.

`ExtractionRunsDaoTest`:

- `findById` hit and miss.
- `list` paging/ordering.
- `listByStudent` returns the student's runs (seed one `applied`, one `failed`)
  with all token/count columns intact, bounded and ordered, excluding another
  student's runs.

`ClaimSupportDaoTest`:

- `listClaimsForObservation` returns every claim linked to the observation (link
  an observation to two claims → both returned), excludes claims linked only to
  other observations, returns empty for an unlinked observation. Verify it is
  the exact reverse of `listObservationsForClaim` on the same fixture.

### Resource / route tests (new files, mirroring `SessionsResourceTest`)

Each of `ClaimsResourceTest`, `ObservationsResourceTest`,
`ExtractionRunsResourceTest`:

- `GET /{slug}` (authenticated) → 200, list table contains a seeded row, omits
  `inList = false` columns.
- `GET /{slug}/{id}` → 200, detail shows all fields including the
  `inList = false` ones.
- read-only proof: `GET /{slug}/new` → 404; `GET /{slug}/{id}/edit` → not-found;
  `POST /{slug}/{id}/delete` → not-found; no Edit/Delete/New affordance
  rendered.
- malformed id (`/{slug}/not-an-id`) → 404.
- unauthenticated request → redirected/401 per the admin gate (one case, as the
  existing resource tests do).

`ClaimsResourceTest` additionally: detail renders a "Supporting observations"
panel whose row links to `/observation/{linkedId}`.

`ObservationsResourceTest` additionally: detail renders a "Supported claims"
panel whose row links to `/claim/{linkedId}`.

### User-page integration (extend `UsersResourceTest` / `StudentsResourceTest`)

- A user whose student has claims/observations/extraction runs: `GET /user/{id}`
  renders the three nested tables in the student panel, each row linking to the
  canonical `/claim|observation|extraction-run/{id}` path.
- A user whose student has none: the three panels render empty (no error).
- The pre-existing student panel assertions (profile fields, version history)
  still pass.

### Fixtures

`AdminTestSupport` gains seeders: `seedConvo`, `seedConvoRequest` (FK parents
for observations/extraction_runs), `seedObservation`, `seedClaim`,
`seedExtractionRun`, `seedClaimSupport`. Each delegates to the corresponding DAO
inside `database.withConnection`, following the existing
`seedUser`/`seedStudent` shape. `resetDatabase`'s `TRUNCATE users CASCADE`
already reaches the new tables (all FK-chain to `students`/`convos`, themselves
CASCADE off `users`).

## Implementation Plan

1. **Add the read-only DAO methods.** Extend `ClaimsDao` (`list`,
   `listByStudent`), `ObservationsDao` (`findById`, `list`, bounded
   `listByStudent`), `ExtractionRunsDao` (`findById`, `list`, `listByStudent`),
   `ClaimSupportDao` (`listClaimsForObservation`). Declare the matching
   `Findable`/`Listable` supertypes where applicable.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`

2. **Test the DAO methods.** Add the cases in the four `*DaoTest` files per the
   Tests section.
   - Verify:
     `nix develop -c bin/test db --force --tests "ed.unicoach.db.dao.ClaimsDaoTest"`
   - Verify:
     `nix develop -c bin/test db --force --tests "ed.unicoach.db.dao.ObservationsDaoTest"`
   - Verify:
     `nix develop -c bin/test db --force --tests "ed.unicoach.db.dao.ExtractionRunsDaoTest"`
   - Verify:
     `nix develop -c bin/test db --force --tests "ed.unicoach.db.dao.ClaimSupportDaoTest"`

3. **Add the three descriptors.** Create `ClaimsResource`,
   `ObservationsResource`, `ExtractionRunsResource` with the fields, edges, and
   null write handlers specified above.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`

4. **Weave the student-panel tables.** Add `STUDENT_PANEL_LIMIT` and the three
   nested `EdgePanel.Table` panels to `StudentsResource.buildPanel`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`

5. **Register the descriptors.** Add the three to the `AdminRegistry` list in
   `Application.kt`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`

6. **Add fixtures and resource/route tests.** Extend `AdminTestSupport` with the
   seeders; add the three `*ResourceTest` files and the `UsersResourceTest`/
   `StudentsResourceTest` cases.
   - Verify: `nix develop -c bin/test admin-server --force`

7. **Full gate.** Run the Kotlin + ktlint gate.
   - Verify: `nix develop -c bin/test check --force`

## Files Modified

- `db/src/main/kotlin/ed/unicoach/db/dao/ClaimsDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/ObservationsDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/ExtractionRunsDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/ClaimSupportDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/ClaimsDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/ObservationsDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/ExtractionRunsDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/ClaimSupportDaoTest.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ClaimsResource.kt`
  (new)
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ObservationsResource.kt`
  (new)
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ExtractionRunsResource.kt`
  (new)
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/StudentsResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/Application.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/AdminTestSupport.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/ClaimsResourceTest.kt`
  (new)
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/ObservationsResourceTest.kt`
  (new)
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/ExtractionRunsResourceTest.kt`
  (new)
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/UsersResourceTest.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/StudentsResourceTest.kt`
