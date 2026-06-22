# RFC 64: Google SSO Login

## Executive Summary

This RFC adds Google sign-in as a federated login mechanism and reshapes the
authentication model so a single user can hold multiple login mechanisms. The
iOS client obtains a Google ID token natively and POSTs it to a new
`POST /api/v1/auth/google` endpoint; the backend verifies the token's RS256
signature and claims against Google's JWKS and establishes a session.

The current model stores a single SSO credential in `users.sso_provider_id`
alongside a closed `AuthMethod` (`Password | SSO | Both`), which cannot
represent more than one federated identity. This RFC replaces that column with a
`user_auth_identities` table — one append-only row per federated identity, keyed
by `(provider, subject)` — keeps the password credential on `users` as a
nullable `password_hash`, and deletes `AuthMethod` and `SsoProviderId`. The "at
least one auth method" guarantee moves from a column `CHECK` to an
application-level invariant, because the credential now spans two tables and a
row-local `CHECK` can no longer express it.

A new nullable `sessions.login_method` records how each session authenticated
(`password` | `google`).

Account linking is by verified email: when a Google sign-in's `email_verified`
claim is true and an active user already holds that email, the Google identity
is attached to that user; otherwise a new user is created. Because password
registration has no email-verification flow, linking carries a documented
residual risk (a pre-seeded password account retains co-access); closing it is
deferred to a future email-verification effort.

Scope is backend-only; iOS integration is a separate RFC.

## Detailed Design

### Data Models

#### `user_auth_identities` (new, append-only)

One row records the immutable fact that a federated `(provider, subject)`
belongs to a user, established at a point in time. It follows the append-only
log-table pattern, not the mutable-entity pattern: rows are inserted once and
never updated or deleted by application code. It therefore carries **no**
`version`, `updated_at`, `deleted_at`, or version-history table.

```sql
CREATE TABLE user_auth_identities (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider TEXT NOT NULL,
  subject TEXT NOT NULL,
  email TEXT NOT NULL,
  email_verified BOOLEAN NOT NULL,
  CONSTRAINT user_auth_identities_provider_check CHECK (provider IN ('google')),
  CONSTRAINT user_auth_identities_subject_length_check CHECK (length(subject) <= 255),
  CONSTRAINT user_auth_identities_subject_not_empty_check CHECK (length(trim(subject)) > 0),
  CONSTRAINT user_auth_identities_email_length_check CHECK (length(email) <= 254),
  CONSTRAINT user_auth_identities_email_not_empty_check CHECK (length(trim(email)) > 0),
  CONSTRAINT user_auth_identities_email_lowercase_check CHECK (email = LOWER(email)),
  CONSTRAINT user_auth_identities_email_format_check CHECK (email LIKE '%@%')
);

CREATE UNIQUE INDEX user_auth_identities_provider_subject_idx
  ON user_auth_identities (provider, subject);
CREATE INDEX user_auth_identities_user_id_idx
  ON user_auth_identities (user_id);
```

- `provider` is a closed `CHECK` set (`'google'` only). Adding a provider is a
  one-line `CHECK`-widening migration, accepted as the cost of a strong
  enumeration guarantee at the database level.
- `subject` is the provider's stable subject identifier (Google's `sub` claim) —
  the only key used to resolve a returning login. `UNIQUE(provider, subject)`
  permits at most one user per federated identity; it does not restrict a user
  to one identity per provider (a user may legitimately link two Google
  accounts, distinct `sub`s).
- `email` and `email_verified` are **provenance only** — the claims Google
  asserted at the time the row was created. They are never re-synced and never
  read on the login path; lookup is strictly by `(provider, subject)`. They
  carry no uniqueness or foreign-key guarantee and may diverge from
  `users.email` after the account email is edited, after the Google-side email
  changes, or across multiple identities.
- `created_at` is the logical fact time, `row_created_at` the physical insert
  time — the same dual-timestamp convention `email_sends` uses.
