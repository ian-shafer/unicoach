# RFC 82: Versioned colleges + read-only admin browsing

## Executive Summary

`colleges` is a domain entity (`College` / `CollegeId` / `NewCollege` +
`CollegesDao`) but is unversioned, has no admin presence, and is reachable only
via `psql`. This RFC makes `colleges` a versioned reference entity — using the
house versioning mechanism (`enforce_versioning()` + a `colleges_versions`
table, the same pieces `users`/`students` use) composed **à la carte without
soft-delete** — and surfaces it as a read-only resource in the admin website,
with a version-history panel showing how a school's facts changed across
Scorecard ingests. This is the colleges-first slice that precedes the later
college-list entity; it does not add college-list.

The version writer is the Scorecard ingest upsert, not an OCC request path. The
`ON CONFLICT DO UPDATE` branch sets `version = colleges.version + 1` and fires
**only on an actual content change** (a tuple-level `IS DISTINCT FROM` over the
curated columns), so re-ingesting an unchanged row neither bumps the version nor
writes a history row. The upsert is restructured as a CTE that always returns
exactly one row (inserted, updated, or unchanged), preserving the DAO's one-row
contract and leaving the loader untouched.

Deletes are blocked by a new shared `prevent_delete()` BEFORE DELETE trigger
(neither existing guard fits: `prevent_physical_delete()` names a `deleted_at`
column absent here; `prevent_log_delete()` is log retention). To guard immutable
`id`/`created_at` on a table without the `row_*` audit-clock split,
`prevent_immutable_updates()` is narrowed to `id` + `created_at` only; a new
`prevent_physical_timestamp_update()` carries the `row_created_at` guarantee on
the five tables that have that column.

## Detailed Design

### Data Models

#### `colleges` table change (`db/schema/0023.version-colleges.sql`)

A `version` column plus the versioning/guard triggers are added to the existing
table from `db/schema/0015.create-colleges.sql`; no curated column changes.

- `ALTER TABLE colleges ADD COLUMN version INTEGER NOT NULL DEFAULT 1`. Existing
  rows become `version = 1`. The column is trigger-managed, never
  client-supplied (mirrors `users.version`).

Trigger set on `colleges` after this RFC (BEFORE unless noted; BEFORE triggers
fire in trigger-name order, so `00` delete-guard precedes `00a` immutable-guard
precedes `01` versioning):

| Trigger                                  | Event                 | Procedure                     | Origin   |
| ---------------------------------------- | --------------------- | ----------------------------- | -------- |
| `trigger_00_prevent_colleges_delete`     | DELETE                | `prevent_delete()`            | this RFC |
| `trigger_00a_prevent_immutable_updates`  | UPDATE                | `prevent_immutable_updates()` | this RFC |
| `trigger_01_enforce_colleges_versioning` | INSERT/UPDATE         | `enforce_versioning()`        | this RFC |
| `trigger_03_enforce_colleges_updated_at` | UPDATE                | `update_colleges_timestamp()` | RFC 67   |
| `trigger_04_log_college_version`         | INSERT/UPDATE (AFTER) | `log_college_version()`       | this RFC |

`colleges` deliberately has **no**
`trigger_00b_prevent_physical_timestamp_update` and **no** `deleted_at`: it
carries only logical `created_at`/`updated_at`, so there is no physical audit
clock to guard and no soft-delete column. The existing
`update_colleges_timestamp()` (touches only `updated_at`) is retained unchanged.

#### `colleges_versions` table (`db/schema/0023.version-colleges.sql`)

A version-history table recording every committed change to a `colleges` row,
mirroring `users_versions` (`db/schema/0001.create-users.sql`) minus the
`deleted_at` and `row_*` columns that `colleges` does not have.

