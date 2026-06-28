# SPEC: Admin v1 Resource Descriptors (the "users spine")

## I. Overview

This directory holds the concrete [`AdminResource`](../engine/AdminResource.kt)
descriptors that configure the admin engine for the v1 tables: `users`
([UsersResource.kt](./UsersResource.kt)), the embedded `students` profile
([StudentsResource.kt](./StudentsResource.kt)), `sessions`
([SessionsResource.kt](./SessionsResource.kt)), and the immutable
`system_prompts` catalog
([SystemPromptsResource.kt](./SystemPromptsResource.kt)). It also holds the
read-only coaching-memory views (RFC 77): `claims`
([ClaimsResource.kt](./ClaimsResource.kt)), `observations`
([ObservationsResource.kt](./ObservationsResource.kt)), and `extraction_runs`
([ExtractionRunsResource.kt](./ExtractionRunsResource.kt)), the read-only
conversation views (RFC 81): `convos` ([ConvosResource.kt](./ConvosResource.kt))
and `convo_requests` ([ConvoRequestsResource.kt](./ConvoRequestsResource.kt)),
and the read-only colleges view (RFC 82): `colleges`
([CollegesResource.kt](./CollegesResource.kt)). Each descriptor **decides** one
table's kind, its allowed operation set, its sensitive columns, its relationship
edges, and the exact write path each operation takes through the typed DAOs. The
generic routing, rendering, and paging machinery lives in
[`../engine`](../engine); this directory only declares _what_ the engine does
for these tables, never _how_ the engine does it.

The coaching-memory, conversation, and colleges descriptors are list+detail only
— all four write handlers are `null`, `isDeleted` is always `false`, and the
read `scope`/`includeDeleted` arguments are ignored (the same posture as
`sessions`/`system_prompts`). The coaching-memory and conversation descriptors
exist to make the per-student memory, LLM spend, and conversation history
inspectable; they are also woven into the student profile as nested panels (see
`StudentsResource`). The colleges descriptor exposes College Scorecard data and
its change history for browsing.

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
  `"Email already verified."` when `emailVerifiedAt != null`,
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
  panel for `students_versions`, followed by four panels — Conversations (RFC
  81), Claims, Observations, and Extraction runs (RFC 77, see below), in that
  order. The `users_versions` history table surfaces an `Email Verified` column
  (each row emits `emailVerifiedAt?.toString()` or empty when null) alongside
  `Version`/`Email`/`Name`/`Admin`/`Updated`/`Deleted`.
- `ClaimsResource` declares one `HasMany` edge ("Supporting observations",
  targeting `observation`) and `ObservationsResource` one `HasMany` edge
  ("Supported claims", targeting `claim`); their resolved panels link across the
  `claim_support` join. The "Supporting observations" panel carries five
  columns: ID (`refSlug = "observation"`), Convo (`refSlug = "convo"`), Source
  Request (`refSlug = "convo-request"`), Uttered (TIMESTAMP), Quote — added in
  RFC 81 so the claim detail shows the conversation context for each backing
  observation. `ExtractionRunsResource` declares no edges. `ConvosResource`
  declares one `HasMany` edge ("Turns", targeting `convo-request`). A transient
  DAO fault while resolving any such panel propagates as a failed `Result`,
  matching the existing edge-resolution contract.
- The `HasMany` `sessions` rows MUST navigate to the canonical `/session/{id}`
  detail path via the id column's `refSlug = "session"` glyph (RFC 79). They
  MUST NEVER link to a nested `/user/{id}/session/{id}` path.
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
- **resolveEdges** — builds the embedded student panel (via
  `StudentsResource.buildPanel`), a sessions `EdgePanel.Table` (id column:
  `FieldType.UUID`, `refSlug = "session"`; created/expires columns:
  `FieldType.TIMESTAMP`; up to 50 rows from `SessionsDao.listByUser`), and a
  `users_versions` `EdgePanel.Table` whose columns include `Admin`
  (`FieldType.BOOL`), `Email Verified` (`FieldType.TIMESTAMP`),
  `Updated`/`Deleted` (`FieldType.TIMESTAMP`) — all rendering through
  `renderCell`. Side effects: DB reads only. Errors: any DAO fault → failed
  `Result`. Idempotent: yes.
