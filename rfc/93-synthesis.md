# RFC 93: Synthesis

## Executive Summary

Synthesis is a per-student background reflection pass: it reads a student's
accumulated model — distilled `claims`, their `college_list_entries`, and a
current-date/calendar context — and writes **commitments**, coach-owned
intentions the coach surfaces the next time the student shows up.

This RFC defines the `commitments` schema here, in its first consumer, rather
than in `extraction` (RFC 66): extraction never writes a commitment, so defining
it there would be a writerless schema (the brief's no-standalone-schema
decision). A commitment resolves (`open → fulfilled | dropped`), carries a
trigger, declares `disclosure = explicit | internal`, and cites the claims it
reasoned over for provenance. `fulfilled / explicit` is the promise-kept metric;
internal commitments are coaching notes, excluded from it.

The MVP ships three **internal lenses** — gap, timing, contradiction — which
reason only over data already in the model, so synthesis ships without depending
on `college-knowledge` or the fit-lens (a later RFC). The pass mirrors
extraction's worker structure (read transaction under a student advisory lock →
LLM call outside any transaction → write transaction re-acquiring the lock),
records per-student token usage on every billed call including failures
(`synthesis_runs`, mirroring `extraction_runs`), and stays off the chat request
path (RFC 43).

Delivery is **pull-only**: open explicit commitments ride the coach's first
reply in the student's next conversation and are marked fulfilled (mechanics in
Detailed Design). No notification infrastructure is built (`push-delivery`,
deferred). The **periodic trigger is deferred to a scheduler/cron RFC**; this
RFC ships the per-student pass, invoked manually via the existing
`bin/q-enqueue` until the cron exists.

## Detailed Design

### Placement and trigger

Synthesis runs only in the `queue-worker` daemon, as a `JobHandler` for a new
`JobType.SYNTHESIZE_STUDENT` whose payload is `{studentId}`. It follows the
extraction precedent (RFC 66): a domain `SynthesisService` owns the pass and a
thin `SynthesisHandler` adapts it to `ed.unicoach.queue.JobHandler`. The
multi-second LLM call never pins a pooled connection or holds a lock (see
Synthesis behavior).

**The periodic trigger is out of scope and deferred to a future scheduler/cron
RFC.** No scheduler, cron, self-re-enqueue, or daemon tick exists in this
codebase today; the `jobs` table supports only one-shot delayed execution
(`scheduled_at`). Rather than build an ad-hoc scheduler here, this RFC ships the
per-student pass and leaves recurrence — the clock and the "which students"
eligibility sweep — to a dedicated cron RFC. Until it lands, a pass is enqueued
manually with the existing tool:
`bin/q-enqueue SYNTHESIZE_STUDENT '{"studentId":"<uuid>"}'`. Synthesis is
therefore **not** enqueued from the chat request path; `ConvoRoutes` and
`rest-server` are untouched by the generation side (they change only for the
delivery read — see Pull delivery). The per-student **freshness gate** (below)
makes even a naive future cron that enqueues every student cheap: a student
whose model has not changed since their last applied run no-ops before any LLM
call.

### Schema (migration `0025.create-synthesis.sql`)

Three tables. `commitments` is a mutable entity (`postgres-entity-table-design`)
modeled exactly on `claims` (RFC 66): status-based lifecycle, no versioning, no
`deleted_at`. `commitment_support` and `synthesis_runs` are append-only logs
(`postgres-log-table-design`). All reuse the shared guard functions from prior
migrations (`prevent_physical_delete`, `prevent_immutable_updates`,
`prevent_physical_timestamp_update`, `update_timestamp`, `prevent_log_update`,
`prevent_log_delete`). Closed enums are `TEXT` + named `CHECK` (project
convention). PostgreSQL 18; `uuidv7()` is built-in.

#### `commitments` — mutable entity

A coach-owned intention derived from reflection over the student's model. It
resolves from `open` to `fulfilled` (surfaced to the student as a promise kept)
or `dropped` (its basis went away). `trigger` is a SQL reserved word, so the
surfacing condition column is named `trigger_kind`.

