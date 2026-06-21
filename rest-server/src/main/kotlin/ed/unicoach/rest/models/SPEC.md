# SPEC: `rest-server/src/main/kotlin/ed/unicoach/rest/models`

## I. Overview

This package is the **HTTP boundary data-transfer layer** for the `rest-server`
module. It defines the Kotlin `data class` DTOs serialized to and from JSON on
every REST endpoint. The types carry no business logic, no domain validation,
and no persistence concerns; they are the structural contract between the JSON
wire format and the routing layer. Fields use platform-neutral JVM types
(`String`, `java.util.UUID`, `Boolean`, `Int`, `java.time.Instant`) or other
DTOs in this package — never domain value classes from `service` (e.g. `UserId`,
`EmailAddress`, `StudentId`, `PartialDate`) — so the wire shape stays decoupled
from domain internals.

---

## II. Behavioral Contracts

### Auth DTOs

#### `PublicUser` — [`PublicUser.kt`](./PublicUser.kt)

- **Behavior**: Read-only projection of a user safe to expose over the wire —
  only fields the client is permitted to observe. Fields: `id: UUID`,
  `email: String`, `name: String`, `emailVerified: Boolean`.
- **`emailVerified` semantics**: A flat boolean reporting whether the account's
  email address has been verified. The routing layer derives it from the domain
  user's verification timestamp (true once the user has verified, false
  otherwise); this DTO transports only the resolved boolean and never the
  underlying timestamp. The field surfaces verification state uniformly on
  register, login, me, and verify-email responses.
- **`id` type**: `java.util.UUID`, not a domain wrapper. Jackson serializes
  `UUID` to a plain UUID string; a wrapper would produce a nested
  `{"id":{"value":"…"}}`.
- **Side Effects**: None. Pure value type.
- **Error Handling**: None. Deserialization failures are handled upstream.
- **Idempotency**: N/A (pure DTO).
- **Shared Usage**: Embedded by `RegisterResponse`, `LoginResponse`,
  `MeResponse`, and `VerifyEmailResponse`. A change to this type affects every
  endpoint that returns user data.

#### `RegisterRequest` — [`RegisterRequest.kt`](./RegisterRequest.kt)

- **Behavior**: Deserialized from the `POST /api/v1/auth/register` request body.
  Fields: `email: String`, `password: String`, `name: String`. Validation and
  normalization occur downstream in the auth service.
- **Side Effects**: None at this layer.
- **Error Handling**: Jackson deserialization failures (missing fields, wrong
  types) propagate to `StatusPages`, which maps them to `400 Bad Request` with
  an `ErrorResponse`.
- **Idempotency**: N/A (inbound DTO only).
- **Sensitivity**: `password` is a plaintext secret; downstream logging redacts
  it.

#### `RegisterResponse` — [`RegisterResponse.kt`](./RegisterResponse.kt)

- **Behavior**: Serialized as the `201 Created` body for a successful
  registration. Sole field `user: PublicUser`. Carries no session token — the
  token travels in a `Set-Cookie` header, not the body.
- **Side Effects**: None. Pure outbound DTO.
- **Error Handling**: N/A.
- **Idempotency**: N/A (outbound DTO only).

#### `LoginRequest` — [`LoginRequest.kt`](./LoginRequest.kt)

- **Behavior**: Deserialized from the `POST /api/v1/auth/login` request body.
  Fields: `email: String`, `password: String`. Credential verification and
  session issuance occur downstream.
- **Side Effects**: None at this layer.
- **Error Handling**: Jackson deserialization failures propagate to
  `StatusPages` as `400 Bad Request` with an `ErrorResponse`.
- **Idempotency**: N/A (inbound DTO only).
- **Sensitivity**: `password` is a plaintext secret; downstream logging redacts
  it.
- **Serialization note**: This DTO carries a
  `kotlinx.serialization.@Serializable` annotation — the lone deviation from the
  package's Jackson-via- `ContentNegotiation` convention. The live login route
  binds it via Jackson regardless, so the annotation is inert on that path.
  Every other DTO is a plain Jackson-bound `data class`.

#### `LoginResponse` — [`LoginResponse.kt`](./LoginResponse.kt)

- **Behavior**: Serialized as the `200 OK` body for a successful login. Sole
  field `user: PublicUser`. Carries no session token — the token travels in a
  `Set-Cookie` header.
