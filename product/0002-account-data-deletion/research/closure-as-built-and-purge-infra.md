# Closure as built, and the purge infrastructure — research for brief 0002

**Scope.** What RFC 252's account CLOSE does in the code today (the code wins
over the RFC), how a closed account stays invisible, and which existing
machinery an ERASURE slice can reuse. Read at `main@00bdeb7e` in the
`unicoach-docs-0002-account-data-deletion` worktree, 2026-10-30.

**Method.** Static reading only: `rfc/252-the-deletion-partition.md` first, then
`AccountClosureService` and every DAO it calls, then grep over `db/schema/`,
`service/`, `rest-server/`, `admin-web/`, `cron/`, `queue/`, `bin/`, `ios-app/`
and the test trees. No Gradle, no tests, no database were run; every claim about
SQL is read from the migration or the DAO string, not observed live. Line
numbers are from this checkout.

---

## 1. What close does, as built

`AccountClosureService.close(userId)`
(`service/src/main/kotlin/ed/unicoach/account/AccountClosureService.kt:54-67`)
runs ONE `database.withConnection` transaction. In order:

| #    | Statement (as the DAO writes it)                                                                                                                                                                        | Table(s)                                                                | Where                                                                                                                                      |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| 1    | `SELECT * FROM users WHERE id = ? FOR UPDATE NOWAIT`, then ACTIVE-scope check in Kotlin; absent/closed → `NotFound`, no further write                                                                   | `users`                                                                 | `UsersDao.kt:88-115` via `lockLiveUser` `AccountClosureService.kt:70-77`                                                                   |
| 2    | `SELECT * FROM students WHERE user_id = ?` (ACTIVE default), `SELECT * FROM supporters WHERE user_id = ? AND deleted_at IS NULL`                                                                        | `students`, `supporters`                                                | `StudentsDao.kt:92-118`, `SupportersDao.kt:97-104`                                                                                         |
| 3a   | student side: `UPDATE supporter_student_links SET deleted_at = NOW(), version = version + 1 WHERE student_id = ? AND deleted_at IS NULL RETURNING *`                                                    | `supporter_student_links` (+ `_versions` by the `AFTER UPDATE` trigger) | `SupporterStudentLinksDao.sever` `:489-508`, called by `severAll` `:477-480`                                                               |
| 3b   | for EACH severed link, in the loop: read the other party's role row and `users` row in `ALL` scope, then `INSERT` one `SEND_EMAIL` job unless the recipient is closed or unverified (INFO line instead) | `jobs`                                                                  | `LinkEndedNotice.tell` `service/.../family/LinkEndedNotice.kt:66-92`; `QueueService.enqueue(session, …)` `queue/.../QueueService.kt:50-60` |
| 3c   | `UPDATE supporter_link_requests SET resolution = 'cancelled', resolved_at = NOW() WHERE student_id = ? AND resolution IS NULL RETURNING *`                                                              | `supporter_link_requests`                                               | `SupporterLinkRequestsDao.cancelAllPendingFor` `:341-357`                                                                                  |
| 3d–f | the same three on the supporter side (`WHERE supporter_id = ?`), telling each student                                                                                                                   | as above                                                                | `AccountClosureService.kt:91-97`                                                                                                           |
| 4    | `UPDATE supporter_invites SET revoked_at = NOW() WHERE inviter_user_id = ? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > NOW() RETURNING *`                                           | `supporter_invites`                                                     | `SupporterInvitesDao.revokeAllByInviter` `:208-220`                                                                                        |
| 5    | student only: `UPDATE cost_report_shares SET revoked_at = NOW() WHERE student_id = ? AND revoked_at IS NULL RETURNING *`                                                                                | `cost_report_shares`                                                    | `CostReportSharesDao.revokeLive` `:112-125`                                                                                                |
| 6    | student only: `UPDATE students SET version = ?, deleted_at = NOW() WHERE id = ? AND version = ? RETURNING *` (OCC)                                                                                      | `students` (+ `students_versions` by trigger)                           | `StudentsDao.delete` `:237-249` → `softDeleteReturning` `SqlSessionQueries.kt:475-488`                                                     |
| 7    | supporter only: `UPDATE supporters SET deleted_at = NOW(), version = version + 1 WHERE id = ? AND version = ? AND deleted_at IS NULL RETURNING *`                                                       | `supporters` (+ `_versions`)                                            | `SupportersDao.delete` `:142-162`                                                                                                          |
| 8    | `UPDATE users SET version = ?, deleted_at = NOW() WHERE id = ? AND version = ? RETURNING *`                                                                                                             | `users` (+ `users_versions`)                                            | `UsersDao.delete` `:288-299`                                                                                                               |
| 9    | **physical** `DELETE FROM user_auth_identities WHERE user_id = ?` — admitted by the guard only because step 8 already ran in this transaction                                                           | `user_auth_identities`                                                  | `UserAuthIdentitiesDao.deleteForClosedUser` `:123-126`; guard `db/schema/user-auth-identities-delete-on-closed-account.sql`                |
| 10   | `UPDATE sessions SET version = version + 1, is_revoked = true WHERE user_id = ? AND is_revoked = false`                                                                                                 | `sessions`                                                              | `SessionsDao.revokeAllForUser` `:258-268`                                                                                                  |

