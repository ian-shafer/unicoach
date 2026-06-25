# SPEC.md — `ios-app/UnicoachiOS`

## I. Overview

The **UnicoachiOS** target is the student-facing native iOS client for the
Unicoach REST server. Its domain covers **registration**, **login/logout**,
**cookie-based session restoration**, **email-verification gating**,
**student-profile onboarding**, a **multi-turn coaching conversation**
(SSE-streamed turns), and **browsing and resuming prior conversations** (a
most-recently-used list, each entry re-entered with its server-fetched history).
A root state machine (`UserAuthState`) routes the user between an auth flow
(login/register), an email-verification screen that blocks until the address is
confirmed, a one-time onboarding screen that creates the student profile, and
the authenticated home screen, which pushes `ConversationView` to hold a
multi-turn coaching conversation. Visual tokens and shared UI components are
specified separately in [DesignSystem/SPEC.md](./DesignSystem/SPEC.md).

The architecture is strict **MVVM**: Views → ViewModels → Clients (`AuthClient`,
`StudentClient`, `ConversationClient`) over a shared, injected `APIClient`.
Views own their ViewModels with `@StateObject` and never call network APIs
directly; every ViewModel is `@MainActor` so all `@Published` mutations land on
the main thread; each client conforms to a protocol (`AuthClientProtocol`,
`StudentClientProtocol`, `ConversationClientProtocol`) for test injection.
Durable guarantees that constrain this layering and the wire contracts live in
the sibling [INVARIANTS.md](./INVARIANTS.md).

---

## II. Behavioral Contracts

### Root state machine — `UserAuthState`

See [`UserAuthState.swift`](./UserAuthState.swift). An `Equatable` enum with
**eight cases**:

```
loading | unauthenticated | onboarding(PublicUser) | authenticated(PublicUser)
       | verificationRequired(PublicUser) | serverError | unexpectedError | noConnectivity
```

- **Equality** compares the payload cases (`onboarding`, `authenticated`,
  `verificationRequired`) by their user's `id` only; the five payload-free cases
  match by case identity.
- **`verificationRequired(PublicUser)`** is the authenticated-but-unverified
  state. The server gates `students/me` until the email is confirmed, so this
  state owns the verification lifecycle and blocks all other navigation; it
  carries the user so the blocked screen can display the address.
- **`serverError`** versus **`unexpectedError`** is a deliberate split:
  `serverError` is a genuine 5xx (the server's problem, retry may help);
  `unexpectedError` is any other unhandled failure — an unrecognized 4xx or a
  client-side error with no HTTP status — usually a missing handler on the
  client side.

**Root scene rendering** (one view per case, in
[`UnicoachiOSApp.swift`](./UnicoachiOSApp.swift)): `.loading` → `ProgressView`
that triggers `checkSession()` via `.task`; `.unauthenticated` → `AuthFlowView`;
`.onboarding` → `OnboardingView`; `.authenticated` → `HomeView`;
`.verificationRequired(user)` → `VerificationRequiredView`; `.serverError` →
`ErrorView` ("Server Problem", retry = `checkSession()`); `.unexpectedError` →
`ErrorView` ("Something Went Wrong", retry = `checkSession()`);
`.noConnectivity` → `ErrorView` ("No Connection", retry = `checkSession()`).

**Profile gating.** Every authentication success — session restore (`me()`),
login, and registration — resolves through `AppViewModel.resolveProfileState`
before settling. That resolver short-circuits on `emailVerified == false` →
`.verificationRequired(user)`; otherwise it calls `StudentClient.fetchProfile()`
and routes profile-absent (`nil`) → `.onboarding(user)`, present →
`.authenticated(user)`. An authenticated user without a confirmed email is never
routed past `.verificationRequired`, and one without a student profile is never
routed to `.authenticated`. `onLoginSuccess` / `onRegisterSuccess` are `async`
so this resolution completes before the caller's loading state releases.

A `409 student_profile_required` from the stream endpoint is an abnormal edge
(the profile was deleted server-side mid-session); its handler re-enters
`.onboarding(user)` from `.authenticated` without a confirming `fetchProfile()`
round-trip — the 409 itself proves absence. The transition is a no-op from any
other state; no error is surfaced to the user.

---

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
  `http://localhost:8080` fallback. It does not throw or crash, and never
  force-unwraps a parsed URL.
- `defaultBackendURL` performs exactly one side effect: a
  `Bundle.main.object(forInfoDictionaryKey: "UnicoachBackendURL")` read, whose
  result it resolves through `resolveBackendURL`. It is the production default
  for `APIClient.init`'s `baseURL`.
- **Idempotency**: Both are referentially transparent for a fixed bundle value.

### `ClientKey.swift`

See [`ClientKey.swift`](./ClientKey.swift).

```
func resolveClientKey(_ infoValue: String?) -> String?
func defaultClientKey() -> String?
```

- `resolveClientKey` is **pure** — trims `infoValue` and returns the trimmed
  string when non-empty, else `nil` (nil, empty, or whitespace-only). It does
  not throw or crash.
- `defaultClientKey` performs exactly one side effect: a
  `Bundle.main.object(forInfoDictionaryKey: "UnicoachClientKey")` read, resolved
  through `resolveClientKey`. It is the production default for
  `APIClient.init`'s `clientKey`; a blank/absent value yields `nil`, so no
  header is sent.
- **Idempotency**: Both are referentially transparent for a fixed bundle value.

### `APIClient`

See [`APIClient.swift`](./APIClient.swift). The single HTTP layer; the three
domain clients are thin endpoint bindings composed over it. It is
domain-agnostic (no endpoint paths, no concrete model types beyond generic
`Codable` parameters, no per-status domain semantics) and declares
`@unchecked Sendable` with all owned state `let` after `init`. It sends the
build-time client key on the `X-Unicoach-Client-Key` header of every request on
both the `perform` and `stream` paths whenever the key is non-nil.

```
func post<B: Encodable>(_ path: String, body: B) async throws -> (Data, HTTPURLResponse)
func post(_ path: String) async throws -> (Data, HTTPURLResponse)
func get(_ path: String) async throws -> (Data, HTTPURLResponse)
func delete(_ path: String) async throws -> (Data, HTTPURLResponse)
func patch<B: Encodable>(_ path: String, body: B) async throws -> (Data, HTTPURLResponse)
```

- **Side effects**: Exactly one HTTP request per call, carrying the
  `X-Unicoach-Client-Key` header when a client key is configured (non-nil).
  Body-bearing requests set `Content-Type: application/json` (GETs and body-less
  POSTs and `delete` do not; `patch` is body-bearing). The default session
  persists `Set-Cookie` headers into `HTTPCookieStorage.shared`; tests inject an
  ephemeral session.
- **Failure modes** (client-synthesized codes — these never originate from the
  server, on either the request or the stream path): `NSURLErrorTimedOut` →
  `ErrorResponse(code: "TIMEOUT")`; other transport errors → `"NETWORK_ERROR"`;
  non-`HTTPURLResponse` → `"UNKNOWN"`.

```
func decode<T: Decodable>(data:response:expectedStatus:) throws -> T
```

- Status match + parse → `T`; status match + parse failure →
  `ErrorResponse(code: "DECODE_ERROR")`; status mismatch → `ErrorResponse`
  decoded from the body, or `"SERVER_ERROR"` if the body is unparseable (e.g.
  502 HTML). On the mismatch path, `decodeError` **stamps the originating HTTP
  status into `ErrorResponse.status`** (the server body carries no status),
  which downstream routing uses to split 5xx from other 4xx.

```
func expect(data:response:expectedStatus:) throws
```

- Same mismatch mapping (and `status` stamping) as `decode`, with no
  success-body decode (used for 204 responses).

```
func stream<B: Encodable>(_ path: String, body: B, accept: String, expectedStatus: Int) async throws -> URLSession.AsyncBytes
```

- Domain-agnostic — carries no SSE semantics. Builds the request exactly as
  `perform` does, plus the `Accept` header, and issues it on the dedicated
  60-second-idle-timeout stream session. On a status mismatch the buffered body
  is drained and the decoded `ErrorResponse` (or `"SERVER_ERROR"` on an
  unparseable body) is thrown. Returns the live byte stream once headers arrive.

```
func transportError(_ error: Error) -> ErrorResponse
```

- The single transport-error mapping point: `NSURLErrorTimedOut` → `"TIMEOUT"`,
  anything else → `"NETWORK_ERROR"`. Shared by `perform`, `stream`, and
  `ConversationClient`'s mid-iteration `AsyncBytes` failures.

- **Observability**: Every point where `APIClient` swallows or converts an error
  logs the lossless structured error with `privacy: .public` (never only
  `localizedDescription`).
- **Idempotency**: `APIClient` itself imposes none; safety is the verb's (GET
  safe, POST not).

