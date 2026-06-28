# RFC 76: Admin email-verification actions

## Executive Summary

Two operator actions on the admin `user` detail page: mark a user's email
verified, and send (re-issue) a verification email. Both domain operations
already exist — `UsersDao.markEmailVerified` (a versioned conditional update
stamping `email_verified_at`, captured in `users_versions`) and
`EmailVerificationService.resend` (burn outstanding tokens, issue a fresh one,
best-effort post-commit send). This RFC is admin plumbing: it exposes those
operations through the admin engine and surfaces the verification status, which
the `user` descriptor does not render today.

The admin detail renderer currently emits only the descriptor-derived
Edit/Delete/Undelete buttons; there is no path for a resource to declare its own
action buttons. Rather than special-case the `user` table in the shared
renderer, this adds a generic per-row `customActions` declaration to
`AdminResource`, keeping the "engine renders from the descriptor" boundary
intact and reusable for future per-row actions.

`UsersResource` declares the two verification actions, registers their POST
routes alongside the existing owner-nested `students` routes, gains an injected
`EmailVerificationService`, and surfaces a read-only verification-status field.
No schema change.

## Detailed Design

### Data Models

No schema change. The feature consumes existing structures only:

- `users.email_verified_at` (`TIMESTAMPTZ NULL`, migration `0013`), already
  mirrored into `users_versions`. `User.emailVerifiedAt: Instant?` exposes it.
- `verification_tokens` (migration `0014`), written by
  `EmailVerificationService.resend` via `VerificationTokensDao`.

### Engine: `CustomAction` and `AdminResource.customActions`

A custom action is a per-row POST button declared by a descriptor and rendered
by the engine. New type (`admin-server/.../engine/CustomAction.kt`):

```kotlin
data class CustomAction<ROW>(
  val label: String,
  // Appended to "/${slug}/${idPath}" to form the POST target; the resource
  // registers a matching route in registerExtraRoutes.
  val pathSuffix: String,
  // null => enabled. Non-null => the button renders disabled, and the string is
  // its hover title explaining why. Single source of truth: enabled iff null.
  val disabledReason: (ROW) -> String?,
)
```

New `AdminResource` member, defaulting empty so existing descriptors are
unaffected:

```kotlin
val customActions: List<CustomAction<ROW>>
  get() = emptyList()
```

`renderDetail` renders one POST form per entry in `resource.customActions`,
immediately after the existing Edit/Delete/Undelete action block and before the
edge panels. For each action it computes `disabledReason(row)`; when null the
button is an ordinary submit POST to `/${slug}/${idPath}/${pathSuffix}`; when
non-null the submit button carries the HTML `disabled` attribute and a `title`
set to the reason string. The existing `actionButton` helper is not reused (it
emits neither a disabled state nor a title); custom actions render their own
form/button.

The button is the only render surface affected: the field table, the
Edit/Delete/Undelete block, and edge panels are unchanged. The engine never
inspects the `pathSuffix` target — route registration is the descriptor's
responsibility, exactly as for the owner-nested `students` actions.

### `UsersResource`

**Status field.** A read-only `emailVerifiedAt` `AdminField` with display label
`"Email Verified"` (`FieldType.TIMESTAMP`, `editable = false`,
`sensitive = false`) is appended to `fields`, and `cells` gains
`"emailVerifiedAt" to (row.emailVerifiedAt?.toString()
?: "")`. This is a
read-only domain column written only by the dedicated verification/change-email
paths; it is absent from `createExtraInputs` and the edit form, so the generic
`UserEdit` update path cannot touch it.

**Dependency.** The constructor gains
`private val emailVerificationService: EmailVerificationService` alongside the
existing `argon2Hasher`.

**Custom actions.** Two entries in `customActions`, sharing one predicate
`verificationDisabledReason(user): String?`:

```
verificationDisabledReason(user) =
  when {
    user.emailVerifiedAt != null -> "Email already verified."
    user.deletedAt != null       -> "User is deleted."
    else                         -> null
  }
```

- `CustomAction("Mark email verified", "verify-email", ::verificationDisabledReason)`
- `CustomAction("Send verification email", "send-verification-email", ::verificationDisabledReason)`

**Routes.** `registerExtraRoutes` registers two POST endpoints next to the
existing `students` actions, both under the engine's gated scope:

- `POST /user/{id}/verify-email` — parse the id (bad id → redirect `/user`),
  then `db.withConnection { UsersDao.markEmailVerified(session, id) }`;
  `fold(onSuccess → respondRedirect("/user/{id}"), onFailure →
  respondDaoError)`.
  `markEmailVerified` is idempotent on an already-verified active user (returns
  the row unchanged, no version bump) and yields `NotFoundException` only when
  the user is absent or soft-deleted.
