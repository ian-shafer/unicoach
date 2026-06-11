# RFC 42: iOS Student-Profile Onboarding

## Executive Summary

The UnicoachiOS app routes every authenticated user straight to `HomeView` and
has no concept of a student profile, so there is no iOS surface for the backend
`POST /api/v1/students` endpoint. The coaching feature
([rfc/39](39-ios-coaching-conversation-api.md)) requires a student profile and
rejects profile-less callers, but no iOS coaching surface exists yet — so a
profile-less user is currently stuck. This RFC builds the missing onboarding: a
profile-less authenticated user is detected at launch and routed to a form that
creates the profile.

A new `UserAuthState.onboarding(PublicUser)` branch carries the gating, an
`OnboardingView`/`OnboardingViewModel` captures the graduation date, a new
`StudentClient` calls the endpoint, and the HTTP plumbing in `AuthClient` is
extracted into a shared `APIClient`. The gate is unconditional — all
authenticated users are treated as students.

The change is additive to the REST server's existing student API; no backend
modification is required.

## Detailed Design

### Data Models

Three structs are added to `Models.swift`, mirroring the `PublicStudent`
contract in `openapi.yaml` and `StudentRoutes.kt`. All are `Codable` with field
names mapping 1:1 to JSON keys.

```
struct CreateStudentRequest: Codable
    let expectedHighSchoolGraduationDate: String

struct PublicStudent: Codable, Equatable
    let id: UUID
    let expectedHighSchoolGraduationDate: String
    let version: Int
    let createdAt: Date
    let updatedAt: Date

struct StudentResponse: Codable
    let student: PublicStudent
```

`expectedHighSchoolGraduationDate` is the zero-padded canonical ISO wire string
matching `^\d{4}(-\d{2}(-\d{2})?)?$` (`YYYY` | `YYYY-MM` | `YYYY-MM-DD`),
carried as a `String`; the app never models it as a structured date type across
the wire. `PublicStudent` is `Equatable` to support `XCTAssertEqual` in tests.

### Networking Layer

`APIClient` is a single concrete class owning all JSON-over-HTTP plumbing,
extracted from the current `AuthClient`. It is domain-agnostic: it knows no
endpoints and decides no per-status semantics. It holds an immutable `baseURL`
and `URLSession` (configured with the 10-second request timeout) and is
`@unchecked Sendable`.

```
final class APIClient: @unchecked Sendable
    let baseURL: URL
    let session: URLSession
    init(baseURL: URL = URL(string: "http://localhost:8080")!, session: URLSession? = nil)

    func post<B: Encodable>(_ path: String, body: B) async throws -> (data: Data, response: HTTPURLResponse)
    func post(_ path: String) async throws -> (data: Data, response: HTTPURLResponse)
    func get(_ path: String) async throws -> (data: Data, response: HTTPURLResponse)

    func decode<T: Decodable>(data: Data, response: HTTPURLResponse, expectedStatus: Int) throws -> T
    func expect(data: Data, response: HTTPURLResponse, expectedStatus: Int) throws
```

`post`/`get` perform a JSON request and guarantee an `HTTPURLResponse` to the
caller, JSON-encoding the body when present. Transport failures map to the
`ErrorResponse` envelope: `NSURLErrorTimedOut` → `TIMEOUT`; any other network
error → `NETWORK_ERROR`; a non-`HTTPURLResponse` → `UNKNOWN`. `decode` returns
the decoded `T` on `response.statusCode == expectedStatus` (`DECODE_ERROR` on a
parse failure), otherwise throws the body decoded as `ErrorResponse`
(`SERVER_ERROR` when the body is unparseable). `expect` is the empty-body
counterpart, applying the same status check without decoding (e.g. HTTP 204,
consumed by `AuthClient.logout`). This preserves `AuthClient`'s current behavior
exactly; status-to-outcome interpretation that diverges between clients stays in
the clients.

