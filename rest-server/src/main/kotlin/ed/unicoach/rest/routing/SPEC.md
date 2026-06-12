# SPEC.md — `rest-server/src/main/kotlin/ed/unicoach/rest/routing`

## I. Overview

The HTTP routing layer of the unicoach platform. This directory declares all
`/api/v1/auth/*` and `/api/v1/students/*` route handlers:

- [`AuthRoutes.kt`](./AuthRoutes.kt) — authentication flows. Owns the full
  session cookie lifecycle: minting on registration and login, reading on
  identity resolution, and clearing on logout. Delegates all business decisions
  to `AuthService`.
- [`StudentRoutes.kt`](./StudentRoutes.kt) — the owner-resolved student profile
  resource. Every handler resolves the current `User` from the session cookie
  via `AuthService`, then delegates to `StudentService`. There is **no path
  identifier** — the profile is always the caller's own.

Routes contain no domain logic. They map cookies and request bodies onto service
calls and map service outcomes onto HTTP responses.

---

## II. Invariants

### Route Structure

- Auth routes MUST be nested under the `/api/v1/auth` route block, and student
  routes under `/api/v1/students`, each registered by its handler's
  `registerRoutes(route)`.
- Any route block that permits a fixed set of methods (`/login`, `/auth/me`,
  `/logout`, `/students` (`POST` only), `/students/me`) MUST call
  `rejectUnsupportedMethods(...)` listing exactly its allowed methods, so all
  other methods return `405 Method Not Allowed` with an `Allow` header.
- `POST /api/v1/auth/register` uses `post("/register")` and MUST NOT call
  `rejectUnsupportedMethods` — Ktor handles method rejection natively for leaf
  `post()` handlers.
- The student resource is **owner-resolved**: routes MUST NOT expose a path id.
  `POST` targets the collection (`/api/v1/students`); `GET`/`PATCH`/`DELETE`
  target the singleton `/api/v1/students/me`.

### Token Handling

- For **identity resolution** (`auth/me`, `logout`, and every student handler),
  the raw cookie value MUST be hashed via `TokenHash.fromRawToken(token)`
  (SHA-256) before being passed to any service call. The plain token is NEVER
  used as a lookup key.
- For **session hand-off** (`register`, `login`), the raw `oldCookieToken` is
  forwarded verbatim to the service, which owns the remint decision. This is the
  only path on which an unhashed token leaves the routing layer.
- Raw tokens MUST NOT be validated against a format/regex at the routing layer —
  any value is hashed and looked up, which matches 0 rows for invalid tokens.
  This prevents distinguishing "wrong format" from "not found."

### Session Cookie

- The session cookie MUST be set with: `HttpOnly = true`, `SameSite = Strict`,
  `path = "/"`, `secure = sessionConfig.cookieSecure`, and
  `domain = sessionConfig.cookieDomain`.
- Cookie-clearing (on logout, and on a successful `DELETE /api/v1/students/me`)
  MUST use `maxAge = 0L` with domain, path, secure, HttpOnly, and SameSite
  attributes **identical** to those used when setting the cookie. A mismatch in
  any attribute leaves the original cookie intact in the browser.
- The cookie MUST be cleared only on a **definitive** account/session
  termination: a `204` logout, or a `DeleteStudentResult.Success`. It MUST NOT
  be cleared on a `DELETE` that returns `404 STUDENT_NOT_FOUND`, on an
  unauthenticated request, or on any thrown service exception — the underlying
  session may still be valid, and clearing would strand the client.

### Error Mapping

- Route logic MUST call `getOrThrow()` on `Result<T>` types returned by the
  service layer.
- `Exception` instances are propagated outwards and handled globally by the Ktor
  `StatusPages` plugin.
- Known domain outcomes (e.g. `RegisterResult.ValidationFailure`,
  `RegisterResult.DuplicateEmail`, `CreateStudentResult.AlreadyExists`,
  `UpdateStudentResult.VersionConflict`) MUST be handled explicitly in
  exhaustive `when` branches over the sealed result type.

### Session Hand-off (Register / Login)

- `register` and `login` MUST forward the raw incoming session cookie (if any)
  to the service as `oldCookieToken`, so the service can remint an existing
  anonymous session rather than orphan it. The routing layer does NOT decide
  whether to remint or create — it only hands the old token across.
- The cookie set on a successful `register`/`login` MUST be the opaque token
  returned by the service outcome (`outcome.token`), never a value minted in the
  routing layer.

### Student Owner Resolution

- Every student handler MUST resolve the current `User` from the session cookie
  before touching `StudentService`. Resolution hashes the raw cookie token via
  `TokenHash.fromRawToken()` and calls `AuthService.getCurrentUser(tokenHash)`.
- A missing cookie OR a `null` user (unknown/expired/revoked session) MUST
  short-circuit to `401 UNAUTHORIZED` (`ErrorResponse(code = "UNAUTHORIZED")`)
  **before** any `StudentService` call. `401` is the ONLY status that signals
  "not authenticated"; it MUST NOT be conflated with `404`.
- The resolved `User.id` is the sole owner key passed to `StudentService`. No
  student id is ever read from the request path or body — the resource the
  caller can read, modify, or delete is always its own.
- "Authenticated but no profile" is a distinct, expected outcome: `GET`/`PATCH`/
  `DELETE` on `/me` MUST return `404 STUDENT_NOT_FOUND`, never `401`.
- `DELETE /api/v1/students/me` MUST resolve the user and forward **both** the
  `User.id` and the session `tokenHash` to
  `StudentService.deleteStudentAndAccount()`, so the account/session teardown is
  owner-scoped to the presented session.

---

## III. Behavioral Contracts

### `AuthRouteHandler.registerRoutes(route: Route)` — [`AuthRoutes.kt`](./AuthRoutes.kt)

