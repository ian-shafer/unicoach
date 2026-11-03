# RFC 267 — The sweep: erasure a week after the close

**Slice:** `deletion/04/the-sweep` (brief 0002, _account erasure_). **Status:**
proposed. **Base:** `main@6f082dbd`. **Lane:** rfc.

## Executive Summary

RFC 266 wrote the walk: `erase_account` erases one closed account, and an
operator runs it at `POST /user/{id}/erase`. Nothing runs it on its own. This
RFC keeps brief 0002's promise without anyone acting: closing an account
enqueues, in the close transaction, one erase job delayed by N days (D2, N = 7,
one config key). When it comes due, the job runs the walk in one transaction
and, as its last statement, enqueues the one email that says the account is
deleted (D5). The email's `email_sends` row is the deletion receipt (D1), keyed
on `deleted_user_id`.

The slice text describes a periodic sweep that finds due accounts and enqueues
the erase jobs. A job delayed at the close does the same work with nothing to
find: one job type instead of two, no periodic row, no batch, and nothing that
could enqueue an account twice (decision D-H, Ian at approval, 2026-11-01).

It also lands D13 (Ian, 2026-11-01, "Widen the reaper"): the walk no longer
deletes `jobs` rows — it would have deleted its own erase job and, on a re-run,
a confirmation not yet sent — and the queue's reaper now deletes every finished
job, completed or dead-lettered, 30 days after it finished.

After it lands, Settings → Delete account is erasure end to end: the account
closes at once, is erased a week later, and the person is told.

## Detailed Design

### 1. What the code says, where it differs from the slice

Read from `main@6f082dbd`.

- **The queue already delays.** `QueueService.enqueue(session, …, delay)` writes
  `scheduled_at = NOW() + delay` on the caller's transaction, and the worker
  claims a job only once `scheduled_at` has passed. The close transaction can
  schedule the erasure itself; no sweep is needed to find it (D-H).
- **A handler is not given a transaction.** `QueueWorker` runs
  `handler.execute(payload)` outside any transaction and records the outcome in
  a second one (`QueueWorker.kt:198-258`). "One transaction" for the erase job
  therefore means the handler's own `withConnection`: the walk and the email
  enqueue commit together; the job's `COMPLETED` row is written after. A crash
  between the two re-runs the job, which §4's idempotency answers.
- **A re-run of the walk would erase the receipt.** The walk erases every row
  keyed on a root by its live key _or its sibling_ (RFC 266 §3 step 5), so the
  receipt, keyed on `deleted_user_id`, is erased by the next call. RFC 266's "a
  second call returns no rows and writes nothing" held only because nothing was
  ever written against an erased account. §2 makes it hold by returning at once
  for an erased account.
- **The admin door would send no email.** It calls the same service, so the
  enqueue goes in the service (§4) and both doors confirm. A dead-lettered erase
  job finished by an operator (RFC 266 §10) still tells the person.
- **An account can be closed, reopened and closed again.** Admin-web's
  `POST /user/{id}/undelete` reopens a closed account. The first close's job is
  still scheduled and would erase the account N days after the _first_ close.
  The job therefore names the close it was scheduled for (§3).
- **Accounts closed before this lands have no job.** §3's backfill gives each
  one.
- **The reaper is Kotlin and reaps `COMPLETED` only.** `QueueWorker`'s reap loop
  calls `JobsDao.deleteBefore(session, setOf(COMPLETED), 30.days)`. There is no
  `FAILED` status: a failed job is rescheduled or dead-lettered, so "every
  finished job" (D13) is `COMPLETED` and `DEAD_LETTERED`.
- **Nothing logs a dead-letter.** `QueueWorker` dead-letters silently, so "a
  dead-lettered erase job is an operator alert" has no alert to be. §5 adds one
  line, for every job type.
- **There is no DB-clock helper.** Tests move `jobs.scheduled_at` into the past
  instead (the `QueueWorkerTest` precedent of backdating), which is what the
  slice's "DB clock advanced past N" asks for.

### 2. The walk leaves `jobs` alone, and returns at once when erased

A migration restates `erase_account` as its latest definition with two changes:

