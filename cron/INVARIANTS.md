# INVARIANTS — cron

The periodic-task scheduler: a `:cron` process runs a one-minute loop that
atomically claims due `periodic_jobs` rows and enqueues each onto the existing
queue as an ordinary job. The flow is one-directional — cron feeds the queue,
never the reverse — so the scheduler stays domain-agnostic (it reads a row's
`job_type`/`payload` and enqueues) and every consumer reuses the queue's
hardened execution.

## Invariants

### Scheduling decisions use the database clock, never a host wall clock

**Rule:** The scheduler's due-checks and next-fire computation MUST use the
database clock (`NOW()`), never a host wall clock. `claimDue` selects due rows
by `next_run_at <= NOW()` and returns `NOW() AS db_now`, and the scheduler
computes `CronSchedule.nextRunAt(..., after = dbNow)` from that value, not from
`Instant.now()`.

**Why:** Schedulers run on N hosts with independent, skewed clocks. A host-clock
comparison would double-fire a schedule (a fast host sees a row as due while the
DB does not, or two hosts disagree on the boundary) or skip it (a slow host
never sees it due). Anchoring both the due predicate and the next-fire
computation to the single database clock makes the decision
total-order-consistent across all schedulers regardless of host drift.

### Every periodic job MUST be idempotent

**Rule:** Any job registered as a periodic task (any `job_type` a
`periodic_jobs` row enqueues) MUST be idempotent — safe to run concurrently with
itself and to re-run.

**Why:** The scheduler guarantees only best-effort single execution. Overlap
under a slow run, duplicates across ticks, and a re-run after a lost tick are
all possible (scheduling is not exactly-once; a crash after commit but before
the enqueued job runs loses that tick, and `FOR UPDATE SKIP LOCKED` plus the
daily re-production of a sweep can produce duplicate downstream jobs). A
non-idempotent periodic job can therefore double-apply its effect.

## History

- [x] [RFC-97: Periodic Task Infrastructure](../rfc/97-periodic-task-infrastructure.md)
