# Research: where a person's data lands that no account-keyed walk reaches

Brief 0002 (account data deletion). Companion to the account-keyed table map;
this report covers only what a walk that starts at `users` / `students` /
`supporters` and follows foreign keys **cannot see**.

Base: `main@00bdeb7e`, read in the worktree
`/Users/ian/Work/unicoach-docs-0002-account-data-deletion`. Paths below are
relative to that root; `file:line` cites the line as it stands at that SHA.

## Method

Read-only. Schema facts come from `db/schema/*.sql` (applied order in
`db/schema/ORDER`), confirmed against the Kotlin that writes each table (`grep`
over `service/`, `db/`, `queue/`, `email/`, `rest-server/`, `queue-worker/`).
Operational facts come from `infra/*.tf`, `infra/files/*`, `bin/*` and the
`.env*` files. Client facts come from `ios-app/UnicoachiOS/*.swift`. No Gradle,
no tests, no database was run; nothing was fetched from the web. Where a fact
lives outside the repo (the live RDS backup window) I say so rather than guess.

Two conventions used throughout:

- **owner row** — a domain row that carries `llm_request_id` and a person key
  (`convo_requests`, `extraction_runs`, `synthesis_runs`, `fit_lens_runs`,
  `supporter_llm_costs`). The call log itself carries no person key by design.
- **forward FK** — the owner row points _at_ the log row. A cascade that starts
  at the person deletes the owner row and leaves the log row standing. This is
  the shape of every branch in §1 and is why they are invisible to an FK walk.

---

## 1. The LLM call log

### 1a. What the three tables hold

`db/schema/0038.create-llm-call-log.sql`:

| table               | column                                             | what it is                                                                                    | cite       |
| ------------------- | -------------------------------------------------- | --------------------------------------------------------------------------------------------- | ---------- |
| `llm_requests`      | `system TEXT`                                      | the full system text sent, verbatim                                                           | 0038:35    |
| `llm_requests`      | `content JSONB`                                    | the whole message array sent (every user turn, every prior assistant turn, every tool result) | 0038:36    |
| `llm_requests`      | `tools JSONB`, `tool_choice JSONB`, `params JSONB` | tool schemas / forcing / vendor params (no personal data)                                     | 0038:38-40 |
| `llm_responses`     | `content JSONB`                                    | the assistant blocks returned, incl. `tool_use` inputs the model chose                        | 0038:81    |
| `llm_responses`     | `provider_request_id`, token counts, `latency_ms`  | provider ids and usage                                                                        | 0038:84-91 |
| `llm_responses`     | `cost_nanodollars`, `cost_is_estimated`            | the frozen per-call cost                                                                      | 0041:21-24 |
| `llm_responses_raw` | `payload JSONB`                                    | the provider's raw response body, verbatim                                                    | 0038:126   |

Keys: `llm_requests.id` (identity); `llm_responses.request_id` →
`llm_requests(id)` `ON DELETE CASCADE`, `UNIQUE` (0038:76-77);
`llm_responses_raw.response_id` → `llm_responses(id)` `ON DELETE CASCADE`
(0038:124). **No** `student_id`, `convo_id`, `user_id` or `supporter_id` on any
of the three; the header says so explicitly (0038:5-8).

All three carry `BEFORE UPDATE` and `BEFORE DELETE` raising triggers
(0038:53-56, 112-115, 129-132) using the shared `prevent_log_update` /
`prevent_log_delete` from 0006 (whose message is "prune by partition/retention",
0006:35). The `ON DELETE CASCADE` on the two child tables is therefore inert
today: a delete of the parent is refused before it can cascade.

**What the text actually contains.** Since migration 0039 the call log is the
_only_ copy of the conversation text.
`0039.convo-requests-onto-llm-call-log.sql` dropped `convo_requests.content`
(0039:38-42) and dropped `convo_responses` and `convo_responses_raw` outright
(0039:50-51). `convo_requests` now holds `convo_id`, `kind`, `turn_id` and the
pointer `llm_request_id` — no words. The student's words, the coach's words, and
the tool results (college list, money profile, cost tables as the tools returned
them) exist only in `llm_requests.content` / `llm_responses.content` /
`llm_responses_raw.payload`. The per-turn `system` text is composed from the
student's own state for a student turn (`CoachTurnLoop.kt:381-383` passes
`turn.system`; the composition itself was not read in detail here) and, for a
parent turn, from the **student's name, relationship and shared college list**
(§5).

### 1b. Every attribution path (owner row → person)

All owner rows point _at_ `llm_requests` with a plain `REFERENCES` and no
`ON DELETE` clause, so a delete of the log row would be refused by the FK as
well as by the trigger; and a delete of the owner row leaves the log row.

