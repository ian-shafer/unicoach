-- students + students_versions: birth date and expected graduation are typed
-- pii.* (RFC 263, slice deletion/01/the-classification). The shape is in
-- classify-users.sql.
--
-- The graduation date's CHECK is named for `graduation_on`: the full column
-- name overruns PostgreSQL's 63-byte identifier limit. Its precision column is
-- not personal (it is how precisely the date was given, not the date), and
-- grad_date_is_period_start already admits NULL: is_period_start(NULL, p) is
-- NULL or TRUE for every precision.

ALTER TABLE students
    ALTER COLUMN date_of_birth                      TYPE pii.date,
    ALTER COLUMN expected_high_school_graduation_on TYPE pii.date,
    ALTER COLUMN date_of_birth                      DROP NOT NULL,
    ALTER COLUMN expected_high_school_graduation_on DROP NOT NULL,
    ADD CONSTRAINT students_date_of_birth_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR date_of_birth IS NOT NULL),
    ADD CONSTRAINT students_graduation_on_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR expected_high_school_graduation_on IS NOT NULL);

ALTER TABLE students_versions
    ALTER COLUMN date_of_birth                      TYPE pii.date,
    ALTER COLUMN expected_high_school_graduation_on TYPE pii.date,
    ALTER COLUMN date_of_birth                      DROP NOT NULL,
    ALTER COLUMN expected_high_school_graduation_on DROP NOT NULL,
    ADD CONSTRAINT students_date_of_birth_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR date_of_birth IS NOT NULL),
    ADD CONSTRAINT students_graduation_on_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR expected_high_school_graduation_on IS NOT NULL);
