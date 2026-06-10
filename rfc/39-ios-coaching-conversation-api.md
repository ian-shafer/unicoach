# 39 — iOS Coaching Conversation API (OpenAPI contract)

## Executive Summary

This RFC specifies the REST API contract — **OpenAPI definitions only** — for AI
coaching conversations consumed by the iOS app. The sole deliverable is
additions to `api-specs/openapi.yaml`: new paths and component schemas. No server
code, DAO, service, LLM client, prompt assembly, or iOS code is in scope. The
contract is published ahead of the backend so the iOS app and the backend can
proceed in parallel against an agreed interface.

The surface covers three areas: conversation lifecycle (start, list, fetch,
rename, archive, delete), message turns (post a user message and receive the
coach reply; fetch history), and token-by-token streaming over Server-Sent
Events (SSE). Streaming is exposed as **dedicated endpoints** that are siblings
to the buffered ones (not content-negotiation), so every operation has a single
response media type, so the hand-written iOS `URLSession` client (RFC 27) needs
one decoder per operation and schemathesis (RFC 02) fuzzes a single content type
per path.

A conversation is **started with its first user message** and carries a derived
`lastActivityAt` distinct from `updatedAt`; archive and delete are **distinct
states** (reversible archive vs. soft-delete). The contract follows RFC 02
conventions (`/api/v1` paths, camelCase `operationId`s, `{resource}` response
wrappers, `ErrorResponse`) and projects RFC 32's model; *Detailed Design* states
the RFC 32 alignments that make these distinctions load-bearing. It deliberately
defers pagination (for both conversation and message listings), rich content
blocks, and optimistic-concurrency versioning; a cursor parameter can be added
additively without breaking the unpaginated array shape. Its resource
projections drop the `PublicX` prefix carried by the file's existing
`PublicUser`/`PublicStudent` (see *Scope and conventions*).

## Detailed Design

### Scope and conventions

This RFC adds endpoints and schemas to the single, hand-maintained
`api-specs/openapi.yaml` (OpenAPI 3.0.3). It changes nothing else. The additions
adopt the file's existing conventions:

- Paths under `/api/v1/`; camelCase, verb-first `operationId`s.
- Success bodies wrap the resource under a named key (`{ "conversation": … }`),
  matching `RegisterResponse`/`StudentResponse`.
- Resource projections are named without the `PublicX` prefix the file uses for
  `PublicUser`/`PublicStudent` (`Conversation`, `Message`): the prefix is
  redundant inside a spec whose `components.schemas` are by definition the public
  contract, and a cross-cutting rename of the existing schemas (and their Kotlin
  DTOs) is out of scope here. Errors are the shared `ErrorResponse`
  `{ code, message, fieldErrors? }`.
- **Authentication follows the established implicit convention.** Protected
  endpoints document a `401` response and do not declare a formal
  `securitySchemes` block or per-operation cookie parameter (matching the
  `/api/v1/students*` operations; only `/api/v1/auth/me` inlines the cookie
  parameter). Every endpoint in this RFC is authenticated by the
  `unicoach_session` cookie and operates on the **caller's own student**;
  ownership is resolved server-side. No `securitySchemes` block is introduced, to
  avoid diverging from the current file.

The following RFC 32 alignments are load-bearing and are reflected in the schemas
below:

- **`lastActivityAt` ≠ `updatedAt`.** Posting a message appends to
  `convo_requests` and does **not** update `convos.updated_at` (RFC 32, D-1). A
  recency-sorted chat list therefore needs a derived `lastActivityAt`
  (`MAX(convo_requests.created_at)`); `updatedAt` advances only on rename.
- **No OCC version on conversations.** `convos` is unversioned (RFC 32, D-3), so
  `updateConversation`/`deleteConversation` take no `version` — last-write-wins
  on `name`. This differs from `PublicStudent`, which carries `version`.
