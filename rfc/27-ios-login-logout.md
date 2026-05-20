# RFC-27: iOS Login / Logout

## Executive Summary

This specification defines the iOS app flows for email/password login and logout,
building on the registration-only app established in RFC-12. It introduces
session-aware root navigation driven by a `UserAuthState` enum, a startup
`/me` session check, `LoginView`/`LoginViewModel` for credential entry,
`HomeView` for the authenticated state with logout, and a reusable `ErrorView`
for infrastructure failures. The `AuthClient` is refactored to extract a shared
HTTP helper and expanded with `login()`, `logout()`, and `me()` methods. The
existing `RegistrationView`/`RegistrationViewModel` are updated to participate
in the auth lifecycle via success callbacks. No server-side changes are required
— the backend `/login`, `/logout`, and `/me` endpoints are already implemented.

## Detailed Design

### Auth State Model

A `UserAuthState` enum drives root view selection in `UnicoachiOSApp`:

```swift
enum UserAuthState {
    case loading
    case unauthenticated
    case authenticated(PublicUser)
    case serverError
    case noConnectivity
}
```

`AppViewModel` owns a `@Published var authState: UserAuthState` initialized to
`.loading`. It is the single source of truth for authentication state. It owns
the `AuthClient` instance and exposes `checkSession()`, `onLoginSuccess(_:)`,
`onRegisterSuccess(_:)`, and `logout()` mutations.

### App Startup Flow

On launch, `UnicoachiOSApp` renders based on `AppViewModel.authState`:

1. Initial state is `.loading` — a spinner is displayed.
2. `.task` fires `AppViewModel.checkSession()`, which calls
   `AuthClient.me()` (`GET /api/v1/auth/me`).
3. State transitions:

| `/me` outcome | `UserAuthState` |
|---|---|
| `200 OK` with valid `MeResponse` | `.authenticated(user)` |
| `401 Unauthorized` | `.unauthenticated` |
| `NSURLErrorNotConnectedToInternet`, `NSURLErrorTimedOut`, `NSURLErrorCannotFindHost` | `.noConnectivity` |
| Any other HTTP status or decode failure | `.serverError` |

Cookie persistence is handled automatically by `HTTPCookieStorage.shared` via
`URLSessionConfiguration.default`. A session cookie set during a prior
registration or login survives app relaunch without manual storage.

### Root View Coordinator

`UnicoachiOSApp` switches on `authState`:

- `.loading` → `ProgressView`
- `.unauthenticated` → `AuthFlowView(authClient: viewModel.authClient, ...)`
- `.authenticated(let user)` → `HomeView(user: user, onLogout: viewModel.logout)`
- `.serverError` → `ErrorView` with "Something Went Wrong" copy and retry
- `.noConnectivity` → `ErrorView` with "No Connection" copy and retry

### AuthFlowView — Login ↔ Registration Navigation

`AuthFlowView` holds a `@State private var showingRegistration: Bool = false`
and swaps between `LoginView` and `RegistrationView` as peer views with a
transition animation. No `NavigationStack` is used — these are peer screens,
not a parent-child hierarchy.

```swift
struct AuthFlowView: View {
    let authClient: AuthClientProtocol
    @State private var showingRegistration = false
    let onLoginSuccess: (PublicUser) -> Void
    let onRegisterSuccess: (PublicUser) -> Void
    // ...
    if showingRegistration {
        RegistrationView(
            authClient: authClient,
            onRegisterSuccess: onRegisterSuccess,
            onSwitchToLogin: { showingRegistration = false }
        )
    } else {
        LoginView(
            authClient: authClient,
            onLoginSuccess: onLoginSuccess,
            onSwitchToRegister: { showingRegistration = true }
        )
    }
}
```

### AuthClient Refactor

The current `AuthClient` has a single `register()` method with inline HTTP
boilerplate. This RFC refactors it to extract two private helpers:

- `performRequest<T: Decodable>(method:path:body:expectedStatus:) async throws -> T`
  — handles URL construction, request encoding, HTTP dispatch, status code
  validation, success/error JSON decoding, and error classification (timeout,
  network, server, decode failures).
- `performVoidRequest(method:path:expectedStatus:) async throws`
  — same pipeline but expects no response body (for `logout`), but still attempts to decode `ErrorResponse` from the body if the status is not the expected status.

The existing `register()` method is refactored to delegate to `performRequest`.

