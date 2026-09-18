# RFC 207 — The student's own test scores

**Slice:** `test/06/your-scores` (brief 0010, `product/0010-when-do-i-test`).
**Status:** proposed (revised after the park; consumes RFC 209 and RFC 227).
**Base:** `main@bc37e1fe` (coach prompt v39).

## Summary

The coach can be told a score and forgets it the moment the conversation ends.
This RFC gives the student's own admission-test scores a home: one small decline
record, one score table, one chat tool that records, corrects, removes and
declines, and one context block composed whenever there is something to
remember, so a LATER session still has the number she gave us in this one. It
stores facts only. Nothing here predicts a score, coaches prep, gives odds,
computes a superscore, or compares a student to a school's band (D9, RFC 195,
RFC 197's correction to D14).

It is what `test/07/a-score-is-money` — "you are ten points below the next money
band" — is technically blocked on.

## Decisions this inherits, and does not re-open

- **D12 (brief 0010) — store her own scores.** Invited, declinable, never
  gating, removable.
- **Non-negotiable 5 (brief 0001 D12) — value before ask.** A decline is a
  first-class recorded state and the coach never re-raises it itself.
- **D9 — no prep, no prediction, no odds.** Standing.
- **D13 — the whole test family.** SAT and ACT are symmetric and the SAT is
  never the default noun. The table assumes no 1600 scale.
- **D21 (RFC 227) — one date seam.** The sitting date is STORED through RFC
  209's shape and SPOKEN through `PartialDate.usDate()`; this RFC adds no date
  pattern of its own.
- **D22 — no calendar pressure, no single user.** Every executed test that needs
  a concrete student carries a second student deliberately unlike her.
- **D23 (RFC 209) — one partial-date storage pattern.** `taken_on` +
  `taken_
  precision`, never three SMALLINTs.
- **D24 (RFC 209) — one declinable-answer component.** Only the refusal is
  stored; `answered` is derived.

## Detailed Design

### 1. What is stored — a decline record and a score list

Two different things, in two tables.

`admission_test_score_declines` is the refusal and nothing else: one live row
per student who has said no, carrying only its own lifecycle. `answered` is
NEVER stored (D24):
`AnswerStatus.of(declinedAt = liveRow?.createdAt, hasValue =
scores.isNotEmpty())`
derives the tri-state, so "answered beside zero rows" is unrepresentable rather
than policed. The table is deliberately NOT a versioned aggregate
(`cost_report_shares` precedent, `0073`): its only mutations are insert and
soft-delete, so no OCC version and no history table. A withdrawal is the
soft-delete; the old refusal row stays on record as history.

`admission_test_scores` holds the facts: one row per (student, component,
sitting). It IS versioned, on `college_list_entries`/`money_profiles` precedent,
because a corrected score is an edit a family may ask about later.

### 2. The component carries the scale

`test` is RFC 197's `AdmissionTest` (`sat` | `act`), reused rather than
re-spelled. The new `AdmissionTestScoreComponent` enum in `:db` carries the
identity of one score — its stored code, its owning test, the words a family
hears, and its published scale.

| component             | test | scale    | step |
| --------------------- | ---- | -------- | ---- |
| `sat_total`           | SAT  | 400–1600 | 10   |
| `sat_reading_writing` | SAT  | 200–800  | 10   |
| `sat_math`            | SAT  | 200–800  | 10   |
| `act_composite`       | ACT  | 1–36     | 1    |
| `act_english`         | ACT  | 1–36     | 1    |
| `act_math`            | ACT  | 1–36     | 1    |
| `act_reading`         | ACT  | 1–36     | 1    |
| `act_science`         | ACT  | 1–36     | 1    |

The scale is a property of the COMPONENT, never of the table, which is what
makes D13 true by construction. `PublishedScoreScale` in `:db` (RFC 195) gains
one constant, `SAT_STEP = 10`, and the ACT sections reuse `ACT_COMPOSITE`; the
SQL CHECK remains the authority and the Kotlin object is its mirror. Ten-point
steps are the PUBLISHED scale, not a plausibility judgement: `1337` is not a low
SAT total, it is not an SAT total — the same claim RFC 195 makes when it refuses
to render a stored `0` as an average.

