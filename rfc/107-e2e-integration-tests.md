# RFC 107: End-to-end integration tests for chat and email

## Executive Summary

The chat and email subsystems are covered by unit tests that fake at the high
provider port (`ChatProvider`, `EmailProvider`), so the real provider — the wire
parser, the SSE→`ChatEvent` mapping, the SES exception→`ProviderResult` mapping
— and the real DAO writes are never exercised together against a real database.
The existing full-stack routing tests (`ConvoExtractionEnqueueTest`,
`ConvoToolLoopRoutingTest`) hand a hand-built `object : ChatProvider` to
`appModule`, bypassing everything the provider does.

This RFC adds end-to-end integration tests that fake **only at the low transport
seam** and run **real code everywhere above it**: the real
`AnthropicChatProvider` over a fake `AnthropicStreamTransport`, the real
`SesEmailProvider` over a fake `SesSendOperation`, real
`EmailService`/`CoachingService`/`ExtractionService`/ `SynthesisService`, real
DAO writes, and a real Postgres via the existing `bin/test` harness. Offline
jobs run through the real `QueueWorker`: real inbound HTTP request → real
transactional enqueue → real `JobHandler` dispatch on the claimed job → real DB
side effects.

The seam fakes and the recorded wire captures they replay are shared via the
Gradle `java-test-fixtures` plugin on `:chat` and `:email`; the fakes currently
private to each module's unit tests migrate into `src/testFixtures`
(deduplicated, no copies left behind) and become scripted, multi-response
replayers so a tool-dispatch loop's several provider calls are served from one
recorded queue.

No production wiring changes: each real provider is constructed around its fake
seam and passed through the existing injection points.

## Detailed Design

### Seams and real signatures

The fake sits at the narrowest transport boundary; everything above is real and
constructor-injected. The real provider constructors (verified in source, not
the task brief) each take the seam plus an `AutoCloseable` backing resource —
there is no `config` parameter:

- `:chat` —
  `fun interface AnthropicStreamTransport { fun stream(body: JsonObject): Flow<AnthropicTransportEvent> }`
  (real impl `KtorAnthropicStreamTransport`). Real provider:
  `AnthropicChatProvider(transport: AnthropicStreamTransport, resource: AutoCloseable)`.
  Per RFC 106 the real provider is wrapped by the real
  `LlmCallLog(provider,
  database)` — the production recorder that writes
  `llm_requests`/`llm_responses`/ `llm_responses_raw` — and it is that
  `LlmCallLog` that `appModule`, `ExtractionService`, and `SynthesisService` now
  take. Tests build
  `LlmCallLog(AnthropicChatProvider(fakeTransport, AutoCloseable {}), database)`.
  The `LlmCallLog` is real (it is a recorder these tests assert on), so this
  stays within "real everything above the transport seam"; `ChatProvider` is
  never faked.
- `:email` —
  `fun interface SesSendOperation { suspend fun send(request: SendEmailRequest): SendEmailResponse }`
  (real impl wraps `SesV2Client`). Real provider:
  `SesEmailProvider(ses: SesSendOperation, resource: AutoCloseable)`. Tests
  build `SesEmailProvider(fakeSesOp, AutoCloseable {})` and pass it to
  `EmailService`. `EmailProvider` is never faked in these tests.

### Fixtures home: `java-test-fixtures` per module

Apply the built-in `java-test-fixtures` Gradle plugin to `:chat` and `:email`.
Consumers depend via `testImplementation(testFixtures(project(":chat")))` and
`testImplementation(testFixtures(project(":email")))`. The `testFixtures` source
set does not inherit a module's `implementation` dependencies, so each fixture
set declares its own compile deps (`testFixturesImplementation`): `:chat` needs
kotlinx-coroutines-core and kotlinx-serialization-json; `:email` needs aws-sesv2
and kotlinx-coroutines-core.

#### `:chat` fixtures (`chat/src/testFixtures/kotlin/ed/unicoach/chat/`)

The three fakes currently private inside `AnthropicChatProviderTest.kt`
(`FakeTransport`, `CapturingTransport`, the `Replay` record) and the existing
`AnthropicTestFixtures` object migrate here. `AnthropicChatProviderTest` and the
transport tests are updated to consume them from testFixtures; no duplicate
definitions remain.