- **customActions** — two entries, both gated by the shared
  `verificationDisabledReason` predicate (enabled only for an active, unverified
  user): "Mark email verified" (`verify-email`) and "Send verification email"
  (`send-verification-email`).
- **registerExtraRoutes** — registers the three owner-nested `students` action
  endpoints plus the two email-verification action endpoints (all below) under
  the engine's gated route scope.
- **fields** — `id` (`FieldType.UUID`, `refSlug = "user"`); other id/FK columns
  stay `FieldType.TEXT` or `FieldType.TIMESTAMP` as appropriate.

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
- **fields** — `id` (`FieldType.UUID`, `refSlug = "student"`), `userId`
  (`FieldType.UUID`, `refSlug = "user"`); other fields stay their existing
  types. Both UUID fields compact to an ellipsis plus the tail in the embedded
  panel.
- **buildPanel(db, userId)** — assembles the inline embedded panel for a user:
  the student profile (if any), its create/edit forms (the single editable
  graduation field), the nested `students_versions` history table, and — after
  the history table — four nested panels in order: Conversations (RFC 81),
  Claims, Observations, and Extraction runs (RFC 77). When a profile is present,
  `fields` is a `List<LabeledCell>` (RFC 79) built from the descriptor's
  `fields` list: each non-sensitive field becomes a `LabeledCell` carrying the
  field's `label`, `type`, and `refSlug` alongside the cell value, so the
  embedded field table routes through `renderCell` identically to a top-level
  detail field — including UUID compaction for `id` and `userId`. Each panel is
  built by a private helper
  (`buildConversationsPanel`/`buildClaimsPanel`/`buildObservationsPanel`/`buildExtractionRunsPanel`)
  that fetches at most `STUDENT_PANEL_LIMIT` rows via the matching DAO and
  carries typed `EdgePanel.Table.Column` entries (UUID id columns set
  `FieldType.UUID`; BIGINT id columns stay `FieldType.TEXT`; datetime columns
  carry `FieldType.TIMESTAMP`) so each cell renders as a formatted date, a
  compacted UUID, or a raw integer as appropriate. `buildConversationsPanel`
  calls
  `ConvosDao.listByStudentWithActivity(session, studentId, ArchiveScope.ALL,
  SoftDeleteScope.ALL, STUDENT_PANEL_LIMIT, 0)`;
  the coaching-memory panels call
  `…Dao.listByStudent(id, STUDENT_PANEL_LIMIT, 0)`. The Conversations panel
  columns are: ID (`refSlug = "convo"`), Name, Last Activity (TIMESTAMP),
  Created (TIMESTAMP), Archived (TIMESTAMP), Deleted (TIMESTAMP) — the panel
  predates `FieldType.UUID`, so its id renders full-width with a link glyph
  rather than compacted. The claims panel's id column is `FieldType.UUID`; the
  observations and extraction-runs panels' id columns are `FieldType.TEXT`
  (BIGINT). The `convoId` column in the observations panel is `FieldType.UUID`
  with `refSlug = "convo"`. When a fetched page fills to `STUDENT_PANEL_LIMIT`,
  the shared `truncationRow` helper appends one cells-only "Showing first 50 —
  see /{slug} for full list" row (no per-row href — RFC 79 removed row-level
  hrefs). Side effects: DB reads only. Returns a "no profile yet" panel on
  `NotFoundException`; propagates any other DAO fault (including a fault on any
  of the four nested panel loads) as a failed `Result`. Idempotent: yes.
- **`STUDENT_PANEL_LIMIT`** — package-private constant `50`, the per-panel row
  cap for the nested coaching-memory tables, mirroring the sessions panel's
  limit. A student with more memory shows the first page only; full enumeration
  is via the global `/{slug}` lists.

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
- **fields** — `id` (`FieldType.UUID`, `refSlug = "session"`), `userId`
  (`FieldType.UUID`, `refSlug = "user"`); `tokenHash` (`FieldType.TEXT`,
  `sensitive = true`); other fields as existing.

