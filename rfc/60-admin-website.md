# RFC 60: Admin Website (Framework + Users Spine)

## Executive Summary

This RFC introduces an internal, developer-facing admin website for inspecting
and mutating database rows without a `psql` session. It is a new isolated
`admin-server` Gradle module that serves server-rendered HTML (Ktor's
`kotlinx.html` DSL) from its own process on a configurable, internal-only bind
host. It replaces ad-hoc direct database access for debugging and manual
testing.

The tool is **domain-aware**, not a flat table browser: a registry of typed
resource descriptors drives navigation, list/detail rendering, and forms, and
weaves related rows together (a user's embedded student profile, its sessions,
and its version history all compose onto one user page). Every operation
delegates to the existing typed DAOs, so OCC version handling, `Result`-typed
error classification, and string normalization are reused rather than
re-implemented as SQL strings.

Operations are constrained to exactly what the schema's triggers already permit
(no trigger changes): entity deletes are soft (`deleted_at`), `sessions` deletes
are physical, and immutable/append-only tables are never mutated. Authorization
adds an `is_admin` flag to `users`; the admin logs in through the existing
`AuthService`, and every request is gated on `is_admin`. A `bin/admin-grant`
script bootstraps the first admin.

To keep scope atomic, this RFC delivers the **framework plus the `users` spine**
— `users`, the embedded `students` profile, `sessions`, and the version-history
panels — which together exercise every architectural pattern the engine needs. A
uniform DAO interface family and the remaining tables (`convos`, the transcript
logs, `system_prompts`, `jobs`) are deferred to follow-up RFCs that extend this
proven engine.

## Detailed Design

### Module and process

`admin-server` is a new Gradle module mirroring `rest-server`'s structure: a
Ktor + Netty embedded server with an `application` plugin `mainClass` of
`ed.unicoach.admin.ApplicationKt`. It depends on `:common`, `:db`, and
`:service`. It is launched and supervised by the same `bin/daemon-up` machinery
as the other daemons.

The server binds to a configurable host defaulting to `127.0.0.1` — the primary
security boundary is network isolation; the tool is never internet-exposed.
Reaching it from another host is an operator concern (SSH tunnel / VPN), out of
scope here.

The admin server constructs its own dependencies at startup, mirroring
`rest-server`'s `startServer()`:

```
AppConfig.load("common.conf", "db.conf", "admin-server.conf")  // classpath + local overlay
Database(DatabaseConfig.from(config))
AuthService(database, Argon2Hasher(), TokenGenerator())        // login + getCurrentUser
```

It does not load `rest-server.conf` and shares no configuration object with
`rest-server`; the admin's cookie and bind settings live in a dedicated
`admin-server.conf` and `AdminConfig`.

### Authorization model

Authorization adds a single boolean column to `users` and reuses the existing
session/cookie machinery.

**Schema.** Migration `0012.add-users-is-admin.sql` adds
`is_admin BOOLEAN NOT NULL DEFAULT false` to `users`, adds the same column
(`NOT NULL DEFAULT false`) to `users_versions`, and `CREATE OR REPLACE`s
`log_user_version()` to copy `NEW.is_admin` into the history row. The column is
a normal domain column: it is mutable (not covered by
`prevent_immutable_updates()`), so privilege grants are ordinary versioned
updates and are captured in `users_versions` like every other field change.

**Login.** `admin-server` serves an unauthenticated `GET /login` HTML form and a
`POST /login` handler that calls
`AuthService.login(email, password, oldCookieToken = null, sessionExpirationSeconds = session.expirationSeconds, userAgent, initialIp)`,
sourcing `sessionExpirationSeconds` from `AdminConfig` and `userAgent`/`initialIp`
from the request. On `LoginResult.Success(user, token)` it sets an admin session
cookie (name/attributes from `AdminConfig`) carrying the raw `token` and redirects
to `/`. Every non-`Success` variant (`UserNotFound`, `PasswordMismatch`,
`PasswordNotSet`, `InvalidEmail`) re-renders the login form with a single generic
"invalid email or password" message (no variant disclosure). `POST /logout` calls
`AuthService.logout(...)` and clears the cookie. Login authenticates _any_ valid
user; authorization is enforced separately by the gate.

