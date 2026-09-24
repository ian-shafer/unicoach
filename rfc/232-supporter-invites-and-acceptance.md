# RFC 232 — Supporter invites and acceptance

**Slice:** `family/02/invite-and-accept` (brief 0014, _Parents in the room_,
handle `family`).\
**Base:** `main@67af2f56`. **Builds on:** RFC 221 (`family/01/the-link`), RFC
222 (`family/08/the-age-floor`), RFC 155 (`cost_report_shares`), RFC 224
(`web/02/sign-in`).

## Summary

The only way a supporter-student link is ever created: one party mints a
shareable invite link, and the other opens it on the web and accepts (brief 0014
D2). Both directions work: student invites parent/supporter, and
parent/supporter invites student.

The act of sharing the invite link IS the inviter's consent (Ian's gate-1
amendment to D2). The recipient opens the link in an ordinary browser, reads the
transparent ceiling statement explaining what linking does and does not share
(value before ask, D3 and D12), and accepts in one click without a
counter-confirmation step from the inviter.

Accepting works whether the recipient already has an account, is signed out, or
registers anew: the invite token rides through `/register` and `/sign-in`, and
the link is made the moment the recipient is authenticated. Acceptance is atomic
in itself — consume, supporter creation and link insert are one transaction —
but it is a SEPARATE transaction from the registration or login before it, so a
fault leaves the account made and the invite still live. `EmailVerificationGate`
is explicitly exempted for acceptance (correction X8), so a new parent or
student does not deadlock before verifying their email address.

This slice adds one table (`supporter_invites`), the REST mint endpoint and iOS
share sheet integration, the public-web preview and accept routes, and seamless
atomic linking on registration and sign-in.

## Context and binding decisions

From brief 0014, committed and binding:

- **D1/D2 — spine plus parent coach; invite is consent; accept completes.** A
  link exists only after an invite from one side and an explicit accept from the
  other. Sharing the link IS the inviter's consent; acceptance is one click by
  the recipient.
- **D3 — view, suggest, own workspace; ceiling stated in the invite.** Parents
  get read access, suggest affordances, and their own workspace. They never edit
  student data or read coach conversations. The invite explicitly states this
  ceiling.
- **D7 — hashed shareable invite tokens.** Hash at rest, single-use, expiring,
  shareable via any channel (iOS share sheet, SMS, email).
- **D10 (standing) — DDL presented at the approval gate.** Every new database
  table requires explicit approval with DDL.
- **D12 (standing) — value before ask.** The accept page explains what the link
  grants and keeps private before asking the recipient to sign in or sign up.
  Declining is a first-class outcome leaving accounts intact.
- **D21 — adult side is its own entity (`supporters`).** A link references
  `supporters.id`, not `users.id`. Accepting as an adult creates the
  `supporters` row if one does not exist yet.
- **D22 — neutral schema vocabulary, honest relationship word.** The per-link
  `relationship` carries `parent`, `guardian`, `grandparent`, `supporter`.
  Default is `parent`.
- **Correction X7 — `cost_report_shares` hashed-token precedent.** Hash at rest
  (SHA-256), raw token only in the link, NOT `verification_tokens` whose
  `user_id NOT NULL` cannot represent an invite to a non-user.
- **Correction X8 — `EmailVerificationGate` exemption.** The gate blocks
  unverified authenticated callers. Invite acceptance must be exempted so a
  parent can accept before verifying email.

## Detailed Design

### 1. Database Schema: `supporter_invites`

A single-use, hashed, revocable, and expiring credential table in
`db/schema/supporter-invites.sql`.

