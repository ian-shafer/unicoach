# RFC 276 — Password reset on the web

**Slice:** `web/12/password-reset` (brief 0013, _Unicoach on the web_).
**Status:** approved (Ian, 2026-11-08: "Approve all"). **Base:**
`main@77ba5c1b`. **Lane:** rfc.

## Executive Summary

Anyone who forgets their password today has no way back in: no reset route
exists anywhere in the backend. This RFC adds "Forgot your password?" to the
sign-in page. The person enters an address, receives a single-use link, chooses
a new password, every other session on the account ends, and they are signed in.

It is built beside email verification rather than on top of it, as the slice
decided, on a new general `account_tokens` table: single-use tokens mailed to an
account's address, each row naming its `type`, hashed at rest. Password reset is
its first type. Around it sit a new `PasswordResetService`, a new email template
and two new pages. The mailed `GET` only shows a form, so a mail scanner
fetching the link cannot spend it. The web calls the service in-process (D13).

The request form gives one answer whatever is typed. The link stops working if
the account's address changes after it was sent. Because the link proves control
of the inbox, using it also marks the address verified.

The decisions are in §8. The largest still open is what an Apple-only or
Google-only account receives (D-A).

## Detailed Design

### 1. What the code says

| Fact                                                                                                                                            | Where                           |
| ----------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------- |
| Verification tokens store only a SHA-256 hash and are claimed by a compare-and-swap `UPDATE`.                                                   | `VerificationTokensDao`         |
| `SessionsDao.revokeAllForUser` exists, so ending every session needs no new write.                                                              | `SessionsDao.kt:379`            |
| The address can change two ways: `AuthService.changeEmail`, and an operator's edit through admin `UsersDao.update`.                             | `UsersResource.kt:266`          |
| Erasure keeps the `users` row, so a new table keyed on it needs an explicit delete; `ON DELETE CASCADE` never fires.                            | `erase_account_delete_keyed`    |
| `erase_account_walk_edges` lists the physically deleted tables by name, and `ErasureClassificationTest` holds every column to the live catalog. | `erasure-functions.sql:734`     |
| `/register` answers `409` for a taken address, so this site already tells a stranger whether an address has an account.                         | `RegisterPage.kt:72`            |
| The rendered email body, including its link, is stored in `email_sends`, as verification's is. `admin-web` does not display that table.         | `VerificationEmailContext` KDoc |
| The password rules are inline in `RegistrationValidator`.                                                                                       | `RegistrationValidator.kt`      |

### 2. The flow

1. "Forgot your password?" on `/sign-in` links to `GET /forgot-password`.
2. `POST /forgot-password` calls `PasswordResetService.request`. A well-formed
   address gets the one confirmation page whether or not it has an account. A
   malformed one re-renders the form at `400` with the typed value (D12); format
   says nothing about whether an account exists.
3. For a live account, one transaction burns the account's earlier unconsumed
   reset links, inserts the new token's hash and enqueues the email (ASYNC_WORK:
   a required enqueue is part of the transaction).
4. The mailed `GET /reset-password?token=…` reads the token without changing it.
   A usable link renders the form; a dead one renders the dead-link page.
5. `POST /reset-password` checks the link, checks the password rules, hashes
   outside any transaction (Argon2 is slow and must not hold a connection), then
   in one transaction:
   - claims the token by compare-and-swap;
   - writes `users.password_hash` through a new dedicated
     `UsersDao.setPassword`;
   - calls `SessionsDao.revokeAllForUser`, which signs out the iPhone too;
   - burns the account's other reset links;
   - if the address was unverified, runs `recordEmailVerified` with the required
     `afterVerified` hook. It is skipped for a verified address because that
     hook must not run twice for one address;
   - mints a new session, inside the same transaction, so no moment exists in
     which the password changed and the sign-in did not.
6. `SignInCompletion.complete`, the landing every other sign-in uses (D-B), with
   no invite or report token.

Reset belongs to the account, not to a role. Nothing in the service, the routes
or the pages reads `students` or `supporters`: a student, a supporter and an
account that has not finished onboarding take the same path, and the sign-in
landing sends each to its usual place afterwards.

A password that breaks the rules re-renders the form at `400` and does not spend
the token. Two simultaneous posts cannot both win the claim; the loser gets the
"already used" page.

### 3. Data model

**New table (standing D10, full DDL):**

