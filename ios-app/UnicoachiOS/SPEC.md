# SPEC.md — `ios-app/UnicoachiOS`

## I. Overview

The **UnicoachiOS** target is the student-facing native iOS client for the
Unicoach REST server. Its domain covers **registration**, **login/logout**,
**cookie-based session restoration**, and **student-profile onboarding**. A root
state machine (`UserAuthState`) routes the user between an auth flow
(login/register), a one-time onboarding screen that creates the student profile,
and the authenticated home screen. Visual tokens and shared UI components are
specified separately in [DesignSystem/SPEC.md](./DesignSystem/SPEC.md).

---

## II. Invariants

### Architecture

- The app MUST enforce strict **MVVM** layering: Views (`RegistrationView`,
  `LoginView`, `OnboardingView`, …) → ViewModels (`RegistrationViewModel`,
  `LoginViewModel`, `OnboardingViewModel`, `AppViewModel`) → Clients
  (`AuthClient`, `StudentClient`). Views MUST NOT call network APIs directly.
- Every ViewModel MUST be annotated `@MainActor` to guarantee all `@Published`
  mutations execute on the main thread.
- `AuthClient` MUST conform to `AuthClientProtocol` and `StudentClient` MUST
  conform to `StudentClientProtocol` to permit dependency injection in tests.
- Views MUST use `@StateObject` (not `@ObservedObject`) to own their ViewModels,
  guaranteeing the ViewModel is not recreated across view rebuilds.

### Root state machine

- `UserAuthState` =
  `loading | unauthenticated | onboarding(PublicUser) |
  authenticated(PublicUser) | serverError | noConnectivity`.
  The root scene MUST render exactly one view per case: `.loading` → progress
  indicator that triggers `checkSession()`; `.unauthenticated` → `AuthFlowView`;
  `.onboarding` → `OnboardingView`; `.authenticated` → `HomeView`;
  `.serverError` / `.noConnectivity` → `ErrorView` with retry =
  `checkSession()`.
- `UserAuthState` equality MUST compare associated users by `id` only.
- **Profile gating.** Every authentication success — session restore (`me()`),
  login, and registration — MUST resolve through `StudentClient.fetchProfile()`
  before settling: profile absent (`nil`) → `.onboarding(user)`; present →
  `.authenticated(user)`. An authenticated user without a student profile is
  NEVER routed to `.authenticated`. `onLoginSuccess` / `onRegisterSuccess` are
  `async` so this resolution completes before the caller's loading state
  releases.

### Shared HTTP layer

- All HTTP traffic MUST flow through `APIClient`. `AuthClient` and
  `StudentClient` are thin endpoint bindings composed over an injected
  `APIClient`; they MUST NOT construct `URLRequest`s, own a `URLSession`, or
  configure transport (timeout, encoding) themselves.
- `APIClient` MUST remain domain-agnostic: it NEVER references endpoint paths,
  concrete model types (beyond generic `Codable` parameters), or per-status
  domain semantics. Treating a specific status as a domain outcome (e.g. 404 →
  "no profile") is the caller's job.
- `APIClient` MUST declare `@unchecked Sendable`; all owned state (`baseURL`,
  `session`) MUST be `let` after `init`.

### Date decoding (wire contract)

- The REST server serializes `Instant` timestamps as ISO-8601 strings with
  **variable-precision fractional seconds** and a trailing `Z`
  (`2025-01-07T22:16:27.092942Z`; `2025-01-07T22:16:27Z` on a whole second).
  `APIClient`'s decoder MUST parse **both** forms. Neither stock strategy works:
  `.deferredToDate` expects a numeric timestamp; plain `.iso8601` rejects
  fractional seconds. A refactor MUST NOT replace the fraction-tolerant strategy
  with a stock one — this regresses silently because Swift's default-encoded
  test fixtures round-trip (see
  [../UnicoachiOSTests/TESTING.md](../UnicoachiOSTests/TESTING.md), Boundary
  fidelity).

### Error-code casing (wire contract)

