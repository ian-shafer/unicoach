# SPEC.md — `ios-app/UnicoachiOS`

## I. Overview

The **UnicoachiOS** target is the student-facing native iOS client for the
Unicoach REST server. Its domain covers **registration**, **login/logout**,
**cookie-based session restoration**, **student-profile onboarding**, and a
**multi-turn coaching conversation** (SSE-streamed turns). A root state
machine (`UserAuthState`) routes the user between an auth flow (login/register),
a one-time onboarding screen that creates the student profile, and the
authenticated home screen, which pushes `ConversationView` to hold a multi-turn
coaching conversation. Visual tokens and shared UI components are specified
separately in [DesignSystem/SPEC.md](./DesignSystem/SPEC.md).

---

## II. Invariants

### Architecture

- The app MUST enforce strict **MVVM** layering: Views (`RegistrationView`,
  `LoginView`, `OnboardingView`, `ConversationView`, …) → ViewModels
  (`RegistrationViewModel`, `LoginViewModel`, `OnboardingViewModel`,
  `ConversationViewModel`, `AppViewModel`) → Clients (`AuthClient`,
  `StudentClient`, `ConversationClient`). Views MUST NOT call network APIs
  directly.
- Every ViewModel MUST be annotated `@MainActor` to guarantee all `@Published`
  mutations execute on the main thread.
- `AuthClient` MUST conform to `AuthClientProtocol`, `StudentClient` to
  `StudentClientProtocol`, and `ConversationClient` to
  `ConversationClientProtocol` to permit dependency injection in tests.
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
  releases. The gate makes a `409 student_profile_required` from the stream
  endpoint an abnormal edge (the profile was deleted server-side mid-session);
  its handler MUST re-enter `.onboarding(user)` from `.authenticated` WITHOUT a
  confirming `fetchProfile()` round-trip — the 409 itself proves absence. The
  transition is a no-op from any other state; no error is surfaced to the user.

### Shared HTTP layer

- All HTTP traffic MUST flow through `APIClient`. `AuthClient`, `StudentClient`,
  and `ConversationClient` are thin endpoint bindings composed over an injected
  `APIClient`; they MUST NOT construct `URLRequest`s, own a `URLSession`, or
  configure transport (timeout, encoding) themselves. `ConversationClient` owns
  ONLY the SSE-specific concerns: line splitting, frame assembly, and
  frame→event decoding.
- `APIClient` MUST remain domain-agnostic: it NEVER references endpoint paths,
  concrete model types (beyond generic `Codable` parameters), or per-status
  domain semantics. Treating a specific status as a domain outcome (e.g. 404 →
  "no profile") is the caller's job.
- `APIClient` MUST declare `@unchecked Sendable`; all owned state MUST be `let`
  after `init`.

### Backend base URL

- `APIClient`'s base URL MUST resolve through `resolveBackendURL`
  (`BackendURL.swift`). Resolution MUST be total: a `nil`, empty,
  whitespace-only, or unparseable `UnicoachBackendURL` value MUST yield the
  `http://localhost:8080` fallback and MUST NEVER crash (NEVER force-unwrap a
  parsed URL). A value is accepted ONLY if, after trimming, it parses to a `URL`
  with both a non-nil `scheme` and a non-nil `host`; every other input uses the
  fallback.
- In production, `APIClient.init`'s `baseURL` defaults to `defaultBackendURL()`,
  which reads the `UnicoachBackendURL` `Info.plist` key (baked at build time
  from the `UNICOACH_BACKEND_URL` build setting). Tests inject an explicit
  `baseURL`, so the resolver path is exercised in production only.

### Streaming transport