`decode` uses a `JSONDecoder` configured for the server's wire encoding of
`Instant` timestamps. The REST server serializes `Instant` via Jackson's
`JavaTimeModule`, emitting ISO-8601 strings with variable-precision fractional
seconds and a trailing `Z` (e.g. `2025-01-07T22:16:27.092942Z`, or
`2025-01-07T22:16:27Z` on a whole second). `JSONDecoder`'s default
`.deferredToDate` strategy expects a numeric timestamp and fails on these, and
`.iso8601` rejects fractional seconds — so the decoder is given a custom date
strategy that parses with a fractional-seconds formatter and falls back to a
no-fraction one. `PublicStudent.createdAt`/`updatedAt` are the only `Date`
fields on the wire (`PublicUser` has none), so this is the field that makes the
strategy load-bearing. Test fixtures MUST encode these timestamps as ISO-8601
strings, not Swift's numeric `Date` default, or they silently diverge from the
real boundary.

`AuthClient` retains `AuthClientProtocol` and its four methods unchanged in
signature, but its initializer changes to compose the shared layer:

```
class AuthClient: AuthClientProtocol, @unchecked Sendable
    init(apiClient: APIClient = APIClient())
```

`StudentClient` is the student-domain wrapper. Its protocol is segregated from
`AuthClientProtocol` so view models depend only on the surface they use.

```
protocol StudentClientProtocol: Sendable
    func createStudent(request: CreateStudentRequest) async throws -> PublicStudent
    func fetchProfile() async throws -> PublicStudent?

class StudentClient: StudentClientProtocol, @unchecked Sendable
    init(apiClient: APIClient = APIClient())
```

`createStudent` posts to `/api/v1/students`, decodes the 201 `StudentResponse`,
and returns its `student`. `fetchProfile` gets `/api/v1/students/me`; **a 404
response returns `nil`** — the "no profile yet" state is a domain outcome, not
an error — keyed on the HTTP status, not on the body's `STUDENT_NOT_FOUND` code,
which is never inspected. Every other non-200 status throws the decoded
`ErrorResponse`. Each client keeps its own `Logger` for its request-start debug
message; `APIClient` logs only status receipt. No log emits passwords, tokens,
or session identifiers.

### Routing

`UserAuthState` gains one branch. `Equatable` compares it by the carried user
id, matching the existing `authenticated` arm.

```
enum UserAuthState: Equatable
    case loading
    case unauthenticated
    case onboarding(PublicUser)        // authenticated, no student profile
    case authenticated(PublicUser)     // authenticated, profile exists
    case serverError
    case noConnectivity
```

`AppViewModel` injects the shared `APIClient` and both clients, and routes all
three authentication entry points through one profile resolve.

```
@MainActor class AppViewModel: ObservableObject
    init(apiClient: APIClient = APIClient(),
         cookieStorage: CookieStorageProtocol = HTTPCookieStorage.shared,
         authClient: AuthClientProtocol? = nil,        // nil → AuthClient(apiClient:)
         studentClient: StudentClientProtocol? = nil)  // nil → StudentClient(apiClient:)

    func checkSession() async                          // launch
    func onLoginSuccess(_ user: PublicUser) async
    func onRegisterSuccess(_ user: PublicUser) async
    func onOnboardingComplete(_ user: PublicUser)      // onboarding → authenticated
```

The production call sites use the zero-argument `AppViewModel()` (all defaults):
nil `authClient`/`studentClient` trigger construction of one `APIClient` handed
to both real clients, and `cookieStorage` keeps its existing
`HTTPCookieStorage.shared` default. Tests inject mocks, leaving the `APIClient`
default unused. `checkSession` calls `me()` first; on success it delegates to a
private `resolveProfileState(user)`, as do `onLoginSuccess` and
`onRegisterSuccess`. The success callbacks are `async` so the resolve runs
inline before the awaiting view model's loading state clears, avoiding an
intermediate screen.

`resolveProfileState(user)` maps `fetchProfile()` outcomes to state:

