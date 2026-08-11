# RFC 111: Apple SSO Login (REST API)

## Executive Summary

This RFC adds "Sign in with Apple" to the REST API: `POST /api/v1/auth/apple`
accepts `{ "idToken": ..., "name": ... }`, verifies the Apple identity token,
and links or creates the user, minting the session cookie exactly as
`POST /api/v1/auth/google` (RFC 64) does. The iOS client half is a later RFC,
mirroring the RFC 64 → RFC 90 split; the optional client-supplied `name` field
anticipates it, because Apple's token never carries a name claim.

Rather than duplicating the Google stack, this RFC generalizes it: the
Google-named verifier types become provider-agnostic (`IdTokenVerifier`,
`JwksIdTokenVerifier`, `FederatedIdentity`, …), instantiated once per provider
from per-provider config blocks (`auth.google`, `auth.apple`), and
`AuthService.loginWithGoogle` becomes a single shared
`loginWithSso(provider, …)`. Apple's differences are absorbed at the edges:
`email_verified` may arrive as the string `"true"`, the accepted audience is the
app bundle ID, and the name for a newly provisioned user falls back to the
client-supplied value.

The RFC also closes an account pre-hijacking hole in the existing shared
resolution logic, superseding RFC 64's behavior: an SSO identity now links onto
an email-matched existing user **only if that user's email is verified**
(`emailVerifiedAt` set). An unverified match rejects the login with the new
`account_email_not_verified` code instead of silently attaching the victim's
federated identity to an attacker-registered password account. The gate applies
to both providers.

Schema impact is two widened CHECK constraints (`'apple'` as a provider and
login method). No new dependencies; no infra changes.

## Detailed Design

### Generalization of the RFC 64 verifier stack

Every Google-named type in `service`'s auth verification stack is renamed
provider-agnostic; behavior is unchanged except where stated. This is the
mechanism by which the Apple path reuses — rather than copies — the
security-critical verification and resolution code.

| RFC 64 name                       | Generalized name              |
| --------------------------------- | ----------------------------- |
| `GoogleTokenVerifier`             | `IdTokenVerifier`             |
| `GoogleIdentity`                  | `FederatedIdentity`           |
| `GoogleTokenInvalidException`     | `IdTokenInvalidException`     |
| `GoogleTokenUnavailableException` | `IdTokenUnavailableException` |
| `JwksGoogleTokenVerifier`         | `JwksIdTokenVerifier`         |
| `StubGoogleTokenVerifier`         | `StubIdTokenVerifier`         |
| `DisabledGoogleTokenVerifier`     | `DisabledIdTokenVerifier`     |
| `GoogleAuthConfig`                | `SsoProviderConfig`           |
| `GoogleTokenVerifierFactory`      | `IdTokenVerifierFactory`      |
| `GoogleLoginResult`               | `SsoLoginResult`              |

The stub's documented `stub:` fake-token format and sentinel tokens are
unchanged. `DisabledIdTokenVerifier` remains the fail-closed variant for hosts
serving no SSO route; admin-web now actually wires it (see Wiring below),
replacing the `StubGoogleTokenVerifier()` it constructs today.

### `JwksIdTokenVerifier`

One JWKS verifier class serves both providers, differing only by constructor
inputs (`jwkProvider`, `issuers`, `clientIds`, `clockSkew`) — already
parameterized in the RFC 64 implementation. Two claim-reading changes:

- `email_verified` is accepted as either a JSON boolean or the strings
  `"true"`/`"false"` — Apple asserts it as a string, Google as a boolean. Any
  other value, or absence, reads as unverified.
- A verified token missing the `email` claim fails as `IdTokenInvalidException`,
  exactly like a missing `sub`. The schema requires an email everywhere
  (`users.email`, `user_auth_identities.email` NOT NULL); the iOS client
  requests the email scope, so absence indicates a misconfigured or foreign
  client.

Apple's `name`: the identity token never carries one, so
`FederatedIdentity.name` is simply null on the Apple path — no provider-
specific code.

Private-relay addresses (`@privaterelay.appleid.com`) need no special handling:
they are ordinary verified emails that never match an existing user, so they
provision a fresh user. Consequence: an existing password user who chooses "Hide
My Email" gets a second account, not a link — unavoidable, since the relay hides
the real address.

Nonce support is deliberately omitted for parity with the Google path; both
providers share the same within-expiry token-replay exposure.

### Configuration — `SsoProviderConfig` and `IdTokenVerifierFactory`

