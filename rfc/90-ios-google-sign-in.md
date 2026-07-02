# RFC 90: iOS Google Sign-In

## Executive Summary

This RFC adds "Sign in with Google" to the UnicoachiOS SwiftUI client. It is the
iOS-client half of Google SSO; the backend contract shipped in
[RFC 64](64-google-sso-login.md) and is live. The app obtains a native Google ID
token, POSTs `{ "idToken": "<token>" }` to `POST /api/v1/auth/google`, and the
server verifies the token, links or creates the user, and sets the session
cookie exactly as password login does.

The client work is: add the `GoogleSignIn-iOS` SDK via Swift Package Manager
(the project's first SPM dependency); configure the iOS OAuth client ID and its
redirect URL scheme in `Info.plist`; place Google's `GIDSignInButton` in the
existing login UI; add a `signInWithGoogle(idToken:)` path to `AuthClient`; and
handle in `LoginViewModel` every outcome RFC 64 defines (success plus the
`unauthorized` / `email_not_verified` / `account_disabled` /
`service_unavailable` rejections; auth routes use lowercase error codes). The
Google method lives on a narrow `GoogleAuthenticating` protocol, not on
`AuthClientProtocol` itself, so the three preview/test conformers that never
build a `LoginViewModel` stay untouched with no stub or default required.

The SDK call is presentation-coupled and shows real UI, so it sits behind a
`GoogleSignInProviding` protocol returning a `GoogleSignInOutcome` — signed-in
token or user cancellation — keeping `LoginViewModel` unit-testable with a mock
and letting cancellation be handled as an ordinary result rather than a caught
error. On success the response flows through the existing `onLoginSuccess`
callback and `UserAuthState` machine unchanged.

Scope is iOS-client-only; no backend, schema, or config changes. The iOS client
ID (`681996899529-dmtovdf2r2ptf0mcajic3squcrkhsip6`, pinned in `.env.prod`'s
`GOOGLE_CLIENT_IDS`) must already be a registered backend audience — a
documented, unenforced cross-artifact coupling. End-to-end sign-in is verified
on a simulator against the deployed backend as part of implementation.

## Detailed Design

### Dependency

`GoogleSignIn-iOS` (`https://github.com/google/GoogleSignIn-iOS`), product
`GoogleSignIn`, added to the `UnicoachiOS` app target via SPM with the
up-to-next-major constraint `from: "9.0.0"` (latest at design time is 9.2.0).
`project.pbxproj` currently holds **zero** package references, so implementation
adds the first `XCRemoteSwiftPackageReference`, its
`XCSwiftPackageProductDependency`, and the product's membership in the app
target's `Frameworks` build phase. This is the highest-risk mechanical step; a
real `xcodebuild` compile is the gate (see Implementation Plan).

### Configuration (`Info.plist`)

The iOS OAuth client ID is a single fixed, public (non-secret) constant that
does not vary by environment, so it is written literally into `Info.plist`
rather than injected through the `bin/build-ios` / `ios-app/env` build-setting
seam used for the environment-specific `UNICOACH_BACKEND_URL` /
`UNICOACH_CLIENT_KEY`. Two keys are added:

- `GIDClientID` —
  `681996899529-dmtovdf2r2ptf0mcajic3squcrkhsip6.apps.googleusercontent.com`,
  the iOS OAuth client ID. The SDK reads this key from `Info.plist`
  automatically; no `GIDConfiguration` is constructed in code.
- `CFBundleURLTypes` — one URL type whose `CFBundleURLSchemes` is the
  **reversed** client ID
  (`com.googleusercontent.apps.681996899529-dmtovdf2r2ptf0mcajic3squcrkhsip6`),
  the OAuth redirect scheme the SDK requires.

The concrete client-ID value is the existing Google Cloud iOS OAuth client,
already pinned in `.env.prod`'s `GOOGLE_CLIENT_IDS` (the audiences
`JwksGoogleTokenVerifier` accepts, per RFC 64) — that variable is the source of
truth for the coupling. A mismatch makes every sign-in fail verification
(`401
unauthorized`). This coupling cannot be derived or enforced across the
iOS/backend artifact boundary, so it is documented here and in a comment
adjacent to the `Info.plist` keys — the "document the coupling" tier of the
no-remote-breakage rule.

