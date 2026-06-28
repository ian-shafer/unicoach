# RFC 81: Admin conversation views

## Executive Summary

The coaching-conversation tables (`convos`, `convo_requests`, `convo_responses`)
have no admin surface: a stored conversation and its turns can only be inspected
in `psql`. Observations and extraction runs already link to conversation rows by
foreign key, but those id cells render as plain text because no `convo` /
`convo-request` resource exists for RFC 79's link mechanism to target.

This RFC adds two read-only `AdminResource` descriptors to the descriptor-driven
admin engine:

- **`convo`** (kind `ENTITY`): a conversation's fields plus a panel of its
  turns.
- **`convo-request`** (kind `LOG`): one request _and its paired reply_. The row
  is a `ConvoTurn` (request + 1:1 `convo_responses` row), so the reply's
  content, stop reason, token counts, and latency render on the request's detail
  page. Nothing foreign-keys a `convo_responses` row, so no separate response
  resource is added; the verbatim provider payload (`convo_responses_raw`) is
  out of scope.

Both are top-level (the engine registers a `/{slug}/{id}` detail route only for
top-level resources) and read-only, matching the RFC 77 coaching-memory views.

Conversations become reachable from: the student detail page (a new
**Conversations** panel), `observations` (`convo_id`, `source_request_id`),
`extraction_runs` (`convo_id`, `through_request_id`), and `claims` (via the
observation that bridges a claim to its conversation). The render layer gains
pretty-printed JSON for `FieldType.JSON` so opaque message content is legible.

No schema migration: these are read views over existing tables.

## Detailed Design

### Data Models

No new database tables, columns, or domain types. The design reads existing
tables (`convos`, `convo_requests`, `convo_responses`) through existing domain
models (`Convo`, `ConvoWithActivity`, `ConvoTurn`, `ConvoRequest`,
`ConvoResponse`) and id types (`ConvoId`, `ConvoRequestId`).

A conversation's turns are
`ConvoTurn(request: ConvoRequest, response:
ConvoResponse?)` — the response is
null for a mid-flight or failed turn. The `convo-request` resource's `ROW` type
is `ConvoTurn`; its `ID` type is `ConvoRequestId`. The `convo` resource's `ROW`
type is `ConvoWithActivity` (carrying the derived `lastActivityAt`); its `ID`
type is `ConvoId`.

### API Contracts

#### `ConvosDao` (`db/src/main/kotlin/ed/unicoach/db/dao/ConvosDao.kt`)

Four additive reads. Existing method signatures are unchanged except for two
trailing defaulted parameters on `listByStudentWithActivity`, so existing
callers (`CoachingService`, tests) are unaffected.

```kotlin
// Global, paginated convo list with derived last-activity, for the /convo list
// page. Ordered by c.created_at DESC, c.id. `scope` filters deleted_at; all
// archive states are returned (admin sees archived rows).
fun listWithActivity(
  session: SqlSession,
  scope: SoftDeleteScope,
  limit: Int,
  offset: Int,
): Result<List<ConvoWithActivity>>

// Global, paginated turn firehose for the /convo-request list page. One row per
// request, LEFT JOIN its 1:1 response. Ordered by r.id DESC (BIGINT IDENTITY is
// monotonic with insertion, and is the PK index, so no sort over a non-indexed
// column). `scope` filters the owning convo's deleted_at.
fun listTurns(
  session: SqlSession,
  scope: SoftDeleteScope,
  limit: Int,
  offset: Int,
): Result<List<ConvoTurn>>

// One turn by request id, for the /convo-request/{id} detail page. Request plus
// its paired response (null when none). NotFoundException when no request
// matches, or when the owning convo is excluded by `scope`.
fun findTurnByRequestId(
  session: SqlSession,
  requestId: ConvoRequestId,
  scope: SoftDeleteScope,
): Result<ConvoTurn>

// Existing method — add trailing defaulted limit/offset. limit = null preserves
// the current unbounded behaviour for existing callers; the student panel passes
// STUDENT_PANEL_LIMIT.
fun listByStudentWithActivity(
  session: SqlSession,
  studentId: StudentId,
  archive: ArchiveScope = ArchiveScope.UNARCHIVED,
  scope: SoftDeleteScope = SoftDeleteScope.ACTIVE,
  limit: Int? = null,
  offset: Int = 0,
): Result<List<ConvoWithActivity>>
```