- **Error-code casing is split per route family and is load-bearing.** Student
  routes (`/api/v1/students*`) emit UPPERCASE codes (`UNAUTHORIZED`,
  `STUDENT_NOT_FOUND`, `VALIDATION_ERROR`, `STUDENT_ALREADY_EXISTS`); auth
  routes (`/api/v1/auth/*`) emit lowercase codes (`unauthorized`,
  `validation_failed`, `conflict`). Code matching MUST be exact and per-route:
  `checkSession` matches `"unauthorized"` (from `/auth/me`);
  `resolveProfileState` matches `"UNAUTHORIZED"` (from `/students/me`). Tests
  MUST pin both casings (`AppViewModelTests`). Normalizing either side breaks
  session routing silently.

### Observability

- Every point where `APIClient` swallows or converts an error (transport
  failure, non-HTTP response, success-body decode failure, unparseable error
  body, decoded server error) MUST log the lossless structured error with
  `privacy: .public`. The diagnostic boundary NEVER logs only
  `localizedDescription`.
- Loggers MUST NOT emit passwords, tokens, or session identifiers; email address
  in debug-level messages is the sole permitted PII.

### Concurrency / Safety

- Each ViewModel's `isLoading` MUST be `true` for the entire duration of an
  in-flight network request and MUST revert to `false` regardless of success or
  failure (via `defer`).

### Local validation

- `RegistrationViewModel` MUST reject a registration attempt before issuing any
  network call if any of the three fields (`email`, `name`, `password`) is
  empty. The resulting `errorResponse` MUST carry `code: "VALIDATION"`.
- `RegistrationViewModel` MUST reject a `password` shorter than 8 characters
  before issuing any network call. The resulting `errorResponse` MUST carry
  `code: "VALIDATION"` with a `fieldErrors` entry for the `"password"` field.
- `LoginViewModel` MUST reject a login attempt before issuing any network call
  if the whitespace-trimmed `email` or the `password` is empty, with
  `errorResponse.code: "VALIDATION"`. Login enforces no password-length rule.

### Graduation-date integrity (onboarding)

- The graduation date is picker-derived state (`precision` ∈ {`year`,
  `yearMonth`, `full`} plus `year`/`month`/`day` integers); malformed dates are
  unrepresentable — there is no free-text path.
- `isoDate` MUST emit exactly one of the canonical zero-padded forms `YYYY` |
  `YYYY-MM` | `YYYY-MM-DD`, matching the chosen precision.
- `day` MUST be clamped to the month/year's actual day count (leap-year aware)
  whenever `year` or `month` changes.
- The selectable year window is the initialization year −4 … +8.

### Transport security

- `Info.plist` MUST configure
  `NSAppTransportSecurity →
  NSAllowsArbitraryLoads: true` to permit plain HTTP
  connections to a local development server.

### Accessibility / UI

- Every interactive UI element MUST declare both `.accessibilityIdentifier` and
  `.accessibilityLabel`. Per-view identifier rosters are listed in the view
  contracts (§III).
- Submit buttons MUST be in a loading state (`isLoading == true`) while a
  request is in flight to prevent duplicate submissions.
- Keyboard focus MUST advance automatically: registration Email → Name →
  Password on Return, with Password Return triggering registration; login Email
  → Password on Return, with Password Return triggering login.

### Toolchain

- Minimum deployment target is **iOS 17.0** / **Swift 6.0**.
- The project MUST use zero third-party dependencies — only `SwiftUI`,
  `Foundation`, and `os` from the Apple standard-library frameworks.

---

## III. Behavioral Contracts

### `APIClient`

See [`APIClient.swift`](./APIClient.swift).

```
func post<B: Encodable>(_ path: String, body: B) async throws -> (Data, HTTPURLResponse)
func post(_ path: String) async throws -> (Data, HTTPURLResponse)
func get(_ path: String) async throws -> (Data, HTTPURLResponse)
```

- **Side effects**: Exactly one HTTP request per call. Body-bearing requests set
  `Content-Type: application/json` (GETs and body-less POSTs do not). The
  default session persists `Set-Cookie` headers into `HTTPCookieStorage.shared`;
  tests inject an ephemeral session.
- **Failure modes** (client-synthesized codes — these NEVER originate from the
  server): `NSURLErrorTimedOut` → `ErrorResponse(code: "TIMEOUT")`; other
  transport errors → `"NETWORK_ERROR"`; non-`HTTPURLResponse` → `"UNKNOWN"`.

```
func decode<T: Decodable>(data:response:expectedStatus:) throws -> T
```

