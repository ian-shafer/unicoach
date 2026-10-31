# RFC 262 — The chat, on the web

**Slice:** `web/04/the-chat` (brief 0013, _Unicoach on the web_). **Status:**
approved (Ian, 2026-10-30). **Base:** `main@00bdeb7e`. **Lane:** rfc.

## Executive Summary

A signed-in student opens `app.<domain>`, lands on the chat, types a question
and watches the coach's answer arrive. This is the slice where Unicoach becomes
usable in a browser.

The page is server-rendered, so a conversation can be READ without JavaScript.
SENDING needs JavaScript: a small TypeScript module posts the composer to the
server and paints the answer as it streams. There is no no-JavaScript send path
— Ian dropped it at this RFC's approval stop (2026-10-30: "Do we really need to
write code for this? Let's not build a fully featured product before we even
launch"), overriding gate-2 D20 and the slice's no-JavaScript acceptance
criterion. `public-web` calls `CoachingService` in-process (D13); it never calls
the REST API.

Three things move or appear on the server so the web cannot drift from the app:

- **The extraction enqueue moves into `CoachingService`.** Today it lives in the
  REST route, so a web turn would silently skip it (spec preamble 10).
- **The student coach's tool list gets one home**,
  `StudentToolRegistry.standard`. Today it is 20 tools built inline in
  `rest-server`'s `Application.kt`; a second copy in `public-web` would drift.
- **The two failure sentences** the REST API sends move to `:service`, so both
  clients say the same thing.

Coach Markdown is rendered to HTML by ONE renderer, in Kotlin, matching the iOS
subset, with an explicit sanitisation policy: every character is escaped, links
are kept only for `http` and `https`. While a reply streams, the browser shows
its plain text; when it completes, the server sends the rendered HTML and the
browser swaps it in.

A failed turn keeps the student's words on screen, marked as not answered, with
a "Try again" button. No table, no migration, no session-metadata change.

## Detailed Design

### 1. What exists, and what does not

- `CoachingService.startConvo` / `postTurn` return a pre-flight outcome
  (`ValidationFailure`, `BudgetExhausted`, `NotFound` for `postTurn`) or
  `Started(convo, userTurn, reply: Flow<ReplyEvent>)`. The flow is cold;
  collecting it runs the provider turn. Exactly one terminal: `Completed` or
  `Failed(retriable, reason)`. `reason` is server-side only.
- `CoachingService.listTurns` returns `VisibleExchange`s; failed turns are not
  among them (`ConvoProjection`). `ConvoContent.renderText` flattens coach
  content to text.