### SDK seam — `GoogleSignInProviding`

`GIDSignIn.sharedInstance.signIn(withPresenting:)` needs a presenting
`UIViewController` and drives interactive UI, so it is unusable in unit tests
and must be isolated. A protocol wraps it; the outcome logic in `LoginViewModel`
depends only on the protocol.

```swift
@MainActor
protocol GoogleSignInProviding {
    // Presents Google's account chooser; returns the outcome. Throws only for
    // genuine failures — user cancellation is a returned outcome, not one.
    func signIn() async throws -> GoogleSignInOutcome
}

enum GoogleSignInOutcome {
    case signedIn(String) // the resulting ID token string
    case cancelled        // user dismissed the sheet — an ordinary outcome
}

enum GoogleSignInError: Error {
    case presentationUnavailable // no key-window root view controller found
    case missingIdToken          // GIDSignInResult carried no ID token
    case sdkError(Error)         // any other GIDSignIn failure
}
```

User dismissal of the account chooser is an expected domain state, not a
failure, so it is modeled as a `GoogleSignInOutcome` case rather than a thrown
`GoogleSignInError`; `throws` is reserved for the three genuine failure modes.

`GoogleSignInProvider` (production) resolves the presenting view controller from
the active foreground `UIWindowScene`'s key-window `rootViewController`
(throwing `presentationUnavailable` when absent), calls
`GIDSignIn.sharedInstance.signIn(withPresenting:)`, and returns
`.signedIn(result.user.idToken!.tokenString)` (throwing `missingIdToken` when
the token is nil). It maps `GIDSignInError.canceled` to the returned
`.cancelled` outcome and any other thrown error to `.sdkError`. It takes no
client ID — the SDK self-configures from `Info.plist`'s `GIDClientID`.

The provider is owned by `AppViewModel` (default `GoogleSignInProvider()`,
injectable for tests) and threaded
`AppViewModel → UnicoachiOSApp → AuthFlowView → LoginView → LoginViewModel`,
mirroring how `authClient` is owned and passed.

### OAuth redirect handling (`UnicoachiOSApp`)

The root scene gains `.onOpenURL { GIDSignIn.sharedInstance.handle($0) }` so the
SDK receives the OAuth callback URL under the SwiftUI `App` lifecycle (there is
no `UIApplicationDelegate`). This is the only change to `UnicoachiOSApp` beyond
passing the provider into `AuthFlowView`.

### API Contract — `GoogleAuthenticating`

```swift
protocol GoogleAuthenticating {
    func signInWithGoogle(idToken: String) async throws -> LoginResponse
}
```

`AuthClientProtocol` has seven conformers in the worktree — `AuthClient`,
`MockAuthClient`, the three preview clients (`LoginPreviewAuthClient`,
`RegistrationPreviewAuthClient`, `VerificationPreviewAuthClient`), and the two
inline `DelayedAuthClient` classes in `LoginViewModelTests` and
`RegistrationViewModelTests` — each of which exhaustively implements every
protocol member. Adding `signInWithGoogle` as a bare `AuthClientProtocol`
requirement would break all seven at compile time; a default-throwing extension
would satisfy the compiler but plant a sentinel member on the protocol whose
only purpose is to be unreachable, which the codebase's no-sentinels standard
rejects. Instead, `signInWithGoogle` lives entirely on the separate
`GoogleAuthenticating` protocol — `AuthClientProtocol` itself is **not modified
by this RFC**. `LoginViewModel`'s `authClient` property is typed
`AuthClientProtocol & GoogleAuthenticating`, so only conformers of both satisfy
it.

`GoogleAuthenticating` is adopted by four of the seven `AuthClientProtocol`
conformers:

