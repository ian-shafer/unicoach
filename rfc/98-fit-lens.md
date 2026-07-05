# RFC 98: fit-lens

## Executive Summary

fit-lens is a reflection feature: between sessions the coach reaches into the
college dataset and proactively surfaces "I found a school you'd love" — a
specific, **real** college the student has not mentioned, with a rationale
grounded in what they said. It is the last step of the coaching-memory
reflection spine (`features/coaching-memory.md`).

It is its **own periodic job, a sibling to synthesis — not a lens inside
`SynthesisService`**: fit-lens additionally retrieves external knowledge (the
college dataset), so it carries a different cost profile (retrieval plus two LLM
calls), a slower cadence, and needs failure isolation. Keeping it separate
leaves `SynthesisService` pure and untouched.

**Blocked on RFC 97 (not yet on this branch's base).** fit-lens follows the
sweep→fan-out pattern RFC 97 introduces and depends on RFC 97's `:cron` process,
`periodic_jobs` table, scheduler, and `StudentsDao.listActiveIds` — none of
which exist on-branch today. A new `FIT_LENS_SWEEP` dispatcher (enqueued by
`:cron` weekly) fans out one `FIT_LENS` job per active student; a
`FitLensHandler` over a new `FitLensService` runs one student.

`FitLensService.discover()` is explicit two-call orchestration — **reason →
retrieve → reason**: LLM call #1 translates the student's claims into a
structured `CollegeQuery`; `CollegeSearchService.search()` runs it directly in
the worker; LLM call #2 reasons over the **real** `CollegeMatch`es and proposes
one school with a rationale, persisted to a dedicated `fit_suggestions` entity
and surfaced through the existing next-session opener. Novelty is a
deterministic write-time guarantee (a `UNIQUE` constraint plus an under-lock
recheck), never a prompt hope.

## Detailed Design

### Architecture

Sibling of synthesis (justified in the Executive Summary — cost profile,
cadence, failure isolation), one-directional over RFC 97's infrastructure. RFC
97 is **not on this branch's base**; the symbols it must provide (`:cron`,
`periodic_jobs`, its scheduler, `StudentsDao.listActiveIds`) are preconditions,
not existing reuse — see Dependencies.

```
periodic_jobs('fit-lens', weekly, enabled=FALSE)
  │ :cron (RFC 97) claims + enqueues, one txn
  ▼
jobs: FIT_LENS_SWEEP
  │ queue-worker → FitLensSweepHandler: listActiveIds, fan-out
  ▼
jobs: FIT_LENS × N students
  │ queue-worker → FitLensHandler → FitLensService.discover(studentId)
  ▼
fit_suggestions (open)  ──►  CoachingService next-session opener (surfaced)
```

`:cron` (RFC 97) will know nothing of fit-lens; it reads the seed row's
`job_type`/`payload` and enqueues, as RFC 97 does for synthesis's own sweep.
`SynthesisService` is not read, written, or imported by any fit-lens code, and
vice versa — the isolation this RFC exists to preserve.

### `FitLensService.discover()` — reason → retrieve → reason

`FitLensService.discover(studentId: StudentId): FitLensResult`, structured after
`SynthesisService.synthesize()`: a read phase and a write phase, each its own
transaction under the student advisory lock, with the LLM calls and retrieval
**outside** any transaction (an LLM call must never hold a DB connection).

1. **Read phase** (txn, `AdvisoryLockDao.lockStudent`): read active claims
   (`ClaimsDao.listActiveByStudent`), the college list
   (`CollegeListEntriesDao.listActiveByStudent`), and the set of college ids
   already suggested (`FitSuggestionsDao.listSuggestedCollegeIds`). Resolve both
   prompt catalog rows (`SystemPromptsDao.findByNameAndVersion`) up front so a
   later failure still has both provenance pins. Three gates short-circuit
   before any tokens are spent, each a no-op returning `FitLensResult.Skipped`:
   - **minClaims floor:** fewer than `config.minClaims` active claims → too
     little signal to search on.
   - **Freshness gate:** the max `updated_at` across active claims and list
     entries is at or before `FitLensRunsDao.lastAppliedAt(session, studentId)`
     → the model is unchanged since the last applied run.
   - **Failure circuit breaker:** `FitLensRunsDao.consecutiveFailuresSince` (the
     count of `failed` runs since the last `applied` run, or since the first run
     if never applied) is at or above `config.maxConsecutiveFailures` → stop
     re-billing a model state that has failed to parse `maxConsecutiveFailures`
     times running. A `failed` run does not advance freshness, so without this
     gate an unchanged-but-unparseable model would re-bill two LLM calls every
     weekly tick indefinitely; this bounds the waste. The breaker resets the
     moment an `applied` run lands (any parse success) or the model changes
     enough to eventually parse, whichever comes first.