Three new methods are added:

```swift
protocol AuthClientProtocol: Sendable {
    func register(request: RegisterRequest) async throws -> RegisterResponse
    func login(request: LoginRequest) async throws -> LoginResponse
    func logout() async throws
    func me() async throws -> MeResponse
}
```

- **`login()`**: `POST /api/v1/auth/login`. Expected status: `200`. Body:
  `LoginRequest`. Returns `LoginResponse`.
- **`logout()`**: `POST /api/v1/auth/logout`. Expected status: `204`. No
  request body, no response body. The server clears the cookie via
  `Set-Cookie: Max-Age=0`; `URLSession` processes this automatically.
- **`me()`**: `GET /api/v1/auth/me`. Expected status: `200`. No request body.
  Returns `MeResponse`.

Error classification within the helper:

| Condition | `ErrorResponse.code` |
|---|---|
| `NSURLErrorTimedOut` | `"TIMEOUT"` |
| `NSURLErrorNotConnectedToInternet`, `NSURLErrorCannotFindHost` | `"NETWORK_ERROR"` |
| Non-`HTTPURLResponse` | `"UNKNOWN"` |
| HTTP ≠ expected + valid JSON error body | Decoded from body |
| HTTP ≠ expected + unparseable body | `"SERVER_ERROR"` |
| HTTP = expected + decode failure | `"DECODE_ERROR"` |

### Data Models

Added to `Models.swift`:

```swift
struct LoginRequest: Codable {
    let email: String
    let password: String
}

struct LoginResponse: Codable {
    let user: PublicUser
}

struct MeResponse: Codable {
    let user: PublicUser
}
```

`PublicUser`, `ErrorResponse`, and `FieldError` already exist and are reused.
`LoginResponse` and `MeResponse` are kept as separate types despite identical
structure — they map to different server endpoints with different semantics.

### InfrastructureError

A client-side classification enum for errors outside user control:

```swift
enum InfrastructureError: Identifiable {
    case serverError
    case noConnectivity
    case timeout

    var id: String { ... }
    var title: String { ... }
    var description: String { ... }
    var systemImage: String { ... }
}
```

Property values:

| Case | `title` | `description` | `systemImage` |
|---|---|---|---|
| `.serverError` | `"Something Went Wrong"` | `"We couldn't reach the server. Please try again later."` | `"exclamationmark.triangle"` |
| `.noConnectivity` | `"No Connection"` | `"Check your internet connection and try again."` | `"wifi.slash"` |
| `.timeout` | `"Request Timed Out"` | `"The server took too long to respond. Please try again."` | `"clock.badge.exclamationmark"` |

### ErrorView

A reusable view wrapping `ContentUnavailableView` (iOS 17+):

```swift
struct ErrorView: View {
    let title: String
    let description: String
    let systemImage: String
    let retryAction: (() -> Void)?

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: systemImage)
        } description: {
            Text(description)
        } actions: {
            if let retryAction {
                Button("Try Again", action: retryAction)
            }
        }
    }
}
```

Used in two contexts:
- **Root coordinator**: Full-screen for startup failures (`.serverError`,
  `.noConnectivity`). "Try Again" re-fires `checkSession()`.
- **Form submissions**: Presented via `.fullScreenCover(item:)` over the
  login/registration form. The form stays alive underneath — "Try Again"
  dismisses the cover, preserving user input for retry.

### LoginView

A SwiftUI `Form` with two fields:

- **Email**: `TextField` with `.emailAddress` keyboard,
  `.textInputAutocapitalization(.never)`, `.disableAutocorrection(true)`.
- **Password**: `SecureField`.
- **Focus**: `@FocusState` advancing Email → Password on Return. Password
  Return triggers login action.
- **Submit button**: Disabled during `isLoading`. Shows `ProgressView` when
  loading.
- **Error display**: Inline error text below the form for `401` responses
  (user-fixable). `.fullScreenCover(item: $viewModel.infrastructureError)` for
  infrastructure errors.
- **Navigation**: "Don't have an account? Register" text button at the bottom,
  invoking `onSwitchToRegister` callback.
- **Accessibility**: `.accessibilityIdentifier` and `.accessibilityLabel` on
  every interactive element: `loginEmailField` / `"Email"`,
  `loginPasswordField` / `"Password"`, `loginButton` / `"Log In"`,
  `switchToRegisterButton` / `"Register"`.

### LoginViewModel