- `AuthClient` (the real POST) and `MockAuthClient` (returns
  `signInWithGoogleResult`) — the two conformers that perform actual Google
  behaviour.
- `LoginPreviewAuthClient` (`LoginView.swift`'s `#Preview`) and
  `LoginViewModelTests`' `DelayedAuthClient` — the two fakes that construct a
  `LoginViewModel` and must therefore satisfy its
  `AuthClientProtocol & GoogleAuthenticating` constraint. Each implements
  `signInWithGoogle` explicitly (no shared default exists to inherit).

The remaining three conformers never construct a `LoginViewModel` and are not
touched: `RegistrationPreviewAuthClient`, `VerificationPreviewAuthClient`, and
`RegistrationViewModelTests`' `DelayedAuthClient`.

The `AuthClient` implementation POSTs `GoogleLoginRequest` to
`/api/v1/auth/google` and decodes `expectedStatus: 200`, identical in shape to
`login`. The session cookie in the `Set-Cookie` response header is stored
automatically by the shared `HTTPCookieStorage` backing `URLSession` — the same
automatic persistence the password path relies on; no cookie handling is added.
Reuses the existing `LoginResponse` / `PublicUser`.

```swift
// Models.swift
struct GoogleLoginRequest: Codable {
    let idToken: String
}
```

`APIClient.decode` already maps non-200 responses: a parseable error body
becomes an `ErrorResponse` with its lowercase `code` and stamped `status`; an
unparseable `5xx` body becomes the synthesized `SERVER_ERROR`; transport
failures become `TIMEOUT` / `NETWORK_ERROR`. No `APIClient` change is needed.

### Loading state — `SignInPhase`

```swift
enum SignInPhase {
    case idle
    case passwordLoading
    case googleLoading
}
```

`LoginViewModel`'s two independent `@Published var isLoading` /
`isGoogleLoading` booleans are replaced by a single
`@Published var phase: SignInPhase`. The password and Google flows are mutually
exclusive — each button is already disabled whenever either is loading — so two
booleans admit an impossible both-loading state that a reader has to reason
past; `SignInPhase` makes it unrepresentable. `login()` sets
`phase = .passwordLoading` (reset to `.idle` via `defer`); `signInWithGoogle()`
sets `phase = .googleLoading` (same). `LoginView` derives the shared disabled
state and the Google spinner from `phase` (see UI below).

### Outcome handling (`LoginViewModel.signInWithGoogle`)

A new `signInWithGoogle()` method is added. `login()` is unchanged apart from
now setting `phase` instead of `isLoading`. The method runs the provider, then
the client, mapping outcomes exactly as `login()` maps client failures:

1. Clear `errorResponse` / `infrastructureError`; set `phase = .googleLoading`
   (reset to `.idle` via `defer`).
2. `try await googleSignInProvider.signIn()`:
   - `.cancelled` outcome → return silently (no banner, no callback) — an
     ordinary branch of the result, not a caught error.
   - `.signedIn(idToken)` outcome → proceed to step 3 with `idToken`.
   - any thrown `GoogleSignInError` (`presentationUnavailable`,
     `missingIdToken`, `sdkError`) → set `errorResponse` with client-synthesized
     code `GOOGLE_SIGN_IN_FAILED` and a localized message; return.
3. `try await authClient.signInWithGoogle(idToken:)`:
   - success → `await onLoginSuccess(response.user)`.
   - `ErrorResponse` with code `TIMEOUT` → `infrastructureError = .timeout`;
     `NETWORK_ERROR` → `.noConnectivity`; `SERVER_ERROR` → `.serverError`.
   - any other `ErrorResponse` (`unauthorized`, `email_not_verified`,
     `account_disabled`, `service_unavailable`) → `errorResponse = error`
     (inline `FormErrorBanner`, showing the server message).
   - any non-`ErrorResponse` throw → `infrastructureError = .serverError`.

Design notes:

- The four server outcomes render as an inline banner rather than routing to a
  screen. `email_not_verified` here is a **pre-session rejection** — the Google
  account's own email is unverified and no session was established — which is
  categorically different from the app's `verificationRequired` state (an
  authenticated user awaiting verification). It therefore must **not** enter
  that flow; a banner is correct. `account_disabled` and `service_unavailable`
  (transient Google JWKS outage) likewise leave the user on the login screen,
  where the Google button is immediately available to retry.
