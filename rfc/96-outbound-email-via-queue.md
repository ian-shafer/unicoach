# RFC 96: Outbound Email via the Queue

## Executive Summary

Outbound email is transmitted inline on the request coroutine today:
`EmailVerificationService.sendVerificationEmail` awaits a synchronous SES call
before the HTTP response returns, best-effort after commit. This RFC moves all
transactional email — every sender, current and future — onto the existing job
queue. A request never transmits email; it enqueues a `SEND_EMAIL` job, and the
`queue-worker` process is the sole transmitter.

The job carries **intent, not rendered bytes**:
`EmailJobPayload { to, template,
context }`. `to` is the recipient captured at
enqueue; `template` selects a renderer; `context` is a self-contained JSON blob
(for verification, `{ verifyToken }`). The worker resolves the renderer,
produces subject/body, and sends through the unchanged `EmailService`, which
transmits then records the `email_sends` row (record-after-send, its existing
ordering). The worker reads no domain state, keeping `email/` a generic
transmitter decoupled from the user domain.

The enqueue is **transactional**: it joins the request's own transaction via a
new `QueueService.enqueue(session, …)`. The verification token is issued and the
job enqueued atomically; if the enqueue fails the transaction rolls back and the
request fails. Once committed, the queue owns delivery (at-least-once, existing
retry/backoff/dead-letter; no dedupe). This replaces today's silent best-effort
send.

Two producers become enqueue-only: `rest-server` (register, resend,
change-email) and `admin-web` (RFC-76 admin actions). No schema migration — the
intent rides `jobs.payload`; `email_sends` is unchanged. The RFC introduces one
durable principle in a new root `ASYNC_WORK.md`: async-capable work defaults to
the queue, and a required enqueue is part of the request transaction.

## Detailed Design

### Data Models

No database migration. The intent rides the existing `jobs.payload` (`jsonb`);
`email_sends` and the `jobs` NOTIFY trigger are unchanged.

**`JobType.SEND_EMAIL`** — one new variant in
`queue/src/main/kotlin/ed/unicoach/queue/JobType.kt`
(`SEND_EMAIL("SEND_EMAIL")`). There is exactly one email job type; email types
are distinguished by `template`, not by `JobType`.

**`EmailJobPayload`** (`email/`) — the generic job payload, `@Serializable`:

```
data class EmailJobPayload(
  val to: String,               // recipient address, captured at enqueue
  val template: EmailTemplate,  // selects the renderer
  val context: JsonObject,      // self-contained render inputs
)
```

