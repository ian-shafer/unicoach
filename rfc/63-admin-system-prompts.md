# RFC 63: Admin System Prompts

## Executive Summary

This RFC adds admin management of the `system_prompts` catalog to the
descriptor-driven admin engine, and extracts the one admin write-path fragment
that the DAO capability interfaces (`db/dao/Dao.kt`) let us write once instead
of per entity.

`system_prompts` is an immutable entity: its triggers forbid `UPDATE` and
`DELETE`, and a "new version" is a new immutable row. The new
`SystemPromptsResource` is the first instance of the long-declared
`AdminKind.IMMUTABLE_ENTITY` — create plus list/detail only, no edit, no delete.
This requires `SystemPromptsDao` (read-only today) to gain `Findable` /
`Listable` / `Creatable` and an insert, a `NewSystemPrompt` input model, and a
small `inList` field affordance so a 1 MB `body` is not dumped into the list
table. It also extracts the soft-delete OCC dance (`findById` →
`delete(id, version)`) — today duplicated in `UsersResource.delete`/`undelete` —
into one shared helper. The Detailed Design opens by bounding which fragments
the capability interfaces generalize and which stay per-entity.

## Detailed Design

### Premise: what the capability interfaces do and do not generalize

The admin engine (`admin-server/.../engine`) already isolates all
routing/rendering from per-table logic (`engine/SPEC.md`, "Descriptor-driven
routing"): adding a table is declaring an `AdminResource` and the nullability of
its `create`/`update`/`delete` handlers, not editing the router. The per-entity
code a descriptor still hand-writes is `fields`, `cells(row)`,
`parseId`/`idToPath`, `resolveEdges`, and the `create`/`update` handlers that
parse an HTML `Map<String, String>` into validated value classes and assemble
the DAO's `NEW`/`EDIT` input. The capability interfaces (`Findable`, `Listable`,
`Creatable`, `OccDeletable`, … in `db/dao/Dao.kt`) type the _final_ call
(`Creatable.create(session, input: NEW)`) but not the construction of `input`
from an untyped form — that marshalling is the per-entity surface. A fully
generic capability-driven admin would relocate that marshalling, not remove it;
this RFC therefore adds the concrete resource and extracts only the one write
fragment expressible purely over the interfaces, because it consumes only an id
and a row version: the soft-delete OCC dance, and nothing more.

### Data Models

