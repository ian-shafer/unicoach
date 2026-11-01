-- supporter_link_requests: every key onto an account gains its sibling onto the
-- deleted table (student_id -> deleted_student_id and supporter_id ->
-- deleted_supporter_id). RFC 264, slice deletion/02/the-deleted-tables (brief
-- 0002 D1).
--
-- WHY. Erasure keeps this row and takes the key off the account: the live key
-- goes NULL and the sibling takes the same id, which is the deleted row's id
-- (D9). The pair CHECK reads exactly the two columns, so a row is never keyed
-- on both, and drift rule 4 (ErasureClassificationTest) finds the pair by it.
-- Nothing in this slice writes a sibling; deletion/03 is its first writer, so
-- every existing row keeps its live key and passes the CHECK as it validates.

ALTER TABLE supporter_link_requests
    ADD COLUMN deleted_student_id UUID NULL REFERENCES deleted_students(id),
    ALTER COLUMN student_id DROP NOT NULL,
    ADD CONSTRAINT supporter_link_requests_student_id_pair_check
        CHECK (num_nonnulls(student_id, deleted_student_id) = 1);

ALTER TABLE supporter_link_requests
    ADD COLUMN deleted_supporter_id UUID NULL REFERENCES deleted_supporters(id),
    ALTER COLUMN supporter_id DROP NOT NULL,
    ADD CONSTRAINT supporter_link_requests_supporter_id_pair_check
        CHECK (num_nonnulls(supporter_id, deleted_supporter_id) = 1);