### `SystemPromptsResource` (IMMUTABLE_ENTITY, top-level)

`slug = "system-prompt"`, `title = "System Prompt"`. Fields (declaration order =
detail-row order): `id` (`FieldType.UUID`, `refSlug = "system-prompt"`,
read-only), `name` (TEXT, editable), `version` (TEXT, editable), `createdAt`
(TIMESTAMP, read-only), `body` (MULTILINE, editable, `inList = false`). `body`
is `editable = true` so it renders as a create-form textarea and in full on
detail, but `inList = false` so the up-to-1 MB body is omitted from the list
table; since `update` is null, no edit form is ever served, so `body` is never
re-editable. No edges.

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

### `ConvosResource` (ENTITY, top-level, read-only — RFC 81)

`slug = "convo"`, `title = "Conversations"`, `kind = AdminKind.ENTITY`. ROW type
is `ConvoWithActivity` (the convo row plus derived `lastActivityAt`). `parseId`
accepts a UUID into `ConvoId`. Fields (declaration order = detail-row order):
`id` (TEXT, `refSlug = "convo"`), `studentId` (TEXT, `refSlug = "student"`),
`name` (TEXT), `lastActivityAt` (TIMESTAMP), `createdAt` (TIMESTAMP),
`updatedAt` (TIMESTAMP, `inList = false`), `archivedAt` (TIMESTAMP), `deletedAt`
(TIMESTAMP). No field is `sensitive`. One `HasMany` edge, "Turns" (targeting
`convo-request`).

- **list** — reads via
  `ConvosDao.listWithActivity(session, scope, limit, offset)` inside
  `db.withConnection`. Side effects: DB read. Idempotent: yes.
- **get** — reads one convo via
  `ConvosDao.findByIdWithActivity(session, id,
  SoftDeleteScope.ALL)` (always
  includes deleted). Side effects: DB read. Errors: `NotFoundException` (→ 404).
  Idempotent: yes.
- **create/update/delete/undelete** — all `null`; the engine registers no
  create/edit/delete routes.
- **isDeleted** — `row.convo.deletedAt != null`.
- **resolveEdges** — one "Turns" panel built from
  `ConvosDao.listTurns(session,
  row.convo.id, SoftDeleteScope.ALL, TURNS_PANEL_LIMIT, 0)`
  (capped at `TURNS_PANEL_LIMIT = 50`). Columns: Request
  (`refSlug = "convo-request"`), Sent (TIMESTAMP), Model, Stop Reason, In (INT),
  Out (INT). A trailing truncation row is appended when the page fills to the
  cap. Response cells are blank when `turn.response == null`. Side effects: DB
  read. Errors: DAO fault → failed `Result`. Idempotent: yes.

### `ConvoRequestsResource` (LOG, top-level, read-only — RFC 81)

`slug = "convo-request"`, `title = "Requests"`, `kind = AdminKind.LOG`. ROW type
is `ConvoTurn` (the request paired with its optional 1:1 response). `parseId`
accepts a numeric id via `toLongOrNull()` into `ConvoRequestId`. No edges.

Fields (list columns marked `inList = true` by default; `inList = false` for
detail-only): `id` (TEXT, `refSlug = "convo-request"`), `convoId` (TEXT,
`refSlug
= "convo"`), `createdAt` ("Sent", TIMESTAMP), `provider` (TEXT),
`modelRequested` (TEXT), `systemPromptId` (TEXT, `inList = false`,
`refSlug = "system-prompt"`), `requestParams` (JSON, `inList = false`),
`content` (JSON, `inList = false`), `responseStopReason` (TEXT),
`responseModelResolved` (TEXT, `inList = false`), `responseInputTokens` (INT),
`responseOutputTokens` (INT), `responseCacheReadTokens` (INT, `inList = false`),
`responseCacheWriteTokens` (INT, `inList = false`), `responseLatencyMs` (INT,
`inList = false`), `responseProviderRequestId` (TEXT, `inList = false`),
`responseContent` (JSON, `inList = false`), `responseCreatedAt` ("Replied",
TIMESTAMP, `inList = false`). Response fields are blank when
`row.response == null`; nullable sub-fields on a present response also blank via
`?.toString() ?: ""`. No `sensitive` column.

