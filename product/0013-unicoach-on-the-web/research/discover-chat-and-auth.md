# DISCOVER — chat surface and auth surface, exact ground truth (brief 0013)

All citations are `path:line` in the ROOT checkout `/Users/ian/Work/unicoach` at
the time of writing. Read-only; nothing was built or run there.

This file is assembled from three passes: the conversation/streaming pass
(below, done directly), plus three delegated passes whose full detail is in
sibling files:

- `research/discover-markdown-subset.md` — the iOS Markdown subset the web must
  match
- `research/discover-auth.md` — auth routes, cookie, verification gate, password
  reset

The summary sections here restate their load-bearing facts; the sibling files
are the citations of record for them.

---

## 0. Two corrections before anything else

**Correction 1 — `public-web` does NOT need the REST API for chat, and today has
no HTTP client at all.** `public-web` already depends on `:service`, `:auth`,
`:db` in-process (`public-web/build.gradle.kts:11-21`) and already calls domain
services directly: `DbEmailVerifier` over `Database` and
`ServiceCostReportSource` over `CollegeCostService`
(`public-web/src/main/kotlin/ed/unicoach/web/Application.kt:50-52`). There is no
Ktor _client_ dependency in `public-web/build.gradle.kts`. So "the browser talks
only to `app.<domain>`" is satisfied by public-web calling `CoachingService` in
the same JVM — the REST shapes in §1–§2 are then the _reference contract for the
browser-facing JSON/SSE that public-web must itself emit_, not something
public-web consumes. A slice that writes a Ktor HTTP client from public-web to
`api.<domain>` would be adding a hop the codebase deliberately does not have
(`public-web/.../Application.kt:28-32` says so in words: "both in-process rather
than one service hopping to the other over HTTP").

**Correction 2 — `public-web` today has NO session, NO auth, NO cookie handling
of any kind.** "Unlike `rest-server` it installs no client-key gate, and unlike
`admin-web` it installs no auth gate — every route is public"
(`public-web/src/main/kotlin/ed/unicoach/web/Application.kt:22-23`). Its whole
routing table is 8 public routes plus static assets
(`public-web/src/main/kotlin/ed/unicoach/web/Routing.kt:116-154`). So _every_
logged-in surface — session read, session write, login form, logout, "who am I"
— is new code in public-web. It cannot be "reused from rest-server" as-is: the
session plumbing lives in `rest-server`
(`rest-server/src/main/kotlin/ed/unicoach/rest/auth/SessionConfig.kt`,
`.../auth/CallerResolution.kt`, `.../plugins/SessionExpiryPlugin.kt`) and is not
in a shared module. `web-common` contains only request logging and two response
helpers (`web-common/src/main/kotlin/ed/unicoach/web/common/...`).

**Already true and useful:** the ALB routes `app.<domain>` → public-web and
`api.<domain>` → rest-server, on one instance (`infra/locals.tf:24-27`,
`infra/alb.tf:82-98`), and the ALB idle timeout is already raised to 300s
_specifically so streamed chat is not severed_ (`infra/alb.tf:8-10`). Streaming
from `app.<domain>` inherits that. Verified in config only — not tested against
a live ALB.

---

## 1. Conversation + messages REST contract (`rest-server`)

Registered at `/api/v1/conversations`
(`rest-server/src/main/kotlin/ed/unicoach/rest/routing/ConvoRoutes.kt:89-116`).
Every route is cookie-authenticated; unsupported methods answer `405` with an
`Allow` header (`ConvoRoutes.kt:94,98,104,109,113` +
`rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt:35-44`).

JSON conventions
(`rest-server/src/main/kotlin/ed/unicoach/rest/plugins/Serialization.kt:14-34`):
camelCase field names, `Instant` serialized as ISO-8601 strings
(`JavaTimeModule`, `WRITE_DATES_AS_TIMESTAMPS` disabled, lines 30-32), and
**requests reject unknown properties** (`FAIL_ON_UNKNOWN_PROPERTIES = true`,
line 18) and missing creator properties (line 19). Responses are pretty-printed
(`INDENT_OUTPUT`, line 17) — but the SSE mapper deliberately disables indent,
because a multi-line `data:` payload breaks SSE framing
(`ConvoRoutes.kt:80-87`).

### 1.1 Shared shapes

```kotlin
// rest-server/.../models/Conversation.kt:5-12
Conversation(id: String, name: String, createdAt: Instant, updatedAt: Instant,
             lastActivityAt: Instant?, archivedAt: Instant?)
// rest-server/.../models/Message.kt:5-10
Message(id: String, role: String, content: String, createdAt: Instant)
// rest-server/.../models/ErrorResponse.kt:5-9
ErrorResponse(code: ErrorCode, message: String, fieldErrors: List<FieldError>? = null)
```

`role` is the literal string `"user"` or `"coach"`
(`ConvoRoutes.kt:493,502,512`) — **not** `"assistant"`. Message `id` is a
synthesized string, `"u_<convoRequestId>"` for the user side and
`"c_<convoRequestId>"` for the coach side (`ConvoRoutes.kt:492,501,513`) — i.e.
**both messages of a turn share the same UUID with different prefixes**, and a
message id is not a row id you can fetch.

`ErrorCode` wire strings are lowercase snake_case, enumerated exactly once in
`rest-server/.../models/ErrorCode.kt:13-63`. The ones the chat page can see:
`unauthorized`, `validation_failed`, `not_found`, `student_profile_required`,
`coach_unavailable`, `coach_failed`, `coaching_budget_exhausted`,
`email_not_verified`, `payload_too_large`, `internal_error`. `fieldErrors` is
emitted explicitly as `null` when absent (`api-specs/openapi.yaml:2376-2382`).

### 1.2 The seven endpoints

| Method + path                                       | Request body                                                                                                              | Success                                                                                                                                            | Error statuses                                                                                                                                                                   |
| --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `POST /api/v1/conversations`                        | `CreateConversationRequest{message: String, name: String? = null}` (`models/CreateConversationRequest.kt:3-6`)            | `201` `CreateConversationResponse{conversation, userMessage, coachMessage}` (`models/CreateConversationResponse.kt:3-7`; `ConvoRoutes.kt:149-156`) | `400` validation (`:138`), `402` budget (`:141-143`), `401` no session (`:132`), `409` `student_profile_required` (`:133`), `500` coach failed (`:159-161`), `413` oversize body |
| `GET /api/v1/conversations?status=active\|archived` | —                                                                                                                         | `200` `ConversationListResponse{conversations: [Conversation]}` (`models/ConversationListResponse.kt:3-5`; `ConvoRoutes.kt:173`)                   | `400` unknown `status` value (`:169` + `:411-416`), `401` (`:168`)                                                                                                               |
| `GET /api/v1/conversations/{id}`                    | —                                                                                                                         | `200` `ConversationResponse{conversation}` (`ConvoRoutes.kt:182`)                                                                                  | `401` (`:177`), `404` `not_found` (`:183`)                                                                                                                                       |
| `PATCH /api/v1/conversations/{id}`                  | `UpdateConversationRequest{name: String? = null, archived: Boolean? = null}` (`models/UpdateConversationRequest.kt:9-12`) | `200` `ConversationResponse` (`ConvoRoutes.kt:194`)                                                                                                | `400` when BOTH fields absent — reported as a field error on `name` (`service/.../CoachingService.kt:185-191`), `401`, `404`                                                     |
| `DELETE /api/v1/conversations/{id}`                 | —                                                                                                                         | `204` no body (`ConvoRoutes.kt:206`)                                                                                                               | `401`, `404`                                                                                                                                                                     |
| `GET /api/v1/conversations/{id}/messages`           | —                                                                                                                         | `200` `MessageListResponse{messages: [Message]}` (`ConvoRoutes.kt:217`)                                                                            | `401`, `404`                                                                                                                                                                     |
| `POST /api/v1/conversations/{id}/messages`          | `PostMessageRequest{message: String}` (`models/PostMessageRequest.kt:3-5`)                                                | `201` `PostMessageResponse{userMessage, coachMessage}` (`ConvoRoutes.kt:245-251`)                                                                  | `400` validation, `401`, `404` (`:233-235`), `402` budget (`:237-239`), `500` coach failed (`:254-256`)                                                                          |

Notes that bite:

- **A bad/non-UUID `{conversationId}` is `404`, not `400`**
  (`ConvoRoutes.kt:122-125` returns null → `respondNotFound()`).
- **Ownership never leaks.** Foreign / soft-deleted / missing convo are the same
  `404` (`service/.../CoachingService.kt:139-145`, `:333-338`), and `NotFound`
  outranks `BudgetExhausted` (`service/.../PostTurnResult.kt:13-16`).
- **`GET /conversations` for a user with no student profile is `200` with an
  empty list**, not `409` (`ConvoRoutes.kt:170`). But `POST /conversations` for
  the same user is `409` `student_profile_required` (`:133`). The list page and
  the send action therefore disagree on purpose; the web must handle both.
- List order is most-recent-activity first:
  `ORDER BY MAX(r.created_at) DESC NULLS LAST,
  c.created_at DESC, c.id`
  (`db/src/main/kotlin/ed/unicoach/db/dao/ConvosDao.kt:313`). There is no
  pagination on the coaching path — `limit` defaults to null/unbounded
  (`ConvosDao.kt:295-303`).
- Validation rules, single source: message must be non-blank after trim, and ≤
  **100 000** characters (`service/.../CoachingService.kt:105,1117-1124`);
  `name` trims, must be non-blank and ≤ **255** chars
  (`db/.../models/ConvoName.kt:11-19`); when `name` is omitted it is derived
  from the message (whitespace collapsed, first **80** chars)
  (`CoachingService.kt:106,1126-1132`).

### 1.3 In-process equivalent (what public-web would actually call)

`CoachingService`
(`service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt:83-101`)
exposes exactly the operations above, all `suspend`, all returning
`Result<sealed outcome>`:

```
listConvos(studentId, archive: ArchiveScope): Result<List<ConvoWithActivity>>      // :123
getConvo(studentId, convoId): Result<GetConvoResult>                              // :133
deleteConvo(studentId, convoId): Result<DeleteConvoResult>                        // :148
listTurns(studentId, convoId): Result<ListTurnsResult>                            // :164
updateConvo(studentId, convoId, ConvoUpdate(name, archived)): Result<UpdateConvoResult> // :180
startConvo(studentId, message, name): Result<StartConvoResult>                    // :243
postTurn(studentId, convoId, message): Result<PostTurnResult>                      // :320
```

Its constructor is NOT trivial:
`(database, llmCallLog: LlmCallLog, config: CoachingConfig,
budgetService: BudgetService, tools: ToolRegistry = empty, clock)`
— and `budgetService` is deliberately undefaulted so "a root cannot ship an
ungated coach by omission" (`CoachingService.kt:83-101`, comment at `:76-82`). A
public-web slice that constructs a `CoachingService` must wire the LLM call log,
the coaching config, the budget service **and the tool registry** — dropping the
tool registry silently produces a coach with no tools, which compiles. Copy the
wiring from `rest-server`'s `Application.kt` rather than inventing it (not
enumerated here — flagged as UNVERIFIED detail for the slice author to read at
`rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`).

