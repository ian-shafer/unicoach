# DISCOVER — auth, registration, session, and the student-less user

Brief 0014 "parents in the room". Static reading of the worktree
`/Users/ian/Work/unicoach-docs-0014-parents-in-the-room` at current `main`. No
build, no tests. Every claim carries `path:line`.

---

## 1. Headline corrections to the brief's assumptions

**C1 — Billing is per-STUDENT today, not per-user.** The brief says "per-user
billing unchanged".
`subscriptions.student_id UUID NOT NULL REFERENCES
students(id)`
(`db/schema/0042.create-subscriptions.sql:33`), and `SubscriptionRoutes.kt:49`
refuses a caller with no student row
(`resolveStudent(user) ?: return respondStudentProfileRequired()`). A parent
user has no `students` row, so today a parent literally cannot hold, verify, or
restore a subscription. "Unchanged" is not a no-op: it is a decision that the
parent side is free, or a new slice that re-keys entitlement off `users`.

**C2 — Account deletion is student-shaped and a parent could not delete their
account.** `DELETE /api/v1/students/me` is the ONLY account-deletion path
(`StudentRoutes.kt:64,143-160`). It calls
`StudentService.deleteStudentAndAccount`
(`service/.../student/StudentService.kt:119-143`), which starts with
`StudentsDao.findByUserIdForUpdate` and returns `DeleteStudentResult.NotFound`
when there is no student — rendered as `404
student_not_found`
(`StudentRoutes.kt:42-44,156-158`). A student-less user therefore has **no**
delete path at all. "Author-partitioned deletion" has no code hook yet; a slice
must add a user-scoped delete that does not assume a student.

**C3 — `verification_tokens` is a partial precedent only, and its `user_id` does
not fit an invite.**
`verification_tokens.user_id UUID NOT NULL REFERENCES
users(id)`
(`db/schema/0014.create-verification-tokens.sql:13`). An invite sent to a person
who has not registered yet has **no** user row, so the invite table cannot copy
that column. The closer precedent for a shareable, long-lived, revocable link is
`cost_report_shares` (`db/schema/0073.create-cost-report-shares.sql:15-31`): no
`expires_at`, no `consumed_at`, `revoked_at` set once, one live row per student
(`:29-31`), and the raw token **derived** as `HMAC-SHA256(secret, id)` rather
than random (`:12-14`, `service/.../coaching/report/ShareTokenDeriver.kt`). An
invite wants a hybrid: hashed like both, single-use + expiring like 0014,
addressed to an email rather than a user id.

**C4 — No birthdate exists anywhere in the product.**
`grep -rni
'birth|dob|date_of_birth'` over `db/schema` returns no column; the
iOS grep for `dateOfBirth|birth|dob` over `ios-app/UnicoachiOS` returns no
field. The only date collected about a student is the variable-precision
expected HS graduation date (`db/schema/0005.create-students.sql:16-18`). A 13+
DOB gate is 100% new work, and it cannot be read off anything stored.

**C5 — The "student-less user" already exists, transiently.**
`POST
/api/v1/auth/register` creates the user, mints the session cookie and
returns `201` with no student (`AuthRoutes.kt:109-140`,
`AuthService.kt:50-151`). From then until onboarding posts a student the user is
student-less — so the concept is not new, only its permanence is.

**C6 — There is no role concept anywhere.** No `role` column on `users`
(`0001:1-38`; `0012.add-users-is-admin.sql:5` adds only `is_admin`), and nothing
role-like on the client. Role typing is new in both tiers.

---

## 2. Registration, session, and the verification gate

### Routes (all under `/api/v1/auth`, `AuthRoutes.kt:69-106`)

`POST /register` (`:109-140`, `RegisterRequest{email,password,name}` —
`models/RegisterRequest.kt:3-7`; 201 + cookie) · `POST /login` (`:179-195`) ·
`POST /google` (`:240-247`, `{idToken}`; absent name falls back to the email
local-part, `AuthService.kt:672`) · `POST /apple` (`:249-252`,
`{idToken, name?}` — client-supplied name) · `GET /me` (`:156-163`) ·
`POST /logout` (`:165-177`) · `POST /verify-email` (`:218-238`) ·
`POST /resend-verification` (`:341-351`) · `POST /change-email` (`:358-386`).

### Session issuance

