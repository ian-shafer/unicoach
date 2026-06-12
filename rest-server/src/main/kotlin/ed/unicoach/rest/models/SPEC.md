# SPEC: `rest-server/src/main/kotlin/ed/unicoach/rest/models`

## I. Overview

This package is the **HTTP boundary data-transfer layer** for the `rest-server`
module. It defines the Kotlin data classes that are serialized to and from JSON
on every REST endpoint. All types here are pure DTOs: they carry no business
logic, no domain validation, and no persistence concerns. They are the
structural contract between HTTP wire format and the routing layer.

---

## II. Invariants

- **I1**: Every type in this package MUST be a Kotlin `data class`. No `object`,
  `sealed class`, or mutable class is permitted here.
- **I2**: Types MUST NOT import or depend on any domain value class from
  `service` (e.g., `UserId`, `EmailAddress`, `StudentId`, `PartialDate`). All
  fields MUST use platform-neutral JVM types (`String`, `java.util.UUID`,
  `Boolean`, `Int`, `java.time.Instant`) or other DTOs in this package, to
  decouple HTTP serialization from domain internals.
- **I3**: `FieldError` MUST NOT reside in this package. It is owned by
  [`common/src/main/kotlin/ed/unicoach/error/FieldError.kt`](../../../../../../../../common/src/main/kotlin/ed/unicoach/error/FieldError.kt).
  `ErrorResponse` imports it from there and MUST NOT redefine it locally.
- **I4**: `RegisterResponse` MUST NOT contain a `token` field. Session tokens
  are transmitted exclusively via `Set-Cookie` headers as mandated by RFC-11.
  Any version of `RegisterResponse` carrying `val token: String` is incorrect.
- **I5**: `PublicUser.id` MUST be typed as `java.util.UUID`, not a domain value
  class wrapper (e.g., `UserId`). Jackson serializes `UUID` to a plain UUID
  string natively; wrapping it would produce a nested object
  `{"id":{"value":"…"}}`.
- **I6**: `LoginResponse` MUST NOT contain a `token` field. As with
  `RegisterResponse` (I4), the session token is transmitted exclusively via the
  `Set-Cookie` header per RFC-11. `LoginResponse` MUST wrap `PublicUser` as its
  sole field (`val user: PublicUser`) and MUST NOT inline raw user fields.
- **I6b**: `LoginRequest` is deserialized from the login request body and MUST
  expose exactly `email: String` and `password: String`. It currently carries a
  `kotlinx.serialization.@Serializable` annotation — a deviation from this
  package's Jackson-via-`ContentNegotiation` convention (every other DTO is a
  plain Jackson-bound `data class`). Wire (de)serialization on the live login
  route is performed by Jackson regardless; the annotation is inert for that
  path. New DTOs MUST NOT add `@Serializable`; this is the lone exception, not a
  pattern.
- **I7**: `ErrorResponse.fieldErrors` MUST be typed as `List<FieldError>?`
  (nullable). It MUST default to `null`, not `emptyList()`, so that
  non-validation errors serialize without the key entirely.
- **I8**: `MeResponse` MUST wrap `PublicUser` as its sole field. It MUST NOT
  embed raw user fields inline.
- **I9**: The variable-precision graduation date crosses this boundary as a
  `String`, never as a domain `PartialDate`. `CreateStudentRequest`,
  `UpdateStudentRequest`, and `PublicStudent` MUST all type
  `expectedHighSchoolGraduationDate` as `String`. Parsing to and rendering from
  `PartialDate` is owned by the `service`/`db` layers; this package transports
  only the wire string.
- **I10**: The graduation-date string is the **zero-padded ISO canonical form**
  matching `^\d{4}(-\d{2}(-\d{2})?)?$` — exactly `YYYY`, `YYYY-MM`, or
  `YYYY-MM-DD` at the stored precision. This package does not enforce the
  pattern; it declares it as the contract. Inbound non-conforming values are
  rejected downstream as `VALIDATION_ERROR`; outbound values are always emitted
  at this precision.
- **I11**: `PublicStudent.id` MUST be typed as `java.util.UUID` (same rationale
  as I5: native UUID-string serialization, no nested wrapper). It MUST NOT be
  typed as the domain `StudentId` value class.
- **I12**: `PublicStudent.version` MUST be a plain `Int`. It carries the OCC
  version the client echoes back in `UpdateStudentRequest.version` for
  optimistic-concurrency checks. (The domain version is itself a plain `Int`;
  this DTO transports it unwrapped.)
