# RFC 248 — One place for permissions

**Slice:** `beyond/10/one-place-for-permissions` (brief 0011, "beyond money").
**Lane:** A (rfc) — a new table and a new REST surface. **Base:**
`main@4336f7dc`.

One modelled place for what a student allows others to see of their own data.
The first permission it holds is `intended_subjects` — "people I share with may
see what I want to study" — whose first reader will be the Family Cost Report
(brief 0011 D18/D31). This slice is **deliberately invisible**: it adds the
permission, its history and the student's own API to grant and withdraw it, and
NOTHING reads it yet. No page, no chat answer, no screen renders differently
after this lands. Reachability is `beyond/07/the-report-weave`'s job — that
slice reads the permission when it renders the report.

## What the code says (measured at design time, 2026-10-10)

Two read-only research passes over `main@4336f7dc`; findings with `path:line`
citations in `.scratch/ship/rfc-248/research/`. The load-bearing facts:

| #  | Fact                                                                                                                                                                                                                                                                                                                                                                                                            |
| -- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| P1 | **Nothing models a permission today.** No table, column or enum anywhere grants another person sight of a student's data. `claims.visibility` is about what the coach shows the STUDENT (`0019.create-coaching-memory.sql:70,84`).                                                                                                                                                                              |
| P2 | **The report's reader is an anonymous token holder.** `ServiceCostReportSource.kt:64-82` resolves token -> `cost_report_shares.student_id` -> the live cost profile in ONE session. The link is multi-use and forwardable and carries no reader identity (brief 0014 X3/X12). So a permission over what the report shows is keyed on the **student**, never on a share row or a supporter link.                 |
| P3 | **The report is live, not a snapshot** (spec C12). A permission therefore gates the RENDER, and must be readable inside the report's one session — `beyond/07` needs a read that takes a `SqlSession`.                                                                                                                                                                                                          |
| P4 | **Parents can now hold accounts** (`supporters`, `supporter_student_links`, RFC 221/232/241), but no code path lets a supporter read any student data yet (`SupporterResponses.kt:20-39`; `family/05/the-subject-seam` is unbuilt). Brief 0014 D4(b) makes a student's college list visible to linked parents BY DEFAULT — a different rule from this slice's "withheld by default". See §6.                    |
| P5 | **The shape worth reusing** is the per-student versioned entity: uuidv7 key, `version`, the four timestamps, `deleted_at`, a `*_versions` table, the shared triggers by name (`00`/`00a`/`00b`/`01`/`03`) plus a table-own column guard (`00c`) and history writer (`04`). Newest instance: `supporters-and-student-links.sql`; nearest in meaning: `intended_subject_status` (`intended-subjects.sql:40-277`). |
| P6 | **`enforce_versioning` does not increment for you** — every UPDATE sets `version = version + 1` or fails 40001 (`0000.shared-functions.sql:17-32`). `IntendedSubjectsDao.writeStatus` suppresses a write that changes nothing so the history only records real changes (`IntendedSubjectsDao.kt:270-298`); `MoneyProfilesDao` always bumps. A grant/withdraw toggle wants the former.                           |
| P7 | **The student's doors today:** REST `GET`/`PUT` on `/api/v1/students/me/money-profile` and `/api/v1/students/me/intended-subjects` behind `SessionCallerResolution` (401 / 409 `student_profile_required`), iOS "Your details" (calls those routes), and chat tools. The web has no settings page. The iOS design system has no on/off control.                                                                 |
| P8 | **"withheld" is already a cost word.** `WithheldReason`, `NetPrice.Withheld`, `BlendedFigureApplicability.WITHHELD` live on `CostReportPage` — the page `beyond/07` will gate. A permission state spelled `withheld` would sit beside an unrelated `Withheld` in that file. The stored vocabulary below avoids it; the product docs' word "withheld" still describes the default in prose.                      |

## Detailed Design

### 1. A permission is a row, named by a closed list

```sql
CREATE TABLE sharing_permissions (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    version        INTEGER     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ NULL,

    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    permission TEXT NOT NULL,
    status     TEXT NOT NULL,

    CONSTRAINT sharing_permissions_permission_check
        CHECK (permission IN ('intended_subjects')),
    CONSTRAINT sharing_permissions_status_check
        CHECK (status IN ('granted', 'refused'))
);

CREATE UNIQUE INDEX sharing_permissions_student_permission_active_idx
    ON sharing_permissions (student_id, permission) WHERE deleted_at IS NULL;
```

