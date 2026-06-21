# SPEC.md — `rest-server/src/main/kotlin/ed/unicoach/rest/routing`

## I. Overview

The HTTP routing layer of the unicoach platform. This directory declares all
`/api/v1/auth/*`, `/api/v1/students/*`, and `/api/v1/conversations/*` route
handlers:

- [`AuthRoutes.kt`](./AuthRoutes.kt) — authentication and email-verification
  flows. Owns the full session cookie lifecycle: minting on registration and
  login, reading on identity resolution, and clearing on logout. Delegates
  credential/session decisions to `AuthService` and email-verification decisions
  to `EmailVerificationService`.
- [`StudentRoutes.kt`](./StudentRoutes.kt) — the owner-resolved student profile
  resource. Every handler resolves the current `User` from the session cookie
  via `AuthService`, then delegates to `StudentService`. There is no path
  identifier — the profile is always the caller's own.
- [`ConvoRoutes.kt`](./ConvoRoutes.kt) — the coaching-conversation resource
  family (the nine operations of `api-specs/openapi.yaml`). Resolves the caller
  to a `Student` (cookie → `User` → `Student`) before any `CoachingService`
  call, then maps coaching outcomes onto buffered JSON responses or hand-rolled
  Server-Sent-Event streams.

Routes contain no domain logic. They map cookies and request bodies onto service
calls and map service outcomes onto HTTP responses.

---

## II. Behavioral Contracts

### II-A. Cross-Cutting Behavior

These behaviors recur across handlers and are described once here rather than
re-tabulated per endpoint.

#### Route Structure & Method Restriction

- Auth routes register under the `/api/v1/auth` block, student routes under
  `/api/v1/students`, and conversation routes under `/api/v1/conversations`,
  each installed by its handler's `registerRoutes(route)`.
- Every leaf route block that permits a fixed method set wraps it in a
  `route(...)` block calling `rejectUnsupportedMethods(...)` with exactly its
  allowed methods. Any other verb returns `405 Method Not Allowed` with an
  RFC-9110 `Allow` header. This applies to `/register`, `/login`, `/auth/me`,
  `/logout`, `/auth/verify-email`, `/auth/resend-verification` (all `POST`/`GET`
  single-verb), `/students` (`POST`), `/students/me` (`GET`/`PATCH`/`DELETE`),
  the conversation collection, `/conversations/stream`,
  `/conversations/{conversationId}`, `…/messages`, and `…/messages/stream`.
- The student resource is owner-resolved: no path id is exposed. `POST` targets
  the collection (`/api/v1/students`); `GET`/`PATCH`/`DELETE` target the
  singleton `/api/v1/students/me`.

#### Token Handling

- For **identity resolution** (`auth/me`, `logout`, `resend-verification`, and
  every student/conversation handler), the raw cookie value is hashed via
  `TokenHash.fromRawToken(token)` (SHA-256) before any service call. The plain
  token is never a lookup key.
- For **session hand-off** (`register`, `login`), the raw `oldCookieToken` is
  forwarded verbatim to the service, which owns the remint decision. This is the
  only path on which an unhashed token leaves the routing layer.
- For **email verification** (`verify-email`), the body-carried verification
  token is forwarded verbatim to `EmailVerificationService.verify`; it is not a
  session token and is not hashed by the routing layer.
- Raw tokens are not validated against a format/regex at the routing layer — any
  value is hashed and looked up, which matches 0 rows for invalid tokens,
  collapsing "wrong format" and "not found" into one outcome.

#### Session Cookie

- The session cookie is set with `HttpOnly = true`, `SameSite = Strict`,
  `path = "/"`, `secure = sessionConfig.cookieSecure`, and
  `domain = sessionConfig.cookieDomain`.
- Cookie-clearing (on logout, and on a successful `DELETE /api/v1/students/me`)
  uses `maxAge = 0L` with domain, path, secure, HttpOnly, and SameSite
  attributes identical to those used when setting the cookie (an attribute
  mismatch would leave the original cookie intact in the browser).
- The cookie is cleared only on a definitive account/session termination: a
  `204` logout, or a `DeleteStudentResult.Success`. It is not cleared on a
  `DELETE` returning `404 STUDENT_NOT_FOUND`, on an unauthenticated request, or
  on any thrown service exception.

#### Error Mapping

- Route logic calls `getOrThrow()` on `Result<T>` returned by the service layer.
  Thrown `Exception`s propagate outward and are mapped by the Ktor `StatusPages`
  plugin.