- **I13**: `PublicStudent` MUST expose `createdAt` and `updatedAt` as
  `java.time.Instant`. It MUST NOT expose `deletedAt` — soft-delete state is
  internal audit state and MUST NOT cross the HTTP boundary. The domain model
  carries no row-audit timestamps, so there is nothing further to leak.
- **I14**: `StudentResponse` MUST wrap `PublicStudent` as its sole field (the
  same envelope shape as `MeResponse`/`RegisterResponse`). It MUST NOT inline
  student fields.

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
  types) propagate to `StatusPages` which maps them to `400 Bad Request` with an
  `ErrorResponse`.
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

- **Purpose**: Serialized as the `200 OK` response body for
  `GET /api/v1/auth/me`.
- **Fields**: `user: PublicUser`.
- **Side Effects**: None. Pure outbound DTO.
- **Error Handling**: N/A.
- **Idempotency**: N/A (outbound DTO only).

### `LoginRequest` — [`LoginRequest.kt`](./LoginRequest.kt)

- **Purpose**: Deserialized from the login request body
  (`POST /api/v1/auth/login`).
- **Fields**: `email: String`, `password: String`.
- **Side Effects**: None at this layer. Credential verification and session
  issuance occur in `AuthService`.
- **Error Handling**: Jackson deserialization failures propagate to
  `StatusPages` as `400 Bad Request` with an `ErrorResponse`.
- **Idempotency**: N/A (inbound DTO only).
- **Logging constraint**: Logging pipelines MUST mask or redact `password`.
- **Serialization note**: Carries a `kotlinx.serialization.@Serializable`
  annotation (see I6b); the live route still binds it via Jackson.

### `LoginResponse` — [`LoginResponse.kt`](./LoginResponse.kt)

- **Purpose**: Serialized as the `200 OK` response body for a successful login.
  The session token is NOT included; it is transmitted via `Set-Cookie` (I6).
- **Fields**: `user: PublicUser`.
- **Side Effects**: None. Pure outbound DTO.
- **Error Handling**: N/A.
- **Idempotency**: N/A (outbound DTO only).

### `ErrorResponse` — [`ErrorResponse.kt`](./ErrorResponse.kt)

- **Purpose**: Uniform error envelope returned on all failure responses across
  all REST routes. Ensures structural compatibility across endpoints.
- **Fields**: `code: String`, `message: String`,
  `fieldErrors: List<[FieldError](../../../../../../../../common/src/main/kotlin/ed/unicoach/error/FieldError.kt)>? = null`.
- **Side Effects**: None. Pure outbound DTO.
- **Error Handling**: `fieldErrors` is only present on `400 Bad Request`
  responses from validation failures. All other error responses serialize with
  `fieldErrors` absent (null → key omitted by Jackson).
- **Idempotency**: N/A (outbound DTO only).
- **Global Constraint**: This is the **only** error envelope permitted on any
  REST response. Route handlers MUST NOT construct ad-hoc error JSON.
- **Student-route codes**: Student endpoints reuse this envelope with codes
  `VALIDATION_ERROR`, `STUDENT_ALREADY_EXISTS`, `STUDENT_NOT_FOUND`,
  `VERSION_CONFLICT`, and `UNAUTHORIZED`. Validation `fieldErrors` for the
  graduation date use `field = "expectedHighSchoolGraduationDate"`.

### `CreateStudentRequest` — [`CreateStudentRequest.kt`](./CreateStudentRequest.kt)

- **Purpose**: Deserialized from the `POST /api/v1/students` request body.
- **Fields**: `expectedHighSchoolGraduationDate: String` (zero-padded ISO
  variable-precision, per I9/I10).
- **Side Effects**: None at this layer. The string is parsed to a `PartialDate`
  and validated in `StudentService`.
- **Error Handling**: A malformed or impossible date surfaces downstream as a
  `400 VALIDATION_ERROR`; missing/wrong-typed JSON propagates to `StatusPages`
  as `400 Bad Request`.
- **Idempotency**: N/A (inbound DTO only).

### `UpdateStudentRequest` — [`UpdateStudentRequest.kt`](./UpdateStudentRequest.kt)

- **Purpose**: Deserialized from the `PATCH /api/v1/students/me` request body.
- **Fields**: `expectedHighSchoolGraduationDate: String` (per I9/I10),
  `version: Int` — the expected OCC version the client last observed.
