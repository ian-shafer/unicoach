# RFC 113: Sign in with Apple in the iOS App

## Executive Summary

This RFC adds "Sign in with Apple" to the UnicoachiOS SwiftUI client. It is the
iOS-client half of Apple SSO; the backend contract shipped in
[RFC 111](111-apple-sso-login.md) and is live at `POST /api/v1/auth/apple`. The
app obtains a native Apple identity token through `AuthenticationServices`,
POSTs `{ "idToken": "...", "name": "..." }`, and the server verifies the token,
links or creates the user, and sets the session cookie exactly as the Google and
password paths do.

The client generalizes rather than duplicates: RFC 90's Google-only
`GoogleSignInProviding` / `GoogleAuthenticating` pair becomes one
`SsoSignInProviding` protocol with a provider-tagged `SsoCredential`, with
Google and Apple as its two implementations — mirroring what RFC 111 did to the
backend verifier stack, and for the same reason.

Two client-side facts drive the design. Apple returns the user's name **only on
the first authorization**, and the backend consumes a name only when
provisioning, so a failed first POST would lose the name permanently; the
provider therefore persists it and resends until it is no longer needed. Hide My
Email produces a working `@privaterelay.appleid.com` account email.

Scope includes one server change, because without it the feature is broken for
exactly the users Apple's privacy features attract: SSO provisioning never set
`email_verified_at`, so every new SSO user — Google's included, today — is
routed to `VerificationRequiredView` and told to check mail that, at a relay
address, cannot arrive.

## Detailed Design

### Server: a verified SSO sign-in marks the account email verified

`AuthService.resolveOrProvisionUser` marks the resolved user's email verified
when, and only when, that user's current email equals the provider-verified
email from the token, and returns the `User` that `UsersDao.markEmailVerified`
yields. Returning the pre-mark row instead would persist the flag and still
answer `"emailVerified": false` — `runSsoSignIn` puts the resolution's `User`
straight into `SsoLoginResult.Success`, and `PublicUser.from` derives
`emailVerified` from that object's `emailVerifiedAt` — leaving the client on
`VerificationRequiredView`, the exact failure this change removes. Both
resolution branches converge on one private helper taking the candidate `User`
and the token `EmailAddress` and returning the user to resolve to, so the
returning-login branch (`resolved`) and the link/create branch (`target`) are
guarded by the same code rather than two copies of it. A `markEmailVerified`
failure is rethrown like every other DAO failure in this function, aborting the
transaction and surfacing as a 500; a best-effort ignore is rejected, since it
would sign the user in unverified and reproduce that same failure silently.

`loginWithSso` already hard-gates on `identity.emailVerified` before any
resolution happens, so reaching this point means the provider asserted the
address. `UsersDao.markEmailVerified` is the existing dedicated primitive —
versioned, conditional on `email_verified_at IS NULL`, idempotent on a second
call, and taking the same `SqlSession`, so it joins the sign-in transaction and
needs no new DAO surface.

The equality guard is load-bearing on one of the three resolution paths:

- **create** — the new user's email _is_ the token email. Guard trivially true.
- **link** — the match is found by `UsersDao.findByEmail(session, email)` using
  the token email. Guard trivially true. Marking here heals legacy SSO rows on
  their next sign-in. No backfill migration: the population is small, and a
  healed row is one sign-in away. Note the healing is not automatic for a user
  who is already signed in — `me()` only reads `emailVerifiedAt`, so an existing
  SSO user sits on `VerificationRequiredView` until they use its "Log Out"
  button and sign in again. Reverting this change is code-only; rows it already
  marked stay verified, which is the correct state anyway.
- **returning login** — the user is resolved by `(provider, subject)`, and
  `AuthService.changeEmail` may since have moved them to a different address,
  resetting `email_verified_at` to NULL to force re-verification. The token
  still asserts the _old_ provider address. Marking unguarded here would verify
  an address nobody proved; the guard is what prevents it.

`resolveOrProvisionUser`'s KDoc asserts that this code path "never sets
`emailVerifiedAt`" as part of its reasoning about why a passwordless match is
always linkable. That reasoning stays correct — the linking gate fires only when
`passwordHash != null` — but the factual claim becomes false and is corrected.

No schema change, no new error code, no change to the linking gate. The
`account_email_not_verified` refusal is unaffected: it fires only for a match
holding a password credential, and this change never marks such a user verified
unless their own email matched the token.

### Client data models

