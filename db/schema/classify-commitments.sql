-- commitments: what the coach resolved to raise, and why it was dropped, are
-- typed pii.*, and the table gains deleted_at (RFC 263, slice
-- deletion/01/the-classification). The shape is in classify-users.sql.
-- drop_reason is already nullable.

ALTER TABLE commitments
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ALTER COLUMN statement   TYPE pii.text,
    ALTER COLUMN drop_reason TYPE pii.text,
    ALTER COLUMN statement DROP NOT NULL,
    ADD CONSTRAINT commitments_statement_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR statement IS NOT NULL);
