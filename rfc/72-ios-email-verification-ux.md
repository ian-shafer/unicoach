# RFC 72: iOS email-verification UX

## Executive Summary

The iOS client has no email-verification awareness, so an authenticated but
unverified user is broken: `AppViewModel.resolveProfileState` calls
`GET /api/v1/students/me`, the server-side verification gate answers
`403 email_not_verified`, and that unrecognized 4xx falls through to
`.unexpectedError` — the generic "Something Went Wrong" screen. The backend
verification mechanism (the `emailVerified` flag on `PublicUser`, the gate, and
the `resend-verification` / `change-email` endpoints) already exists; the client
does not consume it.

This RFC adds a `verificationRequired(PublicUser)` state to `UserAuthState`,
derived from `emailVerified == false`, and a blocked screen that owns the
verification lifecycle. The state machine routes every unverified session — from
login, register, session restore, or the gate's defensive `403` — to that
screen, which gates all other navigation until verification succeeds.

The screen offers four actions: resend the verification email
(`POST /api/v1/auth/resend-verification`), change the email address
(`POST /api/v1/auth/change-email`, in a modal sheet), check again (re-runs
`me()`), and log out. Because deep-link routing is out of scope, the
`emailVerified` flip is observed by re-running `me()` — manually via "Check
again" and silently when the app returns to the foreground.

Out of scope: universal-link / deep-link routing, and all backend work.

## Detailed Design

### Data Models

`PublicUser` (`ios-app/UnicoachiOS/Models.swift`) gains a required field:

```swift
struct PublicUser: Codable {
    let id: UUID
    let email: String
    let name: String
    let emailVerified: Bool
}
```

The field is required (no default). A defaulted `Bool` would let a verified user
decode as unverified if the server ever omitted the key, silently routing them
to the blocked screen; the backend always serializes `emailVerified`, so a
required field is both safe and faithful to the wire contract. Adding it forces
every `PublicUser(...)` construction site (27 sites across 7 files; see
`Files Modified`) to pass the field.

Two DTOs for change-email, mirroring the existing request/response pairs:

```swift
struct ChangeEmailRequest: Codable { let email: String }
struct ChangeEmailResponse: Codable { let user: PublicUser }
```

`UserAuthState` (`ios-app/UnicoachiOS/UserAuthState.swift`) gains a case
carrying the user whose email is unverified (the screen displays the address):

```swift
case verificationRequired(PublicUser)
```

Its equality arm compares by `id`, matching the existing `onboarding` /
`authenticated` payload cases:

```swift
case (.verificationRequired(let lhsUser), .verificationRequired(let rhsUser)):
    return lhsUser.id == rhsUser.id
```

### API Contracts

The backend endpoints already exist; the client adds two `AuthClient`
(`ios-app/UnicoachiOS/AuthClient.swift`) methods on `AuthClientProtocol`:

```swift
func resendVerification() async throws
func changeEmail(_ email: String) async throws -> PublicUser
```

- `resendVerification` issues `POST /api/v1/auth/resend-verification` with no
  body and asserts `204` via `apiClient.expect`, mirroring `logout`. The backend
  collapses both "sent" and "already verified" to `204`; `401 unauthorized`
  surfaces as a thrown `ErrorResponse`.
- `changeEmail` issues `POST /api/v1/auth/change-email` with a
  `ChangeEmailRequest`, decodes `200` into `ChangeEmailResponse` via
  `apiClient.decode`, and returns its `.user`. The returned user has
  `emailVerified == false` (the backend resets verification on email change).
  `400 validation_failed` (with `FieldError("email", …)`), `409 conflict`, and
  `401 unauthorized` surface as thrown `ErrorResponse`s carrying `status`.

`MockAuthClient` (`ios-app/UnicoachiOSTests/MockAuthClient.swift`) gains
`resendVerificationResult: Result<Void, Error>?` and
`changeEmailResult: Result<PublicUser, Error>?`, following its existing
result-stub pattern.