---

### `AuthClient` / `AuthClientProtocol`

See [`AuthClient.swift`](./AuthClient.swift). All methods issue exactly one HTTP
request via the injected `APIClient`; all errors surface as `ErrorResponse` per
the `APIClient` contract.

| Method                 | Request                                 | Success status              | Returns            |
| ---------------------- | --------------------------------------- | --------------------------- | ------------------ |
| `register(request:)`   | `POST /api/v1/auth/register`            | 201                         | `RegisterResponse` |
| `login(request:)`      | `POST /api/v1/auth/login`               | 200                         | `LoginResponse`    |
| `me()`                 | `GET /api/v1/auth/me`                   | 200                         | `MeResponse`       |
| `logout()`             | `POST /api/v1/auth/logout`              | 204 (no body, via `expect`) | `Void`             |
| `resendVerification()` | `POST /api/v1/auth/resend-verification` | 204 (no body, via `expect`) | `Void`             |
| `changeEmail(_:)`      | `POST /api/v1/auth/change-email`        | 200                         | `PublicUser`       |

- `resendVerification` sends no body. The server collapses both "sent" and
  "already verified" to `204`; `401 unauthorized` surfaces as a thrown
  `ErrorResponse`.
- `changeEmail` sends a `ChangeEmailRequest(email:)`, decodes the `200`
  `ChangeEmailResponse`, and returns its `.user` (whose `emailVerified` is
  `false` — a fresh address starts unverified).
- **Idempotency**: `register` and `login` are not idempotent at the client;
  retries are the caller's responsibility. `me` is safe; `logout` and
  `resendVerification` are idempotent server-side. `changeEmail` is not
  idempotent (each call re-targets the address and re-sends a verification
  mail).
- **Logging**: Emits `os.Logger` (subsystem `com.unicoachapp.UnicoachiOS`,
  category `AuthClient`) debug messages on request start.

---

### `StudentClient` / `StudentClientProtocol`

See [`StudentClient.swift`](./StudentClient.swift).

```
func createStudent(request: CreateStudentRequest) async throws -> PublicStudent
```

- `POST /api/v1/students`; 201 → returns the student unwrapped from
  `StudentResponse`. Server errors propagate as `ErrorResponse`: 400
  `validation_error`, 401 `unauthorized`, 409 `student_already_exists`.
- **Idempotency**: Not idempotent at the client; the server enforces the
  per-user singleton via 409.

```
func fetchProfile() async throws -> PublicStudent?
```

- `GET /api/v1/students/me`; 200 → student; **HTTP 404 → `nil`** — "no profile
  yet" is a domain outcome, not an error. The `nil` mapping keys on the HTTP
  status code, not on the body's `student_not_found` code (the body is never
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
- **Shared pump**: A single `(path, body, opener)` SSE pump backs both
  endpoints, differing only in path, request body, and the legal opener. It owns
  only the SSE-specific concerns: empty-line-preserving line splitting
  (`sseLines`), frame assembly (`SSEFrameAssembler`), and frame→event decoding
  by the `type` discriminator — no `URLRequest`, `URLSession`, or transport
  configuration of its own.
- **Opener strictness**: Frame decoding is gated by the endpoint's legal opener:
  `conversation` decodes only on `streamConversation`, `user_message` only on
  `postMessage`; the off-contract opener → `"SERVER_ERROR"`.
- **Concurrency**: Byte iteration runs in its own non-main-actor `Task`; the
  `@MainActor` consumer only awaits events. Consumer cancellation propagates via
  `onTermination` → `task.cancel()`; a `CancellationError` finishes the stream
  without error — leaving the screen mid-stream is not a failure.
- **Termination**: The terminal `message` frame finishes the stream and stops
  reading; a stream that ends with no terminal frame finishes cleanly (no
  error).
- **Failure modes**: a `type:"error"` frame → the carried `ErrorResponse` is
  thrown; an unknown/off-contract `type` or undecodable payload →
  `"SERVER_ERROR"`; a mid-iteration `AsyncBytes` failure occurs outside
  `APIClient.stream()` and is mapped through the shared `transportError` →
  `"TIMEOUT"` / `"NETWORK_ERROR"`.
- **Idempotency**: No — `streamConversation` creates a conversation;
  `postMessage` appends a turn server-side.

```
func listConversations() async throws -> [Conversation]
func fetchMessages(conversationId: UUID) async throws -> [Message]
```

