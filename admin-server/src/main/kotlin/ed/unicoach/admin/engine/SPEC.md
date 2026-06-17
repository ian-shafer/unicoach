# SPEC.md — `admin-server/src/main/kotlin/ed/unicoach/admin/engine`

## I. Overview

The descriptor-driven engine of the admin website. A registry of typed resource
**descriptors** — one per database table — drives navigation, list/detail
rendering, forms, and relationship weaving entirely from declaration; the engine
itself is table-agnostic and contains no per-table logic. A descriptor's
[`AdminKind`](./AdminResource.kt) selects its allowed operation set and render
strategy, so adding a table is a matter of declaring a descriptor, not extending
the router. The engine never builds SQL: every read and write delegates to the
descriptor's typed handlers, which are backed by the existing DAOs.

- [`AdminResource.kt`](./AdminResource.kt) — the descriptor contract
  (`AdminResource<ROW, ID>`) and the `AdminKind` taxonomy.
- [`AdminField.kt`](./AdminField.kt) — `AdminField` column descriptor and the
  `FieldType` render/input strategy.
- [`AdminEdge.kt`](./AdminEdge.kt) — declared relationships (`Parent`,
  `HasMany`, `History`, `Embedded`).
- [`EdgePanel.kt`](./EdgePanel.kt) — the type-erased, render-ready payload an
  edge resolves to.
- [`AdminRegistry.kt`](./AdminRegistry.kt) — slug-keyed lookup over all
  descriptors.
- [`AdminRouting.kt`](./AdminRouting.kt) — generic per-resource route
  registration driven by the registry and each descriptor's capabilities.

---

## II. Invariants

### Descriptor-driven routing

- The engine MUST register routes purely from the registry and each descriptor's
  declared capabilities. It MUST NOT contain any per-table branch — a new table
  is added by registering a descriptor, never by editing the router.
- Route registration for create, update, undelete, and delete MUST be
  **conditional on the corresponding handler being non-null**. When
  `create`/`update`/`delete`/`undelete` is null the route MUST NOT be registered.
  The mutation handlers (`POST /{slug}`, `POST /{slug}/{id}`,
  `POST /{slug}/{id}/delete`, `GET /{slug}/{id}/edit`) additionally re-check the
  handler at request time and MUST return a not-found page if it is absent.
- Only resources with `topLevel == true` MUST receive the dashboard nav entry
  and the `GET /{slug}` list route plus the full per-resource route set. A
  non-top-level resource MUST NOT be listed or reachable through a generated
  route.
- `registerExtraRoutes` MUST be invoked for **every** registered resource
  (top-level or not), inside the same route scope as the generic routes, so any
  owner-nested action endpoints sit behind the same authorization gate.

### Canonical routing

- Every entity MUST have exactly **one** detail URL: `/{slug}/{id}`. The engine
  MUST NOT register nested detail URLs (never `/user/{id}/session/{id}`).
- Relationship panels MUST link child and parent rows to that single canonical
  `/{slug}/{id}` URL. An [`EdgePanel`](./EdgePanel.kt)'s `href` is always a
  canonical detail link, never a nested path.
- `EMBEDDED_ENTITY` is the **sole** kind exempt from owning any standalone URL:
  it MUST NOT have a list route or a detail route. Its mutating actions are
  nested under its owner via `registerExtraRoutes`. Its `get`/`list` members MUST
  still be implemented (the owner's `Embedded` edge calls `get` to render the
  inline panel) but MUST NEVER be bound to a route.

### Soft-delete visibility

- Admin reads MUST surface soft-deleted rows. List reads MUST pass
  `SoftDeleteScope.ALL`; detail and edit reads MUST pass `includeDeleted = true`.
  A soft-deleted row therefore remains visible and is marked via `isDeleted`.
- An `undelete` route MUST be offered only when the descriptor supplies a
  non-null `undelete` handler; resources without a restore path MUST NOT expose
  one.

### Paging without `COUNT(*)`

- The engine MUST page lists with a **fixed page size** and MUST NOT issue a
  count query to detect a following page. It MUST request `pageSize + 1` rows: a
  present surplus row signals a next page, drives the "next" link, and MUST be
  dropped before the table is rendered.
