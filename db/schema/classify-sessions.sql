-- sessions: the device and network a person signed in from are typed pii.*
-- (RFC 263, slice deletion/01/the-classification). The shape is in
-- classify-users.sql.
--
-- HARD-DELETED, so the shape stops at step 1. Gate-2 decision D10 physically
-- deletes an erased account's sessions; a session row is never erased in place.
-- So these columns keep their nullability as it is, the table gains no
-- deleted_at and no presence CHECK, and the catalog says which way the table
-- goes: a pii.* column and no deleted_at means deleted, not erased (Ian,
-- RFC 263 approval).

ALTER TABLE sessions
    ALTER COLUMN user_agent TYPE pii.text,
    ALTER COLUMN initial_ip TYPE pii.text,
    ALTER COLUMN metadata   TYPE pii.jsonb;
