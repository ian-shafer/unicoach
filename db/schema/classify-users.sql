-- users + users_versions: the account's personal columns are typed pii.*
-- (RFC 263, slice deletion/01/the-classification, brief 0002 D7/D8).
--
-- This is the first of the per-family classification migrations. Each one does
-- the same five things to one table family, live table and _versions together
-- (a history row is never typed differently from the row it copies -- the
-- create-partial-date-storage.sql precedent):
--
--   1. retype every personal column to its pii.* domain;
--   2. drop NOT NULL from each personal column that had it;
--   3. replace it with one CHECK per column, so a LIVE row still cannot hold a
--      NULL there (standing D7). Erasure (deletion/03) sets deleted_at and then
--      clears the values; the CHECK is one-way and never forces a NULL;
--   4. add deleted_at, on a table erased in place that lacked it;
--   5. give an existing CHECK that would refuse the erased state one arm,
--      `(deleted_at IS NOT NULL AND <column> IS NULL) OR <rule as today>`,
--      keeping the constraint's name.
--
-- A _versions table's CHECK carries the LIVE table's name: it is the same rule,
-- constraint names are per table, and `<table>_versions_<column>_...` overruns
-- PostgreSQL's 63-byte identifier limit on several families.
--
-- The format CHECKs on email and name (trim, lowercase, not empty, length) stay
-- as they are. Each already admits NULL, and each keeps refusing '' on a live
-- row: no empty string or placeholder is ever the erased value.
--
-- Retyping a column to a domain rewrites the table under ACCESS EXCLUSIVE,
-- inside this migration's transaction. No trigger fires on a rewrite.
--
-- trim_users_strings() is NULL-safe as it stands: LOWER(trim(NULL)) is NULL.

ALTER TABLE users
    ALTER COLUMN email         TYPE pii.text,
    ALTER COLUMN name          TYPE pii.text,
    ALTER COLUMN display_name  TYPE pii.text,
    ALTER COLUMN password_hash TYPE pii.text,
    ALTER COLUMN email DROP NOT NULL,
    ALTER COLUMN name  DROP NOT NULL,
    ADD CONSTRAINT users_email_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR email IS NOT NULL),
    ADD CONSTRAINT users_name_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR name IS NOT NULL);

ALTER TABLE users_versions
    ALTER COLUMN email         TYPE pii.text,
    ALTER COLUMN name          TYPE pii.text,
    ALTER COLUMN display_name  TYPE pii.text,
    ALTER COLUMN password_hash TYPE pii.text,
    ALTER COLUMN email DROP NOT NULL,
    ALTER COLUMN name  DROP NOT NULL,
    ADD CONSTRAINT users_email_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR email IS NOT NULL),
    ADD CONSTRAINT users_name_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR name IS NOT NULL);