- Append-only is enforced by reusing the shared `prevent_log_update()` /
  `prevent_log_delete()` guards (from
  `db/schema/0006.create-coaching-conversations.sql`, already used by
  `email_sends`): `BEFORE UPDATE` and `BEFORE DELETE` triggers raising `P0001`.
  As on `convo_requests`/`convo_responses`, the `BEFORE DELETE` guard and the
  `ON DELETE CASCADE` FK coexist harmlessly: the parent `users` row is
  `prevent_physical_delete`-protected and only ever soft-deleted, so the cascade
  never fires. CASCADE is retained only for the hypothetical hard-delete case.

Domain types (module `db`):

```kotlin
@JvmInline value class AuthIdentityId(val value: UUID) : Id   // override val asString get() = value.toString(), per UserId

enum class AuthProvider(val wire: String) { GOOGLE("google") }

@JvmInline value class ProviderSubject private constructor(val value: String) {
  companion object { fun create(value: String): ValidationResult<ProviderSubject> }  // trim; reject blank / > 255
}

data class AuthIdentity(
  override val id: AuthIdentityId,
  val userId: UserId,
  val provider: AuthProvider,
  val subject: ProviderSubject,
  val email: EmailAddress,
  val emailVerified: Boolean,
  override val createdAt: Instant,
) : Identifiable<AuthIdentityId>, Created

data class NewAuthIdentity(
  val userId: UserId,
  val provider: AuthProvider,
  val subject: ProviderSubject,
  val email: EmailAddress,
  val emailVerified: Boolean,
)
```

#### `users` (modified — credential reshape)

The single-credential `AuthMethod` model is removed. The password credential
remains on the row as a nullable column; federated credentials move entirely to
`user_auth_identities`.

- Drop `users_auth_method_check`, the `users_sso_provider_id_idx` partial index,
  and the `sso_provider_id` length/not-empty/trim constraints.
- Drop column `sso_provider_id` from `users` **and** `users_versions`, and
  recreate the `log_user_version()` trigger function with `sso_provider_id`
  removed but **every other current column preserved** — notably `is_admin`,
  which migration `0012` added to the function. The recreate copies `id`,
  `version`, the four timestamps, `deleted_at`, `email`, `name`, `display_name`,
  `password_hash`, and `is_admin` (i.e. the `0012` column list minus
  `sso_provider_id`).
- `password_hash` stays as today (nullable `TEXT`, ≤255).

The "every user has at least one auth method" guarantee can no longer be a
row-local `CHECK` (an SSO-only user's sole credential is a row in another table,
inserted after the user row). It becomes an application invariant: `AuthService`
creates a user and its first credential atomically, and no flow in this RFC ever
nulls a `password_hash` or deletes an identity.

```kotlin
data class User( /* … */ val passwordHash: PasswordHash?, /* … */ )   // replaces authMethod: AuthMethod
data class NewUser( /* … */ val passwordHash: PasswordHash?, val isAdmin: Boolean = false )
data class UserVersion( /* … */ val passwordHash: PasswordHash?, /* … */ )
```

`UsersDao` reconstructs `passwordHash` directly from the nullable column; the
`AuthMethod` reconstruction/bind helpers (`readAuthMethod`, `bindAuthMethod`,
`bindAuthMethodColumn`) and `CorruptPersistedAuthMethodException` are removed.
`UserEdit` is unchanged in shape (it already excludes credentials); only its doc
comment is updated to drop the `sso_provider_id` reference.

#### `sessions` (modified — login method)

A new nullable column records how a session authenticated.

```sql
ALTER TABLE sessions ADD COLUMN login_method TEXT NULL;
-- backfill existing authenticated sessions before adding the paired constraint
UPDATE sessions SET login_method = 'password' WHERE user_id IS NOT NULL;
ALTER TABLE sessions
  ADD CONSTRAINT sessions_login_method_check CHECK (login_method IN ('password', 'google')),
  ADD CONSTRAINT sessions_login_method_presence_check
    CHECK ((user_id IS NULL) = (login_method IS NULL));
```