```sql
CREATE TABLE commitments (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

  lens       TEXT NOT NULL,                     -- gap | timing | contradiction
  disclosure TEXT NOT NULL,                     -- explicit | internal
  status     TEXT NOT NULL DEFAULT 'open',      -- open | fulfilled | dropped

  statement    TEXT NOT NULL,                   -- the coach's intention, free text
  trigger_kind TEXT NOT NULL DEFAULT 'next_session', -- surfacing condition
  trigger_at   TIMESTAMPTZ NULL,                -- advisory date the insight references (timing); recorded, never acted on (no scheduler)

  fulfilled_at          TIMESTAMPTZ NULL,
  disclosed_in_convo_id UUID        NULL REFERENCES convos(id) ON DELETE RESTRICT, -- convos are never physically deleted (prevent_physical_delete), so RESTRICT never fires
  dropped_at            TIMESTAMPTZ NULL,
  drop_reason           TEXT        NULL,

  CONSTRAINT commitments_lens_check         CHECK (lens IN ('gap','timing','contradiction')),
  CONSTRAINT commitments_disclosure_check   CHECK (disclosure IN ('explicit','internal')),
  CONSTRAINT commitments_status_check       CHECK (status IN ('open','fulfilled','dropped')),
  CONSTRAINT commitments_trigger_kind_check CHECK (trigger_kind IN ('next_session')),
  CONSTRAINT commitments_statement_length_check     CHECK (length(statement) <= 2048),
  CONSTRAINT commitments_statement_not_empty_check  CHECK (length(trim(statement)) > 0),
  CONSTRAINT commitments_drop_reason_length_check   CHECK (drop_reason IS NULL OR length(drop_reason) <= 255),
  -- Lifecycle consistency: fulfilled iff surfaced into a convo; dropped iff timestamped.
  -- Together these force an 'open' row to have all three resolution columns NULL.
  CONSTRAINT commitments_fulfilled_consistency_check CHECK (
    (status = 'fulfilled') = (fulfilled_at IS NOT NULL AND disclosed_in_convo_id IS NOT NULL)
  ),
  CONSTRAINT commitments_dropped_consistency_check CHECK (
    (status = 'dropped') = (dropped_at IS NOT NULL)
  )
);

-- The opener read (delivery): open explicit commitments for a student.
CREATE INDEX commitments_student_open_explicit_idx
  ON commitments (student_id) WHERE status = 'open' AND disclosure = 'explicit';
-- Synthesis open-set read + admin filtering.
CREATE INDEX commitments_student_status_idx ON commitments (student_id, status);

CREATE TRIGGER trigger_00_prevent_commitments_physical_delete
BEFORE DELETE ON commitments FOR EACH ROW EXECUTE PROCEDURE prevent_physical_delete();
CREATE TRIGGER trigger_00a_prevent_commitments_immutable_updates
BEFORE UPDATE ON commitments FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_updates();
CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON commitments FOR EACH ROW EXECUTE PROCEDURE prevent_physical_timestamp_update();
CREATE TRIGGER trigger_03_enforce_commitments_updated_at
BEFORE UPDATE ON commitments FOR EACH ROW EXECUTE PROCEDURE update_timestamp();
```

The trigger set is `claims`' set verbatim (retargeted): a mutable, status-based
entity with the four-timestamp split and no versioning. `disclosure = internal`
means "not announced as a promise," not "hidden" (brief principle 4): an
internal commitment is a coaching note that persists in the record and feeds the
next synthesis pass's dedup context, but is never surfaced as an opener and is
excluded from the promise-kept metric. No CHECK forbids `internal` +
`fulfilled`; disclosure is a delivery/metric filter, not a storage constraint,
so the model stays open to a future lens that surfaces internal insights.
`trigger_kind` is a single-member CHECK today (`next_session`); a future
time/event trigger widens it by migration and populates `trigger_at`, which this
RFC records but no scheduler reads. A `timing` commitment therefore surfaces at
the next session like any other — `trigger_at` is provenance (the future date
the insight references) only, inert until a scheduler RFC reads it.

#### `commitment_support` — append-only link log

The many-to-many link from a commitment to the **claims** it reasoned over. Each
row is the immutable fact "this claim was cited as basis for this commitment."
Synthesis reflects over distilled claims, never the raw transcript (brief
principle 3), so the honest provenance target is claims; the trail to the
utterance is preserved transitively through `claim_support` (commitment → claim
→ observation). A commitment with no support rows is a whole- model inference
(e.g. a gap — the absence of any claim about a topic), exactly as most `claims`
cite no observation.

```sql
CREATE TABLE commitment_support (
  commitment_id UUID NOT NULL REFERENCES commitments(id) ON DELETE CASCADE,
  claim_id      UUID NOT NULL REFERENCES claims(id)      ON DELETE CASCADE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (commitment_id, claim_id)
);

-- Reverse lookup: "which commitments does this claim back" (stale-drop + admin).
CREATE INDEX commitment_support_claim_idx ON commitment_support (claim_id);

CREATE TRIGGER trigger_00_prevent_commitment_support_update
BEFORE UPDATE ON commitment_support FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_commitment_support_delete
BEFORE DELETE ON commitment_support FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
```

#### `synthesis_runs` — append-only log (token ledger + provenance + freshness marker)

One row per **billed synthesis LLM call** over a student — success or failure.
It mirrors `extraction_runs` and serves three jobs: the student's synthesis
**freshness marker** (`MAX(created_at) WHERE outcome = 'applied'` — the last
time a pass applied); the **provenance** of the call (prompt pin, model); and
the per-student **token ledger**, so every token spent on a student is recorded
even when the pass fails and retries.

```sql
CREATE TABLE synthesis_runs (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

  -- 'applied' wrote commitments and advances the freshness marker; 'failed'
  -- billed tokens but produced unusable output (marker unchanged, counts zero).
  outcome TEXT NOT NULL,

  system_prompt_id UUID NOT NULL REFERENCES system_prompts(id) ON DELETE RESTRICT,
  provider         TEXT NOT NULL,
  model_resolved   TEXT NULL,

  commitments_written INTEGER NOT NULL DEFAULT 0,
  commitments_dropped INTEGER NOT NULL DEFAULT 0,

  input_tokens       INTEGER NULL,
  output_tokens      INTEGER NULL,
  cache_read_tokens  INTEGER NULL,
  cache_write_tokens INTEGER NULL,

  CONSTRAINT synthesis_runs_outcome_check  CHECK (outcome IN ('applied','failed')),
  CONSTRAINT synthesis_runs_provider_check CHECK (provider IN ('anthropic','log')),
  CONSTRAINT synthesis_runs_model_resolved_length_check
    CHECK (model_resolved IS NULL OR length(model_resolved) <= 255),
  CONSTRAINT synthesis_runs_failed_counts_check CHECK (
    outcome <> 'failed' OR (commitments_written = 0 AND commitments_dropped = 0)
  ),
  CONSTRAINT synthesis_runs_counts_nonneg_check CHECK (
    commitments_written >= 0 AND commitments_dropped >= 0
  ),
  CONSTRAINT synthesis_runs_tokens_nonneg_check CHECK (
    (input_tokens       IS NULL OR input_tokens       >= 0) AND
    (output_tokens      IS NULL OR output_tokens      >= 0) AND
    (cache_read_tokens  IS NULL OR cache_read_tokens  >= 0) AND
    (cache_write_tokens IS NULL OR cache_write_tokens >= 0)
  )
);

-- Freshness marker: latest applied run per student.
CREATE INDEX synthesis_runs_student_applied_idx
  ON synthesis_runs (student_id, created_at) WHERE outcome = 'applied';
-- Per-student token-accounting scan.
CREATE INDEX synthesis_runs_student_idx ON synthesis_runs (student_id, created_at);

CREATE TRIGGER trigger_00_prevent_synthesis_runs_update
BEFORE UPDATE ON synthesis_runs FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_synthesis_runs_delete
BEFORE DELETE ON synthesis_runs FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
```

