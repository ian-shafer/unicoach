## Executive Summary

This specification defines the architecture for stateful user sessions in
unicoach, replacing the stateless JSON Web Token (JWT) approach for
authentication. By persisting sessions in PostgreSQL, the platform natively
tracks active device forensics (IP and User Agent). It supports anonymous guest
sessions prior to authentication. The architecture mitigates DoS attacks,
database CPU starvation, and session fixation vulnerabilities.

## Detailed Design

### Securing the Session Token

- **Token Generation**: The server generates a cryptographically secure 256-bit
  random byte array using `java.security.SecureRandom`, encoded strictly as
  `Base64Url` (creating a ~43-44 character opaque string). This cryptography
  logic MUST be encapsulated in a mockable generic `SessionGenerator` class
  located natively in `ed.unicoach.util` and injected downward strictly via
  constructor DI—never written as a static singleton.
- **Routing Protection**: The interceptor enforces a strict Regex pattern
  (`^[A-Za-z0-9_-]{43,44}$`) rejecting invalid payloads with `400 Bad Request`.
- **Database Persistence**: The token is hashed using SHA-256 and stored as a
  binary `BYTEA` in the `sessions` table (reducing index width vs VARCHAR). The
  plain text is NEVER stored.

### Data Models

- **Table**: `sessions`
  - `id`: UUID (Primary Key)
  - `user_id`: UUID (Foreign Key to `users.id`, **NULLABLE**)
  - `token_hash`: BYTEA (SHA-256 hash of the transparent token) (MUST have
    `UNIQUE INDEX`)
  - `user_agent`: VARCHAR (Client device info)
  - `initial_ip`: VARCHAR (The origination point IP)
  - `metadata`: JSONB (NULLABLE. Flexible schema, capped at `2KB`. Contains
    `active_ips` array tracking VPN jumps).
  - `created_at`: TIMESTAMP
  - `expires_at`: TIMESTAMP (Default to `created_at + 7 days`) (MUST have
    `INDEX`)
  - `is_revoked`: BOOLEAN (Default false)
- **API Models**:
  - `Session`: Represents a session (id, created_at, user_id, metadata,
    user_agent, initial_ip) for UI enumeration.

### Domain Orchestration

- **Anonymous Sessions & Fixation Defense**: If `user_id` is null, the session
  is anonymous. Upon registration, the application MUST delete the anonymous row
  and mint an entirely **new** opaque token for the authenticated state to
  prevent Session Fixation.
- **Active Sliding Expiry Window**: To prevent abrupt disconnection on Day 7, if
  a valid token hits the server and its `expires_at` is less than 2 Days away,
  the application synchronously updates the database row
  (`expires_at = NOW() + 7 days`) inline during the HTTP request lifecycle.
- **Synchronous Zombie Purging**: An infrastructure-scheduler-friendly (e.g.
  Cron) synchronous `execute()` function located in `SessionCleanupJob`
  explicitly manages zombie expirations
  (`DELETE FROM sessions WHERE expires_at < NOW()`). It structurally logs bounds
  to stderr and operates entirely decoupled from brittle self-scheduling
  application coroutines.
- **DAO Search Identifiers**: Search results (e.g., `NotFound`) are strictly
  modeled as Data Classes retaining their specific query identity bounds
  (`val message: String`) organically exposing deterministically trackable
  states.
- **DRY DAO Execution**: Building on core defensive coding philosophies,
  `SessionsDao.kt` MUST abstract repetitive JDBC `try/catch/use` closure
  definitions into a foundational private generic `executeSafely` function,
  strictly averting procedural boilerplate propagation across internal methods.
- **`ByteArray` Mapping Gotcha**: JVM arrays resolve equality by reference by
  default. Internal repository implementations explicitly invoke
  `.contentEquals()` avoiding broken token-matching heuristics inherently.
- **Configuration Parsing**: `SessionConfig.kt` structurally maps HOCON
  representations natively into `java.time.Duration` natively (e.g., parsing
  `expiration = 7d` into typesafe boundaries over arbitrary raw `Long`
  integers).

### API Contract updates

- `POST /api/v1/auth/register` (Modified): The JSON `RegisterResponse` is
  entirely stripped of the `token` parameter natively. The opaque session token
  itself is transmitted strictly securely natively via a `Set-Cookie` header
  mapping explicitly enforced `HttpOnly`, `Secure`, and `SameSite=Strict`
  domains dynamically. Any `login` endpoint artifacts are fully excised from
  scope.

## Tests

### Unit & Service

- `SessionsDaoTest`: Verify insertion, nullable anonymous states, retrieval,
  `BYTEA` bindings, expiry extension updates, and implicit 2KB `metadata` caps.
- `AuthServiceTest`: Verify native reminting during anonymous registration.
- `SessionCleanupTest`: Verify background deletion coroutines.

### Integration

- `SessionRoutingTest`:
  - Verify Base64Url Regex rejects invalid prefixes.

## Implementation Plan

1. **Flyway Migration (`service`)**: Add new schema initialization script for
   the `sessions` table (`BYTEA`, `user_agent`, `initial_ip`, `metadata`).
   Verify via `./bin/test ed.unicoach.db.dao.SessionsDaoTest`.
2. **Database DAO (`service`)**: Create `SessionsDao.kt` handling native binary
   hashing mappings, byte limits, and `extendExpiry(id)`. Verify via
   `./bin/test ed.unicoach.db.dao.SessionsDaoTest`.
3. **Auth Service Orchestration (`service`)**: Manage background zombie purges
   via synchronous external jobs. Verify via
   `./bin/test ed.unicoach.auth.SessionCleanupTest`.
4. **Ktor Auth Pipeline (`rest-server`)**: Implement the strict Base64 Regex
   pattern natively. Verify via `./bin/test ed.unicoach.rest.AuthRoutingTest`.
5. **API Specifications**: Update `openapi.yaml`.

## Files Modified

- `db/schema/0002.create-sessions.sql` [NEW]
- `service/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt` [NEW]
- `service/src/test/kotlin/ed/unicoach/db/dao/SessionsDaoTest.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt` [MODIFY]
- `service/src/main/kotlin/ed/unicoach/auth/SessionCleanupJob.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/util/SessionGenerator.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/db/models/Session.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/auth/SessionConfig.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/JwtConfig.kt` [DELETE]
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt` [MODIFY]
- `rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt` [MODIFY]
- `api-specs/openapi.yaml` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt` [MODIFY]
- `rest-server/src/main/resources/rest-server.conf` [MODIFY]
- `rest-server/src/test/kotlin/ed/unicoach/rest/auth/SessionConfigTest.kt` [NEW]
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/JwtConfigTest.kt`
  [DELETE]
- `service/src/main/kotlin/ed/unicoach/auth/AuthResult.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/auth/SessionCleanupTest.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/RegisterResponse.kt`
  [MODIFY]