- Streaming MUST use a second, dedicated `URLSession` with a 60-second
  inter-chunk idle timeout (the request session's is 10 seconds): a per-request
  `timeoutInterval` cannot reliably extend a session-level value, and a
  10-second idle timeout aborts slow time-to-first-token. An injected session
  backs both paths.

### SSE framing (wire contract)

- The byte loop MUST deliver empty lines to the frame assembler — empty lines
  ARE SSE's frame boundaries. `AsyncLineSequence` (`bytes.lines`) MUST NOT be
  used: it discards empty lines, which compiles and silently never dispatches
  any frame.
- At end of stream, a complete buffered frame whose terminating blank line was
  cut off MUST be flushed; a genuinely partial frame MUST be dropped.
- CRLF-delimited bodies MUST parse identically to LF-delimited bodies.
- **Opener strictness is per-endpoint.** The start endpoint's stream MUST open
  with a `conversation` frame; the follow-up endpoint's with a `user_message`
  frame. A frame carrying the OTHER endpoint's opener is off-contract and MUST
  decode to `SERVER_ERROR` — it is NEVER accepted.

### Turn lifecycle

- A conversation is an append-only thread of turns (`[ChatTurn]`); each turn
  pairs one student message with the coach reply it elicits. At most ONE turn
  may be in flight at a time — `isStreaming` gates both `send()` and `retry()`.
- Each turn's stream MUST yield an opener (`conversation` on the first turn,
  `user_message` on follow-ups) → `delta`\* → terminal (`message` | `error`). A
  turn is COMPLETE only when the terminal `message` frame arrives.
- **Establishment-gated dispatch.** The established `conversation` is `nil` until
  the first turn COMPLETES, and dispatch keys on it: `nil` → start endpoint
  (`streamConversation`); non-`nil` → follow-up endpoint (`postMessage` against
  the established id). The first turn's conversation MUST be held in
  `pendingConversation` and committed to `conversation` ONLY on the terminal
  `message` frame — NEVER on the opener: a first turn that fails server-side is
  soft-deleted, so an unestablished retry MUST re-create via the start endpoint
  and NEVER reuse the dead id.
- **Retry-in-place.** A failed turn (no terminal `message`) MUST remain
  retryable in place: `retry()` clears that turn's failure, partial coach text,
  and prior coach message, then re-streams the same student message under the
  establishment rule above. A COMPLETED turn MUST NOT be re-submittable.

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
  `validation_failed`, `conflict`); conversation routes
  (`/api/v1/conversations*`) emit lowercase codes (`student_profile_required`).
  Code matching MUST be exact and per-route: `checkSession` matches
  `"unauthorized"` (from `/auth/me`); `resolveProfileState` matches
  `"UNAUTHORIZED"` (from `/students/me`); `ConversationViewModel.handle()` matches
  the lowercase server code (`"student_profile_required"`) and UPPERCASE
  client-synthesized codes case-sensitively. Tests MUST pin both casings
  (`AppViewModelTests`). Normalizing either side breaks session routing and
  re-onboarding routing silently.

### Observability

- Every point where `APIClient` swallows or converts an error (transport
  failure, non-HTTP response, success-body decode failure, unparseable error
  body, decoded server error) MUST log the lossless structured error with
  `privacy: .public`. The diagnostic boundary NEVER logs only
  `localizedDescription`.
- Loggers MUST NOT emit passwords, tokens, or session identifiers; email address
  in debug-level messages is the sole permitted PII.

### Concurrency / Safety

- Each ViewModel's in-flight flag (`isLoading` / `isStreaming`) MUST be `true`
  for the entire duration of an in-flight network request and MUST revert to
  `false` regardless of success or failure (via `defer`).

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

### Window rendering

- `Info.plist` MUST declare a `UILaunchScreen` key (an empty dictionary
  suffices). Its presence opts the app into native full-screen rendering;
  removing it reverts every screen to legacy letterbox compatibility mode (a
  reduced-height canvas with black bands). This is an app-wide invariant and
  MUST NOT be removed absent a replacement launch storyboard.

### Transport security

- `Info.plist` MUST configure
  `NSAppTransportSecurity →
  NSAllowsArbitraryLoads: true` to permit plain HTTP
  connections to a LAN/Tailscale development host.

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

