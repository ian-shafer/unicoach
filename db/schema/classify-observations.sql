-- observations: the person's quoted words are typed pii.*, and the log gains
-- deleted_at so the row can be erased in place (RFC 263, slice
-- deletion/01/the-classification). The shape is in classify-users.sql.
--
-- Adding a NULL column with no default is a catalog change only; the existing
-- rows are live and stay so. The update guard on the log does not fire on DDL.

ALTER TABLE observations
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ALTER COLUMN quote TYPE pii.text,
    ALTER COLUMN quote DROP NOT NULL,
    ADD CONSTRAINT observations_quote_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR quote IS NOT NULL);