- **Behavior**: Registers all `/api/v1/auth/*` route handlers onto the Ktor
  routing tree.
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
    the service; the routing layer performs none of it directly.
  - On `Success`, writes a `Set-Cookie` header carrying the opaque token
    returned by the service (`outcome.token`).
- **Response mapping**:

  | Condition                            | Status            | Body                                                            |
  | ------------------------------------ | ----------------- | --------------------------------------------------------------- |
  | `RegisterResult.Success`             | `201 Created`     | `RegisterResponse { user: PublicUser }`                         |
  | `RegisterResult.ValidationFailure`   | `400 Bad Request` | `ErrorResponse(code="validation_failed", fieldErrors=[...])`    |
  | `RegisterResult.DuplicateEmail`      | `409 Conflict`    | `ErrorResponse(code="conflict", fieldErrors=[{field="email"}])` |
  | Exceptions thrown by `.getOrThrow()` | (propagated)      | Mapped by `StatusPages`                                         |

- **Idempotency**: Not idempotent — duplicate email returns `409`.

---

### `POST /api/v1/auth/login` — [`AuthRoutes.kt`](./AuthRoutes.kt)

- **Request**: JSON body `{"email": string, "password": string}`. Deserialized
  as `LoginRequest`.
- **Side effects**:
  - Calls `AuthService.login()` — verifies credentials and manages session
    state.
  - Writes `Set-Cookie` header with the new opaque raw token upon success.
- **Response mapping**:

  | Condition                            | Status             | Body                                 |
  | ------------------------------------ | ------------------ | ------------------------------------ |
  | `LoginResult.Success`                | `200 OK`           | `LoginResponse { user: PublicUser }` |
  | All other `LoginResult` variants     | `401 Unauthorized` | `ErrorResponse(code="unauthorized")` |
  | Exceptions thrown by `.getOrThrow()` | (propagated)       | Mapped by `StatusPages`              |

- **Method restriction**: Non-POST methods → `405 Method Not Allowed` (via
  `rejectUnsupportedMethods(HttpMethod.Post)`).
- **Idempotency**: Not idempotent — creates a new session.

---

### `GET /api/v1/auth/me` — [`AuthRoutes.kt`](./AuthRoutes.kt)

- **Request**: No body. Session identity derived from the request cookie named
  `sessionConfig.cookieName`.
- **Side effects**: Calls `AuthService.getCurrentUser()` — DB read only. No
  writes.
- **Response mapping**:

  | Condition                            | Status             | Body                                 |
  | ------------------------------------ | ------------------ | ------------------------------------ |
  | Cookie absent                        | `401 Unauthorized` | `ErrorResponse(code="unauthorized")` |
  | User successfully fetched            | `200 OK`           | `MeResponse { user: PublicUser }`    |
  | User returned `null`                 | `401 Unauthorized` | `ErrorResponse(code="unauthorized")` |
  | Exceptions thrown by `.getOrThrow()` | (propagated)       | Mapped by `StatusPages`              |

- **Method restriction**: Non-GET methods → `405 Method Not Allowed` (via
  `rejectUnsupportedMethods(HttpMethod.Get)`).
- **Idempotency**: Yes — read-only, no mutations.

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

- **Method restriction**: Non-POST methods → `405 Method Not Allowed` (via
  `rejectUnsupportedMethods(HttpMethod.Post)`).
- **Idempotency**: Yes — logout of an already-revoked, expired, or missing
  session returns `204`.

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

- **Method restriction**: Non-POST methods → `405 Method Not Allowed` (via
  `rejectUnsupportedMethods(HttpMethod.Post)`).
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
  `405 Method Not Allowed`.
- **Idempotency**: Yes — read-only, no mutations.

---

### `PATCH /api/v1/students/me` — [`StudentRoutes.kt`](./StudentRoutes.kt)

- **Request**: JSON body
  `{"expectedHighSchoolGraduationDate": string, "version": int}`. Deserialized
  as `UpdateStudentRequest`. Owner resolved from the session cookie; `version`
  carries the caller's expected OCC version.
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
  delete, the account is gone and a subsequent request is unauthenticated.

---

## IV. Infrastructure & Environment

This directory contains no module-specific infrastructure requirements beyond
those inherited from the parent `rest` module. Relevant configuration:

| HOCON key              | Source             | Used by                                                                                    |
| ---------------------- | ------------------ | ------------------------------------------------------------------------------------------ |
| `session.cookieName`   | `rest-server.conf` | Cookie read/write in all routes                                                            |
| `session.cookieDomain` | `rest-server.conf` | Cookie `Domain` attribute                                                                  |
| `session.cookieSecure` | `rest-server.conf` | Cookie `Secure` flag                                                                       |
| `session.expiration`   | `rest-server.conf` | Session TTL; forwarded to `AuthService.register()`/`login()` as `sessionExpirationSeconds` |

All four values are parsed by `SessionConfig.from(config)` in the parent `auth/`
package and injected into both route handlers as `sessionConfig`.

### Injected Dependencies

- **`AuthService`** (`service` module): Provides `register()`, `login()`,
  `getCurrentUser()`, and `logout()`. Used by both handlers —
  `StudentRouteHandler` uses it solely for owner resolution.
- **`StudentService`** (`service` module): Provides `createStudent()`,
  `getStudentForUser()`, `updateStudent()`, and `deleteStudentAndAccount()`.
- **`SessionConfig`** (`rest/auth/` package): Cookie parameters.

---

## V. History

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
      request-size limit and the `413` `StatusPages` mapping. Routing no longer
      reads any payload bound.
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
      response. The `version: Int` JSON wire contract
      (`UpdateStudentRequest`/`StudentResponse`) is unchanged.