- **`content` is projected to a plain string.** `convo_requests.content` /
  `convo_responses.content` are opaque JSONB content-blocks (RFC 32, D-6); the
  API exposes the rendered text as `Message.content`. Rich blocks (images,
  tool use) are out of scope for this iteration.
- **`Message.id` is opaque, derived by role-prefixing.** RFC 32 keys user turns
  (`convo_requests`) and coach turns (`convo_responses`) by independent BIGINT
  PKs in separate tables, so the raw row ids share no namespace. The server
  role-prefixes the projection — `u_{convo_requests.id}` for user turns,
  `c_{convo_responses.id}` for coach turns — guaranteeing a value unique across
  both roles within (and across) conversations. The schema description exposes
  none of this: clients receive an opaque, stable string and must not parse the
  prefix or assume numeric ordering.
- **Archive is an additive backend column.** RFC 32 ships only `deleted_at`;
  D-1 explicitly permits adding a nullable state column additively. Modeling
  archive as a distinct state assumes the backend adds a nullable `archived_at`
  to `convos` in a future migration. This RFC, being spec-only, documents the
  contract assumption and does not add the column.

### Create-flow and streaming shape (resolved)

A conversation is created together with its first user turn. To preserve
streaming on the first reply while keeping every operation single-media-type,
the create and message operations are each offered as a **buffered/streamed
pair**:

| Concern | Buffered (JSON) | Streamed (SSE) |
| :--- | :--- | :--- |
| Start conversation + first reply | `POST /conversations` | `POST /conversations/stream` |
| Subsequent turn + reply | `POST /conversations/{id}/messages` | `POST /conversations/{id}/messages/stream` |

Both start operations are normative: the first coaching reply is typically the
longest and benefits most from streaming, so `streamConversation` ships
alongside the buffered `createConversation`.

### Endpoint summary

| Method | Path | operationId | Success |
| :--- | :--- | :--- | :--- |
| POST | `/api/v1/conversations` | `createConversation` | `201` |
| POST | `/api/v1/conversations/stream` | `streamConversation` | `200` (SSE) |
| GET | `/api/v1/conversations` | `listConversations` | `200` |
| GET | `/api/v1/conversations/{conversationId}` | `getConversation` | `200` |
| PATCH | `/api/v1/conversations/{conversationId}` | `updateConversation` | `200` |
| DELETE | `/api/v1/conversations/{conversationId}` | `deleteConversation` | `204` |
| GET | `/api/v1/conversations/{conversationId}/messages` | `listMessages` | `200` |
| POST | `/api/v1/conversations/{conversationId}/messages` | `postMessage` | `201` |
| POST | `/api/v1/conversations/{conversationId}/messages/stream` | `streamMessage` | `200` (SSE) |

Both `list` operations return full unpaginated arrays this iteration; a
`cursor`/`limit` query parameter is the additive forward-compatible extension
(see *Error Handling — Unbounded listings*).

### Data Models (component schemas)

Added under `components.schemas`. `ErrorResponse` and `FieldError` already exist
and are reused.