The existing `listTurns(session, convoId, scope)` (per-conversation) and
`findByIdWithActivity(session, id, scope)` are reused as-is for the `convo`
detail page and its turns panel.

#### `ConvosResource` (`admin-server/.../resources/ConvosResource.kt`)

New descriptor. `slug = "convo"`, `title = "Conversations"`,
`kind =
AdminKind.ENTITY`, `topLevel = true`. All write handlers (`create`,
`update`, `delete`, `undelete`) null. `ROW = ConvoWithActivity`, `ID = ConvoId`.

- `edges = listOf<AdminEdge>(AdminEdge.HasMany("Turns", targetSlug = "convo-request"))`
  — the static descriptor for the one Turns panel; `resolveEdges` builds the
  matching `EdgePanel.Table`, mirroring `ObservationsResource`.
- `parseId(raw)` — `UUID` parse → `ConvoId`, null on malformed.
- `idToPath(id)` — `id.value.toString()`.
- `rowId(row)` — `row.convo.id`.
- `isDeleted(row)` — `row.convo.deletedAt != null`.
- `list(db, limit, offset, scope)` — delegates to `ConvosDao.listWithActivity`.
- `get(db, id, includeDeleted)` — delegates to `ConvosDao.findByIdWithActivity`
  with `SoftDeleteScope.ALL` (admin reads include deleted).
- `resolveEdges(db, row)` — one `EdgePanel.Table` "Turns" built from
  `ConvosDao.listTurns(session, row.convo.id, SoftDeleteScope.ALL)`.

Fields (all `editable = false`, `sensitive = false`):

| name             | label         | type      | refSlug   | inList |
| ---------------- | ------------- | --------- | --------- | ------ |
| `id`             | ID            | TEXT      | `convo`   | true   |
| `studentId`      | Student ID    | TEXT      | `student` | true   |
| `name`           | Name          | TEXT      | —         | true   |
| `lastActivityAt` | Last Activity | TIMESTAMP | —         | true   |
| `createdAt`      | Created       | TIMESTAMP | —         | true   |
| `updatedAt`      | Updated       | TIMESTAMP | —         | false  |
| `archivedAt`     | Archived      | TIMESTAMP | —         | true   |
| `deletedAt`      | Deleted       | TIMESTAMP | —         | true   |

Turns panel columns: `Request` (refSlug `convo-request`), `Sent` (TIMESTAMP),
`Model` (request `modelRequested`), `Stop Reason` (response, blank when none),
`In` (response `inputTokens`), `Out` (response `outputTokens`). The request-id
cell's link glyph navigates to the full turn at `/convo-request/{id}`.

#### `ConvoRequestsResource` (`admin-server/.../resources/ConvoRequestsResource.kt`)

New descriptor. `slug = "convo-request"`, `title = "Requests"`,
`kind =
AdminKind.LOG`, `topLevel = true`. All write handlers null.
`ROW = ConvoTurn`, `ID = ConvoRequestId`.

- `edges = emptyList<AdminEdge>()` — no edge panels (the `convoId` field links
  to the parent convo), matching `SystemPromptsResource`.
- `parseId(raw)` — `Long` parse → `ConvoRequestId`, null on malformed.
- `idToPath(id)` — `id.value.toString()`.
- `rowId(row)` — `row.request.id`.
- `isDeleted(row)` — always false (logs are not soft-deleted).
- `list(db, limit, offset, scope)` — delegates to `ConvosDao.listTurns`
  (global).
- `get(db, id, includeDeleted)` — delegates to `ConvosDao.findTurnByRequestId`
  with `SoftDeleteScope.ALL`.
- `resolveEdges` — returns no panels.

Fields (all `editable = false`, `sensitive = false`). Request fields first, then
the flattened reply; response cells are blank when `row.response == null`, and
individual response sub-fields are blank when their own value is null even
though `row.response` is present (a transport-error turn carries a non-null
`row.response` with null `content` and null token counts). All blanks are
blank-suppressed by the render layer.