```sql
CREATE TABLE supporter_invites (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Token hash: SHA-256 of raw secret token (like cost_report_shares and verification_tokens)
    token_hash     BYTEA       NOT NULL,

    -- Inviter identification: exactly one of student_id or supporter_id is populated.
    -- All three foreign keys CASCADE, stated on every one of them: an invite is a
    -- pointer to the parties, so a deleted party takes its pending invites with it.
    -- Left bare, supporter_id would default to NO ACTION and a stale invite would
    -- refuse the deletion of the supporters row it names, while the student side
    -- of the same table cleaned up silently.
    inviter_user_id UUID       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    inviter_role    TEXT        NOT NULL CHECK (inviter_role IN ('student', 'supporter')),
    student_id      UUID        NULL REFERENCES students(id) ON DELETE CASCADE,
    supporter_id    UUID        NULL REFERENCES supporters(id) ON DELETE CASCADE,

    -- Relationship role offered (RFC 221 D22 vocabulary)
    relationship    TEXT        NOT NULL CHECK (relationship IN ('parent', 'guardian', 'grandparent', 'supporter')),

    expires_at      TIMESTAMPTZ NOT NULL,
    consumed_at     TIMESTAMPTZ NULL,
    revoked_at      TIMESTAMPTZ NULL,

    CONSTRAINT supporter_invites_inviter_endpoints_check CHECK (
        (inviter_role = 'student' AND student_id IS NOT NULL AND supporter_id IS NULL) OR
        (inviter_role = 'supporter' AND supporter_id IS NOT NULL AND student_id IS NULL)
    )
);

CREATE UNIQUE INDEX supporter_invites_token_hash_idx
    ON supporter_invites (token_hash);

CREATE INDEX supporter_invites_student_id_active_idx
    ON supporter_invites (student_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE INDEX supporter_invites_supporter_id_active_idx
    ON supporter_invites (supporter_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE INDEX supporter_invites_inviter_user_id_idx
    ON supporter_invites (inviter_user_id);
```

Like `verification_tokens` (0014) and `cost_report_shares` (0073),
`supporter_invites` is neither a versioned aggregate nor an append-only log: its
only mutations are compare-and-swap setting `consumed_at` once on acceptance, or
`revoked_at` once on revocation. No OCC version or history table is required.

### 2. Token Generation and Lifetime

- **Token derivation:** 32 secure-random bytes generated via `TokenGenerator`
  (matching `verification_tokens` and `sessions`), Base64-URL unpadded (43
  characters). Stored as `SHA-256` hash in `token_hash`.
- **Lifetime:** 14 days, stated ONCE — `supporterInvite.tokenTtl = "14 days"` in
  `service.conf`, read with no Kotlin fallback the way
  `emailVerification.tokenTtl` is, so a missing or misnamed key fails at boot
  instead of inventing a lifetime nobody declared. Long enough for family
  coordination over weekends, short enough for hygiene.
- **Multiple live invites:** An inviter may hold several live invites at once. A
  student with two parents (e.g. Mom and Dad) can invite both independently.

### 3. Acceptance Mechanics & Atomic Linking

Acceptance occurs in one transaction via `SupporterInviteService.acceptInvite`.
The CAS consume is the LAST refusal point, not the first: a refusal that will
not produce a link — a self-addressed invite, a supporter with no student
profile — must not burn a single-use invite the family would then have to
re-mint.

1. Read the LIVE invite by token hash
   (`consumed_at IS NULL AND revoked_at IS NULL AND expires_at > NOW()`). Not
   live: refuse, and nothing is burned.
2. Verify caller is not the inviter (`caller.user.id != invite.inviterUserId`).
   Self-linking is prohibited (and guarded by trigger
   `supporter_student_links_not_self_link`).
3. Resolve or create endpoints:
   - When student invited supporter:
     - Ensure caller has a `supporters` row (`SupportersDao.findByUserId`). If
       absent, mint one via
       `SupportersDao.create(NewSupporter(userId = caller.user.id))`.
     - `supporterId` = caller's supporter row id; `studentId` =
       `invite.studentId`.
   - When supporter invited student:
     - Verify caller has a `students` row (`StudentsDao.findByUserId`). If
       absent, caller must complete student onboarding.
     - `supporterId` = `invite.supporterId`; `studentId` = caller's student row
       id.
4. CAS consume — the last refusal point, so only an acceptance that will link
   burns the token:
   ```sql
   UPDATE supporter_invites
   SET consumed_at = NOW()
   WHERE token_hash = ? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > NOW()
   RETURNING *
   ```
5. Insert link:
   `SupporterStudentLinksDao.create(NewSupporterStudentLink(supporterId, studentId, invite.relationship))`
   If already active (idempotent / concurrent), the link stands.
6. All executed in one atomic database transaction (`database.withConnection`).
   A fault at step 5 rolls step 4 back with it, so the invite is still live and
   the recipient can open the same link again.

### 4. Public Web: `/invite` Page (Value Before Ask)

Mounted in `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`:

- **`GET /invite?token=<raw>`**:
  - `secretQueryParams("token")` installed route-scoped so raw tokens never
    appear in logs.
  - `setLinkHolderPrivacyHeaders()` installed (`no-store`, `no-referrer`,
    `X-Robots-Tag: noindex`).
  - Validation: If token is ill-formed, unknown, expired, consumed, or revoked:
    Render the uniform branded refusal page (`InviteMissReason` logged to
    `inviteLog`, never revealed to client — the `/report` precedent). The reason
    names which of a blank token, a repeated `token` parameter, or no live
    invite behind the hash produced the miss.
  - If valid:
    - Display inviter's name and role: "[Name] invited you to connect on
      unicoach as a [relationship]."
    - Display the ceiling statement:
      - **What connecting allows:**
        - View college list, key application deadlines, and milestones.
        - View estimated college costs and affordability computations.
        - Access your own AI coach workspace for financial aid and guidance.
      - **What remains private:**
        - Student coach conversations are 100% private to the student; no
          transcripts are shared.
        - Supporters cannot edit the college list, student profile, or student
          tasks.
    - **If signed in as anyone but the inviter:**
      - Button: "Accept Invitation" (POST to `/invite` with hidden field
        `token`).
      - Button: "Decline" (navigates away without modifying anything).
    - **If signed in AS THE INVITER:** no accept button at all — one sentence
      saying it is their own link to share, and a way back to their account.
      `acceptInvite` refuses a self-addressed invite and that refusal renders
      the uniform branded 404, so offering the button would tell the person who
      minted a LIVE link that it is dead.
    - **If signed out:**
      - Button: "Create Account to Accept" (`/register?token=<raw>`).
      - Button: "Sign In to Accept" (`/sign-in?token=<raw>`).
      - Button: "Decline" (navigates to marketing home).

- **`POST /invite`**:
  - Requires signed-in session (`handleAccess(WebAccess.SIGNED_IN)`), explicitly
    exempted from email verification.
  - Calls `SupporterInviteService.acceptInvite`.
  - On success: redirects `303 See Other` to `/account?notice=invite-accepted`.
    The key is hyphenated because every `WebNotice` key is.
  - On refusal/invalid: renders branded refusal.

- **Registration & Sign-In Linkage**:
  - `/register` and `/sign-in` accept an optional `token` query parameter / form
    field — the SAME field name `/invite` uses, so the one
    `secretQueryParams(TOKEN_FIELD)` redaction contract covers all three doors.
    A second field name would need a redaction entry of its own to stay out of
    the logs, and both routes scope it secret for exactly that reason. They also
    carry `/invite`'s `setLinkHolderPrivacyHeaders()`: redaction keeps the raw
    token out of the LOG, and a URL holding a live single-use credential must
    equally not be kept by a shared cache, indexed, or handed on in a `Referer`.
  - Upon successful registration or login, if a token is provided,
    `acceptInvite` is executed immediately, in its OWN transaction —
    registration and acceptance are two commits, not one. A fault between them
    leaves the account created and the invite still LIVE, which is the
    recoverable half: the recipient opens the same link again.
  - The outcome is BOUND, never discarded: `acceptInvite` returns its refusals
    as successful values, so a door that only called `getOrThrow()` would
    announce a link it never made. A linked recipient gets
    `notice=invite-accepted`; a refused one gets `notice=invite-not-accepted` —
    one sentence for all three refusals, because `POST /invite` serves one body
    for all three and a distinguishable answer here would be the enumeration
    oracle that refusal avoids.
  - Produces exactly one link and never a duplicate.

### 5. REST API: Minting & Accepting Invites

Mounted in
`rest-server/src/main/kotlin/ed/unicoach/rest/routing/SupporterInviteRoutes.kt`:

- **`POST /api/v1/supporter-invites`**:
  - Authenticated.
  - Determines caller role:
    - If caller has a `students` row: inviter is student (`student_id`), target
      is supporter.
    - If caller has a `supporters` row (or is an adult user): inviter is
      supporter (`supporter_id`), target is student. Adult `supporters` row is
      created on demand if not yet present.
  - Body: `{ "relationship": "parent" }` (optional, default "parent").
  - Returns:
    ```json
    {
      "id": "uuid",
      "inviteUrl": "https://<web-domain>/invite?token=<raw>",
      "inviterRole": "student",
      "relationship": "parent",
      "expiresAt": "2026-09-30T12:00:00Z"
    }
    ```