`@MainActor class LoginViewModel: ObservableObject`:

- `@Published var email`, `password`, `isLoading`, `errorResponse: ErrorResponse?`,
  `infrastructureError: InfrastructureError?`
- Constructor: `init(authClient: AuthClientProtocol, onLoginSuccess: @escaping (PublicUser) -> Void)`
- **Local validation**: Rejects if either field is empty → sets
  `errorResponse(code: "VALIDATION")`. No minimum password length check — that
  is a registration concern.
- **Login flow**:
  1. Clear `errorResponse` and `infrastructureError`.
  2. Validate locally.
  3. Set `isLoading = true`, `defer { isLoading = false }`.
  4. Call `authClient.login(request:)`.
  5. On success: invoke `onLoginSuccess(response.user)`.
  6. On `ErrorResponse` with `code == "TIMEOUT"`: set
     `infrastructureError = .timeout`.
  7. On `ErrorResponse` with `code == "NETWORK_ERROR"`: set
     `infrastructureError = .noConnectivity`.
  8. On `ErrorResponse` with `code == "SERVER_ERROR"`: set
     `infrastructureError = .serverError`.
  9. On all other `ErrorResponse`: set `errorResponse` (user-fixable — 401,
     400, etc.).

### HomeView

Minimal post-login screen:

- Displays `user.name` and `user.email` from `PublicUser`.
- "Log Out" button.
- On logout tap: `HomeView` manages a local `@State private var isLoggingOut = false` to show a brief loading state on the button. It sets `isLoggingOut = true`, then `await`s the `onLogout` closure. The network call (`AuthClient.logout()`) and fallback cookie clearing (`HTTPCookieStorage.shared.removeCookies(since:)`) are centralized within `AppViewModel.logout()`. `HomeView` remains purely presentational with no `AuthClient` dependency.

### RegistrationView / RegistrationViewModel Updates

- `RegistrationViewModel` gains an `onRegisterSuccess: (PublicUser) -> Void`
  callback parameter. On successful registration, it invokes this callback
  instead of clearing form fields.
- `RegistrationViewModel` gains a `@Published var infrastructureError:
  InfrastructureError?` property. Error classification logic mirrors
  `LoginViewModel` — codes `"TIMEOUT"`, `"NETWORK_ERROR"`, `"SERVER_ERROR"` map
  to `infrastructureError`; all others map to `errorResponse`.
- `RegistrationView` gains a `.fullScreenCover(item:)` for infrastructure
  errors.
- `RegistrationView` gains an `onSwitchToLogin` callback and a "Already have
  an account? Log in" text button.

### Error Handling Summary

Errors split into two categories based on who can fix them:

**User-fixable** (inline/alert via `errorResponse`):
- `401 Unauthorized` — wrong credentials
- `400 Bad Request` — malformed input
- `409 Conflict` — duplicate email (registration)
- Local validation failures

**Infrastructure** (`.fullScreenCover()` via `infrastructureError`):
- `5xx` server errors
- Network timeouts
- No connectivity
- Unparseable server responses

### Dependencies

No new dependencies. All required infrastructure uses native Apple frameworks:
`SwiftUI`, `Foundation`, `os`.

## Tests

### AuthClient Tests (`AuthClientTests.swift` — Modified)

Existing tests are retained. New tests added using the established
`MockURLProtocol` pattern with ephemeral `URLSession`:

- **`testLoginSuccess`**: Mock returns `200` with `LoginResponse` JSON.
  Assert decoded `PublicUser` fields match.
- **`testLoginUnauthorized`**: Mock returns `401` with
  `ErrorResponse(code: "unauthorized")` JSON. Assert thrown
  `ErrorResponse.code == "unauthorized"`.
- **`testLoginServerError`**: Mock returns `500` with non-JSON body. Assert
  thrown `ErrorResponse.code == "SERVER_ERROR"`.
- **`testLogoutSuccess`**: Mock returns `204` with empty body. Assert no
  error thrown.
- **`testMeSuccess`**: Mock returns `200` with `MeResponse` JSON. Assert
  decoded `PublicUser` fields match.
- **`testMeUnauthorized`**: Mock returns `401` with `ErrorResponse` JSON.
  Assert thrown `ErrorResponse.code == "unauthorized"`.
- **`testLoginRequestEncoding`**: Assert login sends `POST` with
  `Content-Type: application/json`.