- Status match + parse → `T`; status match + parse failure →
  `ErrorResponse(code: "DECODE_ERROR")`; status mismatch → `ErrorResponse`
  decoded from the body, or `"SERVER_ERROR"` if the body is unparseable (e.g.
  502 HTML).

```
func expect(data:response:expectedStatus:) throws
```

- Same mismatch mapping as `decode`, with no success-body decode (used for 204
  responses).

- **Idempotency**: `APIClient` itself imposes none; safety is the verb's (GET
  safe, POST not).

---

### `AuthClient` / `AuthClientProtocol`

See [`AuthClient.swift`](./AuthClient.swift). All methods issue exactly one HTTP
request via the injected `APIClient`; all errors surface as `ErrorResponse` per
the `APIClient` contract.

| Method               | Request                      | Success status              | Returns            |
| -------------------- | ---------------------------- | --------------------------- | ------------------ |
| `register(request:)` | `POST /api/v1/auth/register` | 201                         | `RegisterResponse` |
| `login(request:)`    | `POST /api/v1/auth/login`    | 200                         | `LoginResponse`    |
| `me()`               | `GET /api/v1/auth/me`        | 200                         | `MeResponse`       |
| `logout()`           | `POST /api/v1/auth/logout`   | 204 (no body, via `expect`) | `Void`             |

- **Idempotency**: `register` and `login` are not idempotent at the client;
  retries are the caller's responsibility. `me` is safe; `logout` is idempotent
  server-side.
- **Logging**: Emits `os.Logger` (subsystem `com.unicoach.UnicoachiOS`, category
  `AuthClient`) debug messages on request start.

---

### `StudentClient` / `StudentClientProtocol`

See [`StudentClient.swift`](./StudentClient.swift).

```
func createStudent(request: CreateStudentRequest) async throws -> PublicStudent
```

- `POST /api/v1/students`; 201 → returns the student unwrapped from
  `StudentResponse`. Server errors propagate as `ErrorResponse`: 400
  `VALIDATION_ERROR`, 401 `UNAUTHORIZED`, 409 `STUDENT_ALREADY_EXISTS`.
- **Idempotency**: Not idempotent at the client; the server enforces the
  per-user singleton via 409.

```
func fetchProfile() async throws -> PublicStudent?
```

- `GET /api/v1/students/me`; 200 → student; **HTTP 404 → `nil`** — "no profile
  yet" is a domain outcome, not an error. The `nil` mapping MUST key on the HTTP
  status code, NEVER on the body's `STUDENT_NOT_FOUND` code (the body is never
  decoded on 404). 401 and other statuses throw.
- **Idempotency**: Yes (safe GET).

---

### `AppViewModel`

See [`AppViewModel.swift`](./AppViewModel.swift). Owns the published
`authState: UserAuthState` (initially `.loading`). Composes `AuthClient` and
`StudentClient` over a shared `APIClient`; all four collaborators (including
`CookieStorageProtocol`) are constructor-injectable.

```
func checkSession() async
```

- **Trigger**: Root scene's `.loading` case (app launch and error-state retry).
- Sets `.loading`, calls `authClient.me()`; on success resolves the profile
  state (below). Errors: `"unauthorized"` → `.unauthenticated`; `"TIMEOUT"` /
  `"NETWORK_ERROR"` → `.noConnectivity`; everything else → `.serverError`.
- **Idempotency**: Yes — read-only against the server.

```
func onLoginSuccess(_ user: PublicUser) async
func onRegisterSuccess(_ user: PublicUser) async
```

- **Trigger**: Awaited by `LoginViewModel` / `RegistrationViewModel` on endpoint
  success. Both resolve the profile state: `studentClient.fetchProfile()` →
  `nil` ⇒ `.onboarding(user)`; non-`nil` ⇒ `.authenticated(user)`. Errors:
  `"TIMEOUT"` / `"NETWORK_ERROR"` → `.noConnectivity`; `"UNAUTHORIZED"` →
  `.unauthenticated`; everything else → `.serverError`.

```
func onOnboardingComplete(_ user: PublicUser)
```

- **Trigger**: `OnboardingView`'s `onComplete` callback. Sets
  `.authenticated(user)` without re-fetching the profile.

```
func logout() async
```

- **Trigger**: `HomeView`'s logout button. Best-effort over the network: a
  failed `POST /logout` is logged and NEVER blocks local teardown. All cookies
  in the injected `CookieStorageProtocol` MUST be deleted and the state MUST end
  `.unauthenticated` on every path.
