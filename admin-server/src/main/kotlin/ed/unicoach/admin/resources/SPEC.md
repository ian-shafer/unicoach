# SPEC: Admin v1 Resource Descriptors (the "users spine")

## I. Overview

This directory holds the concrete [`AdminResource`](../engine/AdminResource.kt)
descriptors that configure the admin engine for the v1 tables: `users`
([UsersResource.kt](./UsersResource.kt)), the embedded `students` profile
([StudentsResource.kt](./StudentsResource.kt)), `sessions`
([SessionsResource.kt](./SessionsResource.kt)), and the immutable
`system_prompts` catalog
([SystemPromptsResource.kt](./SystemPromptsResource.kt)). Each descriptor
**decides** one table's kind, its allowed operation set, its sensitive columns,
its relationship edges, and the exact write path each operation takes through
the typed DAOs. The generic routing, rendering, and paging machinery lives in
[`../engine`](../engine); this directory only declares _what_ the engine does
for these tables, never _how_ the engine does it.

The shared soft-delete OCC helper [`occSoftDelete`](./OccDelete.kt) lives here
too: the "load current version, then OCC delete/undelete" sequence expressed
once against the `db/dao/Dao.kt` capability-interface intersection
(`SoftDeleteFindable` + `OccDeletable`), so `UsersResource`'s
`delete`/`undelete` are one-line delegations rather than duplicated inline
read-then-write blocks.

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
- `system_prompts` MUST be `AdminKind.IMMUTABLE_ENTITY` (the first instance of
  this kind) and MUST offer `create` plus read/list/detail only. Its `update`,
  `delete`, and `undelete` MUST all be `null` — the table's triggers forbid
  `UPDATE`/`DELETE` and it has no soft-delete column — so the engine registers
  no edit/delete routes and renders no Edit/Delete actions. `isDeleted` MUST
  always be `false` and the read `scope`/`includeDeleted` arguments MUST be
  ignored. A "new version" of a prompt is a fresh row created through `create`,
  never an edit.
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

### `users` email-verification actions

- `users.email_verified_at` MUST surface as a read-only `emailVerifiedAt` field
  ("Email Verified", `FieldType.TIMESTAMP`, `editable = false`,
  `sensitive = false`) — it appears in detail/list cells but NEVER in
  `createExtraInputs` or the edit form, so the generic `UserEdit` update path
  cannot touch it. It is written only by dedicated verification/change-email DAO
  paths.
- `UsersResource` MUST expose two `customActions`, both gated by a single shared
  predicate `verificationDisabledReason(row)`: it returns
  `"Email already
  verified."` when `emailVerifiedAt != null`,
  `"User is deleted."` when `deletedAt != null`, else `null`. The buttons render
  enabled iff it returns `null` (an active, unverified user). This predicate
  MUST be the single source of truth gating both actions.
  - **Mark email verified** (`verify-email`) → `POST /user/{id}/verify-email`.
  - **Send verification email** (`send-verification-email`) →
    `POST /user/{id}/send-verification-email`.
- Both routes MUST parse the id (redirecting to `/user` on a bad id) and run
  under the engine's gated route scope. `verify-email` MUST delegate to
  `UsersDao.markEmailVerified`; `send-verification-email` MUST load the user at
  `SoftDeleteScope.ACTIVE` (so a soft-deleted user yields `NotFoundException` →
  404, rejecting a forged POST) and then call `EmailVerificationService.resend`.
  These paths MUST NEVER construct SQL or bypass the typed DAO/service.

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
- The soft-delete OCC dance (load the current row at `SoftDeleteScope.ALL` to
  confirm existence, then issue the OCC delete/undelete keyed on that row's
  version, both on a single `Database.withConnection` session) MUST be expressed
  once in `occSoftDelete` ([OccDelete.kt](./OccDelete.kt)), programmed against
  the `SoftDeleteFindable` + `OccDeletable` capability intersection, not a
  concrete DAO. `UsersResource.delete`/`undelete` MUST delegate to it. The
  helper MUST propagate the DAO's `NotFoundException` (unknown id) and
  `ConcurrentModificationException` (version raced between read and write)
  unchanged. The owner-nested `students` delete MUST NOT be retrofitted onto
  this helper: it keys on `findByUserId`, a parented lookup off
  `SoftDeleteFindable`, outside the helper's seam.

### `system_prompts` create path