- **Side Effects**: None. Pure outbound DTO.
- **Error Handling**: N/A.
- **Idempotency**: N/A (outbound DTO only).

#### `MeResponse` — [`MeResponse.kt`](./MeResponse.kt)

- **Behavior**: Serialized as the `200 OK` body for `GET /api/v1/auth/me`. Sole
  field `user: PublicUser`.
- **Side Effects**: None. Pure outbound DTO.
- **Error Handling**: N/A.
- **Idempotency**: N/A (outbound DTO only).

#### `VerifyEmailRequest` — [`VerifyEmailRequest.kt`](./VerifyEmailRequest.kt)

- **Behavior**: Deserialized from the email-verification request body. Sole
  field `token: String` — the opaque verification token the client received out
  of band (e.g. via an emailed link). The token is validated and consumed
  downstream by the email-verification service.
- **Side Effects**: None at this layer.
- **Error Handling**: Jackson deserialization failures propagate to
  `StatusPages` as `400 Bad Request` with an `ErrorResponse`. Token-level
  failures surface downstream as `400` responses carrying the lowercase auth-
  family error codes `invalid_token`, `token_expired`, and `token_already_used`.
- **Idempotency**: N/A (inbound DTO only). The underlying verification operation
  is single-use: a token that has already been consumed yields
  `token_already_used`.

#### `VerifyEmailResponse` — [`VerifyEmailResponse.kt`](./VerifyEmailResponse.kt)

- **Behavior**: Serialized as the `200 OK` body for a successful email
  verification. Sole field `user: PublicUser` — the same envelope shape as
  `MeResponse`/`LoginResponse`. On success the embedded
  `PublicUser.emailVerified` reads `true`.
- **Side Effects**: None. Pure outbound DTO.
- **Error Handling**: N/A (the error path uses `ErrorResponse`, not this type).
- **Idempotency**: N/A (outbound DTO only).

### Error envelope

#### `ErrorResponse` — [`ErrorResponse.kt`](./ErrorResponse.kt)

- **Behavior**: The uniform error envelope returned on every failure response
  across all REST routes. Fields: `code: String`, `message: String`,
  `fieldErrors: List<FieldError>? = null`. `FieldError` is imported from
  [`common/.../error/FieldError.kt`](../../../../../../../../common/src/main/kotlin/ed/unicoach/error/FieldError.kt)
  and is not redefined here.
- **`fieldErrors` shape**: Defaults to `null` (not `emptyList()`), so non-
  validation errors serialize with the key omitted entirely. `fieldErrors` is
  populated only on `400` validation responses.
- **Code families**: Auth routes (register, login, me, verify-email) use
  lowercase codes (e.g. `unauthorized`, `invalid_token`, `token_expired`,
  `token_already_used`). Student routes use uppercase codes: `VALIDATION_ERROR`,
  `STUDENT_ALREADY_EXISTS`, `STUDENT_NOT_FOUND`, `VERSION_CONFLICT`,
  `UNAUTHORIZED`. Graduation-date validation errors use
  `field = "expectedHighSchoolGraduationDate"`.
- **Side Effects**: None. Pure outbound DTO.
- **Idempotency**: N/A (outbound DTO only).
- **Reuse**: This is the single error envelope across all REST responses; it is
  also embedded by `StreamErrorEvent` rather than duplicated as a parallel error
  shape.

### Student DTOs

#### `CreateStudentRequest` — [`CreateStudentRequest.kt`](./CreateStudentRequest.kt)

- **Behavior**: Deserialized from the `POST /api/v1/students` request body. Sole
  field `expectedHighSchoolGraduationDate: String`. The string crosses the
  boundary in zero-padded ISO variable-precision canonical form
  (`^\d{4}(-\d{2}(-\d{2})?)?$` — `YYYY`, `YYYY-MM`, or `YYYY-MM-DD`); parsing to
  a domain `PartialDate` and pattern validation occur downstream.
- **Side Effects**: None at this layer.
- **Error Handling**: A malformed or impossible date surfaces downstream as a
  `400 VALIDATION_ERROR`; missing/wrong-typed JSON propagates to `StatusPages`
  as `400 Bad Request`.
- **Idempotency**: N/A (inbound DTO only).

#### `UpdateStudentRequest` — [`UpdateStudentRequest.kt`](./UpdateStudentRequest.kt)