- **`testMeRequestEncoding`**: Assert me sends `GET` with no request body.
- **`testLogoutRequestEncoding`**: Assert logout sends `POST` with no
  request body.

### LoginViewModel Tests (`LoginViewModelTests.swift` — New)

Uses `MockAuthClient` conforming to `AuthClientProtocol`:

- **`testEmptyFieldsRejectedLocally`**: Set email, leave password empty.
  Assert `errorResponse?.code == "VALIDATION"`. Assert no network call.
- **`testSuccessfulLoginInvokesCallback`**: Configure mock to return
  `LoginResponse`. Assert `onLoginSuccess` closure is called with correct
  `PublicUser`.
- **`testUnauthorizedSetsErrorResponse`**: Configure mock to throw
  `ErrorResponse(code: "unauthorized")`. Assert `errorResponse` is set,
  `infrastructureError` is nil.
- **`testServerErrorSetsInfrastructureError`**: Configure mock to throw
  `ErrorResponse(code: "SERVER_ERROR")`. Assert
  `infrastructureError == .serverError`, `errorResponse` is nil.
- **`testTimeoutSetsInfrastructureError`**: Configure mock to throw
  `ErrorResponse(code: "TIMEOUT")`. Assert
  `infrastructureError == .timeout`.
- **`testLoadingStateToggles`**: Use `DelayedAuthClient` pattern from
  existing `RegistrationViewModelTests`. Assert `isLoading == true` during
  network call, `false` after.

### AppViewModel Tests (`AppViewModelTests.swift` — New)

Uses `MockAuthClient`:

- **`testCheckSessionAuthenticatedOnSuccess`**: Configure mock `me()` to
  return `MeResponse` with user. Assert
  `authState == .authenticated(user)`.
- **`testCheckSessionUnauthenticatedOn401`**: Configure mock `me()` to
  throw `ErrorResponse(code: "unauthorized")`. Assert
  `authState == .unauthenticated`.
- **`testCheckSessionNoConnectivityOnTimeout`**: Configure mock `me()` to
  throw `ErrorResponse(code: "TIMEOUT")`. Assert
  `authState == .noConnectivity`.
- **`testCheckSessionServerErrorOnServerFailure`**: Configure mock `me()`
  to throw `ErrorResponse(code: "SERVER_ERROR")`. Assert
  `authState == .serverError`.
- **`testLogoutTransitionsToUnauthenticatedOnFailure`**: Configure mock
  `logout()` to throw. Assert `authState == .unauthenticated` regardless.

### RegistrationViewModel Tests (`RegistrationViewModelTests.swift` — Modified)

- **`testSuccessfulRegistrationInvokesCallback`**: Replace current
  "clear fields" assertion with assertion that `onRegisterSuccess` closure
  is called with correct `PublicUser`.
- **`testServerErrorSetsInfrastructureError`**: Configure mock to throw
  `ErrorResponse(code: "SERVER_ERROR")`. Assert
  `infrastructureError == .serverError`.

### MockAuthClient Extraction

`MockAuthClient` is extracted from `RegistrationViewModelTests.swift` into a dedicated test utility file `MockAuthClient.swift` so it can be shared with `LoginViewModelTests` and `AppViewModelTests`. It is expanded with configurable `loginResult`, `logoutResult`, and `meResult` properties for the three new protocol methods.

## Implementation Plan

1. **Add new models to `Models.swift`** — Add `LoginRequest`, `LoginResponse`,
   `MeResponse`.
   - _Verify_:
     `xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS build`

2. **Create `InfrastructureError.swift`** — Define the `InfrastructureError`
   enum with `serverError`, `noConnectivity`, `timeout` cases and computed
   `title`, `description`, `systemImage` properties.
   - _Verify_:
     `xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS build`

3. **Refactor `AuthClient` — extract HTTP helper** — Create
   `performRequest<T>()` and `performVoidRequest()` private methods. Refactor
   existing `register()` to delegate to the helper. No new endpoints yet.
   - _Verify_:
     `xcodebuild test -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16'`
     — all existing tests pass.

4. **Add `login()`, `logout()`, `me()` to `AuthClient`** — Implement the
   three new methods using the extracted helper. Update `AuthClientProtocol`.
   - _Verify_:
     `xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS build`

5. **Add AuthClient tests for new endpoints** — Login success/failure, logout
   success, me success/failure, request encoding assertions.
   - _Verify_:
     `xcodebuild test -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16'`