```yaml
Conversation:
  type: object
  required: [id, name, createdAt, updatedAt]
  properties:
    id:
      type: string
      format: uuid
    name:
      type: string
      maxLength: 255
    createdAt:
      type: string
      format: date-time
    updatedAt:
      type: string
      format: date-time
      description: Advances on rename only; not on new messages.
    lastActivityAt:
      type: string
      format: date-time
      nullable: true
      description: >
        Derived MAX(convo_requests.created_at). Null for a conversation with no
        turns yet. Use this — not updatedAt — to sort the chat list by recency.
    archivedAt:
      type: string
      format: date-time
      nullable: true
      description: Non-null when the conversation is archived; null when active.

Message:
  type: object
  required: [id, role, content, createdAt]
  properties:
    id:
      type: string
      description: >
        Opaque, stable message id. Clients must not parse it or assume ordering.
    role:
      type: string
      enum: [user, coach]
    content:
      type: string
      description: Rendered message text projected from the stored content blocks.
    createdAt:
      type: string
      format: date-time

CreateConversationRequest:
  type: object
  required: [message]
  properties:
    message:
      type: string
      minLength: 1
      maxLength: 100000
      description: The first user message; the conversation name is derived from it when name is omitted.
    name:
      type: string
      minLength: 1
      maxLength: 255
      description: Optional caller-supplied name; server derives one from the first message when absent.

PostMessageRequest:
  type: object
  required: [message]
  properties:
    message:
      type: string
      minLength: 1
      maxLength: 100000
      description: The user message text. Named to match CreateConversationRequest.message; both carry the same concept.

UpdateConversationRequest:
  type: object
  minProperties: 1
  description: At least one field must be present. Conversations are unversioned; no version field.
  properties:
    name:
      type: string
      minLength: 1
      maxLength: 255
    archived:
      type: boolean
      description: true archives (sets archivedAt); false unarchives (clears archivedAt).

ConversationResponse:
  type: object
  required: [conversation]
  properties:
    conversation:
      $ref: '#/components/schemas/Conversation'

ConversationListResponse:
  type: object
  required: [conversations]
  properties:
    conversations:
      type: array
      items:
        $ref: '#/components/schemas/Conversation'

CreateConversationResponse:
  type: object
  required: [conversation, userMessage, coachMessage]
  properties:
    conversation:
      $ref: '#/components/schemas/Conversation'
    userMessage:
      $ref: '#/components/schemas/Message'
    coachMessage:
      $ref: '#/components/schemas/Message'

PostMessageResponse:
  type: object
  required: [userMessage, coachMessage]
  properties:
    userMessage:
      $ref: '#/components/schemas/Message'
    coachMessage:
      $ref: '#/components/schemas/Message'

MessageListResponse:
  type: object
  required: [messages]
  properties:
    messages:
      type: array
      items:
        $ref: '#/components/schemas/Message'
```

#### Stream event schemas

The SSE payload model. Each event is one serialized `StreamEvent` (see
*Streaming protocol*).

```yaml
ConversationCreatedEvent:
  type: object
  required: [type, conversation, userMessage]
  properties:
    type:
      type: string
      enum: [conversation]
    conversation:
      $ref: '#/components/schemas/Conversation'
    userMessage:
      $ref: '#/components/schemas/Message'

UserMessageEvent:
  type: object
  required: [type, userMessage]
  properties:
    type:
      type: string
      enum: [user_message]
    userMessage:
      $ref: '#/components/schemas/Message'

MessageDeltaEvent:
  type: object
  required: [type, text]
  properties:
    type:
      type: string
      enum: [delta]
    text:
      type: string
      description: A token/text fragment of the coach reply, appended in order.

MessageCompletedEvent:
  type: object
  required: [type, message]
  properties:
    type:
      type: string
      enum: [message]
    message:
      $ref: '#/components/schemas/Message'
      description: The persisted coach message (see Streaming protocol for the delta-concatenation guarantee).

StreamErrorEvent:
  type: object
  required: [type, error]
  properties:
    type:
      type: string
      enum: [error]
    error:
      $ref: '#/components/schemas/ErrorResponse'

StreamEvent:
  oneOf:
    - $ref: '#/components/schemas/ConversationCreatedEvent'
    - $ref: '#/components/schemas/UserMessageEvent'
    - $ref: '#/components/schemas/MessageDeltaEvent'
    - $ref: '#/components/schemas/MessageCompletedEvent'
    - $ref: '#/components/schemas/StreamErrorEvent'
  discriminator:
    propertyName: type
    mapping:
      conversation: '#/components/schemas/ConversationCreatedEvent'
      user_message: '#/components/schemas/UserMessageEvent'
      delta: '#/components/schemas/MessageDeltaEvent'
      message: '#/components/schemas/MessageCompletedEvent'
      error: '#/components/schemas/StreamErrorEvent'
```

### API Contracts (paths)

#### Conversation lifecycle