### `BackendURL.swift`

See [`BackendURL.swift`](./BackendURL.swift).

```
func resolveBackendURL(_ infoValue: String?) -> URL
func defaultBackendURL() -> URL
```

- `resolveBackendURL` is **pure** — no side effects. It trims `infoValue` and
  returns the parsed `URL` only when the trimmed string is non-empty and parses
  to a URL with both a `scheme` and a `host`; every other input (nil, empty,
  whitespace-only, scheme-less, host-less, unparseable) returns the
  `http://localhost:8080` fallback. It NEVER throws or crashes.
- `defaultBackendURL` performs exactly one side effect: a
  `Bundle.main.object(forInfoDictionaryKey: "UnicoachBackendURL")` read, whose
  result it resolves through `resolveBackendURL`. It is the production default
  for `APIClient.init`'s `baseURL`.
- **Idempotency**: Both are referentially transparent for a fixed bundle value.

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
  server, on either the request or the stream path): `NSURLErrorTimedOut` →
  `ErrorResponse(code: "TIMEOUT")`; other transport errors → `"NETWORK_ERROR"`;
  non-`HTTPURLResponse` → `"UNKNOWN"`.

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

```
func stream<B: Encodable>(_ path: String, body: B, accept: String, expectedStatus: Int) async throws -> URLSession.AsyncBytes
```

- Domain-agnostic — carries no SSE semantics. Builds the request exactly as
  `perform` does, plus the `Accept` header, and issues it on the dedicated
  stream session (§II Streaming transport). On a status mismatch the buffered
  body is drained and the decoded `ErrorResponse` (or `"SERVER_ERROR"` on an
  unparseable body) is thrown. Returns the live byte stream once headers arrive.

```
func transportError(_ error: Error) -> ErrorResponse
```

- The SINGLE transport-error mapping point: `NSURLErrorTimedOut` → `"TIMEOUT"`,
  anything else → `"NETWORK_ERROR"`. Shared by `perform`, `stream`, and
  `ConversationClient`'s mid-iteration `AsyncBytes` failures — the durable
  guarantee is centralization: exactly one transport-error vocabulary for both
  the request and stream paths.

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

### `ConversationClient` / `ConversationClientProtocol`

See [`ConversationClient.swift`](./ConversationClient.swift).

```
func streamConversation(request: CreateConversationRequest) -> AsyncThrowingStream<ConversationStreamEvent, Error>
func postMessage(conversationId: UUID, request: PostMessageRequest) -> AsyncThrowingStream<ConversationStreamEvent, Error>
```

- Both endpoints `POST` with `Accept: text/event-stream`, expected status 200,
  via `APIClient.stream`: `streamConversation` → `/api/v1/conversations/stream`
  (opens the conversation); `postMessage` →
  `/api/v1/conversations/{id}/messages/stream` (a follow-up turn on an
  established conversation).
- **Shared pump**: A single `(path, body, opener)` SSE pump backs both endpoints,
  differing ONLY in path, request body, and the legal opener. It owns ONLY the
  SSE-specific concerns: empty-line-preserving line splitting (`sseLines`), frame
  assembly (`SSEFrameAssembler`), and frame→event decoding by the `type`
  discriminator — no `URLRequest`, `URLSession`, or transport configuration of
  its own.
- **Opener strictness**: Frame decoding is gated by the endpoint's legal opener
  (§II SSE framing): `conversation` decodes only on `streamConversation`,
  `user_message` only on `postMessage`; the off-contract opener → `"SERVER_ERROR"`.
- **Concurrency**: Byte iteration runs in its own non-main-actor `Task`; the
  `@MainActor` consumer only awaits events. Consumer cancellation propagates via
  `onTermination` → `task.cancel()`; a `CancellationError` finishes the stream
  WITHOUT error — leaving the screen mid-stream is not a failure.
- **Termination**: The terminal `message` frame finishes the stream and stops
  reading; a stream that ends with no terminal frame finishes cleanly (no error).
