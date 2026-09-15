-- Intended subjects: what a student wants to study, as one of OUR subjects
-- (db/data/subjects.json -> the `subjects` table), never as a raw federal code.
-- RFC 205.
--
-- Two tables, because there are two things:
--   intended_subject_status — one row per student: HOW they answered the
--                             question (unanswered/answered/declined/undecided).
--   intended_subjects       — many rows per student, capped at 3: the unordered
--                             SET of subjects they named.
--
-- Both follow the college_list_entries composition (0024) exactly: uuidv7
-- primary key, version, the four timestamps, deleted_at soft delete, a
-- *_versions history table, and the shared triggers by name. No shared function
-- is added or redefined; the four functions this migration defines in place are
-- its own, exactly as 0024 defines its own.

-- ---------------------------------------------------------------------------
-- subjects.status — deprecation, not deletion
--
-- A subject the taxonomy file no longer carries is DEPRECATED once a student
-- references it, and stays resolvable for that student. A deprecated subject is
-- offered to nobody and matches nothing.
--
-- A status column, not `retired_at` and not a boolean: a timestamp would claim
-- an event history this table cannot honestly keep (a subject can be dropped
-- from the file and later restored), and both a timestamp and a boolean are
-- two-valued. A status leaves room for a third state without a migration.
-- Spelled the way `college_list_entries.status` is spelled.
-- ---------------------------------------------------------------------------

ALTER TABLE subjects ADD COLUMN status TEXT NOT NULL DEFAULT 'active';
ALTER TABLE subjects ADD CONSTRAINT subjects_status_check
    CHECK (status IN ('active','deprecated'));

-- ---------------------------------------------------------------------------
-- intended_subject_status — the ANSWER: how this student answered
-- "what do you want to study?".
-- ---------------------------------------------------------------------------

CREATE TABLE intended_subject_status (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    version        INTEGER     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ NULL,

    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

    -- Four states, not three: "I don't know yet" is a real, common and useful
    -- answer at 16, and it must not read as silence. `status` beside
    -- `college_list_entries.status`, not `intended_subject_status_status`.
    --
    -- A single NAMED constraint, so retyping this column onto the four-value
    -- answer domain test/06.1 will introduce is a drop-constraint plus an
    -- alter-type, with no data change.
    status TEXT NOT NULL DEFAULT 'unanswered',

    CONSTRAINT intended_subject_status_status_check
        CHECK (status IN ('unanswered','answered','declined','undecided'))
);

CREATE UNIQUE INDEX intended_subject_status_student_active_idx
    ON intended_subject_status (student_id) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- intended_subjects — the VALUES: an unordered set, at most 3, no ranking.
-- ---------------------------------------------------------------------------

CREATE TABLE intended_subjects (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    version        INTEGER     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ NULL,

    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

    -- Our taxonomy's own key. RESTRICT, like every other reference foreign key
    -- in this schema: a student's answer is never removed as a side effect of
    -- reloading reference data.
    subject_slug slug NOT NULL REFERENCES subjects(slug) ON DELETE RESTRICT
);

-- One ACTIVE row per (student, subject). Removing and re-adding later is a NEW
-- row with its own history, so a prior removal's history is never resurrected
-- into it — college_list_entries_student_college_active_idx verbatim.
CREATE UNIQUE INDEX intended_subjects_student_subject_active_idx
    ON intended_subjects (student_id, subject_slug) WHERE deleted_at IS NULL;
CREATE INDEX intended_subjects_student_idx
    ON intended_subjects (student_id) WHERE deleted_at IS NULL;
-- The lookup the deprecation decision needs: who depends on this slug?
--
-- Deliberately NOT partial on `deleted_at IS NULL`, unlike the two indexes
-- above. The foreign key onto `subjects(slug)` is RESTRICT and it does not care
-- about `deleted_at`: a student who added a subject and later REMOVED it still
-- holds the reference, and `DELETE FROM subjects` for that slug still fails with
--   ERROR: update or delete on table "subjects" violates RESTRICT setting of
--          foreign key constraint "intended_subjects_subject_slug_fkey"
-- So the deprecation decision must count over ALL rows, live and soft-deleted,
-- and a partial index cannot answer the question it exists for.
CREATE INDEX intended_subjects_subject_idx
    ON intended_subjects (subject_slug);

-- ---------------------------------------------------------------------------
-- History: the full college_list_entries pattern, copied verbatim (0024)
--
-- A student's subjects are kept, not overwritten. "What was she planning in
-- March" must be answerable, so removal is a soft delete and every committed
-- state is snapshotted — INCLUDING `deleted_at`, so the history records the
-- removal itself and not merely the last row before it.
-- ---------------------------------------------------------------------------