- **list** — reads via `ConvosDao.listTurns(session, scope, limit, offset)` (the
  global overload, not per-convo) inside `db.withConnection`. Side effects: DB
  read. Idempotent: yes.
- **get** — reads one turn via
  `ConvosDao.findTurnByRequestId(session, id,
  SoftDeleteScope.ALL)`. Side
  effects: DB read. Errors: `NotFoundException` (→ 404). Idempotent: yes.
- **create/update/delete/undelete** — all `null`.
- **isDeleted** — always `false`.

### `ClaimsResource` (ENTITY, top-level, read-only — RFC 77)

`slug = "claim"`, `title = "Claim"`, `kind = AdminKind.ENTITY` (the `claims`
table is revisable in the domain, but the admin exposes no writes). `parseId`
accepts a UUID into `ClaimId`. List columns (`inList = true`): `id`
(`FieldType.UUID`, `refSlug = "claim"`), `studentId` (`FieldType.UUID`,
`refSlug = "student"`), `status`, `kind`, `topic`, `confidence`, `createdAt`.
Detail-only (`inList = false`): `origin`, `subject`, `visibility`, `statement`
(MULTILINE), `supersededById` (`FieldType.UUID`, `refSlug = "claim"`),
`supersededAt`, `retractedAt`, `updatedAt`. `statement` is kept out of the list
and shown in full on detail (the `system_prompts.body` treatment). No column is
`sensitive` (redaction is reserved for credential material such as
`password_hash`/`token_hash`, not content). One `HasMany` edge, "Supporting
observations".

The "Supporting observations" edge panel uses
`Column("ID", refSlug =
"observation")` with no `FieldType.UUID` override — the
observation id is a BIGINT and stays `FieldType.TEXT`.

- **list** — reads via `ClaimsDao.list(session, limit, offset)` inside
  `db.withConnection` (`scope` ignored). Side effects: DB read. Idempotent: yes.
- **get** — reads one claim via `ClaimsDao.findById` (`includeDeleted` ignored).
  Errors: `NotFoundException` (→ 404). Idempotent: yes.
- **create/update/delete/undelete** — all `null`; the engine registers no
  create/edit/delete routes and renders no Edit/Delete/New affordance.
- **isDeleted** — always `false`.
- **resolveEdges** — one "Supporting observations" panel built from
  `ClaimSupportDao.listObservationsForClaim`; columns are ID
  (`refSlug =
  "observation"`), Convo (`refSlug = "convo"`), Source Request
  (`refSlug =
  "convo-request"`), Uttered (TIMESTAMP), Quote. Side effects: DB
  read. Errors: DAO fault → failed `Result`. Idempotent: yes.

### `ObservationsResource` (LOG, top-level, read-only — RFC 77)

`slug = "observation"`, `title = "Observation"`, `kind = AdminKind.LOG` (the
`observations` log is insert-only in the domain). `parseId` accepts a numeric id
via `toLongOrNull` into `ObservationId`. List columns: `id` (`FieldType.TEXT` —
BIGINT id, stays TEXT, `refSlug = "observation"`), `studentId`
(`FieldType.UUID`, `refSlug = "student"`), `convoId` (`FieldType.UUID`,
`refSlug = "convo"` — UUID value compacts and links to the registered `convo`
resource), `utteredAt` (TIMESTAMP), `createdAt` (TIMESTAMP). Detail-only:
`sourceRequestId` (`FieldType.TEXT` — carries a BIGINT `convo_requests.id`,
`refSlug = "convo-request"`), `quote` (MULTILINE, kept out of the list and shown
in full on detail). No `sensitive` column. One `HasMany` edge, "Supported
claims".