- The two non-streaming reads, each exactly one `GET` via `APIClient`, returning
  the wire array unwrapped from its envelope (`ConversationListResponse` /
  `MessageListResponse`): `listConversations` → `GET /api/v1/conversations`;
  `fetchMessages` → `GET /api/v1/conversations/{id}/messages`.
- **Order preservation**: Both return their array in the server's received order
  and do not re-sort. `listConversations` sends no `status` query parameter —
  the server defaults to the unarchived scope; the empty list arrives as a
  `200`, never a `404`.
- **Failure modes**: Unlike `StudentClient.fetchProfile`, these reads do not
  short-circuit on a status code. Every non-`200` (including
  `404 {"code":"not_found"}` for a soft-deleted or foreign conversation on
  `fetchMessages`) routes through `decode`'s `decodeError` and throws the
  decoded `ErrorResponse` (or `"SERVER_ERROR"` on an unparseable body).
- **Idempotency**: Yes (safe GETs).

```
func deleteConversation(conversationId: UUID) async throws
func setArchived(conversationId: UUID, archived: Bool) async throws
```

- The two single-entity-by-id writes, each exactly one request via `APIClient`,
  returning `Void`: `deleteConversation` → `DELETE /api/v1/conversations/{id}`,
  expects `204` (via `expect`); `setArchived` →
  `PATCH /api/v1/conversations/{id}` with an `UpdateConversationRequest`
  carrying only `archived` (the `nil` `name` is dropped from the wire), expects
  `200` (via `expect`). `setArchived`'s `200` `ConversationResponse` body is
  intentionally discarded — the caller drops the row regardless, so no
  `ConversationResponse` DTO exists on iOS. `setArchived` is named generically
  (not `archive`) because the PATCH is symmetric; the client carries no
  archive-only assumption.
- **Failure modes**: Like `fetchMessages` (and unlike `fetchProfile`), neither
  short-circuits on a status code. Every non-success — including
  `404 {"code":"not_found"}` for an already-deleted or foreign conversation —
  routes through `expect`'s `decodeError` and throws the decoded `ErrorResponse`
  (or `"SERVER_ERROR"` on an unparseable body).
- **Idempotency**: `deleteConversation` is idempotent server-side (a re-delete
  yields `404`); `setArchived` is idempotent for a fixed `archived` value.

---

### `SSEParser` — `SSEFrameAssembler` / `ServerSentEvent`

See [`SSEParser.swift`](./SSEParser.swift). The transport-agnostic field-level
SSE parser that `ConversationClient`'s pump feeds line-by-line.
`ServerSentEvent` is the assembled frame — an optional `event` and a `data`
string. No side effects; no `URLSession`, no domain decoding (the
`type`-discriminated frame→event decoding lives in `ConversationClient`).

```
mutating func consume(line: String) -> ServerSentEvent?
mutating func flushPending() -> ServerSentEvent?
```

- **`consume(line:)`** is fed one line at a time. It normalizes a trailing `\r`
  (so CRLF and LF bodies parse identically), then:
  - a **blank line** flushes the buffered frame — returns the assembled
    `ServerSentEvent` if any payload accumulated, else `nil`;
  - a **comment line** (leading `:`) is ignored entirely → `nil`;
  - otherwise the line is parsed `field:value`, where a single leading space
    after the colon is stripped, and a line with **no colon** is treated as a
    field name with an **empty value**. `event:` records the value (last `event`
    line wins); each `data:` value is appended; any **other field is ignored**
    (no payload recorded). A field-bearing line returns `nil` (no frame yet).
- **`flush` semantics**: a frame's `data` is its `data:` values **joined with
  `\n`**; flushing resets the accumulator. A frame with no payload lines flushes
  to `nil`, not an empty event.
- **`flushPending()`** emits a buffered, complete frame at end of stream (the
  case where the terminating blank line was consumed silently by the upstream
  line splitter). It is end-of-stream only — `consume(line:)` never auto-flushes
  a frame that has not seen its blank-line boundary.
- **Idempotency**: Stateful by design — each `consume`/`flush` advances and may
  reset the in-progress frame buffer; it is not a pure per-call mapping.

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
  state (below). `ErrorResponse` mapping: `"unauthorized"` → `.unauthenticated`;
  `"TIMEOUT"` / `"NETWORK_ERROR"` → `.noConnectivity`; otherwise, if
  `error.status >= 500` → `.serverError`, else → `.unexpectedError`. A
  non-`ErrorResponse` throw → `.unexpectedError`.
- **Idempotency**: Yes — read-only against the server.

```
func onLoginSuccess(_ user: PublicUser) async
func onRegisterSuccess(_ user: PublicUser) async
```

- **Trigger**: Awaited by `LoginViewModel` / `RegistrationViewModel` on endpoint
  success. Both delegate to `resolveProfileState(user)`.

```
private func resolveProfileState(_ user: PublicUser) async
```

- The shared post-auth resolver. **Verification gate first**:
  `user.emailVerified
  == false` → `.verificationRequired(user)` with no
  network call. Otherwise calls `studentClient.fetchProfile()` → `nil` ⇒
  `.onboarding(user)`; non-`nil` ⇒ `.authenticated(user)`.
- **`fetchProfile` error mapping**: `"TIMEOUT"` / `"NETWORK_ERROR"` →
  `.noConnectivity`; `"unauthorized"` → `.unauthenticated`;
  **`"email_not_verified"` → `.verificationRequired(user)`** (a defensive arm
  for the race where the routed user read as verified but a concurrent
  change-email reset the flag, yielding the server gate's 403 — routed to the
  blocked screen, not `.unexpectedError`); otherwise, `error.status >= 500` →
  `.serverError`, else → `.unexpectedError`. A non-`ErrorResponse` throw →
  `.unexpectedError`.

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
  stream endpoint (the optimistic turn is dropped). No network call — the 409
  itself proves the profile is absent. Re-enters `.onboarding(user)` from
  `.authenticated`; a no-op from any other state.
- **Idempotency**: Yes.

```
func recheckVerification() async -> VerificationRecheckOutcome
```

- **Trigger**: `VerificationViewModel`'s "Check Again" button and the app's
  foreground re-check (`.onChange(of: scenePhase)` while
  `.verificationRequired`).
- Re-runs `me()` to observe an `emailVerified` flip **without unwinding the
  blocked screen on transient failure**. A verified user transitions out via
  `resolveProfileState` and returns `.verified`; an unverified user returns
  `.stillUnverified`, leaving the screen in place; an `"unauthorized"`
  `ErrorResponse` tears the screen down to `.unauthenticated` and returns
  `.failed`; any other error returns `.failed` while leaving the screen in place
  so it can render inline feedback.
- **`VerificationRecheckOutcome`**: an `Equatable` nested enum
  `verified | stillUnverified | failed`.