The two-phase shape is the important part: `startConvo`/`postTurn` return
**synchronously** with a _cold_ `Flow<ReplyEvent>`; collecting the flow is what
executes the provider turn (`service/.../StartConvoResult.kt:9-16`).
`ReplyEvent` is `Delta(text)` |
`Completed(convoRequest, content: JsonElement, createdAt)` |
`Failed(retriable: Boolean, reason: String)`, with `Completed`/`Failed` marked
`Terminal` (`service/.../ReplyEvent.kt:17-34`). **Exactly one terminal per
turn.** `Failed.reason` is server-side only and must never be shown
(`ReplyEvent.kt:14-15`).

Coach text is not stored as text: content is an Anthropic content-block array,
flattened by `ConvoContent.renderText` (`service/.../ConvoContent.kt:34-40` →
`chat/.../ContentBlocks.kt:21`), which concatenates every `type == "text"` block
and yields `""` for anything else. The web must use the same function; it must
not re-implement block flattening.

---

## 2. The SSE contract — precise

Both streaming endpoints are **POST** (`ConvoRoutes.kt:97,112`). This matters:
`EventSource` cannot do POST, which is exactly why the brief's decision is
fetch + `ReadableStream`. There is no `GET` streaming endpoint anywhere.

Framing, verbatim from the emitter (`ConvoRoutes.kt:356-364`):

