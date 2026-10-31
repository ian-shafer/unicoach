-- claims: what the coach concluded about the person is typed pii.*, and the
-- table gains deleted_at (RFC 263, slice deletion/01/the-classification). The
-- shape is in classify-users.sql.

ALTER TABLE claims
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ALTER COLUMN statement TYPE pii.text,
    ALTER COLUMN statement DROP NOT NULL,
    ADD CONSTRAINT claims_statement_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR statement IS NOT NULL);
