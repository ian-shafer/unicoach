# RFC 97: Periodic Task Infrastructure

## Executive Summary

A general periodic-task framework: a new `:cron` process runs a one-minute loop
that atomically claims due rows from a `periodic_jobs` table and enqueues each
onto the existing queue; the work itself runs as an ordinary queue job. The flow
is one-directional — **cron feeds the queue, never the reverse** — so the
scheduler stays domain-agnostic (it reads a row's `job_type`/`payload` and
enqueues) and every consumer reuses the queue's hardened execution (concurrency,
`executionTimeout`, retry, dead-letter, attempt logging).

Synthesis is the first consumer, migrating off its manual trigger. RFC 93 built
`SYNTHESIZE_STUDENT` as an enabled queue consumer with no producer — it only ran
via manual `bin/q-enqueue`, its periodic trigger explicitly deferred to "a
future scheduler/cron RFC" (rfc/93-synthesis.md:45). This is that RFC. A new
`SYNTHESIS_SWEEP` dispatcher job (enqueued by cron on a daily schedule)
enumerates active students and fans out one `SYNTHESIZE_STUDENT` per student;
the existing `SynthesisHandler` and `SynthesisService` are unchanged.

A recipe, `recipes/PERIODIC_JOB.md`, documents adding a periodic job.

## Detailed Design

### Architecture

Three tiers, one-directional:

```
periodic_jobs (schedule)      :cron process (1-min loop)          jobs (queue)
  name, job_type, payload,  ─▶  claims due row, advances       ─▶  SYNTHESIS_SWEEP
  schedule, timezone,           next_run_at, enqueues                  │ queue-worker
  next_run_at, last_run_at      job_type/payload (one txn)             ▼
                                                              SynthesisSweepHandler
                                                                enqueues per student
                                                                       ▼
                                                              jobs: SYNTHESIZE_STUDENT × N
                                                                       │ queue-worker
                                                                       ▼
                                                              SynthesisHandler → synthesize()
```

The `:cron` process only schedules (claim + enqueue). It runs no domain work and
knows nothing about synthesis or students. The `queue-worker` process runs all
handlers. The two are separate programs; `:cron` depends on `:queue` (for
`JobType`, `JobsDao`) and `:db`, never the reverse.

### Data model: `periodic_jobs` (migration `0029.create-periodic-jobs.sql`)

The `0029`/`0030` numbers assume `0028` is the current head; if another RFC
lands a `0029` first, renumber both to the next free pair at implementation time
(the migration chain is strictly sequential).

A mutable operational table modeled on `jobs`
(`db/schema/0003.create-queue.sql`): `TEXT`/`JSONB` columns, length/size CHECKs,
a two-timestamp split with an `updated_at` trigger. It is **not** a domain
entity — no `uuidv7` id (the `name` is the natural key), no soft-delete or
physical-delete guards (rows are operator-managed config).

```sql
CREATE TABLE periodic_jobs (
    name         TEXT        NOT NULL PRIMARY KEY,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    job_type     TEXT        NOT NULL,   -- a queue JobType value to enqueue
    payload      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    schedule     TEXT        NOT NULL,   -- UNIX 5-field cron
    timezone     TEXT        NOT NULL DEFAULT 'UTC',
    next_run_at  TIMESTAMPTZ NOT NULL,   -- materialized next fire; the claim key
    last_run_at  TIMESTAMPTZ NULL,       -- audit: last time the row was claimed
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,

    CONSTRAINT periodic_jobs_name_length_check     CHECK (length(name) <= 128),
    CONSTRAINT periodic_jobs_job_type_length_check CHECK (length(job_type) <= 128),
    CONSTRAINT periodic_jobs_schedule_length_check CHECK (length(schedule) <= 256),
    CONSTRAINT periodic_jobs_timezone_length_check CHECK (length(timezone) <= 64),
    CONSTRAINT periodic_jobs_payload_size_check    CHECK (octet_length(payload::text) <= 65536)
);

-- Claim predicate: the due, enabled rows, cheapest next-fire first.
CREATE INDEX periodic_jobs_due_idx ON periodic_jobs (next_run_at) WHERE enabled;

-- updated_at maintenance, mirroring update_jobs_timestamp (0003).
CREATE OR REPLACE FUNCTION update_periodic_jobs_timestamp() RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END; $$ LANGUAGE plpgsql;
CREATE TRIGGER trigger_03_enforce_periodic_jobs_updated_at
BEFORE UPDATE ON periodic_jobs FOR EACH ROW EXECUTE PROCEDURE update_periodic_jobs_timestamp();
```