The recipient travels in the payload rather than a `userId`/`version`: the job
must serve recipients who are not users, verification is bound to the
point-in-time address the token was issued for (not the user's current address),
and the worker stays free of domain lookups.

**`EmailTemplate`** (`email/`) — an enum of renderable templates, serialized by
constant name. `EMAIL_VERIFICATION` is the sole initial entry.

**`RenderedEmail`** (`email/`) — the render output: `EmailSubject` +
`EmailBody`.

**`VerificationEmailContext`** (`service/auth/`) — the verification template's
context, `@Serializable`: `{ verifyToken: String }`. The raw single-use token is
placed here at enqueue (it is available in the issuing transaction and stored
nowhere else); the worker renders the `?token=` link from it. This is the same
token exposure as today's rendered body, which `email_sends` already persists.

### API Contracts

**`EmailTemplateRenderer`** (`email/`) — the render port:

```
interface EmailTemplateRenderer {
  val template: EmailTemplate
  fun render(context: JsonObject): Result<RenderedEmail>
}
```

`render` returns `Result.failure` for a context that cannot be deserialized or
produces an invalid `EmailSubject`/`EmailBody` — a permanent, non-retriable
condition.

**`VerificationEmailRenderer(verifyUrlBase)`** (`service/auth/`) — implements
`EmailTemplateRenderer` for `EMAIL_VERIFICATION`. It holds the verification copy
(relocated from `EmailVerificationService.sendVerificationEmail`) and builds the
verify link `"$verifyUrlBase?token=$verifyToken"`. `verifyUrlBase` comes from
`EmailVerificationConfig` (the `emailVerification.verifyUrlBase` key of
`service.conf`), which the worker already loads.

**`EmailSendHandler(emailService, renderers)`** (`email/`) — the `JobHandler`
for `SEND_EMAIL`. It builds a `template → renderer` map (rejecting duplicates at
construction) and executes:

1. deserialize `EmailJobPayload`,
2. reconstruct the recipient via `EmailAddress.create(payload.to)`,
3. resolve the renderer for `payload.template`,
4. `renderer.render(payload.context)`,
5. `emailService.send(to, subject, body)`,
6. fold the `Result<SentEmail>` into a `JobResult`.

The worker classifies only _thrown_ exceptions (its `catch` defaults any
uncaught throwable to `RetriableFailure`); a `Result.failure` returned normally
carries no automatic classification, so — like `ExtractionHandler`, which maps
its own sealed outcome type — the handler must fold the `Result` itself. Steps
1–4 return `PermanentFailure` on their own failure (deserialize,
`EmailAddress.create`, renderer lookup, or `render`) before any send. Step 6
folds `send`'s outcome by inspecting the failure's marker interface:

- `Result.success` → `JobResult.Success`.
- `Result.failure` whose exception `is PermanentError`
  (`EmailRejectedException`, `EmailConfigException`) →
  `JobResult.PermanentFailure`.
- `Result.failure` whose exception `is TransientError`
  (`EmailDeliveryException`) → `JobResult.RetriableFailure`.
- `Result.failure` carrying an **unmarked** exception (e.g. a raw DB error from
  the `email_sends` write after a successful provider send) → **rethrow**, so
  the worker's `catch` default maps it to `RetriableFailure`. The handler never
  invents a classification for an exception it does not recognize; the root
  cause reaches the worker log unaltered.

`config` is
`JobTypeConfig(concurrency = 4, maxAttempts = 5, lockDuration =
2.minutes, executionTimeout = 30.seconds)`:
a send is a short network call, and distinct jobs are independent, so several
run in parallel.

**`QueueService.enqueue(session, jobType, payload, maxAttempts?, delay?)`**
(`queue/`) — a session-threaded overload alongside the existing
connection-owning `enqueue`. It inserts via `JobsDao.insert(session, …)` on the
caller's open `SqlSession`, so the job commits and rolls back with the caller's
transaction, and returns the existing `EnqueueResult`. The connection-owning
overload stays for best-effort fire-and-forget callers (extraction).

**`EmailVerificationService`** (`service/auth/`) — swaps its `EmailService`
dependency for `QueueService`. `sendVerificationEmail(to, rawToken)` is replaced
by:

```
fun enqueue(session: SqlSession, to: EmailAddress, rawToken: String): Result<Unit>
```

which builds the `VerificationEmailContext`, serializes an `EmailJobPayload`,
and calls the session-threaded `QueueService.enqueue`, mapping
`EnqueueResult.DatabaseFailure` to `Result.failure`. It performs no rendering.
`issueToken` is unchanged. `resend` issues the token and calls `enqueue` inside
its existing `withConnection` block; the `AlreadyVerified` branch enqueues
nothing.

**`AuthService.register` / `changeEmail`** — the enqueue moves _inside_ the
existing `withConnection` transaction, immediately after `issueToken`, and its
`Result` is unwrapped with `getOrThrow()`. The post-commit best-effort
`sendVerificationEmail` blocks are deleted. A failed enqueue therefore aborts
the transaction: no user/email change is committed, and the route's existing
`getOrThrow()` surfaces it as a 500.

### Error Handling / Edge Cases

The step-6 fold (API Contracts) is the source of truth for marker → `JobResult`;
this section maps the upstream SES conditions to those markers and states each
outcome's `email_sends` side-effect (verified against `SesEmailProvider` and
`EmailExceptions.kt`):

- **Sent** — `EmailService` writes the `SENT` `email_sends` row and the fold
  returns `Success`; the retry engine is idle.