plus `sharing_permissions_versions` (every live column, no CHECKs,
`PRIMARY KEY
(id, version)`,
`id REFERENCES sharing_permissions(id) ON DELETE RESTRICT`, an
`(id, updated_at)` index), a table-own
`prevent_sharing_permission_column_updates()` freezing `student_id` and
`permission` (trigger `00c`), a table-own `log_sharing_permission_version()`
(trigger `04`), and the shared triggers `00`/`00a`/`00b`/`01`/`03` by name —
`intended_subject_status` verbatim. No shared function is added or redefined.

Why each choice:

- **One row per (student, permission), not one column per permission.** D35 asks
  for ONE place that holds every future permission. A column per permission (the
  `money_profiles` shape) makes each new permission an `ALTER TABLE` plus a
  `CREATE OR REPLACE` of the history writer, and makes one row's history
  interleave unrelated consents. A row per permission gives each consent its own
  history, and a new permission costs one widened CHECK and one Kotlin enum
  entry.
- **Named and explicit, never a key-value pair** (spec). `permission` is
  `TEXT` + one named CHECK + exactly one Kotlin
  `enum class SharingPermission(val
  value: String)` with `fromValue`
  (CLAUDE.md schema conventions). A permission nobody reviewed cannot be
  written: the database refuses it and the enum cannot name it. Widening the
  list is a migration, so it is reviewed at a /ship gate every time.