`job_type` is `TEXT` validated in the application (mapped through
`JobType.fromValue` at read time, as `jobs.job_type` is); no SQL CHECK
enumerates the queue's `JobType` values, to avoid coupling the schema to the
enum. `schedule` is the source of truth; `next_run_at` is a value derived from
it and must be recomputed whenever `schedule` changes.

### Data model: seed (`0030.seed-synthesis-periodic-job.sql`)

```sql
INSERT INTO periodic_jobs (name, job_type, payload, schedule, timezone, next_run_at, enabled)
VALUES (
  'synthesis', 'SYNTHESIS_SWEEP', '{}', '0 3 * * *', 'UTC',
  date_trunc('day', NOW() AT TIME ZONE 'UTC') + INTERVAL '1 day 3 hours',
  FALSE
);
```

`'0 3 * * *'` = 03:00 UTC daily; `next_run_at` seeds to the next 03:00 UTC
boundary (not `NOW()`, which would fire on the first tick after deploy), and the
scheduler owns the column thereafter. The row seeds `enabled = FALSE` so the
sweep never fires against an environment whose worker has synthesis off (its
`SYNTHESIZE_STUDENT` jobs would hit "no handler registered"); an operator flips
`enabled` to `TRUE` via the admin toggle (§Admin) in the same environments where
`synthesis.enabled = true` gates the worker's handler registration. The two
switches are coupled by operator convention, not enforced in schema — the
sweep's own no-handler behavior is inert (§Error handling), so a mismatch
degrades to logged skips, not a crash.

### Domain models (`:cron`)

Value types beside the scheduler, following the `Job`/`NewJob` style in
`:queue`:

- `PeriodicJobName` — an inline value class over `String`.
- `PeriodicJob` — the full row (`name`, `jobType: JobType`,
  `payload: JsonObject`, `schedule: String`, `timezone: ZoneId`,
  `nextRunAt: Instant`, `lastRunAt: Instant?`, `enabled: Boolean`, timestamps).
  `jobType` is mapped via `JobType.fromValue` at DAO read time; `timezone` via
  `ZoneId.of`.

### API: `CronSchedule` (`:cron`)

The sole seam over `cron-utils`; nothing else imports the library. A plain
class, constructed once in `cron/…/Application.kt` and constructor-injected into
`PeriodicScheduler` — the codebase's existing pattern for a CPU-bound,
library-wrapping utility (mirrors `Argon2Hasher` injected into `AuthService`). A
single implementation, so no interface; the seam is the class itself.

```kotlin
class CronSchedule {
  /** Next fire strictly after [after], per a UNIX 5-field [schedule] in [timezone]. */
  fun nextRunAt(schedule: String, timezone: ZoneId, after: Instant): Result<Instant>
}
```

Wraps
`ExecutionTime.forCron(CronParser(UNIX).parse(schedule)).nextExecution(...)`. An
unparseable `schedule` or an absent next occurrence is a `Result.failure`
(surfaced by the scheduler as a logged skip, never a crash).

### API: `PeriodicJobsDao` (`:cron`, raw-JDBC `SqlSession`, mirroring `JobsDao`)

A row that cannot be reconstructed from its persisted form — an unknown
`job_type` or a malformed `payload` — is **corrupt**, not fatal: `mapRow` throws
a `PeriodicJobRowCorruptException(name, field, value, cause)`, and every
read/write path below turns that into a named outcome instead of a 500 or a
crashed tick.

