# Auth Me Endpoint

## Executive Summary

This specification defines a `GET /api/v1/auth/me` REST endpoint that resolves
the currently authenticated user from the session cookie. The endpoint extracts
the opaque session token from the cookie header, hashes it via SHA-256, looks up
the session in PostgreSQL via `SessionsDao`, and if the session is bound to a
`user_id`, retrieves the user via `UsersDao`. Authenticated requests return
`200 OK` with a `MeResponse` containing a `PublicUser`. Unauthenticated requests
(missing cookie, invalid/expired session, anonymous session, or soft-deleted
user) return `401 Unauthorized`. As a secondary concern, this spec corrects the
`SessionsDao.findByTokenHash()` parameter type from raw `ByteArray` to the
domain `TokenHash` class.

## Detailed Design

### DAO Signature Correction

`SessionsDao.findByTokenHash()` currently accepts a raw `ByteArray` parameter.
Per the sessions spec (spec 11), raw hashes MUST be wrapped in the strongly
typed `TokenHash(val value: ByteArray)` class to prevent domain signature drift
and broken reference-equality heuristics. The method signature MUST change from:

```kotlin
fun findByTokenHash(session: SqlSession, tokenHash: ByteArray): SessionFindResult
```

to:

```kotlin
fun findByTokenHash(session: SqlSession, tokenHash: TokenHash): SessionFindResult
```

The internal implementation extracts `tokenHash.value` for JDBC binding. All
existing callers (`AuthRoutes.kt`, `SessionsDaoTest.kt`,
`SessionCleanupTest.kt`) MUST be updated to pass `TokenHash` instances.

### Domain Orchestration

A new `suspend fun getCurrentUser(tokenHash: TokenHash): MeResult` method MUST
be added to `AuthService`. The method MUST wrap the database interaction in
`withContext(Dispatchers.IO)` to avoid Netty event loop starvation, consistent
with the existing `register()` method's pattern. It encapsulates the full read
pipeline:

1. Dispatch to `Dispatchers.IO` via `withContext`.
2. Open a database connection via `database.withConnection`.
3. Call `SessionsDao.findByTokenHash(session, tokenHash)`.
4. If `SessionFindResult.NotFound` or `SessionFindResult.DatabaseFailure`,
   return `MeResult.Unauthenticated` or `MeResult.DatabaseFailure` respectively.
5. If `SessionFindResult.Success` but `session.userId` is null (anonymous
   session), return `MeResult.Unauthenticated`.
6. Call `UsersDao.findById(session, userId)`.
7. If `FindResult.NotFound` (user soft-deleted or missing), return
   `MeResult.Unauthenticated`.
8. If `FindResult.Success`, return `MeResult.Authenticated(user)`.
9. If `FindResult.DatabaseFailure`, return `MeResult.DatabaseFailure`.

### Data Models

#### `MeResult` (New — `service` module)

```kotlin
sealed interface MeResult {
  data class Authenticated(val user: User) : MeResult
  data object Unauthenticated : MeResult
  data class DatabaseFailure(val error: ExceptionWrapper) : MeResult
}
```

This sealed interface MUST reside in its own file at
`service/src/main/kotlin/ed/unicoach/auth/MeResult.kt`, following the
established pattern of `AuthResult.kt`.

#### `MeResponse` (New — `rest-server` module)

```kotlin
data class MeResponse(val user: PublicUser)
```

This DTO wraps `PublicUser` for the `200 OK` JSON response body, consistent with
the `RegisterResponse(val user: PublicUser)` pattern.

### Token Hashing Helper

The register route in `AuthRoutes.kt` already contains inline SHA-256 hashing
logic in multiple locations. To eliminate duplication, a private helper function
MUST be extracted:

```kotlin
private fun hashToken(token: String): TokenHash {
    val hash = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
    return TokenHash(hash)
}
```

Both the existing `/register` route and the new `/me` route MUST use this
function instead of inline `MessageDigest` calls.

### API Contract (`GET /api/v1/auth/me`)

The `/me` route is nested as a sub-route within the existing `/api/v1/auth`
route block in `AuthRoutes.kt`. No changes to `Routing.kt` are required.