The transport fake becomes **scripted / multi-response**. A coaching
tool-dispatch turn makes several provider calls in one HTTP turn (call 1 →
`tool_use`, call 2 → final text after a `TOOL_RESULT` continuation), and each
call is a fresh cold-flow collection, so the fake replays a **queue** of
recorded `Replay`s — one dequeued per `stream()` call — and **captures each
received `body: JsonObject`**. Structural surface:

- `ScriptedAnthropicTransport(replays: List<Replay>) : AnthropicStreamTransport`
  — dequeues the next `Replay` per `stream()` call (throws if the script is
  exhausted, surfacing an unexpected extra provider call), records every request
  body into an ordered `bodies: List<JsonObject>`, exposes `calls: Int`.
  Subsumes the single-shot `FakeTransport`/`CapturingTransport` (a one-element
  script is the degenerate case), which are removed.
- `Replay(events: List<AnthropicTransportEvent>, throwing: Throwable? = null)` —
  the recorded emit sequence, optionally terminated by a thrown
  `AnthropicHttpException`/`IOException` (the HTTP/IO failure shapes).

`AnthropicTestFixtures` continues to expose the recorded canonical streams
(`canonicalTextReplay`, `toolUseFrames`, `thinkingFrames`, `errorFrame`,
`errorBody`, `opened`, `frame`, `sse`) plus new recorded streams this RFC needs
(below). The transport tests keep parsing the `*SseBody` forms and the provider
tests keep replaying the `*Frames` forms, so both remain in lockstep with the
same recorded bytes.

New recorded streams added to `AnthropicTestFixtures`:

- `searchCollegesToolUseFrames` — a `tool_use` block named `search_colleges` (a
  tool the real `ToolRegistry` serves) with input `{"cipPrefix":"26"}`,
  `stop_reason = "tool_use"`. Drives the chat tool loop.
- `recordExtractionFrames` — a `tool_use` block named `record_extraction`
  (`ExtractionService.RECORD_EXTRACTION_TOOL_NAME`) whose `input` object matches
  the tool's `input_schema` and `ExtractionService.parseOutput` (claims +
  observations), `stop_reason = "tool_use"`.
- `recordSynthesisFrames` — the analogous forced-tool `tool_use` stream for the
  synthesis tool (name and `input` shape read from `SynthesisService` at
  implementation), producing at least one commitment.

#### `:email` fixtures (`email/src/testFixtures/kotlin/ed/unicoach/email/`)

The `SesSendOperation` test lambdas and the port-level `FakeProvider` currently
private in
`SesEmailProviderTest.kt`/`EmailServiceTest.kt`/`EmailSendHandlerTest.kt`
migrate here; those unit tests consume them from testFixtures with no copies
left behind. Structural surface:

- `SesOutcome` — a recorded SES result: either `Response(SendEmailResponse)` or
  `Throw(Throwable)`, holding the **real SDK shapes**
  (`SendEmailResponse {
  messageId = ... }`; `MessageRejected`,
  `MailFromDomainNotVerifiedException`, `AccountSuspendedException`,
  `SendingPausedException`, `BadRequestException` for the Rejected branch;
  `TooManyRequestsException`, `LimitExceededException` for the TransientFailure
  branch).
- `ScriptedSesSendOperation(outcomes: List<SesOutcome>) : SesSendOperation` —
  dequeues one `SesOutcome` per `send()`, records each received
  `SendEmailRequest` into `requests: List<SendEmailRequest>`.
  `SesFixtures.sent(messageId)` / `.rejected(...)` / `.transient(...)` build the
  common single-outcome scripts.
- `FakeEmailProvider(outcome: ProviderResult) : EmailProvider` — the migrated
  port-level fake, retained for the existing `EmailService`/`EmailSendHandler`
  unit tests only (not used by the new seam-level integration tests).

### End-to-end depth: through the real `QueueWorker`

Offline-job tests boot the real `QueueWorker`
(`queue/src/main/kotlin/ed/unicoach/queue/QueueWorker.kt`) with the real handler
set wired to seam-faked providers, mirroring the `queue-worker` process assembly
(`queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt`). The worker
has no single-shot poll — the established pattern (`QueueWorkerTest`) is
`worker.start(scope)`, poll the `jobs` table until the target job reaches a
terminal/expected status (`COMPLETED`, `DEAD_LETTERED`, or back to `SCHEDULED`
for a retriable failure) with a bounded timeout, then `worker.stop(timeout)`. A
shared `awaitJobStatus` helper (modeled on `QueueWorkerTest.awaitStatus`) is
used. This exercises claim, execute, `job_attempts` recording, and the
complete/dead-letter/reschedule transitions — the real dispatch path, not a
direct service call.