`SsoProviderConfig.from(config: Config, path: String)` reads a named HOCON block
(`"auth.google"` or `"auth.apple"`), with the same fields, fallbacks, and
comma-separated-list tolerance as `GoogleAuthConfig` today.

`IdTokenVerifierFactory.fromConfig(config: SsoProviderConfig, realProviderId:
String)`
maps the config's `provider` selector: `"stub"` → `StubIdTokenVerifier`,
`realProviderId` (`"google"` or `"apple"`, supplied by the caller) →
`JwksIdTokenVerifier`, anything else → failure. Empty `clientIds` under the real
provider fails, as today.

`service.conf` gains an `auth.apple` block mirroring `auth.google`:

```hocon
auth {
  apple {
    # Required substitution (no default): a forgotten cloud override fails the
    # JVM at boot rather than silently running the offline `stub` verifier.
    # "apple" | "stub".
    provider = ${APPLE_AUTH_PROVIDER}
    clientIds = []                      # accepted audiences (app bundle IDs)
    clientIds = ${?APPLE_CLIENT_IDS}    # comma-separated
    issuers = ["https://appleid.apple.com"]
    jwksUri = "https://appleid.apple.com/auth/keys"
    jwksUri = ${?APPLE_JWKS_URI}
    clockSkew = "60s"
    connectTimeout = "10s"
    readTimeout = "10s"
  }
}
```

Dotenv entries: `.env.dev` and `.env.template` set `APPLE_AUTH_PROVIDER=stub`;
`.env.prod` sets `APPLE_AUTH_PROVIDER=apple` and
`APPLE_CLIENT_IDS=coach.uni.UnicoachiOS`. The client-IDs value must equal the
iOS app's `PRODUCT_BUNDLE_IDENTIFIER` (`ios-app/UnicoachiOS.xcodeproj`) — a
cross-artifact coupling that cannot be derived or enforced across the
iOS/backend boundary, documented here and in a comment beside the `.env.prod`
entry, the same tier RFC 90 used for `GOOGLE_CLIENT_IDS`. No Terraform/infra
changes: the prod host receives env via the layered dotenv (RFC 95), and the
existing `var.google_client_ids` Terraform variable is dead plumbing (removal is
out of scope, tracked separately).

### `AuthService.loginWithSso`

`loginWithGoogle` generalizes to one method shared by both routes:

```kotlin
suspend fun loginWithSso(
  provider: AuthProvider,          // GOOGLE or APPLE; also selects LoginMethod
  idToken: String,
  clientProvidedName: String?,     // Apple route: request.name; Google route: null
  oldCookieToken: String?,
  sessionExpirationSeconds: Long,
  userAgent: String?,
  initialIp: String?,
): Result<SsoLoginResult>
```

`AuthService` gains a second constructor parameter — two explicit params of the
shared interface type, `googleTokenVerifier: IdTokenVerifier` and
`appleTokenVerifier: IdTokenVerifier`, selected by `when (provider)`. Explicit
over a `Map<AuthProvider, IdTokenVerifier>`: no lookup nullability, and hosts
wire exactly what they serve.

`SsoLoginResult` is `GoogleLoginResult` renamed, with its five outcomes
(`Success`, `InvalidToken`, `EmailNotVerified`, `AccountDisabled`,
`VerificationUnavailable`) plus one new:

- `LinkBlockedUnverifiedEmail` — the token verified and its email matches an
  existing active user, but that user's `emailVerifiedAt` is null.

**The linking gate.** In `resolveOrProvisionUser`, the first-link branch (no
`(provider, subject)` row, active email match found) now requires the matched
user's `emailVerifiedAt` to be set; otherwise it returns the
`LinkBlockedUnverifiedEmail` resolution and nothing is written. This supersedes
RFC 64's unconditional link, closing the pre-hijacking vector where an attacker
registers (and never verifies) a password account with the victim's email, and
the victim's later SSO login silently links onto it — leaving the attacker
password access to the victim's account. Returning logins and fresh provisioning
are unaffected. The gate intentionally discloses account existence (the caller
learns an unverified account with this email exists); the caller has just proven
control of a provider identity bearing that email, and the actionable error
(verify first, or use password login) outweighs the opacity of a generic
rejection.

The gate cannot fall back to creating a second user:
`users_email_unique_active_idx` forbids it. Rejection is the only sound outcome.

