# SPEC.md — `ios-app/UnicoachiOS`

## I. Overview

The **UnicoachiOS** target is a native iOS application whose sole domain is user
registration. It exposes a single registration form, communicates with the
Unicoach REST server's `POST /api/v1/auth/register` endpoint, and surfaces
server-side or local validation errors to the user. No other workflows
(login, logout, password-reset) exist in this target.

---

## II. Invariants

### Architecture

- The app MUST enforce strict **MVVM** layering: `RegistrationView` (View) →
  `RegistrationViewModel` (ViewModel) → `AuthClient` (Network). View MUST NOT
  call network APIs directly.
- `RegistrationViewModel` MUST be annotated `@MainActor` to guarantee all
  `@Published` mutations execute on the main thread.
- `AuthClient` MUST conform to `AuthClientProtocol` to permit dependency
  injection in tests. Concrete `AuthClient` MUST NOT be directly instantiated
  in `RegistrationViewModel` beyond its default parameter.
- `RegistrationView` MUST use `@StateObject` (not `@ObservedObject`) to own
  `RegistrationViewModel`, guaranteeing the ViewModel is not recreated across
  view rebuilds.

### Concurrency / Safety

- `AuthClient` MUST declare `@unchecked Sendable`. All state it owns
  (`baseURL`, `session`) MUST be immutable (`let`) after `init`.
- `RegistrationViewModel.isLoading` MUST be `true` for the entire duration of an
  in-flight network request and MUST revert to `false` regardless of success or
  failure (via `defer`).

### Local Validation

- `RegistrationViewModel` MUST reject a registration attempt before issuing any
  network call if any of the three fields (`email`, `name`, `password`) is empty.
  The resulting `errorResponse` MUST carry `code: "VALIDATION"`.
- `RegistrationViewModel` MUST reject a `password` shorter than 8 characters
  before issuing any network call. The resulting `errorResponse` MUST carry
  `code: "VALIDATION"` with a `fieldErrors` entry for the `"password"` field.

### Networking

- `AuthClient` MUST set `Content-Type: application/json` on every request.
- `AuthClient` MUST configure `URLSession` with `timeoutIntervalForRequest = 10`
  seconds.
- `AuthClient` MUST use `JSONEncoder` for request serialization and `JSONDecoder`
  for response deserialization; no alternative codecs are permitted.
- `AuthClient` MUST treat HTTP 201 as the sole success status code; all other
  codes MUST map to an `ErrorResponse` throw.
- `AuthClient` MUST catch `NSURLErrorDomain / NSURLErrorTimedOut` and rethrow as
  `ErrorResponse(code: "TIMEOUT", …)`.
- `AuthClient` MUST catch all other network errors and rethrow as
  `ErrorResponse(code: "NETWORK_ERROR", …)`.
- `AuthClient` MUST catch JSON decode failures on error responses and rethrow as
  `ErrorResponse(code: "SERVER_ERROR", …)`, preventing crashes from non-JSON
  bodies (e.g., 502 HTML payloads).

### Transport Security

- `Info.plist` MUST configure `NSAppTransportSecurity → NSAllowsArbitraryLoads:
  true` to permit plain HTTP connections to a local development server.

### Accessibility / UI

- Every interactive UI element in `RegistrationView` MUST declare both
  `.accessibilityIdentifier` and `.accessibilityLabel`:
  - `emailField` / `"Email"`, `nameField` / `"Name"`, `passwordField` /
    `"Password"`, `registerButton` / `"Register"`, `loadingIndicator` /
    `"loadingIndicator"`.
- The Register button MUST be disabled (`isLoading == true`) while a request is
  in flight to prevent duplicate submissions.
- Keyboard focus MUST advance automatically: Email → Name → Password on Return
  key. Password Return MUST trigger the registration action.

### Toolchain

- Minimum deployment target is **iOS 17.0** / **Swift 6.0**.
- The project MUST use zero third-party dependencies — only `SwiftUI`,
  `Foundation`, and `os` from the Apple standard-library frameworks.
- The logger MUST NOT emit passwords, tokens, or session identifiers;
  email address in debug-level messages is the sole permitted PII.

---

## III. Behavioral Contracts

### `AuthClient` / `AuthClientProtocol`

See [`AuthClient.swift`](./AuthClient.swift).

```
func register(request: RegisterRequest) async throws -> RegisterResponse
```

- **Input**: `RegisterRequest` with `email`, `password`, `name` (all non-empty;
  the protocol itself enforces no constraint; the ViewModel enforces pre-call
  validation).
- **Side Effects**: Issues a single `POST /api/v1/auth/register` HTTP request;
  the `URLSession` default configuration automatically persists any `Set-Cookie`
  headers received into `HTTPCookieStorage.shared`.
