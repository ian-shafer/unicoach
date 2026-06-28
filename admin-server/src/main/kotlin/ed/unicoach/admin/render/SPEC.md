# SPEC.md — `admin-server/src/main/kotlin/ed/unicoach/admin/render`

## I. Overview

The server-side HTML rendering layer of the admin website. It turns a resolved
**resource descriptor** (`AdminResource`, `AdminField`, `EdgePanel` from the
engine) plus already-fetched row data into `kotlinx.html` markup: the shared
page chrome (nav sidebar + content), list tables, detail pages (field table +
one panel per edge), field-typed create/edit forms, and standalone HTML error
pages. It is the view layer only — it holds no domain logic, reads no database,
and mutates no state.

RFC 79 introduced three central rendering helpers in the new
[`CellRender.kt`](./CellRender.kt) that make four display conventions uniform
across all cells: datetime formatting in a configured timezone, boolean glyphs,
entity-reference id-link glyphs, and blank suppression. RFC 81 extended
`renderValue` with a `FieldType.JSON` branch that pretty-prints JSON cell values
inside a `<pre>` element. RFC 83 added a `FieldType.UUID` branch (compacting a
UUID id to an ellipsis plus its last `idTailChars` characters with a
click-to-copy button) and the admin server's first delegated click-to-copy
script. Every cell (list rows, detail field table, all edge-table and
embedded-panel cells) routes through the same `renderCell` helper. The render
context (`AdminDisplay`) is constructed once in `adminModule` and threaded into
the render functions; the render layer never holds the registry directly.

---

## II. Behavioral Contracts

All functions in this directory are `kotlinx.html` builder extensions (or, for
error pages, an `ApplicationCall` extension that writes a response). None
performs DB or network access; their only effect is HTML emission (and, for the
error helpers, a single HTTP response write). Each consumes the descriptor and
pre-fetched row data passed by the engine and produces markup without mutating
the descriptor, the row data, or any caller state. They carry no idempotency
concern beyond "calling twice emits the markup twice."

### `AdminDisplay` — [`CellRender.kt`](./CellRender.kt)

The render-time context for the display conventions of RFC 79 and RFC 83.
Constructed once in `adminModule` (via `DisplayConfig.toAdminDisplay`) and
threaded into the render functions; the render layer never holds the registry,
only this object.

- **`zone: ZoneId`** — the timezone all datetimes render in.
- **`idLinkGlyph: String`** — the entity-reference link glyph (e.g. `🔗`).
- **`boolTrueGlyph: String`** — the true boolean glyph (e.g. `✓`).
- **`boolFalseGlyph: String`** — the false boolean glyph (e.g. `✗`).
- **`idTailChars: Int`** — the number of trailing characters kept when
  compacting a UUID id (RFC 83). Positive; validated at startup.
- **`copyGlyph: String`** — the glyph text of the per-UUID-cell click-to-copy
  button (RFC 83).
- **`isSupported: (slug: String) -> Boolean`** — `registry.bySlug(it) != null`;
  the render layer uses only this predicate, not the registry itself.

### `DisplayConfig.toAdminDisplay(isSupported)` — [`CellRender.kt`](./CellRender.kt)

Converts the parsed `DisplayConfig` into an `AdminDisplay` by field-by-field
copy plus the `isSupported` predicate. Centralising the conversion here (next to
both types) means a new `DisplayConfig` field cannot be silently omitted at the
`adminModule` wiring site.

### `FlowContent.renderValue(value, type, display)` — [`CellRender.kt`](./CellRender.kt)

Renders the typed value only — no entity link. The single place the datetime,
boolean, and UUID compaction display conventions live.

- **`FieldType.TIMESTAMP`**: the source instant formatted as `MMM d, yyyy`
  (`Locale.ENGLISH`) in `display.zone`, in a `<span>` whose `title` attribute
  carries the verbatim source ISO string for hover. A blank value renders
  nothing. A value that does not parse as an `Instant` is logged at WARN and
  rendered as raw text (never throws).
