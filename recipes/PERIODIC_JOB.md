# Recipe: Add a periodic job

Run recurring background work on a schedule. Periodic jobs are driven by the
`:cron` module (the **cron process**) and the `periodic_jobs` table; the actual
work runs as an ordinary **queue job** in the `queue-worker`. Adding a periodic
job is the normal queue-job pattern plus one seed row — you never modify the
`:cron` module.

## How it works

The cron process runs a one-minute loop. Each tick it atomically claims every
due `periodic_jobs` row (`next_run_at <= NOW()` under `FOR UPDATE SKIP LOCKED`),
advances that row's `next_run_at` to the next cron occurrence and `last_run_at`
to now, and enqueues the row's `job_type` / `payload` onto the queue — all in
one transaction. The `queue-worker` then runs the enqueued job through its
registered `JobHandler`.

The scheduler is domain-agnostic: it only reads a row and enqueues. It never
runs your work and knows nothing about any specific job. Adding a job therefore
touches only the queue side and the seed data, never `:cron`.

## Read these first

- `queue/src/main/kotlin/ed/unicoach/queue/JobHandler.kt` — the interface you
  implement.
- `queue/src/main/kotlin/ed/unicoach/queue/JobResult.kt` — the three outcomes
  `execute` returns.
- `queue/src/main/kotlin/ed/unicoach/queue/JobTypeConfig.kt` — the per-type
  execution config.
- `service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionHandler.kt`
  — a concrete `JobHandler` to model yours on.
