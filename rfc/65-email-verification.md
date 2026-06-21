# RFC 65: Email Verification (Backend)

## Executive Summary

Email+password users currently register and receive a session with no proof they
control the address. This RFC adds the backend mechanism to verify an email: a
marker on the user, a single-use token table, issuance + delivery on
registration, and verify/resend endpoints.

A nullable `email_verified_at` marker is added to `users`: a mutable domain
column whose **migration shape** mirrors `is_admin` from migration `0012` (added
to `users` + `users_versions`, copied by the `log_user_version` trigger, not in
`prevent_immutable_updates`), but whose **write isolation** mirrors `authMethod`
— it is written only by the dedicated verification path, never the generic
`UserEdit`/`update` path (unlike `is_admin`, which lives in `UserEdit`). A new
`verification_tokens` table stores only the SHA-256 **hash** of each token
(`TokenHash.fromRawToken`, the same construction `sessions` uses); the raw token
exists only in the email link. Tokens carry an expiry and a `consumed_at`
single-use marker enforced by a compare-and-swap `UPDATE`.

On registration the token is inserted inside the existing user-creation
transaction, then the verification email is sent **after commit, best-effort**
through the already-built but unwired `EmailService` — wiring `email.conf` into
the rest-server and recording the send in the `email_sends` ledger.

`POST /api/v1/auth/verify-email` hashes the supplied token, validates it
(unexpired, unconsumed), marks the user verified, and burns the token plus any
sibling tokens. `POST /api/v1/auth/resend-verification` (authenticated)
invalidates outstanding tokens and issues a fresh one. `register`/`login`/`me`
report verification state via a new `emailVerified` boolean on `PublicUser`.

Verification is **reported, not enforced**: a full session issues regardless.
Enforcement, change-email, the landing page, and iOS are out of scope.

## Detailed Design

### Data Models

#### Migration `0013.add-users-email-verified-at.sql`

Adds the verification marker as a mutable, versioned column, following the
`is_admin` precedent in `0012`:

- `ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ NULL;`
- `ALTER TABLE users_versions ADD COLUMN email_verified_at TIMESTAMPTZ NULL;`
- `CREATE OR REPLACE FUNCTION log_user_version()` to copy
  `NEW.email_verified_at` into the history row alongside the existing columns.

The column is **not** covered by `prevent_immutable_updates`, so marking a user
verified is an ordinary versioned `UPDATE` captured in `users_versions`. New
users default to `NULL` (unverified).

#### Migration `0014.create-verification-tokens.sql`

A single-use credential table. It is neither a versioned aggregate nor an
append-only log: its only mutation is setting `consumed_at` exactly once,
guarded by a compare-and-swap `UPDATE`, so no OCC `version` column and no
`_versions` history are needed. Modeled on `sessions` (a hashed credential), not
on `students`.

```sql
CREATE TABLE verification_tokens (
  id             UUID PRIMARY KEY DEFAULT uuidv7(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  user_id    UUID NOT NULL REFERENCES users(id),
  token_hash BYTEA NOT NULL,          -- SHA-256 of the raw token; raw never stored
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ NULL        -- single-use marker; set once on verify
);

CREATE UNIQUE INDEX verification_tokens_token_hash_idx ON verification_tokens (token_hash);
CREATE INDEX verification_tokens_user_id_idx ON verification_tokens (user_id);
```

`row_created_at` is the unmapped audit-clock column: the `VerificationToken`
domain type exposes only `createdAt` (mapped from `created_at`) and ignores
`row_created_at`, matching `Session` against the `sessions` table.

No `purpose`/`type` column: this table serves email verification only. The
change-email flow is out of scope here and will extend or add structure against
the table as it then lives in the code. No physical-delete guard (expired-token
pruning is out of scope), matching `sessions`.

#### Domain types (`db` module)

- `User` gains `val emailVerifiedAt: Instant?`.
- `UserVersion` gains `val emailVerifiedAt: Instant?` (faithful history
  exposure).
- `VerificationTokenId` — `@JvmInline value class … : Id`, mirroring
  `SessionId`.
- `VerificationToken` — `id: VerificationTokenId`, `userId: UserId`,
  `expiresAt: Instant`, `consumedAt: Instant?`, `createdAt: Instant`.
- `NewVerificationToken` — `userId: UserId`, `tokenHash: TokenHash`,
  `expiresAt: Instant`.

`NewUser` and `UserEdit` are unchanged: `email_verified_at` is written only by
the dedicated verification path, never the generic update path (the same
isolation `authMethod` already has).

#### REST DTOs (`rest-server` module)