One creation-input record is added to `db/models`, the sibling of `NewUser` /
`NewStudent`. It carries only the three immutable domain columns as plain
strings; `id`, `created_at`, and `row_created_at` are DB-defaulted and never
client-supplied. Strings (not value classes) match the existing `SystemPrompt`
model, which holds `name`/`version`/`body` as `String`; canonicalization and
bounds are DB-enforced (the table's `CHECK`/`UNIQUE` constraints).

```kotlin
data class NewSystemPrompt(
  val name: String,
  val version: String,
  val body: String,
)
```

No schema change: the `system_prompts` table (migration 0007) already supports
`INSERT` — its immutability triggers fire only on `UPDATE`/`DELETE`. Adding an
admin insert path widens the row authors from migration-only (today
`SystemPromptsDao` exposes no writes; rows come from migration 0011) to
migration or the admin tool; the per-row immutability guarantee is untouched,
since no row is ever mutated or deleted.

### API Contracts — `SystemPromptsDao` (`db/dao/SystemPromptsDao.kt`)

`SystemPromptsDao` adopts three capability interfaces from `db/dao/Dao.kt` and
gains the three backing methods, all routed through the `SqlSession` query
helpers in `db/dao/SqlSessionQueries.kt`. It keeps its existing concrete
`findByNameAndVersion`. It implements plain `Findable` (not
`SoftDeleteFindable`) and plain `Listable` (not `SoftDeleteListable`) because
`system_prompts` has no `deleted_at` column.

```kotlin
object SystemPromptsDao :
  Findable<SystemPrompt, SystemPromptId>,
  Listable<SystemPrompt>,
  Creatable<NewSystemPrompt, SystemPrompt> {

  override fun findById(session: SqlSession, id: SystemPromptId): Result<SystemPrompt>
  // SELECT * FROM system_prompts WHERE id = ?  via queryOne; NotFoundException on no row.

  override fun list(session: SqlSession, limit: Int, offset: Int): Result<List<SystemPrompt>>
  // SELECT * FROM system_prompts ORDER BY name ASC, version ASC, created_at DESC LIMIT ? OFFSET ?
  // via queryList. created_at DESC is a deterministic final tie-breaker, redundant under
  // UNIQUE (name, version) but kept so the page order is total and stable.

  override fun create(session: SqlSession, input: NewSystemPrompt): Result<SystemPrompt>
  // insertReturning("system_prompts", {name, version, body}, ::mapPrompt, mapError = ::mapPromptError)

  fun findByNameAndVersion(session: SqlSession, name: String, version: String): Result<SystemPrompt>
  // unchanged
}
```

`create` uses the `insertReturning` column-map helper
(`db/dao/SqlSessionQueries.kt`, columns `name`, `version`, `body`;
`id`/`created_at`/`row_created_at` left to their DB defaults and read back from
`RETURNING *`). It passes a dedicated SQLSTATE mapper, because the default
`mapDatabaseError` classifies the unique-violation `23505` as an opaque
`DatabaseException`, which the admin form layer cannot render as a field error:

```kotlin
private fun mapPromptError(e: SQLException): Exception =
  when (e.sqlState) {
    "23505", "23514" -> ConstraintViolationException(e)  // (name,version) unique; or any CHECK
    else -> mapDatabaseError(e)
  }
```

This mirrors `ConvosDao.mapConvoError` (`23505`/`23514` →
`ConstraintViolationException`). A duplicate `(name, version)` and a bound/CHECK
violation both surface as `ConstraintViolationException`, which the engine
already renders as a form-level error. `mapPromptError` deliberately covers only
the insert-reachable states; the table's immutability triggers raise `P0001`
(migration 0007), but the insert-only admin path never issues an `UPDATE` or
`DELETE`, so `P0001` is unreachable here and falls through to
`mapDatabaseError`.

### API Contracts — shared soft-delete OCC helper (`admin-server/.../resources/OccDelete.kt`, new)

A single suspend helper expresses the "load current version, then OCC
delete/undelete" sequence once, programmed against the `db/dao/Dao.kt`
capability-interface intersection rather than a concrete DAO. It applies to any
DAO that is **both** `SoftDeleteFindable` and `OccDeletable` over the same
`(ROW, ID)`:

```kotlin
internal suspend fun <ID, ROW, DAO> Database.occSoftDelete(
  dao: DAO,
  id: ID,
  deleted: Boolean,
): Result<Unit>
  where ID : Id,
        ROW : Identifiable<ID>,
        ROW : Versioned,
        DAO : SoftDeleteFindable<ROW, ID>,
        DAO : OccDeletable<ROW, ID>
// withConnection: findById(id, SoftDeleteScope.ALL) -> (deleted ? delete : undelete)(id, row.version)
// Propagates the DAO's NotFoundException / ConcurrentModificationException unchanged.
```

The helper wraps both the `findById` read and the OCC write in a single
`Database.withConnection`, threading the same `SqlSession` to both — identical
to the single-connection read-then-write `UsersResource.delete`/`undelete`
perform today.

`UsersResource.delete`/`undelete` become one-line delegations
(`db.occSoftDelete(UsersDao, id, deleted = true/false)`). The behaviour is
identical: the same `findById(ALL)` existence check and the same OCC write the
two blocks perform today. The student nested-delete route in `UsersResource` is
**not** retrofitted: it keys on `findByUserId` (a parented lookup that is not on
`SoftDeleteFindable`), outside this helper's seam; forcing it in would require a
second finder parameter that defeats the "program against the interface" intent.
This is the same boundary discipline the existing DAOs apply: parented `listBy*`
lookups stay concrete methods, off the capability interfaces in `db/dao/Dao.kt`.

### API Contracts — `SystemPromptsResource` (`admin-server/.../resources/SystemPromptsResource.kt`, new)

A new
`object SystemPromptsResource : AdminResource<SystemPrompt, SystemPromptId>`,
`kind = AdminKind.IMMUTABLE_ENTITY`, `topLevel = true`,
`slug = "system-prompt"`, `title = "System Prompt"` (the nav label and page
heading). The IMMUTABLE_ENTITY operation set is realized purely through handler
nullability, per the engine's existing contract:

| Member                | Value                                                                                                                                                            |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `create`              | non-null — parses the form, builds `NewSystemPrompt`, calls `Creatable.create`, maps the returned row to its `id` (the handler type is `Result<SystemPromptId>`) |
| `update`              | `null` — table forbids `UPDATE`                                                                                                                                  |
| `delete` / `undelete` | `null` — table forbids `DELETE`; no soft-delete column                                                                                                           |
| `list`                | `SystemPromptsDao.list(session, limit, offset)` (`scope` ignored — no `deleted_at`)                                                                              |
| `get`                 | `SystemPromptsDao.findById(session, id)` (`includeDeleted` ignored)                                                                                              |
| `isDeleted`           | always `false`                                                                                                                                                   |
| `edges`               | empty                                                                                                                                                            |
| `resolveEdges`        | default (empty)                                                                                                                                                  |

With `update`/`delete`/`undelete` null, the engine registers no edit/delete
routes, renders no Edit/Delete actions on the detail page, and the
unconditionally-registered `GET /{slug}/{id}/edit`, `POST /{slug}/{id}`,
`POST /{slug}/{id}/delete` re-check nullability at request time and return the
not-found page. The result is a create + list/detail surface, which is the full
useful catalog-authoring surface for an immutable entity.

Fields (declaration order = detail-row order). Each also carries a human `label`
(the second `AdminField` constructor argument, no default), omitted here:

| name        | type        | editable | sensitive | inList    |
| ----------- | ----------- | -------- | --------- | --------- |
| `id`        | `TEXT`      | false    | false     | true      |
| `name`      | `TEXT`      | true     | false     | true      |
| `version`   | `TEXT`      | true     | false     | true      |
| `createdAt` | `TIMESTAMP` | false    | false     | true      |
| `body`      | `MULTILINE` | true     | false     | **false** |

`body` is `editable = true` so it appears as a textarea on the create form
(`renderForm` emits only `editable && !sensitive` fields, and `FormView` already
renders `MULTILINE` as a textarea); since `update == null`, no edit form is ever
served, so `body` is never re-editable. `body` is `inList = false` so the list
table — which renders every `inList && !sensitive` field via `cells` — omits the
up-to-1 MB body; the detail page renders `body` in full (detail iterates all
`fields`).

The `create` handler trims `name` and `version` (identifiers; surrounding
whitespace is never intended and the table's `*_trimmed_check` would otherwise
reject it) but passes `body` **verbatim** (trailing whitespace/newlines in a
prompt body are significant — the schema deliberately exempts `body` from a
trimmed check). It rejects blank `name`/`version`/`body` client-side with an
`IllegalArgumentException` carrying a field message; all other validity (length,
size, uniqueness) is DB-enforced and surfaces as `ConstraintViolationException`.

### Engine affordance — `AdminField.inList` (`engine/AdminField.kt`, `render/ListView.kt`)

`AdminField` gains `val inList: Boolean = true`. `ListView.renderList` changes
its column filter from `fields.filterNot { it.sensitive }` to
`fields.filter { it.inList && !it.sensitive }`. This is the minimum general
affordance for a resource with a field too large for a list cell; it is
defaulted `true`, so every existing descriptor (`UsersResource`,
`StudentsResource`, `SessionsResource`) is unaffected, and it is orthogonal to
`sensitive` (which also removes a field from forms and detail — wrong for
`body`, which must appear in both). Detail and form rendering are unchanged.

### Error Handling / Edge Cases

- **Duplicate `(name, version)`.** `create` → `ConstraintViolationException`
  (`23505` via `mapPromptError`) → the engine re-renders the create form with
  the submitted values and the generic "A field violates a database constraint"
  message (`AdminRouting.createFormErrorMessage`). No engine change: the
  existing branch already handles `ConstraintViolationException`.
- **Blank field.** Empty `name`/`version`/`body` → `IllegalArgumentException`
  before any DB call → form re-rendered with the field message.
- **Over-long name/version, over-size body, untrimmed (post-trim impossible for
  name/version).** DB `CHECK` (`23514`) → `ConstraintViolationException` → form
  error.
- **No edit/delete.** `GET /system-prompt/{id}/edit`,
  `POST /system-prompt/{id}`, `POST /system-prompt/{id}/delete` → not-found page
  (handlers null at request time). No Edit/Delete/Undelete controls render on
  the detail page.
- **Unknown id.** `get` → `NotFoundException` → 404 page. Malformed id segment →
  `parseId` returns null → not-found page (engine contract).
- **OCC helper.** `occSoftDelete` propagates `NotFoundException` (unknown id)
  and `ConcurrentModificationException` (version raced between the `findById`
  and the OCC write) unchanged, identical to the inline blocks it replaces.

### Dependencies

None added. No new libraries, no configuration, no migration. The work uses the
existing `SqlSession` query helpers (`db/dao/SqlSessionQueries.kt`: `queryOne`,
`queryList`, `insertReturning`), the existing capability interfaces
(`db/dao/Dao.kt`), the existing admin engine and renderers, and the existing
`:db` ↔ `:admin-server` module wiring. `CoachingService.findByNameAndVersion` is
the sole external `SystemPromptsDao` consumer and is unaffected (the additions
are purely additive).

## Tests

All run under `nix develop -c bin/test --force` (independent runs need `--force`
to avoid an all-cached no-op). New DAO cases follow the existing
`SystemPromptsDaoTest` raw-connection pattern: the suite already re-asserts the
migration-0011 `coach/v1` seed via a
`@BeforeEach ... INSERT ... ON CONFLICT DO
NOTHING`, and a sibling suite
(`ConvosDaoTest`) truncates `system_prompts` mid-run under unspecified JVM
ordering. New cases therefore insert their own unique `(name, version)` rows and
assert only over those rows — never an absolute row count, an absolute page
offset, or the presence/absence of foreign rows. The ever-present `coach/v1`
seed row (which sorts before any `rfc63-*` name) must not break any assertion.

### `db` module — `SystemPromptsDaoTest` (extended)

- **`create` round-trips.** `create(NewSystemPrompt("rfc63-a", "v1", "Body A"))`
  returns a `SystemPrompt` whose `name`/`version`/`body` match, with a non-null
  generated `id` and a populated `createdAt`.
- **`findById` returns the created row.** After a `create`, `findById(row.id)`
  returns an equal row; `findById` of a random `SystemPromptId` →
  `NotFoundException`.
- **`list` orders and pages.** Insert `("rfc63-b","v1")`, `("rfc63-a","v2")`,
  `("rfc63-a","v1")`; page the full catalog (`list` across successive
  `offset`/`limit` windows until exhausted), filter the concatenated result to
  the three inserted ids, and assert their relative order is `a/v1`, `a/v2`,
  `b/v1` (name ASC, then version ASC). The assertion is over relative order of
  the inserted rows only — it tolerates the seed `coach/v1` and any rows other
  suites leave behind, so it makes no claim about a specific page's contents at
  a fixed offset.
- **duplicate `(name, version)` → `ConstraintViolationException`.** A second
  `create` with the same `(name, version)` as an existing row fails with
  `ConstraintViolationException` (proves `mapPromptError` maps `23505`).
- **CHECK violation → `ConstraintViolationException`.** `create` with a blank
  `body` (`"   "`) fails with `ConstraintViolationException` (proves
  `mapPromptError` maps `23514`). This calls `SystemPromptsDao.create` directly,
  exercising the DB CHECK path — distinct from the resource-layer blank-body
  case (`SystemPromptsResourceTest`), where the handler rejects `"   "` earlier
  with `IllegalArgumentException` before any DB call.

### `admin-server` module — `SystemPromptsResourceTest` (new, Ktor `testApplication`)

A `seedSystemPrompt(name, version, body)` helper is added to `AdminTestSupport`
(direct `SystemPromptsDao.create`); each test uses unique `(name, version)`.

- **Gate.** `/system-prompt` with an admin session → `200`; the dashboard
  `GET /` lists "System Prompt" in the nav (resource is `topLevel`).
- **List omits body.** Seed a prompt with a long body; `GET /system-prompt`
  renders its `name`/`version` row but the response does **not** contain the
  body text (proves `inList = false`).
- **Detail shows full body, no edit/delete controls.** `GET /system-prompt/{id}`
  contains the full `body`, `name`, `version`, `createdAt`; the response
  contains none of the action targets `/system-prompt/{id}/edit`,
  `/system-prompt/{id}/delete`, or `/system-prompt/{id}/undelete` (asserting on
  the control URLs, not the words "Edit"/"Delete", which a body could contain).
- **Create form.** `GET /system-prompt/new` renders inputs for `name`,
  `version`, and a `body` textarea; no `id`/`createdAt` inputs.
- **Create success.** `POST /system-prompt` with valid `name`/`version`/`body`
  redirects to the new row's `/system-prompt/{id}`, and a subsequent
  `findByNameAndVersion` resolves the inserted row.
- **Create duplicate.** `POST /system-prompt` with an existing `(name, version)`
  re-renders the form with status `400` and the constraint message; no second
  row is created (`findByNameAndVersion(name, version)` still resolves the
  single original row).
- **Create blank.** `POST /system-prompt` with a blank `body` re-renders the
  form with status `400`; no row is created.
- **Body whitespace preserved.** `POST /system-prompt` with a `body` ending in a
  newline stores the body verbatim (trailing newline intact on read-back), while
  a `name` submitted with surrounding spaces is trimmed.
- **Immutability routes 404.** `GET /system-prompt/{id}/edit`,
  `POST /system-prompt/{id}`, and `POST /system-prompt/{id}/delete` each return
  the not-found page for an existing prompt id.
- **Malformed id routes 404.** `GET /system-prompt/not-a-uuid` returns the
  not-found page (`parseId` returns null), proving the `parseId`/`idToPath`
  round-trip rejects non-UUID segments.

### `admin-server` module — `UsersResourceTest` (unchanged — OCC-helper regression net)

The existing soft-delete, undelete, and OCC-conflict cases recompile and run
unchanged against the refactored `UsersResource.delete`/`undelete`, proving the
`occSoftDelete` extraction is behaviour-preserving. No new case is added.

## Implementation Plan

Each step is locally verifiable inside the Nix dev shell (`nix develop -c …`).

1. **Add the `NewSystemPrompt` model.** Create
   `db/src/main/kotlin/ed/unicoach/db/models/NewSystemPrompt.kt`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin` succeeds.

2. **Extend `SystemPromptsDao`.** Declare
   `Findable<SystemPrompt,
   SystemPromptId>`, `Listable<SystemPrompt>`,
   `Creatable<NewSystemPrompt,
   SystemPrompt>`; add `findById` (`queryOne`),
   `list` (`queryList`,
   `ORDER BY
   name ASC, version ASC, created_at DESC LIMIT ? OFFSET ?`),
   `create` (`insertReturning` over `name`/`version`/`body` with
   `mapError = ::mapPromptError`), and the private `mapPromptError`. Keep
   `findByNameAndVersion`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin` succeeds.

3. **Extend the DAO test.** Add the five `SystemPromptsDaoTest` cases (create
   round-trip, `findById`, `list` ordering/paging, duplicate, CHECK), each
   self-seeding with unique `(name, version)`.
   - Verify: `nix develop -c bin/test db --force` passes; read
     `db/build/test-results/test/*SystemPromptsDaoTest*.xml` and confirm a
     non-zero, increased executed count.

4. **Add the `inList` field affordance.** Add `val inList: Boolean = true` to
   `AdminField`; change `ListView.renderList`'s column filter to
   `fields.filter { it.inList && !it.sensitive }`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin` succeeds.

5. **Extract the OCC helper and refactor `UsersResource`.** Create
   `admin-server/.../resources/OccDelete.kt` with `Database.occSoftDelete`;
   replace the `UsersResource.delete`/`undelete` bodies with
   `db.occSoftDelete(UsersDao, id, deleted = true/false)`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin` succeeds.

6. **Add `SystemPromptsResource` and register it.** Create
   `admin-server/.../resources/SystemPromptsResource.kt` (fields, `cells`,
   `parseId`/`idToPath`, `list`/`get`, `create` with trim/validation; all of
   `update`/`delete`/`undelete` null). Add it to the `AdminRegistry` list in
   `Application.adminModule`. Add `seedSystemPrompt` to `AdminTestSupport`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin` and
     `:admin-server:compileTestKotlin` succeed.

7. **Add the resource test.** Add `SystemPromptsResourceTest` with the cases
   listed in the Tests section above.
   - Verify: `nix develop -c bin/test admin-server --force` passes; read
     `admin-server/build/test-results/test/*SystemPromptsResourceTest*.xml` and
     `*UsersResourceTest*.xml` and confirm non-zero executed counts (the latter
     proving the OCC refactor stayed green).

8. **Run the full suite.**
   - Verify: `nix develop -c bin/test --force` passes.

## Files Modified

### Created

- `db/src/main/kotlin/ed/unicoach/db/models/NewSystemPrompt.kt` — the
  creation-input record.
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/SystemPromptsResource.kt`
  — the `IMMUTABLE_ENTITY` descriptor (create + list/detail).
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/OccDelete.kt` — the
  `Database.occSoftDelete` capability-interface helper.
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/SystemPromptsResourceTest.kt`
  — the resource HTTP tests.

### Modified — `db` module

- `db/src/main/kotlin/ed/unicoach/db/dao/SystemPromptsDao.kt` — implement
  `Findable`/`Listable`/`Creatable`; add `findById`, `list`, `create`,
  `mapPromptError`.
- `db/src/test/kotlin/ed/unicoach/db/dao/SystemPromptsDaoTest.kt` — add the
  create/find/list/duplicate/CHECK cases.

### Modified — `admin-server` module

- `admin-server/src/main/kotlin/ed/unicoach/admin/engine/AdminField.kt` — add
  `inList: Boolean = true`.
- `admin-server/src/main/kotlin/ed/unicoach/admin/render/ListView.kt` — filter
  list columns by `inList && !sensitive`.
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/UsersResource.kt` —
  `delete`/`undelete` delegate to `occSoftDelete`.
- `admin-server/src/main/kotlin/ed/unicoach/admin/Application.kt` — register
  `SystemPromptsResource` in the `AdminRegistry`.
- `admin-server/src/test/kotlin/ed/unicoach/admin/AdminTestSupport.kt` — add
  `seedSystemPrompt`.