- **Idempotency**: Yes — read-only except for the transition out on `.verified`.

```
func logout() async
```

- **Trigger**: `HomeView`'s and `VerificationRequiredView`'s logout button.
  Best-effort over the network: a failed `POST /logout` is logged and never
  blocks local teardown. Every cookie in the injected `CookieStorageProtocol` is
  deleted and the state ends `.unauthenticated` on every path.
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
- **Local validation** (no network call): rejects the attempt if any of the
  three fields (`email`, `name`, `password`) is empty, or if `password` is
  shorter than 8 characters. An empty-field rejection yields
  `errorResponse.code = "VALIDATION"`; the length rejection yields the same code
  with a `fieldErrors` entry for `"password"`. Format validation is deferred to
  the server.
- **Success**: Awaits `onRegisterSuccess(response.user)`. Fields are not cleared
  — navigation away is the success affordance.
- **Failure split**: `"TIMEOUT"` → `infrastructureError = .timeout`;
  `"NETWORK_ERROR"` → `.noConnectivity`; `"SERVER_ERROR"` → `.serverError`; any
  other `ErrorResponse` → `errorResponse` (inline domain error); unknown thrown
  error → `.serverError`. Infrastructure and domain errors surface through these
  two distinct published properties.
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
- **Local validation** (no network call): rejects before any network call if the
  whitespace-trimmed `email` or the `password` is empty, with
  `errorResponse.code = "VALIDATION"`. Login enforces no password-length rule.
- **Success**: Awaits `onLoginSuccess(response.user)`. Fields are not cleared.
- **Failure split**: Identical to `RegistrationViewModel.register()`.
- **Idempotency**: No — each call submits a new login attempt.

---

### `VerificationViewModel`

See [`VerificationViewModel.swift`](./VerificationViewModel.swift). Drives
`VerificationRequiredView`. Owns the displayed `email` (seeded from the routed
user, updated on a successful change-email) and four distinct published feedback
channels so confirmations never collide.

```
init(user:authClient:onRecheck:onLogout:)
```

- Seeds `email` from `user.email`; captures the injected `AuthClientProtocol`,
  the `onRecheck: () async -> AppViewModel.VerificationRecheckOutcome` callback
  (bound to `AppViewModel.recheckVerification`), and the `onLogout` callback.

```
@MainActor func resend() async
```

- Clears `resendError` and `resendConfirmation`, sets `isResending` (guarded by
  `defer`), and calls `authClient.resendVerification()`. **Success** sets
  `resendConfirmation` to "Verification email sent to \<email>." Any
  `ErrorResponse` → `resendError`; a non-`ErrorResponse` throw → a synthesized
  `ErrorResponse(code: "SERVER_ERROR")` on `resendError`.

```
@MainActor func checkAgain() async
```

- Clears `recheckMessage`, sets `isChecking` (guarded by `defer`), awaits
  `onRecheck()`. On `.verified` the screen is torn down before any message would
  render (no message set); `.stillUnverified` sets `recheckMessage` to the
  "isn't verified yet" prompt; `.failed` sets the "couldn't check" prompt. This
  VM never tears down the screen itself — the `onRecheck` callback owns the
  `.unauthenticated` transition.

```
func onEmailChanged(_ user: PublicUser)
```

- Bound as `ChangeEmailViewModel.onChanged`. Updates `email` to the new address,
  clears `resendConfirmation`, and sets `changeConfirmation` (the second,
  distinct confirmation channel) to "Verification email sent to \<new address>."

- **Confirmation channels**: `changeConfirmation` (change-email) and
  `resendConfirmation` (resend) are separate published properties so a
  change-email confirmation and a resend confirmation never overwrite each
  other; `recheckMessage` is the separate re-check feedback channel.
- **Idempotency**: `resend` / `checkAgain` are server-safe re-runs;
  `onEmailChanged` is a pure state update.

---

### `ChangeEmailViewModel`

See [`ChangeEmailViewModel.swift`](./ChangeEmailViewModel.swift). Drives
`ChangeEmailView`.

```
init(email:authClient:onChanged:)
@MainActor func submit() async
```

- **Init**: Seeds the editable `email` from the current address; captures the
  injected `AuthClientProtocol` and an `onChanged: (PublicUser) -> Void`
  callback.
- **Pre-conditions**: Clears `errorResponse` on entry.
- **Local pre-flight** (no network call): if the whitespace-trimmed `email` is
  empty → `errorResponse(code: "VALIDATION")` and returns. All **format**
  validation is deferred to the server (mirroring `RegistrationViewModel`).
- **Side effect**: `authClient.changeEmail(email)` (sets `isLoading`, guarded by
  `defer`). **Success** invokes `onChanged(user)` with the returned (still
  unverified) `PublicUser`. Any `ErrorResponse` → `errorResponse`; a
  non-`ErrorResponse` throw → a synthesized `"SERVER_ERROR"` on `errorResponse`.
- **Idempotency**: No — each successful submit re-targets the address.

---

### `OnboardingViewModel`

See [`OnboardingViewModel.swift`](./OnboardingViewModel.swift).

```
@MainActor func submit() async
```

- **Init**: Requires a `StudentClientProtocol`, an `onComplete: () -> Void`
  callback, and the initialization `year` (anchors the selectable window).
- **Graduation date**: picker-derived state (`precision` ∈ {`year`, `yearMonth`,
  `full`} plus `year`/`month`/`day` integers); there is no free-text path.
  `isoDate` emits exactly one canonical zero-padded form `YYYY` | `YYYY-MM` |
  `YYYY-MM-DD` matching the precision; `day` is clamped to the month/year's
  actual day count (leap-year aware) whenever `year` or `month` changes; the
  selectable year window is the initialization year −4 … +8.
- **Side effects**: `POST` via `StudentClient.createStudent` with
  `CreateStudentRequest(expectedHighSchoolGraduationDate: isoDate)`.
- **Success**: Invokes `onComplete()`.
- **Failure**: **`student_already_exists` is treated as success** (invokes
  `onComplete()`): a retried onboarding converges instead of dead-ending the
  user. Other `ErrorResponse` → published `errorResponse`; unknown errors →
  `ErrorResponse(code: "UNKNOWN")`. `isLoading` is guarded by `defer`.
- **Idempotency**: Effectively idempotent (409 converges).

---

### `ConversationViewModel`

See [`ConversationViewModel.swift`](./ConversationViewModel.swift). Drives an
append-only `turns: [ChatTurn]` thread; `conversation` is `nil` until the first
turn completes. `ChatTurn` carries the student message, `coachStreamingText`, an
optional canonical `coachMessage`, and an optional `failure: TurnFailure`. At
most one turn is in flight at a time.