CREATE TABLE intended_subjects_versions (
    id             UUID        NOT NULL REFERENCES intended_subjects(id) ON DELETE RESTRICT,
    version        INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    row_created_at TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    row_updated_at TIMESTAMPTZ NOT NULL,
    deleted_at     TIMESTAMPTZ NULL,
    student_id     UUID        NOT NULL,
    subject_slug   slug        NOT NULL,
    PRIMARY KEY (id, version)
);

CREATE INDEX intended_subjects_versions_id_updated_at_idx
    ON intended_subjects_versions (id, updated_at);

CREATE TABLE intended_subject_status_versions (
    id             UUID        NOT NULL REFERENCES intended_subject_status(id) ON DELETE RESTRICT,
    version        INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    row_created_at TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    row_updated_at TIMESTAMPTZ NOT NULL,
    deleted_at     TIMESTAMPTZ NULL,
    student_id     UUID        NOT NULL,
    status         TEXT        NOT NULL,
    PRIMARY KEY (id, version)
);

CREATE INDEX intended_subject_status_versions_id_updated_at_idx
    ON intended_subject_status_versions (id, updated_at);

-- ---------------------------------------------------------------------------
-- This migration's own guards. `prevent_immutable_updates()` is SHARED and, as
-- redefined by 0023, guards `id` and `created_at` ONLY — it cannot be given
-- column names. Freezing a business column therefore needs this migration's own
-- function, defined in place exactly as 0023 and 0024 define theirs.
--
-- `student_id` and `subject_slug` never change after insert: if a row could be
-- updated from one subject to another, "she was planning nursing in March"
-- would be answered by a row that is now a biology row. A student swaps a
-- subject by removing one row and inserting another.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION prevent_intended_subject_immutable_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.student_id IS DISTINCT FROM OLD.student_id THEN
        RAISE EXCEPTION 'The student_id field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.subject_slug IS DISTINCT FROM OLD.subject_slug THEN
        RAISE EXCEPTION 'The subject_slug field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- The status table freezes `student_id` alone — its `status` column is exactly
-- what is meant to change.
CREATE OR REPLACE FUNCTION prevent_intended_subject_status_immutable_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.student_id IS DISTINCT FROM OLD.student_id THEN
        RAISE EXCEPTION 'The student_id field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION log_intended_subject_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO intended_subjects_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at,
        deleted_at, student_id, subject_slug
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at,
        NEW.row_updated_at, NEW.deleted_at, NEW.student_id, NEW.subject_slug
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION log_intended_subject_status_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO intended_subject_status_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at,
        deleted_at, student_id, status
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at,
        NEW.row_updated_at, NEW.deleted_at, NEW.student_id, NEW.status
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- Triggers
--
-- BEFORE triggers fire in trigger-name order, 0024's order verbatim: 00
-- delete-guard, 00a shared immutable-guard, 00b row_created_at-guard, 01
-- versioning, 03 updated_at; then 04 AFTER INSERT OR UPDATE snapshot. 00c is
-- this table's extra column guard, after the shared one and before versioning.
-- ---------------------------------------------------------------------------

CREATE TRIGGER trigger_00_prevent_intended_subjects_physical_delete
BEFORE DELETE ON intended_subjects
FOR EACH ROW EXECUTE PROCEDURE prevent_physical_delete();

CREATE TRIGGER trigger_00a_prevent_intended_subjects_immutable_updates
BEFORE UPDATE ON intended_subjects
FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_updates();

CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON intended_subjects
FOR EACH ROW EXECUTE PROCEDURE prevent_physical_timestamp_update();

CREATE TRIGGER trigger_00c_prevent_intended_subjects_column_updates
BEFORE UPDATE ON intended_subjects
FOR EACH ROW EXECUTE PROCEDURE prevent_intended_subject_immutable_updates();

CREATE TRIGGER trigger_01_enforce_intended_subjects_versioning
BEFORE INSERT OR UPDATE ON intended_subjects
FOR EACH ROW EXECUTE PROCEDURE enforce_versioning();

CREATE TRIGGER trigger_03_enforce_intended_subjects_updated_at
BEFORE UPDATE ON intended_subjects
FOR EACH ROW EXECUTE PROCEDURE update_timestamp();

CREATE TRIGGER trigger_04_log_intended_subject_version
AFTER INSERT OR UPDATE ON intended_subjects
FOR EACH ROW EXECUTE PROCEDURE log_intended_subject_version();

CREATE TRIGGER trigger_00_prevent_intended_subject_status_physical_delete
BEFORE DELETE ON intended_subject_status
FOR EACH ROW EXECUTE PROCEDURE prevent_physical_delete();