- **Side Effects**: None at this layer. Validation and the
  optimistic-concurrency check occur in `StudentService`/`StudentsDao`.
- **Error Handling**: A stale `version` surfaces as `409 VERSION_CONFLICT`; a
  bad date as `400 VALIDATION_ERROR`; an absent profile as
  `404 STUDENT_NOT_FOUND`.
- **Idempotency**: N/A (inbound DTO only).

### `PublicStudent` — [`StudentResponse.kt`](./StudentResponse.kt)

- **Purpose**: Read-only projection of a student safe to expose over the wire.
  Carries only client-observable fields (per I13).
- **Fields**: `id: UUID`, `expectedHighSchoolGraduationDate: String`,
  `version: Int`, `createdAt: Instant`, `updatedAt: Instant`.
- **Side Effects**: None. Pure value type.
- **Error Handling**: None. Serialization is handled by the `Serialization`
  plugin.
- **Idempotency**: N/A (pure DTO).
- **Shared Usage**: Embedded by `StudentResponse`. Returned by all student
  read/write success responses (`POST`, `GET /me`, `PATCH /me`).

### `StudentResponse` — [`StudentResponse.kt`](./StudentResponse.kt)

- **Purpose**: Serialized as the success body for `POST /api/v1/students`
  (`201`), `GET /api/v1/students/me` (`200`), and `PATCH /api/v1/students/me`
  (`200`). `DELETE /api/v1/students/me` returns `204` with no body and does not
  use this type.
- **Fields**: `student: PublicStudent`.
- **Side Effects**: None. Pure outbound DTO.
- **Error Handling**: N/A.
- **Idempotency**: N/A (outbound DTO only).

---

## IV. Infrastructure & Environment

- **Serialization**: All types in this package are serialized/deserialized by
  Jackson via the `ContentNegotiation` plugin installed in
  `rest-server/.../plugins/Serialization.kt`. Jackson MUST be configured with:
  - `KotlinModule` (for Kotlin data class support)
  - `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = true`
  - `DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES = true` These
    settings enforce an allowlist principle: surplus or missing JSON fields are
    hard failures, not silent ignores.
- **UUID Serialization**: `PublicUser.id` and `PublicStudent.id` are typed as
  `java.util.UUID`. Jackson serializes `UUID` as a plain UUID string by default
  with `KotlinModule` active. No custom serializer is required.
- **Instant Serialization**: `PublicStudent.createdAt`/`updatedAt` are typed as
  `java.time.Instant`. The `Serialization` plugin registers `JavaTimeModule` and
  disables `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS`, so `Instant`
  serializes as an ISO-8601 string rather than a numeric epoch. DTOs in this
  package rely on that configuration and define no per-field serializer.
- **No Module-Specific Environment Variables**: This package has no direct
  configuration, env var, or runtime directory requirements. All infrastructure
  is owned by the `Serialization` plugin in the enclosing `rest-server` module.

---

## V. History

- [x] [RFC-08: Auth Registration](../../../../../../../../rfc/08-auth-registration.md)
      — Introduced `RegisterRequest`, `PublicUser`, `RegisterResponse`, and
      `ErrorResponse` (with `FieldError` dependency).
- [x] [RFC-10: Auth Login](../../../../../../../../rfc/10-auth-login.md) —
      Introduced `LoginRequest` and `LoginResponse`.
- [x] [RFC-11: Sessions](../../../../../../../../rfc/11-sessions.md) —
      Superseded the JWT token flow. Token fields were dropped from
      `RegisterResponse` and `LoginResponse`; cookie-based session transport
      became canonical. Both login DTOs remain, now reduced to their
      cookie-session shape (`LoginResponse { user: PublicUser }`).
- [x] [RFC-13: Auth Me](../../../../../../../../rfc/13-auth-me.md) — Introduced
      `MeResponse`.
- [x] [RFC-31: Student Profile](../../../../../../../../rfc/31-student-profile.md)
      — Introduced `CreateStudentRequest`, `UpdateStudentRequest`,
      `StudentResponse`, and `PublicStudent`. Established the graduation date as
      a zero-padded ISO variable-precision wire `String` (I9/I10),
      `PublicStudent.id` as `UUID` and `version` as plain `Int` (I11/I12), and
      `Instant` timestamps serialized via `JavaTimeModule`.