**Name derivation.** `deriveName` generalizes to a fallback chain over
candidates: token `name` claim → `clientProvidedName` → email local-part. A
candidate is usable iff its trimmed value is non-blank and ≤ 255 characters (the
`users_name_length_check` bound); an unusable candidate falls through to the
next. This replaces the current throw-on-invalid, which lets a > 255-char Google
`name` claim abort the transaction as a 500 — a latent defect this RFC fixes.
The name is used only when provisioning a new user; an existing user is never
renamed. `clientProvidedName` is client-asserted and unverifiable (Apple
surfaces the name only to the client, once, at first authorization) — acceptable
for a cosmetic display-name default on a brand-new account.

The `email_verified` claim gate, the single retry on `23505`-derived constraint
violations from concurrent first logins, and `mintSession` carry over unchanged,
now shared by both providers. Sessions minted on the Apple path use
`LoginMethod.APPLE`.

### API contract

`POST /api/v1/auth/apple`, mirroring the Google route:

```kotlin
// rest-server models
data class AppleLoginRequest(
  val idToken: String,  // named for parity with GoogleLoginRequest, not
                        // Apple's "identityToken" vocabulary
  val name: String?,    // optional; used only when provisioning a new user
)
```

Success: 200 with the existing `LoginResponse` body and session cookie —
first-time signup and returning login indistinguishable. Error mapping, all
lowercase wire codes:

| `SsoLoginResult`             | Status | Code                         |
| ---------------------------- | ------ | ---------------------------- |
| `InvalidToken`               | 401    | `unauthorized`               |
| `EmailNotVerified`           | 403    | `email_not_verified`         |
| `LinkBlockedUnverifiedEmail` | 403    | `account_email_not_verified` |
| `AccountDisabled`            | 403    | `account_disabled`           |
| `VerificationUnavailable`    | 503    | `service_unavailable`        |

`ErrorCode` gains `ACCOUNT_EMAIL_NOT_VERIFIED("account_email_not_verified")` —
distinct from `email_not_verified`, which indicts the provider account's email
rather than the local account's. Because the gate is shared, **both** SSO routes
can now return it; both entries in `api-specs/openapi.yaml` gain the code, and
the spec gains the `/api/v1/auth/apple` path.

The route handler reuses the Google route's outcome-to-response mapping (one
shared private mapper in `AuthRoutes`, parameterized by provider for log/
message wording).

### Data model — migration `0043.add-apple-auth-provider.sql`

Two CHECK constraints widen; nothing else changes (the `user_auth_identities`
append-only shape, indexes, and triggers are untouched):

```sql
ALTER TABLE user_auth_identities
  DROP CONSTRAINT user_auth_identities_provider_check,
  ADD CONSTRAINT user_auth_identities_provider_check
    CHECK (provider IN ('google', 'apple'));

ALTER TABLE sessions
  DROP CONSTRAINT sessions_login_method_check,
  ADD CONSTRAINT sessions_login_method_check
    CHECK (login_method IN ('password', 'google', 'apple'));
```

Kotlin: `AuthProvider` gains `APPLE("apple")`; `LoginMethod` gains
`APPLE("apple")`. Their `fromWire` companions need no change.

### Wiring

- `rest-server/Application.kt` builds both verifiers —
  `IdTokenVerifierFactory.fromConfig(SsoProviderConfig.from(config,
  "auth.google").getOrThrow(), "google")`
  and the `auth.apple`/`"apple"` counterpart — and passes both to `AuthService`.
- `admin-web/Application.kt` and `AdminTestSupport` wire
  `DisabledIdTokenVerifier` for both parameters. Admin-web serves no SSO route;
  the fail-closed verifier replaces today's stub-in-production wiring and
  matches the variant's documented purpose.
- Test call sites (`AuthServiceTest`, `CallerResolutionTest`) pass
  `StubIdTokenVerifier()` for both parameters.

### Dependencies

None new. `com.auth0:java-jwt` and `com.auth0:jwks-rsa` (RFC 64) already handle
RS256/JWKS for any issuer.

## Tests

All backend tests run DB-backed via the unscoped `nix develop -c bin/test`.

### `service` — `JwksIdTokenVerifierTest` (new)

Exercises the real verifier with an in-test RSA keypair, a fake `JwkProvider`
serving the public key, and locally signed JWTs. RFC 64 shipped this class
untested; the Apple claim quirk lives here, so this RFC closes the gap.