Per-student LLM spend across the product is a UNION/SUM over `convo_responses`
(chat), `extraction_runs`, and `synthesis_runs` until the future cross-feature
`token_ledger` unifies them. The `'log'` provider value admits
`LogOnlyChatProvider` for deterministic tests.

#### Seed (migration `0026.seed-synthesis-system-prompt.sql`)

Seeds the `synthesis` / `v1` `system_prompts` row (mirrors
`0020.seed-extraction-system-prompt.sql`). The prompt instructs a **strict
JSON** document reasoning only over the supplied claims, college list, and
calendar context — never outside knowledge, never inventing facts — of the
shape:

```
{"commitments":[{
  "lens":"gap"|"timing"|"contradiction",
  "disclosure":"explicit"|"internal",
  "statement":<string>,
  "triggerAt":<ISO-8601 date or omitted>,
  "supports":[<claim id string>]
}]}
```

Each `supports` entry is a claim id drawn from the supplied active-claim set.
The prompt is told the current date, to emit at most
`synthesis.maxNewCommitmentsPerRun` commitments, to not restate any supplied
open commitment, and to use `explicit` only for an intention it genuinely means
to raise with the student.

### Domain models and DAOs

New value/row types in `db/src/main/kotlin/ed/unicoach/db/models/`, following
the `Claim`/`Observation` style (inline value classes for ids, `New*` inputs
omitting DB-generated columns, closed enums as Kotlin `enum class` with
`value: String` + `fromValue`):

- `CommitmentId` (`UUID`), `Commitment`, `NewCommitment`.
- `CommitmentLens`, `CommitmentDisclosure`, `CommitmentStatus`,
  `CommitmentTriggerKind` enums (lowercase string values matching the CHECKs).
- `CommitmentSupport`, `NewCommitmentSupport`.
- `SynthesisRunId` (`BIGINT`), `SynthesisRun`, `NewSynthesisRun`, and a
  `SynthesisOutcome` enum (`applied` | `failed`).

New DAOs in `db/src/main/kotlin/ed/unicoach/db/dao/`, stateless `object`s using
the raw-JDBC `SqlSession` helpers, mixing the `Dao.kt` capability interfaces:

- `CommitmentsDao` — `create(session, NewCommitment)`;
  `findById(session, CommitmentId)`; `listOpenByStudent(session, studentId)`
  (status `open`, ordered `created_at, id` — synthesis dedup + stale-drop);
  `listOpenExplicitByStudent(session, studentId)` (the opener read, served by
  `commitments_student_open_explicit_idx`);
  `markFulfilled(session, id, convoId)` (sets `status='fulfilled'`,
  `fulfilled_at=NOW()`, `disclosed_in_convo_id=convoId`, guarded by the
  immutable-column trigger); `drop(session, id, reason)` (sets
  `status='dropped'`, `dropped_at=NOW()`, `drop_reason=reason`);
  `listByStudent(session, studentId)` and `list(session, limit, offset)` for the
  admin surface.
- `CommitmentSupportDao` — `link(session, commitmentId, claimId)` (idempotent
  via `ON CONFLICT (commitment_id, claim_id) DO NOTHING ... RETURNING`, reading
  the existing row back on conflict — identical to `ClaimSupportDao.link`);
  `listClaimsForCommitment(session, commitmentId)`;
  `listCommitmentsForClaim(session, claimId)` (reverse lookup for stale-drop and
  admin).
- `SynthesisRunsDao` — `append(session, NewSynthesisRun)`;
  `lastAppliedAt(session, studentId): Result<Instant?>`
  (`MAX(created_at) WHERE student_id = ? AND outcome = 'applied'`, served by the
  partial index).

No new advisory-lock DAO: synthesis reuses `AdvisoryLockDao.lockStudent` (RFC
66). `ClaimsDao.listActiveByStudent` and
`CollegeListEntriesDao.listActiveByStudent` (RFC 91) are reused unchanged.

### Synthesis behavior

`SynthesisService`
(`service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisService.kt`)
owns the pass; `SynthesisHandler` is the `JobHandler` adapter. Both are new. The
three-phase structure mirrors `ExtractionService` — a read transaction, the LLM
call outside any transaction, then a write transaction — because the `chat()`
call is `suspend` and takes seconds; holding a pooled connection and its
`pg_advisory_xact_lock` across it is precluded. `SynthesisService` is a plain
`class`, not `ExtractionService`'s `open class`: the divergence is intentional,
as no subclassing is needed and tests inject a fake `ChatProvider` rather than
override a method.