- `GOOGLE_SIGN_IN_FAILED` is UPPERCASE by the existing `ios-app/UnicoachiOS`
  casing rule: client-synthesized codes stay UPPERCASE, disjoint from backend
  lowercase wire codes. It is only displayed, never branched on.
- `UserAuthState` is unchanged: success reuses `onLoginSuccess`, which routes
  through `resolveProfileState` exactly as password login does.

### UI (`LoginView`)

Google's `GIDSignInButton` is wrapped in a `UIViewRepresentable`
(`GoogleSignInButton`) whose tap runs
`Task { await viewModel.signInWithGoogle() }`. It is placed below the existing
"Log In" button, separated by an "or" divider. Both the Log In button and the
Google button are disabled while `viewModel.phase != .idle`; the Log In button's
own spinner is driven by `phase == .passwordLoading`, and a `ProgressView`
overlays the Google button while `phase == .googleLoading`. Using
`GIDSignInButton` keeps Google brand-guideline compliance and requires no
bundled logo asset. Existing `infrastructureError` full-screen handling in
`LoginView` already covers the `.timeout` / `.serverError` / `.noConnectivity`
outcomes the Google path can raise, so no new full-screen surface is added. That
cover's retry action calls `login()` — it re-runs the **password** path, not the
Google path; for a Google-only user with empty credential fields `login()`
short-circuits to the `VALIDATION` banner rather than retrying sign-in. Google
recovery is therefore re-tapping the Google button after dismissing the cover —
always available on the login screen — while the retry button stays correct for
the password path it belongs to.

### Error Handling / Edge Cases

- **User cancels the Google sheet:** provider returns the `.cancelled` outcome
  (not thrown); the method returns with no banner and no state change beyond
  `phase` resetting to `.idle`.
- **No presenting view controller:** provider throws `.presentationUnavailable`
  → `GOOGLE_SIGN_IN_FAILED` banner (not a crash).
- **ID token missing from the SDK result:** `.missingIdToken` →
  `GOOGLE_SIGN_IN_FAILED` banner; nothing is POSTed.
- **Concurrent taps:** the shared disabled state (`phase != .idle`) prevents a
  second in-flight sign-in; `SignInPhase` makes a simultaneous
  password-and-Google in-flight state unrepresentable.
- **Transient Google outage (`503 service_unavailable`):** inline banner with
  the server message; retryable in place.
- **Client ID absent from backend `GOOGLE_CLIENT_IDS`:** every token is rejected
  as `401 unauthorized`; surfaced as a banner. Prevented operationally by the
  documented coupling, not by client code.

### Dependencies

- `GoogleSignIn-iOS` SPM package (new; app target only). No other new
  dependencies. iOS builds and tests run via system `xcodebuild` (scheme
  `UnicoachiOS`, an installed simulator), outside the Nix dev shell and
  `bin/test`.

## Tests

iOS tests run via `xcodebuild test` (scheme `UnicoachiOS`), not `bin/test`. Two
layers, matching the existing grain (`AuthClientTests` drives `AuthClient`
through `MockURLProtocol`; `LoginViewModelTests` drives the view model through
mocks), plus one manual end-to-end pass.

### `AuthClientTests` (transport, via `MockURLProtocol`)

- `testGoogleSignInSuccess` — 200 with a `LoginResponse` body; asserts request
  path `/api/v1/auth/google`, method `POST`, `Content-Type: application/json`,
  and that the request body decodes to `GoogleLoginRequest(idToken:)` with the
  sent token; asserts the returned user.