- Create MUST `trim` `name` and `version` (identifiers whose surrounding
  whitespace the table's `*_trimmed_check` would otherwise reject) but pass
  `body` **verbatim** (trailing whitespace/newlines in a prompt body are
  significant; the schema exempts `body` from a trimmed check). It MUST reject a
  blank `name`/`version`/`body` client-side with an `IllegalArgumentException`
  field message before any DB call. All other validity (length, size,
  uniqueness) is DB-enforced and surfaces as `ConstraintViolationException`
  (form re-render). The handler MUST build `NewSystemPrompt`, call
  `SystemPromptsDao.create`, and return the new row's `id`.

### `users` create path

- Create MUST hash the submitted plaintext password via the injected
  `Argon2Hasher` into a `PasswordHash` and set it on `NewUser.passwordHash`
  directly (no `AuthMethod` wrapper), then call the users-create DAO method. It
  MUST NOT route through `AuthService.register`, because registration also mints
  a session — an unwanted side effect for an admin-created row.
- Create MUST reject blank/invalid email, name, display name, or a blank
  password before touching the DAO, surfacing each as a field-level failure.
- A duplicate email MUST surface the DAO's `DuplicateEmailException` as a
  form-level field error (the form re-renders), not a 500.

### Soft-delete reads

- `users`/`students` reads MUST include soft-deleted rows so a deleted row stays
  visible and is marked: list passes `SoftDeleteScope.ALL`, detail/get maps the
  engine's `includeDeleted` flag to a `SoftDeleteScope` via the package-internal
  `Boolean.toScope()` extension (`true` → `SoftDeleteScope.ALL`, `false` →
  `SoftDeleteScope.ACTIVE`) at the DAO call boundary. Undelete MUST be offered
  only on `users` (the sole v1 table whose DAO has an `undelete`); `isDeleted`
  drives the marker/undelete affordance.
- `sessions` has no `deleted_at`; `SessionsResource.isDeleted` MUST always be
  `false` and the `scope` argument MUST be ignored.

### Edge weaving (canonical linking)

- The `user` detail page MUST declare exactly three edges: an `Embedded`
  `students` panel, a `HasMany` `sessions` table, and a `History` panel for
  `users_versions`. The embedded `students` panel MUST itself nest a `History`
  panel for `students_versions`. The `users_versions` history table surfaces an
  `Email Verified` column (each row emits `emailVerifiedAt?.toString()` or empty
  when null) alongside `Version`/`Email`/`Name`/`Admin`/`Updated`/`Deleted`.
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

- **list** — reads via `UsersDao.list(session, scope, limit, offset)` with
  `SoftDeleteScope.ALL`. Side effects: DB read. Errors: failed `Result` on DB
  fault. Idempotent: yes.
- **get** — reads one user via
  `UsersDao.findById(session, id, includeDeleted.toScope())`. Side effects: DB
  read. Errors: `NotFoundException` (→ 404), DB fault (→ error page).
  Idempotent: yes.
- **create** — validates email/name/displayName/password, hashes the password to
  a `PasswordHash` and sets `NewUser.passwordHash` directly (no `AuthMethod`),
  inserts via `UsersDao.create`. Side effects: one row inserted (no session
  created). Errors: `IllegalArgumentException` for each invalid/blank field;
  `DuplicateEmailException` → form re-render with a field error. Idempotent: no.
- **update** — re-reads the row (`findById` with `SoftDeleteScope.ALL`) to
  confirm existence, builds a `UserEdit` from the form's `version`, email, name,
  displayName, isAdmin, then calls `UsersDao.update`; the form's version drives
  the DAO's `WHERE version = ?` enforcement. Side effects: one versioned row
  update (`email`/`name`/`display_name`/`is_admin` only). Errors:
  missing/invalid version or field → `IllegalArgumentException`; DAO
  `ConcurrentModificationException` on a stale version → conflict page (no
  overwrite). Idempotent: no (version bumps).
- **delete** — delegates to `db.occSoftDelete(UsersDao, id, deleted = true)`,
  which reads the current version then soft-deletes (sets `deleted_at`, bumps
  version). Side effects: one versioned soft delete. Idempotent: no.
- **undelete** — delegates to `db.occSoftDelete(UsersDao, id, deleted = false)`,
  which reads the current version then restores. Side effects: one versioned
  restore. Idempotent: no.
- **resolveEdges** — builds the embedded student panel, a sessions table (via
  the per-user sessions list DAO), and a `users_versions` history table (via the
  user-versions DAO) whose columns include `Email Verified` (emitting each
  version's `emailVerifiedAt?.toString()` or empty). Side effects: DB reads
  only. Errors: any DAO fault → failed `Result`. Idempotent: yes.
- **customActions** — two entries, both gated by the shared
  `verificationDisabledReason` predicate (enabled only for an active, unverified
  user): "Mark email verified" (`verify-email`) and "Send verification email"
  (`send-verification-email`).
- **registerExtraRoutes** — registers the three owner-nested `students` action
  endpoints plus the two email-verification action endpoints (all below) under
  the engine's gated route scope.

### `users` email-verification actions (registered by `UsersResource`)

- **`POST /user/{id}/verify-email`** — parses the id (bad id → redirect to
  `/user`), then `UsersDao.markEmailVerified(session, id)` on a `withConnection`
  session. Side effects: a versioned conditional update stamping
  `email_verified_at = NOW()` and bumping `version` while it is still NULL on an
  active row. On an already-verified active user the DAO returns the row
  unchanged (no second version bump) — **idempotent**. Errors:
  `NotFoundException` → 404 only when the user is absent or soft-deleted
  (`deleted_at IS NULL` guard); other DAO fault → rendered DAO-error page. On
  success: redirect to `/user/{id}`.
- **`POST /user/{id}/send-verification-email`** — parses the id (bad id →
  redirect to `/user`), loads the user via
  `UsersDao.findById(session, id, SoftDeleteScope.ACTIVE)` (a soft-deleted user
  yields `NotFoundException` → 404), then calls
  `EmailVerificationService.resend(user)`. `resend` returns `ResendResult.Sent`
  or `ResendResult.AlreadyVerified` — both are `Result.success` and redirect to
  `/user/{id}`; a DB fault during token issuance → rendered DAO-error page. The
  outer/inner `fold` is nested (not flattened via `mapCatching`) because
  `resend` is a `suspend` call that opens its own connection. Side effects: an
  already-verified user is a no-op; otherwise outstanding verification tokens
  are consumed and a fresh one issued in `resend`'s own transaction, then the
  email is sent best-effort post-commit. Delivery is best-effort: a send failure
  is swallowed/logged upstream, so a successful action reports that a token was
  issued, not that mail was delivered. Idempotent: no (a fresh token is issued
  each call for an unverified user).

### Owner-nested `students` actions (registered by `UsersResource`)

- **`POST /user/{id}/student`** — parses the owner id and the ISO `PartialDate`
  graduation field, then creates the student via the students-create DAO. Side
  effects: one row inserted. Errors: bad owner id → redirect to `/user`;
  malformed date → redirect to the owner detail; DAO fault → rendered DAO-error
  page. On success: redirect to the owner detail.
- **`POST /user/{id}/student/update`** — re-reads the owner's student
  (`findByUserId` with `SoftDeleteScope.ALL`), builds a `StudentEdit` from the
  existing row's `id`/`version` and the new graduation date, calls
  `StudentsDao.update`. Side effects: one versioned update. Errors: as above.
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

- **list** — reads via `SessionsDao.list(session, limit, offset)` (`scope`
  ignored). Side effects: DB read. Idempotent: yes.
- **get** — reads one session via `SessionsDao.findById`. Side effects: DB read.
  Errors: `NotFoundException` (→ 404). Idempotent: yes.
- **create/update/undelete** — all `null`.
- **delete** — physically removes the row via `SessionsDao.destroy`. Side
  effects: one row permanently deleted; a subsequent `get` returns
  `NotFoundException`. Idempotent: no.
- **resolveEdges** — returns a single `Parent` panel: a link to `/user/{userId}`
  when `userId` is non-null, else an "owner absent" panel. Side effects: none
  (no DB read; the foreign key is already on the row). Idempotent: yes.

### `SystemPromptsResource` (IMMUTABLE_ENTITY, top-level)

`slug = "system-prompt"`, `title = "System Prompt"`. Fields (declaration order =
detail-row order): `id` (TEXT, read-only), `name` (TEXT, editable), `version`
(TEXT, editable), `createdAt` (TIMESTAMP, read-only), `body` (MULTILINE,
editable, `inList = false`). `body` is `editable = true` so it renders as a
create-form textarea and in full on detail, but `inList = false` so the up-to-1
MB body is omitted from the list table; since `update` is null, no edit form is
ever served, so `body` is never re-editable. No edges.

- **list** — reads via `SystemPromptsDao.list(session, limit, offset)` (`scope`
  ignored). Side effects: DB read. Idempotent: yes.
- **get** — reads one prompt via `SystemPromptsDao.findById` (`includeDeleted`
  ignored). Side effects: DB read. Errors: `NotFoundException` (→ 404).
  Idempotent: yes.
- **create** — trims `name`/`version`, passes `body` verbatim, rejects any blank
  field with `IllegalArgumentException`, builds `NewSystemPrompt`, inserts via
  `SystemPromptsDao.create`, returns the new row's `id`. Side effects: one row
  inserted. Errors: blank field → `IllegalArgumentException` (form re-render);
  duplicate `(name, version)` or bound/CHECK violation →
  `ConstraintViolationException` (form re-render with the constraint message).
  Idempotent: no.
- **update/delete/undelete** — all `null`. The engine renders no Edit/Delete
  actions, and the unconditionally-registered edit/delete/undelete routes
  re-check nullability at request time and return the not-found page.
- **isDeleted** — always `false`. **edges** — empty; **resolveEdges** — default
  (empty).

### `occSoftDelete` (shared helper, [OccDelete.kt](./OccDelete.kt))

- **`Database.occSoftDelete(dao, id, deleted)`** — a `suspend` extension over
  the `SoftDeleteFindable<ROW, ID>` + `OccDeletable<ROW, ID>` intersection. On a
  single `withConnection` session: `findById(id, SoftDeleteScope.ALL)` to
  confirm existence, then `delete(id, row.version)` when `deleted` is true, else
  `undelete(id, row.version)`. Side effects: one versioned soft delete/restore.
  Errors: propagates the DAO's `NotFoundException` (unknown id) and
  `ConcurrentModificationException` (raced version) unchanged. Idempotent: no.
  `UsersResource.delete`/`undelete` delegate to it
  (`db.occSoftDelete(UsersDao, id, deleted = true/false)`); behaviour is
  identical to the inline blocks it replaced.

## IV. Infrastructure & Environment

- No descriptor in this directory reads environment variables or config keys
  directly. `UsersResource` takes two constructor-injected collaborators: an
  `Argon2Hasher` (used by the create path) and an `EmailVerificationService`
  (used by the `send-verification-email` action to resend a verification token).
  `StudentsResource`, `SessionsResource`, and `SystemPromptsResource` are
  stateless `object`s. All DB access is via the injected `Database`.
  Module-level config (bind host, cookie, session expiry) lives in
  `admin-server.conf` / `AdminConfig`, outside this directory.

## V. History

- [x] [RFC-60: Admin Website (Framework + Users Spine)](../../../../../../../../rfc/60-admin-website.md)
- [x] [RFC-62: DAO Capability Interfaces and Shared Query Scaffolding](../../../../../../../../rfc/62-dao-interfaces.md)
      — Retargeted the descriptors to the renamed/narrowed DAO surface: the
      soft-delete reads map the engine's `includeDeleted: Boolean` to a
      `SoftDeleteScope` via the package-internal `Boolean.toScope()` extension;
      `UsersDao.listAll`/`SessionsDao.listAll` calls became `list`,
      `SessionsDao.deleteById` became `destroy`; the `users` update and the
      owner-nested `students` update build a `UserEdit`/`StudentEdit` instead of
      copying a whole row.
- [x] [RFC-63: Admin System Prompts](../../../../../../../../rfc/63-admin-system-prompts.md)
      — Added `SystemPromptsResource` (the first `AdminKind.IMMUTABLE_ENTITY`:
      create + list/detail, no edit/delete, `body` `inList = false`) and the
      shared `occSoftDelete` helper ([OccDelete.kt](./OccDelete.kt)) programmed
      against the `SoftDeleteFindable` + `OccDeletable` capability intersection;
      `UsersResource.delete`/`undelete` became one-line delegations to it. The
      owner-nested `students` delete was deliberately not retrofitted (it keys
      on the parented `findByUserId`, off the capability seam).
- [x] [RFC-64: Google SSO Login](../../../../../../../../rfc/64-google-sso-login.md)
      — Incidental call-site update for the reshaped `NewUser`: the `AuthMethod`
      sealed type (and `SsoProviderId`) was removed, so the `users` create path
      now hashes the password into a `PasswordHash` and sets
      `NewUser.passwordHash` directly instead of wrapping it in
      `AuthMethod.Password`.
- [x] [RFC-76: Admin Email-Verification Actions](../../../../../../../../rfc/76-admin-email-verification-actions.md)
      — Added a read-only `emailVerifiedAt` field/cell (and an `Email Verified`
      column on the `users_versions` history panel), injected an
      `EmailVerificationService` into `UsersResource`, and added two
      `customActions` gated by the shared `verificationDisabledReason` predicate
      (active, unverified user only): `POST /user/{id}/verify-email` (idempotent
      `UsersDao.markEmailVerified`) and
      `POST /user/{id}/send-verification-email` (loads the active user, then
      `EmailVerificationService.resend`, best-effort delivery).