1. **Step 6 deletes D10's four keyed kinds only** (`sessions`,
   `verification_tokens`, `user_auth_identities`, the account's
   `supporter_invites`). The `PERFORM public.erase_account_delete_jobs(...)`
   line goes, and with it step 1's two reads, which only fed that predicate.
   `erase_account_delete_jobs`, `erase_account_addresses` and
   `erase_account_token_hashes` are dropped (D13).
2. **An erased account returns no rows at once.** After the existing lock, the
   two refusals (`erase_account_exists`, `erase_account_closed`) and the root
   resolution, so a corrupt account (`erase_account_one_root`) still raises on a
   re-run:

   ```sql
   IF EXISTS (SELECT 1 FROM public.deleted_users du WHERE du.id = account) THEN
       RETURN;
   END IF;
   ```

   A walk is one transaction, so an account with a `deleted_users` row was
   erased whole; nothing is left for a second call to do, and the receipt (and
   anything else written against the erased account on purpose) is never touched
   by a re-run.

A `jobs` row that names the account now outlives the erasure until the reaper
takes it (§5). RFC 266's other effects are unchanged.

### 3. The erase job, scheduled at the close

`JobType` gains one value:

```kotlin
// One per closed account (RFC 267): enqueued by the close, delayed by D2's N;
// its handler runs the walk and enqueues the confirmation email.
ERASE_ACCOUNT("ERASE_ACCOUNT"),
```

**The payload names the account and the close:**

```kotlin
@Serializable
data class AccountErasurePayload(
  val userId: String,
  val closedAt: String, // users.deleted_at of the close that scheduled it, ISO-8601
)
```

**At the close.** One class schedules the job for every writer of a close:

```kotlin
class AccountErasureScheduler(
  private val queueService: QueueService,
  private val config: AccountErasureConfig,
) {
  fun schedule(session: SqlSession, closed: User)
}
```

`schedule` runs the required enqueue (`ASYNC_WORK.md`):
`queueService.enqueueOrThrow(session, JobType.ERASE_ACCOUNT, AccountErasurePayload(closed.id, closed.deletedAt).asJson(), delay = config.grace)`,
where `closed` is the row `UsersDao.delete` returns. `QueueService` gains
`enqueueOrThrow`, the one home of the required enqueue: the session-threaded
`enqueue` whose `DatabaseFailure` is thrown, mapped by `mapDatabaseError` as
every DAO maps one, so a close that cannot schedule its erasure does not close.
`LinkEndedNotice` uses it too. `AccountClosureService.close` calls `schedule` as
the last statement of its transaction; it gains an `AccountErasureScheduler`
constructor parameter, and `rest-server` wires it. Admin-web's Delete
(`POST /user/{id}/delete`) is a close as well: it reads the row
`SoftDeleteScope.ACTIVE`, soft-deletes it and calls the same `schedule` in the
same transaction, so no `users.deleted_at` is set without its job. An account
already closed or erased is not found (404), so a second Delete neither
re-stamps the close nor schedules a second erasure. Undelete is unchanged, and
the job then answers `NotClosed`.

**The address is not in the payload.** It is re-read inside the erase
transaction, before the walk (decision D-A): one fewer personal value in `jobs`,
and the address the account has when it is erased.

**The backfill.** A migration gives every account already closed and not erased
one job, scheduled from its own close:

```sql
INSERT INTO jobs (job_type, payload, scheduled_at)
SELECT 'ERASE_ACCOUNT',
       jsonb_build_object('userId', u.id::text, 'closedAt', to_jsonb(u.deleted_at) #>> '{}'),
       u.deleted_at + INTERVAL '7 days'
  FROM users u
 WHERE u.deleted_at IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM deleted_users du WHERE du.id = u.id);
```

The `7 days` is D2's N written once, for accounts closed before the setting
existed; the migration runs once and never reads the setting again. An account
closed more than 7 days before the deploy is erased within seconds of the first
worker start after it (decision D-E).

**Changing N** changes the delay of closes from then on; a job already scheduled
keeps its time.

### 4. The erase: `AccountErasureService`

The service gains the queue and the enqueue; both doors call it:

```kotlin
class AccountErasureService(
  private val database: Database,
  private val queueService: QueueService,
) {
  suspend fun erase(userId: UserId): Result<ErasureOutcome>                               // the admin door
  suspend fun eraseClose(userId: UserId, closedAt: Instant): Result<CloseErasureOutcome>  // the job
}

sealed interface CloseErasureOutcome {
  data object CloseSuperseded : CloseErasureOutcome // closed again since
}

sealed interface ErasureOutcome : CloseErasureOutcome {
  data class Erased(
    val tables: List<TableErasure>,
    val confirmation: ErasureConfirmation,
  ) : ErasureOutcome
  data object AlreadyErased : ErasureOutcome
  data object NotClosed : ErasureOutcome
  data object NotFound : ErasureOutcome
}

enum class ErasureConfirmation { ENQUEUED, NO_VERIFIED_ADDRESS }
```

Each door answers only what it can: `CloseSuperseded` is the job's alone, so the
admin door's `when` has no arm for it. Each runs one `withConnection`:

1. `AccountErasureDao.lock(session, userId): Boolean` —
   `SELECT 1 FROM users
   WHERE id = ? FOR UPDATE`, its own statement; false →
   `NotFound`. The lock comes first so a concurrent erasure of the same account
   has committed, and is seen by every later statement, or has not begun. The
   reads after it are plain reads.
2. `AccountErasureDao.isErased(session, userId)` — whether a `deleted_users` row
   exists → `AlreadyErased`. Nothing else runs, so a duplicate job or a re-run
   sends no second email.
3. `eraseClose` only:
   `AccountErasureDao.closedAt(session, userId, closedAt): CloseMatch` —
   `SELECT deleted_at IS NOT NULL, deleted_at = ? FROM users WHERE id = ?`, the
   payload's `closedAt` compared in SQL so the precision is Postgres's own. No
   row is `NO_ACCOUNT` → `NotFound`, an answer and not a failure; `LIVE` →
   `NotClosed`; `ANOTHER_CLOSE` → `CloseSuperseded` (that later close has its
   own job); `THIS_CLOSE` continues. The handler parses `closedAt` with its
   offset (`OffsetDateTime`): the backfill writes the migrating session's
   offset, not `Z`.
4. The address: `UsersDao.findByIdOrNull(session, userId, SoftDeleteScope.ALL)`,
   its `email` when `emailVerifiedAt` is set, else none. The account is closed
   and not yet erased, so the owning mapper reads it. Read before the walk NULLs
   it. An unverified address is never written to (decision D-B), as
   `LinkEndedNotice` already does.
5. `AccountErasureDao.erase(session, userId)` — the walk, as today. Under the
   lock its `erase_account_exists` refusal cannot arise; its
   `erase_account_closed` refusal is the admin door's `NotClosed`.
6. When step 4 found an address:
   `queueService.enqueueOrThrow(session, JobType.SEND_EMAIL, EmailJobPayload(to = address,
   template = ACCOUNT_ERASED, context = {}, account =
   EmailAccountKey.Deleted(userId)).asJson())`.
   A failure is thrown, so the whole erasure rolls back (required enqueue,
   `ASYNC_WORK.md`). This is the last statement.

The INFO line keeps its per-table counts and gains the confirmation value; it
carries the user id and no personal value.

**The admin door** (`POST /user/{id}/erase`) renders `AlreadyErased` as the
existing 200 page with an empty table and the line "Already erased; no email
sent.", and an `Erased` page gains one line: "Confirmation email queued." or "No
verified address; no email sent."

**`AccountErasureHandler`** (`ERASE_ACCOUNT`, `service/.../account/`, registered
unconditionally in `queue-worker`):

| `eraseClose` result            | Job result                                                               |
| ------------------------------ | ------------------------------------------------------------------------ |
| `Erased`, `AlreadyErased`      | `Success`                                                                |
| `NotClosed`, `CloseSuperseded` | `Success`, logged at INFO: reopened, or closed again with its own job    |
| `NotFound`, payload unparsed   | `PermanentFailure` — dead-lettered at once                               |
| failure                        | `RetriableFailure(message, cause)` — the queue's retry, then dead-letter |

Config:
`JobTypeConfig(concurrency = 1, maxAttempts = 3, lockDuration = 5.minutes, executionTimeout = 2.minutes)`.
A dead-lettered erase job leaves the account closed and un-erased: the walk
rolled back whole, so every guard is as it was. It is an operator alert (§5),
not a retry forever; the admin door erases it, and sends the email, on re-run.