```kotlin
class PeriodicJobsDao {
  /** Locks and returns one due, enabled row (next_run_at <= NOW()), plus DB NOW();
      FOR UPDATE SKIP LOCKED so concurrent schedulers never claim the same row.
      The caller holds the row lock for the remainder of its transaction. */
  fun claimDue(session: SqlSession): ClaimDueResult
  // Claimed(job, dbNow) | Corrupt(name, dbNow, cause) | NoneDue | DatabaseFailure

  /** Advances next_run_at and sets last_run_at = NOW() for a claimed, healthy row. */
  fun advance(session: SqlSession, name: PeriodicJobName, nextRunAt: Instant): PeriodicUpdateResult

  /** Parks a claimed row (bad schedule or Corrupt) by moving next_run_at without
      stamping last_run_at — nothing ran. Never calls mapRow, so it succeeds on
      a corrupt row. */
  fun park(session: SqlSession, name: PeriodicJobName, retryAt: Instant): ParkResult

  /** Sets `enabled` for a row by name; the admin toggle's write path, and the
      operator's remedy for a corrupt row. Never calls mapRow, so disable
      succeeds on a corrupt row. */
  fun setEnabled(session: SqlSession, name: PeriodicJobName, enabled: Boolean): SetEnabledResult

  fun list(session: SqlSession): PeriodicListResult          // admin; degrades per-row
  fun findByName(session: SqlSession, name: PeriodicJobName): PeriodicFindResult
}
```

`claimDue` selects `*, NOW() AS db_now` so the scheduler computes the next fire
against the **database** clock, not the host clock (Invariant 1). Its `Corrupt`
outcome carries the locked row's `name`, `dbNow`, and cause, letting the
scheduler park that row inside the same claim transaction rather than folding it
into `DatabaseFailure`.

`park` and `setEnabled` share a "confirm, don't reconstruct" shape: each runs an
`UPDATE … RETURNING *` and reads only the `was_updated` flag — never `mapRow` —
so both succeed on a row `mapRow` cannot reconstruct. `ParkResult` and
`SetEnabledResult` are accordingly `Success | NotFound(name) |
DatabaseFailure`,
with `Success` carrying no row. `setEnabled` is idempotent: setting an
already-`enabled` row re-`Success`es with a no-op write.

`findByName` gains a `Corrupt(name, cause)` outcome, distinct from `NotFound`
and `DatabaseFailure`, for a found-but-unreconstructable row. `list` degrades
per-row: `PeriodicListResult.Success(jobs, corruptRows: List<CorruptRow>)`
carries every reconstructable row plus a `CorruptRow(name, field, value,
cause)`
for each row it could not reconstruct, so one corrupt row never folds the whole
admin table into `DatabaseFailure`.

### API: `PeriodicScheduler` (`:cron`)

```kotlin
class PeriodicScheduler(
  private val database: Database,
  private val jobsDao: JobsDao,                 // enqueue in the claim transaction
  private val periodicJobsDao: PeriodicJobsDao,
  private val cronSchedule: CronSchedule = CronSchedule(),
  private val tickInterval: Duration = 1.minutes,
  private val brokenRowCooldown: Duration = 1.hours,
) {
  fun start(scope: CoroutineScope)              // launches the tick loop
  fun stop(timeout: Duration)
}
```

Lifecycle mirrors `QueueWorker.start/stop`. Each tick drains due rows in a loop
until none is due; a private `DispatchOutcome` enum
(`DISPATCHED | SKIPPED |
NONE_DUE`) drives the drain — only `NONE_DUE` stops it,
so a dispatched row and a skipped (parked) row both keep it going, and one
misconfigured row can never starve the healthy rows behind it. Each row's
claim→dispatch runs in one `database.withConnection` transaction: `claimDue` →
on `Claimed(job, dbNow)`,
`cronSchedule.nextRunAt(job.schedule, job.timezone, dbNow)` → either
`advance(name, next)` +
`jobsDao.insert(session, NewJob(job.jobType, job.payload, maxAttempts = null, delay = null))`
(dispatched) or, on an unparseable schedule, `park` (skipped); on
`Corrupt(name, dbNow, cause)`, `park` directly (skipped). Claim, advance, and
enqueue commit together, so a due row is enqueued exactly once per window and
the schedule never advances without a matching enqueue (and vice versa). The
scheduler uses `JobsDao` directly rather than `QueueService`, because
`QueueService.enqueue` opens its own transaction and cannot compose into the
claim transaction.

