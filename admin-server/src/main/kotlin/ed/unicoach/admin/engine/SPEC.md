# SPEC.md — `admin-server/src/main/kotlin/ed/unicoach/admin/engine`

## I. Overview

The descriptor-driven engine of the admin website. A registry of typed resource
**descriptors** — one per database table — drives navigation, list/detail
rendering, forms, and relationship weaving entirely from declaration; the engine
is table-agnostic and contains no per-table logic. A descriptor's
[`AdminKind`](./AdminResource.kt) names its category, while its handler
nullability selects the offered operation set, so adding a table is a matter of
declaring a descriptor, not extending the router. The engine builds no SQL:
every read and write delegates to the descriptor's typed handlers, which are
backed by the existing DAOs.

- [`AdminResource.kt`](./AdminResource.kt) — the descriptor contract
  (`AdminResource<ROW, ID>`) and the `AdminKind` taxonomy.
- [`AdminField.kt`](./AdminField.kt) — `AdminField` column descriptor and the
  `FieldType` render/input strategy.
- [`AdminEdge.kt`](./AdminEdge.kt) — declared relationships (`Parent`,
  `HasMany`, `History`, `Embedded`).
- [`CustomAction.kt`](./CustomAction.kt) — a descriptor-declared per-row POST
  button the engine renders alongside the derived Edit/Delete/Undelete actions.
- [`EdgePanel.kt`](./EdgePanel.kt) — the type-erased, render-ready payload an
  edge resolves to.
- [`AdminRegistry.kt`](./AdminRegistry.kt) — slug-keyed lookup over all
  descriptors.
- [`AdminRouting.kt`](./AdminRouting.kt) — generic per-resource route
  registration driven by the registry and each descriptor's capabilities.

---

## II. Behavioral Contracts

### `AdminResource<ROW, ID>` — [`AdminResource.kt`](./AdminResource.kt)

The per-table descriptor. `ROW` is the domain model, `ID` its typed id.

- **Declaration members** (`slug`, `title`, `kind`, `topLevel`, `fields`,
  `edges`, `createExtraInputs`, `customActions`): pure data read by the router
  and renderers; no I/O.
- **`customActions`**: declarative list of per-row POST buttons (see
  [`CustomAction`](./CustomAction.kt)) the engine renders on the detail page
  after the Edit/Delete/Undelete block; default empty, so existing descriptors
  are unaffected. Pure data — the engine renders the button and evaluates each
  entry's `disabledReason` against the row, but never inspects `pathSuffix` or
  owns the action's route. Registering the matching route for each entry's
  `pathSuffix` is the descriptor's own responsibility via `registerExtraRoutes`.
- **`rowId`, `cells`, `isDeleted`, `parseId`, `idToPath`**: pure projections
  over a row or id; no I/O. `cells` produces the `field name -> rendered string`
  map used for both list/detail tables and pre-filling the edit form. `idToPath`
  is the inverse of `parseId`; redirect targets after create/update/undelete are
  built from `idToPath`, not by stringifying the id. `parseId` returns `null`
  for a malformed segment, which the router turns into a not-found page rather
  than a server error.
- **`list(db, limit, offset, scope)`**: reads a page of rows via a DAO. The
  router calls it with `SoftDeleteScope.ALL`, so soft-deleted rows are included.
  - _Side effects_: DB read only.
  - _Errors_: returns `Result.failure` carrying the DAO's typed exception (e.g.
    `NotFoundException`, `DatabaseException`); the engine renders it via the
    shared error responders.
  - _Idempotent_: yes (read-only).
- **`get(db, id, includeDeleted)`**: reads one row via a DAO. The router calls
  it with `includeDeleted = true`, so a soft-deleted row remains viewable and
  editable; `isDeleted(row)` then drives the deleted marker and undelete action.
  - _Side effects_: DB read only.
  - _Errors_: `Result.failure` with the DAO's exception (notably
    `NotFoundException` for an unknown/out-of-scope id).
  - _Idempotent_: yes.