Cookie only: `setSessionCookie` (`AuthRoutes.kt:34-47`) — HttpOnly,
`SameSite=Strict`, path `/`. Row is `sessions` (`0002:1-27`): unique
`token_hash BYTEA`, nullable `user_id`, `expires_at`, `is_revoked`;
`login_method` added in `0016` and widened to `'apple'` in `0043:13`. Register
re-mints an existing anonymous session rather than adding one
(`AuthService.kt:103-137`). Caller resolution is one cached helper,
`rest-server/.../auth/CallerResolution.kt:41-54` →
`ResolvedCaller(tokenHash,
session, user)` (`:15-19`); it "resolves identity
only; asserts nothing about email verification" (`:37-38`).

### Two interceptors run before every handler

1. **`configureClientKeyGate`** (`plugins/ClientKeyGate.kt:16-34`): requires
   header `X-Unicoach-Client-Key` unless the path is in `allowlistPaths`, which
   is `["/healthz", "/api/v1/subscriptions/apple-notifications"]`
   (`rest-server/src/main/resources/rest-server.conf:57`). **Consequence for a
   slice:** a browser following an invite link cannot set that header. An invite
   landing page must be served by **public-web**, exactly as `/verify-email` and
   `/report` are (`public-web/.../Routing.kt:122-146,201-232`), not by
   `rest-server`.
2. **`configureEmailVerificationGate`**
   (`plugins/EmailVerificationGate.kt:32-52`): exempts only `/healthz` and any
   path starting `/api/v1/auth/` (`:38`); an authenticated caller with
   `emailVerifiedAt == null` gets `403
   email_not_verified` (`:44-50`).
   **Consequence for a slice:** any new `/api/v1/invites/...` endpoint is
   blocked for a brand-new unverified parent. Either the accept flow runs on
   public-web against a token (no session), or the allowlist gains an explicit
   entry — a decision, not a detail.

### Email verification mechanics (the invite-token precedent)

- Table `verification_tokens` (`0014:8-20`): `user_id` (NOT NULL FK, `:13`),
  `token_hash BYTEA` (SHA-256; raw never stored, `:14`), `expires_at`,
  `consumed_at`; unique index on `token_hash` (`:19`). No `version` and no
  `_versions` table, deliberately (`:2-6`).
- Raw token = 256 random bits, URL-safe base64, unpadded
  (`service/.../util/TokenGenerator.kt:9-13`). TTL default `"24 hours"`
  (`service.conf:397-398`). Link = `publicWeb.urlBase + "/verify-email"` with
  `?token=<raw>` (`EmailVerificationConfig.kt:16-19`, `service.conf:402-403`).
- Issue + enqueue run **inside the caller's transaction** (RFC 96):
  `EmailVerificationService.kt:42-55,65-80`, called from
  `AuthService.kt:143-144` (register) and `:194-195` (change-email). A failed
  enqueue aborts everything.
- Consume is a compare-and-swap:
  `UPDATE ... SET consumed_at = NOW() WHERE
  token_hash = ? AND consumed_at IS NULL AND expires_at > NOW()`
  (`db/.../dao/VerificationTokensDao.kt:62-64`). Zero rows are classified into
  `InvalidToken`/`Expired`/`AlreadyConsumed` by a follow-up read
  (`auth/.../DbEmailVerifier.kt:48-66`); success marks the user verified and
  burns siblings (`:42-43`, `VerificationTokensDao.kt:94-99`). `resend` burns,
  re-issues and enqueues in one transaction
  (`EmailVerificationService.kt:88-101`).

**Reusable shape for an invite slice:** copy 0014 (hashed, CAS consume, no OCC
version) but replace `user_id` with inviter + invited email + role, because the
invitee may not be a user yet.

### How an email actually goes out

1. Producer enqueues `JobType.SEND_EMAIL` with
   `EmailJobPayload{to, template,
   context}`
   (`email/.../EmailJobPayload.kt:17-21`). The recipient travels in the payload
   precisely because "the job must serve recipients who are not users"
   (`:11-13`) — already true of the invite case.
2. `EmailTemplate` has exactly **one** constant today: `EMAIL_VERIFICATION`
   (`email/.../EmailTemplate.kt:11-12`).
3. `EmailSendHandler` resolves the renderer by template from an injected list
   and rejects duplicates (`email/.../EmailSendHandler.kt:36-44`); an
   unresolvable template is a permanent failure (`:84-86`).
4. Wiring is one line:
   `add(EmailSendHandler(emailService, listOf(VerificationEmailRenderer(verifyUrlBase))))`
   (`queue-worker/.../worker/Application.kt:124`).
5. Terminal outcomes go append-only to `email_sends` (`0008:14-28`) — SENT and
   REJECTED only.

