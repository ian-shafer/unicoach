-- email_sends: every key onto an account gains its sibling onto the deleted
-- table (user_id -> deleted_user_id). RFC 264, slice
-- deletion/02/the-deleted-tables (brief 0002 D1).
--
-- WHY. Erasure keeps this row and takes the key off the account: the live key
-- goes NULL and the sibling takes the same id, which is the deleted row's id
-- (D9). The pair CHECK reads exactly the two columns, so a row is never keyed
-- on both, and drift rule 4 (ErasureClassificationTest) finds the pair by it.
-- Nothing in this slice writes a sibling; deletion/03 is its first writer, so
-- every existing row keeps its live key and passes the CHECK as it validates.
--
-- user_id is already nullable: NULL is a send no account is known to own
-- (classify-email-sends.sql). Its pair is therefore AT MOST one (RFC 264 D-D):
-- a send may have no account, but never both keys.

ALTER TABLE email_sends
    ADD COLUMN deleted_user_id UUID NULL REFERENCES deleted_users(id),
    ADD CONSTRAINT email_sends_user_id_pair_check
        CHECK (num_nonnulls(user_id, deleted_user_id) <= 1);
