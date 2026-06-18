# SPEC: Admin v1 Resource Descriptors (the "users spine")

## I. Overview

This directory holds the concrete [`AdminResource`](../engine/AdminResource.kt)
descriptors that configure the admin engine for the v1 tables: `users`
([UsersResource.kt](./UsersResource.kt)), the embedded `students` profile
([StudentsResource.kt](./StudentsResource.kt)), and `sessions`
([SessionsResource.kt](./SessionsResource.kt)). Each descriptor **decides** one
table's kind, its allowed operation set, its sensitive columns, its relationship
edges, and the exact write path each operation takes through the typed DAOs. The
generic routing, rendering, and paging machinery lives in
[`../engine`](../engine); this directory only declares _what_ the engine does
for these three tables, never _how_ the engine does it.

## II. Invariants

### Operation sets mirror the schema's guards

- `users` MUST be `AdminKind.ENTITY` and MUST offer create, read/list,
  OCC-checked update, soft-delete (sets `deleted_at`), and undelete.
- `students` MUST be `AdminKind.EMBEDDED_ENTITY` and MUST NOT expose any
  engine-bound `create`/`update`/`delete`/`undelete` handler — all four are
  `null`. Its mutations are owner-nested (see below).
- `sessions` MUST be `AdminKind.ENTITY` but MUST NOT offer `create` (rows
  originate from auth flows) and MUST NOT offer `update` (its DAO mutators are
  domain lifecycle operations, not field edits) — both are `null`. It MUST NOT
  offer `undelete`.
- Entity deletes MUST be soft for `users` (and for the owner-nested `students`
  action), and MUST be physical for `sessions`. No descriptor may add or change
  a DB trigger; the soft-vs-physical choice reflects exactly which delete the
  table's triggers already permit.
- `users_versions` and `students_versions` are append-only history: the
  descriptors MUST surface them read-only (history panels) and MUST NEVER mutate
  them.

### Embedded-entity routing

- `StudentsResource.topLevel` MUST be `false`; the resource MUST NOT acquire a
  standalone `/student` list or `/student/{id}` detail route. Its observable
  contract is that `GET /student` and `GET /student/{id}` both resolve to 404.
- `StudentsResource.list` MUST return an empty list (it is never bound to a list
  route); `StudentsResource.get` exists solely so the owner's embedded panel can
  read the row.
- All `students` mutations MUST be reached through owner-nested action endpoints
  registered by `UsersResource` under `/user/{id}/student[...]`, never under a
  `/student` path. These are action endpoints (POST-only, redirect back to the
  owner detail), not detail pages, so the engine's one-detail-URL-per-entity
  rule is preserved.

### Sensitive columns

- `users.password_hash` and `sessions.token_hash` MUST be declared
  `sensitive = true`. A sensitive field MUST NOT appear in any create/edit form,
  MUST be redacted in detail/list views, and its raw value MUST NEVER render.
  The `users` edit form MUST NOT contain a `passwordHash` input; the `sessions`
  detail/list MUST NEVER emit the raw token hash.

### Write path purity

- Every write (`create`, `update`, `delete`, `undelete`, and the owner-nested
  `students` actions) MUST delegate to a typed DAO method inside a
  `Database.withConnection` transaction. This directory MUST NEVER construct
  SQL.
- The `users` update path MUST enforce optimistic concurrency by carrying the
  **form-supplied** `version` into the row handed to the DAO update, so a stale
  version fails the DAO's versioned write rather than silently overwriting.
- A `users` update with a missing/unparseable `version` form field MUST fail
  rather than default a version.

### `users` create path

- Create MUST hash the submitted plaintext password via the injected
  `Argon2Hasher` and build `AuthMethod.Password(...)` directly, then call the
  users-create DAO method. It MUST NOT route through `AuthService.register`,
  because registration also mints a session — an unwanted side effect for an
  admin-created row.
- Create MUST reject blank/invalid email, name, display name, or a blank
  password before touching the DAO, surfacing each as a field-level failure.
- A duplicate email MUST surface the DAO's `DuplicateEmailException` as a
  form-level field error (the form re-renders), not a 500.

### Soft-delete reads

- `users`/`students` reads MUST include soft-deleted rows so a deleted row stays
  visible and is marked: list passes `SoftDeleteScope.ALL`, detail/get passes
  `includeDeleted = true`. Undelete MUST be offered only on `users` (the sole v1
  table whose DAO has an `undelete`); `isDeleted` drives the marker/undelete
  affordance.
- `sessions` has no `deleted_at`; `SessionsResource.isDeleted` MUST always be
  `false` and the `scope` argument MUST be ignored.

### Edge weaving (canonical linking)

- The `user` detail page MUST declare exactly three edges: an `Embedded`
  `students` panel, a `HasMany` `sessions` table, and a `History` panel for
  `users_versions`. The embedded `students` panel MUST itself nest a `History`
  panel for `students_versions`.
- The `HasMany` `sessions` rows MUST link to the canonical `/session/{id}`
  detail path. They MUST NEVER link to a nested `/user/{id}/session/{id}` path.