- **Failure modes**: a `type:"error"` frame → the carried `ErrorResponse` is
  thrown; an unknown/off-contract `type` or undecodable payload → `"SERVER_ERROR"`;
  a mid-iteration `AsyncBytes` failure occurs outside `APIClient.stream()` and is
  mapped through the shared `transportError` → `"TIMEOUT"` / `"NETWORK_ERROR"`.
- **Idempotency**: No — `streamConversation` creates a conversation;
  `postMessage` appends a turn server-side.

---

### `AppViewModel`

See [`AppViewModel.swift`](./AppViewModel.swift). Owns the published
`authState: UserAuthState` (initially `.loading`). Composes `AuthClient`,
`StudentClient`, and `ConversationClient` over a shared `APIClient`; all five
collaborators (including `CookieStorageProtocol`) are constructor-injectable.

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
func onStudentProfileRequired()
```

- **Trigger**: `ConversationViewModel.handle()` via `HomeView`'s
  `onProfileRequired` callback, on a `409 student_profile_required` from the
  stream endpoint (the optimistic turn is dropped). No
  network call — the 409 itself proves the profile is absent (§II Profile
  gating). Re-enters `.onboarding(user)` from `.authenticated`; a no-op from any
  other state.
- **Idempotency**: Yes.

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

### `ConversationViewModel`

See [`ConversationViewModel.swift`](./ConversationViewModel.swift). Drives an
append-only `turns: [ChatTurn]` thread; `conversation` is `nil` until the first
turn completes (§II Turn lifecycle). `ChatTurn` carries the student message,
`coachStreamingText`, an optional canonical `coachMessage`, and an optional
`failure: TurnFailure`.

```
@MainActor func send() async
@MainActor func retry(_ id: ChatTurn.ID) async
```

- **Init**: Requires a `ConversationClientProtocol` and an
  `onProfileRequired: () -> Void` callback.
- **In-flight gate**: Both `send()` and `retry()` return immediately if
  `isStreaming` — only one turn streams at a time.
- **Send gating (`canSend`)**: A computed, non-publishing presentational gate
  for the send button = `!isStreaming` AND trimmed `messageText` non-empty. It
  carries NO completed-turn guard — a completed turn MUST NOT disable the next
  send (multi-turn). `canSend` governs ONLY button tappability; `send()`'s own
  `isStreaming` and empty/length guards remain the authoritative defense.
- **`send()`**: Clears `validationError`, then locally validates the trimmed
  `messageText` (no network call) — empty or exceeds 100_000 characters →
  `validationError` with `code: "VALIDATION"` and a `"message"` field error, then
  returns. Otherwise appends an optimistic `ChatTurn` (synthetic `Message`, UUID
  id), clears the composer, and streams the turn.
- **`retry(id:)`**: Clears the target turn's `failure`, partial
  `coachStreamingText`, and `coachMessage`, then re-streams its existing student
  message under the establishment rule (§II Turn lifecycle).
- **Event handling** (per turn): `.conversation` stashes `pendingConversation`
  and REPLACES the optimistic user message with the server copy; `.userMessage`
  replaces the user message on follow-up turns; `.delta` appends to the turn's
  `coachStreamingText`; `.completed` sets the turn's canonical `coachMessage` and
  — on the first turn only — commits `pendingConversation` to `conversation`.
- **Failure model**: A turn-scoped failure is a `TurnFailure` attached to
  `ChatTurn.failure` — `.server(ErrorResponse)` for a coach `error` frame or
  `.infrastructure(InfrastructureError)` for a transport-class failure. This VM
  does NOT publish the `errorResponse` / `infrastructureError` pair the
  auth/onboarding VMs use; `validationError` is its only VM-level published error
  (a pre-send banner).
- **Failure split** (`handle`, case-sensitive): `"student_profile_required"` →
  invokes `onProfileRequired()` and REMOVES the optimistic turn (no failure
  published); `"TIMEOUT"` → `.infrastructure(.timeout)`; `"NETWORK_ERROR"` →
  `.infrastructure(.noConnectivity)`; `"SERVER_ERROR"` and any non-`ErrorResponse`
  throw → `.infrastructure(.serverError)`; any other `ErrorResponse` →
  `.server(error)`.
- **Concurrency**: `isStreaming` is guarded by `defer`.
- **Idempotency**: No — each `send()` appends a new turn; `retry()` re-dispatches
  an existing failed turn.

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

See [`HomeView.swift`](./HomeView.swift). Owns a `NavigationStack`. Takes the
authenticated user, a `ConversationClientProtocol`, an `onProfileRequired`
callback, and an `onLogout` callback. Displays the user's name and email, a
Start Coaching `NavigationLink` (identifier `startCoachingButton`) that pushes
`ConversationView`, and a destructive Log Out `LoadingButton` that awaits the
injected `onLogout` callback. Re-entering `.onboarding` tears down the
`NavigationStack` — and any pushed conversation screen — with it.

#### `ConversationView`

See [`ConversationView.swift`](./ConversationView.swift). Pushed from
`HomeView`'s Start Coaching link.

- **Thread**: Renders `ForEach(viewModel.turns)`; each turn shows the student
  bubble, the coach bubble (once streaming text or a completed message exists), a
  streaming indicator on the in-flight turn, and — on failure — an inline failure
  view with a Retry button. Auto-scrolls to the newest turn.
- **Layout**: The root `VStack` and the thread `ScrollView` MUST fill all
  vertical space the navigation container offers (both carry
  `maxHeight: .infinity`); the thread scrolls within that space while the
  composer keeps its intrinsic height and pins to the bottom edge.
- **Composer**: A single vertically-growing `TextField` with the send button —
  a `CircularIconButton` (see [DesignSystem/SPEC.md](./DesignSystem/SPEC.md)) —
  overlaid bottom-trailing. The field's trailing inset MUST track the button's
  rendered width (plus its edge inset) so input never underlaps the button at
  any Dynamic Type size; a fixed/hardcoded inset is forbidden. The input field
  is disabled ONLY while a turn is streaming (`isStreaming`); the send button's
  enabled state is bound to `ConversationViewModel.canSend`. A completed turn
  re-enables both for the next turn, and a failed turn is retried in place from
  its own Retry button.
- **Bubble rendering**: The coach bubble prefers the canonical
  `coachMessage.content` over the live streaming buffer.
- **Error display**: A pre-send `validationError` renders in a `FormErrorBanner`
  above the composer; per-turn failures render INLINE in the thread (a
  `FormErrorBanner` for `.server`, an icon+title+description for
  `.infrastructure`) — no `fullScreenCover`, unlike login/registration.
- **Accessibility identifiers**: `messageField`, `sendButton`, `userBubble`,
  `coachBubble`, `streamingIndicator`, `retryButton`.

#### `ErrorView`

See [`ErrorView.swift`](./ErrorView.swift). Full-screen `ContentUnavailableView`
with title, description, system image, and an optional retry action. Used both
as a root error scene and as the `fullScreenCover` for `InfrastructureError`
(which supplies per-case title, description, and system image).

#### `UnicoachiOSApp`

See [`UnicoachiOSApp.swift`](./UnicoachiOSApp.swift). `@main` entry point; owns
the `AppViewModel` via `@StateObject` and switches the root scene over
`UserAuthState` exactly as required by the root-state-machine invariants (§II).
The `.loading` case attaches a `.task` that runs `checkSession()`. The
`.authenticated` case wires `conversationClient` and `onStudentProfileRequired`
into `HomeView`.

---

### `Models.swift`

See [`Models.swift`](./Models.swift). Value types shared across the networking
and UI layers, paired per endpoint:

- Register: `RegisterRequest` / `RegisterResponse` (wraps `PublicUser`).
- Login: `LoginRequest` / `LoginResponse` (wraps `PublicUser`).
- Session restore: `MeResponse` (wraps `PublicUser`).
- Student profile: `CreateStudentRequest` / `StudentResponse` (wraps
  `PublicStudent`).
- Conversation: domain models `Conversation`, `Message`, `MessageRole`
  (`user` | `coach`); the request DTOs `CreateConversationRequest` (start) and
  `PostMessageRequest` (follow-up); the domain event `ConversationStreamEvent`
  (`conversation` | `userMessage` | `delta` | `completed`); and the five wire
  frames (`ConversationCreatedFrame`, `UserMessageFrame`, `MessageDeltaFrame`,
  `MessageCompletedFrame`, `StreamErrorFrame`) decoded by their `type`
  discriminator. `Message.id` is an opaque `String` and is NEVER parsed;
  `Conversation.id` decodes as `UUID`; `CreateConversationRequest.name` is
  always `nil` this iteration — the server derives the name.
- Errors: `ErrorResponse` (conforms to `Error` and `Identifiable` via
  `id = code`; exposes `fieldError(for:)` for per-field lookup) and `FieldError`
  (`Equatable` for test assertions).

`PublicStudent.createdAt` / `updatedAt` are `Date` — their decoding depends on
the `APIClient` date wire contract (§II). All wire types are `Codable`; field
names map 1:1 to JSON keys with no custom `CodingKeys`.

---

## IV. Infrastructure & Environment

- **Bundle ID**: `com.unicoach.UnicoachiOS` (defined in
  [`Info.plist`](./Info.plist)).
- **App display name**: `Unicoach`.
- **Backend base URL**: Resolved at launch by `defaultBackendURL()` from the
  `UnicoachBackendURL` `Info.plist` key, baked at build time from the
  `UNICOACH_BACKEND_URL` build setting (`Info.plist` value
  `$(UNICOACH_BACKEND_URL)`). When the key is unset, empty, or unparseable, the
  resolver falls back to `http://localhost:8080`. Overridable via constructor
  injection (tests pass an explicit `baseURL`).