```
event: <name>\ndata: <compact JSON>\n\n        // flushed after every event
```

Response head: `Content-Type: text/event-stream` and `Cache-Control: no-store`
(`ConvoRoutes.kt:334-335`). **No `id:` field, no `retry:` field, no
heartbeat/comment frames, no `done` sentinel.** So SSE auto-reconnect and
`Last-Event-ID` resumption do not exist; a dropped connection cannot be resumed,
only re-read via `GET .../messages`.

### 2.1 Event catalogue

Payload classes: `rest-server/.../models/StreamEvent.kt:9-33`. Every payload
carries a redundant `type` field equal to the `event:` name (so a client may
switch on either).

| `event:`       | `data:` shape                                                              | Emitted at                                                                                     |
| -------------- | -------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `conversation` | `{conversation: Conversation, userMessage: Message, type: "conversation"}` | `POST /conversations/stream` only, as the FIRST frame (`ConvoRoutes.kt:281-290`)               |
| `user_message` | `{userMessage: Message, type: "user_message"}`                             | `POST /conversations/{id}/messages/stream` only, as the FIRST frame (`ConvoRoutes.kt:315-317`) |
| `delta`        | `{text: String, type: "delta"}`                                            | zero or more, per `ReplyEvent.Delta` (`ConvoRoutes.kt:339-341`)                                |
| `message`      | `{message: Message, type: "message"}`                                      | terminal, success (`ConvoRoutes.kt:343-346`)                                                   |
| `error`        | `{error: ErrorResponse, type: "error"}`                                    | terminal, failure (`ConvoRoutes.kt:348-350`)                                                   |