Both parked cases (`Corrupt` row, unresolvable schedule) go through one
`parkBrokenRow` path: `park(name, dbNow + brokenRowCooldown)` (default 1 hour),
so the row drops out of the due window instead of remaining cheapest-due and
being reclaimed every tick.

The cron **process** (`cron/…/Application.kt`, an `application` module) loads
config, builds `Database`, `JobsDao`, `PeriodicJobsDao`, `CronSchedule`, and
`PeriodicScheduler` (constructor-injecting `CronSchedule` into it), `start()`s
the scheduler, and awaits — the `queue-worker` `main` pattern. It registers no
handlers; it only enqueues.

### API: queue-side additions

- `JobType.SYNTHESIS_SWEEP` added to `queue/…/JobType.kt`. `SYNTHESIZE_STUDENT`,
  `SynthesisHandler`, and `SynthesisPayload` are **kept** (RFC 93's design
  stands; the deferred producer is now supplied, not the coupling removed).
- `StudentsDao.listActiveIds(session): Result<List<StudentId>>` — the sweep's
  enumeration (active students; the per-student freshness gate no-ops those not
  due — an SQL eligibility push-down is a named future optimization, not built).
- `SynthesisSweepHandler(database, queueService)` in
  `service/…/coaching/synthesis/` — a `JobHandler` for `SYNTHESIS_SWEEP`:
  `config = JobTypeConfig(concurrency = 1, maxAttempts = 1, executionTimeout = 5.minutes, lockDuration = 10.minutes)`;
  `execute` lists active students and
  `queueService.enqueue(SYNTHESIZE_STUDENT, …)` per student, best-effort (an
  enqueue failure for one student is logged and the sweep continues).
  `maxAttempts = 1` because the next daily tick re-produces the sweep —
  next-tick re-production is the retry, not queue backoff.

Registered in `queue-worker/…/Application.kt` under the existing
`if (synthesisConfig.enabled)` block that already gates `SynthesisHandler`, so
the sweep handler and the `SYNTHESIZE_STUDENT` handler are present together or
absent together: when synthesis is off, neither is registered and the seed row
is `enabled = FALSE`, so no `SYNTHESIS_SWEEP` fans out into unhandled
`SYNTHESIZE_STUDENT` jobs.

### Admin

`PeriodicJobsResource` (RFC 77 descriptor engine), a class taking
`PeriodicJobsDao` by constructor. All row fields — `name`, `job_type`,
`schedule`, `timezone`, `next_run_at`, `last_run_at`, `enabled`, timestamps —
are read-only (`create`/`update`/`delete`/`undelete` all `null`); `enabled` is
the sole mutable column, flipped by a per-row action, not the edit form. The id
type is `PeriodicJobName`: `parseId` delegates to `PeriodicJobName.parse`, which
allowlists a lowercase slug (`^[a-z0-9][a-z0-9-]{0,127}$`, `MAX_LENGTH =
128`)
rather than merely checking length, so a parsed name can never carry
`CR`/`LF`/`/`/`?`/`#` into the redirect `Location` header; `idToPath` returns
`name`. Registered in `admin-web/…/Application.kt`'s `AdminRegistry(listOf(…))`.

The toggle is two `CustomAction<PeriodicJob>` buttons — `Enable`
(`disabledReason` non-null when already enabled) and `Disable` (non-null when
already disabled) — so exactly one is active per row state and the other renders
disabled with the reason as tooltip. Two buttons rather than one because
`CustomAction.label` is a static string, not a function of the row. Their
`registerExtraRoutes` handlers `POST /{slug}/{name}/enable|disable`, each
parsing the id and calling `periodicJobsDao.setEnabled(session, name, enabled)`
inside `database.withConnection`, then dispatching the result: `Success` →
redirect to detail; `NotFound` → 404; `DatabaseFailure` → `respondDaoError`.
Buttons render on the detail page (where the engine renders `customActions`),
following the `UsersResource` verify-action pattern. `setEnabled`'s `Success`
never reconstructs the row, so `Disable` also succeeds on a corrupt row — the
operator's quarantine remedy (below).