**Gate.** A Ktor plugin runs on every request except `/login`, `/logout`, and
`/healthz`. It reads the admin cookie, hashes it via `TokenHash.fromRawToken`,
resolves the user via `AuthService.getCurrentUser(tokenHash)`, and:

- missing/invalid cookie or unknown token → redirect to `/login`;
- resolved user with `is_admin == false` → `403` with an HTML "not authorized"
  page;
- resolved admin → request proceeds, with the `User` available to handlers.

**Bootstrap.** `bin/admin-grant <email>` mints the first admin (the tool that
grants admin cannot be used until an admin exists). It is the single documented
exception to the DAO-only write path: a `psql` script that, in one transaction,
reads the active user's current `version` and issues
`UPDATE users SET is_admin = true, version = <current+1> WHERE id = ? AND version = <current>`.
All invariants remain enforced by the DB triggers the statement fires
(`enforce_versioning`, `update_timestamp`, `log_user_version`). Subsequent
grants/revocations are done in-tool by editing the `is_admin` field on the user
form — the column is the single source of truth.

### Registry and engine

The engine is a registry of typed **resource descriptors**; each table is one
descriptor. A descriptor's `kind` determines its allowed operation set and
render strategy, which is what makes adding a table cheap. Structural shape
(declarative; no operation bodies shown):

```
enum class FieldType { TEXT, MULTILINE, INT, BOOL, TIMESTAMP, JSON, ENUM }

data class AdminField(
  val name: String,        // form/column key
  val label: String,
  val type: FieldType,
  val editable: Boolean,   // false -> read-only (id, timestamps, version)
  val sensitive: Boolean,  // true  -> redacted in views, absent from forms
)

sealed interface AdminEdge {
  // Renders a link + summary of a parent row on the detail page.
  data class Parent(val label: String, val targetSlug: String) : AdminEdge
  // Renders an inline table of child rows linking to their own detail pages.
  data class HasMany(val label: String, val targetSlug: String) : AdminEdge
  // Renders a version-history panel (read-only) for this row.
  data class History(val label: String) : AdminEdge
  // Renders an owned entity inline as a panel with its own action endpoints.
  data class Embedded(val label: String, val resource: AdminResource<*, *>) : AdminEdge
}

enum class AdminKind { ENTITY, EMBEDDED_ENTITY, IMMUTABLE_ENTITY, LOG, NON_ENTITY, SUPPORT }

// One per table. Operations are typed handlers that call DAOs; unsupported
// operations (per kind) are null. ROW = domain model, ID = its id type.
interface AdminResource<ROW, ID> {
  val slug: String              // canonical route segment, e.g. "user"
  val title: String
  val kind: AdminKind
  val topLevel: Boolean         // appears in nav + has a /{slug} list
  val fields: List<AdminField>
  val edges: List<AdminEdge>

  fun rowId(row: ROW): ID
  fun cells(row: ROW): Map<String, String>   // field name -> rendered cell

  // Admin reads include soft-deleted rows: lists pass SoftDeleteScope.ALL,
  // detail reads pass includeDeleted = true. limit/offset drive paging.
  suspend fun list(db: Database, limit: Int, offset: Int, scope: SoftDeleteScope): Result<List<ROW>>
  suspend fun get(db: Database, id: ID, includeDeleted: Boolean): Result<ROW>
  // Optional per kind; null = operation not offered:
  val create: (suspend (db: Database, form: Map<String, String>) -> Result<ID>)?
  val update: (suspend (db: Database, id: ID, form: Map<String, String>) -> Result<Unit>)?
  val delete: (suspend (db: Database, id: ID) -> Result<Unit>)?  // soft or hard per kind
}
```

The engine registers generic routes for every `topLevel` resource and renders
from the descriptor:

| Route                      | Condition        | Renders / does                                    |
| -------------------------- | ---------------- | ------------------------------------------------- |
| `GET /`                    | —                | Dashboard: nav of top-level sections              |
| `GET /{slug}`              | `topLevel`       | List view (table of `cells`, rows link to detail) |
| `GET /{slug}/new`          | `create != null` | Create form from editable `fields`                |
| `POST /{slug}`             | `create != null` | Create → redirect to new row's detail             |
| `GET /{slug}/{id}`         | —                | Detail: field table + edge panels                 |
| `GET /{slug}/{id}/edit`    | `update != null` | Edit form (current values; hidden `version`)      |
| `POST /{slug}/{id}`        | `update != null` | Update → redirect to detail                       |
| `POST /{slug}/{id}/delete` | `delete != null` | Delete (soft/hard) → redirect to list             |

**Canonical routing invariant.** A given entity has exactly one detail URL,
`/{slug}/{id}`. Relationship panels render rows as links to that canonical URL;
there are no nested detail URLs (never `/user/{id}/session/{id}`). An
`EMBEDDED_ENTITY` is the sole exception to having _any_ standalone URL: it has
no list and no detail page, and its mutating actions are nested under its owner
(see below). Its `get`/`list` members are still implemented — the owner's
`Embedded` edge calls `get` to render the inline panel — they are simply never
bound to a route.

Rendering is HTML via `kotlinx.html`: a shared layout (nav sidebar of top-level
sections + content), a list table, a detail page (field table followed by one
panel per edge), and field-typed form inputs. `JSON` fields render as a textarea
of raw JSON, validated as well-formed before submit; `BOOL` as a checkbox;
`TIMESTAMP`/`INT`/`TEXT`/`MULTILINE`/`ENUM` accordingly. `sensitive` fields are
redacted in views and never appear in forms.

### Operation matrix (respect DB guards)

Operations are exactly what the schema permits — no trigger changes. For the v1
resources:

| Resource            | Kind            | Create |     Read/List     | Update  |       Delete        |
| ------------------- | --------------- | :----: | :---------------: | :-----: | :-----------------: |
| `users`             | ENTITY          |   ✓    |         ✓         | ✓ (OCC) | soft (`deleted_at`) |
| `students`          | EMBEDDED_ENTITY |   ✓    |     ✓ (panel)     | ✓ (OCC) | soft (`deleted_at`) |
| `sessions`          | ENTITY          |   ✗    |         ✓         |    ✗    |   hard (physical)   |
| `users_versions`    | SUPPORT         |   ✗    | ✓ (history panel) |    ✗    |          ✗          |
| `students_versions` | SUPPORT         |   ✗    | ✓ (history panel) |    ✗    |          ✗          |

`sessions` offers no create (rows originate from auth flows) and no update (its
DAO mutators are domain-specific lifecycle operations, not field edits); it is a
top-level list plus per-user has-many plus physical delete, which is the useful
debugging surface.

### Relationship weaving

The `/user/{id}` detail page is the richest composition and exercises every edge
renderer:

- **Embedded** `students` — the user's 1:1 student profile rendered as a panel
  with create / edit / soft-delete actions; no `/student` route exists. Actions
  POST to owner-nested endpoints: `POST /user/{id}/student`,
  `POST /user/{id}/student/update`, `POST /user/{id}/student/delete`. These are
  action endpoints, not detail pages, so the canonical-routing invariant holds.
- **HasMany** `sessions` — an inline table of the user's sessions (via
  `SessionsDao.listByUser`), each row linking to `/session/{id}`.
- **History** `users_versions` — a read-only panel of the user's version rows
  (via `UsersDao.listVersions`).
- Inside the embedded student panel, a nested **History** panel of
  `students_versions` (via `StudentsDao.listVersions`).

`/session/{id}` renders a **Parent** edge link back to its owning `/user/{id}`
when `user_id` is non-null.

### Write path

All in-tool writes delegate to typed DAO methods inside
`Database.withConnection` transactions; the engine never builds SQL.

- **OCC.** Edit forms carry the current `version` in a hidden field. The update
  handler reads the row for update, applies form fields, and calls the DAO
  update, which performs the versioned write. A
  `ConcurrentModificationException` from the DAO renders a "row changed since
  you loaded it; reload" page. `convos`/`sessions` are not updatable here, so
  the only OCC update paths are `users` and `students`.