- **`POST /api/v1/supporter-invites/accept`**:
  - Authenticated. Exempted from `EmailVerificationGate` (matching correction
    X8).
  - Body: `{ "token": "<raw>" }`.
  - Accepts invite for caller and returns
    `{ "status": "accepted", "linkId": "uuid" }`.

### 6. iOS App: Settings Share Door

In `ios-app/UnicoachiOS/SettingsView.swift`:

- Add a "Family & Supporters" section:
  - If signed in as student: button "Invite a Parent or Supporter".
  - Tapping calls `POST /api/v1/supporter-invites`, receives `inviteUrl`.
  - Presents native iOS share sheet (`ShareLink` or `UIActivityViewController`)
    with prefilled text:
    `"Join me on unicoach to support my college journey: <inviteUrl>"`
  - The "if signed in as student" gate is the app's state machine, not a
    condition in this view: `AuthenticatedRootView` — and therefore
    `SettingsView` — is reached only from `UserAuthState.authenticated`, which
    `AppViewModel.resolveProfileState` enters only when
    `studentClient.fetchProfile()` returns a profile. A user with no student
    profile is in `.onboarding` and never sees this screen.

## Files Modified

- `db/schema/supporter-invites.sql` [NEW]: DDL for `supporter_invites`.
- `db/schema/ORDER`: the migration line (placed at land time by `ship-order`).
- `db/src/main/kotlin/ed/unicoach/db/dao/SupporterInvitesDao.kt` [NEW]: create,
  read, CAS consume, revoke. No list-live reads: nothing in this slice lists an
  inviter's live invites, and the slice that adds that surface adds the read it
  needs.
- `db/src/main/kotlin/ed/unicoach/db/models/SupporterInvite.kt`,
  `SupporterInviteId.kt`, `NewSupporterInvite.kt`, `InviterRole.kt` [NEW].
- `db/src/test/kotlin/ed/unicoach/db/dao/SupporterInvitesDaoTest.kt` [NEW].
- `service/src/main/kotlin/ed/unicoach/family/SupporterInviteConfig.kt` [NEW]:
  invite URL base + TTL.
- `service/src/main/kotlin/ed/unicoach/family/SupporterInviteService.kt` [NEW]:
  mint, preview, accept (atomic link), revoke.
- `service/src/main/resources/service.conf`: `supporterInvite` config block.
- `service/src/test/kotlin/ed/unicoach/family/SupporterInviteServiceTest.kt`
  [NEW].
- `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`: `INVITE_PATH`,
  `inviteRoute`, and register/sign-in invite-token plumbing.
- `public-web/src/main/kotlin/ed/unicoach/web/Application.kt`: wire
  `SupporterInviteService`.
- `public-web/src/main/kotlin/ed/unicoach/web/render/InvitePage.kt` [NEW]:
  ceiling page, accept form, decline, refusal.
- `public-web/src/main/kotlin/ed/unicoach/web/render/FormControls.kt`:
  `INVITE_ACCEPTED` and `INVITE_NOT_ACCEPTED` notices.
- `public-web/src/main/kotlin/ed/unicoach/web/render/RegisterPage.kt` /
  `SignInPage.kt`: invite token hidden field.
- `public-web/src/test/kotlin/ed/unicoach/web/InviteRoutingTest.kt` [NEW].
- `public-web/src/test/kotlin/ed/unicoach/web/InviteLinkContractTest.kt` [NEW]:
  the `supporterInvite.inviteUrlBase` <-> `INVITE_PATH` contract the compiler
  cannot see (the `ReportLinkContractTest` precedent).
- `public-web/src/test/kotlin/ed/unicoach/web/CspConformanceTest.kt`: the
  `GET /invite` route-coverage entry.
- `public-web/src/test/kotlin/ed/unicoach/web/WebAuthTestSupport.kt` /
  `FakeEmailVerifier.kt`: invite service wiring + seed helpers.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/SupporterInviteRoutes.kt`
  [NEW]: mint + accept REST doors.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt` / `Application.kt`:
  wire the routes and service.
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/EmailVerificationGate.kt`:
  exempt `/api/v1/supporter-invites/accept`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/SupporterInviteRequests.kt`
  [NEW].
