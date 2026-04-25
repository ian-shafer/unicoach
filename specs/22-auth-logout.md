# Auth Logout Endpoint

## Executive Summary

This specification defines a `POST /api/v1/auth/logout` REST endpoint that
revokes the current session and clears the session cookie. The endpoint extracts
the opaque session token from the cookie header, hashes it via SHA-256, and
performs a blind `UPDATE` on the `sessions` table setting `is_revoked = true`.
The endpoint is fully idempotent: it returns `204 No Content` regardless of
whether a session was found, already revoked, expired, or the cookie was missing.
The cookie is always cleared on the response. The only non-204 response is
`500 Internal Server Error` on database failure. No queue is required — the
revocation is a single synchronous write. The existing `expireZombieSessions()`
cleanup job already garbage-collects revoked rows.

## Detailed Design

### DAO Method

A new `revokeByTokenHash` method MUST be added to `SessionsDao`. It performs a
blind `UPDATE` — no preceding `SELECT` is required. The method sets
`is_revoked = true` and increments `version` by 1. The `WHERE` clause matches
on `token_hash = ?` AND `is_revoked = false`. It does NOT filter by
`expires_at > NOW()` — revoking an already-expired session is harmless and
avoids an unnecessary predicate.

Declaration:

- `SessionsDao.revokeByTokenHash(session: SqlSession, tokenHash: TokenHash): SessionUpdateResult`

The SQL sets `version = version + 1` inline, which satisfies the existing
`enforce_versioning` trigger (`NEW.version = OLD.version + 1`). The statement
uses `RETURNING *` to populate `SessionUpdateResult.Success` with the updated
`Session`. If 0 rows are affected (token not found or already revoked), the
method returns `SessionUpdateResult.NotFound("Session not found or already revoked")`.

The existing `SessionUpdateResult` sealed interface (Success, NotFound,
DatabaseFailure) is reused without modification.

### Domain Orchestration

A new `suspend fun logout(tokenHash: TokenHash): LogoutResult` method MUST be
added to `AuthService`. The method wraps the database interaction in
`withContext(Dispatchers.IO)` consistent with the existing `register()` and
`getCurrentUser()` methods. It encapsulates the revocation pipeline:

1. Dispatch to `Dispatchers.IO` via `withContext`.
2. Open a database connection via `database.withConnection`.
3. Call `SessionsDao.revokeByTokenHash(session, tokenHash)`.
4. If `SessionUpdateResult.Success`, return `LogoutResult.Success`.
5. If `SessionUpdateResult.NotFound`, return `LogoutResult.Success`
   (idempotent — the session is already gone or revoked).
6. If `SessionUpdateResult.DatabaseFailure`, return
   `LogoutResult.DatabaseFailure`.

### Data Models

#### `LogoutResult` (New — `service` module)

A sealed interface residing in its own file at
`service/src/main/kotlin/ed/unicoach/auth/LogoutResult.kt`, following the
established pattern of `AuthResult.kt` and `MeResult.kt`.

- `LogoutResult.Success` — data object. The session was revoked, or was already
  revoked/expired/missing. The client is logged out.
- `LogoutResult.DatabaseFailure(val error: ExceptionWrapper)` — data class. A
  transient database error prevented the revocation.

No `Unauthenticated` variant exists. All non-database-error outcomes are
`Success` because logout is idempotent.

### API Contract (`POST /api/v1/auth/logout`)

The `/logout` route is nested as a sub-route within the existing `/api/v1/auth`
route block in `AuthRoutes.kt`. No changes to `Routing.kt` are required.

The route block structure:

- `route("/api/v1/auth")` (existing)
  - `post("/register") { ... }` (existing)
  - `route("/me") { ... }` (existing)
  - `route("/logout")`:
    - `post { ... }`
    - `rejectUnsupportedMethods(HttpMethod.Post)`

#### Request

No request body. The session identity is derived entirely from the cookie.

#### Cookie Extraction

The route handler reads `call.request.cookies[sessionConfig.cookieName]`. If the
cookie is absent, the handler skips the service call entirely, clears the cookie,
and responds `204`.

#### Token Hashing

If a cookie value is present, it is hashed via `TokenHash.fromRawToken(token)`.
The raw cookie value is NOT validated against the Base64Url regex — consistent
with the `/me` endpoint's security decision (spec 13). Any value is hashed and
passed to the DAO, which will match 0 rows for invalid tokens.

#### Delegation

Pass the `TokenHash` to `AuthService.logout()`.

#### Response Mapping