- **Success**: HTTP 201 → decodes and returns `RegisterResponse`.
- **Failure modes**:

  | Condition | Error thrown |
  |-----------|-------------|
  | `NSURLErrorTimedOut` | `ErrorResponse(code: "TIMEOUT")` |
  | Other network error | `ErrorResponse(code: "NETWORK_ERROR")` |
  | Non-`HTTPURLResponse` | `ErrorResponse(code: "UNKNOWN")` |
  | HTTP ≠ 201 + valid JSON body | `ErrorResponse` decoded from body |
  | HTTP ≠ 201 + unparseable body | `ErrorResponse(code: "SERVER_ERROR")` |
  | HTTP 201 + decode failure | `ErrorResponse(code: "DECODE_ERROR")` |

- **Idempotency**: No — each call unconditionally issues one POST; retries are
  the caller's responsibility.
- **Logging**: Emits `os.Logger` (subsystem: `com.unicoach.UnicoachiOS`,
  category: `AuthClient`) debug messages on request start and HTTP status
  receipt.

---

### `RegistrationViewModel`

See [`RegistrationViewModel.swift`](./RegistrationViewModel.swift).

```
@MainActor func register() async
```

- **Input**: Reads `self.email`, `self.name`, `self.password` from `@Published`
  properties.
- **Pre-conditions**: Always clears `errorResponse` on entry.
- **Local validation** (no network call):
  - Any field empty → sets `errorResponse(code: "VALIDATION")`, returns.
  - `password.count < 8` → sets `errorResponse(code: "VALIDATION", fieldErrors:
    [{field: "password", …}])`, returns.
- **Network call**: Constructs `RegisterRequest` and calls
  `authClient.register(request:)`.
- **Success side effect**: Resets `email`, `name`, and `password` to `""`.
- **Failure side effect**: Sets `errorResponse` to the thrown `ErrorResponse`;
  catches unknown errors as `ErrorResponse(code: "UNKNOWN")`.
- **Loading state**: `isLoading = true` before the network call; guaranteed
  reset to `false` via `defer` on all paths.
- **Idempotency**: No — each call submits a new registration attempt.

---

### `RegistrationView`

See [`RegistrationView.swift`](./RegistrationView.swift).

- **Responsibility**: Presents a SwiftUI `Form` with three input fields and a
  submit button; binds exclusively to `RegistrationViewModel`.
- **Side effects**: None — delegates all side-effectful logic to the ViewModel.
- **Error display**: Inline per-field `Text` labels for `fieldErrors` matching
  `email`, `name`, and `password`; global `.alert(item:)` for non-field errors.
- **Keyboard behavior**: `.textInputAutocapitalization(.never)` on Email;
  `.textInputAutocapitalization(.words)` on Name; `SecureField` on Password.
  All conditional on `#if os(iOS)`.
- **Idempotency**: N/A (pure view).

---

### `UnicoachiOSApp`

See [`UnicoachiOSApp.swift`](./UnicoachiOSApp.swift).

- **Responsibility**: `@main` entry point; sets `RegistrationView` as the sole
  root scene in `WindowGroup`.
- **Side effects**: None.
- **Idempotency**: N/A.

---

### `Models.swift`

See [`Models.swift`](./Models.swift).

- Defines five value types used across the networking and UI layers:
  `RegisterRequest`, `PublicUser`, `RegisterResponse`, `FieldError`,
  `ErrorResponse`.
- `ErrorResponse` conforms to `Error` and `Identifiable` (via `id = code`) to
  enable direct use with SwiftUI `.alert(item:)`.
- `FieldError` conforms to `Equatable` in addition to `Codable`, enabling
  `XCTAssertEqual` comparisons in test assertions.
- All types are `Codable`; field names map 1:1 to JSON keys with no custom
  `CodingKeys`.

---

## IV. Infrastructure & Environment

- **Bundle ID**: `com.unicoach.UnicoachiOS` (defined in [`Info.plist`](./Info.plist)).
- **App display name**: `Unicoach`.
- **Default base URL**: `http://localhost:8080` (hardcoded default in
  `AuthClient.init`; overridable via constructor injection in tests).
- **Transport security**: `NSAllowsArbitraryLoads: true` — required for local
  HTTP development; MUST NOT be removed without updating the networking stack to
  use HTTPS and providing a server certificate.
- **Network timeout**: 10 seconds (`timeoutIntervalForRequest`) configured in
  `AuthClient.init`; not externally configurable at runtime.
- **Cookie storage**: `HTTPCookieStorage.shared` (implicit via
  `URLSessionConfiguration.default`). Tests MUST use `URLSessionConfiguration.ephemeral`
  to avoid cross-test cookie contamination.
- **Test isolation**: `AuthClientTests` injects `MockURLProtocol` via an
  ephemeral `URLSessionConfiguration`. `RegistrationViewModelTests` injects
  `MockAuthClient` conforming to `AuthClientProtocol`.
- **Build verification**:
  ```
  xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace \
             -scheme UnicoachiOS build
  ```
- **Test verification**:
  ```
  xcodebuild test \
    -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace \
    -scheme UnicoachiOS \
    -destination 'platform=iOS Simulator,name=iPhone 16'
  ```

---

## V. History

- [x] [RFC-12: iOS Application Registration Spec](../../rfc/12-ios-app.md)
