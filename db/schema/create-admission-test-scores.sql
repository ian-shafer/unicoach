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