- boolean `email_verified: true` → identity with `emailVerified = true`
- string `"true"` → `emailVerified = true` (the Apple shape)
- string `"false"` → `emailVerified = false`
- absent `email_verified` → `emailVerified = false`
- missing `email` claim → `IdTokenInvalidException`
- missing `sub` claim → `IdTokenInvalidException`
- audience not in `clientIds` → `IdTokenInvalidException`
- issuer mismatch → `IdTokenInvalidException`
- expired beyond `clockSkew` leeway → `IdTokenInvalidException`
- unknown `kid` (provider throws non-transport `JwkException`) →
  `IdTokenInvalidException`
- `JwkProvider` transport failure (IOException-derived) →
  `IdTokenUnavailableException`
- absent `name` claim → identity with `name = null`

### `service` — `IdTokenVerifierTest` (renamed `GoogleTokenVerifierTest`)

Existing stub and factory cases carry over under the new names, plus:

- factory returns `JwksIdTokenVerifier` for `realProviderId = "apple"` with
  `provider = "apple"` and non-empty `clientIds`
- factory fails for empty `clientIds` under `provider = "apple"`

### `service` — `SsoAuthServiceTest` (renamed `GoogleAuthServiceTest`)

Existing cases carry over. The pre-existing
`link attaches an identity to an existing password user` case now verifies the
seeded user's email first — under the gate, the unverified seed it uses today
would be rejected. New cases:

- Apple login for a new user creates the user, an `('apple', sub)` identity row,
  and a session with `login_method = 'apple'`
- link blocked: email-matched user with null `emailVerifiedAt` →
  `LinkBlockedUnverifiedEmail`; no identity row, no session, user untouched —
  asserted for **both** providers
- link succeeds onto a verified email-matched user (Apple)
- `clientProvidedName` is used when provisioning via Apple (no token claim)
- token `name` claim wins over `clientProvidedName` (Google)
- unusable `clientProvidedName` (blank; > 255 chars) falls back to the email
  local-part — no failure
- `clientProvidedName` does not rename an existing linked or matched user

### `rest-server` — `AppleAuthRoutingTest` (new, mirrors `GoogleAuthRoutingTest`)

Drives `POST /api/v1/auth/apple` against the stub verifier
(`APPLE_AUTH_PROVIDER=stub` via the test dotenv layer):

- valid token for a new user → 200 with a session cookie; supplied `name`
  appears on the created user
- valid token, no `name` → 200; name derives from the email local-part
- valid token for a returning user → 200
- unverified provider email → 403 `email_not_verified`
- registered-but-unverified local account with matching email → 403
  `account_email_not_verified`
- invalid token → 401 `unauthorized`
- `stub:unavailable` → 503 `service_unavailable`
- non-POST methods → 405
- the issued cookie authenticates a subsequent `GET /api/v1/auth/me`

### `rest-server` — `GoogleAuthRoutingTest`

One new case: registered-but-unverified local account with matching email → 403
`account_email_not_verified`.

### `service` — `SsoProviderConfigTest` (renamed `GoogleAuthConfigTest`)

Existing cases carry over, parameterized by path; new case reading `auth.apple`
(defaulted issuer/jwksUri, env-shaped comma-separated `clientIds`).

### Guards that need no new code

`ErrorCodeTest` enforces the new code's lowercase wire casing; schemathesis
picks up `/api/v1/auth/apple` from the updated OpenAPI spec.

## Implementation Plan

Every step's gate is the unscoped full suite: `nix develop -c bin/test`
(`bin/test` re-inits the test DB, so migration steps are exercised
automatically).

1. **Migration + enums.** Add `db/schema/0043.add-apple-auth-provider.sql` (both
   CHECK widenings); add `AuthProvider.APPLE` and `LoginMethod.APPLE`. Verify:
   `nix develop -c bin/test`.
2. **Mechanical generalization.** Apply the rename table (files and types),
   including `GoogleIdentity.kt` → `FederatedIdentity.kt` and the test-file
   renames; change the factory signature to
   `fromConfig(config,
   realProviderId)` and the config reader to
   `SsoProviderConfig.from(config,
   path)`; update all call sites. Swap
   admin-web (`Application.kt`, `AdminTestSupport`) to `DisabledIdTokenVerifier`
   — the one deliberate behavior change in this step. Verify:
   `nix develop -c bin/test`;
   `grep -rn "Google" service/src/main/kotlin/ed/unicoach/auth/` returns no type
   names (comments referencing Google-the-provider are fine).
3. **Claim tolerance + crypto tests.** Implement the bool-or-string
   `email_verified` read in `JwksIdTokenVerifier`; add `JwksIdTokenVerifierTest`
   with all cases above. Verify: `nix develop -c bin/test` with the new tests
   among the executed count.