```yaml
/api/v1/conversations:
  post:
    summary: Start a conversation with its first message
    description: >
      Creates a conversation owned by the caller's student and records the first
      user turn, returning the conversation plus the buffered coach reply.
      The name is derived from the first message when not supplied.
    operationId: createConversation
    requestBody:
      required: true
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/CreateConversationRequest'
    responses:
      '201':
        description: Conversation started; first turn recorded
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateConversationResponse'
      '400':
        description: Validation failure (empty/oversized message, invalid name)
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '413':
        description: Request body exceeds the configured size limit
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '401':
        description: Not authenticated
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '409':
        description: The caller has no student profile (code student_profile_required)
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '500':
        description: Internal Server Error
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
  get:
    summary: List the caller's conversations
    operationId: listConversations
    parameters:
      - in: query
        name: status
        required: false
        schema:
          type: string
          enum: [active, archived]
          default: active
        description: active returns non-archived conversations; archived returns archived ones. Deleted conversations are never returned.
    responses:
      '200':
        description: The caller's conversations, most recent activity first
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ConversationListResponse'
      '401':
        description: Not authenticated
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '500':
        description: Internal Server Error
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'

/api/v1/conversations/stream:
  post:
    summary: Start a conversation and stream the first reply
    description: >
      As createConversation, but streams the coach's first reply as
      Server-Sent Events. The stream opens with a conversation event carrying the
      new conversation id and the persisted user message, then emits delta events,
      and terminates with a single message or error event.
    operationId: streamConversation
    requestBody:
      required: true
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/CreateConversationRequest'
    responses:
      '200':
        description: SSE stream of the first turn
        content:
          text/event-stream:
            schema:
              $ref: '#/components/schemas/StreamEvent'
      '400':
        description: Validation failure (rejected before the stream opens)
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '413':
        description: Request body exceeds the configured size limit
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '401':
        description: Not authenticated
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '409':
        description: The caller has no student profile (code student_profile_required)
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '500':
        description: Internal Server Error
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'

/api/v1/conversations/{conversationId}:
  parameters:
    - in: path
      name: conversationId
      required: true
      schema:
        type: string
        format: uuid
  get:
    summary: Fetch a conversation
    operationId: getConversation
    responses:
      '200':
        description: The conversation
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ConversationResponse'
      '401':
        description: Not authenticated
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '404':
        description: No such conversation owned by the caller
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '500':
        description: Internal Server Error
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
  patch:
    summary: Rename and/or archive a conversation
    operationId: updateConversation
    requestBody:
      required: true
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/UpdateConversationRequest'
    responses:
      '200':
        description: Updated conversation
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ConversationResponse'
      '400':
        description: Validation failure (empty body, invalid name)
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '401':
        description: Not authenticated
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '404':
        description: No such conversation owned by the caller
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '500':
        description: Internal Server Error
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
  delete:
    summary: Delete (soft-delete) a conversation
    description: >
      Soft-deletes the conversation: it leaves all listings and can no longer be
      fetched. The underlying transcript is preserved as the memory source of
      truth. Distinct from archive, which is reversible.
    operationId: deleteConversation
    responses:
      '204':
        description: Conversation deleted
      '401':
        description: Not authenticated
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '404':
        description: No such conversation owned by the caller
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '500':
        description: Internal Server Error
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
```

#### Message turns

