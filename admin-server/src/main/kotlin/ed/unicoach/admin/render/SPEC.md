# SPEC.md — `admin-server/src/main/kotlin/ed/unicoach/admin/render`

## I. Overview

The server-side HTML rendering layer of the admin website. It turns a resolved
**resource descriptor** (`AdminResource`, `AdminField`, `EdgePanel` from the
engine) plus already-fetched row data into `kotlinx.html` markup: the shared
page chrome (nav sidebar + content), list tables, detail pages (field table +
one panel per edge), field-typed create/edit forms, and standalone HTML error
pages. It is the view layer only — it holds no domain logic, reads no database,
and mutates no state.

---

## II. Invariants

### Purity of the render layer

- Rendering functions MUST be pure with respect to persistence: they consume the
  descriptor and pre-fetched row data passed by the engine and produce HTML.
  They MUST NOT open a `Database` connection, call a DAO, or perform any read or
  write. All data access happens in the engine/resource layer before a render
  function is invoked.
- A render function MUST NOT mutate the descriptor, the row data, or any caller
  state. Its only effect is emitting nodes into the `kotlinx.html` builder it is
  scoped to.

### Sensitive-field handling

- A field whose `sensitive` flag is set MUST be redacted in the detail view
  (rendered as a fixed placeholder, never the underlying value) and MUST be
  excluded from the list view's columns.
- A `sensitive` field MUST NOT be emitted as any form input — neither on create
  nor on edit. The form renderer drops sensitive fields regardless of their
  `editable` flag, so a sensitive value can never be submitted back through a
  form.

### Field-type → input mapping

- The form input emitted for a field MUST be determined solely by its
  `FieldType`: `JSON` and `MULTILINE` render a `<textarea>`; `BOOL` a checkbox
  whose checked state reflects the current value `"true"` and whose submitted
  value is `"true"`; `ENUM` a `<select>` populated from the field's `enumValues`
  with the current value pre-selected; `INT` a numeric input; every other type a
  typed text input. A `JSON` field's textarea MUST carry the raw JSON string
  verbatim; well-formedness is validated before submit by the calling handler,
  not by this layer.
- The form renderer MUST emit inputs only for fields that are both `editable`
  and non-`sensitive`; read-only fields (id, timestamps, version) are never
  rendered as editable inputs.

### Form construction

- An edit form MUST carry the row's current `version` in a hidden input named
  `version` so the engine can perform an optimistic-concurrency (OCC) update. A
  create form (no version supplied) MUST NOT emit the hidden version input.
- All forms MUST submit via HTTP `POST` to the action URL supplied by the
  caller; the render layer never decides the target endpoint.
- Create-only auxiliary inputs (values not backed by a stored field, e.g. a
  plaintext password) MUST be injected only through the caller-supplied `extra`
  hook, never inferred by the renderer.

### Canonical linking

- Every link the render layer emits to an entity's detail page MUST use the
  canonical `/{slug}/{id}` form derived from the descriptor's slug and
  `idToPath`. List-row links, detail-page headings, and edge-panel rows MUST NOT
  construct nested detail URLs.
- In both list rows and edge tables, only the first cell of a row MUST be
  rendered as the canonical detail link; remaining cells render as plain text.

### Soft-delete marking

- A row reported deleted by the descriptor's `isDeleted` MUST be visually marked
  (a "deleted" badge) in both the list view and the detail heading. The render
  layer surfaces deleted rows rather than hiding them.
- The detail view MUST offer the delete action only when the descriptor exposes
  a `delete` handler and the row is not already deleted, and MUST offer the
  undelete action only when the descriptor exposes an `undelete` handler and the
  row is deleted. These two actions are mutually exclusive for a given row
  state.

### Pager

- The list view MUST render a "previous" link only when `offset > 0` and a
  "next" link only when the engine reports a surplus row (`hasNext`). The render
  layer computes the previous offset by clamping `offset - pageSize` at zero and
  the next offset as `offset + pageSize`; it MUST NOT compute totals or page
  counts (no `COUNT(*)` is available to it).

### Edge panels

- The detail view MUST render exactly one panel per resolved `EdgePanel`, in the
  order supplied. Each panel variant has a fixed presentation: a parent link
  renders its summary as a hyperlink; an absent parent renders a fixed "(none)"
  note; a table panel renders its columns and rows (an empty table collapses to
  a "(none)" note); an embedded panel renders an inline create form when the
  owned entity is absent, otherwise its field table plus an inline edit form, a
  delete action, and any nested panels.
- Embedded and nested panels MUST address the owned entity's mutations through
  the owner-nested action paths carried on the `Embedded` panel
  (`ownerSlug`/`ownerId`), never through a standalone entity URL.

### Error pages

- Error pages MUST render standalone, with the nav sidebar suppressed
  (`nav = false`).
- DAO failures MUST be classified to a single status mapping shared by all write
  paths: a not-found failure renders **404**; an OCC conflict renders **409** as
  a reload-prompt page (it MUST NOT silently overwrite); a transient or any
  otherwise-unclassified DB failure renders **503**. The 409 and 503 pages MUST
  carry retry/reload guidance text.
- The constraint/duplicate-violation case is NOT served as an error page: the
  render layer re-renders the originating form with the submitted values
  preserved and a field error, so the operator does not lose input. (The
  duplicate/constraint classification happens in the calling handler, which then
  invokes the form renderer with the prior values.)

---

## III. Behavioral Contracts

