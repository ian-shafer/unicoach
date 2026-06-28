# RFC 79: Admin display conventions

## Executive Summary

The admin website renders every cell value as a pre-stringified plain string:
`cells()` emits ISO instants (`Instant.toString()`), `"true"`/`"false"`, and raw
UUIDs, and the render layer prints them verbatim. This RFC makes four display
conventions uniform and central instead of per-descriptor:

1. **Datetimes** display as `MMM d, yyyy` (e.g. `Jan 3, 2026`) in a configured
   timezone, with the full source ISO instant on hover (`title`).
2. A new `admin.display.timezone` config value is the zone all datetimes render
   in.
3. **Every ID cell** (primary id and foreign-key columns) renders its value
   followed by a non-breaking space and a link glyph (`🔗`) hyperlinking to that
   entity's detail page — but only when the target slug is a registered admin
   resource.
4. **Booleans** render as a green check / red cross glyph.

The link glyph and both boolean glyphs are configurable.

All four conventions are enforced by a single central helper, so list, detail,
and every edge panel render identically; Detailed Design specifies the
mechanism.

## Detailed Design

### Render context: `AdminDisplay`

A render-time context object, constructed once in `adminModule` and threaded
into the render functions. It is the render layer's only source of zone, glyphs,
and entity-support knowledge.

```kotlin
// render/CellRender.kt
data class AdminDisplay(
  val zone: java.time.ZoneId,
  val idLinkGlyph: String,
  val boolTrueGlyph: String,
  val boolFalseGlyph: String,
  val isSupported: (slug: String) -> Boolean,
)
```

`isSupported` is `{ registry.bySlug(it) != null }`. The render layer never holds
the registry; it holds only this predicate.

### Central rendering helpers

Three `kotlinx.html` `FlowContent` extensions in `render/CellRender.kt`. They
are the single place the conventions live; every table cell routes through them.

```kotlin
fun FlowContent.renderValue(value: String, type: FieldType, display: AdminDisplay)
fun FlowContent.renderRefLink(value: String, refSlug: String?, display: AdminDisplay)
fun FlowContent.renderCell(value: String, type: FieldType, refSlug: String?, display: AdminDisplay)
```

- `renderValue` renders the typed value only (no link):
  - `TIMESTAMP`: produces the source instant formatted as `MMM d, yyyy`
    (`Locale.ENGLISH`) in `display.zone`, carrying the verbatim source ISO
    string as a hover title. A blank value produces nothing. A value that does
    not parse as an `Instant` produces its raw text (defensive; never throws).
  - `BOOL`: an allowlist of the two stringified bools — the configured true
    glyph (`bool-true` class) for `"true"`, the configured false glyph
    (`bool-false` class) for `"false"`. A blank value produces nothing; any
    other value surfaces as raw text (with a logged warning) rather than masking
    as false. `cells()` always stringifies bools as `"true"`/`"false"`, so the
    raw-text branch is unreachable in practice but keeps an unexpected value
    visible.
  - all other types: the raw text.
- `renderRefLink`: when `refSlug != null`, `value` is non-blank, and
  `display.isSupported(refSlug)`, produces a leading non-breaking space followed
  by the configured link glyph as a hyperlink (in the `id-link` class) to that
  entity's detail page (`/{refSlug}/{value}`). Otherwise produces nothing.
- `renderCell` = `renderValue` then `renderRefLink`. **Every** cell renders
  through `renderCell` uniformly — list rows, the detail field table, and all
  edge-table cells alike: the value as plain text followed by the
  `renderRefLink` glyph. No cell wraps its value text in a hyperlink; the glyph
  is the sole link to the entity, so navigation to a row's detail page is the
  primary-id column's own `refSlug` glyph (`/{slug}/{id}`). A list row still
  appends its `deleted` badge after the id cell.

The datetime hover carries the **source** instant verbatim (UTC, `…Z`), matching
the requirement's example; the visible date is that instant in `display.zone`,
so a near-midnight instant can show a different calendar date than the `title`.

### Field metadata: entity references

`AdminField` gains a nullable target slug. A non-null `refSlug` marks the column
as an entity reference; the primary id sets its own slug, foreign-key columns
set the referenced slug.

```kotlin
data class AdminField(
  val name: String,
  val label: String,
  val type: FieldType,
  val editable: Boolean,
  val sensitive: Boolean,
  val inList: Boolean = true,
  val enumValues: List<String> = emptyList(),
  val refSlug: String? = null,   // new
)
```

No new `FieldType` variant: id columns stay `TEXT`; `refSlug` alone drives the
link. A `refSlug` whose slug is unregistered renders the value with no link
(covers `convoId`, `sourceRequestId`, `throughRequestId`, which have no admin
resource).

Per-resource `refSlug` assignments:

| Resource       | id → self        | FK columns                                                                                           |
| -------------- | ---------------- | ---------------------------------------------------------------------------------------------------- |
| user           | `user`           | —                                                                                                    |
| student        | `student`        | `userId`→`user`                                                                                      |
| session        | `session`        | `userId`→`user`                                                                                      |
| observation    | `observation`    | `studentId`→`student` (`convoId`, `sourceRequestId`: unsupported)                                    |
| claim          | `claim`          | `studentId`→`student`, `supersededById`→`claim`                                                      |
| extraction-run | `extraction-run` | `studentId`→`student`, `systemPromptId`→`system-prompt` (`convoId`, `throughRequestId`: unsupported) |
| system-prompt  | `system-prompt`  | —                                                                                                    |

### Edge-panel typing

`EdgePanel.Table` columns and `EdgePanel.Embedded` fields become typed so the
same `renderCell` governs them. Cells stay raw strings (positional); the column
carries the type and ref-slug.

```kotlin
data class Table(
  override val label: String,
  val columns: List<Column>,   // was List<String>
  val rows: List<Row>,
) : EdgePanel {
  data class Column(
    val label: String,
    val type: FieldType = FieldType.TEXT,
    val refSlug: String? = null,
  )
  data class Row(val href: String?, val cells: List<String>)
}

data class Embedded(
  // …unchanged members…
  val fields: List<LabeledCell>,   // was List<Pair<String, String>>
  // …
) : EdgePanel

data class LabeledCell(
  val label: String,
  val type: FieldType,
  val refSlug: String?,
  val value: String,
)
```

`Column`'s defaults keep plain text columns terse (`Column("Topic")`). Edge
builders set `type`/`refSlug` only on id/timestamp/bool columns.

### Configuration

`AdminConfig` gains a nested `DisplayConfig`, parsed fail-fast from a new
`admin.display` section (mirroring the existing `from` pattern). The timezone is
validated through `ZoneId.of`, so a malformed zone fails at startup.

```kotlin
data class DisplayConfig(
  val timezone: java.time.ZoneId,
  val idLinkGlyph: String,
  val boolTrueGlyph: String,
  val boolFalseGlyph: String,
)
```

```hocon
# admin-server.conf
admin {
  display {
    timezone       = "UTC"
    timezone       = ${?ADMIN_DISPLAY_TIMEZONE}
    idLinkGlyph    = "🔗"
    idLinkGlyph    = ${?ADMIN_ID_LINK_GLYPH}
    boolTrueGlyph  = "✓"
    boolTrueGlyph  = ${?ADMIN_BOOL_TRUE_GLYPH}
    boolFalseGlyph = "✗"
    boolFalseGlyph = ${?ADMIN_BOOL_FALSE_GLYPH}
  }
}
```

### Styling

`Layout.kt` `STYLES` gains three classes: `bool-true` (green), `bool-false`
(red), and `id-link` (no underline on the glyph link). Exact color and property
values are left to implementation.

### Wiring

`adminModule` builds `AdminDisplay` from `adminConfig.display` and the registry
predicate, then passes it to `registerAdminRoutes(registry, database, display)`.
`registerAdminRoutes` forwards it to `renderList`/`renderDetail`. `renderDetail`
threads `display` through its private `renderEdgePanel`/`renderTablePanel`/
`renderEmbeddedPanel` helpers so edge-panel cells route through the same helper.
The edit-form path is untouched: it pre-fills from the raw `cells()` map.

### Error Handling / Edge Cases

- Blank cell → renders nothing (no empty span, no dangling glyph).
- Unparseable timestamp → raw text, no throw.
- `refSlug` set but slug unregistered, or value blank → no link.
- The non-breaking space is emitted as a `` text node between value and anchor,
  so the glyph never wraps to its own line.
- Forms and the detail heading are unchanged; the heading id is a title, not a
  data cell, and gets no glyph (the id _field row_ does, per the uniform rule).

### Dependencies

`java.time` only (JDK). No new libraries, no schema/migration changes.

## Tests

New unit suite `render/CellRenderTest.kt` (renders each helper into a string):

- `timestamp renders MMM d, yyyy in the configured zone` — UTC instant →
  `Jan 3, 2026`.
- `timestamp hover title carries the verbatim source ISO` — `title` equals the
  input ISO string.
- `non-UTC zone shifts the displayed date across midnight` — instant
  `2026-06-28T02:00:00Z` in `America/Los_Angeles` → `Jun 27, 2026`, title still
  the `…Z` string.
- `blank timestamp renders nothing`.
- `unparseable timestamp renders the raw text without throwing`.
- `bool true renders the configured true glyph in bool-true`.
- `bool false renders the configured false glyph in bool-false`.
- `blank bool renders nothing`.
- `supported entity ref renders value, nbsp, then a glyph link to slug detail` —
  asserts `` and `href="/user/{id}"`.