The "Supported claims" edge panel uses
`Column("ID", FieldType.UUID, refSlug =
"claim")` — the claim id is a UUID and
compacts in the panel.

- **list** — reads via `ObservationsDao.list(session, limit, offset)` inside
  `db.withConnection`. Side effects: DB read. Idempotent: yes.
- **get** — reads one observation via `ObservationsDao.findById`. Errors:
  `NotFoundException` (→ 404). Idempotent: yes.
- **create/update/delete/undelete** — all `null`.
- **isDeleted** — always `false`.
- **resolveEdges** — one "Supported claims" panel built from
  `ClaimSupportDao.listClaimsForObservation`; rows link to `/claim/{id}`. Side
  effects: DB read. Errors: DAO fault → failed `Result`. Idempotent: yes.

### `ExtractionRunsResource` (LOG, top-level, read-only — RFC 77)

`slug = "extraction-run"`, `title = "Extraction Run"`, `kind = AdminKind.LOG`
(append-only log of billed extraction LLM calls). `parseId` accepts a numeric id
via `toLongOrNull` into `ExtractionRunId`. List columns (token and write-count
columns kept on the list so per-student LLM spend is eyeballable): `id`
(`FieldType.TEXT` — BIGINT id, stays TEXT, `refSlug = "extraction-run"`),
`studentId` (`FieldType.UUID`, `refSlug = "student"`), `outcome`,
`modelResolved`, `claimsWritten` (INT), `inputTokens` (INT), `outputTokens`
(INT), `createdAt` (TIMESTAMP). Detail-only: `convoId` (`FieldType.UUID`,
`refSlug = "convo"` — UUID value compacts and links), `throughRequestId`
(`FieldType.TEXT` — BIGINT `convo_requests.id`, `refSlug = "convo-request"`),
`systemPromptId` (`FieldType.UUID`, `refSlug = "system-prompt"`), `provider`,
`observationsWritten` (INT), `claimsSuperseded` (INT), `cacheReadTokens` (INT),
`cacheWriteTokens` (INT). No `sensitive` column. No edges.

- **list** — reads via `ExtractionRunsDao.list(session, limit, offset)` inside
  `db.withConnection`. Side effects: DB read. Idempotent: yes.
- **get** — reads one run via `ExtractionRunsDao.findById`. Errors:
  `NotFoundException` (→ 404). Idempotent: yes.
- **create/update/delete/undelete** — all `null`.
- **isDeleted** — always `false`. **edges** — empty; **resolveEdges** — default
  (empty).

### `CollegesResource` (ENTITY, top-level, read-only — RFC 82)

`slug = "college"`, `title = "College"`, `kind = AdminKind.ENTITY` (the
`colleges` table is a versioned mutable entity). `parseId` accepts a UUID into
`CollegeId`. The table carries no `deleted_at`, so `isDeleted` is always `false`
and `scope`/`includeDeleted` are ignored.

List columns (`inList = true`): `name`, `city`, `state`, `control`,
`admissionRate`, `netPrice`. All fields are `editable = false`. Detail-only
(`inList = false`): `id`, `version`, `unitId`, `opeid`, `region`, `locale`,
`latitude`, `longitude`, `undergradEnrollment`, `satAvg`, `costAttendance`,
`tuitionInState`, `tuitionOutState`, `graduationRate`, `medianEarnings`,
`pctPell`, `website`, `createdAt`, `updatedAt`. The three decimal-ratio fields
(`admissionRate`, `graduationRate`, `pctPell`) are declared `FieldType.TEXT`
rather than `FieldType.INT` to preserve the fractional value (0.0–1.0). No
column is `sensitive`. One `History` edge ("Version history").

- **list** — reads via `CollegesDao.list(session, limit, offset)` inside
  `db.withConnection` (`scope` ignored). Ordered `name, unit_id`. Side effects:
  DB read. Idempotent: yes.