6. **Create `UserAuthState.swift`** — Define the `UserAuthState` enum.
   - _Verify_:
     `xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS build`

7. **Create `AppViewModel.swift`** — Implement `checkSession()`, `logout()`,
   `onLoginSuccess()`, `onRegisterSuccess()`. Mark `@MainActor`.
   - _Verify_:
     `xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS build`

8. **Create `AppViewModelTests.swift`** — All five test cases.
   - _Verify_:
     `xcodebuild test -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16'`

9. **Create `ErrorView.swift`** — `ContentUnavailableView`-based reusable
   component.
   - _Verify_:
     `xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS build`

10. **Create `LoginViewModel.swift`** — Local validation, `authClient.login()`,
    `onLoginSuccess` callback, `errorResponse` vs `infrastructureError` split.
    - _Verify_:
      `xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS build`

11. **Create `LoginViewModelTests.swift`** — All six test cases.
    - _Verify_:
      `xcodebuild test -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16'`

12. **Create `LoginView.swift`** — Form with email/password, inline errors,
    `.fullScreenCover()` for infrastructure errors, "Register" link.
    - _Verify_:
      `xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS build`

13. **Create `HomeView.swift`** — User info display, logout button with
    loading state, optimistic logout on failure.
    - _Verify_:
      `xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS build`

14. **Create `AuthFlowView.swift`** — Peer-swap container toggling between
    `LoginView` and `RegistrationView`.
    - _Verify_:
      `xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS build`

15. **Update `RegistrationViewModel`** — Replace "clear fields" success
    behavior with `onRegisterSuccess` callback. Add `infrastructureError`
    property and classification logic.
    - _Verify_:
      `xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS build`

16. **Update `RegistrationView`** — Add `.fullScreenCover()` for
    infrastructure errors. Add "Already have an account? Log in" link. Wire
    `onRegisterSuccess` and `onSwitchToLogin` callbacks.
    - _Verify_:
      `xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS build`

17. **Update `RegistrationViewModelTests`** — Adapt existing tests for
    callback pattern. Add infrastructure error classification test.
    - _Verify_:
      `xcodebuild test -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16'`

18. **Extract and Expand `MockAuthClient`** — Move `MockAuthClient` to a new shared `MockAuthClient.swift` file. Add `login()`, `logout()`, `me()` stubs with configurable results.
    - _Verify_:
      `xcodebuild test -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16'`

19. **Update `UnicoachiOSApp`** — Replace `RegistrationView()` with root
    coordinator switching on `AppViewModel.authState`.
    - _Verify_:
      `xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS build`

20. **Update `project.pbxproj`** — Add all new `.swift` files to the Xcode
    project build phases and file references.
    - _Verify_:
      `xcodebuild test -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16'`
      — full test suite passes.

## Files Modified

- `ios-app/UnicoachiOS/UserAuthState.swift` [NEW]
- `ios-app/UnicoachiOS/InfrastructureError.swift` [NEW]
- `ios-app/UnicoachiOS/AppViewModel.swift` [NEW]
- `ios-app/UnicoachiOS/ErrorView.swift` [NEW]
- `ios-app/UnicoachiOS/LoginView.swift` [NEW]
- `ios-app/UnicoachiOS/LoginViewModel.swift` [NEW]
- `ios-app/UnicoachiOS/HomeView.swift` [NEW]
- `ios-app/UnicoachiOS/AuthFlowView.swift` [NEW]
- `ios-app/UnicoachiOSTests/MockAuthClient.swift` [NEW]
- `ios-app/UnicoachiOSTests/LoginViewModelTests.swift` [NEW]
- `ios-app/UnicoachiOSTests/AppViewModelTests.swift` [NEW]
- `ios-app/UnicoachiOS/Models.swift` [MODIFY]
- `ios-app/UnicoachiOS/AuthClient.swift` [MODIFY]
- `ios-app/UnicoachiOS/RegistrationView.swift` [MODIFY]
- `ios-app/UnicoachiOS/RegistrationViewModel.swift` [MODIFY]
- `ios-app/UnicoachiOS/UnicoachiOSApp.swift` [MODIFY]
- `ios-app/UnicoachiOSTests/AuthClientTests.swift` [MODIFY]
- `ios-app/UnicoachiOSTests/RegistrationViewModelTests.swift` [MODIFY]
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` [MODIFY]