```
CREATE TABLE colleges_versions (
  id                   UUID        NOT NULL REFERENCES colleges(id) ON DELETE RESTRICT,
  version              INTEGER     NOT NULL,
  -- every curated colleges column, same types/nullability as colleges:
  unit_id              INTEGER     NOT NULL,
  opeid                TEXT        NULL,
  name                 TEXT        NOT NULL,
  city                 TEXT        NOT NULL,
  state                TEXT        NOT NULL,
  region               SMALLINT    NULL,
  locale               SMALLINT    NULL,
  latitude             DOUBLE PRECISION NULL,
  longitude            DOUBLE PRECISION NULL,
  control              SMALLINT    NOT NULL,
  undergrad_enrollment INTEGER     NULL,
  admission_rate       DOUBLE PRECISION NULL,
  sat_avg              INTEGER     NULL,
  cost_attendance      INTEGER     NULL,
  net_price            INTEGER     NULL,
  tuition_in_state     INTEGER     NULL,
  tuition_out_state    INTEGER     NULL,
  graduation_rate      DOUBLE PRECISION NULL,
  median_earnings      INTEGER     NULL,
  pct_pell             DOUBLE PRECISION NULL,
  website              TEXT        NULL,
  created_at           TIMESTAMPTZ NOT NULL,
  updated_at           TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (id, version)
);
```

No secondary index is added: `listVersions`'s `WHERE id = ? ORDER BY version` is
served by the leftmost prefix of the `(id, version)` primary key. (This differs
from `users_versions`, which indexes a _distinct_ `(id, updated_at)` tuple for
date-range timeline scans; an `(id, version)` index here would only duplicate
the PK.)

The FK is `ON DELETE RESTRICT` (mirrors `users_versions`); combined with
`prevent_delete()` on `colleges`, the history can never be orphaned. The table
carries no write guards of its own — it is written only by the AFTER trigger and
is never updated/deleted in normal operation; matching `users_versions`, which
also has none.

#### `College` model (`db/src/main/kotlin/ed/unicoach/db/models/College.kt`)

`College` gains `override val version: Int` and implements `Versioned` (from
`db/models/Entity.kt`) alongside its existing
`Identifiable`/`Created`/`Updated`. The class doc comment's "but no versioning
or soft-delete" is changed to state the row is versioned via a trigger-managed
`version` and history table, with no soft-delete. `NewCollege` is unchanged:
`version` is DB-managed, never client-supplied.

#### `CollegeVersion` model (`db/src/main/kotlin/ed/unicoach/db/models/CollegeVersion.kt`)

A new immutable snapshot of one `colleges_versions` row, mirroring
`UserVersion`:

```
data class CollegeVersion(
  override val id: CollegeId,
  override val version: Int,
  // every curated College field (unitId..website), same types
  override val createdAt: Instant,
  val updatedAt: Instant,
) : Identifiable<CollegeId>, Created, Versioned
```

### Shared trigger functions (`db/schema/0023.version-colleges.sql`)

New shared functions are defined in this migration (the first consumer), the
same pattern by which `update_colleges_timestamp()` was introduced in RFC 67 and
`prevent_log_delete()` in the convos migration.
`db/schema/0000.shared-functions.sql` is an applied migration and is immutable;
`prevent_immutable_updates()` is therefore redefined in place with
`CREATE OR REPLACE` here, which all existing triggers pick up by name with no
re-wiring.

- `prevent_delete()` — `RAISE EXCEPTION 'Deletions are blocked on this table.'`
  (`P0001`). Generic; honest for a versioned mutable entity that is neither
  soft-deletable nor a log.
- `log_college_version()` — AFTER INSERT/UPDATE; inserts `NEW`'s curated columns
  plus `version`, `created_at`, and `updated_at` into `colleges_versions`.
  Mirrors `log_user_version()`.
- `prevent_immutable_updates()` — **redefined** to guard `id` + `created_at`
  only (the immutable fields every entity has), dropping the static
  `row_created_at` reference. This makes it attachable to a table without the
  `row_*` split (`colleges`). Behavior for `id`/`created_at` is unchanged on all
  tables that already use it. The `db/schema/INVARIANTS.md` rule "`claims` MUST
  carry `prevent_physical_delete` + `prevent_immutable_updates`" stays literally
  satisfied — `claims` keeps the trigger — and the dropped `row_created_at` arm
  is preserved verbatim by `prevent_physical_timestamp_update()` on the same
  five tables, so no durable guarantee is weakened. `INVARIANTS.md` is
  human-gated and outside the implementation write-scope; this RFC flags the
  narrowing so the human gate can re-confirm the rule's wording (the function it
  names now covers `id`/`created_at` only).