- **Transport timeouts**: the request session uses a 10-second
  `timeoutIntervalForRequest`; the stream session uses the 60-second inter-chunk
  idle timeout `APIClient.streamIdleTimeout` (a `static let`, NOT
  constructor-injectable). Both sessions are built in `APIClient.init` when none
  is injected; the sessions themselves are injectable, and an injected session
  backs both paths.
- **Transport security**: `NSAllowsArbitraryLoads: true` — required for plain
  HTTP to a LAN/Tailscale dev host; MUST NOT be removed without updating the
  networking stack to use HTTPS and providing a server certificate.
- **Cookie storage**: Injected via `CookieStorageProtocol` (production:
  `HTTPCookieStorage.shared`).
- **Test isolation**: Client tests (`APIClient` / `AuthClient` / `StudentClient`
  / `ConversationClient`) inject `MockURLProtocol` through an ephemeral
  `URLSession` via `APIClient(baseURL:session:)` — avoiding cross-test cookie
  contamination; ViewModel tests use protocol mocks (`MockAuthClient`,
  `MockStudentClient`, `MockConversationClient`). See
  [../UnicoachiOSTests/TESTING.md](../UnicoachiOSTests/TESTING.md) for
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
- [x] [RFC-41: iOS Start Coaching Conversation](../../rfc/41-ios-start-coaching-conversation.md)
- [x] [RFC-42: iOS Student-Profile Onboarding](../../rfc/42-ios-student-profile-onboarding.md)
- [x] [RFC-48: iOS multi-turn coaching conversation](../../rfc/48-ios-multi-turn-conversation.md)
- [x] [RFC-49: iOS Chat UX Improvements](../../rfc/49-ios-chat-ux-improvements.md)
- [x] [RFC-51: iOS Deploy to Physical Device](../../rfc/51-ios-deploy-to-device.md)