CREATE TRIGGER trigger_00a_prevent_intended_subject_status_immutable_updates
BEFORE UPDATE ON intended_subject_status
FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_updates();

CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON intended_subject_status
FOR EACH ROW EXECUTE PROCEDURE prevent_physical_timestamp_update();

CREATE TRIGGER trigger_00c_prevent_intended_subject_status_column_updates
BEFORE UPDATE ON intended_subject_status
FOR EACH ROW EXECUTE PROCEDURE prevent_intended_subject_status_immutable_updates();

CREATE TRIGGER trigger_01_enforce_intended_subject_status_versioning
BEFORE INSERT OR UPDATE ON intended_subject_status
FOR EACH ROW EXECUTE PROCEDURE enforce_versioning();

CREATE TRIGGER trigger_03_enforce_intended_subject_status_updated_at
BEFORE UPDATE ON intended_subject_status
FOR EACH ROW EXECUTE PROCEDURE update_timestamp();

CREATE TRIGGER trigger_04_log_intended_subject_status_version
AFTER INSERT OR UPDATE ON intended_subject_status
FOR EACH ROW EXECUTE PROCEDURE log_intended_subject_status_version();

-- ---------------------------------------------------------------------------
-- Two invariants the CHECKs cannot express, judged at COMMIT.
--
-- Deferred is not a convenience, it is required: mid-transaction the two tables
-- are legitimately inconsistent while a student swaps one subject for another,
-- and a CHECK cannot contain a subquery in any case.
--
--   cap              — a student has at most 3 active intended_subjects rows.
--   status iff rows  — a student has at least one active row EXACTLY when
--                      status = 'answered'. The cross-table translation of
--                      money_profiles' *_value_iff_answered_check.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION enforce_intended_subjects_cap()
RETURNS TRIGGER AS $$
DECLARE
    -- The cap, written ONCE. It was a raw 3 in the test AND re-typed into the
    -- message; the two could drift apart and the message would then lie about
    -- the rule that just refused the write.
    max_subjects CONSTANT INTEGER := 3;
    subject_count INTEGER;
    affected_student UUID;
BEGIN
    affected_student := COALESCE(NEW.student_id, OLD.student_id);
    SELECT count(*) INTO subject_count
      FROM intended_subjects
     WHERE student_id = affected_student AND deleted_at IS NULL;
    IF subject_count > max_subjects THEN
        -- The student is NAMED. This trigger is DEFERRED, so it fires at COMMIT
        -- with no row and no statement context: the message text is the whole
        -- payload the operator gets, and 'found 4' with no student in it names
        -- nothing they can go and look at.
        RAISE EXCEPTION 'Student [%] may have at most % intended subjects; found [%].',
            affected_student, max_subjects, subject_count
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION enforce_intended_subject_status_agreement()
RETURNS TRIGGER AS $$
DECLARE
    affected_student UUID;
    answer_status TEXT;
    subject_count INTEGER;
BEGIN
    affected_student := COALESCE(NEW.student_id, OLD.student_id);
    SELECT status INTO answer_status
      FROM intended_subject_status
     WHERE student_id = affected_student AND deleted_at IS NULL;
    SELECT count(*) INTO subject_count
      FROM intended_subjects
     WHERE student_id = affected_student AND deleted_at IS NULL;

    -- No status row at all is the state of a student who was never asked; the
    -- subjects table must be empty for them too.
    IF answer_status IS NULL THEN
        answer_status := 'unanswered';
    END IF;

    IF answer_status = 'answered' AND subject_count = 0 THEN
        -- Named for the same reason as the cap above: deferred, so this text is
        -- all the operator gets.
        RAISE EXCEPTION 'An answered intended-subject status requires at least one subject; student [%].',
            affected_student
            USING ERRCODE = 'P0001';
    END IF;
    IF answer_status <> 'answered' AND subject_count > 0 THEN
        RAISE EXCEPTION 'Intended subjects are only held by an answered status; student [%] has status [%].',
            affected_student, answer_status
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trigger_05_enforce_intended_subjects_cap
AFTER INSERT OR UPDATE ON intended_subjects
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE PROCEDURE enforce_intended_subjects_cap();

CREATE CONSTRAINT TRIGGER trigger_06_enforce_intended_subjects_status_agreement
AFTER INSERT OR UPDATE ON intended_subjects
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE PROCEDURE enforce_intended_subject_status_agreement();

CREATE CONSTRAINT TRIGGER trigger_06_enforce_intended_subject_status_agreement
AFTER INSERT OR UPDATE ON intended_subject_status
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE PROCEDURE enforce_intended_subject_status_agreement();