- `prevent_physical_timestamp_update()` — **new**; `RAISE EXCEPTION` when
  `NEW.row_created_at IS DISTINCT FROM OLD.row_created_at` (`P0001`). Carries
  the `row_created_at` immutability guarantee that `prevent_immutable_updates()`
  no longer enforces, and is attached to the five tables that have the column.

The five tables currently attaching `prevent_immutable_updates()` and having
`row_created_at` — `users`, `sessions`, `students`, `convos`, `claims` — each
gain `trigger_00b_prevent_physical_timestamp_update` (BEFORE UPDATE) in this
migration so the loud `row_created_at` guarantee is preserved exactly. This is
the rationale for de-coupling rather than probing column presence at runtime:
each guard names exactly the columns it protects.

### Ingest upsert (`db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt`)

`upsert` is restructured so the conflict branch bumps the version only on a real
change and the statement always returns exactly one row.

```
WITH up AS (
  INSERT INTO colleges (unit_id, opeid, ..., website)
  VALUES (?, ?, ..., ?)
  ON CONFLICT (unit_id) DO UPDATE SET
    opeid = EXCLUDED.opeid, ..., website = EXCLUDED.website,
    version = colleges.version + 1
  WHERE (colleges.opeid, colleges.name, ..., colleges.website)
        IS DISTINCT FROM
        (EXCLUDED.opeid, EXCLUDED.name, ..., EXCLUDED.website)
  RETURNING *
)
SELECT * FROM up
UNION ALL
SELECT * FROM colleges WHERE unit_id = ? AND NOT EXISTS (SELECT 1 FROM up)
```

The `IS DISTINCT FROM` compares **only the 21 curated columns** as a row-tuple.
A whole-row `colleges IS DISTINCT FROM EXCLUDED` would be unconditionally true —
`EXCLUDED.id` is a fresh `uuidv7()`, `EXCLUDED.version` is `1`, and
`EXCLUDED.created_at`/`updated_at` are `NOW()` — so it would defeat the no-op
skip and bump the version on every ingest. The tuple compare fixes that.

When the `WHERE` is unsatisfied (unchanged row) the `DO UPDATE` performs no
write and `RETURNING` yields zero rows; the `UNION ALL` arm then returns the
existing row. The conflict guarantees the row exists, so exactly one row is
always returned. The bound `unit_id` parameter appears twice (INSERT VALUES and
the UNION arm). `enforce_versioning()` is satisfied in every path: INSERT
defaults `version = 1`; a real UPDATE sets `colleges.version + 1`; an unchanged
row never updates.

`CollegeScorecardLoader` (`college/...`) is unchanged: it consumes the upsert
result only as success/failure and never reads the returned `College` fields, so
returning the unchanged row counts the row as loaded exactly as before. The load
count does not distinguish "unchanged" from "changed" (YAGNI; the existing
summary is sufficient).

### DAO read surface (`db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt`)

`CollegesDao` (currently a bare `object`) composes three existing capability
interfaces from `db/dao/Dao.kt`; `colleges` has no soft-delete, so it uses the
no-scope variants:

```
object CollegesDao :
  Findable<College, CollegeId>,
  Listable<College>,
  VersionHistory<CollegeId, CollegeVersion>
```

- `findById(session, id): Result<College>` —
  `SELECT * FROM colleges WHERE id = ?`; `NotFoundException` on no row (per
  `Findable`). Distinct from the existing nullable `findByUnitId`, which is
  unchanged.
- `list(session, limit, offset): Result<List<College>>` —
  `SELECT * FROM colleges ORDER BY name, unit_id LIMIT ? OFFSET ?`. `unit_id` is
  unique, so the order is total/deterministic for count-free paging.
- `listVersions(session, id): Result<List<CollegeVersion>>` —
  `SELECT * FROM colleges_versions WHERE id = ? ORDER BY version`. Unpaged, per
  the `VersionHistory` interface and `UsersDao.listVersions`: one college's
  history is bounded by the number of ingests that changed that single row.