- `testGoogleSignInUnauthorized` — 401 `{ "code": "unauthorized" }` → throws
  `ErrorResponse` with code `unauthorized`, `status == 401`.
- `testGoogleSignInEmailNotVerified` — 403 `{ "code": "email_not_verified" }` →
  code `email_not_verified`, `status == 403`.
- `testGoogleSignInAccountDisabled` — 403 `{ "code": "account_disabled" }` →
  code `account_disabled`, `status == 403`.
- `testGoogleSignInServiceUnavailable` — 503 `{ "code": "service_unavailable" }`
  → code `service_unavailable`, `status == 503`.
- `testGoogleSignInServerError` — 500 with an unparseable body → synthesized
  `SERVER_ERROR`.

### `LoginViewModelTests` (outcomes, via `MockAuthClient` + `MockGoogleSignInProvider`)

`MockGoogleSignInProvider.signInResult` is typed
`Result<GoogleSignInOutcome, Error>?`, so cancellation is configured as
`.success(.cancelled)` rather than `.failure(GoogleSignInError.cancelled)`.
`MockAuthClient` conforms to `GoogleAuthenticating` explicitly (adding
`signInWithGoogleResult`); the file's inline `DelayedAuthClient` (used by the
pre-existing `testLoadingStateToggles`) also conforms explicitly, since it
constructs a `LoginViewModel` and must satisfy
`AuthClientProtocol & GoogleAuthenticating` — its `signInWithGoogle` is
unexercised by that test and `fatalError()`s like its other unused members. That
pre-existing test's assertion on the loading flag changes from
`viewModel.isLoading` to `viewModel.phase == .passwordLoading`
(`viewModelRef?.isLoading` inside `DelayedAuthClient` likewise becomes
`viewModelRef?.phase == .passwordLoading`).

- `testGoogleSignInSuccessInvokesCallback` — provider returns
  `.signedIn(token)`, client returns a user → `onLoginSuccess` user matches; no
  `errorResponse`, no `infrastructureError`.
- `testGoogleSignInCancellationIsSilentNoOp` — provider returns `.cancelled` →
  no `errorResponse`, no `infrastructureError`, callback not invoked, client not
  called.
- `testGoogleSignInProviderFailureShowsBanner` — provider throws `.sdkError` →
  `errorResponse.code == "GOOGLE_SIGN_IN_FAILED"`; client not called.
- `testGoogleSignInUnauthorizedSetsErrorResponse` — client throws `unauthorized`
  → `errorResponse.code == "unauthorized"`, no `infrastructureError`.
- `testGoogleSignInEmailNotVerifiedSetsErrorResponse` — client throws
  `email_not_verified` → `errorResponse.code == "email_not_verified"` (asserts
  it does **not** enter a verification flow — the view model has no such
  transition).
- `testGoogleSignInAccountDisabledSetsErrorResponse` — client throws
  `account_disabled` → `errorResponse.code == "account_disabled"`.
- `testGoogleSignInServiceUnavailableSetsErrorResponse` — client throws
  `service_unavailable` (status 503) →
  `errorResponse.code ==
  "service_unavailable"`, no `infrastructureError`.
- `testGoogleSignInServerErrorSetsInfrastructureError` — client throws
  `SERVER_ERROR` → `infrastructureError == .serverError`, no `errorResponse`.
- `testGoogleSignInTimeoutSetsInfrastructureError` — client throws `TIMEOUT` →
  `infrastructureError == .timeout`.
- `testGoogleSignInLoadingStateToggles` — `phase == .googleLoading` while the
  (delayed) provider/client run and `phase == .idle` after.

### Manual end-to-end (in scope)