`login_method` is `NULL` exactly for anonymous (pre-auth) sessions and non-null
for user-bound sessions — the paired `presence_check` ties the two columns so an
authenticated session can never lack a method and an anonymous one can never
carry one.

```kotlin
enum class LoginMethod(val wire: String) { PASSWORD("password"), GOOGLE("google") }

data class Session( /* … */ val loginMethod: LoginMethod?, /* … */ )
data class NewSession( /* … */ val loginMethod: LoginMethod? = null )   // null = anonymous
```

`SessionsDao.create` binds `login_method`; `SessionsDao.remintToken` (the
anonymous→authenticated path used by `register`) gains a `newLoginMethod`
parameter. `login_method` is stored on `Session` and is not surfaced through
`/auth/me` in this RFC.

### Verification (module `service`)

Google ID-token verification is hidden behind an interface so it is swappable
and offline-testable, mirroring the chat-provider factory pattern.

```kotlin
data class GoogleIdentity(
  val subject: String,
  val email: String,
  val emailVerified: Boolean,
  val name: String?,
)

interface GoogleTokenVerifier {
  fun verify(idToken: String): Result<GoogleIdentity>   // failure: GoogleTokenInvalidException | GoogleTokenUnavailableException
}
```

- `JwksGoogleTokenVerifier` (production) uses `com.auth0:java-jwt` to verify the
  RS256 signature and `com.auth0:jwks-rsa`'s caching `JwkProvider` to fetch
  Google's signing keys from the configured JWKS URI. It checks: signature;
  `iss` ∈ configured issuers (`accounts.google.com`,
  `https://accounts.google.com`); `aud` ∈ configured client IDs; `exp`/`iat`
  within the configured clock skew. It reads `sub`, `email`, `email_verified`,
  `name`. A JWKS-fetch/transport failure surfaces as
  `GoogleTokenUnavailableException` (a `TransientError`); any signature/claim
  failure surfaces as `GoogleTokenInvalidException` (a `PermanentError`).
- `StubGoogleTokenVerifier` (test/dev) deterministically decodes a documented
  fake token format into a `GoogleIdentity` without network access. Selected by
  configuration; never wired in production.
- `GoogleTokenVerifierFactory.fromConfig(GoogleAuthConfig)` returns the
  production or stub verifier by `provider` key and fails fast on missing
  required configuration (`clientIds`), matching the chat-provider boot
  contract.

### API Contracts

#### `POST /api/v1/auth/google`

Sits beside the existing `/api/v1/auth/*` routes, under the same client-key gate
and serialization. Request and success response reuse the existing
`LoginResponse`/`PublicUser` shapes; success sets the session cookie exactly as
`login` does (`HttpOnly`, `SameSite=Strict`, `path=/`, `secure` per config).

```
POST /api/v1/auth/google
Request:  { "idToken": "<google id token>" }            // GoogleLoginRequest
200 OK:   { "user": { "id", "email", "name" } }          // LoginResponse + Set-Cookie
```

Both first-time signup and returning login return `200` with the cookie; the
response does not distinguish them, so account existence is not disclosed.

Failure responses follow the auth-route convention (lowercase error codes):

| Condition                                                       | Status | `error` code          |
| --------------------------------------------------------------- | ------ | --------------------- |
| Malformed / expired / wrong `aud` / wrong `iss` / bad signature | 401    | `unauthorized`        |
| Token valid but `email_verified` is false                       | 403    | `email_not_verified`  |
| Matched/linked user is soft-deleted                             | 403    | `account_disabled`    |
| Google JWKS endpoint unreachable (transient)                    | 503    | `service_unavailable` |

#### Sign-in flow (`AuthService.loginWithGoogle`)