```kotlin
class SynthesisService(
  private val database: Database,
  private val chatProvider: ChatProvider,
  private val config: SynthesisConfig,
  private val clock: Clock = Clock.systemUTC(),
) {
  suspend fun synthesize(studentId: StudentId): SynthesisResult
}

sealed interface SynthesisResult {
  data object Success : SynthesisResult                                  // applied or a no-op (soft-deleted / not fresh / cap reached / lost race)
  data class TransientFailure(val message: String, val cause: Throwable? = null) : SynthesisResult
}
```

The timing lens reasons over the current date, which the pass reads as
`Instant.now(clock)` when assembling the prompt. `clock` is a constructor
`Clock` defaulting to `Clock.systemUTC()` — the injectable testing seam
`JwtGenerator` already uses (`common/.../JwtGenerator.kt`) — so a test can pin
"today" to a fixed instant and exercise the timing lens deterministically. It is
the only date input the pass needs; the freshness gate and every lifecycle
timestamp are DB-clocked through `NOW()` defaults and `lastAppliedAt`.

_Read transaction_ (`database.withConnection`):

1. Load the student (`StudentsDao.findById`, `SoftDeleteScope.ALL`). A
   soft-deleted student → `Success` no-op.
2. `AdvisoryLockDao.lockStudent(session, studentId)`.
3. `lastAppliedAt = SynthesisRunsDao.lastAppliedAt(studentId)`.
4. `activeClaims = ClaimsDao.listActiveByStudent(studentId)`. Empty → `Success`
   no-op (nothing distilled to reflect on).
5. `listEntries = CollegeListEntriesDao.listActiveByStudent(studentId)`.
6. **Freshness gate.** Let `freshness = MAX(updated_at)` across `activeClaims`
   and `listEntries`. If `lastAppliedAt != null` and
   `freshness <= lastAppliedAt`, the model is unchanged since the last applied
   pass → `Success` no-op (this is what makes a naive future cron cheap). This
   gate is a **read-phase-only** guard on `MAX(updated_at)`; the write-phase
   re-check (step 11) keys solely on `lastAppliedAt` and guards only the
   lost-race case. A model mutation landing between the read and write phases is
   not re-gated here — it is accepted as next-pass work (the following pass sees
   the newer `freshness` and runs).
7. `openCommitments = CommitmentsDao.listOpenByStudent(studentId)`. If
   `openCommitments.size >= config.maxOpenCommitments` → `Success` no-op (the
   open set is saturated; do not pile up).
8. `prompt = SystemPromptsDao.findByNameAndVersion(config.promptName,
   config.promptVersion)`.

   The transaction commits and releases the lock, carrying forward
   `activeClaims`/`listEntries`/`openCommitments` and a snapshot of
   `lastAppliedAt` for the write-time re-check.

_LLM call_ (no transaction):

9. Assemble the **calendar + model context** and call `chatProvider.chat` (the
   accumulate extension returning a `ChatEvent.Terminal`). Context =
   `Instant.now(clock)` as "today"; the student's
   `expected_high_school_graduation_*`; `activeClaims` (capped at
   `config.maxClaims`, each with `statement`/`kind`/`topic`/`confidence` and the
   `uttered_at` of its supporting observations for recency); `listEntries`
   (college, `status`, timestamps); and `openCommitments` (statements, so the
   model does not duplicate them). No transcript, no external knowledge.
10. Branch on the terminal, exactly as extraction does:
    - `ChatEvent.Rejected` / `TransientFailure` — no billed, usable call: write
      no row, return `TransientFailure` (nothing to account).
    - `ChatEvent.Completed` — capture `response.usage` and
      `response.modelResolved` (billed regardless of content), then
      parse/validate the JSON.

_Write transaction_ (`database.withConnection`):

11. `AdvisoryLockDao.lockStudent`; re-read `lastAppliedAt`. If an applied run
    appeared since the read-phase snapshot (a concurrent same-student pass won),
    write nothing and return `Success` (the lost-race no-op) — the time-based
    analogue of extraction's watermark re-check.
12. Re-load `activeClaims` under the held lock and build the active id set. Each
    proposed commitment's `supports` claim ids are filtered to this fresh set;
    ids no longer active are dropped from the link list (a claim going inactive
    mid-window is not fatal — synthesis output is additive, not a mutation of
    claim lifecycle, so unlike extraction's supersede target it does not fail
    the pass). A commitment whose supports filter to empty is still created
    (support is optional).
13. If the `Completed` response was **unparseable/invalid**, append one
    `synthesis_runs` row with `outcome='failed'`, the prompt/provider/model and
    captured token usage, zero counts; commit; return `TransientFailure`
    (retries to `maxAttempts`, then dead-letter).
14. On a **valid** response: take the first `config.maxNewCommitmentsPerRun`
    proposed commitments; insert each (`CommitmentsDao.create`) and link its
    valid support (`CommitmentSupportDao.link`). Then **stale-drop**: for each
    open commitment that has ≥1 support claim and none of those claims are in
    the fresh active set, `CommitmentsDao.drop(id, "stale_basis")` — this prunes
    intentions whose belief was superseded or retracted, and is the writer that
    makes the `dropped` terminal reachable. Append the `synthesis_runs` row with
    `outcome='applied'`, the counts, provenance, and token usage.