- **Transient SES failure** — throttle, `TooManyRequests`, `LimitExceeded`,
  timeout, 5xx, or unknown → `EmailDeliveryException : TransientError` → (per
  the fold) `RetriableFailure`. No `email_sends` row is written; the queue
  reschedules with exponential backoff and dead-letters at `maxAttempts`. This
  is the only path that engages the retry engine.
- **Permanent SES rejection** — `MessageRejected`, `MailFromDomainNotVerified`,
  `AccountSuspended`, `SendingPaused`, `BadRequest` → `EmailRejectedException`;
  plus `EmailConfigException` (bad `defaultFrom`) — both `PermanentError` →
  `PermanentFailure`, dead-lettered on the first attempt. For a rejection
  `EmailService` has already written the `REJECTED` `email_sends` row;
  re-sending identical bytes cannot change the outcome. **Edge:** if that
  `REJECTED` row write itself fails, `recordRejected` returns the raw DB failure
  in preference to `EmailRejectedException`, so the outcome is the unmarked
  failure below (→ `RetriableFailure`), not `PermanentFailure` — a benign
  duplicate attempt on a permanent rejection (the retry re-sends and is rejected
  again). Rejections are thus _usually_, not unconditionally, permanent.
- **Handler-internal permanent errors** — malformed payload, unresolvable
  template, `to` failing `EmailAddress.create`, or a `render` failure (steps
  1–4) — hit the fold's steps-1–4 `PermanentFailure` before any SES call, no
  `email_sends` row. This is poison-message protection: a structurally broken
  job cannot wedge a worker.
- **Unmarked failure** — a DB write of the `email_sends` row failing _after_ a
  successful SES send (or the rejected-row-write edge above) surfaces as a raw,
  unmarked exception; the fold's rethrow branch defers to the worker's `catch`
  default (`RetriableFailure`). The retry re-runs the send, which may transmit
  the email a second time.

At-least-once delivery admits **duplicate sends** on three seams — the unmarked
DB-failure retry above, a worker crash after SES accepts but before the outcome
write (recovered by `stuckJobReaper`, RUNNING → SCHEDULED), and an execution
timeout firing while an SES call actually succeeded. No dedupe is added; this is
the accepted contract. The duplicate is benign for the sole current template:
the verification link is idempotent (the same single-use token in each copy;
verifying once burns it, and a second identical email verifies to the same
state), so a recipient receiving the email twice suffers no incorrect outcome.

**Integration-test token extraction.** The rest-server integration tests are
deliberately scoped to **enqueue only**: they neither boot a `QueueWorker` nor
poll `email_sends`. Because the enqueue is transactional, the `SEND_EMAIL` job
is present in the `jobs` table the instant the enqueueing HTTP response returns,
so the test reads the raw token straight from the enqueued payload's
`context.verifyToken` — where `EmailVerificationService.enqueue` placed it, the
same raw token the worker would render into the verify link — with no polling
(helper: `rest-server/.../EnqueuedVerificationEmail.kt`). The
`resend already-verified → no new email to that recipient` assertion is a
recipient-scoped `COUNT(*)` against the `jobs` table, not `email_sends`.
End-to-end delivery testing — the worker transmitting and the `email_sends` row
being recorded — is explicitly **deferred to a future RFC**.

**Out of scope (documented, not built):**

- **Config-error classification.** `EmailConfigException` stays a
  `PermanentError`, so a bad `email.defaultFrom` dead-letters every email job
  until the config is fixed and the dead-letters are manually requeued.
  Reclassifying it to park-and-drain belongs to `EmailExceptions.kt` and affects
  its markers globally; not changed here.
- **Dead-letter visibility.** Permanent rejections and exhausted retries land in
  `DEAD_LETTER` with no alerting. Observability on the dead-letter set is a
  separate concern.

### Dependencies

- `queue-worker` gains a compile dependency on `:email` and adds `email.conf` to
  its `AppConfig.load` list; it is the only process that now constructs
  `EmailService`.