All functions in this directory are `kotlinx.html` builder extensions (or, for
error pages, an `ApplicationCall` extension that writes a response). None
performs DB or network access; their only effect is HTML emission (and, for the
error helpers, a single HTTP response write). All are pure with respect to the
descriptor and row data and carry no idempotency concern beyond "calling twice
emits the markup twice."

### `HTML.adminPage(pageTitle, nav, topLevelResources, content)` — [`Layout.kt`](./Layout.kt)

- **Behavior**: Emits the page `<head>` (title + inline stylesheet) and
  `<body>`. When `nav` is true, emits the sidebar (dashboard link, one link per
  `topLevelResources` entry to `/{slug}`, and a `POST /logout` button), then the
  `<main>` content. When `nav` is false, emits only `<main>` content — the
  standalone form used by login and error pages.
- **Side effects**: HTML emission only.
- **Inputs**: `topLevelResources` supplies nav link targets/labels; the renderer
  reads only each resource's `slug` and `title`.

### `MAIN.renderList(resource, rows, offset, pageSize, hasNext)` — [`ListView.kt`](./ListView.kt)

- **Behavior**: Emits the title, an optional "+ New" link when the descriptor
  exposes a `create` handler, a table whose columns are the descriptor's
  non-sensitive fields, one row per element of `rows` (first cell linked to the
  canonical detail page, deleted rows badged), and the prev/next pager.
- **Inputs**: `rows` is the page already trimmed of the engine's surplus
  `limit + 1` probe row; `hasNext` reports whether that probe row existed.
- **Side effects**: HTML emission only. No DB access.

### `MAIN.renderDetail(resource, row, edges)` — [`DetailView.kt`](./DetailView.kt)

- **Behavior**: Emits the heading (with deleted badge when applicable), the full
  field table (sensitive fields shown as a redacted placeholder), the
  edit/delete/undelete actions permitted by the descriptor and row state, then
  one panel per resolved `EdgePanel`.
- **Inputs**: `edges` is the already-resolved, render-ready panel list produced
  by the resource; this function performs no edge resolution and no DAO calls.
- **Side effects**: HTML emission only.

### `FlowContent.actionButton(action, label)` — [`DetailView.kt`](./DetailView.kt)

- **Behavior**: Emits a single-button `POST` form to `action`. Used for
  delete/undelete and embedded-panel nested actions.
- **Side effects**: HTML emission only.

### `FlowContent.renderForm(action, editableFields, values, version, submitLabel, extra)` — [`FormView.kt`](./FormView.kt)

- **Behavior**: Emits a `POST` form to `action`. When `version` is non-null,
  emits a hidden `version` input first. Emits one typed input per field that is
  both `editable` and non-`sensitive`, pre-filled from `values[field.name]`.
  Invokes the optional `extra` builder for create-only auxiliary inputs, then
  the submit button.
- **Error handling**: This is the re-render target on a duplicate/constraint
  failure — callers pass the rejected `values` back through to preserve the
  operator's input; the renderer itself raises nothing.
- **Side effects**: HTML emission only.

### `ApplicationCall.respondErrorPage(status, heading, detail)` — [`ErrorPages.kt`](./ErrorPages.kt)

- **Behavior**: Writes a standalone (no-nav) HTML page with the given heading
  and detail at the given HTTP status.
- **Side effects**: Writes exactly one HTTP response. No DB access.

### `ApplicationCall.respondNotFound(detail)` / `respondConflict()` / `respondServiceUnavailable()` — [`ErrorPages.kt`](./ErrorPages.kt)

- **Behavior**: Fixed-status convenience wrappers over `respondErrorPage` —
  **404** not-found, **409** OCC-conflict reload prompt, **503** transient
  database error — each with its standard heading and guidance text.
- **Side effects**: One HTTP response each.

### `ApplicationCall.respondDaoError(error)` — [`ErrorPages.kt`](./ErrorPages.kt)

- **Behavior**: The single DAO-failure classifier shared by the generic engine
  routes and the owner-nested action endpoints. Maps `NotFoundException` → 404,
  `ConcurrentModificationException` → 409 reload page, `TransientError` → 503,
  and any other throwable → 503. Centralization keeps every write path's
  error-page contract identical.
- **Error handling**: This function _consumes_ a `Throwable` and renders a page;
  it never re-throws. It does NOT handle duplicate/constraint violations — those
  are re-rendered into the originating form by the calling handler, not routed
  here.
- **Side effects**: One HTTP response.
- **Note**: This is the layer's only inbound reference to DAO exception types
  (`NotFoundException`, `ConcurrentModificationException`) and to
  `TransientError` — used solely to classify an already-raised failure into a
  status code, not to perform persistence.

---

## IV. Infrastructure & Environment

- **`io.ktor:ktor-server-html-builder`** — supplies
  `ApplicationCall.respondHtml`, used by the error-page helpers to write
  `kotlinx.html` documents as HTTP responses. This is the render layer's only
  Ktor coupling, and it is confined to `ErrorPages.kt`; the
  list/detail/form/layout builders are framework-agnostic `kotlinx.html`
  extensions.
- **`kotlinx.html`** — the HTML DSL all view builders target.
- No environment variables or config keys are read by this directory. Bind host,
  cookie, and session settings live in `admin-server.conf` / `AdminConfig`,
  consumed by the application and auth layers, not here.

---

## V. History

- [x] [RFC-60: Admin Website (Framework + Users Spine)](../../../../../../../../rfc/60-admin-website.md)