```kotlin
suspend fun loginWithGoogle(
  idToken: String,
  oldCookieToken: String?,
  sessionExpirationSeconds: Long,
  userAgent: String?,
  initialIp: String?,
): Result<GoogleLoginResult>

sealed interface GoogleLoginResult {
  data class Success(val user: User, val token: String) : GoogleLoginResult
  data object InvalidToken : GoogleLoginResult
  data object EmailNotVerified : GoogleLoginResult
  data object AccountDisabled : GoogleLoginResult
  data object VerificationUnavailable : GoogleLoginResult
}
```

Ordered logic:

1. `verify(idToken)`. On `GoogleTokenInvalidException` → `InvalidToken`; on
   `GoogleTokenUnavailableException` → `VerificationUnavailable`.
2. If `!emailVerified` → `EmailNotVerified` (hard gate; nothing is created or
   linked).
3. In one `database.withConnection` transaction (which commits or rolls back the
   whole block atomically):
   1. `UserAuthIdentitiesDao.findByProviderAndSubject(GOOGLE, sub)`. If found,
      load the user with `SoftDeleteScope.ALL`; if soft-deleted →
      `AccountDisabled`, else this is a **returning login**.
   2. Else `UsersDao.findByEmail(google.email)` (active only). If found →
      **link**: insert a `NewAuthIdentity` for that user. If absent →
      **create**:
      `UsersDao.create(NewUser(email = google.email, name = derivedName,
      displayName = null, passwordHash = null))`,
      then insert the identity. `derivedName` is the `name` claim, falling back
      to the email local-part when the claim is absent or blank.
   3. Mint a token; if `oldCookieToken` resolves to a live session, revoke it
      (mirroring `login`); create a session with `loginMethod = GOOGLE`.
4. `Success(user, token)`.

Because step 3 runs inside one Postgres transaction, a
`UNIQUE(provider,
subject)` (or `users_email_unique_active_idx`) violation from
a concurrent first login aborts the _entire_ transaction — an in-transaction
re-read is impossible (`current transaction is aborted`). The unique-violation
recovery therefore retries the **whole** `withConnection` block, not a statement
within it: on catching the `23505`-derived violation
(`ConstraintViolationException` from the identity index, or
`DuplicateEmailException` from `users_email_unique_active_idx` on the email
race), `loginWithGoogle` re-enters step 3 from the top in a fresh transaction,
where `findByProviderAndSubject` now finds the row the winner committed and
proceeds as a returning login. The retry is bounded (a single retry suffices,
since after the winner commits the second attempt resolves deterministically); a
second failure surfaces as a `500`.

### Error Handling / Edge Cases

- **Pre-seeded password account (documented residual risk).** Linking trusts
  Google's `email_verified` to prove mailbox ownership but cannot prove the
  pre-existing password account's email, since password registration has no
  verification step. An attacker who registered a password account under a
  victim's email before the victim's first Google sign-in retains co-access
  after linking (shared access, not lockout — the victim always reaches the
  account via Google). This is accepted as a pre-launch limitation; the closure
  path is an email-verification flow (or password-proof-on-link), out of scope
  here.
- **Concurrent first login (same new `sub`).** Two simultaneous requests can
  both reach the create/link branch; one loses the `UNIQUE(provider, subject)`
  race. The loser's whole transaction aborts on the `23505` violation; the loser
  retries the entire `withConnection` block once, where the now-committed
  identity resolves as a returning login (see the sign-in flow). The same retry
  also absorbs the `users_email_unique_active_idx` race when two unknown-`sub`
  sign-ins share one new email.
- **`email_verified` absent.** Treated as false (gate fails).
- **`name` claim absent.** New-user creation derives the name from the email
  local-part; if that cannot form a valid `PersonName`, creation fails and the
  error bubbles as a `500` (no silent placeholder).
- **Google email changed since linking.** Login still resolves by `sub`; the
  stored identity `email` is intentionally not re-synced.
- **Anonymous-session integrity.** The `sessions_login_method_presence_check`
  rejects any attempt to set a method without a user or to bind a user without a
  method, at the database level.

### Dependencies