```
@MainActor func send() async
@MainActor func retry(_ id: ChatTurn.ID) async
```

- **Init**: Two initializers, both requiring a `ConversationClientProtocol` and
  an `onProfileRequired: () -> Void` callback. The **fresh** init (Start
  Coaching / compose) leaves `conversation = nil` and `historyLoad = .ready` —
  no history to fetch, and the first turn routes to `streamConversation`. The
  **seed** init (`init(conversation:…)`, used by `ConversationListView` rows)
  sets `conversation` so every turn routes to `postMessage`, and sets
  `historyLoad = .loading` until `loadHistory()` rebuilds the thread.
- **Readiness (`isReady`)**: A computed, non-publishing gate =
  `historyLoad == .ready`. The view combines it with the stream flag for the
  composer-disabled gate (`isStreaming || !isReady`); `canSend` is unchanged.
- **In-flight gate**: Both `send()` and `retry()` return immediately if
  `isStreaming` — only one turn streams at a time.
- **Send gating (`canSend`)**: A computed, non-publishing presentational gate
  for the send button = `!isStreaming` AND trimmed `messageText` non-empty. It
  carries no completed-turn guard — a completed turn does not disable the next
  send (multi-turn). `canSend` governs only button tappability; `send()`'s own
  `isStreaming` and empty/length guards remain the authoritative defense.
- **`send()`**: Clears `validationError`, then locally validates the trimmed
  `messageText` (no network call) — empty or exceeds 100_000 characters →
  `validationError` with `code: "VALIDATION"` and a `"message"` field error,
  then returns. Otherwise appends an optimistic `ChatTurn` (synthetic `Message`,
  UUID id), clears the composer, and streams the turn.
- **Establishment-gated dispatch**: `conversation` is `nil` until the first turn
  completes, and dispatch keys on it: `nil` → start endpoint
  (`streamConversation`); non-`nil` → follow-up endpoint (`postMessage` against
  the established id). The first turn's conversation is held in
  `pendingConversation` and committed to `conversation` only on the terminal
  `message` frame — never on the opener: a first turn that fails server-side is
  soft-deleted, so an unestablished retry re-creates via the start endpoint and
  does not reuse the dead id.
- **`retry(id:)`**: Clears the target turn's `failure`, partial
  `coachStreamingText`, and `coachMessage`, then re-streams its existing student
  message under the establishment rule. A completed turn is not re-submittable.
- **Event handling** (per turn): `.conversation` stashes `pendingConversation`
  and replaces the optimistic user message with the server copy; `.userMessage`
  replaces the user message on follow-up turns; `.delta` appends to the turn's
  `coachStreamingText`; `.completed` sets the turn's canonical `coachMessage`
  and — on the first turn only — commits `pendingConversation` to
  `conversation`.
- **Failure model**: A turn-scoped failure is a `TurnFailure` attached to
  `ChatTurn.failure` — `.server(ErrorResponse)` for a coach `error` frame or
  `.infrastructure(InfrastructureError)` for a transport-class failure. This VM
  does not publish the `errorResponse` / `infrastructureError` pair the
  auth/onboarding VMs use; `validationError` is its only VM-level published
  error (a pre-send banner).
- **Failure split** (`handle`, case-sensitive): `"student_profile_required"` →
  invokes `onProfileRequired()` and removes the optimistic turn (no failure
  published); `"TIMEOUT"` → `.infrastructure(.timeout)`; `"NETWORK_ERROR"` →
  `.infrastructure(.noConnectivity)`; `"SERVER_ERROR"` and any
  non-`ErrorResponse` throw → `.infrastructure(.serverError)`; any other
  `ErrorResponse` → `.server(error)`.
- **Concurrency**: `isStreaming` is guarded by `defer`.
- **Idempotency**: No — each `send()` appends a new turn; `retry()`
  re-dispatches an existing failed turn.

```
@MainActor func loadHistory() async
```

- **Trigger**: The seed-path view's `.task` and the history-failed Retry button.
- **Guard / idempotency**: A no-op unless `conversation != nil && turns.isEmpty`
  — does nothing on the fresh path and on an already-loaded thread, so repeated
  appearances never re-fetch or wipe the thread. After a failure `turns` stayed
  empty, so a retry re-runs the fetch.
- **Side effect**: One `GET` via `ConversationClient.fetchMessages`; rebuilds
  `turns` from the flat `[Message]` by pairing `user`-then-`coach` in order with
  fresh `UUID` keys (never `Message.id`). A trailing `.user` yields a turn with
  `coachMessage == nil`; an orphan `.coach` is dropped.
- **State**: Sets `historyLoad = .loading` on entry, `.ready` on success;
  `.failed(ErrorResponse)` on a thrown `ErrorResponse`, else a synthesized
  `SERVER_ERROR`. It never publishes a turn-scoped `failure` — history-load
  failure is distinct from a per-turn send failure.

---

### `ConversationListViewModel`

See [`ConversationListViewModel.swift`](./ConversationListViewModel.swift).
Publishes `state: ConversationListState`
(`loading | loaded([Conversation]) | empty | failed(ErrorResponse)`), initial
`.loading`. An empty list is the distinct `.empty` outcome, not a degenerate
`.loaded([])`, so the view renders a dedicated no-conversations affordance. Also
publishes `actionError: ErrorResponse?` — a per-action failure channel kept
separate from `state`, driving an item-style alert (`ErrorResponse` is
`Identifiable` by `code`).

```
@MainActor func load() async
```

- **Trigger**: `ConversationListView`'s `.task`, re-run on every appearance so
  MRU order reflects a conversation just continued and popped back from.
- **Side effect**: One `GET` via `ConversationClient.listConversations`,
  preserving the server's order verbatim. `[]` → `.empty`; non-empty →
  `.loaded(conversations)`.
- **Failure modes**: A thrown `ErrorResponse` (transport or decoded server
  error) → `.failed(error)`; any other error → a synthesized `SERVER_ERROR`
  `.failed`.
- **Idempotency**: Yes (safe GET); each call resets to `.loading` then
  resettles.

```
@MainActor func archive(_ conversation: Conversation) async
@MainActor func delete(_ conversation: Conversation) async
```

- **Trigger**: Row swipe actions / context menu (`archive`); the view's
  confirmation dialog (`delete`) — the dialog gating is the view's concern, not
  the VM's.
- **Optimistic mutation with rollback**: both share one shape. Guard
  `state == .loaded` and the conversation is present (matched by `id`),
  capturing `originalIndex`; if absent, no-op (no client call). Remove the row
  immediately (→ `.empty` if it was the last). Call the client
  (`setArchived(…, archived: true)` / `deleteConversation`). On success, nothing
  further. On failure, reinsert into the **current** list at
  `min(originalIndex, currentCount)` (restoring `.loaded` from `.empty`), then
  set `actionError` to the thrown `ErrorResponse` (or a synthesized
  `"SERVER_ERROR"`). `.failed` is never entered — it is reserved for
  initial-load failure, which replaces the whole screen.