**Corrupt rows.** `list` degrades per-row (§API), so the list route renders
every healthy row (200 OK) and logs each corrupt one by name/field/value rather
than failing the whole page. The detail route's `get` maps
`PeriodicFindResult.Corrupt` to a named 404 (a corrupt row cannot be rendered as
a `PeriodicJob` — no enum value matches its `job_type`, or its `payload` will
not parse) — logged with the row name and reason, not a bare 500. The disable
action is the operator's remedy: it quarantines a corrupt row (parks it via
`setEnabled`, which never calls `mapRow`) instead of erroring.

`admin-web` gains direct `implementation` deps on `:cron` (`PeriodicJobsDao`,
`PeriodicJob`, `PeriodicJobName`) and `:queue` (`JobType`, the declared type of
`PeriodicJob.jobType`); `:cron` keeps `:queue` as `implementation`. Cost:
`:cron`'s runtime deps (`cron-utils`, scheduler classes) ride on admin-web's
classpath unused — an accepted trade for not relocating the DAO.

### Deployment

A new `unicoach-cron.service` systemd unit (modeled on
`unicoach-queue-worker.service`: `Type=simple`,
`EnvironmentFile=/etc/unicoach/env`, `Restart=on-failure`), registered in
`cloud-init.yaml` (`write_files` + `systemctl enable`) and handled by
`deploy-on-instance.sh` (symlink + restart). Reaching the already-running
production instance requires a one-time live SSM write of the unit file, because
`cloud-init` runs only at first boot.

### Error handling / edge cases

- **N concurrent schedulers.** `FOR UPDATE SKIP LOCKED` + advancing
  `next_run_at` in the claim transaction: exactly one scheduler enqueues per due
  window; the rest skip the locked row and, post-commit, see `next_run_at` in
  the future.
- **Scheduler crash mid-tick.** The claim transaction rolls back; `next_run_at`
  is unadvanced and the row unlocked, so the next tick reclaims it. A crash
  _after_ commit but before the enqueued job runs loses that tick until the next
  occurrence (scheduling is not exactly-once; missed ticks are acceptable).
- **Unparseable `schedule` / no next occurrence.** `cronSchedule.nextRunAt`
  returns a failure; the scheduler logs and parks the row (`next_run_at` moved
  `brokenRowCooldown` — default 1h — past the DB clock; `last_run_at` untouched,
  nothing enqueued) rather than leaving it cheapest-due every tick. A
  misconfigured row is inert, not fatal, and cannot starve healthy rows.
- **Row's `job_type`/`payload` cannot be reconstructed** (unknown enum value /
  malformed JSON). `claimDue` yields `Corrupt(name, dbNow, cause)`; the
  scheduler parks it the same way as an unparseable schedule. Distinct from the
  next case: this is a string that matches no `JobType` value at all, caught in
  `PeriodicJobsDao.mapRow` before the row ever reaches the queue.
- **`job_type` names a known but unregistered queue type.** A valid `JobType`
  enum value for which the worker has no handler registered: the scheduler
  enqueues it as normal; the worker's dispatch logs "no handler registered" —
  the existing queue contract (`QueueWorker.kt`).
- **Overlapping sweeps / duplicate per-student jobs.** Tolerated: each
  `SYNTHESIZE_STUDENT` is idempotent (per-student advisory lock + synthesis
  freshness gate), so a duplicate is a cheap no-op.
- **`enabled = false`.** The claim's `WHERE enabled` skips the row; nothing is
  enqueued. Handlers stay registered regardless.
- **Slow sweep.** `executionTimeout` (5m) < `lockDuration` (10m) so the sweep
  cannot outlive its queue lock and be reclaimed mid-run.

### Dependencies

- `cron-utils` (`com.cronutils:cron-utils:9.2.1`, Apache-2.0) — only runtime
  transitive dep is `slf4j-api`, already present. Not yet in
  `libs.versions.toml`; added there and depended on by `:cron` (step 1).
- Reuses the queue (`JobType`, `JobsDao`, `QueueService`, `NewJob`), `Database`,
  `StudentsDao`, `SynthesisService`/`SynthesisHandler` (RFC 93), and the RFC 77
  admin engine — all implemented.