**An invite-email slice touches exactly:** a new `EmailTemplate` constant, a new
`EmailTemplateRenderer` + context, one URL-base config key, and
`Application.kt:124`.

---

## 3. Where a `students` row is created, and who assumes one

### Creation

`POST /api/v1/students` (`StudentRoutes.kt:56-60,70-91`). Body is one field:
`CreateStudentRequest{expectedHighSchoolGraduationDate: String}`, an ISO partial
date (`models/CreateStudentRequest.kt:3-5`). The user comes from the session,
never the body (`:71,78`). Outcomes: 201, `400 validation_error`,
`409
student_already_exists` (`:79-89`); service at `StudentService.kt:28-50`.

Schema: `students.user_id UUID NOT NULL REFERENCES users(id)` (`0005:13`) with a
**total** (non-partial) unique index `students_user_id_unique_idx`
(`0005:33-36`) — one student per user forever, even across soft deletes. A
`users` row is either a student or not; there is no second-student escape hatch.

### The single resolution seam

Every student-scoped route gets from session → user → student through one
interface: `CallerResolution` / `SessionCallerResolution`
(`rest-server/.../routing/CallerResolution.kt:28-59`), whose `resolveStudent` is
`studentService.getStudentForUser(user.id)` (`:50`), returning `null` when
absent (`StudentService.kt:53-66`). This single seam is the natural insertion
point for "resolve the student this PARENT is linked to".

### Exact behaviour today for a caller with no student row

| Endpoint                               | Line                                       | Answer                           |
| -------------------------------------- | ------------------------------------------ | -------------------------------- |
| `GET /api/v1/students/me`              | `StudentRoutes.kt:100-105,42-44`           | `404 student_not_found`          |
| `PATCH /api/v1/students/me`            | `:133-135`                                 | `404 student_not_found`          |
| `DELETE /api/v1/students/me`           | `:156-158`                                 | `404 student_not_found` (see C2) |
| College list (5 routes)                | `CollegeListRoutes.kt:139,185,197,214,266` | `409 student_profile_required`   |
| Money profile (2 routes)               | `MoneyProfileRoutes.kt:56,71`              | `409 student_profile_required`   |
| Coaching usage                         | `CoachingUsageRoutes.kt:42`                | `409 student_profile_required`   |
| Subscriptions verify                   | `SubscriptionRoutes.kt:49`                 | `409 student_profile_required`   |
| Create conversation / post message     | `ConvoRoutes.kt:133,268`                   | `409 student_profile_required`   |
| **List conversations**                 | `ConvoRoutes.kt:170`                       | `200 OK` with an **empty list**  |
| Conversation detail / messages / patch | `ConvoRoutes.kt:178,189,202,213,224,297`   | `404`                            |
| College search                         | `CollegeRoutes.kt:43`                      | works — user only, no student    |
| Vocabularies                           | `VocabularyRoutes.kt:49`                   | works — user only, no student    |

Nothing 500s. The `409 student_profile_required` shape is defined once
(`CallerResolution.kt:56-58`, code at `models/ErrorCode.kt:24`) and the iOS
client already treats it as "go to onboarding" — see §5. **That is the single
biggest risk for a parent slice:** if a parent account hits any of those 9
routes, the current app pushes them into student onboarding.

### The "student-less viewer" that already ships

`public-web` serves `GET /report?token=<raw>` to a wholly unauthenticated reader
(`public-web/.../Routing.kt:201-232`): one query token, `no-store` +
`X-Robots-Tag` + `no-referrer` set before the outcome is known (`:204`), token
redacted from logs (`:203`), a repeated `token` param refused (`:209-213`), and
a byte-identical branded 404 for missing/blank/unknown/revoked (`:222-228`).
Copy this page verbatim for an invite-accept landing page.

---

## 4. What a 13+ DOB gate would touch

Nothing stores a birthdate (C4). A minimal gate touches:

1. **Schema**: a new nullable column on `users` — plus the mirrored column in
   `users_versions` and the `log_user_version()` INSERT column list
   (`0001:47-61,78-90`); the three-part precedent is
   `0013.add-users-email-verified-at.sql:7-9`. `users` not `students` is forced,
   because a parent has no student row.
2. **Wire**: a field on `RegisterRequest` (`models/RegisterRequest.kt:3-7`), the
   `AuthService.register` signature (`AuthService.kt:50-58`), and
   `RegistrationValidator` (`service/.../auth/RegistrationValidator.kt`).