```yaml
/api/v1/conversations/{conversationId}/messages:
  parameters:
    - in: path
      name: conversationId
      required: true
      schema:
        type: string
        format: uuid
  get:
    summary: Fetch the message history
    description: Returns all messages in the conversation in chronological order (oldest first).
    operationId: listMessages
    responses:
      '200':
        description: The conversation's messages
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/MessageListResponse'
      '401':
        description: Not authenticated
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '404':
        description: No such conversation owned by the caller
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '500':
        description: Internal Server Error
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
  post:
    summary: Post a user message and receive the coach reply
    operationId: postMessage
    requestBody:
      required: true
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/PostMessageRequest'
    responses:
      '201':
        description: The turn was recorded; the user and coach messages are returned
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/PostMessageResponse'
      '400':
        description: Validation failure (empty/oversized message)
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '413':
        description: Request body exceeds the configured size limit
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '401':
        description: Not authenticated
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '404':
        description: No such conversation owned by the caller
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '500':
        description: Internal Server Error
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'

/api/v1/conversations/{conversationId}/messages/stream:
  parameters:
    - in: path
      name: conversationId
      required: true
      schema:
        type: string
        format: uuid
  post:
    summary: Post a user message and stream the coach reply
    description: >
      As postMessage, but streams the reply as Server-Sent Events. The stream
      opens with a user_message event echoing the persisted user message, then
      emits delta events, and terminates with a single message or error event.
    operationId: streamMessage
    requestBody:
      required: true
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/PostMessageRequest'
    responses:
      '200':
        description: SSE stream of the turn
        content:
          text/event-stream:
            schema:
              $ref: '#/components/schemas/StreamEvent'
      '400':
        description: Validation failure (rejected before the stream opens)
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '413':
        description: Request body exceeds the configured size limit
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '401':
        description: Not authenticated
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '404':
        description: No such conversation owned by the caller
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
      '500':
        description: Internal Server Error
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ErrorResponse'
```

### Streaming protocol (SSE)

The two `*/stream` endpoints respond `200 text/event-stream`. Each SSE frame
carries one serialized `StreamEvent` as its `data:` payload, and the SSE `event:`
field equals that event's `type`:

```
event: delta
data: {"type":"delta","text":"Let's"}

event: message
data: {"type":"message","message":{"id":"…","role":"coach","content":"Let's start…","createdAt":"…"}}
```

Event sequence:

- `streamConversation`: exactly one `conversation` event (carrying the new
  `Conversation` and the persisted `userMessage`) → zero or more `delta`
  events → exactly one terminal `message` (success) **or** `error` (failure).
- `streamMessage`: exactly one `user_message` event (echoing the persisted user
  message) → zero or more `delta` events → exactly one terminal `message` **or**
  `error`. It opens with `user_message` rather than `conversation` because a
  follow-up turn creates no conversation; only `streamConversation` delivers a
  `conversation` event.

The terminal `message` event's `content` equals the concatenation of all
preceding `delta.text` fragments; the client uses it to obtain the canonical
message id and reconcile its streamed buffer. The stream closes after the
terminal event.

### Error Handling / Edge Cases

- **Pre-stream vs in-stream failures.** Authentication (`401`), ownership/not
  found (`404`), request validation (`400`/`409`), and body-size rejection
  (`413`) are decided **before** the SSE stream opens and are returned as ordinary
  HTTP error statuses with an `ErrorResponse` body. Once a `200 text/event-stream` response has begun, any
  failure (e.g. an upstream model error) is delivered as a terminal `error`
  event inside the stream — the HTTP status is already committed to `200`.
- **Ownership.** Every `{conversationId}` operation resolves the conversation
  within the caller's student. A conversation that does not exist, is
  soft-deleted, or belongs to another student returns `404` uniformly (existence
  is not leaked). Archived conversations are still fetchable and writable;
  archive only affects default listing.
- **No student profile.** A conversation is owned by the caller's student
  (`convos.student_id`). If the caller has no student profile, the create
  endpoints return `409` with code `student_profile_required`; `listConversations`
  returns an empty list; `{conversationId}` operations return `404`. The
  `{conversationId}` operations fold "no profile" into the uniform ownership
  `404` by design — they do not declare a separate `409`, since a profile-less
  caller owns no conversation and existence is never leaked.
- **Validation.** The `message` field must be within the size bound
  (`minLength: 1`, `maxLength: 100000`); a whitespace-only value passes JSON-schema
  `minLength` but is rejected server-side after trimming (the schema cannot
  express "non-empty after trim"), yielding `400`. `name`, when supplied, is 1–255
  chars (`400` on violation), matching `convos_name_length_check`.
  `updateConversation` requires at least one field (`minProperties: 1`); an empty
  body is `400`.