- Known domain outcomes (e.g. `RegisterResult.ValidationFailure`,
  `RegisterResult.DuplicateEmail`, `VerifyEmailResult.Expired`,
  `CreateStudentResult.AlreadyExists`, `UpdateStudentResult.VersionConflict`)
  are handled explicitly in exhaustive `when` branches over the sealed result
  type.

#### Error-Code Casing (Per Route Family)

Error codes are a per-route-family convention, not a global one. `AuthRoutes`
and `ConvoRoutes` emit **lowercase snake_case** codes (`unauthorized`,
`validation_failed`, `conflict`, `invalid_token`, `token_expired`,
`token_already_used`, `not_found`, `student_profile_required`,
`coach_unavailable`, `coach_failed`). `StudentRoutes` emits **UPPERCASE** codes
(`UNAUTHORIZED`, `VALIDATION_ERROR`, `STUDENT_NOT_FOUND`,
`STUDENT_ALREADY_EXISTS`, `VERSION_CONFLICT`). A code's casing is part of that
route's contract; clients match per route.

#### User Projection

`register`, `login`, `me`, and `verify-email` build a `PublicUser` from the
service-returned `User`, including `emailVerified`, derived as
`user.emailVerifiedAt != null`.

#### Conversation Caller Resolution

- Every conversation handler resolves identity in two steps before any
  `CoachingService` call: cookie → `AuthService.getCurrentUser` (`User`), then
  `StudentService.getStudentForUser` (`Student`). The resolved `Student.id` is
  the sole owner key; no owner is read from path or body.
- A missing cookie or a `null` user short-circuits to `401`
  (`code = "unauthorized"`) before any coaching call.
- "Authenticated user with no `Student` profile" is handled per operation:
  - **Create / stream-create** (`POST /conversations`,
    `POST /conversations/stream`) → `409` (`code = "student_profile_required"`).
  - **List** (`GET /conversations`) → `200` with an empty list.
  - **All per-conversation reads/mutations** (`GET`/`PATCH`/`DELETE` on
    `/{conversationId}`, `GET`/`POST` on `…/messages`, stream-message) → `404`
    (`code = "not_found"`).
- A malformed (non-UUID) `{conversationId}` segment maps to `404`
  (`code = "not_found"`), never `400`. The id is parsed leniently
  (`runCatching`); an unparseable id is indistinguishable from a non-existent
  one.
- The `status` query parameter on `GET /conversations` accepts only `active` (or
  absent) → unarchived and `archived` → archived; any other value returns `400`
  (`code = "validation_failed"`, field `status`).

#### Message Identity Projection

Wire `Message.id` values are opaque, role-prefixed strings: a user message is
`"u_" + ConvoRequest.id`, a coach message is `"c_" + ConvoResponse.id`. The
prefix disambiguates the two id spaces in a single flat message list; the whole
string is opaque to clients.

#### SSE Streaming

- The two streaming endpoints (`POST /conversations/stream`,
  `POST /conversations/{conversationId}/messages/stream`) resolve all pre-flight
  outcomes — unauthenticated, missing profile, unknown conversation, and request
  `ValidationFailure` — as ordinary buffered HTTP responses
  (`401`/`409`/`404`/`400`) before the event stream is opened. An error is not
  reported as an in-stream `error` frame once the `text/event-stream` response
  has begun.
- Once `streamReply` opens the stream it writes exactly one opening event
  (`conversation` for create, `user_message` for message), then relays zero or
  more `delta` frames, then terminates with exactly one terminal frame — a
  single `message` (on `ReplyEvent.Completed`) or a single `error` (on
  `ReplyEvent.Failed`). The reply `Flow` carries exactly one terminal event.
- SSE responses carry `Cache-Control: no-store` and content type
  `text/event-stream`. Each event is framed as `event: {type}\ndata: {json}\n\n`
  and flushed.
- SSE event payloads are serialized by a handler-private mapper with
  `INDENT_OUTPUT` disabled (a multi-line `data:` payload would break SSE
  framing); it is not the shared pretty-printing serializer.
- A coach failure's retriability drives its error code: a retriable failure maps
  to `coach_unavailable`, a non-retriable failure to `coach_failed`. This
  mapping is identical whether the failure surfaces as a buffered `500`
  (create/post-message) or an in-stream `error` frame.

---

### `AuthRouteHandler.registerRoutes(route: Route)` — [`AuthRoutes.kt`](./AuthRoutes.kt)

- **Behavior**: Registers all `/api/v1/auth/*` route handlers (`/register`,
  `/login`, `/me`, `/logout`, `/verify-email`, `/resend-verification`) onto the
  Ktor routing tree.