The route is `DELETE /api/v1/auth/me` (`AuthRoutes.kt:65`, handler `:150-166`):
`204` + cleared cookie on `Closed`; `401` + cleared cookie on `NotFound`.
OpenAPI `operationId: deleteAccount` (`api-specs/openapi.yaml:294`).

**Divergences from RFC 252 §1 (prose vs code):**

- **`supporter_link_requests` is a third two-party table** the close writes
  (steps 3c/3f), added by RFC 254 after 252 was approved. The service KDoc names
  all three (`AccountClosureService.kt:36-40`) and says plainly: _"A new such
  table needs the same treatment here; nothing checks for it."_
- The RFC's step 4 ("notify, for each severed link") is interleaved per link
  inside step 3 rather than a separate pass; same effect, same transaction.
- `close` takes no session token (RFC amendment already records this).
- Step 9 is the ONLY physical `DELETE` in the close. Everything else is an
  `UPDATE` that stamps a timestamp.

**What close deliberately leaves:** every other row the closer owns — `convos`,
`convo_requests/responses`, `claims`, `observations`, `commitments`,
`college_list_entries`, `money_profiles`, `admission_test_scores`,
`intended_subjects`, `sharing_permissions`, `share_events`,
`verification_tokens`, `extraction_runs`/`synthesis_runs`/`fit_lens_runs`,
`subscriptions`, `supporter_llm_costs`, and the `users` row's own `email`,
`name`, `display_name`, `password_hash` (`0001.create-users.sql:13-18`). The
`users_versions` history keeps every prior value of those too.

### The admin-only close path

It is in the separate `:admin-web` module, behind the `is_admin` gate
(`admin-web/.../auth/AdminAuth.kt:151-183`), not in `rest-server`:

- `POST /user/{id}/delete` →
  `Database.occSoftDelete(UsersDao, id, deleted = true)` → `UsersDao.delete`
  ONLY (`UsersResource.kt:132-133`, `OccDelete.kt:22-40`). No link sever, no
  notice, no invite/report revoke, no identity delete, no session revoke. The
  sessions keep resolving to nothing because session resolution reads the user
  in ACTIVE scope (§2), which is the only thing that ends the person's access.
- `POST /user/{id}/undelete` → `UsersDao.undelete` (`UsersResource.kt:135-136`,
  `UsersDao.kt:301-313`). **A reopen path exists for `users`** — relevant to any
  grace-period design (brief D2): an operator can reverse a close today, but
  only the `users` half; links, invites, identities and sessions stay gone.