2. **LLM call #1 — formulate** (no txn): `chatProvider.chat` with the
   `fit_lens_query` prompt; the message carries the student's claims — capped at
   `config.maxClaims` (`activeClaims.take(config.maxClaims)`, mirroring
   synthesis) so a claim-heavy student cannot balloon prompt size and cost — and
   the **exclusion set** (college-list + already-suggested colleges, by name).
   The same capped claim set feeds LLM call #2. The model returns a strict JSON
   object of `CollegeQuery` filter fields, parsed defensively into the typed
   `CollegeQuery`. The service sets `limit` itself (`config.searchLimit`); the
   model never sets it. An unparseable or type-invalid document fails the pass
   (`Failed`, tokens recorded).

3. **Retrieve** (no shared txn): `collegeSearchService.search(query)` — called
   **directly in the worker**. This is the same retrieval path live chat uses
   (`CollegesDao.search` SQL), reused, not re-implemented. A zero-match result
   is a valid `Skipped` outcome (nothing novel to reason over), not a failure.

4. **LLM call #2 — reason** (no txn): `chatProvider.chat` with the
   `fit_lens_reason` prompt; the message carries the student's claims and the
   real `CollegeMatch` list. The model returns a strict JSON object naming one
   `collegeId` drawn from the supplied matches plus a `rationale`, or an empty
   object when nothing genuinely fits (`Skipped`). A `collegeId` not present in
   the supplied match set is rejected (`Failed`) — the model may only choose a
   retrieved row.

5. **Write phase** (txn, `AdvisoryLockDao.lockStudent`): the deterministic
   novelty gate (below). On a clean, novel `collegeId`,
   `FitSuggestionsDao.create` inserts an `open` suggestion. Either way,
   `FitLensRunsDao.append` records the run (`applied`, both prompt pins,
   provider/model, `suggestions_written` ∈ 0..1, `matches_considered`, summed
   tokens). `applied` advances the freshness marker whether or not a suggestion
   was written — a full pass over the current model state.

### Novelty gate — two layers

Layer 1 (input, steering): the exclusion set fed into LLM call #1 biases the
candidate pool novel by construction. Layer 2 (write-time, the guarantee):
because the LLM is non-deterministic, the write phase re-verifies the chosen
`collegeId` under the advisory lock against the student's **structured** college
ids — active `college_list` entries plus prior `fit_suggestions` — and
`fit_suggestions` carries `UNIQUE(student_id, college_id)` as the DB backstop. A
collision writes no suggestion (`suggestions_written = 0`, tokens still logged).
A non-deterministic or retried LLM cannot produce a re-suggestion. The
deterministic dedupe covers structured college ids only; schools merely named in
free-text claims are steered against in Layer 1 but not hard-blocked (no
reliable id to match on).

### Surfacing — the next-session opener (pull)

`CoachingService`'s existing opener (RFC 93), which composes open explicit
commitments into a student's next conversation, is extended to **also** read
open `fit_suggestions` (`FitSuggestionsDao.listOpenForOpener`, joined to
`colleges` for the display name), compose the "I found a school you'd love:
&lt;name&gt; — &lt;rationale&gt;" line, and mark them surfaced
(`FitSuggestionsDao.markSurfaced`) on a successful first reply. A new
`coaching.surfaceFitSuggestions` gate governs the fit-lens contribution,
alongside the existing `surfaceCommitments`. Push delivery (a notification for
urgency) remains the separate planned push-delivery RFC.

### Data models

**`fit_suggestions`** — a mutable entity: the coach's proposed school for a
student, modeled on `commitments` (four-timestamp split, no versioning, no
`deleted_at`).

```sql
CREATE TABLE fit_suggestions (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  college_id UUID NOT NULL REFERENCES colleges(id) ON DELETE RESTRICT, -- colleges are never physically deleted (RFC 67/0023)

  status    TEXT NOT NULL DEFAULT 'open',   -- open | surfaced
  rationale TEXT NOT NULL,                   -- the coach's pitch, grounded in real match numbers

  surfaced_at          TIMESTAMPTZ NULL,
  surfaced_in_convo_id UUID        NULL REFERENCES convos(id) ON DELETE RESTRICT,

  CONSTRAINT fit_suggestions_status_check           CHECK (status IN ('open','surfaced')),
  CONSTRAINT fit_suggestions_rationale_length_check CHECK (length(rationale) <= 2048),
  CONSTRAINT fit_suggestions_rationale_not_empty_check CHECK (length(trim(rationale)) > 0),
  -- surfaced iff both surfacing columns set; an 'open' row has both NULL.
  CONSTRAINT fit_suggestions_surfaced_consistency_check CHECK (
    (status = 'surfaced') = (surfaced_at IS NOT NULL AND surfaced_in_convo_id IS NOT NULL)
  ),
  -- The novelty backstop: a college is proposed to a student at most once, ever.
  CONSTRAINT fit_suggestions_student_college_unique UNIQUE (student_id, college_id)
);

CREATE INDEX fit_suggestions_student_open_idx ON fit_suggestions (student_id) WHERE status = 'open';
CREATE INDEX fit_suggestions_student_idx      ON fit_suggestions (student_id, created_at);
```

Guard triggers mirror `commitments` (`prevent_physical_delete`,
`prevent_immutable_updates`, `prevent_physical_timestamp_update`,
`update_timestamp`), reusing the shared functions with no new function.