| Outcome                                                 | State                  |
| ------------------------------------------------------- | ---------------------- |
| returns a `PublicStudent`                               | `.authenticated(user)` |
| returns `nil` (404)                                     | `.onboarding(user)`    |
| throws `ErrorResponse` code `TIMEOUT` / `NETWORK_ERROR` | `.noConnectivity`      |
| throws `ErrorResponse` code `UNAUTHORIZED`              | `.unauthenticated`     |
| any other throw                                         | `.serverError`         |

The student route emits `UNAUTHORIZED` in uppercase, unlike the auth route's
lowercase `unauthorized` that `checkSession`'s `me()` branch already matches;
the resolve mapping uses the student-route casing.

`UnicoachiOSApp` adds the `.onboarding(user)` arm to its root switch, presenting
`OnboardingView` with the shared `studentClient` and an `onComplete` closure
that invokes `onOnboardingComplete(user)`.

### Onboarding Form

`OnboardingViewModel` owns the form state, assembles the canonical ISO string,
and submits it. The picker-derived state cannot represent a malformed or
impossible date, so no client-side validation precedes submission; the server's
`VALIDATION_ERROR` remains the authoritative check and is surfaced as a form
error (tested — see `testSubmitValidationErrorSetsError`).

```
@MainActor class OnboardingViewModel: ObservableObject
    enum Precision { case year, yearMonth, full }
    @Published var precision: Precision
    @Published var year: Int
    @Published var month: Int        // 1...12
    @Published var day: Int          // clamped into dayRange
    @Published var isLoading: Bool
    @Published var errorResponse: ErrorResponse?

    var dayRange: ClosedRange<Int>   // derived from year + month, leap-aware
    var isoDate: String              // zero-padded canonical string at selected precision

    init(studentClient: StudentClientProtocol, onComplete: @escaping () -> Void,
         year: Int)
    func submit() async
```

`year` is initialized to the current calendar year (tests inject a fixed value
for determinism); the selectable range is
`(year − yearWindowBack) ... (year + yearWindowForward)` where both offsets are
named constants (default 4 back, 8 forward). `dayRange` is recomputed from the
selected year and month (28/29/30/31, leap-aware) and `day` is clamped into it
on any change, so `Feb 30` / `Apr 31` are never representable. `isoDate` emits
`YYYY`, `YYYY-MM`, or `YYYY-MM-DD` with zero-padded month and day at the
selected precision.

`submit` sets `isLoading`, builds
`CreateStudentRequest(expectedHighSchoolGraduationDate: isoDate)`, and calls
`createStudent`. Success invokes `onComplete`. A thrown `ErrorResponse` with
code `STUDENT_ALREADY_EXISTS` is treated as completion (the profile now exists;
a concurrent device created it) and also invokes `onComplete`. Any other
`ErrorResponse` is stored in `errorResponse`; a non-`ErrorResponse` throw
becomes an `ErrorResponse` with code `UNKNOWN` (nil `fieldErrors`). `isLoading`
reverts on every path.

`OnboardingView` is a `SwiftUI` view owning its `OnboardingViewModel` via
`@StateObject`. It presents a segmented precision `Picker` and `year` / `month`
/ `day` value pickers (month and day shown only at the matching precision), an
error banner bound to `errorResponse`, and a loading-aware create button.
Accessibility identifiers: `precisionPicker`, `yearPicker`, `monthPicker`,
`dayPicker`, `createProfileButton`, `loadingIndicator`. The view delegates all
side effects to the view model.

### API Contracts

Consumed unchanged from the REST server (verified against `openapi.yaml` and
`StudentRoutes.kt`):

- `POST /api/v1/students` — body `CreateStudentRequest`; **201**
  `StudentResponse`; **400** `ErrorResponse(VALIDATION_ERROR)` with
  `fieldErrors[field =
  "expectedHighSchoolGraduationDate"]`; **401**
  `ErrorResponse(UNAUTHORIZED)`; **409**
  `ErrorResponse(STUDENT_ALREADY_EXISTS)`.
- `GET /api/v1/students/me` — **200** `StudentResponse`; **401**
  `ErrorResponse(UNAUTHORIZED)`; **404** `ErrorResponse(STUDENT_NOT_FOUND)`.

