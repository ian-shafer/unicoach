# RFC 66: Extraction

## Executive Summary

Extraction is a per-conversation background pass that distills finished coaching
chat into durable structure: it reads a conversation's transcript and writes
**observations** (immutable records of what the student said) and **claims**
(the coach's revisable beliefs), linked many-to-many through **claim_support**.
This is the substrate the rest of the Coaching Memory feature builds on; today
the coach forgets the student between conversations because nothing folds a
transcript into reusable belief.

The pass runs in the existing queue worker, never on the chat request path. A
coaching turn already runs on the request coroutine with an SSE relay
(`CoachingService.postTurn`/`startConvo`); after a turn's reply is persisted,
the route enqueues an `EXTRACT_CONVERSATION` job (one indexed INSERT,
fire-and-forget), and the `queue-worker` daemon performs the LLM distillation
and all memory writes out-of-band. So the multi-second LLM call never pins a
pooled connection, the worker pass splits into a read transaction, the LLM call
outside any transaction, and a write transaction. Conversations have no explicit
"finished" signal (no `ended_at` on `convos`), so extraction is **incremental
and debounced**: each turn schedules a delayed pass over the conversation's
not-yet-extracted user messages, tracked by a per-conversation watermark.

The new persistence substrate is four tables — `observations` and
`extraction_runs` (append-only logs), `claims` (mutable entity), and
`claim_support` (append-only link log) — plus their DAOs, an `ExtractionService`
and `ExtractionHandler` in the `service` module, and the wiring to build a
`ChatProvider` inside the worker. The `commitments` table is deferred to the
`synthesis` RFC (its first consumer); design decisions are stated inline below.

## Detailed Design

### Placement: off the chat request path

Extraction work runs only in the `queue-worker` daemon. The trigger follows the
existing `rest-server`→`queue-worker` precedent (`SessionExpiryHandler` in
`net`, enqueued by `SessionExpiryPlugin`): a domain handler that implements
`ed.unicoach.queue.JobHandler`, enqueued by a cheap INSERT near the request and
executed asynchronously by the worker.

**Trigger.** After a coaching turn reaches a successful terminal outcome,
`ConvoRouteHandler` enqueues `JobType.EXTRACT_CONVERSATION` with payload
`{convoId, throughRequestId}`, where `throughRequestId` is the
`convo_requests.id` (BIGINT) of the user turn just persisted. The enqueue is a
single INSERT — same fire-and-forget intent as the session-expiry enqueue, but
issued inline at the turn's terminal event rather than via
`SessionExpiryPlugin`'s `ResponseSent` hook — and is skipped on failed turns
(`ReplyEvent.Failed`). The extraction _work_ (LLM call, parsing, memory writes)
happens entirely in the worker.

**Debounce.** The job is enqueued with `delay = extraction.debounce` (config,
default 5 minutes). Rapid successive turns each enqueue a job; the watermark
(below) makes all but the most-advanced pass cheap no-ops, so debouncing
coalesces work without a queue-native dedup key.

**Per-student serialization.** `claims` and `claim_support` are student-scoped
(they carry `student_id`, never `convo_id`; the hot read is
`claims_student_active_idx` on `student_id`), so the unit of mutation is the
_student_, not the conversation: two conversations of the same student race on
supersede/reinforce/confidence against the same claim rows. Each of the pass's
two short transactions (read, then write — see Extraction behavior) therefore
takes a transaction-scoped advisory lock keyed on the **student id**
(`pg_advisory_xact_lock(hashtextextended(student_id::text, 0))`), issued through
`AdvisoryLockDao.lockStudent(session, studentId)` (a new `:db` DAO — see below)
before the transaction's reads/writes; advisory locks are a net-new pattern in
this codebase (no prior use). The lock is held only across each short
transaction, **never** across the LLM call between them; the write transaction
re-acquires it. Distinct students still extract in parallel up to
`JobTypeConfig.concurrency`; all passes for the same student — across all of
that student's conversations — serialize on the student lock. The
per-conversation **watermark** (below) is the windowing/idempotency mechanism
for a given conversation's stream; it is orthogonal to the lock, which guards
shared claim state. A pass that lost the race re-checks its conversation's
watermark under the write lock (below) and no-ops if a concurrent pass over the
same conversation already advanced past its target.

The advisory-lock SQL lives in `:db`, not `:service`: the raw
`SqlSession.execute` helper is `internal` to `:db`, so a public
`AdvisoryLockDao.lockStudent(session,
studentId)` issues it and is the only call
`ExtractionService` makes — matching the existing
`database.withConnection { session -> SomeDao.method(session, …) }` pattern used
everywhere in `:service`.

**Watermark.** "Not yet extracted" is defined by the highest
`through_request_id` recorded in `extraction_runs` for the conversation with
`outcome = 'applied'` (0 if none). The pass processes user turns with
`convo_requests.id ∈ (watermark, throughRequestId]`. If
`throughRequestId <= watermark`, the pass is a no-op success (a coalesced
duplicate). The watermark is read once in the read transaction to assemble the
window and **re-read under the write-time student lock** before persisting; that
write-time re-check is what makes the at-least-once job delivery idempotent for
a given conversation's stream across the lock-free LLM window — without unique
constraints on derived rows. The student advisory lock, held across each short
transaction, is what serializes the shared claim writes (supersede/reinforce/
confidence) of concurrent same-student passes.

Extraction runs entirely in the worker; the chat turn takes no inline memory
actions. The brief's hybrid lean (high-confidence actions inline) is not
buildable today — the `ChatProvider` port (`chat/ChatProvider.kt`) streams a
closed `ChatEvent` taxonomy with **no tool-use dispatch** in `CoachingService`
(a `stop_reason` of `tool_use` is recorded but nothing acts on it), and inline
writes would land on the request coroutine against the request-path constraint —
so it is deferred to a later RFC once tool-use exists in the chat path; this
schema precludes nothing, since a future inline writer targets the same
`observations`/`claims` tables.

### Schema (migration `0019.create-coaching-memory.sql`)

Four tables. `observations`, `claim_support`, and `extraction_runs` are
append-only logs (`postgres-log-table-design`); `claims` is a mutable entity
(`postgres-entity-table-design`). All reuse the shared guard functions already
defined by prior migrations (`prevent_log_update`, `prevent_log_delete`,
`prevent_immutable_updates`, `prevent_physical_delete`, `update_timestamp`).
Closed enums are `TEXT` + named `CHECK` (project convention, not native pg
enums). PostgreSQL 18; `uuidv7()` is built-in.

#### `observations` — append-only log

Immutable records of what a student said. The quote span is verbatim; an
observation is true forever, so belief revision happens only at the `claims`
layer, never here.

##### Log Configuration

| Setting         | Selection                        | Implementation Requirement                                                            |
| :-------------- | :------------------------------- | :------------------------------------------------------------------------------------ |
| **Identity**    | `BIGINT` identity                | Referenced individually by `claim_support`; internal-only, monotonic, never exposed.  |
| **Stream Key**  | `student_id`                     | NOT NULL FK to `students`; the owning evidence stream, indexed for replay.            |
| **Ordering**    | `created_at` (+ `id` tiebreaker) | `ORDER BY created_at, id`; no explicit `seq`.                                         |
| **Time Model**  | Ingest + Event                   | `created_at` = when extraction recorded it; `uttered_at` = when the student said it.  |
| **Raw Payload** | None                             | The source utterance is reconstructable from `convo_requests.content`; not re-stored. |
| **Retention**   | Indefinite                       | Provenance is load-bearing; not partitioned now (low volume). Revisit if it grows.    |
| **Corrections** | Forbidden                        | Observations are immutable; a wrong belief is handled by retracting the claim.        |

```sql
CREATE TABLE observations (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),         -- ingest time

  student_id        UUID   NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  convo_id          UUID   NOT NULL REFERENCES convos(id) ON DELETE CASCADE,
  source_request_id BIGINT NOT NULL REFERENCES convo_requests(id) ON DELETE CASCADE,
  uttered_at        TIMESTAMPTZ NOT NULL,                -- event time (= the source turn's created_at)

  quote TEXT NOT NULL,                                   -- verbatim span the student said

  CONSTRAINT observations_quote_length_check    CHECK (length(quote) <= 4096),
  CONSTRAINT observations_quote_not_empty_check CHECK (length(trim(quote)) > 0)
);

CREATE INDEX observations_student_id_created_at_idx ON observations (student_id, created_at);
CREATE INDEX observations_convo_source_idx          ON observations (convo_id, source_request_id);

CREATE TRIGGER trigger_00_prevent_observations_update
BEFORE UPDATE ON observations FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_observations_delete
BEFORE DELETE ON observations FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
```

`quote` is not trimmed-checked: it is a verbatim span and leading/trailing
whitespace may be meaningful. `uttered_at` is copied from the source
`convo_requests.created_at` so a reader has the utterance time without a join.

#### `claims` — mutable entity

The coach's current belief about a student, distilled from observations and/or
reasoned across other claims. Revisable: it gains confidence, is superseded by a
newer belief, or is retracted, while its supporting observations stay immutable.

##### Entity Configuration

| Setting        | Selection            | Implementation Requirement                                                         |
| :------------- | :------------------- | :--------------------------------------------------------------------------------- |
| **ID Type**    | `UUIDv7`             | `uuidv7()` default; referenced by `claim_support` and the self-supersession FK.    |
| **Mutability** | Mutable              | 4-timestamp pattern (`created_at`/`row_created_at`/`updated_at`/`row_updated_at`). |
| **Timestamps** | Advanced             | Matches `convos`/`students`; `update_timestamp` trigger maintains them.            |
| **Versioning** | Disabled (see below) | No `claims_versions`; lifecycle captured by `status` + supersession pointer.       |
| **Deletions**  | None (status-based)  | No `deleted_at`; withdrawal is `status = 'retracted'`, preserved for eval.         |

```sql
CREATE TABLE claims (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

  origin     TEXT NOT NULL,                       -- student_stated | coach_inferred
  status     TEXT NOT NULL DEFAULT 'active',      -- active | superseded | retracted
  kind       TEXT NOT NULL,                       -- goal | preference | constraint | fact | concern
  subject    TEXT NOT NULL,                       -- student | family | college | application
  topic      TEXT NOT NULL,                       -- academics | activities | finances | location | career | timeline | wellbeing
  visibility TEXT NOT NULL DEFAULT 'student_visible', -- student_visible | internal

  statement  TEXT NOT NULL,                       -- the belief, free text
  confidence INTEGER NOT NULL DEFAULT 0,          -- 0..1000 fixed-point; code-recomputed (see below)

  superseded_by_id UUID        NULL REFERENCES claims(id) ON DELETE RESTRICT, -- claims are never physically deleted (prevent_physical_delete), so RESTRICT never fires
  superseded_at    TIMESTAMPTZ NULL,
  retracted_at     TIMESTAMPTZ NULL,

  CONSTRAINT claims_origin_check     CHECK (origin IN ('student_stated','coach_inferred')),
  CONSTRAINT claims_status_check     CHECK (status IN ('active','superseded','retracted')),
  CONSTRAINT claims_kind_check       CHECK (kind IN ('goal','preference','constraint','fact','concern')),
  CONSTRAINT claims_subject_check    CHECK (subject IN ('student','family','college','application')),
  CONSTRAINT claims_topic_check      CHECK (topic IN ('academics','activities','finances','location','career','timeline','wellbeing')),
  CONSTRAINT claims_visibility_check CHECK (visibility IN ('student_visible','internal')),
  CONSTRAINT claims_statement_length_check    CHECK (length(statement) <= 2048),
  CONSTRAINT claims_statement_not_empty_check CHECK (length(trim(statement)) > 0),
  CONSTRAINT claims_confidence_range_check    CHECK (confidence BETWEEN 0 AND 1000),
  CONSTRAINT claims_not_self_superseded_check CHECK (superseded_by_id IS NULL OR superseded_by_id <> id),
  -- Lifecycle consistency: superseded iff it points at its successor; retracted iff timestamped.
  CONSTRAINT claims_superseded_consistency_check CHECK (
    (status = 'superseded') = (superseded_by_id IS NOT NULL AND superseded_at IS NOT NULL)
  ),
  CONSTRAINT claims_retracted_consistency_check CHECK (
    (status = 'retracted') = (retracted_at IS NOT NULL)
  )
);

-- Active-belief injection ("what does the coach currently believe about X")
-- is the hot read; index it partially.
CREATE INDEX claims_student_active_idx ON claims (student_id) WHERE status = 'active';
CREATE INDEX claims_student_status_idx ON claims (student_id, status);

CREATE TRIGGER trigger_00_prevent_claims_physical_delete
BEFORE DELETE ON claims FOR EACH ROW EXECUTE PROCEDURE prevent_physical_delete();
CREATE TRIGGER trigger_00a_prevent_claims_immutable_updates
BEFORE UPDATE ON claims FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_updates();
CREATE TRIGGER trigger_03_enforce_claims_updated_at
BEFORE UPDATE ON claims FOR EACH ROW EXECUTE PROCEDURE update_timestamp();
```

Supersession models "the student changed their mind": the old claim is set
`status = 'superseded'`, `superseded_by_id` points at a new active claim, and
the immutable observations behind both survive — which is what lets the coach
_notice_ the change. Retraction (`status = 'retracted'`) withdraws a belief
without a replacement; the row is kept because the correction log is the
feature's best eval signal (brief principle 6). The enum _memberships_ for
`kind`/`subject`/`topic` are a closed set chosen here and tunable by a later
migration; the architectural decision is TEXT + CHECK, not a native enum.

**Claims versioning — disabled.** Recurrence drives frequent `confidence`
updates, so a `claims_versions` mirror would record every bump at high write
churn and trigger-maintenance cost. The belief-history that matters — reversal —
is already captured by `status` + `superseded_by_id` + immutable observations,
and `extraction_runs` timestamps pass-level provenance; full versioning is
therefore disabled (as written above).

**`confidence` — code-computed with recency decay.** The `INTEGER` in `[0,1000]`
(fixed-point) is recomputed by code — **never** the LLM — at each pass from the
claim's `claim_support` set: `confidence = round(1000 * (1 - exp(-Σ w_i)))`,
where each supporting observation contributes
`w_i = 0.5 ^ (age_days(uttered_at) / extraction.confidenceHalfLifeDays)`.
Recurrence raises confidence with diminishing returns; age halves each
contribution every half-life. Computing in code keeps the value reproducible and
tied to actual recurrence. Decay is applied at pass time, so a claim's score
refreshes only when its student is next active (which re-triggers a pass); a
periodic global decay recompute is deferred to `synthesis`.

**`visibility` — student-visible by default.**
`visibility ∈ {student_visible,
internal}` defaults to `student_visible`. Per
brief principle 4, `internal` means "not surfaced unprompted" (coaching-process
notes such as "tends to underestimate reach schools"), **not** "hidden from the
student" — anything stored survives the student asking to see their full record,
and the extraction prompt never uses `internal` to stash inferences the student
may not see. No parent/counselor columns are added: no such entity exists, and
`visibility` governs in-product surfacing to the _student_, not third-party
access — any such sharing is a later access-control concern, not a storage
change here.

#### `claim_support` — append-only link log

The many-to-many link from claims to the observations backing them. Each row is
an immutable fact: "this observation was cited as support for this claim." A
claim's confidence rises with the count and recency of its support; a claim with
no `claim_support` rows is a pure cross-claim inference (`coach_inferred`).

##### Log Configuration

| Setting         | Selection    | Implementation Requirement                                                             |
| :-------------- | :----------- | :------------------------------------------------------------------------------------- |
| **Identity**    | No id        | A pure link; composite PK `(claim_id, observation_id)`, never referenced individually. |
| **Stream Key**  | `claim_id`   | NOT NULL FK; "what backs this claim" is the primary read.                              |
| **Ordering**    | `created_at` | Audit ordering only; intra-timestamp order is immaterial.                              |
| **Time Model**  | Ingest-only  | `created_at`; the linking time is the extraction time.                                 |
| **Raw Payload** | None         | —                                                                                      |
| **Retention**   | Indefinite   | Tracks provenance; not partitioned.                                                    |
| **Corrections** | Forbidden    | A link is never edited; retracting the claim is the revision path.                     |

```sql
CREATE TABLE claim_support (
  claim_id       UUID   NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
  observation_id BIGINT NOT NULL REFERENCES observations(id) ON DELETE CASCADE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (claim_id, observation_id)
);

-- Reverse lookup: "which claims does this observation support".
CREATE INDEX claim_support_observation_idx ON claim_support (observation_id);

CREATE TRIGGER trigger_00_prevent_claim_support_update
BEFORE UPDATE ON claim_support FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_claim_support_delete
BEFORE DELETE ON claim_support FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
```

#### `extraction_runs` — append-only log (provenance + watermark)

One row per **billed extraction LLM call** over a conversation — success or
failure. It serves three jobs: the conversation's extraction **watermark**
(`MAX(through_request_id) WHERE outcome = 'applied'`); the **provenance** of the
call that produced the pass's claims (model, prompt pin); and the per-pass
**token ledger**, so every token spent on a student is recorded even when the
pass fails and retries. Mirrors how `convo_requests` pins what shaped each turn.

##### Log Configuration

| Setting         | Selection                        | Implementation Requirement                                                    |
| :-------------- | :------------------------------- | :---------------------------------------------------------------------------- |
| **Identity**    | `BIGINT` identity                | Monotonic; not externally referenced, but addressable for telemetry.          |
| **Stream Key**  | `convo_id`                       | NOT NULL FK; the watermark query filters by it.                               |
| **Ordering**    | `created_at` (+ `id` tiebreaker) | `ORDER BY created_at, id`.                                                    |
| **Time Model**  | Ingest-only                      | `created_at` = when the pass ran.                                             |
| **Raw Payload** | None                             | The provider's raw payload is not retained for extraction (cost); pins only.  |
| **Retention**   | Indefinite                       | Watermark + per-user token-spend history; never pruned below the applied max. |
| **Corrections** | Forbidden                        | Append-only history of passes.                                                |

```sql
CREATE TABLE extraction_runs (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  -- Stream + per-user accounting. student_id is denormalized from the convo
  -- (immutable on a convo), so "tokens spent on this student" is a single-table scan.
  convo_id           UUID   NOT NULL REFERENCES convos(id) ON DELETE CASCADE,
  student_id         UUID   NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  through_request_id BIGINT NOT NULL REFERENCES convo_requests(id) ON DELETE CASCADE, -- window target attempted; counts toward the watermark only when outcome = 'applied'

  -- Every billed LLM call writes exactly one row. 'applied' advanced the watermark
  -- and wrote memory; 'failed' billed tokens but produced unusable output
  -- (watermark unchanged, write counts zero).
  outcome TEXT NOT NULL,

  -- Provenance of the distillation call (always present — every row is one call).
  system_prompt_id UUID NOT NULL REFERENCES system_prompts(id) ON DELETE RESTRICT,
  provider         TEXT NOT NULL,
  model_resolved   TEXT NULL,

  observations_written INTEGER NOT NULL DEFAULT 0,
  claims_written       INTEGER NOT NULL DEFAULT 0,
  claims_superseded    INTEGER NOT NULL DEFAULT 0,

  -- Token usage, recorded for every billed call (success OR failure). Mirrors the
  -- four-column shape of convo_responses so chat + extraction spend are summable.
  input_tokens       INTEGER NULL,
  output_tokens      INTEGER NULL,
  cache_read_tokens  INTEGER NULL,
  cache_write_tokens INTEGER NULL,

  CONSTRAINT extraction_runs_outcome_check  CHECK (outcome IN ('applied','failed')),
  CONSTRAINT extraction_runs_provider_check CHECK (provider IN ('anthropic','log')),
  CONSTRAINT extraction_runs_model_resolved_length_check
    CHECK (model_resolved IS NULL OR length(model_resolved) <= 255),
  -- A failed call wrote no memory.
  CONSTRAINT extraction_runs_failed_counts_check CHECK (
    outcome <> 'failed' OR (observations_written = 0 AND claims_written = 0 AND claims_superseded = 0)
  ),
  CONSTRAINT extraction_runs_counts_nonneg_check CHECK (
    observations_written >= 0 AND claims_written >= 0 AND claims_superseded >= 0
  ),
  CONSTRAINT extraction_runs_tokens_nonneg_check CHECK (
    (input_tokens       IS NULL OR input_tokens       >= 0) AND
    (output_tokens      IS NULL OR output_tokens      >= 0) AND
    (cache_read_tokens  IS NULL OR cache_read_tokens  >= 0) AND
    (cache_write_tokens IS NULL OR cache_write_tokens >= 0)
  )
);

-- Watermark: highest applied target per conversation.
CREATE INDEX extraction_runs_convo_watermark_idx
  ON extraction_runs (convo_id, through_request_id) WHERE outcome = 'applied';
-- Per-user token-accounting scan.
CREATE INDEX extraction_runs_student_idx ON extraction_runs (student_id, created_at);

CREATE TRIGGER trigger_00_prevent_extraction_runs_update
BEFORE UPDATE ON extraction_runs FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_extraction_runs_delete
BEFORE DELETE ON extraction_runs FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
```

`extraction_runs` is in scope as the persistence substrate the extraction
behavior writes to: it is the minimal state that makes incremental extraction
idempotent (watermark) and the claims auditable (which model/prompt produced
them). It is additionally the per-pass **token ledger**: every billed extraction
call — including failed, retried passes — records its usage against the student,
so no token is spent on a student without a recorded row. A future cross-feature
`token_usage` ledger will unify this with chat's `convo_responses` as the single
source of truth for per-user spend; until then, per-user spend is a UNION/SUM
over those two tables. The `'log'` provider value admits `LogOnlyChatProvider`
for deterministic tests.

**`commitments` deferred to `synthesis`.** The brief lists `commitments` under
this RFC, but its first writer/reader is the `synthesis` reflection loop, so
defining the table here would yield a writerless schema; it is deferred to
`synthesis` (which designs its resolve lifecycle and `disclosure` semantics
against a real writer). This RFC ships observations/claims/claim_support only.

### Domain models and DAOs

New value/row types in `db/src/main/kotlin/ed/unicoach/db/models/`, following
the existing `Convo`/`ConvoRequest` style (inline value classes for ids, `New*`
input types that omit DB-generated columns):

- `ObservationId` (`BIGINT`), `Observation`, `NewObservation`.
- `ClaimId` (`UUID`), `Claim`, `NewClaim`, and a `ClaimRevision` input type
  carrying the next `status`/`confidence`/`superseded_by_id` for updates.
- Closed enums as Kotlin `enum class`: `ClaimOrigin`, `ClaimStatus`,
  `ClaimKind`, `ClaimSubject`, `ClaimTopic`, `ClaimVisibility`, each with a
  `value: String` and a `fromValue` companion, persisted as the lowercase string
  matching the SQL CHECK (mirrors the provider-string convention, not a native
  pg enum).
- `ClaimSupport` (and `NewClaimSupport`).
- `ExtractionRunId` (`BIGINT`), `ExtractionRun`, `NewExtractionRun`.

New DAOs in `db/src/main/kotlin/ed/unicoach/db/dao/`, as stateless `object`s
using the raw-JDBC `SqlSession` helpers (`queryOne`, `insertReturning`,
`mutateReturning`), mixing the capability interfaces from `Dao.kt`:

- `ObservationsDao` — `append(session, NewObservation): Result<Observation>`;
  `listByConvoRange(session, convoId, afterRequestId, throughRequestId): Result<List<Observation>>`;
  `listByStudent(session, studentId): Result<List<Observation>>`.
- `ClaimsDao` — `create(session, NewClaim)`;
  `listActiveByStudent(session, studentId)`;
  `revise(session, claimId, ClaimRevision)` (sets
  status/confidence/supersession, guarded by the immutable-column trigger);
  `findById`.
- `ClaimSupportDao` —
  `link(session, claimId, observationId): Result<ClaimSupport>` (idempotent via
  `ON CONFLICT (claim_id, observation_id) DO NOTHING ... RETURNING`);
  `listObservationsForClaim(session, claimId)`.
- `ExtractionRunsDao` —
  `append(session, NewExtractionRun): Result<ExtractionRun>`;
  `watermark(session, convoId): Result<Long>`
  (`COALESCE(MAX(through_request_id), 0)`).
- `AdvisoryLockDao` — `lockStudent(session, studentId): Result<Unit>`, issuing
  `SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))` via the
  `internal` `SqlSession.execute` helper. The lock SQL originates inside `:db`
  because that helper is not visible from `:service`; this is the public surface
  `ExtractionService` calls. Net-new pattern (no prior advisory-lock use).

### Extraction behavior

`ExtractionService` (`service/.../coaching/extraction/ExtractionService.kt`)
owns the pass; `ExtractionHandler` is the thin `JobHandler` adapter that the
worker registers. Both are new.

**`ExtractionHandler`** implements `ed.unicoach.queue.JobHandler`:

- `jobType = JobType.EXTRACT_CONVERSATION`.
- `config = JobTypeConfig(concurrency = 4, maxAttempts = 5, lockDuration = 10.minutes, executionTimeout = 5.minutes)`
  (LLM calls are slow; same-student claim-write correctness is guarded by the
  student advisory lock, not by `concurrency = 1`).
- `execute(payload)` deserializes `ExtractionPayload`, then delegates to
  `ExtractionService.extract(convoId, throughRequestId)`. Malformed payload →
  `JobResult.PermanentFailure`. Transient DB/provider error →
  `RetriableFailure`. Success → `JobResult.Success`.

**`ExtractionService.extract(convoId, throughRequestId)`** runs in three phases
— a read transaction, the LLM call **outside any transaction**, then a write
transaction. The LLM call takes seconds; holding a pooled
`database.withConnection` connection (and its `pg_advisory_xact_lock`) open
across it is precluded, and the `chat()` provider call is `suspend` while
`withConnection`'s block is non-suspend. Splitting the work keeps each
transaction short:

_Read transaction_ (`database.withConnection`):

1. Load the conversation and its `student_id`, then acquire the student advisory
   lock (`AdvisoryLockDao.lockStudent(session, studentId)`).
2. Read `watermark = ExtractionRunsDao.watermark(convoId)`. If
   `throughRequestId <= watermark`, write nothing and return success (no-op).
3. Load the window of turns with request id in `(watermark, throughRequestId]`
   (user `convo_requests.content` + the paired `convo_responses.content` for
   context) and the student's current active claims
   (`ClaimsDao.listActiveByStudent`) to assemble the prompt context.
4. Resolve the extraction system prompt from `system_prompts` via
   `SystemPromptsDao.findByNameAndVersion(session, extraction.promptName,
   extraction.promptVersion)`
   (defaults `extraction` / `v1`) — the same explicit name+version pin
   `CoachingService` uses for the coach prompt.

   The transaction then commits and releases the lock.

_LLM call_ (no transaction):

5. Call the injected `ChatProvider` (non-streaming, via the `chat()` accumulate
   extension, which returns a `ChatEvent.Terminal`) with the assembled context.
   The prompt instructs a **strict JSON** document of operations
   (transcript-only — no external knowledge):
   - `observations`: `[{ sourceRequestId, quote }]` — verbatim spans from user
     turns in the window.
   - `claims`:
     `[{ op, statement, kind, subject, topic, origin, visibility,
     supports: [observationRef], targetClaimId? }]`,
     where `op ∈ {new, reinforce,
     supersede}`. `reinforce` adds support to
     `targetClaimId`; `supersede` marks `targetClaimId` superseded and creates a
     replacement; `new` creates a fresh claim. `observationRef` indexes into the
     returned `observations`.
6. Branch on the terminal:
   - `ChatEvent.Rejected`/`TransientFailure` carry no usage (no billed, usable
     call): write no row and return `RetriableFailure` — there is nothing to
     account.
   - `ChatEvent.Completed` carries the call's `TokenUsage` at
     `Completed.response.usage` (`ChatResponse.usage`). Capture it (it is billed
     regardless of what the JSON contains) and parse/validate the response
     against the closed enums and the active-claim id set, deferring all writes
     to the write transaction.

_Write transaction_ (`database.withConnection`):

7. **Re-acquire** the student advisory lock (`AdvisoryLockDao.lockStudent`) and
   **re-read the watermark**. If a concurrent same-conversation pass advanced it
   to `>= throughRequestId`, write nothing and return success (the lost-race
   no-op). This re-check preserves the conversation's idempotency across the
   lock-free LLM window; the re-acquired student lock serializes the claim
   writes below.
8. **Re-load the student's active claims** (`ClaimsDao.listActiveByStudent`)
   under the held write lock. The active set read in the read txn is stale — a
   concurrent same-student pass may have superseded or retracted claims since —
   so `supersede`/`reinforce` ops are validated against this fresh set, not the
   read-txn snapshot. An op whose `targetClaimId` is no longer active fails the
   pass (see the supersede edge case).
9. If the `Completed` response was **unparseable/invalid**, append one
   `extraction_runs` row with `outcome = 'failed'`, the prompt/provider/model
   and captured token usage, zero write counts, and the attempted
   `through_request_id` (which does not advance the watermark); commit, then
   return `RetriableFailure` (LLM nondeterminism may resolve on retry;
   dead-letters after `maxAttempts`).
10. On a **valid** response: insert observations; apply claim ops (insert
    `new`/`supersede` replacements via `ClaimsDao.create`, mark superseded via
    `ClaimsDao.revise`, add `claim_support` links); recompute `confidence` for
    every touched claim from its support set (the recency-decay formula above) —
    the support read and the `confidence` write happen under the same held
    student lock, so a concurrent same-student pass cannot insert
    `claim_support` between them and cause a lost update; and append the
    `extraction_runs` row with `outcome = 'applied'`,
    `through_request_id = throughRequestId` (advancing the watermark),
    prompt/provider/model, write counts, and token usage.

A `Completed` call therefore writes exactly one `extraction_runs` row —
`applied` on valid output, `failed` (carrying the call's token usage) on
unparseable output — so retries never spend unrecorded tokens. A
`Rejected`/`TransientFailure` terminal (no usage, no usable call) writes no row.
Confidence is **never** assigned by the LLM; the LLM proposes structure, code
computes confidence. Observations are written even when they support no claim
(most do not) — the evidence is retained regardless.

### Configuration

`ExtractionConfig` (`service/.../coaching/extraction/ExtractionConfig.kt`,
`from(Config)`), block `extraction { … }` added to
`service/.../resources/service.conf`:

| Key                                 | Type     | Purpose                                                                                             |
| :---------------------------------- | :------- | :-------------------------------------------------------------------------------------------------- |
| `extraction.enabled`                | Boolean  | Master switch for enqueue + handler registration.                                                   |
| `extraction.debounce`               | Duration | Job delay coalescing rapid turns (default `5m`).                                                    |
| `extraction.promptName`             | String   | `system_prompts.name` to resolve (default `extraction`).                                            |
| `extraction.promptVersion`          | String   | `system_prompts.version` pin, resolved with `promptName` via `findByNameAndVersion` (default `v1`). |
| `extraction.model`                  | String   | Model id requested for the distillation call.                                                       |
| `extraction.maxTokens`              | Int      | Output bound for the extraction call.                                                               |
| `extraction.windowMaxTurns`         | Int      | Safety cap on turns assembled into one pass.                                                        |
| `extraction.confidenceHalfLifeDays` | Double   | Decay half-life for the confidence formula.                                                         |

The `queue-worker` `main()` must additionally load `chat.conf` and build a
`ChatProvider` via
`ChatProviderFactory.fromConfig(ChatConfig.from(config).getOrThrow()).getOrThrow()`
— both calls return `Result`, so each is unwrapped — and pass it into
`ExtractionService` (the worker currently builds no `ChatProvider`). `chat.conf`
ships in the `:chat` module's resources, so the new direct `:chat` dependency
brings it onto the worker classpath; only the `AppConfig.load(...)` file list
changes (add `chat.conf`).

### Error Handling / Edge Cases

- **At-least-once delivery / duplicate jobs.** The watermark makes any pass
  whose `throughRequestId <= watermark` a no-op (per-conversation idempotency);
  the student advisory lock serializes claim writes across concurrent
  same-student passes. No unique constraint on derived rows is needed.
- **Failed coaching turns.** No enqueue on `ReplyEvent.Failed`; only successful
  turns advance the conversation worth extracting.
- **Conversation deleted before the pass runs.** The job loads the convo; a
  soft-deleted convo (its `deleted_at` set) is skipped as a no-op success (no
  run appended). Students and convos are soft-deleted only (`deleted_at`);
  physical deletion is blocked DB-wide by `prevent_physical_delete` (P0001), so
  the `ON DELETE CASCADE`/`RESTRICT` FK clauses on these tables never fire
  today. Physical erasure of memory rows is out of scope for this RFC.
- **No extractable content.** The triggering user turn is always in the window
  (its `convo_requests` row is persisted before the reply, even on a failed
  turn), so the window is never empty; a window whose content yields no
  observations still writes an `applied` run (zero counts) and advances the
  watermark.
- **`Completed` returns malformed/invalid JSON.** A `Completed` terminal carries
  usage, so a `failed` `extraction_runs` row is written in the write transaction
  carrying the call's token usage (the spend is recorded), then
  `RetriableFailure` up to `maxAttempts`, then dead-letter; the watermark does
  not advance, so a later turn re-attempts. This is the only failure that writes
  a row.
- **`Rejected`/`TransientFailure` terminal.** These terminals carry no usage (no
  billed, usable call), so no run row is written and the pass returns
  `RetriableFailure` — there is nothing to account.
- **`extraction.enabled = false`.** `ConvoRouteHandler` skips the enqueue and
  the worker does not register the handler; the chat path is unchanged.
- **Supersede targeting a non-active claim.** Validation is against the active
  claim set **re-loaded inside the write transaction under the held student
  lock** (write-txn step 8), not the read-txn snapshot, since a concurrent
  same-student pass may have superseded/retracted the target in between. An op
  whose `targetClaimId` is not in that fresh set → `RetriableFailure` (stale
  prompt context) rather than corrupting lifecycle state.

### Dependencies

- `service` gains a dependency on `:queue` (to implement `JobHandler` and read
  `JobType`/`ExtractionPayload`), mirroring `net → queue`. No cycle: `queue`
  depends only on `:common`, `:db`.
- `queue-worker` gains a **direct** `:chat` dependency (it is currently only
  transitive via `:service`) to build the `ChatProvider` and bring `chat.conf`
  onto the classpath.
- New job type `JobType.EXTRACT_CONVERSATION` (alongside the existing
  `JobType.SESSION_EXTEND_EXPIRY`) and `ExtractionPayload` (`@Serializable`,
  `convoId: String`, `throughRequestId: Long`) in `:queue`, alongside
  `SessionExpiryPayload`.
- Migration `0020.seed-extraction-system-prompt.sql` seeds the `extraction`
  `system_prompts` row (mirrors `0011.seed-coach-system-prompt.sql`).

## Tests

DB/DAO tests use the project harness (`bin/test`, recreated test DB);
service/handler tests use a fake `ChatProvider` returning canned JSON for
determinism. Run via `nix develop -c bin/test --force` and verify executed
counts against declared (block-bodied tests only).

**Migration / schema (`db` module).**

- `MigrationAppliesTest` (or existing migration harness): `0019` and `0020`
  apply cleanly on a fresh DB; tables, indexes, and triggers exist.
- Immutability guards: `UPDATE`/`DELETE` on `observations`, `claim_support`,
  `extraction_runs` raise `P0001`; `UPDATE` of `claims.id`/`created_at` raises;
  `DELETE` on `claims` raises (`prevent_physical_delete`).
- CHECK constraints: each enum column rejects an out-of-set value; `confidence`
  rejects `< 0` and `> 1000`; `claims_superseded_consistency_check` rejects
  `status='superseded'` with null `superseded_by_id`;
  `claims_retracted_consistency_check` rejects `status='retracted'` with null
  `retracted_at`; `claims_not_self_superseded_check` rejects
  `superseded_by_id = id`; `observations_quote_not_empty_check` rejects blank
  quote; `extraction_runs` rejects an `outcome` outside `{applied,failed}` and a
  `failed` row with nonzero write counts.

**DAO tests.**

- `ObservationsDaoTest`: append returns a row with DB id and `uttered_at`;
  `listByConvoRange` returns only rows in `(after, through]` ordered by
  `created_at, id`; FK violation on unknown `source_request_id` surfaces as a
  failure.
- `ClaimsDaoTest`: `create` defaults `status='active'`, `confidence=0`,
  `visibility='student_visible'`; `listActiveByStudent` excludes
  superseded/retracted; `revise` flips status and sets supersession/confidence
  and bumps `updated_at`; reviving an immutable column fails.
- `ClaimSupportDaoTest`: `link` is idempotent (second identical link is a no-op,
  no duplicate-key error); `listObservationsForClaim` returns the linked set;
  reverse index lookup works.
- `ExtractionRunsDaoTest`: `watermark` is 0 with no runs, **ignores `failed`
  rows**, and returns `MAX(through_request_id)` over `applied` rows; `append`
  records `outcome`, counts, prompt/provider provenance, and all four token
  columns; a per-student token sum aggregates across an `applied` + a `failed`
  row.
- `AdvisoryLockDaoTest`: `lockStudent` returns success; a second connection's
  `lockStudent` for the **same** student blocks until the first transaction
  commits, while a different student's lock is acquired immediately (distinct
  keys do not contend).

**`ExtractionServiceTest`** (fake provider).

- Happy path: a window of user turns produces observations + a `new` claim with
  `claim_support` links; `extraction_runs` advances the watermark; counts match.
- Reinforce: a second pass whose output reinforces an existing claim adds
  support and **raises** `confidence` (asserts monotonic increase for added
  recent support).
- Supersede: output that supersedes an active claim leaves the old row
  `status='superseded'` with `superseded_by_id` set and a new active claim, and
  the old observations remain.
- Confidence/decay: two supporting observations with old `uttered_at` yield
  lower confidence than two recent ones; deterministic given fixed inputs.
- Idempotency: re-running with the same `throughRequestId <= watermark` writes
  nothing and returns success.
- Empty/failed window: appends a run, writes no observations/claims.
- Unparseable `Completed`: a `Completed` terminal with malformed JSON writes a
  `failed` run carrying the call's token usage, returns a transient failure, and
  leaves the watermark unchanged.
- `Rejected`/`TransientFailure` terminal: writes **no** run row, returns a
  transient failure, leaves the watermark unchanged (no usage to account).
- Token accounting: a successful pass records input/output/cache tokens on the
  `applied` run; a student's total extraction spend sums correctly across an
  `applied` + a `failed` (unparseable `Completed`) pass (no tokens lost on
  retry).
- Soft-deleted convo: no-op success.
- `visibility` default and `internal` honored from provider output.
- Stale supersede target: a `supersede`/`reinforce` op whose `targetClaimId` was
  active at read time but is no longer in the write-txn re-loaded active set
  (e.g. superseded by an interleaved write) fails the pass with a transient
  failure and mutates no claim — proving validation runs against the fresh,
  lock-held set, not the read-txn snapshot.
- Prompt resolution: the pass resolves the prompt via
  `findByNameAndVersion(promptName, promptVersion)` and records that
  `system_prompt_id` on the run; an absent `(name, version)` row surfaces a
  transient failure (no run, watermark unchanged).

**`ExtractionHandlerTest`.**

- Valid payload → delegates and returns `Success`.
- Malformed payload → `PermanentFailure`.
- Transient service error → `RetriableFailure`.
- `config` advertises `EXTRACT_CONVERSATION`, `executionTimeout < lockDuration`.

**`rest-server` enqueue test (`ConvoRouteHandler`).**

- A successful turn enqueues exactly one `EXTRACT_CONVERSATION` job with
  `{convoId, throughRequestId}` matching the persisted user turn.
- A failed turn enqueues nothing.
- `extraction.enabled = false` enqueues nothing.
- The enqueue does not alter the turn's HTTP/SSE response (status, body,
  frames).