- **`EmailSendHandler` is always registered, ungated.** Unlike the
  `extractionConfig.enabled`/`synthesisConfig.enabled` switches (email
  verification is not an optional feature — registration is broken without it),
  it registers unconditionally, like `SessionExpiryHandler`. When
  `EMAIL_PROVIDER` is unset the packaged `provider = "log"` default is used, so
  the worker still boots and the handler runs — it logs instead of transmitting
  (the same silent-drop hazard the multi-env note below covers). There is no
  `email.enabled` gate.
- `admin-web` gains a compile dependency on `:queue` to construct
  `QueueService`.
- **Module graph for `EmailJobPayload` (lives in `email/`).** `service/` already
  declares `implementation(project(":email"))` and
  `implementation(project(":queue"))`, so `EmailVerificationService` builds the
  payload and calls `QueueService` with no new edge. `admin-web` today declares
  `implementation(project(":email"))` directly only to construct
  `EmailConfig`/`EmailProviderFactory`/`EmailService` in `Application.kt` and
  `AdminTestSupport`; step 9 deletes all of those and drives resend through
  `EmailVerificationService.resend` (a `:service` symbol that reaches the
  payload transitively), leaving admin-web with no direct `:email` reference.
  The direct `:email` edge is therefore **removed**, and `:queue` is added to
  construct `QueueService`. `queue-worker` already declares
  `implementation(project(":service"))` (so `VerificationEmailRenderer` in
  `service/auth/` is reachable) and adds `:email` directly for
  `EmailSendHandler`/ `EmailService` — Gradle `implementation` is
  non-transitive, so the direct edge is required even though `:service` already
  pulls `:email`. The `build.gradle.kts` deltas are `admin-web` (+`:queue`,
  −`:email`), `queue-worker` (+`:email`), `email` (adds the
  `kotlin.serialization` plugin — `EmailJobPayload` is `@Serializable` — and an
  `implementation(project(":queue"))` edge, since the payload's enqueue types
  live in `:queue`), and `service` (adds the `kotlin.serialization` plugin for
  `VerificationEmailContext`). All four are required by the Detailed Design.
- **Multi-env (RFC-87):** `queue-worker` is a new consumer of `email.*`. Its
  production environment must carry `EMAIL_PROVIDER=ses`, `EMAIL_DEFAULT_FROM`,
  and SES credentials in the same overlay that supplies them to `rest-server`
  today; absent the overlay, the packaged default `provider = "log"` silently
  drops real mail. `verifyUrlBase` derives from
  `${APP_DOMAIN}`/`${PUBLIC_WEB_PORT}` in `service.conf`, which the worker
  already loads and resolves, so the worker renders the same link the request
  path would have.

## Tests

**`queue/` — `JobTypeTest`**

- `SEND_EMAIL round-trips through fromValue`: `JobType.fromValue("SEND_EMAIL")`
  is `SEND_EMAIL` and its `value` is `"SEND_EMAIL"`.

**`queue/` — `QueueServiceTest`**

- `enqueue(session) inserts a SCHEDULED SEND_EMAIL job on the caller's
  connection`:
  within a `withConnection`, the overload returns `EnqueueResult.Success` and
  the row is present with the given payload.
- `enqueue(session) rolls back with the surrounding transaction`: enqueue then
  throw inside one `withConnection`; after rollback, no matching `jobs` row
  exists — proving the job shares the caller's transaction.

**`email/` — `EmailJobPayloadTest`**

- `EmailJobPayload round-trips through JsonObject`: encode → `deserialize`
  yields an equal `to`/`template`/`context`.

**`email/` — `EmailSendHandlerTest`** (real `EmailService` + real DB; provider
stubbed per case)

- `valid job sends, records SENT, returns Success` (`LogOnlyEmailProvider`):
  `JobResult.Success` and one `email_sends` `SENT` row whose body carries the
  rendered link.
- `permanent rejection records REJECTED and returns PermanentFailure` (stub →
  `ProviderResult.Rejected`): `PermanentFailure` and a `REJECTED` row.
- `transient failure returns RetriableFailure and writes no row` (stub →
  `ProviderResult.TransientFailure`): `RetriableFailure`, zero `email_sends`
  rows.
- `malformed payload returns PermanentFailure`: a `JsonObject` missing fields;
  no SES call.
