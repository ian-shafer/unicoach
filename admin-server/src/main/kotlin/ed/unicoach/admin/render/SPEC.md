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

## II. Behavioral Contracts

All functions in this directory are `kotlinx.html` builder extensions (or, for
error pages, an `ApplicationCall` extension that writes a response). None
performs DB or network access; their only effect is HTML emission (and, for the
error helpers, a single HTTP response write). Each consumes the descriptor and
pre-fetched row data passed by the engine and produces markup without mutating
the descriptor, the row data, or any caller state. They carry no idempotency
concern beyond "calling twice emits the markup twice."

### `HTML.adminPage(pageTitle, nav, topLevelResources, content)` — [`Layout.kt`](./Layout.kt)

- **Behavior**: Emits the page `<head>` (title + inline stylesheet) and
  `<body>`. When `nav` is true, emits the sidebar (dashboard link, one link per
  `topLevelResources` entry to `/{slug}`, and a `POST /logout` button), then the
  `<main>` content. When `nav` is false, emits only the `<main>` content — the
  standalone form used by login and error pages.
- **Inputs**: `topLevelResources` supplies nav link targets/labels; the renderer
  reads only each resource's `slug` and `title`.
- **Side effects**: HTML emission only.

### `MAIN.renderList(resource, rows, offset, pageSize, hasNext)` — [`ListView.kt`](./ListView.kt)

- **Behavior**: Emits the title, an optional "+ New" link when the descriptor
  exposes a `create` handler, a table whose columns are the descriptor's fields
  that are both list-visible (`inList`) and non-sensitive, one row per element
  of `rows`, and a prev/next pager. Fields flagged off-list (`inList == false`)
  or sensitive are excluded from the columns; the same field set governs the
  header cells and every body cell, so columns and values stay aligned. Only the
  first cell of each row is rendered as the canonical `/{slug}/{id}` detail link
  (derived from the descriptor's `slug` and `idToPath`); the remaining cells
  render as plain text. A row reported deleted by `isDeleted` is marked with a
  "deleted" badge rather than hidden. A null cell value renders as the empty
  string.
- **Pager**: A "« Previous" link appears only when `offset > 0`, targeting the
  previous offset clamped at zero (`offset - pageSize` floored at 0). A "Next »"
  link appears only when `hasNext` is true, targeting `offset + pageSize`. No
  total or page count is computed.
- **Inputs**: `rows` is the page already trimmed of the engine's surplus
  `limit + 1` probe row; `hasNext` reports whether that probe row existed.
- **Side effects**: HTML emission only.

### `MAIN.renderDetail(resource, row, edges)` — [`DetailView.kt`](./DetailView.kt)

- **Behavior**: Emits the heading (with a "deleted" badge when `isDeleted` is
  true), the full field table, the permitted edit/delete/undelete actions, then
  the descriptor's custom-action buttons, then one panel per resolved
  `EdgePanel` in the order supplied. A sensitive field's value is shown as a
  fixed redacted placeholder (`••• (redacted)`), never the underlying value.
- **Actions**: The "Edit" link appears only when the descriptor exposes an
  `update` handler. The delete action appears only when the descriptor exposes a
  `delete` handler and the row is not deleted; the undelete action appears only
  when the descriptor exposes an `undelete` handler and the row is deleted — the
  two are mutually exclusive for a given row state.
- **Custom actions**: After the Edit/Delete/Undelete block and before the edge
  panels, one `actionButton` is emitted per `resource.customActions` entry,
  posting to `/{slug}/{id}/{action.pathSuffix}`. Each button is rendered
  enabled-or-disabled per `action.disabledReason(row)`: a null reason yields an
  enabled button, a non-null reason a disabled button whose `title` carries the
  reason.
- **Edge panels**: Each `EdgePanel` variant has a fixed presentation. A
  `ParentLink` renders its summary as a hyperlink to `panel.href`; a
  `ParentAbsent` renders a fixed "(none)" note; a `Table` renders its columns
  and rows (an empty table collapses to a "(none)" note, with only the first
  cell of each row linked when `row.href` is set); an `Embedded` renders an
  inline create form when the owned entity is absent, otherwise its field table
  plus an inline edit form, a delete action, and any nested table panels.
  Embedded and nested mutations post to the owner-nested action paths built from
  the `Embedded` panel's `ownerSlug`/`ownerId` (e.g.
  `/{ownerSlug}/{ownerId}/student/update`), not a standalone entity URL.
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
  other type a typed text input. JSON well-formedness is validated before submit
  by the calling handler, not this layer.
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
- **`kotlinx.html`** — the HTML DSL all view builders target.
- No environment variables or config keys are read by this directory. Bind host,
  cookie, and session settings live in `admin-server.conf` / `AdminConfig`,
  consumed by the application and auth layers, not here.

---

## IV. History

- [x] [RFC-60: Admin Website (Framework + Users Spine)](../../../../../../../../rfc/60-admin-website.md)
- [x] [RFC-63: Admin System Prompts](../../../../../../../../rfc/63-admin-system-prompts.md)
- [x] [RFC-76: Admin email-verification actions](../../../../../../../../rfc/76-admin-email-verification-actions.md)
