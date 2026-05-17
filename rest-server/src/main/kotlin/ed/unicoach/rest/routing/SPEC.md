# SPEC.md — `rest-server/src/main/kotlin/ed/unicoach/rest/routing`

## I. Overview

The HTTP routing layer of the unicoach platform. This directory contains a
single file — [`AuthRoutes.kt`](./AuthRoutes.kt) — that declares all
`/api/v1/auth/*` route handlers. It owns the
full session cookie lifecycle for authentication flows: minting on registration,
reading on identity resolution, and clearing on logout. It contains no domain
logic — all business decisions are delegated to `AuthService`.

---

## II. Invariants

### Route Structure

- All routes in this package MUST be nested under the `/api/v1/auth` route
  block defined in `authRoutes()`.
- Route blocks that permit only one method (`/me`, `/logout`) MUST call
  `rejectUnsupportedMethods(...)` to return `405 Method Not Allowed` with an
  `Allow` header for all other methods.
- `POST /api/v1/auth/register` uses `post("...")` and MUST NOT call
  `rejectUnsupportedMethods` — Ktor handles method rejection natively for
  leaf `post()` handlers.

### Payload Guard

- `POST /api/v1/auth/register` MUST check `Content-Length` before reading the
  body. If `Content-Length > 4096`, it MUST respond `413 Payload Too Large`
  with `ErrorResponse(code = "payload_too_large", ...)` and return immediately
  without deserializing the body.

### Token Handling

- Raw cookie values MUST be hashed via `TokenHash.fromRawToken(token)` (SHA-256
  via `java.security.MessageDigest`) before being passed to any service or DAO
  call. The plain token string is NEVER forwarded to the domain layer.
- Raw tokens MUST NOT be validated against the Base64Url regex at the routing
  layer — any value is hashed and passed to the DAO, which matches 0 rows for
  invalid tokens. This prevents distinguishing "wrong format" from "not found."

### Session Cookie

- The session cookie MUST be set with: `HttpOnly = true`, `SameSite = Strict`,
  `path = "/"`, `secure = sessionConfig.cookieSecure`, and
  `domain = sessionConfig.cookieDomain`.
- Cookie-clearing on logout MUST use `maxAge = 0L` with domain, path, secure,
  HttpOnly, and SameSite attributes **identical** to those used when setting the
  cookie. A mismatch in any attribute leaves the original cookie intact in the
  browser.
- Cookie clearing MUST NOT occur on `LogoutResult.DatabaseFailure` — the
  session may still be valid and clearing would strand the client without a
  way to retry.

### Error Mapping

- Route logic MUST call `getOrThrow()` on `Result<T>` types returned by the service layer.
- `Exception` instances are propagated outwards and handled globally by the Ktor `StatusPages` plugin.
- Known domain outcomes (e.g. `RegisterOutcome.ValidationFailure`, `RegisterOutcome.DuplicateEmail`) MUST be handled explicitly in `when` branches.

### Session Reminting (Register)

- On successful registration, if a session cookie is already present in the
  request, the handler MUST attempt to remint the existing anonymous session
  (via `SessionsDao.remintToken()`) before creating a new session
  (`SessionsDao.create()`). A new session is created only when no matching
  session is found.
- DB exceptions during session write (remint or create) MUST be silently
  swallowed. The registration response (`201 Created`) is sent regardless of
  session persistence failure.
- `SessionsDao.remintToken()` and `SessionsDao.create()` are NOT idempotent.
  Concurrent calls race on OCC versioning; only one write succeeds. This is
  acceptable because session persistence failures are swallowed and the
  registration response is sent regardless.

---

## III. Behavioral Contracts

### `Route.authRoutes(authService, database, sessionConfig, tokenGenerator)` — [`AuthRoutes.kt`](./AuthRoutes.kt)

- **Behavior**: Registers all `/api/v1/auth/*` route handlers onto the Ktor
  routing tree.
- **Side effects**: Route table registration only — no I/O at call time.
- **Idempotency**: Not idempotent — calling twice installs duplicate routes.

---

### `POST /api/v1/auth/register` — [`AuthRoutes.kt`](./AuthRoutes.kt)

- **Request**: JSON body `{"email": string, "password": string, "name": string}`.
  Deserialized as `RegisterRequest`. `Content-Length` is checked before read.
- **Side effects**:
  - Calls `AuthService.register()` — writes a `users` row.
  - Calls `tokenGenerator.generateToken()` to produce a new cryptographically
    secure 256-bit Base64Url opaque token. Hashes it via
    `TokenHash.fromRawToken()` for DAO persistence; the raw token is set as
    the cookie value.
  - Calls `SessionsDao.remintToken()` (if a valid anonymous session cookie is
    present) OR `SessionsDao.create()` — writes a `sessions` row. Both calls
    are NOT idempotent; concurrent races are resolved via OCC versioning.
  - Writes `Set-Cookie` header with the new opaque raw token.