- New Gradle module `:cron` (`settings.gradle.kts`), `application` plugin,
  depending on `:common`, `:db`, `:queue`.
- `admin-web` gains direct `implementation` deps on `:cron` and `:queue` for the
  periodic-jobs resource (§Admin); the resulting `cron-utils`/scheduler
  classpath bloat on admin-web is accepted over relocating the DAO.

## Tests

DB/DAO tests use the project harness (recreated test DB); scheduler tests use a
real DB with the real `JobsDao` and `PeriodicJobsDao` (so the claim + advance +
enqueue all-or-nothing transaction is exercised against Postgres, which a fake
DAO cannot prove); the `cron-utils` seam is unit-tested directly. Run
`nix develop -c bin/test <module> -f`, verifying executed-vs-declared counts.

**Migration (`db`).**

- `0029`/`0030` apply on a fresh DB; `periodic_jobs` has the columns, the
  `periodic_jobs_due_idx` partial index, and the `updated_at` trigger; the
  `synthesis` seed row exists with `job_type='SYNTHESIS_SWEEP'`,
  `schedule='0 3 * * *'`.
- Length/size CHECKs reject an over-long `name`/`job_type`/`schedule` and an
  over-large `payload`; `UPDATE` bumps `updated_at`.

**`CronScheduleTest` (`cron`).**

- `nextRunAt('0 3 * * *', UTC, t)` returns the next 03:00 UTC strictly after
  `t`; a `t` exactly at 03:00 returns the following day.
- `nextRunAt('*/15 * * * *', UTC, t)` returns the next quarter-hour.
- A non-UTC zone offsets correctly.
- An unparseable schedule returns `Result.failure`.

**`PeriodicJobsDaoTest` (`cron`).**

- `claimDue` returns a row with `next_run_at <= NOW()` and `enabled`, and DB
  now; returns `NoneDue` when the only due row is `enabled = false` or
  `next_run_at` is future; returns `Corrupt` (not `DatabaseFailure`) for a due
  row with an unknown `job_type`.
- Two connections: the first `claimDue` locks the row; a concurrent `claimDue`
  (separate transaction) returns `NoneDue` (SKIP LOCKED), proving no
  double-claim.
- `advance` sets `next_run_at` to the given instant and `last_run_at`; an
  unknown name returns `NotFound`.
- `park` moves `next_run_at` without stamping `last_run_at`; succeeds on a
  corrupt row without reconstructing it; an unknown name returns `NotFound`.
- `list`/`findByName` return persisted fields for healthy rows; `list` degrades
  per-row — a corrupt row (unknown `job_type`, and separately a malformed
  `payload`) surfaces in `corruptRows` without hiding the healthy rows;
  `findByName` returns `Corrupt` for an unreconstructable row.
- `setEnabled(name, true)` on a seeded `enabled = false` row returns `Success`
  and `findByName` reads `enabled = true`; `setEnabled(name, false)` flips it
  back; an unknown name returns `NotFound`; re-enabling an already-enabled row
  returns `Success` (idempotent); succeeds on a corrupt row without
  reconstructing it.

**`PeriodicSchedulerTest` (`cron`).**

- A due row: one tick inserts exactly one `jobs` row of the row's `job_type`,
  advances `next_run_at` to the computed next fire, and sets `last_run_at`.
- A future or `enabled = false` row: a tick inserts no `jobs` row and does not
  advance.
- An unparseable-schedule row: a tick parks it (no enqueue, no `last_run_at`
  stamp, no throw), and does not starve a healthy due row behind it.
- A corrupt-`job_type` row: same parked-inert behavior and non-starvation as the
  unparseable-schedule case.
- Claim + enqueue atomicity: a forced `jobsDao.insert` failure leaves
  `next_run_at` unadvanced (nothing committed).

**`SynthesisSweepHandlerTest` (`service`).**

- With N active students, `execute` enqueues N `SYNTHESIZE_STUDENT` jobs
  (asserted by reading the `jobs` table through the real `QueueService` over the
  test DB) and returns `Success`.
- No active students → `Success`, zero enqueues.
- A single enqueue failure is logged and does not abort the sweep (remaining
  students still enqueued); `execute` still returns `Success`.