| owner row                             | FK to log                 | person key                                                                                                                                    | cite                                 |
| ------------------------------------- | ------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| `convo_requests.llm_request_id`       | NOT NULL, no ON DELETE    | `convo_id` → `convos.student_id` (ON DELETE CASCADE from students, 0006:57) **and**, since RFC 258, `convos.supporter_id` with `convos.party` | 0039:31-32; `convos-party.sql:18-30` |
| `extraction_runs.llm_request_id`      | NOT NULL                  | `extraction_runs.student_id`                                                                                                                  | 0040:29-30                           |
| `synthesis_runs.llm_request_id`       | NOT NULL                  | `synthesis_runs.student_id`                                                                                                                   | 0040:47-48                           |
| `fit_lens_runs.query_llm_request_id`  | NOT NULL                  | `fit_lens_runs.student_id` (ON DELETE CASCADE, 0031:88)                                                                                       | 0040:70-71                           |
| `fit_lens_runs.reason_llm_request_id` | NULL                      | same row — **two ids per run**, the second present only when a reason call ran                                                                | 0040:72                              |
| `supporter_llm_costs.llm_request_id`  | PRIMARY KEY, no ON DELETE | `supporter_llm_costs.supporter_id` → `supporters(id)`, no ON DELETE                                                                           | `supporter-llm-cost.sql:20-26`       |

That is the complete set:
`grep -l "llm_requests\|llm_responses" db/schema/*.sql` returns only 0038, 0039,
0040, 0041, `supporter-llm-cost.sql` and `convos-party.sql`. Nothing else
references `llm_requests(id)` or `llm_responses(id)`. The orphan query in
`LlmCallsDao.listUnlinkedCalls`
(`db/src/main/kotlin/ed/unicoach/db/dao/LlmCallsDao.kt:425-432`) anti-joins
exactly these five tables (six columns), which is the code's own statement of
the same set.