- **Side effects**: Route table registration only — no I/O at call time.
- **Idempotency**: Not idempotent — calling twice installs duplicate routes.

---

### `POST /api/v1/auth/register` — [`AuthRoutes.kt`](./AuthRoutes.kt)

- **Request**: JSON body
  `{"email": string, "password": string, "name": string}`. Deserialized as
  `RegisterRequest`.
- **Side effects**:
  - Calls `AuthService.register()`, forwarding `oldCookieToken`, the session
    expiration, the `User-Agent` header, and the remote host. All persistence
    (the `users` row, session minting/reminting, token generation) is owned by
    the service.
  - On `Success`, writes a `Set-Cookie` header carrying the opaque token
    returned by the service (`outcome.token`).
- **Response mapping**:

  | Condition                            | Status            | Body                                                            |
  | ------------------------------------ | ----------------- | --------------------------------------------------------------- |
  | `RegisterResult.Success`             | `201 Created`     | `RegisterResponse { user: PublicUser }`                         |
  | `RegisterResult.ValidationFailure`   | `400 Bad Request` | `ErrorResponse(code="validation_failed", fieldErrors=[...])`    |
  | `RegisterResult.DuplicateEmail`      | `409 Conflict`    | `ErrorResponse(code="conflict", fieldErrors=[{field="email"}])` |
  | Exceptions thrown by `.getOrThrow()` | (propagated)      | Mapped by `StatusPages`                                         |

- **Method restriction**: Non-POST → `405` with `Allow: POST`.
- **Idempotency**: Not idempotent — duplicate email returns `409`.

---

### `POST /api/v1/auth/login` — [`AuthRoutes.kt`](./AuthRoutes.kt)

- **Request**: JSON body `{"email": string, "password": string}`. Deserialized
  as `LoginRequest`.
- **Side effects**:
  - Calls `AuthService.login()` — verifies credentials and manages session
    state.
  - Writes `Set-Cookie` with the new opaque raw token (`outcome.token`) on
    success.
- **Response mapping**:

  | Condition                            | Status             | Body                                 |
  | ------------------------------------ | ------------------ | ------------------------------------ |
  | `LoginResult.Success`                | `200 OK`           | `LoginResponse { user: PublicUser }` |
  | All other `LoginResult` variants     | `401 Unauthorized` | `ErrorResponse(code="unauthorized")` |
  | Exceptions thrown by `.getOrThrow()` | (propagated)       | Mapped by `StatusPages`              |

- **Method restriction**: Non-POST → `405`.
- **Idempotency**: Not idempotent — creates a new session.

---

### `GET /api/v1/auth/me` — [`AuthRoutes.kt`](./AuthRoutes.kt)

- **Request**: No body. Session identity derived from the request cookie named
  `sessionConfig.cookieName`.
- **Side effects**: Calls `AuthService.getCurrentUser()` — DB read only.
- **Response mapping**:

  | Condition                            | Status             | Body                                 |
  | ------------------------------------ | ------------------ | ------------------------------------ |
  | Cookie absent                        | `401 Unauthorized` | `ErrorResponse(code="unauthorized")` |
  | User successfully fetched            | `200 OK`           | `MeResponse { user: PublicUser }`    |
  | User returned `null`                 | `401 Unauthorized` | `ErrorResponse(code="unauthorized")` |
  | Exceptions thrown by `.getOrThrow()` | (propagated)       | Mapped by `StatusPages`              |

- **Method restriction**: Non-GET → `405`.
- **Idempotency**: Yes — read-only.

---

### `POST /api/v1/auth/logout` — [`AuthRoutes.kt`](./AuthRoutes.kt)

- **Request**: No body. Session identity derived from the request cookie.
- **Side effects**: On a cookie-present request, hashes the token and calls
  `AuthService.logout(tokenHash)` to revoke the session. Always clears the
  session cookie on `204`.
- **Response mapping**:

  | Condition                            | Status           | Cookie Cleared? | Body                    |
  | ------------------------------------ | ---------------- | --------------- | ----------------------- |
  | Cookie absent                        | `204 No Content` | Yes             | None                    |
  | Success                              | `204 No Content` | Yes             | None                    |
  | Exceptions thrown by `.getOrThrow()` | (propagated)     | **No**          | Mapped by `StatusPages` |

- **Method restriction**: Non-POST → `405`.
- **Idempotency**: Yes — logout of an already-revoked, expired, or missing
  session returns `204`.

---

### `POST /api/v1/auth/verify-email` — [`AuthRoutes.kt`](./AuthRoutes.kt)