```sql
-- RFC 276: Single-use tokens mailed to an account's address. One table for
-- every such token; `type` says what a token can do. Only the SHA-256 hash
-- is stored; the raw token exists only in the emailed link. The values of
-- `type` are owned by the Kotlin enum AccountTokenType.
CREATE TABLE account_tokens (
  id             UUID PRIMARY KEY DEFAULT uuidv7(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  user_id     UUID NOT NULL REFERENCES users(id),
  type        TEXT NOT NULL CHECK (type IN ('password_reset')),
  -- users.version when the token was issued; users_versions at this version
  -- holds the address the link was mailed to.
  user_version INTEGER NOT NULL,
  token_hash  BYTEA NOT NULL,
  expires_at  TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ NULL
);

CREATE UNIQUE INDEX account_tokens_token_hash_idx
  ON account_tokens (token_hash);
CREATE INDEX account_tokens_user_id_type_idx
  ON account_tokens (user_id, type);
```

A later type is one value in that `CHECK` and one enum entry. The table stores
no address. `user_version` points at the `users_versions` row, which holds the
address as it was when the link was mailed, and the claim requires that address
to still equal `users.email`. So a link in a mailbox the account has left is
dead however the address changed, with one check at the claim covering every
writer. It compares addresses, not version numbers, because a rename or a
verification also bumps `users.version` and must not kill a link. There is no
foreign key: a missing version row matches nothing, so the claim fails closed.

The same migration restates `erase_account_walk_edges` and
`erase_account_delete_keyed` from their current definitions, adding this table
to the exclusion list and the delete. No session-metadata schema changes (D11).

Kotlin: `AccountToken`, `NewAccountToken`, `AccountTokenId` and
`enum class AccountTokenType(val value: String)` with its `fromValue` companion,
in `db/models`, and `AccountTokensDao` mirroring `VerificationTokensDao`. Every
read and claim takes the type, so a token minted for one type cannot be spent on
another:

```sql
UPDATE account_tokens t SET consumed_at = NOW()
  FROM users u, users_versions uv
 WHERE t.token_hash = ? AND t.type = ?
   AND t.consumed_at IS NULL AND t.expires_at > NOW()
   AND u.id = t.user_id AND u.deleted_at IS NULL
   AND uv.id = u.id AND uv.version = t.user_version AND uv.email = u.email
RETURNING t.*
```

plus `findSpendable(type, hash)`, the same conditions as a read for the mailed
`GET`, `findByTokenHash(type, hash)` and `consumeAllForUser(type, userId)`.

`claim` first takes a blocking `SELECT ... FOR UPDATE` on the owning `users` row
and holds it for the transaction. Without it, an address change could commit
between the claim and `recordEmailVerified`, marking the new address verified on
the strength of a link mailed to the old one. It blocks rather than using
`NOWAIT` so the loser of two simultaneous posts sees "already used", not a
fault.

### 4. Service

`PasswordResetService(database, queueService, tokenGenerator, argon2Hasher, sessionMinter, config, afterVerified)`
in `service/auth`, with `afterVerified` required as on `AuthService`.
`AuthService.mintSession` and `mintSessionMetadata` move to a small
`SessionMinter(tokenGenerator)`, which both services use, so there is one
definition of what a new session is.

```kotlin
sealed interface PasswordResetRequest { Submitted; InvalidEmail(error) }
sealed interface ResetLink { Usable; sealed interface Dead : ResetLink { NotValid(reason: NotValidReason); Expired; AlreadyUsed } }
sealed interface PasswordResetResult { Done(user: User, sessionToken: String); Rejected(violations: List<PasswordViolation>); Dead(link: ResetLink.Dead) }

suspend fun request(email: String): Result<PasswordResetRequest>
suspend fun inspect(rawToken: String): Result<ResetLink>
suspend fun reset(
  rawToken: String,
  newPassword: String,
  oldCookieToken: String?,
  sessionExpiration: Duration,
  userAgent: String?,
  initialIp: String?,
): Result<PasswordResetResult>
```

`PasswordResetResult.Dead` holds a `ResetLink.Dead`, so a usable link cannot be
reported as dead. `request` normalizes the address as `login` does and returns
`Submitted` for no account, a closed account and a sent link alike. Each of
those is logged at info with the outcome and never the address.

Classifying a failed claim: consumed, which includes a link burned by a newer
request → `AlreadyUsed`; expired → `Expired`; anything else → `NotValid`, whose
reason says whether the token was unknown or its owner changed (a moved address
or a closed account). The page ignores the reason; the log, written where the
row is in hand, records it with the token row id and user id and never the raw
token.

The password rules move to a
`passwordViolations(password): List<PasswordViolation>` in `service/auth`, a
sealed type holding each rule's one sentence, which `RegistrationValidator` and
`reset` both call, so the two forms cannot disagree.

### 5. Email

`EmailTemplate.PASSWORD_RESET`, `PasswordResetEmailContext(resetToken)` and
`PasswordResetEmailRenderer(resetUrlBase)`, listed in `emailRenderers`, which
now takes the two typed configs so the two URL bases cannot be swapped.

> **Subject:** Reset your Unicoach password
>
> Someone asked to reset the password for your Unicoach account. To choose a new
> password, visit:
>
> {link}
>
> This link works once and expires soon. If you did not ask for this, you can
> ignore this message and your password will stay the same.