- `unresolvable template returns PermanentFailure`: handler built with an empty
  renderer list; a well-formed `EMAIL_VERIFICATION` payload yields
  `PermanentFailure`.
- `invalid recipient returns PermanentFailure`: `to = "not-an-email"`; no SES
  call.
- `DB write failing after a successful send yields RetriableFailure`: provider
  stubbed → `ProviderResult.Sent`, but the `email_sends` insert is made to fail
  (an `EmailService` over a `Database` whose connection rejects the insert —
  e.g. a dropped/renamed `email_sends` table, or a `Database` stub whose
  `withConnection` throws). `EmailService.send` returns a `Result.failure`
  carrying the raw, unmarked DB exception; the handler rethrows it and the
  worker's default maps it to `RetriableFailure`. This is the duplicate-send
  seam from Error Handling — asserting `RetriableFailure` here pins the
  rethrow-on-unmarked branch of the step-6 fold.

**Duplicate-send seams — not tested, deliberately.** Of the three at-least-once
duplicate seams (Error Handling), only the unmarked DB-failure retry is covered,
by the `EmailSendHandlerTest` case above (it maps to `RetriableFailure` at the
handler boundary). The other two — a worker crash between SES-accept and the
outcome write (recovered by the existing `stuckJobReaper`, RUNNING → SCHEDULED)
and an `executionTimeout` firing while the SES call actually succeeded — are
properties of the unchanged queue engine, not of this RFC's code; reproducing
them would require injecting a mid-`send` crash or a race, which no unit or
integration harness here exercises. They are left untested because the duplicate
they produce is benign (the idempotent verification link, per Error Handling),
so there is no behavior to assert beyond "the same email may arrive twice."

**`service/auth/` — `VerificationEmailRendererTest`**

- `renders fixed subject, body, and verify link`:
  `render({ verifyToken: "abc"
  })` with a known `verifyUrlBase` yields the
  fixed subject and a body containing `"$verifyUrlBase?token=abc"` and the
  welcome copy.
- `missing verifyToken returns failure`: `render` of an empty context is
  `Result.failure` (mapped by the handler to `PermanentFailure`).

**`service/auth/` — `EmailVerificationServiceTest`** (rewritten onto
`QueueService`)

- `enqueue inserts a SEND_EMAIL job carrying the token`: inside a
  `withConnection`, `issueToken` then `enqueue`; a `SEND_EMAIL` `jobs` row
  exists with `template = EMAIL_VERIFICATION`, `to =` the user email, and
  `context.verifyToken =` the issued raw token.
- `enqueue failure rolls back the issued token`: with a failing enqueue, the
  surrounding `withConnection` rolls back — no verification token persists and
  the method returns `Result.failure`.
- `resend for a verified user enqueues nothing`: `ResendResult.AlreadyVerified`
  and zero `SEND_EMAIL` rows.
- `resend burns prior tokens, issues one, enqueues one job`: exactly one
  outstanding token and one `SEND_EMAIL` row.

**`service/auth/` — `AuthServiceTest`** (updated)

- `register enqueues one SEND_EMAIL job and sends nothing inline`: one
  `SEND_EMAIL` row addressed to the registrant; zero `email_sends` rows.
- `register rolls back the user and token when the enqueue fails`: with a
  failing enqueue, `register` returns failure and neither the user nor a
  verification token is persisted (the behavioral change from today's
  best-effort send).
- `changeEmail enqueues a job to the new address and burns prior tokens`: one
  `SEND_EMAIL` row to the new address and exactly one outstanding token.

**`service/auth/` — `GoogleAuthServiceTest`** (updated): construction rewired to
`QueueService`; the Google path (pre-verified) enqueues no `SEND_EMAIL` job.

**`rest-server/` — `EmailVerificationRoutingTest`** (updated): proves **enqueue
only** — it does not boot a `QueueWorker` or observe delivery (`email_sends`).
It asserts the request commits a `SEND_EMAIL` job to the `jobs` table and, where
a verify flow needs the raw token, sources it from that job's
`context.verifyToken` (via `EnqueuedVerificationEmail`).