- **`FieldType.BOOL`**: `"true"` → the configured true glyph in a
  `<span class="bool-true">`; `"false"` → the configured false glyph in
  `<span class="bool-false">`. A blank value renders nothing. Any other value
  surfaces as raw text (rather than being masked as false) so an unexpected
  value is visible. `cells()` always stringifies bools as `"true"`/`"false"`, so
  the raw-text branch is unreachable in practice.
- **`FieldType.JSON`**: the cell string is parsed as a JSON element and
  re-emitted pretty-printed inside a `<pre>` element (via a private
  `PRETTY_JSON = Json { prettyPrint = true }` instance). A blank value renders
  nothing. A value that does not parse as JSON is logged at WARN (logger name
  `ed.unicoach.admin.CellRender`) and rendered as raw text (never throws),
  mirroring the TIMESTAMP fallback.
- **`FieldType.UUID`** (RFC 83): a compacted UUID id rendered as: (1) a `<span>`
  carrying the full value as a hover `title`, whose text is `…` (U+2026)
  followed by `value.takeLast(display.idTailChars)` when
  `value.length > display.idTailChars`, or the verbatim value otherwise (no
  misleading ellipsis for short values); (2) a
  `<button type="button"
  class="id-copy" data-full="<value>">` carrying
  `display.copyGlyph` as text. The `type="button"` prevents accidental form
  submission from an enclosing form. A blank value renders nothing (no span, no
  button).
- **All other types**: the raw text verbatim.

### `FlowContent.renderRefLink(value, refSlug, display)` — [`CellRender.kt`](./CellRender.kt)

Renders the trailing entity-reference link. When `refSlug` is non-null, `value`
is non-blank, and `display.isSupported(refSlug)` is true: emits a non-breaking
space text node followed by the configured `idLinkGlyph` as a hyperlink in the
`id-link` CSS class to `/{refSlug}/{value}`. The glyph href uses the full value
(not the compacted display), so navigation targets the correct entity even for
UUID columns that render a tail only. Otherwise renders nothing. The
non-breaking space is a separate text node so the glyph never wraps to its own
line.

### `FlowContent.renderCell(value, type, refSlug, display)` — [`CellRender.kt`](./CellRender.kt)

The composite cell helper: `renderValue` then `renderRefLink`. Every cell in the
admin website (list rows, detail field table, all edge-table cells, and embedded
panel field cells) routes through this function uniformly (RFC 79). No cell
wraps its value text in a hyperlink; the trailing glyph is the sole link to the
entity, so navigation to a row's detail page is the primary-id column's own
`refSlug` glyph.

### `HTML.adminPage(pageTitle, nav, topLevelResources, content)` — [`Layout.kt`](./Layout.kt)

- **Behavior**: Emits the page `<head>` (title + inline stylesheet) and
  `<body>`. When `nav` is true, emits the sidebar (dashboard link, one link per
  `topLevelResources` entry to `/{slug}`, and a `POST /logout` button), then the
  `<main>` content, then the inline copy script. When `nav` is false, emits only
  the `<main>` content and the script — the standalone form used by login and
  error pages gets the script too, as a passive listener it is harmless where no
  id cell exists.
- **Inline styles** (`STYLES`): includes RFC-79 classes `bool-true` (green),
  `bool-false` (red), and `id-link` (no underline); and RFC-83 classes
  `button.id-copy` (minimal inline control — no border/background, glyph-sized,
  baseline-aligned) and `button.id-copy.copied` (transient green "copied" state
  toggled by the script and cleared by `setTimeout`).
- **Inline script** (`SCRIPT`, RFC 83): a single delegated `click` listener on
  `document`. A click whose `target.closest('.id-copy')` is non-null writes that
  element's `data-full` to the clipboard via `navigator.clipboard.writeText` and
  toggles a transient `copied` class removed by
  `setTimeout(_, COPY_FEEDBACK_MS)`. Guards on `navigator.clipboard` being
  present; degrades silently to a no-op on a non-secure origin (hover `title`
  remains the fallback to the full value). Emitted as the last child of `body`
  via `script { unsafe { +SCRIPT } }`, mirroring the inline-style pattern. No
  per-button state, no per-element id.