- `POST /user/{id}/student/delete` → `StudentsDao.delete` on the student row
  alone (`UsersResource.kt:327-338`). `StudentsResource` registers no
  delete/undelete (`:144-145`). `SupportersDao` has no `undelete` at all ("no
  re-opening path", `SupporterService.kt:355`).

This is the path `ReportShareLookup` and `LinkEndedNotice` mean by "the operator
admin can close a users row without its students row"
(`ReportShareLookup.kt:50-57`). Admin-web runs with SSO verifiers disabled
(`admin-web/.../Application.kt:83-105`); it is operator tooling, not a user
surface.

---

## 2. Soft-delete readers: how a closed account stays invisible

**The mechanism is one predicate, applied per read.** `SoftDeleteScope`
(`db/.../models/SoftDeleteScope.kt:12-16`: `ACTIVE`, `DELETED`, `ALL`) renders
to `deleted_at IS NULL` / `IS NOT NULL` / `TRUE`
(`SqlSessionQueries.kt:611-616`). Every `SoftDeleteFindable.findById` without a
scope defaults to `ACTIVE` (`Dao.kt:30-34`). There is no row-level security, no
view layer, no global filter: a reader that passes `ALL` sees the closed row.

**Session resolution** — `AuthService.resolveSession`
(`service/.../auth/AuthService.kt:221-243`): token → `sessions` row →
`UsersDao.findById(session, userId)` with the ACTIVE default (`:235`) →
`NotFound` collapses to `null`. So a closed user's still-valid cookie resolves
to "no session" even where step 10 had not run (the admin path).

**Role rows** — `StudentsDao.findByUserId` ACTIVE default (`:92-118`);
`SupportersDao.findById/findByUserId` interpolate `${scope.predicate()}` (`:83`,
`:104`). The link roster joins `deleted_at IS NULL` on the LINK, the role row
AND the `users` row (`SupporterStudentLinksDao.kt:66-67`,
`listActiveLinkedStudents` `:270-287`), so a parent's coach cannot see a closed
student even if only one of the three rows were closed. The returning SSO path
reads in `ALL` scope on purpose and answers `Disabled`
(`AuthService.kt:534-535`) — but after RFC 252 the identity row is gone, so that
branch is reached only for an admin-closed account.

**Reads in `ALL` scope that would still show a closed account's data to a
human:**

| Surface                                            | What a human sees                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | Where |
| -------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----- |
| **admin-web, every resource**                      | Every list page is read in `ALL` scope and every detail page with `includeDeleted = true` (`AdminRouting.kt:72`, `:130`, `:205`). The `users` and `students` pages render `deletedAt` as a column (`UsersResource.kt:78`, `StudentsResource.kt:90`); the student detail page lists the closed student's convos in `ALL` scope including deleted ones (`StudentsResource.kt:309-322`); `ConvosResource` and `ConvoRequestsResource` render full turns (`ConvosResource.kt:46`, `:120`, `:188`); `LlmRequestsResource` is a read-only firehose of every `llm_requests`/`llm_responses` row, i.e. every transcript, chat and background (`LlmRequestsResource.kt` header); `MoneyProfilesResource`, `CollegeListEntriesResource`, `ClaimsResource`, `ObservationsResource`, `CommitmentsResource` likewise. **This is the main human-facing leak after a close.** |       |
| **Budget meter**                                   | `BudgetService.accountOfStudent` reads the student in `ALL` scope by design ("a closed profile's spend is still spend", `BudgetService.kt:143-166`), and `account_llm_cost` has no `deleted_at` predicate (`account-llm-cost.sql:22-27`). Not a human surface in itself, but it means the cost ledger must survive any erasure that keeps the meter honest.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |       |
| **Family Cost Report**                             | Fixed by RFC 252: `ReportShares.lookup` reads student and user in `ALL` scope and tests `deleted_at` itself, answering `AccountClosed` → 404 `MissReason.ACCOUNT_CLOSED` (`ReportShareLookup.kt:40-58`, `ServiceCostReportSource.kt:102`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |       |
| **Notices**                                        | `LinkEndedNotice` and `LinkRequestedNotice` read both parties in `ALL` scope but skip a closed recipient with an INFO line (`LinkEndedNotice.kt:72-81`, `LinkRequestedNotice.kt:57-64`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |       |
| **Link requests / invites**                        | `SupporterLinkRequestService` and `SupporterInviteService` read in `ALL` scope only to tell "closed" from "corrupt" and refuse (`SupporterLinkRequestService.kt:185`, `:211`; `SupporterInviteService.kt:279-305`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |       |
| **Periodic / queued jobs**                         | Both sweeps enumerate `SELECT id FROM students WHERE deleted_at IS NULL` (`StudentsDao.listActiveIds` `:272-277`; `SynthesisSweepHandler.kt:53`, `FitLensSweepHandler.kt:49`). The per-student handlers re-check in `ALL` scope and no-op on a closed student (`SynthesisService.kt:127-134`, `FitLensService.kt:228-233`, `ExtractionService.kt:155-161`). A job queued before the close therefore does not bill after it.                                                                                                                                                                                                                                                                                                                                                                                                                                    |       |
| **Parent's own transcript about a closed student** | Not an `ALL`-scope read but worth naming: a supporter's convo carries `party = 'supporter'`, its own `supporter_id`, AND the student's `student_id` ("about whom", `db/schema/convos-party.sql:5`, `:26-30`), and its turns are the parent's words and the coach's words about the student. RFC 252 keeps the parent's conversations ("everything you have entered are still yours", §3). Erasure of the student does not touch them; whether it should is a D1 question the brief has not asked.                                                                                                                                                                                                                                                                                                                                                              |       |

`StudentGraduation` also reads in `ALL` scope (`StudentGraduation.kt:16-37`) for
the FK-guaranteed-row reason, not to surface anything.

---

## 3. Periodic jobs (RFC 97)

**Declaring one is a row, not code.** `periodic_jobs`
(`db/schema/0029.create-periodic-jobs.sql:15-33`): `name` PK, `job_type` (a
queue `JobType` value, validated at read time, no SQL CHECK), `payload` JSONB,
`schedule` (5-field cron), `timezone`, `next_run_at` (the claim key),
`last_run_at`, `enabled`. Rows are "operator-managed config" with no soft-delete
or physical-delete guard (`:6-7`). Each existing job is seeded by a migration
that `INSERT`s one row with `enabled = FALSE` and a `next_run_at` on the next
boundary rather than `NOW()` (`0030.seed-synthesis-periodic-job.sql:13-18`,
`0033.seed-fit-lens-periodic-job.sql:15-20`). An operator flips `enabled`
through admin-web's `Enable`/`Disable` actions (`PeriodicJobsResource.kt:27-37`,
`:176-179`).

**Scheduling.** The `:cron` process (`cron/src/main/kotlin/ed/unicoach/cron/`)
runs `PeriodicScheduler` (`PeriodicScheduler.kt:51-117`): a one-minute tick that
drains due rows, each claimed with
`SELECT *, NOW() AS db_now FROM periodic_jobs WHERE enabled AND next_run_at <= NOW() ORDER BY next_run_at LIMIT 1 FOR UPDATE SKIP LOCKED`
(`PeriodicJobsDao.claimDue` `:90-120`), advanced with
`SET next_run_at = ?, last_run_at = NOW()` (`advance` `:131-144`), and enqueued
onto `jobs` via `JobsDao` in the SAME transaction
(`PeriodicScheduler.kt:37-43`). Cron knows nothing about the work; it only
enqueues.

**Handling.** The queue worker registers `JobHandler`s in
`queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt:118-172`; a
`JobType` is one enum entry (`queue/.../JobType.kt:3-33`). The sweep precedent:
`SynthesisSweepHandler` (`service/.../synthesis/SynthesisSweepHandler.kt:34-82`)
declares `jobType = SYNTHESIS_SWEEP`, a
`JobTypeConfig(concurrency = 1,
maxAttempts = 1, lockDuration = 10m, executionTimeout = 5m)`,
enumerates `StudentsDao.listActiveIds`, and fans out one per-student job each,
best-effort, with "next tick re-produces the sweep" as the retry. Registration
is gated by the same config switch as the per-item handler so producer and
consumer are present together or absent together (`Application.kt:156-160`).

**The two rules, cited.** `cron/INVARIANTS.md:12-25` — _scheduling decisions use
the database clock, never a host wall clock_ (`claimDue` returns `NOW()` and the
next fire is computed from it; `Results.kt:43-45`); and
`cron/INVARIANTS.md:27-38` — _every periodic job MUST be idempotent_, "safe to
run concurrently with itself and to re-run", because scheduling is best-effort
single execution (overlap, duplicates across ticks, a lost tick). The jobs
reaper uses the same clock discipline:
`DELETE FROM jobs WHERE status IN (…)
AND updated_at < NOW() - ?::interval`
(`JobsDao.deleteBefore` `:406-425`). (`INVARIANTS.md` files are retired as a
mechanism, per CLAUDE.md; this one is still an accurate description of
`PeriodicJobsDao`.)

**What a "purge closed accounts older than N days" job would need:**

1. `queue/src/main/kotlin/ed/unicoach/queue/JobType.kt` — a
   `PURGE_CLOSED_ACCOUNTS_SWEEP` (and, on the sweep precedent, a per-account
   `PURGE_CLOSED_ACCOUNT`).
2. A seed migration under `db/schema/` (unnumbered name, `ORDER` line by
   `ship-order`, RFC 180) inserting the `periodic_jobs` row, `enabled = FALSE`.
3. A sweep handler in `service/` that selects
   `users WHERE deleted_at IS NOT NULL AND deleted_at < NOW() - N` — there is no
   such read today; `UsersDao.list(session, scope, …)` exists for admin paging
   (`UsersDao.kt:404-409`) and `SoftDeleteScope.DELETED` exists but has no
   production caller — and fans out per account.
4. A per-account handler that runs the purge (§"Missing" below: the purge itself
   does not exist).
5. Registration in `queue-worker/.../Application.kt`, gated by a config switch
   like `synthesis.enabled`.
6. Admin visibility comes free: the row appears in `PeriodicJobsResource`.

Both handlers must be idempotent by the invariant above — a purge that is a set
of `DELETE … WHERE owner = ?` statements is naturally so.

---

## 4. Configuration: where "N days" would live

Three mechanisms exist; they are for different kinds of value.

**HOCON `.conf` with an env override — the house pattern for an operational
knob.** Every module ships a `src/main/resources/<module>.conf` (`service.conf`,
`queue.conf`, `cron.conf`, `session.conf`, …). The idiom is a default followed
by `${?ENV_VAR}`:

```
budget {
  freeAllowanceUsd = 5.00
  freeAllowanceUsd = ${?FREE_ALLOWANCE_USD}
}
```

(`service/src/main/resources/service.conf:649-655`; likewise
`synthesis.enabled = ${?SYNTHESIS_ENABLED}` `:798-800`,
`extraction.debounce = 5m` / `${?EXTRACTION_DEBOUNCE}` `:774-777` — a duration
knob, the closest shape to "N days"). A value with NO default
(`session.cookieSecure = ${SESSION_COOKIE_SECURE}`,
`common/.../session.conf:24`) fails the JVM at boot when the environment forgets
it. Env values come from the layered dotenv (`bin/common:7-29`, `.env` +
`.env.dev`/`.env.<env>`) and, in the cloud, from SSM parameters
(`infra/ssm.tf:53-80`: plain `String` for config, `SecureString` for the
out-of-band secrets list at `:43-50`). SSM is a delivery channel for env vars,
not a separate config system.

**`policy_parameters` — a product/reference CONSTANT with a citation, not a
knob.** `db/schema/0077.create-and-seed-policy-parameters.sql:38-80`: one row
per `(award_year, parameter)`, `value INTEGER`, `source_name`, `source_url`; the
vocabulary is a closed CHECK mirrored by exactly one Kotlin enum
(`PolicyParameter`, `:7-8`), corrected only by a new migration upserting on the
natural key (`:19-23`). Read by `PolicyParametersDao` →
`FederalAidPolicyService`. It exists so the COACH can state a figure with its
source; it is not something an operator tunes per environment.

**Code defaults for scheduler/runtime timings.** `cron.conf:1-5` says it
outright: the tick interval is a code default
(`PeriodicScheduler.tickInterval
= 1.minutes`, `:56`); the finished-job
retention is `QueueWorker.completedJobRetention = 30.days`
(`QueueWorker.kt:35`), a constructor default with no conf key and no env
override.

**For "N days before purge":** the house answer is the first pattern — a
`purge { closedAccountRetention = 30d; closedAccountRetention = ${?…} }` block
in `service.conf` read by a `*Config.from(config)` class (the
`BudgetConfig`/`SynthesisConfig` shape), because it is an operational value that
legitimately differs between dev (minutes) and prod (days) and that an operator
may move without a migration. The `periodic_jobs.payload` JSONB is also
available to carry it per row, but nothing reads a payload for a tunable today
(both seeds are `'{}'`).

---

## 5. Subscriptions after close

**The row** (`db/schema/0042.create-subscriptions.sql:25-60`, re-keyed by
`subscriptions-key-on-account.sql`): `id` (ours), `user_id` (ours, NOT NULL,
`REFERENCES users(id)` with no `ON DELETE` action, `:54`, `:98`),
`original_transaction_id` (**Apple's** stable per-subscription identity, the
`UNIQUE` upsert key `:37`, `:50`), `product_id` (Apple's SKU), `status` (Apple's
enum snake-cased, `:40-42`), `period_start`/`period_end` (Apple's window).
**There is no `environment` column** — the sandbox/production distinction lives
in `appstore.conf`, not the row. `student_id` was dropped by the re-key
migration (`subscriptions-key-on-account.sql:102-134`). `subscriptions_versions`
records every state including `user_id` (`:58`). The table carries a
`prevent_physical_delete` guard (§9 list).

**Does the refresh keep running after close?** Yes, by design.
`SubscriptionService.refresh(originalTransactionId)` finds the row by
transaction (`SubscriptionsDao.findByOriginalTransactionId` `:212-221`, no join
to `users`, no `deleted_at` test) and re-records under `existing.userId`
(`SubscriptionService.kt:121-135`). Nothing in `SubscriptionRefreshHandler`
(`service/.../subscriptions/SubscriptionRefreshHandler.kt:22-53`) looks at the
account. RFC 252 states this is correct ("the entitlement state is Apple's
fact"). An erasure that deletes the `users` row would make the refresh's upsert
fail on the FK, and one that keeps a tombstone `users` row keeps the refresh
working.

**Who needs the row after close, other than rebind-on-return?** Only the refresh
above and the admin's view. The rebind is the `ON CONFLICT` arm:
`user_id = EXCLUDED.user_id … WHERE … OR (subscriptions.user_id <> EXCLUDED.user_id
AND EXISTS (SELECT 1 FROM users u WHERE u.id = subscriptions.user_id AND u.deleted_at IS NOT NULL))`
(`SubscriptionsDao.kt:109-128`). **That arm depends on the closed `users` row
still existing with `deleted_at` set.** A purge cannot physically delete a
`users` row while a `subscriptions` row references it (the FK takes no
`ON DELETE` action, so Postgres refuses the delete), and `subscriptions` itself
carries `prevent_physical_delete`; so the only consistent erasure shapes are a
PII-blanked tombstone `users` row (rebind keeps working) or re-keying the
subscription to a sentinel account (rebind then sees a LIVE owner and answers
`OwnedByOtherAccount` — the returning subscriber is locked out). The budget
meter does not read `subscriptions` for a closed account because no request can
arrive for one.

**The webhook** (RFC 112): `POST /api/v1/subscriptions/apple-notifications`
(`AppleNotificationRoutes.kt:170`). It verifies the signed payload and **writes
nothing but one `jobs` row**:
`enqueue(REFRESH_SUBSCRIPTION,
SubscriptionRefreshPayload(originalTransactionId, notificationUuid,
notificationType))`
(`:109-121`). There is no notifications table; the `jobs.payload` is the only
record, and `COMPLETED` jobs are reaped after 30 days (`QueueWorker.kt:35`,
`:290-301`). Everything is keyed by Apple's `original_transaction_id`; a
notification for a transaction no row owns is `NotBound` and logged
(`SubscriptionRefreshHandler.kt:69-79`).

---

## 6. LLM cost ledger

**Shape.** Cost is frozen per call on `llm_responses.cost_nanodollars`
(`0041.add-llm-response-cost.sql:22`). `llm_requests` holds the provider, model,
**the system prompt verbatim and the full message array** (`content
JSONB`,
`0038.create-llm-call-log.sql:29-51`) and has NO account key; it is linked to a
person only through the domain rows that point at it. The student spine
`student_llm_cost` (`0041…:58-80`) unions `convo_requests → convos.student_id`,
`extraction_runs.student_id`, `synthesis_runs.student_id` and `fit_lens_runs`
(two request ids). The supporter spine is
`supporter_llm_costs(llm_request_id PK, supporter_id)`
(`supporter-llm-cost.sql:20-26`), an append-only log with an UPDATE guard and
deliberately no DELETE guard: "retention on the call log is what removes it"
(`:32-41`). `account_llm_cost` (`account-llm-cost.sql:32-55`) re-keys both onto
`users.id` via INNER joins to `students`/`supporters`, with no `deleted_at`
predicate on purpose (`:22-27`).

**Readers.** Exactly one production reader: `AccountLlmCostDao`
(`db/.../dao/AccountLlmCostDao.kt:12-40`, `lifetimeCost` and the windowed read)
— "the ONLY cost read production makes". It feeds `BudgetService` →
`Entitlement` → the four turn gates' `402 coaching_budget_exhausted`
(`rest-server/.../Routing.kt:102-108`, `ConvoRoutes.kt:329`,
`SupporterConvoRoutes.kt:224`) and the student's own usage bar
`GET /students/me/coaching-usage` (`CoachingUsageRoutes.kt:19-26`). Admin-web
shows the underlying `llm_requests` rows (`LlmRequestsResource`), not a
per-account total. There is no finance report, no export, no aggregate over
accounts.

**If a closed account's cost rows were ERASED** (the
`llm_requests`/`llm_responses` rows and their domain pointers): nothing in
production breaks for that account — no request can arrive for it, so no gate
reads it. Two things change: (a) the `prevent_log_delete` triggers on
`llm_requests`, `llm_responses`, `llm_responses_raw`, `convo_requests`,
`convo_responses`, `extraction_runs`, `synthesis_runs`, `fit_lens_runs` (§9)
refuse the delete, so erasure needs the guard-breach D3 asks for; (b) if the
SAME person returns on a new account, their lifetime free allowance restarts —
which is already the case after RFC 252 (new `users.id`, empty ledger), so
erasure does not widen it. `supporter_llm_costs` has no delete guard, so it can
go with its `llm_requests` parent only if that parent's guard is breached first
(PK FK).

**If they were ANONYMISED (keys severed, rows kept):** the views are INNER joins
on real FKs (`account-llm-cost.sql:43`, `:55`; `student_llm_cost` through
`convos.student_id`), so a row can be re-pointed only at another existing
`students`/`supporters`/`users` row — there is no nullable owner column and
`convos.student_id` is trigger-immutable (`convos-party.sql:54-57`). Severing
therefore means either (i) a sentinel "erased" account that absorbs the cost
rows (it would then show a nonsense lifetime total, harmless because it never
sends a turn), or (ii) keeping a tombstone `users`/`students`/`supporters` row
with its PII columns blanked, which the schema does not allow today
(`users.email NOT NULL` + format CHECKs `0001:13-30`, `name NOT NULL`;
`users_email_unique_active_idx` is partial on `deleted_at IS NULL` `:41`, so a
closed row's email need not be unique, but it must still look like an email).
The transcripts in `llm_requests.content` are the part that cannot be
"anonymised" by a key change at all — the student's words are the payload.

---

## 7. Dev / test reset: what exists

- **`bin/db-reset`** (`bin/db-reset:6-16`) drops and recreates the WHOLE
  `POSTGRES_DB` (`db-drop → db-create → db-migrate`). Per-checkout database, so
  safe, but it is a whole-world reset, not a per-account one.
- **`bin/state-apply [-f] <world.yaml>`** (RFC 138; `bin/state-apply:6-19`,
  `fixture/.../StateApplyApplication.kt`, `WorldFile.kt:14-31`): declarative,
  create-only, local-only. v1 supports ONLY `users:` (email, password, name,
  verified, admin). `-f` runs `db-reset` first. It builds a clean user; it does
  not clear or rebuild a student's coaching state.
- **Admin-web per-student actions** are `Trigger synthesis` and
  `Trigger
  fit-lens` (`StudentsResource.kt:154-172`) — enqueue-only. No action
  clears claims, observations, commitments, college list, money profile or
  convos. Admin resources are read-only for the coaching tables
  (`ObservationsResource.kt:88-89`, `SynthesisRunsResource.kt:138-139`, etc.).
- **Test fixtures** build a clean student by inserting rows directly:
  `CoachingTestDb.createStudent(label)`
  (`service/src/test/.../CoachingTestDb.kt:172-183`) inserts `users` +
  `StudentRowFixture.insert` (`db/src/testFixtures/.../StudentRowFixture.kt`);
  suites clean up with `TRUNCATE TABLE … CASCADE` (`CoachingTestDb.kt:80-84`).
  **`TRUNCATE` does not fire the row-level `BEFORE DELETE` guards** — which is
  exactly why the test suite can "erase" and production cannot. It is not a
  per-account operation.
- **`bin/db-dump`** (`bin/db-dump:4-9`) is a `pg_dump -Fc` of the whole DB for
  worktree migration, not an account export.

**Net:** there is no "reset this account's coaching state" affordance —
user-facing, admin or dev. The only clean slate is a new account or a new
database. Brief 0002's "reset" would be new code; the traversal it needs is the
same one a purge needs (the brief's "delete is the superset" holds).

---

## 8. Data export

**None.** No route, handler, OpenAPI operation, public-web page, iOS screen or
`bin/` script matches export / download / takeout / portability
(`rest-server/src/main`, `public-web/src/main`, `api-specs/openapi.yaml`,
`ios-app/*.swift`, `bin/`). The privacy page's access clause is "by contacting
us" (`PrivacyPage.kt:50`). GDPR Art. 15/20 is a manual process today; an
export-before-erase step (brief D5) would be built from nothing.

---

## 9. Tests that walk the schema — precedent for a drift guard

RFC 252 refused a per-table list ("nothing would keep a per-table list
current"). Brief 0002 wants the opposite: a test that fails when a new
account-linked table is NOT covered. The suite already has the shape, three
times, in `db/src/test/kotlin/ed/unicoach/db/`:

- **`NoUrlColumnOutsideExternalDocumentsTest`** (`:11-31`, query `:63-82`) —
  reads `information_schema.columns` for the whole public schema, matches every
  column whose name says url/link against an explicit **allowlist with a reason
  per entry** (`:53-61`), and **proves anti-vacuity** by creating a probe column
  in a rolled-back transaction and asserting the guard names it (`:27-30`,
  `:121`). This is the closest precedent: "every X in the live schema is either
  covered or listed with a reason".
- **`PartialDateStorageHasOneReaderTest`** (`:20-45`) — three halves: a schema
  half over `information_schema.columns`, a source half whose module list is
  DERIVED from `settings.gradle.kts` so a new module joins the sweep
  automatically and an empty sweep fails rather than passing, and the claim
  itself. Its KDoc states the rule the drift guard needs: _"a guard inside the
  artifact under rewrite is not a guard."_
- **`PublisherMoneyColumnsAreGoneTest`** (`:16`, `:129`) — the same discipline
  for dropped columns.
- Constraint-level introspection is common in DAO tests: `pg_constraint` /
  `pg_get_constraintdef` in `SupporterStudentLinksDaoTest.kt:733`,
  `CostReportSharesDaoTest.kt:221`, `SharingPermissionsDaoTest.kt:111`,
  `ProgramMoneyDaoTest.kt:327`, `CollegeSearchIndexRebuildTest.kt:372`, `:998`;
  `information_schema.referential_constraints` in
  `IntendedSubjectsDaoTest.kt:354-355`.
- **`ErrorCodeTest`** (`rest-server/src/test/.../models/ErrorCodeTest.kt:8-26`)
  is the enum-shaped build guard (every wire code lowercase, distinct).
- Shell-side: `bin/db-scripts-tests`, `bin/db-create`, `bin/db-adopt` read
  `pg_tables`/`information_schema` for harness purposes, not as a rule.

**What a drift guard would query.** The account-linked set is derivable from the
catalog: every table with a foreign key whose referenced table is `users`,
`students` or `supporters`, transitively (`pg_constraint WHERE
contype = 'f'`).
Read statically from `db/schema/`, that set today is:
`admission_test_score_declines, admission_test_scores, claims,
college_list_entries, commitments, convos, cost_report_shares,
extraction_runs, fit_lens_runs, fit_suggestions, intended_subject_status,
intended_subjects, money_profiles, observations, sessions, share_events,
sharing_permissions, students, students_versions, subscriptions,
supporter_invites, supporter_link_requests, supporter_llm_costs,
supporter_student_links, supporters, supporters_versions, synthesis_runs,
user_auth_identities, users_versions, verification_tokens`
(30 direct; plus `*_versions` and `*_support` children and
`convo_requests/responses` one hop further, and
`llm_requests/llm_responses(_raw)` reachable only through `convo_requests`, the
run tables and `supporter_llm_costs`). A guard built the `NoUrlColumn…` way —
catalog walk, allowlist with reasons for "kept on purpose" (`subscriptions`, the
cost spine), anti-vacuity probe — fits Ian's objection to page-wide rules only
if each entry carries its reason; that is the discipline the precedent already
enforces.

**Delete guards, inventoried** (static grep of `BEFORE DELETE ON`): 34 tables
carry one — the entity guard `prevent_physical_delete()`
(`0000.shared-functions.sql:34-39`, "Use soft deletes by setting deleted_at") on
`users, students, supporters, convos, claims, commitments, observations,
college_list_entries, money_profiles, subscriptions, supporter_student_links,
supporter_link_requests, user_auth_identities, …`;
the log guard `prevent_log_delete()` (`0006…:33-38`, "prune by
partition/retention") on
`llm_requests, llm_responses, llm_responses_raw, convo_requests,
convo_responses(_raw), extraction_runs, synthesis_runs, fit_lens_runs,
fit_suggestions, email_sends, share_events, …`;
and `prevent_delete()` (`0023…:33-38`) on `colleges`. **Account-linked tables
WITHOUT a delete guard:** `sessions` (physical delete is normal,
`SessionsResource` KDoc), `verification_tokens`, `cost_report_shares`,
`supporter_invites`, `supporter_llm_costs`, `admission_test_score_declines`,
`jobs`, and every `*_versions` table — but the versions tables pin their parent
with `ON DELETE RESTRICT` (`0001:48`, `0005:39`,
`supporters-and-student-links.sql:114`, `0042:70`), so the parent cannot go
while its history stays. The one guard that already admits a conditional delete
is the RFC 252 identity guard
(`user-auth-identities-delete-on-closed-account.sql`: `RETURN OLD` only when the
owning `users` row has `deleted_at IS NOT NULL`, else
`RAISE … CONSTRAINT
= 'user_auth_identities_owner_closed'`). It is the template
for D3.

---

## 10. Door copy, quoted exactly

**iOS — `ios-app/UnicoachiOS/SettingsView.swift`**

- Line 290 / 293 (the button, with the cross-reference comment at 286-288 naming
  `PrivacyPage.kt`): `"Delete account"`
- Line 319 (prompt header): `"Delete your account?"`
- Line 323 (prompt body):
  `"Your account is closed and you are signed out on every device. Anyone connected to you is told the connection has ended — not why. Their own accounts are not affected."`
- Line 350 / 354 (confirm button): `"Delete account"`
- Lines 390-391 (billing notice, conditional vs known):
  `"If you subscribe through Apple, your subscription continues until you cancel it."`
  / `"Your subscription is billed by Apple and continues until you cancel it."`

**iOS — `ios-app/UnicoachiOS/LoginView.swift`**

- Line 136: `"Your account has been closed."`
- Line 140:
  `"Signed in with Apple? To remove unicoach from your Apple ID too, go to Settings › your name › Sign-In & Security › Sign in with Apple."`
  (KDoc at 130-133: shown to every closed account because the client cannot tell
  the provider.)

**iOS — `ios-app/UnicoachiOS/FamilyConnectionsSection.swift`** (disconnect, for
the "stay as they are" wording that erasure may need to revisit)

- Line 274:
  `"Disconnect \(connection.name)? They lose access right away. Your account and theirs stay as they are. We let them know the connection ended."`
- Line 276: the mirror,
  `"Disconnect from \(connection.name)? You lose access right away. …"`

**public-web —
`public-web/src/main/kotlin/ed/unicoach/web/render/PrivacyPage.kt`**

- Lines 30-33 (what we collect):
  `"We collect the account information you provide — such as your name and email address — and the essay content you submit so the service can give you feedback."`
  (Note: "essay content" predates the coach; the page is stale beyond the
  deletion sentence.)
- Lines 46-48 (comment naming the iOS control), 50-53 (the clause):
  `"You may request access to your account information by contacting us. You can close your account in the app: Settings › Delete account. Some information may be retained as required to operate the service or to comply with legal obligations."`

The email the other party receives says nothing about closure by design
(`LinkEndedNotice.kt:24-28`); its text is in `LinkEndedEmailRenderer` (`:135+`)
and would not change with erasure.

---

## Reusable for erasure (what exists)

- **The close itself as the first phase.** `AccountClosureService` is already
  the "access ends now" half of D2; a purge runs later against rows whose
  `users.deleted_at` is older than N. The three two-party tables are already
  written from the closer's side only, so a purge never has to decide ownership
  of a link row — it is already severed and history-stamped.
- **The periodic machinery, end to end.** `periodic_jobs` row + seed migration +
  `PeriodicScheduler` + `JobType` + a `JobHandler` registered in the worker,
  with the sweep/fan-out precedent, the DB-clock claim, the idempotency rule,
  admin enable/disable, and the dead-letter/attempt log for failures. Nothing
  new is needed to RUN a purge on a schedule.
- **The conditional delete-guard pattern.** The identity guard admits a physical
  `DELETE` only when the owner is closed. The same function shape, parameterised
  by the owner column, is D3's "guard that admits a declared purge context" —
  and `prevent_physical_delete` / `prevent_log_delete` are shared functions in
  `0000`/`0006`, so a replacement is one migration per table, not a rewrite.
- **The HOCON-plus-env knob** for N days, read through a `*Config.from`.
- **The catalog-walk test discipline**
  (`NoUrlColumnOutsideExternalDocumentsTest` and siblings) for the drift guard,
  including the anti-vacuity probe.
- **`SoftDeleteScope.DELETED`** exists in the enum and the predicate helper with
  no production caller — the purge sweep's read.
- **Subscription rebind and refresh** need no change IF the closed `users` row
  survives as a tombstone (they read `users.deleted_at`).
- **The cost ledger** needs no change if its rows and their owner rows survive;
  the meter already ignores `deleted_at`.
- **`UsersDao.undelete`** and admin `POST /user/{id}/undelete` are a
  reopen-within-grace primitive for the `users` half, if D2 wants one.

## Missing for erasure (what must be built)

- **The purge itself.** No code deletes or blanks any account-owned row other
  than `user_auth_identities`. Needed: a traversal from `users.id` through
  `students.id`/`supporters.id` to every child (the 30 direct tables above and
  their `*_versions`/`*_support`/`convo_*` children), with a per-table decision
  (D1): delete, or keep with keys severed.
- **A way past 34 delete guards and the `RESTRICT` history FKs**, per table,
  that still refuses every non-purge delete (D3). The versions tables mean a
  parent cannot be deleted without its history going first, and the history
  holds every prior PII value.
- **An anonymisation target the schema can hold.** `users.email`/`name` are NOT
  NULL with format CHECKs; `convos.student_id` is immutable by trigger; the cost
  views are INNER joins on real FKs. Severing keys needs either nullable owner
  columns (a schema change on the cost spine) or a tombstone row whose PII
  columns are legally blank — neither exists.
- **Transcripts with no account key.** `llm_requests.content`/`system`,
  `llm_responses.content`, `llm_responses_raw` are reachable only through
  `convo_requests`, the three run tables and `supporter_llm_costs`; the
  traversal must follow those pointers or the student's words stay. A parent's
  convo ABOUT a closed student (`convos.party = 'supporter'`, `student_id` set)
  is a cross-party case the brief has not decided.
- **Email addresses outside `users`:** `email_sends.recipient_email`
  (`0008…:18`, log-guarded) and the `to` field of every `SEND_EMAIL`
  `jobs.payload` (only `COMPLETED` jobs are reaped, after 30 days;
  `FAILED`/dead-lettered ones persist). `user_auth_identities` provider email is
  already gone at close.
- **The sweep read** (`users WHERE deleted_at < NOW() - N`) and the config key
  for N.
- **The drift guard test** (catalog walk + allowlist with reasons + probe).
- **A "reset" door** — none exists at any level; it is the same traversal minus
  the identity rows.
- **Export** — nothing exists for Art. 15/20.
- **Copy**: `SettingsView.swift:323`, `LoginView.swift:136`,
  `PrivacyPage.kt:50-53` all say "closed"/"retained"; erasure changes each.
- **Admin visibility of closed data** is total today (every admin list in `ALL`
  scope); an erasure must decide what the operator sees of a purged account (a
  tombstone row? nothing?) — admin-web's `get(includeDeleted =
  true)` will 404
  cleanly on a physically deleted row, but edges that join from a surviving row
  (e.g. a subscription's `user_id`) will not.

## Could not determine

- Whether `SessionCleanupJob` (`service/.../auth/SessionCleanupJob.kt`, calls
  `expireZombieSessions`) runs anywhere: no wiring was found in any
  `Application.kt`, so revoked/expired `sessions` rows appear to accumulate.
  Verify at runtime before relying on it for purge.
- The RFC 252 research archive (`.scratch/ship/rfc-252/research/`) is not in the
  tree; its §2e/§4 findings are cited above only as the RFC restates them.
- Live catalog facts (the actual FK graph, including migrations that re-pointed
  columns) were read from migration files, not from a running database; the
  drift guard should derive the set from `pg_constraint`, not from this
  document.