| name                        | label                | type      | refSlug         | inList |
| --------------------------- | -------------------- | --------- | --------------- | ------ |
| `id`                        | ID                   | TEXT      | `convo-request` | true   |
| `convoId`                   | Convo                | TEXT      | `convo`         | true   |
| `createdAt`                 | Sent                 | TIMESTAMP | —               | true   |
| `provider`                  | Provider             | TEXT      | —               | true   |
| `modelRequested`            | Model Requested      | TEXT      | —               | true   |
| `systemPromptId`            | System Prompt        | TEXT      | `system-prompt` | false  |
| `requestParams`             | Request Params       | JSON      | —               | false  |
| `content`                   | Request Content      | JSON      | —               | false  |
| `responseStopReason`        | Response Stop Reason | TEXT      | —               | true   |
| `responseModelResolved`     | Response Model       | TEXT      | —               | false  |
| `responseInputTokens`       | Input Tokens         | INT       | —               | true   |
| `responseOutputTokens`      | Output Tokens        | INT       | —               | true   |
| `responseCacheReadTokens`   | Cache Read Tokens    | INT       | —               | false  |
| `responseCacheWriteTokens`  | Cache Write Tokens   | INT       | —               | false  |
| `responseLatencyMs`         | Latency (ms)         | INT       | —               | false  |
| `responseProviderRequestId` | Provider Request ID  | TEXT      | —               | false  |
| `responseContent`           | Response Content     | JSON      | —               | false  |
| `responseCreatedAt`         | Replied              | TIMESTAMP | —               | false  |

`cells(row)` stringifies each value to its plain form (instants via
`Instant.toString()`, ids via `value.toString()`, JSON via
`JsonElement.toString()`); presentation is the render layer's job. Each nullable
response sub-field (`content`, `inputTokens`, `outputTokens`, the cache/latency
counts, `providerRequestId`, `modelResolved`) maps to `""` when null —
`?.toString() ?: ""`, never `.toString()` on null — so a transport-error turn
with a present `row.response` but null `content`/token fields stringifies those
cells to `""` (no NPE) and the render layer blank-suppresses them.

#### Registration (`admin-server/.../Application.kt`)

`ConvosResource` and `ConvoRequestsResource` are added to the `AdminRegistry`
constructor list. The engine then auto-registers `/convo`, `/convo/{id}`,
`/convo-request`, `/convo-request/{id}`, and (via `registry.bySlug`) the display
support predicate reports both slugs supported, so RFC 79 link glyphs to them
resolve.

#### JSON rendering (`admin-server/.../render/CellRender.kt`)

`renderValue` gains a `FieldType.JSON` branch: parse the cell string as JSON and
emit it pretty-printed inside a `<pre>` element. On parse failure, log at WARN
and emit the raw text — the defensive, never-throws pattern already used for
`FieldType.TIMESTAMP`. A blank value renders nothing (unchanged). A private
`Json { prettyPrint = true }` instance lives in `CellRender.kt`. All cell
surfaces (detail field table, list rows, edge-table cells) route through
`renderCell` → `renderValue`, so the convention is uniform; JSON fields carry no
`refSlug`, so no link glyph follows.

#### Link wiring (existing resources)

- **`ObservationsResource`**: add `refSlug = "convo"` to the `convoId` field and
  `refSlug = "convo-request"` to the `sourceRequestId` field.
- **`ExtractionRunsResource`**: add `refSlug = "convo"` to the `convoId` field
  and `refSlug = "convo-request"` to the `throughRequestId` field.
- **`ClaimsResource`**: the "Supporting observations" panel gains two columns —
  `Convo` (refSlug `convo`) and `Source Request` (refSlug `convo-request`) —
  sourced from each observation's `convoId` / `sourceRequestId`. This is the
  generalized claim→conversation path: the observation is the only bridge
  between a claim and a conversation, so the link rides on the observations
  already shown.
- **`StudentsResource`**:
  - New **Conversations** panel (added to the embedded student's `nested`
    panels), built from
    `ConvosDao.listByStudentWithActivity(session, studentId,
    ArchiveScope.ALL, SoftDeleteScope.ALL, STUDENT_PANEL_LIMIT, 0)`.
    Columns: `ID` (refSlug `convo`), `Name`, `Last Activity` (TIMESTAMP),
    `Created` (TIMESTAMP), `Archived` (TIMESTAMP), `Deleted` (TIMESTAMP). Uses
    the shared `truncationRow` helper, like the other coaching-memory panels.
  - The existing **Observations** panel's `Convo ID` column gains
    `refSlug =
    "convo"`.

### Error Handling / Edge Cases

- **Missing row**: `findByIdWithActivity` / `findTurnByRequestId` return
  `NotFoundException`; the engine's detail handler routes it through
  `respondDaoError` to the 404 page.
- **Malformed id in path**: `parseId` returns null; the engine responds 404
  before any DAO call.