**The knob**, in `service.conf`, read by `AccountErasureConfig.from(config)`
(the `SynthesisConfig` pattern: private constructor, `Result`, the unit attached
once):

```hocon
accountErasure {
  # D2's N: a closed account is erased this many days after its close.
  # The app and the email say "within 30 days"; that copy is not derived from it.
  graceDays = 7
  graceDays = ${?ACCOUNT_ERASURE_GRACE_DAYS}
}
```

`graceDays` ≥ 0 (0 erases on the first worker poll after the close: the
first-session test's setting); any other value fails startup.

### 5. The reaper reaps every finished job (D13); a dead-letter is logged

`QueueWorker`'s reap loop calls
`jobsDao.deleteBefore(session, setOf(JobStatus.COMPLETED, JobStatus.DEAD_LETTERED), finishedJobRetention)`,
for every job type and every account. `completedJobRetention` and
`completedJobReapInterval` are renamed `finishedJobRetention` and
`finishedJobReapInterval`; their values (30 days, hourly) are unchanged.
`updated_at` is the finish time (the status change writes it), so "30 days after
it finished" is the existing predicate. `SCHEDULED` and `RUNNING` rows — an
erase job waiting out its seven days among them — are never reaped. A
`JobDeleteResult.DatabaseFailure` logs one ERROR line with its error, so a
failing reap is not read as nothing to reap.

This is how the confirmation's `SEND_EMAIL` row, which carries the address,
leaves the database: 30 days after it sent.

**The alert.** Where `QueueWorker` dead-letters a job it logs one ERROR line:
job type, job id, attempt number and the last error message (decision D-C). It
carries no payload. For an erase job that line is the operator's cue to re-run
the admin door.

### 6. The email: `ACCOUNT_ERASED`, and the receipt's key

`EmailTemplate` gains `ACCOUNT_ERASED`; `AccountErasedEmailRenderer`
(`service/.../account/`) renders it from an empty context and is added to
`emailRenderers`. The copy is **interim** — `deletion/05/the-words` owns it:

> **Subject:** Your unicoach account has been deleted
>
> Your unicoach account has been deleted, as you asked, and the information in
> it has been erased.
>
> We keep a record that the account existed and when it was deleted, your
> subscription record with Apple, and our usage records with your words removed.
>
> If you subscribed through Apple, the subscription continues until you cancel
> it in your Apple ID settings.
>
> — unicoach

No name: the account's name is erased with it, and the email has no other record
to take one from.

**The key.** The account an email is recorded against is one value, from the
payload down:

```kotlin
sealed interface EmailAccountKey {
  data class Live(val userId: UserId) : EmailAccountKey     // email_sends.user_id
  data class Deleted(val userId: UserId) : EmailAccountKey  // email_sends.deleted_user_id
}
```

`EmailJobPayload.account: EmailAccountKey?`,
`EmailService.send(to, subject,
body, account: EmailAccountKey?)` and
`NewEmailSend.account: EmailAccountKey?` replace the `userId` fields and
parameters; `EmailSendsDao.insert` writes the one column the key names. Every
existing producer passes `Live` (or null). The wire does not change, so
`SEND_EMAIL` jobs already queued still decode: `EmailJobPayload`'s serializer
writes the account as `userId` (always, null for none) and `deletedUserId` (only
when set). A wire payload naming both, or an id that is not a UUID, does not
decode (`InvalidAccountKeyException.NamesBothAccounts` / `.NotAUuid`, a
`SerializationException` holding the parts as properties), and the handler
dead-letters it as a malformed payload (`PermanentFailure`). The receipt is an
ordinary append-only `email_sends` row with its body intact; §2 keeps any walk
from touching it.

### Reachability

Settings → Delete account (iOS, both roles, RFC 252) closes the account and
schedules its erasure. N days later the account is erased with no operator
action, and the person's verified address receives one email saying so. The
admin door (`POST /user/{id}/erase`) erases one account sooner and sends the
same email. Nothing new is asked of the person.

### Schema

No new table, column or constraint. Migrations, each appended to
`db/schema/ORDER`:

- `erasure-walk-leaves-jobs.sql` — restates `erase_account` (§2); drops
  `erase_account_delete_jobs`, `erase_account_addresses`,
  `erase_account_token_hashes`.
- `schedule-erasure-of-closed-accounts.sql` — the backfill (§3): one
  `ERASE_ACCOUNT` job per account closed and not erased.

### Decisions

- **D-H — a job delayed at the close, not a periodic sweep.** Ian at approval,
  2026-11-01 ("Could we delay a job for a week instead of using a cron?"; "Do
  not worry about a max"). Replaces the slice's two job types with one; no batch
  size.
- **D-A — the erase job re-reads the address; its payload carries the id and the
  close only.** Alternative: the close writes the address into the job.
- **D-B — the email goes to a verified address only.** An unverified address was
  never shown to belong to the person; erasure still runs, and the outcome says
  no email was sent. Alternative: send to whatever address the account has.
- **D-C — operator visibility is log lines.** One INFO line per erasure
  (counts), one ERROR line per dead-lettered job of any type. Alternative: a
  count on the admin dashboard, which has no counts today.
- **D-E — the backfill runs on the old schedule.** An account closed more than 7
  days before the deploy is erased right after it. Alternative: schedule every
  backfilled job 7 days after the deploy, a week to look before the first
  production erasure.
- **D-G — both doors confirm.** The email is enqueued by the service, so an
  operator's erasure tells the person too.

## Tests

All against the real test database.

**The close** (`AccountClosureServiceTest`, amended):

1. A close enqueues exactly one `ERASE_ACCOUNT` job naming the account and its
   `deleted_at`, with `scheduled_at` = `deleted_at` + `graceDays`.
2. A close whose erase enqueue fails (a test trigger raising on an
   `ERASE_ACCOUNT` insert into `jobs`) rolls back: the account is live.

**The door, end to end** (`AccountErasureEndToEndTest`, a real `QueueWorker`
with the erase and email handlers and the real SES provider over the scripted
SES seam, as the other end-to-end email tests): build an account with
`ErasureFixture`, close it through `AccountClosureService`; the worker does not
run the job before it is due; move its `scheduled_at` into the past; assert
`deleted_users` has the id, the walk ran (the `users` row's `email` is NULL),
one `SEND_EMAIL` job with `ACCOUNT_ERASED` went to the old address, the SES seam
sent one email, and one `email_sends` row has `deleted_user_id` = the id,
`user_id` NULL and the body intact. Then `AccountErasureService.erase` answers
`AlreadyErased`, the receipt is unchanged, and no second email is enqueued. With
`graceDays` = 0 the same close is erased with no clock moved.

**Erase job** (`AccountErasureHandlerTest`):

3. A walk that fails (a test trigger raising on `deleted_users` insert) gives
   `RetriableFailure`; under a real `QueueWorker` the job dead-letters after
   three attempts and logs the ERROR line; the account is closed, has no
   `deleted_users` row, its `users.email` is intact, an UPDATE on one of its
   `email_sends` rows is still refused by `prevent_log_update`, and no
   `SEND_EMAIL` was enqueued. Dropping the trigger and calling the admin door
   erases it and enqueues the email.
4. Undeleted → `NotClosed`; undeleted and closed again → the first job is
   `CloseSuperseded` and writes nothing, the second erases. Both are `Success`.
5. `NotFound` and an unparseable payload are `PermanentFailure`.
6. A second job for an erased account is `AlreadyErased`: no second email.

**Erasure service** (`AccountErasureServiceTest`):

7. A closed account whose address is unverified is erased with
   `NO_VERIFIED_ADDRESS` and no `SEND_EMAIL` job.
8. A failing email enqueue (a test trigger raising on a `SEND_EMAIL` insert into
   `jobs`) rolls the erasure back: no `deleted_users` row, the address intact.

**The backfill** (migration test): a closed un-erased account gets one job
scheduled 7 days after its close; a live and an erased account get none.

**The walk** (`AccountErasureTest`, amended):

9. `jobs` rows naming the account, in every status, survive the walk unchanged
   (replaces the RFC 266 test that they were deleted).
10. A second `erase_account` on an erased account returns no rows, and an
    `email_sends` row keyed on its `deleted_user_id` written between the two
    calls is unchanged.

**Reaper and dead-letter log** (`QueueWorkerTest`):

11. `COMPLETED` and `DEAD_LETTERED` rows finished 31 days ago are deleted with
    their attempts; ones finished 29 days ago, and `SCHEDULED`/`RUNNING` rows of
    any age, are kept.
12. A dead-lettered job logs one ERROR line naming its type and id.

**Email** (`EmailSendHandlerTest`, `EmailSendsDaoTest`):

13. A payload with `deletedUserId` records `deleted_user_id`; one with `userId`
    records `user_id`; one with both is `PermanentFailure` and sends nothing.
    `EmailJobPayloadTest`: a job in the queued `{to, template, context, userId}`
    shape decodes to `Live`, and `Live`/null payloads are written in that same
    shape.
14. `AccountErasedEmailRenderer` renders the subject and body above from `{}`;
    `EmailRenderersTest` still holds the list equal to the enum.

**Config** (`AccountErasureConfigTest`): the default is 7; the environment
override is read; −1 fails.

**Admin** (`UsersResourceTest`): the erase page shows the confirmation line for
each outcome; Delete enqueues one `ERASE_ACCOUNT` job naming the account and its
`deleted_at`, scheduled `graceDays` after it; a second Delete of the closed
account is 404 and leaves `deleted_at` and the one job as they were.

## Implementation Plan

1. **Walk.** `erasure-walk-leaves-jobs.sql` + `ORDER` line; amend
   `AccountErasureTest` (tests 9, 10). Run `bin/test`.
2. **Reaper and dead-letter log.** Widen and rename in `QueueWorker`; the ERROR
   line; tests 11, 12.
3. **Email key.** `EmailAccountKey`, `NewEmailSend`, `EmailSendsDao`,
   `EmailService`, `EmailJobPayload.deletedUserId`, `EmailSendHandler`; update
   every caller to `Live`; test 13.
4. **Template.** `ACCOUNT_ERASED`, `AccountErasedEmailRenderer`,
   `emailRenderers`; test 14.
5. **Erasure service.** `ErasureOutcome`/`ErasureConfirmation`, the DAO reads
   (`lock`, `isErased`, `closedAt`; the address through `UsersDao`),
   `eraseClose`, the enqueue; admin-web wiring and the page lines; tests 7, 8
   and the admin test.
6. **The job.** `JobType.ERASE_ACCOUNT`, `AccountErasurePayload`,
   `AccountErasureConfig` + `service.conf`, `AccountErasureHandler`,
   registration in `queue-worker`; tests 3–6 and the config test.
7. **The close.** The enqueue in `AccountClosureService`, `rest-server` wiring;
   tests 1, 2.
8. **Backfill.** `schedule-erasure-of-closed-accounts.sql` + `ORDER` line; its
   test.
9. **End to end.** `AccountErasureEndToEndTest`. Full `bin/test`.

## Files Modified

- `db/schema/erasure-walk-leaves-jobs.sql`,
  `db/schema/schedule-erasure-of-closed-accounts.sql`, `db/schema/ORDER`
- `db/src/main/kotlin/ed/unicoach/db/dao/AccountErasureDao.kt`
- `queue/src/main/kotlin/ed/unicoach/queue/JobType.kt`, `QueueWorker.kt`,
  `QueueService.kt`
- `email/src/main/kotlin/ed/unicoach/email/` — `EmailJobPayload`,
  `EmailTemplate`, `EmailService`, `EmailSendHandler`, `EmailAccountKey`,
  `EmailExceptions`, `dao/NewEmailSend.kt`, `dao/EmailSendsDao.kt`
- `service/src/main/kotlin/ed/unicoach/account/` — `AccountClosureService`,
  `AccountErasureService`, `AccountErasureScheduler`, `ErasureOutcome`,
  `AccountErasureHandler`, `AccountErasurePayload`, `AccountErasureConfig`,
  `AccountErasedEmailRenderer`; `EmailRenderers.kt`; every `EmailService.send` /
  `EmailJobPayload` caller (`family/LinkEndedNotice` also takes
  `enqueueOrThrow`)
- `service/src/main/resources/service.conf`
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`
- `admin-web/` — `Application.kt`, `UsersResource.kt`, `ErasureView.kt`
- tests beside each