- A failed FIRST turn soft-deletes its convo (`CoachTurnLoop`'s `finally`).
- `public-web` has sessions, the `VERIFIED` route wrappers (`getVerified` /
  `postVerified`), the same-origin guard on every non-GET (RFC 224), the
  enforcing CSP with `connect-src 'self'`, and the Deno TypeScript rail (RFC
  225). It does not have `:chat` on its compile classpath, does not load
  `chat.conf`, and constructs no coach.
- Chat needs a `students` row. Registration does not create one;
  `StudentService.createStudent` (graduation date + date of birth) does, and the
  only door to it today is iOS onboarding. `web/06` brings it to the web.

### 2. The service-layer moves

#### 2a. The extraction enqueue (spec preamble 10)

New `service/.../coaching/extraction/ExtractionScheduler.kt`:

```kotlin
class ExtractionScheduler(
  private val queueService: QueueService,
  private val config: ExtractionConfig,
) {
  /** Fire-and-forget: a queue failure is logged and swallowed, never alters the turn. */
  suspend fun schedule(convoId: ConvoId, throughRequestId: ConvoRequestId)
}
```

Its body is `ConvoRouteHandler.enqueueExtraction` + `mapPayload`, moved, not
copied: the `enabled` check, the debounce `delay`, the `DatabaseFailure` warn,
the swallowed throw. One exception is not swallowed: a `CancellationException`
is rethrown, because the enqueue now runs inside the turn (below) and a client
disconnect must cancel that turn, not be logged as a queue failure.

- `CoachingService` takes `extraction: ExtractionScheduler` as an UNDEFAULTED
  constructor parameter, beside `budgetService` and for the same reason: a root
  cannot ship a coach that skips extraction by omission.
- `TurnParty.onSucceeded()` becomes `onSucceeded(finalRequest: ConvoRequest)`;
  the loop passes `currentCall.request`, the same request the `Completed` event
  carries (RFC 94: the watermark advances over any tool excursion in one pass).
- `StudentTurnParty.onSucceeded` calls
  `extraction.schedule(convoId,
  finalRequest.id)` after its existing
  commitment / fit-suggestion marks. `SupporterTurnParty.onSucceeded` stays a
  no-op (parent turns are not extracted today; `SupporterConvoRoutes` passes
  `onCompleted = {}`).
- `ConvoRouteHandler` loses `queueService`, `extractionConfig`,
  `enqueueExtraction`, `mapPayload`; `streamReply` and the buffered handlers
  stop enqueueing. `streamConversationReply`'s `onCompleted` parameter goes if
  no caller still needs it.

**One ordering change, stated.** The enqueue used to run after the REST
`message` frame was written; it now runs just BEFORE the `Completed` event is
emitted, because `onSucceeded` precedes the emit. The cost is one `jobs`
insert's latency before the last frame; the enqueue is still never made on a
failed turn. A client that disconnects after the provider finished but before
the frame is written now still gets its turn extracted — which is correct,
because the turn is persisted.

#### 2b. One home for the student coach's tools

New `service/.../coaching/StudentToolRegistry.kt`, mirroring
`SupporterToolRegistry.standard`: a `standard(...)` factory returning the
`ToolRegistry` of the 20 tools `rest-server/.../Application.kt` builds inline
today, in the same order with the same collaborators:

```kotlin
suspend fun standard(
  database: Database,
  supporterInvites: SupporterInviteService,
  costReportShares: CostReportShareService,
): ToolRegistry
```

It is `suspend` because it loads the `Codebook` itself
(`Codebook.loadOrEmpty(database)`), which keeps `:college` off `public-web`'s
classpath: a caller passes only the two collaborators that carry configuration,
and the registry constructs the stateless services over `database` itself.
`rest-server` and `public-web` both call it; no tool list exists anywhere else.
`rest-server`'s other routes keep their own instances of the stateless services.

#### 2c. One home for the failure sentences

New `service/.../coaching/FailedTurnCopy.kt`:

```kotlin
/** What a student is told about a failed turn. The reason never reaches them. */
fun studentSentence(retriable: Boolean): String =
  if (retriable) "The coach is temporarily unavailable — try again."
  else "The coach could not respond to this message."

fun ReplyEvent.Failed.studentSentence(): String = studentSentence(retriable)
```

The sentence takes the one fact it depends on, so a caller that has no failed
event — the composer's sentence for a failure no event reports (§10) — asks for
it directly rather than building a `Failed` with a blank reason. `rest-server`'s
`coachFailedError` reads it; the wire strings are unchanged.

### 3. Wiring the coach into `public-web`

- `public-web/build.gradle.kts`: `implementation(project(":chat"))` (for
  `ChatConfig`, `ChatProviderFactory`);
  `testImplementation(testFixtures(
  project(":chat")))` for
  `ScriptedAnthropicTransport`.
- `PUBLIC_WEB_CONF_RESOURCES` adds `chat.conf` (after `service.conf`, the
  `rest-server` order). `CHAT_PROVIDER` becomes required for `public-web`, as it
  already is for `rest-server`; production's one `/etc/unicoach/env` already
  carries it and `CHAT_ANTHROPIC_API_KEY` for every service on the host, so no
  infra change.
- `startServer` runs every typed reader BEFORE `Database(...)`, so a refusal
  never strands a pool: its existing `PublicWebConfig`, `RequestLoggingConfig`,
  `DatabaseConfig`, `EmailVerificationConfig` and `SupporterInviteConfig`, then
  the coach's, read together by one `CoachConfigs.read(config)` in this order:
  `CoachingConfig`, `LlmPriceBook` (with
  `requireExplicitlyPriced(listOf(coachingConfig.model))`), `BudgetConfig`,
  `SubscriptionPlans`, `ChatConfig`, `ExtractionConfig`, `CostReportConfig` (the
  `share_cost_report` tool's secret). Then, after the pool: the provider, the
  registry (which loads the `Codebook`, §2b), `LlmCallLog`, `BudgetService`,
  `ExtractionScheduler` and the `CoachingService`, plus `StudentService` for
  student resolution. A registry refusal closes the provider it would otherwise
  strand, and a refused coach build closes the pool it needed (the original
  failure is rethrown; a failure to close rides along as suppressed).
- The provider is closed on `ApplicationStopped` beside the database
  (rest-server never closes it; public-web does). `ChatProviderFactory` returns
  a `CloseableChatProvider` (`ChatProvider` + `AutoCloseable`), so that close is
  in the type public-web holds, not found by a runtime cast.
- `publicWebModule` takes `coachingService` and `studentService`.
- The one Hikari pool (10) is shared; the loop opens a connection per write, not
  per stream.

### 4. Routes and the door

| Method + path     | Access   | Does                                                                    |
| ----------------- | -------- | ----------------------------------------------------------------------- |
| `GET /chat`       | VERIFIED | a NEW conversation: empty state + composer (iOS's root is always fresh) |
| `GET /chat/{id}`  | VERIFIED | that conversation's visible exchanges + composer                        |
| `POST /chat`      | VERIFIED | `startConvo` with the form's `message`, answered as a stream (§6)       |
| `POST /chat/{id}` | VERIFIED | `postTurn` with the form's `message`, answered as a stream (§6)         |

The composer is a real `<form method="post">` pointing at those URLs, so its
`action` names the conversation; the TypeScript is what submits it. The page
carries a `<noscript>` line, "Sending a message needs JavaScript turned on.", so
a student with it off is told why the button does nothing useful.

- Body: `application/x-www-form-urlencoded`, one `message` field, read with the
  existing `formParametersOrNull` (an unreadable body, or an absent or repeated
  `message` → a 400 notice, §5).
- `GET /chat/{id}` with a non-UUID `{id}`, or a convo that is missing, deleted
  or another student's → the branded 404 (`NotFound` never leaks ownership); the
  same cases on `POST` → the 404 notice (§5). `{id}` is parsed by
  `canonicalUuidOrNull`, moved from `rest-server` to
  `web-common/.../http/CanonicalUuid.kt` so both servers read an id by one rule
  (a canonical UUID only; `UUID.fromString`'s short groups are refused).
- CSRF: the same-origin guard already refuses every non-GET without a matching
  `Origin`/`Referer`; a same-origin `fetch` POST sends `Origin`.
- `mapPathToAccess` adds `/chat` and `/chat/…` as VERIFIED (the optimisation);
  the routes register through `getVerified` / `postVerified` (the authority).

**The door.** The signed-in root is the chat:

- `signedInDoorLanding` falls back to `CHAT_PATH` instead of `ACCOUNT_PATH`, so
  a signed-in `GET /`, `/sign-in` or `/register` lands on `/chat`.
- `signInLandingUrl` sends a plain sign-in (no invite or claim notice) to
  `/chat`. A sign-in carrying a notice keeps its current landing, because the
  notice is rendered there.
- The signed-in header gains "Chat", before "Account".

**No student row** (D-B, Ian at approval: "catch this in an interceptor"). A
verified user with no `students` row is redirected to the student signup page
before any `/chat` handler runs. The interceptor is a ROUTE-SCOPED plugin
installed on the `/chat` route tree — after Ktor has resolved the route, so no
path spelling reaches a chat handler around it (the RFC 224 lesson; a
pre-routing path match is an optimisation, never the authority). It resolves the
student once through `StudentService.getStudentForUser`, stores it on
`call.attributes`, and the chat handlers take it from there typed, the way
`getVerified` hands a handler its `WebCaller`. With none, it answers `302`
(`GET`) / `303` (`POST`) to `STUDENT_SIGNUP_PATH` and finishes.

The web has no student signup page yet — `web/06` builds it, behind `web/05`. So
this slice reserves the path, `GET /get-started` (VERIFIED), and renders a
placeholder there: "The coach needs a little about you first. For now, set that
up in the Unicoach app on iPhone — setting it up here on the web is coming
soon." A caller who already has a student row is sent on to `/chat`. `web/06`
replaces the placeholder with the real form at the same path, so the redirect
does not move. `/get-started` is its own route (`signup/GetStartedRoute.kt`),
registered beside the chat's, not part of it: `web/06` replaces that one
function. A supporter-only account (a parent, with no student row) is redirected
to `/account`, not to student signup, where the existing parent note already
tells them the parents' coach is in the iPhone app; an account holding both rows
is a student, as on iOS.

### 5. Refusals before the stream: a notice, not a page

Every pre-flight outcome is known BEFORE the stream opens, and persisted
nothing. Each is answered as a short server-rendered HTML fragment
(`text/html`), at a real status, which the client puts in place of the pending
turn while the student's text goes back into the composer (§10):

| Outcome                                      | Status | Notice                                                        |
| -------------------------------------------- | ------ | ------------------------------------------------------------- |
| `ValidationFailure`                          | `400`  | `:service`'s own field message                                |
| unreadable body, absent / repeated `message` | `400`  | "That message couldn't be sent." (`:service` was never asked) |
| `BudgetExhausted`                            | `402`  | the out-of-allowance notice (§8)                              |
| `NotFound`                                   | `404`  | "This conversation isn't available." and a link to a new one  |

(No student row never reaches a handler: the §4 interceptor redirects first.)

The fragment is rendered by our own templates (the same trust as
`completed.html`, §6), never from request text, and the response carries an
`X-Chat-Notice` header, which is the only thing that lets the client insert a
non-stream body (§10). A `ValidationFailure` notice carries `:service`'s
`FieldError`s whole and joins their messages only when it renders. The three
malformed shapes read as one sentence to the student, and the log line names
which one it was. Budget exhaustion is therefore never a broken stream: the
student sees the notice where the reply would have been, their own message
marked "Not answered" (§7), and their message in the composer.

The 404 notice is a fragment, not a page, so it must reach the client as
written: the notice responder sets an `OwnNotFoundBody` call attribute, and the
`StatusPages` 404 handler leaves a call carrying it alone. Every other 404 (an
unmatched path, a dead token) still renders the branded page.

### 6. The stream: the web's own format (spec preamble 9)

`Content-Type: text/event-stream`, `Cache-Control: no-store`. Framing is
`event: <name>\ndata: <compact JSON>\n\n`, flushed per event — the same framing
`rest-server` writes, chosen for its trivially incremental parser, NOT for
compatibility; nothing outside `public-web` reads this stream.

| event       | data                                      | when              |
| ----------- | ----------------------------------------- | ----------------- |
| `started`   | `{"conversationUrl": "/chat/<uuid>"}`     | first, once       |
| `delta`     | `{"text": "…"}`                           | 0..n              |
| `completed` | `{"html": "<rendered coach message>"}`    | terminal, success |
| `failed`    | `{"retriable": bool, "message": "<§2c>"}` | terminal, failure |

- Exactly one terminal, last. A `delta` run may end in `failed`.
- `completed.html` is the coach message rendered by the ONE Markdown renderer
  (§9) — the same fragment `GET /chat/{id}` renders for that exchange.
- Pre-stream outcomes (validation, budget, not found, no student) are answered
  BEFORE the stream opens, as the §5 notices — never as an event.
- No `id:`, `retry:` or heartbeat. **No reconnection** (D-C2): a stream that
  ends without a terminal is shown as a retriable failure, the text kept. A
  disconnect cancels the server-side turn (as on REST), so there is no reply to
  resume.

### 7. A failed turn never swallows the student's words (spec preamble 11)

`web/05`'s session drafts have not landed, so this slice uses the explicit
failed-turn rendering. The failed turn is drawn as a pair:

- the student's message, as typed, styled as a sent message marked "Not
  answered";
- beneath it the sentence from §2c, and a **Try again** button.

Retriable and non-retriable differ in the sentence ("temporarily unavailable —
try again" / "could not respond to this message"). Both offer Try again, as iOS
does — a non-retriable failure may still succeed with edited text.

The TypeScript renders that pair in place of the half-painted reply (any partial
text is removed, never left looking like an answer), and puts the text back in
the composer only when the composer is empty, so a failure never overwrites what
the student has typed meanwhile. Try again re-sends the original words, whatever
the composer now holds.

This is presentation only. Nothing about a failed turn is stored that is not
stored today; a reload of the conversation later shows only completed exchanges,
which is the existing projection.

### 8. Out of allowance (spec: `web/10` owns the copy)

`BudgetExhausted` is a pre-flight outcome, so it is always the §5 notice, never
a stream event. The copy is interim until `web/10`: "Coaching is paused", then
one sentence by `Entitlement.basis` (reusing the iOS `PaywallCopy` sentences):

- `FreeAllowance` → "You've used your free coaching."
- `Subscription(resetsAt)` → "You've used this period's coaching. It resets
  <date>."
- `AdminExemption` → "You've used your coaching allowance."

then (D18) "To keep going, subscribe in the Unicoach app on iPhone. Subscribing
on the web isn't available yet. Everything else here still works." The unsent
message stays in the composer. The entitlement is logged with
`budgetSkipMessage("chat", …)` as REST does. No dollar figure, token count or
provider name is rendered.

### 9. Markdown: one renderer, the iOS subset, a stated sanitisation policy

New package `public-web/src/main/kotlin/ed/unicoach/web/markdown/`:

- `MarkdownBlocks.kt` — a port of
  `ios-app/UnicoachiOS/Markdown/MarkdownBlock.swift`'s block parser: the same
  `Block` model (heading 1–6, paragraph, flattened list with `depth` and
  `bullet | ordered(n) | task(done)` markers, quote as lines, verbatim code with
  the info string discarded, table with alignments normalised rectangular, rule)
  and the same rules (§2 of `research/discover-markdown-subset.md`): setext
  headings, ordinals resolved at parse, indent-stack depth, loose lists, lazy
  continuation, unclosed fence and header-only table kept. NOT supported,
  exactly as iOS: indented code, nested quotes, block structure inside a quote,
  HTML blocks, reference links, footnotes.
- `MarkdownInline.kt` — inline spans to match Foundation's behaviour as probed
  (§3 of the research): `**strong**`, `*em*`/`_em_`, `***both***`, `~one~` and
  `~~two~~` strikethrough, `` `code` ``, `[text](url)` (title dropped, emphasis
  inside the link text dropped, empty text renders nothing), bare `https://`,
  `www.` and email autolinks (an email is parsed as iOS parses it, then rendered
  as text by the link policy below), `<https://…>` autolinks, backslash escapes,
  HTML entities decoded, backslash-newline hard break, unclosed emphasis and
  malformed links left as their own characters. Images render as their alt text.
- `MarkdownHtml.kt` — `FlowContent.markdown(source: String)` rendering through
  kotlinx.html builders only.

**Why the two parsers are hand-written, not a CommonMark library.** The target
is what the app draws, and it differs from CommonMark at PARSE time, not only in
rendering, so a library's tree would have to be un-parsed to reach it. The block
layer (a port of the iOS parser): no indented code blocks; a nested `>` is a
literal, not a nested quote; block structure inside a quote is literal text;
list depth comes from a stack of the indents the list has used, flattened to a
run of items. The inline layer (what Foundation was probed to produce): a single
`~one~` is strikethrough; bare `www.` hosts and bare emails autolink; emphasis
inside a link's text is lost. Escaping is not either parser's job — the renderer
builds every element through kotlinx.html (rule 1 below), so a parser bug
garbles a reply's rendering and cannot inject HTML. Both files' KDoc says the
same.

**Sanitisation policy** (the slice states it, because coach output is
model-generated text reaching a browser — an injection surface iOS never had):

1. **Every character of model text reaches the page as an escaped text node.**
   The renderer builds HTML with kotlinx.html tag builders and `+text`; `unsafe`
   and raw-string HTML are never used. Raw HTML in a reply (`<script>`, `<b>`,
   `<img onerror>`) renders as its literal characters, as on iOS.
2. **Links keep their `href` only for `http:` and `https:`** (scheme compared
   case-insensitively after trimming). Any other target — `mailto:` (Ian, at
   approval), `javascript:`, `data:`, `vbscript:`, a relative path, a
   protocol-relative `//host` — renders as the link's text with no anchor. Kept
   links get `rel="noopener noreferrer nofollow ugc"` and `target="_blank"` so
   following one never replaces the chat (D-E).
3. **No `<img>`, no `style` attribute, no `class` from model text.** Classes on
   rendered elements are the renderer's own, fixed.
4. The enforcing CSP (no `'unsafe-inline'`) stays as the second line, not the
   first.

The TypeScript never parses Markdown and never builds HTML from model text: it
paints deltas with `textContent` and inserts only `completed.html`, a fragment
the server produced through rule 1 (D-A).

**Presentation, matching iOS:** soft breaks drawn (`white-space: pre-wrap` on
paragraphs), h1/h2 one size and h3–h6 another, quote as a leading hairline in
the secondary colour, code blocks outlined and wrapping (never scrolling), list
depth as indentation of a flat row list, task markers ☐/☑, links in the text
colour and underlined. **Tables never scroll sideways** (RFC 120): a `<table>`
at wide widths; under the 640 px breakpoint each row stacks into a block whose
cells read "Header: value", via a `data-label` attribute the renderer writes
from the escaped header text — no inline style. The table carries the site's
existing `data-table` class, so it reuses the one narrow-screen stacking rule
the stylesheet already has rather than a second copy. A cell under an empty
header carries no `data-label`, so it reads as its value alone, as iOS speaks
it.

### 10. The client: `chat.ts`

`public-web/src/main/ts/chat.ts`, mounted from `main.ts` like `backToTop`
(`mount(document)` does nothing when the page has no `form.chat-composer`). No
inline handlers (CSP).

- **Submit:** intercept the composer's `submit` (always `preventDefault`); a
  blank message sends nothing. Otherwise POST
  `new URLSearchParams(new FormData(form))` to `form.action` with
  `Accept: text/event-stream, text/html`. A `200 text/event-stream` response is
  the stream. A response the fetch reached by following a redirect (the D-B
  interceptor, or the auth gate after a session expired) navigates the page to
  `response.url`. A response carrying the `X-Chat-Notice` header is a §5 notice:
  its body (our own fragment) is put where the reply would have gone, the
  student's bubble is marked "Not answered", and the text returns to the
  composer if it is empty (§7). Any other response — the branded 503 or 404
  page, a proxy's error page — is never inserted: it is the retriable failure
  (§7), as is a fetch that rejects before a response and a notice whose body
  cannot be read. Each such cause is reported with `console.error`.
- **Optimistic turn:** append the student's message, clear the textarea, show
  "Coach is typing…" until the first delta.
- **Stream:** a pure, tested `SseParser` over `ReadableStream` chunks (handles a
  frame split across chunks, multi-event chunks, a trailing partial frame).
  `delta` → append to the reply element's `textContent`. `completed` → replace
  the reply element with the parsed `html` (a `<template>`), update the form's
  `action` and `history.replaceState` to `conversationUrl` (only now — a failed
  first turn's convo no longer exists). `failed` / EOF without a terminal / read
  error → §7. An event name the client does not know is skipped silently (a
  later server's); a known event whose data does not decode is skipped and
  reported with `console.warn`.
- **Mount:** a composer missing its textarea, Send button, `action` or retriable
  failure sentence is reported and left unmounted, never driven with a blank
  sentence or a guessed destination.
- **Composer while streaming** (D-C): the textarea stays enabled — the student
  can keep typing — and Send is disabled until the terminal, so one conversation
  never has two turns in flight.
- **Focus and scroll:** focus stays in the textarea after send. The transcript
  follows the streaming reply only while the student is within 80 px of the
  bottom; scrolling up stops the follow, scrolling back to the bottom resumes
  it. On page load `chat.ts` scrolls the transcript to its end (the transcript
  is its own scroll container, so a URL fragment would not move it); the last
  exchange still carries `id="latest"` as its anchor. Enter inserts a newline;
  Send is the button (and ⌘/Ctrl+Enter).
- **Layout** (D9): the composer is pinned to the bottom of a `100dvh` column,
  the transcript scrolls above it; fluid 320–1440 px through the existing
  tokens. The chat page uses its own `ShellWidth` if the two existing ones do
  not fit. The chat page hides the site footer (its two links are already in the
  header, and a footer under a pinned composer would unpin it), and carries a
  visually-hidden `h1` (`.visually-hidden`) as the page's accessible title.

### Reachability

The door is the signed-in root: sign in on a laptop and the first page is
`/chat`. The header's "Chat" link reaches it from every signed-in page. Existing
conversations are reachable by URL here; `web/09` adds the list.

### Value before ask, guided not gated

The student types immediately; nothing about the chat asks for a profile it does
not need. The one hard requirement — a `students` row — is the existing service
rule, and a student without one is sent to the student signup page (D-B) rather
than shown a broken composer.

### Data model, schema, session metadata

None. No table, no migration, no `sessions.metadata` change (D11 does not fire).
The composer's unsent text lives only in the page; `web/05` brings session
drafts (D12) to every signed-in form, this one included.

### Dependencies

`public-web` → `:chat` (compile) and `:chat` testFixtures (test). No new
library. Deno: none beyond the existing toolchain.

### Decisions (defaults proposed)

- **D-A — Markdown while streaming.** Plain text while streaming, swapped for
  the server-rendered HTML at completion; one renderer, in Kotlin. Alternative:
  re-render on the server per delta and stream HTML (live formatting, ~O(n²)
  bytes); or a second renderer in TypeScript (two renderers can disagree —
  rejected).
- **D-B — a signed-in user with no student profile** (a spec gap: `web/04`
  assumes one, `web/06` creates it). Ian at approval: a route-scoped interceptor
  on `/chat` redirects them to the student signup page. That page is `web/06`'s;
  this slice reserves `/get-started` with the placeholder copy in §4.
  Supporter-only accounts go to `/account` instead (default).
- **D-C — the composer while streaming.** Typing allowed, Send disabled until
  the reply ends. The approved spec ("stays usable") wins over iOS, which
  disables the field. **D-C2:** no automatic reconnection; a dropped stream is a
  retriable failure with the text kept.
- **D-D — no no-JavaScript send path** (Ian, at approval, overriding gate-2
  D20). A conversation reads without JavaScript; sending needs it, and a
  `<noscript>` line says so. No buffered form handler, no turn timeout, no
  native-submit fallback.
- **D-E — links open in a new tab** with `noopener noreferrer nofollow ugc`;
  only `http` and `https` are links. `mailto:` is NOT supported (Ian at
  approval); a bare email address renders as plain text, where iOS links it.
- **D-F — interim out-of-allowance copy**, §8, until `web/10`.
- **D-G — failed turn = kept message + sentence + "Try again"**, text back in
  the composer, §7.
- **D-H — the extraction enqueue now runs before the final event** rather than
  after the REST frame is written, §2a.

New user-visible strings: "What's on your mind about college?" (empty state,
iOS's), "Message the coach" (composer label), "Send", "Coach is typing…", "Not
answered", "Try again", "Chat" (header), "Sending a message needs JavaScript
turned on." (`<noscript>`), "This conversation isn't available.", "That message
couldn't be sent." (a malformed send, §5; only a hand-built request reads it),
the `/get-started` placeholder (§4), and the §8 copy.

### Edge cases

- Two tabs posting to one convo at once: two turns, each its own exchange — the
  REST behaviour, unchanged.
- Client disconnect mid-stream: the turn is cancelled server-side (REST
  behaviour); the page, if still open, shows a retriable failure.
- A reply whose final text is empty: `completed.html` is an empty message
  element, as `renderText` yields `""` today.
- A message over 100 000 characters: `ValidationFailure` → §5's 400.
- `Failed.reason` never reaches HTML, an event or a log line visible to the
  student; it is logged server-side by the loop as today.

## Tests

All Kotlin tests run against a real database; the provider is
`ScriptedAnthropicTransport` (chat testFixtures) where a test needs a specific
reply or failure. No test reads source text.

**`:service`**

1. `CoachingServiceTest`: a completed `startConvo` turn enqueues exactly one
   `EXTRACT_CONVERSATION` job with
   `{convoId, throughRequestId = Completed's
   request}`; a tool-round turn's
   `throughRequestId` is the FINAL request.
2. A `Failed` turn (retriable and not) enqueues nothing;
   `extraction.enabled =
   false` enqueues nothing.
3. A completed `postTurn` enqueues one job.
4. `ExtractionScheduler`: a queue `DatabaseFailure` is swallowed (the turn still
   completes).
5. A supporter turn enqueues nothing.
6. `StudentToolRegistry.standard` contains the 20 tool names, once each.
7. `FailedTurnCopy`: each `retriable` value maps to its sentence.

**`:rest-server`**

8. The existing `ConvoExtractionEnqueueTest` and `ConvoStreamErrorRoutingTest`
   pass unchanged in assertion (they now exercise the service-layer enqueue and
   copy). Together with test 17 this is the "either caller skips it" proof:
   enqueue lives in the service, so a door cannot skip it, and each door has a
   test that a completed turn produced the job.

**`:public-web` — routing (`ChatRoutingTest`)**

9. Signed-out `GET /chat` → sign-in; unverified → verify hold;
   `GET //chat`/`/%63hat` → refused the same way (route-declared access).
10. Signed-in `GET /`, `/sign-in`, `/register` → `/chat`; plain `POST /sign-in`
    lands on `/chat`; the header shows "Chat".
11. `GET /chat` renders the empty state, a composer posting to `/chat`, and the
    `<noscript>` line.
12. `GET /chat/{id}` after a completed turn shows the student's text and the
    rendered reply, anchored `#latest`.
13. Another student's convo, a deleted one, a non-UUID id → `GET` answers the
    branded 404.
14. User without a `students` row → `GET /chat`, `GET /chat/{id}`, `GET //chat`
    → `302` to `/get-started`; `POST /chat` → `303` there, no turn run and no
    convo row; a supporter-only account → `/account`; `/get-started` renders the
    placeholder, and sends a student on to `/chat`.
15. Cross-site `POST /chat` (foreign `Origin`) → `403`, no turn run.

**`:public-web` — stream (`ChatStreamTest`)**

16. `POST /chat` → `200 text/event-stream`, `no-store`; events are `started`,
    `delta`…, `completed` in order; concatenated deltas equal the reply text;
    `completed.html` equals the fragment `GET /chat/{id}` renders for that
    exchange; `started.conversationUrl` names the new convo.
17. That turn produced exactly one `EXTRACT_CONVERSATION` job.
18. `POST /chat/{id}` appends a second exchange.
19. Deltas then a scripted failure → `started`, `delta`…,
    `failed{retriable, message}` for each `retriable` value, and no `reason`
    text anywhere in the body; a failed first turn leaves no convo.
20. §5 notices, each with no event bytes and nothing persisted: blank message
    and absent `message` → `400`; budget exhausted → `402` with the basis
    sentence and the D18 copy; another student's / deleted / non-UUID convo →
    `404`.
21. A body carrying two `message` values → the `400` notice; no convo and no
    provider call. An absent field, a repeated one and a non-form body each read
    the one sentence, and each is logged with its own cause. Every notice
    carries the contract's notice header.
22. A message of 100 001 characters → the `400` notice carrying `:service`'s
    length sentence; no convo, no turn and no provider call.

**`:public-web` — Markdown (`MarkdownBlocksTest`, `MarkdownInlineTest`,
`MarkdownHtmlTest`)**

21. A Kotlin translation of every block and inline case in
    `ios-app/UnicoachiOSTests/MarkdownParserTests.swift` (`:14-517`), source in
    → blocks out, plus the research's PROBE cases (7 `#`, `#tag`, `- - -`, `1)`,
    2/4/6-space depths, indented code as paragraph, nested quote literal, HTML
    block as paragraph, `~one~`, bare URL / `www.` / email autolinks, entity
    decoding, bold-in-link loses bold, image → alt text, empty link → nothing).
22. `MarkdownFixture.worstCaseReply` renders every block type.
23. Sanitisation: `<script>alert(1)</script>`, `<img src=x onerror=…>`, a
    `"><svg onload=…>` inside link text and inside a table cell, render as
    escaped text (asserted on the parsed DOM: no `script`/`img`/`svg` element,
    no `on*` attribute); `[x](javascript:…)`, `[x](JaVaScRiPt:…)`,
    `[x](data:…)`, `[x](/relative)`, `[x](//evil)` render as text with no
    anchor, as do `[x](mailto:a@b.com)` and a bare `a@b.com`; `http`/`https`
    links carry the stated `rel` and `target`.
24. Tables: a `data-label` per cell equal to its header's text; a header
    containing `"` is escaped in the attribute.

**`:public-web` — mechanisms**

25. The chat page passes the existing served-bytes CSP conformance test
    (`CspConformanceTest` walks registered pages; `/chat` is added to its set).
26. The chat stylesheet pins `100dvh` and the narrow-table stacking rule (the
    `ShellMechanismTest` style), and has a rule for every chat class the server
    renders — the composer, the notice, the list, both alignments and every
    indent depth up to `MARKDOWN_MAX_DRAWN_DEPTH` — and for each styled class
    `chat.ts` builds, read from the contract fixture (below).
27. The client contract. `src/test/resources/chat-client-contract.json` records
    the page hooks, the styled client-built classes, the notice header and one
    sample of each event. `ChatClientContractTest` asserts the SERVED chat page
    carries every hook and the SERVED stream writes exactly those events with
    those fields and kinds; `chat_test.ts` asserts the client's constants equal
    the hooks and decodes every sample field for field. Neither side can rename
    alone.
28. `CoachConfigsTest`: the server's own conf list with `coaching.model`
    overridden to an unpriced model → `CoachConfigs.read` refuses with
    `UnpricedModelsException` naming it. The reader takes only a `Config`, so
    the refusal precedes any pool or provider.

**TypeScript (`chat_test.ts`, Deno, structural fakes as in
`backToTop_test.ts`)**

27. `SseParser`: one event, several per chunk, a frame split at every byte
    offset, a trailing partial frame held until the next chunk, unknown event
    ignored. The byte split is driven through the real `ReadableStream` and
    `TextDecoder` path with multi-byte text (`café — ✓`), so a cut inside a
    character is painted whole.
28. The turn state machine: `started → delta… → completed` paints text then
    swaps HTML and updates `action` + history; `failed` removes partial text,
    shows the sentence, restores the composer text; EOF without terminal → the
    retriable failure; a redirected response → navigation to its URL; a response
    marked `X-Chat-Notice` → its body in place of the reply and the text
    restored; an unmarked non-stream response (a 503, 502, 404 or 400 page), an
    unreadable notice body and a fetch rejection → the retriable failure, never
    pasted in, each reported; a known event whose data is not a JSON object of
    its shape → dropped and reported, never thrown; a blank message sends
    nothing.
29. Send disabled while a turn is in flight and re-enabled on every terminal;
    the textarea is never disabled (asserted on the page's own `domComposer`
    over structural element fakes).
30. Scroll-follow: follows within 80 px of the bottom, stops when scrolled up.
31. The contract: the client's hooks and styled classes equal
    `chat-client-contract.json`'s, and each of its events decodes (test 27).

**Manual (D19, not a gate):** the device matrix — 375×667, 412×915, 820×1180,
1180×820, 1440×900, 1280×720 — for `/chat`, a streamed reply, a failed turn, a
wide table. Reported as an open item.

## Implementation Plan

1. **Failure copy.** Add `FailedTurnCopy.kt` + test 7; point `rest-server`'s
   `coachFailedError` at it. Verify: `nix develop -c bin/test`.
2. **Extraction into the service.** Add `ExtractionScheduler`; change
   `TurnParty.onSucceeded` to take the final request; wire `StudentTurnParty`;
   add the undefaulted constructor parameter and update every construction site
   (production and tests); strip the enqueue from `ConvoRouteHandler` and its
   constructor. Add tests 1–5. Verify: `nix develop -c bin/test` — the existing
   REST enqueue tests must pass unmodified in their assertions.
3. **Tool registry.** Add `StudentToolRegistry.standard`; replace
   `rest-server`'s inline list with it. Test 6. Verify:
   `nix develop -c
   bin/test`.
4. **Markdown.** Add `markdown/` (blocks, inline, HTML) and tests 21–24. Verify:
   `nix develop -c bin/test`.
5. **Coach wiring in `public-web`.** Gradle deps, `chat.conf`, the boot sequence
   of §3, close the provider on stop, test harness support (`WebAuthTestSupport`
   gains a `CoachingService` built over a scripted provider). Verify:
   `nix develop -c bin/test`.
6. **Pages and the door.** `CHAT_PATH`, access classes, the two `GET` routes,
   the no-student rendering, the door changes and the header link. Tests 9–15,
   25. Verify: `nix develop -c bin/test`.
7. **Stream.** The two `POST` routes: the §5 notices (with §8's copy) and the
   event stream (§6). Tests 16–20. Verify: `nix develop -c bin/test`.
8. **Client.** `chat.ts` + `chat_test.ts`, mounted in `main.ts`; chat styles in
   `site.css` (test 26); rebuild the bundle. Verify:
   `nix develop -c
   bin/build-web-assets` then
   `nix develop -c bin/build-web-assets -c`.
9. **Full suite.** `nix develop -c bin/test`, unscoped; report executed and
   failed counts. Run the page locally (`bin/public-web-up`) and send a message
   as a smoke check.

## Files Modified

- `service/src/main/kotlin/ed/unicoach/coaching/` — `CoachingService.kt`,
  `CoachTurnLoop.kt`, `FailedTurnCopy.kt` (new), `StudentToolRegistry.kt` (new),
  `extraction/ExtractionScheduler.kt` (new)
- `service/src/main/kotlin/ed/unicoach/family/SupporterCoachingService.kt`
  (`onSucceeded` signature)
- `service/src/test/kotlin/ed/unicoach/coaching/` — `CoachingServiceTest.kt` and
  new tests; every test constructing `CoachingService`
- `rest-server/src/main/kotlin/ed/unicoach/rest/` — `Application.kt`,
  `Routing.kt`, `routing/ConvoRoutes.kt`, `routing/ConversationStream.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/` — construction sites of
  `CoachingService` / `ConvoRouteHandler`
- `web-common/src/main/kotlin/ed/unicoach/web/common/http/CanonicalUuid.kt`
  (new: `canonicalUuidOrNull`, moved from `rest-server`)
- `public-web/build.gradle.kts`
- `chat/src/main/kotlin/ed/unicoach/chat/` — `ChatProvider.kt`
  (`CloseableChatProvider`), `ChatProviderFactory.kt`,
  `AnthropicChatProvider.kt`, `LogOnlyChatProvider.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/` — `Application.kt`,
  `Routing.kt`, `Forms.kt` (new: the form helpers, moved out of `Routing.kt`),
  `auth/WebAuthGate.kt`, `auth/RouteAccess.kt` (new: the access wrappers, moved
  out of `Routing.kt`), `chat/` (new: routes, the stream responder, the
  notices), `signup/GetStartedRoute.kt` (new), `markdown/` (new),
  `render/ChatPage.kt` (new), `render/GetStartedPage.kt` (new),
  `render/ErrorPages.kt`, `render/Layout.kt`
- `public-web/src/main/resources/` — `static/site.css`, `static/js/` (rebuilt
  bundle), `web-assets.properties`
- `public-web/src/main/ts/` — `chat.ts`, `chat_test.ts` (new), `main.ts`
- `public-web/src/test/resources/chat-client-contract.json` (new)
- `public-web/src/test/kotlin/ed/unicoach/web/` — `ChatRoutingTest.kt`,
  `ChatStreamTest.kt`, `ChatClientContract.kt` / `ChatClientContractTest.kt`
  (new), `ShellMechanismTest.kt`, `CoachConfigsTest.kt`, `markdown/*Test.kt`
  (new), `WebAuthTestSupport.kt`, `FakeEmailVerifier.kt`
  (`testPublicWebModule`), `CspConformanceTest.kt`, `SignInRoutingTest.kt` /
  `WebAuthGateTest.kt` (landing now `/chat`)

## Rollback

Revert the commit. Nothing is stored that did not exist before; conversations
started on the web are ordinary conversations the iOS app already lists. The
extraction move is behaviour-preserving for the REST path, so reverting it
restores the route-side enqueue with no data repair.