- `change-email then verify the new address end-to-end reports verified`: reads
  the new address's raw token from the enqueued `SEND_EMAIL` payload
  (`context.verifyToken`), then drives the `/verify-email` endpoint and asserts
  it reports `emailVerified=true` for the new address.
- `resend already-verified returns 204 enqueuing no new email to that
  recipient`:
  a recipient-scoped `COUNT(*)` against the `jobs` table (not `email_sends`) is
  unchanged across the already-verified resend.
- Tests that insert their own token (full loop, expired, reuse, bogus, 405s) are
  behaviorally unchanged.

**`admin-web/` — `AdminTestSupport` / `UsersResourceTest`** (updated): wiring
rebuilt on `QueueService`; the admin resend action asserts a `SEND_EMAIL` job is
enqueued to the target user.

## Invariants

This RFC introduces two related durable rules, recorded together. They are
cross-cutting — they govern every request-handling surface (`rest-server`,
`admin-web`, future ones) rather than a single directory — so they are recorded
in a new **repo-root `ASYNC_WORK.md`** rather than a per-directory
`INVARIANTS.md`. This placement is a deliberate, human-gated deviation from the
per-directory convention, approved as part of this RFC. They complement, and do
not contradict, the existing `rest/routing/INVARIANTS.md` rule for best-effort
side-effects: that rule governs _recoverable_ fire-and-forget work (extraction);
these govern async-capable work in general, and _required_ side-effects (email)
in particular.

### Async-capable work defaults to the queue

**Rule:** Work that can be performed asynchronously — third-party network I/O
(email/SES and the like) or any latency-unbounded side-effect — strongly
defaults to the queue and MUST NOT run inline on the request coroutine absent a
specific, documented reason it must (e.g. its result is required for the
response to be correct). The queue is the default; an inline async-capable path
carries the burden of justification.

**Why:** Inline third-party I/O binds a request's latency and success to a
dependency that is not part of its contract; a slow or failing provider then
degrades or fails a request that has otherwise succeeded. The queue provides the
retry, backoff, and timeout isolation the request path cannot.

**Target:** `ASYNC_WORK.md` (repo root).

### A required enqueue is part of the request transaction

**Rule:** When an enqueued side-effect is required (not best-effort), its
enqueue MUST occur inside the request's database transaction, and the request
MUST fail if the enqueue fails. Once the transaction commits, the request MUST
NOT wait on or depend on the job's execution.

**Why:** Enqueuing in-transaction makes the side-effect intent atomic with the
state that triggers it — no committed user without its verification-email job,
and none of that job without the committed user — and gives the enqueue the same
fate as the row it accompanies. Waiting on execution would reintroduce the
inline coupling the queue exists to remove; at-least-once delivery makes
completion the queue's responsibility, not the request's.

**Target:** `ASYNC_WORK.md` (repo root).

## Implementation Plan

Each step is independently compilable and testable. Tests are forced to re-run
(`-f`) and the executed count is confirmed, per the queue caching behavior.

1. **`SEND_EMAIL` job type.** Add the variant to `JobType.kt`; extend
   `JobTypeTest`.
   - Verify:
     `nix develop -c bin/test queue -f --tests "ed.unicoach.queue.JobTypeTest"`

2. **Session-threaded enqueue.** Add the `QueueService.enqueue(session, …)`
   overload; extend `QueueServiceTest` with the insert-on-session and
   rollback-with-transaction cases.
   - Verify:
     `nix develop -c bin/test queue -f --tests "ed.unicoach.queue.QueueServiceTest"`

3. **Generic email job types.** Add `EmailTemplate`, `RenderedEmail`,
   `EmailTemplateRenderer`, and `EmailJobPayload` to `email/`; add
   `EmailJobPayloadTest`. Update the stale "module stays unwired" comment on
   `EmailProviderFactory.kt`.
   - Verify:
     `nix develop -c bin/test email -f --tests "ed.unicoach.email.EmailJobPayloadTest"`