### 2.2 Ordering and terminality — the rules

1. Exactly one opening frame, and it is endpoint-specific: `conversation` on the
   create-stream, `user_message` on the message-stream
   (`ConvoRoutes.kt:281-290`, `:315-317`). The iOS client _rejects_ the wrong
   opener for the endpoint as a malformed frame
   (`ios-app/UnicoachiOS/ConversationClient.swift:204-209,219-223`); the web
   should be equally strict.
2. Then 0..n `delta` frames, in order. Concatenating every `delta.text` equals
   the terminal `message.message.content` — asserted:
   `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoRoutingTest.kt:372-374`.
3. Then **exactly one** terminal frame, `message` or `error`, and it is the LAST
   frame (`ConvoRoutes.kt:337-352`; the flow yields exactly one `Terminal`,
   `service/.../ReplyEvent.kt:22`). Asserted at
   `ConvoRoutingTest.kt:368-371,388-391` and
   `ConvoStreamErrorRoutingTest.kt:208-212,229-231`.
4. After the terminal frame the response body simply ends. Nothing signals
   "stream over" apart from that frame and EOF.
5. **Deltas can be followed by `error`.** A turn that streamed partial text and
   then failed emits `conversation`, `delta`…, `error`
   (`ConvoStreamErrorRoutingTest.kt:197-212`). The web must be able to discard
   or mark partial text it already painted.

### 2.3 Which failures are pre-stream (plain JSON) and which are mid-stream

**All admission failures happen BEFORE `respondBytesWriter` opens the stream**,
so they are ordinary JSON responses with a real status code, never an SSE frame
(`ConvoRoutes.kt:370-391` spells this out; handlers at `:266-320`):

