# SPEC: `rest-server/src/main/kotlin/ed/unicoach/rest/models`

## I. Overview

This package is the **HTTP boundary data-transfer layer** for the `rest-server`
module. It defines the Kotlin data classes that are serialized to and from JSON
on every REST endpoint. All types here are pure DTOs: they carry no business
logic, no domain validation, and no persistence concerns. They are the
structural contract between HTTP wire format and the routing layer.

---

## II. Invariants

- **I1**: Every type in this package MUST be a Kotlin `data class`. No
  `object`, `sealed class`, or mutable class is permitted here.
- **I2**: Types MUST NOT import or depend on any domain value class from
  `service` (e.g., `UserId`, `EmailAddress`). All fields MUST use primitive
  JVM types (`String`, `UUID`, `Boolean`, `Int`) to decouple HTTP serialization
  from domain internals.
- **I3**: `FieldError` MUST NOT reside in this package. It is owned by
  [`common/src/main/kotlin/ed/unicoach/error/FieldError.kt`](../../../../../../../../common/src/main/kotlin/ed/unicoach/error/FieldError.kt).
  `ErrorResponse` imports it from there and MUST NOT redefine it locally.
- **I4**: `RegisterResponse` MUST NOT contain a `token` field. Session tokens
  are transmitted exclusively via `Set-Cookie` headers as mandated by RFC-11.
  Any version of `RegisterResponse` carrying `val token: String` is incorrect.
- **I5**: `PublicUser.id` MUST be typed as `java.util.UUID`, not a domain value
  class wrapper (e.g., `UserId`). Jackson serializes `UUID` to a plain UUID
  string natively; wrapping it would produce a nested object `{"id":{"value":"…"}}`.
- **I6**: `LoginRequest.kt` and `LoginResponse.kt` MUST NOT exist in this
  package. The login flow was superseded by the cookie-session architecture in
  RFC-11, which removed the JWT-bearing `LoginResponse`.
- **I7**: `ErrorResponse.fieldErrors` MUST be typed as `List<FieldError>?`
  (nullable). It MUST default to `null`, not `emptyList()`, so that
  non-validation errors serialize without the key entirely.
- **I8**: `MeResponse` MUST wrap `PublicUser` as its sole field. It MUST NOT
  embed raw user fields inline.

---

## III. Behavioral Contracts

### `PublicUser` — [`PublicUser.kt`](./PublicUser.kt)

- **Purpose**: Read-only projection of a user safe to expose over the wire.
  Contains only fields the client is permitted to observe.
- **Fields**: `id: UUID`, `email: String`, `name: String`.
- **Side Effects**: None. Pure value type.
- **Error Handling**: None. Deserialization failures are handled upstream by
  `StatusPages`.
- **Idempotency**: N/A (pure DTO).
- **Shared Usage**: Embedded by both `RegisterResponse` and `MeResponse`.
  Changes to this type affect all endpoints that return user data.

### `RegisterRequest` — [`RegisterRequest.kt`](./RegisterRequest.kt)

- **Purpose**: Deserialized from the `POST /api/v1/auth/register` request body.
- **Fields**: `email: String`, `password: String`, `name: String`.
- **Side Effects**: None at this layer. Validation and normalization occur in
  `AuthService`.
- **Error Handling**: Jackson deserialization failures (missing fields, wrong
  types) propagate to `StatusPages` which maps them to `400 Bad Request` with
  an `ErrorResponse`.
- **Idempotency**: N/A (inbound DTO only).
- **Logging constraint**: Logging pipelines MUST mask or redact `password` to
  prevent plaintext secrets appearing in traces.

### `RegisterResponse` — [`RegisterResponse.kt`](./RegisterResponse.kt)

- **Purpose**: Serialized as the `201 Created` response body for a successful
  registration. Session token is NOT included; it is transmitted via
  `Set-Cookie`.
- **Fields**: `user: PublicUser`.
- **Side Effects**: None. Pure outbound DTO.
- **Error Handling**: N/A.
- **Idempotency**: N/A (outbound DTO only).

### `MeResponse` — [`MeResponse.kt`](./MeResponse.kt)

- **Purpose**: Serialized as the `200 OK` response body for `GET /api/v1/auth/me`.
- **Fields**: `user: PublicUser`.
- **Side Effects**: None. Pure outbound DTO.
- **Error Handling**: N/A.
- **Idempotency**: N/A (outbound DTO only).

### `ErrorResponse` — [`ErrorResponse.kt`](./ErrorResponse.kt)

- **Purpose**: Uniform error envelope returned on all failure responses across
  all REST routes. Ensures structural compatibility across endpoints.
- **Fields**: `code: String`, `message: String`, `fieldErrors: List<[FieldError](../../../../../../../../common/src/main/kotlin/ed/unicoach/error/FieldError.kt)>? = null`.
- **Side Effects**: None. Pure outbound DTO.
- **Error Handling**: `fieldErrors` is only present on `400 Bad Request`
  responses from validation failures. All other error responses serialize with
  `fieldErrors` absent (null → key omitted by Jackson).
- **Idempotency**: N/A (outbound DTO only).
- **Global Constraint**: This is the **only** error envelope permitted on any
  REST response. Route handlers MUST NOT construct ad-hoc error JSON.

---

## IV. Infrastructure & Environment

- **Serialization**: All types in this package are serialized/deserialized by
  Jackson via the `ContentNegotiation` plugin installed in
  `rest-server/.../plugins/Serialization.kt`. Jackson MUST be configured with:
  - `KotlinModule` (for Kotlin data class support)
  - `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = true`
  - `DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES = true`
  These settings enforce an allowlist principle: surplus or missing JSON fields
  are hard failures, not silent ignores.
- **UUID Serialization**: `PublicUser.id` is typed as `java.util.UUID`. Jackson
  serializes `UUID` as a plain UUID string by default with `KotlinModule`
  active. No custom serializer is required.
- **No Module-Specific Environment Variables**: This package has no direct
  configuration, env var, or runtime directory requirements. All infrastructure
  is owned by the `Serialization` plugin in the enclosing `rest-server` module.

---

## V. History

- [x] [RFC-08: Auth Registration](../../../../../../../../rfc/08-auth-registration.md) — Introduced `RegisterRequest`, `PublicUser`, `RegisterResponse`, and `ErrorResponse` (with `FieldError` dependency).
- [x] [RFC-10: Auth Login](../../../../../../../../rfc/10-auth-login.md) — Proposed `LoginRequest` and `LoginResponse`; both were superseded by RFC-11 and do not exist in the current codebase.
- [x] [RFC-11: Sessions](../../../../../../../../rfc/11-sessions.md) — Superseded the JWT token flow. `RegisterResponse.token` was removed; `LoginRequest`/`LoginResponse` were excised. Cookie-based session transport became canonical.
- [x] [RFC-13: Auth Me](../../../../../../../../rfc/13-auth-me.md) — Introduced `MeResponse`.