- `com.auth0:jwks-rsa` (latest stable `0.24.x`) added to the version catalog
  (`com.auth0:java-jwt` `4.4.0` is already present). Both are added to
  `service`.
- No new outbound infrastructure beyond HTTPS access to Google's JWKS endpoint,
  reached by `jwks-rsa`'s own HTTP client.

### Configuration

A new `auth.google` section in `service.conf`, loaded via
`GoogleAuthConfig.from(config)` (same `Result`-returning, fail-fast contract as
`SessionConfig`/`ChatConfig`):

```hocon
auth {
  google {
    provider = "google"                 # "google" | "stub"
    provider = ${?GOOGLE_AUTH_PROVIDER}
    clientIds = []                       # accepted audiences; required when provider = "google"
    clientIds = ${?GOOGLE_CLIENT_IDS}    # comma-separated
    issuers = ["accounts.google.com", "https://accounts.google.com"]
    jwksUri = "https://www.googleapis.com/oauth2/v3/certs"
    jwksUri = ${?GOOGLE_JWKS_URI}
    clockSkew = "60s"
    connectTimeoutMs = 10000
    readTimeoutMs = 10000
  }
}
```

```kotlin
data class GoogleAuthConfig(
  val provider: String,
  val clientIds: List<String>,
  val issuers: List<String>,
  val jwksUri: String,
  val clockSkew: Duration,
  val connectTimeoutMs: Long,
  val readTimeoutMs: Long,
) { companion object { fun from(config: Config): Result<GoogleAuthConfig> } }
```

`Application.appModule` builds `GoogleAuthConfig`, constructs the verifier via
`GoogleTokenVerifierFactory.fromConfig`, and passes it as a new `AuthService`
constructor argument.

## Tests

### `db` — `UserAuthIdentitiesDaoTest`

- `create` inserts and returns a populated `AuthIdentity` with a generated id
  and `createdAt`.
- `create` twice with the same `(provider, subject)` fails with a constraint
  violation.
- `create` allows the same `subject` under distinct providers (forward-compat;
  asserted via direct SQL once a second provider exists — for now, two rows for
  one `user_id` with distinct `subject`s succeed).
- `findByProviderAndSubject` returns the row when present and
  `NotFoundException` when absent.