- **Soft delete.** `users`/`students` delete handlers call `UsersDao.delete` /
  `StudentsDao.delete` (set `deleted_at`, bump version). Admin reads include
  soft-deleted rows so they remain visible and are visually marked: detail
  handlers call `findById(..., includeDeleted = true)` (the existing boolean
  parameter on `UsersDao`/`StudentsDao` `findById`), and list handlers pass
  `SoftDeleteScope.ALL` to `listAll`. The new list method adopts the existing
  `SoftDeleteScope` enum (`ConvosDao` precedent); the per-row `findById` keeps
  its established boolean parameter rather than gaining a scope.
- **Hard delete.** `sessions` delete calls a new `SessionsDao.deleteById`
  (physical `DELETE`, permitted by the absence of `prevent_physical_delete` on
  `sessions`).
- **`users` create.** The handler hashes the submitted plaintext password and
  builds the auth method directly — no `AuthService.register` (which also
  creates sessions): `Argon2Hasher().hash(password)` →
  `PasswordHash.create(...)` → `AuthMethod.Password(...)` → `NewUser(...)`
  (carrying `isAdmin`) → `UsersDao.create`. Uniqueness and normalization are
  DB-enforced; `DuplicateEmailException` renders a field error.
- **`students` create/edit.** Through `StudentService` /`StudentsDao`; the
  graduation date is the variable-precision `PartialDate` (year required;
  month/day optional), submitted as the ISO string the service already accepts.
- **Sensitive columns.** `users.password_hash` and `sessions.token_hash` are
  marked `sensitive`: redacted in detail views, absent from all forms, never
  logged.

### DB-layer changes

Adding `is_admin` ripples through the `users` read/write path; the admin read
surface adds list/version methods the app never needed.

- `User` and `UserVersion` gain `isAdmin: Boolean`; `NewUser` gains
  `isAdmin: Boolean = false`. The default minimizes churn at existing
  construction sites.
- `UsersDao`: `mapUser`/`mapUserVersion` read `is_admin`; the `INSERT` (create)
  and `UPDATE` (`doUpdate`, `revertToVersion`) statements include `is_admin`.
  New methods:
  - `fun listAll(session: SqlSession, scope: SoftDeleteScope = SoftDeleteScope.ALL, limit: Int, offset: Int): Result<List<User>>`
  - `fun listVersions(session: SqlSession, id: UserId): Result<List<UserVersion>>`