- `config` advertises `SYNTHESIS_SWEEP` and `executionTimeout < lockDuration`.

**`StudentsDaoTest` (`db`).**

- `listActiveIds` returns active students' ids and excludes soft-deleted ones.

**Admin (`admin-web`).**

- `PeriodicJobsResourceTest`: list/detail render `schedule`, `next_run_at`,
  `last_run_at`, `enabled`; no create/edit/delete affordance and no edit form
  (`GET /periodic-job/{name}/edit` → 404).
- A `enabled = false` row's detail offers `Enable` active, `Disable` disabled
  (tooltip "Already disabled."); `POST /periodic-job/{name}/enable` redirects
  and the row re-reads `enabled = true`, now offering `Disable` active, `Enable`
  disabled. Reverse for `/disable`.
- `POST /periodic-job/{unknown}/enable` → 404 (`setEnabled` → `NotFound`).
- The list renders healthy rows with a corrupt row present, no 500; a corrupt
  row's detail responds a named 404, not a 500; disabling a corrupt row's name
  succeeds (quarantine).
- `parseId` allowlists a lowercase slug and rejects out-of-allowlist names
  (including ones carrying `CR`/`LF`/`/`/`?`/`#`).

**Recipe validation.** After implementation, re-run the completeness pass: an
LLM given only `recipes/PERIODIC_JOB.md` and repo read access produces a
correct, compilable change set for a new periodic job (clean PASS).

## Invariants

Two, both targeting `cron/INVARIANTS.md`.

- **Rule:** The scheduler's due-checks and next-fire computation MUST use the
  database clock (`NOW()`), never a host wall clock. **Why:** schedulers run on
  N hosts with independent, skewed clocks; a host-clock comparison would
  double-fire or skip a schedule. **Target:** `cron/`.
- **Rule:** Any job registered as a periodic task MUST be idempotent — safe to
  run concurrently with itself and to re-run. **Why:** the scheduler guarantees
  only best-effort single execution (overlap under a slow run, duplicates across
  ticks, a re-run after a lost tick are all possible); a non-idempotent periodic
  job can double-apply. **Target:** `cron/`.

## Implementation Plan

Each step is atomic and locally verifiable in the Nix dev shell.

1. **Module scaffold + dependency.** Add `:cron` to `settings.gradle.kts`;
   create `cron/build.gradle.kts` (`application` plugin; deps
   `:common`,`:db`,`:queue`, `cron-utils`); add `cron-utils` to
   `libs.versions.toml`. Verify: `nix develop -c ./gradlew :cron:compileKotlin`.

2. **Migration + seed.** Add `0029.create-periodic-jobs.sql` and
   `0030.seed-synthesis-periodic-job.sql`. Verify:
   `nix develop -c bin/test db -f`;
   `psql "$POSTGRES_DB" -c '\d+ periodic_jobs'`.

3. **`CronSchedule` + test.** The `cron-utils` seam and `CronScheduleTest`.
   Verify: `nix develop -c bin/test cron -f`.

4. **Models.** `PeriodicJobName`, `PeriodicJob` in `:cron`. Verify:
   `nix develop -c ./gradlew :cron:compileKotlin`.

5. **`PeriodicJobsDao` + test.** `claimDue`/`advance`/`setEnabled`/`list`/
   `findByName` and `PeriodicJobsDaoTest` (including the concurrent SKIP-LOCKED
   case and the `setEnabled` toggle/idempotency/`NotFound` cases). Verify:
   `nix develop -c bin/test cron -f`.

6. **`PeriodicScheduler` + test.** The tick loop and `PeriodicSchedulerTest`.
   Verify: `nix develop -c bin/test cron -f`.

7. **Cron process entrypoint.** `cron/…/Application.kt` (`main`) + `cron.conf`.
   Verify: `nix develop -c ./gradlew :cron:installDist`.

8. **Invariants.** Create `cron/INVARIANTS.md` with the two rules above. Verify:
   `nix develop -c deno fmt --check cron/INVARIANTS.md`.

9. **Sweep job type + eligibility query.** Add `JobType.SYNTHESIS_SWEEP`;
   `StudentsDao.listActiveIds` + `StudentsDaoTest` case. Verify:
   `nix develop -c ./gradlew :queue:compileKotlin`;
   `nix develop -c bin/test db -f`.