```kotlin
route("/api/v1/auth") {
    post("/register") { ... }
    route("/me") {
        get { ... }
        rejectUnsupportedMethods(HttpMethod.Get)
    }
}
```

- **Cookie Extraction**: The route handler reads
  `call.request.cookies[sessionConfig.cookieName]`. If the cookie is absent,
  respond `401 Unauthorized` with an `ErrorResponse`.
- **Token Hashing**: The raw cookie value is hashed via `hashToken(token)`.
- **Delegation**: Pass the `TokenHash` to `AuthService.getCurrentUser()`.
- **Response Mapping**:
  - `MeResult.Authenticated(user)` → `200 OK` with
    `MeResponse(PublicUser(id, email, name))`
  - `MeResult.Unauthenticated` → `401 Unauthorized` with
    `ErrorResponse("unauthorized", "Not authenticated")`
  - `MeResult.DatabaseFailure` → `500 Internal Server Error` with
    `ErrorResponse("internal_error", "An internal error occurred")`
- **Method Rejection**: Non-GET methods on `/me` MUST return
  `405 Method Not Allowed` via the existing
  `rejectUnsupportedMethods(HttpMethod.Get)` utility.

### Error Handling and Edge Cases

- **Missing cookie**: `401`. No database call issued.
- **Expired or revoked session**: `SessionsDao.findByTokenHash()` filters by
  `expires_at > NOW()` and `is_revoked = false`. Expired/revoked sessions return
  `SessionFindResult.NotFound`, mapped to `401`.
- **Anonymous session**: Session exists but `user_id` is null. Mapped to `401`.
- **Soft-deleted user**: `UsersDao.findById()` excludes soft-deleted users by
  default (`includeDeleted = false`). Returns `FindResult.NotFound`, mapped to
  `401`.
- **Malformed cookie**: The route does NOT validate the cookie format against
  the Base64Url regex defined in spec 11. Any cookie value is hashed via SHA-256
  (which accepts arbitrary input) and passed to `findByTokenHash()`, which will
  not match any stored session. This is a deliberate security decision:
  returning `401` for all invalid states (missing, malformed, expired,
  anonymous) prevents attackers from distinguishing "your token format is wrong"
  from "your token doesn't exist." Format validation belongs in the future
  session validation middleware.
- **Sliding expiry**: Explicitly out of scope. Session expiry extension belongs
  in a future session validation middleware that runs across all authenticated
  routes, not in a single endpoint. See Future Work section.

### Dependencies

No new dependencies. All required infrastructure (`SessionsDao`, `UsersDao`,
`Database`, `SessionConfig`, `TokenHash`, `PublicUser`, `ErrorResponse`) already
exists.

### Future Work

- **Session Validation Middleware**: A Ktor interceptor/plugin that validates
  the session cookie and resolves the authenticated user on all protected
  routes. This middleware is the correct home for cross-cutting session concerns
  including sliding expiry extension.
- **Expiry Renewal Queue**: Rather than synchronously extending `expires_at`
  during the HTTP request lifecycle, session renewals should be dispatched onto
  an asynchronous work queue to avoid write contention on the hot read path.

## Tests

### DAO Tests (`SessionsDaoTest.kt` — Modified)

- **Existing tests updated**: All calls to `SessionsDao.findByTokenHash()` MUST
  pass `TokenHash(hash)` instead of raw `ByteArray`. No behavioral changes;
  purely a type-level migration.

### Service Tests (`AuthServiceTest.kt` — Modified)

New `getCurrentUser` tests require a real PostgreSQL database with seeded
sessions and users. Tests MUST use the existing `AppConfig.load()` +
`DatabaseConfig.from()` pattern and issue
`TRUNCATE TABLE sessions, users CASCADE` in `@BeforeEach`.

- **Authenticated session returns user**: Create a user via `UsersDao.create()`,
  create a session with that `userId` and a known `tokenHash`, call
  `getCurrentUser(tokenHash)`, assert `MeResult.Authenticated` with matching
  user fields.
- **Anonymous session returns unauthenticated**: Create a session with
  `userId = null`, call `getCurrentUser(tokenHash)`, assert
  `MeResult.Unauthenticated`.