Build with `bin/build-ios prod-simulator`, which targets the live AWS deployment
(`https://api.uni.coach`, derived from `.env.prod`); that backend's
`GOOGLE_CLIENT_IDS` already carries the client ID, so the audience matches. (The
default `simulator` target instead derives `localhost:8080` from the repo
`.env`, whose config need not carry the audience — running the manual pass there
would spuriously fail with `401 unauthorized` unless the local `.env` sets the
same `GOOGLE_CLIENT_IDS`.) On that build, with the real `GIDClientID` baked into
`Info.plist`: tap "Sign in with Google", complete a real Google account chooser,
and confirm the app lands authenticated (session cookie set, `HomeView`/
onboarding reached). Verify both branches RFC 64 defines — a first-time Google
account creates a user; a returning/linked account logs in — and that cancelling
the sheet returns to the login screen with no error.

## Invariants

None. The client-ID ↔ backend `GOOGLE_CLIENT_IDS` coupling is captured as prose
in Detailed Design and an `Info.plist` comment, not as an `INVARIANTS.md` rule
(kept few and human-gated). The existing `ios-app/UnicoachiOS` casing invariant
is respected, not changed: the new `GOOGLE_SIGN_IN_FAILED` code is client-
synthesized and UPPERCASE.

## Implementation Plan

Each step is verified with system `xcodebuild` (outside the Nix dev shell). Use
the `simulator` env for a no-signing compile/test: `nix develop`-free, e.g.
`bin/build-ios simulator` and
`xcodebuild test -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 17 Pro'`.

1. **Add the SDK.** Add `GoogleSignIn-iOS` (product `GoogleSignIn`) to the
   `UnicoachiOS` target via SPM with the constraint `from: "9.0.0"`. Verify:
   `bin/build-ios simulator` compiles with the package resolved.
2. **Configure `Info.plist`.** Add `GIDClientID` (the concrete iOS OAuth client
   ID) and a `CFBundleURLTypes` entry with the reversed-client-ID scheme; add
   the coupling comment. Verify: `bin/build-ios simulator` still compiles; the
   built app's `Info.plist` contains both keys.
3. **Add `GoogleSignInProvider.swift`.** Define `GoogleSignInProviding` (its
   `signIn()` returns `GoogleSignInOutcome`), `GoogleSignInOutcome`
   (`.signedIn(String)` / `.cancelled`), `GoogleSignInError`
   (`presentationUnavailable` / `missingIdToken` / `sdkError` — no `.cancelled`
   case), and `GoogleSignInProvider`; add the file to the app target. Verify:
   `bin/build-ios simulator` compiles.
4. **Add `GoogleLoginRequest` and `GoogleAuthenticating`.** Define
   `GoogleAuthenticating`
   (`signInWithGoogle(idToken:) async throws ->
   LoginResponse`) as its own
   protocol — `AuthClientProtocol` is not modified; conform `AuthClient` to it
   with the real POST; add the `GoogleLoginRequest` model. Verify:
   `bin/build-ios simulator` compiles — the three conformers that never build a
   `LoginViewModel` (`RegistrationPreviewAuthClient`,
   `VerificationPreviewAuthClient`, `RegistrationViewModelTests`'
   `DelayedAuthClient`) are untouched and still satisfy `AuthClientProtocol`
   alone.
5. **Wire the view model.** Add `SignInPhase`, replacing `LoginViewModel`'s
   `isLoading` / `isGoogleLoading` booleans with a single
   `@Published var phase`; change the `authClient` property's type to
   `AuthClientProtocol & GoogleAuthenticating`; add `signInWithGoogle()` and the
   `googleSignInProvider` dependency; thread the provider through
   `AppViewModel`, `UnicoachiOSApp`, `AuthFlowView`, `LoginView`; add
   `.onOpenURL` to the root scene. Verify: compiles.
6. **Add the UI.** Add `GoogleSignInButton` (the `GIDSignInButton`
   `UIViewRepresentable`) and place it with the "or" divider in `LoginView`;
   derive the shared disabled state and Google spinner from `viewModel.phase`;
   conform `LoginPreviewAuthClient` to `GoogleAuthenticating` explicitly and
   update its preview to supply a provider. Verify: compiles; SwiftUI preview
   renders.