- `rest-server/src/test/kotlin/ed/unicoach/rest/SupporterInviteRoutingTest.kt`
  [NEW].
- `ios-app/UnicoachiOS/Models.swift`: invite request/response models.
- `ios-app/UnicoachiOS/StudentClient.swift`: `SupporterInviteClient` + protocol.
- `ios-app/UnicoachiOS/AppViewModel.swift`, `UnicoachiOSApp.swift`,
  `AuthenticatedRootView.swift`: client wiring.
- `ios-app/UnicoachiOS/SettingsView.swift`: "Family & Supporters" invite
  button + share link.

## Implementation Plan

1. **Schema & DAO (`:db`)**:
   - Write `db/schema/supporter-invites.sql` and add to `db/schema/ORDER`.
   - Implement `SupporterInvitesDao` and model classes.
   - Add unit tests in `SupporterInvitesDaoTest` covering create, consume,
     revoke, expiry, the inviter-endpoints CHECK constraint, the UNIQUE
     token-hash index, and the `supporter_student_links_not_self_link` trigger.
2. **Service Layer (`:service`)**:
   - Implement `SupporterInviteService` covering mint, findByToken, and
     acceptInvite with atomic supporter/link creation.
   - Add comprehensive tests in `SupporterInviteServiceTest` covering happy
     path, both directions, self-link refusal, expired token refusal, and
     duplicate link handling.
3. **Public Web (`:public-web`)**:
   - Implement `InvitePage.kt` with clear ceiling statement and value-before-ask
     presentation.
   - Implement `GET /invite` and `POST /invite` in `Routing.kt`.
   - Hook invite token into `/register` and `/sign-in` flows for atomic
     registration+acceptance.
   - Add tests in `InviteRoutingTest` verifying rendering, refusal uniformity,
     signed-out and signed-in accept flows.
4. **REST Server (`:rest-server`)**:
   - Implement `SupporterInviteRoutes.kt` and register in `Routing.kt`.
   - Update `EmailVerificationGate.kt` to exempt invite acceptance.
   - Add tests in
     `rest-server/src/test/kotlin/ed/unicoach/rest/SupporterInviteRoutingTest.kt`,
     beside the other `*RoutingTest` suites in that package.
5. **iOS App (`:ios-app`)**:
   - Add invite action to `SettingsView.swift` launching share sheet.

## Tests

- `db/src/test/kotlin/ed/unicoach/db/dao/SupporterInvitesDaoTest.kt`:
  - Mint new invite, verify token hash at rest.
  - CAS consume: burns token once, second consume fails.
  - Revocation: burns token, cannot consume.
  - Expiry: expired token cannot be consumed.
  - Direction checks: student_id vs supporter_id constraints.
- `service/src/test/kotlin/ed/unicoach/family/SupporterInviteServiceTest.kt`:
  - Student invites supporter -> supporter accepts -> `supporters` row created,
    link row created.
  - Supporter invites student -> student accepts -> link row created.
  - Self-link refused by the service check; the
    `supporter_student_links_not_self_link` trigger half is covered in
    `SupporterInvitesDaoTest`, which is where the link write lives.
  - Secret token not persisted raw: the stored bytes are SHA-256 of the raw
    token, and no column of the row carries the raw token.
  - Atomic link creation with rollback on fault: a closed student endpoint makes
    the link insert fail after the CAS consume, and the invite must still be
    live afterwards.
  - Expired invite refused.
  - A second invite accepted by an already-linked supporter reuses the live
    link.
- `public-web/src/test/kotlin/ed/unicoach/web/InviteRoutingTest.kt`:
  - Unauthenticated GET shows ceiling statement and sign-up / sign-in options
    without asking for credentials.
  - Blank / absent / unknown / expired / consumed / revoked token renders the
    same branded refusal (no oracle).
  - Signed-in POST /invite consumes token and redirects to `/account`.
  - Registration with an invite token creates the user and the link.
  - Sign-in with an invite token creates the link.
  - A refused token on either authenticating door redirects with
    `notice=invite-not-accepted` and links nobody.
- `rest-server/src/test/kotlin/ed/unicoach/rest/SupporterInviteRoutingTest.kt`:
  - `POST /api/v1/supporter-invites` mints invite and returns URL.
  - `POST /api/v1/supporter-invites/accept` succeeds even when caller is
    unverified (X8).