- `POST /user/{id}/send-verification-email` — parse the id (bad id → redirect
  `/user`), then chain the load and the resend through a single `Result`:
  `db.withConnection { UsersDao.findById(session, id, SoftDeleteScope.ACTIVE) }`
  threaded into `emailVerificationService.resend(user)` (mirroring the
  `mapCatching` flatten already used by the `student/update` route in
  `registerExtraRoutes`, since `findById` yields `Result<User>` and `resend`
  yields `Result<ResendResult>`), then
  `fold(onSuccess → respondRedirect("/user/{id}"), onFailure →
  respondDaoError)`.
  `resend` returns `ResendResult.Sent` or `ResendResult.AlreadyVerified` — both
  are `Result.success` and redirect; the `onFailure` branch covers only a DB
  fault during token issuance, since delivery is best-effort and a send failure
  is swallowed/logged upstream.

Routes register unconditionally (matching the engine's edit/delete pattern); the
disabled button is a UX affordance, and the handlers defend the
forged/raced-POST case via the idempotent DAO contract.

### API Contracts

| Method | Path                                 | Body | Success            | Failure                                                                           |
| ------ | ------------------------------------ | ---- | ------------------ | --------------------------------------------------------------------------------- |
| POST   | `/user/{id}/verify-email`            | none | `302 → /user/{id}` | bad id → `302 → /user`; absent/soft-deleted → `404`; DB fault → engine error page |
| POST   | `/user/{id}/send-verification-email` | none | `302 → /user/{id}` | bad id → `302 → /user`; DB fault on token issue → engine error page               |

Both routes sit behind the admin gate (registered under the engine's gated
scope). The detail page (`GET /user/{id}`) additionally renders the two buttons
and the `email_verified_at` row.

### Error Handling / Edge Cases

- **Already verified.** Button renders disabled with title "Email already
  verified." A forged POST to `verify-email` returns the row unchanged
  (idempotent `markEmailVerified`) and redirects; a forged POST to
  `send-verification-email` returns `AlreadyVerified` and redirects. No second
  token, no version bump.
- **Soft-deleted user.** Both buttons disabled with title "User is deleted." A
  forged `verify-email` POST hits `markEmailVerified`'s `deleted_at IS NULL`
  guard → `NotFoundException` → 404. `send-verification-email` loads with
  `SoftDeleteScope.ACTIVE` → `NotFoundException` → 404.
- **Delivery failure.** `resend` swallows/logs a provider rejection; the admin
  action reports success. The button confirms a token was issued, not that mail
  was delivered (accepted; preserves `resend`'s best-effort contract).
- **Unparseable id.** Redirect to `/user`, consistent with the existing
  `students` action handlers.

### Dependencies

- `EmailVerificationService`, `EmailService`, and `EmailConfig` are already
  constructed in `Application.startServer` and `AdminTestSupport` (today only to
  satisfy the `AuthService` constructor). This RFC threads the existing
  `emailVerificationService` into `adminModule` and on into `UsersResource`; no
  new service is built. The email provider is config-selected (`log` by default,
  `ses` via `EMAIL_PROVIDER`), unchanged.

## Tests

All in `admin-server/.../resources/UsersResourceTest.kt`, following the existing
cookie-authenticated `testApplication` pattern (`installTestAdminModule`,
`adminCookie`, non-following client). `AdminTestSupport.seedUser` creates an
unverified user (`email_verified_at` defaults NULL).

1. **Unverified active user shows both actions enabled.** Seed a user, GET
   `/user/{id}`; assert the body contains both `Mark email verified` and
   `Send
   verification email`, and that neither button form is rendered
   `disabled` (assert the rendered action buttons carry no `disabled` attribute
   / no "already verified" title).
2. **Mark-verified stamps the column and flips the buttons.** Seed a user, POST
   `/user/{id}/verify-email`; assert `302 → /user/{id}`. Re-GET the detail;
   assert the `Email Verified` row now shows a non-empty timestamp and both
   buttons render `disabled` with title `Email already verified.`. Cross-check
   via `UsersDao.findById` that `emailVerifiedAt != null`.
3. **Mark-verified is idempotent.** Seed a user, POST `/user/{id}/verify-email`
   twice; assert both return `302`. Read the user and assert the version bumped
   exactly once relative to the seeded version (second call is a no-op
   read-back).
4. **Already-verified renders disabled buttons with the title.** Seed a user,
   mark verified directly via `UsersDao.markEmailVerified`, GET `/user/{id}`;
   assert both action buttons are `disabled` and the body contains
   `Email
   already verified.`.
5. **Soft-deleted user renders disabled buttons with the deleted title.** Seed a
   user, soft-delete via `POST /user/{id}/delete`, GET `/user/{id}`; assert both
   action buttons are `disabled` and the body contains `User is deleted.`.
6. **Send-verification-email issues a token.** Seed an unverified user, POST
   `/user/{id}/send-verification-email`; assert `302 → /user/{id}` and that a
   `verification_tokens` row now exists for the user (query the test DB for a
   row with the user's id and `consumed_at IS NULL`).
7. **Send-verification-email on a verified user is a no-op success.** Mark a
   user verified, POST `/user/{id}/send-verification-email`; assert `302` and
   that no unconsumed `verification_tokens` row exists for the user (`resend`
   short-circuits on `AlreadyVerified`).
8. **Forged POST on a soft-deleted user returns 404.** Seed a user, soft-delete
   via `POST /user/{id}/delete`, then POST `/user/{id}/verify-email` and POST
   `/user/{id}/send-verification-email`; assert both return `404` (the
   `deleted_at IS NULL` / `SoftDeleteScope.ACTIVE` guards yield
   `NotFoundException` → `respondDaoError` → 404), covering the disabled-button
   bypass described in Error Handling.

## Implementation Plan

Each step is independently compilable/testable. Run all toolchain commands
inside the Nix dev shell (`nix develop -c ...`).

1. **Add the `CustomAction` type and the `AdminResource.customActions` member.**
   Create
   `admin-server/src/main/kotlin/ed/unicoach/admin/engine/CustomAction.kt` with
   the `CustomAction<ROW>` data class. Add
   `val customActions: List<CustomAction<ROW>> get() = emptyList()` to
   `AdminResource`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`.

2. **Render custom actions in `renderDetail`.** In `DetailView.kt`, after the
   Edit/Delete/Undelete `div` and before the edges loop, iterate
   `resource.customActions`; for each, compute `disabledReason(row)` and render
   a POST form to `/${resource.slug}/$idPath/${action.pathSuffix}` whose submit
   button is disabled with a `title` when the reason is non-null, enabled
   otherwise. No existing descriptor declares custom actions, so behaviour is
   unchanged for all current pages.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`; existing
     `admin-server` tests still pass: `nix develop -c bin/test admin-server`.

3. **Thread `EmailVerificationService` into `UsersResource`.** Add the
   constructor parameter to `UsersResource`. Add the `emailVerificationService`
   parameter to `adminModule` in `Application.kt` and pass it into
   `UsersResource(...)`; pass the already-built `emailVerificationService` at
   the `startServer` call site. Update `AdminTestSupport.installTestAdminModule`
   to pass its existing `emailVerificationService` into `adminModule`.
   - Verify:
     `nix develop -c ./gradlew :admin-server:compileKotlin
     :admin-server:compileTestKotlin`.

4. **Add the `emailVerifiedAt` field and the two custom actions to
   `UsersResource`.** Append the read-only `emailVerifiedAt` `AdminField` and
   its `cells` entry. Add the shared `verificationDisabledReason` predicate and
   the two `CustomAction` entries. Register `POST /user/{id}/verify-email` and
   `POST
   /user/{id}/send-verification-email` in `registerExtraRoutes`, with
   the fold/redirect/`respondDaoError` handling specified in Detailed Design.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`.

5. **Add the tests.** Implement tests 1–8 in `UsersResourceTest.kt`. Where a
   test inspects `verification_tokens`, query the test DB directly (as
   `AdminTestSupport.resetDatabase` does) or via `VerificationTokensDao`.
   - Verify:
     `nix develop -c bin/test admin-server --tests
     "ed.unicoach.admin.resources.UsersResourceTest"`;
     then the gate: `nix develop -c bin/test check`.

## Files Modified

- `admin-server/src/main/kotlin/ed/unicoach/admin/engine/CustomAction.kt` —
  **new**: the `CustomAction<ROW>` type.
- `admin-server/src/main/kotlin/ed/unicoach/admin/engine/AdminResource.kt` — add
  the `customActions` member (default empty).
- `admin-server/src/main/kotlin/ed/unicoach/admin/render/DetailView.kt` — render
  custom-action buttons (enabled, or disabled with a `title`).
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/UsersResource.kt` —
  `emailVerifiedAt` field + cell; injected `EmailVerificationService`; two
  custom actions; two POST routes in `registerExtraRoutes`.
- `admin-server/src/main/kotlin/ed/unicoach/admin/Application.kt` — add
  `emailVerificationService` to `adminModule` and pass it to `UsersResource`;
  pass the existing instance at the `startServer` call site.
- `admin-server/src/test/kotlin/ed/unicoach/admin/AdminTestSupport.kt` — pass
  the existing `emailVerificationService` into `adminModule` via
  `installTestAdminModule`.
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/UsersResourceTest.kt`
  — tests 1–8.