4. **Email send handler.** Add `EmailSendHandler`; add `EmailSendHandlerTest`.
   - Verify:
     `nix develop -c bin/test email -f --tests "ed.unicoach.email.EmailSendHandlerTest"`

5. **Verification renderer.** Add `VerificationEmailContext` and
   `VerificationEmailRenderer` to `service/auth/`; add
   `VerificationEmailRendererTest`.
   - Verify:
     `nix develop -c bin/test service -f --tests "ed.unicoach.auth.VerificationEmailRendererTest"`

6. **Rework `EmailVerificationService`.** Swap `EmailService` → `QueueService`;
   replace `sendVerificationEmail` with `enqueue`; enqueue in `resend`'s
   transaction. Rewrite `EmailVerificationServiceTest`.
   - Verify:
     `nix develop -c bin/test service -f --tests "ed.unicoach.auth.EmailVerificationServiceTest"`

7. **Rework `AuthService`.** Move the enqueue into the `register`/`changeEmail`
   transactions; delete the post-commit send blocks. Update `AuthServiceTest`
   and `GoogleAuthServiceTest`.
   - Verify: `nix develop -c bin/test service -f`

8. **Rewire `rest-server`.** Drop `EmailProvider`/`EmailService`/`EmailConfig`
   construction and the `email.conf` load in `Application.kt`, and drop the
   `emailService` parameter from `appModule`; construct
   `EmailVerificationService` with `queueService`. Rework
   `EmailVerificationRoutingTest` to assert the enqueue: query the `jobs` table
   for the `SEND_EMAIL` job and source the token from its payload — no worker
   boot, no `email_sends` poll; scope the resend assertion to the recipient.
   Removing the `emailService` parameter breaks every `appModule` caller: update
   the five test bootstraps that construct `EmailService` —
   `EmailVerificationGateTest`, `CallerResolutionTest` (also constructs
   `EmailVerificationService` directly), `ConvoStreamErrorRoutingTest`,
   `ConvoExtractionEnqueueTest`, and `ConvoToolLoopRoutingTest` — dropping the
   `EmailService` construction and, where present, rewiring the direct
   `EmailVerificationService` onto `QueueService`.
   - Verify: `nix develop -c bin/test rest-server -f`

9. **Rewire `admin-web`.** In `build.gradle.kts` add
   `implementation(project(":queue"))` and remove the now-unused
   `implementation(project(":email"))`; in `Application.kt` drop the
   `EmailService`/provider construction and `email.conf` load, construct
   `QueueService`, wire it into `EmailVerificationService`. Update
   `AdminTestSupport` and `UsersResourceTest`.
   - Verify: `nix develop -c bin/test admin-web -f`

10. **Wire the worker.** Add `implementation(project(":email"))` to
    `queue-worker`; add `email.conf` to its `AppConfig.load`; construct
    `EmailConfig`/provider/`EmailService`, construct
    `EmailVerificationConfig.from(config)` (its `verifyUrlBase` — `service.conf`
    is already loaded), build the renderer list
    (`VerificationEmailRenderer(verifyUrlBase)`), and `add(EmailSendHandler(…))`
    to the `handlers` list **unconditionally** (no `enabled` gate, alongside
    `SessionExpiryHandler`), and close the provider on shutdown when
    `AutoCloseable`.
    - Verify: `nix develop -c ./gradlew :queue-worker:compileKotlin`

11. **Record the invariant.** Create repo-root `ASYNC_WORK.md` with the two
    rules from the Invariants section; add a one-line pointer to it from
    `CLAUDE.md`.
    - Verify: `nix develop -c bin/format -c`

12. **Full gate.** Format and the whole suite.
    - Verify: `nix develop -c bin/format -c && nix develop -c bin/test -f`

## Files Modified

**Created**

