-- user_auth_identities: the provider's id for the person and the address it
-- vouched for are typed pii.* (RFC 263, slice
-- deletion/01/the-classification). The shape is in classify-users.sql.
--
-- HARD-DELETED, as sessions (classify-sessions.sql): gate-2 decision D10
-- physically deletes the account's identities, so both columns KEEP NOT NULL,
-- and the table gains no deleted_at and no presence CHECK.

ALTER TABLE user_auth_identities
    ALTER COLUMN subject TYPE pii.text,
    ALTER COLUMN email   TYPE pii.text;
