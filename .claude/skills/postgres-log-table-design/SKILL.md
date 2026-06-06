---
name: postgres-log-table-design
description: >-
  Opinionated guidance for PostgreSQL append-only log tables (event logs, audit
  trails, transcripts, telemetry, outboxes), prioritizing write-once integrity,
  faithful capture, and ordered replay. Use when designing tables that record
  immutable facts or events rather than mutable current state. The sibling to
  postgres-entity-table-design.
---

# Postgres Log Table Design

- [Intended Audience](#intended-audience)
- [Philosophy](#philosophy)
- [When to Use](#when-to-use)
- [What is a Log?](#what-is-a-log)
  - [Log vs. Entity](#log-vs-entity)
  - [Entities That Own Logs](#entities-that-own-logs)
- [Identifiers and Ordering](#identifiers-and-ordering)
- [Immutability Enforcement](#immutability-enforcement)
- [Timestamps: Ingest vs. Event Time](#timestamps-ingest-vs-event-time)
- [Capture Philosophy](#capture-philosophy)
- [Raw Payload Isolation](#raw-payload-isolation)
- [Retention and Partitioning](#retention-and-partitioning)
- [References and Foreign Keys](#references-and-foreign-keys)
- [Spec Presentation](#spec-presentation)
  - [Log Configuration Template](#log-configuration-template)
- [Examples](#examples)
  - [Worked Example: Conversation Transcripts](#worked-example-conversation-transcripts)
  - [Functions and Triggers](#functions-and-triggers)

This skill defines an opinionated design philosophy for PostgreSQL **log
tables**: append-only, immutable records of facts and events. It is the sibling
of `postgres-entity-table-design`. Where an entity models _current state_ that
mutates over time, a log models _things that happened_ and never change. The two
patterns share cosmetic DNA (a synthetic key, FKs to other tables, a
`created_at`) but invert most invariants — a log has no `version` OCC, no
`*_versions` mirror, no `updated_at`, and no `deleted_at`.

Like its sibling, this approach prioritizes strong database-level guarantees at
the cost of Postgres vendor lock-in, and suits small-to-medium applications.

## Intended Audience

- _Backend Engineers_ designing audit trails, event logs, transcripts, telemetry
  sinks, or transactional outboxes.
- _AI Agents_ generating DDL for append-only data, needing an opinionated
  baseline instead of reinventing the pattern each time.
- _Data Engineers_ who want write-once integrity and faithful, reprocessable
  capture enforced at the database layer.

## Philosophy

- _Write Once, Read Forever_: A log row is an immutable historical fact. The
  database MUST forbid `UPDATE` and `DELETE` so application bugs cannot rewrite
  history.
- _Capture-Rich at Write Time_: A log is a source of truth. Capture the full
  envelope of an event when it happens; you cannot go back and re-observe it.
- _Store the Irreplaceable, Derive the Rest_: Persist verbatim what cannot be
  regenerated. Never persist what is reconstructable from data you already keep
  — duplicated context is the classic source of quadratic log bloat.
- _Order is a Guarantee_: A log is inherently sequenced. Replay order must be
  deterministic, not incidental.
- _Retention is a Design Input_: Logs grow without bound. Plan archival and
  pruning into the schema from day one, not after the disk fills.

## When to Use

Use this approach when the table records **events or facts** that, once written,
never change:

- Audit trails and change logs
- Conversation / message transcripts
- Telemetry, metrics, and analytics events
- Transactional outboxes and integration event streams
- Any "what happened" record where history must be preserved exactly

Do NOT use this approach when:

- The table models **mutable current state** (a user, an order, a profile) — use
  `postgres-entity-table-design` instead.
- You need to look up and edit "the current value" of something. A log has no
  current value; it has a history you fold over.

A table can be a log even though it has references to entity tables e.g. a
`users` table. This just adds richness to log table. Note that it may be more
prudent to map to a versioned table e.g. `user_versions` as that will be the
point-in-time values for the foreign entity.

## What is a Log?

A log is an ordered, append-only sequence of immutable records belonging to a
**stream** (a partition key — e.g. all messages in one conversation, all audit
events for one account).

### Log vs. Entity

| Concern             | Entity Table                             | Log Table                                         |
| :------------------ | :--------------------------------------- | :------------------------------------------------ |
| Lifecycle           | Created, mutated, deleted                | Appended once, never changed                      |
| `updated_at`        | Yes (mutable)                            | **No** — write-once                               |
| `version` / OCC     | Optional, for concurrent edits           | **No** — nothing mutates, nothing to contend      |
| `*_versions` mirror | Optional, to record history              | **No** — the log _is_ the history                 |
| `deleted_at`        | Soft-delete flag                         | **No** — events don't un-happen (see Corrections) |
| Ordering            | Incidental                               | **First-class** (by `created_at` within a stream) |
| Deletion            | Per-row, rare                            | Bulk, by age/partition (retention)                |
| Referenced by       | Other tables point _to_ it as live state | Consumed by analytics/derivation, rarely FK'd-to  |

The headline: **roughly half the entity table mechanics disappear.** No version
column, no OCC trigger, no sibling versions table, no logical-delete filtering.
What you add instead is strict immutability enforcement, ordering, and a
retention story.

### Entities That Own Logs

The test for log-vs-entity is one question: **does the row hold domain data that
will ever be mutated?** Domain data is the meaningful content (a name, a status,
an end time) — as opposed to table _mechanics_ (the id, `created_at`, ordering
columns), which never change in either pattern. If any domain field will be
updated after the row is written, the row is an **entity** — model it with
`postgres-entity-table-design`. Only if it carries no mutable domain data can it
be a log.

A very common shape is an **entity that owns one or more logs**: the long-lived,
mutable thing (a conversation with an editable name and an end time set later,
an account, a device) is the entity, and the append-only events about it are
logs. In that case:

- The **log holds an FK to the owning entity**, never the reverse — the entity
  does not point at individual log rows.
- Keep mutable or derived fields (names, statuses, summaries, rollups) **on the
  entity or in a separate derived layer**, never on the log. The log stays pure
  append-only facts; anything that needs to change lives where change is
  allowed.

## Identifiers and Ordering

- _Identity is optional — add an ID only if entries are referenced
  individually_: unlike an entity, a log row does not automatically need its own
  identity. Add a primary key **when individual entries must be addressed** —
  referenced by another table (a `response` pointing at its `request`, a sibling
  raw-payload table keyed 1:1 to the row), returned to a client by id, or cited
  by a derived/audit process. **Omit the ID** for pure append streams that are
  only ever read in aggregate or as an ordered scan (most telemetry and metrics)
  — a synthetic key there is dead weight on every insert and index. Do not add
  one reflexively.
  - _Choosing the id type — prefer a monotonic `BIGINT` for most logs_: a
    `BIGINT GENERATED ALWAYS AS IDENTITY` is 8 bytes (half a UUID — material at
    log volume and on every index) and increments in insert order, making it a
    clean ordering tiebreaker. Choose **`UUIDv7`** (16 bytes) instead when ids
    are generated outside one Postgres instance (sharding, client-side
    generation) or exposed externally, where a guessable, count-leaking integer
    is undesirable. Both are insert-ordered. Expect **gaps** from either
    (rolled-back inserts, sequence caching) — an id is not a gapless counter;
    use the explicit `seq` below if you need contiguity.
- _Stream key_: a `NOT NULL` FK to the owning stream (e.g. `convo_id`). Index
  it; replaying a stream is your most common read.
- _Ordering — `created_at` is the ordering mechanism_: replay order within a
  stream is **defined by `created_at`**. When the table has an `id` (above), the
  canonical query is `ORDER BY created_at, id`: `created_at` carries the
  ordering meaning, and the `id` is only a **deterministic tiebreaker** (both
  `BIGINT` identity and `UUIDv7` ids are insert-ordered, so either works). State
  this explicitly in the design — do not leave replay order implicit.
  - _Why a tiebreaker is needed_: `created_at` defaults to `NOW()`, which is
    transaction-start time, so rows written in the same transaction (a
    request/response pair, a batch insert) share an identical `created_at`. The
    `id` breaks those ties stably so ordering is total and reproducible.
  - _No-ID logs_: a table without an `id` has no tiebreaker, so
    equal-`created_at` rows have **unspecified relative order**. That is fine
    when intra-timestamp order does not matter (aggregate/telemetry reads). If
    you need a total order _and_ have no id, that is itself a reason to add
    either an `id` or the explicit `seq` below.
  - _Index the replay path_: `(stream_id, created_at)` (see the worked example),
    so streaming a conversation in order is an index scan.
  - _Contention-free_: no counter to assign, no lock to hold. Sufficient for the
    overwhelming majority of logs.
  - _Advanced — explicit sequence_: add a per-stream `seq` with a
    `UNIQUE (stream_id, seq)` constraint **only** when you need contiguous,
    gap-detectable ordering (e.g. to prove no event was lost). Beware: assigning
    `max(seq)+1` races under concurrency — serialize it (per-stream advisory
    lock or a dedicated per-stream counter), or you will get duplicate-key
    errors or gaps. Do not reach for this unless contiguity is a real
    requirement.

## Immutability Enforcement

This is the heart of the pattern. Enforce write-once **in the database**, not in
application code:

- _Forbid `UPDATE`_: a `BEFORE UPDATE` trigger that raises an exception.
- _Forbid `DELETE`_: a `BEFORE DELETE` trigger that raises an exception
  (retention deletes happen at the partition level — see below — or via an
  explicitly privileged maintenance role).
- _No `version` / OCC_: rows never change, so there is no lost-update problem to
  guard against.
- _No `*_versions` table_: the log is its own complete history.
- _Corrections are appends, never mutations_: if a logged fact was wrong, append
  a **compensating record** (a correction event that references the original).
  Never edit the original — that would destroy the audit guarantee that makes a
  log trustworthy.

## Timestamps: Ingest vs. Event Time

A log row is written once, so it needs only a creation timestamp — there is no
`updated_at`.

- `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`: when the row was recorded
  (ingest time). Use the database `NOW()` as the single source of truth, never
  an application clock. This column is also the log's **ordering mechanism**
  (see [Identifiers and Ordering](#identifiers-and-ordering)) — it carries both
  meanings, so it is always present and never null.
- _Event vs. ingest time_: when the event _occurred_ can differ from when it was
  _recorded_ — client-buffered events, backfills, replays. If that gap matters,
  add an explicit `occurred_at` (the event's own time) distinct from
  `created_at` (the ingest time). This is the log analogue of the entity skill's
  logical-vs-physical timestamp split. If they can never differ, omit
  `occurred_at`.

## Capture Philosophy

A log is consumed by things built _later_ (analytics, audits, derived models),
so capture generously now:

- _Record the full envelope_, not just the human-visible payload. For a request
  to an external service that means the parameters, the resolved identifiers,
  and the response metadata — everything a future reader needs to understand and
  trust the record.
- _Store the irreplaceable verbatim; never store the reconstructable._ If a
  value can be rebuilt from rows you already keep, storing it again is pure
  bloat — and if it grows with stream length (e.g. re-storing the whole prior
  history on every append), the bloat is **quadratic**. Keep each fact once.
- _Pin upstream versions (provenance)._ Anything that _shaped_ the record but
  lives elsewhere and changes over time — a prompt version, a config version, a
  model identifier, a code revision — record the exact version in use at write
  time. A log is only trustworthy for later analysis if you know what produced
  each row.
- _jsonb for evolving payloads._ Use `JSONB` for semi-structured content that
  will gain fields over time, so schema evolution stays additive. Use native SQL
  `NULL` for absent jsonb — never sentinels like `'{}'::jsonb` (inherited from
  the entity skill). Shape content columns to hold structured blocks, not bare
  strings, when future content types are plausible.

## Raw Payload Isolation

Logs often carry a large verbatim blob — a raw third-party response, a full
request dump — kept as a fidelity backup for reprocessing. Isolate it:

- Put the blob in **its own column or, better, a sibling table** keyed 1:1 to
  the log row. Then it can be archived to cold storage or dropped **without
  touching the hot log rows** your common queries scan.
- This makes "shed the expensive part later" a one-line operation (drop a column
  / truncate a sibling table / drop old partitions) instead of a risky rewrite.
- Decide verbatim storage by the reconstructable test: keep the blob you
  _cannot_ rebuild; skip the one you can. Design the column nullable/separable
  so future retention can reclaim it.

## Retention and Partitioning

Logs grow unbounded — retention is part of the schema, not an afterthought.

- _Partition by time_: `PARTITION BY RANGE (created_at)` on high-volume logs.
  Pruning then becomes `DROP TABLE` on an old partition — instant, no bloat, no
  `VACUUM` storm — versus a row-by-row `DELETE`.
- _Deletion means retention, not soft-delete_: a log has no `deleted_at`.
  "Delete" is a bulk, age-based archival/prune operation, ideally at partition
  granularity. Per-row `DELETE` stays forbidden by trigger.
- _Archive before prune_: where history must survive beyond hot storage, move
  cold partitions (or the isolated raw-payload table) to cheaper storage before
  dropping. Treat deletion as a one-way door you have deliberately chosen to
  open.

## References and Foreign Keys

- _Logs FK to entities_, to anchor each event to its stream/context
  (`convo_id → convo`, `account_id → accounts`).
- _Entities generally do not FK to log rows._ Live application state should not
  depend on a specific event row; logs are written by the app and read by
  analytics/derivation. If you find an entity pointing at a log row as
  authoritative state, reconsider whether that data belongs in an entity
  instead.
- _Children of a log stream are logs too._ In a request/response pair, both are
  append-only; the response FKs to the request.

## Spec Presentation

When authoring a specification for a new log table, include a **Log
Configuration** table at the start of the `Detailed Design` block — the
unambiguous at-a-glance contract for the implementor (mirrors the entity skill's
Entity Configuration table).

### Log Configuration Template

```markdown
### Log Configuration

| Setting         | Selection                                         | Implementation Requirement                                                 |
| :-------------- | :------------------------------------------------ | :------------------------------------------------------------------------- |
| **Identity**    | `BIGINT` identity / `UUIDv7` / No id              | (e.g. `BIGINT` id, because `response` is referenced 1:1 by `response_raw`) |
| **Stream Key**  | (e.g. `convo_id`)                                 | NOT NULL FK to owning stream; indexed for replay                           |
| **Ordering**    | `created_at` (+ `id` tiebreaker) / Explicit `seq` | (e.g. `ORDER BY created_at, id`; no explicit sequence column)              |
| **Time Model**  | Ingest-only / Ingest + Event                      | (e.g. Ingest-only `created_at`; omit `occurred_at`)                        |
| **Raw Payload** | None / Inline column / Isolated sibling           | (e.g. Isolated 1:1 sibling table for archival)                             |
| **Retention**   | Indefinite / Time-partitioned                     | (e.g. `PARTITION BY RANGE (created_at)`, monthly; drop > 24 months)        |
| **Corrections** | Forbidden / Compensating-append                   | (e.g. Compensating-append referencing the corrected row)                   |
```

## Examples

### Worked Example: Conversation Transcripts

A student↔LLM transcript store. This is the
[entity-that-owns-logs](#entities-that-own-logs) case: `convo` is a **mutable
entity** (it has an editable `name`, and an `ended_at` that is unknown at the
start and set later), so it is governed by `postgres-entity-table-design`, not
this skill. The append-only logs are `request` (what was sent) and `response`
(what came back), which hang off that entity. The stateless LLM API resends the
full history each turn, so the prior history is **reconstructable** — it is
deliberately _not_ re-stored on each request (that would be the quadratic trap).
The raw provider response **is** irreplaceable, so it is kept verbatim, isolated
in a sibling table for cheap future archival.

The two log tables carry a `BIGINT` identity id **because each is referenced
individually**: `response` points at its `request`, and `response_raw` is keyed
1:1 to its `response`. `BIGINT` (not `UUIDv7`) because these ids are generated
in one Postgres instance and never exposed externally, so the smaller, monotonic
key wins. (`convo` has a `UUIDv7` id because it is an entity, per the entity
skill.) A pure telemetry log with no such cross-references (e.g. a raw page-view
event stream) would omit the id entirely and order by `created_at` alone.

```sql
-- Stream header: a MUTABLE ENTITY, not a log. Governed by
-- postgres-entity-table-design (updated_at trigger, optional versioning/OCC).
-- Shown minimally here only to anchor the FKs from the log tables below.
CREATE TABLE convo (
  id         UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),  -- mutable: name edits, ending
  student_id UUID NOT NULL REFERENCES students(id),
  name       TEXT NULL,                           -- editable by the student
  ended_at   TIMESTAMPTZ NULL,                    -- unknown at start; set later
  CONSTRAINT convo_name_len CHECK (name IS NULL OR length(name) <= 255)
);
CREATE INDEX convo_student_id_idx ON convo (student_id);

-- Log: one turn sent to the model. Stores ONLY the new user input + envelope.
-- Does NOT store the replayed history (reconstructable -> would be O(n^2)).
CREATE TABLE convo_request (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- internal-only, monotonic
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  convo_id        UUID NOT NULL REFERENCES convo(id),
  -- Provenance: pin what shaped the call. References versioned entities
  -- (a prompts table / model registry) defined elsewhere.
  model_requested TEXT NOT NULL,
  request_params  JSONB NULL,            -- temperature, max_tokens, etc.
  prompt_version  UUID NULL,             -- FK to a versioned system-prompt entity
  -- The new user input, block-capable for future image/tool content.
  content         JSONB NOT NULL,
  CONSTRAINT convo_request_model_len CHECK (length(model_requested) <= 255)
);
CREATE INDEX convo_request_stream_idx ON convo_request (convo_id, created_at);

-- Log: the model's reply, 1:1 with a request. Carries the response envelope.
CREATE TABLE convo_response (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  request_id          BIGINT NOT NULL UNIQUE REFERENCES convo_request(id),
  convo_id            UUID NOT NULL REFERENCES convo(id),  -- denormalized for stream queries
  content             JSONB NULL,        -- assistant output blocks; NULL on error
  model_resolved      TEXT NULL,         -- exact model that actually ran
  input_tokens        INTEGER NULL,
  output_tokens       INTEGER NULL,
  cached_tokens       INTEGER NULL,
  stop_reason         TEXT NULL,         -- end_turn | max_tokens | error | ...
  provider_request_id TEXT NULL,
  latency_ms          INTEGER NULL,
  CONSTRAINT convo_response_stop_len CHECK (stop_reason IS NULL OR length(stop_reason) <= 64)
);
CREATE INDEX convo_response_stream_idx ON convo_response (convo_id, created_at);

-- Isolated raw payload: irreplaceable verbatim backup, in its own table so it
-- can be archived/dropped without touching the hot response rows.
CREATE TABLE convo_response_raw (
  response_id BIGINT NOT NULL PRIMARY KEY REFERENCES convo_response(id),
  payload     JSONB NOT NULL             -- exact provider response, verbatim
);
```

> [!NOTE] A failed request with no model reply is represented as a
> `convo_response` row with `stop_reason = 'error'` and `content = NULL`,
> preserving the 1:1 relationship and recording that the attempt happened.

### Functions and Triggers

Log tables need only two guards — reject updates and reject deletes — applied to
every log table. No versioning, OCC, `updated_at`, or versions-mirror triggers
are used. These function definitions are inlined so the skill is portable across
projects.

```sql
-- Reject any UPDATE: log rows are write-once.
CREATE OR REPLACE FUNCTION prevent_log_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Log rows are append-only and cannot be updated.';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Reject any DELETE: retention is performed at the partition level or by a
-- privileged maintenance path, never as an ad-hoc row delete.
CREATE OR REPLACE FUNCTION prevent_log_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Log rows cannot be deleted; prune by partition/retention.';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Apply both guards to each LOG table: convo_request, convo_response,
-- convo_response_raw. NOT convo — it is a mutable entity, not a log.
CREATE TRIGGER prevent_convo_request_update
BEFORE UPDATE ON convo_request
FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();

CREATE TRIGGER prevent_convo_request_delete
BEFORE DELETE ON convo_request
FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
```

> [!NOTE] **Time-partitioned retention.** When a log is partitioned with
> `PARTITION BY RANGE (created_at)`, expired data is reclaimed by dropping whole
> partitions (`DROP TABLE convo_request_2026_01;`). `DROP TABLE` on a partition
> is not a row `DELETE`, so it is not blocked by `prevent_log_delete()` —
> pruning stays cheap while ad-hoc row deletion stays forbidden.