- **Inputs**: `topLevelResources` supplies nav link targets/labels; the renderer
  reads only each resource's `slug` and `title`.
- **Side effects**: HTML emission only.

### `MAIN.renderList(resource, rows, offset, pageSize, hasNext, display)` — [`ListView.kt`](./ListView.kt)

- **Behavior**: Emits the title, an optional "+ New" link when the descriptor
  exposes a `create` handler, a table whose columns are the descriptor's fields
  that are both list-visible (`inList`) and non-sensitive, one row per element
  of `rows`, and a prev/next pager. Fields flagged off-list (`inList == false`)
  or sensitive are excluded from the columns; the same field set governs the
  header cells and every body cell, so columns and values stay aligned. Every
  cell renders through `renderCell(value, field.type, field.refSlug, display)` —
  the typed value followed by the ref-link glyph. UUID id columns compact to an
  ellipsis plus the tail; BIGINT id columns render raw text. No cell's value
  text is wrapped in a hyperlink; the id column's own `refSlug` glyph is the
  sole navigation link to a row's detail page. A row reported deleted by
  `isDeleted` is marked with a "deleted" badge (after the first cell's glyph)
  rather than hidden. A null cell value renders as the empty string.
- **Pager**: A "« Previous" link appears only when `offset > 0`, targeting the
  previous offset clamped at zero (`offset - pageSize` floored at 0). A "Next »"
  link appears only when `hasNext` is true, targeting `offset + pageSize`. No
  total or page count is computed.
- **Inputs**: `rows` is the page already trimmed of the engine's surplus
  `limit + 1` probe row; `hasNext` reports whether that probe row existed.
- **Side effects**: HTML emission only.

### `MAIN.renderDetail(resource, row, edges, display)` — [`DetailView.kt`](./DetailView.kt)

- **Behavior**: Emits the heading (with a "deleted" badge when `isDeleted` is
  true), the full field table, the permitted edit/delete/undelete actions, then
  the descriptor's custom-action buttons, then one panel per resolved
  `EdgePanel` in the order supplied. A sensitive field's value is shown as a
  fixed redacted placeholder (`••• (redacted)`), never the underlying value.
  Every non-sensitive cell routes through
  `renderCell(value, field.type, field.refSlug, display)`.
- **Actions**: The "Edit" link appears only when the descriptor exposes an
  `update` handler. The delete action appears only when the descriptor exposes a
  `delete` handler and the row is not deleted; the undelete action appears only
  when the descriptor exposes an `undelete` handler and the row is deleted — the
  two are mutually exclusive for a given row state.
- **Custom actions**: After the Edit/Delete/Undelete block and before the edge
  panels, one `actionButton` is emitted per `resource.customActions` entry,
  posting to `/{slug}/{id}/{action.pathSuffix}`. Each button is rendered
  enabled-or-disabled per `action.disabledReason(row)`.
- **Edge panels**: Each `EdgePanel` variant has a fixed presentation. A
  `ParentLink` renders its summary as a hyperlink to `panel.href`; a
  `ParentAbsent` renders a fixed "(none)" note; a `Table` renders its typed
  columns and rows through `renderTablePanel` (every cell routes through
  `renderCell` using the column's `type` and `refSlug`; an empty table collapses
  to a "(none)" note); an `Embedded` renders an inline create form when the
  owned entity is absent, otherwise its `LabeledCell` field table (each cell
  through `renderCell` using the cell's `type` and `refSlug`) plus an inline
  edit form, a delete action, and any nested table panels. Embedded and nested
  mutations post to the owner-nested action paths built from the `Embedded`
  panel's `ownerSlug`/`ownerId`.
- **Inputs**: `edges` is the already-resolved, render-ready panel list produced
  by the resource; this function performs no edge resolution and no DAO calls.
- **Side effects**: HTML emission only.

### `FlowContent.actionButton(action, label, disabledReason)` — [`DetailView.kt`](./DetailView.kt)

- **Behavior**: Emits a single-button `POST` form to `action`. Used for
  delete/undelete, embedded-panel nested actions, and descriptor-declared custom
  actions. The button is enabled iff `disabledReason` is null: when
  `disabledReason` is non-null the button carries the HTML `disabled` attribute
  and a `title` set to the reason string; when null the button is enabled and
  carries neither attribute. `disabledReason` defaults to null.
- **Side effects**: HTML emission only.

### `FlowContent.renderForm(action, editableFields, values, version, submitLabel, extra)` — [`FormView.kt`](./FormView.kt)

- **Behavior**: Emits a `POST` form to `action`. When `version` is non-null,
  emits a hidden `version` input first (driving an optimistic-concurrency update
  on edit); when `version` is null (create), no hidden version input is emitted.
  Emits one typed input per field that is both `editable` and non-`sensitive`,
  pre-filled from `values[field.name]`; read-only fields (id, timestamps,
  version) and sensitive fields are never emitted as inputs, so a sensitive
  value cannot be submitted back through a form. Invokes the optional `extra`
  builder for create-only auxiliary inputs (e.g. a plaintext password), then the
  submit button.
- **Field-type → input mapping** (via the private `renderInput`): `JSON` and
  `MULTILINE` render a `<textarea>` carrying the raw value verbatim; `BOOL` a
  checkbox whose checked state reflects the current value `"true"` and whose
  submitted value is `"true"`; `ENUM` a `<select>` populated from the field's
  `enumValues` with the current value pre-selected; `INT` a numeric input; every
  other type (including `UUID`, though UUID fields are always `editable = false`
  and never reach a form) a typed text input. JSON well-formedness is validated
  before submit by the calling handler, not this layer.
- **Error handling**: This is the re-render target on a duplicate/constraint
  failure — callers pass the rejected `values` back through to preserve the
  operator's input; the renderer itself raises nothing.
- **Side effects**: HTML emission only.

### `ApplicationCall.respondErrorPage(status, heading, detail)` — [`ErrorPages.kt`](./ErrorPages.kt)

- **Behavior**: Writes a standalone HTML page (nav suppressed, `nav = false`)
  with the given heading and detail at the given HTTP status.
- **Side effects**: Writes exactly one HTTP response. No DB access.

### `ApplicationCall.respondNotFound(detail)` / `respondConflict()` / `respondServiceUnavailable()` — [`ErrorPages.kt`](./ErrorPages.kt)

- **Behavior**: Fixed-status convenience wrappers over `respondErrorPage` —
  **404** not-found, **409** OCC-conflict reload prompt, **503** transient
  database error — each with its standard heading and guidance text. The 409 and
  503 pages carry retry/reload guidance; the 409 page prompts a reload rather
  than overwriting.
- **Side effects**: One HTTP response each.

### `ApplicationCall.respondDaoError(error)` — [`ErrorPages.kt`](./ErrorPages.kt)

- **Behavior**: The single DAO-failure classifier shared by the generic engine
  routes and the owner-nested action endpoints. Maps `NotFoundException` → 404,
  `ConcurrentModificationException` → 409 reload page, `TransientError` → 503,
  and any other throwable → 503. Centralizing it keeps every write path's
  error-page outcome identical.
- **Error handling**: It consumes a `Throwable` and renders a page; it never
  re-throws. It does not handle duplicate/constraint violations — those are
  re-rendered into the originating form by the calling handler (with the
  submitted values preserved and a field error), not routed here.
- **Side effects**: One HTTP response.
- **Note**: This is the layer's only inbound reference to DAO exception types
  (`NotFoundException`, `ConcurrentModificationException`) and to
  `TransientError` — used solely to classify an already-raised failure into a
  status code, not to perform persistence.

---

## III. Infrastructure & Environment

- **`io.ktor:ktor-server-html-builder`** — supplies
  `ApplicationCall.respondHtml`, used by the error-page helpers to write
  `kotlinx.html` documents as HTTP responses. This is the render layer's only
  Ktor coupling, confined to `ErrorPages.kt`; the list/detail/form/layout
  builders are framework-agnostic `kotlinx.html` extensions.
- **`kotlinx.html`** — the HTML DSL all view builders target. `button`, `span`,
  `script`, and `attributes[...]` (for `data-full`) are used by the RFC-83 UUID
  render path.
- **`java.time`** — `Instant`, `ZoneId`, `DateTimeFormatter` used by the
  `TIMESTAMP` renderer in `CellRender.kt`. No new library dependency.
- **`kotlinx.serialization.json`** — `Json`, `JsonElement` used by the `JSON`
  renderer in `CellRender.kt` to parse and pretty-print JSON cell values. Flows
  transitively via `:common`; no new direct dependency in `admin-server`.
- No environment variables or config keys are read by this directory. Display
  configuration (timezone, glyphs, `idTailChars`, `copyGlyph`) lives in
  `admin-server.conf` / `DisplayConfig` / `AdminConfig`, consumed by the
  application bootstrap, not here. `AdminDisplay` is the already-parsed and
  pre-wired context object threaded into render functions.

---

## IV. History

- [x] [RFC-60: Admin Website (Framework + Users Spine)](../../../../../../../../rfc/60-admin-website.md)
- [x] [RFC-63: Admin System Prompts](../../../../../../../../rfc/63-admin-system-prompts.md)
- [x] [RFC-76: Admin email-verification actions](../../../../../../../../rfc/76-admin-email-verification-actions.md)
- [x] [RFC-79: Admin display conventions](../../../../../../../../rfc/79-admin-display-conventions.md)
      — added [`CellRender.kt`](./CellRender.kt) with `AdminDisplay`,
      `DisplayConfig.toAdminDisplay`, and the three central helpers
      `renderValue` / `renderRefLink` / `renderCell`; added `bool-true`,
      `bool-false`, and `id-link` CSS classes to `Layout.kt`; rewrote
      `ListView.kt` to accept `display` and route every cell through
      `renderCell`; rewrote `DetailView.kt` to accept `display` and route the
      detail field table, edge-table cells (`renderTablePanel`), and embedded
      field cells (`renderEmbeddedPanel`) through `renderCell`.
      `EdgePanel.Table` rows no longer carry an `href`; navigation is via the
      primary-id column's `refSlug` glyph. `EdgePanel.Embedded.fields` is now
      `List<LabeledCell>`.
- [x] [RFC-81: Admin conversation views](../../../../../../../../rfc/81-admin-conversation-views.md)
      — added `FieldType.JSON` branch to `renderValue` in
      [`CellRender.kt`](./CellRender.kt): parses the cell string as a JSON
      element and re-emits it pretty-printed inside a `<pre>` element; on parse
      failure logs at WARN and emits raw text (never throws). Added the private
      `PRETTY_JSON = Json { prettyPrint = true }` instance and the private
      `renderJsonValue` helper in `CellRender.kt`.
- [x] [RFC-83: Compact display of entity id columns](../../../../../../../../rfc/83-admin-compact-id-display.md)
      — added `AdminDisplay.idTailChars` and `AdminDisplay.copyGlyph`; carried
      both through `DisplayConfig.toAdminDisplay`. Added `renderIdValue`
      (private) and the `FieldType.UUID -> renderIdValue(value, display)` branch
      in `renderValue`: a compacted `<span title="<full>">…<tail></span>` plus a
      `<button type="button" class="id-copy" data-full="<full>">` carrying the
      configured `copyGlyph`. Added the `SCRIPT` constant to `Layout.kt` with
      one delegated `document`-level `click` listener that copies `data-full` to
      the clipboard and toggles a transient `copied` class; emitted as the last
      child of `body` via `script { unsafe { +SCRIPT } }` on every page. Added
      `button.id-copy` and `button.id-copy.copied` CSS rules to `STYLES`.
      `renderCell` and `renderRefLink` are unchanged — the glyph `href`
      continues to use the full value, so navigation targets the correct entity.