- **Deleted conversation**: admin reads use `SoftDeleteScope.ALL`, so deleted
  convos and their turns are visible; the `convo` detail/list marks the row
  deleted via `isDeleted`. A `convo-request` under a deleted convo is reachable;
  its `Convo` link leads to the convo page carrying the deleted marker.
- **Turn without a response** (mid-flight / failed): response cells are blank
  and blank-suppressed; the page renders the request alone.
- **Transport-error turn** (present `row.response`, null `content` and null
  token fields): the present response cells (e.g. `stopReason`) render, while
  the null-valued sub-field cells stringify to `""` and are blank-suppressed —
  no NPE and no `.toString()` on null.
- **Unparseable JSON cell**: rendered as raw text with a WARN log (never
  throws).
- **Large content** (`content` is bounded at 1 MiB by the schema): rendered in
  full inside `<pre>`. Admin fidelity is preferred over truncation; the heavy
  JSON fields are `inList = false`, so list pages stay light.
- **Empty turn firehose / convo list**: the engine renders an empty table.

### Dependencies

None new. `kotlinx.serialization` `Json` is already a project dependency. No new
configuration keys, no migration, no changes outside the `admin-server` and `db`
modules.

## Tests

### `ConvosDaoTest` (`db/src/test/kotlin/ed/unicoach/db/dao/ConvosDaoTest.kt`)

- `listWithActivity returns convos across students ordered by created_at desc` —
  seed two students with convos; assert global result spans both, newest first.
- `listWithActivity paginates by limit and offset` — seed > limit convos; assert
  the page window and that `limit` bounds the row count.
- `listWithActivity scope ALL includes deleted, ACTIVE excludes` — soft-delete
  one convo; assert presence under `ALL`, absence under `ACTIVE`.
- `listWithActivity includes archived convos` — archive one; assert it appears
  regardless of archive state.
- `listWithActivity derives lastActivityAt` — convo with turns has the max
  request `created_at`; convo without turns has null.
- `listTurns global returns turns across convos ordered by id desc` — seed turns
  in two convos; assert global order is most-recent request first.
- `listTurns global paginates by limit and offset`.
- `listTurns global includes turns with no response` — request without response
  appears with a null `response`.
- `listTurns global scope filters deleted convos` — turns of a soft-deleted
  convo absent under `ACTIVE`, present under `ALL`.
- `findTurnByRequestId returns request and paired response`.
- `findTurnByRequestId returns null response when none exists`.
- `findTurnByRequestId NotFound for missing request id`.
- `findTurnByRequestId NotFound when owning convo excluded by scope` —
  soft-deleted convo's request under `ACTIVE`.
- `listByStudentWithActivity limit caps rows and offset pages` — seed > limit;
  assert window.
- `listByStudentWithActivity default (no limit) returns all` — regression that
  the existing unbounded behaviour is preserved.

### `CellRenderTest` (`admin-server/.../render/CellRenderTest.kt`)

- `JSON value renders pretty-printed inside pre` — a compact JSON object renders
  with a `<pre>` element and newlines/indentation.
- `JSON array renders pretty-printed inside pre` — a compact JSON array
  (non-object top level) renders pretty-printed in `<pre>`.
- `JSON primitive renders pretty-printed inside pre` — a top-level JSON
  primitive (e.g. a string or number) renders in `<pre>` without throwing.
- `JSON blank value renders nothing`.
- `unparseable JSON renders raw text and does not throw`.
- `JSON field renders no ref link` — a JSON cell with no `refSlug` emits no
  glyph.

### `ConvosResourceTest` (`admin-server/.../resources/ConvosResourceTest.kt`)

Route-level via `AdminTestSupport`, admin session authenticated.

- `GET /convo lists conversations` — 200; contains a seeded convo name and the
  `/convo/{id}` link glyph.
- `GET /convo/{id} renders fields and turns panel` — 200; shows student link,
  last-activity, and a Turns row linking to `/convo-request/{id}`.
- `GET /convo/{id} marks a deleted conversation` — deleted badge present; row
  still reachable.
- `GET /convo/{id} returns 404 for missing id` and `for malformed id`.
- `convo has no create/edit/delete controls` — read-only assertion.

### `ConvoRequestsResourceTest` (`admin-server/.../resources/ConvoRequestsResourceTest.kt`)