- `StudentsDao`: new
  `fun listVersions(session: SqlSession, id: StudentId): Result<List<StudentVersion>>`,
  reading `students_versions` (columns mirror `students`). Requires a new
  `StudentVersion` domain model (mirroring `Student`'s historical columns).
- `SessionsDao`: new
  `fun findById(session: SqlSession, id: SessionId): Result<Session>`,
  `fun listByUser(session: SqlSession, userId: UserId, limit: Int, offset: Int): Result<List<Session>>`,
  `fun listAll(session: SqlSession, limit: Int, offset: Int): Result<List<Session>>`,
  `fun deleteById(session: SqlSession, id: SessionId): Result<Unit>`.
  `sessions` has no `deleted_at` column, so these take no `SoftDeleteScope`; the
  sessions resource ignores the `scope` argument the engine passes to
  `AdminResource.list`.

`SoftDeleteScope.ALL` already exists. List methods order by `created_at DESC`
and accept `limit`/`offset`; the engine passes a fixed page size (50) and an
offset parsed from the `?offset=` query string. To surface a next page without a
`COUNT(*)`, the engine requests `limit + 1` rows: if the surplus row is present
it renders a "next" link (`?offset=offset+size`) and drops the surplus from the
table; a "previous" link renders whenever `offset > 0`.

### Configuration

New `admin-server/src/main/resources/admin-server.conf`:

```
admin {
  server {
    host = "127.0.0.1"
    host = ${?ADMIN_SERVER_HOST}
    port = 8081
    port = ${?ADMIN_SERVER_PORT}
  }
  session {
    cookieName    = "admin_session"
    cookieDomain  = ""        # empty => host-only cookie
    cookieDomain  = ${?ADMIN_COOKIE_DOMAIN}
    cookieSecure  = false
    cookieSecure  = ${?ADMIN_COOKIE_SECURE}
    expirationSeconds = 86400
  }
}
```

`AdminConfig.from(config): Result<AdminConfig>` parses these fail-fast at
startup, mirroring `DatabaseConfig.from`.

### Error handling and edge cases

- **Not found.** `get`/`list` returning `NotFoundException` (or empty) → `404`
  HTML page.
- **OCC conflict.** `ConcurrentModificationException` on update → conflict page
  prompting reload (no silent overwrite).
- **Duplicate email / constraint violation.** `DuplicateEmailException` /
  `ConstraintViolationException` on create/update → the form re-rendered with
  the error message and the submitted values preserved.
- **Malformed JSON field.** Rejected client-irrelevant; validated server-side
  before the DAO call → form re-rendered with a field error.
- **Transient DB failure.** `DatabaseException` /
  `LockAcquisitionFailureException` → `503` HTML page.
- **Login failure.** Any non-`Success` `LoginResult` re-renders `GET /login`'s
  form with a generic "invalid email or password" message; no variant is
  disclosed.
- **Unauthorized vs unauthenticated.** No/invalid session → redirect to
  `/login`; authenticated non-admin → `403` page (never a redirect loop, since
  `/login` is exempt from the gate).
- **Soft-deleted rows.** Visible in admin lists/detail (include-deleted scope)
  and marked; offered an undelete action only where a DAO `undelete` exists
  (`users`).

### Dependencies

- New runtime dependency `io.ktor:ktor-server-html-builder` (Ktor `3.4.2`, the
  pinned version) added to `gradle/libs.versions.toml` and the `admin-server`
  build. No other new third-party dependency.
- Internal module dependencies: `:common`, `:db`, `:service`.
- No dependency on `:rest-server`, `:queue`, `:email`, or `:chat` in this RFC.

## Tests

### `db` module (`nix develop -c bin/test db --force`)

- **`UsersDaoTest` — `is_admin` round-trip.** `create` with `isAdmin = true`
  reads back `true`; default `create` reads back `false`.
- **`UsersDaoTest` — `is_admin` history capture.** Updating `is_admin` from
  `false` to `true` bumps `version`, and `listVersions` returns a row whose
  `isAdmin` reflects each historical value (verifies the `log_user_version`
  replacement).
- **`UsersDaoTest` — `listAll` scope.** With a mix of active and soft-deleted
  users, `listAll(scope = ALL)` returns both; `scope = ACTIVE` excludes deleted;
  ordering is `created_at DESC`; `limit`/`offset` page correctly.
- **`UsersDaoTest` — `listVersions` ordering.** Multiple updates yield versions
  in ascending `version` order.
- **`StudentsDaoTest` — `listVersions`.** After updates, returns the student's
  historical rows mapped to `StudentVersion`.
- **`SessionsDaoTest` — `findById`.** Returns the session by id; unknown id →
  `NotFoundException`.
- **`SessionsDaoTest` — `listByUser` / `listAll`.** Return the expected
  sessions, `created_at DESC`, paged; `listByUser` filters to the owner.
- **`SessionsDaoTest` — `deleteById`.** Physically removes the row; a subsequent
  `findById` → `NotFoundException`.
- **Migration test.** Applying `0012` against the test DB succeeds; an existing
  user row gets `is_admin = false`; `users_versions` gains the column.

### `admin-server` module (`nix develop -c bin/test admin-server --force`, Ktor `testApplication`)

- **Health.** `GET /healthz` → `200`, constant body, no auth.
- **Gate — unauthenticated.** Any `/user` request with no cookie → `302` to
  `/login`.
- **Gate — non-admin.** A valid session for a user with `is_admin = false` →
  `403` on `/user`.
- **Gate — admin.** A valid admin session → `200` on `/`, `/user`, `/session`.
- **Login/logout.** `POST /login` with valid admin credentials sets the cookie
  and redirects to `/`; `POST /logout` clears it; subsequent request redirects
  to `/login`.
- **Login failure.** `POST /login` with a wrong password (and with an unknown
  email) re-renders the form with the generic "invalid email or password"
  message and sets no cookie.
- **Users list/detail.** Seeded users render in `/user`; `/user/{id}` shows
  fields with `password_hash` redacted and absent from the edit form.
- **List pagination.** Seeding more than one page of users renders a "next"
  link on `/user`; following it (`?offset=`) shows the second page, and a
  "previous" link appears; the surplus `limit + 1` row is not rendered.
- **Users create.** `POST /user` with email/name/password creates a loginable
  user (password verifies via `Argon2Hasher`); duplicate email re-renders the
  form with an error.
- **Users update + OCC.** Editing name bumps version; submitting a stale
  `version` renders the conflict page.
- **Users soft-delete.** `POST /user/{id}/delete` sets `deleted_at`; the row
  stays visible and marked deleted; undelete restores it.
- **Grant/revoke admin.** Editing `is_admin` to `true`/`false` is reflected on
  the next gate evaluation for that user.
- **Embedded student.** `/user/{id}` with no profile shows a create panel;
  `POST /user/{id}/student` creates it; edit/soft-delete operate inline; no
  `/student` route resolves (404).
- **Version panels.** `/user/{id}` renders a `users_versions` history panel; the
  embedded student panel renders a `students_versions` history panel.
- **Sessions.** `/session` lists sessions; `/session/{id}` links to its owner;
  `POST /session/{id}/delete` physically deletes it; `token_hash` never
  rendered.
- **Canonical routing.** A has-many session row on `/user/{id}` links to
  `/session/{id}` (not a nested path).

### `bin` (`nix develop -c bin/admin-grant <email>` against the test DB)

- Granting a known active user sets `is_admin = true`, bumps `version`, and adds
  a `users_versions` row; re-running is observable (a second version bump) and
  errors cleanly on an unknown email.

## Implementation Plan

Each step is independently compilable/testable. Verification commands assume the
nix dev shell (`nix develop -c ...`).

1. **Migration + `is_admin` model/DAO plumbing.**
   - Add `db/schema/0012.add-users-is-admin.sql`:
     `ALTER TABLE users ADD COLUMN
     is_admin BOOLEAN NOT NULL DEFAULT false`;
     `ALTER TABLE users_versions ADD
     COLUMN is_admin BOOLEAN NOT NULL DEFAULT false`;
     `CREATE OR REPLACE FUNCTION
     log_user_version()` including `is_admin`.
   - Add `isAdmin` to `User`, `UserVersion`, `NewUser` (default `false`).
   - Update `UsersDao.mapUser`/`mapUserVersion` and the `INSERT`/`UPDATE`
     statements to carry `is_admin`.
   - Verify: `nix develop -c bin/db-reset`;
     `nix develop -c bin/test db --force`.
2. **Admin read methods + `StudentVersion`.**
   - Add `UsersDao.listAll`, `UsersDao.listVersions`.
   - Add `StudentVersion` model and `StudentsDao.listVersions`.
   - Add `SessionsDao.findById`, `listByUser`, `listAll`, `deleteById`.
   - Add the DAO tests enumerated above.
   - Verify: `nix develop -c bin/test db --force`.
3. **Module scaffold + health.**
   - `include("admin-server")` in `settings.gradle.kts`; add
     `ktor-server-html-builder` to `gradle/libs.versions.toml`; create
     `admin-server/build.gradle.kts`.
   - Add `admin-server.conf`, `AdminConfig`, `Application.kt` (config load,
     `Database`, `AuthService`, Netty bind), and `GET /healthz`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`;
     `nix develop -c bin/build-admin-server`; start it and `curl` `/healthz`.
4. **Auth: login, logout, gate.**
   - Login/logout routes + cookie handling; the `is_admin` gate plugin.
   - Add the gate/login `admin-server` tests.
   - Verify: `nix develop -c bin/test admin-server --force`.
5. **Engine core: descriptors, registry, routing, rendering.**
   - `AdminField`/`AdminEdge`/`AdminResource`/`AdminKind`; the registry; generic
     routes; `kotlinx.html` layout/list/detail/form renderers; HTML error pages.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`.
6. **Users resource.**
   - Descriptor (fields incl. sensitive `password_hash`, `is_admin`; edges:
     embedded student, has-many sessions, history) wired to `UsersDao` +
     `Argon2Hasher` create path.
   - Add the users `admin-server` tests.
   - Verify: `nix develop -c bin/test admin-server --force`.
7. **Embedded students resource.**
   - Owner-nested create/update/delete endpoints; embedded panel render;
     `students_versions` history panel.
   - Add the embedded-student tests.
   - Verify: `nix develop -c bin/test admin-server --force`.
8. **Sessions resource.**
   - List/detail/hard-delete; parent edge to user; has-many wiring on the user
     page.
   - Add the sessions tests.
   - Verify: `nix develop -c bin/test admin-server --force`.
9. **`bin` scripts + module-list updates.**
   - `bin/build-admin-server`, `bin/admin-server-up`, `bin/admin-server-down`,
     `bin/admin-server-check`, `bin/admin-server-wait-for-health`,
     `bin/admin-grant`.
   - Add `admin-server` to `bin/test` `MODULES` (+ help text) and add
     `build-admin-server` to `bin/build` `MODULES` (which lists `build-*` script
     names, not bare module names).
   - Verify: `nix develop -c bin/build`;
     `nix develop -c bin/admin-grant <email>` against the test DB;
     `nix develop -c bin/test admin-server --force`.

## Files Modified

### Created — `admin-server` module

- `admin-server/build.gradle.kts`
- `admin-server/src/main/resources/admin-server.conf`
- `admin-server/src/main/kotlin/ed/unicoach/admin/Application.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/AdminConfig.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/auth/AdminAuth.kt`
  (login/logout routes + `is_admin` gate plugin)
- `admin-server/src/main/kotlin/ed/unicoach/admin/engine/AdminResource.kt`
  (`AdminResource`, `AdminKind`)
- `admin-server/src/main/kotlin/ed/unicoach/admin/engine/AdminField.kt`
  (`AdminField`, `FieldType`)
- `admin-server/src/main/kotlin/ed/unicoach/admin/engine/AdminEdge.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/engine/AdminRegistry.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/engine/AdminRouting.kt`
  (generic route registration)
- `admin-server/src/main/kotlin/ed/unicoach/admin/render/Layout.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/render/ListView.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/render/DetailView.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/render/FormView.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/render/ErrorPages.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/UsersResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/StudentsResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/SessionsResource.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/Health.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/HealthTest.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/auth/AdminAuthTest.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/UsersResourceTest.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/StudentsResourceTest.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/SessionsResourceTest.kt`

### Created — schema, model, scripts

- `db/schema/0012.add-users-is-admin.sql`
- `db/src/main/kotlin/ed/unicoach/db/models/StudentVersion.kt`
- `bin/build-admin-server`
- `bin/admin-server-up`
- `bin/admin-server-down`
- `bin/admin-server-check`
- `bin/admin-server-wait-for-health`
- `bin/admin-grant`

### Modified — `db` module

- `db/src/main/kotlin/ed/unicoach/db/models/User.kt` (add `isAdmin`)
- `db/src/main/kotlin/ed/unicoach/db/models/UserVersion.kt` (add `isAdmin`)
- `db/src/main/kotlin/ed/unicoach/db/models/NewUser.kt` (add `isAdmin = false`)
- `db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt` (map/insert/update
  `is_admin`; `listAll`, `listVersions`)
- `db/src/main/kotlin/ed/unicoach/db/dao/StudentsDao.kt` (`listVersions`)
- `db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt` (`findById`,
  `listByUser`, `listAll`, `deleteById`)
- `db/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/StudentsDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/SessionsDaoTest.kt`

### Modified — build and tooling

- `settings.gradle.kts` (`include("admin-server")`)
- `gradle/libs.versions.toml` (`ktor-server-html-builder`)
- `bin/test` (`MODULES` + help text)
- `bin/build` (`MODULES`)