### Test isolation and boot

Follows the canonical full-stack pattern
(`rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt`,
`ConvoExtractionEnqueueTest.kt`): JUnit5 + kotlin.test, real Postgres from
`AppConfig.load(...)` + `DatabaseConfig`, one embedded Netty server per class on
an ephemeral port (`port = 0`), a shared `HttpClient(CIO)`, and a raw JDBC
`Connection` for row assertions and per-test `TRUNCATE`.
`StubGoogleTokenVerifier` and a direct-SQL `markEmailVerified` reuse the
existing helpers. Registration uses a unique random email per test for row
isolation.

### Data models (assertion targets, not new schema)

No schema changes. Tests assert against existing tables, per the RFC 106
provider-agnostic LLM call log (which this RFC builds on):

- Chat: `convo_requests` (`system_prompt_id`, `llm_request_id`) links the turn
  to the logged call. Provider/model live on `llm_requests` (`provider`,
  `model_requested`); the response on `llm_responses` (`content`,
  `model_resolved`, `stop_reason`, the four token columns,
  `provider_request_id`), reached via
  `llm_responses.request_id = convo_requests.llm_request_id`; the verbatim
  payload on `llm_responses_raw` (RFC 106 dropped `convo_responses` /
  `convo_responses_raw`).
- Email (`0003.create-queue.sql`, email schema): `jobs` (`job_type`, `status`),
  `job_attempts` (`status`, `error_message`), `email_sends` (`status`,
  `recipient_email`, `body`, `provider`, `provider_message_id`,
  `error_message`).
- Extraction/synthesis: `extraction_runs` / `synthesis_runs` (outcome, plus an
  `llm_request_id` to the logged call; RFC 106 moved
  `provider`/`model_resolved`/ tokens onto `llm_requests`/`llm_responses`) plus
  `claims`/`observations` and commitments.

### Error handling / edge cases

- Provider terminal taxonomy is exercised through the real provider: recorded
  `error` frames and thrown `AnthropicHttpException`/`IOException` map to
  `Rejected`/`TransientFailure`, which the chat route surfaces as HTTP 500 and
  the offline services surface as `TransientFailure` → `RetriableFailure` →
  worker reschedule.
- SES exception shapes map through the real `SesEmailProvider`: Rejected →
  `email_sends` REJECTED row + `deadLetterJob`; TransientFailure → **no**
  `email_sends` row + `reschedule` (job returns to `SCHEDULED`). This is the
  current transmit-then-record behavior; tests assert it as-is and do **not**
  reorder record vs transmit.
- A script exhausted by an unexpected extra provider call throws, failing the
  test rather than silently returning a canned reply.

### Dependencies

Gradle `java-test-fixtures` (built-in). New `testFixturesImplementation` deps as
above. `:rest-server` test source gains `testFixtures(project(":chat"))` and
`testFixtures(project(":email"))`; it already depends on `:queue`, `:service`,
`:email`, `:chat`.

## Tests

New test classes under `rest-server/src/test/kotlin/ed/unicoach/rest/`. All boot
via `appModule`/real `QueueWorker` and assert against real Postgres rows.

### `ChatToDbIntegrationTest` (chat provider → DB, through Ktor routing)

Server wired with
`LlmCallLog(AnthropicChatProvider(ScriptedAnthropicTransport(script), AutoCloseable {}), database)`.

1. `a plain turn persists request, response, and raw rows with usage and stop_reason`
   — script = `[canonicalTextReplay]`. `POST /api/v1/conversations`
   (registered + verified user + student). Assert HTTP 201; one `convo_requests`
   row (`system_prompt_id` set) joined to `llm_requests`
   (`provider =
   "anthropic"`, `model_requested`); one `llm_responses` row
   (via `request_id = convo_requests.llm_request_id`) with
   `stop_reason = "end_turn"`, `model_resolved` non-null, and
   `input_tokens`/`output_tokens` matching the recorded
   `message_start`/`message_delta` usage; one `llm_responses_raw` row whose
   payload carries the recorded `id`/`content`.
2. `the request body sent to the provider maps the ChatRequest` — assert
   `transport.bodies[0]` has `model`, `max_tokens`, `stream = true`, `system`,
   and the user message content block. Confirms the real provider built the wire
   body.
