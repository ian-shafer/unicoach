-- students: every key onto an account gains its sibling onto the deleted table
-- (user_id -> deleted_user_id). RFC 264, slice deletion/02/the-deleted-tables
-- (brief 0002 D1).
--
-- WHY. Erasure keeps this row and takes the key off the account: the live key
-- goes NULL and the sibling takes the same id, which is the deleted row's id
-- (D9). The pair CHECK reads exactly the two columns, so a row is never keyed
-- on both, and drift rule 4 (ErasureClassificationTest) finds the pair by it.
-- Nothing in this slice writes a sibling; deletion/03 is its first writer, so
-- every existing row keeps its live key and passes the CHECK as it validates.
--
-- students_versions copies the key, so it gains the copy too: nullable, no
-- foreign key (a history table records what was written;
-- subscriptions-key-on-account.sql), the live copy made nullable, the same pair
-- CHECK. Existing history rows keep their live copy and a NULL sibling, which
-- the CHECK admits. log_student_version() names its columns, so it is restated
-- from add-students-date-of-birth.sql with the sibling appended to both lists;
-- nothing else in it changes.

ALTER TABLE students
    ADD COLUMN deleted_user_id UUID NULL REFERENCES deleted_users(id),
    ALTER COLUMN user_id DROP NOT NULL,
    ADD CONSTRAINT students_user_id_pair_check
        CHECK (num_nonnulls(user_id, deleted_user_id) = 1);

ALTER TABLE students_versions
    ADD COLUMN deleted_user_id UUID NULL,
    ALTER COLUMN user_id DROP NOT NULL,
    ADD CONSTRAINT students_versions_user_id_pair_check
        CHECK (num_nonnulls(user_id, deleted_user_id) = 1);

CREATE OR REPLACE FUNCTION log_student_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO students_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        user_id,
        expected_high_school_graduation_on,
        expected_high_school_graduation_precision,
        date_of_birth,
        deleted_user_id
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.user_id,
        NEW.expected_high_school_graduation_on,
        NEW.expected_high_school_graduation_precision,
        NEW.date_of_birth,
        NEW.deleted_user_id
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