- **get** — reads one college via `CollegesDao.findById(session, id)` inside
  `db.withConnection` (`includeDeleted` ignored). Errors: `NotFoundException` (→
  404). Idempotent: yes.
- **create/update/delete/undelete** — all `null`; the engine registers no
  create/edit/delete routes and renders no Edit/Delete/New affordance.
- **isDeleted** — always `false`.
- **resolveEdges** — one "Version history" `EdgePanel.Table` built from
  `CollegesDao.listVersions(session, row.id)`. Columns: `Version` (INT), `Name`,
  `City`, `State`, `Control` (INT), `Admission Rate`, `Net Price` (INT),
  `Updated` (TIMESTAMP). Rows are rendered newest-last (ascending version order
  from `listVersions`). Side effects: DB read. Errors: DAO fault → failed
  `Result`. Idempotent: yes.

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
  `StudentsResource`, `SessionsResource`, `SystemPromptsResource`,
  `ClaimsResource`, `ObservationsResource`, `ExtractionRunsResource`,
  `ConvosResource`, and `ConvoRequestsResource` are stateless `object`s. All DB
  access is via the injected `Database`. Module-level config (bind host, cookie,
  session expiry) lives in `admin-server.conf` / `AdminConfig`, outside this
  directory.

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
- [x] [RFC-77: Admin Coaching-Memory Views](../../../../../../../../rfc/77-admin-coaching-memory-views.md)
      — Added three read-only descriptors over the coaching-memory tables:
      `ClaimsResource` (ENTITY, slug `claim`, one "Supporting observations"
      edge), `ObservationsResource` (LOG, slug `observation`, one "Supported
      claims" edge), and `ExtractionRunsResource` (LOG, slug `extraction-run`,
      no edges, token/write-count columns on the list). All three are
      list+detail only (four write handlers `null`, `isDeleted` always `false`,
      `scope`/`includeDeleted` ignored), delegating list/get to the typed DAOs
      inside `db.withConnection`. `StudentsResource.buildPanel` was extended to
      nest Claims, Observations, and Extraction-runs tables under the student
      profile (after the version-history table), each capped at the new
      `STUDENT_PANEL_LIMIT = 50` via `…Dao.listByStudent` with a shared
      `truncationRow` disclosure pointing at the canonical list.
- [x] [RFC-82: Versioned Colleges](../../../../../../../../rfc/82-versioned-colleges.md)
      — Added `CollegesResource` (ENTITY, `slug = "college"`, read-only: all
      four write handlers `null`, `isDeleted` always `false`,
      `scope`/`includeDeleted` ignored). Fields: all 25 curated columns from
      `College`; the three 0.0–1.0 decimal-ratio fields (`admissionRate`,
      `graduationRate`, `pctPell`) declared `FieldType.TEXT`. List shows
      `name`/`city`/`state`/`control`/ `admissionRate`/`netPrice`; the remaining
      fields are detail-only. One `AdminEdge.History` edge ("Version history")
      resolved via `CollegesDao.listVersions`, rendered as an `EdgePanel.Table`
      with columns
      `Version`/`Name`/`City`/`State`/`Control`/`Admission Rate`/`Net Price`/
      `Updated`. The descriptor is a stateless `object` with no injected
      collaborators; `college_programs` is not surfaced as an edge.