The copy states no duration, so a changed TTL cannot make it wrong.

### 6. Configuration

```hocon
passwordReset {
  tokenTtl = "1 hour"
  tokenTtl = ${?PASSWORD_RESET_TOKEN_TTL}
  resetUrlBase = ${publicWeb.urlBase}"/reset-password"
}
```

Read by a `PasswordResetConfig.from`, shaped like `EmailVerificationConfig`.
`resetUrlBase` passes through `normalizeUrlBase`.

### 7. Pages

All render through `authFormCard` and `siteLayout`, signed-out doors with
`csrf = null`, covered by the same-origin guard. New constants
`FORGOT_PASSWORD_PATH` and `RESET_PASSWORD_PATH` sit beside `SIGN_IN_PATH`.
`/reset-password` is a `route` block with `secretQueryParams(TOKEN_FIELD)` and
`setLinkHolderPrivacyHeaders()`, as `/invite` has, because its URL holds a live
credential. The token rides a hidden field on the `POST`.

| Page / state         | Status | Words                                                                                                                                                                                         |
| -------------------- | ------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Sign-in link         | —      | "Forgot your password?", under the password field                                                                                                                                             |
| Request form         | 200    | **Forgot your password?** Enter your email address and we'll send you a link to choose a new one. Field: Email. Button: Send link. Then "Back to sign in".                                    |
| Confirmation         | 200    | **Check your email** If there's a Unicoach account for that address, we've sent it a link to choose a new password. If nothing arrives, check your spam folder or try again in a few minutes. |
| Reset form           | 200    | **Choose a new password** Field: New password (`autocomplete=new-password`). Button: Save new password.                                                                                       |
| Password rejected    | 400    | The form, with each violation listed above it                                                                                                                                                 |
| Dead link: not valid | 400    | **Link not valid** This link isn't valid. You can ask for a new one.                                                                                                                          |
| Dead link: expired   | 410    | **Link expired** This link has expired. You can ask for a new one.                                                                                                                            |
| Dead link: used      | 410    | **Link already used** This link has already been used. You can ask for a new one, or sign in.                                                                                                 |

Each dead page links to `/forgot-password`, so nobody is stranded.

Errors and edge cases: an unreadable or repeated token field reads as
`NotValid`, through `presentedToken`. A database fault reaches the site's `503`.
Dependencies: none new.

### 8. Decisions

**D-A — an Apple-only or Google-only account gets the reset link.** Recommended:
send it, and let them set a password. Prod's web Apple and Google buttons are
dark until the web client IDs exist, so this is the only way such a user can
reach the website today. The alternative is an email saying "you sign in with
Apple" and no password; it adds a second email and leaves them stuck on the web
until those IDs exist. Cost of the recommendation: `passwordHash != null` stops
meaning "created through registration". The SSO link gate reads that fact, so
Implementation Plan step 3 corrects its KDoc. The gate's behaviour is
unaffected, because a reset always leaves the address verified.

**D-B — a successful reset signs the person in** (Ian, 2026-11-08). Whoever
holds the link can set the password and sign in seconds later anyway, so a
second sign-in adds a step and no protection. It goes through
`SignInCompletion.complete`, like every other sign-in.

**D-C — the link lasts one hour** (config). Long enough to find the email, short
enough that a stored copy in `email_sends` or a forwarded message goes dead
quickly.

**D-D — no defence against response timing.** Recommended: the status, body and
headers are identical, and the timing differs by a few database statements for
an address with an account. A timing defence buys nothing while `/register`
answers `409` for a taken address. The alternative moves the lookup to a queue
job so the request does identical work, at the price of a new job type and
storing addresses strangers typed. The acceptance criterion is met at the level
of what the form says; this RFC does not claim more.