`mapCollege` reads the new `version` column; a new `mapCollegeVersion` maps a
`colleges_versions` row. The existing `upsert`/`upsertProgram`/`search` and the
error mapper are otherwise undisturbed. The class doc comment ("The tables carry
no version column, so the upserts carry no optimistic-concurrency guard") is
updated to describe `colleges` as versioned via the trigger-managed `version`
that the upsert bumps on a real change; `college_programs` remains unversioned
(out of scope).

### Admin resource (`admin-server/.../resources/CollegesResource.kt`)

A new read-only descriptor, following the `ExtractionRunsResource` read-only
pattern and the RFC 79 display conventions (per
`admin-server/.../engine/SPEC.md`).

- `object CollegesResource : AdminResource<College, CollegeId>`,
  `slug =
  "college"`, `title = "College"`, `kind = AdminKind.ENTITY`,
  `topLevel = true`. `kind` is the honest classification — `colleges` is a
  mutable versioned entity — while read-only is expressed through handler
  nullability (the engine branches on handler nullability, not `kind`):
  `create`, `update`, `delete`, `undelete` are all `null`. `isDeleted` is always
  `false`; `list`/`get` ignore `scope`/`includeDeleted` (no `deleted_at`).
- `list` → `CollegesDao.list`; `get` → `CollegesDao.findById`. Builds no SQL.
- Fields: every curated column plus `version`, `createdAt`, `updatedAt`, all
  `editable = false`. The `id` field sets `refSlug = "college"`. List columns
  (`inList = true`): `name`, `city`, `state`, `control`, `admissionRate`,
  `netPrice`; all other fields `inList = false` (the table is large — the list
  stays narrow, the detail page shows the full row).
- `edges = listOf(AdminEdge.History("Version history"))`. `resolveEdges` builds
  one `EdgePanel.Table` from `CollegesDao.listVersions`, the same shape the user
  detail page uses for `users_versions` (`UsersResource.resolveEdges`): columns
  for `version` + the curated fields + `updated_at`, typed per RFC 79.
- Registered in the `AdminRegistry` list in
  `admin-server/.../admin/Application.kt`.

`college_programs` is **not** surfaced as a `HasMany` edge in this RFC. There is
no `college_programs` admin descriptor/slug for a child row's id-link glyph to
target; programs-per-college is large (an inline table would need its own
paging); and programs are orthogonal to the "how did this school's facts change"
versioning story this RFC delivers. Deferred per YAGNI until there is a
demonstrated need to browse programs in admin.

### Error Handling / Edge Cases

- **Physical delete on `colleges`**: blocked by `prevent_delete()` (`P0001`).
  `college_programs.college_id` is `ON DELETE CASCADE`, but the cascade can
  never fire because the parent delete is rejected first — consistent with
  deletes being blocked.
- **No-op re-ingest**: returns the unchanged row, no version bump, no history
  row (the `RETURNING`/`UNION ALL` path above).
- **Stale `version` on ingest**: not possible — the upsert sets
  `version = colleges.version + 1` from the current row inside the statement;
  there is no client-supplied version to conflict.
- **`get` / `findById` on an unknown id**: `NotFoundException` → the engine's
  not-found page (read-only routes still 404 correctly).
- **Write routes on `college`**: `create`/`update`/`delete`/`undelete` are
  `null`, so the engine registers no create routes and the unconditionally
  registered edit/delete/undelete routes re-check nullability and return the
  not-found page at request time.

### Dependencies

No new libraries. Schema migration `0023` depends on the existing shared
functions in `0000` and the `colleges` table from `0015`. The admin resource
depends only on `:db` (`CollegesDao`, `College`, `CollegeId`, `CollegeVersion`)
and the existing admin engine. No chat or iOS changes.

## Tests

### `db/src/test/kotlin/ed/unicoach/db/dao/CollegesDaoTest.kt`

Real-DB tests (the suite already truncates `colleges, college_programs` per
test).

- **`upsert inserts at version 1 and logs one history row`**: a fresh `upsert`
  returns `version == 1`; `listVersions(id)` returns exactly one row at
  `version == 1` matching the inserted values.
- **`upsert of changed content bumps version and logs a second history row`**:
  upsert the same `unit_id` with a changed curated field (e.g. `name`); the
  returned college has `version == 2`; `listVersions` returns two rows
  (`version` 1 then 2) and the v2 row carries the new value.
- **`re-upsert of identical content is a no-op`**: upsert the same `NewCollege`
  twice; the second call succeeds and returns `version == 1` (no bump);
  `listVersions` still returns exactly one row. Proves the tuple
  `IS DISTINCT
  FROM` skip and the `UNION ALL` row-return.
- **`upsert preserves id and created_at across a change`**: id and `createdAt`
  from the first upsert equal those after a content-changing upsert.
- **`findById returns the row, or NotFoundException when absent`**: a seeded id
  returns the college; a random id yields `NotFoundException`.
- **`list pages name-stable with limit and offset`**: seed several colleges;
  assert ordering by `name, unit_id` and that `limit`/`offset` page without
  overlap.
- **`listVersions orders ascending by version`**: after three content changes,
  versions are `[1,2,3,4]` in order.
- **`physical delete on colleges is rejected`**: a raw `DELETE FROM colleges`
  raises (the `prevent_delete()` trigger).
- **`updating id or created_at on colleges is rejected`**: raw `UPDATE`s setting
  `id` and `created_at` each raise (`prevent_immutable_updates()` on a table
  without `row_created_at` — proves the de-coupled guard works there).

### `db/src/test/kotlin/ed/unicoach/db/dao/StudentsDaoTest.kt` (regression, unedited)

The existing `expectImmutableFailure("row_created_at", ...)` assertion
(StudentsDaoTest.kt) is the regression guard that the split preserved the loud
`row_created_at` guarantee: it asserts only that an `SQLException` is thrown,
now raised by `prevent_physical_timestamp_update()` instead of
`prevent_immutable_updates()`. No edit required; it must remain green.

### `college/src/test/kotlin/ed/unicoach/college/CollegeScorecardLoaderTest.kt`

- **Extend `re-running the loader is idempotent`**: after the second identical
  `load`, assert every college is still `version == 1` and `colleges_versions`
  holds exactly one row per college (the loader's no-op path bumps nothing). The
  existing `collegesLoaded == 5` assertion is retained (unchanged rows still
  count as loaded).
- **New `re-ingesting a changed institution bumps version and logs history`**:
  load institutions, then load a second file with one institution's `name`
  changed for the same `unit_id`; assert that college is `version == 2` and its
  `colleges_versions` has two rows, while untouched colleges stay at
  `version == 1`.

### `admin-server/src/test/kotlin/ed/unicoach/admin/resources/CollegesResourceTest.kt`

Mirrors `ExtractionRunsResourceTest` (read-only resource), using
`AdminTestSupport`.

- **`list shows the configured columns and a nav entry`**: `GET /college`
  renders a seeded college's
  `name`/`city`/`state`/`control`/`admission_rate`/`net_price` and the dashboard
  lists the College section; an `inList = false` column header (e.g. "OPEID") is
  absent from the list.
- **`detail shows the full row and the version-history panel`**:
  `GET
  /college/{id}` renders the full field table including `version`, and a
  "Version history" panel with the seeded row's version(s).
- **`resource is read-only`**: `GET /college/new` and `GET /college/{id}/edit`
  return the not-found page; `POST /college`, `POST /college/{id}/delete`, and
  `POST /college/{id}/undelete` return the not-found page (no
  create/edit/delete).
- **`unknown id is a not-found page`**: `GET /college/{random-uuid}` →
  not-found.

## Implementation Plan

Each step is independently buildable/testable. Tests run via
`nix develop -c bin/test ...` per the project harness; admin/ios tests excepted
where noted.

1. **Write migration `0023.version-colleges.sql`.** Add, in order: the shared
   functions (`prevent_delete`, `log_college_version`,
   `prevent_physical_timestamp_update`, and the `CREATE OR REPLACE` of
   `prevent_immutable_updates` narrowed to `id` + `created_at`);
   `ALTER TABLE colleges ADD COLUMN version`; `CREATE TABLE colleges_versions` +
   index; backfill one v1 row per existing college **before** the log trigger;
   the four `colleges` triggers; and
   `trigger_00b_prevent_physical_timestamp_update` on `users`, `sessions`,
   `students`, `convos`, `claims`.
   - Verify: `nix develop -c bin/db-reset` (or the project's migrate path)
     applies cleanly; `nix develop -c psql ... -c '\d colleges'` shows the
     `version` column and the five triggers; `\d colleges_versions` shows the
     table.

2. **Add `version` to `College` and create `CollegeVersion`.** Implement
   `Versioned`, update the doc comment; add `CollegeVersion.kt`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`.

3. **Update `CollegesDao`.** Add the capability-interface supertypes; rewrite
   `upsert` as the CTE with the curated-tuple `IS DISTINCT FROM` and
   `version = colleges.version + 1`; add `findById`, `list`, `listVersions`,
   `mapCollegeVersion`; have `mapCollege` read `version`; refresh the class doc.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`.

4. **Write `CollegesDaoTest` and extend the loader test.** Implement every DAO
   test above; extend `re-running the loader is idempotent` and add the
   changed-institution test.
   - Verify:
     `nix develop -c bin/test -f db --tests "ed.unicoach.db.dao.CollegesDaoTest"`
     and `... college --tests "ed.unicoach.college.CollegeScorecardLoaderTest"`;
     confirm "N executed" (not all-cached). Also run
     `... db --tests "ed.unicoach.db.dao.StudentsDaoTest"` to confirm the
     `row_created_at` regression stays green.

5. **Add `CollegesResource` and register it.** Declare the descriptor; add it to
   the `AdminRegistry` list in `Application.kt`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`.

6. **Extend `AdminTestSupport` and write `CollegesResourceTest`.** Add a
   `seedCollege` helper and a `colleges` truncate to `resetDatabase`; implement
   the admin tests above.
   - Verify (system `xcodebuild`-independent; admin is a JVM module):
     `nix develop -c bin/test admin-server --tests "ed.unicoach.admin.resources.CollegesResourceTest"`.

7. **Full gate.** Run the Kotlin + ktlint pre-commit gate.
   - Verify: `nix develop -c bin/test check`.

## Files Modified

- `db/schema/0023.version-colleges.sql` — **new**. The migration: shared
  functions (`prevent_delete`, `log_college_version`,
  `prevent_physical_timestamp_update`, redefined `prevent_immutable_updates`),
  `colleges.version` column, `colleges_versions` table + backfill, `colleges`
  triggers, and `trigger_00b` on
  `users`/`sessions`/`students`/`convos`/`claims`.
- `db/src/main/kotlin/ed/unicoach/db/models/College.kt` — add `version`,
  implement `Versioned`, update the doc comment.
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeVersion.kt` — **new** version
  snapshot model.
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt` — capability-interface
  supertypes; CTE upsert with version bump + no-op skip; `findById`, `list`,
  `listVersions`, `mapCollegeVersion`; `mapCollege` reads `version`; class doc.
- `db/src/test/kotlin/ed/unicoach/db/dao/CollegesDaoTest.kt` — new versioning,
  read, no-op, blocked-delete, and immutable-update tests.
- `college/src/test/kotlin/ed/unicoach/college/CollegeScorecardLoaderTest.kt` —
  extend the idempotency test with version assertions; add the
  changed-institution re-ingest test.
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/CollegesResource.kt`
  — **new** read-only descriptor with the History edge.
- `admin-server/src/main/kotlin/ed/unicoach/admin/Application.kt` — register
  `CollegesResource` in the `AdminRegistry` list.
- `admin-server/src/test/kotlin/ed/unicoach/admin/AdminTestSupport.kt` — add a
  `seedCollege` helper and a `colleges` truncate in `resetDatabase`.
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/CollegesResourceTest.kt`
  — **new** admin list/detail/read-only/history tests.