- **Idempotency**: Yes.

---

### `RegistrationViewModel`

See [`RegistrationViewModel.swift`](./RegistrationViewModel.swift).

```
@MainActor func register() async
```

- **Init**: Requires an `AuthClientProtocol` and an
  `onRegisterSuccess: (PublicUser) async -> Void` callback; there is no default
  client parameter.
- **Pre-conditions**: Clears both `errorResponse` and `infrastructureError` on
  entry.
- **Local validation** (no network call): per §II Local validation.
- **Success**: Awaits `onRegisterSuccess(response.user)`. Fields are NOT cleared
  — navigation away is the success affordance.
- **Failure split**: `"TIMEOUT"` → `infrastructureError = .timeout`;
  `"NETWORK_ERROR"` → `.noConnectivity`; `"SERVER_ERROR"` → `.serverError`; any
  other `ErrorResponse` → `errorResponse` (inline domain error); unknown thrown
  error → `.serverError`. Infrastructure and domain errors MUST surface through
  these two distinct published properties.
- **Idempotency**: No — each call submits a new registration attempt.

---

### `LoginViewModel`

See [`LoginViewModel.swift`](./LoginViewModel.swift).

```
@MainActor func login() async
```

- **Init**: Requires an `AuthClientProtocol` and an
  `onLoginSuccess: (PublicUser) async -> Void` callback.
- **Pre-conditions**: Clears both `errorResponse` and `infrastructureError` on
  entry.
- **Local validation** (no network call): per §II Local validation (trims
  whitespace from email for the emptiness check; no length rule).
- **Success**: Awaits `onLoginSuccess(response.user)`. Fields are NOT cleared.
- **Failure split**: Identical to `RegistrationViewModel.register()`.
- **Idempotency**: No — each call submits a new login attempt.

---

### `OnboardingViewModel`

See [`OnboardingViewModel.swift`](./OnboardingViewModel.swift).

```
@MainActor func submit() async
```

- **Init**: Requires a `StudentClientProtocol`, an `onComplete: () -> Void`
  callback, and the initialization `year` (anchors the selectable window).
- **Side effects**: `POST` via `StudentClient.createStudent` with
  `CreateStudentRequest(expectedHighSchoolGraduationDate: isoDate)`.
- **Success**: Invokes `onComplete()`.
- **Failure**: **`STUDENT_ALREADY_EXISTS` MUST be treated as success** (invokes
  `onComplete()`): a retried onboarding converges instead of dead-ending the
  user. Other `ErrorResponse` → published `errorResponse`; unknown errors →
  `ErrorResponse(code: "UNKNOWN")`. `isLoading` is guarded by `defer`.
- **Idempotency**: Effectively idempotent (409 converges).

---

### Views

All views are pure presentation: they delegate side-effectful logic to their
ViewModel or injected callbacks. Idempotency: N/A.

#### `AuthFlowView`

See [`AuthFlowView.swift`](./AuthFlowView.swift). Toggles between `LoginView`
(default) and `RegistrationView` with animated transitions, driven by the two
views' `onSwitchToRegister` / `onSwitchToLogin` callbacks. Forwards
`onLoginSuccess` / `onRegisterSuccess` to `AppViewModel`.

#### `RegistrationView`

See [`RegistrationView.swift`](./RegistrationView.swift).

- **Error display**: Field-level messages render inline under the matching field
  via `ErrorResponse.fieldError(for:)`; the domain `errorResponse` message
  renders in a `FormErrorBanner`; infrastructure errors present a full-screen
  `ErrorView` via `fullScreenCover(item:)` whose retry clears
  `infrastructureError` and resubmits.
- **Accessibility identifiers**: `emailField`, `nameField`, `passwordField`,
  `registerButton`, `loadingIndicator`, `switchToLoginButton`.
- **Keyboard**: Email field uses `.emailAddress` keyboard; Name autocapitalizes
  words; Password is a secure field.

#### `LoginView`

See [`LoginView.swift`](./LoginView.swift).

- **Error display**: Domain `errorResponse` message in a `FormErrorBanner`;
  infrastructure errors via full-screen `ErrorView` (same pattern as
  `RegistrationView`).
- **Accessibility identifiers**: `loginEmailField`, `loginPasswordField`,
  `loginButton`, `switchToRegisterButton`.