- `401 unauthorized` — no/invalid session
- `409 student_profile_required` (create-stream) / `404 not_found`
  (message-stream)
- `400 validation_failed` with `fieldErrors`
- `402 coaching_budget_exhausted`
- `413 payload_too_large`

**Only provider failure is mid-stream**, and then the HTTP status is already
`200` (`ConvoStreamErrorRoutingTest.kt:206,228`). The mapping
(`ConvoRoutes.kt:404-409`):

- retriable → `coach_unavailable`, message
  `"The coach is temporarily unavailable — try again."`
- non-retriable → `coach_failed`, message
  `"The coach could not respond to this message."`

A consequence for the web client: the fetch must send
`Accept: text/event-stream,
application/json`, because the error bodies are JSON
and a `text/event-stream`-only Accept makes content negotiation answer an empty
`406` instead of the real status. The iOS client learned this the hard way and
documents it (`ios-app/UnicoachiOS/ConversationClient.swift:134-145`). **This is
the single most likely bug in a first web streaming slice.**

### 2.4 Side effects tied to the terminal

On a `Completed` terminal only, an `EXTRACT_CONVERSATION` queue job is enqueued
after the frame is written, debounced, fire-and-forget, never on failure
(`ConvoRoutes.kt:343-346,425-454`). Any non-REST path that runs turns (i.e.
public-web calling `CoachingService` directly) **must replicate this enqueue**,
or conversation extraction silently stops happening for web turns.
`CoachingService` does not do it for you — the route does.

---

## 3. The non-streaming twins: is there a complete no-JS fallback?

**Content-wise: yes. Practically: only with caveats a slice must plan for.**

- `POST /api/v1/conversations` returns `201` with the conversation, the user
  message _and_ the complete coach message (`ConvoRoutes.kt:145-157`).
  `POST /conversations/{id}/messages` returns `201` with both messages
  (`:241-252`). `GET /conversations/{id}/messages` returns the whole transcript
  (`:211-220`). So a form-post → full-page-render chat page loses **nothing**
  but progressive display. The buffered path drains the same flow to its
  terminal (`ConvoRoutes.kt:418-423`).
- Caveat 1 — **latency is the whole reply**. The request blocks for the full
  provider turn plus any tool excursions. The ALB's 300s idle timeout
  (`infra/alb.tf:8-10`) is the outer bound; there is no smaller server-side
  timeout in these handlers that I found. A no-JS POST that takes 40s is normal,
  and the browser shows a blank loading tab throughout.
- Caveat 2 — **a failed turn is invisible in the transcript**.
  `ConvoProjection.visibleExchanges` emits an exchange only when the closing
  response is `completed` and not `tool_use`; failed or still-open turns are
  dropped (`service/.../ConvoProjection.kt:92-130`, doc at `:74-91`). So after a
  `500 coach_failed`, a re-render from `GET .../messages` shows **neither** the
  user's message nor any error — the user's text appears to have vanished. A
  no-JS page must carry the error into the re-render itself.
- Caveat 3 — the buffered path has a degenerate arm: if the flow produces no
  terminal at all, `drain` fabricates `Failed(retriable = true, "no terminal")`
  → `500 coach_unavailable` (`ConvoRoutes.kt:419-423`).
- Caveat 4 — these endpoints take **JSON**, not
  `application/x-www-form-urlencoded`. A real no-JS HTML form cannot post to
  them directly; public-web must accept the form itself (as it already does for
  `POST /verify-email` with `call.receiveParameters()`,
  `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt:137-149`) and call the
  service.
- Caveat 5 — there is no CSRF protection anywhere today to copy: public-web's
  one state-mutating route is token-bearing and unauthenticated
  (`Routing.kt:134-149`). A cookie-authenticated form POST on `app.<domain>`
  needs CSRF defence that does not exist in this repo. Flagged loudly: **I found
  no CSRF token, no `SameSite` form-origin check, and no `Origin`/`Referer`
  check in any module.**

---