- **`create(db, form)?`**: `null` when the kind offers no create. Hashes or
  transforms non-stored inputs as needed and delegates to a DAO create.
  - _Side effects_: one DB write (insert, firing the table's triggers).
  - _Errors_: `Result.failure` with a constraint exception
    (`DuplicateEmailException`, `ConstraintViolationException`) or
    `IllegalArgumentException` for bad input → form re-rendered with the
    message; other failures → DAO-error page.
  - _Idempotent_: no.
- **`update(db, id, form)?`**: `null` when the kind offers no update. Forwards
  the form's `version` so the DAO performs the versioned (OCC) write.
  - _Side effects_: one DB write (versioned update; bumps version, captures
    history per the table's triggers).
  - _Errors_: `ConcurrentModificationException` → conflict page prompting reload
    (the stale write is not applied); constraint / `IllegalArgumentException` →
    form re-render; else DAO-error page.
  - _Idempotent_: no (OCC — a stale `version` conflicts).
- **`delete(db, id)?`**: `null` when the kind offers no delete. Soft or hard per
  the descriptor's backing DAO.
  - _Side effects_: one DB write (sets `deleted_at` + bumps version for soft;
    physical `DELETE` for hard).
  - _Errors_: `Result.failure` → DAO-error page.
  - _Idempotent_: no.
- **`undelete(db, id)?`**: `null` unless restore is offered (soft-delete
  entities only). Restores a soft-deleted row.
  - _Side effects_: one DB write.
  - _Errors_: `Result.failure` → DAO-error page.
  - _Idempotent_: no.
- **`resolveEdges(db, row)`**: resolves the declared `edges` into flat
  `EdgePanel`s using the descriptor's DAOs and foreign keys. Default returns an
  empty list. The router carries no `ROW`/`ID` generics into edge rendering —
  the type erasure at the `EdgePanel` boundary keeps the generic router free of
  per-resource type parameters.
  - _Side effects_: DB reads (one or more per edge).
  - _Errors_: `Result.failure` → DAO-error page on the detail route.
  - _Idempotent_: yes (read-only).
- **`registerExtraRoutes(scope, db)`**: default no-op. Registers owner-nested
  action endpoints (e.g. an embedded entity's nested create/update/delete) into
  the given route scope. Invoked for every registered resource inside the same
  scope as the generic routes, so nested actions sit behind the same
  authorization gate.
  - _Side effects_: route-table registration only; no I/O at call time.
  - _Idempotent_: no — calling twice installs duplicate routes.

### `AdminKind` — [`AdminResource.kt`](./AdminResource.kt)

The taxonomy (`ENTITY`, `EMBEDDED_ENTITY`, `IMMUTABLE_ENTITY`, `LOG`,
`NON_ENTITY`, `SUPPORT`) classifying a resource. Its single engine-observable
effect is the `EMBEDDED_ENTITY` route exemption: an embedded resource is the one
kind that never owns a standalone list or detail route. The concrete operation
set per kind is realized through handler nullability on the descriptor — the
engine branches on the handlers, not on the kind, with that one exception.

### `AdminField` / `FieldType` — [`AdminField.kt`](./AdminField.kt)

A column descriptor: how a field renders in views and forms. `editable == false`
renders the field read-only; `sensitive == true` redacts the field in views and
omits it from every form (create and edit), independent of `editable`. `inList`
(default `true`) controls list-table visibility only: `inList == false` drops
the field from the list table while detail and form rendering remain unaffected
— used to keep an oversized value (e.g. a large prompt body) out of a list cell.
It is orthogonal to `sensitive`, which removes the field from forms and detail
as well. `enumValues` is consulted only when `type == FieldType.ENUM`.
`FieldType` names the render/input strategy (`TEXT`, `MULTILINE`, `INT`, `BOOL`,
`TIMESTAMP`, `JSON`, `ENUM`). Pure data — no behavior or I/O.

### `AdminEdge` — [`AdminEdge.kt`](./AdminEdge.kt)

The declared relationships a detail page may render. Each variant maps to one
edge renderer and is resolved by the owning descriptor's `resolveEdges` into an
`EdgePanel`. Linked rows target the single canonical `/{slug}/{id}` detail URL,
never a nested path:

- **`Parent`** — a link + summary of one parent row, targeting `targetSlug`'s
  canonical detail URL.
- **`HasMany`** — an inline table of child rows, each linking to its own
  `targetSlug` detail URL.
- **`History`** — a read-only version-history panel for the current row.
- **`Embedded`** — an owned (`EMBEDDED_ENTITY`) resource rendered inline;
  carries the embedded `AdminResource`. The embedded resource has no standalone
  URL; its actions nest under the owner.

Pure declarative data; no behavior or I/O.

### `CustomAction<ROW>` — [`CustomAction.kt`](./CustomAction.kt)

A descriptor-declared per-row POST button rendered by the engine on the detail
page, alongside the descriptor-derived Edit/Delete/Undelete actions. It carries
a `label` (button text), a `pathSuffix` (appended to `/{slug}/{idPath}` to form
the POST target), and a `disabledReason` predicate over the row.

- **`disabledReason(row)`** is the single source of truth for the button's
  enabled state: `null` renders the button enabled; a non-null string renders it
  disabled and supplies its hover title explaining why.
- The engine renders the button and evaluates `disabledReason`, but never
  inspects `pathSuffix` — the descriptor registers the matching route itself in
  `registerExtraRoutes`. An entry whose `pathSuffix` has no registered route
  yields a POST to a non-existent endpoint; the engine does not detect this.

Pure declarative data; no behavior or I/O.

### `EdgePanel` — [`EdgePanel.kt`](./EdgePanel.kt)

The type-erased, render-ready output of edge resolution. Resolving inside the
descriptor (typed, DAO-aware) and rendering from this flat panel keeps the
"engine renders from the descriptor" boundary without leaking generics across
the router. Variants:

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
- _Side effects_: none — pure in-memory lookup.
- _Idempotent_: yes.
- The slug index is built with `associateBy { it.slug }`: when two resources
  share a slug, the later one in the construction list wins and the earlier is
  shadowed. There is no duplicate-slug detection at construction.

### `Route.registerAdminRoutes(registry, database)` — [`AdminRouting.kt`](./AdminRouting.kt)

Registers the dashboard, the generic per-resource routes for every top-level
resource, and each resource's owner-nested extra routes. Route registration is
driven purely from the registry and each descriptor's declared capabilities;
there is no per-table branch. Only `topLevel` resources receive the dashboard
nav entry and the generic route set; a non-top-level resource is neither listed
nor reachable through a generated route. `registerExtraRoutes` is invoked for
every resource (top-level or not), in the same scope as the generic routes. No
generic route backs a descriptor's `customActions`: each custom action's POST
target is registered by the descriptor through `registerExtraRoutes`, not by
this router.

- **Routes registered** (per top-level resource, conditionally):

  | Route                        | Condition          | Effect                                                               |
  | ---------------------------- | ------------------ | -------------------------------------------------------------------- |
  | `GET /`                      | always             | Dashboard: nav of top-level sections                                 |
  | `GET /{slug}`                | `topLevel`         | List page (table of `cells`; rows link to detail)                    |
  | `GET /{slug}/new`            | `create != null`   | Create form (`fields` + `createExtraInputs`)                         |
  | `POST /{slug}`               | `create != null`   | Create → redirect to new row's canonical detail                      |
  | `GET /{slug}/{id}`           | always             | Detail page (field table + resolved edge panels)                     |
  | `GET /{slug}/{id}/edit`      | always\*           | Edit form; missing id / `update == null` → not-found page            |
  | `POST /{slug}/{id}`          | always\*           | Update → redirect to detail; OCC conflict → conflict page            |
  | `POST /{slug}/{id}/delete`   | always\*           | Delete → redirect to list; missing id / `delete == null` → not-found |
  | `POST /{slug}/{id}/undelete` | `undelete != null` | Undelete → redirect to detail                                        |

  \*Registered unconditionally, then re-checks handler nullability at request
  time and returns a not-found page when the handler is absent. The create
  routes are gated at registration time instead.

- **Paging**: lists are paged with a fixed page size (`PAGE_SIZE`, a
  compile-time constant) and no `COUNT(*)`. The list call requests
  `PAGE_SIZE + 1` rows; a present surplus row signals a next page, drives the
  "next" link, and is dropped before the table renders. A "previous" link
  renders whenever the resolved offset is greater than zero. The offset is
  parsed from the `offset` query parameter and coerced to a non-negative value
  (default `0`).
- **`createExtraInputs`**: rendered only on the create form and passed through
  the submitted form map to the `create` handler; absent from detail and edit
  views.
- **Side effects**: route-table registration at call time; the registered
  handlers perform DB reads/writes through descriptor handlers and emit HTML or
  redirects. No SQL is built here.
- **Error handling**: handler `Result.failure` is dispatched to the shared
  responders — not-found, conflict, constraint/validation (form re-render with a
  message from `createFormErrorMessage`), and a generic DAO-error page — never
  an unhandled throw to the client. `createFormErrorMessage` maps
  `DuplicateEmailException`, `ConstraintViolationException`, and
  `IllegalArgumentException` to user-facing messages and returns `null` for any
  other throwable (which then falls through to the DAO-error page). A malformed
  id yields a not-found page.
- **Idempotency**: registration is not idempotent (duplicate routes on a second
  call). The registered `GET` handlers are read-only/idempotent; `POST` mutation
  handlers are not (updates are subject to OCC).

---

## III. Infrastructure & Environment

- **Fixed page size**: list pagination uses a single compile-time `PAGE_SIZE`
  constant in [`AdminRouting.kt`](./AdminRouting.kt); it is not configurable and
  not derived from any environment variable.
- **No module-specific config**: this package reads no HOCON keys or environment
  variables directly. The `Database` and route scope are injected by the
  admin-server bootstrap; cookie, bind, and session configuration live outside
  this package.
- The package depends on `:db` (`Database`, `SoftDeleteScope`, the DAO exception
  types) and Ktor server routing/HTML. It builds no SQL and owns no schema.

---

## IV. History

- [x] [RFC-60: Admin Website (Framework + Users Spine)](../../../../../../../../rfc/60-admin-website.md)
      — introduced the descriptor/registry/generic-routing engine:
      `AdminResource`/`AdminKind`, `AdminField`/`FieldType`, `AdminEdge`,
      `EdgePanel`, `AdminRegistry`, and `AdminRouting`. Established canonical
      routing (one `/{slug}/{id}` detail URL per entity), the `EMBEDDED_ENTITY`
      no-standalone-URL exemption, descriptor-driven conditional route
      registration, include-deleted admin reads, `PAGE_SIZE + 1` count-free
      paging, the delegate-to-DAO (never build SQL) write path, OCC-aware
      updates, and sensitive-field redaction.
- [x] [RFC-63: Admin System Prompts](../../../../../../../../rfc/63-admin-system-prompts.md)
      — added `AdminField.inList` (default `true`) so an oversized field (the
      system-prompt body) is omitted from the list table while remaining in
      detail and form views, orthogonal to `sensitive`.
- [x] [RFC-65: Email Verification (Backend)](../../../../../../../../rfc/65-email-verification.md)
      — reformatted the update route's `ConcurrentModificationException` branch
      in [`AdminRouting.kt`](./AdminRouting.kt) to a block body; no behavioral
      change to the engine.
- [x] [RFC-76: Admin email-verification actions](../../../../../../../../rfc/76-admin-email-verification-actions.md)
      — added [`CustomAction.kt`](./CustomAction.kt) and the
      `AdminResource.customActions` member (default empty): descriptor-declared
      per-row POST buttons the engine renders after the Edit/Delete/Undelete
      block, with `disabledReason` as the single source of truth for enabled
      state. The engine never inspects `pathSuffix`; route registration stays
      with the descriptor's `registerExtraRoutes`. `AdminRouting.kt` is
      unchanged — no generic custom-action route.