- **Behavior**: Deserialized from the `PATCH /api/v1/students/me` request body.
  Fields: `expectedHighSchoolGraduationDate: String` (same canonical form as
  above), `version: Int` — the OCC version the client last observed, echoed back
  for the optimistic-concurrency check downstream.
- **Side Effects**: None at this layer.
- **Error Handling**: A stale `version` surfaces as `409 VERSION_CONFLICT`; a
  bad date as `400 VALIDATION_ERROR`; an absent profile as
  `404 STUDENT_NOT_FOUND`.
- **Idempotency**: N/A (inbound DTO only).

#### `PublicStudent` — [`StudentResponse.kt`](./StudentResponse.kt)

- **Behavior**: Read-only projection of a student safe to expose over the wire.
  Fields: `id: UUID`, `expectedHighSchoolGraduationDate: String`,
  `version: Int`, `createdAt: Instant`, `updatedAt: Instant`. `id` is a
  `java.util.UUID` (native UUID-string serialization, no nested wrapper);
  `version` is a plain `Int` carrying the OCC version. Soft-delete state
  (`deletedAt`) is not exposed.
- **Side Effects**: None. Pure value type.
- **Error Handling**: None.
- **Idempotency**: N/A (pure DTO).
- **Shared Usage**: Embedded by `StudentResponse`; returned by all student
  read/write success responses (`POST`, `GET /me`, `PATCH /me`).

#### `StudentResponse` — [`StudentResponse.kt`](./StudentResponse.kt)

- **Behavior**: Serialized as the success body for `POST /api/v1/students`
  (`201`), `GET /api/v1/students/me` (`200`), and `PATCH /api/v1/students/me`
  (`200`). Sole field `student: PublicStudent`. `DELETE /api/v1/students/me`
  returns `204` with no body and does not use this type.
- **Side Effects**: None. Pure outbound DTO.
- **Error Handling**: N/A.
- **Idempotency**: N/A (outbound DTO only).

### Conversation & Message DTOs

Each conversation/message DTO lives in its own file, named to mirror the
corresponding OpenAPI schema, so the generated client stays in sync with the
wire shape.

#### Wire projections

- **`Conversation`** — [`Conversation.kt`](./Conversation.kt): read-only
  projection of a coaching conversation. `id` is an opaque `String` (not
  narrowed to `UUID`); `lastActivityAt`/`archivedAt` are nullable `Instant`.
  Embedded by every conversation response envelope.
- **`Message`** — [`Message.kt`](./Message.kt): read-only projection of a single
  turn. `id` is an opaque `String`; `role` is a wire `String`, not a domain
  enum. Embedded by message and stream responses.
- **Side Effects / Idempotency**: None; pure value types.

#### Request DTOs

- **`CreateConversationRequest`** —
  [`CreateConversationRequest.kt`](./CreateConversationRequest.kt): body of
  `POST /api/v1/conversations`. `message: String`, `name: String? = null`.
- **`PostMessageRequest`** — [`PostMessageRequest.kt`](./PostMessageRequest.kt):
  body of `POST /api/v1/conversations/{id}/messages`. Sole field
  `message: String`.
- **`UpdateConversationRequest`** —
  [`UpdateConversationRequest.kt`](./UpdateConversationRequest.kt): body of
  `PATCH /api/v1/conversations/{id}`. `name: String?` and `archived: Boolean?`,
  both nullable with `null` defaults so a one-field PATCH deserializes (the
  `FAIL_ON_MISSING_CREATOR_PROPERTIES` setting does not reject an omitted
  field). The "at least one field present" rule is enforced in the route
  handler, not by this DTO.
- **Side Effects**: None at this layer.
- **Error Handling**: Missing/wrong-typed JSON propagates to `StatusPages` as
  `400 Bad Request`. A `PATCH` body with neither field present is rejected by
  the route handler.
- **Idempotency**: N/A (inbound DTOs only).

#### Response envelopes

- **`ConversationResponse`** (`conversation`), **`ConversationListResponse`**
  (`conversations: List<Conversation>`), **`CreateConversationResponse`**
  (`conversation`, `userMessage`, `coachMessage`), **`PostMessageResponse`**
  (`userMessage`, `coachMessage`), **`MessageListResponse`**
  (`messages: List<Message>`). Each is a pure outbound envelope wrapping the
  `Conversation`/`Message` projections.
- **Side Effects / Error Handling / Idempotency**: None; pure outbound DTOs.