3. `a tool-dispatch turn continues with a TOOL_RESULT and collapses the transcript`
   — script =
   `[Replay(opened + searchCollegesToolUseFrames), canonicalTextReplay]`. Assert
   two provider calls (`transport.calls == 2`); `transport.bodies[1]`'s last
   message is a `tool_result` block referencing the recorded `tool_use_id` (the
   TOOL_RESULT continuation); the excursion's `llm_responses` rows hold the
   ordered stop reasons (opener `tool_use`, continuation `end_turn`); the
   transcript endpoint collapses to one user + one coach message; the extraction
   enqueue (if enabled) targets the continuation request id.
4. `a provider transient failure fails the turn without a success response row`
   — script = `[Replay(opened, throwing = IOException)]`. Assert HTTP 500 and no
   `llm_responses` row with `stop_reason = "end_turn"` (a failed turn leaves no
   successful response row).

### `EmailSendE2eTest` (register → enqueue → worker → DB)

Server wired via `appModule`; a real `QueueWorker` wired with
`EmailSendHandler(EmailService(db, SesEmailProvider(ScriptedSesSendOperation(script), AutoCloseable {}), emailConfig), listOf(VerificationEmailRenderer(base)))`.
Both share the test Database.

1. `registration enqueues exactly one SEND_EMAIL job` — `POST /auth/register`;
   assert one `jobs` row `job_type = SEND_EMAIL`, `status = SCHEDULED`, payload
   `to` = the registered email.
2. `the worker sends, records a SENT row, and the body carries the raw verify token`
   — script = `[sent("m-1")]`. Start worker; await job `COMPLETED`. Assert one
   `email_sends` row `status = SENT`, `provider = "ses"`,
   `provider_message_id =
   "m-1"`; `transport.requests[0]` (the
   `SendEmailRequest`) body contains a verify URL; extract the token from it and
   assert `GET /auth/verify-email?token=<t>` succeeds — proving the transmitted
   link carried the real raw token (only its hash is persisted).
3. `a rejected send records a REJECTED row and dead-letters the job` — script =
   `[rejected(MessageRejected)]`. Await job `DEAD_LETTERED`. Assert one
   `email_sends` row `status = REJECTED`, `error_message` preserving the SES
   reason; a `job_attempts` row `status = PERMANENT_FAILURE`.
4. `a transient send writes no row and reschedules the job` — script =
   `[transient(TooManyRequestsException)]`, `maxAttempts` left default. Await
   the job back to `SCHEDULED` (rescheduled). Assert **zero** `email_sends` rows
   and a `job_attempts` row `status = RETRIABLE_FAILURE`. Documents the
   transmit-then- record hazard on the transient branch: the job is retried with
   no ledger row.
5. `a failed enqueue rolls back user creation (atomic transaction)` — wire
   `appModule` with `QueueService(database, FailingJobsDao(SEND_EMAIL))`, a
   testFixtures `JobsDao` subclass that throws on a `SEND_EMAIL` insert.
   `POST
   /auth/register`; assert HTTP 500, **no** `users` row for the email,
   and **no** `jobs` row for it — the enqueue and the user insert share one
   transaction, so a failed enqueue rolls back the user (RFC 96 atomicity,
   tested through the route).

### `OfflineCoachingE2eTest` (extraction / synthesis → DB, through the worker)

Seeds a convo with one closed coaching turn (via the chat route with a
`canonicalTextReplay` script) so extraction has a window, and the required
`system_prompts` row (existing test seed/migration). Worker wired with
`ExtractionHandler(ExtractionService(db, AnthropicChatProvider(ScriptedAnthropicTransport([Replay(opened + recordExtractionFrames)]), AutoCloseable {}), extractionConfig))`
and the analogous `SynthesisHandler`.

1. `extraction persists an extraction_runs row and claim/observation side effects`
   — the create-convo turn enqueues `EXTRACT_CONVERSATION`. Start worker; await
   job `COMPLETED`. Assert one `extraction_runs` row (`outcome` applied,
   `provider =
   "anthropic"`, `model_resolved` non-null, token usage from the
   recorded stream) and the parsed `claims`/`observations` rows for the student.
2. `synthesis persists a synthesis_runs row and commitment side effects` —
   enqueue `SYNTHESIZE_STUDENT` (via the admin trigger route or a direct
   `QueueService` enqueue on the request path); await `COMPLETED`; assert one
   `synthesis_runs` row and the commitment side effects from
   `recordSynthesisFrames`.
