# RFC 252 — The deletion partition

**Slice:** `family/09/the-deletion-partition` (brief 0014, _parents in the
room_). **Status:** proposed. **Base:** `main@a81472d9`.

A student or a parent can delete their account from Settings in the iOS app.
Deleting an account ends every connection it holds. Each person on the other end
gets one email saying the connection ended, and the email does not say why.
Their own account and everything they own is left as it was.

## Context

Brief 0014 D9: _"Account deletion (in-app, both roles) severs all links and
notifies the other party without stating a reason; each party's own
conversations, inputs, and account survive the other's deletion."_ D11: deletion
**builds** rather than extends, because brief 0002 was never implemented.

Seven facts from the code decide this design. Research is in
`.scratch/ship/rfc-252/research/` (archived with the run).

1. **The only delete is student-shaped.** `DELETE /api/v1/students/me`
   (`StudentRoutes.kt:168-185` → `StudentService.deleteStudentAndAccount`,
   `StudentService.kt:242-268`) soft-deletes `students` and `users` and revokes
   ONE session. A caller with no `students` row gets `404 student_not_found`, so
   a parent cannot delete at all. No client calls it.
2. **Closing an account severs nothing.** The links, the closer's unused invite
   links, the Family Cost Report link, a dual-role user's `supporters` row and
   every other session all stay live. `SupportersDao.delete` has no production
   caller.
3. **The Family Cost Report link outlives its student.**
   `ServiceCostReportSource.getOutcome` (`ServiceCostReportSource.kt:71-81`)
   checks `revoked_at` only, and `CollegeCostService.readInSession` has no
   student-liveness check, so a parent's saved link keeps rendering a closed
   student's college list and costs.
4. **Only two tables hold keys from both parties:** `supporter_student_links` (+
   `_versions`) and `supporter_invites`. Every other per-account table has a
   single owner key (`users.id`, `students.id` or `supporters.id`). So "the
   other party's data survives" reduces to: write only under the closer's keys,
   and treat links and invites explicitly.
5. **There is one channel to a person: queued email.** `SEND_EMAIL` →
   `EmailSendHandler` → SES, one template (`EMAIL_VERIFICATION`). No push, no
   in-app inbox. ASYNC_WORK.md rule 2: a required enqueue joins the request
   transaction, and the request fails if it fails.
6. **A deletion-only notice would state the reason.** RFC 249's disconnect sends
   nothing. If deletion emails and disconnect does not, receiving the email
   tells the recipient the other person deleted their account.
7. **Nothing can be physically erased today.** Every child table carries a
   raising `BEFORE DELETE` guard and every `*_versions` table a `RESTRICT` FK
   (research §2e); `llm_requests` holds transcripts with no account key. Erasure
   needs a purge mechanism and per-table retain-or-erase decisions — exactly
   brief 0002's gate-1 questions (D1-D3), which Ian never took.

## What this slice does NOT do

