-- The 13+ age floor (RFC 222, slice family/08 of brief 0014): a full date of
-- birth on every student.
--
-- COPPA reaches a general-audience service only through ACTUAL KNOWLEDGE that
-- it collects personal information from a child under 13. A neutral
-- date-of-birth question at student creation keeps unicoach outside the
-- verifiable-parental-consent machinery, so the date has to be stored, once,
-- as a real DATE.
--
-- There is deliberately NO CHECK constraint here. An age test needs today's
-- date and CURRENT_DATE is not IMMUTABLE, so Postgres refuses it in a
-- constraint; and StudentsDao maps 23514 to ConstraintViolationException, which
-- the route renders as 500 -- a check could only ever produce a server error,
-- never an honest refusal. The floor is evaluated in StudentService against an
-- injected Clock, and that is the only place it lives.

-- Both tables in one migration, on the create-partial-date-storage precedent:
-- StudentsDao.mapStudent reads SELECT * from students AND students_versions, so
-- a half-migrated pair has no valid reader.
ALTER TABLE students          ADD COLUMN date_of_birth DATE NULL;
ALTER TABLE students_versions ADD COLUMN date_of_birth DATE NULL;

-- Ian's call at the RFC 222 approval gate, 2026-09-15: make the column
-- mandatory for all rows and backfill the existing ones with 10 July 2009.
-- There are no real students yet, so the sentinel is not a false fact about a
-- real person -- it is a pre-launch artefact with a finite lifetime, recorded
-- here because it will look like real data later.
--
-- The backfill must not look like an edit. students carries trigger_01
-- (versioning), trigger_03 (updated_at) and trigger_04 (history), so a plain
-- UPDATE would bump every student's version and write a history row recording a
-- change no family made.
ALTER TABLE students DISABLE TRIGGER USER;

UPDATE students SET date_of_birth = DATE '2009-07-10';

ALTER TABLE students ENABLE TRIGGER USER;

-- The history table has no triggers of its own; it is written only by
-- log_student_version().
UPDATE students_versions SET date_of_birth = DATE '2009-07-10';

ALTER TABLE students          ALTER COLUMN date_of_birth SET NOT NULL;
ALTER TABLE students_versions ALTER COLUMN date_of_birth SET NOT NULL;

-- A plpgsql body is TEXT and is NOT dependency-tracked: without this full
-- restatement the next write to students dies with 42703, naming a column the
-- function never learned about.
CREATE OR REPLACE FUNCTION log_student_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO students_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        user_id,
        expected_high_school_graduation_on,
        expected_high_school_graduation_precision,
        date_of_birth
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.user_id,
        NEW.expected_high_school_graduation_on,
        NEW.expected_high_school_graduation_precision,
        NEW.date_of_birth
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