A `Completed` call therefore writes exactly one `synthesis_runs` row — `applied`
on valid output, `failed` (carrying token usage) on unparseable output — so
retries never spend unrecorded tokens; a `Rejected`/`TransientFailure` terminal
writes no row. `SynthesisHandler` mirrors `ExtractionHandler`:

```kotlin
class SynthesisHandler(private val synthesisService: SynthesisService) : JobHandler {
  override val jobType = JobType.SYNTHESIZE_STUDENT
  override val config = JobTypeConfig(concurrency = 4, maxAttempts = 5, lockDuration = 10.minutes, executionTimeout = 5.minutes)
  override suspend fun execute(payload: JsonObject): JobResult   // malformed → PermanentFailure; Success → Success; TransientFailure → RetriableFailure
}
```

### Pull delivery (next-session opener)

An open explicit commitment reaches the student as the coach's **first reply in
their next conversation**: at `startConvo`, the commitments are composed into
the coach system prompt so the coach raises them naturally, and are marked
fulfilled. This is the first memory→chat injection in the codebase (today
`CoachingService` sends only the bare coach prompt + transcript). Reading
commitments and composing the prompt are a cheap DAO read on the request path —
RFC 43 forbids the LLM reflection _work_ there, which stays in the worker, not a
memory read; chat itself already runs on the request coroutine.