- `GET /convo-request lists turns` — 200; most-recent first.
- `GET /convo-request/{id} renders request content and paired reply` — pretty
  JSON `<pre>` for content; response stop reason and token cells present.
- `GET /convo-request/{id} renders a request with no response` — response cells
  blank; page renders.
- `GET /convo-request/{id} renders a transport-error turn` — seed a present
  response with `content = null`, `stopReason = "error"`, and null token counts;
  assert the page renders 200 with the stop-reason cell present and the
  content/token cells blank, no crash.
- `convoId links to /convo and systemPromptId links to /system-prompt`.
- `GET /convo-request/{id} returns 404 for missing and malformed id`.

### `StudentsResourceTest` (modified)

- `student detail shows a Conversations panel` — convo rows link to `/convo`.
- `Conversations panel shows a truncation row past the limit` — seed >
  `STUDENT_PANEL_LIMIT` convos; assert the disclosure row.
- `observations panel Convo ID column links to /convo`.

### `ObservationsResourceTest` (modified)

- `observation detail links convoId to /convo and sourceRequestId to
  /convo-request`.

### `ClaimsResourceTest` (modified)

- `supporting-observations panel shows Convo and Source Request columns linking
  to /convo and /convo-request`.

### `ExtractionRunsResourceTest` (modified)

- `extraction-run detail links convoId to /convo and throughRequestId to
  /convo-request`.

## Implementation Plan

1. **Add `ConvosDao` reads.** Add `listWithActivity`, global `listTurns`,
   `findTurnByRequestId`; add trailing defaulted `limit`/`offset` to
   `listByStudentWithActivity`. Add the `ConvosDaoTest` cases above.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin :db:compileTestKotlin`
   - Verify:
     `nix develop -c bin/test db --tests "ed.unicoach.db.dao.ConvosDaoTest" -f`
     (confirm "N executed", not all-cached).

2. **Pretty-print JSON in the render layer.** Add the `FieldType.JSON` branch to
   `renderValue` (parse → `<pre>` pretty JSON; WARN + raw text on failure) and a
   private pretty `Json` in `CellRender.kt`. Add the `CellRenderTest` cases.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`
   - Verify:
     `nix develop -c bin/test admin-server --tests "ed.unicoach.admin.render.CellRenderTest" -f`

3. **Add the two resources and register them.** Create `ConvosResource.kt` and
   `ConvoRequestsResource.kt`; add both to the `AdminRegistry` list in
   `Application.kt`; add any `appendResponse` helper needed to
   `AdminTestSupport`. Add `ConvosResourceTest` and `ConvoRequestsResourceTest`.
   - Verify:
     `nix develop -c ./gradlew :admin-server:compileKotlin :admin-server:compileTestKotlin`
   - Verify:
     `nix develop -c bin/test admin-server --tests "ed.unicoach.admin.resources.ConvosResourceTest" --tests "ed.unicoach.admin.resources.ConvoRequestsResourceTest" -f`

4. **Wire links from existing resources.** Add `refSlug` to the convo FK fields
   in `ObservationsResource` and `ExtractionRunsResource`; add the `Convo` and
   `Source Request` columns to the `ClaimsResource` supporting-observations
   panel; add the Conversations panel and the observations-panel `Convo` link in
   `StudentsResource`. Update `StudentsResourceTest`,
   `ObservationsResourceTest`, `ClaimsResourceTest`,
   `ExtractionRunsResourceTest`.
   - Verify:
     `nix develop -c ./gradlew :admin-server:compileKotlin :admin-server:compileTestKotlin`
   - Verify:
     `nix develop -c bin/test admin-server --tests "ed.unicoach.admin.resources.*" -f`

5. **Full gate.** Run the Kotlin + Postgres + ktlint pre-commit gate.
   - Verify: `nix develop -c bin/test check -f`

## Files Modified

- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ConvosResource.kt`
  (new)
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ConvoRequestsResource.kt`
  (new)
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/ConvosResourceTest.kt`
  (new)
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/ConvoRequestsResourceTest.kt`
  (new)
- `db/src/main/kotlin/ed/unicoach/db/dao/ConvosDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/ConvosDaoTest.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/render/CellRender.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/render/CellRenderTest.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/Application.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ObservationsResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ExtractionRunsResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ClaimsResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/StudentsResource.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/StudentsResourceTest.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/ObservationsResourceTest.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/ClaimsResourceTest.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/ExtractionRunsResourceTest.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/AdminTestSupport.kt`