- **Invalid token returns unauthenticated**: Call `getCurrentUser()` with a
  `TokenHash` that does not match any session, assert
  `MeResult.Unauthenticated`.
- **Expired session returns unauthenticated**: Create a session with
  `expiration = Duration.ofSeconds(-1)` (already expired), call
  `getCurrentUser(tokenHash)`, assert `MeResult.Unauthenticated`.
- **Soft-deleted user returns unauthenticated**: Create a user, soft-delete it
  via `UsersDao.delete()`, create a session with that `userId`, call
  `getCurrentUser(tokenHash)`, assert `MeResult.Unauthenticated`.

### Integration Tests (`AuthRoutingTest.kt` — Modified)

- **Authenticated `/me` returns 200 with user**: Register a user via
  `POST /api/v1/auth/register`, extract the `Set-Cookie` header from the
  response, issue `GET /api/v1/auth/me` with that cookie, assert `200 OK` and
  response body contains the registered user's email and name.
- **Missing cookie returns 401**: Issue `GET /api/v1/auth/me` with no cookie
  header, assert `401 Unauthorized`.
- **Invalid cookie returns 401**: Issue `GET /api/v1/auth/me` with a garbage
  cookie value, assert `401 Unauthorized`.
- **POST to `/me` returns 405**: Issue `POST /api/v1/auth/me`, assert
  `405 Method Not Allowed`.

## Implementation Plan

1. **Fix `SessionsDao.findByTokenHash()` signature**: Change the `tokenHash`
   parameter type from `ByteArray` to `TokenHash`. Update the internal JDBC
   binding to use `tokenHash.value`.
   - _Verify_: `./gradlew :service:classes` compiles. Downstream callers will
     fail until updated in step 2.

2. **Update all `findByTokenHash` callers and extract `hashToken` helper**:
   Update `SessionsDaoTest.kt` and `SessionCleanupTest.kt` to pass
   `TokenHash(hash)` instead of raw `ByteArray`. In `AuthRoutes.kt`, extract a
   private `hashToken(token: String): TokenHash` function and refactor the
   existing inline `MessageDigest` calls in the `/register` route to use it.
   - _Verify_: `./bin/test ed.unicoach.db.dao.SessionsDaoTest` and
     `./bin/test ed.unicoach.auth.SessionCleanupTest` pass.

3. **Add `MeResult` sealed interface**: Create
   `service/src/main/kotlin/ed/unicoach/auth/MeResult.kt`.
   - _Verify_: `./gradlew :service:classes` compiles.

4. **Add `AuthService.getCurrentUser()` method**: Implement the read-only lookup
   pipeline.
   - _Verify_: `./gradlew :service:classes` compiles.

5. **Add `AuthService.getCurrentUser` tests**: Implement the 5 service-layer
   test cases in `AuthServiceTest.kt`.
   - _Verify_: `./bin/test ed.unicoach.auth.AuthServiceTest`.

6. **Add `MeResponse` REST model**: Create
   `rest-server/src/main/kotlin/ed/unicoach/rest/models/MeResponse.kt`.
   - _Verify_: `./gradlew :rest-server:classes` compiles.

7. **Add `GET /me` route**: Add the route handler to `AuthRoutes.kt` within the
   existing `/api/v1/auth` route block. Apply
   `rejectUnsupportedMethods(HttpMethod.Get)` to the `/me` sub-route.
   - _Verify_: `./gradlew :rest-server:classes` compiles.

8. **Add integration tests**: Implement the 4 integration test cases in
   `AuthRoutingTest.kt`.
   - _Verify_: `./bin/test ed.unicoach.rest.AuthRoutingTest`.

9. **Update OpenAPI spec**: Add `GET /api/v1/auth/me` path with `200`, `401`,
   and `500` response schemas. Add `MeResponse` to `components/schemas`.
   - _Verify_: Manual review of `openapi.yaml`.

## Files Modified

- `service/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt` [MODIFY]
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt` [MODIFY]
- `service/src/main/kotlin/ed/unicoach/auth/MeResult.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/MeResponse.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/db/dao/SessionsDaoTest.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/auth/SessionCleanupTest.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt` [MODIFY]
- `rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt` [MODIFY]
- `api-specs/openapi.yaml` [MODIFY]