- `ASYNC_WORK.md`
- `email/src/main/kotlin/ed/unicoach/email/EmailTemplate.kt`
- `email/src/main/kotlin/ed/unicoach/email/RenderedEmail.kt`
- `email/src/main/kotlin/ed/unicoach/email/EmailTemplateRenderer.kt`
- `email/src/main/kotlin/ed/unicoach/email/EmailJobPayload.kt`
- `email/src/main/kotlin/ed/unicoach/email/EmailSendHandler.kt`
- `email/src/test/kotlin/ed/unicoach/email/EmailJobPayloadTest.kt`
- `email/src/test/kotlin/ed/unicoach/email/EmailSendHandlerTest.kt`
- `service/src/main/kotlin/ed/unicoach/auth/VerificationEmailContext.kt`
- `service/src/main/kotlin/ed/unicoach/auth/VerificationEmailRenderer.kt`
- `service/src/test/kotlin/ed/unicoach/auth/VerificationEmailRendererTest.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/EnqueuedVerificationEmail.kt` —
  test seam observing the enqueued `SEND_EMAIL` job (count + token), replacing
  the old `email_sends` poll.
- `service/src/test/kotlin/ed/unicoach/auth/FailingJobsDao.kt` — a `JobsDao`
  whose `insert` returns `DatabaseFailure`, driving the enqueue-failure/rollback
  paths.
- `service/src/test/kotlin/ed/unicoach/auth/SendEmailJobQueries.kt` — helpers
  asserting the enqueued `SEND_EMAIL` `jobs` rows.

**Modified**

- `CLAUDE.md` — pointer to `ASYNC_WORK.md`.
- `queue/src/main/kotlin/ed/unicoach/queue/JobType.kt` — add `SEND_EMAIL`.
- `queue/src/main/kotlin/ed/unicoach/queue/QueueService.kt` — session-threaded
  `enqueue` overload.
- `queue/src/main/kotlin/ed/unicoach/queue/dao/JobsDao.kt` — made `open` (class
  and `insert`) as an injectable test seam for the enqueue-failure paths.
- `queue/src/test/kotlin/ed/unicoach/queue/JobTypeTest.kt`
- `queue/src/test/kotlin/ed/unicoach/queue/QueueServiceTest.kt`
- `email/build.gradle.kts` — add the `kotlin.serialization` plugin and
  `implementation(project(":queue"))`.
- `service/build.gradle.kts` — add the `kotlin.serialization` plugin.
- `email/src/main/kotlin/ed/unicoach/email/EmailExceptions.kt` — add the
  poison-message exception types the handler's steps-1–4 `PermanentFailure`
  raises (`InvalidRecipientException`, `UnresolvableTemplateException`,
  `EmailRenderException`, `RenderValidationException`), each carrying its typed
  `ValidationError`/root cause.
- `email/src/main/kotlin/ed/unicoach/email/EmailProviderFactory.kt` — stale
  "unwired" comment.
- `service/src/main/kotlin/ed/unicoach/auth/EmailVerificationService.kt`
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt`
- `service/src/test/kotlin/ed/unicoach/auth/EmailVerificationServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/auth/GoogleAuthServiceTest.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — drop the
  `emailService` parameter from `appModule`; construct
  `EmailVerificationService` with `queueService`.
- `rest-server/src/test/kotlin/ed/unicoach/rest/EmailVerificationRoutingTest.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/EmailVerificationGateTest.kt`
  — its `moduleWith` helper constructs `EmailService` and passes it to
  `appModule`; drop that construction.
- `rest-server/src/test/kotlin/ed/unicoach/rest/auth/CallerResolutionTest.kt` —
  constructs `EmailService` and `EmailVerificationService(db, emailService, …)`
  directly; rewire onto `QueueService`.
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoStreamErrorRoutingTest.kt`
  — constructs `EmailService` and passes it to `appModule`; drop that
  construction.
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoExtractionEnqueueTest.kt` —
  same (two `appModule` call sites).
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoToolLoopRoutingTest.kt` —
  same.
- `admin-web/build.gradle.kts` — add `:queue`, remove `:email`.
- `admin-web/src/main/kotlin/ed/unicoach/admin/Application.kt`
- `admin-web/src/test/kotlin/ed/unicoach/admin/AdminTestSupport.kt`
- `admin-web/src/test/kotlin/ed/unicoach/admin/resources/UsersResourceTest.kt`
- `queue-worker/build.gradle.kts` — add `:email`.
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt`