10. **`SynthesisSweepHandler` + registration.** The handler +
    `SynthesisSweepHandlerTest`; register in `queue-worker/…/Application.kt`.
    Verify: `nix develop -c bin/test service -f`;
    `nix develop -c ./gradlew :queue-worker:compileKotlin`.

11. **Admin resource + toggle.** Add `implementation(project(":cron"))` and
    `implementation(project(":queue"))` to `admin-web/build.gradle.kts`;
    `PeriodicJobsResource` (read-only fields + `Enable`/`Disable`
    `CustomAction`s and their `registerExtraRoutes` calling `setEnabled`) +
    test; register in `admin-web/…/Application.kt`. Verify:
    `nix develop -c bin/test admin-web -f`.

12. **Deployment.** `infra/files/unicoach-cron.service`; register in
    `cloud-init.yaml`; handle in `deploy-on-instance.sh`. Verify:
    `nix develop -c deno fmt --check infra/files/cloud-init.yaml`; deploy-dry
    review.

13. **Docs + full gate.** Update `features/coaching-memory.md` (synthesis via
    periodic-task infra; push-delivery rides the queue's `scheduled_at`);
    confirm `recipes/PERIODIC_JOB.md`. Verify:
    `nix develop -c bin/format -c && nix develop -c bin/test check -f`.

## Files Modified

**Created**

- `cron/build.gradle.kts`
- `cron/src/main/kotlin/ed/unicoach/cron/Application.kt`
- `cron/src/main/kotlin/ed/unicoach/cron/PeriodicScheduler.kt`
- `cron/src/main/kotlin/ed/unicoach/cron/CronSchedule.kt`
- `cron/src/main/kotlin/ed/unicoach/cron/PeriodicJob.kt`
- `cron/src/main/kotlin/ed/unicoach/cron/PeriodicJobName.kt`
- `cron/src/main/kotlin/ed/unicoach/cron/dao/PeriodicJobsDao.kt`
- `cron/src/main/kotlin/ed/unicoach/cron/dao/Results.kt`
- `cron/src/main/resources/cron.conf`
- `cron/src/test/kotlin/ed/unicoach/cron/CronScheduleTest.kt`
- `cron/src/test/kotlin/ed/unicoach/cron/PeriodicSchedulerTest.kt`
- `cron/src/test/kotlin/ed/unicoach/cron/dao/PeriodicJobsDaoTest.kt`
- `cron/INVARIANTS.md`
- `db/schema/0029.create-periodic-jobs.sql`
- `db/schema/0030.seed-synthesis-periodic-job.sql`
- `service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisSweepHandler.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/synthesis/SynthesisSweepHandlerTest.kt`
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/PeriodicJobsResource.kt`
- `admin-web/src/test/kotlin/ed/unicoach/admin/resources/PeriodicJobsResourceTest.kt`
- `infra/files/unicoach-cron.service`
- `recipes/PERIODIC_JOB.md` (drafted during design)

**Modified**

- `settings.gradle.kts` — `include("cron")`.
- `gradle/libs.versions.toml` — add `cron-utils`.
- `queue/src/main/kotlin/ed/unicoach/queue/JobType.kt` — add `SYNTHESIS_SWEEP`.
- `db/src/main/kotlin/ed/unicoach/db/dao/StudentsDao.kt` — add `listActiveIds`.
- `db/src/test/kotlin/ed/unicoach/db/dao/StudentsDaoTest.kt` — `listActiveIds`
  case.
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` — register
  `SynthesisSweepHandler`.
- `admin-web/build.gradle.kts` — add `implementation(project(":cron"))` and
  `implementation(project(":queue"))`.
- `admin-web/src/main/kotlin/ed/unicoach/admin/Application.kt` — register
  `PeriodicJobsResource`.
- `infra/files/cloud-init.yaml` — write + enable `unicoach-cron.service`.
- `infra/files/deploy-on-instance.sh` — symlink + restart the cron process.
- `features/coaching-memory.md` — synthesis triggers via periodic-task infra;
  push-delivery rides the queue.