- `LogoutResult.Success` → `204 No Content` with cookie cleared.
- `LogoutResult.DatabaseFailure` → `500 Internal Server Error` with
  `ErrorResponse("internal_error", "An internal error occurred")`. The cookie
  is NOT cleared on failure — the session might still be valid, and clearing the
  cookie would strand the client without a way to retry.

#### Cookie Clearing

On `204`, the response MUST include a `Set-Cookie` header that instructs the
browser to delete the session cookie. The cookie MUST be appended with:

- `name`: `sessionConfig.cookieName`
- `value`: empty string
- `domain`: `sessionConfig.cookieDomain`
- `path`: `/`
- `secure`: `sessionConfig.cookieSecure`
- `httpOnly`: `true`
- `maxAge`: `0` (instructs the browser to delete immediately)
- `SameSite`: `Strict`

All attributes MUST exactly match the attributes used when setting the cookie in
the `/register` route. A mismatch in `domain`, `path`, or `secure` will cause
browsers to treat the clear directive as a different cookie and leave the
original intact.

#### Method Rejection

Non-POST methods on `/logout` MUST return `405 Method Not Allowed` via the
existing `rejectUnsupportedMethods(HttpMethod.Post)` utility.

### Error Handling and Edge Cases

- **Missing cookie**: `204`. No database call issued. Cookie cleared.
- **Invalid/malformed cookie**: Hashed via SHA-256, passed to DAO, matches 0
  rows. `204`. Cookie cleared.
- **Valid session**: Revoked. `204`. Cookie cleared.
- **Already-revoked session**: DAO WHERE clause (`is_revoked = false`) matches 0
  rows. `204`. Cookie cleared.
- **Expired session**: DAO does not filter by `expires_at`. If the row exists
  and `is_revoked = false`, it gets revoked. If already cleaned up by
  `expireZombieSessions()`, 0 rows match. Either way, `204`.
- **Anonymous session (user_id = null)**: Revoked the same as an authenticated
  session. The DAO does not filter by `user_id`. `204`.
- **Concurrent logout**: Two requests revoke the same session simultaneously.
  One succeeds (returns `SessionUpdateResult.Success`), the other matches 0 rows
  (returns `SessionUpdateResult.NotFound`). Both map to `LogoutResult.Success`.
  Both respond `204`.
- **Database failure**: `500`. Cookie NOT cleared.

### SessionExpiryPlugin Interaction

The `SessionExpiryPlugin` fires on `ResponseSent` for every 2xx response that
carries a session cookie in the request. The logout endpoint responds `204` — a
2xx status — and the original cookie is still present in `call.request.cookies`.
Without mitigation, the plugin would enqueue a wasted
`SESSION_EXTEND_EXPIRY` job for the token that was just revoked. The job is
benign (`findByTokenHash` filters `is_revoked = false`, so the handler returns
`JobResult.Success` with no mutation), but the enqueue is unnecessary work.

To eliminate the wasted enqueue and make the intent explicit,
`/api/v1/auth/logout` MUST be added to the `sessionExpiry.ignorePathPrefixes`
list in `rest-server.conf`.

### Dependencies

No new dependencies. All required infrastructure (`SessionsDao`, `AuthService`,
`Database`, `SessionConfig`, `TokenHash`, `ErrorResponse`,
`SessionUpdateResult`, `ExceptionWrapper`) already exists.

## Tests

### DAO Tests (`SessionsDaoTest.kt` — Modified)

New `revokeByTokenHash` tests require a real PostgreSQL database. Tests MUST use
the existing test setup pattern in `SessionsDaoTest.kt`.

- **`revokeByTokenHash revokes active session`**: Create a session with
  `is_revoked = false`. Call `revokeByTokenHash(session, tokenHash)`. Assert
  `SessionUpdateResult.Success`. Assert the returned session has
  `version == 2`. Verify the session is no longer returned by
  `findByTokenHash()` (which filters `is_revoked = false`).

- **`revokeByTokenHash returns NotFound for nonexistent token`**: Call
  `revokeByTokenHash(session, unknownTokenHash)`. Assert
  `SessionUpdateResult.NotFound`.

- **`revokeByTokenHash returns NotFound for already-revoked session`**: Create
  a session. Revoke it. Call `revokeByTokenHash` again with the same token hash.
  Assert `SessionUpdateResult.NotFound`.

### Service Tests (`AuthServiceTest.kt` — Modified)

New `logout` tests require a real PostgreSQL database with seeded sessions. Tests
MUST use the existing `AppConfig.load()` + `DatabaseConfig.from()` pattern and
the `TRUNCATE TABLE sessions, users CASCADE` in `@BeforeEach`.