4. **`AuthService` generalization.** Replace `loginWithGoogle` with
   `loginWithSso`; add the `appleTokenVerifier` constructor parameter (update
   the six call sites); rename the result ADT to `SsoLoginResult` and add
   `LinkBlockedUnverifiedEmail`; implement the linking gate in
   `resolveOrProvisionUser`; replace `deriveName` with the usable-candidate
   fallback chain including `clientProvidedName`. Update and extend
   `SsoAuthServiceTest` per the Tests section. Verify:
   `nix develop -c bin/test`.
5. **Configuration.** Add the `auth.apple` block to `service.conf`; add
   `APPLE_AUTH_PROVIDER=stub` to `.env.dev` and `.env.template`; add
   `APPLE_AUTH_PROVIDER=apple` and `APPLE_CLIENT_IDS=coach.uni.UnicoachiOS`
   (with the bundle-ID coupling comment) to `.env.prod`; extend
   `SsoProviderConfigTest` and the factory `"apple"` cases. Verify:
   `nix develop -c bin/test`.
6. **REST surface.** Add `ACCOUNT_EMAIL_NOT_VERIFIED` to `ErrorCode`; add
   `AppleLoginRequest`; add the `/api/v1/auth/apple` route sharing the outcome
   mapper in `AuthRoutes`; wire both verifiers in `rest-server/Application.kt`;
   map `LinkBlockedUnverifiedEmail` on both routes. Add `AppleAuthRoutingTest`;
   extend `GoogleAuthRoutingTest`. Verify: `nix develop -c bin/test`.
7. **OpenAPI.** Add `/api/v1/auth/apple` and the `account_email_not_verified`
   403 responses to both SSO routes in `api-specs/openapi.yaml`. Verify: the
   Deno YAML parse check (per the repo's validation tooling), then
   `nix develop -c bin/test` for the final full-suite pass.

## Files Modified

Expected scope; non-exhaustive by convention.

- `db/schema/0043.add-apple-auth-provider.sql` — **new**: CHECK widenings.
- `db/src/main/kotlin/ed/unicoach/db/models/AuthProvider.kt`,
  `db/src/main/kotlin/ed/unicoach/db/models/LoginMethod.kt` — `APPLE` entries.
- `service/src/main/kotlin/ed/unicoach/auth/` — renames per the table:
  `GoogleTokenVerifier.kt` → `IdTokenVerifier.kt`, `GoogleIdentity.kt` →
  `FederatedIdentity.kt`, `JwksGoogleTokenVerifier.kt` →
  `JwksIdTokenVerifier.kt`, `StubGoogleTokenVerifier.kt` →
  `StubIdTokenVerifier.kt`, `GoogleTokenVerifierFactory.kt` →
  `IdTokenVerifierFactory.kt`, `GoogleAuthConfig.kt` → `SsoProviderConfig.kt`,
  `GoogleLoginResult.kt` → `SsoLoginResult.kt`; `AuthService.kt` —
  `loginWithSso`, gate, name chain, second verifier param.
- `service/src/main/resources/service.conf` — `auth.apple` block.
- `service/src/test/kotlin/ed/unicoach/auth/` — `GoogleTokenVerifierTest.kt` →
  `IdTokenVerifierTest.kt`, `GoogleAuthConfigTest.kt` →
  `SsoProviderConfigTest.kt`, `GoogleAuthServiceTest.kt` →
  `SsoAuthServiceTest.kt`; `JwksIdTokenVerifierTest.kt` — **new**;
  `AuthServiceTest.kt` — constructor call sites.
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorCode.kt` — new code;
  `models/AppleLoginRequest.kt` — **new**; `routing/AuthRoutes.kt` — apple
  route + shared outcome mapper; `Application.kt` — both verifiers.
- `rest-server/src/test/kotlin/ed/unicoach/rest/AppleAuthRoutingTest.kt` —
  **new**; `GoogleAuthRoutingTest.kt` — gate case;
  `auth/CallerResolutionTest.kt` — constructor call site.
- `admin-web/src/main/kotlin/ed/unicoach/admin/Application.kt`,
  `admin-web/src/test/kotlin/ed/unicoach/admin/AdminTestSupport.kt` —
  `DisabledIdTokenVerifier` wiring.
- `api-specs/openapi.yaml` — apple path + new 403 code on both SSO routes.
- `.env.dev`, `.env.template`, `.env.prod` — `APPLE_AUTH_PROVIDER` /
  `APPLE_CLIENT_IDS` entries.