7. **Add tests.** Add `MockGoogleSignInProvider.swift`
   (`signInResult: Result<
   GoogleSignInOutcome, Error>?`); extend
   `MockAuthClient` with `: GoogleAuthenticating` conformance and
   `signInWithGoogleResult`; conform `LoginViewModelTests`' inline
   `DelayedAuthClient` to `GoogleAuthenticating` (unexercised `signInWithGoogle`
   `fatalError()`s like its other unused members) and update its and
   `testLoadingStateToggles`' assertions from `isLoading` to
   `phase == .passwordLoading`; add the `AuthClientTests` and
   `LoginViewModelTests` cases above; add the new test files to the test target.
   Verify: `xcodebuild test -scheme UnicoachiOS -destination
   '<simulator>'`
   passes, with the new tests among those executed.
8. **Manual end-to-end.** Build with `bin/build-ios prod-simulator` (targets the
   deployed backend, whose `GOOGLE_CLIENT_IDS` includes the client ID) and
   perform the manual pass above. Verify: real sign-in reaches the authenticated
   state; cancel returns cleanly.

## Files Modified

- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` — SPM package reference +
  product dependency; target membership for the new source and test files.
- `ios-app/UnicoachiOS/Info.plist` — `GIDClientID`, `CFBundleURLTypes`, coupling
  comment.
- `ios-app/UnicoachiOS/GoogleSignInProvider.swift` — **new**:
  `GoogleSignInProviding`, `GoogleSignInOutcome`, `GoogleSignInError`,
  production provider.
- `ios-app/UnicoachiOS/GoogleSignInButton.swift` — **new**: `GIDSignInButton`
  `UIViewRepresentable` wrapper.
- `ios-app/UnicoachiOS/Models.swift` — `GoogleLoginRequest`.
- `ios-app/UnicoachiOS/AuthClient.swift` — new `GoogleAuthenticating` protocol
  (`signInWithGoogle(idToken:)`), conformed by `AuthClient` with the real POST.
  `AuthClientProtocol` itself is not modified. (The three conformers that never
  build a `LoginViewModel` —
  `RegistrationView.swift`/`RegistrationPreviewAuthClient`,
  `VerificationRequiredView.swift`/`VerificationPreviewAuthClient`, and
  `RegistrationViewModelTests`' `DelayedAuthClient` — are untouched by this
  file's change and are not listed for it.)
- `ios-app/UnicoachiOS/LoginViewModel.swift` — `SignInPhase` (replacing
  `isLoading` / `isGoogleLoading`), `signInWithGoogle()`, provider dependency,
  `authClient` retyped to `AuthClientProtocol & GoogleAuthenticating`.
- `ios-app/UnicoachiOS/LoginView.swift` — Google button, "or" divider,
  `phase`-derived disabled state, provider param, preview update;
  `LoginPreviewAuthClient` conforms to `GoogleAuthenticating` explicitly.
- `ios-app/UnicoachiOS/AuthFlowView.swift` — thread the provider to `LoginView`.
- `ios-app/UnicoachiOS/AppViewModel.swift` — own `googleSignInProvider`
  (injectable default).
- `ios-app/UnicoachiOS/UnicoachiOSApp.swift` — `.onOpenURL` OAuth handling; pass
  the provider into `AuthFlowView`.
- `ios-app/UnicoachiOSTests/MockGoogleSignInProvider.swift` — **new**: mock
  provider (`signInResult: Result<GoogleSignInOutcome, Error>?`).
- `ios-app/UnicoachiOSTests/MockAuthClient.swift` — `: GoogleAuthenticating`
  conformance, `signInWithGoogleResult`.
- `ios-app/UnicoachiOSTests/AuthClientTests.swift` — Google transport cases.
- `ios-app/UnicoachiOSTests/LoginViewModelTests.swift` — Google outcome cases;
  inline `DelayedAuthClient` conforms to `GoogleAuthenticating` explicitly
  (unexercised `signInWithGoogle` `fatalError()`s); `testLoadingStateToggles`
  updated from `isLoading` to `phase == .passwordLoading`.