- `PublicUser` gains `val emailVerified: Boolean` (computed
  `user.emailVerifiedAt != null`). This single field surfaces state on
  `register`, `login`, `me`, and `verify-email`.
- `VerifyEmailRequest` — `{ "token": String }`.
- `VerifyEmailResponse` — `{ "user": PublicUser }`.

### API Contracts

DAO surface (`VerificationTokensDao`, `db` module), built on the DAO capability
interfaces in `db/src/main/kotlin/ed/unicoach/db/dao/Dao.kt` (`Creatable` for
create; concrete methods for the rest):

```kotlin
object VerificationTokensDao : Creatable<NewVerificationToken, VerificationToken> {
  override fun create(session: SqlSession, input: NewVerificationToken): Result<VerificationToken>
  fun consume(session: SqlSession, tokenHash: TokenHash): Result<VerificationToken>
  fun findByTokenHash(session: SqlSession, tokenHash: TokenHash): Result<VerificationToken>
  fun consumeAllForUser(session: SqlSession, userId: UserId): Result<Int>
}
```

Behaviour:

- `consume` is a compare-and-swap that atomically claims an unconsumed,
  unexpired token by its hash and stamps `consumed_at`, returning the claimed
  row. When no row matches (unknown hash, already consumed, or expired) it
  yields `Result.failure(NotFoundException())`, the same no-row convention
  `SessionsDao` uses; the failed-verify classifier then calls `findByTokenHash`.
- `findByTokenHash` reads a token by hash in any state (consumed/expired
  included) for classifying a failed `consume`; an unknown hash yields
  `Result.failure(NotFoundException())`.
- `consumeAllForUser` stamps `consumed_at` on every still-unconsumed token for a
  user and returns the affected count; already-consumed rows are left untouched.

`UsersDao` gains one writer:

```kotlin
fun markEmailVerified(session: SqlSession, id: UserId): Result<User>
```

Behaviour: a versioned conditional update that sets `email_verified_at = NOW()`
for the user only while it is still `NULL`, bumping `version`, and returns the
updated row. When no row matches (already verified), it falls back to
`findById(session, id, SoftDeleteScope.ACTIVE)` and returns the existing user
unchanged — idempotent, no second version bump — failing only if the user is
truly absent.

`UsersDao.mapUser` and the `users_versions` mapper read `email_verified_at`.