**`fit_lens_runs`** — an append-only log: the per-student token ledger,
provenance, and freshness marker, mirroring `synthesis_runs`. Because a pass
makes two billed calls, its four token columns hold the **sum** of both calls
(every token on a completed pass recorded, failed passes included); per-call
granularity — and capturing partial/retried-pass spend — is deferred to the
planned separate token-accounting table (see Failure semantics).

```sql
CREATE TABLE fit_lens_runs (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

  -- 'applied' completed a full pass and advances the freshness marker (with or
  -- without a suggestion written); 'failed' billed tokens for unusable output.
  outcome TEXT NOT NULL,

  query_system_prompt_id  UUID NOT NULL REFERENCES system_prompts(id) ON DELETE RESTRICT,
  reason_system_prompt_id UUID NOT NULL REFERENCES system_prompts(id) ON DELETE RESTRICT,
  provider       TEXT NOT NULL,
  model_resolved TEXT NULL,

  suggestions_written INTEGER NOT NULL DEFAULT 0,  -- 0 (no novel fit) or 1
  -- size of the retrieved set call #2 saw: 0 for a completed retrieve that
  -- matched nothing; NULL only when the retrieve call never ran (a Failed pass
  -- that died at LLM call #1, before any search).
  matches_considered  INTEGER NULL,

  input_tokens       INTEGER NULL,
  output_tokens      INTEGER NULL,
  cache_read_tokens  INTEGER NULL,
  cache_write_tokens INTEGER NULL,

  CONSTRAINT fit_lens_runs_outcome_check  CHECK (outcome IN ('applied','failed')),
  CONSTRAINT fit_lens_runs_provider_check CHECK (provider IN ('anthropic','log')),
  CONSTRAINT fit_lens_runs_model_resolved_length_check
    CHECK (model_resolved IS NULL OR length(model_resolved) <= 255),
  CONSTRAINT fit_lens_runs_suggestions_bounds_check CHECK (suggestions_written BETWEEN 0 AND 1),
  CONSTRAINT fit_lens_runs_failed_counts_check
    CHECK (outcome <> 'failed' OR suggestions_written = 0),
  CONSTRAINT fit_lens_runs_matches_nonneg_check
    CHECK (matches_considered IS NULL OR matches_considered >= 0),
  CONSTRAINT fit_lens_runs_tokens_nonneg_check CHECK (
    (input_tokens       IS NULL OR input_tokens       >= 0) AND
    (output_tokens      IS NULL OR output_tokens      >= 0) AND
    (cache_read_tokens  IS NULL OR cache_read_tokens  >= 0) AND
    (cache_write_tokens IS NULL OR cache_write_tokens >= 0)
  )
);

CREATE INDEX fit_lens_runs_student_applied_idx
  ON fit_lens_runs (student_id, created_at) WHERE outcome = 'applied';
CREATE INDEX fit_lens_runs_student_idx ON fit_lens_runs (student_id, created_at);
```

Guard triggers mirror `synthesis_runs` (`prevent_log_update`,
`prevent_log_delete`).

**Kotlin domain types** (`:db` `models/`): `FitSuggestionId` (inline value class
over `UUID`), `FitSuggestionStatus` (`OPEN`/`SURFACED`, TEXT-backed enum),
`FitSuggestion`, `NewFitSuggestion(studentId, collegeId, rationale)`,
`FitSuggestionForOpener(id, collegeName, city, state, rationale)` (the opener
projection), `FitLensOutcome` (`APPLIED`/`FAILED`), `FitLensRun`,
`NewFitLensRun`. `FitLensResult` (`:service`) is the `discover()` return:
`Applied` / `Skipped(reason)` / `Failed(reason)` /
`TransientFailure(message, cause)`.

**Failure semantics — two failure modes, split by cause.** fit-lens
distinguishes a **transient service error** from **unusable model output**, and
they get opposite queue treatment:

- **`TransientFailure(message, cause)`** — a genuinely transient service error
  (provider 5xx / timeout, network blip, transient DB error) raised _before the
  pass produced a usable result_. This mirrors
  `SynthesisResult.TransientFailure` → `JobResult.RetriableFailure`, so the
  queue retries up to `maxAttempts`. It writes **no** `fit_lens_runs` row (the
  pass never completed) and does **not** count toward the failure circuit
  breaker (an infra blip is not a model-state failure). A thrown transient
  exception that escapes `discover` reaches the same outcome — `QueueWorker`
  catches it into `RetriableFailure` — but `discover` returns `TransientFailure`
  explicitly where it can, for a testable contract.
- **`Failed(reason)`** — the pass ran to completion but the **model output** is
  unusable (unparseable / type-invalid `CollegeQuery`, or a `collegeId` outside
  the match set). This is dead-lettered, **not** retried: it writes a billed
  `failed` `fit_lens_runs` row and the handler returns `JobResult.Success`, so
  the queue does not re-run it. A same-model re-parse has no reason to succeed,
  so a retry would just re-bill two LLM calls; the weekly tick, bounded by the
  failure circuit breaker, is the only re-run path. This is the deliberate
  divergence from synthesis, which has no dead-letter path.