- **Request**: JSON body `{"token": string}`. Deserialized as
  `VerifyEmailRequest`. No authentication — the verification token is itself the
  credential, so no session cookie is read.
- **Side effects**: Calls `EmailVerificationService.verify(request.token)`. The
  service owns token lookup, expiry/consumption checks, and the
  `users.email_verified_at` update; the routing layer performs no persistence.
  No cookie is set or cleared.
- **Response mapping**:

  | Condition                            | Status            | Body                                                                      |
  | ------------------------------------ | ----------------- | ------------------------------------------------------------------------- |
  | `VerifyEmailResult.Success`          | `200 OK`          | `VerifyEmailResponse { user: PublicUser }` (with `emailVerified == true`) |
  | `VerifyEmailResult.InvalidToken`     | `400 Bad Request` | `ErrorResponse(code="invalid_token")`                                     |
  | `VerifyEmailResult.Expired`          | `400 Bad Request` | `ErrorResponse(code="token_expired")`                                     |
  | `VerifyEmailResult.AlreadyConsumed`  | `400 Bad Request` | `ErrorResponse(code="token_already_used")`                                |
  | Exceptions thrown by `.getOrThrow()` | (propagated)      | Mapped by `StatusPages`                                                   |

- **Method restriction**: Non-POST → `405` with `Allow: POST`.
- **Idempotency**: Not idempotent in effect — the token is single-use; the first
  call verifies, and a replay of the same token returns
  `400 token_already_used`.

---

### `POST /api/v1/auth/resend-verification` — [`AuthRoutes.kt`](./AuthRoutes.kt)

- **Request**: Empty body. Authenticated via the session cookie; the caller is
  resolved identically to `me` (cookie → `TokenHash.fromRawToken` →
  `AuthService.getCurrentUser`).
- **Side effects**: On a resolved `User`, calls
  `EmailVerificationService.resend(user)`. The service decides whether to mint a
  fresh token and send a verification email. No cookie is set or cleared.
- **Response mapping**:

  | Condition                               | Status             | Body                                 |
  | --------------------------------------- | ------------------ | ------------------------------------ |
  | Cookie absent                           | `401 Unauthorized` | `ErrorResponse(code="unauthorized")` |
  | Resolved user is `null`                 | `401 Unauthorized` | `ErrorResponse(code="unauthorized")` |
  | `resend()` succeeds (`Sent`)            | `204 No Content`   | None                                 |
  | `resend()` succeeds (`AlreadyVerified`) | `204 No Content`   | None                                 |
  | Exceptions thrown by `.getOrThrow()`    | (propagated)       | Mapped by `StatusPages`              |

  Both `ResendResult` variants (`Sent`, `AlreadyVerified`) collapse to the same
  `204` with no body, so the response never reveals whether an email was sent or
  whether the account was already verified.

- **Method restriction**: Non-POST → `405` with `Allow: POST`.
- **Idempotency**: Yes for an authenticated caller — every successful call
  returns `204` regardless of current verification state (each `Sent` call may,
  as a side effect, issue another token/email).

---

### `StudentRouteHandler.registerRoutes(route: Route)` — [`StudentRoutes.kt`](./StudentRoutes.kt)

- **Behavior**: Registers all `/api/v1/students*` route handlers onto the Ktor
  routing tree.
- **Side effects**: Route table registration only — no I/O at call time.
- **Idempotency**: Not idempotent — calling twice installs duplicate routes.

---

### `POST /api/v1/students` — [`StudentRoutes.kt`](./StudentRoutes.kt)

- **Request**: JSON body `{"expectedHighSchoolGraduationDate": string}` (ISO,
  variable precision). Deserialized as `CreateStudentRequest`. Owner resolved
  from the session cookie.
- **Side effects**: On a resolved user, calls
  `StudentService.createStudent(userId, ...)` — creates the caller's profile.
- **Response mapping**:

  | Condition                                 | Status             | Body                                                        |
  | ----------------------------------------- | ------------------ | ----------------------------------------------------------- |
  | Unauthenticated (no cookie / `null` user) | `401 Unauthorized` | `ErrorResponse(code="UNAUTHORIZED")`                        |
  | `CreateStudentResult.Success`             | `201 Created`      | `StudentResponse { student: PublicStudent }`                |
  | `CreateStudentResult.ValidationFailure`   | `400 Bad Request`  | `ErrorResponse(code="VALIDATION_ERROR", fieldErrors=[...])` |
  | `CreateStudentResult.AlreadyExists`       | `409 Conflict`     | `ErrorResponse(code="STUDENT_ALREADY_EXISTS")`              |
  | Exceptions thrown by `.getOrThrow()`      | per `StatusPages`  | Processed by `StatusPages`                                  |