- **Request body size (`413`).** The `100000`-char `message` ceiling encodes to at
  most 400000 bytes (worst-case 4-byte UTF-8), which sits well under RFC 32's
  `convo_requests_content_size_check` (`octet_length ≤ 1048576`). RFC 29 enforces
  a global ingress body limit (8 KiB default) returning `413` before content
  negotiation, and 400000 bytes exceeds it, so the body-bearing operations
  (`createConversation`, `streamConversation`, `postMessage`, `streamMessage`)
  require a per-path `server.requestSize` override sized above 400000 bytes and
  declare a `413` response. Without the override the `400` oversize branch is
  unreachable.
  Configuring the override is a backend obligation (out of scope for this
  spec-only RFC); the contract documents the `413` so clients handle it.
- **Empty conversation.** `lastActivityAt` is `null` until the first turn exists;
  in practice the first turn is created atomically with the conversation, so a
  conversation observed via the API always has at least one turn.
- **Unbounded listings (deferred pagination).** `listConversations` and
  `listMessages` return full unpaginated arrays. Expected cardinality is small
  per student (tens of conversations; tens-to-low-hundreds of turns each), so the
  first iteration omits pagination; a `cursor`/`limit` query parameter is the
  additive forward-compatible extension.
- **Archive before backend migration.** `archivedAt` and `status=archived` depend
  on the future additive `archived_at` column (see *Dependencies*). Until that
  migration lands, `archivedAt` is always `null` and `status=archived` returns an
  empty list. The `PATCH archived` toggle is accepted (not a `400`) but is a
  no-op while the column is absent: `archivedAt` continues to read back `null`.
  Coupling its acceptance to migration timing would tie the contract to a
  backend detail this spec-only RFC keeps out of scope.
- **Archive vs delete.** Archive (`PATCH archived: true`) is reversible and keeps
  the conversation listable under `status=archived`. Delete (`DELETE`) is a
  user-facing soft-delete: the conversation disappears from all listings and
  fetches `404`, while the transcript persists for the memory pipeline.

### Dependencies

- `api-specs/openapi.yaml` (RFC 02) — the single spec file these definitions are
  added to. Reuses its `ErrorResponse` and `FieldError` schemas and its
  `/api/v1` + `{resource}`-wrapper conventions; its resource projections omit the
  `PublicX` prefix carried by `PublicUser`/`PublicStudent` (see *Scope and
  conventions*).
- RFC 32 (coaching-conversations schema) — the persistence model the contract
  projects: `convos` (name, soft-delete, no version), the append-only turn logs
  (`lastActivityAt` derivation), and opaque JSONB content. **Backend dependency
  (out of scope here):** the archive state requires a future additive
  `archived_at` column on `convos` (permitted by RFC 32 D-1).
- RFC 27 (iOS login/logout) — the client consuming this contract; informs the
  separate-endpoint streaming choice (the client is hand-written over
  `URLSession`, and SSE is a distinct client code path).
- RFC 29 (request payload size limits) — the global `413` ingress limit the
  body-bearing paths declare. The byte arithmetic and the required backend
  `server.requestSize` override are specified once under *Error Handling /
  Request body size*; not restated here.
- Tooling: Deno (already provided via the `flake.nix` dev shell and used by the
  project per `deno.json`) for spec validation, parsing YAML through
  `jsr:@std/yaml`. PyYAML is **not** in the dev shell (schemathesis runs via the
  Docker image, RFC 02), so validation uses Deno, not `python3`. No new
  dependencies, libraries, or schema extensions are introduced.

## Tests

This is a spec-only change; there is no runnable server endpoint to exercise, and
the existing `bin/test-fuzz` (schemathesis) requires a live server that does not
yet implement these paths. Verification is therefore **static validation of the
OpenAPI document** plus structural assertions on the additions. End-to-end
contract conformance (schemathesis against the running server) is the
responsibility of the backend RFC that implements these endpoints.