- **`logout revokes active session`**: Create a user, create a session with that
  user's ID and a known token hash. Call `authService.logout(tokenHash)`. Assert
  `LogoutResult.Success`. Verify the session is no longer returned by
  `SessionsDao.findByTokenHash()`.

- **`logout returns Success for nonexistent token`**: Call
  `authService.logout(unknownTokenHash)`. Assert `LogoutResult.Success`.

- **`logout returns Success for already-revoked session`**: Create a session,
  revoke it via `SessionsDao.revokeByTokenHash()`, then call
  `authService.logout(tokenHash)`. Assert `LogoutResult.Success`.

### Integration Tests (`AuthRoutingTest.kt` — Modified)

- **`logout with valid cookie returns 204 and clears cookie`**: Register a user
  via `POST /api/v1/auth/register`, extract the `Set-Cookie` header. Issue
  `POST /api/v1/auth/logout` with that cookie. Assert `204 No Content`. Assert
  the response contains a `Set-Cookie` header with `Max-Age=0` for the session
  cookie. Issue `GET /api/v1/auth/me` with the original cookie and assert
  `401 Unauthorized` (session is now revoked).

- **`logout without cookie returns 204`**: Issue
  `POST /api/v1/auth/logout` with no cookie header. Assert `204 No Content`.

- **`logout with invalid cookie returns 204`**: Issue
  `POST /api/v1/auth/logout` with a garbage cookie value. Assert
  `204 No Content`.

- **`GET to logout returns 405`**: Issue `GET /api/v1/auth/logout`. Assert
  `405 Method Not Allowed`.

## Implementation Plan

1. **Add `SessionsDao.revokeByTokenHash()` method**: Add the blind UPDATE method
   to `SessionsDao.kt`. The method sets `is_revoked = true` and
   `version = version + 1` where `token_hash = ?` and `is_revoked = false`. Uses
   `RETURNING *` and maps via `mapSession()`. Returns
   `SessionUpdateResult.Success`, `NotFound`, or `DatabaseFailure`.
   - _Verify_: `./gradlew :db:classes` compiles.

2. **Add `revokeByTokenHash` DAO tests**: Add the 3 DAO-level test cases to
   `SessionsDaoTest.kt`.
   - _Verify_: `./bin/test ed.unicoach.db.dao.SessionsDaoTest`.

3. **Add `LogoutResult` sealed interface**: Create
   `service/src/main/kotlin/ed/unicoach/auth/LogoutResult.kt` with `Success`
   and `DatabaseFailure` variants.
   - _Verify_: `./gradlew :service:classes` compiles.

4. **Add `AuthService.logout()` method**: Implement the revocation pipeline that
   delegates to `SessionsDao.revokeByTokenHash()` and maps `NotFound` to
   `LogoutResult.Success`.
   - _Verify_: `./gradlew :service:classes` compiles.

5. **Add `AuthService.logout` tests**: Add the 3 service-layer test cases to
   `AuthServiceTest.kt`.
   - _Verify_: `./bin/test ed.unicoach.auth.AuthServiceTest`.

6. **Add `POST /logout` route**: Add the route handler to `AuthRoutes.kt` within
   the existing `/api/v1/auth` route block. Apply
   `rejectUnsupportedMethods(HttpMethod.Post)` to the `/logout` sub-route. Clear
   the cookie on 204 responses using `Max-Age=0` with matching domain, path,
   secure, HttpOnly, and SameSite attributes.
   - _Verify_: `./gradlew :rest-server:classes` compiles.

7. **Add integration tests**: Add the 4 integration test cases to
   `AuthRoutingTest.kt`.
   - _Verify_: `./bin/test ed.unicoach.rest.AuthRoutingTest`.

8. **Add `/api/v1/auth/logout` to `ignorePathPrefixes`**: Append the path to
   the `sessionExpiry.ignorePathPrefixes` list in `rest-server.conf` so the
   `SessionExpiryPlugin` does not enqueue a wasted expiry job for a
   just-revoked session.
   - _Verify_: `grep logout rest-server/src/main/resources/rest-server.conf`.

9. **Update OpenAPI spec**: Add `POST /api/v1/auth/logout` path with `204` and
   `500` response schemas.
   - _Verify_: Manual review of `openapi.yaml`.

## Files Modified

- `db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt` [MODIFY]
- `db/src/test/kotlin/ed/unicoach/db/dao/SessionsDaoTest.kt` [MODIFY]
- `service/src/main/kotlin/ed/unicoach/auth/LogoutResult.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt` [MODIFY]
- `rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt` [MODIFY]
- `rest-server/src/main/resources/rest-server.conf` [MODIFY]
- `api-specs/openapi.yaml` [MODIFY]