`CoachingService.startConvo`'s preflight transaction gains: when
`config.surfaceCommitments` is true, load
`CommitmentsDao.listOpenExplicitByStudent(session, studentId)`; if non-empty,
compose the outgoing system text as `prompt.body` + a rendered block ("Since you
last spoke, you have been reflecting on the following — raise what is relevant,
naturally: …"). The `Preflight` carries the composed `system: String` and the
`disclosedCommitmentIds: List<CommitmentId>`. `buildReplyFlow` uses
`preflight.system` in the `ChatRequest`. On a **successful** terminal, the
existing tx-2 (`persistTerminal`) additionally calls
`CommitmentsDao.markFulfilled(id, convo.id)` for each disclosed id.

Fulfillment is bound to a successful turn deliberately: on a failed first turn
`persistTerminal` soft-deletes the convo, so leaving the commitments `open` lets
them re-surface next session rather than being marked fulfilled against a
discarded conversation. `postTurn` never surfaces commitments (empty disclosed
list) — only a new conversation opens with reflection, so an insight is not
re-raised mid-conversation. When synthesis is disabled or nothing is pending,
the system text is unchanged from today and behavior is identical.

`fulfilled = surfaced into a session opener` is the operative definition of the
promise-kept metric (`fulfilled / explicit`); a truly unprompted, coach-first
message (rather than a raised-in-first-reply opener) requires `push-delivery`.
There is no new REST or OpenAPI surface — the opener rides the coach's normal
reply.

### Configuration

`SynthesisConfig`
(`service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisConfig.kt`,
`from(Config)`), block `synthesis { … }` in
`service/.../resources/service.conf`, mirroring `ExtractionConfig`:

| Key                                 | Type    | Purpose                                                       |
| :---------------------------------- | :------ | :------------------------------------------------------------ |
| `synthesis.enabled`                 | Boolean | Master switch for handler registration.                       |
| `synthesis.promptName`              | String  | `system_prompts.name` (default `synthesis`).                  |
| `synthesis.promptVersion`           | String  | `system_prompts.version` pin (default `v1`).                  |
| `synthesis.model`                   | String  | Model id for the reflection call.                             |
| `synthesis.maxTokens`               | Int     | Output bound.                                                 |
| `synthesis.maxClaims`               | Int     | Safety cap on active claims assembled into one prompt.        |
| `synthesis.maxOpenCommitments`      | Int     | Open-set cap; a student at the cap no-ops until some resolve. |
| `synthesis.maxNewCommitmentsPerRun` | Int     | Ceiling on commitments created by one pass.                   |

There is no `debounce` key (no per-turn enqueue to coalesce); cadence is the
future cron's concern, and the freshness gate bounds redundant work. The
coaching block gains one key for delivery:

| Key                           | Type    | Purpose                                                     |
| :---------------------------- | :------ | :---------------------------------------------------------- |
| `coaching.surfaceCommitments` | Boolean | Gate for opener injection at `startConvo` (default `true`). |

### Worker wiring

`queue-worker/.../worker/Application.kt` currently builds a `ChatProvider` only
inside `if (extractionConfig.enabled)`. It is restructured to build the provider
once when **either** extraction or synthesis is enabled, then register
`ExtractionHandler` (if extraction enabled) and `SynthesisHandler` (if synthesis
enabled). `service` already depends on `:queue` (RFC 66) and the worker already
has the direct `:chat` dependency and `chat.conf` on its classpath (RFC 66), so
no new module dependency or config file is added.

### Admin views

Two read-only admin resources via the RFC 77 descriptor engine, giving the
promise-kept metric and token spend visibility (declarative additions, not new
infrastructure):

- `CommitmentsResource` — mirrors `ClaimsResource` (a mutable, status-based
  entity with no `deleted_at`; all four write handlers `null`, so no
  create/edit/delete affordance). Fields: id, studentId, lens, disclosure,
  status, statement, trigger_kind, trigger_at, fulfilled_at,
  disclosed_in_convo_id, dropped_at, drop_reason, timestamps. One `HasMany` edge
  to supporting claims via `CommitmentSupportDao.listClaimsForCommitment`.
- `SynthesisRunsResource` — mirrors `ExtractionRunsResource` (append-only log,
  read-only): outcome, counts, provider/model provenance, the four token
  columns.

Both are registered in `admin-web/.../Application.kt`'s single
`AdminRegistry(listOf(…))` — that list is the sole resource enumeration under
the RFC 77 descriptor engine (nav and index are derived from it in
`engine/AdminRouting.kt`, which enumerates nothing itself), so no other file
needs editing to register them.

### Error Handling / Edge Cases

- **At-least-once delivery / duplicate jobs.** Concurrent same-student passes
  serialize on the student advisory lock; the write-time `lastAppliedAt`
  re-check no-ops the lost race, so no duplicate commitment set is written. A
  later job (post-activity) legitimately re-runs because
  `freshness > lastAppliedAt`.
- **Nothing to reflect on.** No active claims, or model unchanged since the last
  applied run, or the open-commitment cap reached → `Success` no-op, no row, no
  LLM call.
- **Soft-deleted student.** The pass loads the student and no-ops (`Success`) if
  `deleted_at` is set; physical deletion is blocked DB-wide
  (`prevent_physical_delete`), so the `ON DELETE CASCADE` clauses never fire
  today.
- **`Completed` returns malformed/invalid JSON.** A `failed` `synthesis_runs`
  row records the token spend; `RetriableFailure` to `maxAttempts`, then
  dead-letter; the freshness marker does not advance, so a later pass
  re-attempts.
- **`Rejected`/`TransientFailure` terminal.** No usage, no row,
  `RetriableFailure`.
- **Support claim inactive at write time.** The id is filtered out of the link
  list (not fatal); the commitment is still created. Distinct from extraction's
  fatal stale-supersede target because synthesis does not mutate claim
  lifecycle.
- **Stale open commitment.** A pass drops any open commitment whose entire
  (non-empty) support set is inactive, so the opener does not surface an
  intention built on a retracted belief. A commitment with no support is never
  auto-dropped.
- **`synthesis.enabled = false`.** The worker does not register the handler; a
  manually enqueued job finds no handler and is left `SCHEDULED` (never claimed)
  — consistent with any disabled job type.
- **Opener with `coaching.surfaceCommitments = false` or no pending
  commitments.** System text is unchanged; the chat path behaves exactly as
  today.

### Dependencies

- Reads `claims`/`claim_support` (RFC 66) and `college_list_entries` (RFC 91);
  both are implemented. Reuses `AdvisoryLockDao` (RFC 66) and the RFC 77 admin
  engine.
- Reuses the existing `ChatProvider` abstraction (`:chat`); the `'log'` provider
  value backs `LogOnlyChatProvider`
  (`chat/src/main/kotlin/ed/unicoach/chat/LogOnlyChatProvider.kt`), the existing
  deterministic collaborator the `synthesis_runs.provider` CHECK admits.
- New `JobType.SYNTHESIZE_STUDENT` and `SynthesisPayload` (`@Serializable`,
  `studentId: String`) in `:queue`, alongside `ExtractionPayload`.
- **Periodic invocation is a deferred dependency**: a future scheduler/cron RFC
  supplies recurrence and the eligibility sweep. Until then, `bin/q-enqueue`
  enqueues a pass. No dependency on `college-knowledge`, the fit-lens, or
  `chat-tool-use`.

## Tests

DB/DAO tests use the project harness (recreated test DB); service/handler tests
use a fake `ChatProvider` returning canned JSON and an injected fixed `Clock`
for determinism. Run via `nix develop -c bin/test <module> -f` and verify
executed counts against declared (block-bodied tests only).

**Migration / schema (`db`).**

- `0025` and `0026` apply cleanly on a fresh DB; tables, indexes, triggers
  exist.
- Immutability guards: `UPDATE`/`DELETE` on `commitment_support` and
  `synthesis_runs` raise `P0001`; `UPDATE` of `commitments.id`/`created_at`
  raises; `DELETE` on `commitments` raises (`prevent_physical_delete`).
- CHECKs: each `commitments` enum column rejects an out-of-set value;
  `commitments_fulfilled_consistency_check` rejects `status='fulfilled'` with a
  null `fulfilled_at` or null `disclosed_in_convo_id`;
  `commitments_dropped_consistency_check` rejects `status='dropped'` with null
  `dropped_at`; blank/2049-char `statement` rejected; `synthesis_runs` rejects
  an `outcome` outside `{applied,failed}` and a `failed` row with nonzero
  counts.

**DAO tests.**

- `CommitmentsDaoTest`: `create` defaults `status='open'`,
  `trigger_kind='next_session'`; `listOpenByStudent` and
  `listOpenExplicitByStudent` exclude fulfilled/dropped and (for the latter)
  internal, ordered `created_at, id`; `markFulfilled` sets status +
  `fulfilled_at`
  - `disclosed_in_convo_id` and bumps `updated_at`; `drop` sets status +
    `dropped_at` + `drop_reason`; mutating an immutable column fails; an FK
    violation on unknown `student_id` surfaces as failure.
- `CommitmentSupportDaoTest`: `link` is idempotent (repeat is a no-op success,
  not a duplicate-key error); `listClaimsForCommitment` /
  `listCommitmentsForClaim` are exact inverses; `UPDATE`/`DELETE` raises.
- `SynthesisRunsDaoTest`: `lastAppliedAt` is null with no runs, **ignores
  `failed` rows**, and returns `MAX(created_at)` over `applied` rows; `append`
  records outcome, counts, prompt/provider provenance, and all four token
  columns; a per-student token sum aggregates across an `applied` + a `failed`
  row.

**`SynthesisServiceTest`** (fake provider, injected fixed `Clock`).

- Happy path: active claims + a fresh model produce a `gap` and a
  `contradiction` commitment with `commitment_support` links to the cited
  claims; `synthesis_runs` records `applied` with matching counts and token
  usage.
- Explicit vs internal: an `internal` commitment is created but is excluded from
  `listOpenExplicitByStudent`; an `explicit` one is included.
- Timing lens: with `clock` pinned to a fixed instant, the assembled prompt
  carries that "today" (asserted via the fake provider's captured request) and
  the pass persists the provider's `trigger_at` commitment deterministically.
- Freshness gate: a second pass with no claim/list change since the last applied
  run writes nothing and returns `Success` (no LLM call, asserted via the fake
  provider's call count); a pass after a new claim runs and applies.
- Open-set cap: a student already at `maxOpenCommitments` no-ops.
- `maxNewCommitmentsPerRun`: provider proposing more than the cap persists only
  the first N.
- Dedup context: open commitments are passed to the provider (asserted via the
  captured request), so the prompt can avoid restating them.
- Stale-drop: an open commitment whose only support claim has been superseded is
  dropped (`status='dropped'`, `drop_reason='stale_basis'`); one with no support
  is left open.
- Support filtering: a proposed support id that is inactive at write time is
  omitted from `commitment_support` while the commitment is still created.
- Lost-race no-op: a pass whose read-phase `lastAppliedAt` snapshot is overtaken
  by an interleaved applied run writes nothing and returns `Success`.
- Unparseable `Completed`: a `failed` run carrying token usage,
  `TransientFailure`, freshness marker unchanged.
- `Rejected`/`TransientFailure` terminal: no run row, `TransientFailure`.
- Soft-deleted student and empty active-claim set: `Success` no-op, no row.
- Token accounting: a student's total synthesis spend sums across an `applied` +
  a `failed` (unparseable `Completed`) pass (no tokens lost on retry).
- Prompt resolution: the pass resolves `(promptName, promptVersion)` and records
  that `system_prompt_id`; an absent row surfaces `TransientFailure` (no run).

**`SynthesisHandlerTest`.**

- Valid payload → delegates and returns `Success`.
- Malformed payload → `PermanentFailure`.
- Transient service error → `RetriableFailure`.
- `config` advertises `SYNTHESIZE_STUDENT`, `executionTimeout < lockDuration`.

**`CoachingServiceTest`** (delivery additions).

- `startConvo` with an open explicit commitment composes it into the system text
  (asserted via the fake provider's captured `ChatRequest.system`) and, on a
  successful terminal, marks it `fulfilled` with
  `disclosed_in_convo_id = convo.id`.
- A failed first turn leaves the commitment `open` (convo soft-deleted).
- Internal and already-fulfilled/dropped commitments are not surfaced.
- `postTurn` never surfaces commitments.
- `coaching.surfaceCommitments = false` leaves the system text unchanged.

**Admin.**

- `CommitmentsResourceTest`: list/detail render persisted fields; no
  create/edit/delete affordance; the supporting-claims edge renders linked
  claims and is empty for an uncited commitment.
- `SynthesisRunsResourceTest`: list/detail render outcome, counts, and token
  columns, mirroring `ExtractionRunsResourceTest`.

## Invariants

None. `commitment_support` and `synthesis_runs` are covered by
`db/schema/INVARIANTS.md`'s existing generalized rule ("A new log or immutable
table MUST attach the same guards"), and `commitments` is the standard
mutable-entity pattern already governing `claims`, enforced by the same DB
triggers, not a new directory-specific discipline. Maintaining that invariant's
prose for the new tables (adding them to the log-guard enumeration and the
`row_created_at` table list) is a hand-edit of the existing rule, not a new one
— the same posture as RFC 91. Per-student token recording on every billed
synthesis call is realized by `synthesis_runs` + the tests above, exactly as
extraction realizes it in `extraction_runs`.

## Implementation Plan

Each step is atomic and locally verifiable inside the Nix dev shell.

1. **Migrations.** Add `db/schema/0025.create-synthesis.sql` (the three tables,
   indexes, triggers exactly as specified) and
   `db/schema/0026.seed-synthesis-system-prompt.sql` (seed `synthesis` / `v1`).
   Hand-edit `db/schema/INVARIANTS.md`, all in the second invariant
   ("Append-only log and immutable-entity tables keep their write guards"):
   - **Rule** clause — add `commitment_support` and `synthesis_runs` to the
     parenthesized **log** enumeration (currently
     `convo_requests,
     convo_responses, convo_responses_raw, email_sends, observations,
     claim_support, extraction_runs, college_list_entry_support`).
   - **Why** clause, `row_created_at` sentence — the exact target is: "the
     `row_created_at` guarantee is carried separately by
     `prevent_physical_timestamp_update()` on the six tables that have that
     column (`users`, `sessions`, `students`, `convos`, `claims`,
     `college_list_entries`)." Change the count word `six`→`seven` AND insert
     `commitments` into the parenthesized table list (it becomes `users`,
     `sessions`, `students`, `convos`, `claims`, `commitments`,
     `college_list_entries`).
   - `## History` — append
     `- [x] [RFC-93: Synthesis](../../rfc/93-synthesis.md)`.
   - Verify: `nix develop -c bin/test db -f`;
     `nix develop -c psql "$POSTGRES_DB" -c '\d+ commitments'` shows columns,
     CHECKs, triggers.

2. **Domain models.** Add the id value classes, row/`New*` types, and the enums
   under `db/.../models/`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`.

3. **DAOs + tests.** Add `CommitmentsDao`, `CommitmentSupportDao`,
   `SynthesisRunsDao` with their test files.
   - Verify: `nix develop -c bin/test db -f`.

4. **Queue payload + type.** Add `JobType.SYNTHESIZE_STUDENT` and
   `SynthesisPayload` to `:queue`.
   - Verify: `nix develop -c ./gradlew :queue:compileKotlin`.

5. **Config.** Add `SynthesisConfig` and the `synthesis { … }` block plus the
   `coaching.surfaceCommitments` key in `service/.../resources/service.conf`;
   add `surfaceCommitments` to `CoachingConfig`.
   - Verify: `nix develop -c ./gradlew :service:compileKotlin`.

6. **`SynthesisService` + `SynthesisHandler`.** Implement the three-phase pass
   (freshness gate, prompt resolve, provider call, parse/validate, persist +
   stale-drop, run append) and the handler. Add `SynthesisServiceTest` and
   `SynthesisHandlerTest`.
   - Verify: `nix develop -c bin/test service -f`.

7. **Pull delivery.** Thread open-explicit-commitment composition into
   `CoachingService.startConvo`'s preflight (`Preflight.system` +
   `disclosedCommitmentIds`) and mark-fulfilled into `persistTerminal`'s success
   branch. Add the delivery cases to `CoachingServiceTest`.
   - Verify: `nix develop -c bin/test service -f`.

8. **Worker wiring.** Restructure `queue-worker/.../worker/Application.kt` to
   build the `ChatProvider` once when extraction **or** synthesis is enabled and
   register `SynthesisHandler` under `synthesis.enabled`.
   - Verify: `nix develop -c ./gradlew :queue-worker:compileKotlin`.

9. **Admin resources + tests.** Add `CommitmentsResource` and
   `SynthesisRunsResource`, register both in `admin-web/.../Application.kt`, add
   their tests.
   - Verify: `nix develop -c bin/test admin-web -f`.

10. **Full suite + format gate.**
    - Verify:
      `nix develop -c bin/format -c && nix develop -c bin/test check -f`.

11. **Reconcile the feature brief.** Update `features/coaching-memory.md`:
    advance the `synthesis` index row (defines `commitments`; internal lenses;
    pull delivery) and note that its periodic trigger depends on a future
    scheduler/cron RFC. Keep `push-delivery` as the owner of the
    channel-at-fulfillment generalization and dormant-student reach.
    - Verify: `nix develop -c deno fmt --check features/coaching-memory.md`.

## Files Modified

**Created**

- `db/schema/0025.create-synthesis.sql`
- `db/schema/0026.seed-synthesis-system-prompt.sql`
- `db/src/main/kotlin/ed/unicoach/db/models/CommitmentId.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/Commitment.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewCommitment.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/CommitmentLens.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/CommitmentDisclosure.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/CommitmentStatus.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/CommitmentTriggerKind.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/CommitmentSupport.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewCommitmentSupport.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/SynthesisRunId.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/SynthesisRun.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewSynthesisRun.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/SynthesisOutcome.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/CommitmentsDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/CommitmentSupportDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/SynthesisRunsDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/CommitmentsDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/CommitmentSupportDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/SynthesisRunsDaoTest.kt`
- `queue/src/main/kotlin/ed/unicoach/queue/SynthesisPayload.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisService.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisHandler.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisConfig.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisResult.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/synthesis/SynthesisServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/synthesis/SynthesisHandlerTest.kt`
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/CommitmentsResource.kt`
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/SynthesisRunsResource.kt`
- `admin-web/src/test/kotlin/ed/unicoach/admin/resources/CommitmentsResourceTest.kt`
- `admin-web/src/test/kotlin/ed/unicoach/admin/resources/SynthesisRunsResourceTest.kt`

**Modified**

- `db/schema/INVARIANTS.md` — add `commitment_support`/`synthesis_runs` to the
  log enumeration; add `commitments` to the `row_created_at` table list with
  `six`→`seven`; history entry.
- `queue/src/main/kotlin/ed/unicoach/queue/JobType.kt` — add
  `SYNTHESIZE_STUDENT`.
- `service/src/main/resources/service.conf` — add the `synthesis { … }` block
  and the `coaching.surfaceCommitments` key.
- `service/src/main/kotlin/ed/unicoach/coaching/CoachingConfig.kt` — add
  `surfaceCommitments`.
- `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt` — opener
  composition in `startConvo` preflight; mark-fulfilled in `persistTerminal`'s
  success branch; `Preflight` carries `system` + `disclosedCommitmentIds`.
- `service/src/test/kotlin/ed/unicoach/coaching/CoachingServiceTest.kt` —
  delivery cases.
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` — build the
  `ChatProvider` once when extraction or synthesis is enabled; register
  `SynthesisHandler` under `synthesis.enabled`.
- `admin-web/src/main/kotlin/ed/unicoach/admin/Application.kt` — register
  `CommitmentsResource` and `SynthesisRunsResource`.
- `features/coaching-memory.md` — reconcile the `synthesis` index row and note
  the deferred cron trigger.