3. **The SSO hole**: `POST /google` and `POST /apple` never see a birthdate —
   the requests carry only `idToken` (+ optional Apple `name`)
   (`AuthRoutes.kt:240-252`) and `loginWithSso` creates the user itself
   (`AuthService.kt:335,554`). DOB "at signup" cannot be collected there without
   a post-SSO step — expect a new iOS state between `.verificationRequired` and
   `.onboarding`.
4. **`PublicUser`** (`models/PublicUser.kt:6-11`) if the client must know the
   gate was satisfied.

---

## 5. iOS registration / onboarding, and what parent-first breaks

(Delegated read of `ios-app/UnicoachiOS`; static only.)

One root state machine: `UnicoachiOSApp.swift:15-77` switches on
`viewModel.authState` (`UserAuthState.swift:3-19`) — `.unauthenticated` →
`AuthFlowView`, `.verificationRequired` → `VerificationRequiredView`,
`.onboarding` → `OnboardingView`, `.authenticated` → `AuthenticatedRootView`.
The decision point is `AppViewModel.resolveProfileState`
(`AppViewModel.swift:217-249`): unverified → `.verificationRequired`
(`:218-221`); else `fetchProfile()`, `nil` → `.onboarding`, non-nil →
`.authenticated` (`:222-227`); 404 maps to nil (`StudentClient.swift:28-32`).
Calls mirror §2: register `AuthClient.swift:30-34`, login `:36-40`, Google/Apple
`SsoSignIn.swift:124-138` + `AuthClient.swift:46-57`, me `:65-69`, logout
`:59-63`, resend `:71-75`. Student create posts the single ISO partial-date
string (`StudentClient.swift:17-22`, built at
`OnboardingViewModel.swift:75-86`). The verification link opens OUTSIDE the app
— no deep link (`UnicoachiOSApp.swift:92-95`).

Three constraints on a parent-first slice:

- **Onboarding is unskippable.** No skip control; `submit()` hard-stops on a
  failed create (`OnboardingViewModel.swift:94-103`). A parent would be trapped
  on "when do you graduate?".
- **`409 student_profile_required` means "go to onboarding".**
  `AppViewModel.onStudentProfileRequired()` (`:164-169`) is called from
  `CollegeListViewModel.swift:57,79,135`, `AddCollegeViewModel.swift:99,128`,
  `ConversationViewModel.swift:331`, `YourDetailsViewModel.swift:238,440`. A
  parent account hitting any student-scoped route bounces into student
  onboarding. The server needs a different refusal for a linked-parent caller,
  or the client a different branch on the same code.
- **No `studentId` is held client-side** — zero matches for
  `studentId|student_id`; every student route is `/students/me`
  (`StudentClient.swift:30`, `CollegeListClient.swift:58`,
  `CoachingUsageClient.swift:27`, `MoneyProfileClient.swift:58,69`). Good news
  (no ambient id to collide with) and bad (every client path needs a subject
  parameter, and no `/students/{id}`-shaped server surface exists). No keychain;
  only `UserDefaults` (appearance, Apple name — `AppleNameStore.swift:16-23`).

---

## 6. Concrete slice surface (minimum touch list)

- **DB**: new `parent_student_links` (role-typed, many-to-many, `unlinked_at`,
  never delete); new `link_invites` (0014 shape — hashed, CAS consume, TTL —
  with `user_id` replaced by inviter + invited email + role); optional
  `users.date_of_birth` (+ `users_versions` + `log_user_version()`).
- **Resolution seam**: `routing/CallerResolution.kt:28-59` — the ONE place that
  turns a user into a student.
- **Deletion**: `StudentService.kt:119-143` must split user-deletion from
  student-deletion, or a parent can never leave (C2).
- **Entitlement**: `0042:33` + `SubscriptionRoutes.kt:49` decide whether a
  parent pays (C1).
- **Invite landing page**: `public-web` (`Routing.kt:201-232`), never
  `rest-server` (`ClientKeyGate.kt:16-34`, `rest-server.conf:57`).
- **Email**: `EmailTemplate.kt:11-12` + a renderer +
  `worker/Application.kt:124`.
- **Verification gate**: `EmailVerificationGate.kt:38` allowlist, if accept must
  precede the parent's own verification.
- **iOS**: `AppViewModel.swift:217-249` and `:164-169` (+ its 7 call sites),
  `OnboardingView`/`OnboardingViewModel` (needs a parent branch), and a subject
  selector across `StudentClient` / `CollegeListClient` / `MoneyProfileClient` /
  `CoachingUsageClient`.

**Caveat:** static reading only. No Gradle build, no tests, no running service —
nothing here is compiler- or runtime-verified.