### Routing (`AppViewModel`)

`resolveProfileState` (`ios-app/UnicoachiOS/AppViewModel.swift`) gains a guard
at its head: when the routed `user.emailVerified` is `false`, it sets
`authState` to `.verificationRequired(user)` and returns without fetching the
profile; otherwise the existing `fetchProfile()` onboarding/authenticated logic
runs unchanged.

Gating before `fetchProfile()` is required, not cosmetic: the server-side
verification gate rejects `students/me` for an unverified caller, so the call
does not succeed while the gate is in force. This single insertion routes login
(`onLoginSuccess`), register (`onRegisterSuccess`, always unverified at this
point), and session restore (`checkSession`) through the blocked screen, since
all three funnel through `resolveProfileState`.

The `resolveProfileState` catch block gains one arm: an `ErrorResponse` whose
`code` is `email_not_verified` sets `authState` to
`.verificationRequired(user)`. Its position in the existing chain is after the
`unauthorized` arm and before the `status >= 500` arm (the live chain is
`TIMEOUT`/`NETWORK_ERROR` → `unauthorized` → new `email_not_verified` →
`status >= 500` → `.unexpectedError`).

This is defensive: client-side gating means `fetchProfile()` is normally not
called while unverified, but a race (the `me()`/login response said verified, a
concurrent change-email reset the flag) can still yield the gate's
`403 email_not_verified`. The branch maps it to the blocked screen instead of
today's `.unexpectedError`, and it has the `user` in scope to carry.

A re-check entry point re-runs `me()` and reports an outcome without unwinding
the screen on transient failure:

```swift
enum VerificationRecheckOutcome { case verified, stillUnverified, failed }
func recheckVerification() async -> VerificationRecheckOutcome
```

Behaviour:

- `me()` returns a verified user → `resolveProfileState(user)` runs (transitions
  to onboarding/authenticated, leaving the screen); returns `.verified`.
- `me()` returns an unverified user → no state change (already
  `.verificationRequired`); returns `.stillUnverified`.
- `me()` throws `unauthorized` → `authState = .unauthenticated`; returns
  `.failed` (the screen is torn down regardless).
- `me()` throws any other error (timeout, network, 5xx, decode) → no state
  change; returns `.failed`. Unlike `checkSession`, a re-check failure does
  **not** route to `.noConnectivity` / `.serverError`; the user stays on the
  blocked screen and the screen renders inline feedback, preserving verification
  context.

### Foreground auto re-check (`UnicoachiOSApp`)

`UnicoachiOSApp` (`ios-app/UnicoachiOS/UnicoachiOSApp.swift`) observes
`@Environment(\.scenePhase)`. On transition to `.active`, when and only when
`viewModel.authState` is `.verificationRequired`, it launches a `Task` calling
`viewModel.recheckVerification()` and ignores the outcome (silent). Every
`.active` transition fires a re-check; there is no debounce or throttle. This is
the primary detection path: the user must leave the app to open the verification
link (deep-linking is out of scope), so returning to the foreground is the
natural trigger. The root `switch` gains the `.verificationRequired(let user)`
case rendering `VerificationRequiredView`.

### Screen (`VerificationRequiredView` + view models)

`VerificationRequiredView`
(`ios-app/UnicoachiOS/VerificationRequiredView.swift`) renders the gated email
address and four actions, reusing the existing design system (`LoadingButton`,
`DestructiveButtonStyle`, `LabeledField`, `FormErrorBanner`, `FieldErrorText`).
It owns a `VerificationViewModel` (`@StateObject`) and presents the change-email
sheet.

`VerificationViewModel` (`ios-app/UnicoachiOS/VerificationViewModel.swift`),
`@MainActor`, `ObservableObject`:

- `@Published email: String` — seeded from the routed user, updated on a
  successful change-email so the displayed address tracks the change.
- `@Published changeConfirmation: String?` — the "verification sent to <new
  addres" message set when a change-email succeeds; distinct from
  `resendConfirmation` so the two confirmations do not collide.