The following are asserted by the ad-hoc Deno validation script in the
*Implementation Plan* (step 6), run inline against the edited
`api-specs/openapi.yaml`. The script is invoked directly via `deno run`; it
creates no tracked file, so `Files Modified` lists only `api-specs/openapi.yaml`.

### Document validity

- The file parses as YAML and loads as a valid OpenAPI 3.0.3 document (no syntax
  or structural errors).
- Every local `$ref` (`#/components/schemas/…`) resolves to a defined schema; no
  dangling references are introduced.
- All `operationId`s in the document are unique (the nine new ones do not collide
  with the existing `getHello`/`registerUser`/`loginUser`/`getCurrentUser`/
  `logoutUser`/`createStudent`/`getStudentMe`/`updateStudentMe`/`deleteStudentMe`).

### Path presence and shape

- All nine new paths/operations exist with the exact `operationId`s from the
  *Endpoint summary*.
- `createConversation` and `postMessage` declare `201` success bodies
  (`CreateConversationResponse`, `PostMessageResponse`); `streamConversation` and
  `streamMessage` declare a `200` response whose only content media type is
  `text/event-stream` referencing `StreamEvent`.
- `deleteConversation` declares a `204` with no response body.
- Each authenticated operation declares a `401` response; each `{conversationId}`
  operation declares a `404`; each body-bearing operation declares a `400`; the
  four large-body operations (`createConversation`, `streamConversation`,
  `postMessage`, `streamMessage`) declare a `413`; the create operations declare a
  `409`; every operation declares `500`. All error responses reference
  `ErrorResponse`.
- `listConversations` declares the optional `status` query parameter with enum
  `[active, archived]` and default `active`.

### Schema shape

- `Conversation` requires `[id, name, createdAt, updatedAt]` and declares
  `lastActivityAt` and `archivedAt` as nullable, non-required.
- `Message` requires `[id, role, content, createdAt]` with `role` enum
  `[user, coach]` and `id` typed `string`.
- `CreateConversationRequest` requires `[message]` with optional `name`;
  `PostMessageRequest` requires `[message]`; `UpdateConversationRequest` sets
  `minProperties: 1` and declares no `version` property.
- `StreamEvent` is a `oneOf` of the five event schemas with a `discriminator` on
  `type` whose `mapping` keys are exactly
  `conversation`/`user_message`/`delta`/`message`/`error`, and each event schema
  pins its `type` via a single-value `enum`.
- `StreamErrorEvent` (the in-stream terminal error path) carries an `error`
  property referencing `ErrorResponse`, so an in-stream failure delivered over a
  committed `200` is structurally reachable through the discriminator's `error`
  mapping.

## Implementation Plan

Each step edits only `api-specs/openapi.yaml`. After every step, run the
YAML well-formedness gate (Deno parses the spec through `jsr:@std/yaml`):

```sh
nix develop -c deno eval 'import { parse } from "jsr:@std/yaml"; parse(Deno.readTextFileSync("api-specs/openapi.yaml")); console.log("yaml ok");'
```

The final step runs the full structural script below.

1. **Add component schemas.** Under `components.schemas`, add `Conversation`,
   `Message`, `CreateConversationRequest`, `PostMessageRequest`,
   `UpdateConversationRequest`, `ConversationResponse`, `ConversationListResponse`,
   `CreateConversationResponse`, `PostMessageResponse`, and `MessageListResponse`
   exactly as specified in *Data Models*. Verify:
   - `nix develop -c deno eval 'import { parse } from "jsr:@std/yaml"; parse(Deno.readTextFileSync("api-specs/openapi.yaml")); console.log("yaml ok");'`

2. **Add stream event schemas.** Add `ConversationCreatedEvent`,
   `UserMessageEvent`, `MessageDeltaEvent`, `MessageCompletedEvent`,
   `StreamErrorEvent`, and the `StreamEvent` `oneOf`+`discriminator` from *Stream
   event schemas*. Verify: YAML parse (as above).