- `queue/src/main/kotlin/ed/unicoach/queue/QueueService.kt` — the enqueue API,
  needed only if your job dispatches other jobs.
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` — where
  handlers are constructed and registered.
- `db/schema/0026.seed-synthesis-system-prompt.sql` — a seed-migration example.
- `db/schema/*create-periodic-jobs.sql` — the `periodic_jobs` table definition;
  the authoritative column names, types, defaults, and constraints your seed row
  must satisfy.

## Add a job

Four steps. Steps 1–3 are the standard queue-job pattern (identical to any
one-shot job); step 4 is the only thing that makes it recurring.

### 1. Add a `JobType`

`queue/src/main/kotlin/ed/unicoach/queue/JobType.kt`. The string value equals
the enum name (the existing convention); skip this step if an existing type
fits.

```kotlin
NIGHTLY_DIGEST("NIGHTLY_DIGEST"),
```

### 2. Implement a `JobHandler`

In the module that owns the work (typically `:service`), in a feature-scoped
package `ed.unicoach.<area>.<feature>` — existing handlers live at
`coaching/extraction/` and `coaching/synthesis/`; choose the package matching
your job's domain (the `Application.kt` import in step 3 must match what you
pick). `execute` returns exactly one of `JobResult.Success`,
`JobResult.RetriableFailure(message, cause?)` (re-tried with backoff up to
`maxAttempts`, then dead-lettered), or `JobResult.PermanentFailure(message)`
(dead-lettered immediately — use for a malformed payload). A thrown exception or
a timeout is treated as a retriable failure by the worker.

```kotlin
class NightlyDigestHandler(
  private val digest: DigestService,
) : JobHandler {
  override val jobType = JobType.NIGHTLY_DIGEST

  // See JobTypeConfig.kt for every field + its default. For a periodic job the
  // usual choices: concurrency=1, and maxAttempts low (1–2) — the next tick
  // re-produces the job, so the queue's per-run retry is a backstop, not the
  // primary retry.
  //
  // QUEUE INVARIANT: executionTimeout MUST be strictly less than lockDuration. A
  // job that outlives its lock is reclaimed by the stuck-job reaper and re-run
  // concurrently. The lockDuration default is 1m, so if executionTimeout exceeds
  // that you MUST raise lockDuration past it (as ExtractionHandler does: 5m / 10m).
  override val config =
    JobTypeConfig(
      concurrency = 1,
      maxAttempts = 1,
      executionTimeout = 5.minutes,
      lockDuration = 10.minutes,
    )

  override suspend fun execute(payload: JsonObject): JobResult {
    // ... do the work; return Success / RetriableFailure / PermanentFailure ...
  }
}
```

**If your job is a dispatcher** (it fans out other jobs, as synthesis does),
inject `QueueService` and call `enqueue`, best-effort per item:

```kotlin
when (val r = queueService.enqueue(JobType.SYNTHESIZE_STUDENT, payloadFor(id))) {
  is EnqueueResult.Success -> { /* enqueued */ }
  is EnqueueResult.DatabaseFailure -> logger.warn("enqueue failed for [{}]", id, r.error)
  // log-and-continue: one item's failure must not abort the sweep
}
```

### 3. Register the handler

`queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt`, in the
`handlers` `buildList`. Construct the handler's dependencies the same way the
existing handlers do (the `Database`, a `QueueService`, any domain service and
its config):

```kotlin
add(NightlyDigestHandler(DigestService(database, /* … */)))
```

Dependency construction, concretely: every service takes `database`. If the
service reads config, load it in `Application.kt` with
`SomeConfig.from(config).getOrThrow()` and pass it in — the same way
`ExtractionConfig`/`SynthesisConfig` are loaded there. If the job is a
dispatcher, inject `QueueService(database)`. If the service needs a
`ChatProvider`, construct it inside the existing `if (…enabled)` block that
already builds one; otherwise construct the handler at the top level of the
`buildList`, needing none.

Registration is unconditional. Whether the job actually fires is controlled by
the `enabled` column on its `periodic_jobs` row (below), not by a config flag.

### 4. Seed the schedule row

A new migration under `db/schema/`, named `NNNN.seed-<name>-periodic-job.sql`
where `NNNN` is one greater than the highest existing migration number
(`ls db/schema/`). Migrations are applied and re-checked by `bin/test db`.

```sql
INSERT INTO periodic_jobs (name, job_type, payload, schedule, timezone, next_run_at)
VALUES ('nightly-digest', 'NIGHTLY_DIGEST', '{}', '0 4 * * *', 'UTC', NOW());
```

Column meaning:

- `name` — stable unique identifier (primary key).
- `job_type` — must equal a `JobType` value; this is what gets enqueued.
- `payload` — the enqueued job's `payload` (`jsonb`); `'{}'` if the job needs
  none.
- `schedule` — UNIX 5-field cron (see below).
- `timezone` — IANA zone; `'UTC'` unless the job must align to local wall-clock
  time.
- `next_run_at` — first eligibility. `NOW()` makes the job fire on the next tick
  (within a minute of deploy); set a future timestamp to defer the first run.
  After that the scheduler owns this column.
- `enabled` defaults to `true`; set it `false` (or `UPDATE` it later) to pause
  the job without removing the row.

## Schedule format

- `schedule` is a UNIX 5-field cron string (`min hour dom mon dow`). Minute
  granularity only: the scheduler ticks once a minute, so nothing fires more
  precisely than that.
- `timezone` defaults to `UTC`. Prefer `UTC` — it has no DST transitions to
  reason about; use a named zone only when a job must run at a local wall-clock
  time.
- `schedule` is the source of truth; `next_run_at` is a value derived from it
  after each run. Changing cadence means a new migration
  (`UPDATE periodic_jobs SET schedule = … , next_run_at = …`) or an admin edit —
  and `next_run_at` must be recomputed when `schedule` changes.

## Constraints

- **The scheduler enqueues; it does not run your work.** Your handler executes
  in the `queue-worker` under its job type's `concurrency`, `executionTimeout`,
  retry, and dead-letter behavior.
- **Exactly one enqueue per due window**, regardless of how many cron processes
  run, via the atomic claim. Horizontal scaling is safe by construction.
- **The database clock is the only clock.** Due-checks and next-fire computation
  use DB `NOW()`, never a host wall clock, so schedulers on clock-skewed hosts
  still agree. Do not introduce a host-clock comparison anywhere in the path.
- **Overlap is possible; your handler must tolerate it.** A slow run can be
  re-enqueued on a later tick before the first finishes, and a duplicate can be
  produced across ticks. Design the work to be idempotent. The retry model is
  "re-run on the next due tick," not a retry queue; a crashed run is skipped
  until the next occurrence (scheduling is not exactly-once — missed ticks are
  acceptable).
- **Enable/disable via the `enabled` column**, not by adding or removing the
  handler.

## Verify

- **Handler** — unit-test `execute` with fake dependencies: assert it returns
  `JobResult.Success` for the happy path and `RetriableFailure` on a transient
  dependency error. Only if the job deserializes a `payload` (a dispatcher or a
  parameterized job), also assert a malformed payload returns
  `PermanentFailure`; a no-payload job (`'{}'`) has no such path. Run
  `nix develop -c bin/test <module> -f` and check executed-vs-declared counts.
- **Migration** — `nix develop -c bin/test db -f`; confirm the row with
  `psql "$POSTGRES_DB" -c "SELECT name, job_type, schedule, enabled FROM periodic_jobs"`.
- **End to end** (optional, once the cron process is running): confirm a job of
  your `job_type` appears in `jobs` after the scheduled minute.

## Example: synthesis

Synthesis is the first consumer, built in the same change that introduces this
framework, and follows this recipe exactly:

- **`JobType`** — `SYNTHESIS_SWEEP` in `queue/…/JobType.kt`.
- **Handler** — `SynthesisSweepHandler` in `service/…/coaching/synthesis/`,
  which enumerates eligible students and enqueues one `SYNTHESIZE_STUDENT` job
  per student (best-effort, log-and-continue) via `QueueService`. The
  per-student jobs run through the existing `SynthesisHandler`.
- **Row** — `('synthesis', 'SYNTHESIS_SWEEP', '{}', '0 3 * * *', 'UTC', NOW())`.

The sweep is a thin dispatcher: it only fans out per-student queue jobs. Each
`SYNTHESIZE_STUDENT` job is idempotent (guarded by the per-student advisory lock
and the synthesis freshness gate), so a re-enqueued or duplicated student job is
a cheap no-op — which is what makes overlap and next-tick retry safe.