#### SSE event DTOs — [`StreamEvent.kt`](./StreamEvent.kt)

- **Behavior**: Payloads for the streaming endpoints
  (`POST /api/v1/conversations/stream`,
  `POST /api/v1/conversations/{id}/messages/stream`), one DTO per `event:`
  frame: `ConversationCreatedEvent`, `UserMessageEvent`, `MessageDeltaEvent`,
  `MessageCompletedEvent`, `StreamErrorEvent`. Each carries a fixed `type`
  discriminator `String` with a default matching its OpenAPI mapping, and is
  serialized concretely as its own type — there is no Jackson polymorphic
  configuration (`@JsonTypeInfo`/`@JsonSubTypes`) and no sealed hierarchy.
- **Side Effects / Idempotency**: None; pure outbound DTOs. `StreamErrorEvent`
  embeds the shared [`ErrorResponse`](./ErrorResponse.kt) envelope.

---

## III. Infrastructure & Environment

- **Serialization**: All types are serialized/deserialized by Jackson via the
  `ContentNegotiation` plugin in
  [`rest-server/.../plugins/Serialization.kt`](../plugins/Serialization.kt). The
  Jackson `ObjectMapper` registers `KotlinModule` (Kotlin data-class support)
  and enables `FAIL_ON_UNKNOWN_PROPERTIES` and
  `FAIL_ON_MISSING_CREATOR_PROPERTIES`, so surplus or missing JSON fields are
  hard failures rather than silent ignores.
- **UUID Serialization**: `PublicUser.id` and `PublicStudent.id` are
  `java.util.UUID`; Jackson serializes them to plain UUID strings with
  `KotlinModule` active, requiring no custom serializer.
- **Instant Serialization**: `PublicStudent.createdAt`/`updatedAt` and the
  nullable `Conversation` timestamps are `java.time.Instant`. The
  `Serialization` plugin registers `JavaTimeModule` and disables
  `WRITE_DATES_AS_TIMESTAMPS`, so `Instant` serializes as an ISO-8601 string
  rather than a numeric epoch. DTOs in this package define no per-field
  serializer and rely on that configuration.
- **No module-specific environment**: This package has no direct configuration,
  environment variable, or runtime-directory requirements. All serialization
  infrastructure is owned by the `Serialization` plugin in the enclosing
  `rest-server` module.

---

## IV. History

- [x] [RFC-08: Auth Registration](../../../../../../../../rfc/08-auth-registration.md)
      — Introduced `RegisterRequest`, `PublicUser`, `RegisterResponse`, and
      `ErrorResponse` (with the `FieldError` dependency).
- [x] [RFC-10: Auth Login](../../../../../../../../rfc/10-auth-login.md) —
      Introduced `LoginRequest` and `LoginResponse`.
- [x] [RFC-11: Sessions](../../../../../../../../rfc/11-sessions.md) — Dropped
      the token fields from `RegisterResponse` and `LoginResponse`; cookie-based
      session transport became canonical.
- [x] [RFC-13: Auth Me](../../../../../../../../rfc/13-auth-me.md) — Introduced
      `MeResponse`.
- [x] [RFC-26: Login](../../../../../../../../rfc/26-login.md) — Re-established
      the login surface in its cookie-session shape.
- [x] [RFC-31: Student Profile](../../../../../../../../rfc/31-student-profile.md)
      — Introduced `CreateStudentRequest`, `UpdateStudentRequest`,
      `StudentResponse`, and `PublicStudent`; established the graduation date as
      a zero-padded ISO variable-precision wire `String`, `PublicStudent.id` as
      `UUID`, `version` as plain `Int`, and `Instant` timestamps via
      `JavaTimeModule`.
- [x] [RFC-45: Coaching Service and Conversation REST Surface](../../../../../../../../rfc/45-coaching-service.md)
      — Introduced the conversation/message wire types, request DTOs, response
      envelopes, and the SSE event DTOs in `StreamEvent.kt`; established
      one-file- per-schema naming, opaque `String` ids, nullable-with-default
      PATCH fields, and fixed per-event `type` discriminators without
      polymorphic Jackson config.
- [x] [RFC-65: Email Verification](../../../../../../../../rfc/65-email-verification.md)
      — Added `emailVerified: Boolean` to `PublicUser`, surfacing verification
      state on register, login, me, and verify-email responses; introduced
      `VerifyEmailRequest` (`token`) and `VerifyEmailResponse` (`user`).