3. **Add conversation lifecycle paths.** Under `paths`, add
   `/api/v1/conversations` (`post` `createConversation`, `get`
   `listConversations`) and `/api/v1/conversations/{conversationId}` (`get`,
   `patch`, `delete`) per *API Contracts*. Verify: YAML parse.

4. **Add message paths.** Add `/api/v1/conversations/{conversationId}/messages`
   (`get` `listMessages`, `post` `postMessage`). Verify: YAML parse.

5. **Add streaming paths.** Add `/api/v1/conversations/stream` (`post`
   `streamConversation`) and `/api/v1/conversations/{conversationId}/messages/stream`
   (`post` `streamMessage`). Verify: YAML parse.

6. **Full structural validation.** Run the script below; it must exit `0`:
   ```sh
   nix develop -c deno run --allow-read - <<'TS'
   import { parse } from "jsr:@std/yaml";
   const doc = parse(Deno.readTextFileSync("api-specs/openapi.yaml")) as any;
   if (!String(doc.openapi).startsWith("3.0")) throw new Error("not OpenAPI 3.0.x");
   const paths = doc.paths, schemas = doc.components.schemas;
   const methods = new Set(["get","post","patch","put","delete"]);
   const ids: string[] = [];
   for (const p of Object.values<any>(paths))
     for (const [m, op] of Object.entries<any>(p))
       if (methods.has(m) && op && typeof op === "object") ids.push(op.operationId);
   if (ids.length !== new Set(ids).size) throw new Error("duplicate operationIds: " + ids);
   const expected: [string, string, string][] = [
     ["/api/v1/conversations", "post", "createConversation"],
     ["/api/v1/conversations", "get", "listConversations"],
     ["/api/v1/conversations/stream", "post", "streamConversation"],
     ["/api/v1/conversations/{conversationId}", "get", "getConversation"],
     ["/api/v1/conversations/{conversationId}", "patch", "updateConversation"],
     ["/api/v1/conversations/{conversationId}", "delete", "deleteConversation"],
     ["/api/v1/conversations/{conversationId}/messages", "get", "listMessages"],
     ["/api/v1/conversations/{conversationId}/messages", "post", "postMessage"],
     ["/api/v1/conversations/{conversationId}/messages/stream", "post", "streamMessage"],
   ];
   for (const [path, method, oid] of expected) {
     if (!paths[path]?.[method]) throw new Error(`missing ${method} ${path}`);
     if (paths[path][method].operationId !== oid) throw new Error(`bad operationId ${path}`);
   }
   function* refs(n: any): Generator<string> {
     if (Array.isArray(n)) { for (const v of n) yield* refs(v); }
     else if (n && typeof n === "object")
       for (const [k, v] of Object.entries(n)) {
         if (k === "$ref" && typeof v === "string") yield v; else yield* refs(v);
       }
   }
   for (const r of new Set(refs(doc))) {
     if (!r.startsWith("#/components/schemas/")) throw new Error("unexpected $ref " + r);
     if (!(r.split("/").pop()! in schemas)) throw new Error("dangling $ref " + r);
   }
   for (const s of ["Conversation","Message","CreateConversationRequest",
     "PostMessageRequest","UpdateConversationRequest","ConversationResponse",
     "ConversationListResponse","CreateConversationResponse","PostMessageResponse",
     "MessageListResponse","StreamEvent"])
     if (!(s in schemas)) throw new Error("missing schema " + s);
   if (schemas.StreamEvent.discriminator.propertyName !== "type") throw new Error("bad discriminator");
   console.log(`OK: ${ids.length} operations, ${Object.keys(schemas).length} schemas`);
   TS
   ```

## Files Modified

### Modified

- `api-specs/openapi.yaml` — add the nine paths and the component schemas defined
  above (conversation lifecycle, message turns, SSE streaming). No other files
  change; this RFC is the contract only. The backend and iOS implementations that
  consume this contract are separate, out-of-scope efforts.