- **No erasure of personal data.** This slice CLOSES an account: access ends at
  once, every connection is cut, nothing of the closer's is visible to anyone.
  Rows stay, soft-deleted. Erasing them (Apple 5.1.1(v): "only offering to
  temporarily deactivate or disable an account is insufficient") is a follow-up
  slice with its own decisions — see gate decision 1. The in-app copy therefore
  says "closed", never "erased".
- **No programmatic Sign in with Apple token revocation.** The server holds no
  Apple refresh token and no client secret; iOS never sends the authorization
  code. This slice tells an Apple sign-in user how to remove unicoach from their
  Apple ID (Apple TN3194's manual route). See gate decision 4.
- **No in-app "connection ended" notice and no push.** The other side's list
  simply loses the person, as after an RFC 249 disconnect.
- **No door before a role is chosen.** Role choice, onboarding and the
  verification hold have no Settings. The route accepts those callers; a door on
  those screens is a later slice.
- **No web door.** `public-web` `/account` gains nothing; `/privacy` copy is
  corrected (below).

## Detailed Design

### 1. One close, for every account shape (`:service`)

New `ed.unicoach.account.AccountClosureService.close(userId, currentTokenHash)`.
ONE transaction (`database.withConnection`), in this order:

1. Lock the `users` row (`UsersDao.findByIdForUpdate`, ACTIVE). Absent → the
   result `NotFound` (already closed, or never existed).
2. Read the caller's live `students` row and live `supporters` row (either, both
   or neither may exist).
3. **Sever every live link** of each role row:
   `SupporterStudentLinksDao.severAll(session, LinkSide)` — new, one
   `UPDATE …
   SET deleted_at = NOW(), version = version + 1 WHERE <side> = ? AND deleted_at
   IS NULL RETURNING *`.
   The side rides in the WHERE, exactly as RFC 249's `unlink`. History rows are
   written by the existing trigger.
4. **Notify** — for each severed link, enqueue the link-ended email to the OTHER
   party (§3), inside this transaction.
5. **Revoke the closer's live invite links** —
   `SupporterInvitesDao.revokeAllByInviter(session, userId)` (new, one UPDATE
   over `inviter_user_id`). Today they are refused at read time by
   `findInviterOrNull`; revoking makes the row say what is true.
6. **Revoke the Family Cost Report link** (student role) —
   `CostReportSharesDao.revokeLive(session, studentId)`, the existing CAS.
7. **Soft-delete the role rows**: `StudentsDao.delete`, `SupportersDao.delete`
   (its first production caller), then `UsersDao.delete`, then
   `UserAuthIdentitiesDao.deleteForClosedUser` (§1b).
8. **Revoke every session** — `SessionsDao.revokeAllForUser(session, userId)`
   (new). Today other devices fail only because session resolution filters a
   closed user; revoking them makes the session table say so too.

Everything else the closer owns is left in place and is unreachable: every read
that names a person already filters `users.deleted_at`, `students.deleted_at` or
`supporters.deleted_at` (research §4). The one read that did not — the public
cost report — is fixed by step 6, and `ServiceCostReportSource` also gains a
student-liveness check so a link revoked by any other path cannot render a
closed student either.

**Retained, stated in writing (the drift `family/03.5` asked this slice to
answer):**

- `subscriptions` / `subscriptions_versions`: **kept, bound to the closed
  account until the same Apple ID subscribes on a new account (§1b).** A
  financial record; Apple continues to bill until the person cancels (Apple's
  own rule). `SubscriptionRefreshHandler` keeps refreshing it; that is correct,
  because the entitlement state is Apple's fact.
- `supporter_llm_costs` (and the student cost spine): **kept.** Append-only
  spend; `account_llm_cost` deliberately has no `deleted_at` predicate ("a
  closed account's past spend is still spend", `account-llm-cost.sql:22-27`).
- Every other row the closer owns: kept, soft-deleted or unreachable through a
  soft-deleted owner, until the erasure slice.

`StudentService.deleteStudentAndAccount` is deleted; `ExtractionService` gains
the student-liveness check synthesis and fit-lens already have
(`ExtractionService.kt:133-135`), so a turn queued just before a close does not
run and bill after it.

### 1b. Coming back: the same Apple or Google ID, and the same Apple subscription

Without this, a person who closes an account can never return on the same
sign-in: `user_auth_identities` has a TOTAL unique index on
`(provider, subject)` (`0018…:37-38`) and is append-only, so a returning SSO
login finds the closed user and answers `403 account_disabled`
(`AuthService.kt:522-526`). And their Apple subscription stays bound to the
closed account, so the new account cannot subscribe (`OwnedByOtherAccount`,
`SubscriptionsDao.kt:78-168`).

- **Sign-in.** New migration `user-auth-identities-delete-on-closed-account.sql`
  replaces `trigger_01_prevent_user_auth_identities_delete` with a guard that
  admits a DELETE **only when the owning `users` row is soft-deleted** and still
  refuses every other delete. The close (§1) runs
  `UserAuthIdentitiesDao.deleteForClosedUser(session, userId)` after
  `UsersDao.delete`, in the same transaction. A returning sign-in then finds no
  identity and takes today's first-time path (`resolveOrProvisionUser`: live
  email match, else a new user). Deleting the row also erases the provider email
  it carried.
- **Subscription.** `SubscriptionsDao.upsert`'s conflict arm also rebinds — sets
  `user_id = EXCLUDED.user_id` and bumps version — when the row's CURRENT owner
  is a closed account. A row owned by a LIVE other account is still
  `OwnedByOtherAccount` (D24 is unchanged: two live accounts on one Apple ID
  still cannot share). The history table records the move.

### 2. One route (`:rest-server`)

`DELETE /api/v1/auth/me` → `204` and a cleared session cookie, or `401` with no
session. It closes the CALLER'S ACCOUNT whatever its shape — student, supporter,
both, or neither. It lives under `/api/v1/auth/` beside `GET /auth/me` because
the account IS the auth subject, and that prefix is exempt from
`EmailVerificationGate`, so an unverified account is not refused by the door
that ends it.

`DELETE /api/v1/students/me` is **removed** (no client calls it; its body would
duplicate the new route's). OpenAPI updated for both; the new route joins
`bin/test-fuzz`.

### 3. The link-ended email — every severance, one text

New `EmailTemplate.LINK_ENDED`, context
`LinkEndedContext(recipientName,
otherName)`, renderer `LinkEndedEmailRenderer`
in `service/family/`, registered in `queue-worker/.../Application.kt:124`.
Enqueued through `QueueService.enqueue(session, SEND_EMAIL, …)` on the severing
transaction — **required**: a failed enqueue fails the close or the disconnect
(ASYNC_WORK.md rule 2).

**Sent on every severance**: account close (§1 step 4) AND both RFC 249
disconnect doors (`SupporterService.disconnectAsStudent` /
`disconnectAsSupporter`). The text is identical for all three causes, so the
email cannot state a reason:

> Subject: A connection on unicoach has ended
>
> Hi {recipientName},
>
> You and {otherName} are no longer connected on unicoach.
>
> Nothing about your own account has changed — your conversations and everything
> you have entered are still yours.
>
> — unicoach

It goes to the other party's `users.email` **only when `email_verified_at` is
set**: a linked party may have accepted an invite unverified (correction X8),
and an unverified address may belong to a stranger. A skipped send writes one
INFO line naming the link and the reason. `otherName` is the other party's
display name at severance time.

### 4. iOS — the door (`SettingsView`, both roles)

`SettingsView` is one screen for both callers (`SettingsView.swift:39-51`). Its
button stack gains a destructive **"Delete account"** text button under "Log
Out". Tapping it expands an INLINE confirmation (RFC 249's pattern,
`FamilyConnectionsSection.swift:252-280`, so the snapshot gate can photograph it
— a stock dialog cannot be photographed), seeded by
`deleteAccountConfirmationOnAppear` for the catalogue:

> **Delete your account?** Your account is closed and you are signed out on
> every device. Anyone connected to you is told the connection has ended — not
> why. Their own accounts are not affected.
>
> _[when the caller has a subscription]_ Your subscription is billed by Apple
> and continues until you cancel it. **Manage subscription**
>
> [Delete account] [Cancel]

The subscription line uses the existing `ManageSubscriptionLink` for the
student; the supporter, who has no StoreKit rail, gets the same link driven by
`coverage.kind == own_subscription`. Deletion is not blocked by a live
subscription (Apple allows proceeding after the notice).

The call is on a narrow new protocol `AccountDeleting` (the `SsoAuthenticating`
precedent, `AuthClient.swift:13-20`), so the seven preview conformers need no
stub. On `204` the app runs the logout tail — cookies cleared,
`.unauthenticated` — and also clears the per-user `UserDefaults` keys
(`SupporterSubject.studentId.<userId>`, and every `AppleSignIn.name.<sub>` — all
of them, because nothing on the device records which Apple `sub` the deleted
account used). The login screen then shows one line: "Your account has been
closed." It adds the Apple directions as a question: "Signed in with Apple? To
remove unicoach from your Apple ID too, go to Settings › your name › Sign-In &
Security › Sign in with Apple." (TN3194). _Amended in implementation:_ the
client cannot tell an Apple sign-in account from another — `PublicUser` carries
no provider, and a session restored from its cookie never saw a credential — so
the line is shown to every closed account, phrased so that it is true for each.

_Amended in implementation:_

- The billing notice renders `ManageSubscriptionButton`, the control and caption
  extracted from `ManageSubscriptionLink` without its hairline or its condition:
  the notice's own sentence needs the same condition at the call site, and a
  parent has no `SubscriptionViewModel` to hand the link. The student's notice
  shows when `SubscriptionViewModel.offersManage` is true and opens StoreKit's
  management sheet. The parent's notice shows when
  `coverage.kind == own_subscription` and presents StoreKit's
  `.manageSubscriptionsSheet`, which needs no rail — no Apple URL is copied into
  the app. While the parent's coverage is loading or could not be read, the
  notice shows in a conditional form ("If you subscribe through Apple, your
  subscription continues until you cancel it."): not knowing is not "does not
  pay", and the act is irreversible.
- A `401` from `DELETE /auth/me` ends the session locally (cookies cleared,
  `.unauthenticated`), as `checkSession` does: that session is already dead —
  the account closed from another device, or the session was revoked — so a
  retry could only get `401` again. The app cannot tell which, so it clears no
  per-user `UserDefaults` and does not show the closed line.
- The confirmation's state is one `DeleteAccountConfirmation` (`closed`, `open`,
  `deleting`, `failed`) rather than flags, and it is also the seed, so the
  catalogue can photograph the failure's error line and Cancel cannot leave a
  stale failure behind.
- The default `AppleSignInProvider` is built over the `AppleNameStore` that
  `AppViewModel` sweeps on delete — one instance, not two that agree by default.

### Amended in implementation (server half)

- `AccountClosureService.close(userId)` takes no session token:
  `revokeAllForUser` revokes every session, the current one included, so the
  parameter would be unused.
- The email's names are `users.name`, not the nullable display name — the same
  name RFC 249's lists already show for each person.
- `DELETE /auth/me` answers `401` with a cleared cookie when the account closed
  between session resolution and the close (`CloseAccountOutcome.NotFound`).
- `LinkEndedNotice` also skips, with an INFO line, a recipient whose own account
  is already closed (reachable only through the admin-only close path).
- `/privacy` keeps "contact us" for ACCESS requests; deletion reads "You can
  close your account in the app: Settings › Delete account." A comment at the
  sentence names the iOS control it points at, and a comment at the iOS control
  names the sentence, so a rename from either end finds the other.
- The subject/body validation that turns a template's text into a
  `RenderedEmail` is one `renderedEmail(template, subject, body)` in `:email`
  (`RenderedEmail.kt`), called by `LinkEndedEmailRenderer` and
  `VerificationEmailRenderer` alike; it takes the renderer's `EmailTemplate`
  rather than a hand-typed label.
- `severAll` and RFC 249's `unlink` share ONE private sever statement in
  `SupporterStudentLinksDao`; `unlink` narrows it to one id, so a close and a
  disconnect cannot store different severed rows.
- The report's liveness check covers BOTH shapes of a closed account — the
  `students` row, or the owning `users` row closed alone (the operator admin's
  path) — read in `ALL` scope with `deleted_at` tested at the call site. It is
  logged as its own `MissReason.ACCOUNT_CLOSED`, apart from an unknown or
  revoked token; the page is the same 404 for every reason.
- A role or user row missing behind a link fails the sever naming the link, the
  side and the missing row, rather than as a bare "Record not found".
- `bin/test-fuzz`: `deleteAccount` joins the EXCLUSION list beside `logoutUser`,
  because it destroys the fuzzer's session.

### 5. Copy corrections

- `/privacy` (`PrivacyPage.kt:44-50`): "request deletion by contacting us"
  becomes "close your account in the app: Settings › Delete account".
- RFC 249's disconnect confirmation ("They lose access right away. Your account
  and theirs stay as they are.") adds: "We let them know the connection ended."

### Reachability — the door

Student: side menu → Settings → **Delete account**. Parent: home → Settings →
**Delete account**. Both one level deep, the same screen.

### Value before ask (standing D12)

Not a flow that asks for anything. The confirmation is one tap to open, one to
confirm, and Cancel leaves everything as it was.

## Files Modified

- `db/src/main/kotlin/ed/unicoach/db/dao/SupporterStudentLinksDao.kt` —
  `severAll(session, LinkSide)`
- `db/src/main/kotlin/ed/unicoach/db/dao/SupporterInvitesDao.kt` —
  `revokeAllByInviter`
- `db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt` — `revokeAllForUser`
- `db/src/main/kotlin/ed/unicoach/db/dao/UserAuthIdentitiesDao.kt` —
  `deleteForClosedUser`
- `db/src/main/kotlin/ed/unicoach/db/dao/SubscriptionsDao.kt` — rebind from a
  closed owner
- `db/schema/user-auth-identities-delete-on-closed-account.sql` — new migration
  (no number, RFC 180; `db/schema/ORDER` line by `ship-order`)
- `service/src/main/kotlin/ed/unicoach/account/AccountClosureService.kt` — new
- `service/src/main/kotlin/ed/unicoach/family/LinkEndedNotice.kt` — new: enqueue
  helper + `LinkEndedEmailRenderer` + context
- `service/src/main/kotlin/ed/unicoach/family/SupporterService.kt` — disconnects
  enqueue the notice
- `service/src/main/kotlin/ed/unicoach/student/StudentService.kt` — remove
  `deleteStudentAndAccount`
- `service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionService.kt`
  — student liveness
- `email/src/main/kotlin/ed/unicoach/email/EmailTemplate.kt` — `LINK_ENDED`
- `email/src/main/kotlin/ed/unicoach/email/RenderedEmail.kt` — `renderedEmail`,
  the shared validation step; `service/.../auth/VerificationEmailRenderer.kt`
  calls it
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` — register
  the renderer
- `public-web/src/main/kotlin/ed/unicoach/web/report/ServiceCostReportSource.kt`
  — student liveness
- `public-web/src/main/kotlin/ed/unicoach/web/render/PrivacyPage.kt` — copy
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt` —
  `DELETE /auth/me`
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/StudentRoutes.kt` —
  remove `DELETE /students/me`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` (or wherever
  services are wired) — `AccountClosureService`
- `public-web/src/main/kotlin/ed/unicoach/web/Application.kt` — wire
  `LinkEndedNotice` into its `SupporterService` (constructor change only;
  public-web severs no link itself)
- `api-specs/openapi.yaml`, `bin/test-fuzz` route list
- iOS: `SettingsView.swift`, `AuthClient.swift` (`AccountDeleting`),
  `AppViewModel.swift`, `LoginView.swift`, `FamilyConnectionsSection.swift`
  (copy), `SupporterCoachingSection.swift` or `SettingsView` (supporter manage
  link), `UnicoachiOSTests/SnapshotScenes.swift`, tests, `project.pbxproj` for
  any new file
- Tests under `db/`, `service/`, `rest-server/`, `public-web/`, `queue-worker/`
  as below

## Implementation Plan

1. `:db` — `SupporterStudentLinksDao.severAll` (one sever statement shared with
   `unlink`), `SupporterInvitesDao.revokeAllByInviter`,
   `SessionsDao.revokeAllForUser`, `UserAuthIdentitiesDao.deleteForClosedUser`,
   the `SubscriptionsDao.upsert` closed-owner rebind, and the migration
   `user-auth-identities-delete-on-closed-account.sql` (`ORDER` line by
   `ship-order`), each with DAO tests.
2. `:service` — `AccountClosureService`, `LinkEndedNotice`, disconnect notices,
   extraction liveness; delete `deleteStudentAndAccount`.
3. `:email` + `:queue-worker` — template and registration.
4. `:rest-server` — route swap, OpenAPI, fuzz list. `:public-web` — report
   liveness (student and user rows, its own miss reason), privacy copy.
5. iOS — door, confirmation, success path, login line, snapshot scenes. Runs in
   parallel with 2-4 once the route shape is fixed.

## Tests

- **The other party survives — by what the close writes, not by a table list.**
  The close is a fixed set of statements, each with the closer's key in its
  WHERE. Only two tables hold a key from both parties (links and invites), so
  the test covers exactly those plus the other party's own account rows: close S
  while linked to P, assert P's `users`, `supporters` and sessions rows are
  untouched and live, P's own invites to other students stay live, and P's link
  to a SECOND student is not severed; then the mirror (close P). A dual-role
  account closes both of its rows. No per-table seeding and no schema walk (Ian,
  at the approval gate: nothing would keep a per-table list current). A new
  table can only be affected if the close code starts writing to it, and that
  change is reviewed in its own diff.
- **What the close does:** links severed (history rows written), closer's live
  invites revoked, cost-report link revoked AND unrenderable, all sessions
  revoked, role rows and user soft-deleted, one `SEND_EMAIL` job per severed
  link with the right recipient; unverified recipient → no job + log line;
  `FailingJobsDao(SEND_EMAIL)` → whole close rolls back (nothing committed).
- **Disconnect now notifies:** both RFC 249 doors enqueue exactly one job to the
  other party; rollback on enqueue failure.
- **Same text for every cause:** the rendered email for a close and for a
  disconnect is byte-identical given the same names.
- **Route:** `DELETE /auth/me` 204 + cookie cleared for student, supporter,
  dual-role, role-less and UNVERIFIED callers; 401 with no session; the next
  request with the old cookie is 401; `DELETE /students/me` is gone (404/405).
- **Extraction** skips a closed student's queued turn.
- **Coming back:** the identity delete guard refuses a delete for a LIVE user
  and admits it for a closed one; after a close, the same Apple and the same
  Google subject sign in and get a NEW account (`201`/`200`, not
  `403 account_disabled`). The upsert rebinds a closed owner's subscription to
  the new account (history row written) and still answers `OwnedByOtherAccount`
  for a live owner.
- **iOS:** view-model tests for confirm → call → logout tail + `UserDefaults`
  cleared; failure keeps the user signed in with an error line. Snapshot scenes:
  `settings-delete-confirm-open` (student, with subscription line),
  `settings-supporter-delete-confirm-open`, `login-account-closed`. _Amended in
  implementation:_ plus `settings-delete-confirm-failed` (the error line); a
  `401` ends the local session; a real-suite test of
  `UserDefaultsAppleNameStore.removeAll`.

## Rollback

A revert of the code, plus a forward migration restoring the unconditional
identity delete guard if the guard change itself must go. No new table, no new
column, no configuration key.

## Gate decisions (APPROVED by Ian, 2026-10-11 — "approve")

1. **Close now, erase later.** This slice closes; erasure of personal data is a
   follow-up slice with brief 0002's per-table keep-or-erase decisions.
2. **Every severance emails the other party, one text** — account close and both
   RFC 249 disconnect doors.
3. **A paying user is warned, not blocked** — Apple's billing notice plus the
   manage-subscription link, then deletion proceeds.
4. **Sign in with Apple: the manual route now** (TN3194 directions after
   deletion); programmatic revocation belongs with erasure.
5. **Coming back is fixed in this slice** (§1b) — the closed account's SSO
   identities are deleted and its subscription can rebind to a new account.

Amendment at the gate: the slice's "proven per table" criterion is met by
testing what the close WRITES, not by a per-table fixture or a schema walk —
Ian: _"How would it be maintained? … There is nothing to enforce that."_