**Full-row restore threading.** `mapUser`/`mapUserVersion` already deserialize
`email_verified_at`, so `User` and `UserVersion` carry it. The full-row writer
`updateFullRow` (private; shared by `revertToVersion` and
`updatePhysicalRecord`) reconstructs the row from an explicit column list bound
positionally (`stmt.setInt(1, …)` … `setInt(9, currentVersion)`), so it gains an
`emailVerifiedAt: Instant?` parameter, an `email_verified_at = ?` clause in the
`SET` list, and a new positional bind — which shifts the trailing
`WHERE id = ? AND version = ?` slots (currently 8 and 9) up by one. Both callers
thread the value through: `revertToVersion` passes `target.emailVerifiedAt` (the
historical version's value) and `updatePhysicalRecord` passes
`user.emailVerifiedAt`.

**Revert semantics (resolved):** reverting `users` to an older version
**restores that version's `email_verified_at`** — the column is treated as
ordinary versioned state, exactly like every other column `updateFullRow`
restores. A revert to a pre-verification version therefore sets the user back to
unverified, and a revert to a post-verification version restores the recorded
verification timestamp. This is the only behaviour consistent with
`email_verified_at` living in `users_versions` and being faithfully replayed; it
keeps the version history lossless rather than special-casing the marker.

Service surface (`EmailVerificationService`, `service` module):

```kotlin
class EmailVerificationService(
  database: Database,
  emailService: EmailService,
  tokenGenerator: TokenGenerator,
  config: EmailVerificationConfig,
) {
  // In the caller's transaction: generate raw token, insert its hash + expiry,
  // return the raw token for post-commit delivery.
  fun issueToken(session: SqlSession, userId: UserId): Result<String>
  // Post-commit, best-effort: build link from config.verifyUrlBase, construct a
  // fixed-literal EmailSubject/EmailBody via their create() factories, send via
  // EmailService; logs and returns failure without throwing.
  suspend fun sendVerificationEmail(to: EmailAddress, rawToken: String): Result<Unit>
  // Own transaction: CAS consume → markEmailVerified → consumeAllForUser.
  suspend fun verify(rawToken: String): Result<VerifyEmailResult>
  // Own transaction + post-commit send: no-op if already verified, else
  // consumeAllForUser then issue a fresh token, then send.
  suspend fun resend(user: User): Result<ResendResult>
}

sealed interface VerifyEmailResult {
  data class Success(val user: User) : VerifyEmailResult
  data object InvalidToken : VerifyEmailResult   // unknown hash
  data object Expired : VerifyEmailResult
  data object AlreadyConsumed : VerifyEmailResult
}

sealed interface ResendResult {
  data object Sent : ResendResult
  data object AlreadyVerified : ResendResult
}
```

`EmailVerificationConfig` (`emailVerification` block in `service.conf`, read
like `CoachingConfig`):

```kotlin
class EmailVerificationConfig private constructor(
  val tokenTtl: Duration,     // emailVerification.tokenTtl, e.g. "24 hours"
  val verifyUrlBase: String,  // emailVerification.verifyUrlBase
) { companion object { fun from(config: Config): Result<EmailVerificationConfig> } }
```

HTTP endpoints (both under the existing `AuthRouteHandler`):

- `POST /api/v1/auth/verify-email`
  - Request `VerifyEmailRequest`; no auth required (token is the credential).
  - `200` `VerifyEmailResponse` on success.
  - `400` `ErrorResponse` with lowercase code (auth-route convention):
    `invalid_token`, `token_expired`, or `token_already_used`.
- `POST /api/v1/auth/resend-verification`
  - Authenticated via session cookie; empty body. The handler resolves the user
    identically to `handleMe`. A missing cookie or a `null` resolved user both
    short-circuit to `401`. The resolved `User` is passed to
    `EmailVerificationService.resend(user)`.
  - `204 No Content` on success — including the already-verified case
    (idempotent, no email sent, no state leak).
  - `401` `unauthorized` when the cookie is absent or resolves to no user.

Registration delivery: `AuthService.register` calls
`emailVerificationService.issueToken(session, user.id)` inside its existing
`withConnection` block (atomic with user + session), captures the raw token, and
after the block calls `sendVerificationEmail` best-effort before returning
`RegisterResult.Success`. The registration response is unchanged except for
`PublicUser.emailVerified` (always `false` at this point).

Wiring: `email.conf` is added to `AppConfig.load(...)` in `startServer`.
`startServer` builds `EmailService` (`EmailConfig.from` →
`EmailProviderFactory.fromConfig` → `EmailService`) and
`EmailVerificationConfig`, passing them through `appModule` → `configureRouting`
→ `AuthRouteHandler`, which constructs `EmailVerificationService`. The default
`provider = "log"` (`LogOnlyEmailProvider`) records-but-does-not-transmit, so
tests and dev exercise the full path and the `email_sends` ledger without
sending mail.

### Error Handling / Edge Cases

- **Single-use under concurrency** — `consume` is a conditional
  `UPDATE …
  RETURNING`; exactly one concurrent caller matches the row, the
  rest get zero rows. No double-spend, no application lock.
- **Failed verify classification** — zero-row `consume` triggers a
  `findByTokenHash`: absent → `InvalidToken`; `consumed_at` set →
  `AlreadyConsumed`; `expires_at` past → `Expired`.
- **Already verified** — `markEmailVerified` preserves the first verification
  time (`WHERE email_verified_at IS NULL`, else returns the existing user).
  Verify burns siblings, so a second outstanding token cannot re-mark.
- **Best-effort email** — registration and resend persist the token
  transactionally; the post-commit send failure is logged, not propagated.
  `sendVerificationEmail` builds `EmailSubject`/`EmailBody` from fixed literals
  via their `create()` factories; an `Invalid` construction result folds into
  the same `Result.failure` best-effort path as a provider send rejection
  (logged, not thrown). Registration succeeds; the user can resend. (Best-effort
  vs all-or-nothing is deliberate: a transient SES outage must not block account
  creation.)
- **Resend abuse** — each resend invalidates prior outstanding tokens, bounding
  live tokens per user to one. Per-user send rate-limiting is out of scope.
- **Token in transit** — the raw token rides only in the email link; everything
  at rest is the SHA-256 hash. The POST endpoint (not a clickable GET) keeps the
  single-use token off email-scanner prefetch paths and out of access logs.

### Dependencies

- `service` and `rest-server` add an `implementation(project(":email"))`
  dependency (neither depends on `email` today).
- No new third-party libraries. `email_sends`, `EmailService`,
  `EmailProviderFactory`, `TokenGenerator`, `TokenHash`, and the DAO capability
  interfaces in `db/src/main/kotlin/ed/unicoach/db/dao/Dao.kt` already exist.
- `api-specs/openapi.yaml` gains the two endpoints and the `emailVerified`
  property so the existing contract-fuzz surface stays complete.

## Tests

### `db` module — `VerificationTokensDaoTest` (new)

- `create` inserts a row returning a `VerificationToken` with
  `consumedAt == null` and the supplied `expiresAt`.
- `create` enforces the unique `token_hash` index (second insert of the same
  hash fails with a constraint violation).
- `consume` on a fresh, unexpired token returns the row and sets `consumed_at`.
- `consume` on an already-consumed token returns no row (NotFound).
- `consume` on an expired token returns no row (NotFound).
- `consume` is single-use: two sequential consumes — first succeeds, second
  fails.
- `findByTokenHash` returns the row regardless of consumed/expired state, and
  fails for an unknown hash.
- `consumeAllForUser` sets `consumed_at` on all of a user's unconsumed tokens
  and returns the count; already-consumed rows are untouched.

### `db` module — `UsersDaoTest` (extend)

- `markEmailVerified` flips `email_verified_at` from null to non-null,
  increments `version`, and writes a `users_versions` row carrying
  `email_verified_at`.
- `markEmailVerified` on an already-verified user is idempotent: returns the
  user with the original `email_verified_at`, no second version bump.
- `findById`/`mapUser` round-trips `emailVerifiedAt` (null and non-null).

### `service` module — `EmailVerificationServiceTest` (new)

Uses a stub/log `EmailProvider` to assert send attempts.

- `issueToken` inserts a token whose hash matches `TokenHash.fromRawToken(raw)`
  and whose `expiresAt ≈ now + tokenTtl`.
- `verify` happy path: marks the user verified, returns `Success(user)` with
  `emailVerifiedAt != null`, and burns sibling tokens.
- `verify` with an unknown token → `InvalidToken`.
- `verify` with an expired token → `Expired`.
- `verify` with a consumed token → `AlreadyConsumed`.
- `resend` for an unverified user invalidates the prior token and issues a new
  one (old hash no longer consumable, new hash consumable) and attempts a send.
- `resend` for a verified user → `AlreadyVerified`, issues no token, sends
  nothing.
- `sendVerificationEmail` builds the link `verifyUrlBase + "?token=" + raw` and
  a provider rejection yields `Result.failure` without throwing.

### `service` module — `AuthServiceTest` (extend)

- `register` success inserts exactly one `verification_tokens` row for the new
  user and writes one `email_sends` row (log provider).
- `register` returns `Success` even when the email provider rejects the send
  (best-effort): the user and verification token still exist.

### `service` module — `EmailVerificationConfigTest` (new)

- `from` reads `tokenTtl` and `verifyUrlBase`; missing key → `Result.failure`.

### `rest-server` module — `AuthRoutingTest` / `EmailVerificationRoutingTest`

- `register` response `PublicUser.emailVerified == false`; `me` and `login`
  likewise report `false` before verification.
- Full loop: register → read the issued token from `verification_tokens` →
  `POST /verify-email` returns `200` and its
  `VerifyEmailResponse.user.emailVerified
  == true` → a subsequent `me`
  likewise reports `emailVerified == true`.
- `POST /verify-email` with a bogus token → `400 invalid_token`.
- `POST /verify-email` re-using a consumed token → `400 token_already_used`.
- `POST /verify-email` with an expired token (inserted with past `expires_at`) →
  `400 token_expired`.
- `POST /resend-verification` authenticated → `204`, and a new
  `verification_tokens` row exists; the prior token is consumed.
- `POST /resend-verification` with no session → `401 unauthorized`.
- `POST /resend-verification` for an already-verified user → `204`, no new
  `email_sends` row.
- `GET`/other methods on both routes → `405` with `Allow: POST`.

## Implementation Plan

Run all Kotlin/DB commands inside the Nix dev shell. Verify with
`nix develop -c bin/test --force <module>` (plain runs may be all-cache no-ops).

1. **Schema: marker + token table.** Add
   `db/schema/0013.add-users-email-verified-at.sql` and
   `db/schema/0014.create-verification-tokens.sql` as specified.
   - Verify: `nix develop -c bin/test db` (harness re-inits + migrates the test
     DB; migration failures surface here).
     `nix develop -c psql "$DATABASE_URL" -c '\d verification_tokens'`.

2. **db domain types.** Add `VerificationTokenId`, `VerificationToken`,
   `NewVerificationToken`; add `emailVerifiedAt` to `User` and `UserVersion`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`.

3. **db DAOs.** Update `UsersDao` (`mapUser`, version mapper, full-row restore,
   new `markEmailVerified`); add `VerificationTokensDao`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`.

4. **db tests.** Add `VerificationTokensDaoTest`; extend `UsersDaoTest`.
   - Verify: `nix develop -c bin/test --force db`.

5. **service config + dependency.** Add `implementation(project(":email"))` to
   `service/build.gradle.kts`; add the `emailVerification` block to
   `service/src/main/resources/service.conf`; add `EmailVerificationConfig`.
   - Verify: `nix develop -c ./gradlew :service:compileKotlin`.

6. **service: EmailVerificationService + result types.** Add
   `EmailVerificationService`, `VerifyEmailResult`, `ResendResult`.
   - Verify: `nix develop -c ./gradlew :service:compileKotlin`.

7. **service: register hook.** Inject `EmailVerificationService` into
   `AuthService`; issue the token in the existing transaction and send
   post-commit best-effort.
   - Verify: `nix develop -c ./gradlew :service:compileKotlin`.

8. **service tests.** Add `EmailVerificationServiceTest`,
   `EmailVerificationConfigTest`; extend `AuthServiceTest`.
   - Verify: `nix develop -c bin/test --force service`.

9. **rest-server: DTOs.** Add `emailVerified` to `PublicUser`; add
   `VerifyEmailRequest`, `VerifyEmailResponse`; update the three existing
   `PublicUser` construction sites in `AuthRoutes.kt` (register, login, `me`) to
   pass the new field. The fourth site (the `verify-email` handler) is added in
   step 11.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`.

10. **rest-server: wiring.** Add `implementation(project(":email"))` to
    `rest-server/build.gradle.kts`; add `email.conf` to `AppConfig.load`; build
    `EmailService` + `EmailVerificationConfig` in `startServer`; thread through
    `appModule` → `configureRouting` → `AuthRouteHandler`.
    - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`.

11. **rest-server: routes.** Add `verify-email` and `resend-verification` POST
    routes + handlers (with `rejectUnsupportedMethods(Post)`) to
    `AuthRouteHandler`.
    - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`.

12. **rest-server tests.** Extend `AuthRoutingTest` / add
    `EmailVerificationRoutingTest`.
    - Verify: `nix develop -c bin/test --force rest-server`.

13. **OpenAPI contract.** Add both paths and the `emailVerified` property to
    `api-specs/openapi.yaml`.
    - Verify (parse the spec with Deno's YAML parser — there is no PyYAML in the
      dev shell):

      ```sh
      nix develop -c deno run --allow-read - <<'EOF'
      import { parse } from "jsr:@std/yaml";
      const text = await Deno.readTextFile("api-specs/openapi.yaml");
      const doc = parse(text) as Record<string, any>;
      const paths = doc.paths ?? {};
      for (
        const p of ["/api/v1/auth/verify-email", "/api/v1/auth/resend-verification"]
      ) {
        if (!paths[p]?.post) throw new Error(`missing POST ${p}`);
      }
      const user = doc.components?.schemas?.PublicUser?.properties ?? {};
      if (!user.emailVerified) throw new Error("PublicUser.emailVerified missing");
      console.log("openapi.yaml OK");
      EOF
      ```

    - Then run the existing contract-fuzz suite over the updated spec.

14. **Full gate.** `nix develop -c bin/test check` (tests + ktlint).

## Files Modified

Created:

- `db/schema/0013.add-users-email-verified-at.sql`
- `db/schema/0014.create-verification-tokens.sql`
- `db/src/main/kotlin/ed/unicoach/db/models/VerificationTokenId.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/VerificationToken.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewVerificationToken.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/VerificationTokensDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/VerificationTokensDaoTest.kt`
- `service/src/main/kotlin/ed/unicoach/auth/EmailVerificationService.kt`
- `service/src/main/kotlin/ed/unicoach/auth/EmailVerificationConfig.kt`
- `service/src/main/kotlin/ed/unicoach/auth/VerifyEmailResult.kt`
- `service/src/main/kotlin/ed/unicoach/auth/ResendResult.kt`
- `service/src/test/kotlin/ed/unicoach/auth/EmailVerificationServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/auth/EmailVerificationConfigTest.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/VerifyEmailRequest.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/VerifyEmailResponse.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/EmailVerificationRoutingTest.kt`

Modified:

- `db/src/main/kotlin/ed/unicoach/db/models/User.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/UserVersion.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt`
- `service/build.gradle.kts`
- `service/src/main/resources/service.conf`
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt`
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt`
- `rest-server/build.gradle.kts`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/PublicUser.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt`
- `api-specs/openapi.yaml`