- **A permission names the DATA, not where it shows** (Ian, at the RFC 248 gate,
  2026-10-10: "If one can see them on cost reports, why not see them
  elsewhere?"). The first permission is `intended_subjects`: "people I share
  with may see what I want to study". Every surface that shows the student's
  data to someone else — today the Family Cost Report; later, if they read
  subjects, the parent-account slices — honours the same answer, so a student
  answers once per kind of data, not once per page. The slice text says
  "majors"; the stored data is the student's intended subjects from our own
  taxonomy (D38, RFC 205), so the name says subjects. Consequence for the
  consumer: the words `beyond/07` uses to ask (D18) must say who can see the
  subjects in general, not only "on the report", because a grant given there is
  the grant every later surface reads.
- **Default withheld = no row.** A student who has never answered has no row,
  and "no row" reads as not granted. Silence is not consent (D18).
- **Two stored states, `granted` and `refused`, and the absence of a row as the
  third.** The consumer (`beyond/07`) must tell the student before the first
  share that includes their subjects (D18). To ask once and not nag, it needs to
  know "never asked" from "said no". Both read as not granted. The stored words
  avoid `withheld` (P8) and avoid `answer_status` — that domain is a question's
  vocabulary (`declined` is a refusal to ANSWER, not a refusal to allow).
- **Reversible both ways, forever.** `granted` <-> `refused` is an ordinary
  versioned UPDATE. Nothing is set once (unlike `cost_report_shares.revoked_at`)
  and nothing is append-only (unlike `share_events` `opted_out`).
- **`student_id` and `permission` are frozen** (`00c`). A row is one student's
  answer to one question; moving it to another student or another permission
  would rewrite history.

### 2. Kotlin: one vocabulary, one read the report can use in its session

`:db`:

- `enum class SharingPermission(val value: String)` —
  `INTENDED_SUBJECTS("intended_subjects")`.
- `enum class SharingPermissionStatus(val value: String)` — `GRANTED`,
  `REFUSED`.
- `SharingPermissionRow` (id, version, timestamps, student id, permission,
  status) and a `SharingPermissionsDao` object with:
  - `findLive(session, studentId, permission): Result<SharingPermissionRow?>`
  - `listLiveForStudent(session, studentId): Result<List<SharingPermissionRow>>`
  - `set(session, studentId, permission, status): Result<SharingPermissionRow>`
    — insert-or-update in one transaction:
    `INSERT ... ON CONFLICT (student_id,
    permission) WHERE deleted_at IS NULL DO NOTHING`,
    then
    `UPDATE ... SET
    status = ?, version = version + 1 WHERE ... AND status IS DISTINCT FROM ?`
    (suppress-unchanged, P6), then return the live row. Exactly
    `IntendedSubjectsDao`'s pattern.
  - `listVersions(session, id)` for tests and history.
  - An unknown stored `permission` or `status` string is
    `CorruptPersistedValueException`, never softened (the `MoneyProfilesDao`
    rule).

`:service`, a `SharingPermissionService(database)`:

- `suspend fun read(studentId): Result<Map<SharingPermission, SharingPermissionStatus?>>`
  — one entry per enum entry; `null` means never answered.
- `isGranted(session: SqlSession, studentId, permission): Boolean` — the read
  `beyond/07` calls inside the report's one session (P3). Only `GRANTED` is
  true. Not a `Result`: it throws on a failed read, so the caller's
  `withConnection` rolls back (the `CollegeCostService.readInSession`
  precedent).
- `suspend fun set(studentId, permission, status): Result<SharingPermissionStatus>`.

Nothing calls `isGranted` in this slice. It is the consumer's seam, tested here
so the consumer inherits a proven read rather than writing its own.

### 3. The student's own door: `GET`/`PUT /api/v1/students/me/sharing-permissions`

A sibling of `/api/v1/students/me/intended-subjects`, behind the same
`SessionCallerResolution` ladder (401 unauthenticated; 409
`student_profile_required` for an account with no student profile). No student
id on the wire.

- `GET /api/v1/students/me/sharing-permissions` — always 200, never 404 (the
  intended-subjects precedent: the answer is never absent, only not asked):

  ```json
  {
    "permissions": [
      { "permission": "intended_subjects", "status": "not_asked" }
    ]
  }
  ```

  One entry per `SharingPermission` entry, in enum order. Wire `status` is
  `not_asked | granted | refused`.

- `PUT /api/v1/students/me/sharing-permissions/{permission}` with body
  `{ "status": "granted" | "refused" }` — 200 with the single entry as it now
  stands. An unknown `{permission}` is 404 `not_found`; a missing, unknown or
  `not_asked` status is 400 (a student cannot write "never asked"; withdrawing
  is `refused`). Re-sending the current status is a 200 that writes nothing.

Both operations are published in `api-specs/openapi.yaml` with a coupling test,
like `OpenApiIntendedSubjectsTest`.

**What "reversible from the student's own surface" means here:** the
authenticated student's own `/me` API. No screen and no chat tool change in this
slice (gate decision 2). RFC 134 is the precedent: it landed the money-profile
`GET`/`PUT` before RFC 171 built the screen. A switch on "Your details" now
would be a control with no effect — a student turns it on and the report does
not change — and would move pixels in three existing iOS snapshot scenes. The
switch, and the coach's words at the share moment (D18), belong to `beyond/07`,
which makes the permission do something. This handover is recorded in the close
out against `beyond/07`'s slice text.

### 4. The three existing shapes: a recorded note, not a migration path

`cost_report_shares.revoked_at`, `share_events` kind `opted_out` and the
`money_profiles` status columns are **not changed**. None of them is a
permission over what another person sees: `revoked_at` retires a credential,
`opted_out` silences a nudge to the student, and the `money_profiles` statuses
record answers to questions. Converging them is a separate decision (brief 0011
D35 evidence paragraph); this RFC records them as candidates and gives no
migration path, because a path written now would be a design for a decision
nobody has made.

### 5. Deliberately invisible

After this lands, no user-visible surface changes: no `public-web` file, no
`CostReportPage` change, no prompt, no chat tool, no iOS file. The report keeps
rendering exactly as today whatever the table holds, because nothing reads it.
The only new behaviour is the REST pair in §3.

### 6. Out of scope, stated: per-parent consents

A permission here is one answer per student per kind of data, and it covers
everyone the student shares with: today, anyone holding the report link; later,
any linked parent account whose slice shows that data. It does not build
per-parent answers ("my mum may see this, my uncle may not"). No parent account
can read any student data yet (P4), so per-parent rules would be designed
without a reader. If `family/04` / `family/05` later want them, they extend this
place (for example a nullable link column, with the unique index widened) by
their own RFC and gate. Nothing here forecloses it, and nothing here builds it
(gate decision 3).

This does not collide with brief 0014 D4(b), which makes the college LIST
visible to linked parents by default: that is a different kind of data with its
own rule, owned by `family/04` / `family/05`. Intended subjects stay withheld
until the student grants this permission, on every surface.

## Files Modified

New:

- `db/schema/create-sharing-permissions.sql` — the table, the versions table,
  the two table-own functions, seven triggers. Plus its line in
  `db/schema/ORDER` (re-placed by `ship-order` at land).
- `db/src/main/kotlin/ed/unicoach/db/models/SharingPermission.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/SharingPermissionStatus.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/SharingPermissionRow.kt` (and an id
  value class if the DAO conventions require one)
- `db/src/main/kotlin/ed/unicoach/db/dao/SharingPermissionsDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/SharingPermissionsDaoTest.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/SharingPermissionService.kt`
  (package placement follows `IntendedSubjectsService`)
- `service/src/test/kotlin/ed/unicoach/coaching/SharingPermissionServiceTest.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/SharingPermissionRoutes.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/SharingPermissionModels.kt`
  (request + response DTOs, and `SharingPermissionStatusView` — the closed
  `not_asked | granted | refused` wire enum, mapped exhaustively from the stored
  status)
- `rest-server/src/test/kotlin/ed/unicoach/rest/SharingPermissionsRoutingTest.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/models/OpenApiSharingPermissionsTest.kt`

Edited:

- `db/schema/ORDER` — one appended line.
- `db/src/main/kotlin/ed/unicoach/db/dao/SqlSessionQueries.kt` — `execute(...)`
  gains an optional `mapError` parameter (default unchanged), so the DAO's
  `INSERT ... ON CONFLICT DO NOTHING` maps a missing student by constraint name.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt` — build and register
  the handler.
- `rest-server/src/test/kotlin/ed/unicoach/rest/E2eSupport.kt` — shared
  `registerVerifiedUser` / `registerStudentSession` test fixtures (review: the
  new routing test would otherwise have been the tenth private copy); older
  suites are not migrated here.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — construct the
  service and pass it in.
- `api-specs/openapi.yaml` — the two operations and their schemas.
- Any test-database truncation list that enumerates student child tables (for
  example `service/src/test/kotlin/ed/unicoach/coaching/costs/CostsTestDb.kt`)
  where a missing entry would leak rows between tests.

Not touched: `public-web/**`, `ios-app/**`, `prompts/**`, `cost_report_shares`,
`share_events`, `money_profiles`, `.claude/**`.

## Implementation Plan

1. Migration `create-sharing-permissions.sql` + `ORDER` line, modelled on
   `intended-subjects.sql`'s `intended_subject_status` block (header comment in
   the `supporters-and-student-links.sql` style, stating that nothing reads the
   table yet).
2. `:db` enums, row model, DAO (suppress-unchanged write, corrupt-value refusal,
   constraint-name error mapping as `MoneyProfilesDao`).
3. DAO test: triggers and invariants directly (below).
4. `:service` `SharingPermissionService` + test.
5. `:rest-server` route handler, DTOs, `Routing.kt` + `Application.kt` wiring,
   `openapi.yaml`, routing test, OpenAPI coupling test.
6. `nix develop -c bin/test` (full suite, no module, no `-f`) through
   `ship-run -g`.

## Tests

`SharingPermissionsDaoTest` (`IntendedSubjectsDaoTest` fixture pattern):

- A student with no row reads as no row; `listLiveForStudent` is empty.
- `set(GRANTED)` inserts version 1 with one history row; `set(REFUSED)` moves it
  to version 2 with two history rows; `set(GRANTED)` again to version 3 —
  reversible both ways.
- `set` with the current status writes nothing: version and history count
  unchanged.
- The database refuses an unknown `permission` and an unknown `status` by
  constraint name (`sharing_permissions_permission_check`,
  `sharing_permissions_status_check`).
- A second live row for the same (student, permission) is refused by
  `sharing_permissions_student_permission_active_idx`.
- Physical DELETE is refused; UPDATE of `student_id` or `permission` (with
  `version = version + 1`, so only the column guard can refuse it) is refused.
- An UPDATE that does not bump the version fails with 40001.
- A stored row with a status string outside the enum is reported as corruption,
  not read as not granted (bypass the CHECK in the test by disabling it inside a
  rolled-back transaction, or assert the mapper directly — whichever the house
  tests already do).
- A stored row with a permission string outside the enum is reported as
  corruption too, through `listLiveForStudent` (the only read that does not
  filter on a known permission), never skipped.

`SharingPermissionServiceTest`:

- `read` returns every enum entry, `null` for never answered.
- `isGranted` is false for no row, false for `REFUSED`, true for `GRANTED`.
- `isGranted` reads in the caller's session: a grant written and not yet
  committed in that session is visible to it (P3 — the report decides from its
  own snapshot).
- Two students are independent.

`SharingPermissionsRoutingTest`:

- 401 unauthenticated; 409 `student_profile_required` for a user with no
  student.
- `GET` with nothing stored is 200 with `not_asked`.
- `PUT granted` then `GET` shows `granted`; `PUT refused` then `GET` shows
  `refused`; `PUT granted` again shows `granted`.
- Unknown permission in the path is 404; a missing, unknown or `not_asked`
  status is 400 and writes nothing.
- One student's `PUT` does not change another student's `GET`.

`OpenApiSharingPermissionsTest`: both operations are published.

No test asserts the report is unchanged: the report reads nothing new, and the
existing `CostReportPage` tests already pin its output; a green suite with no
`public-web` file in the diff is that evidence.

## Rollback

Revert the code commit. The table is new and nothing reads it, so dropping it in
a follow-up migration loses only consents that nothing yet honours. No existing
table, column or behaviour changes.