The variants map to `FitLensRunsDao.append` and the queue as follows (single
source for the mapping scattered across the steps above):

| `FitLensResult`             | `fit_lens_runs` row                                                   | `suggestions_written` | queue `JobResult`                           |
| --------------------------- | --------------------------------------------------------------------- | --------------------- | ------------------------------------------- |
| `Applied`                   | one `applied` row                                                     | 0 or 1                | `Success`                                   |
| `Skipped(reason)`           | pre-LLM gate: **no row**; zero-match / reason-`{}`: one `applied` row | — / 0                 | `Success`                                   |
| `Failed(reason)`            | one `failed` row (billed tokens)                                      | 0                     | `Success` (dead-lettered, no retry)         |
| `TransientFailure(msg, cz)` | **no row**                                                            | —                     | `RetriableFailure` (retries, `maxAttempts`) |

`Skipped` splits by _where_ it short-circuits: the three pre-LLM gates
(minClaims, freshness, failure circuit breaker) spend no tokens and write **no**
run row; a `Skipped` after the LLM calls (zero matches, or reason returns `{}`)
has spent tokens, so it writes an `applied` row with `suggestions_written = 0`
and advances freshness. A retried transient pass re-runs `discover` from the
top, so any tokens a partial attempt already billed are re-spent and are not
captured in `fit_lens_runs` (its per-pass ledger records only completed passes,
`applied` or `failed`).

**Known, accepted gap — partial/retried-pass token capture.** Because
`fit_lens_runs` records one row per completed pass (summing both calls) rather
than one row per billed call, tokens spent by a transient-failed or retried
partial pass are billed but not recorded. This is **knowingly accepted for
now**: transient failures are rare and the loss is small. The fix is deferred to
a **separate token-accounting table** that records one row per billed LLM call
(the grain `extraction_runs` already uses), which closes the gap for every
call-based feature at once; fit-lens will move its token accounting onto that
table when it lands. Until then this gap is acceptable.

**Seed — system prompts** (`system_prompts`, mirroring
`0026.seed-synthesis-system-prompt.sql`): two rows, `('fit_lens_query','v1',…)`
and `('fit_lens_reason','v1',…)`. The **query** prompt body documents the
`CollegeQuery` filter schema **and the codebooks for its coded fields**
(`cipPrefix` CIP program code; `control` 1=public / 2=private-nonprofit /
3=private-for-profit; `region`/`locales` Census codes), instructs the model to
emit a JSON object of only those fields and omit any uncertain axis, and to name
no school. The **reason** prompt body instructs the model to choose one school
**from the supplied matches only**, ground the rationale in the supplied numbers
and the student's claims, and return `{}` when nothing fits. Both are
architect-approved verbatim copy; a later version is a new row, never an edit.

**Seed — periodic job** (`periodic_jobs`, RFC 97, mirroring
`0030.seed-synthesis-periodic-job.sql`):

```sql
INSERT INTO periodic_jobs (name, job_type, payload, schedule, timezone, next_run_at, enabled)
VALUES (
  'fit-lens', 'FIT_LENS_SWEEP', '{}', '0 4 * * 1', 'UTC',
  date_trunc('week', NOW() AT TIME ZONE 'UTC') + INTERVAL '1 week 4 hours',
  FALSE
);
```