- A "previous" link MUST render whenever the resolved offset is greater than
  zero. The offset MUST be parsed from the request and MUST be coerced to a
  non-negative value.

### Engine / handler boundary

- The engine MUST NOT build SQL or know table columns. Every read, create,
  update, delete, undelete, and edge resolution MUST be delegated to the
  descriptor's typed handlers, which the descriptor backs with the appropriate
  DAOs. The engine consumes only `Result`, domain rows, the rendered `cells`
  map, and resolved `EdgePanel`s.
- Edge resolution MUST be performed inside the descriptor (`resolveEdges`), which
  owns the foreign keys and DAOs, and MUST return flat, type-erased
  [`EdgePanel`](./EdgePanel.kt)s. The router MUST NOT carry the descriptor's
  `ROW`/`ID` generics into edge rendering — the type erasure at the `EdgePanel`
  boundary is deliberate, keeping the generic router free of per-resource type
  parameters.

### Identifier handling

- A path id segment MUST be parsed via the descriptor's `parseId`; a malformed id
  (null parse) MUST resolve to a not-found page, never a server error. The
  canonical path segment of an id MUST be produced via `idToPath`, the declared
  inverse of `parseId` — redirect targets after create/update/undelete MUST be
  built with `idToPath`, never by stringifying the id directly.

### Sensitive fields

- A field marked `sensitive` MUST be redacted in views, MUST be absent from all
  forms (create and edit), and MUST NEVER be logged. This holds regardless of the
  field's `editable` flag.

### Create-only extra inputs

- `createExtraInputs` MUST appear only on the create form and MUST be passed
  through the submitted form map to the `create` handler. They MUST NOT be
  rendered in detail or edit views — they are non-stored inputs (e.g. a plaintext
  password the handler hashes), never persisted columns.

### Update concurrency

- The update handler MUST forward the form's `version` so the DAO performs the
  versioned (OCC) write. A `ConcurrentModificationException` surfaced by the
  handler MUST render a conflict page prompting reload — it MUST NEVER be
  silently swallowed or allowed to overwrite the row.

### Operation/kind matrix is descriptor-owned

- The mapping from `AdminKind` to the offered operation set (which of
  create/update/delete/undelete are non-null, and whether delete is soft or hard)
  MUST be expressed by each descriptor's handler nullability — the engine MUST
  NOT enforce kind-specific operation rules itself. The engine's only kind-aware
  behavior is the `EMBEDDED_ENTITY` route exemption above.

---

## III. Behavioral Contracts

### `AdminResource<ROW, ID>` — [`AdminResource.kt`](./AdminResource.kt)

The per-table descriptor. `ROW` is the domain model, `ID` its typed id.

- **Declaration members** (`slug`, `title`, `kind`, `topLevel`, `fields`,
  `edges`, `createExtraInputs`): pure data read by the router and renderers; no
  I/O.
- **`rowId`, `cells`, `isDeleted`, `parseId`, `idToPath`**: pure projections over
  a row or id; no I/O. `cells` produces the `field name -> rendered string` map
  used for both list/detail tables and pre-filling the edit form. `idToPath` is
  the exact inverse of `parseId`.
- **`list(db, limit, offset, scope)`**: reads a page of rows via a DAO.
  - *Side effects*: DB read only.
  - *Errors*: returns `Result.failure` carrying the DAO's typed exception (e.g.
    `NotFoundException`, `DatabaseException`); the engine renders it via the
    shared error responders.
  - *Idempotent*: yes (read-only).
- **`get(db, id, includeDeleted)`**: reads one row via a DAO.
  - *Side effects*: DB read only.
  - *Errors*: `Result.failure` with the DAO's exception (notably
    `NotFoundException` for an unknown/out-of-scope id).
  - *Idempotent*: yes.