```swift
enum SsoProvider {
    case google
    case apple
}

/// What a provider produced. Provider-tagged so that "Google never carries a
/// name" is unrepresentable rather than a convention.
enum SsoCredential {
    case google(idToken: String)
    case apple(idToken: String, name: String?)
}

enum SsoSignInOutcome {
    case signedIn(SsoCredential)
    case cancelled // user dismissed the sheet — an ordinary outcome
}

@MainActor
protocol SsoSignInProviding {
    /// The provider this conformer speaks to. Fixed per conformer, so the
    /// caller can set the loading phase before a credential exists and word a
    /// failure banner when `signIn()` throws; a conformer's `signIn()` returns
    /// only this provider's `SsoCredential` case.
    var provider: SsoProvider { get }

    /// Presents the provider's UI; returns the outcome. Throws only for genuine
    /// failures — user cancellation is a returned outcome, not one.
    func signIn() async throws -> SsoSignInOutcome
}

enum AppleSignInError: Error {
    case presentationUnavailable   // no foreground key window to anchor to
    case requestInFlight           // a signIn() is already awaiting its delegate
    case unexpectedCredentialType  // not an ASAuthorizationAppleIDCredential
    case missingIdentityToken      // credential carried no identityToken
    case undecodableIdentityToken  // identityToken Data was not UTF-8
    case authorizationFailed(Error)
}

/// Persists the name Apple discloses on first authorization only.
protocol AppleNameStore {
    func name(forAppleUserId id: String) -> String?
    func store(name: String, forAppleUserId id: String)
}

struct AppleLoginRequest: Encodable {
    let idToken: String
    let name: String?
}

protocol SsoAuthenticating {
    func signIn(with credential: SsoCredential) async throws -> LoginResponse
}

enum SignInPhase: Equatable {
    case idle
    case passwordLoading
    case ssoLoading(SsoProvider)
}

/// The active foreground scene's key window: Apple's `ASPresentationAnchor` and
/// the source of Google's presenting root view controller. One lookup, two
/// callers.
@MainActor
func foregroundKeyWindow() -> UIWindow?
```

`SsoSignInProviding` replaces `GoogleSignInProviding`, `SsoCredential` +
`SsoSignInOutcome` replace `GoogleSignInOutcome`, and `SsoAuthenticating`
replaces `GoogleAuthenticating`. `GoogleSignInError` is unchanged and stays a
separate enum from `AppleSignInError`: the failure modes genuinely differ,
nothing consumes either polymorphically, and `LoginViewModel` routes any thrown
provider error through one mapper.