3. `a provider transient failure reschedules the extraction job` — extraction
   script = `[Replay(opened, throwing = IOException)]`; await the job back to
   `SCHEDULED`; assert no applied `extraction_runs` success row (a `failed` run
   may be recorded per current semantics, confirmed at implementation).

### Migrated unit tests (regression)

After migration, the existing `AnthropicChatProviderTest`,
`KtorAnthropicStreamTransportTest`, `SesEmailProviderTest`, `EmailServiceTest`,
and `EmailSendHandlerTest` compile against the testFixtures fakes and continue
to pass unchanged in behavior — proving the migration is a pure move (no copies,
no behavior drift).

## Invariants

### `chat/src/testFixtures/kotlin/ed/unicoach/chat/`

**Rule:** Integration tests fake the `AnthropicStreamTransport` seam and drive
the real `AnthropicChatProvider`; they MUST NOT fake the `ChatProvider` port.

**Why:** The provider is where the SSE wire is parsed and mapped to the
`ChatEvent`/`ChatResponse` taxonomy and the terminal classification. Faking the
port skips exactly the code these tests exist to cover, leaving the mapping
unverified while the tests still appear green.

**Rule:** The chat fixtures are wire-faithful Anthropic SSE frames, and every
fixture stream MUST decode cleanly through the real
`KtorAnthropicStreamTransport` parser (asserted in the transport tests).

**Why:** A fixture using a shape the real API cannot emit would verify the
provider against a fiction. Requiring the production parser to accept every
fixture is the enforceable fidelity guard — it does not require a live capture,
but it forbids loose approximations the parser would reject.

### `email/src/testFixtures/kotlin/ed/unicoach/email/`

**Rule:** Integration tests fake the `SesSendOperation` seam and drive the real
`SesEmailProvider`; they MUST NOT fake the `EmailProvider` port.

**Why:** The provider is where SES SDK exceptions are mapped to the
`ProviderResult` taxonomy (Sent/Rejected/TransientFailure) and the
`SendEmailRequest` is built. Faking the port skips that mapping.

**Rule:** The email fixtures replay the real SES SDK response and exception
shapes (`SendEmailResponse`, `MessageRejected`, `TooManyRequestsException`, …),
never stand-in types.

**Why:** The provider's mapping keys off the concrete SDK exception classes; a
stand-in would exercise a different branch than production and hide mapping
drift.

### `rest-server/src/test/kotlin/ed/unicoach/rest/`

**Rule:** Integration tests run against a real Postgres (the `bin/test`
harness); the database and its DAO writes are never faked or mocked.

**Why:** The point of these tests is that real DAO writes land in real tables
under real constraints. A faked database would not catch schema, constraint, or
mapping regressions and would make the end-to-end claim false.

## Implementation Plan

Each step is atomic and locally verifiable. Tests run only via
`nix develop -c bin/test` (force re-run with `-f`; confirm "N executed"), never
bare `./gradlew test`.

1. **Apply `java-test-fixtures` to `:chat`; migrate chat fakes.** Add
   `id("java-test-fixtures")` to `chat/build.gradle.kts` with
   `testFixturesImplementation` for coroutines-core and serialization-json. Move
   `AnthropicTestFixtures` and the `FakeTransport`/`CapturingTransport`/`Replay`
   fakes from `chat/src/test/.../AnthropicChatProviderTest.kt` into
   `chat/src/testFixtures/.../` as `AnthropicTestFixtures` +
   `ScriptedAnthropicTransport`/`Replay`; add the new recorded streams
   (`searchCollegesToolUseFrames`, `recordExtractionFrames`,
   `recordSynthesisFrames`). Update
   `AnthropicChatProviderTest`/`KtorAnthropicStreamTransportTest` to consume
   from testFixtures; delete the in-test copies.
   - Verify: `nix develop -c bin/test chat -f` (confirm executed count).
2. **Apply `java-test-fixtures` to `:email`; migrate email fakes.** Add the
   plugin to `email/build.gradle.kts` with `testFixturesImplementation` for
   aws-sesv2 and coroutines-core. Move the `SesSendOperation` lambdas +
   `FakeProvider` into `email/src/testFixtures/.../` as
   `ScriptedSesSendOperation`/`SesOutcome`/ `SesFixtures` + `FakeEmailProvider`.
   Update `SesEmailProviderTest`, `EmailServiceTest`, `EmailSendHandlerTest` to
   consume from testFixtures; delete the in-test copies.
   - Verify: `nix develop -c bin/test email -f`.