## Implementation Plan

Each step is atomic and locally verifiable. Commands run inside the Nix dev
shell.

1. **Migrations.** Add `db/schema/0019.create-coaching-memory.sql` (the four
   tables, indexes, triggers exactly as specified) and
   `db/schema/0020.seed-extraction-system-prompt.sql` (seed `extraction`
   prompt).
   - Verify: `nix develop -c bin/test --force db` (DB recreated + migrated, no
     error).
   - Verify: `nix develop -c psql -U postgres -d "$POSTGRES_DB" -c '\d+ claims'`
     shows the columns, CHECKs, and triggers.

2. **Domain models.** Add id value classes, row types, `New*`/revision inputs,
   and the six `enum class` types under `db/.../models/`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`.

3. **DAOs.** Add `ObservationsDao`, `ClaimsDao`, `ClaimSupportDao`,
   `ExtractionRunsDao`, and `AdvisoryLockDao` (public `lockStudent`, issuing the
   `pg_advisory_xact_lock` SQL via the `internal` `SqlSession.execute`) under
   `db/.../dao/`. Add DAO tests (step-2/3 tables), including an
   `AdvisoryLockDao` test asserting `lockStudent` returns success and a second
   session blocks until the holding transaction commits.
   - Verify: `nix develop -c bin/test --force db`.

4. **Queue payload + type.** Add `JobType.EXTRACT_CONVERSATION` to `JobType.kt`
   and `ExtractionPayload` to `:queue`.
   - Verify: `nix develop -c ./gradlew :queue:compileKotlin`.

5. **Service dependency + config.** Add `implementation(project(":queue"))` to
   `service/build.gradle.kts`; add `ExtractionConfig` and the `extraction { … }`
   block in `service/.../resources/service.conf`.
   - Verify: `nix develop -c ./gradlew :service:compileKotlin`.

6. **`ExtractionService`.** Implement the pass (window assembly, prompt resolve,
   provider call, parse/validate, persist, confidence recompute, run append).
   Add `ExtractionServiceTest` with a fake provider.
   - Verify: `nix develop -c bin/test --force service`.

7. **`ExtractionHandler`.** Implement the `JobHandler` adapter; add
   `ExtractionHandlerTest`.
   - Verify: `nix develop -c bin/test --force service`.

8. **Worker wiring.** In `queue-worker/build.gradle.kts` add
   `implementation(project(":chat"))`; in
   `queue-worker/.../worker/Application.kt` load `chat.conf`, build the
   `ChatProvider` via `ChatProviderFactory`, construct `ExtractionService` +
   `ExtractionHandler`, and add it to `handlers` (guarded by
   `extraction.enabled`).
   - Verify: `nix develop -c ./gradlew :queue-worker:compileKotlin`.

9. **Enqueue from chat path.** Thread `QueueService` + `ExtractionConfig` into
   `ConvoRouteHandler` (via `rest-server/.../Application.kt`); on a successful
   terminal turn in `handleCreate`/`handlePostMessage`/`handleStreamCreate`/
   `handleStreamMessage`, enqueue `EXTRACT_CONVERSATION` (one private helper).
   Add the `rest-server` enqueue test.
   - Verify: `nix develop -c bin/test --force rest-server`.

10. **Full suite + lint.**
    - Verify: `nix develop -c bin/test check --force`.

11. **Reconcile the feature brief.** Update `features/coaching-memory.md` (repo
    root, present once the branch is current with `main`) to reflect RFC 66's
    outcomes: resolve the now-decided **Open forks** (extraction timing → worker
    pass only; visibility → student-visible default; confidence & decay →
    code-computed with recency decay; substrate folded into `extraction`, not
    standalone), advance the `extraction` index row status (to `implemented`),
    and confirm the commitments deferral, the token-recording decision, and the
    `token-ledger` follow-up note are present.
    - Verify: `nix develop -c deno fmt --check features/coaching-memory.md`; the
      Open forks section no longer lists the four resolved forks.

## Files Modified

**Created**

- `db/schema/0019.create-coaching-memory.sql`
- `db/schema/0020.seed-extraction-system-prompt.sql`
- `db/src/main/kotlin/ed/unicoach/db/models/ObservationId.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/Observation.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewObservation.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/ClaimId.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/Claim.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewClaim.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/ClaimRevision.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/ClaimOrigin.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/ClaimStatus.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/ClaimKind.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/ClaimSubject.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/ClaimTopic.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/ClaimVisibility.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/ClaimSupport.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewClaimSupport.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/ExtractionRunId.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/ExtractionRun.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewExtractionRun.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/ObservationsDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/ClaimsDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/ClaimSupportDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/ExtractionRunsDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/AdvisoryLockDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/ObservationsDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/ClaimsDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/ClaimSupportDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/ExtractionRunsDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/AdvisoryLockDaoTest.kt`
- `queue/src/main/kotlin/ed/unicoach/queue/ExtractionPayload.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionService.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionHandler.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionConfig.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/extraction/ExtractionServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/extraction/ExtractionHandlerTest.kt`

**Modified**

- `queue/src/main/kotlin/ed/unicoach/queue/JobType.kt` — add
  `EXTRACT_CONVERSATION`.
- `service/build.gradle.kts` — add `implementation(project(":queue"))`.
- `service/src/main/resources/service.conf` — add the `extraction { … }` block.
- `queue-worker/build.gradle.kts` — add a direct
  `implementation(project(":chat"))` (currently only transitive via `:service`).
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` — add
  `chat.conf` to the `AppConfig.load(...)` file list (it ships in `:chat`
  resources), build `ChatProvider`, construct and register `ExtractionHandler`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — pass
  `QueueService` + `ExtractionConfig` into `ConvoRouteHandler`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/ConvoRoutes.kt` —
  enqueue `EXTRACT_CONVERSATION` on successful turns.
- `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoRoutingTest.kt` — enqueue
  assertions.
- `features/coaching-memory.md` — reconcile the living brief with RFC 66's
  outcomes (resolve the decided open forks, advance the `extraction` status,
  confirm the commitments deferral / token-recording decision / `token-ledger`
  follow-up). Repo-root file; present once the branch is current with `main`.