Session authentication is the existing cookie persisted by `URLSession` into
`HTTPCookieStorage.shared`; no new auth handling is introduced.

### Error Handling / Edge Cases

- **No profile (404)** — modeled as `fetchProfile() == nil`, routed to
  onboarding; never surfaced as an error.
- **Stale gate (409 on submit)** — a profile created on another device between
  the gate check and submit yields `STUDENT_ALREADY_EXISTS`; treated as
  completion.
- **Profile resolve fails after `me()` succeeds** — mapped per the
  `resolveProfileState` table; the user is never stranded on a blank
  authenticated screen.
- **Invalid date** — unrepresentable by construction; the server's
  `VALIDATION_ERROR` is still surfaced as a form error.

### Dependencies

None added. The app remains on `SwiftUI`, `Foundation`, and `os`, deployment
target iOS 17.0 / Swift 6.0, zero third-party dependencies. No backend change.

## Tests

All iOS tests run via `xcodebuild test` against the `UnicoachiOS` scheme on an
iOS Simulator destination. Client tests inject `MockURLProtocol` via an
ephemeral `URLSessionConfiguration`; view-model tests inject protocol mocks.

### `APIClientTests.swift` (new)

- `testPostSuccessDecodesBody` — 201 body decodes to the expected type.
- `testDecodeMismatchThrowsDecodeError` — malformed success body →
  `DECODE_ERROR`.
- `testErrorBodyIsThrownThrough` — non-expected status with a valid
  `ErrorResponse` body throws that envelope verbatim (code and message
  preserved).
- `testUnparseableErrorBodyThrowsServerError` — non-JSON error body →
  `SERVER_ERROR`.
- `testTimeoutMapsToTimeout` — `MockURLProtocol` `didFailWithError`
  `NSURLErrorTimedOut` → `TIMEOUT`.
- `testNetworkErrorMapsToNetworkError` — other `NSURLError` → `NETWORK_ERROR`.
- `testBodyPostSetsContentType` / `testNoBodyPostOmitsContentType` — the `body:`
  overload sets `Content-Type: application/json` and a request body; the no-body
  `post(_:)` overload sends neither.
- `testExpectAcceptsMatchingStatus` / `testExpectRejectsMismatchedStatus` — 204
  passes; non-204 throws the decoded envelope.

### `StudentClientTests.swift` (new)

- `testCreateStudentSuccessReturnsPublicStudent` — asserts
  `POST /api/v1/students`, `Content-Type: application/json`, request body
  `{expectedHighSchoolGraduationDate}`, and returns the decoded `PublicStudent`.
- `testCreateStudentValidationError` — 400 throws
  `ErrorResponse(VALIDATION_ERROR)` with a `fieldErrors` entry for
  `expectedHighSchoolGraduationDate`.
- `testCreateStudentAlreadyExists` — 409 throws
  `ErrorResponse(STUDENT_ALREADY_EXISTS)`.
- `testCreateStudentUnauthorized` — 401 throws `ErrorResponse(UNAUTHORIZED)`.
- `testFetchProfileSuccess` — asserts `GET /api/v1/students/me`; returns the
  decoded `PublicStudent`.
- `testFetchProfileNotFoundReturnsNil` — 404 returns `nil`.
- `testFetchProfileUnauthorizedThrows` — 401 throws
  `ErrorResponse(UNAUTHORIZED)`.

### `MockStudentClient.swift` (new)

Test double conforming to `StudentClientProtocol` with
`createStudentResult:
Result<PublicStudent, Error>?` and
`fetchProfileResult: Result<PublicStudent?,
Error>?`, returning or throwing the
configured value.

### `OnboardingViewModelTests.swift` (new)

- `testIsoDateYearPrecision` / `testIsoDateYearMonthPrecision` /
  `testIsoDateFullPrecision` — assemble `"2028"`, `"2028-06"`, `"2028-06-15"`
  with zero-padded month and day.