**Note on parent turns.** A parent's convo row has `party = 'supporter'` and
`supporter_id` set, but **also** `student_id` = the student it is about
(`convos-party.sql:5-7`: "`convos.student_id` keeps its meaning of 'about
whom'"). So a parent's `convo_requests` rows are reachable from _both_ people:
from the student via `convos.student_id` (cascade) and from the parent via
`convos.supporter_id` (no cascade). The cost views split them by `party`
(`convos-party.sql:84-88`), but a deletion walk has to decide which person owns
the _text_. See §5.

### 1c. Which caller writes which owner row — and when it writes none

Only two places construct a raw `ChatProvider`
(`rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt:191`,
`queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt:132`); both wrap
it in `LlmCallLog` at once, so every provider call opens an `llm_requests` row
(`service/src/main/kotlin/ed/unicoach/coaching/LlmCallLog.kt:25-31, 41-47`). The
log guarantees one `llm_responses` row per request (LlmCallLog.kt:42-47); it
does **not** write any owner row — "the caller writes its own domain/run row
afterward in its own transaction" (LlmCallLog.kt:36-37).

| caller                                    | log entry point   | owner row written                                                                                   | written where / when                                                   | cite                                                 |
| ----------------------------------------- | ----------------- | --------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- | ---------------------------------------------------- |
| student chat turn, opener                 | `recordStreaming` | `convo_requests` (kind `user`)                                                                      | _after_ the `llm_requests` row is committed, in a separate transaction | `CoachTurnLoop.kt:162-165`, `230-244`                |
| student chat turn, tool-loop continuation | `recordStreaming` | `convo_requests` (kind `tool_result`)                                                               | same                                                                   | `CoachTurnLoop.kt:199-201`                           |
| parent chat turn (RFC 258)                | same loop         | `convo_requests` **and** `supporter_llm_costs`, in the same transaction as the `convo_requests` row | `SupporterCoachingService.kt:65-68`, `362-367` (`onRequestAppended`)   |                                                      |
| extraction                                | `record`          | `extraction_runs`                                                                                   | only on a `Completed` terminal (parsed or failed-parse)                | `ExtractionService.kt:304-346`, `371-380`, `513-527` |
| synthesis                                 | `record`          | `synthesis_runs`                                                                                    | only on a `Completed` terminal                                         | `SynthesisService.kt:736-752`, `949-963`             |
| fit-lens, query call                      | `record`          | `fit_lens_runs.query_llm_request_id`                                                                | only on a `Completed` terminal for call #1                             | `FitLensService.kt:429-442`, `451-465`               |
| fit-lens, reason call                     | `record`          | `fit_lens_runs.reason_llm_request_id`                                                               | only when call #2 `Completed`                                          | `FitLensService.kt:386`, `500-514`                   |

**Orphans — `llm_requests` rows with no owner row — are produced on three paths,
two of them routine, not crash-only:**

1. **Any accumulation call whose terminal is `Rejected`, `TransientFailure`, or
   an escaping exception.** Extraction returns `TransientFailure` without
   writing a run (`ExtractionService.kt:307-318`); synthesis likewise
   (`SynthesisService.kt:739-751`); fit-lens likewise
   (`FitLensService.kt:432-441`). The `llm_requests` row — holding the
   conversation window (extraction) or the student's whole coaching memory
   (synthesis, fit-lens) in `content`/`system` — is committed by `record` before
   the terminal is known and stays, un-owned, with its `llm_responses` row
   marked `rejected` / `transient_failure` / `internal_error`. A provider outage
   of an hour leaves one such row per retried job.
2. **A chat turn whose `convo_requests` write fails.** `recordStreaming` commits
   the `llm_requests` row first; a defect in `nextTurnId` / `appendRequestRow`
   (`CoachTurnLoop.kt:168-181`, `203-219`) closes the response row as
   `internal_error` (`LlmCallLog.kt:197-204`) but no `convo_requests` row ever
   exists. The student's full message array is in that orphan. For a parent turn
   the `supporter_llm_costs` row is in the same failed transaction, so it is
   absent too.
3. **A hard crash between the `llm_responses` write and the owner-row write** —
   the case the DAO documents as "rare and permanent"
   (`LlmCallsDao.kt:400-407`).

There is no job or routine that re-links or purges orphans; `listUnlinkedCalls`
is an on-demand operator report (`LlmCallsDao.kt:400-408`), surfaced through
`admin-web/src/main/kotlin/ed/unicoach/admin/resources/LlmRequestsResource.kt`.

### 1d. `account_llm_cost` and its siblings — what they read

`db/schema/account-llm-cost.sql:32-55`: a `UNION ALL` of two views, re-keyed
onto `users.id`:

- `student_llm_cost JOIN students st ON st.id = c.student_id` → `st.user_id`;
- `supporter_llm_cost JOIN supporters sp ON sp.id = c.supporter_id` →
  `sp.user_id`.

`student_llm_cost` (current body: `convos-party.sql:84-109`) is the five-arm
union of §1b's student-keyed owner rows, with the `convo_requests` arm narrowed
to `c.party = 'student'` (line 88), joined to `llm_responses` for the frozen
cost. `supporter_llm_cost` (`supporter-llm-cost.sql:57-68`) is the same shape
over `supporter_llm_costs`.

**No `deleted_at` predicate anywhere in the chain**, and it is deliberate:
`account-llm-cost.sql:22-27` — "a closed account's past spend is still spend,
and a meter that forgets it would reset a lifetime allowance by closing a
profile." `student_llm_cost` joins `convos` with no soft-delete filter
(`convos-party.sql:86-88`), and RFC 252 restates the retention
(`rfc/252-the-deletion-partition.md:121-124`). Consequence for erasure: the cost
views read `llm_responses` cost/token columns _through_ the owner rows. Erasing
the owner rows (to sever attribution) zeroes a closed account's meter; erasing
only the text columns on `llm_requests`/`llm_responses_raw` does not.

`supporter_llm_costs` has an update guard but **no delete guard**
(`supporter-llm-cost.sql:40-41`, with the reasoning at 33-39: "retention on the
call log is what removes it"). `convo_requests` likewise leaves DELETE to the
cascade from `convos`.

---

## 2. The job queue

`db/schema/0003.create-queue.sql`: `jobs.payload JSONB NOT NULL` (0003:6), up to
64 KiB (0003:17), status in `SCHEDULED | RUNNING | COMPLETED | DEAD_LETTERED`
(0003:14-16). `job_attempts.error_message TEXT` up to 4096 chars (0003:32,
38-40), `ON DELETE CASCADE` from `jobs` (0003:27). No FK from either table to
any person. `0004` adds a `pg_notify` trigger only.

### 2a. Payload fields per job type

`queue/src/main/kotlin/ed/unicoach/queue/JobType.kt:3-29` lists ten types (two
are test-only). Person-identifying payload content:

| job type                            | payload class                                                     | fields that identify a person                                                                                                                                                                                                                                                                                                                                                                                                                   | enqueued by                                                                                                       |
| ----------------------------------- | ----------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `SEND_EMAIL`                        | `email/.../EmailJobPayload.kt:17-21`: `to`, `template`, `context` | **`to` = the recipient's email address** (by design: "the recipient travels in the payload rather than a userId", lines 11-14). `context` per template: `EMAIL_VERIFICATION` → **the raw verification token** (`EmailVerificationService.kt:71-74`); `LINK_REQUESTED` → **`claimantName`, `claimantEmail`** of the _other_ party (`LinkRequestedNotice.kt:77-80`); `LINK_ENDED` → **`recipientName`, `otherName`** (`LinkEndedNotice.kt:83-86`) | `AuthService.kt:154, 205`; `EmailVerificationService.kt:76`; `LinkRequestedNotice.kt:82`; `LinkEndedNotice.kt:88` |
| `EXTRACT_CONVERSATION`              | `ExtractionPayload.kt:12-14`                                      | `convoId` (→ a student via `convos`), `throughRequestId`                                                                                                                                                                                                                                                                                                                                                                                        | `ConvoRoutes.kt:389-390`                                                                                          |
| `SYNTHESIZE_STUDENT`                | `SynthesisPayload.kt:11-12`                                       | `studentId`                                                                                                                                                                                                                                                                                                                                                                                                                                     | `SynthesisSweepHandler.kt:62-63`                                                                                  |
| `FIT_LENS`                          | `FitLensPayload.kt:11-12`                                         | `studentId`                                                                                                                                                                                                                                                                                                                                                                                                                                     | `FitLensSweepHandler.kt:58-59`                                                                                    |
| `SESSION_EXTEND_EXPIRY`             | `SessionExpiryPayload.kt:6-7`                                     | `tokenHash` (a session token hash → `sessions.user_id`)                                                                                                                                                                                                                                                                                                                                                                                         | `SessionExpiryPlugin.kt:56-57`                                                                                    |
| `REFRESH_SUBSCRIPTION`              | `SubscriptionRefreshPayload.kt:17-21`                             | `originalTransactionId` (→ `subscriptions.user_id`), `notificationUuid`, `notificationType`, `subtype`                                                                                                                                                                                                                                                                                                                                          | `AppleNotificationRoutes.kt:121`                                                                                  |
| `SYNTHESIS_SWEEP`, `FIT_LENS_SWEEP` | none                                                              | none                                                                                                                                                                                                                                                                                                                                                                                                                                            | `:cron` via `periodic_jobs` (0029)                                                                                |

No payload carries a user's **name** except the two link emails' `context`, and
no payload carries an email address except `SEND_EMAIL.to` and
`LINK_REQUESTED.claimantEmail`.

### 2b. `job_attempts.error_message` carries addresses and names

A permanent or transient email failure is recorded as
`"… email failure to [$to] for template [$template]: [${error.message}]"`
(`email/.../EmailSendHandler.kt:114-124`), and the message reaches
`job_attempts.error_message` through the `job_attempts` INSERT in `JobsDao`
(`queue/.../dao/JobsDao.kt:317-326`). The inner `error.message` can itself carry
the recipient and the rendered **context** (names): `EmailExceptions.kt:35`
(`Invalid recipient [$recipient] …`), `:43`, `:58`
(`Render failed … to [$recipient] with context [$context]`).

### 2c. Retention

- `COMPLETED` jobs are reaped hourly after 30 days (`QueueWorker.kt:35-36`
  defaults `completedJobRetention = 30.days`,
  `completedJobReapInterval = 1.hours`; loop at `QueueWorker.kt:288-310`;
  `JobsDao.deleteBefore` at `JobsDao.kt:406-428`). `job_attempts` go with them
  by cascade.
- **`DEAD_LETTERED` jobs are never reaped**: the reaper passes
  `setOf(JobStatus.COMPLETED)` only (`QueueWorker.kt:298`). A dead-lettered
  `SEND_EMAIL` keeps its `to`, token or names, plus every attempt's
  `error_message`, indefinitely.
- `SCHEDULED` jobs for a closed account (e.g. an `EXTRACT_CONVERSATION` enqueued
  just before close) stay until they run; RFC 252 added a student liveness check
  to extraction so they no-op (`rfc/252:126-129`), but the row and payload
  remain until COMPLETED + 30 days.
- No guard refuses `DELETE` on `jobs`/`job_attempts` (0003 has none), so this
  table _can_ be purged by id (`JobsDao.deleteByIds`, `JobsDao.kt:439-456`).

---

## 3. Email

### 3a. `email_sends` — the rendered message, forever

`db/schema/0008.create-email-sends.sql:13-28`: `recipient_email`,
`sender_email`, **`subject`**, **`body`** (the full plain-text body), `status`,
`provider`, `provider_message_id`, `error_message`. Written by
`email/.../dao/EmailSendsDao.kt:18-28` from the rendered email. No FK to
`users`; the only person key is the address string.

Guarded: `BEFORE UPDATE` and `BEFORE DELETE` raise (0008:30-36) via the shared
log guards. So an account's verification emails, every `LINK_ENDED` body ("Hi
<recipientName>, You and <otherName> are no longer connected",
`LinkEndedNotice.kt:146-147`) and every `LINK_REQUESTED` body ("<claimantName>
(<claimantEmail>) opened the Family Cost Report link you shared",
`LinkRequestedNotice.kt:115`) are kept verbatim, keyed only by recipient
address, and name the _other_ party.

Also written **to stdout** in dev (`LogOnlyEmailProvider.kt:15-22` logs `from`,
`to`, `subject`, `body`; selected by `.env.dev:169 EMAIL_PROVIDER=log`). Prod
uses SES (`.env.prod:69`).

### 3b. `verification_tokens`

`db/schema/0014.create-verification-tokens.sql:8-20`: `user_id` → `users(id)`
with **no `ON DELETE`** (blocks a physical user delete), `token_hash
BYTEA`
(SHA-256; raw never stored, 0014:14), `expires_at`, `consumed_at`. No delete
guard. Reachable from `users` by FK — listed here only because the **raw token**
also sits in the `SEND_EMAIL` payload (§2a) and in the `email_sends.body` of the
verification mail (§3a), so a token the FK walk erases here still has two copies
elsewhere.

---

## 4. Request / HTTP logging and other log lines

### 4a. RFC 103's request log is a stdout line, not a table

`rfc/103-configurable-self-diagnosing-request-log.md:4-16`: one Ktor
`CallLogging` line per request, `METHOD uri -> status`, enriched with a header
allowlist, body **sizes** and latency. Implementation:
`web-common/src/main/kotlin/ed/unicoach/web/common/logging/RequestLogging.kt`
(`call.request.uri` at :70; head built at :129-137; query redaction :202-225).
Defaults (`web-common/src/main/resources/reference.conf:16-26`):
`secretHeaders = Cookie,Authorization` (+ `X-Unicoach-Client-Key` for
rest-server, `rest-server.conf:57`),
`headers = Accept,Content-Type,User-Agent,Expect,Content-Length`,
`detail = failure`. Dev widens to every header on every request
(`.env.dev:190-191`).

What it captures about a person: **the request URI** (path and query, with only
opted-in query params redacted) — so any id in a path, and the share token on a
report URL unless that route opted in; **User-Agent**; **no body**, no user id,
no email, no principal. I did not audit every route's path for ids.

### 4b. Where stdout goes

All four services log through logback's `ConsoleAppender` with the Logstash JSON
encoder (`rest-server/src/main/resources/logback.xml:2-4`; same shape in
`cron/`, `admin-web/`, `public-web/`). The systemd units set no `StandardOutput`
(`infra/files/unicoach-rest-server.service:10`,
`unicoach-queue-worker.service:10`), so stdout lands in the instance's
**journald**. `infra/files/cloud-init.yaml` configures no journald retention, no
CloudWatch agent and no logrotate (grep for
`journal|cloudwatch|logrotate|SystemMaxUse` is empty), so application logs are
retained by journald's defaults on the instance's disk and are not shipped
anywhere. **I could not determine the effective journald cap** from the repo; it
is whatever the AMI's `journald.conf` sets.

The one CloudWatch log group, `/unicoach/<env>/ops-run` (`infra/ops.tf:5-8`,
30-day retention), receives the **stdout of `bin/remote` ops runs**
(`bin/remote:290, 412`). Any operator who runs e.g. `bin/db-query` remotely puts
that query's output — potentially rows of personal data — in CloudWatch for 30
days.

### 4c. Application log lines that print personal data

Grep over `logger.*(info|warn|error|debug)` in `service/`, `rest-server/`,
`email/`, `admin-web/` for `email|name|recipient|to`:

- `service/.../coaching/CoachTurnLoop.kt:492` — on a tool dispatch failure logs
  the **full tool input** the model chose (`input=[{}]`), i.e. whatever the
  student's turn led the model to pass (college names, figures, free text).
- `email/.../EmailSendHandler.kt:69` — logs the **raw payload** of a malformed
  `SEND_EMAIL` job (contains `to` and `context`).
- `email/.../EmailSendHandler.kt:114-119` — logs `"… failure to [$to] …"` with
  the exception chain (which can carry the context, §2b).
- `email/.../LogOnlyEmailProvider.kt:15-22` — full email incl. body (dev only).
- `service/.../subscriptions/SubscriptionService.kt:129` — Apple
  `originalTransactionId`.
- Sweep handlers log `studentId` (`SynthesisSweepHandler.kt:74`,
  `FitLensSweepHandler.kt:70`); routes log request/link ids.

No log line prints a `users.email` or `users.name` directly outside the email
module.

---

## 5. Text about a person that lives outside that person's own rows

RFC 252 landed (`b99a77d3`) as **close**, not erase: rows are soft-deleted,
links severed, the other party emailed (`rfc/252:6-9`, `46-50`, `52-57`;
`service/.../account/AccountClosureService.kt:30-44`). The close writes only
under the closer's own keys (`AccountClosureService.kt:36-40`, `54-66`):
`severAll` on links from the closer's side, cancel pending link requests, revoke
the closer's invites and live cost-report share, soft-delete `students` /
`supporters` / `users` (`closeRows`, :110-118), delete `user_auth_identities`
for the closed user and revoke sessions (:126-127). It explicitly keeps the LLM
cost rows and the subscription (:41-44; `rfc/252:113-124`).

What that leaves, per branch:

| data about X, stored under Y's keys                                                                                                                                                                                                                                                                                                                                                                                                           | where                                                                                                                                                                                                                     | whose deletion should erase it                                                                                                                                                                                                                                                                                                                                                                                                                  | survives today?                                                                                                                                                                                                                                                                                      |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Parent's coach convo about the student.** Every parent turn's `llm_requests.system` carries "The student this person supports is `<studentName>`; this person is their `<relationship>`." plus the student's shared college list (school, status, living plan) — `SupporterCoachingService.kt:263, 286`; `SubjectBlock.render` :380-396. The parent's questions and the coach's answers about the student are in `content`/`llm_responses`. | `convos (party='supporter', supporter_id=parent, student_id=student)` → `convo_requests` → `llm_requests`                                                                                                                 | Ambiguous by design: the convo is the parent's (`supporter_id`, billed via `supporter_llm_costs`) but is ABOUT the student (`convos.student_id`, cascade from students). A student erasure that follows FKs would delete the parent's convo rows (cascade) but strand the `llm_requests` text; one that respects "each party's own conversations survive" (`rfc/252:13-16`) would leave the student's name and list in the parent's live convo. | **Yes.** `ConvosDao.listBySupporterWithActivity` filters only `c.supporter_id` and `c.deleted_at` (`ConvosDao.kt:399-401`), not the student's liveness; the convo keeps rendering after the student closes. The link's severance only refuses the _next_ turn (`SupporterCoachingService.kt:63-65`). |
| **Student's coach convo mentioning the parent** (name, relationship, what they said) — the symmetric case; also distilled into `claims` / `observations` text (0019).                                                                                                                                                                                                                                                                         | student-keyed rows                                                                                                                                                                                                        | the student's; a parent's erasure cannot reach it without text search                                                                                                                                                                                                                                                                                                                                                                           | yes, by RFC 252's rule                                                                                                                                                                                                                                                                               |
| **`LINK_ENDED` email body** naming both parties                                                                                                                                                                                                                                                                                                                                                                                               | `email_sends.body`, keyed by `recipient_email` (§3a); the `SEND_EMAIL` payload until reaped (§2)                                                                                                                          | both: it names the closer and is sent to the other party                                                                                                                                                                                                                                                                                                                                                                                        | yes — email_sends is delete-guarded                                                                                                                                                                                                                                                                  |
| **`LINK_REQUESTED` email body** with the claimant's name and email, sent to the student                                                                                                                                                                                                                                                                                                                                                       | `email_sends.body`; payload                                                                                                                                                                                               | the claimant's (whose name/email it is) and the student's (whose inbox)                                                                                                                                                                                                                                                                                                                                                                         | yes                                                                                                                                                                                                                                                                                                  |
| `supporter_invites`                                                                                                                                                                                                                                                                                                                                                                                                                           | ids and `token_hash` only — `inviter_user_id` (CASCADE), `student_id`/`supporter_id` (CASCADE), `relationship` (`supporter-invites.sql:7-41`). **No names or emails stored.**                                             | the inviter's                                                                                                                                                                                                                                                                                                                                                                                                                                   | reachable by FK; cascades from either endpoint                                                                                                                                                                                                                                                       |
| `supporter_link_requests`                                                                                                                                                                                                                                                                                                                                                                                                                     | ids only: `supporter_id`, `student_id`, `cost_report_share_id` (all CASCADE), `link_id` (`supporter-link-requests.sql:30-50`). No names/emails. Has a raising `BEFORE DELETE` guard (:135-137) that a cascade would trip. | both                                                                                                                                                                                                                                                                                                                                                                                                                                            | reachable by FK; the guard blocks a physical cascade                                                                                                                                                                                                                                                 |
| `supporter_student_links` (+ `_versions`)                                                                                                                                                                                                                                                                                                                                                                                                     | ids + relationship; physical delete refused (`supporters-and-student-links.sql:362-364`), versions `RESTRICT` (:129)                                                                                                      | both                                                                                                                                                                                                                                                                                                                                                                                                                                            | reachable by FK; blocked                                                                                                                                                                                                                                                                             |
| `cost_report_shares` / `share_events`                                                                                                                                                                                                                                                                                                                                                                                                         | `student_id` + `token_hash` (0073:15-23); `share_events` append-only (0081:48-51). The report _content_ is rendered live from the student's rows, not stored.                                                             | the student's                                                                                                                                                                                                                                                                                                                                                                                                                                   | reachable; `share_events` delete-guarded                                                                                                                                                                                                                                                             |
| `sharing_permissions` (+ `_versions`)                                                                                                                                                                                                                                                                                                                                                                                                         | student-keyed only (`create-sharing-permissions.sql:41-58`)                                                                                                                                                               | the student's                                                                                                                                                                                                                                                                                                                                                                                                                                   | reachable; physical delete refused (:142-144)                                                                                                                                                                                                                                                        |

So of the family tables the brief flagged, only the **parent's convo text** and
the **email bodies** actually hold the other party's words/name outside that
party's keys; the invite/link/request tables hold ids and hashes only and are
FK-reachable (though guard-blocked).

---

## 6. Backups and copies

- **RDS automated backups.** `infra/rds.tf:18-47` sets no
  `backup_retention_period`, no `backup_window`, and
  `skip_final_snapshot = true` (:41). The retention is therefore whatever AWS
  applies when the API call omits it. **I could not determine the live value
  from the repo**; confirm with
  `aws rds describe-db-instances --query 'DBInstances[].BackupRetentionPeriod'`
  under `AWS_PROFILE=unicoach-prod`. If it is >0, automated snapshots and
  point-in-time logs hold every table above for that many days after any
  erasure; if 0, there is no backup at all. Either answer matters to the brief.
- **Manual RDS snapshots.** None declared in `infra/`. `skip_final_snapshot`
  means a `tofu destroy` leaves nothing.
- **S3.** One artifacts bucket (`infra/s3.tf`) holding release bundles and
  `bin/remote` input files (`ops-inputs/<run-id>/`, deleted on exit,
  `bin/remote:6-9`), lifecycle-expired at 30 days (`infra/s3.tf:43-60`). No
  database export lands there.
- **Dumps.** `bin/db-dump` / `bin/db-restore` are `pg_dump -Fc` of the **dev**
  database to `$POSTGRES_DUMP_DIR` (`.env.dev:67` = `$VAR_DIR/dumps`, i.e. a
  developer's machine), documented as "Local-only tooling; never deployed"
  (`bin/db-dump:58`). Nothing in `bin/` or `infra/` dumps prod. A developer who
  restores a prod dump locally holds a copy outside every control here — there
  is no tooling for that, but nothing prevents it.
- **CloudWatch.** Only the ops-run group (§4b), 30 days.
- **Analytics / exports.** None: `admin-web` has no CSV/export/download code
  (grep empty); `tools/` contains only `fuzz`.
- **Test database copies.** `bin/test` clones the _test_ DB per module and drops
  the clones (CLAUDE.md, RFC 259); never prod data.

---

## 7. External processors that receive personal data

| processor                                              | what is sent                                                                                                                                                                                                                                                                                                         | selected / configured at                                                                                        |
| ------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| **Anthropic** (Messages API)                           | every system text and message array in §1 — the student's words, coaching memory, college list, money figures; the parent's words and the student's name/list. `POST {baseUrl}/v1/messages` with `x-api-key` (`chat/.../KtorAnthropicStreamTransport.kt:35, 114`). No `metadata.user_id` is sent (grep for `metadata | user_id`in`chat/` is empty), so Anthropic holds no account key, only the text.                                  |
| **Apple App Store Server API**                         | `GET /inApps/v1/subscriptions/{transactionId}` — the `original_transaction_id` (`appstore/.../AppStoreServerApi.kt:81-82, 244`); Apple's notifications arrive at `AppleNotificationRoutes` with the same id                                                                                                          | `.env.prod:77 APP_STORE_ENVIRONMENT=production`; stored id in `subscriptions.original_transaction_id` (0042:37) |
| **Apple Sign in with Apple**                           | the identity token; we store `provider`, `subject` (Apple `sub`), `email` in `user_auth_identities` (0018:21-24)                                                                                                                                                                                                     | `.env.prod:72 APPLE_AUTH_PROVIDER=apple`                                                                        |
| **Amazon SES**                                         | recipient address, subject, body of every email (`email/.../SesEmailProvider.kt:69`)                                                                                                                                                                                                                                 | `.env.prod:69 EMAIL_PROVIDER=ses`; `infra/ses.tf`                                                               |
| **AWS (RDS, journald on EC2, CloudWatch ops-run, S3)** | the database itself and the logs in §4/§6                                                                                                                                                                                                                                                                            | `infra/*.tf`                                                                                                    |

Nothing else leaves the box: no analytics SDK, no push service, no third-party
error tracker was found in `rest-server/`, `service/` or `ios-app/`.

---

## 8. Client-side (iOS)

Per-user data the app persists locally (all in `UserDefaults.standard`; there is
**no Keychain, URLCache or file store** — grep for
`SecItem|Keychain|URLCache|cachesDirectory|documentDirectory` over `ios-app/` is
empty):

| key                                   | holds                                                                  | written by                                              | cleared on delete?                                                                                                                                                            |
| ------------------------------------- | ---------------------------------------------------------------------- | ------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SupporterSubject.studentId.<userId>` | which linked student the parent last viewed                            | `ios-app/UnicoachiOS/SupporterSubjectStore.swift:25-77` | yes, for the signed-in user (`AppViewModel.swift:365-367`)                                                                                                                    |
| `AppleSignIn.name.<sub>`              | the Apple-provided display name, per Apple `sub` (Apple sends it once) | `AppleNameStore.swift:20-48`                            | yes, **all** such keys (`AppViewModel.swift:368`, `removeAll` at `AppleNameStore.swift:45-48`) — because the app cannot know which `sub` the account used (`rfc/252:228-230`) |
| session cookie                        | `HTTPCookieStorage.shared` (`AppViewModel.swift:111`)                  | Ktor session                                            | yes (`endLocalSession`, `AppViewModel.swift:376-382`)                                                                                                                         |

Two gaps:

- On a `401` from `DELETE /auth/me` the app ends the session but **clears no
  per-user `UserDefaults`** (`AppViewModel.swift:357-363`; rationale at
  `rfc/252:253-257`), so an account closed from another device leaves its
  `SupporterSubject` key and Apple name on this one.
- Conversation text is held in memory only (`ConversationViewModel`,
  `ConversationClient`) and not persisted; nothing to clear.

The `MarkdownBlock.swift` hits for `removeAll` are array operations, not
storage.

---

## What a per-account FK walk would miss

Each bullet is one branch; the path is what a purge must follow to reach it.

- **The conversation text itself.** `students → convos → convo_requests` stops
  at `convo_requests.llm_request_id`; the words are in `llm_requests.content`,
  `llm_requests.system`, `llm_responses.content`, `llm_responses_raw.payload`.
  Path: collect `llm_request_id` from `convo_requests` where
  `convos.student_id = S` (and decide `party`), then erase/redact the three log
  rows by id. Note the FK direction: the owner row points at the log, so the
  cascade deletes the pointer and strands the text.
- **Extraction windows.** `extraction_runs.student_id = S` →
  `extraction_runs.llm_request_id` → the same three log rows.
- **Synthesis inputs** (the whole coaching memory in one prompt).
  `synthesis_runs.student_id = S` → `llm_request_id` → log rows.
- **Fit-lens, two ids per run.** `fit_lens_runs.student_id = S` →
  `query_llm_request_id` **and** `reason_llm_request_id` (nullable) → log rows.
  A walk that takes one column misses half the calls.
- **The parent's calls.** `supporters.id = P` →
  `supporter_llm_costs.supporter_id` → `llm_request_id` → log rows. This is the
  only owner row for the cost view; the same calls are _also_ reachable via
  `convos.supporter_id → convo_requests`.
- **Orphaned calls (no owner row).** Produced routinely by any rejected /
  transient / errored extraction, synthesis or fit-lens call
  (`ExtractionService.kt:307-318`, `SynthesisService.kt:739-751`,
  `FitLensService.kt:432-441`) and by a chat turn whose `convo_requests` write
  failed (`CoachTurnLoop.kt:168-181, 203-219`). **No path from any person key
  reaches these.** Only `LlmCallsDao.listUnlinkedCalls` finds them, and only
  content inspection (the student's name or text inside `content`/`system`) can
  attribute one. A purge needs a decision: erase all orphans older than N, or
  accept that they are unattributable and treat the whole log as retention-only.
- **The parent's convo text about a closed student.** Reachable from the
  _parent_ (`convos.supporter_id`) but it names the student and lists their
  colleges in every `llm_requests.system`. Path:
  `convos.student_id = S AND
  party = 'supporter'` → `convo_requests` → log
  rows; then decide whether the parent's own conversation is erased, redacted,
  or kept (RFC 252 says kept).
- **Queue payloads.** `jobs.payload` has no FK: `SEND_EMAIL.to` (address),
  `SEND_EMAIL.context` (raw verification token; `claimantName`/`claimantEmail`;
  `recipientName`/`otherName`), `EXTRACT_CONVERSATION.convoId`,
  `SYNTHESIZE_STUDENT.studentId`, `FIT_LENS.studentId`,
  `SESSION_EXTEND_EXPIRY.tokenHash`,
  `REFRESH_SUBSCRIPTION.originalTransactionId`. Path: JSON search on `payload`
  by address / ids. `DEAD_LETTERED` rows are never reaped.
- **`job_attempts.error_message`.** Carries `to [<address>]` and, for render
  failures, the context (names). Path: text search, or purge by `job_id` after
  the payload search above.
- **`email_sends`.** Keyed by `recipient_email` string only; holds subject and
  full body (which names the _other_ party in link emails). Delete-guarded.
  Path: `recipient_email = <every address the account ever had>` (note
  `users_versions` for past addresses) **plus** `body LIKE '%<name>%'` for mails
  sent to others about this person.
- **The raw verification token** — three copies:
  `verification_tokens.token_hash` (FK-reachable), the `SEND_EMAIL` payload, and
  the `email_sends.body`.
- **Subscriptions after rebinding.** `subscriptions.user_id` is nullable and
  rebinds to a new account on the same Apple ID
  (`subscriptions-key-on-account.sql:54`; `rfc/252:115-120`), so
  `original_transaction_id` outlives the account key.
- **journald on the EC2 instance.** Request lines with URIs and User-Agent; tool
  inputs on dispatch failure (`CoachTurnLoop.kt:492`); malformed email payloads
  (`EmailSendHandler.kt:69`); failure lines with `to [<address>]`. No retention
  configured in-repo; not shipped off-box. Path: none from the DB — operational,
  time-based only.
- **CloudWatch `/unicoach/<env>/ops-run`.** Output of any remote `bin/db-query`
  etc., 30 days. Path: none — operational.
- **RDS automated backups.** Retention not set in `infra/rds.tf`; live value
  unknown from the repo. Every branch above persists in them for the window.
- **Anthropic.** Holds every prompt and response body; no account id attached,
  so there is nothing to key a deletion request on except time. SES holds
  message metadata; Apple holds the transaction and `sub`.
- **The iPhone.** `SupporterSubject.studentId.<userId>` and
  `AppleSignIn.name.<sub>` in `UserDefaults`; cleared by the RFC 252 delete path
  except when the delete returns `401` on this device.

### Things I could not determine

- The effective RDS `BackupRetentionPeriod` (not in the repo).
- The journald size/time cap on the production AMI (not in the repo).
- Whether any route puts an email address in a request path or non-redacted
  query (I read the logging code, not every route).
- The exact contents of a _student_ turn's `system` text (composed in
  `CoachingService`/`CoachTurnLoop`; read only as far as confirming it is
  persisted to `llm_requests.system`).