**D-E — no limit on repeat requests** (Ian, 2026-11-08: "This is not a problem
we need to solve.").

**D-F — using the link marks an unverified address verified.** Recommended: yes;
the link proved the inbox exactly as a verification link does. It also ends a
pre-hijack: if someone registered with a victim's address, the victim's reset
replaces that password and ends that person's sessions.

**D-G — the token table is general** (Ian, 2026-11-08). `account_tokens` holds
single-use mailed tokens of any type; password reset is the first.
`verification_tokens` stays where it is: moving live links is separate work with
its own risk, and nothing here needs it.

## Tests

Real database throughout, through `bin/test`. Token read from the enqueued job.

`AccountTokensDaoTest`: claim succeeds once; second claim is no row; expired is
no row; a changed `users.email` is no row; a rename, which bumps `users.version`
and keeps the address, still claims; soft-deleted user is no row;
`consumeAllForUser` leaves consumed rows alone; `AccountTokenType.fromValue`
round-trips every entry against the `CHECK`.

`PasswordResetServiceTest`:

- `request` for a live account writes one token and one `SEND_EMAIL` job
  carrying `PASSWORD_RESET`, the raw token and the account key.
- `request` for no account, and for a closed account, writes nothing and returns
  `Submitted`.
- A second `request` burns the first link.
- An Apple-only account (no password) gets a link and can set one (D-A).
- A student account, a supporter account and an account with neither role each
  request a link and reset through the same calls.
- A failed enqueue rolls back the token row.
- `reset` changes the hash: old password fails login, new one succeeds.
- `reset` returns a session token that resolves to the user.
- `reset` revokes every earlier session on the account and none on another
  account.
- `reset` burns the account's other links.
- `reset` twice: second is `Dead(AlreadyUsed)` and the password is unchanged.
- `reset` on an expired token is `Dead(Expired)`; on an unknown one,
  `Dead(NotValid)`.
- `reset` after `changeEmail`, and after an operator's `UsersDao.update` of the
  address, is `Dead(NotValid)` and leaves the password alone.
- A rule-breaking password is `Rejected` and the token stays usable.
- `reset` on an unverified account marks it verified and runs `afterVerified`
  once; on a verified account the hook does not run.
- Two concurrent `reset` calls: exactly one `Done`.

`PasswordResetRoutingTest` (`public-web`):

- `/sign-in` shows "Forgot your password?" linking to `/forgot-password`.
- The whole door: request → token from job → `GET` → `POST` → `303` to the
  signed-in landing with a working session cookie → the old session cookie no
  longer works → sign out and sign in with the new password.
- `POST /forgot-password` for a known and an unknown address: same status, same
  headers, byte-identical body.
- A malformed address is `400` with the typed value kept.
- `GET /reset-password` does not spend the token: two `GET`s, then a `POST`
  succeeds.
- Each dead state renders its page with a link to `/forgot-password`.
- A weak password re-renders at `400` with its violations, and a corrected post
  then succeeds.
- Two `token` fields read as not valid.
- `/reset-password` carries the link-holder headers and redacts `token` from the
  request log.

Elsewhere: `PasswordResetEmailRendererTest` (link, subject, undeserializable
context); `PasswordResetConfigTest`; `EmailRenderersTest` passes with the new
template; `RegistrationValidatorTest` stays green after the extraction;
`ErasureClassificationTest` gains the table's entries, including
`account_tokens.user_id` under `NOT_KEYED` and `user_version` as a version
column; `AccountErasureTest` shows an erased account's reset tokens are gone.

## Implementation Plan

1. **Schema.** Write `db/schema/create-account-tokens.sql` with its `ORDER`
   line: the table, indexes and the two restated erasure functions. Update
   `ErasureClassificationTest` and `ErasureFixture`. Verify:
   `nix develop -c bin/test db`.
2. **DAO and models.** Add the models, `AccountTokensDao` and
   `UsersDao.setPassword`, with the DAO tests. Verify:
   `nix develop -c bin/test db`.
3. **Service.** Extract `passwordViolations` and `SessionMinter`. Add
   `PasswordResetConfig`, `service.conf`'s block, the template, context,
   renderer and `PasswordResetService`, with tests. Correct the `passwordHash`
   wording in `AuthService.resolveOrProvisionUser`'s KDoc (D-A). Wire
   `emailRenderers` in the queue worker. Verify:
   `nix develop -c bin/test service`.
4. **Pages and routes.** Add the paths, the sign-in link, the new render file,
   the routes and the wiring in `public-web/Application.kt`, with the routing
   test. Verify: `nix develop -c bin/test public-web`.
5. **Whole suite.** `nix develop -c bin/test`, unscoped, and
   `nix develop -c bin/format -c`.

## Files Modified

Expected scope, non-exhaustive.

- `db/schema/create-account-tokens.sql`, `db/schema/ORDER`
- `db/src/main/kotlin/ed/unicoach/db/dao/AccountTokensDao.kt`,
  `db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt`,
  `db/src/main/kotlin/ed/unicoach/db/models/`
- `db/src/test/kotlin/ed/unicoach/db/`, `db/src/testFixtures/`
- `email/src/main/kotlin/ed/unicoach/email/EmailTemplate.kt`
- `service/src/main/kotlin/ed/unicoach/auth/` (`PasswordResetService`,
  `PasswordResetConfig`, `PasswordResetEmailRenderer`,
  `PasswordResetEmailContext`, `SessionMinter`, `RegistrationValidator`,
  `AuthService`)
- `service/src/main/kotlin/ed/unicoach/EmailRenderers.kt`,
  `service/src/main/resources/service.conf`
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/` (`Routing.kt`, `Application.kt`,
  `auth/WebAuthGate.kt`, `render/SignInPage.kt`, `render/PasswordResetPages.kt`)
- Tests beside each of the above.