- **Response mapping**:

  | Condition | Status | Body |
  |-----------|--------|------|
  | `Content-Length > 4096` | `413 Payload Too Large` | `ErrorResponse(code="payload_too_large")` |
  | `RegisterOutcome.Success` | `201 Created` | `RegisterResponse { user: PublicUser }` |
  | `RegisterOutcome.ValidationFailure` | `400 Bad Request` | `ErrorResponse(code="validation_failed", fieldErrors=[...])` |
  | `RegisterOutcome.DuplicateEmail` | `409 Conflict` | `ErrorResponse(code="conflict", fieldErrors=[{field="email"}])` |
  | Exceptions thrown by `.getOrThrow()` | `400`, `503`, or `500` | Processed by `StatusPages` |

- **Session reminting detail**: Reads the existing cookie; hashes it via
  `TokenHash.fromRawToken()`; calls `SessionsDao.findByTokenHash()`. If
  `SessionFindResult.Success`, calls `SessionsDao.remintToken()` with the new
  `user_id` and a fresh token hash. If no session is found, calls
  `SessionsDao.create()` with a `NewSession`. Exceptions from either path are
  swallowed.
- **Idempotency**: Not idempotent — duplicate email returns `409`.

---

### `GET /api/v1/auth/me` — [`AuthRoutes.kt`](./AuthRoutes.kt)

- **Request**: No body. Session identity derived from the request cookie named
  `sessionConfig.cookieName`.
- **Side effects**: Calls `AuthService.getCurrentUser()` — DB read only. No
  writes.
- **Response mapping**:

  | Condition | Status | Body |
  |-----------|--------|------|
  | Cookie absent | `401 Unauthorized` | `ErrorResponse(code="unauthorized")` |
  | User successfully fetched | `200 OK` | `MeResponse { user: PublicUser }` |
  | User returned `null` | `401 Unauthorized` | `ErrorResponse(code="unauthorized")` |
  | Exceptions thrown by `.getOrThrow()` | `503`, `400` | Processed by `StatusPages` |

- **Method restriction**: Non-GET methods → `405 Method Not Allowed` (via
  `rejectUnsupportedMethods(HttpMethod.Get)`).
- **Idempotency**: Yes — read-only, no mutations.

---

### `POST /api/v1/auth/logout` — [`AuthRoutes.kt`](./AuthRoutes.kt)

- **Request**: No body. Session identity derived from the request cookie.
- **Side effects**: Calls `AuthService.logout()` on a cookie-present request —
  executes a blind `UPDATE sessions SET is_revoked = true` write. Always
  clears the session cookie on `204`.
- **Response mapping**:

  | Condition | Status | Cookie Cleared? | Body |
  |-----------|--------|-----------------|------|
  | Cookie absent | `204 No Content` | Yes | None |
  | Success | `204 No Content` | Yes | None |
  | Exceptions thrown by `.getOrThrow()` | `503`, `400` | **No** | Processed by `StatusPages` |

- **Method restriction**: Non-POST methods → `405 Method Not Allowed` (via
  `rejectUnsupportedMethods(HttpMethod.Post)`).
- **Idempotency**: Yes — logout of an already-revoked, expired, or missing
  session returns `204`.

---



## IV. Infrastructure & Environment

This directory contains no module-specific infrastructure requirements beyond
those inherited from the parent `rest` module. Relevant configuration:

| HOCON key | Source | Used by |
|-----------|--------|---------|
| `session.cookieName` | `rest-server.conf` | Cookie read/write in all routes |
| `session.cookieDomain` | `rest-server.conf` | Cookie `Domain` attribute |
| `session.cookieSecure` | `rest-server.conf` | Cookie `Secure` flag |
| `session.expiration` | `rest-server.conf` | Session TTL; passed to `SessionsDao.create()` and `SessionsDao.remintToken()` in `/register` |

All four values are parsed by `SessionConfig.from(config)` in the parent
`auth/` package and injected into `authRoutes(...)` as `sessionConfig`.

### Injected Dependencies

- **`AuthService`** (`service` module): Provides `register()`, `getCurrentUser()`,
  and `logout()`.
- **`Database`** (`db` module): Used directly for session read/write within the
  `/register` handler only.
- **`SessionConfig`** (`rest/auth/` package): Cookie parameters.
- **`TokenGenerator`** (`common` module): Generates cryptographically secure
  256-bit Base64Url tokens for new or reminted sessions.

---

## V. History

- [x] [RFC-08: Auth Registration](../../../../../../../../rfc/08-auth-registration.md) — Established the `/register` endpoint contract (4KB payload guard, `respondAppError` delegation, `RegisterRequest`/`RegisterResponse` shape). Originally referenced `rest-server/.../routes/AuthRoutes.kt`; those contracts migrated to the current `routing/` path via RFC-11.
- [x] [RFC-11: Sessions](../../../../../../../../rfc/11-sessions.md)
- [x] [RFC-13: Auth Me](../../../../../../../../rfc/13-auth-me.md)
- [x] [RFC-21: Session Expiry Queue](../../../../../../../../rfc/21-session-expiry-queue.md)
- [x] [RFC-22: Auth Logout](../../../../../../../../rfc/22-auth-logout.md)