- `unsupported entity ref renders the value with no link`.
- `blank ref value renders no link`.
- `configured idLinkGlyph and bool glyphs are honored`.

New `AdminConfigTest.kt`:

- `parses admin.display defaults` (UTC, `🔗`, `✓`, `✗`).
- `a malformed timezone fails fast` — `AdminConfig.from` returns
  `Result.failure`.

Integration additions to existing resource tests:

- `UsersResourceTest`: user detail renders `isAdmin` as the bool glyph (not the
  literal `true`) and the id field row carries a `🔗` to `/user/{id}`.
- `SessionsResourceTest`: a session's `userId` cell renders a `🔗` linking to
  `/user/{userId}` in both list and detail.
- `ExtractionRunsResourceTest`: the `convoId` cell on the **detail** page
  renders with **no** link (unsupported slug; `convoId` is `inList = false`, so
  it appears only on detail).
- `UsersResourceTest`: a list row's id cell renders the id as plain text
  followed by a `🔗` to `/user/{id}` (no hyperlink wrapping the value text — the
  uniform `renderCell` form); a blank id value renders nothing (no text, no
  glyph).
- `StudentsResourceTest`: a coaching-memory edge panel renders an id cell with a
  `🔗` and a `Created` cell as a formatted date whose `title` is the source ISO;
  and the embedded student panel renders a `LabeledCell` of type `TIMESTAMP`
  (the embedded student's timestamp field) as a formatted date, exercising the
  embedded-panel render path.

The typed-column conversion for `ClaimsResource`/`ObservationsResource`/
`SystemPromptsResource` is covered by their existing suites plus
`CellRenderTest` plus compilation, so no new per-resource assertions are added.

Verification: `nix develop -c bin/test admin-server --force` (JUnit XML counts
checked; independent runs need `--force`).

## Implementation Plan

1. **Display config.** Add `DisplayConfig` to `AdminConfig.kt` and parse the
   `admin.display` block (validate timezone via `ZoneId.of`); add the
   `display {}` block to `admin-server.conf`. Add `AdminConfigTest.kt`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin` and
     `nix develop -c bin/test admin-server --tests "ed.unicoach.admin.AdminConfigTest" --force`.
2. **Central helpers + styles.** Add `render/CellRender.kt` (`AdminDisplay`,
   `renderValue`/`renderRefLink`/`renderCell`, the formatter); add the CSS
   classes to `Layout.kt`. Add `render/CellRenderTest.kt`.
   - Verify: compile +
     `nix develop -c bin/test admin-server --tests "ed.unicoach.admin.render.CellRenderTest" --force`.
3. **Typed descriptors.** Add `AdminField.refSlug`; convert
   `EdgePanel.Table.columns` to `List<Column>`, add `Column`, and convert
   `Embedded.fields` to `List<LabeledCell>`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin` (expected to
     fail at callers — fixed in steps 4–5).
4. **Thread the context + retarget views.** Add `display` to
   `registerAdminRoutes` and `renderList`/`renderDetail`; build `AdminDisplay`
   in `adminModule`; rewrite `ListView.kt`/`DetailView.kt` cell emission to use
   the helpers. `display` propagates through `DetailView.kt`'s private
   `renderEdgePanel`/`renderTablePanel`/`renderEmbeddedPanel` helpers.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`.
5. **Update resources.** Set `refSlug` on id/FK fields and convert edge
   `columns`/embedded `fields` to the typed forms in `UsersResource`,
   `StudentsResource`, `SessionsResource`, `ObservationsResource`,
   `ClaimsResource`, `ExtractionRunsResource`, `SystemPromptsResource`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin` +
     `nix develop -c bin/test admin-server --force`.
6. **Integration assertions.** Add the convention assertions to
   `UsersResourceTest`, `SessionsResourceTest`, `ExtractionRunsResourceTest`,
   `StudentsResourceTest`.
   - Verify: `nix develop -c bin/test admin-server --force` (declared vs
     executed test counts match).

## Files Modified

- `admin-server/src/main/kotlin/ed/unicoach/admin/AdminConfig.kt`
- `admin-server/src/main/resources/admin-server.conf`
- `admin-server/src/main/kotlin/ed/unicoach/admin/Application.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/engine/AdminField.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/engine/EdgePanel.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/engine/AdminRouting.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/render/CellRender.kt` (new)
- `admin-server/src/main/kotlin/ed/unicoach/admin/render/ListView.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/render/DetailView.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/render/Layout.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/UsersResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/StudentsResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/SessionsResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ObservationsResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ClaimsResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ExtractionRunsResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/SystemPromptsResource.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/AdminConfigTest.kt` (new)
- `admin-server/src/test/kotlin/ed/unicoach/admin/render/CellRenderTest.kt`
  (new)
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/UsersResourceTest.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/SessionsResourceTest.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/ExtractionRunsResourceTest.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/StudentsResourceTest.kt`
