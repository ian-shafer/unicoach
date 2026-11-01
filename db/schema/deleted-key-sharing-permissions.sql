-- sharing_permissions: every key onto an account gains its sibling onto the
-- deleted table (student_id -> deleted_student_id). RFC 264, slice
-- deletion/02/the-deleted-tables (brief 0002 D1).
--
-- WHY. Erasure keeps this row and takes the key off the account: the live key
-- goes NULL and the sibling takes the same id, which is the deleted row's id
-- (D9). The pair CHECK reads exactly the two columns, so a row is never keyed
-- on both, and drift rule 4 (ErasureClassificationTest) finds the pair by it.
-- Nothing in this slice writes a sibling; deletion/03 is its first writer, so
-- every existing row keeps its live key and passes the CHECK as it validates.
--
-- sharing_permissions_versions copies the key, so it gains the copy too:
-- nullable, no foreign key (a history table records what was written;
-- subscriptions-key-on-account.sql), the live copy made nullable, the same pair
-- CHECK. Existing history rows keep their live copy and a NULL sibling, which
-- the CHECK admits. log_sharing_permission_version() names its columns, so it
-- is restated from create-sharing-permissions.sql with the sibling appended to
-- both lists; nothing else in it changes.

ALTER TABLE sharing_permissions
    ADD COLUMN deleted_student_id UUID NULL REFERENCES deleted_students(id),
    ALTER COLUMN student_id DROP NOT NULL,
    ADD CONSTRAINT sharing_permissions_student_id_pair_check
        CHECK (num_nonnulls(student_id, deleted_student_id) = 1);

ALTER TABLE sharing_permissions_versions
    ADD COLUMN deleted_student_id UUID NULL,
    ALTER COLUMN student_id DROP NOT NULL,
    ADD CONSTRAINT sharing_permissions_versions_student_id_pair_check
        CHECK (num_nonnulls(student_id, deleted_student_id) = 1);

CREATE OR REPLACE FUNCTION log_sharing_permission_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO sharing_permissions_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at,
        deleted_at, student_id, permission, status,
        deleted_student_id
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at,
        NEW.row_updated_at, NEW.deleted_at, NEW.student_id, NEW.permission, NEW.status,
        NEW.deleted_student_id
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