#### `OnboardingView`

See [`OnboardingView.swift`](./OnboardingView.swift).

- **Pickers**: Segmented precision picker plus wheel pickers for year, month,
  and day; the month and day pickers render only at sufficient precision (month
  from `yearMonth`, day only at `full`).
- **Error display**: Domain `errorResponse` message in a `FormErrorBanner`.
- **Accessibility identifiers**: `precisionPicker`, `yearPicker`, `monthPicker`,
  `dayPicker`, `createProfileButton`, `loadingIndicator`.

#### `HomeView`

See [`HomeView.swift`](./HomeView.swift). Displays the authenticated user's name
and email plus a destructive Log Out `LoadingButton` that awaits the injected
`onLogout` callback.

#### `ErrorView`

See [`ErrorView.swift`](./ErrorView.swift). Full-screen `ContentUnavailableView`
with title, description, system image, and an optional retry action. Used both
as a root error scene and as the `fullScreenCover` for `InfrastructureError`
(which supplies per-case title, description, and system image).

#### `UnicoachiOSApp`

See [`UnicoachiOSApp.swift`](./UnicoachiOSApp.swift). `@main` entry point; owns
the `AppViewModel` via `@StateObject` and switches the root scene over
`UserAuthState` exactly as required by the root-state-machine invariants (§II).
The `.loading` case attaches a `.task` that runs `checkSession()`.

---

### `Models.swift`

See [`Models.swift`](./Models.swift). Value types shared across the networking
and UI layers, paired per endpoint:

- Register: `RegisterRequest` / `RegisterResponse` (wraps `PublicUser`).
- Login: `LoginRequest` / `LoginResponse` (wraps `PublicUser`).
- Session restore: `MeResponse` (wraps `PublicUser`).
- Student profile: `CreateStudentRequest` / `StudentResponse` (wraps
  `PublicStudent`).
- Errors: `ErrorResponse` (conforms to `Error` and `Identifiable` via
  `id = code`; exposes `fieldError(for:)` for per-field lookup) and `FieldError`
  (`Equatable` for test assertions).

`PublicStudent.createdAt` / `updatedAt` are `Date` — their decoding depends on
the `APIClient` date wire contract (§II). All types are `Codable`; field names
map 1:1 to JSON keys with no custom `CodingKeys`.

---

## IV. Infrastructure & Environment

- **Bundle ID**: `com.unicoach.UnicoachiOS` (defined in
  [`Info.plist`](./Info.plist)).
- **App display name**: `Unicoach`.
- **Default base URL**: `http://localhost:8080` and the 10-second request
  timeout (`timeoutIntervalForRequest`) are configured in `APIClient.init`; both
  are overridable via constructor injection.
- **Transport security**: `NSAllowsArbitraryLoads: true` — required for local
  HTTP development; MUST NOT be removed without updating the networking stack to
  use HTTPS and providing a server certificate.
- **Cookie storage**: Injected via `CookieStorageProtocol` (production:
  `HTTPCookieStorage.shared`).
- **Test isolation**: Client tests (`APIClient` / `AuthClient` /
  `StudentClient`) inject `MockURLProtocol` through an ephemeral `URLSession`
  via `APIClient(baseURL:session:)` — avoiding cross-test cookie contamination;
  ViewModel tests use protocol mocks (`MockAuthClient`, `MockStudentClient`).
  See [../UnicoachiOSTests/TESTING.md](../UnicoachiOSTests/TESTING.md) for
  test-writing conventions.
- **File registration**: New `.swift` files (app or test target) MUST be
  registered in `project.pbxproj` — the project uses explicit file references,
  not filesystem synchronization; unregistered files silently never compile.
- **Build/test**: Run via **system Xcode**, NOT the Nix dev shell:
  ```
  xcodebuild test \
    -project ios-app/UnicoachiOS.xcodeproj \
    -scheme UnicoachiOS \
    -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
  ```

---

## V. History

- [x] [RFC-12: iOS Application Registration Spec](../../rfc/12-ios-app.md)
- [x] [RFC-27: iOS Login / Logout](../../rfc/27-ios-login-logout.md)
- [x] [RFC-30: Auth UI Design System (iOS)](../../rfc/30-auth-ui-styling.md)
- [x] [RFC-42: iOS Student-Profile Onboarding](../../rfc/42-ios-student-profile-onboarding.md)