`'0 4 * * 1'` = 04:00 UTC every Monday (weekly, slower than synthesis's daily);
`next_run_at` seeds to the next Monday 04:00 UTC boundary, and RFC 97's
scheduler owns the column thereafter. `enabled = FALSE` so the sweep never fires
where the worker has fit-lens off (its `FIT_LENS` jobs would hit "no handler
registered"); the operator sets `enabled = TRUE` in the same environments where
`fitLens.enabled = true`, coupled by convention exactly as synthesis is.

### API contracts

- **Queue** (`:queue`): `JobType.FIT_LENS_SWEEP` and `JobType.FIT_LENS` added;
  `FitLensPayload(studentId: String)` (`@Serializable`), mirroring
  `SynthesisPayload`.
- **`FitLensSweepHandler(database, queueService)`** (`:service`
  `coaching/fitlens/`): a `JobHandler` for `FIT_LENS_SWEEP`,
  `config = JobTypeConfig(concurrency = 1, maxAttempts = 1, executionTimeout = 5.minutes, lockDuration = 10.minutes)`.
  `execute` lists active students (`StudentsDao.listActiveIds(session)` — a
  precondition RFC 97 must provide; see Dependencies) and
  `queueService.enqueue(FIT_LENS, …)` per student, best-effort (one enqueue
  failure is logged and the sweep continues). `maxAttempts = 1`: the next weekly
  tick re-produces the sweep.
- **`FitLensHandler(fitLensService)`** (`:service` `coaching/fitlens/`): a
  `JobHandler` for `FIT_LENS`,
  `config = JobTypeConfig(concurrency = 2, maxAttempts = 3, executionTimeout = 8.minutes, lockDuration = 15.minutes)`
  — timeouts heavier than synthesis's (two LLM calls plus retrieval),
  `executionTimeout < lockDuration`. `maxAttempts = 3` (fewer than synthesis's
  5, since each attempt is heavier — two LLM calls plus retrieval): a
  `TransientFailure` maps to `JobResult.RetriableFailure` and rides out a
  transient provider/DB blip across up to three attempts, while a `Failed` pass
  (unusable model output) is dead-lettered as `JobResult.Success` and never
  retried — the two failure modes are split per Failure semantics. Deserializes
  `FitLensPayload`, returns `PermanentFailure` on a malformed payload, delegates
  to `discover`, and maps its `FitLensResult` to a `JobResult`.
- **`FitLensService(database, chatProvider, collegeSearchService, config)`**
  (`:service` `coaching/fitlens/`): `discover(studentId): FitLensResult`.
  `CollegeSearchService` is constructor-injected (a thin orchestrator over
  `Database`), not built internally.
- **`FitSuggestionsDao`** (`:db`): `create(session, NewFitSuggestion)`;
  `listSuggestedCollegeIds(session, studentId): Result<List<CollegeId>>` (the
  novelty recheck source, any status);
  `listOpenForOpener(session, studentId):
  Result<List<FitSuggestionForOpener>>`
  (the opener read, joined to `colleges`);
  `markSurfaced(session, id, convoId): Result<FitSuggestion>`; `findById`;
  `list` (admin).
- **`FitLensRunsDao`** (`:db`): `append(session, NewFitLensRun)`;
  `lastAppliedAt(session, studentId): Result<Instant?>` (freshness marker);
  `consecutiveFailuresSince(session, studentId): Result<Int>` (count of `failed`
  runs since the last `applied` run — the circuit-breaker input); `findById`;
  `list` (admin).
- **`FitLensConfig`** (`:service` `coaching/fitlens/`, `fitLens` block of
  `service.conf`): `enabled`, `model`, `queryMaxTokens`, `reasonMaxTokens`,
  `searchLimit` (coerced into `CollegeSearchService`'s `1..25` band — a value
  outside it is silently clamped), `minClaims`, `maxClaims` (caps the active
  claims assembled into the prompts, mirroring `synthesis.maxClaims`),
  `maxConsecutiveFailures` (the circuit-breaker bound, default `3`),
  `queryPromptName`/`queryPromptVersion`,
  `reasonPromptName`/`reasonPromptVersion`.
- **Admin** (RFC 77 engine, read-only): `FitSuggestionsResource`,
  `FitLensRunsResource`, mirroring
  `CommitmentsResource`/`SynthesisRunsResource`.

### Worker wiring

`queue-worker/…/Application.kt`: `FitLensConfig.from(config)` is read; the
shared `chatProvider` construction gate widens to
`if (extractionConfig.enabled || synthesisConfig.enabled || fitLensConfig.enabled)`;
a new `if (fitLensConfig.enabled)` block builds `CollegeSearchService(database)`
and `FitLensService(…)`, registering both `FitLensHandler` and
`FitLensSweepHandler`. `:college` is added to `queue-worker/build.gradle.kts`
(needed to construct `CollegeSearchService`; `:service` depends on `:college`
via `implementation`, so the type is not otherwise on the worker's classpath).

### Error handling / edge cases

- **Malformed `CollegeQuery` JSON / bad `collegeId` from the model.** Parsed
  defensively; the pass completes with unusable output (`Failed`), tokens are
  recorded, the run is `failed`, freshness is unchanged. The pass is
  **dead-lettered, not retried**: the handler returns `JobResult.Success` so the
  queue does not re-run it. A same-model re-parse has no reason to succeed, so
  re-running would just re-bill two LLM calls; the weekly tick, bounded by the
  failure circuit breaker below, is the only re-run path.
- **Transient service error (provider 5xx / timeout, network, transient DB).**
  Distinct from unusable output: the pass never produced a result, so `discover`
  returns `TransientFailure` (or a thrown exception the queue catches), which
  maps to `JobResult.RetriableFailure` and is retried up to `maxAttempts = 3`.
  No `fit_lens_runs` row is written and it does not count toward the circuit
  breaker (an infra blip is not a model-state failure). A retry re-runs
  `discover` from the top; capturing tokens a partial attempt already billed is
  the known, accepted gap deferred to the separate token-accounting table (see
  Failure semantics).
- **Persistent parse failure on an unchanged model.** A `failed` run does not
  advance freshness, so an unchanged-but-unparseable model would otherwise
  re-bill two LLM calls every weekly tick. The read-phase failure circuit
  breaker (`maxConsecutiveFailures`, default 3) stops the pass as `Skipped` once
  that many `failed` runs accumulate since the last `applied`, bounding the
  re-billing; it resets on the next `applied` run.
- **Zero matches, or the reason call returns `{}`.** A valid `Skipped`/`applied`
  outcome with `suggestions_written = 0` — freshness advances, no retry.
- **Novelty collision at write.** The under-lock recheck (or the `UNIQUE`
  backstop, handled as a skip, not an error) writes no suggestion;
  `suggestions_written = 0`, run `applied`.
- **Failure before both prompts resolve.** No LLM call was billed, so no
  `fit_lens_runs` row is written (both prompt pins are `NOT NULL`). A transient
  cause (e.g. the DB read or first provider call blips) returns
  `TransientFailure` and is retried; a permanent cause (a prompt-catalog row
  genuinely missing) is a config error surfaced as `TransientFailure` as well —
  retrying is harmless and the operator fixes the seed — no `Failed` row, since
  `Failed` is reserved for a completed pass with unusable model output.
- **Concurrent passes for one student.** Best-effort single execution (RFC 97):
  the advisory lock serializes the short read/write transactions but is released
  across the LLM calls, so two overlapping passes could each write a _different_
  novel school — tolerated (both novel, both deduped by `UNIQUE`); a
  re-suggested school is impossible.
- **`enabled = FALSE` seed / handler off.** No `FIT_LENS_SWEEP` fans out; if a
  stray `FIT_LENS` is enqueued with the handler unregistered, the queue logs "no
  handler registered" (existing contract).
- **Surfacing off / no open suggestions.** The opener contributes nothing; the
  commitment path is unaffected.

### Dependencies

- **RFC 97 (hard, NOT on this branch's base).** As of this branch the highest
  migration is `0028`, there is no `rfc/97-*`, no `:cron` process, no
  `periodic_jobs` table, and no `StudentsDao.listActiveIds`. This RFC therefore
  **blocks on RFC 97** for: the `:cron` process, the `periodic_jobs` table, RFC
  97's scheduler, and `StudentsDao.listActiveIds`. fit-lens only **seeds a
  `periodic_jobs` row**; it builds no scheduling. The migration numbers below
  (`0031`–`0033`) assume RFC 97's `0029`/`0030` land first, and
  **renumber-at-impl** to the next free trio (the chain is strictly sequential).
  - **Precondition — `StudentsDao.listActiveIds`.** `FitLensSweepHandler`
    depends on it unguarded. RFC 97 MUST provide it with the signature
    `StudentsDao.listActiveIds(session: SqlSession): Result<List<StudentId>>`
    (active = not soft-deleted). If RFC 97 lands without it, this RFC's impl
    adds it (and its DAO test) as part of step 6; either way it does not exist
    today and is not counted as existing reuse.
- **Sweep→fan-out pattern.** fit-lens follows the sweep-dispatcher→per-unit
  fan-out pattern RFC 97 introduces for synthesis; there is no existing
  `SYNTHESIS_SWEEP` `JobType` variant or `:cron` enqueue path to copy — the
  pattern is inherited from RFC 97's design, and the concrete `FIT_LENS_SWEEP`
  symbols are new here.
- **Reused, live on this branch:** `CollegeSearchService`/`CollegeQuery`/
  `CollegeMatch` (RFC 67), `ChatProvider` (`.chat`), `ClaimsDao`,
  `CollegeListEntriesDao`, `SystemPromptsDao`, `AdvisoryLockDao`, the queue
  (`JobType`, `JobHandler`, `JobTypeConfig`, `QueueService` — the base
  primitives, minus any sweep variant), `CoachingService`'s opener (RFC 93), and
  the RFC 77 admin engine.
- No new Gradle module; no new third-party dependency.

## Tests

DB/DAO tests use the project harness (recreated test DB); service tests use a
real `Database` with a fake `ChatProvider` scripting the two calls, and the real
`CollegeSearchService` over seeded `colleges`. Run
`nix develop -c bin/test <module> -f`, verifying executed-vs-declared counts
(block-body tests only).

**Migration (`db`).**

- `0031`–`0033` apply on a fresh DB (atop RFC 97). `fit_suggestions` and
  `fit_lens_runs` have their columns, indexes, and triggers; the two prompt seed
  rows and the `fit-lens` `periodic_jobs` row exist
  (`job_type='FIT_LENS_SWEEP'`, `schedule='0 4 * * 1'`, `enabled=FALSE`).
- `UNIQUE(student_id, college_id)` rejects a second suggestion of the same
  college to the same student; length/consistency CHECKs reject an empty
  `rationale` and an `open` row with surfacing columns set; `UPDATE` bumps
  `updated_at`; a physical delete/immutable-update is refused.

**`FitSuggestionsDaoTest` (`db`).**

- `create` persists an `open` suggestion; a duplicate `(student, college)` is
  rejected. `listSuggestedCollegeIds` returns every suggested college id
  regardless of status. `listOpenForOpener` returns open rows joined to the
  college name/city/state and excludes surfaced rows. `markSurfaced` flips
  status, sets `surfaced_at`/`surfaced_in_convo_id`.

**`FitLensRunsDaoTest` (`db`).**

- `append` persists `applied` and `failed` rows with summed tokens and both
  prompt pins. `lastAppliedAt` returns the latest `applied` `created_at`, null
  when never applied, and ignores `failed` rows. `consecutiveFailuresSince`
  counts `failed` runs since the last `applied` (0 when the most recent run is
  `applied`; counts from the first run when never applied; resets after an
  intervening `applied`).

**`FitLensServiceTest` (`service`).**

- Happy path: seeded claims + colleges; fake provider returns a `CollegeQuery`
  JSON then a reasoning JSON naming a retrieved college → one `open`
  `fit_suggestion` written, run `applied`, `suggestions_written=1`, tokens
  summed across both scripted calls.
- Novelty (write-time, the invariant): the reasoned college is already on the
  student's `college_list` (and, separately, already in `fit_suggestions`) →
  **no** suggestion written, run `applied`, `suggestions_written=0`. Proves the
  gate holds even when call #2 names a known school.
- minClaims floor, freshness gate, and failure circuit breaker
  (`maxConsecutiveFailures` `failed` runs already logged) each → `Skipped`, no
  LLM call, no run written.
- maxClaims cap: with more than `config.maxClaims` active claims seeded, the
  claim payload the fake provider observes on call #1 is truncated to
  `maxClaims` (the excess claims are absent), bounding prompt size.
- Zero search matches, and reason returns `{}` → `Skipped`/`applied` with no
  suggestion.
- Malformed `CollegeQuery` JSON, and a `collegeId` outside the match set → each
  `FitLensResult.Failed`, a `failed` run recording the spent tokens, freshness
  unchanged. Dead-letter assertion: `FitLensHandler.execute` over the same
  malformed-output scenario returns `JobResult.Success` (no retry).
- Transient service error: the fake provider throws a transient error on call #1
  → `FitLensResult.TransientFailure`, **no** `fit_lens_runs` row written,
  freshness unchanged, and (separately) the circuit-breaker count is unmoved.

**`FitLensSweepHandlerTest` (`service`).**

- N active students → N `FIT_LENS` jobs enqueued (read through the real
  `QueueService`), `Success`; zero active students → `Success`, zero enqueues; a
  single enqueue failure is logged and does not abort the sweep.
- `config` advertises `FIT_LENS_SWEEP` and `executionTimeout < lockDuration`.

**`FitLensHandlerTest` (`service`).**

- A valid payload dispatches to `discover`; a malformed payload →
  `PermanentFailure` (no retry); a `discover` that returns `Failed` →
  `JobResult.Success` (dead-lettered, no retry); a `discover` that returns
  `TransientFailure` → `JobResult.RetriableFailure` (retried); `config`
  advertises `FIT_LENS`, `maxAttempts == 3`, and
  `executionTimeout < lockDuration`.

**`CoachingServiceTest` (`service`).**

- With `surfaceFitSuggestions=true` and an open `fit_suggestion`, `startConvo`'s
  opener composes the "I found a school you'd love: &lt;name&gt;" line and marks
  the suggestion surfaced on a successful first reply; with the gate off, it
  does not, and the commitment opener is unaffected.

**`CoachingConfigTest` / `FitLensConfigTest` (`service`).**

- `surfaceFitSuggestions` is read; `FitLensConfig.from` reads every key and
  fails on an absent one.

**Admin.**

- `FitSuggestionsResourceTest`, `FitLensRunsResourceTest`: list/detail render
  the persisted fields; no create/edit/delete affordance.

## Invariants

One, targeting `service/src/main/kotlin/ed/unicoach/coaching/fitlens/`.

- **Rule:** fit-lens MUST re-verify a proposed college's novelty at write time,
  under the student advisory lock, against the student's structured college ids
  (active `college_list` entries and prior `fit_suggestions`); the LLM exclusion
  set is steering only, never the novelty guarantee. **Why:** the LLM is
  non-deterministic and will periodically re-name a school the student already
  knows; trusting its exclusion-set compliance re-suggests known schools and
  erodes trust in the feature. The `UNIQUE(student_id, college_id)` constraint
  is the backstop for prior suggestions, but the `college_list` dimension is
  cross-table and only the write-time recheck covers it. **Target:**
  `service/src/main/kotlin/ed/unicoach/coaching/fitlens/`.

RFC 97's idempotency invariant (a periodic job must be safe to run concurrently
with itself and to re-run) is **inherited, not redeclared** — fit-lens satisfies
it via the `UNIQUE` constraint plus the write-time recheck (a concurrent or
re-run pass cannot double-suggest).

## Implementation Plan

Each step is atomic and locally verifiable in the Nix dev shell. Steps assume
RFC 97 is on the base; renumber `0031`–`0033` to the next free trio at impl
time.

1. **Migration + seeds.** Add `0031.create-fit-lens.sql` (both tables, indexes,
   triggers, `UNIQUE`), `0032.seed-fit-lens-prompts.sql` (two `system_prompts`
   rows), `0033.seed-fit-lens-periodic-job.sql` (the `periodic_jobs` row).
   Verify: `nix develop -c bin/test db -f`;
   `psql "$POSTGRES_DB" -c '\d+ fit_suggestions' -c '\d+ fit_lens_runs'`.

2. **Domain models + DAOs.** `FitSuggestion*`/`FitLensRun*` models;
   `FitSuggestionsDao`, `FitLensRunsDao`; `FitSuggestionsDaoTest`,
   `FitLensRunsDaoTest`. Verify: `nix develop -c bin/test db -f`.

3. **Queue additions.** `JobType.FIT_LENS_SWEEP`/`FIT_LENS`; `FitLensPayload`.
   Verify: `nix develop -c ./gradlew :queue:compileKotlin`.

4. **`FitLensConfig` + `service.conf`.** Add the `fitLens` block (default
   `enabled = false`) and `coaching.surfaceFitSuggestions = true`;
   `FitLensConfig`
   - `CoachingConfig.surfaceFitSuggestions`; `FitLensConfigTest`,
     `CoachingConfigTest` case. Verify: `nix develop -c bin/test service -f`.

5. **`FitLensService` + test.** The two-call `discover` and `FitLensResult`;
   `FitLensServiceTest` (happy, novelty, gates, zero-match, failure paths).
   Verify: `nix develop -c bin/test service -f`.

6. **Handlers + test.** `FitLensHandler`, `FitLensSweepHandler`;
   `FitLensHandlerTest`, `FitLensSweepHandlerTest`. Verify:
   `nix develop -c bin/test service -f`.

7. **Invariant.** Create
   `service/src/main/kotlin/ed/unicoach/coaching/fitlens/INVARIANTS.md` with the
   novelty rule above. Verify: `nix develop -c deno fmt --check` on the file.

8. **Opener surfacing.** Extend `CoachingService` to read/compose/mark open
   `fit_suggestions` under `surfaceFitSuggestions`; `CoachingServiceTest` cases.
   Verify: `nix develop -c bin/test service -f`.

9. **Worker registration.** `:college` dep on `queue-worker/build.gradle.kts`;
   read `FitLensConfig`, widen the `chatProvider` gate, register both handlers
   in `queue-worker/…/Application.kt`. Verify:
   `nix develop -c ./gradlew :queue-worker:compileKotlin`.

10. **Admin resources.** `FitSuggestionsResource`, `FitLensRunsResource` +
    tests; register in
    `admin-web/src/main/kotlin/ed/unicoach/admin/Application.kt`. Verify:
    `nix develop -c bin/test admin-web -f`.

11. **Docs + full gate.** Update `features/coaching-memory.md` (mark `fit-lens`
    designed → RFC 98; note it is a synthesis sibling, not a lens). Verify:
    `nix develop -c bin/format -c && nix develop -c bin/test check -f`.

## Files Modified

**Created**

- `db/schema/0031.create-fit-lens.sql`
- `db/schema/0032.seed-fit-lens-prompts.sql`
- `db/schema/0033.seed-fit-lens-periodic-job.sql`
- `db/src/main/kotlin/ed/unicoach/db/models/FitSuggestionId.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/FitSuggestionStatus.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/FitSuggestion.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewFitSuggestion.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/FitSuggestionForOpener.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/FitLensOutcome.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/FitLensRun.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewFitLensRun.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/FitSuggestionsDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/FitLensRunsDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/FitSuggestionsDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/FitLensRunsDaoTest.kt`
- `queue/src/main/kotlin/ed/unicoach/queue/FitLensPayload.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensService.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensResult.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensConfig.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensHandler.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensSweepHandler.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/INVARIANTS.md`
- `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensHandlerTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensSweepHandlerTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensConfigTest.kt`
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/FitSuggestionsResource.kt`
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/FitLensRunsResource.kt`
- `admin-web/src/test/kotlin/ed/unicoach/admin/resources/FitSuggestionsResourceTest.kt`
- `admin-web/src/test/kotlin/ed/unicoach/admin/resources/FitLensRunsResourceTest.kt`

**Modified**

- `queue/src/main/kotlin/ed/unicoach/queue/JobType.kt` — add `FIT_LENS_SWEEP`,
  `FIT_LENS`.
- `queue-worker/build.gradle.kts` — add `implementation(project(":college"))`.
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` — read
  `FitLensConfig`, widen the `chatProvider` gate, register `FitLensHandler` and
  `FitLensSweepHandler`.
- `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt` — opener
  reads/composes/marks open `fit_suggestions`.
- `service/src/main/kotlin/ed/unicoach/coaching/CoachingConfig.kt` — add
  `surfaceFitSuggestions`.
- `service/src/main/resources/service.conf` — add the `fitLens` block and
  `coaching.surfaceFitSuggestions`.
- `service/src/test/kotlin/ed/unicoach/coaching/CoachingServiceTest.kt` — opener
  fit-suggestion cases.
- `service/src/test/kotlin/ed/unicoach/coaching/CoachingConfigTest.kt` —
  `surfaceFitSuggestions` case.
- `admin-web/src/main/kotlin/ed/unicoach/admin/Application.kt` — register the
  two resources.
- `features/coaching-memory.md` — mark `fit-lens` designed (RFC 98); sibling of
  synthesis.