- **`create(db, form)?`**: null when the kind offers no create. Hashes/transforms
  non-stored inputs as needed and delegates to a DAO create.
  - *Side effects*: one DB write (insert, firing the table's triggers).
  - *Errors*: `Result.failure` with a constraint exception
    (`DuplicateEmailException`, `ConstraintViolationException`) or
    `IllegalArgumentException` for bad input → form re-rendered with the message;
    other failures → DAO-error page.
  - *Idempotent*: no.
- **`update(db, id, form)?`**: null when the kind offers no update. Performs the
  versioned write under OCC.
  - *Side effects*: one DB write (versioned update; bumps version, captures
    history per the table's triggers).
  - *Errors*: `ConcurrentModificationException` → conflict page; constraint /
    `IllegalArgumentException` → form re-render; else DAO-error page.
  - *Idempotent*: no (OCC — a stale `version` conflicts).
- **`delete(db, id)?`**: null when the kind offers no delete. Soft or hard per
  the descriptor's backing DAO.
  - *Side effects*: one DB write (sets `deleted_at` + bumps version for soft;
    physical `DELETE` for hard).
  - *Errors*: `Result.failure` → DAO-error page.
  - *Idempotent*: no.
- **`undelete(db, id)?`**: null unless restore is offered (soft-delete entities
  only). Restores a soft-deleted row.
  - *Side effects*: one DB write.
  - *Idempotent*: no.
- **`resolveEdges(db, row)`**: resolves declared `edges` into flat `EdgePanel`s
  using the descriptor's DAOs/foreign keys. Default returns an empty list.
  - *Side effects*: DB reads (one or more per edge).
  - *Errors*: `Result.failure` → DAO-error page on the detail route.
  - *Idempotent*: yes (read-only).
- **`registerExtraRoutes(scope, db)`**: default no-op. Registers owner-nested
  action endpoints (e.g. an embedded entity's nested create/update/delete) into
  the given gated route scope.
  - *Side effects*: route-table registration only; no I/O at call time.
  - *Idempotent*: no — calling twice installs duplicate routes.

### `AdminKind` — [`AdminResource.kt`](./AdminResource.kt)

The taxonomy (`ENTITY`, `EMBEDDED_ENTITY`, `IMMUTABLE_ENTITY`, `LOG`,
`NON_ENTITY`, `SUPPORT`) classifying a resource. Its single engine-observable
effect is the `EMBEDDED_ENTITY` route exemption (see Invariants); the concrete
operation set per kind is realized through handler nullability on the descriptor,
not branched on here.

### `AdminField` / `FieldType` — [`AdminField.kt`](./AdminField.kt)

A column descriptor: how a field renders in views and forms. `editable == false`
renders the field read-only; `sensitive == true` triggers the redaction /
form-omission / no-log invariant above. `enumValues` is consulted only when
`type == FieldType.ENUM`. Pure data — no behavior or I/O.

### `AdminEdge` — [`AdminEdge.kt`](./AdminEdge.kt)

The declared relationships a detail page may render. Each variant maps to one
edge renderer and is resolved by the owning descriptor's `resolveEdges` into an
`EdgePanel`:

- **`Parent`** — a link + summary of one parent row, targeting `targetSlug`'s
  canonical detail URL.
- **`HasMany`** — an inline table of child rows, each linking to its own
  `targetSlug` detail URL.
- **`History`** — a read-only version-history panel for the current row.
- **`Embedded`** — an owned (`EMBEDDED_ENTITY`) resource rendered inline; carries
  the embedded `AdminResource`. The embedded resource has no standalone URL; its
  actions nest under the owner. Pure declarative data; no behavior.

### `EdgePanel` — [`EdgePanel.kt`](./EdgePanel.kt)

The type-erased, render-ready output of edge resolution. Resolving inside the
descriptor (typed, DAO-aware) and rendering from this flat panel keeps the
"engine renders from the descriptor" boundary without leaking generics across the
router. Variants:

- **`ParentLink`** — a canonical detail `href` plus a one-line summary.
- **`ParentAbsent`** — a null parent rendered as an absence note (e.g. an
  anonymous session).
- **`Table`** — child rows, each carrying its canonical detail `href` (nullable)
  and ordered `cells` under named `columns`.
- **`Embedded`** — an owned entity rendered inline: when `present` is false it
  offers a create form; otherwise it shows fields plus edit/delete actions and
  any `nested` panels (e.g. the embedded entity's own version history). Carries
  the owner slug/id so its nested actions POST to owner-nested endpoints.

Pure data carrier — no behavior or I/O.

### `AdminRegistry` — [`AdminRegistry.kt`](./AdminRegistry.kt)

The slug-keyed catalog of descriptors, constructed once at startup from the
resource list.

- **`all`**: every registered resource, in declaration order.
- **`topLevel`**: the subset with `topLevel == true` (nav + list-route members).
- **`bySlug(slug)`**: returns the descriptor for a canonical slug, or `null` if
  unknown.
- *Side effects*: none — pure in-memory lookup.
- *Idempotent*: yes.
- Slug uniqueness: descriptors are associated by `slug`; a duplicate slug in the
  construction list collapses to the last entry (last-wins), so slugs MUST be
  unique across registered resources.

### `Route.registerAdminRoutes(registry, database)` — [`AdminRouting.kt`](./AdminRouting.kt)

Registers the dashboard, the generic per-resource routes for every top-level
resource, and each resource's owner-nested extra routes.

- **Routes registered** (per top-level resource, conditionally):

  | Route                      | Condition          | Effect                                              |
  | -------------------------- | ------------------ | --------------------------------------------------- |
  | `GET /`                    | always             | Dashboard: nav of top-level sections                |
  | `GET /{slug}`              | `topLevel`         | List page (table of `cells`; rows link to detail)   |
  | `GET /{slug}/new`          | `create != null`   | Create form (`fields` + `createExtraInputs`)        |
  | `POST /{slug}`             | `create != null`   | Create → redirect to new row's canonical detail      |
  | `GET /{slug}/{id}`         | always             | Detail page (field table + resolved edge panels)     |
  | `GET /{slug}/{id}/edit`    | always*            | Edit form; missing id/`update` → not-found page      |
  | `POST /{slug}/{id}`        | always*            | Update → redirect to detail; OCC conflict → conflict page |
  | `POST /{slug}/{id}/delete` | always*            | Delete → redirect to list; missing id/`delete` → not-found |
  | `POST /{slug}/{id}/undelete`| `undelete != null`| Undelete → redirect to detail                       |

  *Registered unconditionally but re-checks handler nullability at request time
  and returns a not-found page when absent. Create routes are gated at
  registration time.

- **Side effects**: route-table registration at call time; the registered
  handlers perform DB reads/writes through descriptor handlers and emit HTML or
  redirects. No SQL is built here.
- **Error handling**: handler `Result.failure` is dispatched to the shared
  responders — not-found, conflict, constraint/validation (form re-render), and a
  generic DAO-error page — never an unhandled throw to the client. A malformed id
  yields a not-found page.
- **Idempotency**: registration is not idempotent (duplicate routes on a second
  call). The registered `GET` handlers are read-only/idempotent; `POST` mutation
  handlers are not (subject to OCC for updates).

---

## IV. Infrastructure & Environment

- **Fixed page size**: list pagination uses a single compile-time page size
  constant in [`AdminRouting.kt`](./AdminRouting.kt); it is not configurable and
  not derived from any environment variable.
- **No module-specific config**: this package reads no HOCON keys or environment
  variables directly. The `Database` and route scope are injected by the
  admin-server bootstrap; cookie, bind, and session configuration live outside
  this package.
- The package depends on `:db` (`Database`, `SoftDeleteScope`, the DAO exception
  types) and Ktor server routing/HTML. It builds no SQL and owns no schema.

---

## V. History

- [x] [RFC-60: Admin Website (Framework + Users Spine)](../../../../../../../../rfc/60-admin-website.md)
      — introduced the descriptor/registry/generic-routing engine:
      `AdminResource`/`AdminKind`, `AdminField`/`FieldType`, `AdminEdge`,
      `EdgePanel`, `AdminRegistry`, and `AdminRouting`. Established the
      canonical-routing invariant, the `EMBEDDED_ENTITY` no-standalone-URL
      exemption, descriptor-driven conditional route registration,
      include-deleted admin reads, `limit + 1` count-free paging, the
      delegate-to-DAO (never build SQL) write path, and sensitive-field
      redaction.