- `@Published isResending: Bool`, `@Published resendConfirmation: String?`,
  `@Published resendError: ErrorResponse?`.
- `@Published isChecking: Bool`, `@Published recheckMessage: String?` — drives
  the "Checking…" indicator and the disabled state of the Check-again button,
  plus the "still not verified" / failure inline message.
- Dependencies: the `AuthClientProtocol`, the routed user, and callbacks
  `onRecheck: () async -> VerificationRecheckOutcome` and
  `onLogout: () async -> Void` (bound to `AppViewModel.recheckVerification` and
  `AppViewModel.logout`).
- `resend()` sets `isResending`, calls `authClient.resendVerification()`, and on
  success sets `resendConfirmation`; on `ErrorResponse` sets `resendError`.
- `checkAgain()` sets `isChecking`, awaits `onRecheck()`, and on
  `.stillUnverified` / `.failed` sets `recheckMessage` (on `.verified` the
  screen is torn down before the message renders).

`ChangeEmailViewModel` (`ios-app/UnicoachiOS/ChangeEmailViewModel.swift`),
`@MainActor`, `ObservableObject`, drives the sheet, mirroring `LoginViewModel`:

- `@Published email: String`, `@Published isLoading: Bool` (the in-flight flag,
  matching `LoginViewModel`), `@Published errorResponse: ErrorResponse?`.
- Dependencies: the `AuthClientProtocol` and an
  `onChanged: (PublicUser) -> Void` callback.
