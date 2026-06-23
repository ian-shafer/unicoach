# SPEC: `rest-server/src/main/kotlin/ed/unicoach/rest/models`

## I. Overview

This package is the **HTTP boundary data-transfer layer** for the `rest-server`
module. It defines the Kotlin `data class` DTOs — plus the `ErrorCode` enum that
types the wire error code — serialized to and from JSON on every REST endpoint.
The types carry no business logic, no domain validation, and no persistence
concerns; they are the structural contract between the JSON wire format and the
routing layer. Fields use platform-neutral JVM types (`String`,
`java.util.UUID`, `Boolean`, `Int`, `java.time.Instant`) or other DTOs in this
package — never domain value classes from `service` (e.g. `UserId`,
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
- **Factory**: `PublicUser.from(user: ed.unicoach.db.models.User)` projects a
  domain `User` onto this public shape, unwrapping `user.id.value`,
  `user.email.value`, `user.name.value`, and mapping
  `emailVerified = user.emailVerifiedAt != null`. This is the package's only
  direct reference to a `db` domain type; all other DTOs use platform-neutral
  fields exclusively. The factory drops every domain field not in the public
  roster (e.g. password-hash state, federated identities), so newly added domain
  attributes do not reach the wire unless the projection is changed.
- **Side Effects**: None. Pure value type.
- **Error Handling**: None. Deserialization failures are handled upstream.
- **Idempotency**: N/A (pure DTO).
- **Shared Usage**: Embedded by `RegisterResponse`, `LoginResponse`,
  `MeResponse`, and `VerifyEmailResponse`. The auth routes (password login,
  Google login, register, me, verify-email) build it through `from`. A change to
  this type affects every endpoint that returns user data.

#### `GoogleLoginRequest` — [`GoogleLoginRequest.kt`](./GoogleLoginRequest.kt)

- **Behavior**: Deserialized from the `POST /api/v1/auth/google` request body —
  the federated-login surface where a client submits a Google-issued ID token.
  Sole field `idToken: String` (the raw Google ID token JWT).
- **Serialization note**: Carries a `kotlinx.serialization.@Serializable`
  annotation (same as `LoginRequest`); the live route binds it via Jackson
  regardless.
- **Side Effects**: None at this layer. Token verification (RS256 signature,
  issuer/audience/expiry checks) and session issuance occur in the auth service.
- **Error Handling**: Jackson deserialization failures (missing/wrong-typed
  `idToken`) propagate to `StatusPages` as `400 Bad Request` with an
  `ErrorResponse`; a token that fails verification surfaces as an auth-route
  error envelope from the service layer.
- **Idempotency**: N/A (inbound DTO only).
- **Sensitivity**: `idToken` is a bearer credential; downstream logging masks or
  redacts it.

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

#### `ErrorCode` — [`ErrorCode.kt`](./ErrorCode.kt)

- **Behavior**: The closed enum of every REST wire error code. Each constant
  pairs an idiomatic Kotlin name with a `wire: String` carrying its lowercase
  snake_case wire form. Jackson serializes a constant to its `wire` string via
  `@get:JsonValue` on that property; deserialization maps a wire string back to
  the matching constant. Because `ErrorResponse.code` is typed `ErrorCode`, a
  wire code cannot be stringly constructed or mis-cased at a call site — the
  only way to emit a new code is to add a constant here. Every `wire` value is
  lowercase snake_case (`^[a-z][a-z0-9_]*$`).
- **Constants** (Kotlin name → wire): `UNAUTHORIZED` → `unauthorized`,
  `VALIDATION_FAILED` → `validation_failed`, `CONFLICT` → `conflict`,
  `INVALID_TOKEN` → `invalid_token`, `TOKEN_EXPIRED` → `token_expired`,
  `TOKEN_ALREADY_USED` → `token_already_used`, `NOT_FOUND` → `not_found`,
  `STUDENT_PROFILE_REQUIRED` → `student_profile_required`, `COACH_UNAVAILABLE` →
  `coach_unavailable`, `COACH_FAILED` → `coach_failed`, `VALIDATION_ERROR` →
  `validation_error`, `STUDENT_NOT_FOUND` → `student_not_found`,
  `STUDENT_ALREADY_EXISTS` → `student_already_exists`, `VERSION_CONFLICT` →
  `version_conflict`, `BAD_REQUEST` → `bad_request`, `PAYLOAD_TOO_LARGE` →
  `payload_too_large`, `PERMANENT_ERROR` → `permanent_error`, `INTERNAL_ERROR` →
  `internal_error`, `FORBIDDEN` → `forbidden`, `EMAIL_NOT_VERIFIED` →
  `email_not_verified`, `ACCOUNT_DISABLED` → `account_disabled`,
  `SERVICE_UNAVAILABLE` → `service_unavailable`.
- **`VALIDATION_ERROR` vs `VALIDATION_FAILED`**: Two distinct constants with
  distinct wire strings (`validation_error`, `validation_failed`).
  `VALIDATION_ERROR` is the legacy student-family synonym; both currently
  coexist as separate codes.
- **`EMAIL_NOT_VERIFIED`**: The wire code emitted with HTTP `403` when the
  email-verification gate blocks an authenticated-but-unverified caller on a
  gated path.
- **`ACCOUNT_DISABLED` / `SERVICE_UNAVAILABLE`**: The two Google-SSO wire codes.
  `account_disabled` signals a federated login rejected because the matched
  account is disabled; `service_unavailable` signals the upstream Google
  verification path is unreachable (e.g. JWKS fetch failure). Both are emitted
  by the `POST /api/v1/auth/google` route from the auth service.
- **Side Effects**: None. Pure value enum.
- **Error Handling**: N/A.
- **Idempotency**: N/A (pure enum).

#### `ErrorResponse` — [`ErrorResponse.kt`](./ErrorResponse.kt)

- **Behavior**: The uniform error envelope returned on every failure response
  across all REST routes. Fields: `code: ErrorCode`, `message: String`,
  `fieldErrors: List<FieldError>? = null`. The `code` is the enum-typed
  [`ErrorCode`](./ErrorCode.kt) (serialized to its lowercase snake_case wire
  string), not a free `String`. `FieldError` is imported from
  [`common/.../error/FieldError.kt`](../../../../../../../../common/src/main/kotlin/ed/unicoach/error/FieldError.kt)
  and is not redefined here.
- **`fieldErrors` shape**: Defaults to `null` (not `emptyList()`), so non-
  validation errors serialize with the key omitted entirely. `fieldErrors` is
  populated only on `400` validation responses.
- **Code casing**: Every wire code across every route family — auth,
  conversation, student, and cross-cutting plugins — is lowercase snake_case,
  drawn from the [`ErrorCode`](./ErrorCode.kt) enum. The student-family codes
  (`validation_error`, `student_already_exists`, `student_not_found`,
  `version_conflict`) share this casing with the rest. Graduation-date
  validation errors use `field = "expectedHighSchoolGraduationDate"`.
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
  `400` with code `validation_error`; missing/wrong-typed JSON propagates to
  `StatusPages` as `400 Bad Request`.
- **Idempotency**: N/A (inbound DTO only).

#### `UpdateStudentRequest` — [`UpdateStudentRequest.kt`](./UpdateStudentRequest.kt)

- **Behavior**: Deserialized from the `PATCH /api/v1/students/me` request body.
  Fields: `expectedHighSchoolGraduationDate: String` (same canonical form as
  above), `version: Int` — the OCC version the client last observed, echoed back
  for the optimistic-concurrency check downstream.
- **Side Effects**: None at this layer.
- **Error Handling**: A stale `version` surfaces as `409` with code
  `version_conflict`; a bad date as `400` with code `validation_error`; an
  absent profile as `404` with code `student_not_found`.
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
- [x] [RFC-64: Google SSO Login](../../../../../../../../rfc/64-google-sso-login.md)
      — Introduced `GoogleLoginRequest` (`idToken: String`) for the
      `POST /api/v1/auth/google` federated-login surface. Added the
      `PublicUser.from(user: User)` companion factory that projects a domain
      `User` onto the public shape, dropping password-hash state and federated
      identities. Contributed the `ACCOUNT_DISABLED` (`account_disabled`) and
      `SERVICE_UNAVAILABLE` (`service_unavailable`) wire codes for
      federated-login rejection and upstream-verification failure.
- [x] [RFC-65: Email Verification](../../../../../../../../rfc/65-email-verification.md)
      — Added `emailVerified: Boolean` to `PublicUser`, surfacing verification
      state on register, login, me, and verify-email responses; introduced
      `VerifyEmailRequest` (`token`) and `VerifyEmailResponse` (`user`).
- [x] [RFC-69: Email-Verification Gate + Error-Code Unification](../../../../../../../../rfc/69-email-verification-gate.md)
      — Introduced the `ErrorCode` enum as the single source of the wire error
      code and retyped `ErrorResponse.code` from `String` to `ErrorCode`;
      unified all wire codes to lowercase snake_case (lowercasing the five
      former-UPPERCASE student codes); added `EMAIL_NOT_VERIFIED`
      (`email_not_verified`) for the `403` verification gate.