- `findByProviderAndSubject` is unaffected by `users` soft-delete (the identity
  row persists; user-state checks are the caller's responsibility).
- `listByUser` returns all identities for a user, none for an unknown user.
- An `UPDATE` or `DELETE` against a row is rejected by the append-only trigger.
- `ON DELETE CASCADE`: a hard-deleted user (direct SQL) removes its identities.

### `db` — `UsersDaoTest` (modified)

- `create`/`findById`/`findByEmail` round-trip a password user
  (`passwordHash != null`) and an SSO-only user (`passwordHash == null`).
- `update` (via `UserEdit`) never alters `password_hash`.
- `revertToVersion`/`updatePhysicalRecord` restore `password_hash` across
  versions (no `sso_provider_id` column referenced anywhere).
- Version history (`users_versions`) reconstructs without `sso_provider_id`.
- Remove the test asserting `CorruptPersistedAuthMethodException`.

### `db` — `SessionsDaoTest` (modified)

- `create` with `loginMethod = PASSWORD`/`GOOGLE` round-trips the column.
- `create` with `userId != null` and `loginMethod = null` is rejected by
  `sessions_login_method_presence_check`.
- `create` with `userId == null` and a non-null `loginMethod` is rejected by the
  same constraint.
- `remintToken` sets `login_method` on the reminted (formerly anonymous) row.
- `create` with `loginMethod = null` and `userId == null` (anonymous) succeeds.

### `service` — `GoogleTokenVerifierTest`

- `StubGoogleTokenVerifier` decodes a valid fake token to the expected
  `GoogleIdentity`.
- Stub surfaces an invalid token as `GoogleTokenInvalidException`.
- `GoogleTokenVerifierFactory.fromConfig` returns the stub for `provider="stub"`
  and the JWKS verifier for `provider="google"`, and fails when `clientIds` is
  empty under `provider="google"`.

### `service` — `GoogleAuthConfigTest`

- Parses a full `auth.google` block; applies documented defaults for `issuers`,
  `jwksUri`, `clockSkew`, timeouts.
- Splits comma-separated `clientIds`.
- Fails when the `auth.google` section is absent.

### `service` — `GoogleAuthServiceTest` (stub verifier)

- **New user**: unknown `sub`, unknown email → creates a user
  (`passwordHash == null`), one Google identity, a session with
  `loginMethod = GOOGLE`; `Success`.
- **Returning login**: known `(google, sub)` → no new user/identity; new
  session; `Success` for the same user id.
- **Link**: unknown `sub`, email matches an existing password user → inserts an
  identity onto that user; the user keeps its `password_hash`; `Success`; the
  user now has both mechanisms.
- **Email-not-verified**: `email_verified=false` → `EmailNotVerified`; no user,
  identity, or session created.
- **Account disabled**: identity resolves to a soft-deleted user →
  `AccountDisabled`.
- **Invalid token** → `InvalidToken`; nothing created.
- **Verification unavailable**: verifier returns the transient failure →
  `VerificationUnavailable`.
- **Name fallback**: `name` claim absent → user name derived from email
  local-part.
- **Old cookie revoked**: a live `oldCookieToken` is revoked and a fresh session
  issued.
- **Concurrent first login (same `sub`)**: two `loginWithGoogle` calls for the
  same new `sub` yield one identity and two successes (the loser's transaction
  aborts on the identity-index violation, retries the whole block, and falls
  through to returning login).
- **Concurrent first login (same new email, distinct `sub`s)**: two
  `loginWithGoogle` calls with unknown `sub`s sharing one new email yield one
  user; the loser's transaction aborts on `users_email_unique_active_idx`,
  retries, and links its identity onto the winner's user (two identities, one
  user, two successes).

### `service` — `AuthServiceTest`, `SessionCleanupTest`, `StudentServiceTest` (modified)

- Adapt all `NewUser(authMethod = …)` to `NewUser(passwordHash = …)` and all
  user-bound `NewSession(…)` to set `loginMethod`.
- `register`/`login` create sessions with `loginMethod = PASSWORD` (new
  assertions).

### `rest-server` — `GoogleAuthRoutingTest` (new, stub verifier via config)

- `200` + session cookie for a valid token (new user and returning user).
- `403 email_not_verified` for an unverified-email token.
- `401 unauthorized` for an invalid token.
- `503 service_unavailable` when the verifier reports transient failure.
- Non-POST methods are rejected (`405`) like sibling auth routes.
- The issued cookie authenticates a subsequent `/api/v1/auth/me`.

### `rest-server` — `StatusPagesTest` (modified)

- Remove the `CorruptPersistedAuthMethodException → 500` case.

### `rest-server` — contract fuzz

- The authenticated-surface fuzz suite passes against the new endpoint after the
  OpenAPI document is updated (no undocumented status/shape).

### `admin-server` / `net` (modified)

- `AdminTestSupport`/`UsersResource`/`UsersResourceTest`: adapt `NewUser`
  construction; existing admin user/list behaviour unchanged.
- `SessionExpiryHandlerTest`: adapt user-bound `NewSession` to set
  `loginMethod`.

## Implementation Plan

Each step leaves the tree compiling and green. Commands run inside the Nix dev
shell. Independent green runs use `--force` (a plain `bin/test` can be an
all-cache no-op).

### Step 1 — Session `login_method`

1. Add migration `db/schema/0013.add-sessions-login-method.sql` (column,
   backfill, both `CHECK`s).
2. Add `LoginMethod`; add `loginMethod` to `Session` and `NewSession` (default
   `null`).
3. Update `SessionsDao` (`mapSession` read, `create` bind, `remintToken`
   `newLoginMethod` parameter).