- `submit()` guards only against an empty `email` (a local pre-flight check); it
  does **not** validate email format, deferring all format checks to the server.
  On a non-empty value it calls `authClient.changeEmail(email)`. On success it
  invokes `onChanged(user)`; the parent `VerificationViewModel` sets both
  `email` (the new address) and `changeConfirmation` (the "verification sent to
  <new address>" message), then the view dismisses the sheet. A thrown
  `ErrorResponse` populates `errorResponse`; the view maps `fieldErrors`/`code`
  to `FieldErrorText` (`400 validation_failed`, including a
  malformed-but-non-empty address) and `FormErrorBanner` (`409 conflict`).

### Error Handling / Edge Cases

- **Register lands unverified.** A newly registered user is always unverified,
  so `onRegisterSuccess` routes straight to the blocked screen; onboarding
  happens only after verification.
- **Gate 403 race.** See the `email_not_verified` catch arm in Routing.
- **`me()` is gate-exempt.** `auth/me` is on the gate allowlist, so it returns
  the `PublicUser` (with `emailVerified`) even while unverified;
  `recheckVerification` and `checkSession` can always read the current flag. A
  hypothetical `email_not_verified` from `me()` itself carries no user and
  cannot route to `.verificationRequired`; it remains an unhandled 4xx →
  `.unexpectedError` (the existing `checkSession` fallback, unchanged).
- **Change-email keeps the user unverified.** The `200` response carries
  `emailVerified == false`; the screen stays, with the displayed address updated
  and a fresh-link confirmation. No state transition occurs.
- **Repeated resend.** Each resend is independent; the button is disabled only
  while a request is in flight. Per-user send rate-limiting is a backend
  concern, out of scope here.
- **Re-check transient failure.** Stays on the screen with inline feedback (see
  Routing); the user can retry via Check again or by re-foregrounding.
- **Logout from the blocked screen.** Reuses `AppViewModel.logout()` (clears
  cookies → `.unauthenticated`), the escape hatch for abandoning an unverifiable
  account.

### Dependencies

No new third-party dependencies. The screen reuses existing design-system
components and the existing `APIClient` request/decode surface. The backend
endpoints (`resend-verification`, `change-email`) and the `emailVerified` field
already exist on `main`.

## Tests

iOS tests run via `xcodebuild` (scheme `UnicoachiOS`), outside the Nix dev
shell.

The `scenePhase`-driven foreground auto re-check is view-layer glue and is not
tested directly; its behaviour is covered transitively by the
`recheckVerification` unit tests below, matching how the RFC treats other root
`switch` / view wiring.

### `AppViewModelTests` (extend) — `ios-app/UnicoachiOSTests/AppViewModelTests.swift`

- All existing tests that build a `PublicUser` and expect
  onboarding/authenticated outcomes are updated to pass `emailVerified: true`,
  otherwise the new short-circuit routes them to `.verificationRequired`.
- `onLoginSuccess` with an `emailVerified == false` user →
  `.verificationRequired(user)`; `fetchProfile` is never called
  (`fetchProfileCallCount == 0`).
- `onRegisterSuccess` with an unverified user → `.verificationRequired(user)`.
- `checkSession` where `me()` returns an unverified user →
  `.verificationRequired(user)`; `fetchProfile` not called.
- `resolveProfileState` where the user is verified but `fetchProfile` throws
  `email_not_verified` (status `403`) → `.verificationRequired(user)` (the
  defensive race arm).
- The two existing tests that used `email_not_verified` as the generic
  unhandled-4xx example switch to a genuinely unhandled code (e.g.
  `code: "teapot"`, status `418`) so they keep asserting the `.unexpectedError`
  fallback. The swap is mandatory for
  `testCheckSessionProfileFetchUnexpectedErrorOnUnhandled4xx` (it routes through
  `resolveProfileState`'s catch, whose new arm now claims `email_not_verified`,
  so it would otherwise flip to `.verificationRequired`); for
  `testCheckSessionUnexpectedErrorOnUnhandled4xx` (routed through
  `checkSession`'s unchanged catch) it is cosmetic consistency, avoiding
  `email_not_verified` masquerading as a generic unhandled code.
- `recheckVerification` when `me()` returns a verified user with a profile →
  `.authenticated`, returns `.verified` (with `fetchProfileResult` stubbed to a
  profile).
- `recheckVerification` when `me()` returns an unverified user → state unchanged
  (`.verificationRequired`), returns `.stillUnverified`.
- `recheckVerification` when `me()` throws `unauthorized` → `.unauthenticated`,
  returns `.failed`.
- `recheckVerification` when `me()` throws a timeout → state unchanged, returns
  `.failed` (does not route to `.noConnectivity`).

### `AuthClientTests` (extend) — `ios-app/UnicoachiOSTests/AuthClientTests.swift`

- `resendVerification` issues `POST /api/v1/auth/resend-verification` and
  succeeds on `204`.
- `resendVerification` throws the decoded `ErrorResponse` on `401`.
- `changeEmail` issues `POST /api/v1/auth/change-email` with the email body and
  returns the decoded `PublicUser` on `200`.
- `changeEmail` throws the decoded `ErrorResponse` (with `status` and
  `fieldErrors`) on `400` and on `409`.

### `VerificationViewModelTests` (new) — `ios-app/UnicoachiOSTests/VerificationViewModelTests.swift`

- `resend` success sets `resendConfirmation`, clears `resendError`, and toggles
  `isResending` off.
- `resend` failure (`ErrorResponse`) sets `resendError`.
- `checkAgain` toggles `isChecking` around the call and, on a `.stillUnverified`
  outcome, sets `recheckMessage`.
- `checkAgain` on a `.failed` outcome sets `recheckMessage`.

### `ChangeEmailViewModelTests` (new) — `ios-app/UnicoachiOSTests/ChangeEmailViewModelTests.swift`

- `submit` with an empty email sets `errorResponse` without calling the client.
- `submit` success invokes `onChanged` with the returned user and leaves
  `errorResponse` nil.
- `submit` with a malformed-but-non-empty email calls the client (no local
  format guard) and maps the server's `400 validation_failed` (with
  `FieldError("email", …)`) into `errorResponse`, confirming format validation
  is deferred to the server.
- `submit` mapping a `409 conflict` into `errorResponse`.

## Implementation Plan

iOS builds/tests run via `xcodebuild` (scheme `UnicoachiOS`), not `bin/test`.
After each step, build with:
`xcodebuild build -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16'`
(use an installed simulator). Run the suite with `xcodebuild test` on the same
scheme/destination.

1. **Model.** Add `emailVerified: Bool` to `PublicUser`; add
   `ChangeEmailRequest` / `ChangeEmailResponse`. Update every `PublicUser(...)`
   construction site flagged in `Files Modified` (27 sites across the
   app-preview and test files, including `AuthClientTests.swift`) to pass
   `emailVerified:` — `true` for sites whose tests expect
   onboarding/authenticated, otherwise any value.
   - Verify: `xcodebuild build`.

2. **State machine.** Add `verificationRequired(PublicUser)` and its `==` arm to
   `UserAuthState`.
   - Verify: `xcodebuild build`.

3. **AuthClient.** Add `resendVerification` and `changeEmail` to
   `AuthClientProtocol` and `AuthClient`; add the result stubs to
   `MockAuthClient`.
   - Verify: `xcodebuild build`.

4. **AppViewModel routing.** Add the `emailVerified` short-circuit and the
   `email_not_verified` catch arm to `resolveProfileState`; add
   `VerificationRecheckOutcome` and `recheckVerification`.
   - Verify: `xcodebuild build`.

5. **View models.** Add `VerificationViewModel` and `ChangeEmailViewModel`.
   - Verify: `xcodebuild build`.

6. **View + root wiring.** Add `VerificationRequiredView` (screen + change-email
   sheet); add the `.verificationRequired` case to the `UnicoachiOSApp` root
   `switch` and the `scenePhase`-driven foreground re-check. Register every new
   source file in `ios-app/UnicoachiOS.xcodeproj/project.pbxproj`
   (`PBXFileReference`, `PBXBuildFile`, group membership, and the app target's
   `Sources` build phase).
   - Verify: `xcodebuild build`.

7. **Tests.** Extend `AppViewModelTests` and `AuthClientTests`; add
   `VerificationViewModelTests` and `ChangeEmailViewModelTests`. Register the
   two new test files in `project.pbxproj` (under the test target's `Sources`
   build phase).
   - Verify: `xcodebuild test` — full suite green.

## Files Modified

Created:

- `rfc/72-ios-email-verification-ux.md`
- `ios-app/UnicoachiOS/VerificationRequiredView.swift`
- `ios-app/UnicoachiOS/VerificationViewModel.swift`
- `ios-app/UnicoachiOS/ChangeEmailViewModel.swift`
- `ios-app/UnicoachiOSTests/VerificationViewModelTests.swift`
- `ios-app/UnicoachiOSTests/ChangeEmailViewModelTests.swift`

Modified:

- `ios-app/UnicoachiOS/Models.swift`
- `ios-app/UnicoachiOS/UserAuthState.swift`
- `ios-app/UnicoachiOS/AuthClient.swift`
- `ios-app/UnicoachiOS/AppViewModel.swift`
- `ios-app/UnicoachiOS/UnicoachiOSApp.swift`
- `ios-app/UnicoachiOS/RegistrationView.swift` (preview `PublicUser` sites)
- `ios-app/UnicoachiOS/LoginView.swift` (preview `PublicUser` sites)
- `ios-app/UnicoachiOS/HomeView.swift` (preview `PublicUser` site)
- `ios-app/UnicoachiOSTests/MockAuthClient.swift`
- `ios-app/UnicoachiOSTests/AppViewModelTests.swift` (`PublicUser` sites + new
  assertions)
- `ios-app/UnicoachiOSTests/AuthClientTests.swift` (`PublicUser` sites + new
  assertions)
- `ios-app/UnicoachiOSTests/RegistrationViewModelTests.swift` (`PublicUser`
  sites)
- `ios-app/UnicoachiOSTests/LoginViewModelTests.swift` (`PublicUser` sites)
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj`