- **Side effect**: One `PATCH` / `DELETE` via `ConversationClient`.
- **Idempotency**: No — each call performs a server-side mutation.

---

### Views

All views are pure presentation: they delegate side-effectful logic to their
ViewModel or injected callbacks. The dominant convention is that an interactive
element declares both an `.accessibilityIdentifier` and an
`.accessibilityLabel`; it is not universal — e.g. `HomeView`'s "Log Out"
`LoadingButton` passes neither (both of `LoadingButton`'s accessibility
parameters default to `nil`, so no identifier or label is applied). Idempotency:
N/A.

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
  words; Password is a secure field. Focus advances Email → Name → Password on
  Return, with Password Return triggering registration.

#### `LoginView`

See [`LoginView.swift`](./LoginView.swift).

- **Error display**: Domain `errorResponse` message in a `FormErrorBanner`;
  infrastructure errors via full-screen `ErrorView` (same pattern as
  `RegistrationView`).
- **Accessibility identifiers**: `loginEmailField`, `loginPasswordField`,
  `loginButton`, `switchToRegisterButton`.
- **Keyboard**: Focus advances Email → Password on Return, with Password Return
  triggering login.

#### `OnboardingView`

See [`OnboardingView.swift`](./OnboardingView.swift).

- **Pickers**: Segmented precision picker plus wheel pickers for year, month,
  and day; the month and day pickers render only at sufficient precision (month
  from `yearMonth`, day only at `full`).
- **Error display**: Domain `errorResponse` message in a `FormErrorBanner`.
- **Accessibility identifiers**: `precisionPicker`, `yearPicker`, `monthPicker`,
  `dayPicker`, `createProfileButton`, `loadingIndicator`.

#### `VerificationRequiredView`

See [`VerificationRequiredView.swift`](./VerificationRequiredView.swift). The
`.verificationRequired` root scene. Owns a `VerificationViewModel` via
`@StateObject`. A scrolling screen explaining that a verification link was sent
to `viewModel.email`, with the four action buttons below.

- **Feedback display**: `changeConfirmation` and `resendConfirmation` each
  render as a label (separate `Text` rows so the two never collide);
  `resendError` renders in a `FormErrorBanner`; `recheckMessage` renders as a
  label.
- **Actions**: **Check Again** (`viewModel.checkAgain()`), **Resend Email**
  (`viewModel.resend()`), **Change Email** (presents `ChangeEmailView` as a
  `.sheet`), **Log Out** (awaits the injected `onLogout`). On a successful
  change-email the sheet dismisses and `viewModel.onEmailChanged(user)` updates
  the displayed address and sets `changeConfirmation`.
- **Accessibility identifiers**: `changeEmailConfirmation`,
  `resendConfirmation`, `recheckMessage` (feedback rows); `checkAgainButton`,
  `resendVerificationButton`, `changeEmailButton`, `logoutButton` (actions).

#### `ChangeEmailView`

See [`VerificationRequiredView.swift`](./VerificationRequiredView.swift)
(co-located). Presented as a `.sheet` from `VerificationRequiredView`; owns a
`ChangeEmailViewModel` via `@StateObject`. A `NavigationStack` with a single
email `LabeledField`, a "Send Verification" submit button, and a toolbar Cancel
button.

- **Error display**: A `"email"` field error renders inline under the field via
  `fieldError(for: "email")`; a non-field `errorResponse` renders in a
  `FormErrorBanner`.
- **Submit**: Both the field's `.go` submit and the "Send Verification" button
  invoke `viewModel.submit()`; success drives the injected `onChanged` callback
  (which dismisses the sheet from the parent).
- **Accessibility identifiers**: `changeEmailField` (the email field),
  `submitChangeEmailButton`, `cancelChangeEmailButton`.

#### `HomeView`

See [`HomeView.swift`](./HomeView.swift). Owns a `NavigationStack`. Takes the
authenticated user, a `ConversationClientProtocol`, an `onProfileRequired`
callback, and an `onLogout` callback. Displays the user's name and email, a
Start Coaching `NavigationLink` (identifier `startCoachingButton`) that pushes a
fresh `ConversationView`, a Your Conversations `NavigationLink` (identifier
`yourConversationsButton`) that pushes `ConversationListView`, and a destructive
Log Out `LoadingButton` that awaits the injected `onLogout` callback.
Re-entering `.onboarding` (or `.verificationRequired`) tears down the
`NavigationStack` — and any pushed conversation screen — with it.

#### `ConversationView`

See [`ConversationView.swift`](./ConversationView.swift). Pushed in two ways: a
**fresh** screen (`HomeView`'s Start Coaching link, `ConversationListView`'s
compose / start-a-conversation actions) and a **seeded** screen (a
`ConversationListView` row, via `init(conversation:…)`). It owns a single
`.task { await viewModel.loadHistory() }`, which is a no-op on the fresh path.

- **History load**: The thread area renders `viewModel.historyLoad`: `.loading`
  → a progress indicator (identifier `historyLoadingIndicator`); `.failed` → an
  inline `FormErrorBanner` with a Retry button (identifier `historyRetryButton`)
  re-invoking `loadHistory()`; `.ready` → the live thread (the only state a
  fresh VM ever shows).
- **Thread**: Renders `ForEach(viewModel.turns)`; each turn shows the student
  bubble, the coach bubble (once streaming text or a completed message exists),
  a streaming indicator on the in-flight turn, and — on failure — an inline
  failure view with a Retry button. Auto-scrolls to the newest turn.
- **Layout**: The root `VStack` and the thread `ScrollView` both fill all
  vertical space the navigation container offers (`maxHeight: .infinity`); the
  thread scrolls within that space while the composer keeps its intrinsic height
  and pins to the bottom edge.
- **Composer**: A single vertically-growing `TextField` with the send button — a
  `CircularIconButton` (see [DesignSystem/SPEC.md](./DesignSystem/SPEC.md)) —
  overlaid bottom-trailing. The field's trailing inset tracks the button's
  rendered width (plus its edge inset) so input never underlaps the button at
  any Dynamic Type size. The input field is disabled while a turn is streaming
  OR a seeded VM's history is still loading (`isStreaming || !isReady`); the
  send button's enabled state is bound to `ConversationViewModel.canSend`. A
  completed turn re-enables both for the next turn, and a failed turn is retried
  in place from its own Retry button.