4. Update callers that create user-bound sessions to pass a method:
   `AuthService.register`/`login` (`PASSWORD`), and tests (`SessionsDaoTest`,
   `SessionCleanupTest`, `net/.../SessionExpiryHandlerTest`).

Verify:

```
nix develop -c bin/test db --force
nix develop -c bin/test service --force
nix develop -c bin/test net --force
```

### Step 2 — Credential reshape (remove `AuthMethod` / `sso_provider_id`)

1. Add migration `db/schema/0014.drop-users-sso-provider-id.sql`: a `DO` guard
   that `RAISE`s if any `sso_provider_id IS NOT NULL` row exists; drop
   `users_auth_method_check`, `users_sso_provider_id_idx`, and the
   `sso_provider_id` constraints; drop the column from `users` and
   `users_versions`; recreate `log_user_version()` with `sso_provider_id`
   removed but `is_admin` and all other current columns preserved (the `0012`
   column list minus `sso_provider_id`).
2. Replace `authMethod: AuthMethod` with `passwordHash: PasswordHash?` in
   `User`, `NewUser`, `UserVersion`; update `UserEdit` doc comment.
3. Rewrite `UsersDao` `mapUser`/`mapUserVersion`/`create`/`updateFullRow`/
   `revertToVersion` to read/bind `password_hash` directly; delete the
   `AuthMethod` helpers.
4. Delete `db/.../models/AuthMethod.kt` and `db/.../models/SsoProviderId.kt`;
   remove `CorruptPersistedAuthMethodException` from `DaoExceptions`.
5. Update `StatusPages` (drop the corrupt-auth case) and remaining `NewUser`
   callers: `admin-server`
   `UsersResource`/`AdminTestSupport`/`UsersResourceTest`, `UsersDaoTest`,
   `AuthServiceTest`, `StudentServiceTest`, `StatusPagesTest`.

Verify:

```
nix develop -c ./gradlew :db:compileKotlin :service:compileKotlin :rest-server:compileKotlin :admin-server:compileKotlin
nix develop -c bin/test check --force
```

### Step 3 — `user_auth_identities` table + DAO

1. Add migration `db/schema/0015.create-user-auth-identities.sql` (table,
   indexes, append-only guard trigger).
2. Add `AuthIdentityId`, `AuthProvider`, `ProviderSubject`, `AuthIdentity`,
   `NewAuthIdentity`.
3. Add `UserAuthIdentitiesDao` (an `object`, composing the
   `Creatable<NewAuthIdentity, AuthIdentity>` capability interface from `Dao.kt`
   for `create`, plus the bespoke `findByProviderAndSubject` and `listByUser`
   queries — `findByProviderAndSubject`/`listByUser` are not `findById`/`list`
   shapes, so they stay outside the capability interfaces).
4. Add `UserAuthIdentitiesDaoTest`.

Verify:

```
nix develop -c bin/test db --force
```

### Step 4 — Google token verification + config

1. Add `jwks-rsa` to `gradle/libs.versions.toml`; add `java-jwt` + `jwks-rsa` to
   `service/build.gradle.kts`.
2. Add `auth.google` to `service/src/main/resources/service.conf`; add
   `GoogleAuthConfig`.
3. Add `GoogleIdentity`, `GoogleTokenVerifier` (+ `GoogleTokenInvalidException`,
   `GoogleTokenUnavailableException`), `JwksGoogleTokenVerifier`,
   `StubGoogleTokenVerifier`, `GoogleTokenVerifierFactory`.
4. Add `GoogleTokenVerifierTest`, `GoogleAuthConfigTest`.

Verify:

```
nix develop -c bin/test service --force
```

### Step 5 — `AuthService.loginWithGoogle`

1. Add `GoogleLoginResult`; add the `googleTokenVerifier` constructor argument.
2. Implement `loginWithGoogle` (verify → gate → resolve/link/create → session),
   including the unique-violation re-read.