The SSO method stays off `AuthClientProtocol`, as RFC 90 established, so the
three conformers that never construct a `LoginViewModel`
(`RegistrationPreviewAuthClient`, `VerificationPreviewAuthClient`, and
`RegistrationViewModelTests`' `DelayedAuthClient`) remain untouched.

### `AppleSignInProvider`

`AppleSignInProvider` conforms to `SsoSignInProviding` by driving
`ASAuthorizationController` and bridging its delegate callbacks to `async` with
a checked continuation, resumed exactly once.

- The request is `ASAuthorizationAppleIDProvider().createRequest()` with
  `requestedScopes = [.fullName, .email]`. The email scope is **mandatory**:
  `JwksIdTokenVerifier` rejects a token carrying no `email` claim, which the
  route reports as a generic `401 unauthorized`.
- The presentation anchor is resolved at the _start_ of `signIn()`, throwing
  `presentationUnavailable` when no foreground key window exists.
  `presentationAnchor(for:)` returns a non-optional `ASPresentationAnchor`, so
  validating up front is what makes that signature honest; the delegate method
  returns the already-validated window.
- `GoogleSignInProvider` resolves the same foreground key window (for its root
  view controller). That lookup moves to one shared helper used by both.
- `ASAuthorizationError.canceled` maps to the returned `.cancelled` outcome,
  mirroring the Google provider's `NSError` bridge. Every other error throws
  `authorizationFailed`.
- `identityToken` is `Data?`; it is decoded as UTF-8 to the `String` the wire
  contract wants. Absent and undecodable are distinct error cases.
- The credential's `email` property is ignored — the backend reads the email
  from the token, and `AppleLoginRequest` has no email field.
- A second `signIn()` entered while one is awaiting its delegate throws
  `requestInFlight` rather than overwriting the stored continuation. The UI
  already disables both buttons during `.ssoLoading`, but the provider does not
  depend on its caller for that.

The conformer is `@MainActor`; the controller's callbacks are documented to
arrive on the main thread, and the conformance is expressed so that Swift 6
strict concurrency accepts it without weakening the isolation.

`getCredentialState(forUserID:)` and `credentialRevokedNotification` launch
checks are out of scope: the server cookie is the session authority, and `sub`
is stable across revocation, so a revoking user simply signs in again onto the
same account.

### First-authorization name capture

`AppleSignInProvider` holds an injected `AppleNameStore` (`UserDefaults`
conformer in production). On each `signIn()`, when the credential carries a
`fullName`, the formatted name is persisted keyed by `credential.user` and
returned; when it does not, the stored name for _that same_ `credential.user` is
returned instead.

Without this, a first authorization whose POST fails (network drop, the `503`
JWKS outage the backend models, app killed mid-flight) retries with `nil`, the
account is provisioned from the email local-part, and no name-edit surface
exists anywhere in the app — with Hide My Email that local-part is a random
relay string rendered forever by `HomeView`'s `Welcome, \(user.name)!`.

Keying by `credential.user` is what stops a second Apple ID on the same device
inheriting the first one's name.

The stored name is **never cleared** — resending is free (the backend ignores
the field once provisioned), while clearing would need the provider to learn the
backend's outcome through a `SsoSignInProviding` method Google has no use for.

Formatting uses `PersonNameComponentsFormatter` (locale-aware) and trims; an
empty result becomes `nil` so the backend falls through its own candidate chain
rather than receiving a blank `PersonName.create` would reject. No client-side
truncation: a name beyond `PersonName`'s 255-unit cap is pathological, and
`deriveName` already falls through with a logged warning.

The store read/write is reachable from tests as its own member, since `signIn()`
is not:

```swift
extension AppleSignInProvider {
    /// Persists `formattedName` when it is non-nil and non-blank, then returns
    /// the name to send for `appleUserId`: the just-stored one, or the
    /// previously stored one when Apple disclosed nothing. `nil` when neither
    /// exists.
    func resolveName(formattedName: String?, appleUserId: String) -> String?
}
```

### `AuthClient`

`AuthClient.signIn(with:)` switches exhaustively on `SsoCredential`, so each
branch builds its own request type against its own path:

- `.google(idToken:)` → `POST /api/v1/auth/google`, body `GoogleLoginRequest`
- `.apple(idToken:name:)` → `POST /api/v1/auth/apple`, body `AppleLoginRequest`

Both expect `200` with a `LoginResponse` body and the session cookie. No field
is dropped on either branch.

### `LoginViewModel`

`signInWithGoogle()` and `signInWithApple()` are thin call sites over one
private body parameterized by an `SsoSignInProviding`. The body clears the error
surfaces, sets `phase = .ssoLoading(provider.provider)` with a `defer` back to
`.idle`, returns silently on `.cancelled`, maps a thrown provider error to an
inline banner, and otherwise calls `authClient.signIn(with:)` and routes both
outcomes exactly as the Google path does today.

`SignInPhase` gains `.ssoLoading(SsoProvider)` in place of `.googleLoading`,
keeping a both-loading state unrepresentable while distinguishing which button
shows its spinner.

`mapProviderError` is provider-tagged, producing `APPLE_SIGN_IN_FAILED` beside
the existing `GOOGLE_SIGN_IN_FAILED`. Both keep the `ios-app/UnicoachiOS`
convention that client-synthesized codes are UPPERCASE, disjoint from backend
lowercase wire codes, and displayed rather than branched on.

`mapBackendError` is otherwise unchanged, with one addition:
`account_email_not_verified` is intercepted and rendered with client copy rather
than the server's message.

### Error handling and edge cases

The route's outcomes and what the user sees:

| Wire outcome                         | Client result                              |
| ------------------------------------ | ------------------------------------------ |
| `200`                                | `onLoginSuccess` → `UserAuthState` machine |
| `401 unauthorized`                   | inline banner, server message              |
| `403 email_not_verified`             | inline banner, server message              |
| `403 account_disabled`               | inline banner, server message              |
| `403 account_email_not_verified`     | inline banner, **client copy** (below)     |
| `503 service_unavailable`            | inline banner, server message              |
| `TIMEOUT` / `NETWORK_ERROR`          | `infrastructureError` full-screen cover    |
| `SERVER_ERROR` / non-`ErrorResponse` | `infrastructureError` full-screen cover    |
| provider throws                      | inline banner, `APPLE_SIGN_IN_FAILED`      |
| provider returns `.cancelled`        | silent no-op; no banner, no callback       |

`account_email_not_verified` is the one code whose server message — "The matched
account's email is not verified" — is accurate for a log and useless to a user,
naming no way out. It is replaced client-side with provider-neutral copy, since
RFC 111's shared outcome mapper returns this code for the Google route too:

> An unverified account already uses this email. Log in with your password and
> verify your email, then try again.

That is the actual remedy: the refusal means a password account was registered
against this address and never verified, and an unverified account can still log
in, reaching `VerificationRequiredView` and its resend button. Disclosure is
safe — the provider has already proven the signer owns the address.

`email_not_verified` (the Apple account's own email) remains near-unreachable
for Apple but stays mapped; `JwksIdTokenVerifier.readEmailVerified` already
accepts Apple's string-valued `email_verified` claim as well as a JSON boolean.

**Hide My Email** needs no client branch: the relay address arrives as a normal
verified `email` claim and provisions a normal account. It is the case the
server change above exists for — a relay address cannot receive our verification
mail.

A **withheld** email — a token with no `email` claim at all — is not a
client-handleable branch. Requesting the `.email` scope is what prevents it; if
one arrives regardless, the backend answers `401` and the generic banner shows.

**Not fixed here**: the `infrastructureError` cover's Retry is hardcoded to
`login()`, so retrying an SSO timeout re-runs the password form (today's
behaviour on the Google path too). Retry-by-provider is a separate change.

### `LoginView` and `AppleSignInButton`

`AppleSignInButton` wraps `ASAuthorizationAppleIDButton` in a
`UIViewRepresentable`; its tap runs `viewModel.signInWithApple()`. Using Apple's
own control makes HIG compliance structural, and it is the same wrapping
technique RFC 90 used for Google's button before it was restyled.

Apple's own `SignInWithAppleButton` is not used: it owns the request and hands
`Result<ASAuthorization, Error>` to a view closure, which would put the
credential mapping in an untestable view outside the `SsoSignInProviding` seam.

Placement follows Apple's prominence rule, which binds Apple against _other
third-party sign-in_ rather than against our own password form. The existing
spine is kept and Apple is inserted above Google:

`Log In` → "or" divider → **Apple** → Google → "Don't have an account?
Register".

Appearance: `.signIn` type ("Sign in with Apple", parallel to "Sign in with
Google"); `.black` in light mode and `.white` in dark, read from
`@Environment(\.colorScheme)` — the style is fixed at
`ASAuthorizationAppleIDButton` init, so the representable is recreated with
`.id(colorScheme)` rather than mutated in `updateUIView`; `cornerRadius` set to
`DSRadius.button`; full width; the same `ZStack` + `ProgressView` overlay and
`.disabled(phase != .idle)` call site as the Google button — but SwiftUI's
`\.isEnabled` is not bridged into a wrapped `UIView`, so the representable reads
`@Environment(\.isEnabled)` and applies it to the `ASAuthorizationAppleIDButton`
(`isUserInteractionEnabled`, plus the Google button style's `0.5` disabled
opacity) in `updateUIView`; without that the button stays live during
`.ssoLoading` and `requestInFlight` becomes the only double-tap guard;
identifiers `appleSignInButton` and `appleLoadingIndicator`.

`GoogleSignInButton`'s height is font-driven and grows with Dynamic Type;
`ASAuthorizationAppleIDButton` does not scale on its own, so its height is a
`@ScaledMetric` value, mirroring the `@ScaledMetric logoSize` already in the
Google button. The two are matched at every text size but not derived from a
shared metric — Apple's button cannot take the design system's font.

`RegistrationView` offers no SSO at all, so Apple's parity rule does not reach
it.

### Xcode project and the App ID capability

`ios-app/UnicoachiOS/UnicoachiOS.entitlements` is added, declaring
`com.apple.developer.applesignin` = `["Default"]`, and referenced from the app
target's `CODE_SIGN_ENTITLEMENTS`. The test target is unaffected.

Sign in with Apple must also be enabled on the `coach.uni.UnicoachiOS` App ID in
the Apple Developer portal. This is an out-of-band human step and a prerequisite
of the plan below: simulator destinations ad-hoc sign and embed entitlements
without contacting the portal, so compilation and the unit suite do not depend
on it, but a real authorization does, and any device or TestFlight build fails
provisioning without it.

The backend audience is already provisioned: `.env.prod` carries
`APPLE_CLIENT_IDS=coach.uni.UnicoachiOS` — the bundle identifier, which is the
`aud` a native Apple authorization produces — and `APPLE_AUTH_PROVIDER=apple`.
That file already documents the coupling to `PRODUCT_BUNDLE_IDENTIFIER`; this
RFC adds the reciprocal note on the iOS side, in `ios-app/DEPLOY.md`, since the
coupling cannot be derived or enforced across the artifact boundary. `.env.dev`
sets `APPLE_AUTH_PROVIDER=stub`, whose verifier accepts only `stub:`-prefixed
fake tokens, so a genuine Apple token cannot be exercised against a local
backend.

### Dependencies

No new package. `AuthenticationServices` is a system framework and auto-links
from `import`; unlike RFC 90's `GoogleSignIn-iOS`, `project.pbxproj` gains no
`XCRemoteSwiftPackageReference`. Deployment target is iOS 17, well above every
API used.

## Tests

The Kotlin half is gated normally by `nix develop -c bin/test` and the
pre-commit hook. **The Swift half is gated by nothing automatic** —
`bin/format
-c` and `bin/test check` neither compile nor run Swift — so
`xcodebuild` is the only gate and must be run deliberately:

```sh
xcodebuild test \
  -project ios-app/UnicoachiOS.xcodeproj \
  -scheme UnicoachiOS \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

New `.swift` files MUST be registered in `project.pbxproj` or they silently
never compile.

### Kotlin — `SsoAuthServiceTest`

- `a newly provisioned SSO user is email-verified` — first sign-in for an unseen
  subject and email; asserts the created user's `emailVerifiedAt != null` and
  that `PublicUser.emailVerified` would be true.
- `linking onto a prior SSO-provisioned user verifies it` — the existing
  `a second provider links onto a prior SSO-provisioned user even though emailVerifiedAt is null`
  case (`:217`), whose premise inverts: its precondition
  `assertTrue(first.user.emailVerifiedAt == null)` (`:225`) becomes an assertion
  that the first login's user IS verified, the comment above it is rewritten,
  and the case is renamed.
  `link blocked when the email-matched user is unverified, for both providers`
  is deliberately untouched: its `reloaded.emailVerifiedAt == null` assertion
  (`:254`) guards a _refused_ link, which returns `UserResolution.LinkBlocked`
  before any marking and must stay null.
- `a returning login whose account email still matches marks it verified` —
  seeds a legacy SSO user with `emailVerifiedAt == null`, signs in again with
  the same provider and subject; asserts it is now verified. This is the
  legacy-healing path.
- `a returning login after changeEmail does not mark the new address verified` —
  the guard's reason for existing. SSO-provision, `changeEmail` to a different
  address (nulling `email_verified_at`), then sign in again with the same
  provider token; asserts `emailVerifiedAt` stays null.
- `a linked password account keeps its own verification state` — links an SSO
  identity onto a verified password account; asserts no second version bump from
  a redundant mark (idempotence of `markEmailVerified`).

### Kotlin — `AppleAuthRoutingTest` / `GoogleAuthRoutingTest`

- `a first Apple sign-in reports emailVerified true` and its Google twin — the
  route-level proof, asserting the `200` body carries `"emailVerified":true`.

`AuthRoutingTest`'s
`register login and me report emailVerified false before
verification` covers
password registration and is unaffected.

### iOS — `AuthClientTests` (transport, via `MockURLProtocol`)

- `testAppleSignInSuccess` — `200` with a `LoginResponse` body; asserts path
  `/api/v1/auth/apple`, method `POST`, `Content-Type: application/json`, that
  the body decodes to an `AppleLoginRequest` carrying the sent token and name,
  and the returned user.
- `testAppleSignInOmitsNameWhenNil` — `.apple(idToken:name: nil)`; asserts the
  encoded body has no `name` key (or a null one), never an empty string, so the
  backend's candidate chain is reached rather than fed a value
  `PersonName.create` rejects.
- `testAppleSignInUnauthorized` — `401 unauthorized` → `ErrorResponse` code
  `unauthorized`, `status == 401`.
- `testAppleSignInAccountEmailNotVerified` — `403
  account_email_not_verified`
  → code `account_email_not_verified`, `status == 403`. Decode-level proof that
  the new code survives the wire, which no view-model test can give.
- `testAppleSignInEmailNotVerified` / `testAppleSignInAccountDisabled` /
  `testAppleSignInServiceUnavailable` / `testAppleSignInServerError` — the
  remaining mapped outcomes, mirroring the existing Google cases.
- The six existing `testGoogleSignIn*` cases are retargeted from
  `signInWithGoogle(idToken:)` to `signIn(with: .google(idToken:))`; their
  assertions do not change.

### iOS — `LoginViewModelTests` (outcomes, via mocks)

`MockGoogleSignInProvider` is replaced by one `MockSsoSignInProvider` carrying a
configurable `provider` tag and `signInResult: Result<SsoSignInOutcome, Error>?`
— so cancellation is configured as `.success(.cancelled)`, not a thrown error.
`MockAuthClient`'s `signInWithGoogleResult` / `signInWithGoogleCallCount` become
`signInResult` / a captured `[SsoCredential]`, so tests assert **which**
credential was sent, not merely that a call happened. The file's two inline
doubles are updated: `DelayedAuthClient` (`:79`) to `SsoAuthenticating`, and
`DelayedGoogleProvider` (`:209`) to `SsoSignInProviding`.

- `testAppleSignInSuccessInvokesCallback` — provider returns
  `.signedIn(.apple(...))`, client returns a user → `onLoginSuccess` user
  matches; no `errorResponse`, no `infrastructureError`.
- `testAppleSignInForwardsTokenAndName` — asserts the captured credential is
  `.apple` carrying exactly the token and name the provider produced. Guards the
  RFC's central claim: the first-authorization name reaches the wire.
- `testAppleSignInCancellationIsSilentNoOp` — `.cancelled` → no banner, no
  cover, callback not invoked, client not called.
- `testAppleSignInProviderFailureShowsBanner` — provider throws
  `.authorizationFailed` → `errorResponse.code == "APPLE_SIGN_IN_FAILED"`;
  client not called.
- `testAppleSignInAccountEmailNotVerifiedShowsClientCopy` — client throws
  `account_email_not_verified` with the server's message; asserts the displayed
  `errorResponse.message` is the client copy and **not** the server's.
- `testGoogleSignInAccountEmailNotVerifiedShowsClientCopy` — the Google twin of
  the case above; asserts the identical provider-neutral copy, so the
  interception cannot regress into Apple-specific wording.
- `testAppleSignInUnauthorizedSetsErrorResponse` /
  `…EmailNotVerifiedSetsErrorResponse` (asserting it does not enter a
  verification flow) / `…AccountDisabledSetsErrorResponse` /
  `…ServiceUnavailableSetsErrorResponse` — inline banner, server message.
- `testAppleSignInServerErrorSetsInfrastructureError` /
  `…TimeoutSetsInfrastructureError` — full-screen cover, no banner.
- `testAppleSignInLoadingPhase` — `phase == .ssoLoading(.apple)` while the
  delayed provider/client run, `.idle` after.
- `testGoogleSignInLoadingPhase` — the existing
  `testGoogleSignInLoadingStateToggles` retargeted to `.ssoLoading(.google)`,
  proving the two providers do not share a phase.
- The remaining existing `testGoogleSignIn*` cases are retargeted to the new
  mock surface; their assertions do not change.

### iOS — `AppleSignInProviderTests`

`ASAuthorizationAppleIDCredential` has no public initializer, so the provider's
credential path cannot be driven from a test. What is reachable is the name
store and the provider's use of it — through
`resolveName(formattedName:appleUserId:)`, which takes a formatted name and an
Apple user id, against `MockAppleNameStore` — plus the re-entry guard, which
fires before any credential exists.

- `testFirstAuthorizationPersistsName` — a name is stored under the given Apple
  user id.
- `testSubsequentAuthorizationReadsStoredName` — no name supplied, stored name
  returned.
- `testStoredNameIsNotSharedAcrossAppleUserIds` — a second id gets `nil`, not
  the first id's name.
- `testBlankNameIsNotStoredAndYieldsNil` — a whitespace-only name is neither
  persisted nor returned.
- `testNameSurvivesRepeatedReads` — reading does not clear, matching the
  never-cleared rule.
- `testSecondConcurrentSignInThrowsRequestInFlight` — with one `signIn()` left
  suspended awaiting its delegate, a second call throws `.requestInFlight` and
  does not disturb the first continuation.

### What unit tests cannot reach

Four families of behaviour are unreachable from XCTest and are covered only by
the manual pass: construction of a real `ASAuthorizationAppleIDCredential`,
Apple's first-versus-subsequent name disclosure, Hide My Email, and every
`AppleSignInError` case that requires a real credential or controller
(`unexpectedCredentialType`, `missingIdentityToken`, `undecodableIdentityToken`,
`authorizationFailed`). `requestInFlight` is reachable and is tested above.
`presentationUnavailable` is reachable too: `AppleSignInProvider` takes an
injectable window resolver (defaulting to the shared `foregroundKeyWindow()`
helper), so a test supplying `{ nil }` forces the nil branch directly. It is
tested above. Note that re-testing the first-authorization path requires
revoking the app under _Settings → Apple ID → Sign-In & Security → Apps Using
Apple ID → Stop Using_ — a careless first run burns the case.

### Manual end-to-end (required to land)

Built with `bin/build-ios prod-simulator`, which targets the live deployment
whose `APPLE_CLIENT_IDS` already carries the bundle identifier. The default
`simulator` target derives `localhost:8080` from the repo `.env`, and a locally
run backend picks up `APPLE_AUTH_PROVIDER=stub` from `.env.dev` — the ambient
local delta `bin/common` layers over `.env` — whose verifier rejects every real
Apple token, so running the pass there would fail spuriously.

1. **First authorization.** Sign in with an Apple ID that has never authorized
   this app, disclosing the real name and email. Confirm the app reaches
   `HomeView`/onboarding — **not** `VerificationRequiredView` — and greets the
   user by the disclosed name.
2. **Subsequent authorization.** Log out, sign in again with the same Apple ID.
   Apple discloses no name; confirm the session is established and the greeting
   is unchanged.
3. **Hide My Email.** Revoke, then re-authorize choosing "Hide My Email".
   Confirm the account provisions on the `@privaterelay.appleid.com` address and
   lands **authenticated**. This is the direct proof of the server change.
4. **Cancel.** Dismiss the Apple sheet; confirm return to the login screen with
   no banner and no state change.
5. **Placement.** Confirm the Apple button sits above the Google button, that
   the two are the same width and height, and that both track Dynamic Type at
   the largest accessibility size.
6. **Disabled during loading.** With a deliberately slow network, tap Apple and
   then tap both buttons again while the spinner shows; confirm neither responds
   and no second Apple sheet appears.

Device and TestFlight verification is out of scope.

## Implementation Plan

Step 1 is verified in the Nix dev shell; steps 3 onward use system `xcodebuild`,
outside it. `bin/build-ios simulator` requires no signing credentials.

0. **Prerequisite (human, out of band).** Enable Sign in with Apple on the
   `coach.uni.UnicoachiOS` App ID in the Apple Developer portal. Steps 1–9 do
   not depend on it — simulator destinations ad-hoc sign without contacting the
   portal, so the unit suite runs without it; step 10 does. Verify: the
   capability is listed on the App ID.

1. **Mark the email verified on SSO sign-in, and move the tests with it.** In
   `AuthService.resolveOrProvisionUser`, add a private helper taking the
   candidate `User` and the token `EmailAddress` that calls
   `UsersDao.markEmailVerified` when `candidate.email == email` and returns the
   resulting `User` (the candidate unchanged otherwise); route every
   `UserResolution.Resolved(...)` construction — returning login and link/create
   alike — through it. Correct the KDoc's stale "never sets `emailVerifiedAt`"
   claim. In the same step, invert the premise of `SsoAuthServiceTest`'s
   second-provider linking case, add the four new cases, and add the
   `emailVerified: true` route assertions to `AppleAuthRoutingTest` and
   `GoogleAuthRoutingTest`. Verify: `nix develop -c bin/test` green; report the
   executed count.

2. **Add the entitlement.** Create `UnicoachiOS.entitlements` with
   `com.apple.developer.applesignin`, set the app target's
   `CODE_SIGN_ENTITLEMENTS`, and register the file. Verify:
   `bin/build-ios simulator` compiles, and
   `codesign -d --entitlements - <built .app>` lists the key — confirming the
   simulator build embeds it without the portal.

3. **Add the shared SSO seam.** Add `SsoSignIn.swift` (`SsoProvider`,
   `SsoCredential`, `SsoSignInOutcome`, `SsoSignInProviding`, the shared
   foreground-key-window helper); retire `GoogleSignInProviding` /
   `GoogleSignInOutcome` by conforming `GoogleSignInProvider` to the new
   protocol and moving its window lookup to the helper. Verify:
   `bin/build-ios simulator` compiles.

4. **Add the Apple provider and name store.** Add `AppleNameStore.swift`
   (protocol + `UserDefaults` conformer) and `AppleSignInProvider.swift`
   (request, scopes, anchor validation, delegate-to-continuation bridge,
   `AppleSignInError`, store read/write). Register both files. Verify: compiles.

5. **Wire the client.** Rename `GoogleAuthenticating` to `SsoAuthenticating`
   with `signIn(with:)`, conform `AuthClient` with both branches, add
   `AppleLoginRequest` to `Models.swift`, and retype `LoginViewModel`,
   `AppViewModel`, `AuthFlowView`, `LoginView`, and the two preview conformers.
   Verify: compiles — the three `AuthClientProtocol`-only conformers are
   untouched.

6. **Wire the view model.** Change `SignInPhase` to `.ssoLoading(SsoProvider)`,
   collapse the two sign-in bodies into one parameterized by an
   `SsoSignInProviding`, add `signInWithApple()`, tag `mapProviderError`, and
   add the `account_email_not_verified` client copy. Verify: compiles.

7. **Add the UI.** Add `AppleSignInButton.swift` and place it above the Google
   button in `LoginView` with the spinner overlay, the
   `@Environment(\.isEnabled)` bridge into the wrapped button, colour-scheme
   `.id()`, and `@ScaledMetric` height; update the preview host. Verify:
   compiles; both SwiftUI previews render in light and dark.

8. **Add the iOS tests.** Replace `MockGoogleSignInProvider.swift` with
   `MockSsoSignInProvider.swift`, add `MockAppleNameStore.swift` and
   `AppleSignInProviderTests.swift`, update `MockAuthClient` to capture
   credentials, retarget the existing Google cases and the two inline doubles,
   and add the new `AuthClientTests` and `LoginViewModelTests` cases. Register
   every new file in `project.pbxproj`. Verify:
   `xcodebuild test -project ios-app/UnicoachiOS.xcodeproj -scheme UnicoachiOS
   -destination 'platform=iOS Simulator,name=iPhone 17 Pro'`
   passes, with the new tests among those executed.

9. **Document the coupling.** Add the bundle-identifier ↔ `APPLE_CLIENT_IDS`
   note and the App ID capability prerequisite to `ios-app/DEPLOY.md`. Verify:
   `nix develop -c bin/format -c` clean.

10. **Manual end-to-end.** Build with `bin/build-ios prod-simulator` and run the
    six-case pass above. Verify: first authorization lands authenticated with
    the disclosed name; Hide My Email lands authenticated; cancel returns
    cleanly.

## Files Modified

Expected scope; non-exhaustive.

### Server

- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt` — guarded
  `markEmailVerified` call in `resolveOrProvisionUser`; KDoc correction.
- `service/src/test/kotlin/ed/unicoach/auth/SsoAuthServiceTest.kt` — inverted
  linking-case premise; four new cases.
- `rest-server/src/test/kotlin/ed/unicoach/rest/AppleAuthRoutingTest.kt`,
  `rest-server/src/test/kotlin/ed/unicoach/rest/GoogleAuthRoutingTest.kt` —
  route-level `emailVerified: true`.

### iOS sources

- `ios-app/UnicoachiOS/SsoSignIn.swift` — **new**: `SsoProvider`,
  `SsoCredential`, `SsoSignInOutcome`, `SsoSignInProviding`, shared key-window
  helper.
- `ios-app/UnicoachiOS/AppleSignInProvider.swift` — **new**: production
  provider, `AppleSignInError`.
- `ios-app/UnicoachiOS/AppleNameStore.swift` — **new**: protocol +
  `UserDefaults` conformer.
- `ios-app/UnicoachiOS/AppleSignInButton.swift` — **new**:
  `ASAuthorizationAppleIDButton` `UIViewRepresentable`.
- `ios-app/UnicoachiOS/GoogleSignInProvider.swift` — conform to
  `SsoSignInProviding`; window lookup moves to the shared helper.
- `ios-app/UnicoachiOS/AuthClient.swift` — `GoogleAuthenticating` →
  `SsoAuthenticating` with `signIn(with:)`; both branches on `AuthClient`.
- `ios-app/UnicoachiOS/Models.swift` — `AppleLoginRequest`.
- `ios-app/UnicoachiOS/LoginViewModel.swift` — `SignInPhase.ssoLoading`, unified
  sign-in body, `signInWithApple()`, tagged `mapProviderError`,
  `account_email_not_verified` copy.
- `ios-app/UnicoachiOS/LoginView.swift` — Apple button above Google; retyped
  init; preview conformers.
- `ios-app/UnicoachiOS/AuthFlowView.swift`,
  `ios-app/UnicoachiOS/AppViewModel.swift`,
  `ios-app/UnicoachiOS/UnicoachiOSApp.swift` — own and thread the Apple
  provider; retyped `authClient`.

### iOS tests

- `ios-app/UnicoachiOSTests/MockSsoSignInProvider.swift` — **new**, replacing
  `MockGoogleSignInProvider.swift` (**deleted**).
- `ios-app/UnicoachiOSTests/MockAppleNameStore.swift` — **new**.
- `ios-app/UnicoachiOSTests/AppleSignInProviderTests.swift` — **new**.
- `ios-app/UnicoachiOSTests/MockAuthClient.swift` — `SsoAuthenticating`;
  captured credentials.
- `ios-app/UnicoachiOSTests/LoginViewModelTests.swift` — Apple cases; retargeted
  Google cases; both inline doubles updated.
- `ios-app/UnicoachiOSTests/AuthClientTests.swift` — Apple transport cases;
  retargeted Google cases.

### Project and documentation

- `ios-app/UnicoachiOS/UnicoachiOS.entitlements` — **new**:
  `com.apple.developer.applesignin`.
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` — `CODE_SIGN_ENTITLEMENTS`;
  target membership for the seven new files; removal of the deleted mock. No
  package reference is added.
- `ios-app/DEPLOY.md` — bundle-identifier ↔ `APPLE_CLIENT_IDS` coupling; App ID
  capability prerequisite.