- `testDayRangeLeapFebruary` / `testDayRangeNonLeapFebruary` /
  `testDayRangeApril` / `testDayRangeJanuary` — `1...29`, `1...28`, `1...30`,
  `1...31`.
- `testDayClampsWhenMonthShortens` — `day = 31`, switch month to February →
  `day` clamps to the February upper bound.
- `testSubmitSuccessInvokesComplete` — `createStudent` receives the assembled
  ISO string; `onComplete` fires; `isLoading` toggles true→false.
- `testSubmitAlreadyExistsInvokesComplete` — 409 `STUDENT_ALREADY_EXISTS` fires
  `onComplete`; `errorResponse` stays nil.
- `testSubmitValidationErrorSetsError` — 400 sets `errorResponse`; `onComplete`
  not fired.
- `testSubmitUnknownErrorMapsToUnknown` — non-`ErrorResponse` throw →
  `errorResponse.code == "UNKNOWN"`; `onComplete` not fired.

### `AppViewModelTests.swift` (modified)

- `setUp` injects `MockStudentClient` alongside `MockAuthClient` and
  `MockCookieStorage`.
- `testCheckSessionAuthenticatedOnSuccess` (rewritten) — `me()` success +
  `fetchProfile` returns a `PublicStudent` → `.authenticated(user)`.
- `testCheckSessionOnboardingWhenNoProfile` — `me()` success + `fetchProfile`
  returns `nil` → `.onboarding(user)`.
- `testCheckSessionProfileFetchUnauthorized` — `fetchProfile` throws
  `UNAUTHORIZED` → `.unauthenticated`.
- `testCheckSessionProfileFetchTimeout` — `fetchProfile` throws `TIMEOUT` →
  `.noConnectivity`.
- `testCheckSessionProfileFetchServerError` — `fetchProfile` throws other →
  `.serverError`.
- `testOnLoginSuccessRoutesToOnboarding` /
  `testOnLoginSuccessRoutesToAuthenticated` — `await onLoginSuccess(user)` with
  `fetchProfile` nil / non-nil.
- `testOnRegisterSuccessRoutesToOnboarding` — `await onRegisterSuccess(user)`
  with `fetchProfile` nil → `.onboarding(user)`.
- `testOnOnboardingCompleteTransitionsToAuthenticated` — from
  `.onboarding(user)`, `onOnboardingComplete(user)` → `.authenticated(user)`.
- Existing `me()`-failure (including the lowercase `unauthorized` →
  `.unauthenticated` case, which guards the auth-vs-student casing split),
  logout, and cookie tests retained.

### `AuthClientTests.swift` (modified)

`setUp` constructs `APIClient(baseURL:, session: <ephemeral + MockURLProtocol>)`
then `AuthClient(apiClient:)`. All existing register/login/logout/me assertions
remain unchanged, proving the `APIClient` extraction is behavior-preserving.

## Implementation Plan

Every new `.swift` file must be registered in
`ios-app/UnicoachiOS.xcodeproj/project.pbxproj` in four places — `PBXBuildFile`,
`PBXFileReference`, the owning `PBXGroup`, and the target's `Sources` build
phase — under the correct target (`UnicoachiOS` for app sources,
`UnicoachiOSTests` for test sources). A file omitted from `pbxproj` silently
fails to compile.

Verification commands, run from the repo root (system Xcode, not the Nix shell):

```
BUILD: xcodebuild -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS build
TEST:  xcodebuild test -workspace ios-app/UnicoachiOS.xcodeproj/project.xcworkspace -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16'
```

1. **Add student models.** Append `CreateStudentRequest`, `PublicStudent`,
   `StudentResponse` to `Models.swift`. Verify: BUILD.
2. **Extract `APIClient`.** Create `APIClient.swift` (+pbxproj, app target)
   holding the request-building, transport-error mapping, `decode`, and `expect`
   logic moved out of `AuthClient`. Verify: BUILD.
3. **Recompose `AuthClient`.** Change its initializer to
   `init(apiClient: APIClient = APIClient())` and reimplement its four methods
   over the shared layer. Update `AuthClientTests.setUp` to inject an
   `APIClient`. Verify: TEST (all `AuthClientTests` pass unchanged).