- The `session` detail page MUST declare a `Parent` edge back to its owner. When
  `Session.userId` is non-null it MUST render a link to `/user/{userId}`; when
  null it MUST render an "owner absent" panel rather than a broken link.
- A transient DAO failure while resolving any edge panel (the embedded student,
  either version history, or the sessions list) MUST propagate as a failed
  `Result` so the engine renders the correct error page, never an empty panel
  that masks the fault. A _missing_ embedded student profile is a success — a
  "no profile yet" create panel — distinguished from a transient fault by the
  DAO's `NotFoundException`.

## III. Behavioral Contracts

### `UsersResource` (ENTITY, top-level)

- **list** — reads via the users list DAO method with `SoftDeleteScope.ALL`.
  Side effects: DB read. Errors: failed `Result` on DB fault. Idempotent: yes.
- **get** — reads one user including soft-deleted. Side effects: DB read.
  Errors: `NotFoundException` (→ 404), DB fault (→ error page). Idempotent: yes.
- **create** — validates email/name/displayName/password, hashes the password,
  builds `AuthMethod.Password`, inserts via the users-create DAO. Side effects:
  one row inserted (no session created). Errors: `IllegalArgumentException` for
  each invalid/blank field; `DuplicateEmailException` → form re-render with a
  field error. Idempotent: no.
- **update** — re-reads the row (including deleted), overlays the form's
  `version`, email, name, displayName, isAdmin, then calls the users-update DAO.
  Side effects: one versioned row update. Errors: missing/invalid version or
  field → `IllegalArgumentException`; DAO `ConcurrentModificationException` on a
  stale version → conflict page (no overwrite). Idempotent: no (version bumps).
- **delete** — reads the current version, then soft-deletes via the users-delete
  DAO (sets `deleted_at`, bumps version). Side effects: one versioned soft
  delete. Idempotent: no.
- **undelete** — reads the current version, then restores via the users-undelete
  DAO. Side effects: one versioned restore. Idempotent: no.
- **resolveEdges** — builds the embedded student panel, a sessions table (via
  the per-user sessions list DAO), and a `users_versions` history table (via the
  user-versions DAO). Side effects: DB reads only. Errors: any DAO fault →
  failed `Result`. Idempotent: yes.
- **registerExtraRoutes** — registers the three owner-nested `students` action
  endpoints (below) under the engine's gated route scope.

### Owner-nested `students` actions (registered by `UsersResource`)

- **`POST /user/{id}/student`** — parses the owner id and the ISO `PartialDate`
  graduation field, then creates the student via the students-create DAO. Side
  effects: one row inserted. Errors: bad owner id → redirect to `/user`;
  malformed date → redirect to the owner detail; DAO fault → rendered DAO-error
  page. On success: redirect to the owner detail.
- **`POST /user/{id}/student/update`** — re-reads the owner's student (including
  deleted), overlays the new graduation date, calls the students-update DAO.
  Side effects: one versioned update. Errors: as above.
- **`POST /user/{id}/student/delete`** — re-reads the owner's student, calls the
  students-delete DAO with its current version (soft delete). Side effects: one
  versioned soft delete. Errors: as above.

### `StudentsResource` (EMBEDDED_ENTITY, not top-level)

- **list** — MUST return `Result.success(emptyList())`; never bound to a route.
- **get** — reads one student including deleted (used by the owner's panel).
  Side effects: DB read. Errors: `NotFoundException`, DB fault. Idempotent: yes.
- **create/update/delete/undelete** — all `null` (mutations are owner-nested).
- **buildPanel(db, userId)** — assembles the inline embedded panel for a user:
  the student profile (if any), its create/edit forms (the single editable
  graduation field), and the nested `students_versions` history table. Side
  effects: DB reads only. Returns a "no profile yet" panel on
  `NotFoundException`; propagates any other DAO fault as a failed `Result`.
  Idempotent: yes.

### `SessionsResource` (ENTITY, top-level)

- **list** — reads via the sessions list DAO (`scope` ignored). Side effects: DB
  read. Idempotent: yes.
- **get** — reads one session by id. Side effects: DB read. Errors:
  `NotFoundException` (→ 404). Idempotent: yes.
- **create/update/undelete** — all `null`.
- **delete** — physically removes the row via the sessions hard-delete DAO. Side
  effects: one row permanently deleted; a subsequent `get` returns
  `NotFoundException`. Idempotent: no.
- **resolveEdges** — returns a single `Parent` panel: a link to `/user/{userId}`
  when `userId` is non-null, else an "owner absent" panel. Side effects: none
  (no DB read; the foreign key is already on the row). Idempotent: yes.

## IV. Infrastructure & Environment

- No descriptor in this directory reads environment variables or config keys
  directly. The `users` create path depends on an `Argon2Hasher` supplied by
  constructor injection into `UsersResource`; `StudentsResource` and
  `SessionsResource` are stateless `object`s. All DB access is via the injected
  `Database`. Module-level config (bind host, cookie, session expiry) lives in
  `admin-server.conf` / `AdminConfig`, outside this directory.

## V. History

- [x] [RFC-60: Admin Website (Framework + Users Spine)](../../../../../../../../rfc/60-admin-website.md)