## 4. Markdown subset the web must match

See `research/discover-markdown-subset.md` for the element-by-element ground
truth and citations (`ios-app/UnicoachiOS/Markdown/`). Load-bearing point for
slice writing: the web renderer must reproduce _that_ subset — no more (the
corpus is what the coach's prompt is tuned against) and no less — and it must
render from the SAME plain text the API returns, i.e. the output of
`ConvoContent.renderText` (`service/.../ConvoContent.kt:34-40`), which is a
flattened concatenation of text blocks and can therefore contain any Markdown
the model emitted, including constructs the iOS subset drops. Escaping is on the
web side's head: kotlinx.html escapes text nodes, and any `unsafe { +html }` in
a Markdown renderer is an XSS hole with model-authored input.

## 5. Auth surface

See `research/discover-auth.md` for the exact bodies, statuses, cookie flags and
gate behaviour. Two repo-level facts that constrain every auth slice,
established above: public-web installs no auth plugin at all
(`public-web/.../Application.kt:22-23`), and the session machinery lives inside
`rest-server` (`rest-server/src/main/kotlin/ed/unicoach/rest/auth/`), not in a
shared module — so a web login slice either moves that code into a shared module
or writes a second implementation. State which, deliberately, in the slice.

`public-web` already owns the **email-verification landing page**:
`GET /verify-email` renders a confirm form (deliberately side-effect free, so a
scanner prefetch cannot burn the single-use token) and `POST /verify-email`
consumes the token in-process through `EmailVerifier`
(`public-web/src/main/kotlin/ed/unicoach/web/Routing.kt:120-149`, rationale at
`:80-86`). A password reset page should copy that exact two-step shape, for the
same reason.

---

## 7. Server-side work the web needs that DOES NOT EXIST today

Ordered by how badly a slice written in ignorance would fail. Every "does not
exist" below was grep-verified by the pass that owns it; the citation column
names where the _equivalent_ lives.

1. **Any session/auth in `public-web`.** No cookie read, no login, no logout, no
   "current user". Exists only inside `rest-server`
   (`rest-server/src/main/kotlin/ed/unicoach/rest/auth/SessionConfig.kt`,
   `.../auth/CallerResolution.kt:41-54`,
   `.../plugins/SessionExpiryPlugin.kt:24-77`) and is not in a shared module.
   Public-web is explicitly gate-free today
   (`public-web/src/main/kotlin/ed/unicoach/web/Application.kt:22-23`).
2. **A browser-persistent session cookie.** The set path emits **no
   `Max-Age`/`Expires`** (`rest-server/.../routing/AuthRoutes.kt:38-46`), so it
   dies when the browser closes, while the DB row lives 7 days
   (`rest-server/src/main/resources/rest-server.conf:41-42`). "Stay signed in"
   on the web requires adding `maxAge`.
3. **`SameSite=Lax` (or a considered choice).** Hard-coded `Strict`
   (`AuthRoutes.kt:45`). Strict is fine for same-site XHR but suppresses the
   cookie on a top-level navigation arriving from another site — i.e. clicking a
   link in an email, and any future Google/Apple OAuth redirect back to
   `app.<domain>`. Both are on this brief's roadmap.
4. **CSRF protection.** None anywhere. A cookie-authenticated HTML form POST on
   `app.<domain>` needs a token or an origin check; the repo's only mutating
   public-web route is token-bearing and unauthenticated
   (`public-web/.../Routing.kt:134-149`). `SameSite=Strict` is the _current_
   accidental mitigation — weakening it to `Lax` (item 3) removes it. Decide
   both together.
5. **Password reset, entirely.** No route, no table, no token, no template, no
   service, and — the part people miss — **no "set password" writer on
   `UsersDao` at all** (`db/.../dao/UsersDao.kt`; `password_hash` is written
   only at `create`, `UsersDao.kt:145`). `research/discover-auth.md` §5 gives
   the 8-part build list mirroring email verification, file for file. Add to it:
   a `revokeAllForUser` session writer, which also does not exist.
6. **A web Markdown renderer, and the sanitisation policy iOS never needed.**
   The iOS subset is a bespoke repo block parser plus Apple's closed-source
   inline parser (`ios-app/UnicoachiOS/Markdown/MarkdownBlock.swift:161-641`,
   `.../MarkdownInline.swift:20-27`), so no library reproduces it. iOS does **no
   sanitisation** — raw HTML survives as literal characters and
   `javascript:`/`data:` links keep their scheme — which is harmless in SwiftUI
   and an XSS hole in HTML. See `research/discover-markdown-subset.md` §6. The
   web must both match the subset and invent the escaping/URL-scheme policy.
7. **The extraction enqueue on any non-REST turn path.** It lives in the route,
   not the service (`rest-server/.../routing/ConvoRoutes.kt:343-346,425-454`). A
   public-web page that drives `CoachingService` directly and forgets it
   silently stops conversation extraction for web users.
8. **A no-JS-safe error carry-through for a failed turn.** A failed turn is
   invisible to the transcript projection
   (`service/.../ConvoProjection.kt:92-130`), so a plain re-render after
   `coach_failed` shows neither the question nor an error. Something must hold
   that state across the redirect — there is no server-side flash/notice
   mechanism in `public-web` today.
9. **Streaming from `public-web`.** Public-web has no SSE writer and no chat
   route; the only precedent is `rest-server`'s `respondBytesWriter` + manual
   framing (`ConvoRoutes.kt:329-364`). Infrastructure is ready
   (`infra/alb.tf:8-10`, `infra/locals.tf:24-27`); the module is not.
10. **CORS — needed ONLY if a slice breaks the binding decision.** There is no
    CORS plugin anywhere (see `research/discover-auth.md` §0.1; the one "CORS
    test",
    `rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt:125-134`,
    passes on a `405` and therefore asserts nothing). While the browser talks
    only to `app.<domain>`, this costs nothing. The moment any page fetches
    `api.<domain>` directly it is a hard blocker. Treat a CORS requirement
    appearing in a slice as evidence the slice violated the decision.
11. **Operator confirmation that `UNICOACH_CLIENT_KEYS` is unset in prod SSM.**
    The gate is inert with an empty key set
    (`rest-server/.../plugins/ClientKeyGate.kt:18-20`) and the var is empty in
    `.env.template:120` and absent from `infra/*.tf`, but if it was ever seeded
    out of band it `403`s every path but `/healthz` and the Apple notification
    hook (`rest-server/src/main/resources/rest-server.conf:57`). A browser
    cannot hold that secret. **Not verifiable from the repo — must be confirmed
    by the operator.**
12. **`SessionsDao.extendExpiry` ignores the configured expiry** — hard-coded
    `NOW() + INTERVAL '7 days'`
    (`db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt:217`). Not a web
    blocker; it makes any web "session length" copy a guess until fixed.

**Deliberately NOT covered here:** the coaching usage/budget surface
(`GET /api/v1/coaching-usage`, the 402 body, the allowance config). That pass
did not complete and the parent is covering it. The one fact this file asserts
about it is the chat-side redaction rule at
`rest-server/.../routing/ConvoRoutes.kt:370-391`.

---

## 8. Flagged as unverified

- Nothing in this file was built, run, or tested. It is source reading of the
  root checkout only.
- The full `CoachingService` construction wiring in `rest-server`'s
  `Application.kt` was not enumerated; a public-web slice must read it rather
  than guess the dependency list.
- No server-side timeout smaller than the ALB's 300s was found on the buffered
  turn handlers, but I did not audit Netty/Ktor engine defaults, so "300s is the
  bound" is unconfirmed.
- The ALB host routing and idle timeout were read from Terraform, not observed
  against the live load balancer.
- Sibling-file facts (Markdown subset, auth bodies/cookie/gate/reset) are
  restated here from `research/discover-markdown-subset.md` and
  `research/discover-auth.md`; those files carry their own citations and their
  own unverified lists, which I did not re-check independently.