3. **Wire `:rest-server` test deps + shared helpers.** Add
   `testImplementation(testFixtures(project(":chat")))` and `...(":email"))` to
   `rest-server/build.gradle.kts`. Add a testFixtures
   `FailingJobsDao(failOnType)` to `:queue` (`queue/src/testFixtures/.../`;
   apply `java-test-fixtures` to `:queue`, or place it in `:rest-server` test
   source if simpler) and an `awaitJobStatus` helper.
   - Verify: `nix develop -c bin/test rest-server -f` (existing tests still
     green).
4. **Add `ChatToDbIntegrationTest`.** Implement the four tests above.
   - Verify:
     `nix develop -c bin/test rest-server --tests "ed.unicoach.rest.ChatToDbIntegrationTest" -f`.
5. **Add `EmailSendE2eTest`.** Implement the five tests above.
   - Verify:
     `nix develop -c bin/test rest-server --tests "ed.unicoach.rest.EmailSendE2eTest" -f`.
6. **Add `OfflineCoachingE2eTest`.** Implement the three tests above, reading
   the `record_extraction`/synthesis tool `input_schema` and parse code to
   author the recorded forced-tool `input` objects.
   - Verify:
     `nix develop -c bin/test rest-server --tests "ed.unicoach.rest.OfflineCoachingE2eTest" -f`.
7. **Add invariants.** Create the three `INVARIANTS.md` files named in Files
   Modified with the Rules/Whys from the Invariants section.
   - Verify: files exist; `git status` shows them.
8. **Full suite green.** `nix develop -c bin/test -f` (confirm all modules
   executed and pass). Commit.

## Files Modified

Created:

- `rfc/107-e2e-integration-tests.md`
- `chat/src/testFixtures/kotlin/ed/unicoach/chat/AnthropicTestFixtures.kt`
  (moved + extended)
- `chat/src/testFixtures/kotlin/ed/unicoach/chat/ScriptedAnthropicTransport.kt`
- `chat/src/testFixtures/kotlin/ed/unicoach/chat/INVARIANTS.md`
- `email/src/testFixtures/kotlin/ed/unicoach/email/ScriptedSesSendOperation.kt`
- `email/src/testFixtures/kotlin/ed/unicoach/email/FakeEmailProvider.kt`
- `email/src/testFixtures/kotlin/ed/unicoach/email/INVARIANTS.md`
- `queue/src/testFixtures/kotlin/ed/unicoach/queue/FailingJobsDao.kt` (if placed
  in `:queue`)
- `rest-server/src/test/kotlin/ed/unicoach/rest/ChatToDbIntegrationTest.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/EmailSendE2eTest.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/OfflineCoachingE2eTest.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/E2eSupport.kt` (shared
  `awaitJobStatus` + boot helpers)
- `rest-server/src/test/kotlin/ed/unicoach/rest/INVARIANTS.md`

Modified:

- `chat/build.gradle.kts` (apply `java-test-fixtures`, testFixtures deps)
- `email/build.gradle.kts` (apply `java-test-fixtures`, testFixtures deps)
- `rest-server/build.gradle.kts` (consume `testFixtures(project(...))`)
- `chat/src/test/kotlin/ed/unicoach/chat/AnthropicChatProviderTest.kt` (consume
  fixtures; drop private fakes)
- `chat/src/test/kotlin/ed/unicoach/chat/KtorAnthropicStreamTransportTest.kt`
  (consume fixtures)
- `email/src/test/kotlin/ed/unicoach/email/SesEmailProviderTest.kt` (consume
  fixtures)
- `email/src/test/kotlin/ed/unicoach/email/EmailServiceTest.kt` (consume
  `FakeEmailProvider` fixture)
- `email/src/test/kotlin/ed/unicoach/email/EmailSendHandlerTest.kt` (consume
  fixtures)

`system_prompts` is immutable (no `UPDATE`/`DELETE`), so the codebase's
convention for resetting it on the shared test DB is `TRUNCATE` + re-seed, and
tests that read shared prompts self-heal them with a find-or-create /
`ON CONFLICT DO NOTHING` insert (see `admin-web`'s `fitLensPromptId`).
`OfflineCoachingE2eTest` follows that convention: its `@BeforeEach` self-heals
the coach/extraction/synthesis prompts it reads, so a sibling module's truncate
cannot leave it without them. No other module's tests are changed.
