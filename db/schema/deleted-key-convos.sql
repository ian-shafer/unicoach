-- convos: every key onto an account gains its sibling onto the deleted table
-- (student_id -> deleted_student_id and supporter_id -> deleted_supporter_id).
-- RFC 264, slice deletion/02/the-deleted-tables (brief 0002 D1).
--
-- WHY. Erasure keeps this row and takes the key off the account: the live key
-- goes NULL and the sibling takes the same id, which is the deleted row's id
-- (D9). The pair CHECK reads exactly the two columns, so a row is never keyed
-- on both, and drift rule 4 (ErasureClassificationTest) finds the pair by it.
-- Nothing in this slice writes a sibling; deletion/03 is its first writer, so
-- every existing row keeps its live key and passes the CHECK as it validates.
--
-- supporter_id is already nullable: NULL on a student's own conversation
-- (convos-party.sql). Its pair is therefore AT MOST one (RFC 264 D-D).
--
-- convos_party_author_check is restated to read either supporter key, so a
-- parent's conversation is still a supporter conversation after the parent is
-- erased (RFC 264 D-D).

ALTER TABLE convos
    ADD COLUMN deleted_student_id UUID NULL REFERENCES deleted_students(id),
    ALTER COLUMN student_id DROP NOT NULL,
    ADD CONSTRAINT convos_student_id_pair_check
        CHECK (num_nonnulls(student_id, deleted_student_id) = 1);

ALTER TABLE convos
    ADD COLUMN deleted_supporter_id UUID NULL REFERENCES deleted_supporters(id),
    ADD CONSTRAINT convos_supporter_id_pair_check
        CHECK (num_nonnulls(supporter_id, deleted_supporter_id) <= 1);

ALTER TABLE convos DROP CONSTRAINT convos_party_author_check;
ALTER TABLE convos ADD CONSTRAINT convos_party_author_check
    CHECK ((party = 'supporter') = (num_nonnulls(supporter_id, deleted_supporter_id) = 1));