- **Method restriction**: Non-POST → `405`.
- **Idempotency**: Not idempotent — a second create for the same owner returns
  `409 STUDENT_ALREADY_EXISTS`.

---

### `GET /api/v1/students/me` — [`StudentRoutes.kt`](./StudentRoutes.kt)

- **Request**: No body. Owner resolved from the session cookie.
- **Side effects**: Calls `StudentService.getStudentForUser(userId)` — read
  only.
- **Response mapping**:

  | Condition                                 | Status             | Body                                         |
  | ----------------------------------------- | ------------------ | -------------------------------------------- |
  | Unauthenticated (no cookie / `null` user) | `401 Unauthorized` | `ErrorResponse(code="UNAUTHORIZED")`         |
  | Profile found                             | `200 OK`           | `StudentResponse { student: PublicStudent }` |
  | Profile is `null`                         | `404 Not Found`    | `ErrorResponse(code="STUDENT_NOT_FOUND")`    |
  | Exceptions thrown by `.getOrThrow()`      | per `StatusPages`  | Processed by `StatusPages`                   |

- **Method restriction**: Only `GET`/`PATCH`/`DELETE` allowed on `/me`; others →
  `405`.
- **Idempotency**: Yes — read-only.

---

### `PATCH /api/v1/students/me` — [`StudentRoutes.kt`](./StudentRoutes.kt)

- **Request**: JSON body
  `{"expectedHighSchoolGraduationDate": string, "version": int}`. Deserialized
  as `UpdateStudentRequest`; `version` carries the caller's expected OCC
  version.
- **Side effects**: Calls
  `StudentService.updateStudent(userId, expectedVersion, ...)` — conditionally
  updates the caller's profile under OCC.
- **Response mapping**:

  | Condition                                 | Status             | Body                                                        |
  | ----------------------------------------- | ------------------ | ----------------------------------------------------------- |
  | Unauthenticated (no cookie / `null` user) | `401 Unauthorized` | `ErrorResponse(code="UNAUTHORIZED")`                        |
  | `UpdateStudentResult.Success`             | `200 OK`           | `StudentResponse { student: PublicStudent }`                |
  | `UpdateStudentResult.ValidationFailure`   | `400 Bad Request`  | `ErrorResponse(code="VALIDATION_ERROR", fieldErrors=[...])` |
  | `UpdateStudentResult.NotFound`            | `404 Not Found`    | `ErrorResponse(code="STUDENT_NOT_FOUND")`                   |
  | `UpdateStudentResult.VersionConflict`     | `409 Conflict`     | `ErrorResponse(code="VERSION_CONFLICT")`                    |
  | Exceptions thrown by `.getOrThrow()`      | per `StatusPages`  | Processed by `StatusPages`                                  |

- **Method restriction**: as for `/me` above.
- **Idempotency**: Not idempotent under OCC — a stale `version` returns
  `409 VERSION_CONFLICT`.

---

### `DELETE /api/v1/students/me` — [`StudentRoutes.kt`](./StudentRoutes.kt)

- **Request**: No body. Owner resolved from the session cookie; the session
  `tokenHash` is also captured for the teardown.
- **Side effects**: Calls
  `StudentService.deleteStudentAndAccount(userId, tokenHash)` — tears down the
  caller's profile and account. On `Success`, clears the session cookie.
- **Response mapping**:

  | Condition                                 | Status             | Cookie Cleared? | Body                                      |
  | ----------------------------------------- | ------------------ | --------------- | ----------------------------------------- |
  | Unauthenticated (no cookie / `null` user) | `401 Unauthorized` | No              | `ErrorResponse(code="UNAUTHORIZED")`      |
  | `DeleteStudentResult.Success`             | `204 No Content`   | **Yes**         | None                                      |
  | `DeleteStudentResult.NotFound`            | `404 Not Found`    | No              | `ErrorResponse(code="STUDENT_NOT_FOUND")` |
  | Exceptions thrown by `.getOrThrow()`      | per `StatusPages`  | No              | Processed by `StatusPages`                |

- **Method restriction**: as for `/me` above.
- **Idempotency**: Not idempotent at the resource level — after a successful
  delete the account is gone and a subsequent request is unauthenticated.

---

### `ConvoRouteHandler.registerRoutes(route: Route)` — [`ConvoRoutes.kt`](./ConvoRoutes.kt)

- **Behavior**: Registers all `/api/v1/conversations*` route handlers (nine
  operations) onto the Ktor routing tree.
