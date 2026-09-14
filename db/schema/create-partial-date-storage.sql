-- One storage shape for a variable-precision date (RFC 209, brief 0010 D23).
--
-- Three hand-rolled copies of this shape exist in Kotlin-shared / SQL-copied
-- form: students.expected_high_school_graduation_{year,month,day} (0005),
-- CdsMonthDay, and the fourth that test/06 was about to add. The Kotlin half
-- was always shared -- PartialDate, a sealed interface whose own KDoc names
-- test dates as its intended reuse -- and only the storage half was copied.
--
-- The shape is a real DATE normalised to the START of the period, plus the
-- precision it was recorded at. It is the only option a database can USE: "not
-- in the future", ordering, a plain B-tree index and a range join against
-- admission_test_administrations.test_date are each one predicate with no
-- reassembly. Three SMALLINTs plus a shared validity function would have been
-- cheaper to migrate and would have left a date nothing can order; a composite
-- type returns over JDBC as the wire string `(2026,3,)`, which is a hand-rolled
-- format introduced to remove a hand-rolled format.
--
-- The pair NEVER escapes the data-access layer (D23, Ian: "we must use a custom
-- type in kotlin, never a data clump of the data and the precision"). It is
-- decoded to PartialDate on read and encoded on write by PartialDateColumns, so
-- nothing above the DAO can hold a date whose value and precision disagree.
-- That is what disarms this shape's one danger -- a stored 2026-01-01 LOOKS
-- precise -- because the only thing above storage is a YearOnly with no month
-- to leak.

-- The vocabulary, declared ONCE, on the 0054 cds_factor_rating precedent: a
-- future table names the domain instead of retyping the three words, and cannot
-- spell them differently.
CREATE DOMAIN date_precision AS TEXT
    CONSTRAINT date_precision_check
    CHECK (VALUE IN ('year', 'month', 'day'));

-- The one rule that makes the pair unambiguous: a date recorded to the year is
-- stored as 1 January, a date recorded to the month as the 1st. Without it
-- 2026-03-05 could be stored at 'year' precision, and the month we never
-- recorded would be sitting in the column.
CREATE FUNCTION is_period_start(d DATE, p date_precision)
RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS
$$ SELECT CASE p
            WHEN 'year'  THEN d = make_date(EXTRACT(YEAR FROM d)::int, 1, 1)
            WHEN 'month' THEN EXTRACT(DAY FROM d) = 1
            ELSE TRUE
          END $$;

-- ---------------------------------------------------------------------------
-- students + students_versions
-- ---------------------------------------------------------------------------
-- Both tables, in one migration and not two: StudentsDao.mapStudent maps rows
-- from BOTH (it reads SELECT *), so a half-migrated pair has no valid reader.

ALTER TABLE students
    ADD COLUMN expected_high_school_graduation_on        DATE           NULL,
    ADD COLUMN expected_high_school_graduation_precision date_precision NULL;

ALTER TABLE students_versions
    ADD COLUMN expected_high_school_graduation_on        DATE           NULL,
    ADD COLUMN expected_high_school_graduation_precision date_precision NULL;

-- The backfill must not look like an edit. students carries trigger_01
-- (versioning), trigger_03 (updated_at) and trigger_04 (history), so a plain
-- UPDATE would bump every student's version and write a history row recording a
-- change the family never made.
ALTER TABLE students DISABLE TRIGGER USER;

UPDATE students SET
    expected_high_school_graduation_on = make_date(
        expected_high_school_graduation_year::int,
        COALESCE(expected_high_school_graduation_month, 1)::int,
        COALESCE(expected_high_school_graduation_day,   1)::int),
    expected_high_school_graduation_precision =
        CASE WHEN expected_high_school_graduation_day   IS NOT NULL THEN 'day'
             WHEN expected_high_school_graduation_month IS NOT NULL THEN 'month'
             ELSE 'year' END;

ALTER TABLE students ENABLE TRIGGER USER;

-- The history table has no triggers of its own; it is written only by
-- log_student_version().
UPDATE students_versions SET
    expected_high_school_graduation_on = make_date(
        expected_high_school_graduation_year::int,
        COALESCE(expected_high_school_graduation_month, 1)::int,
        COALESCE(expected_high_school_graduation_day,   1)::int),
    expected_high_school_graduation_precision =
        CASE WHEN expected_high_school_graduation_day   IS NOT NULL THEN 'day'
             WHEN expected_high_school_graduation_month IS NOT NULL THEN 'month'
             ELSE 'year' END;

-- Dropping the three columns drops grad_month_range, grad_day_requires_month
-- and grad_date_valid with them: each names only those columns.
ALTER TABLE students
    ALTER COLUMN expected_high_school_graduation_on        SET NOT NULL,
    ALTER COLUMN expected_high_school_graduation_precision SET NOT NULL,
    ADD CONSTRAINT grad_date_is_period_start
        CHECK (is_period_start(expected_high_school_graduation_on,
                               expected_high_school_graduation_precision)),
    DROP COLUMN expected_high_school_graduation_year,
    DROP COLUMN expected_high_school_graduation_month,
    DROP COLUMN expected_high_school_graduation_day;

-- No period-start CHECK on the history table, following money_profiles_versions
-- (0046): a history row mirrors whatever was live and is never validated
-- independently. The date_precision domain still holds there, so the vocabulary
-- cannot be spelled differently in the history either.
ALTER TABLE students_versions
    ALTER COLUMN expected_high_school_graduation_on        SET NOT NULL,
    ALTER COLUMN expected_high_school_graduation_precision SET NOT NULL,
    DROP COLUMN expected_high_school_graduation_year,
    DROP COLUMN expected_high_school_graduation_month,
    DROP COLUMN expected_high_school_graduation_day;

-- A plpgsql body is TEXT and is NOT dependency-tracked (the trap 0094:66-68
-- records): without this restatement the next write to students dies with
-- 42703, naming a column that no longer exists.
CREATE OR REPLACE FUNCTION log_student_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO students_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        user_id,
        expected_high_school_graduation_on,
        expected_high_school_graduation_precision
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.user_id,
        NEW.expected_high_school_graduation_on,
        NEW.expected_high_school_graduation_precision
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