- [x] [RFC-79: Admin display conventions](../../../../../../../../rfc/79-admin-display-conventions.md)
      — Set `refSlug` on every id and foreign-key `AdminField` across all
      descriptors (per-resource assignments: `user.id`→`user`,
      `student.id`→`student`, `student.userId`→`user`, `session.id`→`session`,
      `session.userId`→`user`, `observation.id`→`observation`,
      `observation.studentId`→`student`, `claim.id`→`claim`,
      `claim.studentId`→`student`, `claim.supersededById`→`claim`,
      `extraction-run.id`→`extraction-run`,
      `extraction-run.studentId`→`student`,
      `extraction-run.systemPromptId`→`system-prompt`; columns whose target had
      no admin resource at that point — `convoId`, `sourceRequestId`,
      `throughRequestId` — carried no `refSlug` and rendered with no glyph).
      Converted all `EdgePanel.Table` column lists from `List<String>` to
      `List<Column>` carrying `type`/`refSlug`; removed the per-row `href` field
      from rows (navigation is now via the primary-id column's `refSlug` glyph).
      Converted `EdgePanel.Embedded.fields` in `StudentsResource.buildPanel`
      from `List<Pair<String, String>>` to `List<LabeledCell>` (using each
      field's `type` and `refSlug` from the descriptor). The coaching-memory
      panel builders (`buildClaimsPanel`, `buildObservationsPanel`,
      `buildExtractionRunsPanel`) set `refSlug` on their id columns and
      `FieldType.TIMESTAMP` on their created/uttered-at columns so those cells
      render as formatted dates with hover titles in the student panel.
- [x] [RFC-81: Admin conversation views](../../../../../../../../rfc/81-admin-conversation-views.md)
      — Added two read-only descriptors: `ConvosResource` (ENTITY, slug `convo`,
      ROW = `ConvoWithActivity`, one "Turns" `HasMany` edge targeting
      `convo-request`, capped at `TURNS_PANEL_LIMIT = 50`) and
      `ConvoRequestsResource` (LOG, slug `convo-request`, ROW = `ConvoTurn`, no
      edges, 18 fields covering request fields plus flattened optional response
      sub-fields, three JSON-typed fields). Both are list+detail only (four
      write handlers `null`, `isDeleted` always `false`).
      `StudentsResource.buildPanel` was extended to prepend a Conversations
      panel (before Claims/Observations/ Extraction-runs) built by
      `buildConversationsPanel` via
      `ConvosDao.listByStudentWithActivity(…, ArchiveScope.ALL, SoftDeleteScope.ALL,
      STUDENT_PANEL_LIMIT, 0)`.
      `ObservationsResource.convoId` gained `refSlug = "convo"` and
      `sourceRequestId` gained `refSlug = "convo-request"`.
      `ExtractionRunsResource.convoId` gained `refSlug = "convo"` and
      `throughRequestId` gained `refSlug = "convo-request"`. The "Supporting
      observations" panel in `ClaimsResource.resolveEdges` gained two columns —
      Convo (`refSlug = "convo"`) and Source Request
      (`refSlug = "convo-request"`) — so the claim detail shows conversation
      context for each backing observation.
- [x] [RFC-83: Compact display of entity id columns](../../../../../../../../rfc/83-admin-compact-id-display.md)
      — Flipped every UUID-valued `AdminField` to `FieldType.UUID` across all
      seven resource files, leaving BIGINT-carrying columns (`observations.id`,
      `extraction_runs.id`, `sourceRequestId`, `throughRequestId`) as
      `FieldType.TEXT`. The complete set: `user.id`, `student.id`,
      `student.userId`, `session.id`, `session.userId`, `system-prompt.id`,
      `claim.id`, `claim.studentId`, `claim.supersededById`,
      `observation.studentId`, `observation.convoId`,
      `extraction-run.studentId`, `extraction-run.systemPromptId`,
      `extraction-run.convoId`. `EdgePanel.Table.Column` entries for UUID-valued
      id columns in edge panels also set `FieldType.UUID` (sessions panel ID
      column; claims panel ID column in the student coaching-memory view;
      "Supported claims" panel ID column in `ObservationsResource`); BIGINT id
      columns in edge panels stay `FieldType.TEXT` (observation and
      extraction-run ID columns in the student panel; observation ID in
      `ClaimsResource`'s supporting-observations panel). The `convoId` column in
      the student observations panel sets `FieldType.UUID` (it also carries
      `refSlug = "convo"` after the RFC 81 merge, so it compacts and links).
      `StudentsResource.buildPanel` propagates UUID types into each
      `EdgePanel.LabeledCell` automatically because it copies `field.type` from
      the descriptor — no separate edit needed for the embedded student panel.