- **Side effects**: Route table registration only — no I/O at call time.
- **Idempotency**: Not idempotent — calling twice installs duplicate routes.

All handlers below first resolve the caller to a `Student` (cookie → `User` →
`Student`); the `401`/`409`/`404`/`200-empty` resolution outcomes are governed
by **Conversation Caller Resolution** (§II-A) and are not re-tabulated per row.

---

### `POST /api/v1/conversations` — [`ConvoRoutes.kt`](./ConvoRoutes.kt)

- **Request**: JSON `CreateConversationRequest { message, name? }`.
- **Side effects**: Calls
  `CoachingService.startConvo(student.id, message, name)`, then drains the reply
  `Flow` to its single terminal (buffered — the coach reply is fully
  materialized before responding).
- **Response mapping**:

  | Condition                            | Status                      | Body                                                                     |
  | ------------------------------------ | --------------------------- | ------------------------------------------------------------------------ |
  | `StartConvoResult.ValidationFailure` | `400 Bad Request`           | `ErrorResponse(code="validation_failed", fieldErrors=[...])`             |
  | `Started` → `ReplyEvent.Completed`   | `201 Created`               | `CreateConversationResponse { conversation, userMessage, coachMessage }` |
  | `Started` → `ReplyEvent.Failed`      | `500 Internal Server Error` | `ErrorResponse(code="coach_unavailable" \| "coach_failed")`              |

- **Idempotency**: Not idempotent — creates a conversation and a coach turn.

---

### `POST /api/v1/conversations/stream` — [`ConvoRoutes.kt`](./ConvoRoutes.kt)

- **Request**: JSON `CreateConversationRequest { message, name? }`.
- **Side effects**: Calls `CoachingService.startConvo(...)`. On `Started`, opens
  an SSE stream: opening `conversation` event, then relayed `delta` frames, then
  one terminal `message` or `error` frame.
- **Response mapping**: Pre-flight failures are buffered HTTP before the stream
  opens — `401`/`409` (resolution), `400` (`validation_failed`). On `Started`:
  `200 OK`, `Content-Type: text/event-stream`, `Cache-Control: no-store`, body
  is the event sequence above. Coach failure surfaces as an in-stream `error`
  frame (`coach_unavailable`/`coach_failed`), not an HTTP status change.
- **Idempotency**: Not idempotent.

---

### `GET /api/v1/conversations` — [`ConvoRoutes.kt`](./ConvoRoutes.kt)

- **Request**: No body. Optional `?status=active|archived` query parameter.
- **Side effects**: Calls `CoachingService.listConvos(student.id, archive)` —
  read only.
- **Response mapping**:

  | Condition              | Status            | Body                                                    |
  | ---------------------- | ----------------- | ------------------------------------------------------- |
  | Unknown `status` value | `400 Bad Request` | `ErrorResponse(code="validation_failed", field=status)` |
  | Resolved, no profile   | `200 OK`          | `ConversationListResponse { conversations: [] }`        |
  | Resolved with profile  | `200 OK`          | `ConversationListResponse { conversations: [...] }`     |

- **Idempotency**: Yes — read-only.

---

### `GET /api/v1/conversations/{conversationId}` — [`ConvoRoutes.kt`](./ConvoRoutes.kt)

- **Request**: No body. `{conversationId}` parsed leniently to `ConvoId`.
- **Side effects**: Calls `CoachingService.getConvo(student.id, convoId)` — read
  only.
- **Response mapping**: `200 OK` `ConversationResponse { conversation }` on
  `Found`; `404` (`not_found`) on malformed id, missing profile, or
  `GetConvoResult.NotFound`.
- **Idempotency**: Yes — read-only.

---

### `PATCH /api/v1/conversations/{conversationId}` — [`ConvoRoutes.kt`](./ConvoRoutes.kt)

- **Request**: JSON `UpdateConversationRequest { name?, archived? }` →
  `ConvoUpdate`.
- **Side effects**: Calls `CoachingService.updateConvo(student.id, convoId, …)`
  — renames and/or (un)archives.
- **Response mapping**: `200 OK` `ConversationResponse` on `Success`; `400`
  (`validation_failed`) on `ValidationFailure`; `404` (`not_found`) on malformed
  id, missing profile, or `NotFound`.
- **Idempotency**: Idempotent in effect — re-applying the same name/archive
  state yields the same resource.

---

### `DELETE /api/v1/conversations/{conversationId}` — [`ConvoRoutes.kt`](./ConvoRoutes.kt)

