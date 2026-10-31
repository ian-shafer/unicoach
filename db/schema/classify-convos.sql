-- convos: a conversation's name, set from its first turn, is typed pii.*
-- (RFC 263, slice deletion/01/the-classification). The shape is in
-- classify-users.sql. convos has no _versions table.

ALTER TABLE convos
    ALTER COLUMN name TYPE pii.text,
    ALTER COLUMN name DROP NOT NULL,
    ADD CONSTRAINT convos_name_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR name IS NOT NULL);