- **Bubble rendering**: The coach bubble prefers the canonical
  `coachMessage.content` over the live streaming buffer.
- **Error display**: A pre-send `validationError` renders in a `FormErrorBanner`
  above the composer; per-turn failures render inline in the thread (a
  `FormErrorBanner` for `.server`, an icon+title+description for
  `.infrastructure`) — no `fullScreenCover`, unlike login/registration.
- **Accessibility identifiers**: `messageField`, `sendButton`, `userBubble`,
  `coachBubble`, `streamingIndicator`, `retryButton`, `historyLoadingIndicator`,
  `historyRetryButton`.

#### `ConversationListView`

See [`ConversationListView.swift`](./ConversationListView.swift). Pushed from
`HomeView`'s Your Conversations link; owns a `ConversationListViewModel` and
re-fetches on every appearance (`.task`).

- **States**: Renders `viewModel.state` — `.loading` → a centered progress
  indicator (identifier `conversationListLoading`); `.loaded` → a plain `List`
  of rows (identifier `conversationRow`), each `name` plus a relative timestamp,
  tapping into a **seeded** `ConversationView(conversation:…)`; `.empty` → a
  no-conversations affordance (identifier `conversationListEmpty`) with a
  start-a-conversation link (identifier `startConversationButton`); `.failed` →
  an inline `FormErrorBanner` (identifier `conversationListFailed`) with a Retry
  button (identifier `conversationListRetryButton`).
- **Compose**: A toolbar primary-action button (identifier `composeButton`) and
  the empty-state start link both push a **fresh** `ConversationView`.
- **Row actions**: Each `.loaded` row carries trailing `.swipeActions` and a
  `.contextMenu`, each with an **Archive** button (→ `viewModel.archive` in a
  `Task`) and a destructive **Delete** button. Delete does not delete directly:
  it stages the row into `@State pendingDeletion`, which presents a
  `.confirmationDialog`; only the dialog's destructive confirm invokes
  `viewModel.delete`. An `.alert(item:)` bound to `viewModel.actionError`
  surfaces action failures. The row tap-through `NavigationLink` is unchanged.
- **Row timestamp**: Each row renders a relative timestamp from
  `lastActivityAt`, falling back to `updatedAt` to unwrap the `Date?` (a listed
  conversation always has a visible turn, so `lastActivityAt` is set in
  practice).
- **Accessibility identifiers**: `composeButton`, `conversationListLoading`,
  `conversationRow`, `conversationListEmpty`, `startConversationButton`,
  `conversationListFailed`, `conversationListRetryButton`, `archiveButton`,
  `deleteButton`, `deleteConfirmButton`, `deleteCancelButton`.

#### `ErrorView`

See [`ErrorView.swift`](./ErrorView.swift). Full-screen `ContentUnavailableView`
with title, description, system image, and an optional retry action. Used both
as a root error scene (the `.serverError`, `.unexpectedError`, and
`.noConnectivity` cases, each with its own copy and a `checkSession()` retry)
and as the `fullScreenCover` for `InfrastructureError` (which supplies per-case
title, description, and system image).

#### `UnicoachiOSApp`

See [`UnicoachiOSApp.swift`](./UnicoachiOSApp.swift). `@main` entry point; owns
the `AppViewModel` via `@StateObject` and switches the root scene over all eight
`UserAuthState` cases. The `.loading` case attaches a `.task` running
`checkSession()`. The `.authenticated` case wires `conversationClient` and
`onStudentProfileRequired` into `HomeView`. The `.verificationRequired(user)`
case wires `authClient`, `recheckVerification`, and `logout` into
`VerificationRequiredView`. A root `.onChange(of: scenePhase)` re-checks
verification on every `.active` transition while in `.verificationRequired`
(returning to the foreground is the primary detection path for an
`emailVerified` flip — the user must leave the app to open the link, and
deep-linking is out of scope); the outcome is ignored.

---

### `Models.swift`

See [`Models.swift`](./Models.swift). Value types shared across the networking
and UI layers, paired per endpoint:

- Register: `RegisterRequest` / `RegisterResponse` (wraps `PublicUser`).
- Login: `LoginRequest` / `LoginResponse` (wraps `PublicUser`).
- Session restore: `MeResponse` (wraps `PublicUser`).
- Change email: `ChangeEmailRequest` (`email`) / `ChangeEmailResponse` (wraps
  `PublicUser`).
- Student profile: `CreateStudentRequest` / `StudentResponse` (wraps
  `PublicStudent`).
- Conversation: domain models `Conversation`, `Message`, `MessageRole` (`user` |
  `coach`); the request DTOs `CreateConversationRequest` (start),
  `PostMessageRequest` (follow-up), and `UpdateConversationRequest`
  (`name: String?` / `archived: Bool?`, both optional — a `nil` field is omitted
  via `encodeIfPresent`, enabling a one-field PATCH that leaves the server-side
  `name` untouched); the domain event `ConversationStreamEvent` (`conversation`
  | `userMessage` | `delta` | `completed`); and the five wire frames
  (`ConversationCreatedFrame`, `UserMessageFrame`, `MessageDeltaFrame`,
  `MessageCompletedFrame`, `StreamErrorFrame`) decoded by their `type`
  discriminator; and the two read envelopes `ConversationListResponse`
  (`{conversations: [Conversation]}`) and `MessageListResponse`
  (`{messages: [Message]}`), reusing `Conversation` / `Message` unchanged.
  `Message.id` is an opaque `String` and is never parsed; `Conversation.id`
  decodes as `UUID`; `Conversation.lastActivityAt` / `archivedAt` are `Date?`
  decoded via the date wire contract; `CreateConversationRequest.name` is always
  `nil` this iteration — the server derives the name.
- User: `PublicUser` carries `id` (`UUID`), `email`, `name`, and
  **`emailVerified: Bool`** (the verification-gate flag `resolveProfileState` /
  `recheckVerification` read).
- Errors: `ErrorResponse` conforms to `Codable`, `Error`, `Identifiable` (via
  `id = code`), and `Equatable`; it exposes `fieldError(for:)` for per-field
  lookup. It carries a `status: Int?` that is **excluded from `Codable`** (a
  private `CodingKeys` lists only `code, message, fieldErrors`, so the server
  error body — which has no `status` field — round-trips): `status` defaults to
  `nil` and is stamped in by `APIClient.decodeError`, staying `nil` for
  client-synthesized errors (transport, decode, non-HTTP) that never had a
  status. It drives `AppViewModel`'s `status >= 500` → `.serverError` versus
  otherwise → `.unexpectedError` routing split. `FieldError` is `Equatable` for
  test assertions.