- **Request**: No body.
- **Side effects**: Calls `CoachingService.deleteConvo(student.id, convoId)`.
- **Response mapping**: `204 No Content` on `Success`; `404` (`not_found`) on
  malformed id, missing profile, or `NotFound`.
- **Idempotency**: Not idempotent at the resource level — a second delete
  returns `404`.

---

### `GET /api/v1/conversations/{conversationId}/messages` — [`ConvoRoutes.kt`](./ConvoRoutes.kt)

- **Request**: No body.
- **Side effects**: Calls `CoachingService.listTurns(student.id, convoId)` —
  read only.
- **Response mapping**: `200 OK` `MessageListResponse { messages: [...] }` on
  `Found`, where each turn projects to a user `Message` (`u_…`) followed by a
  coach `Message` (`c_…`) when a response exists; `404` (`not_found`) on
  malformed id, missing profile, or `NotFound`.
- **Idempotency**: Yes — read-only.

---

### `POST /api/v1/conversations/{conversationId}/messages` — [`ConvoRoutes.kt`](./ConvoRoutes.kt)

- **Request**: JSON `PostMessageRequest { message }`.
- **Side effects**: Calls
  `CoachingService.postTurn(student.id, convoId, message)`, then drains the
  reply `Flow` (buffered).
- **Response mapping**:

  | Condition                              | Status                      | Body                                                        |
  | -------------------------------------- | --------------------------- | ----------------------------------------------------------- |
  | `PostTurnResult.ValidationFailure`     | `400 Bad Request`           | `ErrorResponse(code="validation_failed", …)`                |
  | `NotFound` / malformed id / no profile | `404 Not Found`             | `ErrorResponse(code="not_found")`                           |
  | `Started` → `ReplyEvent.Completed`     | `201 Created`               | `PostMessageResponse { userMessage, coachMessage }`         |
  | `Started` → `ReplyEvent.Failed`        | `500 Internal Server Error` | `ErrorResponse(code="coach_unavailable" \| "coach_failed")` |

- **Idempotency**: Not idempotent — appends a turn.

---

### `POST /api/v1/conversations/{conversationId}/messages/stream` — [`ConvoRoutes.kt`](./ConvoRoutes.kt)

- **Request**: JSON `PostMessageRequest { message }`.
- **Side effects**: Calls `CoachingService.postTurn(...)`. On `Started`, opens
  an SSE stream: opening `user_message` event, then `delta` frames, then one
  terminal `message` or `error` frame.
- **Response mapping**: Pre-flight failures are buffered HTTP before the stream
  opens — `401`/`404` (resolution / malformed id / `NotFound`), `400`
  (`validation_failed`). On `Started`: `200 OK`, `text/event-stream`,
  `Cache-Control: no-store`, event sequence above; coach failure is an in-stream
  `error` frame.
- **Idempotency**: Not idempotent — appends a turn.

---

## III. Infrastructure & Environment

This directory contains no module-specific infrastructure requirements beyond
those inherited from the parent `rest` module. Relevant configuration:

| HOCON key              | Source             | Used by                                                                                    |
| ---------------------- | ------------------ | ------------------------------------------------------------------------------------------ |
| `session.cookieName`   | `rest-server.conf` | Cookie read/write in all routes                                                            |
| `session.cookieDomain` | `rest-server.conf` | Cookie `Domain` attribute                                                                  |
| `session.cookieSecure` | `rest-server.conf` | Cookie `Secure` flag                                                                       |
| `session.expiration`   | `rest-server.conf` | Session TTL; forwarded to `AuthService.register()`/`login()` as `sessionExpirationSeconds` |

All four values are parsed by `SessionConfig.from(config)` in the parent `auth/`
package and injected into the route handlers as `sessionConfig`.

### Injected Dependencies

- **`AuthService`** (`service` module): Provides `register()`, `login()`,
  `getCurrentUser()`, and `logout()`. Used by `AuthRouteHandler` for auth flows
  and resend-verification owner resolution, and by `StudentRouteHandler` /
  `ConvoRouteHandler` for owner resolution.
- **`EmailVerificationService`** (`service` module): Provides `verify(token)`
  and `resend(user)`. Held by `AuthRouteHandler` (constructed from an injected
  `EmailService` and `EmailVerificationConfig`) and used only by the
  `verify-email` and `resend-verification` handlers.
- **`StudentService`** (`service` module): Provides `createStudent()`,
  `getStudentForUser()`, `updateStudent()`, and `deleteStudentAndAccount()`.
  `ConvoRouteHandler` uses it solely to resolve the caller's `Student`.