4. **Create `StudentClient`.** Add `StudentClient.swift` (+pbxproj, app target)
   with `StudentClientProtocol` and the concrete client
   (`init(apiClient: APIClient
   = APIClient())`), including the 404→`nil`
   branch in `fetchProfile`. Verify: BUILD.
5. **Add student client tests.** Create `MockStudentClient.swift`,
   `StudentClientTests.swift`, and `APIClientTests.swift` (+pbxproj, test
   target). Verify: TEST.
6. **Extend `UserAuthState`.** Add `case onboarding(PublicUser)` and its
   `Equatable` arm. Verify: BUILD (switch-exhaustiveness surfaces the
   `UnicoachiOSApp` gap, fixed in step 11).
7. **Create `OnboardingViewModel`.** Add `OnboardingViewModel.swift` (+pbxproj,
   app target) with picker state, `dayRange`, `isoDate`, and `submit`. Verify:
   BUILD.
8. **Add onboarding view-model tests.** Create `OnboardingViewModelTests.swift`
   (+pbxproj, test target). Verify: TEST.
9. **Create `OnboardingView`.** Add `OnboardingView.swift` (+pbxproj, app
   target) with precision/value pickers, error banner, create button, and
   accessibility identifiers. Verify: BUILD.
10. **Wire the gate.** Update `AppViewModel` (`apiClient` + `studentClient`
    injection, `resolveProfileState`, `onOnboardingComplete`, `async`
    `onLoginSuccess`/`onRegisterSuccess`, `checkSession` routing through the
    resolve). Propagate the `async` callback type end-to-end: the closure type
    becomes `(PublicUser) async -> Void` through `LoginViewModel`, `LoginView`,
    `RegistrationViewModel`, `RegistrationView`, and `AuthFlowView`, and each
    invocation gains `await`. Verify: BUILD.
11. **Route to onboarding.** Add the `.onboarding(user)` arm to
    `UnicoachiOSApp`'s root switch, presenting `OnboardingView` with the shared
    `studentClient` and an `onComplete` invoking `onOnboardingComplete(user)`.
    Verify: BUILD.
12. **Update app view-model tests.** Apply the `AppViewModelTests` changes.
    Verify: TEST (full suite).

## Files Modified

**Created — app target (`ios-app/UnicoachiOS/`):**

- `ios-app/UnicoachiOS/APIClient.swift`
- `ios-app/UnicoachiOS/StudentClient.swift`
- `ios-app/UnicoachiOS/OnboardingViewModel.swift`
- `ios-app/UnicoachiOS/OnboardingView.swift`

**Created — test target (`ios-app/UnicoachiOSTests/`):**

- `ios-app/UnicoachiOSTests/APIClientTests.swift`
- `ios-app/UnicoachiOSTests/StudentClientTests.swift`
- `ios-app/UnicoachiOSTests/MockStudentClient.swift`
- `ios-app/UnicoachiOSTests/OnboardingViewModelTests.swift`

**Modified — app target:**

- `ios-app/UnicoachiOS/Models.swift`
- `ios-app/UnicoachiOS/AuthClient.swift`
- `ios-app/UnicoachiOS/UserAuthState.swift`
- `ios-app/UnicoachiOS/AppViewModel.swift`
- `ios-app/UnicoachiOS/UnicoachiOSApp.swift`
- `ios-app/UnicoachiOS/LoginView.swift`
- `ios-app/UnicoachiOS/LoginViewModel.swift`
- `ios-app/UnicoachiOS/RegistrationView.swift`
- `ios-app/UnicoachiOS/RegistrationViewModel.swift`
- `ios-app/UnicoachiOS/AuthFlowView.swift`

**Modified — test target:**

- `ios-app/UnicoachiOSTests/AuthClientTests.swift`
- `ios-app/UnicoachiOSTests/AppViewModelTests.swift`

**Modified — project:**

- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` (register all eight created
  files four-fold under the correct target)