`PublicStudent.createdAt` / `updatedAt` are `Date` — their decoding depends on
the `APIClient` date wire contract. All wire types are `Codable`; aside from
`ErrorResponse`'s `status` exclusion, field names map 1:1 to JSON keys with no
custom `CodingKeys`.

---

## III. Infrastructure & Environment

- **Bundle ID**: `com.unicoachapp.UnicoachiOS` (defined in
  [`Info.plist`](./Info.plist)).
- **Bundle name (`CFBundleName`)**: `Unicoach` (no `CFBundleDisplayName` is
  set).
- **Toolchain**: Minimum deployment target **iOS 17.0** / **Swift 6.0**; zero
  third-party dependencies — only `SwiftUI`, `Foundation`, and `os` from the
  Apple standard-library frameworks.
- **Backend base URL**: Resolved at launch by `defaultBackendURL()` from the
  `UnicoachBackendURL` `Info.plist` key, baked at build time from the
  `UNICOACH_BACKEND_URL` build setting (`Info.plist` value
  `$(UNICOACH_BACKEND_URL)`). When the key is unset, empty, or unparseable, the
  resolver falls back to `http://localhost:8080`. Overridable via constructor
  injection (tests pass an explicit `baseURL`).
- **Client key**: Baked at build time from the `UNICOACH_CLIENT_KEY` build
  setting into the `UnicoachClientKey` `Info.plist` key (`Info.plist` value
  `$(UNICOACH_CLIENT_KEY)`), read at launch by `defaultClientKey()`. Blank/unset
  bakes no key, so `APIClient` sends no `X-Unicoach-Client-Key` header (the
  disabled local gate accepts this). Overridable via constructor injection. The
  key is orthogonal to the session cookie: it identifies the client app, not the
  user.
- **Transport timeouts**: the request session uses a 10-second
  `timeoutIntervalForRequest`; the stream session uses the 60-second inter-chunk
  idle timeout `APIClient.streamIdleTimeout` (a `static let`, not
  constructor-injectable) — a longer idle window because a session-level value
  cannot be reliably extended per-request and a 10-second idle aborts slow
  time-to-first-token. Both sessions are built in `APIClient.init` when none is
  injected; the sessions themselves are injectable, and an injected session
  backs both paths.
- **SSE framing**: The byte loop preserves empty lines (SSE frame boundaries)
  via the custom `sseLines` splitter — `AsyncLineSequence` (`bytes.lines`) is
  not used because it discards empty lines. A complete buffered frame whose
  terminating blank line was cut off is flushed at end of stream; a genuinely
  partial frame is dropped. CRLF- and LF-delimited bodies parse identically.
- **Date decoding**: `APIClient`'s decoder parses the server's ISO-8601
  `Instant` strings with **variable-precision fractional seconds** and a
  trailing `Z` (`2025-01-07T22:16:27.092942Z`; `2025-01-07T22:16:27Z` on a whole
  second) — both forms. Neither stock strategy suffices: `.deferredToDate`
  expects a numeric timestamp; plain `.iso8601` rejects fractional seconds.
- **Error-code wire contract**: Server-emitted error codes are lowercase
  snake_case across all route families (auth `/api/v1/auth/*`, student
  `/api/v1/students*`, conversation `/api/v1/conversations*` — e.g.
  `unauthorized`, `student_already_exists`, `student_profile_required`,
  `email_not_verified`). Client-synthesized codes (`TIMEOUT`, `NETWORK_ERROR`,
  `SERVER_ERROR`, `VALIDATION`, `UNKNOWN`, `DECODE_ERROR`) are UPPERCASE, so the
  two namespaces never collide. Code matching is exact and case-sensitive. (This
  is a durable guarantee — see [INVARIANTS.md](./INVARIANTS.md).)
- **Transport security**:
  `NSAppTransportSecurity → NSAllowsArbitraryLoads: true` in `Info.plist` —
  required for plain HTTP to a LAN/Tailscale dev host.
- **Window rendering**: `Info.plist` declares a `UILaunchScreen` key (an empty
  dictionary), opting the app into native full-screen rendering; without it
  every screen reverts to legacy letterbox compatibility mode (a reduced-height
  canvas with black bands).
- **Cookie storage**: Injected via `CookieStorageProtocol` (production:
  `HTTPCookieStorage.shared`).
- **Test isolation**: Client tests (`APIClient` / `AuthClient` / `StudentClient`
  / `ConversationClient`) inject `MockURLProtocol` through an ephemeral
  `URLSession` via `APIClient(baseURL:session:)` — avoiding cross-test cookie
  contamination; ViewModel tests use protocol mocks (`MockAuthClient`,
  `MockStudentClient`, `MockConversationClient`). See
  [../UnicoachiOSTests/TESTING.md](../UnicoachiOSTests/TESTING.md) for
  test-writing conventions.
- **File registration**: New `.swift` files (app or test target) are registered
  in `project.pbxproj` — the project uses explicit file references, not
  filesystem synchronization; unregistered files silently never compile.
- **Build/test**: Run via **system Xcode**, not the Nix dev shell:
  ```
  xcodebuild test \
    -project ios-app/UnicoachiOS.xcodeproj \
    -scheme UnicoachiOS \
    -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
  ```

---

## IV. History

- [x] [RFC-12: iOS Application Registration Spec](../../rfc/12-ios-app.md)
- [x] [RFC-27: iOS Login / Logout](../../rfc/27-ios-login-logout.md)
- [x] [RFC-30: Auth UI Design System (iOS)](../../rfc/30-auth-ui-styling.md)
- [x] [RFC-41: iOS Start Coaching Conversation](../../rfc/41-ios-start-coaching-conversation.md)
- [x] [RFC-42: iOS Student-Profile Onboarding](../../rfc/42-ios-student-profile-onboarding.md)
- [x] [RFC-48: iOS multi-turn coaching conversation](../../rfc/48-ios-multi-turn-conversation.md)
- [x] [RFC-49: iOS Chat UX Improvements](../../rfc/49-ios-chat-ux-improvements.md)
- [x] [RFC-51: iOS Deploy to Physical Device](../../rfc/51-ios-deploy-to-device.md)
- [x] [RFC-54: Client-Key Gate](../../rfc/54-client-key-gate.md)
- [x] [RFC-56: iOS Conversation History](../../rfc/56-ios-conversation-history.md)
- [x] [RFC-58: iOS conversation-list archive and delete actions](../../rfc/58-ios-conversation-list-actions.md)
- [x] [RFC-69: Email-Verification Gate + Error-Code Unification (Backend)](../../rfc/69-email-verification-gate.md)
- [x] [RFC-72: iOS Email-Verification UX](../../rfc/72-ios-email-verification-ux.md)
      </content>
      </invoke>