- **`CoachingService`** (`service` module): Provides `startConvo()`,
  `listConvos()`, `getConvo()`, `updateConvo()`, `deleteConvo()`, `listTurns()`,
  and `postTurn()`. Used only by `ConvoRouteHandler`.
- **`SessionConfig`** (`rest/auth/` package): Cookie parameters.

---

## IV. History

- [x] [RFC-08: Auth Registration](../../../../../../../../rfc/08-auth-registration.md)
      — Established the `/register` endpoint contract
      (`RegisterRequest`/`RegisterResponse` shape). Originally referenced
      `rest-server/.../routes/AuthRoutes.kt`; those contracts migrated to the
      current `routing/` path via RFC-11.
- [x] [RFC-10: Auth Login](../../../../../../../../rfc/10-auth-login.md) —
      Introduced the original `POST /api/v1/auth/login` route surface
      (JWT-based) on the predecessor `routes/AuthRoutes.kt` path; superseded by
      the session-based login of RFC-26.
- [x] [RFC-11: Sessions](../../../../../../../../rfc/11-sessions.md)
- [x] [RFC-13: Auth Me](../../../../../../../../rfc/13-auth-me.md)
- [x] [RFC-21: Session Expiry Queue](../../../../../../../../rfc/21-session-expiry-queue.md)
- [x] [RFC-22: Auth Logout](../../../../../../../../rfc/22-auth-logout.md)
- [x] [RFC-24: Result Types](../../../../../../../../rfc/24-result-types.md)
- [x] [RFC-25: Auth Routes Refactor](../../../../../../../../rfc/25-auth-routes-refactor.md)
- [x] [RFC-26: Login](../../../../../../../../rfc/26-login.md)
- [x] [RFC-29: Request Payload Limits](../../../../../../../../rfc/29-request-payload-limits.md)
      — Removed the in-route `Content-Length`/4KB payload check from
      `AuthRoutes.kt`; body-size enforcement now lives in the `plugins/`
      request-size limit and the `413` `StatusPages` mapping.
- [x] [RFC-31: Student Profile](../../../../../../../../rfc/31-student-profile.md)
      — Added `StudentRoutes.kt`: the owner-resolved student profile resource
      (`POST /api/v1/students`, `GET`/`PATCH`/`DELETE /api/v1/students/me`) with
      `201`/`200`/`204` success and `400`/`404`/`409`/`401` errors; `DELETE`
      clears the session cookie. Renamed `RegisterOutcome` → `RegisterResult` in
      the register handler.
- [x] [RFC-36: Entity Model Capability Taxonomy](../../../../../../../../rfc/36-entity-model-taxonomy.md)
      — Collapsed the per-row student version from a wrapper value class to a
      plain `Int` at the model boundary, so `StudentRoutes.kt` passes
      `request.version` directly and reads `student.version` when building the
      response. The `version: Int` JSON wire contract is unchanged.
- [x] [RFC-45: Coaching Service and Conversation REST Surface](../../../../../../../../rfc/45-coaching-service.md)
      — Added `ConvoRoutes.kt`: the coaching-conversation resource family (nine
      operations under `/api/v1/conversations`) with two SSE-streaming POST
      endpoints. Introduced the lowercase snake_case error-code convention for
      this family, the cookie → `User` → `Student` caller resolution with its
      per-operation profileless handling (`409`/`200-empty`/`404`),
      malformed-id-is-`404`, opaque role-prefixed message ids, and the
      pre-flight-errors-before-the-stream / exactly-one-terminal-frame SSE
      contract.
- [x] [RFC-52: Make the REST Surface Fuzz-Clean](../../../../../../../../rfc/52-make-rest-surface-fuzz-clean.md)
      — Wrapped `/api/v1/auth/register` in a `route("/register")` block with
      `rejectUnsupportedMethods(HttpMethod.Post)`, so a non-POST verb returns
      `405` with an `Allow: POST` header.
- [x] [RFC-65: Email Verification](../../../../../../../../rfc/65-email-verification.md)
      — Added `POST /api/v1/auth/verify-email` (no auth; body
      `VerifyEmailRequest` token; `200 VerifyEmailResponse`, or `400` with
      lowercase `invalid_token`/`token_expired`/`token_already_used`) and
      `POST /api/v1/auth/resend-verification` (cookie-authenticated, empty body,
      `204` on both `Sent` and `AlreadyVerified`, `401` when unresolved). Added
      `EmailVerificationService` to `AuthRouteHandler` and the `emailVerified`
      field to the `PublicUser` projection built by `register`/`login`/`me`.