A `ReportedScore` value class with a private constructor is the only way a score
exists above the DAO, on `AdmittedAverage`'s precedent:
`ReportedScore.of
(component, score)` returns null when the number is not on
that component's published scale and step.

### 3. The sitting date is RFC 209's shape

`taken_on DATE` at the start of the period plus
`taken_precision date_precision`, decoded to `PartialDate` and encoded by
`PartialDateColumns` — never three SMALLINTs, never a `(date, precision)` pair
escaping the DAO. A student who remembers March and not the day has told us
something true; demanding a day would make us refuse her score or invent one.

A sitting in the FUTURE is refused by `TestScoreService`, not by a CHECK (a
CHECK cannot read today). The service reads today through RFC 186's seam —
`LocalDate.ofInstant(clock.instant(), CoachClock.COACH_ZONE)`, never
`LocalDate.now(clock)` — and compares the period START: a year-precision "2026"
is accepted mid-year, because we do not know the month and refusing it would
throw away a true answer.

### 4. Superscores are not stored and not spoken

One row per sitting, nothing computed across sittings. We hold no school's
superscore policy (RFC 197's correction to D14), so a best-of combination is
neither stored nor presented as any school's rule. The existing prompt sentence
already sends the family to the school's own page.

### 5. The DDL

`db/schema/create-admission-test-scores.sql`, a kebab slug with its line placed
in `db/schema/ORDER` by `ship-order` at land (RFC 180).

```sql
-- admission_test_score_declines + admission_test_scores (RFC 207, slice
-- test/06/your-scores): the student's OWN admission-test scores, so a later
-- conversation still has the number she gave us in this one.
--
-- SELF-REPORTED, ALWAYS. Every row here is a number the student typed into a
-- chat message. We hold no feed from College Board or ACT and claim none: the
-- table name, this comment, the KDoc on the model, the wire label and the coach
-- prompt all say "the student told us this". There is deliberately no `source`
-- column, because a column with one legal value is a comment that costs a
-- CHECK; the day an official score can be loaded, that is a migration and a
-- product conversation, not a new enum member.
--
-- NO SUPERSCORE, EVER. We hold no school's superscore policy (RFC 197's
-- correction to D14), so this table stores what was scored on a day and nothing
-- computed across days. A best-of combination is not stored here and is not
-- spoken as any school's rule.
--
-- ONE ROW PER (component, sitting). `test` is RFC 197's AdmissionTest
-- ('sat', 'act'), reused; `component` is this migration's
-- AdmissionTestScoreComponent. The score's scale is a property of its
-- COMPONENT, not of the table, which is what makes brief 0010 D13 true by
-- construction: an ACT composite is as ordinary a row here as an SAT total, and
-- a 200-800 section score cannot be stored as a 1600-scale total.
--
-- THE PUBLISHED SCALE IS THE GATE (RFC 195's rule, applied to the student's
-- own number). PublishedScoreScale in :db is the one Kotlin home of these
-- bounds and this CHECK is the authority that refuses a write. The SAT is
-- published in 10-point steps, so 1337 is not a low SAT total, it is not an
-- SAT total at all. This is the PUBLISHED SCALE, not a plausibility floor: 400
-- and 1600 are both stored and both said as given.
--
-- A SITTING DATE IS VARIABLE PRECISION, on the ONE storage shape RFC 209
-- landed: a real DATE at the start of the period plus a date_precision domain,
-- decoded to PartialDate and encoded by PartialDateColumns. A student who
-- remembers the month and not the day has told us something true, and
-- demanding a day would make us refuse her score or invent one. The pair never
-- escapes the data-access layer.
--
-- NOT-IN-THE-FUTURE IS NOT A CHECK. A CHECK cannot read today, so the "you
-- cannot have sat a test that has not happened" rule lives in TestScoreService
-- against the coach clock (RFC 186's InstantSource seam) and its own test. The
-- comparison is on the period START, so a year-precision "2026" is accepted
-- mid-year -- we do not know the month, and refusing it would throw away a true
-- answer.
--
-- The REFUSAL is its own record and its own table (brief 0010 D24 /
-- AnswerStatus.of): only declined is stored, and "answered" is DERIVED from the
-- existence of a live score row -- CLAUDE.md's "derived figures are never
-- stored". A stored status beside zero rows would be a claim the database
-- cannot check, because the two facts live in different tables. The decline
-- table is deliberately NOT a versioned aggregate (cost_report_shares, 0073):
-- its only mutations are insert and setting withdrawn_at once, so no OCC
-- version and no history table.

-- ---------------------------------------------------------------------------
-- admission_test_score_declines -- the refusal, and nothing else
-- ---------------------------------------------------------------------------

CREATE TABLE admission_test_score_declines (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    student_id  UUID        NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    -- The time she said no. It is created_at rather than a second column:
    -- the row IS the refusal, on the cost_report_shares shape where the only
    -- mutation is the withdrawal below and created_at is the event time.
    withdrawn_at TIMESTAMPTZ NULL
);

-- At most one live decline per student: declining again inserts a row, and the
-- live one is the current refusal. Recording a score after a decline withdraws
-- it (sets withdrawn_at) in the same transaction, and the old refusal stays on
-- record as history without a versions table.
CREATE UNIQUE INDEX admission_test_score_declines_one_live_per_student_idx
    ON admission_test_score_declines (student_id) WHERE withdrawn_at IS NULL;

-- ---------------------------------------------------------------------------
-- admission_test_scores -- one component of one sitting, as the student said it
-- ---------------------------------------------------------------------------

CREATE TABLE admission_test_scores (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    version        INTEGER     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ NULL,

    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

    -- OUR OWN enumerations: TEXT + CHECK IN (...) with one Kotlin enum each
    -- (CLAUDE.md). `test` is RFC 197's AdmissionTest; `component` is this
    -- migration's AdmissionTestScoreComponent.
    test      TEXT    NOT NULL,
    component TEXT    NOT NULL,
    score     INTEGER NOT NULL,

    -- The sitting, at the precision the student actually remembers, on the
    -- RFC 209 storage shape.
    taken_on        DATE            NOT NULL,
    taken_precision date_precision   NOT NULL,

    CONSTRAINT admission_test_scores_test_check
        CHECK (test IN ('sat','act')),

    -- ONE constraint for two rules, because they are one rule: these are the
    -- components we hold, and each belongs to its own test. Split in two, a
    -- row could name a legal component of the other test.
    CONSTRAINT admission_test_scores_component_check
        CHECK ((test = 'sat' AND component IN ('sat_total','sat_reading_writing','sat_math'))
            OR (test = 'act' AND component IN ('act_composite','act_english','act_math','act_reading','act_science'))),

    -- The publishers' own scales, mirrored from PublishedScoreScale in :db
    -- (RFC 195) and extended by the SAT step this table is the first to store.
    -- ELSE FALSE, so a component added above and forgotten here is refused
    -- rather than admitted unbounded.
    CONSTRAINT admission_test_scores_published_scale_check
        CHECK (CASE component
                 WHEN 'sat_total'           THEN score BETWEEN 400 AND 1600 AND score % 10 = 0
                 WHEN 'sat_reading_writing' THEN score BETWEEN 200 AND 800  AND score % 10 = 0
                 WHEN 'sat_math'            THEN score BETWEEN 200 AND 800  AND score % 10 = 0
                 WHEN 'act_composite'       THEN score BETWEEN 1 AND 36
                 WHEN 'act_english'         THEN score BETWEEN 1 AND 36
                 WHEN 'act_math'            THEN score BETWEEN 1 AND 36
                 WHEN 'act_reading'         THEN score BETWEEN 1 AND 36
                 WHEN 'act_science'         THEN score BETWEEN 1 AND 36
                 ELSE FALSE
               END),

    -- The shared period-start rule (RFC 209): 2026-03-05 stored at 'year'
    -- would put a month we never recorded into the column.
    CONSTRAINT admission_test_scores_sitting_is_period_start_check
        CHECK (is_period_start(taken_on, taken_precision))
);

-- One live score per student, component and sitting. taken_precision is NOT in
-- the key: saying "March 2026" and then "March 1, 2026" for the same
-- component is one correction, not two sittings -- both store 2026-03-01, and
-- the second writes the day precision onto the first row. A more precise date
-- whose PERIOD START moves (March 14) is a different sitting. Two sittings in
-- the same year, with only the year known, collapse to one row; that is the
-- honest statement of what we hold ("the 2026 SAT total"), not a claim about
-- which Saturday.
CREATE UNIQUE INDEX admission_test_scores_student_component_sitting_active_idx
    ON admission_test_scores (student_id, component, taken_on)
    WHERE deleted_at IS NULL;

-- The only read is "this student's live scores, newest sitting first".
CREATE INDEX admission_test_scores_student_active_idx
    ON admission_test_scores (student_id, taken_on DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE admission_test_scores_versions (
  id             UUID        NOT NULL REFERENCES admission_test_scores(id) ON DELETE RESTRICT,
  version        INTEGER     NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL,
  row_created_at TIMESTAMPTZ NOT NULL,
  updated_at     TIMESTAMPTZ NOT NULL,
  row_updated_at TIMESTAMPTZ NOT NULL,
  deleted_at     TIMESTAMPTZ NULL,
  student_id     UUID        NOT NULL,
  test           TEXT        NOT NULL,
  component      TEXT        NOT NULL,
  score          INTEGER     NOT NULL,
  taken_on       DATE        NOT NULL,
  taken_precision date_precision NOT NULL,
  PRIMARY KEY (id, version)
);

CREATE INDEX admission_test_scores_versions_id_updated_at_idx
    ON admission_test_scores_versions (id, updated_at);

CREATE OR REPLACE FUNCTION log_admission_test_score_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO admission_test_scores_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        student_id, test, component, score, taken_on, taken_precision
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.student_id, NEW.test, NEW.component, NEW.score, NEW.taken_on, NEW.taken_precision
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_00_prevent_admission_test_scores_physical_delete
BEFORE DELETE ON admission_test_scores
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_delete();

CREATE TRIGGER trigger_00a_prevent_admission_test_scores_immutable_updates
BEFORE UPDATE ON admission_test_scores
FOR EACH ROW
EXECUTE PROCEDURE prevent_immutable_updates();

CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON admission_test_scores
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_timestamp_update();

CREATE TRIGGER trigger_01_enforce_admission_test_scores_versioning
BEFORE INSERT OR UPDATE ON admission_test_scores
FOR EACH ROW
EXECUTE PROCEDURE enforce_versioning();

CREATE TRIGGER trigger_03_enforce_admission_test_scores_updated_at
BEFORE UPDATE ON admission_test_scores
FOR EACH ROW
EXECUTE PROCEDURE update_timestamp();

CREATE TRIGGER trigger_04_log_admission_test_score_version
AFTER INSERT OR UPDATE ON admission_test_scores
FOR EACH ROW
EXECUTE PROCEDURE log_admission_test_score_version();
```

### 6. The write path — `update_test_scores`

A `StudentScopedChatTool`, on `MoneyProfileChatTool`'s contract: total, never
throwing, structured `{"error": ...}` for malformed input, and an echo of the
full post-write state.

Input schema (every field optional; the call carries only what the student just
said):

| field       | type    | meaning                                              |
| ----------- | ------- | ---------------------------------------------------- |
| `component` | enum    | which score, from the eight above                    |
| `score`     | integer | the number she reported                              |
| `taken_on`  | string  | the sitting, ISO `YYYY` \| `YYYY-MM` \| `YYYY-MM-DD` |
| `remove`    | `true`  | forget the score named by `component` + `taken_on`   |
| `declined`  | `true`  | she would rather not share scores at all             |

`test` is NOT an input: the component already names its test, and two fields
that can disagree is a disagreement the model can produce. It is derived and
echoed.

Exactly one intent per call. `declined` with anything else, `score` with
`remove`, a `false` decline flag, an unknown field, a score off its published
scale or step, or a sitting whose period start is in the future are each a
structured error naming the field. Recording a score after a decline is a MIND
CHANGED: the live decline row is soft-deleted in the same transaction, and
`AnswerStatus.of` now derives `ANSWERED`. The "never re-raise" rule binds the
COACH, not the student.

`remove` soft-deletes the row. Account deletion erases everything with the
student (`ON DELETE CASCADE`).

### 7. The read path — the context block

`CoachingService.composeSystem` gains one block, composed when the derived
status is `ANSWERED` or `DECLINED` (the money block's rule, and the same read
shape). A brand-new student who has never been asked composes nothing.

- ANSWERED — the block names every live score, newest sitting first, each score
  spoken through the shared seam:
  `- SAT total 1340, sat Mar 2026 (the day is not recorded)` /
  `- ACT composite 29, sat Oct 25, 2025`.
- DECLINED —
  `Test scores: declined (asked and declined — never re-ask it unless
  the student reopens the topic).`

The status comes from `AnswerStatus.of(declinedAt, hasValue)`; the sitting date
from `PartialDate.usDate()` (RFC 227); the "the day is not recorded" words from
the same caller that already speaks them for the graduation date. No new date
pattern and no new status ladder.

### 8. The coach copy

New sentences in `prompts/coach-system-prompt.md`, one sentence per line, in the
testing block beside the existing self-reported/superscore lines. Each is
sourceable from this tool or from a rule already in the prompt (RFC 187's
lesson):

- The invitation and what it buys: her scores stay on file so a later
  conversation can use them.
- Record with `update_test_scores` when she states, corrects or withdraws a
  score.
- A decline is recorded once and never raised again by the coach.
- No answer is ever gated on a stored score.
- A stored score is what the student told us, never an official score.
- Scores from different sittings are never added together and never presented as
  a school's superscore.
- The D9 line already forbids prediction, prep and odds; nothing here authorises
  comparing a stored score to a school's band as a chance of admission — naming
  a stored score beside a money band is `test/07`'s question and stays out.

`bin/prompt-seed` cuts the version at land; no seed migration is hand-written
and `systemPromptVersion` is not touched by hand (RFC 181/185).

## Files Modified

**New**

- `db/schema/create-admission-test-scores.sql` — the DDL above.
- `db/src/main/kotlin/ed/unicoach/db/models/AdmissionTestScoreComponent.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/AdmissionTestScore.kt` (+ ids,
  `NewAdmissionTestScore`, `AdmissionTestScoreDecline`)
- `db/src/main/kotlin/ed/unicoach/db/dao/AdmissionTestScoresDao.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/testing/TestScoreService.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/testing/ReportedScore.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/testing/TestScoreCopy.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/testing/UpdateTestScoresChatTool.kt`
- tests, listed under Tests below.

**Changed**

- `db/src/main/kotlin/ed/unicoach/db/models/College.kt` — `PublishedScoreScale`
  gains `SAT_STEP`.
- `db/schema/ORDER` — one line, placed by `ship-order` at land.
- `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt` — the read,
  the block, the renderer.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — tool
  registration beside `AdmissionTestTimelineChatTool`.
- `prompts/coach-system-prompt.md` — the copy above.

Not touched: `service/src/main/resources/service.conf` (phase 6 owns the pin),
`db/seed/**`, `.claude/**`, `product/**`, and no change to RFC 209's components
or `students`.

## Implementation Plan

1. Migration + `ORDER` line; `AdmissionTestScoreComponent`,
   `PublishedScoreScale.SAT_STEP`, models and ids.
2. `AdmissionTestScoresDao` on the house shape (find live by student, upsert on
   the natural key, soft delete), with the DB-backed migration and DAO tests
   green before any service code.
3. `ReportedScore` + `TestScoreService`: the scale gate, the future-sitting
   refusal, the decline/score transaction through `AnswerStatus.of`.
4. `UpdateTestScoresChatTool` + registration.
5. `CoachingService` block + renderer.
6. Prompt copy, read back for self-consistency and sourceability before review.
7. Full suite: `nix develop -c bin/test`, no module, no `-f`.

## Tests

Counts are read from the JUnit XML `tests=` attribute, never from
`grep -c "@Test"`.

**Migration (`:db`, DB-backed)**

- Each of the eight components accepts its published bounds and refuses one step
  outside them; `sat_total` refuses `1337` and accepts `1340`.
- `test`/`component` disagreement is refused (`act` + `sat_total`).
- A sitting that is not its own period start is refused (`2026-03-05` at
  `year`); year, month and day precisions are accepted.
- Two live rows for the same (student, component, sitting) are refused, and a
  month-precision re-statement with the same period start UPDATES the row.
- A write bumps `version` and lands an `admission_test_scores_versions` row.

**Service**

- A sitting whose period start is after today is refused; a sitting today is
  accepted; a year-precision "2026" is accepted mid-year. The clock is injected
  and read through `CoachClock`; no test calls `LocalDate.now(clock)`.
- Record → `AnswerStatus.of` derives `ANSWERED`; decline → `DECLINED` with no
  score rows; record after a decline soft-deletes the live decline row and
  derives `ANSWERED`.
- Remove the only score → `UNANSWERED`, and the removed row stays in history.
- A corrected score updates the sitting's row rather than adding a second.

**Tool**

- `score` + `remove`, `declined` + `score`, `declined: false`, an unknown field,
  an off-scale score and a malformed `taken_on` each return a structured error
  naming the field, and write nothing.
- A successful call echoes every live score and the derived status.

**Prompt / context block**

- The block renders for a declined student with NO scores.
- The block is absent for a student who has never been asked, and the rest of
  the system text is byte-identical to today's.
- D22's unlike-her cases: a senior with two sittings, a student past their
  expected graduation date, a student with an empty college list, and a student
  with no residency state each compose a correct block.
- The prompt's new sentences are asserted from named copy constants, and a D9
  guard asserts no new sentence predicts a score, coaches prep, gives odds, or
  compares a stored score to a school's band.
- The date-seam guard (RFC 227) still passes: this RFC renders dates only
  through `PartialDate.usDate()`, so no new hand-rolled pattern exists for it to
  catch.

## Contradictions against the spec, accepted

All four were put to Ian at the gate and accepted; they are recorded here rather
than only in the run scratch.

1. **The details screen is not "later".** The slice text names
   `profile/02/your-details-screen` (brief 0007) as the natural explicit surface
   "later". That screen already landed (RFC 171); scores stay chat-only in this
   slice, and the parity gap belongs to brief 0007.
2. **The money-profile pattern is not "exactly" reproducible.** A list of scores
   cannot carry the money profile's value-iff-answered CHECK in one row. RFC 209
   decides the same point: only the refusal is stored, and `answered` is derived
   from the rows that exist (D24), so the gap is closed rather than worked
   around.
3. **The renderer cannot be the money one.** RFC 209 already extracted
   `renderDeclinableAnswer` to take an answer rather than a `MoneyProfile`; the
   scores block is a LIST, so it renders its own header and does not abuse the
   string renderer.
4. **The spec's own preamble numbers are stale by design** (next free RFC 186,
   prompt v27). The real numbers for this run are RFC 207 and coach prompt v39.

## Open questions for the approval gate

Carried in the gate message: only the revised table shape, because every product
decision was already approved in principle and the two overruled points are now
RFC 209's components.