3. Add `GoogleAuthServiceTest` (stub verifier).

Verify:

```
nix develop -c bin/test service --force
```

### Step 6 — REST endpoint + wiring

1. Add `GoogleLoginRequest`.
2. Add the `/google` route and outcome mapping in `AuthRoutes`.
3. Build `GoogleAuthConfig` + verifier in `Application.appModule`; pass to
   `AuthService`.
4. Add `GoogleAuthRoutingTest` (stub verifier selected via test config).

Verify:

```
nix develop -c bin/test rest-server --force
```

### Step 7 — OpenAPI + fuzz

1. Document `POST /api/v1/auth/google` in `api-specs/openapi.yaml` (request
   schema, `200`/`401`/`403`/`503`).
2. Validate the document and run the authenticated fuzz surface.

Verify:

```
nix develop -c deno run --allow-read - <<'EOF'
import { parse } from "jsr:@std/yaml";
parse(await Deno.readTextFile("api-specs/openapi.yaml"));
console.log("openapi.yaml parses");
EOF
nix develop -c bin/test rest-server --force
nix develop -c bin/test check --force
```

## Files Modified

### Created

- `db/schema/0013.add-sessions-login-method.sql`
- `db/schema/0014.drop-users-sso-provider-id.sql`
- `db/schema/0015.create-user-auth-identities.sql`
- `db/src/main/kotlin/ed/unicoach/db/models/LoginMethod.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/AuthProvider.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/ProviderSubject.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/AuthIdentityId.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/AuthIdentity.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewAuthIdentity.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/UserAuthIdentitiesDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/UserAuthIdentitiesDaoTest.kt`
- `service/src/main/kotlin/ed/unicoach/auth/GoogleAuthConfig.kt`
- `service/src/main/kotlin/ed/unicoach/auth/GoogleIdentity.kt`
- `service/src/main/kotlin/ed/unicoach/auth/GoogleTokenVerifier.kt`
- `service/src/main/kotlin/ed/unicoach/auth/JwksGoogleTokenVerifier.kt`
- `service/src/main/kotlin/ed/unicoach/auth/StubGoogleTokenVerifier.kt`
- `service/src/main/kotlin/ed/unicoach/auth/GoogleTokenVerifierFactory.kt`
- `service/src/main/kotlin/ed/unicoach/auth/GoogleLoginResult.kt`
- `service/src/test/kotlin/ed/unicoach/auth/GoogleAuthServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/auth/GoogleTokenVerifierTest.kt`
- `service/src/test/kotlin/ed/unicoach/auth/GoogleAuthConfigTest.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/GoogleLoginRequest.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/GoogleAuthRoutingTest.kt`

### Modified

- `db/src/main/kotlin/ed/unicoach/db/models/User.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewUser.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/UserVersion.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/UserEdit.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/Session.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewSession.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/DaoExceptions.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/SessionsDaoTest.kt`
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt`
- `service/src/main/resources/service.conf`
- `service/build.gradle.kts`
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/auth/SessionCleanupTest.kt`
- `service/src/test/kotlin/ed/unicoach/student/StudentServiceTest.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/StatusPages.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/StatusPagesTest.kt`
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/UsersResource.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/AdminTestSupport.kt`
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/UsersResourceTest.kt`
- `net/src/test/kotlin/ed/unicoach/net/handlers/SessionExpiryHandlerTest.kt`
- `gradle/libs.versions.toml`
- `api-specs/openapi.yaml`

### Deleted

- `db/src/main/kotlin/ed/unicoach/db/models/AuthMethod.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/SsoProviderId.kt`

### Out-of-band (not modified by the implementer)

- `db/src/main/kotlin/ed/unicoach/db/models/INVARIANTS.md` — its
  editable-surface invariant names `sso_provider_id` in a parenthetical. The
  invariant's substance (Edit records exclude credentials) still holds; the
  stale reference is a human-gated `invariants-writer` follow-up, not an
  implementation edit.
